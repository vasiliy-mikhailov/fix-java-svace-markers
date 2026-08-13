/**
 * THE TWO RECORDS THE MARKERS SCREEN PASSES THROUGH ITSELF, declared once.
 *
 * Neither is in `@fsm/types` yet. They are shapes the API sends and that three components in this
 * tier hand to each other — `MarkerRow` → `WhatHappened` → `FlaggedSource`, `MarkerRow` →
 * `Semaphore` — and a component that redeclares its neighbour's payload is how a field gets renamed
 * on one side of a boundary and quietly ignored on the other.
 *
 * They live in a module rather than in whichever component happens to render them, for the same
 * reason `clock` does: the row and the fold must not own separate ideas of what a flagged source is.
 * `Semaphore` and `FlaggedSource` type their own props from here rather than restating the shape,
 * which is what makes `MarkerRow` handing a payload straight through a compile-time fact instead of a
 * coincidence. The honest end state is one declaration in `@fsm/types`; moving it there is the
 * assembler's call, not a component's, and this module is the one import to rewrite when it happens.
 */

/** A line of source with the number it has IN THE FILE — not its index in the window. */
export type SourceLine = { n: number; text: string }

/**
 * The ±4 lines around a marker, as data rather than as the text blob `flagged()` (2561-2597) built.
 *
 * `fileLines` is the file's real length and exists to be COMPARED with `flagged`. The Java glued the
 * comparison's conclusion into the blob as a sentence ("line 412 — THIS FILE HAS 380. The analyser
 * ran against an older revision"), which meant the one fact that tells a reader not to trust the line
 * number could only be read, never checked, and could not be styled differently from source. `null`
 * when the tree could not be read at all.
 */
export type FlaggedSource = { lines: SourceLine[]; flagged: number; fileLines: number | null }

/**
 * THE TWO FACTS A STATE DOES NOT CARRY: did a test fail before the patch, did it pass after.
 *
 * `null` on a field means never recorded; the whole object is `null` when there is no settlement row
 * at all. The three-way distinction is the point — `flags()` returned "" for no row (2346-2349) and
 * an unlit lamp is not a failed one. `Settlement.note` writes `red_verified=false` on every `proving`
 * stage row because a stage boundary has no build result, so `false` taken from the last row of all
 * says "we ran the test and it did not fail" about a RED that has not run. Api.java takes these from
 * the last row that is NOT `proving`, and sends `null` when there is none.
 */
export type SettlementFlags = { red: boolean | null; green: boolean | null }
