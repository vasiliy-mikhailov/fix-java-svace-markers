'use strict';
/**
 * fsm-prover: settle Svace markers -> the `bugs` Data Table.
 *
 * POST /webhook/prove, plus a 60s schedule that drains status='new' one marker at a time under the
 * runner lease. Two INDEPENDENT agents run per marker:
 *   REPRODUCER writes a JUnit test that FAILS on the unpatched code (verified red on the java-runner)
 *   FIXER writes ONLY a source fix that makes that test pass (verified green)
 * The fixer never authors or edits the test — a structural allowlist drops any edit outside the one
 * suspected file — so a fix cannot be self-authored to game its own test.
 *
 * A marker has TWO acceptable outcomes. Proven ones land in `bugs` with a PR draft; ones that will
 * not reproduce land as a WRITTEN VERDICT. Only infrastructure failures are retried.
 */
const fs = require('fs');
const path = require('path');
const { SUSPICIONS_TABLE, BUGS_TABLE, check } = require('./tables');
const V = require('./versions');
const { REPRODUCER_SYS, FIXER_SYS } = require('./prompts');
const { nodeBody } = require('./lib/inline');

check();

const HERE = __dirname;
const NODES = path.join(HERE, 'nodes');
const LIB = path.join(HERE, 'lib');
const OUT = path.join(HERE, '..', 'workflow_prover.json');
const RUNNER = 'http://fsm-java-runner:8090';

// How many prove attempts a marker gets before a non-reproduction is written up as a verdict. One
// reproducer sample is a weak basis for "this marker is wrong": the agent may simply have failed to
// find an angle. Markers whose checker can only ever be settled by argument skip the retry.
const VERDICT_MIN_ATTEMPTS = 2;

const N = [];
function node(name, type, parameters, typeVersion, x, y = 300, extra) {
  const n = { parameters, id: `p${N.length + 1}`, name, type, typeVersion, position: [x, y] };
  if (extra) Object.assign(n, extra);
  N.push(n);
  return name;
}
const dt = (tid, name) => ({ __rl: true, mode: 'list', value: tid, cachedResultName: name });
const code = (js, perItem) => ({ mode: perItem ? 'runOnceForEachItem' : 'runOnceForAllItems', jsCode: js });

/** A node body: the module source, its deps, and the shim that calls it with n8n's globals. */
function body(file, deps, call) {
  return nodeBody(path.join(NODES, file), { deps: deps.map(d => path.join(LIB, d)), call });
}

function model(name, x, y) {
  return node(name, '@n8n/n8n-nodes-langchain.lmChatOpenAi', {
    model: { __rl: true, mode: 'list', value: 'qwen-3.6-27b-fp8', cachedResultName: 'qwen-3.6-27b-fp8' },
    responsesApiEnabled: false,   // vLLM speaks chat/completions, not the Responses API
    options: { temperature: 0.2, maxTokens: 32000 },
  }, 1.3, x, y, { credentials: { openAiApi: { id: 'qwenvllmcred001', name: 'Qwen vLLM' } } });
}

function agent(name, sys, x, y) {
  return node(name, '@n8n/n8n-nodes-langchain.agent', {
    promptType: 'define', text: '={{ $json.agent_input }}',
    options: { systemMessage: sys, maxIterations: 3 },
  }, 3.1, x, y, { onError: 'continueRegularOutput' });
}

function runTest(name, x, y) {
  // 90 min: clone + RED + GREEN, each build up to 20 minutes
  return node(name, 'n8n-nodes-base.httpRequest', {
    method: 'POST', url: `${RUNNER}/run_test`,
    sendBody: true, contentType: 'json', specifyBody: 'json',
    jsonBody: '={{ JSON.stringify($json.body) }}', options: { timeout: 5400000 },
  }, 4.2, x, y, { onError: 'continueRegularOutput' });
}

// The runner is a single process, so its in-memory lease is a race-free single-instance lock.
// onError=continue + alwaysOutputData + a fail-CLOSED gate: if the runner is unreachable `acquired`
// is undefined, the gate's boolean-true test fails, and the prover simply does not run this tick.
function lease(name, urlPath, payload, x, y) {
  return node(name, 'n8n-nodes-base.httpRequest', {
    method: 'POST', url: RUNNER + urlPath,
    sendBody: true, contentType: 'json', specifyBody: 'json',
    jsonBody: JSON.stringify(payload), options: { timeout: 30000 },
  }, 4.2, x, y, { onError: 'continueRegularOutput', alwaysOutputData: true, executeOnce: true });
}

function gate(name, x, y) {
  return node(name, 'n8n-nodes-base.if', {
    conditions: {
      options: { caseSensitive: true, typeValidation: 'loose' },
      conditions: [{ id: 'acq', leftValue: '={{ $json.acquired }}', rightValue: '',
        operator: { type: 'boolean', operation: 'true', singleValue: true } }],
      combinator: 'and',
    },
  }, 2.2, x, y);
}

// TWO triggers, ONE lease. The schedule drains the backlog; the webhook still works for a manual run.
// Both acquire the 'prover' lease first, so only one drain runs at a time — proving happens marker by
// marker on the runner's isolated build workspace and never piles up n8n executions.
node('Prove webhook', 'n8n-nodes-base.webhook',
  { httpMethod: 'POST', path: 'prove', responseMode: 'lastNode', options: {} },
  2, 0, 300, { webhookId: 'fsmprovehook01' });
lease('Acquire lease (web)', '/lease', { name: 'prover', ttl_s: 1800 }, 120, -60);
gate('Lease gate (web)', 240, -60);
node('Prover schedule', 'n8n-nodes-base.scheduleTrigger',
  { rule: { interval: [{ field: 'seconds', secondsInterval: 60 }] } }, 1.2, 0, 560);
lease('Acquire lease (sched)', '/lease', { name: 'prover', ttl_s: 1800 }, 120, 560);
gate('Lease gate (sched)', 240, 560);

// alwaysOutputData: when the queue is EMPTY this must still emit one item, or the downstream never
// runs and the lease just acquired is never released — that stranded the whole prover for hours. The
// "Has suspicion?" gate then routes the empty item to Release lease instead of into a prove.
node('Get one suspicion', 'n8n-nodes-base.dataTable', {
  resource: 'row', operation: 'get', dataTableId: dt(SUSPICIONS_TABLE, 'suspicions'),
  filters: { conditions: [{ keyName: 'status', condition: 'eq', keyValue: 'new' }] },
  returnAll: false, limit: 1, options: {},
}, 1.1, 400, 560, { alwaysOutputData: true });
node('Has suspicion?', 'n8n-nodes-base.if', {
  conditions: {
    options: { caseSensitive: true, typeValidation: 'loose' },
    conditions: [{ id: 'sus', leftValue: '={{ $json.dedup_key }}', rightValue: '',
      operator: { type: 'string', operation: 'notEmpty', singleValue: true } }],
    combinator: 'and',
  },
}, 2.2, 560, 560);
node('Release lease', 'n8n-nodes-base.httpRequest', {
  method: 'POST', url: `${RUNNER}/lease/release`,
  sendBody: true, contentType: 'json', specifyBody: 'json',
  jsonBody: JSON.stringify({ name: 'prover' }), options: { timeout: 30000 },
}, 4.2, 440, 60, { onError: 'continueRegularOutput', alwaysOutputData: true, executeOnce: true });

// NO DEDUP STAGE. The parent ran one because its LLM suspector reported the same defect from several
// methods. Svace de-duplicates its own markers, and the only repeats are two markers on one line —
// two distinct obligations, kept apart by an occurrence index. Clustering them would retire findings.
node('Get new suspicions', 'n8n-nodes-base.dataTable', {
  resource: 'row', operation: 'get', dataTableId: dt(SUSPICIONS_TABLE, 'suspicions'),
  filters: { conditions: [{ keyName: 'status', condition: 'eq', keyValue: 'new' }] },
  returnAll: true, options: {},
}, 1.1, 220, 300, { alwaysOutputData: true });
node('Loop over suspicions', 'n8n-nodes-base.splitInBatches', { batchSize: 1, options: {} }, 3, 440);
node('Done', 'n8n-nodes-base.noOp', {}, 1, 660, 60);

node('Prep prover', 'n8n-nodes-base.code',
  code(body('prep-prover.js', [], 'return prepProver({ $json, $env, helpers: this.helpers });'), true),
  2, 660, 200);
node('Fetch source', 'n8n-nodes-base.httpRequest', {
  url: '=https://api.github.com/repos/{{ $json.repo }}/contents/{{ $json.file }}?ref={{ $json.branch }}',
  sendHeaders: true,
  headerParameters: { parameters: [
    { name: 'User-Agent', value: 'n8n-fsm' },
    { name: 'Accept', value: 'application/vnd.github+json' },
    { name: 'Authorization', value: '=Bearer {{ $env.GITHUB_TOKEN }}' },
    { name: 'Connection', value: 'close' },
  ] },
  options: { timeout: 60000 },
}, 4.2, 880, 200,
// a 404 or a rate limit must not poison the queue
{ retryOnFail: true, maxTries: 3, waitBetweenTries: 3000, onError: 'continueRegularOutput' });

// --- REPRODUCER: re-anchor the marker, write the failing test, verify it goes red ---
node('Build reproduce input', 'n8n-nodes-base.code',
  code(body('build-reproduce-input.js', [], 'return buildReproduceInput({ $, $json });'), true), 2, 1100, 200);
model('Reproducer Model', 1320, 60);
agent('Reproducer Agent', REPRODUCER_SYS.replace('__STAMP__', V.stamp(V.REPRODUCER_VERSION)), 1320, 200);
node('Parse test', 'n8n-nodes-base.code',
  code(body('parse-test.js', ['json-extract.js', 'test-realness.js'],
    'return parseTest({ $, $json, extractJson, testRealness });'), true), 2, 1540, 200);
runTest('run_test reproduce', 1760, 200);

// --- FIXER: source-only fix, must pass the reproducer's test, verified green ---
node('Build fix input', 'n8n-nodes-base.code',
  code(body('build-fix-input.js', [], 'return buildFixInput({ $, $json });'), true), 2, 1980, 200);
model('Fixer Model', 2200, 60);
agent('Fixer Agent', FIXER_SYS.replace('__STAMP__', V.stamp(V.FIXER_VERSION)), 2200, 200);
node('Parse fix', 'n8n-nodes-base.code',
  code(body('parse-fix.js', ['json-extract.js'], 'return parseFix({ $, $json, extractJson });'), true),
  2, 2420, 200);
runTest('run_test fix', 2640, 200);

// --- judgement + record ---
node('Fix skeptic', 'n8n-nodes-base.code',
  code(body('fix-skeptic.js', [],
    `return fixSkeptic({ $, $json, $env, helpers: this.helpers, skepticStamp: ${JSON.stringify(V.stamp(V.SKEPTIC_VERSION))} });`), true),
  2, 2860, 200, { onError: 'continueRegularOutput' });
node('PR maker', 'n8n-nodes-base.code',
  code(body('pr-maker.js', [],
    `return prMaker({ $, $json, $env, helpers: this.helpers, prStamp: ${JSON.stringify(V.stamp(V.PR_MAKER_VERSION))} });`), true),
  2, 3080, 200, { onError: 'continueRegularOutput' });
node('Record outcome', 'n8n-nodes-base.code',
  code(body('record-outcome.js', [],
    `return recordOutcome({ $, $json, versions: ${JSON.stringify(V.versionsJson())} });`), true), 2, 660, 440);
// The verdict sits BETWEEN Record outcome and the writes, so `state` is final before either table is
// touched: a marker with a written rebuttal is stored as its verdict, and one merely being retried
// never reaches a terminal status. onError=continue — a dead verdict LLM must leave the row as it was,
// never fail the prove and strand the lease.
node('Verdict', 'n8n-nodes-base.code',
  code(body('verdict.js', ['json-extract.js', 'exec-verdict.js'],
    'return verdict({ $, $json, $env, helpers: this.helpers, extractJson, execVerdict, '
    + `minAttempts: ${VERDICT_MIN_ATTEMPTS}, verdictStamp: ${JSON.stringify(V.stamp(V.VERDICT_VERSION))} });`), true),
  2, 660, 620, { onError: 'continueRegularOutput' });

// EXPLICIT mapping, not autoMapInputData: Record outcome also emits `attempts`, which `bugs` has no
// column for, and n8n REJECTS an unknown column on autoMap — every prove used to crash here.
const BUG_COLS = ['suspicion_key', 'repo', 'file', 'title', 'jdk', 'test_path', 'test_code', 'fix_diff',
  'red_verified', 'green_verified', 'value_score', 'value_verdict', 'pr_title', 'pr_body',
  'state', 'infra_reason', 'branch', 'versions', 'verdict_text', 'verdict_kind', 'svace_checker'];
node('Upsert bug', 'n8n-nodes-base.dataTable', {
  resource: 'row', operation: 'upsert', dataTableId: dt(BUGS_TABLE, 'bugs'),
  filters: { conditions: [{ keyName: 'suspicion_key', condition: 'eq', keyValue: '={{ $json.suspicion_key }}' }] },
  columns: {
    mappingMode: 'defineBelow',
    value: Object.fromEntries(BUG_COLS.map(c => [c, `={{ $json['${c}'] }}`])),
    matchingColumns: ['suspicion_key'], schema: [],
    attemptToConvertTypes: false, convertFieldsToString: true,
  },
  options: {},
}, 1.1, 880, 440);
// The next status is computed in the Verdict Code node, not as a nested ternary in an expression:
// infra_error retries (to 3, then infra_stuck), a pending retry returns to 'new', a written rebuttal
// lands as its verdict kind, and only a real judgement retires the marker.
node('Update suspicion', 'n8n-nodes-base.dataTable', {
  resource: 'row', operation: 'update', dataTableId: dt(SUSPICIONS_TABLE, 'suspicions'),
  filters: { conditions: [{ keyName: 'dedup_key', condition: 'eq',
    keyValue: "={{ $('Verdict').item.json.suspicion_key }}" }] },
  columns: {
    mappingMode: 'defineBelow',
    value: {
      status: "={{ $('Verdict').item.json.suspicion_status }}",
      prove_attempts: "={{ $('Verdict').item.json.attempts }}",
      note: "={{ $('Verdict').item.json.suspicion_note }}",
      anchor: "={{ $('Verdict').item.json.anchor }}",
      anchor_status: "={{ $('Verdict').item.json.anchor_status }}",
    },
    matchingColumns: ['dedup_key'], schema: [],
    attemptToConvertTypes: false, convertFieldsToString: true,
  },
  options: {},
}, 1.1, 1100, 440);

// Safety net: if any node hard-fails, the flow never reaches "Release lease" and the lease would sit
// held until its TTL, blocking all proving for ~30 minutes. This path releases it immediately.
node('On prover error', 'n8n-nodes-base.errorTrigger', {}, 1, 0, 760);
node('Release lease (err)', 'n8n-nodes-base.httpRequest', {
  method: 'POST', url: `${RUNNER}/lease/release`,
  sendBody: true, contentType: 'json', specifyBody: 'json',
  jsonBody: JSON.stringify({ name: 'prover' }), options: { timeout: 30000 },
}, 4.2, 240, 760, { onError: 'continueRegularOutput' });

const connections = {
  'On prover error': { main: [[{ node: 'Release lease (err)', type: 'main', index: 0 }]] },
  'Prove webhook': { main: [[{ node: 'Acquire lease (web)', type: 'main', index: 0 }]] },
  'Acquire lease (web)': { main: [[{ node: 'Lease gate (web)', type: 'main', index: 0 }]] },
  'Lease gate (web)': { main: [[{ node: 'Get new suspicions', type: 'main', index: 0 }], []] },
  'Get new suspicions': { main: [[{ node: 'Has suspicion?', type: 'main', index: 0 }]] },
  'Prover schedule': { main: [[{ node: 'Acquire lease (sched)', type: 'main', index: 0 }]] },
  'Acquire lease (sched)': { main: [[{ node: 'Lease gate (sched)', type: 'main', index: 0 }]] },
  'Lease gate (sched)': { main: [[{ node: 'Get one suspicion', type: 'main', index: 0 }], []] },
  'Get one suspicion': { main: [[{ node: 'Has suspicion?', type: 'main', index: 0 }]] },
  // a real marker -> prove; an EMPTY queue -> straight to Release lease, never stranding it
  'Has suspicion?': { main: [[{ node: 'Loop over suspicions', type: 'main', index: 0 }],
    [{ node: 'Release lease', type: 'main', index: 0 }]] },
  'Loop over suspicions': { main: [[{ node: 'Release lease', type: 'main', index: 0 }],
    [{ node: 'Prep prover', type: 'main', index: 0 }]] },
  'Release lease': { main: [[{ node: 'Done', type: 'main', index: 0 }]] },
  'Prep prover': { main: [[{ node: 'Fetch source', type: 'main', index: 0 }]] },
  'Fetch source': { main: [[{ node: 'Build reproduce input', type: 'main', index: 0 }]] },
  'Build reproduce input': { main: [[{ node: 'Reproducer Agent', type: 'main', index: 0 }]] },
  'Reproducer Model': { ai_languageModel: [[{ node: 'Reproducer Agent', type: 'ai_languageModel', index: 0 }]] },
  'Reproducer Agent': { main: [[{ node: 'Parse test', type: 'main', index: 0 }]] },
  'Parse test': { main: [[{ node: 'run_test reproduce', type: 'main', index: 0 }]] },
  'run_test reproduce': { main: [[{ node: 'Build fix input', type: 'main', index: 0 }]] },
  'Build fix input': { main: [[{ node: 'Fixer Agent', type: 'main', index: 0 }]] },
  'Fixer Model': { ai_languageModel: [[{ node: 'Fixer Agent', type: 'ai_languageModel', index: 0 }]] },
  'Fixer Agent': { main: [[{ node: 'Parse fix', type: 'main', index: 0 }]] },
  'Parse fix': { main: [[{ node: 'run_test fix', type: 'main', index: 0 }]] },
  'run_test fix': { main: [[{ node: 'Fix skeptic', type: 'main', index: 0 }]] },
  'Fix skeptic': { main: [[{ node: 'PR maker', type: 'main', index: 0 }]] },
  'PR maker': { main: [[{ node: 'Record outcome', type: 'main', index: 0 }]] },
  'Record outcome': { main: [[{ node: 'Verdict', type: 'main', index: 0 }]] },
  'Verdict': { main: [[{ node: 'Upsert bug', type: 'main', index: 0 }]] },
  'Upsert bug': { main: [[{ node: 'Update suspicion', type: 'main', index: 0 }]] },
  'Update suspicion': { main: [[{ node: 'Loop over suspicions', type: 'main', index: 0 }]] },
};

const wf = {
  id: 'fsmprover00001', name: 'fsm-prover',
  active: true,                       // the schedule trigger must be active to fire
  nodes: N, connections,
  // errorWorkflow = itself: a hard failure fires "On prover error" and releases the lease
  settings: { executionOrder: 'v1', errorWorkflow: 'fsmprover00001' },
};

if (require.main === module) {
  fs.writeFileSync(OUT, JSON.stringify(wf, null, 2));
  console.log(`wrote workflow_prover.json — ${N.length} nodes (independent Reproducer + Fixer)`);
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { wf };
