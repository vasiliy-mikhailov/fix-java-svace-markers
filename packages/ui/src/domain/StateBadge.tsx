import type { MarkerState } from '@fsm/types'
import { Pill, type PillTone } from '../primitives'

export type StateBadgeProps = {
  state: MarkerState
  /**
   * Set only where the word is worth clicking.
   *
   * The caller knows when the pill is a link; the badge cannot. One pass proposed passing
   * `markerKey` "only used when the pill is a link", which puts URL-building inside a component
   * that renders the same word on four screens with three different destinations — and leaves the
   * key sitting unused on the three where it is not.
   *
   * On the markers list this is `/marker?k=…&a=live`, and ONLY for `proving` (index 1761-1764).
   */
  href?: string
}

/**
 * STATE → TONE, WHICH IS THE WHOLE FIX FOR BUG #1.
 *
 * `css()` (Dashboard 2714-2717) was one line: `state.matches("[a-z-]+") ? state : "infra"`. Both
 * verified states contain a slash, so `verified/pr-ready` and `verified/pr-rejected` — the two
 * outcomes the whole pipeline exists to reach — have been rendering in the red of a machine that
 * threw, and the `.verified-pr-ready` / `.verified-pr-rejected` rules at CSS 54 have never once
 * been reached by anything. THIS TABLE CHANGES WHAT THE PAGE LOOKS LIKE, knowingly.
 *
 * The tones are not a reduction of the palette: the Java itself drew eleven states in seven
 * colours (CSS 54-55, 184-200), and this maps onto the six the `Pill` offers with one merge —
 * `queued` joins the greys it already sat beside. Each row below matches what the Java actually
 * rendered, not what its dead rules said:
 *
 *   - `verified/pr-rejected` is grey, not the green of the unreachable rule. The pipeline reached
 *     a conclusion; the PR was refused. `--state-verified-pr-rejected` in `domain.css` is that
 *     same grey, so nothing is lost by borrowing the `quiet` tone for it.
 *   - `interrupted` is `aside`, whose token is the purple the Java gave it (CSS 192).
 *   - `by-design` is `quiet`, the grey the Java gave it (CSS 184) — informational, argued away.
 *   - `infra` is `alarm` and NOTHING ELSE MAY BE. In this app the infra red means "never ran"; a
 *     conclusion wearing it is a settled marker impersonating a broken one.
 */
const TONE: Record<MarkerState, PillTone> = {
  'verified/pr-ready': 'good',
  'verified/pr-rejected': 'quiet',
  reproduced: 'warn',
  'needs-review': 'warn',
  'false-positive': 'quiet',
  'by-design': 'quiet',
  unprovable: 'quiet',
  proving: 'running',
  queued: 'quiet',
  infra: 'alarm',
  interrupted: 'aside',
}

/**
 * A marker's state, as a word in a pill — the markers list, the marker header, the supervisor's
 * subtitle, and every `settled` event in a trace.
 *
 * THE WORD IS THE RECORD'S WORD. `verified/pr-ready` is printed with its slash and not prettified
 * into "PR ready", because it is the string in `settlements.jsonl`, the string `entrypoint.sh`
 * greps for, and the string a reader will search this page for after reading a file.
 *
 * `proving` is the one state still happening, so it is the one that pulses (the `running` tone
 * carries the Java's `.proving::before` dot) and, on the index, the one that is a link: every
 * other word in that column is a conclusion and reads fine as text, but this one is a question —
 * what is it doing — and the answer is one page away.
 */
export function StateBadge({ state, href }: StateBadgeProps) {
  const tone = TONE[state]
  // `exactOptionalPropertyTypes`: an absent link is an ABSENT prop, never `href={undefined}`.
  return href === undefined ? (
    <Pill tone={tone}>{state}</Pill>
  ) : (
    <Pill tone={tone} href={href}>
      {state}
    </Pill>
  )
}
