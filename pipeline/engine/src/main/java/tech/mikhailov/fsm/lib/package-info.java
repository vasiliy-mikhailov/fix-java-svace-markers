/**
 * Shared logic: pure functions and pure data, with no I/O in any of them.
 *
 * <p>SEVERAL OF THESE DELIBERATELY IMPLEMENT JAVASCRIPT SEMANTICS RATHER THAN JAVA ONES, and that is
 * not a stylistic hangover — it is the contract. The wire format, the {@code dedup_key} and the frozen
 * differential corpus all depend on those exact rules, so "simplifying" one of them to the idiomatic
 * Java equivalent silently re-keys the backlog or changes what counts as an empty file. Each class says
 * which rule it owns.
 *
 * <table border="1">
 *   <caption>What is here, and the load-bearing part of each</caption>
 *   <tr><th>class</th><th>what it is</th></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.lib.CheckerMap}, {@link tech.mikhailov.fsm.lib.Severity}</td>
 *       <td>Svace checker → category and one-line meaning; and the severity ordering the queue
 *       drains in</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.lib.TestRealness}</td>
 *       <td>whether a generated test exercises anything, and the reasons it gives — which become
 *       critiques against the reproducer's prompt</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.lib.Json}</td>
 *       <td>the JSON reader and writer. See its class comment for why it is not Jackson; the short
 *       version is that this module has no third-party runtime dependencies</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.lib.JsonExtract}</td>
 *       <td>pulls JSON out of a model reply that may be fenced, prefixed or truncated. Its algorithm
 *       is "try candidates until one parses STRICTLY", so it needs {@link tech.mikhailov.fsm.lib.Json}
 *       to be strict</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.lib.JsText}</td>
 *       <td>{@code trim} and what counts as whitespace, to JavaScript's definition — Java and
 *       JavaScript disagree about four characters in each direction, and one of them decides whether a
 *       source file counts as empty</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.lib.Js}</td>
 *       <td>{@code String(x)}, {@code parseInt}, {@code Number::toString} and object key order, to
 *       JavaScript's rules. The layer above {@link tech.mikhailov.fsm.lib.JsText}, which it uses rather
 *       than restating. {@code Number::toString} is the load-bearing one: {@code dedup_key} is built by
 *       concatenating a number, so {@code Double.toString} would re-key the whole backlog</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.lib.Csv}</td>
 *       <td>the Svace report reader. Bug-compatible, not RFC4180: a mid-field quote and a stray CR are
 *       things real Svace exports contain and this pipeline has been reading for months</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.lib.ExecVerdict}</td>
 *       <td>what the two builds established, read off the prover's reply</td></tr>
 *   <tr><td>{@link tech.mikhailov.fsm.lib.MarkerState}</td>
 *       <td>the one home for the wire spellings of every state. {@code not-a-bug} is the one written
 *       with hyphens, and that is exactly why they live in one place</td></tr>
 * </table>
 */
package tech.mikhailov.fsm.lib;
