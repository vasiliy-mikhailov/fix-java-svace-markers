import type { Style } from './style'

/**
 * One destination in a tab row.
 *
 * `href` is a PROP and not something the component builds. The Java's `tab()` (2139-2142) hard-coded
 * `/marker?k=…`, so the supervisor's tabs and the settings tabs — which are not markers — each
 * hand-rolled their own anchors rather than use it, and `tab()` ended up with zero call sites while
 * three copies of the same markup drifted apart. Whoever knows the URL passes the URL.
 */
export type TabItem = { href: string; label: string; on: boolean }

export type TabRowProps = {
  items: TabItem[]
  /**
   * DEPARTURES, NOT TABS. "the supervisor", "settings" — links that leave this row's set rather
   * than choose within it. They are never lit, whatever `on` says, because lighting one would
   * claim the reader is already there.
   */
  trailing?: TabItem[]
}

const NAV: Style = {
  display: 'flex',
  gap: '2px',
  flexWrap: 'wrap',
  padding: '10px 24px',
  borderBottom: '1px solid var(--border-soft)',
}

const LINK: Style = {
  padding: '5px 11px',
  borderRadius: '6px',
  fontSize: '12px',
  color: 'var(--text-tertiary)',
  textDecoration: 'none',
}

const ON: Style = {
  ...LINK,
  background: 'var(--state-selected-bg)',
  color: 'var(--state-selected-text)',
}

const DEPARTURE: Style = { ...LINK, marginLeft: 'auto' }

/**
 * The tab row every screen shares, and the merge the Java could not do.
 *
 * BUG NOT PORTED (Dashboard `settingsTabs()` 1126-1128): the prompts tab was lit with
 * `current.equals("run") ? "" : "on"` — the negation of the RUN test rather than its own — so on
 * `?a=model` and `?a=subject` two tabs were lit at once and the row stopped saying where you were.
 * Here each item carries its own `on` and nothing tests another item's key.
 */
export function TabRow({ items, trailing }: TabRowProps) {
  return (
    <nav style={NAV}>
      {items.map(item => (
        <a key={item.href} href={item.href} style={item.on ? ON : LINK} aria-current={item.on ? 'page' : undefined}>
          {item.label}
        </a>
      ))}
      {(trailing ?? []).map((item, index) => (
        <a key={item.href} href={item.href} style={index === 0 ? DEPARTURE : LINK}>
          {item.label}
        </a>
      ))}
    </nav>
  )
}
