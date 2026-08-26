'use client'

import { useState, type ReactNode, type ToggleEvent } from 'react'
import type { Style } from './style'

export type DisclosureProps = {
  /**
   * STABLE, AND CARRIED BY THE THING BEING DISCLOSED — a marker key, an event id, `code:<agent>`.
   *
   * Never a position. The Java kept open-state in session storage keyed by `d.id || '#' + index`
   * (KEEP_OPEN 286-307) and `fold()` emitted no id at all, so every fold on a page was keyed by
   * where it happened to sit. On `/settings` the fold exists only for an OVERRIDDEN agent, so
   * reverting one agent renumbered the rest and the wrong agent sprang open on the next render —
   * the same failure KEEP_OPEN's own comment says it had already fixed for the supervisor.
   */
  id: string
  summary: ReactNode
  children: ReactNode
  /**
   * Open unless the reader asked for everything shut. `open()` (Dashboard 2407-2409) reads
   * `?fold=` — PRESENT means fold, absent means open — so the default here is true and a screen
   * turns it off from the URL. `expand` was the Java's name for the opposite of the same fact;
   * it is a URL fact, not a prop name.
   */
  defaultOpen?: boolean
  /**
   * THE HIT AREA BELONGS TO WHOEVER KNOWS HOW BIG THE THING BEING DISCLOSED IS.
   *
   * The default is an 11px line of tertiary text — right for a fold tucked inside a table cell, and
   * pixel-hunting when the fold is a heading over four hundred rows and a reader wants it shut. A
   * `<summary>` is block-level, so the row is already full width; what it lacks is height.
   *
   * The class is separate from the style because a hover band cannot be an inline style, and
   * because a Tailwind utility only exists if the CONSUMER's generator saw the literal in its own
   * source — one shipped from inside a package may simply never be emitted, with no error and no
   * failing test.
   */
  summaryStyle?: Style
  summaryClassName?: string
}

const DETAILS: Style = { margin: '6px 0' }

const SUMMARY: Style = {
  cursor: 'pointer',
  color: 'var(--text-tertiary)',
  fontSize: '11px',
  userSelect: 'none',
}

/**
 * A `<details>` that can hold ANYTHING — a form, a code block, another component.
 *
 * The Java's `fold()` escaped its body into a `<pre>` and so could hold only text, which is why its
 * three richest call sites (the paste box, the `why` cell, the live stream panel) each hand-rolled
 * their own `<details>` instead of using it. That is the split: `Disclosure` takes children,
 * {@link TextFold} takes a string and is built on this.
 *
 * Controlled rather than left to the DOM, because the open bit is state a screen may want to
 * persist across a refresh — a live page that snaps every fold shut on a fifteen-second timer is a
 * page that fights whoever is reading it.
 */
export function Disclosure({
  id,
  summary,
  children,
  defaultOpen = true,
  summaryStyle,
  summaryClassName,
}: DisclosureProps) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <details
      id={id}
      open={open}
      onToggle={(event: ToggleEvent<HTMLDetailsElement>) => setOpen(event.currentTarget.open)}
      style={DETAILS}
    >
      <summary style={{ ...SUMMARY, ...summaryStyle }} className={summaryClassName}>
        {summary}
      </summary>
      {children}
    </details>
  )
}
