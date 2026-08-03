'use strict';
/**
 * Differential harness — A READABILITY AID. It unpacks fixtures; it runs nothing and enforces nothing.
 *
 * The cases, the recorded reference answers and the filesystem tree four families read are frozen
 * under harness/fixtures as gzipped data. This file writes readable copies into harness/out so a
 * person can read, grep and jq the corpus that
 * src/test/java/.../DifferentialHarnessTest.java asserts against.
 *
 * THE COMPARISON IS `mvn test`. This is a convenience for reading a fixture that is otherwise a
 * 227 KB gzip, and nothing depends on its output.
 *
 *   node harness/unpack-fixtures.cjs   # -> harness/out/{cases.json,js-results.json,fixtures/}
 */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const HERE = __dirname;
const FIX = path.join(HERE, 'fixtures');
const OUT = path.join(HERE, 'out');
const TREE = path.join(OUT, 'fixtures');

// The three values that were properties of the MACHINE that generated the fixtures rather than of
// either implementation. HarnessFixtures.java substitutes the same three, for the reasons given
// there at length.
const escape = (s) => JSON.stringify(s).slice(1, -1);
const load = (name) => zlib.gunzipSync(fs.readFileSync(path.join(FIX, name))).toString('utf8')
  .split('@FIXTURES@').join(escape(TREE))
  .split('@CWD@').join(escape(path.resolve(HERE, '..')))
  .split('@PATH@').join(escape(process.env.PATH || ''));

fs.rmSync(TREE, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

// The filesystem tree, rebuilt from the manifest: directories, bodies, the mtimes the `outcome`
// family gates on, and the symlinks that keep the walk honest about loops.
for (const e of JSON.parse(load('tree.json.gz'))) {
  const at = e.path === '' ? TREE : path.join(TREE, e.path);
  if (e.kind === 'dir') {
    fs.mkdirSync(at, { recursive: true });
  } else if (e.kind === 'link') {
    fs.mkdirSync(path.dirname(at), { recursive: true });
    try { fs.symlinkSync(e.target, at); } catch { /* no symlinks here: same as the JS side did */ }
  } else {
    fs.mkdirSync(path.dirname(at), { recursive: true });
    fs.writeFileSync(at, Buffer.from(e.body, 'base64'));
    if (e.mtime !== undefined) fs.utimesSync(at, e.mtime, e.mtime);
  }
}

const cases = load('cases.json.gz');
const results = load('js-results.json.gz');
fs.writeFileSync(path.join(OUT, 'cases.json'), cases);
fs.writeFileSync(path.join(OUT, 'js-results.json'), results);
console.error(`unpacked ${JSON.parse(cases).length} frozen cases and their answers`);
