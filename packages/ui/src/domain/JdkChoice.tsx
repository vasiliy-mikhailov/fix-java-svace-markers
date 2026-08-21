'use client'

import { useState } from 'react'
import { Account, SaveRow, type Style } from '../primitives'
import { SettingCard } from 'ratchet-ui/components'

export type JdkChoiceProps = {
  /** What the record holds. It is not guaranteed to be one of `available` — see below. */
  chosen: string
  /**
   * FROM THE SERVER, ALWAYS. `Subject.JDKS` is 25, 21, 17, 11 and 8 — a property of the image this
   * dashboard is running in, not of the client. A list hardcoded here would go stale the first time
   * the image drops one and would offer a JDK that cannot be selected.
   */
  available: string[]
  /** What the subject builds with when the choice is unusable. */
  fallback: string
  onSave: (v: string) => void
}

const SELECT: Style = {
  background: 'var(--bg-panel)',
  color: 'var(--text-primary)',
  border: '1px solid var(--border-strong)',
  borderRadius: '5px',
  padding: '.35rem .5rem',
  font: 'inherit',
}

const WARN: Style = { margin: '.4rem 0 0', fontSize: '.78rem', color: 'var(--state-needs-review)' }

/**
 * Which JDK the subject is built with.
 *
 * SAVING AN UNKNOWN VALUE FAILS SILENTLY. `Subject.saveJdk` returns without writing when the value
 * is not in `JDKS`, and `subjectPosted` (1158-1160) noticed only by re-reading the file afterwards
 * and comparing — the page's one defence against a save that did nothing was to check. This
 * validates against `available` before sending and says so when the record already holds something
 * the image cannot run, which is a state the Java could reach and never mentioned.
 */
export function JdkChoice({ chosen, available, fallback, onSave }: JdkChoiceProps) {
  const [draft, setDraft] = useState(chosen)
  const known = available.includes(chosen)
  return (
    <SettingCard title="the JDK" provenance={chosen.length === 0 ? 'not set' : chosen} changed={chosen !== fallback}>
      <Account quiet>What the subject is compiled and tested with. Only what this image carries can be chosen.</Account>
      <select
        style={SELECT}
        value={draft}
        aria-label="the JDK"
        onChange={event => setDraft(event.currentTarget.value)}
      >
        {/* The record's value is offered even when the image cannot run it, disabled, so the select
            shows what is actually set instead of quietly snapping to the first option and looking
            like somebody chose it. */}
        {known ? null : (
          <option value={chosen} disabled>
            {`${chosen} — not in this image`}
          </option>
        )}
        {available.map(jdk => (
          <option key={jdk} value={jdk}>
            {jdk}
          </option>
        ))}
      </select>
      {known ? null : (
        <p style={WARN}>
          {`the record holds ${chosen}, which this image cannot run, so the subject falls back to ${fallback}`}
        </p>
      )}
      <SaveRow
        onSave={() => {
          // Unreachable through the select, which offers nothing else. Kept because the failure it
          // guards against is a SILENT one: an unknown value is not written and not complained
          // about, so a component that sent one would show a save that never happened.
          if (!available.includes(draft)) {
            return
          }
          onSave(draft)
        }}
      />
    </SettingCard>
  )
}
