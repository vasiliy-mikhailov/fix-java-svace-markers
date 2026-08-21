'use client'

import { Suspense, useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'next/navigation'
import { EmptyNote, EventFeed, Loaded, PageHeader, type Style, type TraceEventRecord } from '@fsm/ui'
import { DISPOSITIONS, UNSETTLED, type AgentName, type MarkerState } from '@fsm/types'

import { href, read } from '../../lib/api'

/**
 * THE WHOLE TRACE — every prompt, thought, tool call, build and settlement across every marker,
 * merged into one time-ordered story (Java `events()` with an empty key, Dashboard 2170-2199, routed
 * at 442-446).
 *
 * <p>It answers a different question from the marker page. That one asks "why did this settle so",
 * and is read after the fact; this one asks "what is this thing doing", and is read while a run is in
 * flight. Everything below follows from that: newest at the bottom, appended rather than replaced,
 * and the newest window loaded first.
 *
 * <p>OLDEST FIRST, NEWEST AT THE BOTTOM. The brief for this screen says "newest first" and the code
 * it was written from says the opposite — `events()` sorts ascending (2127) and the browser inserted
 * arriving events with `beforeend` (LIVE, line 266). Appending is the whole point: replacing the list,
 * or reversing it, closes every fold the reader opened and throws away where they were. The behaviour
 * is ported, not the description.
 */

/** The record's own vocabulary, straight off `/api/events` (ApiTrace.event). */
type ApiEvent = {
  /** Position in the whole run's ordering. The record has no id; see {@link toRecord}. */
  index: number
  /** Epoch millis, or null when the line's stamp could not be read (ApiTrace.number). */
  at: number | null
  kind: string
  marker: string | null
  agent: string | null
  prompt?: string
  reply?: string
  text?: string
  tool?: string
  /** `arguments` on the wire; `@fsm/types` spells the same field `args`. See {@link toRecord}. */
  arguments?: string
  result?: string
  phase?: string
  passed?: boolean
  infra?: boolean
  summary?: string
  note?: string
  state?: string
  because?: string
  /** Null when the estimator never produced a figure — never 0. See {@link toRecord}. */
  minutes?: number | null
  itemisation?: string
  cause?: string
  /** Served, and this screen has no slot for it. See {@link toRecord}. */
  stack?: string
}

type TracePage = {
  /** The record's TRUE length, whatever this response happened to carry. */
  cursor: number
  from: number
  returned: number
  more: boolean
  openFindings: number
  events: ApiEvent[]
}

/**
 * The server's page size (`ApiTrace.WINDOW`), as a HINT and not a contract.
 *
 * <p>Used only to guess where the last window starts. Every number this page shows comes from the
 * response — `from`, `returned`, `cursor` — so a server that windows differently makes this page load
 * a little more or a little less, and never makes it lie about what it is holding.
 */
const WINDOW = 500

/**
 * Past the end of any record this program can write, so the response is an empty tail carrying the
 * true `cursor` — the documented way to ask how long the record is (ApiTrace.trace: "A `from` past
 * the end is an empty tail rather than an error"). Exactly `Integer.MAX_VALUE`: Dashboard casts the
 * parsed value to `int` (line 403), and a larger number wraps NEGATIVE, which would quietly fetch the
 * OLDEST window while looking like a request for the newest.
 */
const PAST_THE_END = 2147483647

const BACK = { label: 'all markers', href: href('/') }

/** Every word a settlement may carry. Anything else is not a state this run can be in. */
const STATES: readonly string[] = [...DISPOSITIONS, ...UNSETTLED]

function knownState(word: string | undefined): MarkerState | null {
  return word !== undefined && STATES.includes(word) ? (word as MarkerState) : null
}

function said(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure)
}

/**
 * ONE WIRE EVENT AS THE FEED WANTS IT — the adapter, and the reason this file exists.
 *
 * <p>Four reconciliations, each one a place where the payload and the component were read out of the
 * same Java and still ended up with different names or different absences:
 *
 * <ul>
 *   <li><b>`index` against `id`.</b> The component keys rows and folds and ratings by a stable `id`;
 *       the payload has none, because nothing in the record identifies an event. `index` is the
 *       server's own numbering across the WHOLE run and it is absolute — ApiTrace keeps it that way
 *       precisely so a client paging backwards gets the same number for the same event — so it is the
 *       only identity available, and it is passed through untouched. NOT renumbered within the window
 *       this page holds: that renumbering is bug #3 itself, the one that put three different `event`
 *       numbers for one answer into `feedback.jsonl` with nothing to tell them apart.</li>
 *   <li><b>`at` may be null.</b> A line whose stamp could not be read gets `null`, and null is not a
 *       moment in 1970. `NaN` carries "there is no stamp here" into a field typed `number`, and
 *       `EventFeed`'s own `stamp()` answers 0 for anything non-finite — which sorts it to the top of
 *       the run, exactly where the Java's `num()` (2355) put it.
 *
 *       <p>CORRECTED: an earlier note here said no event carries `at` at all, having looked at the
 *       ROOT `trace.jsonl`, which is empty. The record is per marker — `Dashboard.lines()` reads
 *       `m/*​/trace.jsonl` and every line there is stamped. So a null stamp is the ODD path, not the
 *       ordinary one, and the handling above is a guard rather than the normal case. Worth keeping,
 *       and worth not believing the reason it was written for.</li>
 *   <li><b>`agent` is whatever was written.</b> The record holds `agent:reproducer` on most rows and
 *       a bare `reproducer` on others, and one name in it (`fix-skeptic`) is not in `AGENTS` at all.
 *       The page prints the field, as the Java did — tidying the prefix here would make the trace
 *       disagree with the file it is rendering, and nothing keys off the name.</li>
 *   <li><b>`arguments` against `args`.</b> Both spellings exist; the payload sends `arguments`, so
 *       that is what is passed. It is ordinary text with real newlines — `field()` (2697-2701) has
 *       already peeled one layer of JSON string off it, and parsing it again turns every backslash in
 *       a Windows path into an escape.</li>
 * </ul>
 *
 * <p>AND THREE ABSENCES THAT STAY ABSENT. `minutes: null` means the estimator never produced a
 * figure; 0 would say a person would have spent no time on this marker, which is a different claim, a
 * false one, and the one that gets summed over a run. An unreadable `phase` means this is not a build
 * anything can label — the Java's build case would have called it `failed` from two absent booleans,
 * the strongest possible claim from the least possible information, about the one thing this feed must
 * not get wrong. A `state` that is not one of the eleven would index `StateBadge`'s tone table with
 * nothing. In all three the field is left OFF the record and `TraceEvent` renders the row as its bare
 * kind, which is what a row that cannot say what it is deserves.
 */
function toRecord(event: ApiEvent): TraceEventRecord {
  const base = {
    id: String(event.index),
    at: event.at ?? Number.NaN,
    kind: event.kind,
    agent: event.agent === null ? null : (event.agent as AgentName),
    marker: event.marker,
  }
  switch (event.kind) {
    case 'asked':
      // Both in full. The corpus is what prompt tuning replays; a trace that abbreviates either half
      // is a trace nothing can be trained from.
      return { ...base, prompt: event.prompt ?? '', reply: event.reply ?? '' }
    case 'thought':
      return { ...base, text: event.text ?? '' }
    case 'tool':
      return {
        ...base,
        tool: event.tool ?? '',
        arguments: event.arguments ?? '',
        result: event.result ?? '',
      }
    case 'built': {
      // LOWERCASE ON DISK. `marker()` compares `field(e,"phase").equals("red")` (1865) and the page
      // only uppercases for display; three earlier passes typed this `"RED"` and would have lit the
      // wrong lamp. Case is normalised because `one()` printed whatever it was handed; the VALUE is
      // not guessed at.
      const phase = event.phase?.toLowerCase()
      return {
        ...base,
        ...(phase === 'red' || phase === 'green' ? { phase } : {}),
        // Real JSON booleans, never the string "true" — that is what `field()`'s unquoted branch
        // (2707-2722) was added for, and why `red_verified` read empty for every marker that had
        // genuinely gone red. `infra` beats `passed` in the component, which is where that judgement
        // belongs.
        ...(typeof event.passed === 'boolean' ? { passed: event.passed } : {}),
        ...(typeof event.infra === 'boolean' ? { infra: event.infra } : {}),
        summary: event.summary ?? '',
      }
    }
    case 'progress':
      return { ...base, note: event.note ?? '' }
    case 'settled': {
      const state = knownState(event.state)
      return state === null ? base : { ...base, state, because: event.because ?? '' }
    }
    case 'priced':
      return typeof event.minutes === 'number'
        ? { ...base, minutes: event.minutes, itemisation: event.itemisation ?? '' }
        : base
    case 'failed':
      // `stack` arrives and is dropped: `FailedEvent` draws a cause, and folding the stack into that
      // string would have a reader read the whole thing as the cause line. The record still serves it
      // to anything that grows a slot for it.
      return { ...base, cause: event.cause ?? '' }
    default:
      // AN UNKNOWN KIND KEEPS ITS COMMON FIELDS AND GETS NO OTHERS, and `TraceEvent` renders it as its
      // own bare name. The record is append-only and will grow kinds — `@fsm/types` already knows a
      // ninth (`system`) the Java never had a case for. Refusing the row would make every reader of an
      // older build blind to a whole run the day a new kind is written.
      return base
  }
}

/**
 * WHICH MARKER PAGE A ROW POINTS AT, or nothing.
 *
 * <p>The whole key travels in the payload and the crumb shows the tail after the last SLASH — for
 * `repo|src/main/java/Foo.java|82|CHECKER` that reads `Foo.java|82|CHECKER`, which is neither the file
 * name nor the slug. That truncation is `MarkerCrumb`'s, and the marker page's own title (1815) uses
 * the same expression, so the link you click and the heading you land on say the same words.
 *
 * <p>Null for a row that belongs to no marker page. The Java built `/marker?k=<the field>`
 * unconditionally, so a row the supervisor wrote — `marker` is the literal `overwatch` there —
 * produced a link to a marker that cannot exist, on a page whose whole job is to be trustworthy about
 * the record.
 */
function markerHrefFor(event: TraceEventRecord): string | null {
  const marker = event.marker ?? ''
  if (marker.length === 0 || marker === 'overwatch') {
    return null
  }
  // The Java's URL exactly (2183-2187). Links from the shell, from the supervisor's findings and from
  // anybody's bookmarks point at this spelling; `href()` adds only the prefix the shell mounted us at.
  return href(`/marker?k=${encodeURIComponent(marker)}`)
}

/** Everything this page holds of the record, and where in the record it sits. */
type Held = {
  /** Absolute index of the first event held. Zero when the whole record is on the page. */
  from: number
  /** Contiguous and ascending, `from` upwards. */
  events: ApiEvent[]
  /** The record's true length, as the last response reported it. */
  cursor: number
  openFindings: number
}

function through(held: Held): number {
  return held.from + held.events.length
}

/**
 * THE NEWEST WINDOW, WHICH IS THE ONE THIS SCREEN IS FOR.
 *
 * <p>Two requests, because the record does not say how long it is until it is asked. The first asks
 * past the end and reads `cursor` off an empty answer; the second asks for the window ending there.
 *
 * <p>Why the newest and not the first: this run's trace is 61 MB as JSON and the page it replaces is
 * 84 MB, which is not a page load — it is a document larger than the browser will hold, sent so a
 * reader can look at the last twenty things that happened. `ApiTrace` windows for that reason and says
 * a caller that wants more asks for it. The reader is told exactly how much is not on the page, and
 * can ask; what this page must never do is show part of a record silently, because a page showing part
 * of a record reads as the record.
 */
async function newestWindow(): Promise<TracePage> {
  const end = await read<TracePage>(`/api/events?from=${PAST_THE_END}&limit=1`)
  const start = Math.max(0, end.cursor - WINDOW)
  return read<TracePage>(`/api/events?from=${start}`)
}

const FOLD_LINK: Style = {
  display: 'inline-block',
  margin: '10px 24px 0',
  fontSize: '12px',
  color: 'var(--accent-primary)',
  textDecoration: 'none',
}

const EARLIER: Style = { padding: '10px 24px 0', fontSize: '12px', color: 'var(--text-tertiary)' }

const ASK: Style = {
  background: 'none',
  border: 'none',
  padding: 0,
  font: 'inherit',
  fontSize: '12px',
  color: 'var(--accent-primary)',
  cursor: 'pointer',
}

const STALE: Style = { color: 'var(--state-infra)' }

type FoldToggleProps = {
  expanded: boolean
  href: string
  onToggle: () => void
}

/**
 * THE LABEL NAMES THE ACTION, NOT THE STATE, so it inverts against `expanded` (2153). A rebuilder
 * reading it as a state label flips it, which is the single easiest thing on this screen to get
 * backwards.
 *
 * <p>Default is OPEN: `open()` (2407-2409) returns true when `fold` is ABSENT, on the reasoning that a
 * fold saves scrolling and costs a click on every single thing a reader came to look at — reading a
 * prove is reading the prompts. `?fold=1` collapses them for anyone skimming.
 *
 * <p>It stays a real anchor with a real href so it can be copied, bookmarked and opened in a new tab,
 * and it toggles in place rather than reloading eight megabytes.
 */
function FoldToggle({ expanded, href: to, onToggle }: FoldToggleProps) {
  return (
    <a
      href={to}
      style={FOLD_LINK}
      onClick={(clicked) => {
        clicked.preventDefault()
        onToggle()
      }}
    >
      {expanded ? 'fold the long parts' : 'open everything'}
    </a>
  )
}

function TraceScreen() {
  const params = useSearchParams()
  // PRESENT MEANS FOLD, and `?fold=` with no value is not present as far as `open()` is concerned:
  // it reads `query(e,"fold").isEmpty()`, and an empty value is empty. Read once — from here on the
  // toggle is the state and the URL is kept in step with it.
  const [expanded, setExpanded] = useState(() => {
    const fold = params.get('fold')
    return fold === null || fold === ''
  })
  const [held, setHeld] = useState<Held | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [tailFailed, setTailFailed] = useState<string | null>(null)
  const [reading, setReading] = useState(false)

  // The tail poll below reads the current window without being rebuilt every time one arrives: an
  // interval torn down and remade on each append is an interval that never fires on a busy run.
  const heldRef = useRef<Held | null>(null)
  useEffect(() => {
    heldRef.current = held
  }, [held])

  const show = useCallback((page: TracePage) => {
    setHeld({
      from: page.from,
      events: page.events,
      cursor: page.cursor,
      openFindings: page.openFindings,
    })
    setFailed(null)
    setTailFailed(null)
  }, [])

  useEffect(() => {
    let live = true
    newestWindow()
      .then((page) => {
        if (live) {
          show(page)
        }
      })
      .catch((failure: unknown) => {
        if (live) {
          setFailed(said(failure))
        }
      })
    return () => {
      live = false
    }
  }, [show])

  /**
   * THE LIVE TAIL — only what is new, every two seconds.
   *
   * <p>Two seconds is the Java's own cadence: `/events` (576-607) compared the line counts on that
   * tick and pushed when either moved, and the page fetched the fragment from its cursor. The request
   * here is the same fragment request — `from` = everything already held — so a quiet run costs an
   * empty array and a count.
   *
   * <p>WHAT IT MUST NOT DO IS RE-READ THE WHOLE ARRAY. That is why the Java did fragments at all
   * (2135-2143): replacing the body closes every fold the reader opened and loses their place, on a
   * page where the thing they opened is routinely the only reason they are here.
   *
   * <p>The empty record is not a terminal state, so this polls whether or not anything has been traced
   * yet. The Java's empty branch returned BEFORE emitting a cursor (2189-2193), so a page opened before
   * the first event declared itself unable to take fragments and sat empty for the whole prove.
   */
  useEffect(() => {
    let live = true
    const tick = async () => {
      const now = heldRef.current
      if (now === null) {
        return
      }
      const asked = through(now)
      const page = await read<TracePage>(`/api/events?from=${asked}`)
      if (!live) {
        return
      }
      if (page.cursor < asked) {
        // The results directory was replaced under a page that is still reading it — `ApiTrace` says
        // the client sees that in `cursor` rather than in an error. Everything held describes a record
        // that no longer exists, so it goes, rather than being appended to.
        show(await newestWindow())
        return
      }
      setHeld((prev) => {
        if (prev === null) {
          return prev
        }
        // Against the CURRENT window, not the one this request was issued for: an earlier window may
        // have been prepended while it was in flight.
        const next = through(prev)
        const fresh = page.events.filter((event) => event.index >= next)
        if (
          fresh.length === 0 &&
          page.cursor === prev.cursor &&
          page.openFindings === prev.openFindings
        ) {
          // The same object, so React bails out of the render entirely. On a feed this size, redrawing
          // every two seconds to change nothing is the cost the window was introduced to stop.
          return prev
        }
        return {
          from: prev.from,
          events: fresh.length === 0 ? prev.events : [...prev.events, ...fresh],
          cursor: page.cursor,
          openFindings: page.openFindings,
        }
      })
      setTailFailed(null)
    }
    const timer = setInterval(() => {
      void tick().catch((failure: unknown) => {
        if (live) {
          // THE RECORD STAYS ON SCREEN. A poll that fails once — a redeploy, a dropped connection —
          // must not replace what the reader is reading with an error page; it says so in the subtitle
          // and tries again on the next tick.
          setTailFailed(said(failure))
        }
      })
    }, 2_000)
    return () => {
      live = false
      clearInterval(timer)
    }
  }, [show])

  const earlier = useCallback(() => {
    const now = heldRef.current
    if (now === null || now.from === 0) {
      return
    }
    const start = Math.max(0, now.from - WINDOW)
    setReading(true)
    read<TracePage>(`/api/events?from=${start}&limit=${now.from - start}`)
      .then((page) => {
        setHeld((prev) => {
          if (prev === null) {
            return prev
          }
          const before = page.events.filter((event) => event.index < prev.from)
          return {
            from: before.length === 0 ? prev.from : page.from,
            events: [...before, ...prev.events],
            cursor: page.cursor,
            openFindings: page.openFindings,
          }
        })
        setTailFailed(null)
      })
      .catch((failure: unknown) => setTailFailed(said(failure)))
      .finally(() => setReading(false))
  }, [])

  const toggle = useCallback(() => {
    setExpanded((was) => {
      const now = !was
      // THE CHOICE STAYS IN THE URL. The Java's live fetch reused `location.search` (line 262), so a
      // fold choice that did not survive into the request had appended events arriving expanded onto a
      // folded page. It is also what makes this link worth copying. `replaceState` rather than a push:
      // a fold is a way of looking at this page, not another page, and a back button that undid it
      // would strand a reader three presses from the list they came in on.
      window.history.replaceState(null, '', href(now ? '/trace' : '/trace?fold=1'))
      return now
    })
  }, [])

  // THE THREE STATES, IN ONE PLACE — see `Loaded`. Two early returns before, whose headers said
  // different things and whose waiting branch drew nothing under the title.
  if (failed !== null || held === null) {
    return (
      <Loaded
        what="record"
        failed={failed}
        value={held}
        header={<PageHeader
          title="whole trace"
          subtitle="every event this run has recorded"
          back={BACK}
          findingsOpen={0}
        />}
      >
        {() => null}
      </Loaded>
    )
  }

  // `every marker · N event(s)`, and N is the record's true length rather than the number of rows
  // below it. NO STATE PILL HERE: `events()` looked one up by comparing `field(line,"suspicion_key")`
  // against the page's key, which on `/trace` is the empty string — so any settlement row missing that
  // field matched, and an unrelated marker's state landed in this page's subtitle. There is no marker
  // on this screen and therefore no state.
  const subtitle = (
    <>
      {`every marker · ${held.cursor.toLocaleString()} event(s)`}
      {tailFailed === null ? null : <span style={STALE}>{` · not updating (${tailFailed})`}</span>}
    </>
  )

  return (
    <>
      <PageHeader
        title="whole trace"
        subtitle={subtitle}
        back={BACK}
        findingsOpen={held.openFindings}
      />
      <FoldToggle
        expanded={expanded}
        href={href(expanded ? '/trace?fold=1' : '/trace')}
        onToggle={toggle}
      />
      {held.from > 0 ? (
        <div style={EARLIER}>
          {`${held.from.toLocaleString()} earlier event(s) are not on this page. `}
          <button type="button" style={ASK} onClick={earlier} disabled={reading}>
            {reading ? 'reading…' : `read the ${Math.min(WINDOW, held.from)} before these`}
          </button>
        </div>
      ) : null}
      {held.events.length === 0 ? (
        // The copy is this screen's, not the component's. The Java hard-coded the marker page's
        // "Nothing traced for this marker." and showed it here, where there is no marker and nothing
        // traced anywhere. The second sentence is the one that matters: the poll above is still
        // running, and an empty page is not a page that has stopped watching.
        <EmptyNote>
          Nothing traced yet. The first prompt, tool call or build will appear here on its own.
        </EmptyNote>
      ) : (
        <EventFeed
          // REMOUNTED WHEN THE FOLD CHOICE CHANGES, and only then. `Disclosure` seeds its open state
          // from `defaultOpen` at mount and belongs to the reader afterwards — which is right, and
          // means a changed default reaches nothing already on screen without this. The cost is a
          // half-written rating note, which the Java's full page load lost too.
          key={expanded ? 'open' : 'folded'}
          events={held.events.map(toRecord)}
          order="oldest-first"
          // Every marker's rows are interleaved here, so each one says which marker it belongs to.
          showMarker
          markerHrefFor={markerHrefFor}
          defaultOpen={expanded}
          // Where a rating returns to, with the fold choice kept: the row appends its own `#e-<id>` so
          // saving lands the reader back on the event they were reading rather than at the top of a
          // record this long.
          feedbackBack={href(expanded ? '/trace' : '/trace?fold=1')}
          // The ABSOLUTE index the tail resumes from — not the number of rows on the page, because
          // this page holds a window. Java `cursor()` (2418-2420) wrote the same number to
          // `document.body.dataset.events`.
          cursor={through(held)}
        />
      )}
    </>
  )
}

/**
 * `useSearchParams` needs a boundary in a statically exported zone: there is no server to read the
 * query on, so everything below it is client-rendered and the fallback is what the export holds.
 */
export default function TraceRoute() {
  return (
    <Suspense
      fallback={
        <PageHeader
          title="whole trace"
          subtitle="reading the record…"
          back={BACK}
          findingsOpen={0}
        />
      }
    >
      <TraceScreen />
    </Suspense>
  )
}
