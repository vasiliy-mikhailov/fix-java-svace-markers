import type { MarkerState } from '@fsm/types'
import { Tally, type Style } from '../primitives'
import { HumanCost } from './HumanCost'

export type StateCountsProps = {
  /**
   * How many markers are in each state — RESOLVED states, the ones the screen shows.
   *
   * `Partial`, because a run that has settled nothing has no `by-design` key at all and a zero would
   * be a different claim from an absence. Every state PRESENT gets a tile, including the four that
   * are not dispositions: `queued` is how many the run has not reached and `interrupted` is how many
   * nobody is working on, and both are things a reader is looking for.
   */
  counts: Partial<Record<MarkerState, number>>
  /** The run's total human-equivalent minutes. 0 means no estimator has answered yet. */
  humanMinutes: number
}

/** Java's `.counts` (CSS 46). The second strip on the page; `RunProgress` emits the first. */
const STRIP: Style = { display: 'flex', flexWrap: 'wrap', gap: '8px', padding: '14px 24px' }

/**
 * ONE TILE PER STATE THE RUN IS IN, ALPHABETICALLY.
 *
 * Alphabetical is the Java's (a `TreeMap`, 1701) and it is the only order here that does not make a
 * claim. By count reorders the strip under the reader as the run progresses, so the tile you were
 * looking at moves while you look at it; by pipeline order asserts a sequence these states do not
 * form — `infra` is not a later stage than `reproduced`, it is what happens when the tooling breaks.
 * Alphabetical is arbitrary, stable and obviously arbitrary, which is what a legend wants.
 *
 * The comparison is by code unit rather than `localeCompare`, so it matches `TreeMap`'s ordering
 * exactly while both pages exist: `verified/pr-ready` sorts before `verified/pr-rejected` for the
 * same reason in both.
 *
 * THE SECOND STRIP ON THIS SCREEN IS DELIBERATE. `RunProgress` counts the run — settled of total,
 * elapsed, eta — and this counts the markers. Merging them would put "elapsed" next to "by-design".
 */
export function StateCounts({ counts, humanMinutes }: StateCountsProps) {
  const tiles = (Object.entries(counts) as [MarkerState, number | undefined][])
    .filter((entry): entry is [MarkerState, number] => entry[1] !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))

  return (
    <div style={STRIP}>
      {tiles.map(([state, n]) => (
        <Tally key={state} value={n} label={state} />
      ))}
      {/*
       * DRAWN ONLY WHEN SOMETHING HAS BEEN PRICED (1706). Before any estimator has answered, a "0m"
       * tile claims the run has saved nobody any time, which is a statement about the pipeline's
       * worth made out of the absence of an answer.
       *
       * The value is `HumanCost` itself, not a second copy of its formatter. This tile is the total
       * of the column that component renders forty rows of, and the two used to disagree about how
       * to write three quarters of an hour — see the note on `hm()` in HumanCost.tsx.
       */}
      {humanMinutes > 0 ? (
        <Tally value={<HumanCost minutes={humanMinutes} />} label="human-equivalent" />
      ) : null}
    </div>
  )
}
