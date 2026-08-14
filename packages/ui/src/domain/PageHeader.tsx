import type { ReactNode } from 'react'
import type { Style } from '../primitives'
import { FindingsButton } from './FindingsButton'

/**
 * Somewhere to go back to.
 *
 * A LABEL IS NOT A DESTINATION, which is what the Java assumed: `head(title, sub, back)` took the
 * words and hard-coded the href as `/` (2468-2469) whatever the words said. Every caller happened
 * to mean "all markers", so the coincidence never bit — and a screen whose back went anywhere else
 * would have shipped a link that lied. Carrying both fields is the port's chance to stop it being
 * a coincidence.
 */
export type Crumb = { label: string; href: string }

export type PageHeaderProps = {
  title: string
  /**
   * A NODE, NOT A STRING, and this is the single most reconciled prop in the catalogue: nine passes
   * named this component three ways and five of them typed the subtitle `string`.
   *
   * `head()` escaped the title and appended the subtitle RAW (2470-2471). It had to: callers push
   * entities through it (`&middot;`, `&mdash;`) and `/marker` (1849) and `/trace` (2181) push a
   * whole `<span class='s …'>` state pill. A `string` prop here re-opens that hole on the first
   * screen that puts a repo name — or a checker with an `&` in it — in its subtitle. So the
   * screens compose: `<>{key} · <StateBadge state={state} /></>`.
   */
  subtitle: ReactNode
  /** Absent on `/`: `index()` calls the two-arg overload, because the list is where back GOES. */
  back?: Crumb
  findingsOpen: number
}

const HEADER: Style = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: '12px',
  padding: '16px 24px',
  borderBottom: '1px solid var(--border-soft)',
}

const TITLE: Style = { margin: 0, fontSize: '14px', fontWeight: 600 }

const SUB: Style = { color: 'var(--text-tertiary)', fontSize: '12px', marginTop: '3px' }

const CRUMB: Style = {
  display: 'inline-block',
  marginBottom: '6px',
  fontSize: '12px',
  color: 'var(--text-tertiary)',
  textDecoration: 'none',
}

/**
 * The three corner controls, in a row rather than at three hand-measured offsets.
 *
 * The Java positioned them absolutely at `right:.9rem`, `2.6rem` and `4.7rem` (CSS 107-118), so
 * adding a fourth meant re-measuring the other three, and the rule at 118 is the same selector
 * repeated after somebody lost that argument once. A flex row cannot drift.
 */
const ACTIONS: Style = { marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '2px' }

const GEAR: Style = {
  fontSize: '1.25rem',
  lineHeight: 1,
  color: 'var(--text-tertiary)',
  textDecoration: 'none',
  padding: '0.2rem 0.35rem',
  borderRadius: '5px',
}

/**
 * The header all ten screens wear — Java `head()` 2446-2472.
 *
 * WHAT DID NOT COME WITH IT. `head()` also emitted `<style>`, the `LIVE` script and `KEEP_OPEN`
 * (2458-2459), because in a server that concatenates strings the header is simply whatever runs
 * first. None of the three is a header's job here: the stylesheet is the app shell's, SSE is a
 * hook, and fold memory belongs to whoever renders folds — which is also what let `KEEP_OPEN` key
 * folds by DOM position and reopen the wrong one (bug #10).
 *
 * ESCAPING. The Java escaped `title` and not `subtitle`, and only comments kept the two apart.
 * React escapes both; nothing here concatenates markup, so the hole cannot be reopened by a caller
 * who did not read this far.
 *
 * SETTINGS, ASK AND FINDINGS ARE HERE and take no props, because none of them varies by screen.
 * They were once a text link at the BOTTOM of the list, under 356 rows — a link nobody has, since
 * a reader who does not already know the page exists will not scroll past the whole run to find
 * it. Next to each other because all three mean "leave this page and do something", and a reader
 * looking for any of them looks in the same corner.
 */
export function PageHeader({ title, subtitle, back, findingsOpen }: PageHeaderProps) {
  return (
    <header style={HEADER}>
      <div>
        {back === undefined ? null : (
          <a href={back.href} style={CRUMB}>
            {'← '}
            {back.label}
          </a>
        )}
        <h1 style={TITLE}>{title}</h1>
        <div style={SUB}>{subtitle}</div>
      </div>
      <div style={ACTIONS}>
        <FindingsButton open={findingsOpen} />
        <a href="/chat" style={GEAR} title="ask the supervisor" aria-label="ask the supervisor">
          <span aria-hidden="true">{'✉'}</span>
        </a>
        <a href="/settings" style={GEAR} title="settings" aria-label="settings">
          <span aria-hidden="true">{'⚙'}</span>
        </a>
      </div>
    </header>
  )
}
