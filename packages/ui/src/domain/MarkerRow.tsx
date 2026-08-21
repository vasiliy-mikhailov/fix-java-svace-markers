import type { MarkerKey, MarkerState, Severity } from '@fsm/types'
import type { Style } from '../primitives'
import { CELL, HumanCost, ROW, TimeSpent } from 'ratchet-ui/components'
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

export type MarkerRowProps = { marker: MarkerRowData }

/** Java's `td.why` (CSS 61). An argument set across the full width of a screen is unreadable. */
const WHY: Style = { ...CELL, maxWidth: '44em' }

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
 */
export function MarkerRow({ marker }: MarkerRowProps) {
  return (
    // The hover band is the one thing on this table a token cannot express — a pseudo-class needs a
    // rule, not an inline style — so it is a Tailwind utility over a portal token. Six columns is
    // wide enough that losing your line while reading across is a real cost; a consumer whose
    // Tailwind does not scan this package loses the band and nothing else.
    <tr className="hover:bg-[var(--state-hover-bg)]" style={ROW}>
      <td style={CELL}>
        <SeverityBadge severity={marker.severity} />
      </td>
      <td style={CELL}>
        <MarkerIdentity
          markerKey={marker.key}
          file={marker.file}
          line={marker.line}
          checker={marker.checker}
        />
      </td>
      <td style={CELL}>
        {/*
         * PROVING IS THE ONE STATE THAT IS STILL HAPPENING (1758-1760), so it alone is a link, to the
         * live tab. Every other word in this column is a conclusion and reads fine as text; this one
         * is a question — what is it doing — and the answer is one page away.
         *
         * The caller decides that, not the badge: `href` is spread in rather than passed as
         * `undefined` because `exactOptionalPropertyTypes` makes those two different things, and an
         * absent prop is what "this pill is not a link" means.
         */}
        <StateBadge
          state={marker.state}
          {...(marker.state === 'proving'
            ? { href: `/marker?k=${encodeURIComponent(marker.key)}&a=live` }
            : {})}
        />
        <Semaphore flags={marker.flags} state={marker.state} />
      </td>
      <td style={WHY}>
        <WhatHappened
          markerKey={marker.key}
          headline={marker.headline}
          verdictText={marker.verdictText}
          lastNote={marker.lastNote}
          flagged={marker.flagged}
        />
      </td>
      <td style={CELL}>
        <TimeSpent ms={marker.spanMs > 0 ? marker.spanMs : null} events={marker.events} />
      </td>
      <td style={CELL}>
        <HumanCost minutes={marker.humanMinutes > 0 ? marker.humanMinutes : null} />
      </td>
    </tr>
  )
}
