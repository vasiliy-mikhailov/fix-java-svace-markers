package tech.mikhailov.fsm.orch.domain;

/**
 * The identity of one backlog marker — {@code suspicions.dedup_key}, and the key every artifact,
 * comment and infra streak points back at.
 *
 * <p>NEVER CLIPPED, and both DAOs already say why in their own words: two keys cut to the same width
 * would silently become one marker, and one artifact would then answer a question it was not written
 * about. So this type carries the key whole and refuses the two values that would make a row
 * unaddressable — null and blank. An over-long key is an ingester bug and fails in the ingest's own
 * transaction, which rolls back as a whole; a blank one would update every row or none.
 *
 * <p>A TYPE AND NOT A {@code String} because the prove path handles two keys that are spelled the same
 * and are not the same fact: the key this run CLAIMED, and the {@code suspicion_key} the engine echoed
 * back on the artifact. {@code ProvenMarker}'s javadoc records what settling on the wrong one costs —
 * an update that matches no row and a marker parked in {@code proving} until the next restart.
 */
public record MarkerId(String value) {

    public MarkerId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a marker id is the dedup_key of a row in the backlog "
                    + "and cannot be blank: an update keyed on a blank matches nothing, and a claim "
                    + "keyed on one would be a claim on no marker at all");
        }
    }

    /** @see MarkerId */
    public static MarkerId of(String value) {
        return new MarkerId(value);
    }

    /** The key itself, so a log line or a note reads as it always did. */
    @Override
    public String toString() {
        return value;
    }
}
