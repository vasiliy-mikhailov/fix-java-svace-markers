'use client'

import { useState } from 'react'
import { SaveRow, type Style } from '../primitives'
import { ACCOUNT_QUIET, SettingCard } from 'ratchet-ui/components'

export type MirrorRulesProps = {
  /** `<from> <to>` per line, as the record holds it. Blank when clones go where the markers say. */
  rules: string
  onSave: (rules: string) => void
}

const HELP: Style = {
  ...ACCOUNT_QUIET,
  // ABOVE the textarea it explains, not under a control, which is the one declaration it keeps.
  margin: '0 0 8px',
}

const AREA: Style = {
  display: 'block',
  width: '100%',
  minHeight: '5rem',
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

/**
 * WHERE CLONES ACTUALLY GO, when the network cannot reach the host the markers name.
 *
 * <p>Every marker's first field is the repository its code lives in, and on an air-gapped machine
 * none of them is reachable. The two obvious ways out are both wrong: rewriting three hundred markers
 * throws away the canonical identifier a settlement is read against months later, and running
 * `git config --global` by hand inside the container reverts on the next deploy, silently, because a
 * deploy recreates the container. The clone that follows then fails with a message about a missing
 * `pom.xml`, which sends the reader to the build system.
 *
 * <p>So it is stored with the run and applied before anything clones, and the markers go on naming
 * the true repository.
 *
 * <p>UNLIKE THE CREDENTIAL BESIDE IT, THIS IS SENT BACK AND SHOWN. A mirror is not a secret — it is
 * infrastructure — and a field that started blank while a rule was in force would invite somebody to
 * retype it and end up with two rules pointing different ways.
 */
export function MirrorRules({ rules, onSave }: MirrorRulesProps) {
  const [draft, setDraft] = useState(rules)
  const lines = draft.split('\n').filter(l => l.trim().length > 0).length
  return (
    <SettingCard
      title="the git mirror"
      provenance={rules.trim().length === 0 ? 'not set' : `${lines} rule(s)`}
      changed={rules.trim().length > 0}
    >
      <p style={HELP}>
        One <code>&lt;from&gt; &lt;to&gt;</code> pair per line. Anything cloned from a URL beginning
        with <code>from</code> is fetched from <code>to</code> instead, through git&rsquo;s own{' '}
        <code>insteadOf</code> — so the markers go on naming the repository the finding belongs to,
        which is the identifier somebody reads a settlement against months later. Blank fetches from
        wherever each marker says. If the mirror needs a login, put the token in the credential
        above: it goes into git&rsquo;s store and never into a URL.
      </p>
      <textarea
        style={AREA}
        spellCheck={false}
        aria-label="git mirror rules"
        placeholder="https://github.com/    https://gitlab.internal/mirror/"
        value={draft}
        onChange={e => setDraft(e.target.value)}
      />
      <SaveRow onSave={() => onSave(draft)} />
    </SettingCard>
  )
}
