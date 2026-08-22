import { Lamp } from 'ratchet-ui/components'

import type { MarkerState } from '@fsm/types'
import type { Style } from '../primitives/style'
import type { SettlementFlags } from './records'

export type SemaphoreProps = {
  /** No settlement row ⇒ renders nothing. There is no lamp for a marker nobody has judged. */
  flags: SettlementFlags | null
  state: MarkerState
}

/**
 * What each lamp is FOR, which is the only place on this dashboard that says red is supposed to
 * fail.
 *
 * THE SENTENCE IS COMPOSED HERE AND PASSED WHOLE. `Lamp` takes `label` rather than a `which`,
 * because what red and green MEAN is this pipeline's vocabulary and nothing in a shared package has
 * any business knowing it — a red lamp lit is a test that failed ON PURPOSE, which is the opposite
 * of what red means anywhere else. That line was drawn when the component went upstream and it is
 * the reason the component could go upstream at all.
 */
const MEANS: Record<'red' | 'green', string> = {
  red: 'reproduced: the test failed first',
  green: 'fixed: the same test then passed',
}

const APPEARANCE = {
  lit: 'the build said so',
  dim: 'it was reached and did not happen',
  hollow: 'never got this far',
} as const

/** Also ours: the package draws one lamp and knows nothing about there being two. */
const SEMA: Style = { display: 'flex', gap: '5px', marginTop: '5px', alignItems: 'center' }

/**
 * Lit, dim, or hollow — and the middle one is the interesting one.
 *
 * Lit means the build said so. Dim means the stage was reached and it did not happen — a RED that
 * passed, a GREEN that failed, which is the shape of a proof that is not one. Hollow means it was
 * never got to, and a marker whose reproducer declined never had a red to fail: that is a different
 * answer from a red that passed, and two lamps that could only be on or off would tell the reader
 * they were the same.
 */
function appearanceOf(lit: boolean, reached: boolean): keyof typeof APPEARANCE {
  return lit ? 'lit' : reached ? 'dim' : 'hollow'
}

function says(which: 'red' | 'green', lit: boolean, reached: boolean): string {
  return `${MEANS[which]} — ${APPEARANCE[appearanceOf(lit, reached)]}`
}

/**
 * THE TWO FACTS A STATE DOES NOT CARRY: did a test fail before the patch, did the same test pass
 * after. Java `flags()` 2346-2359.
 *
 * BUG NOT PORTED (#8): `flags()` had exactly one caller — `index():1765`, the markers table — so the
 * semaphore was never on `/marker`, the one page that exists to show a single marker's evidence.
 * `red_verified`/`green_verified` are per-marker facts living in settlements.jsonl and they are
 * precisely what a disposition cannot tell you. Put this in the marker summary as well as the list.
 *
 * DEAD GUARD DELETED (#14): Java's `reachedRed` also excluded the state `not-a-bug`. Nothing writes
 * that state — it survives only in dead CSS (line 184) and in this guard — so the guard could never
 * fire, and `not-a-bug` is deliberately absent from `MarkerState`.
 *
 * `reachedGreen` is derived from the red having gone red, NOT from any state: you cannot have fixed
 * a thing you never reproduced, whatever the marker settled as.
 *
 * A `red` of NULL IS HOLLOW, and this was the line the old comment here said to change when the
 * record showed a declined reproducer reading as a red that passed. It did: `Settlement.note`'s
 * four-argument form wrote `red_verified=false` on every `infra` row because a primitive `boolean`
 * could not say "nothing ran". The record can express absence now and `reached` asks the FIELD. The
 * state is still consulted, because a queued marker has not reached anything whatever its flags say.
 *
 * AND THE TWO PAGES DISAGREE, so both are covered by the test rather than one: `marker/page.tsx`
 * folds two nulls into a null `flags` and stops drawing lamps, while the markers table keys on
 * `hasSettlement` and hands over `{ red: null, green: null }`, which still draws two.
 *
 * THE LAMP ITSELF IS `ratchet-ui`'s NOW, and it went there from here. What came back is a dim that
 * is made of the caller's colour — an 18% wash inside a 55% ring — where this drew it in
 * `--bg-subtle` inside a neutral ring. That mattered: dim and hollow were 1.10:1 apart in light and
 * 1.04:1 in dark, so on a nine-pixel dot a dash pattern was the whole difference between "this ran
 * and answered no" and "this was never reached". The labels below were always right, so a screen
 * reader got three states and a sighted reader got two.
 */
export function Semaphore({ flags, state }: SemaphoreProps) {
  if (flags === null) {
    return null
  }
  /* `=== true`, AGAINST A REAL BOOLEAN. Java wrote `red_verified`/`green_verified` unquoted into
     settlements.jsonl, so `field()` handed back the bare word and `"true".equals(red)` was comparing
     a string to something that was never quoted — which is the whole reason `field()` grew an
     unquoted branch (2707-2722), and the reason the semaphore never lit on a marker that had
     genuinely gone red. Nothing here may accept the STRING "true". */
  const redLit = flags.red === true
  const redReached = flags.red !== null && state !== 'queued'
  return (
    <div style={SEMA} role="group" aria-label="what the builds said">
      <Lamp
        lit={redLit}
        reached={redReached}
        colour="var(--build-red)"
        label={says('red', redLit, redReached)}
      />
      <Lamp
        lit={flags.green === true}
        reached={redLit}
        colour="var(--build-green)"
        label={says('green', flags.green === true, redLit)}
      />
    </div>
  )
}
