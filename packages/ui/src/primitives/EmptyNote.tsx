import type { ReactNode } from 'react'
import type { Style } from './style'

export type EmptyNoteProps = { children: ReactNode }

const NOTE: Style = { padding: '48px 24px', color: 'var(--text-tertiary)' }

/**
 * What a page says when there is nothing on it.
 *
 * ONE COMPONENT, AND THE COPY IS NOT A PROP DEFAULT. Three passes proposed `EmptyRun`, `EmptyTrace`
 * and `EmptyNote` with the sentence baked into each; the sentences are the only thing that differed
 * and they do real work — "a quiet page is a good sign", "the supervisor looks on its own
 * schedule", "it can see every marker's state, builds, answers and settlement". A default sentence
 * here would be the one that gets shown on a screen nobody wrote copy for, and a wrong reassurance
 * is worse than a blank page.
 *
 * Which is what happened: `/trace` hard-coded "Nothing traced for this marker" (2191) and showed it
 * unchanged on the whole-trace view, where there is no marker and nothing traced anywhere. That is
 * fixed in the caller's copy, not by a prop.
 */
export function EmptyNote({ children }: EmptyNoteProps) {
  return <div style={NOTE}>{children}</div>
}
