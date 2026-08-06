package tech.mikhailov.fsm.nodes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tech.mikhailov.fsm.lib.AnchorStatus;
import tech.mikhailov.fsm.lib.SourceText;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.lib.Json;

/**
 * {@code Build reproduce input} — re-anchors the marker and assembles the reproducer's prompt.
 *
 * <p>THE PROBLEM THIS NODE EXISTS FOR. The commit Svace scanned is unknown, so {@code File:Line} is
 * resolved against upstream HEAD and the line has almost certainly moved. Handing a model a line
 * number it cannot trust is how a marker gets adjudicated against the WRONG code, and a confident
 * verdict on the wrong lines is worse than no verdict at all. So the line is resolved to its enclosing
 * METHOD by brace matching, and the result is labelled with how far the location can be trusted:
 * {@link AnchorStatus#EXACT}, {@link AnchorStatus#NO_METHOD} or {@link AnchorStatus#UNRESOLVED}.
 *
 * <p>WHY THE SPAN MATTERS AS MUCH AS THE NAME. Every offset in this class — the newline index, the two
 * brace balancers, the mask that blanks comments and literals IN PLACE — exists to keep
 * {@code startLine}, {@code endLine} and the extracted {@code method_text} honest. A parser that names
 * the right method while quoting the wrong lines sends the model to the wrong place just as surely as
 * one that names the wrong method, and it does it while looking correct.
 *
 * <p>IT HAS BEEN FIXED TWICE, and both fixes are load-bearing:
 * <ul>
 *   <li>ANNOTATED PARAMETERS. The parameter list is scanned with a paren BALANCER, not matched by a
 *       regex. The original used {@code \([^;{)]*\)}, which stops at the first {@code )} — so on a
 *       Spring codebase like WebGoat every method with an annotated parameter
 *       ({@code @Value("${webgoat.user.directory}") final String home},
 *       {@code @RequestParam("x") String x}) failed to match and its whole body became invisible.
 *       Those methods then reported "not inside any method", which reads as drift when it is really a
 *       parser gap.</li>
 *   <li>RECORDS. A record's component list looks exactly like a parameter list and its body follows —
 *       but with {@code implements} in between. So the brace is DEMANDED right after the {@code )},
 *       with only a throws clause allowed to intervene. Searching forward for one instead swallows the
 *       whole record and reports every marker inside it as belonging to a method named after the
 *       type.</li>
 * </ul>
 *
 * <p>THE BRACE MATCHING IS DELIBERATELY NOT A PARSER. JavaParser or JDT would give different — mostly
 * better — answers on the shapes below, and every one of those differences moves which method a marker
 * is anchored to. That is a behaviour change to be made on purpose and re-baselined, not a cleanup.
 */
public final class BuildReproduceInput {

    private BuildReproduceInput() {
    }

    /** Past the largest main-java file in the warm repos (~259k). */
    static final int SRC_MAX = 300_000;

    /**
     * JavaScript's {@code .} — every character except a LineTerminator.
     *
     * <p>Spelled out because Java's {@code .} also excludes U+0085 NEXT LINE, which ECMAScript does
     * not, and the only place it appears is inside the escape alternative of the string-literal mask.
     * A mask whose escape rule differs by one character is a mask that can end a literal early, and a
     * literal that ends early leaves a live brace behind.
     */
    private static final String JS_DOT = "[^\\n\\r\\u2028\\u2029]";

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern STRING_LITERAL =
            Pattern.compile("\"(\\\\" + JS_DOT + "|[^\"\\\\\\n])*\"");
    private static final Pattern CHAR_LITERAL =
            Pattern.compile("'(\\\\" + JS_DOT + "|[^'\\\\\\n])*'");

    /**
     * Everything up to and including the method name's opening paren.
     *
     * <p>Group 1 is the signature with its leading whitespace, group 2 the name. Each piece answers a
     * shape that broke it: the annotation block reaches back over {@code @Bean(name = "x")} and
     * {@code @Order(1)}, because Svace reports {@code @Bean}/{@code @Autowired} findings on the
     * ANNOTATION line and not on the signature; the modifier run and the type pattern between them
     * admit a constructor (no return type at all) and {@code Map<String, List<String>>} (commas and
     * nested angle brackets inside one type).
     *
     * <p>{@code \s} is the WIDER whitespace set, not Java's — see {@link SourceText#SPACE_CLASS}.
     */
    private static final Pattern SIGNATURE = Pattern.compile(
            "(?:^|\\n)([ \\t]*(?:@[\\w$.]+(?:\\([^)]*\\))?[ \\t\\n]*)*"
            + "(?:(?:public|private|protected|static|final|abstract|synchronized|native|default"
            + "|strictfp)[ \\t\\n]+)*"
            + "[\\w$.<>\\[\\],?&" + SourceText.SPACE_CLASS + "]+?" + SourceText.SPACE
            + "([A-Za-z_$][\\w$]*)" + SourceText.SPACE + "*)\\(");

    /**
     * Keywords that take a parenthesised head and a braced body, i.e. that look exactly like a method
     * signature.
     *
     * <p>Without this list, a static initialiser whose first statement is an {@code if} with a braced
     * body anchors its marker to a method called {@code if} — a name the agent cannot find in the
     * file, which reads to it as drift and gets the marker wrongly cleared.
     */
    private static final Set<String> SKIP = Set.of("if", "for", "while", "switch", "catch",
            "synchronized", "return", "new", "else", "do", "try");

    /**
     * The Lombok annotations that generate the members Svace complains about.
     *
     * <p>Svace analysed the COMPILED code, where Lombok had already generated the getter, setter or
     * constructor it is flagging. There is no source method to point at, and an agent told only "not
     * inside any method" concludes the marker is stale and wrongly clears it.
     */
    private static final Pattern LOMBOK = Pattern.compile(
            "@(Getter|Setter|Data|Value|AllArgsConstructor|RequiredArgsConstructor"
            + "|NoArgsConstructor|Builder|With)\\b");

    /**
     * The upstream items this node reads.
     *
     * @param prepProver {@code Prep prover} — the marker, its paths and its branch
     * @param githubFile the item the node runs on: the GitHub contents reply, whose {@code content} is
     *                   base64. It may not be a string at all — the node has received the whole
     *                   contents OBJECT when the path resolved to a directory.
     */
    public record Request(Object prepProver, Object githubFile) {

        /** Read the request out of a posted body. The keys are the stage names, snake-cased. */
        public static Request of(Object body) {
            return new Request(Json.get(body, "prep_prover"), Json.get(body, "github_file"));
        }
    }

    /**
     * The marker, re-anchored, plus the prompt built from it.
     *
     * @param marker       the {@code Prep prover} item, spread back out so every field it carries
     *                     reaches the stages after the reproducer
     * @param anchorStatus the wire spelling of an {@link AnchorStatus} — {@code exact} (the line is
     *                     inside a method body), {@code no-method} (it is a field, annotation or
     *                     import), or {@code unresolved} (no source, or the line is past the end of the
     *                     file). A String because it is the column's value; the fourth spelling of that
     *                     column, {@code pending}, is written by {@code ParseMarkers} at ingest, and
     *                     the enum is where all four are declared once.
     * @param lineText     the line as it reads in the checked-out tree, or UNDEFINED when there is
     *                     none — which is NOT the same as the empty string, because an empty fenced
     *                     java block reads to a model as "the line is blank"
     */
    public record Outcome(Object marker, String src, boolean srcTruncated, String agentInput,
                          String anchor, String anchorStatus, String anchorNote, Object lineText,
                          String methodText) {

        /** {@code {...j, src, ...}}: the marker's own keys first, in their order, then these. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (marker instanceof Map<?, ?> fields) {
                for (Map.Entry<?, ?> e : fields.entrySet()) {
                    m.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            m.put("src", src);
            m.put("src_truncated", srcTruncated);
            m.put("agent_input", agentInput);
            m.put("anchor", anchor);
            m.put("anchor_status", anchorStatus);
            m.put("anchor_note", anchorNote);
            if (lineText != null) {           // JSON.stringify drops an undefined value
                m.put("line_text", lineText);
            }
            m.put("method_text", methodText);
            return m;
        }
    }

    /** Re-anchor the marker and build the reproducer's prompt. */
    public static Outcome buildReproduceInput(Request req) {
        Object j = req.prepProver();
        String src = decode(Json.get(req.githubFile(), "content"));
        boolean srcTruncated = src.length() > SRC_MAX;
        if (srcTruncated) {
            src = src.substring(0, SRC_MAX);
        }

        String[] lines = src.split("\n", -1);
        double svLine = Values.numberOr(Json.get(j, "svace_line"), 0);
        String anchor = "";
        // The pessimistic start, and every branch below either leaves it or earns something better.
        // @see AnchorStatus
        AnchorStatus anchorStatus = AnchorStatus.UNRESOLVED;
        String anchorNote = "";
        String methodText = "";
        Object lineText = "";
        if (SourceText.isBlank(src)) {
            // A blank body is what a failed or empty fetch decodes to. Calling that "line 2 is a
            // field or an annotation" invents a finding about a file nobody has.
            anchorNote = "source file could not be fetched";
        } else if (svLine < 1 || svLine > lines.length) {
            // The file got SHORTER than the marker's line: the drift is proven, not merely suspected,
            // and the length it was compared against is part of the proof.
            anchorNote = "line " + Values.plain(svLine)
                    + " is past the end of the file as checked out (" + lines.length
                    + " lines) — the file changed since the scan";
        } else {
            lineText = element(lines, svLine - 1);
            Method em = enclosingMethod(src, svLine);
            if (em != null) {
                anchor = em.name();
                anchorStatus = AnchorStatus.EXACT;
                methodText = em.text();
                anchorNote = "line " + Values.plain(svLine) + " falls inside " + em.name()
                        + "() (lines " + em.startLine() + "-" + em.endLine() + ")";
            } else {
                // Every remaining unanchored marker in the WebGoat report lands on a field or a
                // Lombok annotation. That is not drift and not a parser gap — name the real
                // situation instead, or the agent clears a marker it never looked at.
                anchorStatus = AnchorStatus.NO_METHOD;
                anchorNote = "line " + Values.plain(svLine)
                        + " is not inside any method body (it is a field, annotation or import)"
                        + (LOMBOK.matcher(src).find()
                           ? " — and this class uses Lombok, so the accessor or constructor the "
                             + "checker flagged is GENERATED at compile time and has no source form. "
                             + "Settle the claim against the generated API (for example the getter "
                             + "for this field), not against the annotation."
                           : "");
            }
        }

        String loc = str(j, "file") + ":" + Values.plain(svLine);
        String agentInput =
                "Repository: " + str(j, "repo") + "   (branch " + str(j, "branch") + ", module '"
                + str(j, "module") + "')\n"
                + "Source file: " + str(j, "file") + "\n\n"
                + "SVACE MARKER  [" + orUnknown(j, "svace_severity") + "]  "
                + orUnknown(j, "svace_checker") + "\n"
                + "Location as reported: " + loc + "\n"
                + "The checker's claim: " + Values.text(Json.get(j, "description")) + "\n\n"
                // The blanket "line numbers may have drifted" warning lives in the reproducer's
                // SYSTEM message. What this node contributes is the per-marker signal: how far the
                // location can be trusted for THIS marker.
                + "LOCATION CONFIDENCE: " + anchorStatus.wire() + " — " + anchorNote + "\n"
                // THE GUARD IS "IS THERE A LINE TO QUOTE", NOT "IS THE FIELD NON-NULL", and the two
                // are different in the case that matters. `lineText` starts as "" and stays that way
                // whenever no line was resolved — an unfetched source, or a marker pointing past the
                // end of the file. A non-null test passes for both of those and emits an EMPTY java
                // fence, which reads to the model as "line 42 is blank" — a specific, false claim
                // about the file, where the honest report is that the line is unknown. The block is
                // omitted for a genuinely blank source line too, and for the same reason.
                + (!Values.text(lineText).isEmpty()
                   ? "Line " + Values.plain(svLine) + " as it reads in the checked-out tree:\n"
                     + "```java\n" + Values.text(lineText) + "\n```\n"
                   : "")
                + (!methodText.isEmpty()
                   ? "\nThe enclosing method (this is where the claim should be settled):\n```java\n"
                     + methodText + "\n```\n"
                   : "\nNo enclosing method could be resolved — locate the construct the checker "
                     + "describes yourself.\n")
                + "\nWrite the proof test in package `" + str(j, "pkg") + "`, class `"
                + str(j, "test_class") + "`, at path `" + str(j, "test_path") + "`.\n"
                + "Only write the FAILING test — do not fix the defect.\n\n"
                // A verdict on a file the model only half saw is not trustworthy, so it must be told.
                + (srcTruncated
                   ? "SOURCE FILE (TRUNCATED — you are NOT seeing the whole file):\n```java\n"
                   : "FULL SOURCE FILE:\n```java\n")
                + src + "\n```";

        return new Outcome(j, src, srcTruncated, agentInput, anchor, anchorStatus.wire(), anchorNote,
                lineText, methodText);
    }

    /** A field spliced into the prompt: absent renders EMPTY, and only absent does. */
    private static String str(Object marker, String key) {
        return Values.text(Json.get(marker, key));
    }

    /**
     * {@code (j.field || '?')}. These come straight off the Svace report and any of them can be
     * absent; an unknown field must READ as unknown, because "[]  " invites the model to invent the
     * missing severity and a literal "undefined" is a claim about the marker the report never made.
     */
    private static String orUnknown(Object marker, String key) {
        return Values.orIfBlank(Json.get(marker, key), "?");
    }

    /**
     * Decode the GitHub contents payload, or report nothing at all.
     *
     * <p>The whitespace strip is not cosmetic: the contents API wraps its base64 at 60 columns, and
     * the newlines have to come out before the decode or what reaches the model is whatever the
     * decoder made of the padding. A non-string {@code content} — the whole contents object, which is
     * what arrives when the path resolved to a directory — throws, and the throw is caught and
     * reported as a file that could not be fetched rather than taking the run down.
     */
    private static String decode(Object content) {
        Object v = Values.orIfAbsent(content, "");
        if (!(v instanceof String text)) {
            return "";
        }
        return Values.base64ToUtf8(SourceText.stripSpace(text));
    }

    /**
     * {@code lines[i]} with JS array-index semantics: a fractional or out-of-range index is
     * {@code undefined}, not an exception.
     *
     * <p>{@code svace_line} reaches this node as {@code Number(x) || 0} and nothing rounds it, so a
     * marker whose line arrived as "2.5" really does read a property that is not there, and the
     * quoted-line block is then omitted from the prompt entirely — the honest outcome. An
     * {@code (int)} cast here would quote line 2 and claim it was line 2.5.
     */
    private static Object element(String[] lines, double index) {
        if (index != Math.rint(index) || index < 0 || index >= lines.length) {
            return null;
        }
        return lines[(int) index];
    }

    /** A method body located by brace matching: its name, its line SPAN and its exact text. */
    private record Method(String name, int startLine, int endLine, String text) {
    }

    /**
     * Blank out comments and string/char literals, preserving LENGTH and NEWLINES, so a brace or a
     * quote inside them cannot desynchronise the body scan.
     *
     * <p>The offsets this produces are used against the ORIGINAL source, so a blanked literal that
     * came out one character shorter would slide everything after it and hand the model a method
     * chopped off mid-statement. Each block comment is masked on its OWN (the pattern is reluctant):
     * blanking everything between the first {@code /*} and the last {@code *}{@code /} would take an
     * intervening method's signature and both its braces with it, and that method would vanish.
     */
    private static String mask(String s) {
        String out = replaceAll(s, BLOCK_COMMENT, BuildReproduceInput::blankKeepingNewlines);
        out = replaceAll(out, LINE_COMMENT, m -> " ".repeat(m.length()));
        out = replaceAll(out, STRING_LITERAL, m -> quoted('"', m.length()));
        return replaceAll(out, CHAR_LITERAL, m -> quoted('\'', m.length()));
    }

    private static String blankKeepingNewlines(String m) {
        char[] out = m.toCharArray();
        for (int i = 0; i < out.length; i++) {
            if (out[i] != '\n') {
                out[i] = ' ';
            }
        }
        return new String(out);
    }

    /** A literal reduced to its own delimiters and the right number of spaces between them. */
    private static String quoted(char delimiter, int length) {
        return delimiter + " ".repeat(Math.max(0, length - 2)) + delimiter;
    }

    /**
     * {@code String.prototype.replace(re, fn)} for a global pattern.
     *
     * <p>Hand-rolled rather than {@link Matcher#appendReplacement}, which reinterprets {@code $} and
     * {@code \} in the replacement text — and the replacement here is derived from Java source that is
     * full of both.
     */
    private static String replaceAll(String s, Pattern p, UnaryOperator<String> f) {
        Matcher m = p.matcher(s);
        if (!m.find()) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        do {
            out.append(s, last, m.start()).append(f.apply(m.group()));
            last = m.end();
        } while (m.find());
        return out.append(s, last, s.length()).toString();
    }

    /**
     * The method whose body contains {@code line}, or null.
     *
     * <p>Both scans are BALANCERS. The body one is why a nested block does not end the method early —
     * stopping at the first {@code }} would cut a method off at its first {@code if}, and every leak
     * Svace reports after that block would land outside it.
     */
    private static Method enclosingMethod(String source, double line) {
        String s = mask(source);
        int[] newlines = newlineOffsets(source);
        Matcher m = SIGNATURE.matcher(s);
        int next = 0;
        while (m.find(next)) {
            next = m.end();                              // JS: the regex's lastIndex after exec
            String name = m.group(2);
            if (SKIP.contains(name)) {
                continue;
            }
            int close = balance(s, m.end() - 1, '(', ')');
            if (close < 0) {
                continue;
            }
            int k = close + 1;
            while (k < s.length() && SourceText.isSpace(s.charAt(k))) {
                k++;
            }
            // An interface method ends in ';', not '{'. A scan that runs PAST that ';' looking for a
            // brace finds the next method's body and hands it back under this method's name — the
            // model would be shown one method's code under another's and settle the claim against
            // the wrong lines entirely. So the throws clause may only run up to '{' or ';'.
            if (s.startsWith("throws", k)) {
                while (k < s.length() && s.charAt(k) != '{' && s.charAt(k) != ';') {
                    k++;
                }
            }
            // No brace = an abstract or interface declaration, a record header, or (far more often)
            // an ordinary method CALL that happened to look like a signature.
            if (k >= s.length() || s.charAt(k) != '{') {
                continue;
            }
            int end = balance(s, k, '{', '}');
            if (end < 0) {
                continue;                                // a body cut off by SRC_MAX truncation
            }
            end++;
            // m.start() is the newline BEFORE the signature (or 0), so the span starts on the line
            // after it — which is also why method_text carries that leading newline.
            int startLine = lineOf(newlines, m.start() + 1);
            int endLine = lineOf(newlines, end);
            if (line >= startLine && line <= endLine) {
                return new Method(name, startLine, endLine, source.substring(m.start(), end));
            }
            next = end;
        }
        return null;
    }

    /**
     * Index of the {@code closer} that balances the {@code opener} at {@code from}, or -1.
     *
     * <p>-1 is a real answer, not an error: SRC_MAX truncation cuts wherever 300 000 characters land,
     * so a body with no closing brace is a shape this node really receives. It must resolve to
     * "no method" rather than spin — an unbalanced scan that restarted the signature sweep from where
     * it began would never terminate.
     */
    private static int balance(String s, int from, char opener, char closer) {
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == opener) {
                depth++;
            } else if (c == closer) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Offsets of every '\n', so an offset can become a 1-based line without an O(n) scan each time. */
    private static int[] newlineOffsets(String source) {
        int count = 0;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                count++;
            }
        }
        int[] offsets = new int[count];
        int w = 0;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                offsets[w++] = i;
            }
        }
        return offsets;
    }

    /** 1-based line of an offset: the number of newlines strictly before it, plus one. */
    private static int lineOf(int[] newlines, int offset) {
        int lo = 0;
        int hi = newlines.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (newlines[mid] < offset) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo + 1;
    }
}
