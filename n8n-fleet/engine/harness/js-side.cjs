'use strict';
/**
 * Differential harness, JS side — slice 2 (verdict, fix skeptic, PR maker).
 *
 * Generates the cases, runs the ORIGINAL JavaScript through them, and writes both files. The Java
 * driver reads the same cases.json and writes its own results; compare.cjs diffs the two.
 *
 * Every value is written TYPE-TAGGED ("" is not 0 is not null is not undefined), because the whole
 * point of the exercise is to catch the coercion that looks right and is not.
 *
 *   node harness/js-side.cjs            # -> harness/out/cases.json, harness/out/js-results.json
 */
const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, '..', '..', 'n8n', 'agentic', 'src');
const { verdict } = require(path.join(SRC, 'nodes', 'verdict.js'));
const { fixSkeptic } = require(path.join(SRC, 'nodes', 'fix-skeptic.js'));
const { prMaker } = require(path.join(SRC, 'nodes', 'pr-maker.js'));
const { extractJson } = require(path.join(SRC, 'lib', 'json-extract.js'));
const { execVerdict } = require(path.join(SRC, 'lib', 'exec-verdict.js'));

const OUT = path.join(__dirname, 'out');

// ---------------------------------------------------------------------------------------------
// case building
// ---------------------------------------------------------------------------------------------

/** "this key is not in the item at all", which JSON cannot spell. */
const ABSENT = Symbol('absent');

const cases = [];
const add = (node, id, body, http) => cases.push({ id: node + ': ' + id, node, body, http });

/** A deep copy of `base` with `path` (dotted) set — or deleted, for ABSENT. */
function mut(base, dotted, value) {
  const copy = structuredClone(base);
  const keys = dotted.split('.');
  let o = copy;
  for (const k of keys.slice(0, -1)) {
    if (o[k] === undefined || o[k] === null) o[k] = {};
    o = o[k];
  }
  const last = keys[keys.length - 1];
  if (value === ABSENT) delete o[last]; else o[last] = value;
  return copy;
}

const env = { QWEN_BASE_URL: 'http://llm', QWEN_API_KEY: 'k', QWEN_MODEL: 'm' };

// ---- verdict ---------------------------------------------------------------------------------

const BUILD_FAILED = 'reproducer test never executed (build failed, jdk 25): BUILD FAILURE';

const vBase = () => ({
  item: { state: 'not_reproduced', attempts: 2, infra_reason: '' },
  prep_prover: {
    suspicion_key: 'k', repo: 'o/r', file: 'src/main/java/a/B.java', svace_checker: 'HANDLE_LEAK',
    svace_severity: 'Major', svace_line: 44, description: 'a resource is not closed',
    settle_by: 'test', marker_id: 'm1',
  },
  parse_test: { can_prove: true, repro_root_cause: 'rc', test_score: 90 },
  parse_fix: { fix_root_cause: 'because X' },
  run_test_reproduce: { red_summary: { test_executed: true }, red_output: '' },
  build_reproduce_input: {
    method_text: 'void f(){}', src: 'class B {}', anchor: 'B#f():44',
    anchor_status: 'exact', anchor_note: 'inside f()',
  },
  pr_maker: { pr_reason: '' },
  env,
  min_attempts: 2,
  verdict_stamp: '[stage vd1]',
});

const reply = (kind, text = 'because the guard on line 12 rejects it', confidence = 'high') =>
  ({ replyText: JSON.stringify({ kind, verdict: text, confidence }) });

// 1. THE ROUTING FACTORIAL: state x attempts x infra_reason, with a verdict always available.
const STATES = ['not_reproduced', 'not-a-bug', 'infra_error', 'pr_ready', 'pr_rejected',
  'needs_review', 'fix_failed', 'by_design', 'unprovable', 'false_positive', 'rejected',
  'weird_state', '', null, ABSENT];
const ATTEMPTS = [ABSENT, 0, 1, 2, 3, 5, '3', 2.5, null, 'abc'];
const INFRA = ['', BUILD_FAILED, BUILD_FAILED + ' and source fetch returned nothing',
  'branch unresolved: 404', 'reproducer reply was not parseable JSON',
  'run_test errored: connection reset',
  'source file exceeded 300000 chars and was truncated — a verdict on it is not trustworthy',
  'test never executed (build failed, jdk 25)'];

for (const state of STATES) {
  for (const attempts of ATTEMPTS) {
    for (const infra of INFRA) {
      let b = mut(vBase(), 'item.state', state);
      b = mut(b, 'item.attempts', attempts);
      b = mut(b, 'item.infra_reason', infra);
      add('verdict', `route state=${String(state)} attempts=${String(attempts)} infra=${infra.slice(0, 24)}`,
        b, [reply('false-positive')]);
    }
  }
}

// 2. THE RETRY GATE: the three inputs that decide whether a marker is argued or requeued.
for (const state of ['not_reproduced', 'not-a-bug', 'infra_error', 'pr_ready']) {
  for (const attempts of [1, 2, 3, 5, '1']) {
    for (const settle of ['test', 'argue', ABSENT]) {
      for (const canProve of [true, false, ABSENT]) {
        for (const executed of [true, false, ABSENT]) {
          for (const min of [1, 2]) {
            let b = mut(vBase(), 'item.state', state);
            b = mut(b, 'item.attempts', attempts);
            b = mut(b, 'item.infra_reason', state === 'infra_error' ? BUILD_FAILED : '');
            b = mut(b, 'prep_prover.settle_by', settle);
            b = mut(b, 'parse_test.can_prove', canProve);
            b = mut(b, 'run_test_reproduce.red_summary.test_executed', executed);
            b = mut(b, 'min_attempts', min);
            add('verdict',
              `gate ${state} a=${attempts} settle=${String(settle)} prove=${String(canProve)} ran=${String(executed)} min=${min}`,
              b, [reply('by-design')]);
          }
        }
      }
    }
  }
}

// 3. THE PROMPT, field by field: it is an output of this stage in its own right.
const PROMPT_FIELDS = [
  ['prep_prover.repo', ['o/r', '', null, ABSENT, 42]],
  ['prep_prover.file', ['a/B.java', '', null, ABSENT]],
  ['prep_prover.svace_checker', ['HANDLE_LEAK', '', 0, null, ABSENT]],
  ['prep_prover.svace_severity', ['Major', '', null, ABSENT]],
  ['prep_prover.svace_line', [44, 0, '44', null, ABSENT]],
  ['prep_prover.description', ['a resource is not closed', '', null, ABSENT, { a: 1 }]],
  ['prep_prover.suspicion_key', ['k', '', null, ABSENT]],
  ['build_reproduce_input.anchor_status', ['exact', 'no-method', '', null, ABSENT]],
  ['build_reproduce_input.anchor_note', ['Lombok generates the accessor', '', null, ABSENT]],
  ['build_reproduce_input.method_text', ['void f(){}', '', null, ABSENT, 42, false,
    'void f(){ /*HEAD*/' + 'x'.repeat(20000) + '/*TAIL*/ }', 'x'.repeat(19999), 'x'.repeat(20000),
    'x'.repeat(20001)]],
  ['build_reproduce_input.src', ['class B {}', '', null, ABSENT, 7,
    'class B { /*HEAD*/' + 'x'.repeat(20000) + '/*TAIL*/ }']],
  ['parse_test.repro_root_cause', ['rc', '', null, ABSENT, 0, ['a', 'b']]],
  ['verdict_stamp', ['[stage vd1]', '', null, ABSENT]],
  ['run_test_reproduce.red_output', ['', 'boom', null, ABSENT,
    'LOG-HEAD' + 'y'.repeat(2100) + 'error: cannot find symbol LOG-TAIL', 'z'.repeat(1999),
    'z'.repeat(2000), 'z'.repeat(2001)]],
];
for (const [field, values] of PROMPT_FIELDS) {
  for (const v of values) {
    // once as an ordinary non-reproduction, once through the exhausted-build route, because the two
    // assemble different halves of the prompt
    add('verdict', `prompt ${field}=${JSON.stringify(v === ABSENT ? 'ABSENT' : v).slice(0, 30)}`,
      mut(vBase(), field, v), [reply('false-positive')]);
    let eb = mut(vBase(), 'item.state', 'infra_error');
    eb = mut(eb, 'item.attempts', 3);
    eb = mut(eb, 'item.infra_reason', BUILD_FAILED);
    add('verdict', `prompt/exhausted ${field}=${JSON.stringify(v === ABSENT ? 'ABSENT' : v).slice(0, 30)}`,
      mut(eb, field, v), [reply('by-design')]);
  }
}
// can_prove / test_executed decide WHICH of the three observations the prompt reports
for (const canProve of [true, false, 0, 1, '', 'yes', null, ABSENT]) {
  for (const executed of [true, false, 0, 1, null, ABSENT]) {
    let b = mut(vBase(), 'parse_test.can_prove', canProve);
    b = mut(b, 'run_test_reproduce.red_summary.test_executed', executed);
    add('verdict', `observation prove=${String(canProve)} ran=${String(executed)}`, b,
      [reply('false-positive')]);
  }
}
add('verdict', 'observation: no red_summary at all',
  mut(vBase(), 'run_test_reproduce.red_summary', ABSENT), [reply('false-positive')]);
add('verdict', 'observation: red_summary is not an object',
  mut(vBase(), 'run_test_reproduce.red_summary', 'x'), [reply('false-positive')]);

// 4. THE REPLY, in every shape the endpoint really produces.
const REPLIES = [
  ['valid false-positive', reply('false-positive')],
  ['valid by-design', reply('by-design')],
  ['valid unprovable', reply('unprovable')],
  ['unknown kind', reply('definitely-fine')],
  ['kind missing', { replyText: '{"verdict":"v","confidence":"low"}' }],
  ['kind empty', reply('')],
  ['kind numeric', { replyText: '{"kind":7,"verdict":"v","confidence":"c"}' }],
  ['whitespace verdict', reply('false-positive', '   \n ')],
  ['nbsp verdict', reply('false-positive', ' ')],
  ['bom verdict', reply('false-positive', '﻿')],
  ['unit-separator verdict', reply('false-positive', '')],
  ['verdict with braces', reply('false-positive', 'the {@code close()} call is guarded')],
  ['fenced', { replyText: 'Sure:\n```json\n{"kind":"by-design","verdict":"deliberate","confidence":"high"}\n```\nHope that helps.' }],
  ['fenced, decoy first', { replyText: '{"kind":"false-positive","verdict":"decoy","confidence":"low"}\n```json\n{"kind":"unprovable","verdict":"real","confidence":"high"}\n```' }],
  ['truncated mid-string', { replyText: '{"kind":"by-design","verdict":"the lesson is deliberately vulner' }],
  ['truncated after key', { replyText: '{"kind":"unprovable","verdict":' }],
  ['prose only', { replyText: 'I cannot decide this one.' }],
  ['empty content', { replyText: '' }],
  ['reasoning_content only', { replyReasoning: '{"kind":"by-design","verdict":"intentional","confidence":"medium"}' }],
  ['no choices', { reply: {} }],
  ['choices empty', { reply: { choices: [] } }],
  ['choice without message', { reply: { choices: [{}] } }],
  ['content null', { reply: { choices: [{ message: { content: null } }] } }],
  ['reply null', { reply: null }],
  ['reply undefined', { replyUndefined: true }],
  ['reply is a string', { reply: 'not an object' }],
  ['throw Error', { throwError: 'connection refused' }],
  ['throw long Error', { throwError: 'x'.repeat(250) }],
  ['throw description', { throwDescription: 'The service refused the connection' }],
  ['throw nothing', { throwNothing: true }],
];
for (const [name, r] of REPLIES) {
  add('verdict', 'reply ' + name, vBase(), [r]);
  let eb = mut(vBase(), 'item.state', 'infra_error');
  eb = mut(eb, 'item.attempts', 3);
  eb = mut(eb, 'item.infra_reason', BUILD_FAILED);
  add('verdict', 'reply/exhausted ' + name, eb, [r]);
}

// 5. THE SVACE STUB: unconfigured, blank, configured, broken.
const SVACE_ENVS = [
  ['unset', {}],
  ['blank', { SVACE_BASE_URL: '  ' }],
  ['nbsp only', { SVACE_BASE_URL: ' ' }],
  ['plain', { SVACE_BASE_URL: 'http://svace' }],
  ['trailing slashes', { SVACE_BASE_URL: 'http://svace//' }],
  ['with token', { SVACE_BASE_URL: 'http://svace/', SVACE_TOKEN: 't' }],
  ['token only', { SVACE_TOKEN: 't' }],
  ['empty token', { SVACE_BASE_URL: 'http://svace', SVACE_TOKEN: '' }],
];
const SVACE_REPLIES = [
  ['message + trace', { reply: { message: 'tainted value reaches sink', trace: ['a', 'b'] } }],
  ['msg spelling', { reply: { msg: 'null deref on line 44' } }],
  ['path instead of trace', { reply: { message: 'm', path: [{ line: 4 }] } }],
  ['trace is a string', { reply: { message: 'm', trace: 'a->b' } }],
  ['empty body', { reply: '' }],
  ['null body', { reply: null }],
  ['no fields', { reply: {} }],
  ['throws', { throwError: 'svace down' }],
];
for (const [ename, e] of SVACE_ENVS) {
  for (const [rname, r] of SVACE_REPLIES) {
    add('verdict', `svace ${ename} / ${rname}`, mut(vBase(), 'env', { ...env, ...e }),
      [r, reply('false-positive')]);
  }
  for (const marker of ['m1', 'a b/c?x=1', 42, '', null, ABSENT]) {
    add('verdict', `svace ${ename} / marker=${String(marker)}`,
      mut(mut(vBase(), 'env', { ...env, ...e }), 'prep_prover.marker_id', marker),
      [{ reply: { message: 'm', trace: [] } }, reply('false-positive')]);
  }
}

// 6. HOSTILE / ABSENT ITEMS.
for (const [name, field] of [['item', 'item'], ['prep_prover', 'prep_prover'],
  ['parse_test', 'parse_test'], ['parse_fix', 'parse_fix'], ['run_test_reproduce', 'run_test_reproduce'],
  ['build_reproduce_input', 'build_reproduce_input'], ['pr_maker', 'pr_maker']]) {
  for (const v of [ABSENT, null, '', 0, 'a string', [], 42]) {
    add('verdict', `hostile ${name}=${String(v === ABSENT ? 'ABSENT' : JSON.stringify(v))}`,
      mut(vBase(), field, v), [reply('false-positive')]);
  }
}
for (const state of ['pr_ready', 'pr_rejected', 'needs_review', 'fix_failed']) {
  for (const extra of [
    { test_path: 'src/test/java/a/BTest.java', jdk: '25', pr_title: 'Fix the leak', pr_body: '⚠ x\nmore' },
    { test_path: '', jdk: '', pr_title: '', pr_body: '' },
    {},
  ]) {
    for (const score of [90, 0, '', null, ABSENT, '88']) {
      for (const prReason of ['', 'this module is vendored and patched downstream']) {
        let b = mut(vBase(), 'item', { state, attempts: 1, infra_reason: '', ...extra });
        b = mut(b, 'parse_test.test_score', score);
        b = mut(b, 'pr_maker.pr_reason', prReason);
        add('verdict', `composed ${state} score=${String(score)} reason=${prReason.slice(0, 8)}`,
          b, [reply('false-positive')]);
      }
    }
  }
}
add('verdict', 'no env at all', mut(vBase(), 'env', ABSENT), [reply('false-positive')]);
add('verdict', 'min_attempts absent', mut(vBase(), 'min_attempts', ABSENT), [reply('false-positive')]);
add('verdict', 'min_attempts as a string', mut(vBase(), 'min_attempts', '2'), [reply('false-positive')]);

// ---- fix skeptic -----------------------------------------------------------------------------

const STAMP = '[pipeline S1 (2026-07-27)]  [stage sk5 (2026-07-22)]';
const sBase = () => ({
  prep_prover: { title: 'SQL injection via ORDER BY', description: 'a column name is concatenated into the query' },
  parse_test: { test_code: 'class ServersFsmProofTest { @Test void t(){ assertFalse(sort("x").contains("DROP")); } }' },
  parse_fix: { can_fix: true, fix_edits_json: '[{"path":"Servers.java","old_str":"order by \\" + c"}]' },
  item: { proven: true },
  env: { QWEN_BASE_URL: 'http://inference-vllm:8000/v1', QWEN_API_KEY: 'tok', QWEN_MODEL: 'qwen-3.6-27b-fp8' },
  skeptic_stamp: STAMP,
});
const CUT = 20000;
const SKEPTIC_FIELDS = [
  ['skeptic_stamp', [STAMP, '', null, ABSENT]],
  ['prep_prover.title', ['SQLi', '', null, ABSENT, 0, false, 42, { a: 1 }, ['x', 'y']]],
  ['prep_prover.description', ['a column', '', null, ABSENT, 7]],
  ['parse_test.test_code', ['class T {}', '', null, ABSENT, 42, false, 'a'.repeat(CUT - 1),
    'a'.repeat(CUT), 'a'.repeat(CUT + 1), 'a'.repeat(CUT + 4321), '☃'.repeat(CUT + 2)]],
  ['parse_fix.fix_edits_json', ['[]', '', null, ABSENT, 7, 'b'.repeat(CUT), 'b'.repeat(CUT + 1),
    'b'.repeat(CUT + 4321), [1, 2]]],
];
for (const [field, values] of SKEPTIC_FIELDS) {
  for (const v of values) {
    add('skeptic', `prompt ${field}=${JSON.stringify(v === ABSENT ? 'ABSENT' : v).slice(0, 24)}`,
      mut(sBase(), field, v), [{ replyText: '{"verdict":"sound","reason":"a general whitelist"}' }]);
  }
}
add('skeptic', 'prompt: both fields cut independently',
  mut(mut(sBase(), 'parse_test.test_code', 'a'.repeat(CUT + 7)), 'parse_fix.fix_edits_json', 'b'.repeat(CUT + 9)),
  [{ replyText: '{"verdict":"sound","reason":"r"}' }]);

const SKEPTIC_REPLIES = [
  ['sound', '{"verdict":"sound","reason":"because"}'],
  ['over-fit', '{"verdict":"over-fit","reason":"special-cases the input"}'],
  ['regression-risk', '{"verdict":"regression-risk","reason":"r"}'],
  ['buried in prose', 'Let me look.\n{"verdict":"over-fit","reason":"special-cases the input"}\nDone.'],
  ['unknown word', '{"verdict":"looks-fine"}'],
  ['wrong case', '{"verdict":"SOUND"}'],
  ['no reason', '{"verdict":"sound"}'],
  ['empty reason', '{"verdict":"over-fit","reason":""}'],
  ['no verdict field', '{}'],
  ['reason only', '{"reason":"I could not tell from the diff"}'],
  ['verdict false', '{"verdict":false,"reason":"r"}'],
  ['verdict numeric', '{"verdict":7}'],
  ['prose', 'The fix looks reasonable to me.'],
  ['empty', ''],
  ['open brace', 'result: {'],
  ['close brace', 'result: }'],
  ['braces backwards', '} then {'],
  ['malformed json', '{ verdict: sound }'],
  ['two objects', '{"verdict":"sound","reason":"first"} and {"verdict":"over-fit","reason":"second"}'],
];
for (const [name, content] of SKEPTIC_REPLIES) {
  add('skeptic', 'reply ' + name, sBase(), [{ replyText: content }]);
  add('skeptic', 'reply/reasoning ' + name, sBase(), [{ replyReasoning: content }]);
}
for (const [name, r] of [
  ['no choices', { reply: {} }], ['choices empty', { reply: { choices: [] } }],
  ['choice without message', { reply: { choices: [{}] } }],
  ['content null', { reply: { choices: [{ message: { content: null } }] } }],
  ['reply null', { reply: null }], ['reply undefined', { replyUndefined: true }],
  ['reply a string', { reply: 'nope' }],
  ['throw Error', { throwError: 'ECONNREFUSED 10.0.0.4:8000' }],
  ['throw long', { throwError: 'E'.repeat(400) }],
  ['throw 150', { throwError: 'E'.repeat(150) }],
  ['throw description', { throwDescription: 'The service refused the connection' }],
  ['throw nothing', { throwNothing: true }],
]) {
  add('skeptic', 'transport ' + name, sBase(), [r]);
}
for (const proven of [true, false, 0, 1, '', 'yes', null, ABSENT]) {
  for (const canFix of [true, false, 0, 1, '', null, ABSENT]) {
    add('skeptic', `gate proven=${String(proven)} can_fix=${String(canFix)}`,
      mut(mut(sBase(), 'item.proven', proven), 'parse_fix.can_fix', canFix),
      [{ replyText: '{"verdict":"sound","reason":"r"}' }]);
  }
}
for (const [name, field] of [['prep_prover', 'prep_prover'], ['parse_test', 'parse_test'],
  ['parse_fix', 'parse_fix'], ['item', 'item']]) {
  for (const v of [ABSENT, null, '', 0, [], 'a string']) {
    add('skeptic', `hostile ${name}=${String(v === ABSENT ? 'ABSENT' : JSON.stringify(v))}`,
      mut(sBase(), field, v), [{ replyText: '{"verdict":"sound","reason":"r"}' }]);
  }
}
add('skeptic', 'the item carries a stale verdict',
  mut(sBase(), 'item', { proven: true, skeptic_verdict: 'sound', skeptic_reason: 'from the last attempt', green_passed: true }),
  [{ replyText: '{"verdict":"over-fit","reason":"special-cases the tested column"}' }]);
add('skeptic', 'no env at all', mut(sBase(), 'env', ABSENT), [{ replyText: '{"verdict":"sound","reason":"r"}' }]);

// ---- pr maker --------------------------------------------------------------------------------

const PR_STAMP = '[fsm pr v3]';
const pBase = () => ({
  prep_prover: { repo: 'WebGoat/WebGoat', file: 'src/main/java/a/S.java', title: 'SQLi in sort', description: 'column concatenated' },
  parse_test: { test_code: 'class T { void t() {} }' },
  parse_fix: { fix_root_cause: 'unvalidated column', fix_edits_json: '[{"path":"a"}]' },
  run_test_reproduce: { red_reproduced: true },
  item: { proven: true, skeptic_verdict: 'sound' },
  env: { QWEN_BASE_URL: 'http://vllm:8000/v1', QWEN_API_KEY: 'k-123', QWEN_MODEL: 'qwen-3.6' },
  pr_stamp: PR_STAMP,
});
const CURATED = 'ok {"decision":"make","reason":"production code","pr_title":"Ti","pr_body":"Bo"} done';
const PR_FIELDS = [
  ['pr_stamp', [PR_STAMP, '', null, ABSENT]],
  ['prep_prover.repo', ['WebGoat/WebGoat', '', null, ABSENT, 42]],
  ['prep_prover.file', ['a/S.java', '', null, ABSENT]],
  ['prep_prover.title', ['SQLi in sort', '', null, ABSENT, 0, { a: 1 }]],
  ['prep_prover.description', ['column concatenated', '', null, ABSENT, false]],
  ['parse_fix.fix_root_cause', ['unvalidated column', '', null, ABSENT, 7]],
  ['parse_fix.fix_edits_json', ['[{"path":"a"}]', '', null, ABSENT, 'E'.repeat(4999),
    'E'.repeat(5000), 'E'.repeat(5001), 'E'.repeat(5300), 7, true, ['x', 'y'], { a: 1 }]],
  ['parse_test.test_code', ['class T {}', '', null, ABSENT, 'T'.repeat(3999), 'T'.repeat(4000),
    'T'.repeat(4001), 'T'.repeat(4200), 42, ['a']]],
];
for (const [field, values] of PR_FIELDS) {
  for (const v of values) {
    add('prmaker', `prompt ${field}=${JSON.stringify(v === ABSENT ? 'ABSENT' : v).slice(0, 24)}`,
      mut(pBase(), field, v), [{ replyText: CURATED }]);
  }
}
const PR_REPLIES = [
  ['decision + everything', CURATED],
  ['bare object', '{"decision":"make","reason":"prod code"}'],
  ['no decision', '{"reason":"looks worth proposing"}'],
  ['no reason', '{"decision":"reject"}'],
  ['non-strings', '{"decision":7,"reason":false,"pr_title":42,"pr_body":true}'],
  ['empty title and body', '{"decision":"make","reason":"r","pr_title":"","pr_body":""}'],
  ['title only', '{"decision":"make","reason":"r","pr_title":"New"}'],
  ['body only', '{"decision":"make","reason":"r","pr_body":"New body"}'],
  ['prose, no braces', 'I am not able to decide this one.'],
  ['braces backwards', '} nothing useful {'],
  ['cut off mid-object', '{"decision":"make"'],
  ['stray closing brace', 'I would reject this; the fix only adds a } to the block.'],
  ['empty', ''],
  ['malformed', 'here you go {"decision": make} done'],
];
for (const [name, content] of PR_REPLIES) {
  add('prmaker', 'reply ' + name, pBase(), [{ replyText: content }]);
  add('prmaker', 'reply/reasoning ' + name, pBase(), [{ replyReasoning: content }]);
}
for (const [name, r] of [
  ['no choices', { reply: {} }], ['choices empty', { reply: { choices: [] } }],
  ['choice without message', { reply: { choices: [{}] } }],
  ['reply null', { reply: null }], ['reply undefined', { replyUndefined: true }],
  ['throw Error', { throwError: 'ECONNREFUSED vllm:8000' }],
  ['throw long', { throwError: 'z'.repeat(400) }], ['throw 150', { throwError: 'z'.repeat(150) }],
  ['throw description', { throwDescription: 'The service refused the connection' }],
  ['throw nothing', { throwNothing: true }],
]) {
  add('prmaker', 'transport ' + name, pBase(), [r]);
}
for (const red of [true, false, 0, 1, null, ABSENT]) {
  for (const proven of [true, false, 0, null, ABSENT]) {
    for (const skeptic of ['sound', 'over-fit', 'unknown', 'not-run', '', null, ABSENT]) {
      let b = mut(pBase(), 'run_test_reproduce.red_reproduced', red);
      b = mut(b, 'item.proven', proven);
      b = mut(b, 'item.skeptic_verdict', skeptic);
      add('prmaker', `gate red=${String(red)} proven=${String(proven)} skeptic=${String(skeptic)}`,
        b, [{ replyText: CURATED }]);
    }
  }
}
for (const [name, field] of [['prep_prover', 'prep_prover'], ['parse_test', 'parse_test'],
  ['parse_fix', 'parse_fix'], ['run_test_reproduce', 'run_test_reproduce'], ['item', 'item']]) {
  for (const v of [ABSENT, null, '', 0, [], 'a string']) {
    add('prmaker', `hostile ${name}=${String(v === ABSENT ? 'ABSENT' : JSON.stringify(v))}`,
      mut(pBase(), field, v), [{ replyText: CURATED }]);
  }
}
for (const [title, body] of [['PT', 'PB'], ['', ''], [null, null], [0, 0]]) {
  let b = mut(pBase(), 'parse_fix.pr_title', title);
  b = mut(b, 'parse_fix.pr_body', body);
  add('prmaker', `fallback title=${String(title)} body=${String(body)}`, b,
    [{ throwError: 'ECONNREFUSED vllm:8000' }]);
  add('prmaker', `fallback/skipped title=${String(title)} body=${String(body)}`,
    mut(b, 'item.proven', false), [{ replyText: CURATED }]);
}
add('prmaker', 'stale pr_ fields on the item',
  mut(pBase(), 'item', { proven: false, pr_decision: 'make', pr_curated: true, pr_reason: 'stale', pr_title: 'stale', pr_body: 'stale' }),
  [{ replyText: CURATED }]);

// ---------------------------------------------------------------------------------------------
// running the JS
// ---------------------------------------------------------------------------------------------

/** The stubbed endpoint: one scripted answer per call, the last one repeating. */
function respond(script, i) {
  if (!script || script.length === 0) return undefined;
  const s = script[Math.min(i, script.length - 1)];
  if ('throwError' in s) throw new Error(s.throwError);
  if ('throwDescription' in s) throw { description: s.throwDescription };
  if ('throwNothing' in s) throw null;
  if ('replyText' in s) return { choices: [{ message: { content: s.replyText } }] };
  if ('replyReasoning' in s) return { choices: [{ message: { content: '', reasoning_content: s.replyReasoning } }] };
  if ('replyUndefined' in s) return undefined;
  return s.reply;
}

/** "" is not 0 is not null is not undefined — four results, four tags. */
function tag(v) {
  if (v === undefined) return 'u';
  if (v === null) return 'z';
  const t = typeof v;
  if (t === 'string') return 's:' + v;
  if (t === 'number') return 'n:' + String(v);
  if (t === 'boolean') return 'b:' + String(v);
  if (Array.isArray(v)) return ['a'].concat(v.map(tag));
  if (t === 'object') return ['o'].concat(Object.keys(v).map((k) => [k, tag(v[k])]));
  return 'x:' + t;
}

async function runOne(c) {
  const b = c.body;
  const calls = [];
  const logs = [];
  let i = 0;
  const helpers = { httpRequest: async (o) => { calls.push(o); return respond(c.http, i++); } };
  const nodes = {
    'Prep prover': b.prep_prover, 'Parse test': b.parse_test, 'Parse fix': b.parse_fix,
    'run_test reproduce': b.run_test_reproduce, 'Build reproduce input': b.build_reproduce_input,
    'PR maker': b.pr_maker,
  };
  const $ = (n) => ({ item: { json: nodes[n] } });
  const realLog = console.log;
  console.log = (...a) => logs.push(a.join(' '));
  let out;
  let threw = null;
  try {
    if (c.node === 'verdict') {
      out = await verdict({ $, $json: b.item, $env: b.env, helpers, extractJson, execVerdict,
        minAttempts: b.min_attempts, verdictStamp: b.verdict_stamp });
    } else if (c.node === 'skeptic') {
      out = await fixSkeptic({ $, $json: b.item, $env: b.env, helpers, skepticStamp: b.skeptic_stamp });
    } else {
      out = await prMaker({ $, $json: b.item, $env: b.env, helpers, prStamp: b.pr_stamp });
    }
  } catch (e) {
    threw = (e && e.constructor && e.constructor.name) || String(e);
    // A node that threw produced no item at all. Java cannot spell `undefined`, so both sides say
    // "nothing" the same way here — the THROW is the difference worth reporting, not the encoding of
    // the result that never existed.
    out = null;
  } finally {
    console.log = realLog;
  }
  return { id: c.id, calls: tag(calls), logs: tag(logs), out: tag(out), threw: tag(threw) };
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  // ABSENT is a Symbol, so JSON.stringify already drops those keys — which is exactly what "the key
  // is not there" has to mean on the Java side too.
  fs.writeFileSync(path.join(OUT, 'cases.json'), JSON.stringify(cases));
  const results = [];
  for (const c of cases) results.push(await runOne(c));
  fs.writeFileSync(path.join(OUT, 'js-results.json'), JSON.stringify(results));
  console.error(`js-side: ${cases.length} cases`);
})();
