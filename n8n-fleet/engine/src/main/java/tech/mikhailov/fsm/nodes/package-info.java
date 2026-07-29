/**
 * One class per n8n Code node, mirroring {@code n8n/agentic/src/nodes/*.js}.
 *
 * <p>Each becomes a function from a request body to a response body — no n8n objects, no HTTP — so it
 * stays as testable as the JS module it replaces. The node in n8n shrinks to a POST at this service.
 *
 * <p>THE ONE DESIGN QUESTION THIS PACKAGE HAD TO ANSWER FIRST. The JS reads its inputs through n8n's
 * item graph: {@code $('Prep prover').item.json}, {@code $('Parse test').item.json}, and so on —
 * record-outcome.js alone reaches into five upstream nodes. An HTTP endpoint has no item graph, so the
 * shim has to send those items in the request and the Java side has to name them. That contract is
 * what the first port pinned down, with the node that reads the most upstream nodes, because choosing
 * it once is cheaper than changing it under five callers.
 *
 * <p>THE ANSWER, in {@link tech.mikhailov.fsm.nodes.RecordOutcome.Request}: one JSON key per n8n node,
 * named after the node and snake-cased ({@code prep_prover}, {@code parse_test}, {@code parse_fix},
 * {@code run_test_reproduce}, {@code build_reproduce_input}), the item the node runs on as
 * {@code pr_maker}, and {@code versions} alongside. The items stay UNTYPED on the Java side — they
 * carry twenty-odd fields each, produced by nodes that are not ported yet, and every read goes through
 * {@link tech.mikhailov.fsm.lib.Json}'s JS coercions. A key that does not match reads as an absent
 * node rather than throwing, which is what {@code || {}} did in the JS.
 *
 * <table border="1">
 *   <caption>Port order, most consequential first</caption>
 *   <tr><th>src/nodes/*.js</th><th>why it is where it is in the queue</th></tr>
 *   <tr><td>record-outcome.js → {@link tech.mikhailov.fsm.nodes.RecordOutcome} (PORTED)</td>
 *       <td>the highest-consequence code in the pipeline: every downstream
 *       decision keys off the {@code state} it returns, and a wrong branch does not crash — it
 *       silently retires a real defect or drafts a PR from a fix that was never applied. It also
 *       reads the most upstream items, so it settled the request contract above.</td></tr>
 *   <tr><td>verdict.js</td><td>the routing that decides whether a marker is argued, retried or
 *       retired. Its own comments record markers that were retired with no argument at all because a
 *       route was missing.</td></tr>
 *   <tr><td>parse-markers.js</td><td>ingest: turns the Svace CSV into suspicions. Reads
 *       {@link tech.mikhailov.fsm.lib.CheckerMap}, which is already here.</td></tr>
 *   <tr><td>parse-test.js, parse-fix.js</td><td>thin wrappers over the JSON extractor.</td></tr>
 *   <tr><td>build-reproduce-input.js, build-fix-input.js, prep-prover.js</td><td>prompt assembly.</td></tr>
 *   <tr><td>fix-skeptic.js, pr-maker.js</td><td>model calls plus their result routing.</td></tr>
 * </table>
 */
package tech.mikhailov.fsm.nodes;
