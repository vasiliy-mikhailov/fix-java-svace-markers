import type { MarkerId, MarkerKey } from '@fsm/types'
import { EmptyNote, type Style } from '../primitives'
import { ChatTurn, type ChatTurnData } from './ChatTurn'

export type ChatTranscriptProps = {
  /**
   * IN APPEND ORDER, AND NOT RE-SORTED BY `at`.
   *
   * A turn recorded with `at = 0` — which is what an unreadable timestamp becomes — would jump to
   * the top of a sorted list and put an answer above the question it answers. The file's order is
   * the conversation's order; nothing else is.
   */
  turns: ChatTurnData[]
  markers: ReadonlyMap<MarkerId, MarkerKey>
  /**
   * WHETHER `chat.jsonl` COULD BE READ AT ALL. This field does not exist in the Java (#16).
   *
   * `Chat.turns()` (Chat.java 88-92) swallows the IOException and returns an empty list, so a
   * corrupted or unreadable conversation rendered as a friendly "nothing asked yet" welcome. Two
   * completely different situations — one where there is nothing to show and one where there is
   * something and it cannot be shown — arrived at the reader as the same reassuring sentence, and
   * the only way to find out was to look at the disk.
   */
  readable: boolean
}

const BROKEN: Style = {
  border: '1px solid var(--state-infra)',
  borderRadius: '8px',
  padding: '12px 14px',
  margin: '10px 0',
  fontSize: '12.5px',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
}

/** The conversation with the supervisor, oldest first. */
export function ChatTranscript({ turns, markers, readable }: ChatTranscriptProps) {
  if (!readable) {
    return (
      <div style={BROKEN}>
        the conversation could not be read. This is not an empty conversation — whatever was said is
        still on disk and this page cannot show it.
      </div>
    )
  }
  if (turns.length === 0) {
    return <EmptyNote>nothing asked yet. It can see every marker&apos;s state, builds, answers and settlement.</EmptyNote>
  }
  return (
    <div>
      {turns.map((turn, index) => (
        // Keyed by position, which is the identity an append-only log gives a line: two turns can
        // carry the same text, the same author and the same second. Nothing persists against this
        // key — folds and ratings key by event id (#10, #3).
        <ChatTurn key={index} at={turn.at} who={turn.who} text={turn.text} markers={markers} />
      ))}
    </div>
  )
}
