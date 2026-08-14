import { Disclosure, TextFold, type Style } from '../primitives'

export type SupersededAttemptProps = {
  /** The attempt number THE RECORD gave it, 1-based — never the position in the rendered list. */
  attempt: number
  reply: string
  prompt: string
}

const BOX: Style = {
  border: '1px dashed var(--border-soft)',
  borderRadius: '6px',
  padding: '8px 12px',
  margin: '8px 0',
  // Demoted, not hidden: an answer the run itself threw away is still the only account of why it
  // went round again.
  opacity: 0.72,
}

const SUMMARY: Style = { fontSize: '11px', color: 'var(--text-tertiary)' }

const REPLY: Style = {
  margin: '4px 0 0',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  fontSize: '12.5px',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
}

/**
 * An answer a later attempt replaced.
 *
 * TWO ORDERINGS, DECIDED RATHER THAN INHERITED. The Java rendered these newest-first below the final
 * answer (2007) while printing ascending attempt numbers, which reads as a list that is sorted
 * wrongly. Both halves are kept, deliberately: newest-first is right, because the attempt just
 * before the final one is the one that explains it; and the NUMBER stays the record's, because a
 * number that counted down the rendered list would disagree with the trace, the settlement and the
 * archive directory — the same class of mistake as `rate()` numbering answers by position (#3).
 * The word "superseded" is in the summary so the reversal cannot be misread as a sort.
 *
 * NO RATING CONTROL HERE, unlike {@link AgentAnswer}. Rating an answer the run itself rejected would
 * train the corpus on a reply that was already overruled by a critic.
 *
 * The fold id is the attempt number because an agent tab shows ONE agent: `attempt 2` is unique
 * within the tab, which is the page {@link SupersededAttempt} appears on.
 */
export function SupersededAttempt({ attempt, reply, prompt }: SupersededAttemptProps) {
  return (
    <div style={BOX}>
      <Disclosure
        id={`superseded:${attempt}`}
        defaultOpen={false}
        summary={<span style={SUMMARY}>{`attempt ${attempt}, superseded`}</span>}
      >
        <p style={REPLY}>{reply}</p>
        <TextFold
          id={`superseded:${attempt}:prompt`}
          label="the prompt it answered"
          body={prompt}
          defaultOpen={false}
        />
      </Disclosure>
    </div>
  )
}
