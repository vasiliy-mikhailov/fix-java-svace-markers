// THE HEADING IS THE LIBRARY'S, AND THE MARGIN STAYS OURS. Our four copies of these five
// declarations all omitted `fontWeight`, which is not a declaration that drifted but one nobody
// made: an `h3` with no weight takes the browser's bold, so ours drew at 700 beside the
// sibling's 500. The shared constant names the weight; only the margin is per-site.
import { HEADING } from 'ratchet-ui/components'
import { CodeBlock, type Style } from '../primitives'

export type ToolCall = { tool: string; arguments: string; result: string }

export type ToolLogProps = {
  calls: ToolCall[]
  /**
   * Whether this agent has produced an answer yet.
   *
   * It changes what the log IS. Beside an answer it is supporting detail; without one it is the
   * entire live account of an agent that is still working, and it is styled as the thing that is
   * moving rather than as an appendix.
   */
  answered: boolean
}

const BOX: Style = { margin: '10px 0' }

const LIVE: Style = {
  ...BOX,
  borderLeft: '2px solid var(--state-proving)',
  paddingLeft: '10px',
}

const CALL: Style = { margin: '8px 0' }

const TOOL: Style = { margin: 0, fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)' }

const LABEL: Style = { margin: '6px 0 0', fontSize: '10.5px', color: 'var(--text-tertiary)' }

/**
 * Every tool call this agent made, with its arguments in full.
 *
 * ARGUMENTS ARE NEVER TRUNCATED, and that is a rule with an incident behind it (1950-1951). The Java
 * cut them at 110 characters, which for `write_file` showed the path and hid the file — and the
 * argument to `write_file` IS THE TEST. The one artefact the whole reproduction turns on was the one
 * thing the cut removed, every time, on the screen built to show it.
 *
 * NO FOLD. Two reasons, both concrete: the payload carries no id per call, and `Disclosure` refuses
 * to be keyed by position for good reason (#10); and folding the only live account of a working
 * agent is the same failure {@link Thinking} exists to prevent.
 */
export function ToolLog({ calls, answered }: ToolLogProps) {
  if (calls.length === 0) {
    return null
  }
  const many = calls.length === 1 ? '1 tool call' : `${calls.length} tool calls`
  return (
    <section style={answered ? BOX : LIVE}>
      <h3 style={{ ...HEADING, margin: 0 }}>{answered ? many : `${many}, no answer yet`}</h3>
      {calls.map((call, index) => (
        // Keyed by position: a trace is append-only and one agent legitimately calls the same tool
        // with the same arguments twice. Nothing persists against this key — see the note above.
        <div key={index} style={CALL}>
          <p style={TOOL}>{call.tool}</p>
          <p style={LABEL}>arguments</p>
          <CodeBlock code={call.arguments} />
          {call.result.trim().length === 0 ? null : (
            <>
              {/* A call with no result shows ONE block, not an empty second one: the absence is the
                  statement that the tool returned nothing. */}
              <p style={LABEL}>result</p>
              <CodeBlock code={call.result} />
            </>
          )}
        </div>
      ))}
    </section>
  )
}
