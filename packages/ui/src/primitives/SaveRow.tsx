'use client'

import { useState } from 'react'
import type { Style } from './style'

export type SaveRowProps = {
  saveLabel?: string
  onSave: () => void
  /**
   * The other intent: "put the environment's back", "forget it", "remove it". Its own handler, and
   * the caller sends its own request — see the note on the component.
   */
  destructive?: { label: string; onConfirm: () => void }
}

const ROW: Style = { display: 'flex', gap: '.5rem', alignItems: 'center', margin: '.7rem 0' }

const SAVE: Style = {
  background: 'var(--accent-action)',
  color: 'var(--accent-on-action)',
  border: '1px solid var(--accent-action)',
  borderRadius: '6px',
  padding: '.4rem 1rem',
  font: 'inherit',
  cursor: 'pointer',
}

const PLAIN: Style = {
  background: 'none',
  border: '1px solid var(--border-strong)',
  color: 'var(--text-tertiary)',
  borderRadius: '6px',
  padding: '.4rem 1rem',
  font: 'inherit',
  cursor: 'pointer',
}

const ARMED: Style = { ...PLAIN, color: 'var(--state-infra)', borderColor: 'var(--state-infra)' }

const CANCEL: Style = { ...PLAIN, border: 'none', color: 'var(--text-tertiary)' }

/**
 * Save, and — where there is one — the destructive twin, as TWO INTENTS WITH TWO HANDLERS.
 *
 * THE FOOTGUN THIS EXISTS TO CLOSE. In the Java both actions were the same form, told apart only by
 * which button submitted it: `<button name=revert value=1>`, `<button name=forget value=1>`
 * (1435-1440, 1300-1302). That works for a browser and not for a React client, which serialises
 * component state and would post `forget=1` on EVERY save — silently deleting the API key it was
 * asked to replace. Two intents, two handlers, and the caller issues two different requests.
 *
 * The destructive one arms before it fires. `onConfirm` is the prop's name because the confirming
 * is this component's job: one click that drops the environment's credential with no second step is
 * the same footgun wearing a different hat.
 */
export function SaveRow({ saveLabel = 'save', onSave, destructive }: SaveRowProps) {
  const [armed, setArmed] = useState(false)
  return (
    <div style={ROW}>
      <button type="button" onClick={onSave} style={SAVE}>
        {saveLabel}
      </button>
      {destructive === undefined ? null : armed ? (
        <>
          <button
            type="button"
            onClick={() => {
              setArmed(false)
              destructive.onConfirm()
            }}
            style={ARMED}
          >
            {`really ${destructive.label}?`}
          </button>
          <button type="button" onClick={() => setArmed(false)} style={CANCEL}>
            cancel
          </button>
        </>
      ) : (
        <button type="button" onClick={() => setArmed(true)} style={PLAIN}>
          {destructive.label}
        </button>
      )}
    </div>
  )
}
