import { DataTable } from 'ratchet-ui/components'
import { MARKER_COLUMNS, type MarkerRowData } from './MarkerRow'

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

/** Every marker the run was given — including the ones it has not reached. */
export function MarkerTable({ markers }: MarkerTableProps) {
  return (
    <DataTable
      rows={markers}
      columns={MARKER_COLUMNS}
      // KEYED BY THE MARKER KEY, never by index. React reuses DOM by key, and the fold in the
      // `interpretation` cell holds open state: keyed by position, a marker settling above another
      // one hands its open fold to a different marker's argument. It is the same failure the Java's
      // fold memory had for the same reason (catalogue #10).
      rowKey={marker => marker.key}
      // THE HOVER BAND STAYS IN THIS SOURCE, DELIBERATELY. A Tailwind utility only exists if the
      // consumer's own generator saw the literal somewhere it scans; a class shipped from inside
      // `node_modules` is a rule that may simply never be emitted, with no error and no failing
      // test. Passed from here, our own `@source "../src"` glob covers it.
      rowClassName="hover:bg-[var(--state-hover-bg)]"
    />
  )
}
