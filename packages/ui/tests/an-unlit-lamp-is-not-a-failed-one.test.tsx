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
