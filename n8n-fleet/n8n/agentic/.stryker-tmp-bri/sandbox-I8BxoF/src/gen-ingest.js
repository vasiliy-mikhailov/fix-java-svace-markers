// @ts-nocheck
'use strict';
/**
 * fsm-ingest: turn a Svace marker report into the `suspicions` backlog.
 *
 * POST /webhook/ingest { csv_path?, csv_text?, repo, branch?, path_prefix?, include_tests?,
 *                        only_checkers?, min_severity? }
 *
 * Replaces the parent pipeline's orchestrator + suspector + dedup. No file walking, no LLM detection:
 * Svace already decided what is suspicious, so ingest is a deterministic CSV -> rows transform.
 */
const fs = require('fs');
const path = require('path');
const { SUSPICIONS_TABLE, BUGS_TABLE, check } = require('./tables');
const { INGESTER_VERSION, PIPELINE_VERSION, shortId } = require('./versions');
const { nodeBody } = require('./lib/inline');

check();

const HERE = __dirname;
const OUT = path.join(HERE, '..', 'workflow_ingest.json');

const SUMMARY_JS = `
// Report what landed. The insert node emits one item per upserted row, so count them here rather
// than trusting the parse node's own tally — this is the number that actually reached the table.
const items = $input.all();
let summary = {};
try { summary = JSON.parse($('Parse markers').first().json.__summary || '{}'); } catch (e) {}
return [{ json: { ok: true, rows_written: items.length, ...summary } }];
`.trim();

const N = [];
function node(name, type, parameters, typeVersion, x, y = 300, extra) {
  const n = { parameters, id: `i${N.length + 1}`, name, type, typeVersion, position: [x, y] };
  if (extra) Object.assign(n, extra);
  N.push(n);
  return name;
}
const dtref = (tid, nm) => ({ __rl: true, mode: 'list', value: tid, cachedResultName: nm });

// Match-all = (repo isNotEmpty OR repo isEmpty) under matchType=anyCondition. isNotEmpty alone leaves
// behind half-written rows whose columns are all NULL — that gap kept stale rows on the dashboard
// after a re-run in the parent pipeline.
function clearNode(label, tid, nm, x) {
  return node(label, 'n8n-nodes-base.dataTable', {
    resource: 'row', operation: 'deleteRows', dataTableId: dtref(tid, nm),
    matchType: 'anyCondition',
    filters: { conditions: [
      { keyName: 'repo', condition: 'isNotEmpty', keyValue: '' },
      { keyName: 'repo', condition: 'isEmpty', keyValue: '' },
    ] },
    options: {},
  }, 1.1, x, 140, { onError: 'continueRegularOutput', executeOnce: true, alwaysOutputData: true });
}

node('Ingest webhook', 'n8n-nodes-base.webhook',
  { httpMethod: 'POST', path: 'ingest', responseMode: 'lastNode', options: {} },
  2, 0, 300, { webhookId: 'fsmingesthook1' });
// Fresh ingest = fresh backlog. `bugs` is cleared alongside `suspicions` because every bug row points
// at a suspicion_key; keeping bugs while wiping suspicions leaves orphaned artifacts on screen.
clearNode('Clear suspicions', SUSPICIONS_TABLE, 'suspicions', 220);
clearNode('Clear bugs', BUGS_TABLE, 'bugs', 440);

const PARSE_JS = nodeBody(path.join(HERE, 'nodes', 'parse-markers.js'), {
  deps: [path.join(HERE, 'lib', 'checker-map.js')],
  call: `return parseMarkers({ $, VERSION: ${JSON.stringify(shortId(INGESTER_VERSION))}, `
    + 'CHECKER_MAP, SEVERITY_MAP, SEVERITY_RANK });',
});
node('Parse markers', 'n8n-nodes-base.code',
  { mode: 'runOnceForAllItems', jsCode: PARSE_JS }, 2, 660);

const SUS_COLS = ['dedup_key', 'marker_id', 'repo', 'branch', 'file', 'class_name', 'method', 'line',
  'svace_line', 'anchor', 'anchor_status', 'category', 'severity', 'svace_checker',
  'svace_severity', 'title', 'description', 'evidence', 'status', 'note',
  'prove_attempts', 'version', 'method_key'];
// EXPLICIT mapping, not autoMapInputData: Parse markers also emits `__summary` on the first item, and
// n8n REJECTS an unknown column on autoMap, which would fail the whole insert.
node('Insert suspicions', 'n8n-nodes-base.dataTable', {
  resource: 'row', operation: 'upsert', dataTableId: dtref(SUSPICIONS_TABLE, 'suspicions'),
  filters: { conditions: [{ keyName: 'dedup_key', condition: 'eq', keyValue: '={{ $json.dedup_key }}' }] },
  columns: {
    mappingMode: 'defineBelow',
    value: Object.fromEntries(SUS_COLS.map(c => [c, `={{ $json['${c}'] }}`])),
    matchingColumns: ['dedup_key'], schema: [],
    attemptToConvertTypes: false, convertFieldsToString: true,
  },
  options: {},
}, 1.1, 880);
node('Ingest summary', 'n8n-nodes-base.code',
  { mode: 'runOnceForAllItems', jsCode: SUMMARY_JS }, 2, 1100);

const connections = {
  'Ingest webhook': { main: [[{ node: 'Clear suspicions', type: 'main', index: 0 }]] },
  'Clear suspicions': { main: [[{ node: 'Clear bugs', type: 'main', index: 0 }]] },
  'Clear bugs': { main: [[{ node: 'Parse markers', type: 'main', index: 0 }]] },
  'Parse markers': { main: [[{ node: 'Insert suspicions', type: 'main', index: 0 }]] },
  'Insert suspicions': { main: [[{ node: 'Ingest summary', type: 'main', index: 0 }]] },
};

const wf = {
  id: 'fsmingest000001', name: 'fsm-ingest', active: false,
  nodes: N, connections, settings: { executionOrder: 'v1' },
};

if (require.main === module) {
  fs.writeFileSync(OUT, JSON.stringify(wf, null, 2));
  console.log(`wrote workflow_ingest.json — ${N.length} nodes [${PIPELINE_VERSION.split(' ')[0]}]`);
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { wf, PARSE_JS };
