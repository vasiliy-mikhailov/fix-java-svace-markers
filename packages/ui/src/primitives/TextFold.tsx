import { Disclosure } from './Disclosure'
import type { Style } from './style'

export type TextFoldProps = {
  /** See {@link DisclosureProps.id} — keyed by the thing, never by position. */
  id: string
  /** The bare label. The `(N chars)` is computed here from `body`; never pass a count. */
  label: string
  body: string
  defaultOpen?: boolean
}

const BODY: Style = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  background: 'var(--bg-card)',
  border: '1px solid var(--border-soft)',
  borderRadius: '6px',
  padding: '10px',
  margin: '8px 0',
  overflowX: 'auto',
  fontSize: '12px',
  lineHeight: 1.5,
  color: 'var(--text-secondary)',
}

/**
 * A fold over a blob of text, labelled with how much text it is.
 *
 * The size belongs in the label because the decision a reader makes at a closed fold is whether it
 * is worth opening, and "the prompt" answers that differently at 300 characters and at 30,000.
 *
 * AN EMPTY BODY RENDERS NOTHING AT ALL — not an empty fold (Java `fold()` 2363-2367 returned "").
 * That is load-bearing in two places and both are absences a reader is meant to notice: a tool call
 * that produced no result shows ONE fold instead of two, and an unjudged finding's "what the critic
 * said" simply is not on the page. A fold that opens onto nothing says the critic answered with
 * silence, which is not what happened.
 */
export function TextFold({ id, label, body, defaultOpen = true }: TextFoldProps) {
  if (body.length === 0) {
    return null
  }
  return (
    <Disclosure id={id} defaultOpen={defaultOpen} summary={`${label} (${body.length} chars)`}>
      <pre style={BODY}>{body}</pre>
    </Disclosure>
  )
}
