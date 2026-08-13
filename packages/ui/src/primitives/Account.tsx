import type { ReactNode } from 'react'
import type { Style } from './style'

export type AccountProps = {
  children: ReactNode
  /** The `<span class=k>` variant: the aside under a control, rather than the account beside it. */
  quiet?: boolean
}

const ACCOUNT: Style = {
  margin: '.5rem 0 .2rem',
  fontSize: '.95rem',
  lineHeight: 1.7,
  color: 'var(--text-secondary)',
  maxWidth: '52em',
}

const QUIET: Style = { ...ACCOUNT, fontSize: '.74rem', lineHeight: 1.6, color: 'var(--text-tertiary)' }

/**
 * A paragraph explaining what a control does and why it is a control at all.
 *
 * THE PROSE STAYS IN THE COMPONENT TREE AND NEVER GOES ON THE WIRE. It is not data — it does not
 * come from the record, it does not change per marker, and an API that ships it is an API whose
 * payload grows every time somebody improves a sentence. One exception, and it earns itself:
 * `theRun`'s paragraph interpolates `Workers.LEAST/MOST/DEFAULT` (1451-1452), so that sentence
 * lives inside `ParallelProvers` where those numbers already are.
 */
export function Account({ children, quiet = false }: AccountProps) {
  return <p style={quiet ? QUIET : ACCOUNT}>{children}</p>
}
