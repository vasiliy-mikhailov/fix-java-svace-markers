'use client'

import { useId } from 'react'
import { Account, type Style } from '../primitives'
import type { KeySource } from './KeyStatus'

export type ForgetKeyChoiceProps = {
  keySource: KeySource
  checked: boolean
  onChange: (v: boolean) => void
}

const ROW: Style = { display: 'flex', gap: '.4rem', alignItems: 'baseline', margin: '.5rem 0 0' }

const LABEL: Style = { fontSize: '.8rem', color: 'var(--text-secondary)' }

const LABEL_OFF: Style = { ...LABEL, color: 'var(--text-tertiary)' }

/**
 * "Forget the key saved here and go back to the environment's."
 *
 * IT TRAVELS WITH THE SAVE, WHICH IS WHY IT MUST BE CONTROLLED. In the Java this checkbox sat
 * outside the `<form>` (1359) along with the key field itself, so `forget_key` was never submitted
 * and the branch that handles it was dead code (#5). Here `checked` is the screen's state and goes
 * into the same request as the key — the arrangement that failed is not expressible.
 *
 * IT IS NOT A DESTRUCTIVE BUTTON, and deliberately not `SaveRow`'s destructive slot: this is a
 * choice you make BEFORE saving, not an action of its own, and arming it would be a confirmation
 * for a checkbox nobody has committed to yet. The confirmation is the save.
 *
 * WITH NO KEY OF OUR OWN THERE IS NOTHING TO FORGET, so it is disabled and says why rather than
 * being hidden: a control that vanishes leaves the reader wondering whether they imagined it, and
 * the reason it is inert is the useful half of the sentence.
 */
export function ForgetKeyChoice({ keySource, checked, onChange }: ForgetKeyChoiceProps) {
  const id = useId()
  const nothingSaved = keySource === 'the environment'
  return (
    <div>
      <div style={ROW}>
        <input
          id={id}
          type="checkbox"
          name="forget_key"
          checked={checked}
          disabled={nothingSaved}
          onChange={event => onChange(event.currentTarget.checked)}
        />
        <label htmlFor={id} style={nothingSaved ? LABEL_OFF : LABEL}>
          forget the key saved on this page
        </label>
      </div>
      {nothingSaved ? (
        <Account quiet>
          nothing is saved here — the agents are using the environment&apos;s key, and this page
          cannot unset that
        </Account>
      ) : null}
    </div>
  )
}
