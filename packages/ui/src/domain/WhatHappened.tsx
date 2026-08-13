import type { MarkerKey } from '@fsm/types'
import { Disclosure, type Style } from '../primitives'
import { FlaggedSource } from './FlaggedSource'
// Aliased for the same reason `FlaggedSource.tsx` aliases it: the component and the record it
// renders share a name, and one file cannot read as if they were the same thing.
import type { FlaggedSource as Source } from './records'

export type WhatHappenedProps = {
  /** The `Disclosure`'s stable id. See below — this is a fold that must not be keyed by position. */
  markerKey: MarkerKey
  /**
   * The lane interpreter's first paragraph, checked by its critic. "" until it has run.
   *
   * A READING of the record, which is why it is preferred over the argument's own opening: the
   * verdict is addressed to the next agent and opens by restating what it decided, the summary is
   * addressed to a person. The whole argument is still one click below it.
   */
  headline: string
  /** The settlement's argument, RAW and unabridged. "" while the marker is still in flight. */
  verdictText: string
  /** Last progress note, `settled` because, or `failed` cause — whichever the trace said last. */
  lastNote: string
  /** The ±4 lines the marker points at, or null when there was no tree to read. */
  flagged: Source | null
}

const QUIET: Style = { color: 'var(--text-tertiary)', fontSize: '11px' }

/** Java's `td.why pre` (CSS 63): prose, so it wraps — unlike source, which must not. */
const ARGUMENT: Style = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  fontSize: '12px',
  lineHeight: 1.5,
  margin: '6px 0 0',
  color: 'var(--text-secondary)',
}

/**
 * The first line worth showing — Java `oneLine()` 2367-2375.
 *
 * Agents answer with their verdict first and their reasoning after, so the first non-empty line is
 * the answer. `---` is skipped because a model that has been asked for markdown opens with a rule.
 */
function oneLine(text: string): string {
  for (const line of text.split('\n')) {
    const one = line.trim()
    if (one !== '' && one !== '---') {
      return one
    }
  }
  return ''
}

/** Java `cut()` 2719-2721. */
function cut(text: string, n: number): string {
  return text.length <= n ? text : `${text.slice(0, n)}…`
}

/**
 * The words an argument opens by repeating.
 *
 * The first seven are the dispositions; the rest are the critics' own vocabulary (`make`, `reject`,
 * `sound`, `redo`, `over-fit`, `regression-risk`, `necessary`, `reducible`). An agent asked to answer
 * with a word and then justify it does exactly that, twice — "false-positive false-positive The
 * static analyzer claims…" — and a column of those summarises to nothing.
 */
const RESTATEMENTS = [
  'false-positive',
  'by-design',
  'unprovable',
  'reproduced',
  'needs-review',
  'verified/pr-ready',
  'verified/pr-rejected',
  'make',
  'reject',
  'sound',
  'redo',
  'over-fit',
  'regression-risk',
  'necessary',
  'reducible',
] as const

/**
 * THE FIRST SENTENCE THAT SAYS ANYTHING — Java `firstSentence()` 2612-2631.
 *
 * NOT A TRUNCATION. The whole argument is inside the fold, one click away and on this page; this is
 * only the line a reader scans down the table. The naive first sentence was mostly not one, for two
 * reasons that both had to be fixed for the column to be worth having: an argument restates its
 * verdict before it starts (above), and then clears its throat ("Looking at this issue: 1.").
 *
 * So: flatten the markdown, drop the restatement, skip anything that ends in a colon or is throat
 * clearing, and take the first sentence long enough to be a claim. Where nothing qualifies, the
 * opening as it stands — which is the honest answer for an argument that has no first sentence.
 *
 * THIS LIVES IN THE CLIENT AND THE SERVER SENDS THE RAW TEXT. A pre-computed sentence in the payload
 * would be a decision the reader cannot revisit and would throw away the argument the fold exists to
 * show; it would also freeze this heuristic, and every line of it was written against real answers
 * that had already gone wrong.
 */
function firstSentence(why: string): string {
  // Stripping the markdown leaves its spacing behind: `**The bug**: Line 91` becomes
  // `The bug : Line 91`, which reads as a typo in a column meant to be scanned.
  let flat = why
    .trim()
    .replace(/[*`#>]/g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/\s+([:;,.])/g, '$1')
    .trim()
  for (const word of RESTATEMENTS) {
    while (flat.toLowerCase().startsWith(`${word} `)) {
      flat = flat.slice(word.length).trim()
    }
  }
  for (const sentence of flat.split(/(?<=[.!?])\s+/)) {
    const one = sentence.trim()
    if (one.length >= 40 && !one.endsWith(':') && !/^looking at .{0,40}$/i.test(one)) {
      return cut(one, 240)
    }
  }
  return cut(flat, 240)
}

/**
 * WHY, IN THE TABLE — the fourth column, and the reason the table is worth reading.
 *
 * Three branches, and which one is showing is itself information:
 *
 * 1. Nothing has been said about this marker at all. An em dash, and it means queued.
 * 2. It is running: no argument yet, so the last thing it said about itself, cut at 150 and grey.
 *    That note used to live in a column of its own at the far right (1740-1743), which meant a reader
 *    scanning `why` found a dash for every marker in flight and had to cross the width of the table
 *    to discover that anything was happening at all.
 * 3. It has settled: one readable sentence, and the whole argument under it.
 *
 * THE ARGUMENT IS NOT SHORTENED (1735-1738). Reading the table used to tell you a marker was a
 * false-positive and nothing about why, so every question started by opening it. Folded, because
 * thirty paragraphs is not a table — and whole, because a reason cut at two hundred characters is a
 * reason nobody can check.
 *
 * The flagged source goes in the fold above the argument, because the first thing anybody does with
 * "the analyzer is wrong about line 82" is look at line 82.
 */
export function WhatHappened({
  markerKey,
  headline,
  verdictText,
  lastNote,
  flagged,
}: WhatHappenedProps) {
  const settled = verdictText.trim() !== ''
  const reason = settled ? verdictText : oneLine(lastNote)

  if (reason.trim() === '') {
    return <span style={QUIET}>—</span>
  }
  if (!settled) {
    return <span style={QUIET}>{cut(reason, 150)}</span>
  }
  return (
    // CLOSED, unlike every other fold in this codebase. `Disclosure` defaults to open because a fold
    // on a marker's own page is there to be read; forty of these open at once is the table the
    // argument was folded out of in the first place. The `?fold=` URL rule does not reach here — it
    // decides whether a page's folds start open, and this one starts closed on any page.
    //
    // KEYED BY THE MARKER (catalogue #10). Fold state used to be remembered by an element's position
    // on the page, so settling a marker — which reorders nothing here, but does change which rows
    // have a fold at all — reopened the fold belonging to whoever now sits in that position. The
    // marker key is the only id that survives a row being added above it.
    <Disclosure
      id={markerKey}
      defaultOpen={false}
      summary={headline.trim() === '' ? firstSentence(verdictText) : headline}
    >
      <FlaggedSource source={flagged} />
      <pre style={ARGUMENT}>{verdictText}</pre>
    </Disclosure>
  )
}
