package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * THE PORT IS ONLY A PORT IF THE CODE HAS TO GO THROUGH IT, and this is the thing that keeps saying so.
 *
 * <p>ORIGIN. {@code MarkerRepository} is a port; {@code JdbcMarkerRepository} implements it and its own
 * javadoc admits it "WRAPS SuspicionDao UNCHANGED". So the port was an ADDITION to the DAO rather than a
 * replacement for it, and while it was, nine files in {@code src/main/java} named the DAO directly —
 * which means the four rules the port promises could be walked around by nine callers without a single
 * line of the port changing. {@code MarkerRepositoryContract} makes the two IMPLEMENTATIONS agree; it
 * says nothing at all about who bothers to use either of them.
 *
 * <p>WHAT WAS ACTUALLY MEASURED, because the list that prompted this was close and not right. Stripping
 * comments first — every file in this repository argues at length and names the DAO while doing it —
 * the code references were {@code StartupReconciler}, {@code DashboardService}, {@code BatchConfig},
 * {@code ProveWriter}, {@code SuspicionReader}, {@code ClaimReleaseListener}, {@code ResetPolicy},
 * {@code IngestTasklet} and the adapter itself. {@code WorkModel} names the DAO in ONE javadoc line and
 * has no field, no import and no constructor argument; {@code LiveWatcher} does not name it either — it
 * holds a {@code JdbcTemplate} and writes its own {@code SELECT} against the table. Which is the
 * interesting half: ROUTING CALLERS THROUGH A PORT IS DEFEATED BY ANYONE WILLING TO TYPE THE SQL
 * THEMSELVES, so a rule that only counted DAO references would be a rule with a one-line bypass, and one
 * file in the tree is already using it.
 *
 * <h2>SO THERE ARE TWO RULES, AND THE SECOND IS WHAT MAKES THE FIRST WORTH PASSING</h2>
 *
 * <p>{@link #theOnlyFilesThatNameTheBacklogDaoAreItsAdapterItsRootAndTheArguedExceptions()} — who may
 * hold {@code SuspicionDao}. {@link #nothingOutsideTheDaoWritesItsOwnSqlAgainstTheBacklogTables()} — who
 * may reach {@code suspicions} or {@code infra_strikes} without it. Neither list is a pattern; both are
 * FILES WITH REASONS, so adding to one is a visible diff with a sentence attached. That is exactly the
 * amount of friction wanted: the point is not that nobody may ever touch the backlog directly, it is
 * that nobody may do it by accident, and that the argument is written down where the next reader finds
 * it before they find the code.
 *
 * <h2>THE ARGUMENT EACH EXCEPTION HAS TO MAKE</h2>
 *
 * <p>A port earns its keep where a use case is driven through it by a DOUBLE, because a double that
 * disagrees with the database makes a green test a lie — that is the whole of
 * {@code MarkerRepositoryContract}'s origin story. It earns nothing where the caller IS the adapter and
 * the predicate it needs is CONCURRENCY CONTROL, because no in-memory double can adjudicate a race, and
 * a port there would invite precisely the unfaithful fake this slice exists to end. Widening
 * {@code MarkerRepository} until every caller fits would give the reactor a second {@code SuspicionDao}
 * under a nicer name and a port whose implementors each stub out half of it. So each entry below says
 * which of those it is.
 *
 * <p>{@link #everyExceptionNamesOneRealFileAndIsStillEarningItsPlace()} deletes entries that have
 * stopped applying, so neither list can rot into a standing blessing on a name that has since been
 * reused for something else.
 *
 * <p>WHAT IT DOES NOT SEE, stated so nobody trusts it further than it goes. It reads
 * {@code src/main/java} as TEXT. A caller that reached the table through a view, through a second DAO
 * with a different table name, or through SQL assembled by {@code String.format} is invisible to it, and
 * so is anything in a test. It is a ratchet against the next accidental caller, not a proof.
 */
class NoNewCallerReachesTheBacklogAroundItsPortTest {

    /**
     * One deliberate exception: a file, and why the port is the wrong door for it.
     *
     * @param file the source file's name, which must match exactly one file in the reactor
     * @param why  the argument, for the reader who finds this list before they find the code
     */
    private record Allowed(String file, String why) {

        @Override
        public String toString() {
            return file + ": " + why;
        }
    }

    /** The DAO whose 31 methods this rule is about. Its own file is not a caller of itself. */
    private static final String DAO = "SuspicionDao";

    /** @see #DAO */
    private static final String DAO_FILE = "SuspicionDao.java";

    /** The port the prove path's writes go through, named in the failure messages. */
    private static final String PORT = "tech.mikhailov.fsm.orch.usecase.MarkerRepository";

    /**
     * THE FILES THAT MAY HOLD {@link #DAO}, AND THE ARGUMENT EACH ONE MAKES.
     *
     * <p>Two files that used to be on this list are not: {@code ProveWriter} and
     * {@code ClaimReleaseListener} took a {@code SuspicionDao} for one purpose — to construct a
     * {@code JdbcMarkerRepository} in their own constructors — and called no method on it, before or
     * after. They took the port instead, the composition root builds the adapter, and neither of them
     * can now be handed anything a use-case test cannot hand them too.
     */
    private static final List<Allowed> MAY_NAME_THE_DAO = List.of(
            new Allowed("JdbcMarkerRepository.java",
                    "THE ADAPTER. Naming the DAO is its entire job — it is four lines of translation "
                    + "from the domain's types to the values the statements bind, and it is what the "
                    + "port means by an implementation."),
            new Allowed("BatchConfig.java",
                    "THE COMPOSITION ROOT. Something has to name a concrete adapter or no port can ever "
                    + "be constructed, and a configuration class is where that is supposed to happen. It "
                    + "hands MarkerRepository and ArtifactRepository to the two writers and the DAO "
                    + "itself only to the three beans argued below."),
            new Allowed("SuspicionReader.java",
                    "CONCURRENCY CONTROL, AND A PORT HERE WOULD REINTRODUCE THE LIE. Every method it "
                    + "calls — claimNext, claimAnotherSample, claimKey, flagStranded — is classified "
                    + "CONCURRENCY on the DAO: the WHERE clause is not a guard in front of a decision, "
                    + "it IS the decision, and only the database can take it. A port would invite a fake "
                    + "queue, a fake queue cannot adjudicate a race between two drains, and a green test "
                    + "over one would say nothing whatever about production — which is the exact defect "
                    + "MarkerRepositoryContract exists to catch. The reader is also itself the adapter: "
                    + "there is no interactor behind it for a port to serve. Covered against the real H2 "
                    + "by ClaimExclusivityTest and TheClaimIsTheOnePredicateThatStaysInSqlTest."),
            new Allowed("IngestTasklet.java",
                    "A DIFFERENT LIFECYCLE. deleteAll, allKeys and insertNew raise and discard the "
                    + "backlog; MarkerRepository is the four writes the PROVE path makes on a marker it "
                    + "is holding. Widening it with a bulk insert and a truncate would give the port two "
                    + "unrelated jobs and every implementor half of each. There is no double to be "
                    + "unfaithful either: AReIngestIsSafeByDefaultTest and IngestJobTest run this "
                    + "against the real H2 through the real job."),
            new Allowed("ResetPolicy.java",
                    "A READ THE PORT DOES NOT EXPOSE AND SHOULD NOT. census() is two COUNTs — the "
                    + "backlog's size and how much of it carries a verdict — and MarkerRepository has no "
                    + "reads at all, deliberately: all four of its methods return an update count. It "
                    + "already has the seam it needs, since census() is overridable so a policy test can "
                    + "state a backlog without a database."),
            new Allowed("DashboardService.java",
                    "THE READ MODEL, WHICH NEEDS WHOLE ROWS. findAll, find and countsByStatus feed the "
                    + "wire shape /api/state is a contract for; the write port carries none of them and "
                    + "adding them would let the read path drag the prove path's port around behind it. "
                    + "Every read here degrades rather than fails, which is a property of this class and "
                    + "not of the backlog."),
            new Allowed("StartupReconciler.java",
                    "A SET SELECTION, NOT A GUARD ON ONE MARKER. reconcileInFlight is one UPDATE over "
                    + "every row a dead JVM left claimed, with no marker in hand to ask an entity about "
                    + "— MarkerTransition says the same thing about it in its own javadoc. Routing it "
                    + "through a per-marker port would mean reading 282 rows to write back a subset."));

    /**
     * THE FILES THAT MAY NAME THE BACKLOG'S TABLES IN SQL WITHOUT GOING THROUGH {@link #DAO}.
     *
     * <p>All three are READS, all three are narrower than anything the DAO offers, and none of them
     * decides anything about a marker's lifecycle. A WRITE appearing on this list would be a different
     * conversation: the DAO is where the statements that move a row live, and it is the file whose
     * javadoc classifies every predicate in it.
     */
    private static final List<Allowed> MAY_QUERY_THE_TABLES = List.of(
            new Allowed("LiveWatcher.java",
                    "TWO COLUMNS, EVERY TWO SECONDS, FOR THE LENGTH OF A 26-HOUR RUN. findAll() returns "
                    + "whole rows and three of the columns are CLOBs, so the same poll through the DAO "
                    + "is megabytes of garbage a minute for data this class never reads. The projection "
                    + "names only the primary key and the status column, both fixed by schema.sql, so "
                    + "there is no column list here to drift out of step with the DAO's."),
            new Allowed("CommentDao.java",
                    "A JOIN THE DAO CANNOT EXPRESS. The comment read model needs marker columns beside "
                    + "comment columns in one row, which is a shape neither table's DAO owns; the second "
                    + "statement is an existence check on dedup_key, so a comment cannot be filed "
                    + "against a marker that is not there. Neither writes to the backlog."),
            new Allowed("DatabaseHealth.java",
                    "A LIVENESS PROBE. COUNT(*) against the one table that must be readable for "
                    + "anything else to work; what it reports is whether the datasource answers, not "
                    + "anything about a marker. Going through the DAO would make the health endpoint "
                    + "depend on the DAO staying cheap."));

    /**
     * What makes a string literal a query against the backlog.
     *
     * <p>UPPERCASE KEYWORDS ONLY, on the same reasoning {@code NoQueryIsBuiltFromANonConstantTest}
     * spells out: every query in this codebase capitalises them, the reactor is full of prose that does
     * not, and a case-insensitive {@code FROM} would flag sentences instead of statements. The four
     * keywords are the four ways a table name can be introduced — {@code FROM}, {@code INTO} (INSERT
     * and MERGE), {@code UPDATE} and {@code JOIN}.
     */
    private static final Pattern BACKLOG_QUERY =
            Pattern.compile("\\b(?:FROM|INTO|UPDATE|JOIN)\\s+(suspicions|infra_strikes)\\b");

    // ---- the two rules ---------------------------------------------------------------------------

    @Test
    void theOnlyFilesThatNameTheBacklogDaoAreItsAdapterItsRootAndTheArguedExceptions()
            throws Exception {
        List<Path> sources = mainSources();

        List<String> violations = daoCallers(sources, MAY_NAME_THE_DAO);

        assertThat(violations)
                .as("each file below holds %s directly, and is not one of the %d exceptions argued in "
                        + "%s.MAY_NAME_THE_DAO. The four writes the prove path makes on a marker go "
                        + "through %s, which has an in-memory implementation a use-case test can inject "
                        + "and a contract holding it to the same rules as the SQL. A caller that takes "
                        + "the DAO instead is a caller none of that reaches. Either take the port — "
                        + "which is usually a change to one constructor and one @Bean method, since the "
                        + "composition root already builds the adapter — or add the file here with the "
                        + "argument for why the port is the wrong door for it. What must not happen is "
                        + "another caller that nobody decided on",
                        DAO, MAY_NAME_THE_DAO.size(),
                        NoNewCallerReachesTheBacklogAroundItsPortTest.class.getSimpleName(), PORT)
                .isEmpty();
    }

    /**
     * …AND THE DOOR CANNOT BE WALKED AROUND BY TYPING THE SQL YOURSELF.
     *
     * <p>Without this the rule above is theatre. Any class in this module is one {@code @Autowired
     * JdbcTemplate} away from the table, and one file in the tree already reaches it that way rather
     * than through the DAO — so this is not a hypothetical bypass, it is the bypass that is already in
     * use, argued and named below.
     */
    @Test
    void nothingOutsideTheDaoWritesItsOwnSqlAgainstTheBacklogTables() throws Exception {
        List<Path> sources = mainSources();

        List<String> violations = backlogQueries(sources, MAY_QUERY_THE_TABLES);

        assertThat(violations)
                .as("each line below is SQL naming `suspicions` or `infra_strikes` outside %s. Those two "
                        + "tables are the marker lifecycle: the DAO is the file where every predicate on "
                        + "them is classified as a RULE or as CONCURRENCY CONTROL, and a statement "
                        + "written somewhere else carries neither classification nor any of the tests "
                        + "that hold the classification up. A read that the DAO does not offer is a "
                        + "method the DAO should offer; if it genuinely must live where it is, add the "
                        + "file to %s.MAY_QUERY_THE_TABLES and say why. A WRITE belongs in the DAO with "
                        + "no exceptions — it is a transition, and transitions are what this table is",
                        DAO_FILE,
                        NoNewCallerReachesTheBacklogAroundItsPortTest.class.getSimpleName())
                .isEmpty();
    }

    /**
     * …AND THE PROVE PATH IS ON THE OTHER SIDE OF THE DOOR, which is what makes both rules worth
     * passing. A dependency rule is trivially satisfied by a port nobody implements and nobody calls.
     */
    @Test
    void theTwoWritersOfTheProvePathTakeThePortAndNotTheTable() throws Exception {
        Path batch = mainSourceRoot().resolve("tech/mikhailov/fsm/orch/batch");

        String writer = codeOf(batch.resolve("ProveWriter.java"));
        assertThat(writer)
                .as("ProveWriter drives RecordProvenMarker, whose whole rule is that the artifact is "
                        + "written BEFORE the marker is retired. It must be constructible from the two "
                        + "ports that use case declares, or that rule can only ever be exercised with a "
                        + "datasource under it")
                .contains("MarkerRepository")
                .contains("ArtifactRepository")
                .doesNotContain(DAO);

        String listener = codeOf(batch.resolve("ClaimReleaseListener.java"));
        assertThat(listener)
                .as("ClaimReleaseListener drives ReleaseClaim and ChargeInfrastructureFailures, and "
                        + "BOTH of them branch on an update count of zero — `something else settled this "
                        + "marker, so this failure says nothing about it`. That branch is the one a "
                        + "hand-written double got wrong, so the object driving it has to be injectable "
                        + "with the double the contract holds honest")
                .contains("MarkerRepository")
                .doesNotContain(DAO);
    }

    // ---- neither list is allowed to rot ------------------------------------------------------------

    @Test
    void everyExceptionNamesOneRealFileAndIsStillEarningItsPlace() throws Exception {
        List<Path> sources = mainSources();
        Set<String> namesTheDao = new LinkedHashSet<>(daoCallers(sources, List.of()));
        Set<String> queriesTheTables = new LinkedHashSet<>(backlogQueries(sources, List.of()));

        List<String> stale = new ArrayList<>();
        stale.addAll(unused(sources, MAY_NAME_THE_DAO, namesTheDao, "names " + DAO));
        stale.addAll(unused(sources, MAY_QUERY_THE_TABLES, queriesTheTables,
                "queries the backlog tables"));

        assertThat(stale)
                .as("an entry on either list is a standing exception to a rule, so it has to still be "
                        + "earning it. An entry that suppresses nothing reads as a blessing on a file "
                        + "that may since have been routed through the port, renamed, or deleted — and "
                        + "it would silently cover whatever takes that name next. Delete the lines below "
                        + "rather than leaving them lying around")
                .isEmpty();
    }

    /** The entries in {@code allowed} that no longer suppress anything in {@code found}. */
    private static List<String> unused(List<Path> sources, List<Allowed> allowed, Set<String> found,
                                       String what) {
        List<String> stale = new ArrayList<>();
        for (Allowed entry : allowed) {
            long files = sources.stream()
                    .filter(path -> path.getFileName().toString().equals(entry.file()))
                    .count();
            if (files != 1) {
                stale.add(entry.file() + " names " + files + " files in the reactor, not one");
                continue;
            }
            boolean suppressing = found.stream()
                    .anyMatch(line -> line.contains("/" + entry.file()));
            if (!suppressing) {
                stale.add(entry.file() + " is excused above, but nothing in it " + what + " any more");
            }
        }
        return stale;
    }

    // ---- the scanner checks something --------------------------------------------------------------

    /**
     * THE SCANNER, RUN OVER SOURCE IT IS TOLD THE ANSWER FOR.
     *
     * <p>Without this both rules are one edit away from being green no-ops, and a scanner that had
     * quietly stopped matching would report an empty violation list over a tree full of them and look
     * exactly like success. The comment cases are not padding: every file in this repository argues
     * about the DAO at length, {@code WorkModel} and {@code LiveWatcher} mention it in javadoc and
     * nowhere else, and a scanner that read comments would name both of them and be switched off within
     * a week.
     */
    @Test
    void theScannerReadsTheCodeAndNotTheProseThatArguesAboutIt() {
        assertThat(namesTheDao("""
                import tech.mikhailov.fsm.orch.dao.SuspicionDao;
                class Writer {
                    Writer(SuspicionDao suspicions) { this.dao = suspicions; }
                }
                """))
                .as("the shape the first rule exists for: the DAO as a constructor argument")
                .isTrue();

        assertThat(namesTheDao("""
                class WorkModel {
                    /**
                     * `proving` is the claim token — see
                     * {@link tech.mikhailov.fsm.orch.dao.SuspicionDao#STATUS_PROVING}, which is
                     * explicit that it is not an outcome.
                     */
                    // SuspicionDao#findAll would return the CLOBs too.
                    static final Set<String> UNSETTLED = SuspicionStatus.UNSETTLED;
                }
                """))
                .as("WorkModel's actual shape: named twice in prose, held nowhere. A rule that counted "
                        + "this would be reporting a file with no import, no field and no call")
                .isFalse();

        assertThat(namesTheDao("""
                class Reader {
                    private static final String WHY = "not SuspicionDao#findAll: it returns CLOBs";
                }
                """))
                .as("a string literal is code, not a comment — the sentence is inside the program and "
                        + "the scanner does not get to pretend otherwise. Better a false positive with "
                        + "a one-word fix than a stripper that guesses")
                .isTrue();

        assertThat(backlogQueriesIn("""
                class Watcher {
                    private static final String SCAN = "SELECT dedup_key, status FROM suspicions";
                    private static final String STRIKE = "UPDATE infra_strikes SET strikes = ?";
                    private static final String JOIN =
                            "SELECT c.body FROM marker_comments c LEFT JOIN suspicions s ON s.k = c.k";
                }
                """))
                .as("all three ways a table name is introduced, and both backlog tables")
                .containsExactly("suspicions", "infra_strikes", "suspicions");

        assertThat(backlogQueriesIn("""
                class Routes {
                    List<String> describe() {
                        return List.of("one row from Get new suspicions", "the suspicions table");
                    }
                }
                """))
                .as("English is not SQL. The engine describes its own nodes in sentences that contain "
                        + "the word, and a keyword list that matched lower case would flag every one")
                .isEmpty();

        assertThat(backlogQueriesIn("""
                class Dao {
                    /** This used to read "SELECT * FROM suspicions" before the projection. */
                    // return jdbc.query("SELECT status FROM suspicions");
                    List<Bug> all() { return jdbc.query("SELECT state FROM bugs", MAPPER); }
                }
                """))
                .as("the files here quote the statements they decided not to write; a scanner that read "
                        + "comments would be unusable in this repository")
                .isEmpty();

        assertThat(stripComments("""
                String url = "https://example.test/api"; // a comment
                String kept = "//not a comment//";
                """))
                .as("the stripper is string-aware, or a URL in a literal eats the rest of its line and "
                        + "takes any violation on that line with it")
                .contains("https://example.test/api")
                .contains("//not a comment//")
                .doesNotContain("a comment\"");
    }

    // ---- the scanner --------------------------------------------------------------------------------

    /** Every source outside {@link #DAO_FILE} that names the DAO in code, as a path, in file order. */
    private static List<String> daoCallers(List<Path> sources, List<Allowed> allowed)
            throws IOException {
        Set<String> excused = names(allowed);
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String name = source.getFileName().toString();
            if (name.equals(DAO_FILE) || excused.contains(name)) {
                continue;
            }
            if (namesTheDao(Files.readString(source, StandardCharsets.UTF_8))) {
                violations.add(source.toString());
            }
        }
        return violations;
    }

    /** Every source outside {@link #DAO_FILE} with SQL against a backlog table, as {@code path:table}. */
    private static List<String> backlogQueries(List<Path> sources, List<Allowed> allowed)
            throws IOException {
        Set<String> excused = names(allowed);
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String name = source.getFileName().toString();
            if (name.equals(DAO_FILE) || excused.contains(name)) {
                continue;
            }
            for (String table : backlogQueriesIn(Files.readString(source, StandardCharsets.UTF_8))) {
                violations.add(source + ":" + table);
            }
        }
        return violations;
    }

    private static Set<String> names(List<Allowed> allowed) {
        return allowed.stream().map(Allowed::file)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Does this source hold the DAO anywhere the compiler can see it? */
    private static boolean namesTheDao(String source) {
        return stripComments(source).contains(DAO);
    }

    /** The backlog tables this source names in SQL, in the order the statements appear. */
    private static List<String> backlogQueriesIn(String source) {
        List<String> tables = new ArrayList<>();
        Matcher matcher = BACKLOG_QUERY.matcher(stripComments(source));
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        return tables;
    }

    /**
     * Comments out, STRING LITERALS IN — {@link TestSource#stripComments}, which is where this one
     * moved to on 2026-08-06 so that the two guards that had a NAIVE copy of it read the same text
     * this one does.
     */
    private static String stripComments(String source) {
        return TestSource.stripComments(source);
    }

    /** One file, comments removed — what {@link #theTwoWritersOfTheProvePathTakeThePortAndNotTheTable} reads. */
    private static String codeOf(Path source) throws IOException {
        assertThat(source)
                .as("%s is a file this rule reads by name; a rule with nothing to read passes for the "
                        + "wrong reason", source)
                .isRegularFile();
        return stripComments(Files.readString(source, StandardCharsets.UTF_8));
    }

    // ---- where the sources are -----------------------------------------------------------------------

    /**
     * {@code src/main/java} of EVERY module in the reactor.
     *
     * <p>The orchestrator is the only module with a datasource today, and a check scoped to it would go
     * quiet on the day somebody moves the persistence — which is precisely the day it is wanted.
     */
    private static List<Path> mainSources() throws URISyntaxException, IOException {
        Path reactor = module().getParent();

        List<Path> modules;
        try (Stream<Path> children = Files.list(reactor)) {
            modules = children.map(path -> path.resolve("src").resolve("main").resolve("java"))
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        }
        assertThat(modules)
                .as("the reactor at %s should hold engine, orchestrator and runner; a rule that found "
                        + "no modules would pass by scanning nothing, which is the no-op failure every "
                        + "source-scanning test dies of", reactor)
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
        assertThat(sources)
                .as("with no sources present these rules would pass by finding nothing to check")
                .hasSizeGreaterThan(100);
        return sources;
    }

    /** This module's {@code src/main/java}. */
    private static Path mainSourceRoot() throws URISyntaxException, IOException {
        return module().resolve("src").resolve("main").resolve("java");
    }

    /** The module this test was compiled into, derived from where the class was actually loaded from. */
    private static Path module() throws URISyntaxException, IOException {
        Path testClasses = Path.of(NoNewCallerReachesTheBacklogAroundItsPortTest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        // target/test-classes -> target -> the module.
        return testClasses.getParent().getParent();
    }
}
