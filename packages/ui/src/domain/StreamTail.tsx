import type { Style } from '../primitives'

export type StreamTailProps = {
  /**
   * The END of what the agent has written so far, not the start.
   *
   * Raw model output. React escapes a text child, which is the whole protection here — never reach
   * for `dangerouslySetInnerHTML` on this prop. The Java put it through `esc()` for the same reason
   * (panel() 931) and this is the same rule expressed as "pass it as a child".
   */
  text: string
  /**
   * The text was cut FROM THE HEAD by the server, at `LIVE_TAIL` = 4000 characters
   * (ApiLive.LIVE_TAIL).
   *
   * The cut stays server-side because at a two-second poll it is a bandwidth decision as much as a
   * presentation one — but then the client is holding a fragment it could mistake for the whole
   * answer, so the wire admits it and this component says so out loud.
   */
  truncated: boolean
}

/**
 * A MODEL TURN WRAPS, unlike the source and diff blocks.
 *
 * {@link CodeBlock} uses `white-space: pre` because a wrapped line of source stops lining up with
 * the line number beside it. Nothing lines up here: this is prose with 200-character paragraphs in
 * it, and `pre` alone would put every one of them behind a horizontal scrollbar.
 *
 * NO AUTOSCROLL, and the height cap is why. The panel is replaced every two seconds; a box that
 * jumped to the bottom on each swap would tear the paragraph out from under a reader who had
 * scrolled up to read the start of it. `max-height` + `overflow:auto` leaves the scroll position
 * where the reader put it.
 */
const TAIL: Style = {
  margin: 0,
  padding: '2px 2px 8px',
  maxHeight: '20rem',
  overflow: 'auto',
  fontSize: '12px',
  lineHeight: 1.5,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  // The Java painted this purple (`#a371f7`, CSS 95) on a page that had only a dark theme. There is
  // no token for "a model is thinking", and the two purples this domain does have mean settled
  // by-design and a diff's hunk header — painting four thousand characters of body text in a STATE
  // colour would assert a state about a prove that has settled nothing.
  color: 'var(--text-secondary)',
}

/** Outside the `<pre>` on purpose: copying the tail should yield the model's words and nothing else. */
const CUT: Style = {
  margin: 0,
  padding: '2px 2px 4px',
  fontSize: '11px',
  color: 'var(--text-tertiary)',
}

/**
 * What the model has said so far, as far as it has got.
 *
 * THE TAIL, NOT THE HEAD (panel() 926-928). A reasoning turn runs to tens of thousands of
 * characters and the end is where it is now; opening on the beginning shows the same paragraph for
 * four minutes and a reader concludes the page has died.
 *
 * A blank turn renders one ellipsis rather than an empty box, because an empty bordered box asserts
 * that the agent said something and that the something was nothing. It has simply not started.
 */
export function StreamTail({ text, truncated }: StreamTailProps) {
  return (
    <>
      {truncated ? (
        <p style={CUT}>
          the last {text.length} characters — the start of this turn is in the trace
        </p>
      ) : null}
      <pre style={TAIL}>{text.trim().length === 0 ? '…' : text}</pre>
    </>
  )
}
