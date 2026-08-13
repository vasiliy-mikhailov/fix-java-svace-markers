import type { MarkerId, MarkerKey } from '@fsm/types'
import { RelativeTime, type Style } from '../primitives'
import { MarkerLinkedText } from './MarkerLinkedText'

export type ChatTurnData = {
  /** Epoch ms, or 0. Zero is NOT now — see below. */
  at: number
  /**
   * WHO SAID IT, AS A STRING, AND NOT A UNION.
   *
   * `mine` is not a stored fact: `Chat.Turn.mine()` (Chat.java 52-54) is `who.equals("you")`, so
   * everything that is not exactly `you` — including a value nobody planned for — is the supervisor.
   * Typing this as `'you' | 'supervisor'` would make a third value a compile error at the boundary
   * and a crash at runtime, on a file two processes append to.
   */
  who: string
  text: string
}

export type ChatTurnProps = ChatTurnData & {
  /** Slug → full marker key, for the markers this turn names. `MarkerLinkedText` owns the matching. */
  markers: ReadonlyMap<MarkerId, MarkerKey>
}

const TURN: Style = {
  border: '1px solid var(--border-soft)',
  borderRadius: '8px',
  background: 'var(--bg-card)',
  padding: '8px 12px',
  margin: '8px 0',
}

const MINE: Style = { ...TURN, background: 'var(--state-selected-bg)' }

const HEAD: Style = { display: 'flex', gap: '8px', alignItems: 'baseline', marginBottom: '2px' }

const WHO: Style = { fontSize: '11px', fontWeight: 600, color: 'var(--text-primary)' }

const BODY: Style = {
  margin: 0,
  // The author's own newlines are load-bearing (CSS 125-126), which is why `Chat.answer()` strips
  // only the ENDS before recording: a reply opening with two blank lines rendered an inch below its
  // own name and looked like the supervisor had said nothing.
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  fontSize: '13px',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
}

/**
 * One thing said, by you or by the supervisor.
 *
 * `at === 0` OMITS THE WHOLE "· N ago" SPAN (972). It does not mean now and must never fall back to
 * now: zero is what `num()` returns for a timestamp it could not read, and a turn stamped with the
 * moment of rendering is a lie that gets more convincing the longer you look at it.
 *
 * The body goes through `MarkerLinkedText`, which escapes before it links — the ordering that kept a
 * model's answer from writing markup into the page, and this is a model's answer.
 */
export function ChatTurn({ at, who, text, markers }: ChatTurnProps) {
  const mine = who === 'you'
  return (
    <article style={mine ? MINE : TURN}>
      <header style={HEAD}>
        <span style={WHO}>{mine ? 'you' : 'the supervisor'}</span>
        {at > 0 ? <RelativeTime at={at} variant="conversation" /> : null}
      </header>
      <p style={BODY}>
        <MarkerLinkedText text={text} markers={markers} />
      </p>
    </article>
  )
}
