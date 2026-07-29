// @ts-nocheck
'use strict';
/**
 * n8n Data Table ids for THIS instance.
 *
 * A fresh n8n mints its own ids when the setup workflow runs — they are not the fix-java-bugs ids, and
 * a wrong-but-plausible id fails at RUN time (empty reads, no rows written) rather than at generate
 * time. Kept in one place so pointing the fleet at another instance is a single edit.
 *
 * After running the setup workflow:  node src/sync-tables.js   (rewrites the four ids below)
 */
const SUSPICIONS_TABLE = 'kC7PYCiSwfxNtfiC';
const BUGS_TABLE = 'ZQ1hwP73Ce8y0GJi';
const SCAN_FILES_TABLE = 'DfDnATGu91YbyyuD';
const METHOD_RUNS_TABLE = '25YC8Onozyac6H9t';

const PLACEHOLDER_PREFIX = 'REPLACE_';

/** Fail loudly at generate time rather than emitting a workflow that points nowhere. */
function check() {
  const missing = Object.entries({
    suspicions: SUSPICIONS_TABLE, bugs: BUGS_TABLE,
    scan_files: SCAN_FILES_TABLE, method_runs: METHOD_RUNS_TABLE,
  }).filter(([, v]) => v.startsWith(PLACEHOLDER_PREFIX)).map(([k]) => k);
  if (missing.length) {
    throw new Error('tables.js still has placeholder ids for: ' + missing.join(', ')
      + '\nRun the setup workflow, then sync_tables.py.');
  }
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { SUSPICIONS_TABLE, BUGS_TABLE, SCAN_FILES_TABLE, METHOD_RUNS_TABLE, check };
