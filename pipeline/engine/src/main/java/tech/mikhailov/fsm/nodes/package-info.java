/**
 * One class per decision stage of the prove chain.
 *
 * <p>Each is a function from a request body to a response body — no framework objects, no HTTP — which
 * is why one can be called from a unit test without a container, and why the orchestrator embeds this
 * module as a library rather than addressing it.
 *
 * <p>THE REQUEST CONTRACT, and it is the thing here worth reading before changing anything.
 * {@link tech.mikhailov.fsm.nodes.RecordOutcome.Request} defines it, because that stage reads the most
 * upstream stages and therefore had to settle it for all of them: one JSON key per stage, named after
 * the stage and snake-cased ({@code prep_prover}, {@code parse_test}, {@code parse_fix},
 * {@code run_test_reproduce}, {@code build_reproduce_input}), the item the stage itself runs on as
 * {@code pr_maker}, and {@code versions} alongside.
 *
 * <p>The items stay UNTYPED: they carry twenty-odd fields each, and every read goes through
 * {@link tech.mikhailov.fsm.lib.Json}'s coercions. A key that does not match reads as an ABSENT stage
 * rather than throwing — deliberately, because "that stage never ran" has to reach the routing as a
 * decision instead of crashing the chain half way through a marker.
 *
 * <table border="1">
 *   <caption>The stages, most consequential first</caption>
 *   <tr><th>class</th><th>what it decides, and what a wrong branch costs</th></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.nodes.RecordOutcome}</td>
 *       <td>the highest-consequence code in the pipeline: every downstream decision keys off the
 *       {@code state} it returns, and a wrong branch does not crash — it silently retires a real
 *       defect, or drafts a PR from a fix that was never applied.</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.nodes.Verdict}</td>
 *       <td>the routing that decides whether a marker is argued, retried or retired. Its own comments
 *       record markers that were retired with no argument at all because a route was missing.</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.nodes.ParseMarkers}</td>
 *       <td>ingest: turns the Svace CSV into suspicions, reading
 *       {@link tech.mikhailov.fsm.lib.CheckerMap}. It fails differently from the rest — nothing here
 *       crashes, it corrupts the BACKLOG: an unstripped CI build path, a dedup_key collision or a lost
 *       severity ordering each produce a full run that looks healthy and is worthless. Its CSV reading
 *       is {@link tech.mikhailov.fsm.lib.Csv} and its coercions
 *       {@link tech.mikhailov.fsm.lib.Values}.</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.nodes.ParseTest}, {@link tech.mikhailov.fsm.nodes.ParseFix}</td>
 *       <td>they read the model replies through {@link tech.mikhailov.fsm.lib.JsonExtract}, and they
 *       are not thin: ParseTest decides whether an unusable reply becomes {@code parse_failed} or a
 *       verdict, and ParseFix carries the ALLOWLIST that stops a fixer editing the test it was asked
 *       to pass.</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.nodes.BuildReproduceInput},
 *       {@link tech.mikhailov.fsm.nodes.BuildFixInput},
 *       {@link tech.mikhailov.fsm.nodes.PrepProver}</td><td>prompt assembly.</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.nodes.FixSkeptic}, {@link tech.mikhailov.fsm.nodes.PrMaker}</td>
 *       <td>model calls plus their result routing.</td></tr>
 * </table>
 *
 * <p>THE THREE STAGES THAT CALL A MODEL are three pieces on purpose: a PURE prompt builder, a PURE
 * reply parser, and a thin shell that makes the call and glues the two together, with the endpoint
 * injected as {@link tech.mikhailov.fsm.lib.Llm.Http}. The judgement in the middle is only observable
 * against a live model, but the prompt bytes, the truncation boundaries, the fail-closed defaults and
 * the routing are ordinary deterministic code — and that seam is what lets a unit test reach them
 * without a 15-second round trip. Keep it: fold the call back into the builder and the only way left to
 * test any of it is against a model.
 */
package tech.mikhailov.fsm.nodes;
