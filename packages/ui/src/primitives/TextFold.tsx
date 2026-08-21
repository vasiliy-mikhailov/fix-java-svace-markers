'use client'

import { useState } from 'react'
import type { Style } from './style'

export type TextFoldProps = {
  /** Stable, and carried by the thing being shown — an event id, `code:<agent>`. Never a position. */
  id: string
  /** The bare label. The size is computed here from `body`; never pass a count. */
  label: string
  body: string
  /** Start expanded. A reader who asked for everything open, from `?fold=`, gets everything open. */
  defaultOpen?: boolean
  /** Lines shown before clipping. Below this no control is drawn at all. */
  lines?: number
  /**
   * And a ceiling in CHARACTERS, because lines alone bound nothing here.
   *
   * A checker note and a task are written as paragraphs, one per line, unwrapped. Twelve lines of
   * that is sixteen thousand characters — so the clip meant to make a lane scannable drew the whole
   * task and the reader was back where they started.
   */
  chars?: number
}

const LABEL: Style = {
  color: 'var(--text-tertiary)',
  fontSize: '11px',
  userSelect: 'none',
}

const BODY: Style = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  background: 'var(--bg-card)',
  border: '1px solid var(--border-soft)',
  borderRadius: '6px',
  padding: '10px',
  margin: '4px 0 0',
  overflowX: 'auto',
  fontSize: '12px',
  lineHeight: 1.5,
  color: 'var(--text-secondary)',
}

const MORE: Style = {
  marginTop: '6px',
  padding: 0,
  border: 0,
  background: 'none',
  color: 'var(--accent-primary)',
  fontSize: '11px',
  cursor: 'pointer',
}

/**
 * A LONG BODY, CLIPPED — NEVER HIDDEN, AND NEVER DUMPED WHOLE.
 *
 * <p>This was a `<details>`, and it failed at both ends of the same page. Open, which was the
 * default, it put a 13,549-character task on the screen as one wall of text and every fold below it
 * was somewhere past the fold of the browser. Shut, which is what the standing prompt was, it showed
 * a label and a byte count — so the reader who asked "where is the system prompt?" was looking at a
 * page that had it, behind a word.
 *
 * <p>Neither is a choice a reader can make from what they can see. A count answers "how much" and
 * never "is this the thing I am looking for", and the whole reason to open one of these is to find
 * out which it is. So the first lines are always on the page: enough to recognise, and a control to
 * get the rest. Nothing here truncates anything permanently — the whole body travelled, and the
 * decision about how much to draw is the page's, made where it can be reversed.
 *
 * <p>AN EMPTY BODY RENDERS NOTHING AT ALL — not an empty box. That is load-bearing in two places and
 * both are absences a reader is meant to notice: a tool call that produced no result shows ONE body
 * instead of two, and an unjudged finding's "what the critic said" simply is not on the page. A fold
 * that opens onto nothing says the critic answered with silence, which is not what happened.
 */
export function TextFold({
  id,
  label,
  body,
  defaultOpen = false,
  lines = 12,
  chars = 1200,
}: TextFoldProps) {
  const [open, setOpen] = useState(defaultOpen)
  if (body.length === 0) {
    return null
  }
  const all = body.split('\n')
  // WHICHEVER BOUND BITES FIRST. Many short lines and one enormous one are both walls of text, and
  // a clip that only counts newlines stops the first while waving the second through.
  const byLine = all.slice(0, lines).join('\n')
  const clipped = byLine.length > chars ? `${byLine.slice(0, chars)}…` : byLine
  const long = clipped.length < body.length
  const shown = open || !long ? body : clipped
  return (
    <div id={id}>
      {label === '' ? null : (
        <div style={LABEL}>
          {label} ({body.length} chars)
        </div>
      )}
      <pre style={BODY}>{shown}</pre>
      {long ? (
        <button type="button" onClick={() => setOpen(!open)} style={MORE}>
          {open
            ? 'show less'
            : `show all ${all.length > lines ? `${all.length} lines` : `${body.length} chars`}`}
        </button>
      ) : null}
    </div>
  )
}
