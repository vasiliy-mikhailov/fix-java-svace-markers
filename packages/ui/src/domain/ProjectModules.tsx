'use client'

import { useState } from 'react'
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

/**
 * A ROW TO AIM AT, NOT A LINE OF TEXT.
 *
 * The fold's own summary is an 11px line, and closing a heading that has four hundred rows under it
 * meant hitting seventeen pixels of it. A `<summary>` is block-level so the width was never the
 * problem; the height was. Nine pixels above and below puts it at the same 24px rhythm as a table
 * cell, which is also what makes the hover band read as a row rather than as a highlighted word.
 */
const HIT: Style = { padding: '9px 24px', display: 'block' }

const BAR: Style = {
  display: 'flex',
  gap: '12px',
  alignItems: 'baseline',
  padding: '4px 24px 2px',
  fontSize: '11px',
  color: 'var(--text-tertiary)',
}

const ACTION: Style = {
  background: 'none',
  border: 0,
  padding: 0,
  font: 'inherit',
  color: 'var(--accent-primary)',
  cursor: 'pointer',
}

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
 * <p>SHUT BY DEFAULT WHEN THERE IS A CHOICE TO MAKE, and open when there is not. Sixteen modules all
 * open is the long page this level was split out to end — 416 of ca2_back's markers are in one of
 * them — and a reader who arrives at a project is choosing a module, not reading every marker in it.
 * A single-module project has nothing to choose, so it opens: a fold that hides the only thing on
 * the page is a click that asks a question with one answer.
 *
 * <p>The grouping rule itself IS shared — `groupsOf` — because the order is the argument and there
 * must be one of it: groups in the order the queue first mentions them, rows inside a group in queue
 * order, and never a re-ranking that makes a run look further along than it is.
 */
export function ProjectModules({ markers }: ProjectModulesProps) {
  const groups = groupsOf(markers)
  const alone = groups.length === 1
  /**
   * `null` MEANS "AS EACH ONE WAS", which is not the same as either all-open or all-shut: it is the
   * state before anybody pressed anything, and pressing must not be the only way back to it.
   *
   * `generation` is how the two buttons reach folds that own their own open bit. Bumping it changes
   * every key, React discards the old subtrees, and the new ones mount with the default just set —
   * which is also why the buttons cannot simply set a boolean and leave it: a reader who then opens
   * one module by hand must not have it snap shut when another re-render happens.
   */
  const [all, setAll] = useState<boolean | null>(null)
  const [generation, setGeneration] = useState(0)
  const both = (open: boolean) => {
    setAll(open)
    setGeneration(n => n + 1)
  }
  return (
    <>
      {alone ? null : (
        <div style={BAR}>
          <span>{`${groups.length} modules`}</span>
          <button type="button" style={ACTION} onClick={() => both(true)}>
            open all
          </button>
          <button type="button" style={ACTION} onClick={() => both(false)}>
            close all
          </button>
        </div>
      )}
      {groups.map(group => (
        <Disclosure
          // KEYED BY WHAT IT HOLDS, never by position: the open bit is state and the list is
          // replaced whenever the run moves. The generation rides along so the two buttons above
          // can remount the lot.
          key={`${group.module}#${generation}`}
          id={`m:${group.module}`}
          defaultOpen={all ?? alone}
          summaryStyle={HIT}
          // THE LITERAL STAYS IN THIS PACKAGE'S OWN SOURCE, which our `@source "../src"` glob
          // covers — a Tailwind class shipped from inside `node_modules` is a rule that may never
          // be emitted, with no error and no failing test.
          summaryClassName="hover:bg-[var(--state-hover-bg)]"
          summary={
            <span style={MODULE}>
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
