'use client'

import { useId, useState, type FormEvent } from 'react'
import type { Style } from '../primitives'

export type RateAnswerProps = {
  /**
   * WHAT IS BEING RATED, said once and unambiguously.
   *
   * An answer is identified by the event's stable id. A finding has none — `overwatch.jsonl` is
   * append-only and a finding's position in the file IS its identity, which is also why `Finding`
   * carries `position` rather than an id and why the findings page anchors at `#f<index>` (748).
   */
  target: { kind: 'answer'; eventId: string } | { kind: 'finding'; index: number }
  /** Where the reader is sent back to when the browser does the posting. */
  back: string
  /** Present when the screen would rather not be navigated away from; see the submit handler. */
  onSaved?: () => void
}

const ACTION = '/feedback'

const FORM: Style = {
  marginTop: '12px',
  borderTop: '1px dashed var(--border-soft)',
  paddingTop: '10px',
}

const LABEL: Style = {
  display: 'block',
  color: 'var(--text-secondary)',
  fontSize: '12px',
  marginBottom: '5px',
}

const NOTE: Style = {
  width: '100%',
  background: 'var(--bg-canvas)',
  color: 'var(--text-primary)',
  border: '1px solid var(--border-strong)',
  borderRadius: '6px',
  padding: '8px',
  font: 'inherit',
  fontSize: '12px',
  resize: 'vertical',
}

const BUTTON: Style = {
  background: 'var(--bg-elevated)',
  color: 'var(--text-primary)',
  border: '1px solid var(--border-strong)',
  borderRadius: '6px',
  padding: '5px 12px',
  font: 'inherit',
  fontSize: '11px',
  marginTop: '6px',
  cursor: 'pointer',
}

const SAID: Style = { marginLeft: '8px', fontSize: '11px', color: 'var(--text-tertiary)' }
const LOST: Style = { marginLeft: '8px', fontSize: '11px', color: 'var(--state-infra)' }

/** The anchor the findings page already puts on every finding (748), which its `back` never used —
 *  so saving a rating returned the reader to the top of the page they were three screens down. */
function anchored(back: string, target: RateAnswerProps['target']): string {
  if (target.kind !== 'finding' || back.includes('#')) {
    return back
  }
  return `${back}#f${target.index}`
}

/**
 * THE CONTROL THAT TURNS A BAD ANSWER INTO A TRAINING EXAMPLE.
 *
 * Six props down to three, and the shorter list is the more honest one. The Java sent `marker`,
 * `agent`, `event`, `back`, `prompt` and `reply` as hidden fields so the row it wrote was a complete
 * example without a second read of the trace (2254-2257). That property is worth keeping and is kept
 * — the server does the lookup now. Three things stop being possible the moment the browser stops
 * echoing them back:
 *
 *  - **#3, the numbering.** `event` was the answer's POSITION in whichever list was on screen: across
 *    all markers on `/trace`, within one marker on `/marker?a=trace`, and a third numbering on the
 *    agent tab (`mine.indexOf(last)`, 1996, over an UNSORTED list while `events()` sorts by `at`).
 *    The same physical answer posted a different number from each of the three, and `feedback.jsonl`
 *    holds all three kinds with nothing to tell them apart. The field is called `eventId` and not
 *    `event` on purpose: a reader of the corpus can then tell a row that points at something from a
 *    row that points at a position in a list that no longer exists.
 *  - **#4, the truncation.** `hidden()` wrote `value='…'` in single quotes and `esc()` (2765-2767)
 *    replaced only `&`, `<` and `>` — never an apostrophe. Every prompt or reply containing one was
 *    cut at the first `'` and the remainder parsed as stray attributes. The corpus already holds
 *    mutilated examples and they are not recoverable from the rows themselves; assume the ones with
 *    a truncated `prompt` or `reply` are suspect. React cannot reproduce it — nothing here
 *    concatenates an attribute value — and with the text no longer travelling, there is nothing left
 *    to mutilate.
 *  - **the supervisor's misfiling.** `reported()` posted `marker="overwatch"`, `agent="overwatch"`
 *    (757-758), filing every finding's feedback against a marker that does not exist and crediting
 *    the critic's work to the supervisor. `target` says `finding` and the server takes it from there.
 *
 * IT IS A REAL FORM. `method=post` and an action, so it works with no JavaScript at all and the 303
 * back to `back` still lands the reader where they were — rating six answers should be six clicks
 * and no navigation to speak of. When a screen passes `onSaved` the same form is posted in the
 * background instead, because the alternative on a page that is routinely eight megabytes is a full
 * reload that shuts every fold the reader opened. Both paths post the SAME `FormData` from the SAME
 * form: two payload builders would drift, and the one that drifts is always the one nobody looks at.
 */
export function RateAnswer({ target, back, onSaved }: RateAnswerProps) {
  const noteId = useId()
  const [state, setState] = useState<'idle' | 'saving' | 'saved' | 'lost'>('idle')

  async function submit(submitted: FormEvent<HTMLFormElement>) {
    if (onSaved === undefined) {
      // No handler means no promise to keep the reader in place: let the browser post and follow
      // the redirect, exactly as it does with scripting switched off.
      return
    }
    submitted.preventDefault()
    const form = submitted.currentTarget
    const body = new URLSearchParams()
    // `forEach` rather than `for…of`: the tsconfig's lib is ES2022 + DOM without `dom.iterable`, so
    // iterating a `FormData` does not typecheck here. Urlencoded and not multipart because that is
    // what the form posts on its own, and the two paths have to arrive at the server alike.
    new FormData(form).forEach((value, name) => {
      if (typeof value === 'string') {
        body.append(name, value)
      }
    })
    setState('saving')
    try {
      const answer = await fetch(form.action, { method: 'POST', body })
      if (!answer.ok) {
        setState('lost')
        return
      }
      setState('saved')
      // Cleared only once the server has said yes. A note that vanishes on a failed post is a
      // training example the reader wrote once and will not write again.
      form.reset()
      onSaved()
    } catch {
      // Offline, or the server went away mid-post. Either way the note is still in the box.
      setState('lost')
    }
  }

  const about = target.kind === 'answer' ? 'this agent' : 'the supervisor'
  return (
    <form
      // Remounts when the target changes, so the uncontrolled fields below cannot describe one
      // answer while the reader is looking at another — and a half-written note about a different
      // answer goes with it, which is right.
      key={target.kind === 'answer' ? target.eventId : `f${target.index}`}
      method="post"
      action={ACTION}
      onSubmit={submit}
      style={FORM}
    >
      <input type="hidden" name="about" defaultValue={target.kind} />
      {target.kind === 'answer' ? (
        <input type="hidden" name="eventId" defaultValue={target.eventId} />
      ) : (
        <input type="hidden" name="findingIndex" defaultValue={String(target.index)} />
      )}
      <input type="hidden" name="back" defaultValue={anchored(back, target)} />
      <label htmlFor={noteId} style={LABEL}>
        {`Tell ${about} what it should have done`}
      </label>
      <textarea
        id={noteId}
        name="note"
        rows={4}
        style={NOTE}
        disabled={state === 'saving'}
        placeholder={
          target.kind === 'answer'
            ? 'Write it as you would say it to whoever wrote this answer. This text, the prompt above and the reply become one training example.'
            : 'Write it as you would say it to whoever reported this. This text and the finding above become one training example.'
        }
      />
      <div>
        <button type="submit" style={BUTTON} disabled={state === 'saving'}>
          {state === 'saving' ? 'saving' : 'save'}
        </button>
        <span role="status" style={state === 'lost' ? LOST : SAID}>
          {state === 'saved' ? 'saved' : state === 'lost' ? 'not saved — the note is still here' : ''}
        </span>
      </div>
    </form>
  )
}
