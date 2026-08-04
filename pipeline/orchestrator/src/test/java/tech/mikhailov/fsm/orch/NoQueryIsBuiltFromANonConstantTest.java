package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * NOTHING BUT A CONSTANT IS CONCATENATED INTO SQL, and this is the thing that keeps saying so.
 *
 * <p>ORIGIN. A reader opened {@code JobRunDao} and filed the concatenation in it as SQL injection. The
 * measured inventory was three different shapes wearing one face, and only one of them was even the
 * right SHAPE of hazard:
 *
 * <ol>
 *   <li>{@code "… LIMIT " + limit} — an {@code int}, from one caller passing a compile-time constant.
 *       An {@code int} cannot carry a quote, so it was never exploitable in any literal sense. It binds
 *       trivially, so it is now bound: {@code LIMIT ?}.</li>
 *   <li>The Spring Batch table prefix, spliced into a {@code FROM} clause at four sites in three files.
 *       JDBC CANNOT BIND A TABLE NAME, so there is no version of this that becomes a {@code ?}.
 *       {@link tech.mikhailov.fsm.orch.batch.BatchTables} validates it once, at boot, and refuses to
 *       start on anything that is not a bare SQL identifier. It is on the allowlist below, by name.</li>
 *   <li>{@code CommentDao}'s {@code live} flag — a ternary between two string literals, which no
 *       external data has ever reached. Nothing was wrong with it and nothing about its behaviour
 *       changed; the two branches are named constants now so that a reader can see that in one line
 *       instead of deriving it.</li>
 * </ol>
 *
 * <p>WHY THE TEST IS THE POINT AND THE THREE EDITS ARE NOT. Every one of those sites was written by
 * somebody who needed a table name or a row cap and reached for the only tool that works. That need
 * recurs — the next prefix, the next {@code ORDER BY} column, the next dynamic {@code IN} list — and
 * three edits do nothing about the fourth occurrence. This repository exists to TRIAGE static-analysis
 * markers, so shipping the exact shape of finding it triages is indefensible on presentation alone,
 * whatever the exploitability analysis says. A check that goes red on the day the shape reappears is
 * the only part of this that survives the next contributor.
 *
 * <p>WHAT IS FLAGGED, precisely: a {@code +} chain whose string literals read as SQL, containing an
 * operand that is not a constant. "Constant" is resolved WITHIN THE FILE and transitively — a literal,
 * a {@code String} field or local whose initialiser is constant, or a call to a {@code String} method
 * of the same file whose every {@code return} is constant. That is what lets {@code JobRunDao} keep
 * {@code select()} and {@code groupBy()} as methods and still be checked. Everything else — a
 * parameter, a field assigned in a constructor, a call into another class — is not a constant and has
 * to be bound, or named below.
 *
 * <p>A METHOD IS NOT A LAUNDERING STEP. {@code private String from() { return " FROM " + prefix; }}
 * is reported at its own line AND at every query built from it, because the alternative — trusting a
 * call because its body will be checked separately — is a hole with a one-line exploit:
 * {@code private String table() { return prefix; }} has no SQL literal in it, so nothing would ever be
 * reported anywhere. Binding or blessing the leaf clears every line it caused at once, which is what
 * makes the noise self-limiting.
 *
 * <p>THE ALLOWLIST IS NAMES, NOT A PATTERN, and that is deliberate. A pattern that matched "anything
 * called {@code prefix}" or "anything a comment says is safe" would quietly absorb the next real one.
 * {@link #ALLOWED} is four entries, each naming a file, an identifier and the reason that identifier
 * cannot be a bind parameter. Adding to it is a visible diff with a sentence attached, which is exactly
 * the amount of friction wanted: the point is not that nobody may ever concatenate, it is that nobody
 * may do it by accident. {@link #everyAllowanceStillSuppressesSomething()} deletes entries that have
 * stopped applying, so the list cannot grow stale and start hiding things nobody blessed.
 *
 * <p>WHAT IT DOES NOT SEE, stated so nobody trusts it further than it goes: it reads {@code +}
 * concatenation in {@code src/main/java} only. SQL assembled through a {@code StringBuilder}, through
 * {@code String.format}, or in a test is invisible to it. That boundary is not an oversight — it is the
 * shape a reader for a table name actually writes — but a future assembler that wants to hide from this
 * check will have no trouble, and the check is not the reason to trust the code.
 */
class NoQueryIsBuiltFromANonConstantTest {

    /**
     * One deliberate exception: a file, the identifier spliced into SQL inside it, and why it cannot
     * be bound instead.
     *
     * @param file  the source file's name, which must match exactly one file in the reactor
     * @param name  the identifier as it appears in the query — the leaf, not its call sites
     * @param why   the reason, for the reader who finds this list before they find the code
     */
    private record Allowed(String file, String name, String why) {

        @Override
        public String toString() {
            return file + " may splice `" + name + "`: " + why;
        }
    }

    /**
     * THE FOUR THINGS THAT MAY BE CONCATENATED INTO A QUERY IN THIS REACTOR.
     *
     * <p>All four are the same category — a piece of SQL that is not a VALUE, so no {@code ?} can
     * ever stand in for it — and all four are held safe by something other than this list. The prefix
     * is validated at boot; the rank expression is built out of enum constants no request can reach.
     */
    private static final List<Allowed> ALLOWED = List.of(
            new Allowed("JobRunDao.java", "prefix",
                    "the Spring Batch table prefix, in the FROM clauses of the run history. A table "
                    + "name is not bindable; BatchTables validates it as a SQL identifier at boot and "
                    + "refuses to start otherwise, so what reaches here cannot carry a quote."),
            new Allowed("StaleExecutionReconciler.java", "prefix",
                    "the same validated prefix, in the scan for executions a dead JVM left running."),
            new Allowed("IngestHistory.java", "prefix",
                    "the same validated prefix, in the lookup of the most recent ingest execution."),
            new Allowed("SuspicionDao.java", "RANK",
                    "the CASE expression the backlog is ordered by, built by severityRank() from the "
                    + "Severity enum's own labels and ranks. An ORDER BY expression is not a value and "
                    + "cannot be bound, and nothing outside the enum reaches it."));

    /**
     * What makes a run of string literals read as SQL.
     *
     * <p>UPPERCASE ONLY, which is what keeps this usable. The reactor is full of prose built by
     * concatenation — {@code "HTTP " + status + " from " + uri} — and a case-insensitive {@code FROM}
     * would flag every one of them, at which point the check gets an exclusion list longer than the
     * rule and stops being read. Every query in this codebase spells its keywords in capitals, so the
     * cost of the convention is that a query written in lower case is missed, and the benefit is that
     * the check has no exceptions for English.
     *
     * <p>The other half of that cost, met while writing {@code BatchTables}: a MESSAGE that shouts a
     * keyword — "written into the FROM clause" — reads as SQL to this and gets flagged. The fix there
     * is to write the sentence without the capitals, which is better prose anyway; it is not to add
     * the file to the allowlist.
     */
    private static final Pattern SQL = Pattern.compile(
            "(?:^|[\\s(,])(SELECT\\s|INSERT\\s+INTO\\b|UPDATE\\s|DELETE\\s+FROM\\b|MERGE\\s+INTO\\b"
            + "|FROM\\s|WHERE\\s|JOIN\\s|ORDER\\s+BY\\b|GROUP\\s+BY\\b|HAVING\\s|LIMIT\\s"
            + "|OFFSET\\s|VALUES\\s*\\(|UNION\\s|SET\\s)");

    // ---- the rule ------------------------------------------------------------------------------

    @Test
    void everyFragmentConcatenatedIntoAQueryIsAConstantOrIsOnTheAllowlist() throws Exception {
        List<Path> sources = mainSources();

        assertThat(sources)
                .as("this rule reads the main sources of every module in the reactor; with none of "
                        + "them present it would pass by finding nothing to check, which is the "
                        + "no-op failure every source-scanning test dies of")
                .hasSizeGreaterThan(100);

        List<String> violations = scan(sources, ALLOWED);

        assertThat(violations)
                .as("each line is a piece of SQL built by concatenating something that is not a "
                        + "constant. If it is a VALUE — a limit, a key, a status — bind it with `?` "
                        + "and pass it as an argument; JdbcTemplate takes varargs on every method "
                        + "here. If it is not a value — a table, a column, an ORDER BY expression — "
                        + "no `?` exists for it, so validate it where it is READ, against a strict "
                        + "identifier pattern, refuse to start when it fails, and add it to %s with "
                        + "the reason. What must not happen is a fourth site nobody decided on",
                        NoQueryIsBuiltFromANonConstantTest.class.getSimpleName() + ".ALLOWED")
                .isEmpty();
    }

    /**
     * …AND THE ALLOWLIST IS NOT ALLOWED TO ROT.
     *
     * <p>An entry that no longer suppresses anything is worse than no entry: it reads as a standing
     * blessing on an identifier that may since have been deleted, renamed, or turned into something
     * else entirely. Each one has to still be doing a job, and the file it names has to still exist.
     */
    @Test
    void everyAllowanceStillSuppressesSomething() throws Exception {
        List<Path> sources = mainSources();
        Set<String> raw = new LinkedHashSet<>(scan(sources, List.of()));

        List<String> stale = new ArrayList<>();
        for (Allowed allowed : ALLOWED) {
            long files = sources.stream()
                    .filter(path -> path.getFileName().toString().equals(allowed.file()))
                    .count();
            if (files != 1) {
                stale.add(allowed.file() + " names " + files + " files in the reactor, not one");
                continue;
            }
            boolean used = raw.stream()
                    .anyMatch(line -> line.contains("/" + allowed.file() + ":")
                            && line.endsWith("`" + allowed.name() + "`"));
            if (!used) {
                stale.add(allowed + "  — but nothing in " + allowed.file()
                        + " concatenates `" + allowed.name() + "` into a query any more");
            }
        }

        assertThat(stale)
                .as("an allowlist entry is a standing exception to the rule above, so it has to be "
                        + "earning it. Delete the ones below rather than leaving a blessing lying "
                        + "around for whatever takes that name next")
                .isEmpty();
    }

    // ---- the check checks something ------------------------------------------------------------

    /**
     * THE SCANNER, RUN OVER SOURCE IT IS TOLD THE ANSWER FOR.
     *
     * <p>Without this the test above is one refactor away from being a green no-op: a tokeniser that
     * quietly stopped recognising {@code +} would report an empty violation list over a codebase full
     * of them, and would look exactly like success. Each case below is a shape that has to be caught
     * or a shape that must not be, and the reasons are the reasons the real rule needs to work at all.
     */
    @Test
    void theScannerCatchesTheShapeAndNotTheProse() {
        assertThat(scanOne("Dao.java", """
                class Dao {
                    List<String> byKey(String key) {
                        return jdbc.query("SELECT * FROM suspicions WHERE dedup_key = '" + key + "'");
                    }
                }
                """))
                .as("the shape this whole check exists for: a parameter, quoted into a WHERE clause")
                .containsExactly("Dao.java:3 `key`");

        assertThat(scanOne("Dao.java", """
                class Dao {
                    List<String> recent(int limit) {
                        return jdbc.query("SELECT * FROM runs ORDER BY id DESC LIMIT " + limit);
                    }
                }
                """))
                .as("the JobRunDao shape: an int, unquotable, and still the finding a reader files")
                .containsExactly("Dao.java:3 `limit`");

        assertThat(scanOne("Dao.java", """
                class Dao {
                    private static final String COLS = "a, b, c";
                    private String from() { return " FROM suspicions"; }
                    List<String> all(boolean live) {
                        String tail = live ? " WHERE retracted_at IS NULL" : "";
                        return jdbc.query("SELECT " + COLS + from() + tail);
                    }
                }
                """))
                .as("a constant, a same-file method returning literals, and a ternary between two "
                        + "literals are all constants — flagging these would make the rule unusable")
                .isEmpty();

        assertThat(scanOne("Dao.java", """
                class Dao {
                    private final String prefix;
                    private String from() { return " FROM " + prefix + "JOB_EXECUTION"; }
                    List<String> all() { return jdbc.query("SELECT id" + from()); }
                }
                """))
                .as("a field assigned somewhere else is not a constant, and a method that returns it "
                        + "is not a laundering step: the splice AND the query built from it are both "
                        + "named, and binding or blessing the leaf clears both")
                .containsExactly("Dao.java:3 `prefix`", "Dao.java:4 `from()`");

        assertThat(scanOne("Client.java", """
                class Client {
                    String fail(int status, String uri) {
                        return "HTTP " + status + " from " + uri + ", and no rows were set";
                    }
                }
                """))
                .as("English is not SQL. This is the sentence shape the reactor is full of, and a "
                        + "case-insensitive keyword list would flag every one of them")
                .isEmpty();

        assertThat(scanOne("Dao.java", """
                class Dao {
                    // return jdbc.query("SELECT * FROM t WHERE k = '" + key + "'");
                    /** {@code "SELECT * FROM t WHERE k = '" + key + "'"} is what this used to do. */
                    List<String> byKey(String key) { return jdbc.query("SELECT * FROM t", key); }
                }
                """))
                .as("the files in this repository argue with themselves at length and quote the code "
                        + "they refuse to write; a scanner that read comments would be unusable here")
                .isEmpty();

        assertThat(scanOne("Dao.java", """
                class Dao {
                    List<String> byKey(String key) {
                        return jdbc.query("SELECT * FROM t WHERE k = ?", key);
                    }
                }
                """))
                .as("the fix, as a fixture: a bound parameter is an argument, not an operand")
                .isEmpty();
    }

    // ---- the scanner ---------------------------------------------------------------------------

    /** Every violation in {@code sources}, as {@code path:line `operand`}, in file order. */
    private static List<String> scan(List<Path> sources, List<Allowed> allowed) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String name = source.getFileName().toString();
            Set<String> names = allowed.stream()
                    .filter(entry -> entry.file().equals(name))
                    .map(Allowed::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (String found : violationsIn(Files.readString(source, StandardCharsets.UTF_8), names)) {
                violations.add(source + ":" + found);
            }
        }
        return violations;
    }

    /** The same scanner over one named blob of source, for {@link #theScannerCatchesTheShapeAndNotTheProse()}. */
    private static List<String> scanOne(String name, String source) {
        return violationsIn(source, Set.of()).stream().map(found -> name + ":" + found).toList();
    }

    private static List<String> violationsIn(String source, Set<String> allowed) {
        List<Token> tokens = tokenize(source);
        Constants constants = new Constants(tokens, allowed);
        List<String> violations = new ArrayList<>();
        walk(tokens, chain -> {
            if (chain.size() < 2) {
                return;
            }
            String sql = chain.stream()
                    .filter(NoQueryIsBuiltFromANonConstantTest::isLiteral)
                    .map(operand -> operand.get(0).text())
                    .collect(Collectors.joining());
            if (sql.isEmpty() || !SQL.matcher(sql).find()) {
                return;
            }
            for (List<Token> operand : chain) {
                if (isLiteral(operand) || constants.isConstant(operand)) {
                    continue;
                }
                violations.add(operand.get(0).line() + " `" + render(operand) + "`");
            }
        });
        return violations;
    }

    private static boolean isLiteral(List<Token> operand) {
        return operand.size() == 1 && operand.get(0).kind() == Kind.STRING;
    }

    /** The operand as a reader would grep for it: {@code prefix}, {@code select()}, {@code a.b()}. */
    private static String render(List<Token> operand) {
        StringBuilder out = new StringBuilder();
        for (Token token : operand) {
            if (token.kind() == Kind.STRING) {
                out.append('"').append(token.text()).append('"');
            } else {
                out.append(token.text());
            }
        }
        return out.toString();
    }

    // ---- what counts as a constant -------------------------------------------------------------

    /**
     * The constants of ONE FILE, resolved transitively.
     *
     * <p>A name is constant when everything it is built from is: string and numeric literals, other
     * constants of the same file, and calls to {@code String} methods of the same file whose every
     * {@code return} is constant. A cycle resolves to "not constant", which is the safe direction — a
     * self-referential fragment is not something to wave through.
     *
     * <p>What is deliberately NOT resolved: fields assigned in a constructor, parameters, and calls
     * into other classes. Those are the things a request can reach, and treating them as opaque is
     * what makes the rule mean anything.
     */
    private static final class Constants {

        private final Map<String, List<List<Token>>> definitions = new HashMap<>();
        private final Set<String> allowed;
        private final Map<String, Boolean> resolved = new HashMap<>();

        Constants(List<Token> tokens, Set<String> allowed) {
            this.allowed = allowed;
            collect(tokens);
        }

        /**
         * Every {@code String NAME = …;} and every {@code String name(…) { … }} in the file.
         *
         * <p>Fields and locals are read the same way on purpose: {@code CommentDao} builds its
         * {@code WHERE} tail into a local and {@code SuspicionDao} builds its column list into a
         * {@code static final}, and neither of them is more or less a constant than the other.
         */
        private void collect(List<Token> tokens) {
            for (int i = 0; i + 2 < tokens.size(); i++) {
                if (tokens.get(i).kind() != Kind.IDENT || !tokens.get(i).text().equals("String")) {
                    continue;
                }
                if (tokens.get(i + 1).kind() != Kind.IDENT) {
                    continue;
                }
                String name = tokens.get(i + 1).text();
                Token next = tokens.get(i + 2);
                if (next.kind() != Kind.PUNCT) {
                    continue;
                }
                if (next.text().equals("=")) {
                    definitions.computeIfAbsent(name, key -> new ArrayList<>())
                            .add(readTo(tokens, i + 3, ";"));
                } else if (next.text().equals("(")) {
                    int close = matching(tokens, i + 2, "(", ")");
                    if (close < 0 || close + 1 >= tokens.size()
                            || !tokens.get(close + 1).text().equals("{")) {
                        continue;
                    }
                    int end = matching(tokens, close + 1, "{", "}");
                    if (end < 0) {
                        continue;
                    }
                    List<List<Token>> returns =
                            definitions.computeIfAbsent(name, key -> new ArrayList<>());
                    for (int j = close + 2; j < end; j++) {
                        if (tokens.get(j).kind() == Kind.IDENT
                                && tokens.get(j).text().equals("return")) {
                            returns.add(readTo(tokens, j + 1, ";"));
                        }
                    }
                }
            }
        }

        boolean isConstant(List<Token> operand) {
            if (operand.isEmpty()) {
                return true;
            }
            Token first = operand.get(0);
            if (operand.size() == 1) {
                return first.kind() != Kind.IDENT || isConstant(first.text());
            }
            // `name(…)` — a call. Anything else (a.b(), (Cast) x, array[i]) is opaque.
            if (first.kind() == Kind.IDENT && operand.get(1).kind() == Kind.PUNCT
                    && operand.get(1).text().equals("(")) {
                return isConstant(first.text());
            }
            return false;
        }

        private boolean isConstant(String name) {
            if (allowed.contains(name)) {
                return true;
            }
            Boolean known = resolved.get(name);
            if (known != null) {
                return known;
            }
            List<List<Token>> definition = definitions.get(name);
            if (definition == null) {
                return false;
            }
            resolved.put(name, false);          // a cycle resolves to "not constant"
            boolean constant = definition.stream().allMatch(this::isConstantExpression);
            resolved.put(name, constant);
            return constant;
        }

        private boolean isConstantExpression(List<Token> expression) {
            return operandsOf(expression).stream().allMatch(this::isConstant);
        }

        /**
         * An expression split into the values it can evaluate to.
         *
         * <p>Splits on {@code +} and on both halves of a ternary, and DISCARDS the condition of a
         * ternary — {@code includeRetracted ? "" : LIVE_ONLY} evaluates to one of two literals, and
         * the boolean deciding which is not part of the string.
         */
        private static List<List<Token>> operandsOf(List<Token> expression) {
            List<List<Token>> operands = new ArrayList<>();
            List<Token> current = new ArrayList<>();
            int depth = 0;
            for (Token token : expression) {
                boolean punctuation = token.kind() == Kind.PUNCT;
                if (punctuation && (token.text().equals("(") || token.text().equals("["))) {
                    depth++;
                } else if (punctuation && (token.text().equals(")") || token.text().equals("]"))) {
                    depth--;
                }
                boolean splits = depth == 0 && punctuation && (token.text().equals("+")
                        || token.text().equals(":") || token.text().equals("?"));
                if (!splits) {
                    current.add(token);
                    continue;
                }
                if (!token.text().equals("?") && !current.isEmpty()) {
                    operands.add(current);
                }
                current = new ArrayList<>();
            }
            if (!current.isEmpty()) {
                operands.add(current);
            }
            return operands;
        }

        private static List<Token> readTo(List<Token> tokens, int from, String terminator) {
            List<Token> out = new ArrayList<>();
            int depth = 0;
            for (int i = from; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (token.kind() == Kind.PUNCT) {
                    if ("([{".contains(token.text())) {
                        depth++;
                    } else if (")]}".contains(token.text())) {
                        if (depth == 0) {
                            return out;
                        }
                        depth--;
                    } else if (depth == 0 && token.text().equals(terminator)) {
                        return out;
                    }
                }
                out.add(token);
            }
            return out;
        }

        private static int matching(List<Token> tokens, int open, String opener, String closer) {
            int depth = 0;
            for (int i = open; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (token.kind() != Kind.PUNCT) {
                    continue;
                }
                if (token.text().equals(opener)) {
                    depth++;
                } else if (token.text().equals(closer) && --depth == 0) {
                    return i;
                }
            }
            return -1;
        }
    }

    // ---- reading Java ---------------------------------------------------------------------------

    private enum Kind { STRING, IDENT, PUNCT, NUMBER }

    private record Token(Kind kind, String text, int line) { }

    /**
     * Java as a flat token list: string literals with their quotes off, identifiers, numbers and
     * single punctuation characters. Comments are dropped here rather than filtered later, because
     * every file in this repository quotes the code it decided not to write.
     */
    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int line = 1;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? length : end + 2;
                line += newlines(source, i, end);
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = closingBlock(source, i);
                tokens.add(new Token(Kind.STRING, source.substring(i + 3, Math.max(i + 3, end - 3)),
                        line));
                line += newlines(source, i, end);
                i = end;
            } else if (c == '"') {
                int end = closingQuote(source, i, '"');
                tokens.add(new Token(Kind.STRING, source.substring(i + 1, Math.max(i + 1, end - 1)),
                        line));
                i = end;
            } else if (c == '\'') {
                int end = closingQuote(source, i, '\'');
                tokens.add(new Token(Kind.NUMBER, source.substring(i, end), line));
                i = end;
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = i;
                while (end < length && Character.isJavaIdentifierPart(source.charAt(end))) {
                    end++;
                }
                tokens.add(new Token(Kind.IDENT, source.substring(i, end), line));
                i = end;
            } else if (Character.isDigit(c)) {
                int end = i;
                while (end < length && (Character.isLetterOrDigit(source.charAt(end))
                        || source.charAt(end) == '.' || source.charAt(end) == '_')) {
                    end++;
                }
                tokens.add(new Token(Kind.NUMBER, source.substring(i, end), line));
                i = end;
            } else {
                tokens.add(new Token(Kind.PUNCT, String.valueOf(c), line));
                i++;
            }
        }
        return tokens;
    }

    private static int closingQuote(String source, int open, char quote) {
        int i = open + 1;
        while (i < source.length() && source.charAt(i) != quote) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + 1, source.length());
    }

    private static int closingBlock(String source, int open) {
        int i = open + 3;
        while (i < source.length() && !source.startsWith("\"\"\"", i)) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + 3, source.length());
    }

    private static int newlines(String source, int from, int to) {
        return (int) source.substring(from, to).chars().filter(c -> c == '\n').count();
    }

    /**
     * Java keywords, which end a concatenation rather than joining one.
     *
     * <p>Without this, {@code return "SELECT …" + x} reads as an operand spelled
     * {@code return "SELECT …"} instead of a literal, and every {@code return} of a query becomes a
     * violation of its own.
     */
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "break", "case", "catch", "class", "continue", "default", "do",
            "else", "enum", "extends", "final", "finally", "for", "if", "implements", "import",
            "instanceof", "interface", "native", "new", "package", "private", "protected", "public",
            "record", "return", "static", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "try", "void", "volatile", "while", "yield");

    /** Punctuation that stays INSIDE an operand. Every other punctuation mark ends the chain. */
    private static final String JOINS = "+()[].";

    /**
     * The token stream as {@code +} chains, one call to {@code sink} per chain.
     *
     * <p>An open bracket starts a chain of its own and its close finishes it, so
     * {@code query(a + b, c + d)} yields the two inner chains rather than one nonsense chain across
     * the comma — and the outer expression still sees {@code query(…)} as a single operand, which is
     * what lets a call to a same-file method be resolved rather than flagged.
     */
    private static void walk(List<Token> tokens, Consumer<List<List<Token>>> sink) {
        Deque<Chain> stack = new ArrayDeque<>();
        stack.push(new Chain());
        for (Token token : tokens) {
            boolean punctuation = token.kind() == Kind.PUNCT;
            if (punctuation && (token.text().equals("(") || token.text().equals("["))) {
                stack.peek().feed(token);
                stack.push(new Chain());
            } else if (punctuation && (token.text().equals(")") || token.text().equals("]"))) {
                if (stack.size() > 1) {
                    sink.accept(stack.pop().end());
                }
                stack.peek().feed(token);
            } else if (punctuation && token.text().equals("+")) {
                stack.peek().plus();
            } else if ((punctuation && !JOINS.contains(token.text()))
                    || (token.kind() == Kind.IDENT && KEYWORDS.contains(token.text()))) {
                sink.accept(stack.peek().end());
            } else {
                stack.peek().feed(token);
            }
        }
        while (!stack.isEmpty()) {
            sink.accept(stack.pop().end());
        }
    }

    /** One {@code +} chain under construction: the operands closed so far, and the one in progress. */
    private static final class Chain {

        private final List<List<Token>> operands = new ArrayList<>();
        private List<Token> current = new ArrayList<>();

        void feed(Token token) {
            current.add(token);
        }

        void plus() {
            operands.add(current);
            current = new ArrayList<>();
        }

        List<List<Token>> end() {
            operands.add(current);
            current = new ArrayList<>();
            List<List<Token>> chain = operands.stream().filter(operand -> !operand.isEmpty()).toList();
            operands.clear();
            return chain;
        }
    }

    // ---- where the sources are -------------------------------------------------------------------

    /**
     * {@code src/main/java} of EVERY module in the reactor, not just this one.
     *
     * <p>The orchestrator is the only module with a datasource today. The rule is about the reactor
     * because the next DAO does not have to land here, and a check scoped to the module it was written
     * in would go quiet exactly when somebody moves the persistence somewhere else.
     */
    private static List<Path> mainSources() throws URISyntaxException, IOException {
        Path testClasses = Path.of(NoQueryIsBuiltFromANonConstantTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath();
        // target/test-classes -> target -> the module -> the reactor.
        Path reactor = testClasses.getParent().getParent().getParent();

        List<Path> modules;
        try (Stream<Path> children = Files.list(reactor)) {
            modules = children.map(path -> path.resolve("src").resolve("main").resolve("java"))
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        }
        assertThat(modules)
                .as("the reactor at %s should hold engine, orchestrator and runner; a rule that "
                        + "found no modules would pass by scanning nothing", reactor)
                .hasSizeGreaterThanOrEqualTo(3);

        List<Path> sources = new ArrayList<>();
        for (Path module : modules) {
            try (Stream<Path> walk = Files.walk(module)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(sources::add);
            }
        }
        return sources;
    }
}
