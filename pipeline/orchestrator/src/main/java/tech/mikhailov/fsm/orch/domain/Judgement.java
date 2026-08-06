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
 * <p>THE FIRST THING IT REFUSES: a status nothing in the pipeline claims. {@code SuspicionDao.settle}
 * takes a raw {@code String} and writes whatever arrives, so an unrecognised spelling becomes a live
 * row that no {@code GROUP BY status} on the dashboard, no {@code countSettled} guard on a reset and no
 * drain can see — a marker that is neither queued nor settled and that nothing will ever look at again.
 * Holding {@link SuspicionStatus} rather than a String makes that state unrepresentable past
 * {@link #of}, which is the only door in.
 *
 * <p>AND THE SECOND: {@code proving}. That one IS claimed — it is {@code SuspicionDao}'s queue token,
 * not a spelling nobody owns — which is exactly why the check above lets it through and why it needs
 * its own. A settlement at {@code proving} is a marker returned to the state a prover holds it in, and
 * the row then belongs to nothing: the drain's {@code WHERE status = 'new'} passes it by and the
 * settled groupings do not count it. Nothing in the engine writes it; this refusal is cheap and is
 * what keeps "nothing writes it" a fact rather than a habit.
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
     *                   the queue, and that decision belongs to the engine. It may NOT be
     *                   {@code proving} — see below.
     * @throws UnknownStatus when nothing in {@link SuspicionStatus} claims that spelling
     * @throws IllegalArgumentException when the status is {@code proving}
     */
    public static Judgement of(MarkerId marker, String statusWire, long attempts, String note,
                               String anchor, String anchorStatus, Artifact artifact,
                               ProveTrace trace) {
        SuspicionStatus status = SuspicionStatus.of(statusWire);
        if (status == null) {
            throw new UnknownStatus(marker, statusWire);
        }
        // `proving` IS claimed — by SuspicionDao, as its queue token — so it passes the check above,
        // and it is the one known spelling that is not an outcome. Settling at it writes the marker
        // back into the state a prover holds it in: claimNext selects `status = 'new'`, so no drain
        // would offer the row again, and nothing would say so until a restart's reconciliation swept
        // it up hours later. Nothing in the engine produces it today — Verdict's switch cannot reach
        // it — and this line is what keeps a stage that starts to a compile-free accident instead of a
        // silently parked marker.
        if (status == SuspicionStatus.PROVING) {
            // The message names no adapter: this package may not, and the source guard reads string
            // literals as code precisely so that a class name cannot slip in through one.
            throw new IllegalArgumentException("the engine settled " + marker + " at `proving`, which "
                    + "is a CLAIM and not an outcome: it is the token the queue writes while a prover "
                    + "holds the row. Writing it as a settlement parks the marker where no drain "
                    + "selects it and no dashboard grouping calls it settled, until the next restart "
                    + "requeues it. Settle at the status the argument reached, or release the claim");
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
