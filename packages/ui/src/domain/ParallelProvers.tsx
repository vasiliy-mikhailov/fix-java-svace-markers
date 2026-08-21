'use client'

import { useState } from 'react'
import { Account, LabeledField, SaveRow, type Style } from '../primitives'
import { SettingCard } from 'ratchet-ui/components'

export type ParallelProversProps = {
  /**
   * What the server says it is running, AFTER its own clamp.
   *
   * `Workers.of()` (36-48) falls back to `DEFAULT` when the file is missing OR unreadable (#18), so
   * a `4` here can mean "nobody has set this" or "the file says `four` and nothing could read it".
   * The payload cannot yet tell them apart, which is why the row below says "currently 4" and never
   * claims the code chose it.
   */
  workers: number
  least: number
  most: number
  /** `Workers.DEFAULT`. What you get when nothing readable was set. */
  fallback: number
  onSave: (n: number) => void
}

const ERROR: Style = { margin: '.2rem 0 0', fontSize: '.74rem', color: 'var(--state-infra)' }

/**
 * How many provers run at once.
 *
 * RENDER THE RESPONSE, NOT THE REQUEST. The server clamps on save (`Workers.clamp` 57-59) while the
 * form only set `min` and `max` in the browser (1387-1392), so a saved 40 was kept as 8 and the page
 * went on showing 40 until somebody reloaded — a form that reports a number the server never
 * accepted. The field is text, `Tuning`-style: the bounds are stated in the prose, the server
 * decides, and the caller re-seeds this component from what came back.
 *
 * BUG NOT PORTED (`theRun` 1387): the row was hardcoded `ev asked`, the "somebody changed this"
 * colour, so the width looked overridden even when it was sitting at the default. `changed` is
 * whether it actually differs from the fallback.
 *
 * The one paragraph in this codebase that interpolates numbers lives here rather than in `Account`'s
 * caller, because `LEAST`, `MOST` and `DEFAULT` are already props of this component (Account 1451).
 */
export function ParallelProvers({ workers, least, most, fallback, onSave }: ParallelProversProps) {
  const [draft, setDraft] = useState(String(workers))
  const [refused, setRefused] = useState(false)
  function save() {
    const n = Number(draft.trim())
    // A blank string is 0 to `Number`, so the blank test is separate. Refusing here is not the
    // clamp — the server still owns that; it is refusing to send something that is not a number at
    // all, which would come back as the fallback and look like a save that worked.
    if (draft.trim().length === 0 || !Number.isInteger(n)) {
      setRefused(true)
      return
    }
    setRefused(false)
    onSave(n)
  }
  return (
    <SettingCard title="parallel provers" provenance={`currently ${workers}`} changed={workers !== fallback}>
      <Account>
        {`How many markers are proved at the same time. Between ${least} and ${most}; ${fallback} when nothing readable is set. The server clamps what you save, so what appears here afterwards is what it kept, not what you typed.`}
      </Account>
      <LabeledField name="workers" label="provers" value={draft} onChange={setDraft} />
      {refused ? <p style={ERROR}>that is not a whole number, so nothing was sent</p> : null}
      <SaveRow onSave={save} />
    </SettingCard>
  )
}
