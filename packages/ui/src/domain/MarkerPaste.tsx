'use client'

import { useState } from 'react'
import { Account, Disclosure, type Style } from '../primitives'

export type MarkerPasteProps = { onUse: (text: string) => void }

const AREA: Style = {
  display: 'block',
  width: '100%',
  minHeight: '9rem',
  background: 'var(--bg-panel)',
  color: 'var(--text-primary)',
  border: '1px solid var(--border-strong)',
  borderRadius: '6px',
  padding: '.5rem',
  font: 'inherit',
  fontSize: '12.5px',
  lineHeight: 1.5,
  resize: 'vertical',
}

const BUTTON: Style = {
  background: 'var(--accent-action)',
  color: 'var(--accent-on-action)',
  border: '1px solid var(--accent-action)',
  borderRadius: '6px',
  padding: '.4rem 1rem',
  margin: '.5rem 0 0',
  font: 'inherit',
  cursor: 'pointer',
}

const BUTTON_OFF: Style = {
  ...BUTTON,
  background: 'none',
  color: 'var(--text-tertiary)',
  border: '1px solid var(--border-strong)',
  cursor: 'not-allowed',
}

/**
 * The markers list, typed or pasted instead of uploaded.
 *
 * CLOSED BY DEFAULT, which is the one place a `Disclosure` here disagrees with its own default.
 * `Disclosure` opens unless the reader asked for everything shut, because a fold over a trace is
 * hiding the thing they came for. This is the alternative path to a file upload — the Java's own
 * paste box was a plain `<details>` with no `open` attribute — and an eleven-line textarea sitting
 * open under the uploader reads as the thing you are supposed to use.
 *
 * WHITESPACE-ONLY IS SWALLOWED, the same guard `AskBox` uses: "use these markers" with a box full of
 * newlines would replace the queue with nothing, and it would look exactly like a save that worked.
 */
export function MarkerPaste({ onUse }: MarkerPasteProps) {
  const [draft, setDraft] = useState('')
  const empty = draft.trim().length === 0
  return (
    <Disclosure id="marker-paste" defaultOpen={false} summary="paste a list instead">
      <Account quiet>One marker per line, as repo|file|line|checker. This replaces the queue.</Account>
      <textarea
        style={AREA}
        value={draft}
        onChange={event => setDraft(event.currentTarget.value)}
        spellCheck={false}
        aria-label="markers, one per line"
      />
      <button
        type="button"
        disabled={empty}
        style={empty ? BUTTON_OFF : BUTTON}
        onClick={() => {
          if (empty) {
            return
          }
          onUse(draft)
        }}
      >
        use these markers
      </button>
    </Disclosure>
  )
}
