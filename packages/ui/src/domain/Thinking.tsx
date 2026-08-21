// THE HEADING IS THE LIBRARY'S, AND THE MARGIN STAYS OURS. Our four copies of these five
// declarations all omitted `fontWeight`, which is not a declaration that drifted but one nobody
// made: an `h3` with no weight takes the browser's bold, so ours drew at 700 beside the
// sibling's 500. The shared constant names the weight; only the margin is per-site.
import { HEADING } from 'ratchet-ui/components'
import type { Style } from '../primitives'

export type ThinkingProps = {
  /**
   * IN RECORD ORDER — oldest first, exactly as the trace holds them. This component reverses.
   *
   * The reversal is here and not in the caller because it is a presentation decision (the newest
   * thought is the live one and belongs at the top) and because a screen that reversed the array
   * before passing it would make `id` the only thing left that says which thought came first.
   */
  turns: { id: string; text: string }[]
}

const LIST: Style = { display: 'grid', gap: '6px', margin: '10px 0' }

const ONE: Style = {
  margin: 0,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  borderLeft: '2px solid var(--state-by-design)',
  paddingLeft: '10px',
  fontSize: '12.5px',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
}

/**
 * What the agent said to itself.
 *
 * THIS IS GATHERED BEFORE THE EARLY RETURNS, and that is the whole point of it (1959-1962). An agent
 * seven tool calls into a job has answered nothing, so every "render the answer" branch above skips
 * it — and its thinking is then the ONLY account of what it is doing. A port that renders thinking
 * alongside an answer, as a supporting detail, loses the live case completely: the page goes blank
 * for exactly as long as the interesting part lasts.
 *
 * NOT FOLDED, for the same reason. `TextFold` would give a tidy `(1,240 chars)` summary and hide the
 * one thing on the page that is moving.
 */
export function Thinking({ turns }: ThinkingProps) {
  if (turns.length === 0) {
    return null
  }
  // `toReversed` would be tidier; `slice().reverse()` does not need ES2023 in the lib list.
  const newestFirst = turns.slice().reverse()
  return (
    <section style={LIST}>
      <h3 style={{ ...HEADING, margin: 0 }}>thinking</h3>
      {newestFirst.map(turn => (
        <p key={turn.id} id={turn.id} style={ONE}>
          {turn.text}
        </p>
      ))}
    </section>
  )
}
