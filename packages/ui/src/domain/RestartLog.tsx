import type { MarkerId, MarkerKey } from '@fsm/types'
import { Disclosure, RelativeTime, type Style } from '../primitives'

export type Restart = {
  at: number
  /** The slug — the directory name and the id every API path uses. */
  id: MarkerId
  /** The exact `repo|file|line|checker`. */
  marker: MarkerKey
  /** Which attempt was cut. */
  attempt: number
  /** Whether a running prove was killed, or the restart only queued the next attempt. */
  killed: boolean
  /**
   * WHO ORDERED IT, and the field the Java dropped (#7).
   *
   * `Supervisor.restarts()` counts only the lines WITHOUT `by="person"` (426-430), because the
   * supervisor is allowed two of its own and a person's `/reprove` must not spend them. Flattening
   * the two together — which is what the page did by not showing this — meant a reader watching a
   * marker go round for the third time could not tell whether the supervisor had run out of rope or
   * somebody had been pressing the button.
   */
  by: 'person' | 'supervisor'
  why: string
}

export type RestartLogProps = { restarts: Restart[]; defaultOpen: boolean }

const ROW: Style = {
  display: 'grid',
  gap: '2px',
  padding: '8px 0',
  borderTop: '1px solid var(--border-soft)',
}

const LINE: Style = { display: 'flex', gap: '8px', alignItems: 'baseline', flexWrap: 'wrap', fontSize: '11px' }

const BY: Style = { color: 'var(--text-primary)', fontWeight: 600 }

const KEY: Style = { color: 'var(--text-secondary)', fontSize: '11.5px', wordBreak: 'break-all' }

const SLUG: Style = { color: 'var(--text-tertiary)', fontSize: '11px' }

const WHY: Style = {
  margin: '2px 0 0',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  fontSize: '12px',
  lineHeight: 1.5,
  color: 'var(--text-secondary)',
}

const SUMMARY: Style = { fontSize: '11px', color: 'var(--text-tertiary)' }

/**
 * Every prove the supervisor or a person sent round again.
 *
 * BUG FIXED (#7, `reported()` 723-733). The Java flattened seven recorded fields into one `<pre>`
 * blob and printed four of them; `by` was one of the three it dropped, so the page could not
 * distinguish a restart the supervisor spent from its allowance of two from one a person ordered
 * with `/reprove` — a distinction the supervisor's own counter is built on. Each restart gets a row
 * and every field it recorded is on it.
 *
 * `id` is the slug and `marker` is the exact key, and neither is a link: they were not links in the
 * Java either, and this component does not know the URL of a marker page.
 *
 * NOTHING CUT RENDERS NOTHING. "0 restarts" as a fold is a claim about a thing that did not happen;
 * the tally above already counts them, and it counts zero.
 */
export function RestartLog({ restarts, defaultOpen }: RestartLogProps) {
  if (restarts.length === 0) {
    return null
  }
  const byPerson = restarts.filter(restart => restart.by === 'person').length
  const counted = restarts.length === 1 ? '1 restart' : `${restarts.length} restarts`
  return (
    <Disclosure
      id="restarts"
      defaultOpen={defaultOpen}
      summary={
        <span style={SUMMARY}>
          {byPerson === 0
            ? `${counted}, all by the supervisor`
            : `${counted}, ${byPerson} ordered by a person`}
        </span>
      }
    >
      {restarts.map((restart, index) => (
        // Keyed by position: the log is append-only and the same marker is legitimately restarted
        // twice in the same second by two different actors.
        <div key={index} style={ROW}>
          <div style={LINE}>
            <span style={BY}>{restart.by === 'person' ? 'a person' : 'the supervisor'}</span>
            <span>{restart.killed ? 'killed the running prove' : 'let the attempt finish'}</span>
            <span>{`attempt ${restart.attempt}`}</span>
            {restart.at > 0 ? <RelativeTime at={restart.at} variant="conversation" /> : null}
          </div>
          <span style={KEY}>{restart.marker}</span>
          <span style={SLUG}>{restart.id}</span>
          {restart.why.trim().length === 0 ? null : <p style={WHY}>{restart.why}</p>}
        </div>
      ))}
    </Disclosure>
  )
}
