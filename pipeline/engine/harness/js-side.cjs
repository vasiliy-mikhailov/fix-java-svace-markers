'use strict';
/**
 * Differential harness, JS side — A LOADER, not a runner. All three families, one file.
 *
 * IT USED TO RUN THE JAVASCRIPT. There were three of these — js-side.cjs, input-family-js-side.cjs
 * and json-family-js-side.cjs — and between them they generated 6 910 cases, required the eleven node
 * bodies and helpers under n8n/agentic/src/{lib,nodes}, ran them, and wrote the files the Java drivers
 * and the two compare.cjs scripts read. Those modules have been ported and deleted; there is no
 * JavaScript left to run.
 *
 * WHAT SURVIVED IS THEIR OUTPUT, frozen under harness/fixtures as gzipped data. This file unpacks it
 * into harness/out so a person can read, grep and jq the corpus that the three tests in
 * src/test/java/tech/mikhailov/fsm/harness assert against.
 *
 * IT IS NOT WHAT ENFORCES ANYTHING. The comparison is `mvn test`.
 *
 *   node harness/js-side.cjs                  # all three families
 *   node harness/js-side.cjs json-family-     # one of them
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
  console.error(`js-side: unpacked ${n} frozen cases and their answers  (${prefix || 'node-family'})`);
}
