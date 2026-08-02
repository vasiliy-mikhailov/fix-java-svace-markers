/**
 * THE FEEDBACK STORE'S RECORD AND ITS HARVEST — pure functions, like everything else the engine owns.
 *
 * <p>WHAT THIS PACKAGE IS FOR. A run of 282 markers makes 1410 model calls and throws away every one of
 * the prompts. What survives is a state, a score and a verdict — enough to triage a marker, and nothing
 * at all with which to improve the instructions that produced it. These classes turn one settled prove
 * into one self-contained record: what the stages were given, what they produced, and the concrete
 * complaints the pipeline already made about it, under kinds stable enough to be COUNTED across runs.
 *
 * <p>WHAT IT IS NOT. It is not an optimiser. Nothing here rewrites a prompt, scores one, or ranks two
 * against each other. The algorithm that will do that reads this file later; building the judge and the
 * judged in one pass is how a feedback loop comes to agree with itself.
 *
 * <p>WHY IT IS IN THE ENGINE AND NOT THE ORCHESTRATOR. Everything here is a pure function of the items
 * the chain already produced — no clock, no file, no environment — exactly like {@code nodes} and
 * {@code lib}, and for the same reason: it can then be asserted byte for byte offline. The half that
 * touches a disk ({@code tech.mikhailov.fsm.orch.feedback.FeedbackStore}) lives in the orchestrator,
 * which is the process that owns the run.
 */
package tech.mikhailov.fsm.feedback;
