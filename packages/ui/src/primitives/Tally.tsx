import type { ReactNode } from 'react'
import type { Style } from './style'

export type TallyProps = {
  /** A node, not a number: `settled / total`, `1h 12m` and `—` are all legitimate values here. */
  value: ReactNode
  label: string
}

const BOX: Style = {
  padding: '6px 12px',
  border: '1px solid var(--border-soft)',
  borderRadius: '6px',
  background: 'var(--bg-card)',
}

const VALUE: Style = { fontSize: '17px', display: 'block', fontWeight: 600 }

const LABEL: Style = { color: 'var(--text-tertiary)', fontSize: '11px' }

/**
 * One count, in the strip of counts at the top of the markers list.
 *
 * TWO ADJACENT STRIPS ON THAT PAGE IS DELIBERATE, not a merge somebody forgot: `progress()`
 * (2268-2283) emits one — settled of total, elapsed, extrapolated eta — and `index()` (1701-1708)
 * emits a second immediately after, one box per state plus the human-equivalent total. They count
 * different things and merging them would put "elapsed" next to "by-design".
 */
export function Tally({ value, label }: TallyProps) {
  return (
    <div style={BOX}>
      <b style={VALUE}>{value}</b>
      <span style={LABEL}>{label}</span>
    </div>
  )
}
