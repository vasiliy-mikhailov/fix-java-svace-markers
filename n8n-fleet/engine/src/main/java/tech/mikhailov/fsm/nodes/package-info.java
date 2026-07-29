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
 *   <tr><td>verdict.js → {@link tech.mikhailov.fsm.nodes.Verdict} (PORTED)</td>
 *       <td>the routing that decides whether a marker is argued, retried or
 *       retired. Its own comments record markers that were retired with no argument at all because a
 *       route was missing.</td></tr>
 *   <tr><td>parse-markers.js → {@link tech.mikhailov.fsm.nodes.ParseMarkers} (PORTED)</td>
 *       <td>ingest: turns the Svace CSV into suspicions, reading
 *       {@link tech.mikhailov.fsm.lib.CheckerMap}. It fails differently from the rest — nothing here
 *       crashes, it corrupts the BACKLOG: an unstripped CI build path, a dedup_key collision or a lost
 *       severity ordering all produce a full run that looks healthy and is worthless. Its CSV reader
 *       moved to {@link tech.mikhailov.fsm.lib.Csv} and its JS coercions to
 *       {@link tech.mikhailov.fsm.lib.Js}.</td></tr>
 *   <tr><td>parse-test.js → {@link tech.mikhailov.fsm.nodes.ParseTest} (PORTED),
 *       parse-fix.js → {@link tech.mikhailov.fsm.nodes.ParseFix} (PORTED)</td>
 *       <td>they read the model replies through {@link tech.mikhailov.fsm.lib.JsonExtract}, and they
 *       are not thin: ParseTest decides whether an unusable reply becomes {@code parse_failed} or a
 *       verdict, and ParseFix carries the ALLOWLIST that stops a fixer editing the test it was asked
 *       to pass.</td></tr>
 *   <tr><td>build-reproduce-input.js, build-fix-input.js, prep-prover.js</td><td>prompt assembly.</td></tr>
 *   <tr><td>fix-skeptic.js → {@link tech.mikhailov.fsm.nodes.FixSkeptic} (PORTED),
 *       pr-maker.js → {@link tech.mikhailov.fsm.nodes.PrMaker} (PORTED)</td>
 *       <td>model calls plus their result routing.</td></tr>
 * </table>
 *
 * <p>THE THREE STAGES THAT CALL A MODEL keep the seam their JS was refactored into: a PURE prompt
 * builder, a PURE reply parser, and a thin shell that makes the call and glues the two together, with
 * the endpoint injected as {@link tech.mikhailov.fsm.lib.Llm.Http}. The judgement in the middle is only
 * observable against a live model — n8n/agentic/test/model.skeptic-prmaker.test.js exercises it there —
 * but the prompt bytes, the truncation boundaries, the fail-closed defaults and the routing are
 * ordinary deterministic code, and that split is what lets a unit test reach them without a 15-second
 * round trip. Equivalence for these three is therefore argued on the PROMPT BYTES, the parsed reply and
 * the routing table rather than on a single return value; harness/run.sh does that against the JS.
 */
package tech.mikhailov.fsm.nodes;
