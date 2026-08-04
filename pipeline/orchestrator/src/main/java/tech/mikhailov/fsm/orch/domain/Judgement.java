package tech.mikhailov.fsm.orch.domain;

import tech.mikhailov.fsm.lib.SuspicionStatus;

/**
 * WHAT THE ENGINE ANSWERED ABOUT ONE MARKER — the five columns a settle writes, the artifact behind
 * them, and the critique record the run may have kept.
 *
 * <p>THE ORCHESTRATOR NEVER COMPUTES ONE. Which status a marker settles at is
 * {@code Verdict.nextSuspicionStatus} and {@code Verdict.settledBy}, an exhaustive switch with no
 * default arm, pinned by 6,910 differential cases against catalogues that cannot be regenerated. This
 * type is the SHAPE that answer has to arrive in, and validating a shape is not deciding an answer.
 *
 * <p>THE ONE THING IT REFUSES: a status nothing in the pipeline claims. {@code SuspicionDao.settle}
 * takes a raw {@code String} and writes whatever arrives, so an unrecognised spelling becomes a live
 * row that no {@code GROUP BY status} on the dashboard, no {@code countSettled} guard on a reset and no
 * drain can see — a marker that is neither queued nor settled and that nothing will ever look at again.
 * Holding {@link SuspicionStatus} rather than a String makes that state unrepresentable past
 * {@link #of}, which is the only door in.
 */
public record Judgement(MarkerId marker, SuspicionStatus status, long attempts, String note,
                        String anchor, String anchorStatus, Artifact artifact, ProveTrace trace) {

    public Judgement {
        if (marker == null) {
            throw new IllegalArgumentException("a judgement is about a marker");
        }
        if (status == null) {
            throw new IllegalArgumentException("a judgement names the status its marker settles at");
        }
        if (artifact == null) {
            throw new IllegalArgumentException("a judgement carries the artifact that argues it");
        }
        trace = trace == null ? ProveTrace.EMPTY : trace;
    }

    /**
     * Read one off the wire — the engine's item, already split into fields by the adapter.
     *
     * @param statusWire {@code Verdict}'s {@code suspicion_status}, verbatim. It may be {@code new}:
     *                   an infra error below the engine's ceiling and a pending retry both go back on
     *                   the queue, and that decision belongs to the engine.
     * @throws UnknownStatus when nothing in {@link SuspicionStatus} claims that spelling
     */
    public static Judgement of(MarkerId marker, String statusWire, long attempts, String note,
                               String anchor, String anchorStatus, Artifact artifact,
                               ProveTrace trace) {
        SuspicionStatus status = SuspicionStatus.of(statusWire);
        if (status == null) {
            throw new UnknownStatus(marker, statusWire);
        }
        return new Judgement(marker, status, attempts, note, anchor, anchorStatus, artifact, trace);
    }

    /**
     * A settled status this pipeline has no name for.
     *
     * <p>LOUD, and not a fallback. {@code SuspicionStatus.of} returns null rather than guessing
     * precisely so that a caller has to decide what an unrecognised status means and say so where it
     * decides; this is that decision, taken once: it means the engine and the orchestrator disagree
     * about the vocabulary of a column with 282 live rows in it, and the run must stop rather than add
     * a 283rd nobody can group.
     */
    public static final class UnknownStatus extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        public UnknownStatus(MarkerId marker, String statusWire) {
            super("the engine settled " + marker + " at `" + statusWire + "`, which is not a status "
                    + "this pipeline claims. Writing it would produce a row that no dashboard grouping, "
                    + "no drain and no reset guard can see. Add it to SuspicionStatus — and to the "
                    + "no-default switches that will then refuse to compile — or fix the stage that "
                    + "produced it");
        }
    }
}
