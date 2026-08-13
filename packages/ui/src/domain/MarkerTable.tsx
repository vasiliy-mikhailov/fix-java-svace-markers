import type { Style } from '../primitives'
import { MarkerRow, type MarkerRowData } from './MarkerRow'

export type MarkerTableProps = {
  /**
   * IN `markers.txt` ORDER, AND NOT SORTED — not here and not on the server.
   *
   * The queue is a file somebody wrote and diffs against, so its order is the run's plan: seeded from
   * the queue, settlements overwriting in place, then any settled key the queue has never heard of
   * appended (Run.rows). A table sorted by state throws that away and cannot be compared with the run
   * before it — and sorting by state in particular groups every marker nobody has reached at one end,
   * which looks like progress.
   *
   * A reader who wants a different order has the browser's find; a reader who wants the plan has
   * nowhere else to get it.
   */
  markers: MarkerRowData[]
}

const TABLE: Style = { width: '100%', borderCollapse: 'collapse' }

/** Java's `th` (CSS 49-50). */
const HEAD: Style = {
  textAlign: 'left',
  color: 'var(--text-tertiary)',
  fontWeight: 500,
  fontSize: '11px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  padding: '9px 24px',
  borderBottom: '1px solid var(--border-strong)',
}

/**
 * The six headings, in the order the row reads. They are prose, not field names: "a person would
 * have" says what the last column is FOR, where "minutes" would have said what it holds.
 */
const COLUMNS = ['severity', 'marker', 'state', 'what happened', 'took', 'a person would have']

/** Every marker the run was given — including the ones it has not reached. */
export function MarkerTable({ markers }: MarkerTableProps) {
  return (
    <table style={TABLE}>
      <thead>
        <tr>
          {COLUMNS.map((column) => (
            <th key={column} style={HEAD} scope="col">
              {column}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {markers.map((marker) => (
          // KEYED BY THE MARKER KEY, never by index. React reuses DOM by key, and the fold in the
          // `what happened` cell holds open state: keyed by position, a marker settling above another
          // one hands its open fold to a different marker's argument. It is the same failure the
          // Java's fold memory had for the same reason (catalogue #10), and the row is where it would
          // come back.
          <MarkerRow key={marker.key} marker={marker} />
        ))}
      </tbody>
    </table>
  )
}
