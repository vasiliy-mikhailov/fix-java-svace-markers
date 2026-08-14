import type { ReactNode } from 'react'
import type { Style } from '../primitives'

export type SettingRowProps = {
  /** 'the markers', 'parallel provers', 'reproducer'. */
  name: string
  /** The small uppercase word beside it: 'edited', "the code's own", 'currently 4'. */
  state: string
  /**
   * WHETHER SOMEBODY HAS CHANGED THIS, AND THE ROW PICKS ITS OWN ACCENT FROM IT.
   *
   * THE CLASS NAMES IN THE JAVA LIE. The div is `ev asked` — blue, the colour of a live prompt —
   * when a setting has been overridden, and `ev tool` — grey — when it is still the default (1196,
   * 1224, 1256, 1276, 1332, 1435, 1509). Nothing was asked and no tool ran: the trace-event palette
   * was borrowed to mean "someone has changed this", so a settings page and a trace page used the
   * same two colours for unrelated facts, and any restyling of the trace silently restyled settings.
   *
   * So this takes a BOOLEAN. Never a `kind`, never a `className`, and never anything lifted off a
   * `TraceEvent` — the fact is "changed", and the colour is chosen here where it can be tested.
   *
   * The accent is the portal's `--accent-primary`, not a domain state colour, for the same reason
   * `TabRow` lights a tab with `--state-selected-bg`: having edited a setting is an interaction
   * fact, not something the record decided. It is deliberately not `--state-infra`, which is this
   * domain's red and means a prove threw.
   */
  changed: boolean
  /**
   * The scroll target. Saving redirects to `/settings#<agent>`.
   *
   * BUG NOT PORTED (#13, CSS 86 and 493): `.ev:target` outlined the row in `#f85149` — THE SAME RED
   * `.ev.failed` USES — so the confirmation that a save had worked was drawn in the failure colour.
   * There is no `:target` rule here at all. Recolouring it needed a new token for an outline that
   * says nothing the row does not already say: the row you were sent to is the one with the accent
   * and the word "edited" on it, and a save is not an incident.
   */
  anchorId?: string
  children: ReactNode
}

const ROW: Style = {
  border: '1px solid var(--border-soft)',
  borderLeft: '2px solid var(--border-strong)',
  borderRadius: '8px',
  background: 'var(--bg-card)',
  padding: '12px 14px',
  margin: '10px 0',
}

const ROW_CHANGED: Style = {
  ...ROW,
  borderLeft: '2px solid var(--accent-primary)',
  background: 'var(--bg-soft-accent)',
}

const HEAD: Style = {
  display: 'flex',
  gap: '10px',
  alignItems: 'baseline',
  flexWrap: 'wrap',
  marginBottom: '4px',
}

const NAME: Style = { fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }

const STATE: Style = {
  fontSize: '10.5px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  color: 'var(--text-tertiary)',
}

const STATE_CHANGED: Style = { ...STATE, color: 'var(--accent-primary)' }

/**
 * One setting: what it is called, what it is set to, and the control that changes it.
 *
 * THE MERGE FOUR PASSES COULD NOT DO. `SettingRow`, `SubjectSetting`, `EndpointKeyStatus` and the
 * prompts-page row were four proposals for one idiom that the Java already wrote seven times. They
 * are this. The components in this directory that ARE a whole row — `ParallelProvers`, `JdkChoice`,
 * `SourceZip`, `MarkerQueue`, `GitCredential`, `AgentPromptEditor` — render their own, because each
 * of them knows the fact that decides `changed` and a screen recomputing it would be the second copy
 * of that rule. Use this directly for a row that has no dedicated component.
 */
export function SettingRow({ name, state, changed, anchorId, children }: SettingRowProps) {
  return (
    <section id={anchorId} style={changed ? ROW_CHANGED : ROW}>
      <header style={HEAD}>
        <span style={NAME}>{name}</span>
        <span style={changed ? STATE_CHANGED : STATE}>{state}</span>
      </header>
      {children}
    </section>
  )
}
