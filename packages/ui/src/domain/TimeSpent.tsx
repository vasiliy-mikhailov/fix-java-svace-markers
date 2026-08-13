import type { Style } from '../primitives'
import { clock } from './clock'

export type TimeSpentProps = {
  /**
   * First trace event to last, INCLUDING every gap the marker sat waiting.
   *
   * Wall clock, not machine time: a prove that waited eleven minutes for a Gradle daemon spent
   * eleven minutes, and the figure a person compares against `HumanCost` is the one that includes
   * them. Computed as `last - first` per marker (Api.index), so a marker with one event has a span
   * of 0 and so does a marker with none.
   */
  spanMs: number
  /** How many lines of the record this marker has. Its own answer to "is anything happening". */
  events: number
}

/** Java's `.k` (CSS 52). The whole cell is grey: it is a measurement, not a finding. */
const QUIET: Style = { color: 'var(--text-tertiary)', fontSize: '11px' }

/**
 * HOW LONG THE MACHINE TOOK OVER THIS MARKER, and how much it said while it did.
 *
 * ZERO IS AN EM DASH, WHICH MAKES TWO THINGS LOOK ALIKE: nothing has happened, and it took no time.
 * That is deliberate and it is the Java's behaviour (1776) — a span of 0 comes from a marker with one
 * event or none, and printing "0s" for a queued marker would state a duration for work that has not
 * started. The event count underneath is what tells the two apart, which is why they are one
 * component and not two cells.
 */
export function TimeSpent({ spanMs, events }: TimeSpentProps) {
  return (
    <div style={QUIET}>
      {spanMs > 0 ? clock(spanMs) : '—'}
      <div>{events} event(s)</div>
    </div>
  )
}
