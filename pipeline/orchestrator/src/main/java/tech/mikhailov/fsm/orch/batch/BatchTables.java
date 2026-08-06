package tech.mikhailov.fsm.orch.batch;

import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * THE NAME OF SPRING BATCH'S TABLES, CHECKED ONCE, WHERE IT IS READ.
 *
 * <p>Three classes read the run history out of Spring Batch's own metadata tables with plain SQL —
 * {@link tech.mikhailov.fsm.orch.dao.JobRunDao}, {@link StaleExecutionReconciler} and
 * {@link IngestHistory} — and each of them has to write the table's name into a {@code from} clause.
 * The name is {@code spring.batch.jdbc.table-prefix} followed by a fixed suffix, and the prefix is
 * deployment configuration: it is what Spring Batch itself uses to build the same statements, and a
 * deployment that renames the tables and does not tell these three gets a dashboard reporting zero
 * machine time, a restart repair that finds nothing to repair, and an ingest history that reads as
 * "no ingest has ever run" — three silent wrong answers rather than an error.
 *
 * <h2>WHY IT IS NOT A BIND PARAMETER</h2>
 *
 * <p>Because there is no such thing. JDBC binds VALUES; a {@code ?} is a placeholder in the parse
 * tree where a literal goes, and a table name is not a literal — it is resolved before the plan
 * exists. Every driver rejects {@code select * from ?}. So the choice here is not "concatenate or
 * parameterise", it is "concatenate a checked string or concatenate an unchecked one", and pretending
 * otherwise — a wrapper that looked like binding and was not — would be worse than the concatenation,
 * because the next reader would stop looking.
 *
 * <h2>SO IT IS CHECKED, AT BOOT, AND THE PROCESS REFUSES</h2>
 *
 * <p>The check is here rather than at the four query sites for the ordinary reason: one place that
 * cannot be forgotten beats four that can. It runs in a constructor of a bean the three consumers
 * depend on, so a bad value fails bean creation and the context never starts.
 *
 * <p>REFUSING IS THE POINT, and refusing AT BOOT is most of it. The alternative — let it through and
 * let the query fail — produces a dashboard that answers 500 on its first poll, hours after the
 * deploy, with a stack trace about a missing table. That sends the reader to the database, to the
 * schema, to Spring Batch's initialiser, and eventually to a property they did not know existed. A
 * process that will not start, with the property and the value in the message, sends them to the one
 * line they typed.
 *
 * <p>WHAT COUNTS AS A NAME. A letter, then letters, digits and underscores — an unquoted identifier,
 * which is all Spring Batch's own schema ever uses. Two extensions, both real: the EMPTY prefix, which
 * is the legitimate way to ask for unprefixed tables, and ONE schema qualifier
 * ({@code REPORTING.BATCH_}), which is how a deployment puts the metadata in a schema of its own and
 * is documented Spring Batch usage. Each half of a qualified name is checked as its own identifier, so
 * neither extension admits a quote, a space, a semicolon or a comment marker — there is no value this
 * accepts that can close a string or start a second statement.
 */
@Component
public class BatchTables {

    /** The property, named here once so the refusal message and the {@code @Value} cannot drift. */
    public static final String PROPERTY = "spring.batch.jdbc.table-prefix";

    /** Spring Batch's own default, and this deployment's: nothing sets the property. */
    public static final String DEFAULT = "BATCH_";

    /**
     * An unquoted identifier, optionally qualified by one schema.
     *
     * <p>Anchored by {@link java.util.regex.Matcher#matches()} rather than {@code find()}, which is
     * the whole difference between a check and a decoration: {@code find()} would accept
     * {@code BATCH_"; drop table suspicions --} on the strength of its first eight characters.
     */
    static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)?");

    private final String prefix;

    public BatchTables(@Value("${" + PROPERTY + ":" + DEFAULT + "}") String prefix) {
        this.prefix = validated(prefix);
    }

    /**
     * The prefix, known to be an identifier because this object exists.
     *
     * <p>That is the contract the three consumers rely on, and it is why they take this type rather
     * than a {@code String}: a constructor that cannot complete on a bad value is a guarantee the
     * compiler helps carry, where four separate {@code @Value} injections are four separate chances to
     * skip the check.
     */
    public String prefix() {
        return prefix;
    }

    /**
     * @param prefix the configured value, or null when the property resolved to nothing
     * @return it unchanged, when it is a name
     * @throws IllegalStateException naming the property and the value, when it is not
     */
    public static String validated(String prefix) {
        if (prefix != null && (prefix.isEmpty() || IDENTIFIER.matcher(prefix).matches())) {
            return prefix;
        }
        throw new IllegalStateException(refusal(prefix));
    }

    /**
     * What an operator reads when the process refuses.
     *
     * <p>It quotes the value: the two spellings this is likeliest to catch are a trailing space and a
     * copied-in quote character, and neither is visible unquoted. Every sentence of it is about the
     * one line they typed.
     */
    private static String refusal(String prefix) {
        return PROPERTY + " is " + (prefix == null ? "not set" : "\"" + prefix + "\"")
                + ", which is not a table-name prefix. It names the Spring Batch metadata tables the "
                + "run history, the restart repair and the ingest history read, and a table name "
                + "cannot be a bind parameter in any driver — so it is checked here, on the way up, "
                + "rather than becoming a broken query on the first dashboard poll hours later. "
                + "Allowed: a letter followed by letters, digits and underscores, optionally "
                + "qualified by one schema (REPORTING.BATCH_), or empty for unprefixed tables. "
                + "Leave the property unset for the default, " + DEFAULT + ".";
    }
}
