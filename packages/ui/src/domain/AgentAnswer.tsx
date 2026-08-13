import type { AgentName } from '@fsm/types'
import { TextFold, type Style } from '../primitives'
import { RateAnswer } from './RateAnswer'

export type AgentAnswerProps = {
  agent: AgentName
  reply: string
  prompt: string
  /** Which attempt this answer came from, as the record numbers it: 1-based. */
  attempt: number
  /** How many attempts there are in total. `1` means there is nothing superseded below. */
  attempts: number
  /**
   * THE EVENT'S OWN ID, and the reason `EventBase.id` was added to the record.
   *
   * `rate()` (1996) posted a POSITIONAL INDEX. Three views number the same answer differently —
   * `/marker`'s agent tab counts that agent's answers (2140), `/trace` counts every event (2164) —
   * so a rating collected on one screen landed on a different answer when read back from another,
   * and a settled marker shifted the numbering of everything after it. The corpus was being written
   * against positions in a list that changes.
   */
  eventId: string
  /** Where `/rate` should return the reader to. The screen knows the URL; this component never builds one. */
  back: string
}

const BOX: Style = {
  border: '1px solid var(--border-soft)',
  borderLeft: '2px solid var(--state-reproduced)',
  borderRadius: '6px',
  background: 'var(--bg-card)',
  padding: '10px 12px',
  margin: '10px 0',
}

const HEAD: Style = {
  display: 'flex',
  gap: '8px',
  alignItems: 'baseline',
  flexWrap: 'wrap',
  margin: '0 0 6px',
}

const AGENT: Style = { fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)' }

const ATTEMPT: Style = { fontSize: '11px', color: 'var(--text-tertiary)' }

const REPLY: Style = {
  margin: 0,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  fontSize: '13px',
  lineHeight: 1.6,
  color: 'var(--text-primary)',
}

/**
 * What an agent answered, the prompt it answered, and the one control that writes to the corpus.
 *
 * THE RATING GOES THROUGH `RateAnswer`, WHICH IS THE ONLY THING THAT POSTS TO THE CORPUS. A second
 * form here would be a second payload builder, and the one that drifts is always the one nobody
 * looks at — which is how three views came to number the same answer three different ways (#3).
 * `eventId` is handed to it whole: a stable id, not a position in whichever list is on screen.
 *
 * The section carries `id={eventId}` too, so a link to one answer keeps working when a marker
 * settles and everything after it moves.
 */
export function AgentAnswer({ agent, reply, prompt, attempt, attempts, eventId, back }: AgentAnswerProps) {
  return (
    <section id={eventId} style={BOX}>
      <header style={HEAD}>
        <span style={AGENT}>{agent}</span>
        {attempts > 1 ? <span style={ATTEMPT}>{`attempt ${attempt} of ${attempts}`}</span> : null}
      </header>
      <p style={REPLY}>{reply}</p>
      <TextFold id={`${eventId}:prompt`} label="the prompt it answered" body={prompt} />
      <RateAnswer target={{ kind: 'answer', eventId }} back={back} />
    </section>
  )
}
