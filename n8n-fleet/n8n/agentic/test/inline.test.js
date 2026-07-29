'use strict';
/**
 * `inline` — turning a module on disk into the text an n8n Code node runs.
 *
 * n8n Code nodes cannot require anything, so the generator pastes the source in. Two things in an
 * ordinary CommonJS module are fatal there: `module.exports` (there is no module object, so the node
 * throws on its very first item) and the export block's test-only tail. Both live after the marker,
 * which is why the cut is the whole point of `sourceOf` — and why every assertion below is on the
 * TEXT produced, not on whether a call succeeded. A body that is merely non-empty still explodes in
 * production; only the exact text tells you it will not.
 *
 * The fixtures are shaped like the modules the generator really inlines: a 'use strict' directive, a
 * function, then the marker and the export block.
 */
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { EXPORTS_MARKER, sourceOf, nodeBody } = require('../src/lib/inline');

// Spelled out rather than imported so that changing the constant alone fails here: the 15 modules on
// disk carry this exact line, and a constant that drifts from them strips nothing.
const MARKER = '/* ---- test exports (stripped when inlined into n8n) ---- */';

/** A throwaway directory of modules; `write` returns the path to hand to sourceOf/nodeBody. */
function modules(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'inline-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return (name, src) => {
    const p = path.join(dir, name);
    fs.writeFileSync(p, src);
    return p;
  };
}

/** The shape of every inlined module: directive, body, marker, export block. */
const mod = (body, names) => `'use strict';\n${body}\n\n${MARKER}\nmodule.exports = { ${names} };\n`;

test('the marker is the line the modules on disk actually carry', () => {
  assert.equal(EXPORTS_MARKER, MARKER);
  for (const f of ['nodes/verdict.js', 'nodes/parse-markers.js', 'lib/checker-map.js']) {
    const src = fs.readFileSync(path.join(__dirname, '..', 'src', f), 'utf8');
    assert.ok(src.includes(MARKER), `${f} must carry the marker or the generator refuses to inline it`);
  }
});

test('everything from the marker onward is dropped, directive and all', (t) => {
  const write = modules(t);
  const f = write('verdict.js', mod('function verdict(j) {\n  return j.ok;\n}', 'verdict'));
  // Exact text, not `includes`: a leftover `module.exports` or a trailing blank run is invisible to a
  // containment check and fatal in the node.
  assert.equal(sourceOf(f), 'function verdict(j) {\n  return j.ok;\n}');
});

test('a module with no marker names itself and the line it is missing', (t) => {
  const write = modules(t);
  const f = write('parse-fix.js', "'use strict';\nfunction parseFix() {}\nmodule.exports = { parseFix };\n");
  assert.throws(() => sourceOf(f), (e) => {
    assert.match(e.message, /^parse-fix\.js has no EXPORTS_MARKER/,
      'the generator inlines fifteen modules — the message has to say which one to open');
    assert.ok(e.message.includes(`${MARKER}\nmodule.exports`),
      'the message carries the fix verbatim, so the author can paste it');
    return true;
  });
});

test('the FIRST marker cuts, so nothing below it can smuggle exports into the node', (t) => {
  const write = modules(t);
  // An author who leaves a second export block behind (a half-finished split, a stale copy) still
  // gets a clean body; cutting at the last marker would paste the first `module.exports` into n8n.
  const f = write('parse-test.js', mod('function parseTest() {}', 'parseTest')
    + `\n${MARKER}\nmodule.exports = { parseTest, __private };\n`);
  const src = sourceOf(f);
  assert.equal(src, 'function parseTest() {}');
  assert.ok(!src.includes('module.exports'), 'n8n has no module object — this throws on the first item');
});

test("'use strict' is dropped: n8n's wrapper supplies it and a stray directive is dead weight",
  async (t) => {
    const write = modules(t);
    const tail = (names) => `\n\n${MARKER}\nmodule.exports = { ${names} };\n`;

    await t.test('the directive goes, and the same text inside a string stays', () => {
      const f = write('prep-prover.js', mod("const q = \"'use strict';\";", 'q'));
      assert.equal(sourceOf(f), "const q = \"'use strict';\";");
    });

    await t.test('a comment sharing the directive line survives it', () => {
      // The cut stops at the directive itself. Running past it would splice `// n8n runs` into the
      // body as bare code, and the Code node fails to compile — n8n reports that as a node error
      // with a line number that matches nothing on disk.
      const f = write('prep2.js', "'use strict';// n8n runs strict already\nfunction p() {}" + tail('p'));
      assert.equal(sourceOf(f), '// n8n runs strict already\nfunction p() {}');
    });

    await t.test('a module with no directive of its own is left alone', () => {
      // A directive is a statement at the start of a line, not the characters wherever they appear.
      // Node modules carry prompt text quoting source; rewriting inside one silently changes the
      // string the node sends to the model.
      const body = 'const RULE = "every module opens with \'use strict\'; and ends with the marker";';
      const f = write('prompt-ish.js', body + tail('RULE'));
      assert.equal(sourceOf(f), body);
    });
  });

test('a body kept in a nested function is left alone', (t) => {
  const write = modules(t);
  const body = "function gen() {\n'use strict';\n  return 1;\n}";
  const f = write('gen.js', mod(body, 'gen'));
  // The directive is matched at the start of the source, not at the start of any line: a nested one
  // belongs to the function that declares it and removing it changes that function's semantics.
  assert.equal(sourceOf(f), body);
});

test('a node body is the deps, then the module, then the call shim', (t) => {
  const write = modules(t);
  const map = write('checker-map.js', mod('const CHECKER_MAP = { a: 1 };', 'CHECKER_MAP'));
  const json = write('json-extract.js', mod('function extractJson(t) {\n  return null;\n}', 'extractJson'));
  const entry = write('parse-markers.js', mod('function parseMarkers({ $ }) {\n  return [];\n}', 'parseMarkers'));
  const call = 'return parseMarkers({ $, CHECKER_MAP });';
  // Order is load-bearing: the entry calls its deps, so a dep pasted after it is a ReferenceError at
  // n8n run time. Blank lines between parts keep the joined text parseable.
  assert.equal(nodeBody(entry, { deps: [map, json], call }), [
    'const CHECKER_MAP = { a: 1 };',
    'function extractJson(t) {\n  return null;\n}',
    'function parseMarkers({ $ }) {\n  return [];\n}',
    call,
  ].join('\n\n'));
});

test('a node with no deps is its module and the shim, nothing else', (t) => {
  const write = modules(t);
  const entry = write('verdict.js', mod('function verdict() {\n  return 1;\n}', 'verdict'));
  assert.equal(nodeBody(entry, { call: 'return verdict({ $json });' }),
    'function verdict() {\n  return 1;\n}\n\nreturn verdict({ $json });');
});

test('the assembled body is valid JS that n8n can compile', (t) => {
  const write = modules(t);
  const dep = write('checker-map.js', mod('const CHECKER_MAP = { a: 1 };', 'CHECKER_MAP'));
  const entry = write('parse-markers.js',
    mod('function parseMarkers() {\n  return CHECKER_MAP.a;\n}', 'parseMarkers'));
  const js = nodeBody(entry, { deps: [dep], call: 'return parseMarkers();' });
  // n8n compiles the string as a function body; this is that compile, and the call it then makes.
  assert.equal(new Function(js)(), 1);
});

test('a real module from src/nodes inlines to something n8n can run', () => {
  const entry = path.join(__dirname, '..', 'src', 'nodes', 'parse-markers.js');
  const dep = path.join(__dirname, '..', 'src', 'lib', 'checker-map.js');
  const js = nodeBody(entry, { deps: [dep], call: 'return parseMarkers({ $ });' });
  assert.ok(!js.includes('module.exports'), 'the one thing that must never reach a Code node');
  assert.ok(!/^'use strict';/m.test(js), 'no directive survives, from the dep or from the entry');
  assert.ok(js.startsWith('/**') || js.startsWith('const') || js.startsWith('//'),
    'the dep leads, and it leads with its own first line — no blank run in front');
  assert.ok(js.endsWith('\n\nreturn parseMarkers({ $ });'), 'the shim is last, one blank line clear');
  assert.doesNotThrow(() => new Function(js), 'a body that will not compile fails the node, not us');
});
