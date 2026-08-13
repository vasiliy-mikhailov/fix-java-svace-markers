import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { MarkerTable, RunProgress, StateCounts, type MarkerRowData } from '@fsm/ui'
import type { MarkerState, Severity } from '@fsm/types'

import fixture from './fixture.json' with { type: 'json' }

/**
 * THE COMPONENTS, AGAINST A PAYLOAD THE RUNNING SYSTEM ACTUALLY PRODUCED.
 *
 * <p>`fixture.json` is a verbatim capture of `GET /api/index` from the live record: 356 markers, the
 * real states, the real absent severities, the real prose with its quotes and newlines. Not a fixture
 * somebody wrote to match the code — the whole failure mode this port has hit twice is a test whose
 * fixture agrees with the bug.
 *
 * <p>A page that compiles is not a page that renders. These assert the second thing, without a
 * browser, by putting the real data through the real components and reading the markup back.
 */

type ApiMarker = (typeof fixture)['markers'][number]

/** The screen's adapter, kept identical to app/page.tsx — see the note there on why it lives there. */
function toRow(m: ApiMarker): MarkerRowData {
  return {
    key: m.key,
    repo: m.repo,
    file: m.file,
    line: String(m.line),
    checker: m.checker,
    severity: (m.severity as Severity | null) ?? null,
    state: m.state as MarkerState,
    flags: m.hasSettlement ? { red: m.redVerified, green: m.greenVerified } : null,
    events: m.events,
    spanMs: m.spanMs,
    humanMinutes: m.humanMinutes,
    headline: m.summary,
    verdictText: m.verdictText,
    lastNote: m.lastNote,
    flagged: null,
  }
}

const rows = fixture.markers.map(toRow)
const table = renderToStaticMarkup(<MarkerTable markers={rows} />)

describe('the markers screen renders the real run', () => {
  it('draws a row for every marker the run was given', () => {
    expect(rows).toHaveLength(356)
    // One <tr> per marker, plus the header row.
    const tr = table.split('<tr').length - 1
    expect(tr).toBeGreaterThanOrEqual(356)
  })

  it('keeps the queue order, which is the runphase plan', () => {
    const first = table.indexOf('Assignment5.java')
    const second = table.indexOf('SqlInjectionLesson5b.java')
    expect(first).toBeGreaterThan(-1)
    expect(second).toBeGreaterThan(first)
  })

  it('shows every state the record actually holds', () => {
    for (const state of Object.keys(fixture.run.countsByState)) {
      expect(table, `${state} is in the payload and must reach the page`).toContain(state)
    }
  })

  it('renders the markers with no severity without inventing one', () => {
    const absent = fixture.markers.filter(m => m.severity === null)
    expect(absent.length).toBe(74)
    for (const s of ['Critical', 'Major', 'Minor']) {
      const inTable = table.split(s).length - 1
      const inData = fixture.markers.filter(m => m.severity === s).length
      expect(inTable, `${s} appears ${inTable} times for ${inData} markers`).toBeLessThanOrEqual(
        inData + 1,
      )
    }
  })

  it('escapes prose that carries markup, because model answers do', () => {
    const risky = fixture.markers.filter(
      m => m.verdictText.includes('<') || m.summary.includes('<'),
    )
    if (risky.length > 0) {
      expect(table).not.toContain('<script')
      // React escapes text nodes; the point is that nothing was passed as raw HTML.
      expect(table).toContain('&lt;')
    }
  })

  it('does not claim a build result for a marker still in flight', () => {
    const inFlight = fixture.markers.filter(m => !m.hasSettlement)
    expect(inFlight.length).toBeGreaterThan(0)
    for (const m of inFlight.slice(0, 20)) {
      expect(toRow(m).flags, `${m.id} has no settling row, so no lamp may be lit or unlit`).toBeNull()
    }
  })

  it('agrees with the run totals the API computed', () => {
    const progress = renderToStaticMarkup(
      <RunProgress
        total={fixture.run.total}
        settled={fixture.run.settled}
        beganAt={fixture.run.beganAt}
        now={fixture.run.serverNow}
      />,
    )
    expect(progress).toContain(String(fixture.run.total))
    expect(progress).toContain(String(fixture.run.settled))

    const counts = renderToStaticMarkup(
      <StateCounts
        counts={fixture.run.countsByState as Partial<Record<MarkerState, number>>}
        humanMinutes={fixture.run.humanMinutes}
      />,
    )
    for (const [state, n] of Object.entries(fixture.run.countsByState)) {
      expect(counts, `${state}=${n}`).toContain(String(n))
    }
  })
})
