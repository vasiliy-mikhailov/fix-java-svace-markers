package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tech.mikhailov.fsm.orch.OrchestratorApplication;

/**
 * THE TABLE NAME IS CHECKED, AND A BAD ONE STOPS THE PROCESS RATHER THAN THE DASHBOARD.
 *
 * <p>{@code spring.batch.jdbc.table-prefix} is written into the {@code from} clause of four queries
 * across three classes, because JDBC has no bind parameter for a table name and never will — a
 * {@code ?} is a slot where a VALUE goes, and a table is resolved before the plan that would hold the
 * value exists. So the concatenation stays and the string is checked instead, once, in
 * {@link BatchTables}, which the three readers take as a constructor argument so that no fourth reader
 * can pick up an unchecked one.
 *
 * <p>WHY IT REFUSES INSTEAD OF DEGRADING, which is the part worth a test. All four call sites already
 * swallow {@link org.springframework.dao.DataAccessException} and answer empty, deliberately: a
 * context running with batch switched off still has to serve the dashboard through a 26-hour prove. A
 * misspelled prefix therefore does NOT produce an error anywhere — it produces a dashboard reporting
 * zero machine time, a restart repair that finds nothing to repair, and an ingest history that reads
 * "no ingest has ever run", all with a healthy container and an empty log. That is the same class of
 * failure as the outage {@link StaleExecutionReconciler} exists for: every signal cheerful, one number
 * quietly wrong. A process that will not start says it once, on the way up, next to the value.
 */
class ABadTablePrefixRefusesToStartTest {

    /**
     * The spelling a reader of {@code JobRunDao} would file, made concrete.
     *
     * <p>It is what an anchored check catches and a {@code find()} would not: the first eight
     * characters are a perfectly good prefix, and everything that matters comes after them.
     */
    private static final String POISON = "BATCH_\"; DROP TABLE suspicions; --";

    // ---- what a name is ---------------------------------------------------------------------------

    @Test
    void aNameIsAcceptedInEverySpellingADeploymentActuallyUses() {
        List<String> names = List.of(
                BatchTables.DEFAULT,        // the shipped default, and this deployment's
                "",                         // unprefixed tables — legitimate, and Spring Batch's own
                "B",                        // one letter is a name
                "batch_",                   // lower case is a name
                "B1_x9_",                   // digits and underscores after the first letter
                "REPORTING.BATCH_");        // one schema qualifier, each half its own identifier

        for (String name : names) {
            assertThatCode(() -> BatchTables.validated(name))
                    .as("`%s` is an unquoted identifier and refusing it would break a deployment "
                            + "that has done nothing wrong", name)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * …AND EVERYTHING ELSE IS REFUSED, WITH THE PROPERTY AND THE VALUE IN THE MESSAGE.
     *
     * <p>The list is the ways this actually goes wrong, not a taxonomy: a payload someone pasted, a
     * trailing space from a YAML line, a quote, a semicolon, a hyphen where an underscore was meant, a
     * leading digit, and the property resolving to nothing at all. A message that named the rule but
     * not the VALUE would leave an operator comparing what they think they typed against what the
     * process actually read, which for the trailing-space case is the entire difficulty.
     */
    @Test
    void everySpellingThatIsNotANameIsRefusedAndTheMessageNamesThePropertyAndTheValue() {
        List<String> refused = List.of(
                POISON,
                "BATCH_ ",                  // a trailing space, invisible in the YAML and in a log
                " BATCH_",
                "BATCH_'",
                "BATCH_;",
                "BATCH-",
                "1BATCH_",
                "BATCH_ JOB",
                "A.B.C",                    // one qualifier, not a path
                "\"BATCH_\"");              // a quoted identifier is a different feature, not this one

        for (String value : refused) {
            assertThatThrownBy(() -> BatchTables.validated(value))
                    .as("`%s` is not a table-name prefix and must not reach a query", value)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(BatchTables.PROPERTY)
                    .hasMessageContaining(value);
        }

        assertThatThrownBy(() -> BatchTables.validated(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BatchTables.PROPERTY);
    }

    /** The check is anchored, which is the whole difference between a check and a decoration. */
    @Test
    void theCheckIsAnchoredSoAGoodPrefixDoesNotVouchForWhatFollowsIt() {
        assertThat(BatchTables.IDENTIFIER.matcher(POISON).find())
                .as("an unanchored check WOULD pass this — if that ever stopped being true the "
                        + "assertion below would be proving nothing")
                .isTrue();

        assertThat(BatchTables.IDENTIFIER.matcher(POISON).matches()).isFalse();
    }

    // ---- and the process will not start ------------------------------------------------------------

    /**
     * THE REAL APPLICATION, STARTED FOR REAL, AND REFUSING.
     *
     * <p>The unit tests above pin the rule; this one pins that the rule is WIRED — that the value
     * travels from the property into a bean nothing can start without, rather than into a static
     * method nobody calls. That distinction has cost this project six defects: a check that passes its
     * own tests while no production path reaches it is indistinguishable from a check that works.
     *
     * <p>The {@code test} profile because the point is the refusal, not the datasource: it swaps the
     * on-disk database for an in-memory one and turns off the scheduled tick, so this boots and dies
     * without touching anything on the filesystem.
     */
    @Test
    void aPrefixThatIsNotANameStopsTheProcessStarting() {
        ConfigurableApplicationContext[] leaked = new ConfigurableApplicationContext[1];
        try {
            assertThatThrownBy(() -> leaked[0] = boot(
                    "--spring.profiles.active=test",
                    "--" + BatchTables.PROPERTY + "=" + POISON))
                    .as("a dashboard that answers 500 on its first poll hours after the deploy sends "
                            + "the reader to the database, the schema and Spring Batch's initialiser "
                            + "before it sends them to the line they typed")
                    .hasStackTraceContaining(BatchTables.PROPERTY)
                    .hasStackTraceContaining(POISON)
                    .hasStackTraceContaining(BatchTables.class.getName());
        } finally {
            if (leaked[0] != null) {
                leaked[0].close();
            }
        }
    }

    /**
     * The real application, started for real, and NOT refusing.
     *
     * <p>Without this the test above passes for any reason a context might fail to start, and the
     * shipped configuration could be broken in exactly the way this class claims to be protecting.
     */
    @Test
    void theSameApplicationStartsWithTheShippedPrefix() {
        try (ConfigurableApplicationContext context = boot("--spring.profiles.active=test")) {
            assertThat(context.getBean(BatchTables.class).prefix())
                    .isEqualTo(BatchTables.DEFAULT);
        }
    }

    /**
     * The real application, off the real entry point.
     *
     * <p>Arguments rather than {@code properties(..)}: {@code SpringApplicationBuilder.properties}
     * lands in {@code defaultProperties}, the lowest-precedence source there is, so an override
     * written that way would lose to {@code application.yml} and the test would assert nothing.
     */
    private static ConfigurableApplicationContext boot(String... args) {
        return new SpringApplicationBuilder(OrchestratorApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
