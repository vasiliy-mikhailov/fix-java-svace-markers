'use client'

import { ACCOUNT_QUIET } from 'ratchet-ui/components'

import { useId, type ReactNode } from 'react'
import type { Style } from './style'

export type LabeledFieldProps = {
  name: string
  label: string
  value: string
  onChange: (v: string) => void
  /**
   * WHY THIS SETTING EXISTS, as a visible paragraph — never a placeholder and never a `title`.
   *
   * The Java's `record Field(name, label, type, why)` (1350) is this prop type already, minus the
   * value, and `why` was always rendered. It has to stay rendered: `patience_minutes` and
   * `ceiling_minutes` are two fields with two sentences because they are two different failures —
   * a wire carrying nothing, and an answer that arrives and never ends. Collapsing them into one
   * "timeout" killed eighty-six proves that were fine (1372-1377).
   */
  help?: ReactNode
  /**
   * Text by default, including for the numeric ones.
   *
   * That is not an oversight in the Java: clamping happens on READ, in `Tuning` (65-88), not on
   * input. Switch a field to `number` and you have to mirror Tuning's real bounds here AND re-seed
   * the form from the POST response — a saved 5 comes back as 2, and a form still showing 5 tells
   * the reader a lie the server never told.
   */
  type?: 'text' | 'number'
}

const FIELD: Style = { display: 'block', margin: '.7rem 0' }

const LABEL: Style = {
  display: 'block',
  fontSize: '.78rem',
  color: 'var(--text-tertiary)',
  marginBottom: '.2rem',
}

const INPUT: Style = {
  background: 'var(--bg-panel)',
  color: 'var(--text-primary)',
  border: '1px solid var(--border-strong)',
  borderRadius: '5px',
  padding: '.35rem .5rem',
  width: 'min(34rem, 100%)',
  font: 'inherit',
}

/**
 * THE FOOTNOTE UNDER A FIELD, AND IT IS THE SAME FOOTNOTE `Account` DRAWS.
 *
 * It was a private copy of `Account`'s quiet metrics, written before there was an `Account` to share
 * — and taking `ratchet-ui`'s version repainted every quiet paragraph on the settings page EXCEPT
 * this one, so a page that had two footnote styles briefly had three. `ACCOUNT_QUIET` is exported
 * for exactly this: the call site that sits directly under its own control and wants to be closer
 * to it than a paragraph between two cards would.
 */
const HELP: Style = { ...ACCOUNT_QUIET, display: 'block', margin: '.2rem 0 0' }

/** One setting: what it is called, what it is, and why it is a setting at all. */
export function LabeledField({ name, label, value, onChange, help, type = 'text' }: LabeledFieldProps) {
  const id = useId()
  const helpId = `${id}-help`
  return (
    <div style={FIELD}>
      <label htmlFor={id} style={LABEL}>
        {label}
      </label>
      <input
        id={id}
        name={name}
        type={type}
        value={value}
        onChange={event => onChange(event.currentTarget.value)}
        style={INPUT}
        // The help is DESCRIBED BY, not part of the label: a screen reader announcing four
        // sentences of rationale every time focus lands on the field is a field nobody can fill in.
        aria-describedby={help === undefined ? undefined : helpId}
      />
      {help === undefined ? null : (
        <p id={helpId} style={HELP}>
          {help}
        </p>
      )}
    </div>
  )
}
