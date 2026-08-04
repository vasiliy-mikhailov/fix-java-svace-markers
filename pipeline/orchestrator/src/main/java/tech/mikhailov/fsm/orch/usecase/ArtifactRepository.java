package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.Artifact;

/**
 * Where the evidence goes — the {@code bugs} row a completed prove leaves behind.
 *
 * <p>ONE METHOD, because the use case has one rule about it: it is written BEFORE the marker is
 * retired. See {@link RecordProvenMarker}.
 *
 * <p>UPSERT, NEVER INSERT, and the port says so rather than leaving it to the adapter's discretion: a
 * marker can be proved more than once — an infra failure returns it to the queue and the next attempt
 * writes the same key again, after a 20-minute build — so keyed replacement is what makes the last
 * completed attempt win, which is also the one whose attempt count the marker's own row is carrying.
 */
public interface ArtifactRepository {

    /** Insert or replace the artifact, keyed on the marker it argues. */
    void store(Artifact artifact);
}
