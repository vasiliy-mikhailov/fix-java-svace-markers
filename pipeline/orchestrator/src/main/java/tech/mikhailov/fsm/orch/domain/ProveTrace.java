package tech.mikhailov.fsm.orch.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EVERY PROMPT AND EVERY REPLY ONE PROVE PRODUCED — the critique record, when a deployment asked for
 * one.
 *
 * <p>It is a value and not a side effect on purpose. The prompts and the raw replies are locals inside
 * stages that have already returned by the time anything could go looking for them, so the chain hands
 * them back with the judgement; whether they are then written down is a deployment fact, and the use
 * case must not be able to fail on it.
 *
 * <p>{@link #EMPTY} is what a run with recording OFF carries, and it is a real value rather than a
 * null: the use case appends unconditionally and the journal declines an empty record, which is one
 * branch in the adapter instead of one in the policy. Nothing is built when nothing is recorded — the
 * record is assembled by the chain only when it is going to be kept.
 */
public record ProveTrace(Map<String, Object> record) {

    /** A prove that recorded nothing, because this deployment records nothing. */
    public static final ProveTrace EMPTY = new ProveTrace(Map.of());

    public ProveTrace {
        record = record == null ? Map.of() : new LinkedHashMap<>(record);
    }

    /** True when this run is not accumulating critiques, so there is nothing to append. */
    public boolean isEmpty() {
        return record.isEmpty();
    }
}
