package tech.mikhailov.fsm.orch.domain;

/**
 * The prover's half of {@code suspicions.note} — the audit line a marker carries when the ORCHESTRATOR,
 * and not the engine, is the one that moved it.
 *
 * <p>{@code [stage] text} is the form every note on that column uses, and the anchoring matters: three
 * documents tell an operator how to find markers by note with a {@code LIKE '[…]%'} pattern, and a
 * label that does not lead is a label those queries silently leave behind. The prefix is written once
 * here so the release note and the parking notice cannot drift apart from each other.
 */
public final class ProverNote {

    /** The prefix, in the {@code [stage] text} form every other note on this column uses. */
    private static final String PREFIX = "[prover] ";

    private ProverNote() {
    }

    /** {@code text}, labelled as the prover's. */
    public static String of(String text) {
        return PREFIX + text;
    }
}
