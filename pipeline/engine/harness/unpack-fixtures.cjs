'use strict';
/**
 * Differential harness — A READABILITY AID. All three families, one file. It unpacks fixtures; it runs
 * nothing and enforces nothing.
 *
 * The 6 910 cases and the reference answers recorded for them are frozen under harness/fixtures as
 * gzipped data. This file writes readable copies into harness/out so a person can read, grep and jq
 * the corpora that the three tests in src/test/java/tech/mikhailov/fsm/harness assert against.
 *
 * THE COMPARISON IS `mvn test`.
 *
 *   node harness/unpack-fixtures.cjs                  # all three families
 *   node harness/unpack-fixtures.cjs json-family-     # one of them
 */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const FIX = path.join(__dirname, 'fixtures');
const OUT = path.join(__dirname, 'out');
const FAMILIES = ['', 'input-family-', 'json-family-'];
const only = process.argv[2];

fs.mkdirSync(OUT, { recursive: true });
for (const prefix of FAMILIES) {
  if (only !== undefined && only !== prefix) continue;
  let n = 0;
  for (const name of ['cases.json', 'js-results.json']) {
    const gz = path.join(FIX, prefix + name + '.gz');
    const text = zlib.gunzipSync(fs.readFileSync(gz)).toString('utf8');
    fs.writeFileSync(path.join(OUT, prefix + name), text);
    n = JSON.parse(text).length;
  }
  console.error(`unpacked ${n} frozen cases and their answers  (${prefix || 'node-family'})`);
}
