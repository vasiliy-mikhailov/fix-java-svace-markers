import { isDisposition } from '@fsm/types'
import { Disclosure, type Style } from '../primitives'
import { groupsOf } from './MarkerGroups'
import type { MarkerRowData } from './MarkerRow'
import { MarkerTable } from './MarkerTable'

export type ProjectModulesProps = {
  /** Only this project's markers, in `markers.txt` order — see `MarkerTable` on why not sorted. */
  markers: MarkerRowData[]
}

const MODULE: Style = { color: 'var(--text-primary)', fontSize: '12.5px', fontWeight: 600 }

const COUNT: Style = {
  color: 'var(--text-tertiary)',
  fontSize: '11px',
  fontWeight: 400,
  marginLeft: '10px',
}

/** The gutter is the summary's alone; the table below pays its own, per cell. See `MarkerGroups`. */
const SUMMARY: Style = { paddingLeft: '10px' }

/** `n marker(s) · k decided`. `infra` and `interrupted` are not decisions — `isDisposition` says so. */
function tally(markers: readonly MarkerRowData[]): string {
  const decided = markers.filter(marker => isDisposition(marker.state)).length
  return `${markers.length} marker${markers.length === 1 ? '' : 's'} · ${decided} decided`
}

/**
 * THE SECOND LEVEL: ONE PROJECT'S MODULES, AND ITS MARKERS UNDER THEM.
 *
 * <p>WHY THIS IS NOT `MarkerGroups` WITH THE PROJECTS FILTERED OUT, which is the obvious move and is
 * wrong twice. Its outer loop draws a `Section` per project, and here the page is already titled
 * after the project, so that heading would name the page. And it collapses to a bare `MarkerTable`
 * when there is only one group — which is precisely WebGoat, a single-module repository, so the one
 * project that most needs to be told "these 356 markers are all in the repository root" is the one
 * that would silently lose its heading. That collapse is right where it lives: the registry-less
 * whole-run table had nothing else to say. It is wrong here, and it is pinned by a byte-for-byte
 * test, so this is a second component rather than a flag on the first.
 *
 * <p>The grouping rule itself IS shared — `groupsOf` — because the order is the argument and there
 * must be one of it: groups in the order the queue first mentions them, rows inside a group in queue
 * order, and never a re-ranking that makes a run look further along than it is.
 */
export function ProjectModules({ markers }: ProjectModulesProps) {
  const groups = groupsOf(markers)
  return (
    <>
      {groups.map(group => (
        <Disclosure
          // KEYED BY WHAT IT HOLDS, never by position: the open bit is state and the list is
          // replaced whenever the run moves.
          key={group.module}
          id={`m:${group.module}`}
          summary={
            <span style={{ ...MODULE, ...SUMMARY }}>
              {group.module === '' ? 'the repository root' : group.module}
              {/* A real space as well as the margin: the margin is what a reader sees and the
                  space is what a copied line, or a screen reader, gets. */}
              {' '}
              <span style={COUNT}>{tally(group.markers)}</span>
            </span>
          }
        >
          <MarkerTable markers={group.markers} />
        </Disclosure>
      ))}
    </>
  )
}
