'use client'

import { useState, type KeyboardEvent } from 'react'
import { Account, type Style } from '../primitives'

export type AskBoxProps = {
  /**
   * WHETHER THE SUPERVISOR IS ANSWERING SOMEBODY — anybody.
   *
   * `Chat.answering` is a process-wide `AtomicBoolean` (Chat.java 45), so this is not a fact about
   * this browser: every open tab is disabled by a question asked in another one. That is the truth
   * about the resource and it is worth saying out loud, because a box that is disabled for no
   * visible reason reads as a broken page.
   */
  answering: boolean
  onAsk: (q: string) => void
}

const AREA: Style = {
  display: 'block',
  width: '100%',
  minHeight: '4.5rem',
  background: 'var(--bg-panel)',
  color: 'var(--text-primary)',
  border: '1px solid var(--border-strong)',
  borderRadius: '6px',
  padding: '.5rem',
  font: 'inherit',
  fontSize: '13px',
  lineHeight: 1.5,
  resize: 'vertical',
}

const AREA_OFF: Style = { ...AREA, color: 'var(--text-tertiary)', cursor: 'not-allowed' }

const ROW: Style = { display: 'flex', gap: '.5rem', alignItems: 'center', margin: '.5rem 0 0' }

const BUTTON: Style = {
  background: 'var(--accent-action)',
  color: 'var(--accent-on-action)',
  border: '1px solid var(--accent-action)',
  borderRadius: '6px',
  padding: '.4rem 1rem',
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
 * Where you ask the supervisor something.
 *
 * ENTER SENDS, SHIFT+ENTER IS A NEWLINE (1002-1011), GUARDED TWICE: nothing happens while the box is
 * disabled, and a question that is only whitespace is swallowed. Both guards are in `ask()` below
 * rather than on the keypress, because the button is the same intent and a guard on one path is a
 * guard that the other path does not have.
 *
 * THE DISABLED STATE IS THE CONFIRMATION. There is no separate "sent" indicator anywhere in this
 * design, so a component that cleared the box and re-enabled itself optimistically would be
 * asserting the question was taken — and `Chat.ask()` can answer "still answering the last one", or
 * "could not write the question down", which means it was not taken and is not in the record at all.
 * So: nothing is cleared here. The record is what says the question landed; the caller remounts this
 * with a `key` once the turn appears in the transcript, and the draft survives a failure.
 *
 * `answering` IS THE ONLY "still thinking" ON THIS PAGE. The `unanswered` tail below a transcript is
 * a different fact and means the opposite of what it looks like (983-987, Chat.java 105-108): the
 * dashboard restarted mid-reply and NO ANSWER IS COMING. Whoever renders it must not give it a
 * spinner.
 */
export function AskBox({ answering, onAsk }: AskBoxProps) {
  const [draft, setDraft] = useState('')
  const question = draft.trim()
  function ask() {
    if (answering || question.length === 0) {
      return
    }
    onAsk(question)
  }
  function keyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // `isComposing` is the third guard and the Java had no need of it: Enter while an input method
    // is mid-word commits the word, it does not send the message. Sending there would cut every
    // question a Cyrillic or CJK keyboard writes off at its first space.
    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) {
      return
    }
    event.preventDefault()
    ask()
  }
  return (
    <div>
      <textarea
        style={answering ? AREA_OFF : AREA}
        value={draft}
        disabled={answering}
        onChange={event => setDraft(event.currentTarget.value)}
        onKeyDown={keyDown}
        aria-label="ask the supervisor"
        placeholder="enter sends, shift+enter is a newline"
      />
      <div style={ROW}>
        <button
          type="button"
          disabled={answering || question.length === 0}
          style={answering || question.length === 0 ? BUTTON_OFF : BUTTON}
          onClick={ask}
        >
          ask
        </button>
      </div>
      {answering ? (
        <Account quiet>
          a question is being answered — it may be one somebody asked in another tab, because the
          supervisor answers one at a time for the whole dashboard
        </Account>
      ) : null}
    </div>
  )
}
