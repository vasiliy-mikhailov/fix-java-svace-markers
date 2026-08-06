/**
 * Shared logic: pure functions and pure data, with no I/O in any of them.
 *
 * <p>SEVERAL OF THESE LOOK LIKE THE JDK EQUIVALENT AND ARE NOT, and that is not a stylistic hangover —
 * it is the contract. The wire format and the {@code dedup_key} depend on the exact rules, so
 * "simplifying" one of them to the idiomatic Java call silently re-keys the backlog or changes what
 * counts as an empty file. Each class says which rule it owns and WHY the JDK's answer is the wrong
 * one for that job. "Because JavaScript did it that way" is not such a reason and never was: a class
 * here that has no other one does not belong here (see {@code harness/README.md}).
 *
 * <p>THE TWO WITH THE WIDEST BLAST RADIUS are {@link tech.mikhailov.fsm.lib.Values} — whose
 * {@code plain} is the third segment of {@code dedup_key}, so a change to it re-keys the deployed
 * backlog with nothing going red — and {@link tech.mikhailov.fsm.lib.Json}, whose STRICTNESS is what
 * {@link tech.mikhailov.fsm.lib.JsonExtract} discriminates on. Start there. Every class states the rule
 * it owns, so do not add a table here restating them: it is a second copy of nine class comments kept
 * in step by hand, and javadoc already generates the class list. The ranking above is the only part of
 * such a table that is more than the tool gives you.
 */
package tech.mikhailov.fsm.lib;
