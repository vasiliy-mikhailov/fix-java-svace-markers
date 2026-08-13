import type { MarkerId, MarkerKey, Verdict } from '@fsm/types'
import { Disclosure, RelativeTime, TextFold, type Style } from '../primitives'
import { MarkerLinkedText } from './MarkerLinkedText'
import { VerdictPill } from './VerdictPill'

export type FindingRecord = {
  /**
   * THE POSITION IN THE FILE, and it must survive every reordering.
   *
   * `reported()` walks holds, then unjudged, then refuted (738), so display order is not the file's
   * — and the anchor, the DOM id and the feedback event all keep this number. An index computed from
   * where the card ended up would move the moment a critic judged something, which silently
   * repoints every link anybody had saved. Same failure as `rate()` numbering answers by position
   * (#3), on a different page.
   */
  index: number
  /** Epoch ms. `num()` (2355) turns an unreadable value into 0, which is why 0 is treated as absent. */
  at: number
  verdict: Verdict
  finding: string
  /** What the critic said. Empty for an unjudged finding, and that emptiness is rendered as absence. */
  judgement: string
}

export type FindingCardProps = {
  finding: FindingRecord
  /**
   * Slug → the full `repo|file|line|checker`, for the markers this finding names.
   *
   * `MarkerId` is this codebase's name for what the spec calls a slug (`@fsm/types` 100-107). The
   * map goes to `MarkerLinkedText`, which owns the matching rule — longest slug first — because
   * `Foo_java_82_NULL_DEREF` contains `Foo_java_82_NULL` and a second implementation of that rule
   * would fail quietly, by linking half a name to the wrong marker.
   */
  markers: ReadonlyMap<MarkerId, MarkerKey>
  defaultOpen: boolean
}

const CARD: Style = {
  border: '1px solid var(--border-soft)',
  borderRadius: '8px',
  background: 'var(--bg-card)',
  padding: '10px 12px',
  margin: '10px 0',
}

const REFUTED: Style = {
  ...CARD,
  // Demoted, NOT hidden, and that is deliberate (735-737): a refuted finding is the record of the
  // supervisor being wrong about something, which is what you want the next time it is wrong.
  opacity: 0.66,
}

const SUMMARY: Style = { display: 'inline-flex', gap: '8px', alignItems: 'baseline', flexWrap: 'wrap' }

const TITLE: Style = { fontSize: '12.5px', color: 'var(--text-primary)' }

const BODY: Style = {
  margin: '6px 0 0',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  fontSize: '12.5px',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
}

/**
 * One thing the supervisor noticed, and what its critic made of it.
 *
 * The verdict goes through `VerdictPill` and NOT through `Pill`: `Pill`'s tones resolve to
 * `--state-*`, so a verdict routed through it would re-borrow the marker vocabulary — which is how
 * `refuted` came to be drawn in the `infra` red, the colour that means a prove threw, for the one
 * outcome that is the system working.
 */
export function FindingCard({ finding, markers, defaultOpen }: FindingCardProps) {
  const lines = finding.finding.split('\n')
  const headline = lines[0] ?? ''
  const rest = lines.slice(1).join('\n')
  return (
    <div style={finding.verdict === 'refuted' ? REFUTED : CARD}>
      <Disclosure
        id={`finding-${finding.index}`}
        defaultOpen={defaultOpen}
        summary={
          <span style={SUMMARY}>
            <VerdictPill verdict={finding.verdict} />
            <span style={TITLE}>{headline}</span>
            {/* 0 is not a moment. `num()` returns it for anything it could not parse, and "56 years
                ago" is a worse answer than no answer. */}
            {finding.at > 0 ? <RelativeTime at={finding.at} variant="conversation" /> : null}
          </span>
        }
      >
        {rest.trim().length === 0 ? null : (
          <p style={BODY}>
            <MarkerLinkedText text={rest} markers={markers} />
          </p>
        )}
        {/* Empty renders NOTHING, not an empty fold: an unjudged finding has no judgement, and a
            fold that opens onto nothing says the critic answered with silence. */}
        <TextFold id={`finding-${finding.index}:judgement`} label="what the critic said" body={finding.judgement} />
      </Disclosure>
    </div>
  )
}
