package tech.mikhailov.fsm.orch.domain;

/**
 * HOW MANY TIMES IN A ROW THE PIPELINE FAILED TO REACH AN ANSWER ABOUT ONE MARKER — the counter that is
 * NOT {@code prove_attempts}, and the reason both exist.
 *
 * <p>{@code prove_attempts} counts attempts that REACHED the engine, and an infrastructure failure must
 * never spend one: that rule is what stops "the model was unavailable" being recorded as "we looked at
 * this and got nowhere". Its cost is that a marker whose repository answers 403 for ever is immortal —
 * one wasted claim per tick, on every tick, for the life of the deployment. A streak pays that cost
 * without breaking the rule, because it is a statement about the PLUMBING and can never be read as a
 * finding about the code.
 *
 * <p>THE COUNT COMES FROM THE DATABASE, which is why it is a component and not something this type
 * works out. The increment is an atomic UPDATE that returns the new total; two provers striking the
 * same marker must not both read 2 and both write 3.
 */
public record InfraStreak(MarkerId marker, InfraReason reason, long strikes) {

    public InfraStreak {
        if (marker == null || reason == null) {
            throw new IllegalArgumentException("a streak is a marker and what kept going wrong");
        }
    }

    /**
     * Has this marker run out of chances at {@code ceiling} consecutive failures?
     *
     * @param ceiling {@code fsm.prove.max-infra-strikes}. Zero or less NEVER retires a marker, which is
     *                the behaviour the pipeline had before the streak existed and is still what a
     *                deployment that would rather retry for ever asks for.
     */
    public boolean parksAt(int ceiling) {
        return ceiling > 0 && strikes >= ceiling;
    }

    /**
     * The audit line a parked marker carries — and it has to say what the parking does NOT mean.
     *
     * <p>{@code infra_stuck} sits in the same column as {@code false_positive} and {@code by_design},
     * which are findings about code. This one is not, and a reviewer scanning the dashboard has only
     * this line to tell them apart.
     */
    public String parkNote() {
        return ProverNote.of("parked as infra_stuck after " + strikes + " consecutive infrastructure "
                + "failures; no judgement was ever reached about this marker and prove_attempts is "
                + "unchanged — this is a statement about the pipeline, not about the code: "
                + reason.text());
    }
}
