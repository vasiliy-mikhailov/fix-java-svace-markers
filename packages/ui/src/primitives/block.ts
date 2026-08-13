import type { Style } from './style'

/**
 * The shell both {@link CodeBlock} and {@link DiffBlock} render into — Java `block()` 2618-2622.
 *
 * ESCAPING LIVES IN THESE TWO COMPONENTS AND NOWHERE ELSE (Java comment 2601-2607). The Java's
 * `block()` once concatenated its argument into the page unescaped and one caller handed it a
 * `git diff` straight out of the repository, so a patch touching a line with a `<` in it wrote
 * markup into the dashboard.
 *
 * In React that rule reads: these components emit TEXT NODES and `<span>`s they build themselves.
 * Never `dangerouslySetInnerHTML`, and never a pre-coloured HTML blob from the server — either one
 * puts the escaping back in the hands of every caller, which is the arrangement that failed.
 *
 * `white-space: pre` and not `pre-wrap`: a wrapped line of source no longer lines up with the line
 * number beside it, and lining up is the whole point of showing the flagged line in its neighbours.
 */
export const BLOCK: Style = {
  background: 'var(--bg-card)',
  borderLeft: '2px solid var(--border-strong)',
  borderRadius: '6px',
  padding: '8px 10px',
  margin: '8px 0',
  whiteSpace: 'pre',
  overflowX: 'auto',
  color: 'var(--text-secondary)',
  fontSize: '11.5px',
  lineHeight: 1.5,
}
