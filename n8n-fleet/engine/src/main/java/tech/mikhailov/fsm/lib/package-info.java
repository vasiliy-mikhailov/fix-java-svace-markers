/**
 * Shared logic, mirroring {@code n8n/agentic/src/lib/*.js} one file at a time.
 *
 * <p>These are pure functions and pure data with no n8n in them, which is why they port first: their
 * JS tests run unchanged in meaning, so a Java test that disagrees with one is a porting bug and not a
 * design question.
 *
 * <table border="1">
 *   <caption>JS source of record, and what it became</caption>
 *   <tr><th>src/lib/*.js</th><th>here</th><th>state</th></tr>
 *   <tr><td>checker-map.js</td><td>{@link tech.mikhailov.fsm.lib.CheckerMap},
 *       {@link tech.mikhailov.fsm.lib.Severity}</td><td>ported</td></tr>
 *   <tr><td>test-realness.js</td><td>{@link tech.mikhailov.fsm.lib.TestRealness}</td><td>ported</td></tr>
 *   <tr><td>(the JS runtime's JSON)</td><td>{@link tech.mikhailov.fsm.lib.Json}</td><td>new — see its
 *       class comment for why it is not Jackson</td></tr>
 *   <tr><td>json-extract.js</td><td>{@link tech.mikhailov.fsm.lib.JsonExtract}</td><td>ported — it
 *       needed {@link tech.mikhailov.fsm.lib.Json} first, because its algorithm is "try candidates
 *       until one parses STRICTLY"</td></tr>
 *   <tr><td>(the JS language itself: {@code trim}, and what counts as whitespace)</td>
 *       <td>{@link tech.mikhailov.fsm.lib.JsText}</td><td>new — Java and JavaScript disagree about
 *       four characters in each direction, and one of them decides whether a source file counts as
 *       empty</td></tr>
 *   <tr><td>(the rest of the JS language: {@code String(x)}, {@code parseInt},
 *       {@code Number::toString}, object key order)</td>
 *       <td>{@link tech.mikhailov.fsm.lib.Js}</td><td>new — the layer above
 *       {@link tech.mikhailov.fsm.lib.JsText}, which it uses rather than restating. Number::toString
 *       is the load-bearing one: {@code dedup_key} is built by concatenating a number, so
 *       {@code Double.toString} would re-key the whole backlog</td></tr>
 *   <tr><td>(the CSV reader hand-rolled inside parse-markers.js)</td>
 *       <td>{@link tech.mikhailov.fsm.lib.Csv}</td><td>new — lifted out of the node so it can be
 *       tested on its own. Bug-compatible, not RFC4180: a mid-field quote and a stray CR are things
 *       real Svace exports contain and the pipeline has been reading for months</td></tr>
 *   <tr><td>exec-verdict.js</td><td>{@link tech.mikhailov.fsm.lib.ExecVerdict}</td><td>ported</td></tr>
 *   <tr><td>(state names, JS string literals in four files)</td>
 *       <td>{@link tech.mikhailov.fsm.lib.MarkerState}</td><td>new — the wire spellings had no single
 *       home in the JS, and {@code not-a-bug} is the one written with hyphens</td></tr>
 *   <tr><td>inline.js</td><td>—</td><td>NOT ported. It exists to splice a JS module into an n8n Code
 *       node, which is the very thing this service removes: once a node is an HTTP shim there is
 *       nothing to inline.</td></tr>
 * </table>
 */
package tech.mikhailov.fsm.lib;
