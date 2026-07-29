// @ts-nocheck
'use strict';
/**
 * `check-workflows` — the pre-deploy gate on the generated workflow JSON.
 *
 * The regressions it exists for did not look like failures. A Code node that throws under
 * onError=continueRegularOutput has its INPUT forwarded by n8n, so `method is not defined` and
 * `_toolCache is not defined` both surfaced as a green run with no findings. Those two shapes are
 * pinned below by name, along with the temporal-dead-zone read that cost 78% of another run.
 *
 * The other half of the job is not crying wolf: a gate that reports something on every deploy stops
 * being read, and then it hides the real failure. So the idioms these node bodies actually use — n8n's
 * injected globals, regex literals next to division, nested template literals, ternaries that look
 * like object keys — are asserted CLEAN, and so are the three workflows currently in the repo.
 */
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { checkCode, checkWorkflow, staleness, run, main, lex } = require('../src/check-workflows');

const REPO = path.join(__dirname, '..');
const LIVE = ['workflow_setup.json', 'workflow_ingest.json', 'workflow_prover.json'];

const messages = (r) => r.problems.map((p) => p.message);

/** A throwaway repo layout: `src/<gen>` beside `<artifact>`, which is what `staleness` looks for. */
function sandbox(genSource, artifact) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'check-workflows-'));
  fs.mkdirSync(path.join(dir, 'src'));
  fs.writeFileSync(path.join(dir, 'src', 'gen-setup.js'), genSource);
  fs.writeFileSync(path.join(dir, 'workflow_setup.json'), artifact);
  return path.join(dir, 'workflow_setup.json');
}

test('a syntax error is reported, and nothing else is', () => {
  const r = checkCode('const rows = $input.all(;\nreturn rows;');
  assert.equal(r.problems.length, 1);
  assert.match(r.problems[0].message, /^SYNTAX: /);
  assert.equal(r.problems[0].line, 1, 'the line is the body\'s own, not the async wrapper\'s');
  assert.deepEqual(r.warnings, [], 'an unparseable body has no scope to report on');
});

test('a syntax error stops the scope check rather than burying it in noise', () => {
  // Half-written code has free names everywhere; only the parse failure is actionable.
  const r = checkCode('function f( {\n  return missingA + missingB;\n}');
  assert.equal(r.problems.length, 1);
  assert.match(r.problems[0].message, /^SYNTAX: /);
});

test('an undefined variable is caught — the failure n8n swallows into "no findings"', async (t) => {
  await t.test('`method is not defined`: the class auditor regression', () => {
    const r = checkCode([
      'const rows = $input.all();',
      'return rows.map(r => ({ json: { key: r.json.file + "#" + method } }));',
    ].join('\n'));
    assert.deepEqual(messages(r), ["'method' is not defined"]);
    assert.equal(r.problems[0].line, 2);
  });

  await t.test('`_toolCache is not defined`: an edit that spanned the declaration', () => {
    const r = checkCode([
      'async function callTool(name) {',
      '  if (_toolCache[name]) return _toolCache[name];',
      '  return null;',
      '}',
      'return [{ json: { out: await callTool("x") } }];',
    ].join('\n'));
    assert.deepEqual(messages(r), ["'_toolCache' is not defined"],
      'reported once, not once per use');
  });

  await t.test('a bare assignment declares nothing', () => {
    assert.deepEqual(messages(checkCode('rowCount = 1;\nreturn rowCount;')),
      ["'rowCount' is not defined"]);
  });

  await t.test('a name declared only inside another function does not leak outwards', () => {
    const r = checkCode('function a() { const seen = 1; return seen; }\nreturn a() + seen;');
    assert.deepEqual(messages(r), ["'seen' is not defined"]);
  });

  await t.test('a property of the same name is not a variable', () => {
    // `r.json.method` must never be mistaken for the free `method` above.
    assert.deepEqual(checkCode('const r = $json;\nreturn r.method + r?.method;').problems, []);
  });
});

test('a const read before its declaration is caught — it throws in the dead zone', async (t) => {
  await t.test('at the top level, where nothing defers the read', () => {
    const r = checkCode('const total = base + 1;\nconst base = 2;\nreturn total;');
    assert.deepEqual(messages(r), ["'base' was used before it was defined"]);
    assert.equal(r.problems[0].line, 1);
  });

  await t.test('a class is in the dead zone too, unlike a function declaration', () => {
    assert.deepEqual(messages(checkCode('const v = new H();\nclass H {}\nreturn v;')),
      ["'H' was used before it was defined"]);
    assert.deepEqual(checkCode('const v = h();\nfunction h() { return 1; }\nreturn v;').problems, [],
      'a function declaration hoists, so this one is fine');
  });

  await t.test('a helper closing over state declared later only advises', () => {
    // Legitimate as long as nobody calls it too early, so it must not block a deploy.
    const r = checkCode('function f() { return TAIL; }\nconst TAIL = 2;\nreturn f();');
    assert.deepEqual(r.problems, []);
    assert.deepEqual(r.warnings.map((w) => w.message), ["'TAIL' was used before it was defined"]);
  });

  await t.test('a name declared twice in the same function is left alone', () => {
    // Without block scopes there is no telling which declaration a read resolves to, and a guess
    // here would fire on the `const whole`/`const tryAt` pairs the real json extractor uses.
    const r = checkCode('if ($json.a) { const w = 1; return w; }\nconst w = 2;\nreturn w;');
    assert.deepEqual(r.problems, []);
    assert.deepEqual(r.warnings, []);
  });
});

test('the idioms these node bodies are made of stay clean', async (t) => {
  const clean = {
    "n8n's injected globals": 'const items = $input.all();\n'
      + 'const j = $("Prep prover").first().json;\n'
      + 'return [{ json: { ...j, n: items.length, at: $now, run: $runIndex } }];',
    'regex literals beside division': 'const a = 10, b = 2;\n'
      + 'const re = /a\\/b/g;\nreturn String(a / b / 2).replace(/x/g, () => a);',
    'nested template literals': 'const x = 1;\nreturn `a${`b${x + 1}c`}d${[x].map(v => `${v}`)}`;',
    'a ternary that looks like an object key': 'const ok = true;\n'
      + 'return { status: ok ? { a: 1 } : 2, b: ok ? ok : ok };',
    'destructuring with defaults and rest': 'const { a, b: c, d = 5, ...rest } = $json;\n'
      + 'const [e, , f = a] = [1, 2, 3];\nreturn [a, c, d, rest, e, f];',
    'object and class methods': 'const k = "z";\n'
      + 'class A extends Error { constructor(m) { super(m); } }\n'
      + 'const o = { get v() { return 1; }, async m(x) { return x; }, [k](y) { return y; },\n'
      + '  "a-b"(z) { return z; }, static: 1 };\n'
      + 'return [new A("x"), o];',
    'labelled loops': 'outer: for (const x of [1]) { for (const y of [2]) '
      + '{ if (x) continue outer; if (y) break outer; } }\nreturn 1;',
    'catch bindings and optional chaining': 'let out = {};\n'
      + 'try { out = JSON.parse($json.text); } catch (e) { out = { err: e.message }; }\n'
      + 'return out?.err ?? out?.rows?.[0] ?? null;',
    'comments containing slashes': '// a / b /* not a comment\nconst a = 1; /* x / y */\n'
      + 'return a / 1;',
    'top-level await, which the async wrapper makes legal': 'const r = await Promise.resolve(1);\n'
      + 'return [{ json: { r } }];',
  };
  for (const [name, src] of Object.entries(clean)) {
    await t.test(name, () => {
      const r = checkCode(src);
      assert.deepEqual(r.problems, []);
      assert.deepEqual(r.warnings, []);
    });
  }
});

test('a stale artifact is caught — a failed generator leaves the old JSON on disk', async (t) => {
  await t.test('the JSON no longer matches what the generator produces', () => {
    const file = sandbox('module.exports = { wf: { nodes: [{ name: "Verdict" }] } };',
      JSON.stringify({ nodes: [] }, null, 2));
    assert.match(staleness(file), /^STALE: src\/gen-setup\.js does not produce this JSON/);
  });

  await t.test('the generator throws, so it cannot have produced anything', () => {
    const file = sandbox('throw new Error("tables.js still has placeholder ids for: bugs");', '{}');
    assert.match(staleness(file), /^STALE: .*throws on load \(tables\.js still has placeholder/);
  });

  await t.test('a matching pair is not stale, whatever the file mtimes say', () => {
    const wf = { name: 'fsm-setup', nodes: [{ name: 'Webhook' }] };
    const file = sandbox(`module.exports = { wf: ${JSON.stringify(wf)} };`,
      JSON.stringify(wf, null, 2));
    fs.utimesSync(path.join(path.dirname(file), 'src', 'gen-setup.js'), new Date(), new Date());
    assert.equal(staleness(file), null, 'a touched generator that still agrees is not stale');
  });

  await t.test('a half-written JSON is stale, not a crash', () => {
    // A generator killed mid-write leaves exactly this, and it must not take the run down with it.
    const file = sandbox('module.exports = { wf: { nodes: [] } };', '{ "nodes": [');
    assert.match(staleness(file), /^STALE: this JSON does not parse/);
  });

  await t.test('staleness outranks the node checks, and counts as one problem', () => {
    const file = sandbox('module.exports = { wf: { nodes: [] } };',
      JSON.stringify({ nodes: [{ name: 'Bad', parameters: { jsCode: 'return oops;' } }] }, null, 2));
    const lines = [];
    assert.equal(run([file], (s) => lines.push(s)), 1);
    assert.match(lines.join('\n'), /✗ workflow_setup\.json\n {4}STALE:/);
    assert.ok(!lines.join('\n').includes('oops'), 'validating a stale JSON is the false green');
  });
});

test('the workflows in the repo pass', async (t) => {
  for (const name of LIVE) {
    await t.test(name, () => {
      const r = checkWorkflow(path.join(REPO, name));
      assert.deepEqual(r.problems, []);
      assert.deepEqual(r.warnings, [], 'advisories are noise once they are permanent');
      assert.equal(staleness(path.join(REPO, name)), null, 'and the generator still produces it');
    });
  }

  await t.test('and the whole run is green', () => {
    const lines = [];
    assert.equal(main(LIVE.map((f) => path.join(REPO, f)), (s) => lines.push(s)), 0);
    assert.deepEqual(lines.slice(0, 3), LIVE.map((f) => `✓ ${f}`));
  });

  await t.test('as it is from the command line, where the default is every workflow_*.json', () => {
    const cli = spawnSync(process.execPath, [path.join(REPO, 'src', 'check-workflows.js')],
      { cwd: REPO, encoding: 'utf8' });
    assert.equal(cli.status, 0, cli.stdout + cli.stderr);
    for (const name of LIVE) assert.match(cli.stdout, new RegExp(`✓ ${name}`));
  });
});

test('the lexer refuses to guess at a body it cannot read', () => {
  // Unreachable through checkCode, which parses first — but if these two ever disagree the check
  // must fail loudly rather than analyse half a token stream and report a clean bill.
  assert.throws(() => lex('const a = `unterminated'), /unterminated template literal/);
  assert.throws(() => lex('const re = /unterminated\n;'), /unterminated regex literal/);
});

test('the report says which node failed, and the exit code counts only problems', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'check-workflows-'));
  const file = path.join(dir, 'workflow_x.json');   // unmapped name: no generator to compare against
  fs.writeFileSync(file, JSON.stringify({ nodes: [
    { name: 'Ok', parameters: { jsCode: 'return [];' } },
    { name: 'Parse markers', parameters: { jsCode: 'return rows.concat(method);' } },
    { name: 'Advises', parameters: { jsCode: 'function f() { return T; }\nconst T = 1;\nreturn f();' } },
  ] }));
  const lines = [];
  assert.equal(run([file], (s) => lines.push(s)), 2, 'two free names, and the warning is not one');
  const out = lines.join('\n');
  assert.match(out, /\[Parse markers\] line 1: 'rows' is not defined/);
  assert.match(out, /\[Parse markers\] line 1: 'method' is not defined/);
  assert.match(out, /⚠ \[Advises\] line 1: 'T' was used before it was defined/);
  assert.match(out, /\n2 problem\(s\)$/);
});

test('a node with no jsCode is not a Code node', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'check-workflows-'));
  const file = path.join(dir, 'workflow_x.json');
  fs.writeFileSync(file, JSON.stringify({ nodes: [
    { name: 'Webhook', parameters: { path: 'setup' } },
    { name: 'No params' },
  ] }));
  assert.deepEqual(checkWorkflow(file).problems, []);
});
