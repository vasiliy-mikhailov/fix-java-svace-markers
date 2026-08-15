'use client'

import { useState } from 'react'
import type { Style } from '../primitives'

export type PromptPreambleProps = {
  /** The paragraph the code prepends. Not editable here — see below. */
  text: string
  /** How many of the agents on this page get it. */
  applies: number
  /** How many there are in total, so the reader can see who does NOT get it. */
  total: number
}

const BOX: Style = {
  border: '1px solid var(--border-strong)',
  borderLeft: '3px solid var(--text-tertiary)',
  borderRadius: '6px',
  background: 'var(--bg-card)',
  padding: '10px 12px',
  margin: '12px 0 4px',
}

const HEAD: Style = {
  display: 'flex',
  gap: '10px',
  alignItems: 'baseline',
  flexWrap: 'wrap',
  cursor: 'pointer',
  userSelect: 'none',
}

const NAME: Style = { fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }

const STATE: Style = {
  fontSize: '10.5px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  color: 'var(--text-tertiary)',
}

const WHY: Style = {
  margin: '6px 0 0',
  fontSize: '11.5px',
  lineHeight: 1.6,
  color: 'var(--text-tertiary)',
  maxWidth: '52em',
}

const TEXT: Style = {
  margin: '8px 0 0',
  padding: '8px 10px',
  background: 'var(--bg-panel)',
  border: '1px solid var(--border-strong)',
  borderRadius: '6px',
  fontSize: '12.5px',
  lineHeight: 1.55,
  color: 'var(--text-secondary)',
  whiteSpace: 'pre-wrap',
  maxWidth: '60em',
}

/**
 * WHAT EVERY AGENT IS TOLD BEFORE ITS OWN PROMPT, SHOWN WHERE THE PROMPTS ARE.
 *
 * <p>This page's own rule is that an edit REPLACES a built-in entirely, "because a prompt half from
 * the code and half from a box is a prompt nobody can read in one place". Prepending a paragraph in
 * the Java and not showing it here would break precisely that rule — every box below would be half
 * the prompt, and nothing would say so.
 *
 * <p>It is not editable, and that is the point rather than an omission. Fifteen copies of a
 * paragraph drift, which is the fault this codebase has hit three times in a week; and an edit
 * replaces a prompt whole, so a framing living inside one could be deleted by somebody improving the
 * sentence beneath it without ever noticing it had gone.
 *
 * <p>Collapsed by default. It is the same text for every agent, and a reader who has come here to
 * change one prompt should meet their prompts, not a paragraph they have already read.
 */
export function PromptPreamble({ text, applies, total }: PromptPreambleProps) {
  const [open, setOpen] = useState(false)
  return (
    <section style={BOX}>
      <header
        style={HEAD}
        onClick={() => setOpen(!open)}
        role="button"
        tabIndex={0}
        aria-expanded={open}
        onKeyDown={e => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            setOpen(!open)
          }
        }}
      >
        <span style={NAME}>what every one of them is told first</span>
        <span style={STATE}>
          {open ? 'hide' : 'show'} · {applies} of {total} · not editable
        </span>
      </header>
      <p style={WHY}>
        Prepended by the code to the {applies} agents that judge the SUBJECT&rsquo;s code — the chain,
        and the three that write the summary a person reads. The {total - applies} that watch this
        pipeline rather than the code under test do not get it: telling a watcher its answer ships to
        production is simply false, and an agent given a stake it does not have reasons about the
        wrong thing. Editing a prompt below replaces that prompt and not this.
      </p>
      {open ? <p style={TEXT}>{text}</p> : null}
    </section>
  )
}
