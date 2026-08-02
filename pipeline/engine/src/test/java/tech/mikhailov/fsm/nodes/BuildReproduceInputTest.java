package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.JsValue;
import tech.mikhailov.fsm.nodes.BuildReproduceInput.Outcome;
import tech.mikhailov.fsm.nodes.BuildReproduceInput.Request;

/**
 * {@code Build reproduce input} — re-anchors the marker and assembles the reproducer's prompt.
 *
 * <p>The commit Svace scanned is unknown, so {@code File:Line} is resolved against upstream HEAD and
 * the line has almost certainly moved. Handing a model a line number it cannot trust is how a marker
 * gets adjudicated against the WRONG code, and a confident verdict on the wrong lines is worse than
 * no verdict. So the line is resolved to its enclosing method by brace matching, and labelled with
 * how much the location can be trusted.
 *
 * <p>Synthetic sources rather than the WebGoat corpus: these pin the parser's behaviour on the shapes
 * that break it, which is what a corpus test cannot isolate.
 *
 * <p>The spans ({@code lines 3-6}) and the extracted {@code method_text} are asserted exactly, not
 * just the method name. Every offset in this node — the newline index, the two brace balancers, the
 * mask that blanks comments and literals in place — exists to keep those two numbers honest, and a
 * parser that names the right method while quoting the wrong lines sends the model to the wrong place
 * just as surely.
 *
 * <p>Ported from {@code n8n/agentic/test/build-reproduce-input.test.js}, assertion for assertion.
 */
class BuildReproduceInputTest {

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** The marker the JS fixture builds; each test supplies the line. */
    private static Map<String, Object> marker(Object... overrides) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("repo", "o/r");
        j.put("branch", "main");
        j.put("module", "");
        j.put("file", "src/main/java/a/B.java");
        j.put("pkg", "a");
        j.put("class_name", "B");
        j.put("test_class", "BFsmProofTest");
        j.put("test_path", "src/test/java/a/BFsmProofTest.java");
        j.put("svace_checker", "HANDLE_LEAK");
        j.put("svace_severity", "Major");
        j.put("svace_line", 1L);
        j.put("description", "a resource is not closed on every path");
        for (int i = 0; i < overrides.length; i += 2) {
            j.put((String) overrides[i], overrides[i + 1]);
        }
        return j;
    }

    private static Outcome build(String src, long line) {
        return build(marker("svace_line", line), b64(src));
    }

    private static Outcome build(Map<String, Object> j, Object content) {
        Map<String, Object> file = new LinkedHashMap<>();
        if (content != JsValue.UNDEFINED) {
            file.put("content", content);
        }
        return BuildReproduceInput.buildReproduceInput(new Request(j, file));
    }

    /**
     * The method as it reads in the source. {@code method_text} is cut with OFFSETS, so comparing it
     * against the line range it claims is the one assertion that catches an offset that drifted by a
     * single character. The leading newline is real: the signature match starts at the newline
     * BEFORE the method.
     */
    private static String lines(String src, int from, int to) {
        String[] all = src.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = from - 1; i < to; i++) {
            out.append('\n').append(all[i]);
        }
        return out.toString();
    }

    private static final String SIMPLE = """
            package a;
            public class B {
              public void login(String u) {
                Statement s = c.createStatement();
                s.execute(u);
              }
              public int other() {
                return 1;
              }
            }""";                                    // 10 lines: login is 3-6, other is 7-9

    @Test
    void aLineInsideAMethodAnchorsOntoIt() {
        Outcome r = build(SIMPLE, 5);                // s.execute(u);
        assertEquals("login", r.anchor());
        assertEquals("exact", r.anchorStatus());
        // The span, not just the name: a line-number that drifted still names the right method while
        // pointing the model at the wrong lines, and it would read as a correct answer.
        assertEquals("line 5 falls inside login() (lines 3-6)", r.anchorNote());
        assertEquals(lines(SIMPLE, 3, 6), r.methodText(), "exactly the span it claims, brace to brace");
        assertTrue(r.methodText().contains("s.execute(u);"),
                "the whole method is handed over, not one line");
        assertEquals("    s.execute(u);", r.lineText(), "quoted verbatim, indentation included");
    }

    @Test
    void neighbouringMethodsAreToldApart() {
        assertEquals("other", build(SIMPLE, 8).anchor());
        assertEquals("login", build(SIMPLE, 4).anchor());
        // The span is closed at both ends. Svace points at the declaration itself for a whole class
        // of checkers (unused parameter, missing @Override), and at the closing brace for leaks it
        // reports where the scope ends — both must land in the method, not fall through to
        // "no method".
        assertEquals("login", build(SIMPLE, 3).anchor(), "the signature line is inside");
        assertEquals("login", build(SIMPLE, 6).anchor(), "so is the closing brace");
        assertEquals("other", build(SIMPLE, 7).anchor());
        assertEquals("other", build(SIMPLE, 9).anchor());
    }

    @Test
    void anAnnotatedParameterDoesNotHideTheMethod() {
        // THE REGRESSION: `\([^;{)]*\)` stopped at the annotation's own ')', so on a Spring codebase
        // every method with an annotated parameter became invisible and reported as "not inside any
        // method"
        String src = """
                public class B {
                  @Bean(name = "x")
                  public File pluginTargetDirectory(@Value("${webgoat.user.directory}") final String home) {
                    return new File(home);
                  }
                }""";
        Outcome r = build(src, 4);
        assertEquals("pluginTargetDirectory", r.anchor());
        assertEquals("exact", r.anchorStatus());
        // The balancer must close on the LAST ')', not the annotation's: stopping early would leave
        // `final` where the body brace is expected and drop the method entirely.
        assertEquals("line 4 falls inside pluginTargetDirectory() (lines 2-5)", r.anchorNote());
        assertTrue(r.methodText().contains("return new File(home);"));
    }

    @Test
    void aMarkerOnAStackedAnnotationAnchorsToTheMethodItAnnotates() {
        // Svace reports @Bean/@Autowired findings on the ANNOTATION line, not the signature line, so
        // the span has to reach back over the whole annotation block. If it only reached back over
        // the last annotation, a marker on the first one would be reported as belonging to no method
        // at all.
        String src = """
                package a;
                public class B {
                  @Bean(name = "x")
                  @Order(1)
                  public File pluginTargetDirectory(@Value("y") final String home) {
                    return new File(home);
                  }
                }""";
        Outcome r = build(src, 3);                   // the @Bean line itself
        assertEquals("pluginTargetDirectory", r.anchor());
        assertEquals("line 3 falls inside pluginTargetDirectory() (lines 3-7)", r.anchorNote());
        assertEquals("pluginTargetDirectory", build(src, 4).anchor());
    }

    @Test
    void anAnnotationOnTheSignatureLineDoesNotHideTheMethod() {
        // With the annotation on its own line the scan can simply start at the next line; on the
        // SAME line it has to consume `@Override ` before the return type, or the signature never
        // matches at all.
        String src = """
                package a;
                public class B {
                  @Override public String toString() {
                    return "B";
                  }
                }""";
        Outcome r = build(src, 4);
        assertEquals("toString", r.anchor());
        assertEquals("line 4 falls inside toString() (lines 3-5)", r.anchorNote());
    }

    @Test
    void aMethodCallIsNotMistakenForAMethodDeclaration() {
        String src = """
                public class B {
                  public void run() {
                    helper(1, 2);
                    other();
                  }
                }""";
        Outcome r = build(src, 3);
        assertEquals("run", r.anchor(), "helper(...) has no body, so it cannot be the enclosing method");
        assertEquals("line 3 falls inside run() (lines 2-5)", r.anchorNote());
    }

    @Test
    void aRecordHeaderIsNotMistakenForAMethod() {
        // A record's component list looks exactly like a parameter list, and its body follows — but
        // with `implements` in between. The brace has to be demanded right after the `)` (only a
        // throws clause may intervene); searching FORWARD for one swallows the whole record and
        // reports every marker in it as belonging to a method named after the type.
        String src = """
                package a;
                public record Point(int x, int y) implements Comparable<Point> {
                  public int compareTo(Point o) {
                    return x - o.x;
                  }
                }""";
        assertEquals("", build(src, 2).anchor(), "the header declares a type, not a method");
        Outcome r = build(src, 4);
        assertEquals("compareTo", r.anchor());
        assertEquals("line 4 falls inside compareTo() (lines 3-5)", r.anchorNote());
    }

    @Test
    void aDeclarationWithNoBodyIsNotAMethodBody() {
        // An interface method ends in `;`, not `{`. A scan that runs past that `;` looking for a
        // brace finds the NEXT method's body and hands it back as close()'s — the model would then
        // be shown isEmpty()'s code under close()'s name and settle the claim against the wrong
        // lines entirely.
        String src = """
                package a;
                public interface B {
                  void close() throws IOException;
                  int size();
                  default boolean isEmpty() {
                    return size() == 0;
                  }
                }""";
        Outcome r = build(src, 3);
        assertEquals("no-method", r.anchorStatus());
        assertEquals("", r.anchor(), "close() has no body of its own to point at");
        assertEquals("  void close() throws IOException;", r.lineText());
        assertEquals("", build(src, 4).anchor());
        Outcome d = build(src, 6);
        assertEquals("isEmpty", d.anchor(), "the default method, which does have a body, is still found");
        assertEquals("line 6 falls inside isEmpty() (lines 5-7)", d.anchorNote());
    }

    @Test
    void aSourceCutOffMidDeclarationResolvesToNothingInsteadOfHanging() {
        // SRC_MAX truncation cuts wherever 300000 characters land, so a body with no closing brace
        // and a throws clause with nothing after it are both shapes this node really receives.
        // Neither may spin: an unbalanced scan that restarts the signature sweep from where it began
        // never terminates.
        String cutBody = """
                package a;
                public class B {
                  public void f() {
                    int x = 1;""";
        Outcome b = build(cutBody, 4);
        assertEquals("no-method", b.anchorStatus());
        assertEquals("", b.anchor(), "an unclosed body is not a method whose extent is known");
        String cutThrows = """
                package a;
                public interface B {
                  void f() throws IOException""";
        assertEquals("no-method", build(cutThrows, 3).anchorStatus());
    }

    @Test
    void aNestedBlockDoesNotEndTheMethodEarly() {
        // The body is brace-BALANCED, not closed at the first `}`. Stopping at the inner one would
        // cut the method off at the `if`, and every leak Svace reports after the block would land
        // outside it.
        String src = """
                package a;
                public class B {
                  public void f(int x) {
                    if (x > 0) {
                      g();
                    }
                    h();
                  }
                }""";
        Outcome r = build(src, 7);                   // h(); — after the nested block closes
        assertEquals("f", r.anchor());
        assertEquals("line 7 falls inside f() (lines 3-8)", r.anchorNote());
        assertEquals(lines(src, 3, 8), r.methodText());
    }

    @Test
    void bracesInsideCommentsAndStringsDoNotDesynchroniseTheScan() {
        String src = """
                public class B {
                  public void tricky() {
                    String s = "}{";            // a brace in a comment }
                    /* } another in a comment {
                       and this one runs across lines } */
                    int x = 1;
                  }
                  public void after() {
                    int y = 2;
                  }
                }""";
        assertEquals("tricky", build(src, 6).anchor());
        Outcome a = build(src, 9);
        assertEquals("after", a.anchor(),
                "if masking were wrong, the first method would swallow the rest of the file");
        // Masking blanks in place: same length, same newlines. If the multi-line comment collapsed,
        // every line number after it would be wrong even though the method names still came out
        // right.
        assertEquals("line 9 falls inside after() (lines 8-10)", a.anchorNote());
        assertEquals("    int y = 2;", a.lineText());
    }

    @Test
    void eachBlockCommentIsMaskedOnItsOwn() {
        // Blanking everything between the FIRST `/*` and the LAST `*/` takes f()'s signature and both
        // its braces with it — the method vanishes and its line is reported as belonging to nothing
        // at all.
        String src = """
                public class B {
                  /* opens } */
                  public void f() {
                    int x = 1;
                  }
                  /* closes { */
                  public void g() {
                    int y = 2;
                  }
                }""";
        assertEquals("f", build(src, 4).anchor());
        assertEquals("g", build(src, 8).anchor());
    }

    @Test
    void aBraceInACharLiteralDoesNotDesynchroniseTheScan() {
        // `'}'` is the one-character shape a string-only mask misses, and an escaped quote is the
        // shape a mask that ignores backslashes misses: either leaves a live brace and closes the
        // body early.
        String src = "public class B {\n"
                + "  public void f() {\n"
                + "    char open = '{', close = '}';\n"
                + "    char quote = '\\'', brace = '}';\n"
                + "    int x = 1;\n"
                + "  }\n"
                + "  public void g() {\n"
                + "    int y = 2;\n"
                + "  }\n"
                + "}";
        Outcome r = build(src, 5);
        assertEquals("f", r.anchor());
        // A blanked literal has to come out the SAME WIDTH as the literal it replaced. The offsets
        // are taken from the masked copy and then used to cut the original, so a mask one character
        // short slides everything after it and the model is handed a method chopped off mid-line.
        assertEquals("line 5 falls inside f() (lines 2-6)", r.anchorNote());
        assertEquals(lines(src, 2, 6), r.methodText());
        assertEquals("g", build(src, 8).anchor());
    }

    @Test
    void anEscapedQuoteDoesNotEndTheStringMaskEarly() {
        String src = "public class B {\n"
                + "  public void f() {\n"
                + "    String s = \"he said \\\" } and left\";\n"
                + "    int x = 1;\n"
                + "  }\n"
                + "  public void g() {\n"
                + "    int y = 2;\n"
                + "  }\n"
                + "}";
        assertEquals("f", build(src, 4).anchor());
        assertEquals("g", build(src, 7).anchor());
    }

    @Test
    void aMaskedStringKeepsItsLengthSoTheExtractedMethodIsTheRightSlice() {
        // The mask runs on a copy and the offsets it produces are used against the ORIGINAL source.
        // If a blanked literal came out shorter or longer, every offset after it would slide and the
        // model would be handed a method chopped off mid-statement.
        String src = """
                package a;
                public class B {
                  public void f() {
                    log("a fairly long message so that a length change would show");
                    int x = 1;
                  }
                }""";
        Outcome r = build(src, 5);
        assertEquals("f", r.anchor());
        assertEquals(lines(src, 3, 6), r.methodText());
    }

    @Test
    void aThrowsClauseDoesNotDetachTheBody() {
        String src = """
                public class B {
                  public void io() throws IOException, SQLException {
                    read();
                  }
                }""";
        Outcome r = build(src, 3);
        assertEquals("io", r.anchor());
        assertEquals("line 3 falls inside io() (lines 2-4)", r.anchorNote());
    }

    @Test
    void aControlFlowKeywordIsNotReportedAsTheEnclosingMethod() {
        // A static initialiser has no method to name. `if (ready) {` looks exactly like a signature
        // with a body, so without the keyword skip-list the marker would be anchored to a method
        // called `if` — a name the agent cannot find in the file, which reads as drift.
        String src = """
                package a;
                public class B {
                  static {
                    if (ready) {
                      register("x");
                    }
                  }
                  public void use() {
                    get("a");
                  }
                }""";
        Outcome r = build(src, 5);
        assertEquals("no-method", r.anchorStatus());
        assertEquals("", r.anchor());
        assertEquals("use", build(src, 9).anchor(),
                "and the real method after the initialiser is still found");
    }

    @Test
    void aConstructorAndANestedGenericReturnTypeAreBothAnchored() {
        // A constructor has no return type and `Map<String, List<String>>` has commas and nested
        // angle brackets inside one — the two signature shapes that a naive `Type name(` pattern
        // drops.
        String src = """
                package a;
                public class B {
                  private final Map<String, List<String>> index;
                  public B(Map<String, List<String>> index) {
                    this.index = index;
                  }
                  public Map<String, List<String>> byKey(String k) {
                    return index;
                  }
                }""";
        Outcome c = build(src, 5);
        assertEquals("B", c.anchor());
        assertEquals("line 5 falls inside B() (lines 4-6)", c.anchorNote());
        Outcome g = build(src, 8);
        assertEquals("byKey", g.anchor());
        assertEquals("line 8 falls inside byKey() (lines 7-9)", g.anchorNote());
    }

    @Test
    void aNonBreakingSpaceInASignatureDoesNotHideTheMethod() {
        // JavaScript's \\s matches U+00A0 and Java's does not. Left as Java's, the signature stops
        // matching the moment a file uses one — and the whole method body becomes invisible, which
        // this node then reports as "not inside any method body", indistinguishable from real drift.
        String src = "public class B {\n  public\u00a0void\u00a0f() {\n    int x = 1;\n  }\n}";
        assertEquals("f", build(src, 3).anchor());
        // ...and between the parameter list and the body brace, which is a separate scan.
        String afterParen = "public class B {\n  public void f()\u00a0{\n    int x = 1;\n  }\n}";
        assertEquals("f", build(afterParen, 3).anchor());
    }

    @Test
    void aFieldOrAnnotationIsReportedAsSuchNotAsDrift() {
        String src = """
                import lombok.Getter;
                @Getter
                public class B {
                  private Object[] items;
                }""";
        Outcome r = build(src, 4);
        assertEquals("no-method", r.anchorStatus());
        assertEquals("", r.anchor());
        assertTrue(r.anchorNote().contains("field, annotation or import"));
        // Svace analysed the COMPILED code, where Lombok had already generated the accessor it
        // flagged. An agent told only "not inside any method" concludes the marker is stale and
        // wrongly clears it.
        assertTrue(r.anchorNote().contains("Lombok"));
        assertTrue(r.anchorNote().contains("GENERATED"));
        assertTrue(r.anchorNote().contains("Settle the claim against the generated API"),
                "the note has to say what to settle the claim against, not merely that Lombok is "
                + "present");
    }

    @Test
    void withoutLombokTheNoteDoesNotClaimGeneratedCode() {
        Outcome r = build("public class B {\n  private int x;\n}", 2);
        assertEquals("no-method", r.anchorStatus());
        assertEquals("line 2 is not inside any method body (it is a field, annotation or import)",
                r.anchorNote());
        assertFalse(r.anchorNote().contains("Lombok"));
    }

    @Test
    void aLinePastTheEndOfTheFileIsProvenDrift() {
        Outcome r = build(SIMPLE, 9999);
        assertEquals("unresolved", r.anchorStatus());
        assertTrue(r.anchorNote().contains("past the end"));
        assertTrue(r.anchorNote().contains("9999"));
        assertTrue(r.anchorNote().contains("(10 lines)"),
                "the length it was compared against is part of the proof");
        assertEquals("", r.lineText(), "there is no line to quote");
    }

    @Test
    void theBoundaryEitherWayIsClosed() {
        // The last line is still in the file, one past it is not...
        Outcome last = build(SIMPLE, 10);
        assertEquals("no-method", last.anchorStatus());
        assertEquals("}", last.lineText());
        assertEquals("unresolved", build(SIMPLE, 11).anchorStatus());
        // ...and so is the first line, which a marker on an import or a package statement lands on.
        Outcome first = build(SIMPLE, 1);
        assertEquals("no-method", first.anchorStatus());
        assertEquals("package a;", first.lineText());
    }

    @Test
    void aMarkerWithNoLineNumberAtAllIsUnresolved() {
        Map<String, Object> j = marker();
        j.remove("svace_line");
        Outcome r = build(j, b64(SIMPLE));
        assertEquals("unresolved", r.anchorStatus());
        assertTrue(r.anchorNote().startsWith("line 0 "), "a missing line reads as 0, not as line 1");
        assertEquals("", r.lineText());
    }

    @Test
    void aFractionalLineQuotesNothingRatherThanTheWrongLine() {
        // `Number(x) || 0` does not round, and JS array indexing with 2.5 is `undefined` — so the
        // quoted-line block is omitted entirely. An (int) cast here would quote line 2 while the
        // prompt claimed line 2.5, which is a location the model cannot check.
        Outcome r = build(marker("svace_line", 2.5), b64(SIMPLE));
        assertEquals(JsValue.UNDEFINED, r.lineText());
        assertFalse(r.agentInput().contains("as it reads in the checked-out tree"));
        assertTrue(r.anchorNote().contains("line 2.5 "), "and the note says 2.5, not 2");
    }

    @Test
    void aSourceThatNeverArrivedIsNotMistakenForAnEmptyFile() {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("file", "a/B.java");
        j.put("class_name", "B");
        j.put("svace_line", 5L);
        Outcome r = build(j, JsValue.UNDEFINED);
        assertEquals("", r.src());
        assertTrue(r.anchorNote().contains("could not be fetched"));
        assertEquals("unresolved", r.anchorStatus());
        // With nothing to quote, neither the line block nor the method block may appear — an empty
        // fenced java block reads to a model as "the line is blank", which is a different claim from
        // "unknown".
        assertFalse(r.agentInput().contains("as it reads in the checked-out tree"));
        assertTrue(r.agentInput().contains("No enclosing method could be resolved"));
    }

    @Test
    void aSourceThatIsNothingButWhitespaceCountsAsNotFetched() {
        // A blank body is what a failed or empty fetch decodes to. Calling that "line 2 is a field or
        // an annotation" invents a finding about a file nobody has: the honest report is that there
        // is no source to settle the claim against.
        Outcome r = build("\n   \n\n", 2);
        assertEquals("unresolved", r.anchorStatus());
        assertEquals("source file could not be fetched", r.anchorNote());
    }

    @Test
    void aFileOfInvisibleCharactersAlsoCountsAsNotFetched() {
        // String.isBlank is Character.isWhitespace, which does NOT include U+00A0 or U+FEFF. A file
        // GitHub returns as a lone BOM — what an emptied file saved by a Windows editor looks like —
        // is "empty" to the JS and would be REAL to a port that used isBlank(), which would then
        // adjudicate a Svace marker against a file with no code in it.
        for (String invisible : new String[] {"\ufeff", "\u00a0", "\u2007\u202f",
                                              "\u3000"}) {
            Outcome r = build(invisible, 1);
            assertEquals("source file could not be fetched", r.anchorNote(),
                    "U+" + Integer.toHexString(invisible.charAt(0)));
        }
        // It diverges the other way too: U+001C is whitespace to Java and not to JS, so a file
        // holding only that is a real file.
        assertEquals("no-method", build("\u001c", 1).anchorStatus());
    }

    @Test
    void aContentFieldThatIsNotAStringCountsAsNotFetched() {
        // The GitHub node hands back the whole contents object when the path is a directory or the
        // call was re-shaped upstream. Decoding that throws, and a throw here would take down the run.
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("file", "a/B.java");
        j.put("class_name", "B");
        j.put("svace_line", 1L);
        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("sha", "deadbeef");
        Outcome r = build(j, contents);
        assertEquals("", r.src());
        assertEquals("source file could not be fetched", r.anchorNote());
    }

    @Test
    void base64ThatArrivesLineWrappedStillDecodes() {
        // The GitHub contents API wraps its base64 at 60 characters. The newlines have to come out
        // before the decode, or what reaches the model is whatever the decoder made of the padding.
        String wrapped = b64(SIMPLE).replaceAll("(.{20})", "$1\n");
        Outcome r = build(marker("svace_line", 5L), wrapped);
        assertEquals(SIMPLE, r.src(), "the wrapping is stripped, not decoded");
        assertEquals("login", r.anchor());
    }

    @Test
    void aStrayCharacterInTheBase64DoesNotLoseTheWholeFile() {
        // Node's decoder skips anything outside the alphabet; java.util.Base64 THROWS, and the throw
        // is caught here as "source file could not be fetched" — an infra failure reported for a file
        // that in fact arrived.
        Outcome r = build(marker("svace_line", 5L), b64(SIMPLE) + "!!!");
        assertEquals(SIMPLE, r.src());
        assertEquals("login", r.anchor());
    }

    @Test
    void thePromptTellsTheModelEverythingItNeedsToSettleThisClaim() {
        String p = build(SIMPLE, 5).agentInput();
        for (String want : new String[] {"HANDLE_LEAK", "Major", "a resource is not closed on every path",
            "src/main/java/a/B.java:5", "LOCATION CONFIDENCE: exact", "BFsmProofTest", "package `a`"}) {
            assertTrue(p.contains(want), "prompt must carry " + want);
        }
        assertTrue(p.contains("FULL SOURCE FILE"));
        assertTrue(p.endsWith("FULL SOURCE FILE:\n```java\n" + SIMPLE + "\n```"),
                "the whole file is the last thing in the prompt, fenced");
        // The blanket "line numbers may have drifted" warning lives in the reproducer's SYSTEM
        // message, not here. What this node contributes is the per-marker signal: how far the
        // location can be trusted for THIS marker, and the line as it actually reads in the
        // checked-out tree.
        assertTrue(p.contains("LOCATION CONFIDENCE: exact — line 5 falls inside login() (lines 3-6)"));
        assertTrue(p.contains(
                "Line 5 as it reads in the checked-out tree:\n```java\n    s.execute(u);\n```"));
        assertTrue(p.contains("The enclosing method (this is where the claim should be settled):\n"
                + "```java\n" + lines(SIMPLE, 3, 6) + "\n```"), "the method is fenced whole, not paraphrased");
        assertTrue(p.contains("Repository: o/r   (branch main, module '')"));
        assertTrue(p.contains("Source file: src/main/java/a/B.java"));
        assertTrue(p.contains("Write the proof test in package `a`, class `BFsmProofTest`, "
                + "at path `src/test/java/a/BFsmProofTest.java`."));
        assertTrue(p.contains("Only write the FAILING test"));
    }

    @Test
    void aMarkerMissingItsSeverityCheckerOrClaimStillYieldsAUsablePrompt() {
        // These three come straight off the Svace report and any of them can be absent. A literal
        // `undefined` in the prompt is a claim about the marker that the report never made.
        Map<String, Object> j = marker("svace_line", 5L);
        j.remove("svace_severity");
        j.remove("svace_checker");
        j.remove("description");
        String p = build(j, b64(SIMPLE)).agentInput();
        assertTrue(p.contains("SVACE MARKER  [?]  ?\n"));
        assertTrue(p.contains("The checker's claim: \n"));
        assertFalse(p.contains("undefined"));
    }

    /**
     * A file sized to the byte, on one long line: the truncation boundary is what is under test, and
     * a file with tens of thousands of lines would make every mutant run cost seconds for nothing.
     */
    private static String sized(int size) {
        String head = "package a;\npublic class B {\n  void f() {\n    ";
        String tail = "\n  }\n}";
        int pad = size - head.length() - tail.length();
        return head + "int x = 1; ".repeat((pad + 10) / 11).substring(0, pad) + tail;
    }

    @Test
    void aVeryLargeFileIsTruncatedAndSaysSo() {
        Outcome r = build(sized(300001), 4);
        assertTrue(r.srcTruncated());
        assertEquals(300000, r.src().length(), "cut at the limit exactly, not near it");
        assertTrue(r.agentInput().contains("TRUNCATED"),
                "a verdict on a file the model only half saw is not trustworthy, so it must know");
        assertFalse(r.agentInput().contains("FULL SOURCE FILE"), "and it must not be told the opposite");
        assertTrue(r.agentInput().endsWith(r.src() + "\n```"), "the truncated text is what gets sent");
    }

    @Test
    void aNormalFileIsNotFlaggedAsTruncated() {
        assertFalse(build(SIMPLE, 3).srcTruncated());
        // Exactly at the limit is still whole: the file has to be LONGER than the limit to be cut.
        Outcome edge = build(sized(300000), 4);
        assertFalse(edge.srcTruncated());
        assertEquals(300000, edge.src().length());
        assertTrue(edge.agentInput().contains("FULL SOURCE FILE"));
    }

    @Test
    void theMarkerFieldsArePassedThroughForTheStagesDownstream() {
        Map<String, Object> m = build(SIMPLE, 5).toMap();
        assertEquals("HANDLE_LEAK", m.get("svace_checker"));
        assertEquals("src/main/java/a/B.java", m.get("file"));
        assertEquals("BFsmProofTest", m.get("test_class"));
        assertEquals("a", m.get("pkg"));
        assertEquals(5L, m.get("svace_line"),
                "the reported line survives even though it is only a hint");
    }

    @Test
    void theMarkersOwnKeysComeFirstAndTheNodesOwnOverwriteInPlace() {
        // `{...j, src, ...}`: a key the marker already carried keeps its POSITION and takes the new
        // value. The Data Table columns downstream line up by position.
        Map<String, Object> j = marker("svace_line", 5L, "src", "PRE-EXISTING");
        Map<String, Object> m = build(j, b64(SIMPLE)).toMap();
        assertEquals(SIMPLE, m.get("src"), "the marker's stale value is overwritten, not kept");
        assertEquals(List.of("repo", "branch", "module", "file", "pkg", "class_name", "test_class",
                "test_path", "svace_checker", "svace_severity", "svace_line", "description", "src",
                "src_truncated", "agent_input", "anchor", "anchor_status", "anchor_note",
                "line_text", "method_text"), List.copyOf(m.keySet()));
    }

    @Test
    void theRequestIsReadOutOfAPostedBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prep_prover", marker("svace_line", 5L));
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("content", b64(SIMPLE));
        body.put("github_file", file);
        assertEquals("login", BuildReproduceInput.buildReproduceInput(Request.of(body)).anchor());
    }

    @Test
    void aBlockCommentKeepsItsNewlinesSoTheSignatureAfterItStillAnchors() {
        // The mask blanks a comment IN PLACE, and "in place" includes its newlines. The signature
        // regex only matches at a line start, so for a method that begins on the line where a
        // multi-line comment ends, the newline INSIDE that comment is the one the anchor needs. Blank
        // it to a space and the method becomes invisible — reported as "not inside any method body",
        // which reads as drift.
        String src = """
                public class B {
                  /* a
                     comment */ public void f() {
                    int x = 1;
                  }
                }""";
        Outcome r = build(src, 4);
        assertEquals("f", r.anchor());
        assertEquals("line 4 falls inside f() (lines 2-5)", r.anchorNote());
    }

    @Test
    void anUnbalancedParameterListIsNotAMethod() {
        // Truncation cuts wherever SRC_MAX lands, so a parameter list with no closing paren is a real
        // shape. The balancer answers -1 and the signature is abandoned; treating -1 as an offset
        // would scan from the wrong place and report some later method under this one's name.
        String src = """
                package a;
                public class B {
                  public void f(int x""";
        assertEquals("no-method", build(src, 3).anchorStatus());
        assertEquals("", build(src, 3).anchor());
    }

    @Test
    void aSourceThatEndsAtTheClosingParenDoesNotReadPastTheEnd() {
        // The scan for the body brace starts one character past the ')'. When that ')' is the last
        // character in the file, the very first read is out of bounds — and an exception here takes
        // down a run over a file that merely arrived truncated.
        String src = """
                package a;
                public class B {
                  public void f()""";
        assertEquals("no-method", build(src, 3).anchorStatus());
    }

    @Test
    void aMarkerThatIsNotAnObjectContributesNoFieldsRatherThanCrashing() {
        // `{...null}` is `{}` in JS. Over HTTP the marker is whatever the shim posted, and a request
        // assembled by hand can carry anything; the node's own fields must still come out.
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("content", b64(SIMPLE));
        Map<String, Object> m = BuildReproduceInput
                .buildReproduceInput(new Request(null, file)).toMap();
        assertEquals(List.of("src", "src_truncated", "agent_input", "anchor", "anchor_status",
                "anchor_note", "line_text", "method_text"), List.copyOf(m.keySet()));
    }

    @Test
    void aLineThatCouldNotBeQuotedIsOmittedFromTheItemNotEmittedAsNull() {
        // JSON.stringify drops an undefined value. `line_text: null` would tell the next stage the
        // line was read and found empty, which is a different claim from "there was no line to read".
        Map<String, Object> m = build(marker("svace_line", 2.5), b64(SIMPLE)).toMap();
        assertFalse(m.containsKey("line_text"));
        assertTrue(build(SIMPLE, 5).toMap().containsKey("line_text"), "and a line that WAS read is kept");
    }


    @Test
    void aCommentsOwnNewlineIsWhatLetsTheNextMethodBeFoundAtAll() {
        // The sweep resumes at the END of the previous method's body, which here is in the middle of
        // a line. The next line start it can see is the newline INSIDE the block comment — so if the
        // mask had blanked that newline along with the rest of the comment, there would be no line
        // start before b()'s signature and b() would be invisible. Every marker inside it would then
        // be reported as "not inside any method body", which reads as drift.
        String src = "class B {\n  void a() { int x = 1; } /* c\n     */ void b() {\n"
                + "    int y = 2;\n  }\n}";
        assertEquals("a", build(src, 2).anchor());
        Outcome r = build(src, 4);
        assertEquals("b", r.anchor());
        assertEquals("line 4 falls inside b() (lines 3-5)", r.anchorNote());
    }

}
