'use client'

import { Suspense, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'next/navigation'
import {
  AgentAnswer,
  AgentPending,
  ChainStrip,
  type Stage,
  ClaimCard,
  EmptyNote,
  EventFeed,
  FixDiff,
  FlaggedSource,
  HumanCost,
  LiveStream,
  MarkerAccount,
  PageHeader,
  Semaphore,
  StateBadge,
  SupersededAttempt,
  Tally,
  TestArtifact,
  TextFold,
  Thinking,
  ToolLog,
  duration,
  type FlaggedSourceRecord,
  type LiveView,
  type SettlementFlags,
  type Style,
  type ToolCall,
  type TraceEventRecord,
} from '@fsm/ui'
import { isDisposition, type AgentName, type MarkerState } from '@fsm/types'

import { href, live as subscribe, read } from '../../lib/api'

/**
 * ONE MARKER — the summary, each agent's tab, the prompts it ran under, the live stream, and this
 * marker's slice of the record. Java `marker()` 1815-2024, `promptsFor()` 1467-1522, `events()`
 * 2117-2167.
 *
 * <p>FIVE SCREENS ON ONE ROUTE, because that is what the URLs are: `?k=` names the marker and `?a=`
 * picks the view, exactly as the Java routed them. Links from the shell, from the supervisor's
 * findings and from anybody's bookmarks point at these, so the query string is a contract and not a
 * detail of this file.
 *
 * <p>THE ADAPTER IS THIS FILE'S JOB, and this page has more of it than the index does, because the
 * endpoints were shaped from the record and the components from the Java that drew it:
 *
 * <ul>
 *   <li>{@code flagged} arrives as `{firstLine, flaggedLine, fileLength, lines: string[]}` — an
 *       offset beside an array — and `FlaggedSource` takes lines that carry their own numbers. The
 *       sum is done once, here, rather than in every place that renders a window.
 *   <li>{@code summary} arrives WHOLE, deliberately (ApiMarker.summary: splitting it server-side
 *       would send it twice). `MarkerAccount` wants the half after the first blank line.
 *   <li>{@code redVerified}/{@code greenVerified} arrive flat; `Semaphore` takes a `flags` record
 *       that is null when nothing has settled — three states, not two.
 *   <li>{@code agents: [{agent, runs}]} arrives as a list; `ChainStrip` takes a map.
 * </ul>
 *
 * <p>None of the components were bent to match the payload; the API sends what the record holds and
 * the components take what they draw, and this screen is the one place allowed to know both.
 */

/* ------------------------------------------------------------------ what the wire sends */

/** `GET /api/marker?k=` — ApiMarker.marker. Every string is null when the record has nothing. */
/** One stage of the program, as `Shape` walked it off the tree `Prove` runs. */
type ApiStage = {
  title: string
  within: string
  repeats: string
  reads: string
  steps: { name: string; role: string; agent: boolean }[]
}

type ApiMarker = {
  /** The chain, from the server. Optional only because a record written before this field exists. */
  chain?: ApiStage[]
  key: string
  id: string
  repo: string
  file: string
  line: number
  checker: string
  claimNote: string | null
  state: string | null
  redVerified: boolean | null
  greenVerified: boolean | null
  testPath: string | null
  testCode: string | null
  fixDiff: string | null
  infraReason: string | null
  verdictText: string | null
  summary: string | null
  humanMinutes: number
  attempts: number
  tookMinutes: number
  agents: { agent: string; runs: number }[]
  /** Null when there is no checkout on this machine — a missing convenience, not an error. */
  flagged: {
    /** Null when the marker points past the end of the file: there is no window to show. */
    firstLine: number | null
    flaggedLine: number
    fileLength: number
    lines: string[]
  } | null
}

/** `GET /api/marker/agent?k=&a=` — ApiMarker.markerAgent. One agent's events, oldest first. */
type ApiAgentEvent = {
  /** Null, never 0: an unreadable stamp is not midnight in 1970. */
  at: number | null
  kind: string
  agent: string
  prompt: string | null
  reply: string | null
  text: string | null
  tool: string | null
  arguments: string | null
  result: string | null
  phase: string | null
  passed: boolean | null
  infra: boolean | null
  summary: string | null
  cause: string | null
  note: string | null
}

/** `GET /api/events?from=&limit=` — ApiTrace.trace. A window of the whole run, oldest first. */
type ApiEvent = {
  /** The event's position in the run's own ordering. See {@link toFeedEvent} — it is not an id. */
  index: number
  at: number | null
  kind: string
  marker: string | null
  agent: string | null
  prompt?: string
  /** On an `asking` row: the question, recorded when it was put. */
  task?: string
  /** On an `asking` row: the system prompt it ran under, as sent. */
  standing?: string
  reply?: string
  text?: string
  tool?: string
  arguments?: string
  result?: string
  phase?: string
  passed?: boolean
  infra?: boolean
  summary?: string
  note?: string
  state?: string
  because?: string
  minutes?: number | null
  itemisation?: string
  cause?: string
  stack?: string
}

type ApiEvents = {
  cursor: number
  from: number
  returned: number
  more: boolean
  openFindings: number
  events: ApiEvent[]
}

/** `GET /api/badges` — Zone.badges. The count the header wears on every screen. */
type ApiBadges = { findings: number; proving: number }

/** `GET /api/live?k=` — ApiLive.live, keyed form. */
type ApiLiveOne = {
  marker: string
  slug: string
  settled: boolean
  agent: string | null
  at: number | null
  text: string | null
  truncated: boolean
  serverNow: number
}

/* ------------------------------------------------------------------ the adapters */

/**
 * THE WINDOW, WITH EACH LINE CARRYING ITS OWN NUMBER.
 *
 * The wire sends `firstLine` and an array, which is the shape `FlaggedSource`'s own note calls out:
 * an offset beside an array makes every renderer do the same sum, and a window starting at line 1
 * and one starting at line 78 need it done correctly both times. It is done once, here.
 *
 * `firstLine` is null when the marker points PAST THE END of the file — there is no window then,
 * only the comparison, and the component draws that comparison from `flagged` against `fileLines`.
 * An empty `lines` with a real `flagged` is a different answer from `null`, which means no checkout.
 */
function toSource(flagged: ApiMarker['flagged']): FlaggedSourceRecord | null {
  if (flagged === null) {
    return null
  }
  const first = flagged.firstLine
  return {
    lines: first === null ? [] : flagged.lines.map((text, i) => ({ n: first + i, text })),
    flagged: flagged.flaggedLine,
    fileLines: flagged.fileLength,
  }
}

/**
 * THE TWO FACTS A STATE CANNOT CARRY, or null when no settlement row recorded either.
 *
 * ApiMarker takes these from the last row that is NOT `proving` and sends null when there is none,
 * because `Settlement.note` writes false on every stage-boundary row — so a `false` read off the
 * last row of all says "we ran the test and it did not fail" about a marker whose RED has not run.
 * Both null therefore means "no settlement row", which is exactly `Semaphore`'s null, and one of
 * them being a real boolean means there is a row and the other genuinely was not recorded.
 */
function toFlags(marker: ApiMarker): SettlementFlags | null {
  if (marker.redVerified === null && marker.greenVerified === null) {
    return null
  }
  return { red: marker.redVerified, green: marker.greenVerified }
}

/**
 * The account, which is what is AFTER the first blank line of `summary.txt`.
 *
 * The server sends the file whole and says why (ApiMarker.summary): a file with no blank line IS
 * both halves, so splitting server-side sends the same paragraph twice under two names. The split
 * is the Java's — `indexOf("\n\n")`, first half is the list's headline, second half is this page's
 * account — and where there is no blank line the whole file is the account. Nothing is duplicated
 * by that here: the headline belongs to the markers table, which is a different screen.
 */
function accountOf(summary: string | null): string {
  if (summary === null) {
    return ''
  }
  const gap = summary.indexOf('\n\n')
  return gap < 0 ? summary.trim() : summary.slice(gap + 2).trim()
}

/**
 * AN EVENT'S NAME ON THE AGENT TAB, DERIVED — because nothing on the wire carries one yet.
 *
 * The record's own port notes say every event needs a stable id and that `rate()` posting a
 * POSITION is bug #3: the same answer was numbered one way by the agent tab, another by the whole
 * trace, and `feedback.jsonl` holds both kinds with nothing to tell them apart. This endpoint still
 * sends no id, and `AgentAnswer` cannot take null for one — so the id is built out of facts the
 * record does hold: the marker's slug, the agent, the kind and the record's own stamp. That is
 * stable under append, which is the property a position lacks, and it survives a marker settling
 * above it. Only an event with no readable `at` falls back to its position, and it says so.
 *
 * When the endpoint grows a real id this function is the one thing to delete.
 */
function idOf(markerId: string, agent: string, event: ApiAgentEvent, position: number): string {
  const when = event.at === null ? `unstamped-${position}` : String(event.at)
  return `${markerId}:${agent}:${event.kind}:${when}`
}

/**
 * ONE ROW OF THE RECORD, as the feed wants it.
 *
 * THE ID IS DERIVED THE SAME WAY THE AGENT TAB DERIVES IT, and that is the whole reason this takes
 * a marker id it could otherwise ignore. `index` is right there on the wire and would be shorter —
 * and it is a POSITION, which is bug #3 exactly: the id reaches `RateAnswer`, which posts it into
 * feedback.jsonl, and a corpus row pointing at "event 4137" points at a different event the moment
 * a line with no `at` sorts to the top of the run. Worse, this page would then spell the same
 * answer two ways — a number here and `slug:agent:asked:stamp` on the agent tab — which is the
 * failure the id exists to prevent, reproduced inside one screen.
 *
 * So both tabs build the name out of the same four recorded facts, and the row it writes is
 * self-locating: a reader of the corpus can find that answer from the marker's directory, the agent
 * and the stamp. It is still a workaround for an endpoint that carries no id, and the day one
 * arrives this and {@link idOf} both become `event.id`. Until then a sibling screen that chooses
 * `index` will write a different spelling of the same answer; that is an argument for the id, not
 * for the position.
 *
 * `at` of null becomes 0, which is what `num()` does with an unreadable stamp and what makes such a
 * row sort to the very top — visible, and therefore fixable, where dropping it would not be.
 */
/**
 * THE STAGES THE SERVER WALKED OFF THE PROGRAM, in the shape the strip draws.
 *
 * `/api/marker` sends `chain` from `Prove.stages()`, which walks the tree the runtime executes — so
 * the picture on this page is the program rather than a list somebody kept in step with it. The two
 * lists that used to say this, one of agent names and one of stage labels, are gone from the page.
 *
 * A stage that prompts nobody is dropped rather than drawn empty: the strip is a row of agents, and
 * a heading with no agents under it is a stage a reader cannot click into.
 */
function stagesOf(marker: ApiMarker): Stage[] {
  return (marker.chain ?? []).flatMap(stage => {
    const named = (role: string) => stage.steps.find(s => s.role === role)?.name
    const planner = named('planner')
    const doer = named('doer')
    const verifier = named('verifier')
    return planner === undefined || doer === undefined || verifier === undefined
      ? []
      : [{
          label: stage.title as Stage['label'],
          planner: planner as Stage['planner'],
          doer: doer as Stage['doer'],
          verifier: verifier as Stage['verifier'],
        }]
  })
}

function toFeedEvent(markerId: string, event: ApiEvent): TraceEventRecord {
  const { index, at, kind, marker, agent, state, phase, minutes, ...body } = event
  return {
    ...body,
    // The absolute index is the fallback for a row the record never stamped — it is a position, and
    // it is named as one so nobody reads it as an identity.
    // THE INDEX IS ALWAYS IN IT, because the rest is not unique. A stamp is a millisecond and one
    // agent writes several rows inside one: the connector records the system prompt and the task
    // back to back, so `agent:sent:<ms>` named BOTH of them. Sixty-one rows on one lane carried
    // fifty-six distinct keys. React tolerates that on a first paint and stops tolerating it the
    // moment the list grows — which it now does, on every event the stream pushes — reusing the
    // wrong `<details>` for the wrong event.
    //
    // The index is the record's own position and is stable across reads, so here it is an identity
    // and not a list offset; the stamp stays because it is what a reader recognises.
    id: `${markerId}:${agent ?? 'none'}:${kind}:${at === null ? 'unstamped' : at}:${index}`,
    at: at ?? 0,
    kind,
    marker,
    // The wire sends the name the record holds, which is not always one of the ten this build
    // knows: an agent renamed between runs still has a tab's worth of record.
    agent: agent as AgentName | null,
    ...(state === undefined ? {} : { state: state as MarkerState }),
    // `phase` IS LOWERCASE ON DISK and only `red`/`green` are drawn; anything else leaves the row
    // to render as its bare kind rather than lighting a lamp from a value nobody wrote.
    ...(phase === 'red' || phase === 'green' ? { phase } : {}),
    // A number or nothing, never 0: the estimator that answered in prose priced nothing, and 0
    // would say a person would have spent no time on this marker.
    ...(typeof minutes === 'number' ? { minutes } : {}),
  }
}

/* ------------------------------------------------------------------ small screen-level pieces */

const STRIP: Style = { display: 'flex', flexWrap: 'wrap', gap: '8px', padding: '10px 0' }

const BODY: Style = { padding: '0 24px 32px' }

const QUIET: Style = { color: 'var(--text-tertiary)', fontSize: '11px', margin: '8px 0' }

const LINK: Style = {
  color: 'var(--accent-primary)',
  textDecoration: 'none',
  fontSize: '12px',
  display: 'inline-block',
  margin: '6px 0',
}

const BUTTON: Style = {
  background: 'var(--bg-elevated)',
  color: 'var(--text-primary)',
  border: '1px solid var(--border-strong)',
  borderRadius: '6px',
  padding: '5px 12px',
  font: 'inherit',
  fontSize: '11px',
  cursor: 'pointer',
}

/**
 * THE LABEL NAMES THE ACTION, NOT THE STATE, so it inverts against `expanded` (2153).
 *
 * A rebuilder reading `expanded ? "clip" : "open"` as a state label flips it, and the control then
 * says the opposite of what it does.
 *
 * DEFAULT IS CLIPPED NOW, and the old reasoning for the opposite is worth keeping because it was
 * right about a different component: "reading a prove is reading the prompts and a fold costs a
 * click on every single thing the reader came for". A FOLD did — it showed a label and a byte
 * count. A CLIP does not: the opening of every body is on the page, and a click buys the rest.
 *
 * The choice stays in the URL because it is a fact about the page, not about this session: it is
 * what makes a folded page shareable, and it is what the Java's live fetch reused off
 * `location.search` so that appended events arrived folded on a folded page.
 */
function FoldToggle({ expanded, target }: { expanded: boolean; target: string }) {
  return (
    <a href={target} style={LINK}>
      {expanded ? 'clip the long parts' : 'open everything'}
    </a>
  )
}

/**
 * `/marker?k=…&a=…`, carrying the reader's choice when it is on.
 *
 * The parameter names the state being ASKED FOR, not the state being left — which is why the
 * toggle passes `!expanded` and every other caller passes `expanded` to keep the choice while
 * changing tab.
 */
function tabUrl(key: string, tab: string, full: boolean): string {
  const base = `/marker?k=${encodeURIComponent(key)}${tab === '' ? '' : `&a=${encodeURIComponent(tab)}`}`
  return href(full ? `${base}&open=1` : base)
}

/* ------------------------------------------------------------------ the summary tab */

/**
 * WHAT WAS CLAIMED, WHAT THE BUILDS SAID, WHAT WAS WRITTEN AND WHAT WOULD CHANGE — in the order a
 * person asks it (1853-1968).
 *
 * THE SEMAPHORE IS NEW HERE, and it is the point of the screen (`flags()` had exactly one caller,
 * the markers table). `red_verified`/`green_verified` are per-marker facts in settlements.jsonl and
 * they are precisely the two things a disposition cannot tell you: whether a test failed before the
 * patch, and whether the same test passed after it.
 *
 * BUILDOUTCOMES IS NOT DRAWN, and its absence is deliberate rather than forgotten. It takes
 * `{phase, passed, infra}` per build, which exists only as `built` rows in the trace; this endpoint
 * serves no `builds` array (the catalogue's §5.2 proposed one, ApiMarker does not implement it) and
 * the only other source is a scan of the whole run's record — seventeen windowed requests to find
 * two rows. Composing those three booleans out of `redVerified`/`greenVerified` would be inventing
 * them: a lit lamp says a test failed before the patch, it does not say whether a build produced a
 * test result at all, and `infra` is the third outcome the component exists to distinguish. The two
 * facts that ARE recorded are on the semaphore; the builds themselves are on the record tab, where
 * `BuildEvent` draws them from the rows that carry them.
 */
function SummaryTab({ marker, expanded }: { marker: ApiMarker; expanded: boolean }) {
  // A PROPOSAL EXISTS OR IT DOES NOT, and the state is where that is written. `pr-ready` and
  // `pr-rejected` are both proposals — one accepted upstream-ready, one this pipeline declined to
  // send — and in both the verdict text is a merge request rather than an argument about the code.
  const proposed = (marker.state ?? '').startsWith('verified/pr-')
  const state = marker.state as MarkerState | null
  const account = accountOf(marker.summary)
  const nothing =
    marker.verdictText === null &&
    marker.testCode === null &&
    marker.fixDiff === null &&
    account === ''

  return (
    <>
      {/* Only with a state: `reached` is derived from it, and there is nothing to derive from for
          a key neither the queue nor the record has heard of. */}
      {state === null ? null : <Semaphore flags={toFlags(marker)} state={state} />}
      <ClaimCard
        checker={marker.checker}
        file={marker.file}
        // A STRING, AS THE KEY HOLDS IT. The wire sends a number because that is what the third
        // field parsed to, and the component takes a string on purpose — a range, a blank or a `?`
        // in a marker file must render as what the record contains rather than as NaN.
        line={String(marker.line)}
        claimNote={marker.claimNote}
      />
      <FlaggedSource source={toSource(marker.flagged)} />
      {/* The interpreter's account, above the artefacts: everything below is evidence, and
          evidence is what you read after you know what you are looking at. */}
      <MarkerAccount text={account} />
      <TestArtifact
        // "" is the component's own absent state for both of these (it renders nothing when the
        // path is empty and the code blank, and says so when a path arrived without a body). The
        // null on the wire is preserved as that absence, not defaulted into a claim.
        path={marker.testPath ?? ''}
        code={marker.testCode ?? ''}
      />
      <FixDiff patch={marker.fixDiff ?? ''} />
      {/* Whole and unabridged, folded: a reason cut at two hundred characters is a reason nobody
          can check. An empty body renders no fold at all, which is the absence a reader should
          see rather than a fold that opens onto nothing. */}
      {/*
        THE SAME FIELD IS TWO DIFFERENT DOCUMENTS AND WAS LABELLED AS ONE. On a marker that settled
        `by-design` or `false-positive` this text is an argument — the case for not changing the code
        — and folding it away is right. On one that reached a proposal it is the MERGE REQUEST: a
        title and a body written to be sent to a stranger's repository. Calling that "the argument it
        settled on" and collapsing it hid the deliverable behind a label that described something
        else, on the markers where there IS a deliverable.
      */}
      <TextFold
        id={`verdict:${marker.id}`}
        label={proposed ? 'the merge request' : 'the argument it settled on'}
        body={marker.verdictText ?? ''}
        defaultOpen={expanded || proposed}
      />
      <TextFold
        id={`infra:${marker.id}`}
        label="why the prove could not finish"
        body={marker.infraReason ?? ''}
        defaultOpen={expanded}
      />
      {/* A tile only where there is something to count — a `0` tile is a measurement, and none of
          these three has been measured before the run reaches this marker. Same rule as
          `StateCounts`, which draws the human-equivalent total only once something is priced. */}
      {marker.attempts > 0 || marker.tookMinutes > 0 || marker.humanMinutes > 0 ? (
        <div style={STRIP}>
          {marker.attempts > 0 ? <Tally value={marker.attempts} label="attempt(s)" /> : null}
          {marker.tookMinutes > 0 ? (
            // EVERY ATTEMPT, NOT THIS ONE: `restart_prove` moves a lane to `dead/` and the next
            // attempt starts a fresh trace, so Pace adds the archives back before this is sent.
            <Tally value={duration(marker.tookMinutes * 60_000)} label="the machine took" />
          ) : null}
          {marker.humanMinutes > 0 ? (
            <Tally value={<HumanCost minutes={marker.humanMinutes > 0 ? marker.humanMinutes : null} />} label="human-equivalent" />
          ) : null}
        </div>
      ) : null}
      {nothing ? (
        // A WHOLE-PAGE EMPTY STATE, so the room is re-added here rather than in the primitive.
        <div style={{ padding: '48px 24px' }}>
        <EmptyNote>
          {state === 'proving'
            ? 'This prove is running and has settled nothing yet — the live tab shows what it is saying right now.'
            : state === null
              ? 'Neither the queue nor the record has heard of this key. Nothing has been proved for it, and nothing is going to be until it is queued.'
              : 'Nothing has been written for this marker yet: no account, no test, no patch and no argument. The record tab shows whether anything has happened at all.'}
        </EmptyNote>
        </div>
      ) : null}
    </>
  )
}

/* ------------------------------------------------------------------ one agent's tab */

/**
 * WHAT ONE AGENT SAID, AND WHAT IT DID BEFORE IT SAID IT (1945-2024).
 *
 * THE ORDER IS THE JAVA'S: the final answer, the superseded attempts under it newest-first, the
 * thinking, then the tool log. It reads as an argument with its workings below it.
 *
 * THINKING AND THE TOOL LOG ARE GATHERED BEFORE THE ANSWER IS BRANCHED ON, which is the whole
 * point of them (1992-1995). An agent seven tool calls into a job has answered nothing, and its
 * reasoning is the only account of what it is doing; a page that renders thinking only alongside an
 * answer goes blank for exactly as long as the interesting part lasts.
 *
 * ONE MATCHING RULE, and it is the server's now (ApiMarker.who): the record holds the same agent as
 * `fixer` on its answers and `agent:fixer` on the tool calls made for it, and the Java compensated
 * with `equals` on one and `endsWith` on the other — so a tab could show one agent's tool calls
 * beside another's answers, and `endsWith("fixer")` would also claim a `prefixer`.
 */
function AgentTab({
  marker,
  agent,
  events,
  back,
}: {
  marker: ApiMarker
  agent: AgentName
  events: ApiAgentEvent[]
  back: string
}) {
  const answers = events.filter(e => e.kind === 'asked')
  const thoughts = events.filter(e => e.kind === 'thought')
  const calls: ToolCall[] = events
    .filter(e => e.kind === 'tool')
    .map(e => ({
      tool: e.tool ?? '',
      // IN FULL, ALWAYS. The argument to `write_file` IS the test, and the old 110-character cut
      // showed the path and hid the only thing worth reading.
      arguments: e.arguments ?? '',
      result: e.result ?? '',
    }))
  const turns = thoughts.map((e, i) => ({
    id: idOf(marker.id, agent, e, i),
    text: e.text ?? '',
  }))
  const last = answers[answers.length - 1]

  return (
    <>
      {last === undefined ? (
        // ZERO CALLS AND NO THOUGHTS IS "HAS NOT RUN"; anything else is "working". The component
        // draws the distinction and the caveat that goes with it — "has not run" is a claim about
        // the record, and a trace still being written reads like one that never started.
        <AgentPending agent={agent} calls={calls.length} hasThinking={turns.length > 0} />
      ) : (
        <AgentAnswer
          agent={agent}
          reply={last.reply ?? ''}
          prompt={last.prompt ?? ''}
          attempt={answers.length}
          attempts={answers.length}
          eventId={idOf(marker.id, agent, last, answers.length - 1)}
          back={back}
        />
      )}
      {/* NEWEST-FIRST BELOW THE FINAL ANSWER, with the record's own ascending attempt numbers —
          both halves kept deliberately (the attempt just before the final one is the one that
          explains it, and a number counted down the rendered list would disagree with the trace,
          the settlement and the archive directory). */}
      {answers
        .slice(0, -1)
        .map((earlier, n) => ({ earlier, attempt: n + 1 }))
        .reverse()
        .map(({ earlier, attempt }) => (
          <SupersededAttempt
            key={attempt}
            attempt={attempt}
            reply={earlier.reply ?? ''}
            prompt={earlier.prompt ?? ''}
          />
        ))}
      {/* Record order in, newest-first out: the component reverses, so that the id keeps saying
          which thought came first. */}
      <Thinking turns={turns} />
      <ToolLog calls={calls} answered={last !== undefined} />
    </>
  )
}

/* ------------------------------------------------------------------ the prompts tab */

/**
 * WHAT EACH AGENT WAS ACTUALLY TOLD WHEN THIS MARKER WAS PROVED (`promptsFor()` 1467-1522).
 *
 * RECOVERED, NOT RECORDED: `asked` carries the whole prompt and the task is appended after a
 * `\n\n---\n\n` separator, so everything before the separator IS the instruction that agent was
 * running under. The first `asked` per agent, as the Java's `putIfAbsent` took it — the instruction
 * the stage began under, not the one a second attempt repeated.
 *
 * THE DRIFT ALARM AND ITS REPROVE BUTTON ARE NOT HERE, and that is a decision worth stating rather
 * than a component someone forgot. The alarm answers "has this prompt been edited since?", which
 * needs what the agent WOULD be told now compared under `Prompts.same()` normalisation. Nothing
 * serves that comparison for a marker: `/api/settings/prompts` sends each agent's effective prompt
 * and its own `differs` — effective against built-in, a different question — and the file that
 * computes it says in as many words that a client rewriting the rule in JavaScript would drift from
 * this page. So the comparison is not made rather than made twice, and `/reprove` — the only
 * destructive control in the whole UI — is not offered on the strength of a guess. What is here is
 * the fact the record does hold: the instruction each agent ran under, in full.
 */
function PromptsTab({
  used,
  expanded,
}: {
  used: { agent: string; instruction: string }[]
  expanded: boolean
}) {
  if (used.length === 0) {
    return <EmptyNote>Nothing has been asked for this marker yet.</EmptyNote>
  }
  return (
    <>
      <p style={QUIET}>
        {used.length} agent(s) ran. Whether any of these instructions has been edited since is not
        shown: the comparison belongs to the side that owns the normalisation rule, and nothing
        serves it for one marker — see the note in this file.
      </p>
      {used.map(one => (
        <TextFold
          key={one.agent}
          id={`prompt:${one.agent}`}
          label={`what ${one.agent} was told here`}
          body={one.instruction}
          defaultOpen={expanded}
        />
      ))}
    </>
  )
}
/* ------------------------------------------------------------------ this marker's record */

/** `GET /api/marker/record?k=` — every line of this marker's own lane, and how many there are. */
type ApiMarkerRecord = {
  marker: string
  /** Lines of the lane file this document carries — where a subscriber resumes. */
  lines: number
  events: ApiEvent[]
}

/**
 * THIS MARKER'S RECORD, WHICH IS ONE FILE.
 *
 * <p>This used to be built out of `/api/events`: a window over the RUN — five hundred events of
 * whatever the pool happened to be doing — filtered afterwards for the rows belonging to this
 * marker. It read the newest window first, kept the handful that matched, and offered a button to
 * read further back, and the line under the feed said "53 of them are this marker's" because that
 * was the honest description of what it had. A marker proved an hour ago had its record scattered
 * behind windows nobody would page through.
 *
 * None of it was necessary. A prove is one process per marker writing one file, and that file IS the
 * marker's record. There is no window, nothing to page, nothing to filter, and no sentence about the
 * run to put under the feed.
 *
 * OLDEST AT THE TOP, AND APPENDED TO — never replaced and never reversed. Replacing the list shuts
 * every fold the reader opened and throws away where they were.
 */
function RecordTab({
  markerKey,
  markerId,
  settled,
  expanded,
  back,
  foldTarget,
}: {
  markerKey: string
  /** The marker's slug, for the event ids — see {@link toFeedEvent}. */
  markerId: string
  settled: boolean
  expanded: boolean
  back: string
  foldTarget: string
}) {
  const [events, setEvents] = useState<ApiEvent[] | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const running = useRef(true)

  useEffect(() => {
    running.current = true
    let stop = () => undefined as void
    void (async () => {
      try {
        const doc = await read<ApiMarkerRecord>(
          `/api/marker/record?k=${encodeURIComponent(markerKey)}`,
        )
        if (!running.current) {
          return
        }
        setEvents(doc.events)
        setFailed(null)
        // A SETTLED MARKER'S RECORD DOES NOT GROW, so nothing is opened for it.
        if (settled) {
          return
        }
        // AND THE FRAMES ARE THE CONTENT NOW, not a nudge. Both ends count lines of the same file —
        // this document says how many it carries and the stream numbers its frames the same way —
        // so a frame can be appended as it arrives. While the feed was a run-wide window and the
        // stream was a lane tail, they were two coordinate systems and the only safe thing a frame
        // could say was "something moved, go and fetch your own delta".
        stop = subscribe(
          `/api/stream?k=${encodeURIComponent(markerKey)}&have=${doc.lines}`,
          {
            trace: data => {
              if (!running.current) {
                return
              }
              setEvents(had => (had === null ? [data as ApiEvent] : [...had, data as ApiEvent]))
            },
          },
        )
      } catch (unreachable) {
        if (running.current) {
          setFailed(unreachable instanceof Error ? unreachable.message : String(unreachable))
        }
      }
    })()
    return () => {
      running.current = false
      stop()
    }
  }, [markerKey, settled])

  if (events === null) {
    return (
      <>
        <FoldToggle expanded={expanded} target={foldTarget} />
        {failed === null ? (
          <EmptyNote>reading this marker&rsquo;s record&hellip;</EmptyNote>
        ) : (
          <EmptyNote>the record did not come back &mdash; {failed}</EmptyNote>
        )}
      </>
    )
  }

  return (
    <>
      <FoldToggle expanded={expanded} target={foldTarget} />
      {events.length === 0 ? (
        // A CLAIM THIS PAGE CAN NOW ACTUALLY MAKE. It holds the whole file, so an empty one means
        // nothing has been traced — where the windowed version could only say what it had not seen.
        <EmptyNote>Nothing has been traced for this marker yet.</EmptyNote>
      ) : (
        <EventFeed
          events={events.map(e => toFeedEvent(markerId, e))}
          order="oldest-first"
          // One marker's page: every row belongs to it, and a crumb saying so on every row would be
          // the same word forty times.
          showMarker={false}
          markerHrefFor={e =>
            e.marker === undefined || e.marker === null
              ? null
              : href(`/marker?k=${encodeURIComponent(e.marker)}`)
          }
          defaultOpen={expanded}
          feedbackBack={back}
          cursor={events.length}
        />
      )}
      <p style={QUIET}>
        {events.length} event(s). That is the whole record.
      </p>
      {failed === null ? null : <p style={QUIET}>the last read did not come back &mdash; {failed}</p>}
    </>
  )
}

/* ------------------------------------------------------------------ the screen */

function MarkerScreen() {
  const params = useSearchParams()
  const key = params.get('k') ?? ''
  const tab = params.get('a') ?? ''
  // PRESENT MEANS EVERYTHING IN FULL, and the default is CLIPPED. It was the other way round, on
  // the reasoning that "reading a prove is reading the prompts and a fold costs a click on every
  // single thing the reader came for". That was true of a fold, which showed a label and a byte
  // count; it is not true of a clip, which shows the opening of every body and costs a click only
  // for the rest. Left as it was, a lane opened with a 9,626-character system prompt drawn whole
  // and everything after it a scroll away.
  //
  // The parameter's presence is the fact, not its value: `?open=1` and `?open=` both mean the
  // reader asked for everything in full.
  const expanded = params.get('open') !== null

  const [marker, setMarker] = useState<ApiMarker | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [findings, setFindings] = useState(0)
  const [agentEvents, setAgentEvents] = useState<ApiAgentEvent[] | null>(null)
  const [used, setUsed] = useState<{ agent: string; instruction: string }[] | null>(null)

  /**
   * WHETHER THIS MARKER IS FINISHED, IN A REF RATHER THAN IN THE DEPENDENCY LIST.
   *
   * The agent tab below has to stop polling when the marker settles, and depending on the marker
   * document to learn that would restart its whole effect on every fifteen-second refresh — tearing
   * down the timer it had just set and refetching an agent's entire transcript, prompts and all,
   * because a field it does not read changed. The fact is read at the end of a tick, which is the
   * only moment it decides anything.
   */
  const settledRef = useRef(false)

  /**
   * THE COUNTS COME BEFORE THE TAB IS DISPATCHED ON, which is a rule with an incident behind it
   * (the comment at 1789-1791): an earlier arrangement branched on `?a=` first and two of the tabs
   * called the one-argument `tabs()`, so they rendered the whole chain with every count missing and
   * every stage dimmed — the page said "nothing ever ran here" about a marker that had run five
   * agents. Every tab below loads this document, so the strip is never drawn from nothing.
   */
  useEffect(() => {
    if (key === '') {
      return
    }
    let live = true
    let timer: ReturnType<typeof setTimeout> | undefined
    const tick = async () => {
      try {
        const next = await read<ApiMarker>(`/api/marker?k=${encodeURIComponent(key)}`)
        if (!live) {
          return
        }
        setMarker(next)
        setFailed(null)
        // DO NOT POLL A SETTLED THING. A disposition is the end of this marker's story: its state,
        // its counts and its artefacts cannot change again. `infra`, `queued`, `proving` and
        // `interrupted` all can — the pool may take an infra marker up again — so they keep asking.
        settledRef.current = next.state !== null && isDisposition(next.state)
        if (settledRef.current) {
          return
        }
      } catch (unreachable) {
        if (!live) {
          return
        }
        setFailed(unreachable instanceof Error ? unreachable.message : String(unreachable))
      }
      if (live) {
        timer = setTimeout(() => void tick(), 15_000)
      }
    }
    void tick()
    return () => {
      live = false
      clearTimeout(timer)
    }
  }, [key])

  /**
   * The header's badge, from the one route that serves it to every screen at once.
   *
   * `/api/marker` carries no `openFindings` and `/api/events` does — but a header that took its
   * count from whichever document the current tab happened to load is a header two tabs can
   * disagree about, and this one is in the same corner on all five. Asked once, on load, as the
   * Java's every-page-render did. Until it answers the count is 0, and 0 draws no badge at all:
   * silence is the honest render of a number nobody has yet.
   */
  useEffect(() => {
    let live = true
    void read<ApiBadges>('/api/badges')
      .then(badges => {
        if (live) {
          setFindings(badges.findings)
        }
      })
      .catch(() => {
        // A header that cannot count findings still has a page under it.
      })
    return () => {
      live = false
    }
  }, [])

  // ONE AGENT'S TAB. Its own document, because it changes with every tool call the agent makes
  // while the marker's own changes only when a stage concludes — and it carries every prompt and
  // reply in full, which is not a payload to refetch to notice that a state moved.
  const isAgentTab = key !== '' && tab !== '' && tab !== 'live' && tab !== 'prompts' && tab !== 'trace'
  useEffect(() => {
    // Cleared first: switching from one agent to another must not show the previous agent's answer
    // under the new one's name while the request is in flight.
    setAgentEvents(null)
    if (!isAgentTab) {
      return
    }
    let live = true
    let timer: ReturnType<typeof setTimeout> | undefined
    const tick = async () => {
      try {
        const events = await read<ApiAgentEvent[]>(
          `/api/marker/agent?k=${encodeURIComponent(key)}&a=${encodeURIComponent(tab)}`,
        )
        if (!live) {
          return
        }
        setAgentEvents(events)
        setFailed(null)
      } catch (unreachable) {
        if (!live) {
          return
        }
        setFailed(unreachable instanceof Error ? unreachable.message : String(unreachable))
      }
      // An agent mid-answer is the case this tab exists for: its thinking and its tool calls arrive
      // while it says nothing at all. The marker poll above stops at a disposition and this follows
      // it, because a settled marker's agents have all finished speaking.
      if (live && !settledRef.current) {
        timer = setTimeout(() => void tick(), 15_000)
      }
    }
    void tick()
    return () => {
      live = false
      clearTimeout(timer)
    }
  }, [key, tab, isAgentTab])

  /**
   * THE PROMPTS TAB, ONE DOCUMENT PER AGENT THAT ACTUALLY RAN. An agent with no runs was told
   * nothing here, and asking for its events would be ten requests to learn that ten times.
   *
   * The dependency is the LIST OF NAMES and not the marker document, for the reason the ref above
   * gives and doubled here: this effect is ten requests carrying every prompt and reply in full,
   * and re-running it every fifteen seconds because a count moved would refetch megabytes to redraw
   * text that cannot change. The names change when an agent starts running, which is when the tab
   * genuinely has something new to show.
   */
  const ran = marker === null ? '' : marker.agents.filter(a => a.runs > 0).map(a => a.agent).join(',')
  const loaded = marker !== null
  useEffect(() => {
    if (key === '' || tab !== 'prompts' || !loaded) {
      return
    }
    let live = true
    if (ran === '') {
      // No agent has answered. That is an answer, and it is not "still reading".
      setUsed([])
      return
    }
    void Promise.all(
      ran.split(',').map(async agent => {
        const events = await read<ApiAgentEvent[]>(
          `/api/marker/agent?k=${encodeURIComponent(key)}&a=${encodeURIComponent(agent)}`,
        )
        // The FIRST `asked`, as `putIfAbsent` took it: the instruction the stage began under.
        const first = events.find(e => e.kind === 'asked' && e.prompt !== null)
        const prompt = first?.prompt ?? ''
        const cut = prompt.indexOf('\n\n---\n\n')
        return { agent, instruction: cut < 0 ? prompt : prompt.slice(0, cut) }
      }),
    )
      .then(all => {
        if (live) {
          setUsed(all.filter(one => one.instruction !== ''))
        }
      })
      .catch((e: unknown) => {
        if (live) {
          setFailed(e instanceof Error ? e.message : String(e))
        }
      })
    return () => {
      live = false
    }
  }, [key, tab, ran, loaded])

  if (key === '') {
    return (
      <>
        <PageHeader
          title="a marker"
          subtitle="no marker was asked for"
          back={{ label: 'all markers', href: href('/') }}
          findingsOpen={findings}
        />
        <EmptyNote>
          This page shows one marker, named by the <code>?k=</code> in its address. Pick one from the
          list.
        </EmptyNote>
      </>
    )
  }

  if (marker === null) {
    return (
      <>
        <PageHeader
          title={key.slice(key.lastIndexOf('/') + 1)}
          subtitle={failed === null ? 'reading the marker…' : 'could not read the marker'}
          back={{ label: 'all markers', href: href('/') }}
          findingsOpen={findings}
        />
        {failed === null ? null : <EmptyNote>{failed}</EmptyNote>}
      </>
    )
  }

  const state = marker.state as MarkerState | null
  // THE SAME TRUNCATION THE CRUMB USES — everything after the last SLASH, which for
  // `repo|src/main/java/Foo.java|82|CHECKER` is `Foo.java|82|CHECKER` and is neither the file name
  // nor the marker's slug. It looks like an accident and is not: `MarkerCrumb` prints the same
  // expression, so the link a reader clicks and the heading they land on say the same words.
  const title = key.slice(key.lastIndexOf('/') + 1)
  const runs: Partial<Record<AgentName, number>> = Object.fromEntries(
    marker.agents.map(a => [a.agent, a.runs] as const),
  )
  // An agent the chain no longer lists — this run's trace holds `fix-skeptic` answers from before
  // that agent was renamed — still has a tab's worth of record, so the cast is deliberate: the tab
  // renders, and the strip simply has no chip to light for it.
  const current = tab === 'live' || tab === 'prompts' || tab === 'trace' ? tab : (tab as AgentName | '')
  const settled = state !== null && isDisposition(state)

  return (
    <>
      <PageHeader
        title={title}
        // A NODE, NOT A STRING. `head()` appended the subtitle as raw HTML because callers pushed a
        // state pill through it; composing it here is what closes that hole for good.
        subtitle={
          state === null ? (
            marker.key
          ) : (
            <>
              {marker.key}
              {' · '}
              <StateBadge state={state} />
            </>
          )
        }
        back={{ label: 'all markers', href: href('/') }}
        findingsOpen={findings}
      />
      <div style={BODY}>
        <ChainStrip
          stages={stagesOf(marker)}
          markerKey={marker.key}
          current={current}
          runs={runs}
        />
        {failed === null ? null : <p style={QUIET}>the last poll did not come back &mdash; {failed}</p>}
        {tab === '' ? <SummaryTab marker={marker} expanded={expanded} /> : null}
        {tab === 'live' ? (
          <LiveStream
            markerKey={marker.key}
            /*
             * HOW TO ASK IS THE SCREEN'S, AND THIS IS WHY: a `/api/live?k=` written inside the
             * component works standalone and 404s the moment a shell mounts this zone under a
             * prefix. `read` applies the prefix in the one place that knows it. The adaptation is
             * here too — the wire calls the agent a string and the component wants the union.
             */
            load={(k, signal) =>
              read<ApiLiveOne>(`/api/live?k=${encodeURIComponent(k)}`, { signal }).then(
                (one): LiveView => ({
                  marker: one.marker,
                  slug: one.slug,
                  settled: one.settled,
                  agent: one.agent as AgentName | null,
                  at: one.at,
                  text: one.text,
                  truncated: one.truncated,
                  serverNow: one.serverNow,
                }),
              )
            }
          />
        ) : null}
        {tab === 'prompts' ? (
          used === null ? (
            <EmptyNote>Reading what each agent was told&hellip;</EmptyNote>
          ) : (
            <>
              <FoldToggle expanded={expanded} target={tabUrl(marker.key, 'prompts', !expanded)} />
              <PromptsTab used={used} expanded={expanded} />
            </>
          )
        ) : null}
        {tab === 'trace' ? (
          <RecordTab
            markerKey={marker.key}
            markerId={marker.id}
            settled={settled}
            expanded={expanded}
            back={tabUrl(marker.key, 'trace', expanded)}
            foldTarget={tabUrl(marker.key, 'trace', !expanded)}
          />
        ) : null}
        {isAgentTab ? (
          agentEvents === null ? (
            <EmptyNote>Reading what {tab} said&hellip;</EmptyNote>
          ) : (
            <AgentTab
              marker={marker}
              agent={tab as AgentName}
              events={agentEvents}
              back={tabUrl(marker.key, tab, expanded)}
            />
          )
        ) : null}
      </div>
    </>
  )
}

/**
 * THE QUERY STRING IS READ IN A CHILD, and the boundary is not decoration.
 *
 * This zone is statically exported: there is no server to render against, so `useSearchParams()`
 * has nothing to answer with until the page is in a browser, and Next requires the component that
 * asks to sit under a `Suspense` boundary. Without it the export fails at build — which is the good
 * failure, but not one to ship.
 */
export default function MarkerPage() {
  return (
    <Suspense
      fallback={
        <PageHeader
          title="a marker"
          subtitle="reading the address…"
          back={{ label: 'all markers', href: href('/') }}
          findingsOpen={0}
        />
      }
    >
      <MarkerScreen />
    </Suspense>
  )
}
