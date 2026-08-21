import type { MarkerState } from '@fsm/types'
import type { Style } from '../primitives/style'
import type { SettlementFlags } from './records'

export type SemaphoreProps = {
  /** No settlement row ⇒ renders nothing. There is no lamp for a marker nobody has judged. */
  flags: SettlementFlags | null
  state: MarkerState
}

export type LampProps = {
  which: 'red' | 'green'
  /** The build said so. */
  lit: boolean
  /** This stage was got to at all. */
  reached: boolean
}

/**
 * What each lamp is FOR, which is the only place on this dashboard that says red is supposed to
 * fail.
 *
 * `Lamp` OWNS ITS TOOLTIP and takes no `title` prop. Java passed one from both call sites and both
 * passed a fixed sentence chosen entirely by `which` (2356-2357) — a prop that only ever holds one
 * value per branch of another prop is that branch, spelled twice, waiting to disagree.
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

const SEMA: Style = { display: 'flex', gap: '5px', marginTop: '5px', alignItems: 'center' }

const LAMP: Style = {
  width: '9px',
  height: '9px',
  borderRadius: '50%',
  display: 'block',
  flex: '0 0 auto',
  border: '1px solid var(--build-none)',
}

function lampStyle(which: 'red' | 'green', lit: boolean, reached: boolean): Style {
  if (lit) {
    return {
      ...LAMP,
      // Set once, read three times, so the fill, the edge and the glow cannot drift apart.
      '--lamp': which === 'red' ? 'var(--build-red)' : 'var(--build-green)',
      background: 'var(--lamp)',
      borderColor: 'var(--lamp)',
      boxShadow: '0 0 5px color-mix(in srgb, var(--lamp) 40%, transparent)',
    }
  }
  if (reached) {
    return { ...LAMP, background: 'var(--bg-subtle)' }
  }
  // HOLLOW IS NOT "NO". Dashed and empty, the same shape this codebase uses for an agent that never
  // ran (see `AgentChip`), because both are saying "this never happened" rather than "this failed".
  return { ...LAMP, background: 'transparent', borderStyle: 'dashed' }
}

/**
 * One lamp: lit, dim, or hollow.
 *
 * THREE APPEARANCES, AND THE MIDDLE ONE IS THE INTERESTING ONE. Lit means the build said so. Dim
 * means the stage was reached and it did not happen — a RED that passed, a GREEN that failed, which
 * is the shape of a proof that is not one. Hollow means it was never got to, and a marker whose
 * reproducer declined never had a red to fail: that is a different answer from a red that passed,
 * and two lamps that could only be on or off would tell the reader they were the same.
 *
 * `role="img"` with the whole sentence as its label, not a bare `title` on an empty element the way
 * Java did it: a decorative `<i>` with a tooltip is invisible to anyone not holding a mouse, and
 * these tooltips carry the standard of proof.
 */
export function Lamp({ which, lit, reached }: LampProps) {
  const appearance = lit ? 'lit' : reached ? 'dim' : 'hollow'
  const title = `${MEANS[which]} — ${APPEARANCE[appearance]}`
  return <span role="img" aria-label={title} title={title} style={lampStyle(which, lit, reached)} />
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
 * A `red` of null (a settlement row that never recorded the bit) renders the same as `false` today,
 * because `reached` is a fact about the state and not about the field — that is Java's rule, kept
 * deliberately. If the record ever shows a declined reproducer reading as a red that passed, this
 * derivation is the line to change, and `SettlementFlags` already carries the fact it would need.
 */
export function Semaphore({ flags, state }: SemaphoreProps) {
  if (flags === null) {
    return null
  }
  return (
    <div style={SEMA} role="group" aria-label="what the builds said">
      {/* `=== true`, AGAINST A REAL BOOLEAN. Java wrote `red_verified`/`green_verified` unquoted
          into settlements.jsonl, so `field()` handed back the bare word and `"true".equals(red)`
          was comparing a string to something that was never quoted — which is the whole reason
          `field()` grew an unquoted branch (2707-2722), and the reason the semaphore never lit on a
          marker that had genuinely gone red. Nothing here may accept the STRING "true". */}
        <Lamp which="red" lit={flags.red === true} reached={flags.red !== null && state !== 'queued'} />
      <Lamp which="green" lit={flags.green === true} reached={flags.red === true} />
    </div>
  )
}
