import type { MarkerKey, MarkerState, Severity } from '@fsm/types'
import { type Column, HumanCost, TimeSpent } from 'ratchet-ui/components'
import { MarkerIdentity } from './MarkerIdentity'
import { Semaphore } from './Semaphore'
import { SeverityBadge } from './SeverityBadge'
import { StateBadge } from './StateBadge'
import { WhatHappened } from './WhatHappened'
import type { FlaggedSource, SettlementFlags } from './records'

/**
 * ONE MARKER AS THE TABLE NEEDS IT, in one object.
 *
 * Six cells and one prop: a row that took six props would be a row whose caller assembles it, and the
 * caller is a `map` over a payload. Nothing here is looked up, joined or resolved at render time —
 * `Api.index` does the severity join, the state resolution and the trace roll-up once for the whole
 * page, and everything below is a field.
 */
export type MarkerRowData = {
  key: MarkerKey
  /** Only used to fetch the flagged source, which the server has already done. Kept: it is identity. */
  repo: string
  /** `repo` as a person says it — `WebGoat`, `ca2_back`. `Projects.nameOf` on the server. */
  project: string
  /** The module, or `''` for a repository that is one module. `Projects.moduleOf` on the server. */
  module: string
  file: string
  line: string
  checker: string
  /**
   * `null` IS A REAL ANSWER, not a missing value. Severity is joined in from `severities.tsv` by
   * `basename|line|checker` and the sidecar covers the markers that analyser run reported; the
   * `src/it` and `src/test` ones are genuinely not in it and get an em dash rather than a guess.
   */
  severity: Severity | null
  /**
   * THE STATE ON SCREEN IS NOT `settlements.state`, AND IT IS RESOLVED SERVER-SIDE (Run.java).
   *
   * A settlement row saying `proving` only means a prove once started: the row outlives a container
   * replaced under it, so every interrupted marker read as busy forever. The claim directory is the
   * fact — `proving` with no `results/claims/<slug>` is `interrupted`, and a queued key WITH a claim
   * is `proving`, taken seconds ago before the first stage reported. Both ends of that were wrong
   * before `Run` existed, and a client shown the raw state would be wrong at both ends again.
   *
   * The rule lives in one place because two readers ask it — this zone and the page it replaces —
   * and `slug()` must keep matching `entrypoint.sh` exactly: a claim the resolver cannot find reads
   * as a marker nobody is working on.
   */
  state: MarkerState
  flags: SettlementFlags | null
  events: number
  spanMs: number
  humanMinutes: number
  /** `summary.txt`'s first paragraph, "" if nothing has interpreted this marker yet. */
  headline: string
  /** RAW and unabridged; "" while in flight. `WhatHappened` derives its own one-line summary. */
  verdictText: string
  /** The last progress note / `settled` because / `failed` cause. */
  lastNote: string
  flagged: FlaggedSource | null
}

/**
 * ONE ROW READS AS ONE SENTENCE, LEFT TO RIGHT: how bad, what and where, what we decided, why, what
 * it cost (Java comment 1711-1715).
 *
 * That order is the result of two moves worth not undoing. `where` used to sit between severity and
 * state, which split the two columns a reader actually compares — how serious the analyser thought
 * this was, and what we concluded. And there was a seventh column, `latest`, holding the last thing a
 * running marker said; it is gone because a running marker's progress note IS its `why` until it has
 * a better one, and repeating it at the far right made a reader cross the table to find out whether
 * anything was happening.
 *
 * THIS WAS A `<tr>` AND IS NOW SIX RENDER FUNCTIONS. The two dashboards factored the same table on
 * OPPOSITE axes: the sibling pulled out the SHELL and left its cells written inline, and this pulled
 * out the CELLS over a shell copied privately into two files. Converged, the shell is theirs and the
 * cells are ours — `ratchet-ui` ships no cells at all, and the per-column render prop is the joint
 * that lets this factoring survive as a shape rather than as code. The headings are here rather than
 * in the table because a heading and the cell that fills it are one decision.
 */
export const MARKER_COLUMNS: Column<MarkerRowData>[] = [
  { head: 'severity', cell: marker => <SeverityBadge severity={marker.severity} /> },
  {
    head: 'marker',
    cell: marker => (
      <MarkerIdentity
        markerKey={marker.key}
        file={marker.file}
        line={marker.line}
        checker={marker.checker}
        within={marker.module}
      />
    ),
  },
  {
    head: 'state',
    /*
     * PROVING IS THE ONE STATE THAT IS STILL HAPPENING (1758-1760), so it alone is a link, to the
     * live tab. Every other word in this column is a conclusion and reads fine as text; this one
     * is a question — what is it doing — and the answer is one page away.
     *
     * The caller decides that, not the badge: `href` is spread in rather than passed as
     * `undefined` because `exactOptionalPropertyTypes` makes those two different things, and an
     * absent prop is what "this pill is not a link" means.
     */
    cell: marker => (
      <>
        <StateBadge
          state={marker.state}
          {...(marker.state === 'proving'
            ? { href: `/marker?k=${encodeURIComponent(marker.key)}&a=live` }
            : {})}
        />
        <Semaphore flags={marker.flags} state={marker.state} />
      </>
    ),
  },
  {
    head: 'interpretation',
    /** Java's `td.why` (CSS 61). An argument set across the full width of a screen is unreadable. */
    cellStyle: { maxWidth: '44em' },
    cell: marker => (
      <WhatHappened
        markerKey={marker.key}
        headline={marker.headline}
        verdictText={marker.verdictText}
        lastNote={marker.lastNote}
        flagged={marker.flagged}
      />
    ),
  },
  // THE TWO MEASURED COLUMNS GO RIGHT. They were left-aligned here and right-aligned in the sibling,
  // and a column of numbers a reader scans down compares on its last digit.
  {
    head: 'took',
    align: 'right',
    cell: marker => (
      <TimeSpent ms={marker.spanMs > 0 ? marker.spanMs : null} events={marker.events} />
    ),
  },
  {
    head: 'a person would have',
    align: 'right',
    cell: marker => <HumanCost minutes={marker.humanMinutes > 0 ? marker.humanMinutes : null} />,
  },
]
