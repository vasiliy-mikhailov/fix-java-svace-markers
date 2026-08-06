package tech.mikhailov.fsm.orch.web;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Is the database open and answering? Asked by running a query, because there is no other way to know.
 *
 * <p>A PROBE THAT CANNOT FAIL IS A CONSTANT WITH A URL. A {@code /healthz} that returns a hardcoded
 * 200 {@code ok} answers {@code ok} on every request through a run in which every prove is failing on
 * a refused connection, so a restart policy or a Caddy check wired to it can never fire — and its
 * existence is the reason nobody adds a real one.
 *
 * <p>WHY THE MARKER TABLE AND NOT {@code SELECT 1}. This process is a queue, a schedule and a durable
 * marker table; a {@code SELECT 1} proves the pool can hand out a connection and nothing else. The
 * failure this whole codebase keeps meeting is the SILENT one — a database that is open but whose schema
 * did not apply serves a dashboard of zeros and a prover with an empty queue, and both look like a
 * finished run. Counting {@code suspicions} costs one index scan over a table of 282 rows and answers
 * the question the caller actually has. It cannot produce a false alarm: {@code spring.sql.init} runs
 * {@code schema.sql} with {@code continue-on-error: false} on every start, so a process that is up at
 * all has that table.
 *
 * <p>IT NEVER THROWS. A probe that propagates its own failure would be turned into a 500 by
 * {@link DashboardController}'s exception handler, which is a different status from the 503 a proxy and
 * a container restart policy act on, and would lose the reason on the way.
 *
 * <p>{@link HealthIndicator} as well, so {@code /actuator/health} reports the same finding as
 * {@code /healthz} rather than a second opinion about the same database.
 */
@Component
public class DatabaseHealth implements HealthIndicator {

    /**
     * The probe. Reads the table the dashboard and the prover both read.
     *
     * <p>{@code COUNT(*)} rather than {@code SELECT ... LIMIT 1} so that an empty backlog — an entirely
     * normal state between runs — is a healthy answer and not an absent row.
     */
    static final String QUERY = "SELECT COUNT(*) FROM suspicions";

    /** What a failure says when the exception carries no message of its own. */
    private static final String NOTHING_TO_SAY = "the query failed with no message";

    private final JdbcTemplate jdbc;

    public DatabaseHealth(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One finding.
     *
     * @param up     whether the database is open and answered
     * @param detail why not, in the words the driver used; empty when it is up, because a healthy
     *               answer with an explanation attached invites a caller to parse it
     */
    public record Probe(boolean up, String detail) {

        /**
         * Named {@code answered}/{@code failed} and NOT {@code up}/{@code down}, because a record
         * component called {@code up} already declares a method {@code up()} — a static factory of the
         * same name is read by javac as an attempt to override that accessor, and the compiler rejects
         * it as "invalid accessor method … must be public" rather than as the name clash it is.
         */
        static Probe answered() {
            return new Probe(true, "");
        }

        static Probe failed(String detail) {
            return new Probe(false, detail);
        }
    }

    public Probe probe() {
        try {
            Long count = jdbc.queryForObject(QUERY, Long.class);
            if (count == null) {
                // A connection that answered NULL to a COUNT is not a working database; it is a proxy,
                // a driver in a bad state, or a query that did not run. Never a healthy answer.
                return Probe.failed("the database answered nothing to `" + QUERY + "`");
            }
            return Probe.answered();
        } catch (Exception e) {
            // EVERY failure, with no attempt to sort a closed pool from a missing table from a corrupt
            // file: the caller's only correct response to any of them is to stop sending traffic here.
            String message = e.getMessage();
            return Probe.failed(message == null || message.isBlank() ? NOTHING_TO_SAY : message);
        }
    }

    @Override
    public Health health() {
        Probe probe = probe();
        if (probe.up()) {
            return Health.up().build();
        }
        // The detail is only rendered when management.endpoint.health.show-details says so; the STATUS
        // is what makes /actuator/health answer 503, and that part is never hidden.
        return Health.down().withDetail("database", probe.detail()).build();
    }
}
