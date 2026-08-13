import { CHAIN, type AgentName, type MarkerKey } from '@fsm/types'
import type { ReactNode } from 'react'
import type { Style } from '../primitives/style'

/** The ten that run inside a prove. Derived, never retyped — see {@link STAGES}. */
export type ChainAgent = (typeof CHAIN)[number]

/**
 * The five stage names a reader sees. THE ONLY THING HERE THAT IS NOT ALREADY IN `CHAIN`.
 *
 * `reproducer`/`proof-critic` is what the pipeline calls them; `reproduce` is what the work is. The
 * labels are display copy and live here; the membership and the order do not.
 */
const STAGE_LABELS = ['reproduce', 'fix', 'propose', 'argue', 'price'] as const

export type StageName = (typeof STAGE_LABELS)[number]

export type Stage = { label: StageName; producer: ChainAgent; critic: ChainAgent }

/**
 * THE CHAIN, PAIRED OFF THE ONE LIST — not typed out again.
 *
 * Java held `STAGES` (2072-2077) as a second copy of `Agents.CHAIN` and the copy had drifted:
 * `verdict-critic` was missing from it, so the agent that can send a settlement back for rework had
 * no tab of its own and its answers were readable only in the whole trace. Nobody noticed, because
 * a list that is missing an entry looks exactly like a list.
 *
 * `CHAIN` is five producer/critic pairs in call order, which is a fact its own doc comment states,
 * so pairing it off is not a guess — it is the shape. Adding a stage to the pipeline means adding
 * two agents to `@fsm/types` and one label here; it cannot mean the two lists disagreeing about who
 * is in the chain, because there is only one list of who.
 */
export const STAGES: readonly Stage[] = STAGE_LABELS.flatMap((label, index) => {
  const producer = CHAIN[index * 2]
  const critic = CHAIN[index * 2 + 1]
  // `noUncheckedIndexedAccess` asks for this guard, and it earns its keep: a `CHAIN` that lost an
  // agent drops a whole stage off the strip — visibly, as a gap a reader can see — rather than
  // rendering the word `undefined` as an agent nobody can click.
  return producer === undefined || critic === undefined ? [] : [{ label, producer, critic }]
})

export type ChainStripProps = {
  markerKey: MarkerKey
  /** `''` is the summary tab. */
  current: '' | AgentName | 'live' | 'prompts' | 'trace'
  /**
   * How many times each agent answered — one per `asked` event in this marker's trace.
   *
   * COUNT BEFORE YOU DISPATCH ON THE TAB. Java's `marker()` read the trace, then branched on `?a=`,
   * and an earlier arrangement branched first: two of the tabs called the one-argument `tabs()`
   * (2092-2094) which passes `List.of()`, so those two rendered the whole chain with every count
   * missing and every stage dimmed — the page said "nothing ever ran here" about a marker that had
   * run five agents. The screen loads the trace once and hands the counts in.
   */
  runs: Partial<Record<AgentName, number>>
}

export type ChainStageProps = {
  label: StageName
  producer: { agent: ChainAgent; runs: number }
  critic: { agent: ChainAgent; runs: number }
  markerKey: MarkerKey
  current: string
}

export type AgentChipProps = {
  agent: AgentName
  runs: number
  active: boolean
  /** Whoever knows the URL passes the URL — the lesson `tab()` learned by ending with no callers. */
  href: string
}

function markerHref(key: MarkerKey, agent?: string): string {
  const base = `/marker?k=${encodeURIComponent(key)}`
  return agent === undefined ? base : `${base}&a=${encodeURIComponent(agent)}`
}

const NAV: Style = {
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: '.5rem',
  margin: '.7rem 0 .3rem',
}

const STAGE_BOX: Style = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: '.3rem',
  position: 'relative',
  border: '1px solid var(--border-soft)',
  borderRadius: '7px',
  padding: '.55rem .5rem .35rem',
}

/** A stage nothing ran in. Usually the most informative thing on the page — see the component. */
const STAGE_OFF: Style = { ...STAGE_BOX, opacity: 0.42 }

const LABEL: Style = {
  position: 'absolute',
  top: '-.52rem',
  left: '.5rem',
  fontSize: '.6rem',
  letterSpacing: '.08em',
  textTransform: 'uppercase',
  color: 'var(--text-tertiary)',
  // The label sits ON the border and knocks a hole in it, so its background has to be whatever is
  // behind the strip. That is the page, not a card: this strip is chrome, above the panels.
  background: 'var(--bg-canvas)',
  padding: '0 .25rem',
}

const CHIP: Style = {
  background: 'var(--bg-card)',
  border: '1px solid var(--border-strong)',
  borderRadius: '5px',
  padding: '.2rem .45rem',
  fontSize: '.78rem',
  color: 'var(--text-secondary)',
  whiteSpace: 'nowrap',
  textDecoration: 'none',
}

/** Never ran. Dashed and hollow, the same word the semaphore's third lamp uses. */
const CHIP_NEVER: Style = {
  borderStyle: 'dashed',
  background: 'transparent',
  color: 'var(--text-tertiary)',
}

/**
 * The one you are reading. The portal's selection tokens and not a domain colour: being on a tab is
 * an interaction fact, not a fact about markers — the same call `TabRow` made.
 */
const CHIP_ON: Style = {
  background: 'var(--state-selected-bg)',
  color: 'var(--state-selected-text)',
  fontWeight: 600,
}

const COUNT: Style = { marginLeft: '.35rem', fontWeight: 600 }

const ARROW: Style = { color: 'var(--text-tertiary)', fontSize: '.75rem' }

/**
 * The critic sent it back.
 *
 * Amber, as it was in Java (#d29922). Of this palette's two ambers — they are the same value in
 * both themes — `--state-needs-review` is the one whose NAME is about something wanting another
 * look, which is exactly what a loop is; `--state-interrupted` is about a lane that died.
 */
const LOOP: Style = { color: 'var(--state-needs-review)', fontSize: '.95rem', lineHeight: 1 }

const PILL: Style = {
  background: 'var(--bg-card)',
  border: '1px solid var(--border-strong)',
  borderRadius: '999px',
  padding: '.25rem .6rem',
  fontSize: '.76rem',
  color: 'var(--text-tertiary)',
  textDecoration: 'none',
  whiteSpace: 'nowrap',
}

const PILL_ON: Style = {
  ...PILL,
  background: 'var(--state-selected-bg)',
  color: 'var(--state-selected-text)',
  fontWeight: 600,
}

/**
 * The destinations either side of the chain: the summary, and the three views of the record.
 *
 * NOT the `Pill` primitive, which has six tones and no notion of being the one you are on — a
 * destination that cannot say "you are here" is the wrong shape for a strip whose entire job is to
 * say where you are.
 */
function ChainPill({ href, on, children }: { href: string; on: boolean; children: ReactNode }) {
  return (
    <a href={href} style={on ? PILL_ON : PILL} aria-current={on ? 'page' : undefined}>
      {children}
    </a>
  )
}

/**
 * One agent: its name, how many times it answered, and a link to what it said. Java `chip()`
 * 2132-2137.
 *
 * `runs === 0` OMITS THE COUNT ENTIRELY. Not a zero: a zero is a measurement, and the chip is
 * saying there was nothing to measure. Same instinct as the semaphore's hollow lamp — and the chip
 * takes the same dashed hollow outline, so "never happened" reads as one word across the page.
 *
 * An agent can be both never-run and the tab you are on — it still has a page, which will say so —
 * and the two facts stack rather than one hiding the other.
 */
export function AgentChip({ agent, runs, active, href }: AgentChipProps) {
  const never = runs === 0
  return (
    <a
      href={href}
      style={{ ...CHIP, ...(never ? CHIP_NEVER : {}), ...(active ? CHIP_ON : {}) }}
      aria-current={active ? 'page' : undefined}
      title={never ? `${agent} never ran` : `${agent} answered ${runs} time(s)`}
    >
      {agent}
      {never ? null : <b style={COUNT}>{runs}</b>}
    </a>
  )
}

/**
 * One stage: the pair that runs it, and whether the critic sent the work back. Java `tabs()`
 * 2106-2119.
 *
 * A STAGE THAT DIMS DID NOT RUN, and that is usually the most informative thing on the page — a
 * marker settled at `argue` never reached a fixer, one that stops after `reproduce` never got a
 * red, and neither of those is legible from the disposition alone.
 *
 * THE LOOP IS INFERRED, NOT RECORDED. `producer.runs > 1` IS the critic having objected: nothing
 * else in this chain makes a producer answer twice — a producer runs once and runs again only
 * because its critic sent it back and `Prove` asked it to, which this chain allows exactly once per
 * stage. So there is no `looped` prop; adding one would move the inference back out to every
 * caller, which is where Java kept it. It REPLACES the arrow rather than sitting beside it: the
 * arrow is decoration and the loop is a finding, and two glyphs would read as two hops.
 */
export function ChainStage({ label, producer, critic, markerKey, current }: ChainStageProps) {
  const ran = producer.runs + critic.runs > 0
  return (
    <span style={ran ? STAGE_BOX : STAGE_OFF}>
      <span style={LABEL}>{label}</span>
      <AgentChip
        agent={producer.agent}
        runs={producer.runs}
        active={current === producer.agent}
        href={markerHref(markerKey, producer.agent)}
      />
      {producer.runs > 1 ? (
        <span style={LOOP} role="img" aria-label="the critic sent it back" title="the critic sent it back">
          ↺
        </span>
      ) : (
        <span style={ARROW} aria-hidden="true">
          →
        </span>
      )}
      <AgentChip
        agent={critic.agent}
        runs={critic.runs}
        active={current === critic.agent}
        href={markerHref(markerKey, critic.agent)}
      />
    </span>
  )
}

/**
 * THE CHAIN, WITH WHAT ACTUALLY HAPPENED IN IT. Java `tabs()` 2092-2129.
 *
 * This was ten agent names in a row, which told a reader the pipeline's vocabulary and nothing
 * about the marker in front of them: which stages ran, which never did, and where a critic sent
 * work back. All three are countable from the trace and none of them was shown.
 *
 * NOT A `TabRow`, which is why it is not called `MarkerTabs`. A tab row is a flat set of
 * destinations; this is five bordered groups with a pair of agents inside each, and the borders and
 * the dimming carry as much as the links do.
 *
 * The departures Java hung off the end of this nav — "the supervisor", "settings" (2126-2127) — are
 * not here. `current` cannot express them, because you are never on them while you are on a marker;
 * they are chrome, and `TabRow`'s `trailing` is the thing that already knows a departure is never
 * lit.
 */
export function ChainStrip({ markerKey, current, runs }: ChainStripProps) {
  return (
    <nav style={NAV} aria-label="the chain">
      <ChainPill href={markerHref(markerKey)} on={current === ''}>
        summary
      </ChainPill>
      {STAGES.map(stage => (
        <ChainStage
          key={stage.label}
          label={stage.label}
          producer={{ agent: stage.producer, runs: runs[stage.producer] ?? 0 }}
          critic={{ agent: stage.critic, runs: runs[stage.critic] ?? 0 }}
          markerKey={markerKey}
          current={current}
        />
      ))}
      <ChainPill href={markerHref(markerKey, 'live')} on={current === 'live'}>
        live
      </ChainPill>
      <ChainPill href={markerHref(markerKey, 'prompts')} on={current === 'prompts'}>
        prompts
      </ChainPill>
      <ChainPill href={markerHref(markerKey, 'trace')} on={current === 'trace'}>
        the record
      </ChainPill>
    </nav>
  )
}
