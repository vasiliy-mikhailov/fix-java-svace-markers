import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'

import { AskBox } from '../src/domain/AskBox'
import { MARKER_COLUMNS } from '../src/domain/MarkerRow'
import { MarkerTable } from '../src/domain/MarkerTable'
import type { MarkerRowData } from '../src/domain/MarkerRow'

/**
 * ELEVEN COMPONENTS LEFT THIS PACKAGE AND THE BEHAVIOUR HAD TO STAY.
 *
 * <p>Every shared component in `ratchet-ui` 0.3.0 is the sibling dashboard's implementation, chosen
 * by a rule that has nothing to do with which was better. Most of the time that is fine and the
 * difference is pixels. In four places it is not, because the shared version deliberately does LESS
 * and hands a decision back to the caller — and the caller is this repository.
 *
 * <p>THOSE FOUR DECISIONS ARE NOW ARGUMENTS RATHER THAN CODE, WHICH IS EXACTLY HOW THEY GET LOST.
 * A prop that is quietly dropped in a later edit does not fail to compile: `TimeSpent` without its
 * `> 0` guard renders `0s` for a marker nobody has started, `SectionTabs` without `current: false`
 * lights a link that leaves the page, and `DataTable` without `rowClassName` loses a hover band on
 * the busiest table with no error anywhere. The old components could not be got wrong this way
 * because the rule was inside them.
 *
 * <p>So this holds the seam. It is not testing `ratchet-ui` — that package tests itself — it is
 * testing that what we pass it still says what our own components used to say.
 */
describe('what the adoption had to keep', () => {
  const MARKER: MarkerRowData = {
    key: 'https://x.git|A.java|1|TAINTED_PTR' as MarkerRowData['key'],
    repo: 'https://x.git',
    project: 'x',
    module: '',
    file: 'A.java',
    line: '1',
    checker: 'TAINTED_PTR',
    severity: null,
    state: 'queued',
    flags: null,
    events: 0,
    spanMs: 0,
    humanMinutes: 0,
    headline: '',
    verdictText: '',
    lastNote: '',
    flagged: null,
  }

  it('still dashes a marker nobody has started, rather than saying it took no time', () => {
    // OURS DASHED ON `spanMs <= 0`; THE SHARED ONE DASHES ON `null`. Only the caller knows which of
    // its own fields means "not started" — a positive span here, a start stamp in the sibling — so
    // the rule crossed the boundary and became `ms={spanMs > 0 ? spanMs : null}`.
    const html = renderToStaticMarkup(<MarkerTable markers={[MARKER]} />)
    expect(html, 'a queued marker has a span of 0 and has not taken 0 seconds').not.toContain('0s')
    expect(html).toContain('—')
  })

  it('and dashes a price nobody has estimated', () => {
    // `num()` turns an estimator answering in prose into 0, so the field cannot tell "never priced"
    // from "priced at nothing". The shared component would PRINT the zero, which is an argument
    // worth having and not one to lose by accident.
    const html = renderToStaticMarkup(<MarkerTable markers={[MARKER]} />)
    expect(html).not.toContain('0m')
  })

  it('keeps the event count off a row that has no span', () => {
    // A count beside a dash invites the reading that the job ran and produced nothing.
    const html = renderToStaticMarkup(<MarkerTable markers={[MARKER]} />)
    expect(html).not.toContain('event(s)')
  })

  it('names its six columns in the order one row reads as one sentence', () => {
    expect(MARKER_COLUMNS.map(c => c.head)).toEqual([
      'severity',
      'marker',
      'state',
      'interpretation',
      'took',
      'a person would have',
    ])
  })

  it('right-aligns the two measured columns and nothing else', () => {
    // A column of numbers a reader scans downward compares on its last digit. These were left in
    // this repository and right in the sibling's, and `align` is one prop per column either way.
    const right = MARKER_COLUMNS.filter(c => c.align === 'right').map(c => c.head)
    expect(right).toEqual(['took', 'a person would have'])
  })

  it('passes the hover band from OUR source, where our own Tailwind scan can see it', () => {
    // A utility class only exists if the consumer's generator saw the literal somewhere it scans.
    // Shipped from inside `node_modules` this is a rule that may simply never be emitted — no error,
    // no failing test, and a page that has quietly lost its hover. `the-pulse-survives-the-package`
    // guards the one class the package itself ships; this guards the one we hand it.
    const html = renderToStaticMarkup(<MarkerTable markers={[MARKER]} />)
    expect(html).toContain('hover:bg-[var(--state-hover-bg)]')
  })
})

/**
 * AND THE ONE THING `useAsk` GAVE THIS PAGE HAD NOWHERE HONEST TO GO AT FIRST.
 *
 * <p>`useAsk`'s `busy` is a real gain — between the POST and the re-read that confirms it, `AskBox`
 * was live with the LAST read's `answering` and a second click posted a second question. The
 * shortest way to spend it is `answering={chat.answering || asking.busy}`, and that is wrong: the
 * note under a disabled box says the question being answered "may be one somebody asked in another
 * tab", which is a statement about a process-wide lock and is guaranteed FALSE during this reader's
 * own in-flight post, before the record has heard of the question at all.
 *
 * <p>So the two are separate props with separate sentences, and this is what keeps them separate.
 */
describe('the box shut for two different reasons', () => {
  const box = (props: { answering: boolean; sending?: boolean }) =>
    renderToStaticMarkup(<AskBox {...props} onAsk={() => {}} />)

  it('says the dashboard is busy only when the dashboard is busy', () => {
    expect(box({ answering: true })).toContain('somebody asked in another tab')
  })

  it('and never says that about a question that has not landed yet', () => {
    const html = box({ answering: false, sending: true })
    expect(html, 'the record has not heard of this question').not.toContain('another tab')
    expect(html, 'and a box shut for no visible reason reads as a broken page').toContain(
      'the question is on its way',
    )
  })

  it('shuts the control either way, which is the point of the prop', () => {
    // ON THE TEXTAREA, NOT ON THE MARKUP. The ask BUTTON is disabled whenever the draft is empty,
    // which it always is here — so `toContain('disabled')` passes on an OPEN box and proves nothing.
    const area = (html: string) => html.slice(0, html.indexOf('>', html.indexOf('<textarea')))
    for (const props of [{ answering: true }, { answering: false, sending: true }]) {
      expect(area(box(props)), JSON.stringify(props)).toContain('disabled')
    }
    expect(area(box({ answering: false })), 'and open when neither is true').not.toContain(
      'disabled',
    )
  })
})
