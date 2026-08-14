import { EmptyNote, Tally, type Style } from '../primitives'

export type FindingTallyProps = {
  holds: number
  refuted: number
  /**
   * DERIVED ONCE, ON THE SERVER, as `size - holds - refuted` (715).
   *
   * That subtraction is what folds a blank verdict into `unjudged`, and it is the same rule
   * `verdictOf()` (821-824) applies when it reads a row. This component does NOT re-derive it: two
   * places computing the same fold is how the two of them come to disagree about a row nobody
   * thought about, and the tally is the number people quote in a status meeting.
   */
  unjudged: number
  restarts: number
}

const STRIP: Style = { display: 'flex', gap: '8px', flexWrap: 'wrap', margin: '12px 0' }

/**
 * What the supervisor has reported, counted.
 *
 * BUG NOT PORTED (`reported()` 717). The Java tested `all.isEmpty()` — FINDINGS ONLY — so a
 * run with zero findings and five restarts printed "the supervisor has not reported yet" while
 * `RestartLog` rendered five restarts immediately beneath it. The page contradicted itself in two
 * adjacent elements, and the restarts are the half a reader most needs to believe. The empty case is
 * every count being zero.
 */
export function FindingTally({ holds, refuted, unjudged, restarts }: FindingTallyProps) {
  if (holds + refuted + unjudged + restarts === 0) {
    return <EmptyNote>the supervisor has not reported yet — it looks on its own schedule, not on yours</EmptyNote>
  }
  return (
    <div style={STRIP}>
      <Tally value={holds} label="holds" />
      <Tally value={unjudged} label="unjudged" />
      <Tally value={refuted} label="refuted" />
      <Tally value={restarts} label="restarts" />
    </div>
  )
}
