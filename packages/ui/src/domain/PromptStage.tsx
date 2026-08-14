import type { ReactNode } from 'react'
import { CHAIN, type AgentName } from '@fsm/types'
import type { Style } from '../primitives'

/**
 * THE PROMPTS PAGE IS THE ONE PLACE THE CHAIN'S SHAPE IS INVISIBLE.
 *
 * Everywhere else a stage is drawn as a stage: the chain strip boxes its three agents under a label,
 * the marker tabs step through them in order. Here the same fifteen prompts were a flat column of
 * full-width boxes, each as tall as the last, with nothing between `reproduce-verifier` and
 * `fix-planner` to say that one stage had ended and another begun. Somebody editing a planner had to
 * know from the NAME which doer it feeds — the page would not tell them.
 *
 * That is also why the prompts drift out of step with each other. A planner and the doer that works
 * from its plan are one thought split across two boxes; a page that shows them a screen apart, in a
 * list of fifteen, is a page that gets one of them edited.
 *
 * So a stage is a section, and its three roles sit side by side. Three across is not decoration: it
 * is the unit of work. The grid is `auto-fit`, so on a narrow window it folds to two and then to one
 * without a media query and without the boxes ever going below a width you can write a paragraph in.
 */
export type PromptStageProps = {
  /**
   * The stage's own name — `reproduce`, `fix`, … taken from the agents it holds.
   *
   * Absent for the agents that run in no stage. The watchers still lay out in the same grid, because
   * the alternative is a full-width box: at the width somebody actually has this page open, that is a
   * two-hundred-character line of prose, and a page that sets fifteen prompts in one measure and five
   * in another is the imbalance moved rather than fixed. What separates them is the heading they sit
   * under, which already says they take no part in a prove.
   */
  label?: string
  /** The editors, in the order the server sent them. */
  children: ReactNode
}

/**
 * The stage an agent belongs to, or null if it does not run in a prove.
 *
 * Read off the NAME, which is where the answer already is: every chain agent is `<stage>-<role>`.
 * The alternative — a table mapping fifteen names to five stages — is a fourth copy of the chain's
 * shape, and three copies have gone stale in a day.
 */
export function stageOf(agent: AgentName): string | null {
  if (!(CHAIN as readonly string[]).includes(agent)) {
    return null
  }
  const cut = agent.lastIndexOf('-')
  return cut > 0 ? agent.slice(0, cut) : null
}

const SECTION: Style = { margin: '14px 0 0' }

const LABEL: Style = {
  margin: '0 0 6px',
  fontSize: '11px',
  fontWeight: 600,
  letterSpacing: '.08em',
  textTransform: 'uppercase',
  color: 'var(--text-tertiary)',
}

/** One stage of the chain: its name, and its three roles laid out as three. */
export function PromptStage({ label, children }: PromptStageProps) {
  return (
    <section style={SECTION} {...(label === undefined ? {} : { 'aria-label': label })}>
      {label === undefined ? null : <h3 style={LABEL}>{label}</h3>}
      <div className="fsm-prompt-grid">{children}</div>
    </section>
  )
}
