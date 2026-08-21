'use client'

import { ACCOUNT_QUIET } from 'ratchet-ui/components'

import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import type { Style } from './style'

export type SecretFieldProps = {
  name: string
  label: string
  value: string
  onChange: (v: string) => void
  help?: ReactNode
}

const FIELD: Style = { display: 'block', margin: '.7rem 0' }

const LABEL: Style = {
  display: 'block',
  fontSize: '.78rem',
  color: 'var(--text-tertiary)',
  marginBottom: '.2rem',
}

const ROW: Style = { display: 'flex', gap: '.3rem', alignItems: 'center' }

const INPUT: Style = {
  background: 'var(--bg-panel)',
  color: 'var(--text-primary)',
  border: '1px solid var(--border-strong)',
  borderRadius: '5px',
  padding: '.35rem .5rem',
  width: 'min(34rem, 100%)',
  font: 'inherit',
}

const ICON: Style = {
  background: 'var(--bg-panel)',
  border: '1px solid var(--border-strong)',
  borderRadius: '5px',
  padding: '.3rem .5rem',
  cursor: 'pointer',
  fontSize: '.95rem',
  lineHeight: 1,
  color: 'var(--text-secondary)',
}

const ICON_OFF: Style = { ...ICON, cursor: 'not-allowed', opacity: 0.45 }

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

/**
 * A masked value with the two buttons that make a masked value usable.
 *
 * WHY THIS COULD NOT ALREADY BE SHARED: the Java wrote this markup twice, once for the API key and
 * once for the git token, and both reveal/copy handlers were inline `onclick` strings reaching for
 * a hard-coded element — `getElementById('apikey')` and `getElementById('gittok')` (1398-1410,
 * 1292-1298). Two of them on one page would have fought over the id. Here the id comes from
 * `useId` and the handlers hold a ref, so the component is the component and not a template.
 *
 * WHAT BLANK MEANS IS NOT THIS COMPONENT'S TO DECIDE, and the two screens genuinely disagree: on
 * the model tab a blank key is deliberately LEFT ALONE, because a browser that clears the field
 * must not be able to silently unset the key and leave every agent talking to an endpoint that
 * refuses them (prose at 1414-1420); on the subject tab a blank token is REFUSED (1146-1147).
 * Blank-policy is the form's contract. Say it per screen, in the `help`, or a shared field teaches
 * users a rule that is only true on one of the two pages.
 *
 * THE SECRET IS IN THE PAGE, and that is the price of reveal-and-copy — it is why the dashboard
 * sits behind basic auth (1398-1400 owns that trade). Moving it into JSON keeps the exposure and
 * adds a URL that hands the secret over on its own. If a screen does not need reveal and copy, drop
 * the value from its payload and the secret stops leaving the box.
 */
export function SecretField({ name, label, value, onChange, help }: SecretFieldProps) {
  const id = useId()
  const helpId = `${id}-help`
  const input = useRef<HTMLInputElement>(null)
  const [revealed, setRevealed] = useState(false)
  const [copied, setCopied] = useState(false)
  const [canCopy, setCanCopy] = useState(false)

  // `navigator.clipboard` exists only in a secure context, so copy is silently dead over plain
  // http — and a button that does nothing when clicked is worse than no button. Read after mount:
  // this page is statically exported, so there is no window at the time the HTML is written, and
  // the honest first render is the disabled one.
  useEffect(() => {
    setCanCopy(typeof navigator !== 'undefined' && navigator.clipboard !== undefined)
  }, [])

  useEffect(() => {
    if (!copied) {
      return
    }
    const timer = setTimeout(() => setCopied(false), 1200)
    return () => clearTimeout(timer)
  }, [copied])

  function copy() {
    const field = input.current
    if (field === null || !canCopy) {
      return
    }
    void navigator.clipboard.writeText(field.value).then(() => setCopied(true))
  }

  return (
    <div style={FIELD}>
      <label htmlFor={id} style={LABEL}>
        {label}
      </label>
      <span style={ROW}>
        <input
          id={id}
          ref={input}
          name={name}
          type={revealed ? 'text' : 'password'}
          autoComplete="off"
          value={value}
          onChange={event => onChange(event.currentTarget.value)}
          style={INPUT}
          aria-describedby={help === undefined ? undefined : helpId}
        />
        <button
          type="button"
          onClick={() => setRevealed(was => !was)}
          style={ICON}
          title={revealed ? 'hide' : 'show'}
          aria-pressed={revealed}
        >
          {revealed ? '🙈' : '👁'}
        </button>
        <button
          type="button"
          onClick={copy}
          disabled={!canCopy}
          style={canCopy ? ICON : ICON_OFF}
          title={canCopy ? 'copy' : 'copying needs https'}
        >
          {copied ? '✓' : '📋'}
        </button>
      </span>
      {help === undefined ? null : (
        <p id={helpId} style={HELP}>
          {help}
        </p>
      )}
    </div>
  )
}
