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
 * <p>READ {@link tech.mikhailov.fsm.nodes.RecordOutcome} AND {@link tech.mikhailov.fsm.nodes.Verdict}
 * FIRST — that ordering is the one thing about this package a generated class list cannot tell you.
 * They are where a wrong branch is most expensive, and neither fails loudly. Third is
 * {@link tech.mikhailov.fsm.nodes.ParseMarkers}, which fails differently again: it corrupts the
 * BACKLOG rather than the run. Each of the three says so in its own class comment, at the length the
 * argument needs; a table here restating them was a second copy that had already gone stale once (it
 * linked {@code lib.Js}, deleted 2026-08-05) and is gone as of 2026-08-06.
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
