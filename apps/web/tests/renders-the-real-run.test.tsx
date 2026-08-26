import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { MarkerGroups, MarkerTable, RunProgress, StateCounts, groupsOf, type MarkerRowData } from '@fsm/ui'
import { SEVERITIES, type MarkerState, type Severity } from '@fsm/types'

import fixture from './fixture.json' with { type: 'json' }

/**
 * THE COMPONENTS, AGAINST A PAYLOAD THE RUNNING SYSTEM ACTUALLY PRODUCED.
 *
 * <p>`fixture.json` is a verbatim capture of `GET /api/index` from the live record: 857 markers over
 * two repositories, the real states, the real absent severities, the real prose with its quotes and
 * newlines. Not a fixture somebody wrote to match the code — the whole failure mode this port has hit
 * twice is a test whose fixture agrees with the bug.
 *
 * <p>IT WAS 356 MARKERS OF ONE REPOSITORY and it is now two, because the run is. A capture of a
 * one-project run cannot fail on anything grouping gets wrong, and grouping is now what this screen
 * does: 416 of these markers are in a single module, which is the case a flat table was hiding.
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
    project: m.project,
    module: m.module,
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
const page = renderToStaticMarkup(<MarkerGroups markers={rows} />)

describe('the markers screen renders the real run', () => {
  it('draws a row for every marker the run was given', () => {
    expect(rows).toHaveLength(857)
    // One <tr> per marker, plus a header row per group.
    const tr = page.split('<tr').length - 1
    expect(tr).toBeGreaterThanOrEqual(857)
  })

  it('keeps the queue order, which is the run phase plan', () => {
    const first = page.indexOf('Assignment5.java')
    const second = page.indexOf('SqlInjectionLesson5b.java')
    expect(first).toBeGreaterThan(-1)
    expect(second).toBeGreaterThan(first)
  })

  it('shows every state the record actually holds', () => {
    for (const state of Object.keys(fixture.run.countsByState)) {
      expect(page, `${state} is in the payload and must reach the page`).toContain(state)
    }
  })

  it('renders the markers with no severity without inventing one', () => {
    const absent = fixture.markers.filter(m => m.severity === null)
    expect(absent.length).toBe(74)
    // COUNTED BY THE BADGE'S OWN COLOUR TOKEN, not by the word. This counted occurrences of
    // "Critical", "Major" and "Minor" anywhere in the markup and allowed one extra for slack — and
    // the words are English: 67 "Major"s for 61 badges, because six model verdicts use the word in
    // a sentence. A token is markup this component emits exactly once per badge.
    for (const s of SEVERITIES) {
      const drawn = page.split(`var(--severity-${s.toLowerCase()})`).length - 1
      const held = fixture.markers.filter(m => m.severity === s).length
      expect(drawn, `${s}: ${drawn} badges drawn for ${held} markers`).toBe(held)
    }
    // AND EVERY SEVERITY IN THE RECORD IS ONE THE PAGE HAS A COLOUR FOR. `Normal` reached this
    // fixture from the second project and was in neither the type nor the stylesheet: the badge
    // drew `var(--severity-normal)`, which resolved to nothing.
    const vocabulary = new Set<string | null>([...SEVERITIES, null])
    const unknown = fixture.markers.filter(m => !vocabulary.has(m.severity))
    expect(unknown.map(m => m.severity), 'a severity nobody has a token for').toEqual([])
    // The dash is what absent looks like, and it is drawn once per marker that has no severity.
    expect(page.split('not in severities.tsv').length - 1).toBe(absent.length)
  })

  it('escapes prose that carries markup, because model answers do', () => {
    const risky = fixture.markers.filter(
      m => m.verdictText.includes('<') || m.summary.includes('<'),
    )
    if (risky.length > 0) {
      expect(page).not.toContain('<script')
      // React escapes text nodes; the point is that nothing was passed as raw HTML.
      expect(page).toContain('&lt;')
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
        demonstrated={fixture.run.demonstrated}
        beganAt={fixture.run.beganAt}
        now={fixture.run.serverNow}
      />,
    )
    expect(progress).toContain(String(fixture.run.total))
    expect(progress).toContain(String(fixture.run.settled))
    // AND THE SPLIT REACHES THE PAGE. `settled` alone counted a marker closed by a paragraph the
    // same as one where a test failed before the patch and passed after it — 132 of 347 in the run
    // this fixture came from. A reader cannot ask for the difference if the page never offers it.
    expect(progress).toContain('shown by a test')
    expect(progress).toContain('argued only')
    expect(fixture.run.demonstrated).toBeLessThanOrEqual(fixture.run.settled)

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

/**
 * THE QUEUE IS TWO PROJECTS AND SIXTEEN MODULES, and the screen has to say so.
 *
 * <p>What the flat table did with this exact payload: 857 rows, the 356 of one project followed by
 * the 501 of another, no heading anywhere naming either, and the largest module on the page — 416
 * markers, half the run — indistinguishable from the twelve modules holding one or two.
 */
describe('the markers screen groups the real run by repository and module', () => {
  it('finds every module the record holds, and no invented one', () => {
    const groups = groupsOf(rows)
    // Sixteen, counted from the payload rather than written down: a number in a test is a copy of a
    // fact, and this one changes the day somebody queues a seventeenth module.
    const distinct = new Set(fixture.markers.map(m => `${m.project} ${m.module}`))
    expect(groups).toHaveLength(distinct.size)
    expect(groups.every(g => g.markers.length > 0)).toBe(true)
    expect(groups.reduce((n, g) => n + g.markers.length, 0)).toBe(857)
  })

  it('names both projects and counts what each has decided', () => {
    const webgoat = rows.filter(r => r.project === 'WebGoat')
    const ca2 = rows.filter(r => r.project === 'ca2_back')
    expect(webgoat).toHaveLength(356)
    expect(ca2).toHaveLength(501)
    expect(page).toContain(`WebGoat — ${webgoat.length} markers`)
    expect(page).toContain(`ca2_back — ${ca2.length} markers`)
  })

  it('names the module that is half the run, which the flat table could not', () => {
    expect(page).toContain('ca2-client/ca2-messages-client')
    expect(page).toContain('416 markers')
  })

  it('groups in queue order and never in decided order', () => {
    // ca2_back's first module in the queue is not its biggest, and grouping must not promote it.
    const groups = groupsOf(rows).filter(g => g.project === 'ca2_back')
    const firstMentioned = fixture.markers.find(m => m.project === 'ca2_back')?.module
    expect(groups[0]?.module).toBe(firstMentioned)
    // And every group's rows keep the order the queue gave them.
    for (const group of groups) {
      const queued = fixture.markers
        .filter(m => m.project === group.project && m.module === group.module)
        .map(m => m.key)
      expect(group.markers.map(r => r.key)).toEqual(queued)
    }
  })

  it('takes the module off the path it has already named in the heading', () => {
    // `ca2-client/ca2-messages-client/src/main/java/…` is 33 characters of heading repeated down 416
    // rows. The rows say where in the module; the heading says which module.
    const inside = rows.filter(r => r.module === 'ca2-client/ca2-messages-client')
    const only = renderToStaticMarkup(<MarkerGroups markers={[...inside.slice(0, 3), ...rows.slice(0, 1)]} />)
    expect(only).toContain('ca2-client/ca2-messages-client')
    // The directory line under a row in that module no longer repeats it.
    expect(only).not.toContain('>ca2-client/ca2-messages-client/ru')
  })

  it('still draws one flat table when the run is one repository, as it always did', () => {
    // THE FALLBACK IS A REAL PATH, not a defensive branch: this is every run this dashboard had
    // before today, and the same rows through the same components must come out as the plain table.
    const webgoat = rows.filter(r => r.project === 'WebGoat')
    const grouped = renderToStaticMarkup(<MarkerGroups markers={webgoat} />)
    const flat = renderToStaticMarkup(<MarkerTable markers={webgoat} />)
    expect(grouped).toBe(flat)
    // No heading and no group fold. (The `<details>` in a row is the interpretation cell's own,
    // which is why this asks about the group's summary rather than about `<details>`.)
    expect(grouped).not.toContain('the repository root')
    expect(grouped).not.toContain('markers · ')
  })
})
