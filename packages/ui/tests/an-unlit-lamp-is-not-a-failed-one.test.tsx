import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'

import { Semaphore } from '../src/domain/Semaphore'
import type { SettlementFlags } from '../src/domain/records'

/**
 * THE COMPONENT THAT HAD NO TEST, DRAWING THE CLAIM THIS PIPELINE MUST NEVER MAKE BY ACCIDENT.
 *
 * A marker whose prove died fetching its checkout arrived here as `red: false` with a state of
 * `infra`, and `reached` was derived from the state — so it drew the DIM appearance, whose own
 * label reads "reproduced: the test failed first — it was reached and did not happen". Nothing was
 * reached. No test was ever written.
 *
 * The record can express "no build ran" now and `reached` asks the field. This holds both ends of
 * that, because the failure is invisible from either alone: the server can send a perfectly honest
 * null and this component can still draw a denial from it.
 *
 * AND IT HOLDS THE TWO PAGES SEPARATELY, because they disagree and only one was ever covered.
 * `marker/page.tsx` folds two nulls into a null `flags` and stops drawing lamps entirely; the
 * markers table keys on `hasSettlement` and hands over `{ red: null, green: null }`, which still
 * draws two lamps. A fix tested only through the first would have left the second lying.
 */
describe('the semaphore', () => {
  const draw = (flags: SettlementFlags | null, state: string) =>
    renderToStaticMarkup(<Semaphore flags={flags} state={state as never} />)

  const REACHED_AND_DIDNT = 'it was reached and did not happen'
  const NEVER_GOT_THIS_FAR = 'never got this far'
  const THE_BUILD_SAID_SO = 'the build said so'

  it('draws nothing for a marker nobody has judged', () => {
    expect(draw(null, 'queued')).toBe('')
  })

  it('does not say a test was reached when the prove died before reaching it', () => {
    // THE BUG. An infra failure now reports absent rather than false, and absent is hollow.
    const html = draw({ red: null, green: null }, 'infra')
    expect(html, 'nothing ran, so nothing was reached').not.toContain(REACHED_AND_DIDNT)
    expect(html).toContain(NEVER_GOT_THIS_FAR)
  })

  it('and still says so when the test genuinely ran and did not fail', () => {
    // The distinction the whole fix exists to preserve: `false` is a real answer and must survive.
    const html = draw({ red: false, green: null }, 'needs-review')
    expect(html, 'this one really did run').toContain(REACHED_AND_DIDNT)
  })

  it('lights the red lamp when the test failed first, which is the good outcome here', () => {
    const html = draw({ red: true, green: null }, 'needs-review')
    expect(html).toContain(`reproduced: the test failed first — ${THE_BUILD_SAID_SO}`)
  })

  it('never reaches the green lamp for a marker that was never reproduced', () => {
    // `reachedGreen` is derived from the RED having gone red, not from any state: you cannot have
    // fixed a thing you never reproduced, whatever the marker settled as.
    const html = draw({ red: false, green: null }, 'verified/pr-ready')
    expect(html).toContain(`fixed: the same test then passed — ${NEVER_GOT_THIS_FAR}`)
  })

  it('lights both when the test failed and then passed', () => {
    const html = draw({ red: true, green: true }, 'verified/pr-ready')
    expect(html).toContain(`reproduced: the test failed first — ${THE_BUILD_SAID_SO}`)
    expect(html).toContain(`fixed: the same test then passed — ${THE_BUILD_SAID_SO}`)
  })

  it('is hollow while a marker is still queued, whatever its flags say', () => {
    expect(draw({ red: false, green: false }, 'queued')).not.toContain(REACHED_AND_DIDNT)
  })

  it('refuses the STRING "true", which is how the semaphore failed to light for a year', () => {
    // Java wrote these unquoted, `field()` handed back the bare word, and `"true".equals(red)` was
    // comparing a string to something never quoted. Nothing here may accept it.
    const html = draw({ red: 'true' as unknown as boolean, green: null }, 'needs-review')
    expect(html).not.toContain(`reproduced: the test failed first — ${THE_BUILD_SAID_SO}`)
  })
})

/**
 * AND THE THREE STATES HAVE TO BE THREE PICTURES, not three sentences over two pictures.
 *
 * Every assertion above is on `aria-label`, and every one of them was already true and correct while
 * dim and hollow were 1.10:1 apart in light and 1.04:1 in dark — the same neutral ring, and two
 * fills a nine-pixel dot cannot separate. A screen reader got three states; a sighted reader got
 * two. That is an unusual way round for an accessibility defect and it is exactly why a suite
 * asserting only on text could never see it.
 *
 * So this asserts on what is DRAWN. It does not pin the specific colours — those belong to
 * `ratchet-ui` and to this repository's tokens, and pinning them here would break on any retune —
 * it pins that the three differ from each other at all.
 */
describe('the three appearances are three pictures', () => {
  const styleOf = (html: string, nth: number) =>
    [...html.matchAll(/style="([^"]*)"/g)].map(m => m[1] ?? '')[nth] ?? ''

  // red lit / red dim / red hollow, each read off the FIRST lamp so the colour is held constant.
  const lit = styleOf(renderToStaticMarkup(<Semaphore flags={{ red: true, green: null }} state={'needs-review' as never} />), 1)
  const dim = styleOf(renderToStaticMarkup(<Semaphore flags={{ red: false, green: null }} state={'needs-review' as never} />), 1)
  const hollow = styleOf(renderToStaticMarkup(<Semaphore flags={{ red: null, green: null }} state={'infra' as never} />), 1)

  it('draws a lamp at all in each state', () => {
    for (const [name, s] of [['lit', lit], ['dim', dim], ['hollow', hollow]] as const) {
      expect(s.length, `${name} rendered no style`).toBeGreaterThan(0)
    }
  })

  it('parts dim from hollow by more than a dash pattern', () => {
    // THE DEFECT THIS EXISTS FOR. Both used to be a neutral ring around fills 1.10:1 apart.
    expect(dim, 'dim and hollow are the same picture').not.toBe(hollow)
    expect(dim, 'a stage that RAN and answered no is still about that colour').toContain('--build-red')
    expect(hollow, 'a stage never reached has nothing to say in that colour').not.toContain('--build-red')
    expect(hollow, 'and keeps the dash, so a reader who cannot separate two washes still has it')
      .toContain('dashed')
  })

  it('parts lit from dim by the glow, which is what "the build said so" looks like', () => {
    expect(lit).toContain('box-shadow')
    expect(dim, 'a dim lamp must not glow').not.toContain('box-shadow')
  })

  it('and all three are distinct, which is the whole claim', () => {
    expect(new Set([lit, dim, hollow]).size).toBe(3)
  })
})
