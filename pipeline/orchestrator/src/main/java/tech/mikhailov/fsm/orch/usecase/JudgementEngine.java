package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.Judgement;
import tech.mikhailov.fsm.orch.domain.Marker;

/**
 * THE ENGINE, AS AN EXTERNAL SERVICE. One question in, one answer out.
 *
 * <p>WHY THIS PORT IS COARSE, WHICH IS THE DESIGN'S BIGGEST STRUCTURAL FACT. The prove chain is 23
 * engine call sites INTERLEAVED with one source fetch, two runner calls and five model calls: prep,
 * fetch, build-reproduce-input, reproduce, parse, run, build-fix-input, fix, parse, run, skeptic, PR,
 * record, verdict. A fine-grained port — one per stage — would leave that alternation in the use case,
 * which means the use case imports {@code tech.mikhailov.fsm.nodes}: the exact dependency this whole
 * arrangement exists to prevent. So the seam is drawn where the alternation ENDS, and everything inside
 * it is the adapter's business.
 *
 * <p>AND WHY THE ENGINE IS TREATED THIS WAY AT ALL. It cannot be reshaped: four differential harnesses
 * pin 6,910 engine and 23,401 runner cases through its public entry points, and the catalogues cannot
 * be regenerated because the implementation they were recorded from is deleted. From this layer it is
 * infrastructure exactly like the database or the model endpoint — a thing we depend on through a port
 * we declare, called by an adapter that maps its answer back onto an entity.
 */
public interface JudgementEngine {

    /**
     * Prove one marker and come back with what it settles at.
     *
     * @throws EngineUnreachable when the question was NEVER ANSWERED — GitHub unreachable, the runner
     *         refusing connections, a model endpoint that timed out. Bad NEWS is not this: a 404 from
     *         GitHub, {@code {"ok": false}} from the runner and a model reply that parses to nothing are
     *         all ordinary answers that the engine itself turns into {@code infra_error} or
     *         {@code not_reproduced}. Confusing the two is the failure the whole pipeline is built to
     *         avoid, which is why it is a CHECKED exception: an unchecked one would let a call site
     *         handle infrastructure and judgement in one {@code catch}.
     */
    Judgement judge(Marker marker) throws EngineUnreachable;
}
