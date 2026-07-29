'use strict';
/**
 * Differential harness, JS side — the input-building family (prep prover, build reproduce input,
 * build fix input).
 *
 * Generates the cases, runs the ORIGINAL JavaScript through them, and writes both files. The Java
 * driver reads the same cases.json and writes its own results; compare.cjs diffs the two.
 *
 * Cases are plain JSON, which cannot carry `undefined` — and that is exactly right here. Over the
 * engine's HTTP transport an absent key IS undefined, so "field omitted" is the faithful way to
 * express it and both sides read the same bytes.
 *
 * Every value is written TYPE-TAGGED ("" is not 0 is not null is not undefined), because the whole
 * point of the exercise is to catch the coercion that looks right and is not.
 *
 *   node harness/input-family-js-side.cjs   # -> harness/out/input-family-{cases,js-results}.json
 */
const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, '..', '..', 'n8n', 'agentic', 'src', 'nodes');
const { prepProver } = require(path.join(SRC, 'prep-prover.js'));
const { buildReproduceInput } = require(path.join(SRC, 'build-reproduce-input.js'));
const { buildFixInput } = require(path.join(SRC, 'build-fix-input.js'));

const OUT = path.join(__dirname, 'out');

const cases = [];
let n = 0;
const add = (node, input, note) => cases.push({ id: node + '#' + (n++) + ': ' + note, node, input });

// ---------------------------------------------------------------------------------------------
// prep prover
// ---------------------------------------------------------------------------------------------

const FILES = [
  'src/main/java/org/owasp/webgoat/lessons/challenges/challenge5/Assignment5.java',
  'webgoat-container/src/main/java/org/owasp/webgoat/container/Foo.java',
  'core/legacy/src/main/java/org/a/Foo.java',
  'core//src/main/java/org/a/Foo.java',
  'core///src/main/java/org/a/Foo.java',
  'src/main/java/Root.java',
  'src/main/java/a/B.java',
  'src/main/java//a/B.java',
  'Weird.java',
  'legacy/src/java/org/Weird.java',
  '/src/main/java/a/B.java',                       // THE REGRESSION SHAPE: a leading slash
  './src/main/java/a/B.java',
  'src/main/java/',
  'src/main/java/a/',
  'src/main/java',
  'a/src/main/java/b/src/main/java/C.java',        // the separator twice
  'src/main/java/a/Odd-Name.java',
  'src/main/java/a/Ünicode.java',
  'src/main/java/x/Widget.javadoc.java',           // replace('.java','') is FIRST-occurrence only
  'x.java.java',
  'src/main/java/a/b.JAVA',
  'src/main/java/a/.java',
  'src/main/java/a/B.java/',
  '',
  'src/main/java/a/B$Inner.java',
  'SRC/MAIN/JAVA/a/B.java',
  0, null, 42, true, false, ['src/main/java/a/B.java'], { a: 1 }, 2.5,
];
const CLASS_NAMES = ['', 'B', 'Odd-Name', '123', 0, 5, null, true, false, {}, ['x'], '   ', '_$x'];
// The whitespace values are the trap. JS trim strips U+00A0 and U+FEFF and Java's strip() does not,
// while Java's strip() removes U+001C..U+001F and JS does not. A branch that trims to nothing must
// trigger the lookup; one that does not goes straight into a raw.githubusercontent URL that 404s,
// and every marker in that repo is then recorded as a false positive.
const BRANCHES = ['main', '', '   ', '\u00a0', '\ufeff', '  develop\n', '\u001c', '\u200b',
  '\u2007', '\u202f', '\u3000', '\u1680', '\u2028', '\u0085', '\u0000',
  null, 0, false, 42, true, ['develop'], { a: 1 }, '\t\tmaster\t', 'v5-master'];
const LOOKUPS = [
  { mode: 'body', body: { default_branch: 'v5-master' } },
  { mode: 'body', body: { id: 7 } },
  { mode: 'body', body: null },
  { mode: 'body', body: 'a string body' },
  { mode: 'body', body: { default_branch: '' } },
  { mode: 'body', body: { default_branch: 0 } },
  { mode: 'body', body: { default_branch: 5 } },
  { mode: 'body', body: { default_branch: true } },
  { mode: 'body', body: { default_branch: false } },
  { mode: 'body', body: { default_branch: null } },
  { mode: 'body', body: { default_branch: ['x'] } },
  { mode: 'body', body: { default_branch: { a: 1 } } },
  { mode: 'body', body: { default_branch: '  padded  ' } },
  { mode: 'body', body: [] },
  { mode: 'reject', kind: 'error', message: 'no network' },
  { mode: 'reject', kind: 'error', message: 'Ex' + 'y'.repeat(400) },
  { mode: 'reject', kind: 'error', message: '' },
  { mode: 'reject', kind: 'value', value: { description: '404 - Not Found' } },
  { mode: 'reject', kind: 'value', value: { statusCode: 502 } },
  { mode: 'reject', kind: 'value', value: { message: '', description: 'fallback used' } },
  { mode: 'reject', kind: 'value', value: { message: 0, description: 'zero is falsy' } },
  { mode: 'reject', kind: 'value', value: { message: false, description: '' } },
  { mode: 'reject', kind: 'value', value: { message: 5 } },
  { mode: 'reject', kind: 'value', value: { message: { a: 1 } } },
  { mode: 'reject', kind: 'value', value: { message: ['a', 'b'] } },
  { mode: 'reject', kind: 'value', value: { message: true } },
  { mode: 'reject', kind: 'value', value: { description: 'D'.repeat(500) } },
  { mode: 'reject', kind: 'value', value: null },
  { mode: 'reject', kind: 'value', value: 'boom' },
  { mode: 'reject', kind: 'value', value: 0 },
  { mode: 'reject', kind: 'value', value: [] },
];
// Number(x) is not Double.parseDouble and not parseInt: these are the spellings on which the three
// disagree, and they arrive as Data Table cells.
const NUMBERS = [0, 2, '3', ' 12 ', '0x10', '0b101', '0o17', '1e3', '1d', '1_0', '12abc', true,
  false, null, [7], [1, 2], [], {}, '', '  ', -1, 2.5, 'Infinity', '-Infinity', '.5', '5.',
  '+7', '-0', 1e21, 'NaN', '0x1g', '0x1', '1x5'];
const EVIDENCE = ['Settle-by: test.', 'Settle-by:argue', 'no hint here',
  'Svace Critical marker. Settle-by: argue.', 'settle-by: argue', 'Settle-by:\u00a0argue',
  'Settle-by:\n\targue', 'Settle-by: 123', 'Settle-by:', 'Settle-by: _x$', 'Settle-by: test extra',
  'x Settle-by: a y Settle-by: b', 0, null, false, {}, ['Settle-by: argue'], 42, true, ''];
const RAWS = ['x', '', null, 0, false, 42, 2.5, { a: 1 }, ['a', 'b'], [], true];

const BASE = {
  dedup_key: 'k', repo: 'WebGoat/WebGoat', branch: 'main', class_name: '', method: '',
  category: 'taint', severity: 'high', title: 't', description: 'd',
  evidence: 'Settle-by: test.', svace_line: 44, file: 'src/main/java/a/B.java',
};
const NO_NETWORK = { mode: 'reject', kind: 'error', message: 'no network' };
const prep = (row, lookup, note) => add('prep prover',
  { suspicion: { ...BASE, ...row }, github_token: 'tok', lookup: lookup || NO_NETWORK }, note);

for (const f of FILES) prep({ file: f }, null, 'file');
for (const f of FILES) for (const c of CLASS_NAMES) prep({ file: f, class_name: c }, null, 'file x class');
for (const b of BRANCHES) for (const l of LOOKUPS) prep({ branch: b }, l, 'branch x lookup');
for (const v of NUMBERS) prep({ prove_attempts: v }, null, 'prove_attempts');
for (const v of NUMBERS) prep({ svace_line: v }, null, 'svace_line');
for (const v of NUMBERS) prep({ svace_line: v, line: 7 }, null, 'svace_line + line');
for (const v of NUMBERS) prep({ svace_line: undefined, line: v }, null, 'line only');
for (const v of EVIDENCE) prep({ evidence: v }, null, 'evidence');
for (const key of ['dedup_key', 'repo', 'method', 'category', 'severity', 'title', 'description',
  'marker_id', 'svace_checker', 'svace_severity']) {
  for (const v of RAWS) prep({ [key]: v }, null, 'raw ' + key);
  prep({ [key]: undefined }, null, 'absent ' + key);
}
prep({}, null, 'baseline');
add('prep prover', { suspicion: {}, github_token: 'tok', lookup: NO_NETWORK }, 'empty row');
add('prep prover', { suspicion: null, github_token: 'tok', lookup: NO_NETWORK }, 'null row');
add('prep prover', { suspicion: { ...BASE, branch: '' }, lookup: LOOKUPS[0] }, 'token unset');
add('prep prover', { suspicion: { ...BASE, branch: '' }, github_token: '', lookup: LOOKUPS[0] },
  'token blank');
add('prep prover', { suspicion: { ...BASE, branch: '' }, github_token: 0, lookup: LOOKUPS[0] },
  'token zero');
add('prep prover', { suspicion: { ...BASE, branch: '', repo: null }, github_token: 'tok',
  lookup: LOOKUPS[0] }, 'repo null');
add('prep prover', { suspicion: { ...BASE, branch: '', repo: { a: 1 } }, github_token: 'tok',
  lookup: LOOKUPS[0] }, 'repo object');

// ---------------------------------------------------------------------------------------------
// build reproduce input
// ---------------------------------------------------------------------------------------------

const SIMPLE = `package a;
public class B {
  public void login(String u) {
    Statement s = c.createStatement();
    s.execute(u);
  }
  public int other() {
    return 1;
  }
}`;
// Synthetic sources rather than the WebGoat corpus: each one is a shape that has broken, or could
// break, the brace matcher — which is what a corpus test cannot isolate.
const SOURCES = {
  simple: SIMPLE,
  annotatedParam: `public class B {
  @Bean(name = "x")
  public File pluginTargetDirectory(@Value("\${webgoat.user.directory}") final String home) {
    return new File(home);
  }
}`,
  stackedAnnotations: `package a;
public class B {
  @Bean(name = "x")
  @Order(1)
  public File pluginTargetDirectory(@Value("y") final String home) {
    return new File(home);
  }
}`,
  inlineAnnotation: `package a;
public class B {
  @Override public String toString() {
    return "B";
  }
}`,
  methodCall: `public class B {
  public void run() {
    helper(1, 2);
    other();
  }
}`,
  record: `package a;
public record Point(int x, int y) implements Comparable<Point> {
  public int compareTo(Point o) {
    return x - o.x;
  }
}`,
  iface: `package a;
public interface B {
  void close() throws IOException;
  int size();
  default boolean isEmpty() {
    return size() == 0;
  }
}`,
  cutBody: `package a;
public class B {
  public void f() {
    int x = 1;`,
  cutThrows: `package a;
public interface B {
  void f() throws IOException`,
  cutParams: `package a;
public class B {
  public void f(int x`,
  endsAtParen: `package a;
public class B {
  public void f()`,
  nestedBlock: `package a;
public class B {
  public void f(int x) {
    if (x > 0) {
      g();
    }
    h();
  }
}`,
  bracesInComments: `public class B {
  public void tricky() {
    String s = "}{";            // a brace in a comment }
    /* } another in a comment {
       and this one runs across lines } */
    int x = 1;
  }
  public void after() {
    int y = 2;
  }
}`,
  separateComments: `public class B {
  /* opens } */
  public void f() {
    int x = 1;
  }
  /* closes { */
  public void g() {
    int y = 2;
  }
}`,
  commentEndsOnTheSignatureLine: 'class B {\n  void a() { int x = 1; } /* c\n     */ void b() {\n'
    + '    int y = 2;\n  }\n}',
  charLiterals: `public class B {
  public void f() {
    char open = '{', close = '}';
    char quote = '\\'', brace = '}';
    int x = 1;
  }
  public void g() {
    int y = 2;
  }
}`,
  escapedQuote: `public class B {
  public void f() {
    String s = "he said \\" } and left";
    int x = 1;
  }
  public void g() {
    int y = 2;
  }
}`,
  longLiteral: `package a;
public class B {
  public void f() {
    log("a fairly long message so that a length change would show");
    int x = 1;
  }
}`,
  throwsClause: `public class B {
  public void io() throws IOException, SQLException {
    read();
  }
}`,
  staticInit: `package a;
public class B {
  static {
    if (ready) {
      register("x");
    }
  }
  public void use() {
    get("a");
  }
}`,
  generics: `package a;
public class B {
  private final Map<String, List<String>> index;
  public B(Map<String, List<String>> index) {
    this.index = index;
  }
  public Map<String, List<String>> byKey(String k) {
    return index;
  }
}`,
  lombok: `import lombok.Getter;
@Getter
public class B {
  private Object[] items;
}`,
  plainField: 'public class B {\n  private int x;\n}',
  crlf: 'public class B {\r\n  public void f() {\r\n    int x = 1;\r\n  }\r\n}',
  // JS \s matches U+00A0 and Java's does not, in three separate scans: inside the signature, after
  // the parameter list, and after the method name.
  nbspSignature: 'public class B {\n  public\u00a0void\u00a0f() {\n    int x = 1;\n  }\n}',
  nbspBeforeBrace: 'public class B {\n  public void f()\u00a0{\n    int x = 1;\n  }\n}',
  nbspAfterThrows: 'public class B {\n  public void f() throws E\u00a0{\n    int x = 1;\n  }\n}',
  nbspBeforeParen: 'public class B {\n  public void f\u00a0() {\n    int x = 1;\n  }\n}',
  // JS's `.` excludes exactly the four line terminators; Java's also excludes U+0085 NEXT LINE, so
  // an escape followed by NEL is where a mask written with a bare `.` ends the literal early.
  nextLineChar: 'public class B {\n  public void f() {\n    String s = "a\\\u0085}";\n'
    + '    int x = 1;\n  }\n  public void g() {\n    int y = 2;\n  }\n}',
  textBlock: 'public class B {\n  public void f() {\n    String s = """\n      } not a brace\n'
    + '      """;\n    int x = 1;\n  }\n}',
  unicodeIdent: 'public class B {\n  public void fée() {\n    int x = 1;\n  }\n}',
  emptyMethod: 'class B {\n  void f() {}\n  void g() {}\n}',
  onlyBrace: '}',
  blank: '',
  whitespaceOnly: '\n   \n\n',
  bomOnly: '\ufeff',
  nbspOnly: '\u00a0',
  unitSepOnly: '\u001c',
  noNewline: 'class B { void f() { int x = 1; } }',
  trailingNewline: 'class B {\n  void f() {\n    int x = 1;\n  }\n}\n',
  nestedClass: `public class B {
  class Inner {
    void f() {
      int x = 1;
    }
  }
  void g() {
    int y = 2;
  }
}`,
  varargsAndArray: `public class B {
  public static int[] pick(String... names) {
    return new int[0];
  }
}`,
  lambda: `public class B {
  void f() {
    run(() -> { g(); });
    int x = 1;
  }
}`,
  annotationNoParens: `public class B {
  @Override
  public void f() {
    int x = 1;
  }
}`,
};
const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');
const MARKER = {
  repo: 'o/r', branch: 'main', module: '', file: 'src/main/java/a/B.java', pkg: 'a',
  class_name: 'B', test_class: 'BFsmProofTest', test_path: 'src/test/java/a/BFsmProofTest.java',
  svace_checker: 'HANDLE_LEAK', svace_severity: 'Major', svace_line: 1,
  description: 'a resource is not closed on every path',
};
const bri = (marker, content, note) => add('build reproduce input',
  { prep_prover: marker, github_file: { content } }, note);

// A LINE SWEEP over every source: every line, plus both boundaries. This is where the anchoring gets
// exercised — the compared output includes the SPAN and the extracted method text, not just the name.
for (const [name, src] of Object.entries(SOURCES)) {
  const count = src.split('\n').length;
  for (let line = 0; line <= count + 1; line++) {
    bri({ ...MARKER, svace_line: line }, b64(src), 'sweep ' + name + ' line ' + line);
  }
}
for (const v of NUMBERS) bri({ ...MARKER, svace_line: v }, b64(SIMPLE), 'line type');
bri({ ...MARKER, svace_line: undefined }, b64(SIMPLE), 'line absent');

// Content shapes. Node's base64 decoder is forgiving where java.util.Base64 throws, and a throw here
// is reported as "source file could not be fetched" for a file that in fact arrived.
const CONTENTS = [b64(SIMPLE), b64(SIMPLE).replace(/(.{20})/g, '$1\n'), b64(SIMPLE) + '\n',
  ' ' + b64(SIMPLE) + ' ', b64(SIMPLE).replace(/=+$/, ''), b64(SIMPLE) + '!!!',
  b64(SIMPLE).slice(0, 10) + '=' + b64(SIMPLE).slice(10), 'not base64 at all',
  'YWJj ZGVm', 'aGV~sbG8h{}', 'a', 'ab', 'abc', 'a===', '=abcd', '++//', '--__', 'Pj4+', 'Pz8/',
  '', '   ', '/w==', '4A==', '7A==', '8A==', '4ICA', 'YfCfkmE=', null, 0, false, true, 42,
  { sha: 'deadbeef' }, ['a'], undefined];
for (const c of CONTENTS) bri({ ...MARKER, svace_line: 5 }, c, 'content');

// Marker shapes: an absent or wrong-typed field is spliced RAW into the prompt with `+`.
for (const key of ['repo', 'branch', 'module', 'file', 'pkg', 'test_class', 'test_path',
  'svace_severity', 'svace_checker', 'description']) {
  for (const v of RAWS) bri({ ...MARKER, svace_line: 5, [key]: v }, b64(SIMPLE), 'raw ' + key);
  const without = { ...MARKER, svace_line: 5 };
  delete without[key];
  bri(without, b64(SIMPLE), 'absent ' + key);
}
add('build reproduce input', { prep_prover: { file: 'a/B.java', class_name: 'B', svace_line: 5 },
  github_file: {} }, 'minimal marker, no content');
add('build reproduce input', { prep_prover: null, github_file: { content: b64(SIMPLE) } },
  'null marker');
add('build reproduce input', { prep_prover: { ...MARKER, svace_line: 5, src: 'PRE-EXISTING' },
  github_file: { content: b64(SIMPLE) } }, 'marker already carries src');

// A file sized to the byte: the truncation boundary is what is under test.
const sized = (size) => {
  const head = 'package a;\npublic class B {\n  void f() {\n    ';
  const tail = '\n  }\n}';
  const pad = size - head.length - tail.length;
  return head + 'int x = 1; '.repeat(Math.ceil(pad / 11)).slice(0, pad) + tail;
};
for (const size of [299999, 300000, 300001, 300012]) {
  bri({ ...MARKER, svace_line: 4 }, b64(sized(size)), 'sized ' + size);
}

// ---------------------------------------------------------------------------------------------
// build fix input
// ---------------------------------------------------------------------------------------------

const FIX_MARKER = {
  repo: 'o/r', branch: 'develop', module: 'webgoat-container',
  file: 'src/main/java/a/B.java', pkg: 'a', class_name: 'B',
  test_class: 'BFsmProofTest', test_path: 'src/test/java/a/BFsmProofTest.java',
  svace_checker: 'HANDLE_LEAK', svace_severity: 'Major', svace_line: 42,
  description: 'a resource is not closed on every path',
};
const SRC_FILE = 'package a;\npublic class B {\n  void login() { c.createStatement(); }\n}';
const TEST_CODE = 'class BFsmProofTest {\n  @Test void leaks() { assertNull(B.handle()); }\n}';
const bfi = (marker, extra, note) => add('build fix input', {
  prep_prover: marker,
  build_reproduce_input: extra.bri === undefined ? { src: SRC_FILE } : extra.bri,
  parse_test: extra.parseTest === undefined ? { test_code: TEST_CODE } : extra.parseTest,
  run_test_reproduce: extra.repro,
}, note);

const REPROS = [
  { red_reproduced: true, red_output: 'expected: <null> but was: <Statement@1a2b>' },
  { red_reproduced: false, red_output: 'BUILD SUCCESS' },
  { red_reproduced: true, red_output: 'HEAD-NOISE' + 'x'.repeat(4000) + 'TAIL-CAUSE' },
  { red_reproduced: true, red_output: 'y'.repeat(2500) },
  { red_reproduced: true, red_output: 'y'.repeat(2499) },
  { red_reproduced: true, red_output: 'y'.repeat(2501) },
  { red_reproduced: true, red_output: '' },
  { red_reproduced: true, red_output: null },
  { red_reproduced: true, red_output: 0 },
  { red_reproduced: true, red_output: 42 },
  { red_reproduced: true, red_output: { a: 1 } },
  { red_reproduced: true, red_output: ['a', 'b'] },
  { red_reproduced: true },
  { red_reproduced: 'yes', red_output: 'truthy string' },
  { red_reproduced: 1, red_output: 'truthy number' },
  { red_reproduced: 0, red_output: 'falsy number' },
  { red_reproduced: '', red_output: 'falsy string' },
  { red_reproduced: null }, { red_reproduced: [] }, { red_reproduced: {} }, {},
  undefined, null, 0, 'a string verdict', [],
];
for (const r of REPROS) bfi(FIX_MARKER, { repro: r }, 'reproduce verdict');
for (const key of ['repo', 'branch', 'module', 'file', 'svace_severity', 'svace_checker',
  'svace_line', 'description', 'anchor']) {
  for (const v of RAWS) bfi({ ...FIX_MARKER, [key]: v }, { repro: REPROS[0] }, 'raw ' + key);
  const without = { ...FIX_MARKER };
  delete without[key];
  bfi(without, { repro: REPROS[0] }, 'absent ' + key);
}
for (const v of RAWS) bfi(FIX_MARKER, { repro: REPROS[0], parseTest: { test_code: v } }, 'test_code');
for (const v of RAWS) bfi(FIX_MARKER, { repro: REPROS[0], bri: { src: v } }, 'src');
bfi(FIX_MARKER, { repro: REPROS[0], parseTest: {}, bri: {} }, 'both items empty');
bfi(FIX_MARKER, { repro: REPROS[0], parseTest: null, bri: null }, 'both items null');
bfi(null, { repro: REPROS[0] }, 'null marker');
bfi({ ...FIX_MARKER, test_code: 'PRE-EXISTING' }, { repro: REPROS[0] }, 'marker carries test_code');
bfi({ ...FIX_MARKER, anchor: 'login' }, { repro: REPROS[0] }, 'with anchor');
bfi({ ...FIX_MARKER, svace_severity: '', svace_checker: '', svace_line: 0 },
  { repro: REPROS[0] }, 'blank marker fields');

// ---------------------------------------------------------------------------------------------
// running
// ---------------------------------------------------------------------------------------------

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

/** n8n's httpRequest, scripted: one answer, and it records the options it was handed. */
function lookupStub(spec, calls) {
  return async (options) => {
    calls.push(options);
    if (spec.mode === 'body') return spec.body;
    if (spec.kind === 'error') throw new Error(spec.message);
    throw spec.value;                     // n8n rejects HTTP failures with a plain object
  };
}

async function runOne(c) {
  const input = c.input;
  const calls = [];
  let out;
  if (c.node === 'prep prover') {
    out = await prepProver({
      $json: input.suspicion,
      $env: { GITHUB_TOKEN: input.github_token },
      helpers: { httpRequest: lookupStub(input.lookup, calls) },
    });
  } else if (c.node === 'build reproduce input') {
    out = await buildReproduceInput({
      $: () => ({ item: { json: input.prep_prover } }), $json: input.github_file,
    });
  } else {
    const items = { 'Prep prover': 'prep_prover', 'Build reproduce input': 'build_reproduce_input',
      'Parse test': 'parse_test' };
    out = await buildFixInput({
      $: (name) => ({ item: { json: input[items[name]] } }), $json: input.run_test_reproduce,
    });
  }
  return { calls, out };
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const results = [];
  for (const c of cases) {
    let calls = [];
    let out;
    let threw;
    try {
      ({ calls, out } = await runOne(c));
    } catch (e) {
      // A throw is a RESULT, not a harness failure: the JS node crashing is a behaviour the port has
      // to either reproduce or consciously diverge from, and the report has to say which.
      threw = e && e.constructor ? e.constructor.name : String(e);
    }
    results.push({ id: c.id, calls: tag(calls), logs: tag([]), out: tag(out), threw: tag(threw) });
  }
  fs.writeFileSync(path.join(OUT, 'input-family-cases.json'), JSON.stringify(cases));
  fs.writeFileSync(path.join(OUT, 'input-family-js-results.json'), JSON.stringify(results));
  console.log(`${cases.length} cases, ${results.filter((r) => r.threw !== 'u').length} threw`);
})();
