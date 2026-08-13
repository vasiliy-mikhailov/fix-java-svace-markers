import { CHAIN, WATCH, type AgentName } from '@fsm/types'
import type { Style } from '../primitives'

/**
 * THREE GROUPS EXIST IN THE RECORD. The page showed two.
 *
 * `Agents.java` (410-426) declares `CHAIN`, `WATCH` and `ASKED`. `promptsFor()` tested only
 * `Agents.CHAIN.contains(agent)` (1426) and swept everything else under "watching the run" — which
 * put `chat` there, and chat watches nothing at all: it runs when a person types a question. A
 * reader editing prompts was told the chat agent was part of the supervision machinery.
 */
export type AgentGroup = 'chain' | 'watch' | 'asked'

export type AgentGroupHeadingProps = { group: AgentGroup }

/**
 * Which group an agent is in — ONE RULE, and it is this one.
 *
 * It is exported because the alternative is every screen writing `CHAIN.includes(agent) ? … : …`
 * again, which is the two-copies problem (#12) with a third copy of the chain order thrown in. Note
 * the fallthrough is `asked`, not `watch`: an agent this build has never heard of is a thing someone
 * added, and calling it a watcher is exactly the guess that put `chat` in the wrong group.
 */
export function groupOf(agent: AgentName): AgentGroup {
  if ((CHAIN as readonly string[]).includes(agent)) {
    return 'chain'
  }
  if ((WATCH as readonly string[]).includes(agent)) {
    return 'watch'
  }
  return 'asked'
}

const SAYS: Record<AgentGroup, { title: string; account: string }> = {
  chain: {
    title: 'inside a prove',
    account: 'ten agents, five producer-and-critic pairs, in the order a prove calls them',
  },
  watch: {
    title: 'watching the run',
    account: 'four that look at a run from outside it and never take part in one',
  },
  asked: {
    title: 'answering when asked',
    account: 'runs when a person types a question, and watches nothing',
  },
}

const HEAD: Style = { margin: '20px 0 0', fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }

const ACCOUNT: Style = { margin: '2px 0 0', fontSize: '11.5px', color: 'var(--text-tertiary)' }

/** The heading over a group of agent prompts. */
export function AgentGroupHeading({ group }: AgentGroupHeadingProps) {
  const said = SAYS[group]
  return (
    <div>
      <h2 style={HEAD}>{said.title}</h2>
      <p style={ACCOUNT}>{said.account}</p>
    </div>
  )
}
