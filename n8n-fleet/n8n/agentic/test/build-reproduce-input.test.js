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
 *
 * The spans (`lines 3-6`) and the extracted `method_text` are asserted exactly, not just the method
 * name. Every offset in this node — the newline index, the two brace balancers, the mask that blanks
 * comments and literals in place — exists to keep those two numbers honest, and a parser that names
 * the right method while quoting the wrong lines sends the model to the wrong place just as surely.
 */
const test = require('node:test');
const assert = require('node:assert');
const { buildReproduceInput } = require('../src/nodes/build-reproduce-input');

const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');

async function build(src, { line = 1, prep = {}, content } = {}) {
  const j = {
    repo: 'o/r', branch: 'main', module: '', file: 'src/main/java/a/B.java', pkg: 'a',
    class_name: 'B', test_class: 'BFsmProofTest', test_path: 'src/test/java/a/BFsmProofTest.java',
    svace_checker: 'HANDLE_LEAK', svace_severity: 'Major', svace_line: line,
    description: 'a resource is not closed on every path', ...prep,
  };
  return buildReproduceInput({
    $: () => ({ item: { json: j } }),
    $json: { content: content === undefined ? b64(src) : content },
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
}`;                                          // 10 lines: login is 3-6, other is 7-9

// The method as it reads in SIMPLE. `method_text` is cut with offsets, so comparing it against the
// line range it claims is the one assertion that catches an offset that drifted by a single char.
const lines = (src, from, to) => '\n' + src.split('\n').slice(from - 1, to).join('\n');

test('a line inside a method anchors onto it', async () => {
  const r = await build(SIMPLE, { line: 5 });   // s.execute(u);
  assert.equal(r.anchor, 'login');
  assert.equal(r.anchor_status, 'exact');
  // The span, not just the name: a line-number that drifted still names the right method while
  // pointing the model at the wrong lines, and it would read as a correct answer.
  assert.equal(r.anchor_note, 'line 5 falls inside login() (lines 3-6)');
  assert.equal(r.method_text, lines(SIMPLE, 3, 6), 'exactly the span it claims, brace to brace');
  assert.match(r.method_text, /s\.execute\(u\);/, 'the whole method is handed over, not one line');
  assert.equal(r.line_text, '    s.execute(u);', 'quoted verbatim, indentation included');
});

test('neighbouring methods are told apart', async () => {
  assert.equal((await build(SIMPLE, { line: 8 })).anchor, 'other');
  assert.equal((await build(SIMPLE, { line: 4 })).anchor, 'login');
  // The span is closed at both ends. Svace points at the declaration itself for a whole class of
  // checkers (unused parameter, missing @Override), and at the closing brace for leaks it reports
  // where the scope ends — both must land in the method, not fall through to "no method".
  assert.equal((await build(SIMPLE, { line: 3 })).anchor, 'login', 'the signature line is inside');
  assert.equal((await build(SIMPLE, { line: 6 })).anchor, 'login', 'so is the closing brace');
  assert.equal((await build(SIMPLE, { line: 7 })).anchor, 'other');
  assert.equal((await build(SIMPLE, { line: 9 })).anchor, 'other');
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
  // The balancer must close on the LAST ')', not the annotation's: stopping early would leave `final`
  // where the body brace is expected and drop the method entirely.
  assert.equal(r.anchor_note, 'line 4 falls inside pluginTargetDirectory() (lines 2-5)');
  assert.match(r.method_text, /return new File\(home\);/);
});

test('a marker on a stacked annotation anchors to the method it annotates', async () => {
  // Svace reports @Bean/@Autowired findings on the ANNOTATION line, not the signature line, so the
  // span has to reach back over the whole annotation block. If it only reached back over the last
  // annotation, a marker on the first one would be reported as belonging to no method at all.
  const src = `package a;
public class B {
  @Bean(name = "x")
  @Order(1)
  public File pluginTargetDirectory(@Value("y") final String home) {
    return new File(home);
  }
}`;
  const r = await build(src, { line: 3 });                // the @Bean line itself
  assert.equal(r.anchor, 'pluginTargetDirectory');
  assert.equal(r.anchor_note, 'line 3 falls inside pluginTargetDirectory() (lines 3-7)');
  assert.equal((await build(src, { line: 4 })).anchor, 'pluginTargetDirectory');
});

test('an annotation on the signature line does not hide the method', async () => {
  // With the annotation on its own line the scan can simply start at the next line; on the SAME line
  // it has to consume `@Override ` before the return type, or the signature never matches at all.
  const src = `package a;
public class B {
  @Override public String toString() {
    return "B";
  }
}`;
  const r = await build(src, { line: 4 });
  assert.equal(r.anchor, 'toString');
  assert.equal(r.anchor_note, 'line 4 falls inside toString() (lines 3-5)');
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
  assert.equal(r.anchor_note, 'line 3 falls inside run() (lines 2-5)');
});

test('a record header is not mistaken for a method', async () => {
  // A record's component list looks exactly like a parameter list, and its body follows — but with
  // `implements` in between. The brace has to be demanded right after the `)` (only a throws clause
  // may intervene); searching FORWARD for one swallows the whole record and reports every marker in
  // it as belonging to a method named after the type.
  const src = `package a;
public record Point(int x, int y) implements Comparable<Point> {
  public int compareTo(Point o) {
    return x - o.x;
  }
}`;
  assert.equal((await build(src, { line: 2 })).anchor, '',
    'the header declares a type, not a method');
  const r = await build(src, { line: 4 });
  assert.equal(r.anchor, 'compareTo');
  assert.equal(r.anchor_note, 'line 4 falls inside compareTo() (lines 3-5)');
});

test('a declaration with no body is not a method body', async () => {
  // An interface method ends in `;`, not `{`. A scan that runs past that `;` looking for a brace
  // finds the NEXT method's body and hands it back as `close`'s — the model would then be shown
  // isEmpty()'s code under close()'s name and settle the claim against the wrong lines entirely.
  const src = `package a;
public interface B {
  void close() throws IOException;
  int size();
  default boolean isEmpty() {
    return size() == 0;
  }
}`;
  const r = await build(src, { line: 3 });
  assert.equal(r.anchor_status, 'no-method');
  assert.equal(r.anchor, '', 'close() has no body of its own to point at');
  assert.equal(r.line_text, '  void close() throws IOException;');
  assert.equal((await build(src, { line: 4 })).anchor, '');
  const d = await build(src, { line: 6 });
  assert.equal(d.anchor, 'isEmpty', 'the default method, which does have a body, is still found');
  assert.equal(d.anchor_note, 'line 6 falls inside isEmpty() (lines 5-7)');
});

test('a source cut off mid-declaration resolves to nothing instead of hanging', async () => {
  // SRC_MAX truncation cuts wherever 300000 characters land, so a body with no closing brace and a
  // throws clause with nothing after it are both shapes this node really receives. Neither may spin:
  // an unbalanced scan that restarts the signature sweep from where it began never terminates.
  const cutBody = `package a;
public class B {
  public void f() {
    int x = 1;`;
  const b = await build(cutBody, { line: 4 });
  assert.equal(b.anchor_status, 'no-method');
  assert.equal(b.anchor, '', 'an unclosed body is not a method whose extent is known');
  const cutThrows = `package a;
public interface B {
  void f() throws IOException`;
  assert.equal((await build(cutThrows, { line: 3 })).anchor_status, 'no-method');
});

test('a nested block does not end the method early', async () => {
  // The body is brace-BALANCED, not closed at the first `}`. Stopping at the inner one would cut the
  // method off at the `if`, and every leak Svace reports after the block would land outside it.
  const src = `package a;
public class B {
  public void f(int x) {
    if (x > 0) {
      g();
    }
    h();
  }
}`;
  const r = await build(src, { line: 7 });                // h(); — after the nested block closes
  assert.equal(r.anchor, 'f');
  assert.equal(r.anchor_note, 'line 7 falls inside f() (lines 3-8)');
  assert.equal(r.method_text, lines(src, 3, 8));
});

test('braces inside comments and strings do not desynchronise the scan', async () => {
  const src = `public class B {
  public void tricky() {
    String s = "}{";            // a brace in a comment }
    /* } another in a comment {
       and this one runs across lines } */
    int x = 1;
  }
  public void after() {
    int y = 2;
  }
}`;
  assert.equal((await build(src, { line: 6 })).anchor, 'tricky');
  const a = await build(src, { line: 9 });
  assert.equal(a.anchor, 'after',
    'if masking were wrong, the first method would swallow the rest of the file');
  // Masking blanks in place: same length, same newlines. If the multi-line comment collapsed, every
  // line number after it would be wrong even though the method names still came out right.
  assert.equal(a.anchor_note, 'line 9 falls inside after() (lines 8-10)');
  assert.equal(a.line_text, '    int y = 2;');
});

test('each block comment is masked on its own', async () => {
  // Blanking everything between the FIRST `/*` and the LAST `*/` takes f()'s signature and both its
  // braces with it — the method vanishes and its line is reported as belonging to nothing at all.
  const src = `public class B {
  /* opens } */
  public void f() {
    int x = 1;
  }
  /* closes { */
  public void g() {
    int y = 2;
  }
}`;
  assert.equal((await build(src, { line: 4 })).anchor, 'f');
  assert.equal((await build(src, { line: 8 })).anchor, 'g');
});

test('a brace in a char literal does not desynchronise the scan', async () => {
  // `'}'` is the one-character shape a string-only mask misses, and an escaped quote is the shape a
  // mask that ignores backslashes misses: either leaves a live brace and closes the body early.
  const src = `public class B {
  public void f() {
    char open = '{', close = '}';
    char quote = '\\'', brace = '}';
    int x = 1;
  }
  public void g() {
    int y = 2;
  }
}`;
  const r = await build(src, { line: 5 });
  assert.equal(r.anchor, 'f');
  // A blanked literal has to come out the SAME WIDTH as the literal it replaced. The offsets are
  // taken from the masked copy and then used to cut the original, so a mask one character short
  // slides everything after it and the model is handed a method chopped off mid-line.
  assert.equal(r.anchor_note, 'line 5 falls inside f() (lines 2-6)');
  assert.equal(r.method_text, lines(src, 2, 6));
  assert.equal((await build(src, { line: 8 })).anchor, 'g');
});

test('an escaped quote does not end the string mask early', async () => {
  const src = `public class B {
  public void f() {
    String s = "he said \\" } and left";
    int x = 1;
  }
  public void g() {
    int y = 2;
  }
}`;
  assert.equal((await build(src, { line: 4 })).anchor, 'f');
  assert.equal((await build(src, { line: 7 })).anchor, 'g');
});

test('a masked string keeps its length, so the extracted method is the right slice', async () => {
  // The mask runs on a copy and the offsets it produces are used against the ORIGINAL source. If a
  // blanked literal came out shorter or longer, every offset after it would slide and the model
  // would be handed a method chopped off mid-statement.
  const src = `package a;
public class B {
  public void f() {
    log("a fairly long message so that a length change would show");
    int x = 1;
  }
}`;
  const r = await build(src, { line: 5 });
  assert.equal(r.anchor, 'f');
  assert.equal(r.method_text, lines(src, 3, 6));
});

test('a throws clause does not detach the body', async () => {
  const src = `public class B {
  public void io() throws IOException, SQLException {
    read();
  }
}`;
  const r = await build(src, { line: 3 });
  assert.equal(r.anchor, 'io');
  assert.equal(r.anchor_note, 'line 3 falls inside io() (lines 2-4)');
});

test('a control-flow keyword is not reported as the enclosing method', async () => {
  // A static initialiser has no method to name. `if (ready) {` looks exactly like a signature with a
  // body, so without the keyword skip-list the marker would be anchored to a method called `if` —
  // a name the agent cannot find in the file, which reads as drift.
  const src = `package a;
public class B {
  static {
    if (ready) {
      register("x");
    }
  }
  public void use() {
    get("a");
  }
}`;
  const r = await build(src, { line: 5 });
  assert.equal(r.anchor_status, 'no-method');
  assert.equal(r.anchor, '');
  assert.equal((await build(src, { line: 9 })).anchor, 'use',
    'and the real method after the initialiser is still found');
});

test('a constructor and a nested generic return type are both anchored', async () => {
  // A constructor has no return type and `Map<String, List<String>>` has commas and nested angle
  // brackets inside one — the two signature shapes that a naive `Type name(` pattern drops.
  const src = `package a;
public class B {
  private final Map<String, List<String>> index;
  public B(Map<String, List<String>> index) {
    this.index = index;
  }
  public Map<String, List<String>> byKey(String k) {
    return index;
  }
}`;
  const c = await build(src, { line: 5 });
  assert.equal(c.anchor, 'B');
  assert.equal(c.anchor_note, 'line 5 falls inside B() (lines 4-6)');
  const g = await build(src, { line: 8 });
  assert.equal(g.anchor, 'byKey');
  assert.equal(g.anchor_note, 'line 8 falls inside byKey() (lines 7-9)');
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
  assert.match(r.anchor_note, /Settle the claim against the generated API/,
    'the note has to say what to settle the claim against, not merely that Lombok is present');
});

test('without Lombok the note does not claim generated code', async () => {
  const r = await build('public class B {\n  private int x;\n}', { line: 2 });
  assert.equal(r.anchor_status, 'no-method');
  assert.equal(r.anchor_note,
    'line 2 is not inside any method body (it is a field, annotation or import)');
  assert.ok(!/Lombok/.test(r.anchor_note));
});

test('a line past the end of the file is proven drift', async () => {
  const r = await build(SIMPLE, { line: 9999 });
  assert.equal(r.anchor_status, 'unresolved');
  assert.match(r.anchor_note, /past the end/);
  assert.match(r.anchor_note, /9999/);
  assert.match(r.anchor_note, /\(10 lines\)/,
    'the length it was compared against is part of the proof');
  assert.equal(r.line_text, '', 'there is no line to quote');
  // The boundary either way: the last line is still in the file, one past it is not.
  const last = await build(SIMPLE, { line: 10 });
  assert.equal(last.anchor_status, 'no-method');
  assert.equal(last.line_text, '}');
  assert.equal((await build(SIMPLE, { line: 11 })).anchor_status, 'unresolved');
  // ... and so is the first line, which a marker on an import or a package statement lands on.
  const first = await build(SIMPLE, { line: 1 });
  assert.equal(first.anchor_status, 'no-method');
  assert.equal(first.line_text, 'package a;');
});

test('a marker with no line number at all is unresolved', async () => {
  const r = await build(SIMPLE, { prep: { svace_line: undefined } });
  assert.equal(r.anchor_status, 'unresolved');
  assert.match(r.anchor_note, /^line 0 /, 'a missing line reads as 0, not as line 1');
  assert.equal(r.line_text, '');
});

test('a source that never arrived is not mistaken for an empty file', async () => {
  const r = await buildReproduceInput({
    $: () => ({ item: { json: { file: 'a/B.java', class_name: 'B', svace_line: 5 } } }),
    $json: {},
  });
  assert.equal(r.src, '');
  assert.match(r.anchor_note, /could not be fetched/);
  assert.equal(r.anchor_status, 'unresolved');
  // With nothing to quote, neither the line block nor the method block may appear — an empty fenced
  // java block reads to a model as "the line is blank", which is a different claim from "unknown".
  assert.ok(!/as it reads in the checked-out tree/.test(r.agent_input));
  assert.match(r.agent_input, /No enclosing method could be resolved/);
});

test('a source that is nothing but whitespace counts as not fetched', async () => {
  // A blank body is what a failed or empty fetch decodes to. Calling that "line 2 is a field or an
  // annotation" invents a finding about a file nobody has: the honest report is that there is no
  // source to settle the claim against.
  const r = await build('\n   \n\n', { line: 2 });
  assert.equal(r.anchor_status, 'unresolved');
  assert.equal(r.anchor_note, 'source file could not be fetched');
});

test('a content field that is not a string counts as not fetched', async () => {
  // The GitHub node hands back the whole contents object when the path is a directory or the call
  // was re-shaped upstream. Decoding that throws, and a throw here would take down the run.
  const r = await buildReproduceInput({
    $: () => ({ item: { json: { file: 'a/B.java', class_name: 'B', svace_line: 1 } } }),
    $json: { content: { sha: 'deadbeef' } },
  });
  assert.equal(r.src, '');
  assert.equal(r.anchor_note, 'source file could not be fetched');
});

test('base64 that arrives line-wrapped still decodes', async () => {
  // The GitHub contents API wraps its base64 at 60 characters. The newlines have to come out before
  // the decode, or what reaches the model is whatever the decoder made of the padding.
  const wrapped = b64(SIMPLE).replace(/(.{20})/g, '$1\n');
  const r = await build(SIMPLE, { line: 5, content: wrapped });
  assert.equal(r.src, SIMPLE, 'the wrapping is stripped, not decoded');
  assert.equal(r.anchor, 'login');
});

test('the prompt tells the model everything it needs to settle THIS claim', async () => {
  const p = (await build(SIMPLE, { line: 5 })).agent_input;
  for (const want of ['HANDLE_LEAK', 'Major', 'a resource is not closed on every path',
    'src/main/java/a/B.java:5', 'LOCATION CONFIDENCE: exact', 'BFsmProofTest', 'package `a`']) {
    assert.ok(p.includes(want), `prompt must carry ${want}`);
  }
  assert.match(p, /FULL SOURCE FILE/);
  assert.ok(p.endsWith('FULL SOURCE FILE:\n```java\n' + SIMPLE + '\n```'),
    'the whole file is the last thing in the prompt, fenced');
  // The blanket "line numbers may have drifted" warning lives in the reproducer's SYSTEM message, not
  // here. What this node contributes is the per-marker signal: how far the location can be trusted for
  // THIS marker, and the line as it actually reads in the checked-out tree.
  assert.ok(p.includes('LOCATION CONFIDENCE: exact — line 5 falls inside login() (lines 3-6)'));
  assert.ok(p.includes(
    'Line 5 as it reads in the checked-out tree:\n```java\n    s.execute(u);\n```'));
  assert.ok(p.includes('The enclosing method (this is where the claim should be settled):\n```java\n'
    + lines(SIMPLE, 3, 6) + '\n```'), 'the method is fenced whole, not paraphrased');
  assert.ok(p.includes("Repository: o/r   (branch main, module '')"));
  assert.ok(p.includes('Source file: src/main/java/a/B.java'));
  assert.ok(p.includes('Write the proof test in package `a`, class `BFsmProofTest`, '
    + 'at path `src/test/java/a/BFsmProofTest.java`.'));
  assert.match(p, /Only write the FAILING test/);
});

test('a marker missing its severity, checker or claim still yields a usable prompt', async () => {
  // These three come straight off the Svace report and any of them can be absent. A literal
  // `undefined` in the prompt is a claim about the marker that the report never made.
  const p = (await build(SIMPLE, { line: 5, prep: {
    svace_severity: undefined, svace_checker: undefined, description: undefined } })).agent_input;
  assert.ok(p.includes('SVACE MARKER  [?]  ?\n'));
  assert.ok(p.includes("The checker's claim: \n"));
  assert.ok(!/undefined/.test(p));
});

// A file sized to the byte, on one long line: the truncation boundary is what is under test, and a
// file with tens of thousands of lines would make every mutant run cost seconds for nothing.
const sized = (n) => {
  const head = 'package a;\npublic class B {\n  void f() {\n    ';
  const tail = '\n  }\n}';
  const pad = n - head.length - tail.length;
  return head + 'int x = 1; '.repeat(Math.ceil(pad / 11)).slice(0, pad) + tail;
};

test('a very large file is truncated, and says so', async () => {
  const r = await build(sized(300001), { line: 4 });
  assert.equal(r.src_truncated, true);
  assert.equal(r.src.length, 300000, 'cut at the limit exactly, not near it');
  assert.match(r.agent_input, /TRUNCATED/,
    'a verdict on a file the model only half saw is not trustworthy, so it must know');
  assert.ok(!/FULL SOURCE FILE/.test(r.agent_input), 'and it must not be told the opposite');
  assert.ok(r.agent_input.endsWith(r.src + '\n```'), 'the truncated text is what gets sent');
});

test('a normal file is not flagged as truncated', async () => {
  assert.equal((await build(SIMPLE, { line: 3 })).src_truncated, false);
  // Exactly at the limit is still whole: the file has to be LONGER than the limit to be cut.
  const edge = await build(sized(300000), { line: 4 });
  assert.equal(edge.src_truncated, false);
  assert.equal(edge.src.length, 300000);
  assert.match(edge.agent_input, /FULL SOURCE FILE/);
});

test('the marker fields are passed through for the stages downstream', async () => {
  const r = await build(SIMPLE, { line: 5 });
  assert.equal(r.svace_checker, 'HANDLE_LEAK');
  assert.equal(r.file, 'src/main/java/a/B.java');
  assert.equal(r.test_class, 'BFsmProofTest');
  assert.equal(r.pkg, 'a');
  assert.equal(r.svace_line, 5, 'the reported line survives even though it is only a hint');
});
