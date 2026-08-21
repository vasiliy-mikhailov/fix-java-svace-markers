'use client'

import { useState } from 'react'
import type { AgentName } from '@fsm/types'
import { SaveRow, TextFold, type Style } from '../primitives'
import { SettingCard } from 'ratchet-ui/components'

export type AgentPromptEditorProps = {
  agent: AgentName
  /** The prompt compiled into the image. */
  builtIn: string
  /** What `prompts/<agent>.txt` holds. Blank means nobody has saved one. */
  saved: string
  /**
   * WHETHER THE SAVED PROMPT ACTUALLY DIFFERS, normalised — `Prompts.same()` (1480, 1508).
   *
   * The row's word and the row's accent answer two different questions and the Java conflated them.
   * "Edited" meant only `saved.isBlank() == false` (1415, 1434): paste the built-in back verbatim,
   * or save after changing nothing, and the row said EDITED forever — while `promptsFor()` two
   * hundred lines away answered the same question with `Prompts.same()` normalisation and said the
   * opposite. Both facts are sent now, so the row can say "edited" and "same as the code's" at once,
   * which is the true thing.
   */
  differs: boolean
  onSave: (prompt: string) => void
  onRevert: () => void
}

const AREA: Style = {
  display: 'block',
  width: '100%',
  minHeight: '13rem',
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
 * The prompt one agent is running, and the two things you can do to it.
 *
 * THE TEXTAREA SHOWS THE EFFECTIVE PROMPT — the saved one if there is one, otherwise the built-in.
 * An edit has to start from what the agent is really running; starting from the built-in while a
 * saved override is in force means every edit silently discards the override, and the reader has no
 * way to see that it did.
 *
 * IT DOES NOT RE-SEED FROM PROPS. A settings page that refreshes its payload while somebody is
 * halfway through rewriting a prompt must not eat the paragraph they just typed. When the caller
 * wants the box reset — after a revert, say — it remounts with a `key`, which is the explicit
 * version of the same thing.
 *
 * `revert` goes through `SaveRow`'s destructive slot: it is a separate request with its own handler,
 * because the Java told the two apart by which button submitted the form (#15) and a React client
 * that serialises its state would have posted `revert=1` on every save.
 */
export function AgentPromptEditor({ agent, builtIn, saved, differs, onSave, onRevert }: AgentPromptEditorProps) {
  const effective = saved.trim().length === 0 ? builtIn : saved
  const [draft, setDraft] = useState(effective)
  const state =
    saved.trim().length === 0 ? "the code's own" : differs ? 'edited' : "edited, same as the code's"
  return (
    <SettingCard
      title={agent}
      provenance={state}
      // The ACCENT follows `differs` — whether this agent is running something other than what the
      // code says — while the WORD follows `saved`, which is what the file holds. They are two
      // facts and a row that has been saved back to identical text is not an override.
      changed={differs}
      id={agent}
    >
      <textarea
        style={AREA}
        value={draft}
        onChange={event => setDraft(event.currentTarget.value)}
        spellCheck={false}
        aria-label={`the prompt for ${agent}`}
      />
      <SaveRow onSave={() => onSave(draft)} destructive={{ label: 'revert', onConfirm: onRevert }} />
      {/* Only worth showing when there IS an override: otherwise the fold repeats the textarea.
          Keyed `code:<agent>` — a stable name for the thing being disclosed, not its position (#10).
          On this page the fold exists only for an overridden agent, so reverting one renumbered the
          rest and the wrong agent sprang open on the next render. */}
      {saved.trim().length === 0 ? null : (
        <TextFold id={`code:${agent}`} label="the prompt in the code" body={builtIn} defaultOpen={false} />
      )}
    </SettingCard>
  )
}
