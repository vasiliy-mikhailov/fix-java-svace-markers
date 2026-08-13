import type { AgentName } from '@fsm/types'
import { Pill, type Style } from '../primitives'

export type AgentPendingProps = {
  agent: AgentName
  /** How many tool calls this agent has made. */
  calls: number
  /** Whether it has recorded any thinking. */
  hasThinking: boolean
}

const ROW: Style = { display: 'flex', gap: '8px', alignItems: 'baseline', flexWrap: 'wrap', margin: '10px 0' }

const SAYS: Style = { fontSize: '12.5px', color: 'var(--text-secondary)' }

const CAVEAT: Style = { margin: '2px 0 0', fontSize: '11px', color: 'var(--text-tertiary)' }

/**
 * What to say about an agent that has not answered.
 *
 * ZERO CALLS AND NO THOUGHTS IS "HAS NOT RUN". ANYTHING ELSE IS "WORKING". The Java reported any
 * agent without an answer as not-run, which threw away the only live view of the run: an agent four
 * minutes into a file search, with thinking recorded and six tools called, was described on the page
 * as having done nothing at all. Whoever was watching then reached for the container logs, which is
 * the thing this dashboard exists to make unnecessary.
 *
 * AND "HAS NOT RUN" IS A CLAIM ABOUT THE RECORD, NOT ABOUT THE AGENT. A trace still being written
 * reads exactly like one that was never started — the file is opened when the first line is
 * appended. The caveat below says so rather than letting the page assert something it cannot know.
 *
 * THE MATCHING RULE IS THE SERVER'S, AND IT IS NOW ONE RULE (#20). The Java matched an event to an
 * agent with `equals` for answers and thoughts (1966, 2031) and with `endsWith` for tool calls
 * (1948). An event whose agent was recorded with any prefix therefore contributed its tool calls and
 * none of its answers — and this component is where that surfaced, as an agent stuck at "working, 9
 * tool calls, no answer yet" forever while its answer sat in the trace two lines below.
 */
export function AgentPending({ agent, calls, hasThinking }: AgentPendingProps) {
  const working = calls > 0 || hasThinking
  if (!working) {
    return (
      <div style={ROW}>
        <div>
          <Pill tone="quiet">{`${agent} has not run`}</Pill>
          <p style={CAVEAT}>nothing is recorded for it — which is also what a trace looks like in the second before its first line lands</p>
        </div>
      </div>
    )
  }
  const counted = calls === 1 ? '1 tool call' : `${calls} tool calls`
  return (
    <div style={ROW}>
      <Pill tone="running">{`${agent} is working`}</Pill>
      <span style={SAYS}>{`${counted}, no answer yet`}</span>
    </div>
  )
}
