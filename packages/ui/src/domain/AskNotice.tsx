import type { Style } from '../primitives'

export type AskNoticeProps = {
  /**
   * What `Chat.ask()` said: "still answering the last one", or "could not write the question down:
   * …". Blank means it said nothing, which is what a question that was taken looks like.
   */
  said: string
}

const NOTICE: Style = {
  border: '1px solid var(--state-needs-review)',
  borderRadius: '6px',
  background: 'var(--bg-panel)',
  padding: '8px 12px',
  margin: '10px 0',
  fontSize: '12.5px',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
}

/**
 * The answer to "did that question get taken", when the answer is no.
 *
 * IT IS NOT A TURN AND MUST NOT BE STYLED AS ONE. It is the return value of `Chat.ask()`, not a line
 * in `chat.jsonl` — nobody said it and it will not be there tomorrow. Dressed as a turn it would
 * read as the supervisor replying, and its most important variant means the exact opposite: "could
 * not write the question down" says the question is NOT IN THE RECORD, so the transcript above it is
 * complete and the reader has to ask again.
 *
 * IT IS TRANSIENT NOW. In the Java it travelled in `?said=` and therefore survived every three-second
 * refresh, so a notice about a question asked ten minutes ago sat under the conversation until
 * somebody edited the URL. Here it is state from the POST and goes when the next one is made.
 *
 * ONE STYLE, NOT TWO. Which variant this is cannot be told apart by reading the sentence, and
 * sniffing it for "could not" would be the `!`-sentinel convention `UploadOutcome` was built to end
 * (1130-1198) reinvented on the client. If the two need to look different, the wire grows a boolean
 * the way `refused` did.
 */
export function AskNotice({ said }: AskNoticeProps) {
  if (said.trim().length === 0) {
    return null
  }
  return <p style={NOTICE}>{said}</p>
}
