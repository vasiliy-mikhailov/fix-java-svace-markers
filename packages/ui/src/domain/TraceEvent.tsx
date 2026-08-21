import type { AgentName, MarkerKey, MarkerState, TraceEvent as TraceRow, TraceKind } from '@fsm/types'
import type { ReactNode } from 'react'
import { DiffBlock, Disclosure, TextFold, type Style } from '../primitives'
import { MarkerCrumb } from './MarkerCrumb'
import { RateAnswer } from './RateAnswer'
import { StateBadge } from './StateBadge'

/**
 * ONE ROW OF THE RECORD, as it arrives on the wire.
 *
 * `@fsm/types` describes the trace as a flat bag of optional fields, which is what the file on disk
 * is. Three things the record carries are missing from it and are added here rather than guessed at
 * a call site:
 *
 *  - `id`, the stable identity every event now ships with. Nothing identified an event before, which
 *    is the whole of bug #3: `rate()` posted the event's POSITION in whichever list happened to be on
 *    screen, so the same answer was `event=4` on `/trace`, `event=0` on `/marker?a=trace` and a third
 *    number on the agent tab, and `feedback.jsonl` holds all three kinds with nothing to tell them
 *    apart.
 *  - the per-kind fields `one()` reads and the shared type has not caught up to — `passed`, `state`,
 *    `because`, `minutes`, `itemisation`, `text` — every one of them a `field(e, …)` call in
 *    Dashboard 2196-2220.
 *  - `phase`, narrowed to what is actually written. IT IS LOWERCASE ON DISK: `marker()` compares
 *    `field(e,"phase").equals("red")` (1865) and `one()` uppercases only for display (2203). Three
 *    passes typed it `"RED"` and would have lit the wrong lamp.
 *
 * `kind` is widened back to `string`, deliberately. The record is append-only and will grow kinds;
 * the default branch of `one()` (2219) renders an unknown one as its bare name and that is the
 * correct behaviour, not an oversight to tighten up. `@fsm/types` already knows a ninth kind
 * (`system`) that the Java never had a case for, which is the argument making itself.
 */
export type TraceEventRecord = Omit<TraceRow, 'kind'> & {
  kind: TraceKind | (string & {})
  id: string
  marker?: MarkerKey | 'overwatch' | null
  phase?: 'red' | 'green'
  passed?: boolean
  state?: MarkerState
  because?: string
  minutes?: number
  itemisation?: string
  text?: string
  /**
   * THE BODY, COMPOSED BY THE SERVER for every kind that is only words — see `Body` in the Java.
   *
   * Present and non-empty means the row needs nothing else read out of it, which is why it is
   * tested before the switch. The per-kind fields below survive for the kinds that draw structure.
   */
  said?: string
  /** Which message this was, on a `sent` row: `system`, `user`, `assistant`, or `failed`. */
  role?: string
  /** On a `metered` row: the server's own reason for stopping — `STOP`, `LENGTH`, `ERROR`. */
  finish?: string
  /** Tokens the server charged for the prompt, and for what it generated. */
  input?: number | string
  output?: number | string
  /** Wall clock for the one call, milliseconds. */
  ms?: number | string
  /**
   * Two names for one field: `arguments` is what is on disk and in the payload (`field(e,
   * "arguments")`, 2214), `args` is what `@fsm/types` calls it. Both are read below until the shared
   * type is renamed — dropping the fallback silently empties every tool call written under the other
   * spelling, and an empty fold reads as a tool that was called with nothing.
   */
  arguments?: string
}

export type TraceEventProps = {
  event: TraceEventRecord
  /** True on the whole-trace view, where rows from every marker are interleaved. Was `key.isEmpty()`
   *  (2183) — a test on the page's key, which is a fact about the page and belongs in a prop. */
  showMarker: boolean
  /** Null when there is no page to send the reader to — the supervisor's own record has rows that
   *  belong to no marker. Then the crumb is text, not a dead link. */
  markerHref: string | null
  defaultOpen: boolean
  /** Where a rating returns to (`self`, 2178-2180). This row appends its own anchor to it. */
  feedbackBack: string
}

/* ------------------------------------------------------------------ the palette

   THE LEFT BORDER IS WHICH KIND OF ROW THIS IS — never how the row turned out.

   Outcomes are words. A reader scanning a feed is asking "what sort of thing happened here", and a
   border that changed colour with the answer would make `passed` and `failed` two different kinds of
   event, which they are not.

   ONE REFINEMENT, on `built`: its colour is WHICH BUILD it was, red or green. The Java painted every
   build the same amber (CSS 203) because `--build-red` / `--build-green` did not exist yet; they do
   now, they are the two lamps the marker summary lights, and the row already prints the word RED
   beside them. The colour and the word agreeing is the point — `domain.css` puts it as "a reader
   looking at two lamps needs no legend". It is still not an outcome: a RED build that fails is the
   whole standard of proof, and colouring that failure red would call the best possible result an
   error. */

const EDGE_PLAIN = 'var(--border-soft)'

const KIND_EDGE: Record<string, string> = {
  asked: 'var(--state-proving)',
  // Purple, and the border is the same purple as the name, because REASONING IS NOT EVIDENCE. A
  // thought is what an agent worked through, not what it decided, and the two are one glance apart
  // in a feed that renders them alike.
  thought: 'var(--state-by-design)',
  tool: 'var(--border-strong)',
  // The only kind with no colour of its own, and correctly so: progress notes are punctuation
  // between the events that matter and should recede.
  progress: EDGE_PLAIN,
  settled: 'var(--state-verified-pr-ready)',
  priced: 'var(--state-by-design)',
  // The same red as `infra`, because the underlying event is the same one — the tooling broke. What
  // must not be conflated is the ROW with a marker's state: this is a prove that died, not a marker
  // that settled `infra`. The words below say so; the colour cannot.
  failed: 'var(--state-infra)',
}

function edgeOf(event: TraceEventRecord): string {
  if (event.kind === 'built') {
    if (event.phase === undefined) {
      return EDGE_PLAIN
    }
    return event.phase === 'red' ? 'var(--build-red)' : 'var(--build-green)'
  }
  return KIND_EDGE[event.kind] ?? EDGE_PLAIN
}

function rowStyle(edge: string): Style {
  return {
    borderLeft: `2px solid ${edge}`,
    margin: '0 24px',
    padding: '12px 0 12px 16px',
    // The anchor a rating returns to lands here; without this the row sits under the header.
    scrollMarginTop: '1rem',
    // THE FEED IS UNCAPPED ON PURPOSE (609-617) — eight megabytes after an afternoon — so the cost
    // of the whole record has to be paid somewhere, and it is paid here rather than by slicing.
    // `content-visibility` is the only kind of virtualisation that keeps the record whole: every
    // event stays in the DOM, so find-in-page still finds it and `#e-<id>` still resolves, while
    // the browser skips layout and paint for the ones nobody has scrolled to.
    contentVisibility: 'auto',
    containIntrinsicSize: 'auto 120px',
  }
}

const WHO: Style = { color: 'var(--state-proving)', fontWeight: 600 }
const WHO_THOUGHT: Style = { color: 'var(--state-by-design)', fontWeight: 600 }
const WHO_BUILD: Record<'red' | 'green', Style> = {
  red: { color: 'var(--build-red)', fontWeight: 600 },
  green: { color: 'var(--build-green)', fontWeight: 600 },
}
const WHO_FAILED: Style = { color: 'var(--state-infra)', fontWeight: 600 }

const KIND: Style = {
  color: 'var(--text-tertiary)',
  fontSize: '11px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  marginLeft: '8px',
}

/** The bare `<pre>` a reply, a reason, an itemisation and a cause are shown in — Java `pre` (207-209).
 *  Wraps, unlike `CodeBlock`: this is prose, and prose cut off at the right edge is prose nobody
 *  reads. */
/**
 * WHAT IT WAS ASKED, styled so it does not read as what it said.
 *
 * <p>Same shape as {@link PROSE} and quieter, with a rule down the left: the question and the answer
 * sit next to each other and a reader must be able to tell which is which at a glance, without
 * reading either. A task is often long — a brief carries the whole flagged file — so it is capped
 * and scrolls rather than pushing the reply off the screen.
 */
const ASKED: Style = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  background: 'var(--bg-panel)',
  border: '1px solid var(--border-soft)',
  borderLeft: '3px solid var(--border-strong)',
  borderRadius: '6px',
  padding: '10px',
  margin: '8px 0 4px',
  maxHeight: '18rem',
  overflowY: 'auto',
  fontSize: '11.5px',
  lineHeight: 1.5,
  color: 'var(--text-tertiary)',
}

const PROSE: Style = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  background: 'var(--bg-card)',
  border: '1px solid var(--border-soft)',
  borderRadius: '6px',
  padding: '10px',
  margin: '8px 0',
  overflowX: 'auto',
  fontSize: '12px',
  lineHeight: 1.5,
  color: 'var(--text-secondary)',
}

/** An agent that was never stamped on the row. The Java printed an empty span and the row looked
 *  like nobody said it; saying so is shorter and truer. The alternative — casting `null` to an
 *  `AgentName` — is how `null` ends up rendered as an agent's name. */
function agentLabel(agent: AgentName | null): string {
  return agent ?? 'unrecorded'
}

/* ------------------------------------------------------------------ the eight bodies */

export type AnsweredEventProps = {
  agent: AgentName | null
  reply: string
  prompt: string
  eventId: string
  /** The marker this answer belongs to. Rendered by nothing here, and NOT passed to `RateAnswer`:
   *  the row the server writes resolves its own marker from `eventId`. That is bug #3's other half —
   *  on the supervisor, `reported()` posted `marker="overwatch"` (757-758), filing feedback against a
   *  marker that does not exist and crediting the critic's work to the supervisor. A marker the
   *  browser hands back is a marker the browser can get wrong. */
  marker: string
  back: string
  /** The Java's `expand` reached this fold too (`fold("the prompt it was given", …, expand)`, 2200),
   *  so it is a prop here like it is on the other three bodies. The catalogue's prop list omits it. */
  defaultOpen: boolean
}

/**
 * THE TWO HALVES OF A RECORDED PROMPT.
 *
 * <p>`Agents.runtime` records `prompt + "\n\n---\n\n" + task`, so every `asked` row carries the
 * agent's standing instructions and the one specific thing it was asked, joined. The first is
 * identical across every call that agent makes in the whole run and is already readable on the
 * prompts tab; the second is what distinguishes this event from the others.
 *
 * <p>Split on the FIRST separator only: a task quoting a diff or a build log contains `---` of its
 * own, and splitting on the last would swallow the question into the answer.
 */
const SPLIT = '\n\n---\n\n'

export function taskOf(prompt: string): string {
  const at = prompt.indexOf(SPLIT)
  return at < 0 ? prompt : prompt.slice(at + SPLIT.length)
}

export function standingOf(prompt: string): string {
  const at = prompt.indexOf(SPLIT)
  return at < 0 ? '' : prompt.slice(0, at)
}

/**
 * A ROW THAT IS ONLY WORDS, DRAWN WITHOUT KNOWING WHICH KIND PUT THEM THERE.
 *
 * <p>Six components used to sit here — progress, thought, sent, metered, system and failed — each
 * knowing which field of the record its own kind carried its body in. So did the three endpoints
 * serving those rows, and the shared type listing every field any of them might use. Four copies of
 * one fact, which drifted the way four copies do: `sent` reached the page with its text and without
 * its role, so every lane drew a fold with no label on it.
 *
 * <p>The server composes the body now, in `Body`, and this draws it. A kind of this sort costs one
 * line of Java and nothing at all here.
 *
 * <p>THE KIND IS STILL THE LABEL, because it is what a reader scans a lane for, and the agent
 * beside it is what tells two lanes apart. That is a fact about the row, not a copy of its shape.
 */
export function SaidEvent({
  agent,
  kind,
  said,
  defaultOpen,
}: {
  agent: AgentName | null
  kind: string
  said: string
  defaultOpen: boolean
}) {
  return (
    <>
      {/* THE KIND FIRST, THEN WHO SAID IT. A reader scanning a lane is looking for the kind —
          which row is a thought, which is a tool call — and the agent tells two lanes apart once
          they have found it. The sibling's record reads that way and this one read the other way
          round for no reason anybody wrote down. */}
      <span style={KIND}>{kind}</span>
      <span style={WHO}>{agentLabel(agent)}</span>
      <TextFold
        id={`said:${agent}:${kind}:${said.length}`}
        label=""
        body={said}
        defaultOpen={defaultOpen}
      />
    </>
  )
}

/** What an agent was asked, what it said, and its standing prompt folded underneath. */
export function AnsweredEvent({
  agent,
  reply,
  prompt,
  eventId,
  back,
  defaultOpen,
}: AnsweredEventProps) {
  return (
    <>
      <span style={WHO}>{agentLabel(agent)}</span>
      <span style={KIND}>answered</span>
      {/* WHAT IT WAS ASKED, ABOVE WHAT IT SAID, because a reply on its own cannot be read.
          `trace.asked` records `systemPrompt + "\n\n---\n\n" + task`. The system prompt is the
          same on every call this agent ever makes and has its own tab; the TASK is the part that
          changes, and it is the half somebody reconstructing a prove actually needs. Folding both
          together behind "the prompt it was given (17431 chars)" hid the small specific half behind
          the large repeated one, and the record read as a column of answers to questions nobody
          could see. */}
      {taskOf(prompt).length > 0 ? <pre style={ASKED}>{taskOf(prompt)}</pre> : null}
      <pre style={PROSE}>{reply}</pre>
      <TextFold
        id={`prompt:${eventId}`}
        label="the standing prompt underneath it"
        body={standingOf(prompt)}
        defaultOpen={defaultOpen}
      />
      <RateAnswer target={{ kind: 'answer', eventId }} back={back} />
    </>
  )
}

export type ToolCallEventProps = {
  agent: AgentName | null
  tool: string
  arguments: string
  result: string
  defaultOpen: boolean
  eventId: string
}

/**
 * A UNIFIED DIFF IDENTIFIES ITSELF, and nothing else about a tool call does.
 *
 * A hunk header is the one line no other kind of text produces by accident, so it is the only test
 * made here. Guessing a LANGUAGE from a tool's arguments is the exact mistake `CodeBlock`'s
 * `language` prop exists to stop — `block()` ran `colourJava()` over everything it was handed, and a
 * Kotlin or XML fragment came out with `int` and `class` highlighted wherever those words happened
 * to appear.
 */
function looksLikePatch(text: string): boolean {
  return /^@@ -\d/m.test(text)
}

function Payload({
  id,
  label,
  body,
  defaultOpen,
}: {
  id: string
  label: string
  body: string
  defaultOpen: boolean
}) {
  if (!looksLikePatch(body)) {
    return <TextFold id={id} label={label} body={body} defaultOpen={defaultOpen} />
  }
  // NEW BEHAVIOUR, AND WORTH SAYING SO (bug #19). Syntax colouring never reached `/trace` — `code()`
  // and `diff()` had three call sites between them (1739, 1845, 1934), all on the marker screen —
  // which is to say the test and the patch were coloured everywhere except the record they actually
  // pass through. The label differs from the plain fold's on purpose: a reader deciding whether to
  // open eight kilobytes wants to know it is a patch before they do.
  return (
    <Disclosure id={id} defaultOpen={defaultOpen} summary={`${label} (${body.length} chars, a patch)`}>
      <DiffBlock patch={body} />
    </Disclosure>
  )
}

/** What an agent reached for, what it passed, and what came back. */
export function ToolCallEvent(props: ToolCallEventProps) {
  const { agent, tool, result, defaultOpen, eventId } = props
  // ORDINARY TEXT WITH REAL NEWLINES. `field()` (2697-2701) has already peeled one layer of JSON
  // string off this; parsing it again turns every backslash in a Windows path into an escape.
  const args = props.arguments
  return (
    <>
      <span style={WHO}>{agentLabel(agent)}</span>
      <span style={KIND}>{tool}</span>
      <Payload id={`args:${eventId}`} label="arguments" body={args} defaultOpen={defaultOpen} />
      <Payload
        id={`result:${eventId}`}
        label="what it returned"
        body={result}
        defaultOpen={defaultOpen}
      />
    </>
  )
}

export type BuildEventProps = {
  phase: 'red' | 'green'
  passed: boolean
  infra: boolean
  summary: string
  defaultOpen: boolean
  eventId: string
}

/**
 * INFRA BEATS PASSED (2205-2206).
 *
 * A build that never compiled cannot have failed the TEST, and "failed" would read as evidence about
 * the defect — which is the one thing this row must never say wrongly, because the red build failing
 * is the entire standard of proof. The word is derived from the two booleans here; a `label: string`
 * prop would put that judgement back in Java where it could not be tested.
 *
 * Both booleans arrive UNQUOTED on the wire — real JSON booleans, never `"true"`. That is the reason
 * `field()` grew its unquoted branch (2707-2722) and the reason the semaphore never lit before it.
 */
export function BuildEvent({ phase, passed, infra, summary, defaultOpen, eventId }: BuildEventProps) {
  return (
    <>
      <span style={WHO_BUILD[phase]}>{phase.toUpperCase()}</span>
      <span style={KIND}>{infra ? 'never ran' : passed ? 'passed' : 'failed'}</span>
      <TextFold
        id={`build:${eventId}`}
        label="build output"
        body={summary}
        defaultOpen={defaultOpen}
      />
    </>
  )
}

export type SettledEventProps = { state: MarkerState; because: string }

/**
 * What the prove decided, and why.
 *
 * THE WORD IS `StateBadge`'S AND NOT THIS FILE'S. A second state→colour table here would be a second
 * answer to a question bug #1 shows is easy to get wrong — `css()` (2682-2684) sent both
 * `verified/pr-*` states to the `infra` red, and the green rules at CSS 54 were never once reached —
 * and the two copies would drift the first time a state was added. A settled event and the same
 * marker's row on the index are the same fact and have to be the same colour.
 *
 * The reason is never truncated: a reason cut at 200 characters is a reason nobody can check
 * (1704-1706).
 */
export function SettledEvent({ state, because }: SettledEventProps) {
  return (
    <>
      <StateBadge state={state} />
      <pre style={PROSE}>{because}</pre>
    </>
  )
}

export type PricedEventProps = { minutes: number; itemisation: string }

/** What a person would have spent. `minutes === 0` means both "never priced" and "priced at nothing"
 *  — `num()` swallows an estimator that answered in prose — and both are shown as they arrived. */
export function PricedEvent({ minutes, itemisation }: PricedEventProps) {
  return (
    <>
      <span style={WHO}>{`${minutes} min`}</span>
      <span style={KIND}>human-equivalent</span>
      <pre style={PROSE}>{itemisation}</pre>
    </>
  )
}

/**
 * The body for a row this component cannot read, rendered as its bare name — `one()`'s default
 * branch (2219), kept.
 *
 * It answers two questions, not one. A kind nobody has written a case for yet is the obvious one, and
 * the record is append-only so there will be more of them. The other is a row whose kind is known but
 * whose fields are not there: a `built` row with no `phase` is not a build anything can label, and the
 * Java's build case would have called it `failed` from two ABSENT booleans — the strongest possible
 * claim from the least possible information, about the one thing this feed must not get wrong.
 */
function BareKind({ kind }: { kind: string }) {
  return <span style={KIND}>{kind}</span>
}

function bodyOf(event: TraceEventRecord, defaultOpen: boolean, back: string): ReactNode {
  const eventId = event.id
  // EVERY KIND THAT IS ONLY WORDS, IN ONE BRANCH. `said` is composed by the server; the cases
  // below are the ones that draw structure — a lamp, two folds, a state pill, a figure, a rating.
  if (typeof event.said === 'string' && event.said.length > 0) {
    return (
      <SaidEvent
        agent={event.agent}
        kind={event.kind}
        said={event.said}
        defaultOpen={defaultOpen}
      />
    )
  }
  switch (event.kind) {
    case 'asked':
      return (
        <AnsweredEvent
          agent={event.agent}
          reply={event.reply ?? ''}
          prompt={event.prompt ?? ''}
          eventId={eventId}
          marker={event.marker ?? ''}
          back={back}
          defaultOpen={defaultOpen}
        />
      )
    case 'tool':
      return (
        <ToolCallEvent
          agent={event.agent}
          tool={event.tool ?? ''}
          arguments={event.arguments ?? event.args ?? ''}
          result={event.result ?? ''}
          defaultOpen={defaultOpen}
          eventId={eventId}
        />
      )
    case 'built':
      return event.phase === undefined ? (
        <BareKind kind={event.kind} />
      ) : (
        <BuildEvent
          phase={event.phase}
          passed={event.passed === true}
          infra={event.infra === true}
          summary={event.summary ?? ''}
          defaultOpen={defaultOpen}
          eventId={eventId}
        />
      )
    case 'settled':
      return event.state === undefined ? (
        <BareKind kind={event.kind} />
      ) : (
        <SettledEvent state={event.state} because={event.because ?? ''} />
      )
    case 'priced':
      return event.minutes === undefined ? (
        <BareKind kind={event.kind} />
      ) : (
        <PricedEvent minutes={event.minutes} itemisation={event.itemisation ?? ''} />
      )
    default:
      return <BareKind kind={event.kind} />
  }
}

/**
 * A rating returns to the row it was written on.
 *
 * The Java sent the reader back to `self` — the top of a page that is routinely eight megabytes —
 * because no event had an id to anchor to. Now every one does, and `.ev:target` (CSS 86) is already
 * waiting for it. A `back` that already carries a fragment is left alone: the caller has said where
 * it wants the reader.
 */
function withAnchor(url: string, anchor: string): string {
  return url.includes('#') ? url : `${url}#${anchor}`
}

/**
 * ONE EVENT, rendered the same whether the page is being built or extended.
 *
 * Two renderers would drift, and the one that drifts is always the live path — it is the one nobody
 * looks at directly. That was `one()`'s stated reason for existing (2205-2210) and it survives the
 * port unchanged: `EventFeed` renders the whole record and the live tail through this and nothing
 * else.
 */
export function TraceEvent({
  event,
  showMarker,
  markerHref,
  defaultOpen,
  feedbackBack,
}: TraceEventProps) {
  const anchor = `e-${event.id}`
  const marker = event.marker ?? ''
  return (
    <article id={anchor} style={rowStyle(edgeOf(event))}>
      {/* Only where markers are mixed together, and only when the row says which one it belongs to.
          `markerHref` is nullable because the supervisor's record holds rows belonging to no marker
          at all — see MarkerCrumb, which is where that link was being built unconditionally. */}
      {showMarker && marker.length > 0 ? (
        <MarkerCrumb marker={marker} href={markerHref} />
      ) : null}
      {bodyOf(event, defaultOpen, withAnchor(feedbackBack, anchor))}
    </article>
  )
}
