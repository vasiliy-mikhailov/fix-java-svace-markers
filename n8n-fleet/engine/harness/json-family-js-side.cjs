"use strict";
/**
 * Differential harness, JS side — the JSON/reply-parsing family (json-extract, parse-test, parse-fix).
 *
 * Generates the cases AND runs the ORIGINAL JavaScript over them, so both sides read the same bytes.
 * Systematic, not sampled: every branch of the three modules, plus hostile, absent and wrong-typed
 * fields — and the truncation family is EXHAUSTIVE. For four representative replies EVERY prefix
 * length is a case, because the repair path is the subtlest code in the port and a hand-picked cut
 * only ever finds the bugs you already thought of.
 *
 * Cases are plain JSON. JSON cannot carry `undefined`, which is exactly right: an absent key is
 * expressed by omitting it and an explicit null by writing null, and those are the two things the
 * port has to keep apart.
 *
 * Results are TYPE-TAGGED, in the WIRE form the next stage actually receives: "" and 0 and null and
 * an absent key are four different results, an undefined member is DROPPED as JSON.stringify drops
 * it, and a non-finite number renders as null for the same reason. Key order is preserved, because
 * the Data Table columns follow it.
 *
 *   node harness/json-family-js-side.cjs
 */
const fs = require("fs");
const path = require("path");
const OUT = path.join(__dirname, "out");


const KEYS = ['can_prove', 'test_code', 'root_cause', 'value_verdict'];
const FIX_KEYS = ['can_fix', 'fix_edits', 'root_cause', 'pr_title'];
const SRC = 'src/main/java/a/B.java';
const TEST_PATH = 'src/test/java/a/BFsmProofTest.java';

const cases = [];
let id = 0;
const add = (c) => { cases.push(Object.assign({ id: ++id }, c)); };
const extract = (text, keys, note) => add({ suite: 'extract', text, keys, note });

/* ---------- extract: payloads ---------------------------------------------------------------- */

const JAVA = 'package a;\nclass T {\n  @Test void t() {\n    assertEquals("{", "{");\n'
  + '    String p = "C:\\\\tmp";\n  }\n}';

const payloads = [
  { k: KEYS, o: { can_prove: true, root_cause: 'leak' } },
  { k: KEYS, o: { summary: 'x', can_prove: false, root_cause: 'decoy' } },
  { k: FIX_KEYS, o: { can_fix: true, fix_edits: [{ path: SRC, old_str: 'if (x) {\n  y();\n}', new_str: 'if (x != null) {\n  y();\n}' }], root_cause: 'npe', pr_title: 'Guard', pr_body: 'Body with {braces}.' } },
  { k: KEYS, o: { can_prove: true, test_code: JAVA } },
  { k: KEYS, o: { unrelated: 1, other: [1, 2, { a: null }] } },
  { k: KEYS, o: { can_prove: true, evidence: { line: 42, tags: ['a', 'b'] }, root_cause: null } },
  { k: KEYS, o: { can_prove: true, n: -1.5e10, z: 0, s: '', root_cause: 'e' } },
  { k: FIX_KEYS, o: { can_fix: false, fix_edits: [], pr_title: 'none' } },
];

/* ---------- extract: wrappers ---------------------------------------------------------------- */

const wrappers = [
  ['bare', (s) => s],
  ['prose before', (s) => 'Here is my answer:\n' + s],
  ['prose after', (s) => s + '\nHope that helps.'],
  ['prose both', (s) => 'Sure!\n' + s + '\nLet me know.'],
  ['json fence', (s) => '```json\n' + s + '\n```'],
  ['JSON fence upper', (s) => '```JSON\n' + s + '\n```'],
  ['bare fence', (s) => '```\n' + s + '\n```'],
  ['fence one line', (s) => 'Answer: ```json' + s + '```'],
  ['fence + prose', (s) => 'I will do it.\n```json\n' + s + '\n```\nDone.'],
  ['fence then java fence', (s) => '```json\n' + s + '\n```\n```java\nclass T {}\n```'],
  ['java fence then fence', (s) => '```java\nclass T {}\n```\n```json\n' + s + '\n```'],
  ['javadoc before', (s) => 'The method {@code close()} is never called:\n' + s],
  ['javadoc after', (s) => s + '\nsee {@code m0()} and {@code m1()}'],
  ['nbsp fence', (s) => '```json\u00a0' + s + '\n```'],
  ['decoy before', (s) => '{"summary":"d","can_prove":true,"root_cause":"decoy"}\nreal:\n' + s],
  ['decoy after', (s) => s + '\n(for reference: {"summary":"d","can_prove":false,"root_cause":"decoy"})'],
  ['two fences', (s) => '```json\n{"can_prove":true,"root_cause":"example"}\n```\nactually:\n```json\n' + s + '\n```'],
  ['unterminated fence', (s) => '```json\n' + s],
  ['inside a list', (s) => '[' + s + ']'],
];

for (const p of payloads) {
  for (const pretty of [false, true]) {
    const s = pretty ? JSON.stringify(p.o, null, 2) : JSON.stringify(p.o);
    for (const [name, wrap] of wrappers) {
      extract(wrap(s), p.k, 'wrapper=' + name + ' pretty=' + pretty);
    }
  }
}

/* ---------- extract: EVERY prefix of four replies (the repair path) --------------------------- */

const truncated = [
  { k: KEYS, s: JSON.stringify({ can_prove: true, root_cause: 'the stream is never closed' }) },
  { k: FIX_KEYS, s: JSON.stringify({ can_fix: true, fix_edits: [{ path: 'A.java', old_str: 'x', new_str: 'assertEquals("expected", y)' }], pr_title: 'Guard against null' }) },
  { k: KEYS, s: JSON.stringify({ can_prove: true, test_code: 'class T { void t() { assertEquals("{", "\\\\"); } }', evidence: { line: 42 } }) },
  { k: KEYS, s: '{\n  "can_prove": true,\n  "root_cause": "x",\n  "value_verdict": "real"\n}' },
];
for (const t of truncated) {
  for (let n = 1; n <= t.s.length; n++) {
    extract(t.s.slice(0, n), t.k, 'prefix ' + n + '/' + t.s.length);
  }
  // the same prefixes inside a fence, where the trailing newline belongs to the fence
  for (let n = 1; n <= t.s.length; n += 7) {
    extract('```json\n' + t.s.slice(0, n) + '\n```', t.k, 'fenced prefix ' + n);
  }
}

/* ---------- extract: backslash runs at the cut ------------------------------------------------ */

for (let n = 0; n <= 8; n++) {
  extract('{"can_prove":true,"root_cause":"path C:' + '\\'.repeat(n), KEYS, 'trailing backslashes ' + n);
  extract('{"can_prove":true,"root_cause":"a\\\\b' + '\\'.repeat(n) + '"', KEYS, 'closed, backslashes ' + n);
}

/* ---------- extract: delimiter storms and the bounds ------------------------------------------ */

const ANSWER = '{"can_prove":true,"root_cause":"x"}';
for (const n of [0, 1, 2, 3, 39, 40, 41, 398, 399, 400, 401]) {
  extract(ANSWER + '}'.repeat(n), KEYS, 'stray closers ' + n);
  extract(ANSWER + ']'.repeat(n), KEYS, 'stray brackets ' + n);
}
for (const n of [1, 2, 40, 41, 200]) {
  extract('{'.repeat(n), KEYS, 'opens ' + n);
  extract('}'.repeat(n), KEYS, 'closes ' + n);
  extract('['.repeat(n) + ANSWER, KEYS, 'nested opens ' + n);
}

const codeRefs = (n) => Array.from({ length: n }, (_, i) => 'see {@code m' + i + '()}').join(' ');
for (const before of [0, 1, 39, 40, 41, 60]) {
  for (const after of [0, 1, 39, 40, 45, 60]) {
    extract(codeRefs(before) + '\n{"summary":"x","can_prove":true,"root_cause":"pos"}\n' + codeRefs(after),
      KEYS, 'positional ' + before + '/' + after);
    extract(codeRefs(before) + '\n{"can_prove":true,"summary":"x","root_cause":"anchored"}\n' + codeRefs(after),
      KEYS, 'anchored ' + before + '/' + after);
  }
}

/* ---------- extract: degenerate and hostile --------------------------------------------------- */

const degenerate = ['', ' ', '   \n  ', '\u00a0', '\ufeff', '\u001c', 'no json here at all', 'null',
  'true', '42', '[1,2,3]', '[]', '{}', '{', '}', '{}{}', '{"a":1,,}', '{"a":}', '{"a"}',
  '{"can_prove":tru}', '{"can_prove":true,}', '{"can_prove" : true}',
  '{\u00a0"can_prove":true,"root_cause":"nbsp before key"}',
  '{"can_prove"\u00a0:\u00a0true,"root_cause":"nbsp around colon"}',
  '{"can_prove":true,"root_cause":"x"}\u00a0,\u00a0', '```json\n{\n```', '```json\n42\n```',
  '```json\n[1,2]\n```', '```json\n"a string"\n```', '```json\nnull\n```', '```json\n0\n```',
  '```json\nfalse\n```', '```json\n""\n```', '```\n```', '``````', '```json```',
  '{"a":"' + '\\'.repeat(50), '{"can_prove":true,"root_cause":"\u00e9\u00fc\ud83d\ude00"}',
  '{"can_prove":true,"root_cause":"tab\\there"}', '{"can_prove":true,"root_cause":"\\u0041"}',
  '{"CAN_PROVE":true}', '{"can_prove_x":true}', '{"xcan_prove":true}',
  '{"can_prove":true}{"can_prove":false,"root_cause":"second"}',
  'prefix {"can_prove":true,"root_cause":"a"} middle {"can_prove":false,"root_cause":"b"} suffix'];
for (const d of degenerate) {
  extract(d, KEYS, 'degenerate');
  extract(d, FIX_KEYS, 'degenerate/fixkeys');
}

/* ---------- parse-test ------------------------------------------------------------------------ */

const REAL_TEST = 'class BFsmProofTest {\n  @Test void t() {\n    B b = new B();\n'
  + '    assertEquals(1, b.attack("x"));\n  }\n}';
const MOCKED = 'class BFsmProofTest {\n  @Mock B subject;\n  @Test void t() {\n'
  + '    when(subject.attack("x")).thenReturn(1);\n    assertEquals(1, subject.attack("x"));\n  }\n}';

const PREP_FULL = { repo: 'o/r', branch: 'v5', module: 'm', class_name: 'B', test_class: 'BFsmProofTest',
  marker_id: 'm1', test_path: TEST_PATH, file: SRC, prove_attempts: 2 };
const without = (o, k) => { const c = { ...o }; delete c[k]; return c; };

const preps = [
  ['full', PREP_FULL],
  ['no module', without(PREP_FULL, 'module')],
  ['null module', { ...PREP_FULL, module: null }],
  ['empty module', { ...PREP_FULL, module: '' }],
  ['no class_name', without(PREP_FULL, 'class_name')],
  ['null class_name', { ...PREP_FULL, class_name: null }],
  ['numeric class_name', { ...PREP_FULL, class_name: 42 }],
  ['class_name with regex chars', { ...PREP_FULL, class_name: 'B$1.*' }],
  ['numeric repo', { ...PREP_FULL, repo: 7 }],
  ['false branch', { ...PREP_FULL, branch: false }],
  ['no test_path', without(PREP_FULL, 'test_path')],
  ['empty', {}],
];

const replies = [
  ['proves it', { can_prove: true, test_code: REAL_TEST, root_cause: 'rc', value_verdict: 'vv' }],
  ['proves with a mocked subject', { can_prove: true, test_code: MOCKED, root_cause: 'rc' }],
  ['declines', { can_prove: false, root_cause: 'no sink', value_verdict: 'marker is wrong' }],
  ['true but no test_code', { can_prove: true, root_cause: 'rc' }],
  ['true with empty test_code', { can_prove: true, test_code: '' }],
  ['true with null test_code', { can_prove: true, test_code: null }],
  ['true with numeric test_code', { can_prove: true, test_code: 42 }],
  ['true with object test_code', { can_prove: true, test_code: { a: 1 } }],
  ['true with array test_code', { can_prove: true, test_code: [1, 2] }],
  ['true with false test_code', { can_prove: true, test_code: false }],
  ['can_prove as a string', { can_prove: 'true', test_code: REAL_TEST }],
  ['can_prove as 1', { can_prove: 1, test_code: REAL_TEST }],
  ['can_prove null', { can_prove: null, test_code: REAL_TEST }],
  ['no can_prove', { test_code: REAL_TEST, root_cause: 'rc' }],
  ['null root_cause', { can_prove: false, root_cause: null, value_verdict: null }],
  ['numeric verdicts', { can_prove: false, root_cause: 7, value_verdict: 0 }],
  ['array verdicts', { can_prove: false, root_cause: ['a', 'b'] }],
  ['object verdict', { can_prove: false, value_verdict: { k: 1 } }],
];

for (const [pn, prep] of preps) {
  for (const [rn, reply] of replies) {
    add({ suite: 'parse_test', prep, reproducer: { output: JSON.stringify(reply) },
      note: pn + ' / ' + rn });
  }
}
const rawOutputs = [
  ['absent', undefined], ['empty', ''], ['spaces', '   \n '], ['nbsp only', '\u00a0'],
  ['bom only', '\ufeff'], ['prose', 'I could not reach the sink.'],
  ['fenced', '```json\n{"can_prove":true,"test_code":"class T {}"}\n```'],
  ['fenced + prose', 'Here:\n```json\n{"can_prove":false,"root_cause":"rc"}\n```\nok'],
  ['truncated', '{"can_prove":true,"test_code":"class T { void t() {'],
  ['javadoc decoy', 'The {@code close()} call:\n{"can_prove":true,"test_code":"class T {}"}'],
  ['two objects', '{"can_prove":true,"root_cause":"a"}\n{"can_prove":false,"root_cause":"b"}'],
  ['not an object', '[1,2,3]'], ['null literal', 'null'], ['bare number', '42'],
  ['unrepresentable number', '{"can_prove":true,"test_code":1e400,"root_cause":-1e999}'],
  ['unrepresentable nested', '{"can_prove":true,"evidence":{"n":1e400},"test_code":"class T {}"}'],
];
for (const [rn, out] of rawOutputs) {
  const item = out === undefined ? {} : { output: out };
  add({ suite: 'parse_test', prep: PREP_FULL, reproducer: item, note: 'raw/' + rn });
}
for (const [name, out] of [['numeric output', 42], ['object output', { a: 1 }],
  ['array output', ['{"can_prove":true}']], ['null output', null], ['false output', false],
  ['true output', true]]) {
  add({ suite: 'parse_test', prep: PREP_FULL, reproducer: { output: out }, note: 'wrongtype/' + name });
}

/* ---------- parse-fix ------------------------------------------------------------------------- */

const EDIT = { path: SRC, old_str: 'if (x)', new_str: 'if (x != null)' };
const paths = [SRC, './' + SRC, '/' + SRC, '//' + SRC, './/' + SRC, '.' + SRC, SRC + ' ', ' ' + SRC,
  'a/../' + SRC, '../../../etc/passwd', TEST_PATH, 'pom.xml', 'src/main/java/a/Other.java',
  'src/main/resources/application.properties', '', null, 42, false, true, ['a'], { p: 1 },
  SRC.toUpperCase()];

const fixReplies = [
  ['clean fix', { can_fix: true, fix_edits: [EDIT], root_cause: 'npe', pr_title: 'T', pr_body: 'B' }],
  ['declines', { can_fix: false, root_cause: 'cannot without touching the test' }],
  ['true no edits', { can_fix: true, fix_edits: [] }],
  ['true edits not a list', { can_fix: true, fix_edits: 'see above' }],
  ['true edits null', { can_fix: true, fix_edits: null }],
  ['true edits object', { can_fix: true, fix_edits: { path: SRC } }],
  ['no can_fix', { fix_edits: [EDIT] }],
  ['can_fix string', { can_fix: 'true', fix_edits: [EDIT] }],
  ['can_fix 1', { can_fix: 1, fix_edits: [EDIT] }],
  ['can_fix null', { can_fix: null, fix_edits: [EDIT] }],
  ['mixed legal and test', { can_fix: true, fix_edits: [EDIT, { path: TEST_PATH, old_str: 'assertTrue', new_str: 'assertFalse' }] }],
  ['two legal', { can_fix: true, fix_edits: [EDIT, { path: './' + SRC, old_str: 'a', new_str: 'b' }] }],
  ['three rejects', { can_fix: true, fix_edits: [{ path: TEST_PATH }, { path: 'pom.xml' }, { path: '../x' }] }],
  ['edit with extra members', { can_fix: true, fix_edits: [{ path: SRC, old_str: 'a', new_str: 'b', cmd: 'rm -rf /', file: 'x' }] }],
  ['edit missing strings', { can_fix: true, fix_edits: [{ path: SRC }] }],
  ['edit null strings', { can_fix: true, fix_edits: [{ path: SRC, old_str: null, new_str: null }] }],
  ['edit numeric strings', { can_fix: true, fix_edits: [{ path: SRC, old_str: 1, new_str: 2 }] }],
  ['edit object strings', { can_fix: true, fix_edits: [{ path: SRC, old_str: { a: 1 }, new_str: ['b'] }] }],
  ['edit is null', { can_fix: true, fix_edits: [null] }],
  ['edit is a string', { can_fix: true, fix_edits: ['src/main/java/a/B.java'] }],
  ['edit is a number', { can_fix: true, fix_edits: [7] }],
  ['edit is a list', { can_fix: true, fix_edits: [[EDIT]] }],
  ['pr fields', { can_fix: true, fix_edits: [EDIT], pr_title: 'T', pr_body: 'B', root_cause: 'rc' }],
  ['pr fields null', { can_fix: true, fix_edits: [EDIT], pr_title: null, pr_body: null, root_cause: null }],
  ['pr fields numeric', { can_fix: true, fix_edits: [EDIT], pr_title: 7, pr_body: 0 }],
  ['edit with no path', { can_fix: true, fix_edits: [{ old_str: 'a', new_str: 'b' }] }],
];
for (const p of paths) {
  fixReplies.push(['path ' + JSON.stringify(p),
    { can_fix: true, fix_edits: [{ path: p, old_str: 'a', new_str: 'b' }] }]);
}

const fixPreps = [
  ['full', { file: SRC, test_path: TEST_PATH, module: '', repo: 'o/r', branch: 'main', test_class: 'BFsmProofTest' }],
  ['no file', { test_path: TEST_PATH, repo: 'o/r', branch: 'main' }],
  ['null file', { file: null, test_path: TEST_PATH }],
  ['false file', { file: false, test_path: TEST_PATH }],
  ['numeric file', { file: 7, test_path: TEST_PATH }],
  ['dotted file', { file: './' + SRC, test_path: TEST_PATH }],
  ['slashed file', { file: '/' + SRC, test_path: TEST_PATH }],
  ['no test_path', { file: SRC }],
  ['null test_path', { file: SRC, test_path: null }],
  ['numeric test_path', { file: SRC, test_path: 7 }],
  ['empty', {}],
];
const reproItems = [['25', { jdk: '25' }], ['numeric 25', { jdk: 25 }], ['absent', {}],
  ['null', { jdk: null }], ['empty', { jdk: '' }], ['zero', { jdk: 0 }], ['false', { jdk: false }]];
const parseTestItems = [['normal', { test_code: 'class BFsmProofTest {}' }], ['absent', {}],
  ['null', { test_code: null }], ['numeric', { test_code: 42 }], ['object', { test_code: { a: 1 } }],
  ['false', { test_code: false }]];

for (const [rn, reply] of fixReplies) {
  add({ suite: 'parse_fix', prep: fixPreps[0][1], parse_test: parseTestItems[0][1],
    repro: reproItems[0][1], fixer: { output: JSON.stringify(reply) }, note: 'reply/' + rn });
}
for (const [pn, prep] of fixPreps) {
  for (const [rn, reply] of [fixReplies[0], fixReplies[1], fixReplies[10], fixReplies[25]]) {
    add({ suite: 'parse_fix', prep, parse_test: parseTestItems[0][1], repro: reproItems[0][1],
      fixer: { output: JSON.stringify(reply) }, note: 'prep/' + pn + ' / ' + rn });
  }
}
for (const [jn, repro] of reproItems) {
  for (const [tn, pt] of parseTestItems) {
    add({ suite: 'parse_fix', prep: fixPreps[0][1], parse_test: pt, repro,
      fixer: { output: JSON.stringify({ can_fix: true, fix_edits: [EDIT] }) },
      note: 'jdk=' + jn + ' testcode=' + tn });
  }
}
for (const [rn, out] of rawOutputs) {
  const item = out === undefined ? {} : { output: out };
  add({ suite: 'parse_fix', prep: fixPreps[0][1], parse_test: parseTestItems[0][1],
    repro: reproItems[0][1], fixer: item, note: 'raw/' + rn });
}
// Numbers JSON can express and JSON.stringify cannot write back: 1e400 parses to Infinity. These
// have to be written as literal reply TEXT, because JSON.stringify would have turned them into null
// while building the case file — which is the very behaviour under test.
for (const [name, out] of [['numeric', 42], ['object', { a: 1 }], ['array', ['x']], ['null', null],
  ['false', false], ['fenced fix', '```json\n{"can_fix":true,"fix_edits":[{"path":"' + SRC + '"}]}\n```'],
  ['truncated fix', '{"can_fix":true,"fix_edits":[{"path":"' + SRC + '","old_str":"a'],
  ['unrepresentable number', '{"can_fix":true,"fix_edits":[{"path":"' + SRC
    + '","old_str":1e400,"new_str":{"n":-1e400}}]}'],
  ['unrepresentable in a list', '{"can_fix":true,"fix_edits":[{"path":"' + SRC
    + '","old_str":[1,1e999]}]}'],
  ['unrepresentable pr_title', '{"can_fix":true,"pr_title":1e400,"fix_edits":[{"path":"'
    + SRC + '"}]}'],
  ['prose', 'The bug is in the constructor.']]) {
  add({ suite: 'parse_fix', prep: fixPreps[0][1], parse_test: parseTestItems[0][1],
    repro: reproItems[0][1], fixer: { output: out }, note: 'wrongtype/' + name });
}


fs.mkdirSync(OUT, { recursive: true });
fs.writeFileSync(path.join(OUT, "json-family-cases.json"), JSON.stringify(cases));
const bySuite = {};
for (const c of cases) bySuite[c.suite] = (bySuite[c.suite] || 0) + 1;
console.log("cases: " + cases.length + " " + JSON.stringify(bySuite));


const AG = '/Users/vmihaylov/projects/fix-java-svace-markers/n8n-fleet/n8n/agentic';
const { extractJson } = require(path.join(AG, 'src/lib/json-extract'));
const { testRealness } = require(path.join(AG, 'src/lib/test-realness'));
const { parseTest } = require(path.join(AG, 'src/nodes/parse-test'));
const { parseFix } = require(path.join(AG, 'src/nodes/parse-fix'));

// A non-finite number has no JSON spelling, and JSON.stringify writes null for it. The comparison
// is over the WIRE form — what the runner and the Data Table actually receive — so it renders one
// the same way, exactly as it drops an undefined member.
function numTag(n) {
  return Number.isFinite(n) ? 'num:' + String(n) : 'null';
}

function tag(v) {
  if (v === null) return 'null';
  switch (typeof v) {
    case 'undefined': return 'undefined';
    case 'boolean': return 'bool:' + v;
    case 'number': return numTag(v);
    case 'string': return 'str:' + JSON.stringify(v);
    case 'object': break;
    default: return 'other:' + typeof v;
  }
  if (Array.isArray(v)) return '[' + v.map((e) => (e === undefined ? 'null' : tag(e))).join(',') + ']';
  const parts = [];
  for (const k of Object.keys(v)) {
    if (v[k] !== undefined) parts.push(JSON.stringify(k) + ':' + tag(v[k]));
  }
  return '{' + parts.join(',') + '}';
}

const item = (json) => ({ item: { json } });

async function runCase(c) {
  if (c.suite === 'extract') {
    return { out: tag(extractJson(c.text, c.keys)) };
  }
  if (c.suite === 'parse_test') {
    const logs = [];
    const real = console.log;
    console.log = (...a) => logs.push(a.join(' '));
    try {
      const out = await parseTest({
        $: (n) => { if (n !== 'Prep prover') throw new Error('no fixture for ' + n); return item(c.prep); },
        $json: c.reproducer, extractJson, testRealness,
      });
      return { out: tag(out), log: logs.join('\n') };
    } finally {
      console.log = real;
    }
  }
  const nodes = { 'Prep prover': c.prep, 'Parse test': c.parse_test, 'run_test reproduce': c.repro };
  const out = await parseFix({
    $: (n) => item(nodes[n]),
    $json: c.fixer, extractJson,
  });
  return { out: tag(out) };
}

(async () => {
  const results = [];
  for (const c of cases) {
    try {
      const r = await runCase(c);
      results.push({ id: c.id, ...r });
    } catch (e) {
      // A throw is a RESULT, not a harness failure: the JS crashing where the Java does not is a
      // divergence worth reporting, and in n8n it is the node going red.
      results.push({ id: c.id, threw: e.constructor.name + ': ' + e.message });
    }
  }
  fs.writeFileSync(path.join(OUT, "json-family-js-results.json"), JSON.stringify(results));
  console.log('js results: ' + results.length + ', threw: '
    + results.filter((r) => r.threw).length);
})();
