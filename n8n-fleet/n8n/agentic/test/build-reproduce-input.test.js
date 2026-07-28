'use strict';
/**
 * `Build reproduce input` — re-anchors the marker and assembles the reproducer's prompt.
 *
 * The commit Svace scanned is unknown, so `File:Line` is resolved against upstream HEAD and the line
 * has almost certainly moved. Handing a model a line number it cannot trust is how a marker gets
 * adjudicated against the WRONG code, and a confident verdict on the wrong lines is worse than no
 * verdict. So the line is resolved to its enclosing method by brace matching, and labelled with how
 * much the location can be trusted.
 *
 * Synthetic sources rather than the WebGoat corpus: these pin the parser's behaviour on the shapes
 * that break it, which is what a corpus test cannot isolate.
 */
const test = require('node:test');
const assert = require('node:assert');
const { buildReproduceInput } = require('../src/nodes/build-reproduce-input');

const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');

async function build(src, { line = 1, prep = {} } = {}) {
  const j = {
    repo: 'o/r', branch: 'main', module: '', file: 'src/main/java/a/B.java', pkg: 'a',
    class_name: 'B', test_class: 'BFsmProofTest', test_path: 'src/test/java/a/BFsmProofTest.java',
    svace_checker: 'HANDLE_LEAK', svace_severity: 'Major', svace_line: line,
    description: 'a resource is not closed on every path', ...prep,
  };
  return buildReproduceInput({
    $: () => ({ item: { json: j } }),
    $json: { content: b64(src) },
  });
}

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

test('a line inside a method anchors onto it', async () => {
  const r = await build(SIMPLE, { line: 5 });   // s.execute(u);
  assert.equal(r.anchor, 'login');
  assert.equal(r.anchor_status, 'exact');
  assert.match(r.anchor_note, /login\(\)/);
  assert.match(r.method_text, /public void login/);
  assert.match(r.method_text, /s\.execute\(u\);/, 'the whole method is handed over, not one line');
  assert.equal(r.line_text.trim(), 's.execute(u);');
});

test('neighbouring methods are told apart', async () => {
  assert.equal((await build(SIMPLE, { line: 8 })).anchor, 'other');
  assert.equal((await build(SIMPLE, { line: 4 })).anchor, 'login');
});

test('an annotated parameter does not hide the method', async () => {
  // THE REGRESSION: `\\([^;{)]*\\)` stopped at the annotation's own ')', so on a Spring codebase every
  // method with an annotated parameter became invisible and reported as "not inside any method"
  const src = `public class B {
  @Bean(name = "x")
  public File pluginTargetDirectory(@Value("\${webgoat.user.directory}") final String home) {
    return new File(home);
  }
}`;
  const r = await build(src, { line: 4 });
  assert.equal(r.anchor, 'pluginTargetDirectory');
  assert.equal(r.anchor_status, 'exact');
});

test('a method call is not mistaken for a method declaration', async () => {
  const src = `public class B {
  public void run() {
    helper(1, 2);
    other();
  }
}`;
  const r = await build(src, { line: 3 });
  assert.equal(r.anchor, 'run', 'helper(...) has no body, so it cannot be the enclosing method');
});

test('braces inside comments and strings do not desynchronise the scan', async () => {
  const src = `public class B {
  public void tricky() {
    String s = "}{";            // a brace in a string
    /* } another in a comment { */
    int x = 1;
  }
  public void after() {
    int y = 2;
  }
}`;
  assert.equal((await build(src, { line: 5 })).anchor, 'tricky');
  assert.equal((await build(src, { line: 8 })).anchor, 'after',
    'if masking were wrong, the first method would swallow the rest of the file');
});

test('a throws clause does not detach the body', async () => {
  const src = `public class B {
  public void io() throws IOException, SQLException {
    read();
  }
}`;
  assert.equal((await build(src, { line: 3 })).anchor, 'io');
});

test('a field or annotation is reported as such, not as drift', async () => {
  const src = `import lombok.Getter;
@Getter
public class B {
  private Object[] items;
}`;
  const r = await build(src, { line: 4 });
  assert.equal(r.anchor_status, 'no-method');
  assert.equal(r.anchor, '');
  assert.match(r.anchor_note, /field, annotation or import/);
  // Svace analysed the COMPILED code, where Lombok had already generated the accessor it flagged.
  // An agent told only "not inside any method" concludes the marker is stale and wrongly clears it.
  assert.match(r.anchor_note, /Lombok/);
  assert.match(r.anchor_note, /generated/i);
});

test('without Lombok the note does not claim generated code', async () => {
  const r = await build('public class B {\n  private int x;\n}', { line: 2 });
  assert.equal(r.anchor_status, 'no-method');
  assert.ok(!/Lombok/.test(r.anchor_note));
});

test('a line past the end of the file is proven drift', async () => {
  const r = await build(SIMPLE, { line: 9999 });
  assert.equal(r.anchor_status, 'unresolved');
  assert.match(r.anchor_note, /past the end/);
  assert.match(r.anchor_note, /9999/);
  assert.equal(r.line_text, '', 'there is no line to quote');
});

test('a source that never arrived is not mistaken for an empty file', async () => {
  const r = await buildReproduceInput({
    $: () => ({ item: { json: { file: 'a/B.java', class_name: 'B', svace_line: 5 } } }),
    $json: {},
  });
  assert.equal(r.src, '');
  assert.match(r.anchor_note, /could not be fetched/);
});

test('the prompt tells the model everything it needs to settle THIS claim', async () => {
  const p = (await build(SIMPLE, { line: 5 })).agent_input;
  for (const want of ['HANDLE_LEAK', 'Major', 'a resource is not closed on every path',
    'src/main/java/a/B.java:5', 'LOCATION CONFIDENCE: exact', 'BFsmProofTest', 'package `a`']) {
    assert.ok(p.includes(want), `prompt must carry ${want}`);
  }
  assert.match(p, /FULL SOURCE FILE/);
  // The blanket "line numbers may have drifted" warning lives in the reproducer's SYSTEM message, not
  // here. What this node contributes is the per-marker signal: how far the location can be trusted for
  // THIS marker, and the line as it actually reads in the checked-out tree.
  assert.match(p, /Line 5 as it reads in the checked-out tree/);
  assert.match(p, /enclosing method/);
});

test('a very large file is truncated, and says so', async () => {
  const big = 'public class B {\n  void f() {\n' + '    // pad\n'.repeat(40000) + '  }\n}';
  const r = await build(big, { line: 3 });
  assert.equal(r.src_truncated, true);
  assert.ok(r.src.length <= 300000);
  assert.match(r.agent_input, /TRUNCATED/,
    'a verdict on a file the model only half saw is not trustworthy, so it must know');
});

test('a normal file is not flagged as truncated', async () => {
  assert.equal((await build(SIMPLE, { line: 3 })).src_truncated, false);
});

test('the marker fields are passed through for the stages downstream', async () => {
  const r = await build(SIMPLE, { line: 5 });
  assert.equal(r.svace_checker, 'HANDLE_LEAK');
  assert.equal(r.file, 'src/main/java/a/B.java');
  assert.equal(r.test_class, 'BFsmProofTest');
});
