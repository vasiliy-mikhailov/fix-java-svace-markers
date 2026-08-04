package tech.mikhailov.fsm.orch.domain;

/**
 * WHY A QUESTION WAS NEVER ANSWERED — the one-line, greppable text that reaches a requeued marker's
 * note and, past the ceiling, its {@code infra_stuck} parking notice.
 *
 * <p>IT IS NOT A JUDGEMENT AND CANNOT BECOME ONE. That is the whole reason it is a type: the pipeline's
 * defining failure mode is an infrastructure fault recorded as a finding about the code, so the value
 * that says "the plumbing broke" travels in something that cannot be mistaken for a
 * {@link tech.mikhailov.fsm.lib.MarkerState} or a status.
 *
 * <p>NEVER BLANK. A note that says a marker failed without saying at what is the same as no note, and
 * the note is the entire audit trail for a row that goes back on the queue. A blank reason becomes
 * {@link #UNREPORTED} rather than an empty string, which is what the skip path already wrote for a
 * throwable that carried no message.
 *
 * <p>NOT CLIPPED HERE. The bound belongs to the thing that crosses the process boundary — see
 * {@code InfraFailure.REASON_CUT} — and clipping a second time at a second width would mean two
 * answers to "how much of a reason survives".
 */
public record InfraReason(String text) {

    /** What a throwable that carried no message at all is recorded as. */
    public static final String UNREPORTED = "the failure was not reported";

    public InfraReason {
        if (text == null || text.isBlank()) {
            text = UNREPORTED;
        }
    }

    /** @see InfraReason */
    public static InfraReason of(String text) {
        return new InfraReason(text);
    }

    /** The reason itself, so a composed note reads as it always did. */
    @Override
    public String toString() {
        return text;
    }
}
