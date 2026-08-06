/**
 * Shared logic: pure functions and pure data, with no I/O in any of them.
 *
 * <p>SEVERAL OF THESE LOOK LIKE THE JDK EQUIVALENT AND ARE NOT, and that is not a stylistic hangover —
 * it is the contract. The wire format and the {@code dedup_key} depend on the exact rules, so
 * "simplifying" one of them to the idiomatic Java call silently re-keys the backlog or changes what
 * counts as an empty file. Each class says which rule it owns and WHY the JDK's answer is the wrong
 * one for that job; until 2026-08-05 the reason was always "because JavaScript did it that way", and
 * the ones that had no other reason were deleted with the emulation (see {@code harness/README.md}).
 *
 * <p>THE TWO WITH THE WIDEST BLAST RADIUS are {@link tech.mikhailov.fsm.lib.Values} — whose
 * {@code plain} is the third segment of {@code dedup_key}, so a change to it re-keys the deployed
 * backlog with nothing going red — and {@link tech.mikhailov.fsm.lib.Json}, whose STRICTNESS is what
 * {@link tech.mikhailov.fsm.lib.JsonExtract} discriminates on. Start there. Every class states the rule
 * it owns; an HTML table here restating each of them was a second copy of nine class comments, kept in
 * step by hand, and was deleted on 2026-08-06 rather than re-verified — javadoc already generates the
 * class list, and only the ranking above was ever more than the tool gives you.
 */
package tech.mikhailov.fsm.lib;
