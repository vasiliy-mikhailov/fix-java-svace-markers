'use client'

import { Suspense, useEffect, useState } from 'react'
import { useSearchParams } from 'next/navigation'
import {
  EmptyNote,
  EventFeed,
  FindingCard,
  FindingTally,
  PageHeader,
  RestartLog,
  TabRow,
  type FindingRecord,
  type Restart,
  type TabItem,
  type TraceEventRecord,
} from '@fsm/ui'
import { VERDICTS } from '@fsm/types'
import type { AgentName, MarkerId, MarkerKey, MarkerState, Verdict } from '@fsm/types'

import { href, read } from '../../lib/api'

/**
 * THE SUPERVISOR SCREEN — what is wrong with the pipeline itself.
 *
 * <p>Four views off one route and one payload, selected by `?a=`: the findings the supervisor has
 * reported (the default, `?a=` empty), one agent's share of its record (`?a=overwatch`,
 * `?a=overwatch-critic`), and the whole record (`?a=trace`). The URLs are the Java's exactly —
 * `overwatch()` 584-594 — because the shell links here, the findings name each other, and people
 * have these bookmarked.
 *
 * <p>THE ADAPTER IS THIS FILE'S JOB. The components were specified from the Java and the API from
 * the same reading, so the two vocabularies drifted: the wire says `position` where `FindingCard`
 * says `index`, the wire says `by: null` where `RestartLog` says `by: 'supervisor'`, and the wire's
 * `verdict` is an unconstrained string where the component takes one of three words. Every one of
 * those is reconciled here rather than in either half — the API sends what the record holds, the
 * component takes what it draws, and this page is the one place allowed to know both.
 */

/* ------------------------------------------------------------------ the wire */

type ApiFinding = {
  /** The index in `overwatch.jsonl`, and the number every anchor and feedback row is written against. */
  position: number
  at: number
  verdict: string
  finding: string
  /** "" is how an unjudged finding is shaped. Not an error, and not something to fill in. */
  judgement: string
  /** The directory slugs this finding names. Slugs, not keys — see `slugsIn` below. */
  markers: MarkerId[]
}

type ApiRestart = {
  at: number
  id: MarkerId
  marker: MarkerKey
  attempt: number
  killed: boolean
  /** Who ordered it. Absent — `null` on this wire, `""` in the file — means the supervisor itself. */
  by: string | null
  why: string
}

/**
 * One row of `overwatch-trace.jsonl`, as flat as the file is.
 *
 * `id` is not defaulted and not synthesised anywhere below. A rating posted from this page is
 * written against it (`RateAnswer` → `feedback.jsonl`), and bug #3 is precisely what happens when an
 * event is identified by where it happened to sit in a list: the same answer was `event=4` on one
 * page and `event=0` on another, and the corpus holds both with nothing to tell them apart. An id
 * this page invented would be a third such number, written into training data.
 */
type ApiEvent = {
  id: string
  at: number
  kind: string
  agent: string | null
  marker?: string | null
  prompt?: string
  reply?: string
  text?: string
  tool?: string
  arguments?: string
  args?: string
  result?: string
  phase?: string
  passed?: boolean
  infra?: boolean
  summary?: string
  note?: string
  state?: string
  because?: string
  minutes?: number
  itemisation?: string
  cause?: string
}

/**
 * NO ENVELOPE. This was written as `{ overwatch: { … } }` and the endpoint sends the fields at the
 * top level, so `d.overwatch` was undefined and the screen threw on the first destructure. It threw
 * on MOUNT, in an effect, which a build cannot see and a prerender cannot either — the route compiled
 * and the static HTML was fine. It was caught by mounting the page against a captured payload.
 */
type ApiOverwatch = {
    /** The view the server resolved. Echoed, and deliberately not what this page renders from — the
     *  URL is the authority, so a payload that answered a different view cannot quietly relabel the
     *  tabs under the reader. */
    view: string
    /** holds + unjudged, for the header badge. `refuted` is excluded and `unjudged` counts: a
     *  finding the critic never reached is not one it dismissed (`holding()` 800-809). */
    open: number
    tally: { holds: number; refuted: number; unjudged: number; restarts: number }
    findings: ApiFinding[]
    restarts: ApiRestart[]
    events: ApiEvent[]
}

/* ------------------------------------------------------------------ the views */

type View = 'findings' | 'overwatch' | 'overwatch-critic' | 'trace'

/**
 * WHICH BODY TO DRAW. An unrecognised `?a=` falls through to the findings body, which is the Java's
 * behaviour (584-594) and is kept — but see `tabs()`: it lights no tab, and that asymmetry is also
 * kept, because a lit "findings" tab would tell a reader that `?a=banna` was understood.
 */
function viewOf(a: string): View {
  return a === 'overwatch' || a === 'overwatch-critic' || a === 'trace' ? a : 'findings'
}

/** This route's URL for a view, with the reader's fold choice carried along. */
function urlFor(a: string, folded: boolean): string {
  const query = [...(a === '' ? [] : [`a=${a}`]), ...(folded ? ['fold=1'] : [])]
  return href(query.length === 0 ? '/overwatch' : `/overwatch?${query.join('&')}`)
}

/**
 * The four supervisor tabs, plus settings as a DEPARTURE.
 *
 * `on` is each item's own test against the URL, never a test of another item's key — the Java's
 * `settingsTabs()` lit prompts with `!current.equals("run")` and lit two tabs at once (bug not
 * ported, see `TabRow`). Settings is in `trailing` because it leaves this set rather than choosing
 * within it, and `TabRow` never lights a departure.
 */
function tabs(a: string, folded: boolean): TabItem[] {
  return [
    { href: urlFor('', folded), label: 'findings', on: a === '' },
    { href: urlFor('overwatch', folded), label: 'overwatch', on: a === 'overwatch' },
    {
      href: urlFor('overwatch-critic', folded),
      label: 'overwatch-critic',
      on: a === 'overwatch-critic',
    },
    { href: urlFor('trace', folded), label: 'the record', on: a === 'trace' },
  ]
}

const DEPARTURES: TabItem[] = [{ href: href('/settings'), label: 'settings', on: false }]

/**
 * Every view gets the same exit crumb.
 *
 * `reported()` passed back="all markers" and `supervisorEvents()` passed none (631), so three of the
 * four views had no way out but the browser's back button. The catalogue's reading is that this was
 * two call sites rather than a decision, and there is no view here from which "all markers" is the
 * wrong place to go.
 */
const BACK = { label: 'all markers', href: href('/') }

/* ------------------------------------------------------------------ the adapters */

/**
 * Blank, unknown or missing is `unjudged` — `verdictOf()` 821-824, the same fold the tally's
 * `size - holds - refuted` performs.
 *
 * ONE RULE, NOT TWO: the count in the strip and the pill on the card have to agree about a row
 * nobody thought about, and the tally is the number people quote in a status meeting. A raw cast
 * would also hand `VerdictPill` a word with no colour token, which renders an uncoloured pill for a
 * verdict nobody can read.
 */
function verdictOf(verdict: string): Verdict {
  return (VERDICTS as readonly string[]).includes(verdict) ? (verdict as Verdict) : 'unjudged'
}

/**
 * `position` on the wire is `index` on the card, and it is the position in the FILE.
 *
 * The list below is reordered by verdict for reading; this number is not touched by that. The
 * anchor, the DOM id and the feedback event all carry it, so an index computed from where a card
 * ended up would move the moment the critic judged something — silently repointing every link
 * anybody had saved and every feedback row written afterwards.
 */
function toFinding(f: ApiFinding): FindingRecord {
  return {
    index: f.position,
    at: f.at,
    verdict: verdictOf(f.verdict),
    finding: f.finding,
    judgement: f.judgement,
  }
}

/** Holds, then unjudged, then refuted (`reported()` 738) — file order preserved inside each group. */
const READING_ORDER: readonly Verdict[] = ['holds', 'unjudged', 'refuted']

function inReadingOrder(findings: FindingRecord[]): FindingRecord[] {
  return READING_ORDER.flatMap(verdict => findings.filter(f => f.verdict === verdict))
}

/**
 * WHO CUT THE TREE — the field the Java dropped, and the reason `RestartLog` grew a row per restart.
 *
 * Absence is not a missing value here, it is the supervisor: `Supervisor.restarts()` (173-193)
 * counts only the lines WITHOUT a `by`, because a person's `/reprove` must not spend the
 * supervisor's allowance of two. So an absent `by` is read as the supervisor and ANY name is read as
 * a person — not just the literal "person" — because filing a named actor's restart under the
 * supervisor's budget is the exact confusion the field exists to prevent.
 */
function toRestart(r: ApiRestart): Restart {
  return {
    at: r.at,
    id: r.id,
    marker: r.marker,
    attempt: r.attempt,
    killed: r.killed,
    by: r.by === null || r.by === '' ? 'supervisor' : 'person',
    why: r.why,
  }
}

/**
 * IT IS LOWERCASE ON DISK. `marker()` compares `field(e,"phase").equals("red")` (1865) and `one()`
 * uppercases only for display (2203); the API sketch writes it "RED", and three earlier passes typed
 * it that way. Normalising here means the lamp is lit from the value whichever spelling arrives.
 *
 * Anything else — including absence — returns null, and `TraceEvent` then renders the row as its
 * bare kind. That is the wanted answer: a `built` row with no readable phase is not a build this
 * page can colour, and the Java's build case would have called it `failed` from two absent booleans,
 * which is the strongest possible claim from the least possible information.
 */
function phaseOf(phase: string | undefined): 'red' | 'green' | null {
  const lower = phase?.toLowerCase()
  return lower === 'red' || lower === 'green' ? lower : null
}

function toEvent(e: ApiEvent): TraceEventRecord {
  const { agent, phase, state, marker, ...rest } = e
  const lamp = phaseOf(phase)
  return {
    ...rest,
    // Passed through as written rather than checked against `AGENTS`. The record is append-only and
    // will grow agents; a name this file does not recognise is still the name that said the thing,
    // and narrowing it to null would print "unrecorded" over a real author.
    agent: agent as AgentName | null,
    // Absent stays ABSENT. `exactOptionalPropertyTypes` makes that a different value from present-
    // and-undefined, and the components read absence as "the record does not say".
    ...(marker === undefined || marker === null ? {} : { marker }),
    ...(lamp === null ? {} : { phase: lamp }),
    ...(state === undefined ? {} : { state: state as MarkerState }),
  }
}

/**
 * A CRUMB ONLY WHERE THERE IS A MARKER TO GO TO.
 *
 * The supervisor stamps its own rows `marker="overwatch"` (Overwatch.java 63-64) and its progress
 * lines with a directory slug (Supervisor.java 274). Neither is a key, and the Java built
 * `/marker?k=<that>` unconditionally — so every crumb on this screen linked to a marker page for a
 * marker that cannot exist, on a page whose whole job is to be trustworthy about the record. The
 * test is on what arrived: a key is `repo|file|line|checker`, so anything without the separator gets
 * no link and `MarkerCrumb` draws it as text. When the payload starts carrying the real key for the
 * rows that have one, those rows link and nothing here changes.
 */
function markerHrefFor(event: TraceEventRecord): string | null {
  const marker = event.marker ?? ''
  return marker.includes('|') ? href(`/marker?k=${encodeURIComponent(marker)}`) : null
}

/**
 * THE SLUG→KEY MAP THIS PAYLOAD DOES NOT CARRY, which is why it is empty.
 *
 * `FindingCard` hands this to `MarkerLinkedText` to turn the slugs a finding names into links, and
 * the href must carry the marker's KEY — a slug is a directory name and `/marker?k=` wants an
 * identity. The wire gives each finding a `markers: MarkerId[]`, which is the slugs and not the keys
 * they were made from, so there is nothing here to build a correct link out of. Mapping a slug to
 * itself would produce exactly the dead `/marker?k=<not a key>` link that `markerHrefFor` above
 * exists to stop, one component over.
 *
 * An empty map renders the finding's prose unchanged, which is also its normal state before a run
 * starts and `markers.txt` exists. When the payload grows the `slugs` map the catalogue asks for,
 * this becomes `new Map(Object.entries(data.slugs))` and every name in every finding becomes a link.
 */
const NO_SLUGS: ReadonlyMap<MarkerId, MarkerKey> = new Map()

/* ------------------------------------------------------------------ the screen */

/** The body sits in the header's gutter; the components draw themselves, this only lines them up. */
const GUTTER = { padding: '0 24px' } as const

function SupervisorScreen() {
  const params = useSearchParams()
  const a = params.get('a') ?? ''
  const view = viewOf(a)
  // PRESENT MEANS FOLD, absent means open (`open()` 2374-2376 reads `?fold=`, and it tests presence,
  // not the value). Open by default is deliberate and the reason is stated at 2369-2373: reading a
  // prove is reading the prompts. The toggle control itself belongs to `/trace`; this screen honours
  // the parameter so a link that carries it arrives folded.
  const folded = params.get('fold') !== null
  const defaultOpen = !folded

  const [data, setData] = useState<ApiOverwatch | null>(null)
  const [failed, setFailed] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    // ONE READ, NO POLL, AND THAT IS A DECISION.
    //
    // The Java refreshes the index every 15s and appends to a live trace every 2s, but no supervisor
    // view emits `cursor()` and `LIVE` returns early without one (255-259), so this screen has always
    // been static. Keeping it static is not laziness about a missing feature: this page is nothing
    // but disclosures, every one of them opened by the reader, and a wholesale refetch re-renders
    // them at `defaultOpen` — which throws away what somebody had opened while they were reading it.
    // `EventFeed`'s `cursor` prop is left ABSENT below for the same reason; when the payload learns
    // to cursor the supervisor's events, this can append instead of replacing.
    read<ApiOverwatch>(`/api/overwatch?a=${encodeURIComponent(a)}`)
      .then(d => {
        if (live) {
          setData(d)
          setFailed(null)
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
  }, [a])

  // Zero on both branches below is "not known yet", not "nothing stands" — and `FindingsButton`
  // draws no badge at zero for that exact reason (2400-2403: a badge reading zero teaches a reader
  // to ignore it). A count guessed while the payload is in flight would be a claim about the
  // pipeline made out of the absence of an answer.
  if (failed !== null) {
    return (
      <>
        <PageHeader
          title="the supervisor"
          subtitle="could not read what it has said"
          back={BACK}
          findingsOpen={0}
        />
        <EmptyNote>{failed}</EmptyNote>
      </>
    )
  }
  if (data === null) {
    return (
      <PageHeader
        title="the supervisor"
        subtitle="reading what it has said…"
        back={BACK}
        findingsOpen={0}
      />
    )
  }

  const items = tabs(a, folded)
  // Where a rating returns to. `TraceEvent` appends the row's own `#e-<id>`, so the reader lands on
  // the event they rated rather than at the top of a record that runs to megabytes.
  const self = urlFor(a, folded)

  if (view === 'findings') {
    const { tally, findings, restarts } = data
    const cards = inReadingOrder(findings.map(toFinding))
    // Whether `FindingTally` has drawn the strip or spoken for the empty page itself. Its empty test
    // is every count at zero — the Java tested `all.isEmpty()`, findings only, so a run with no
    // findings and five restarts printed "the supervisor has not reported yet" directly above a fold
    // listing five restarts. Two notes saying different things about the same page is the same bug
    // from the other side, so the note below is drawn only when the strip is.
    const reported = tally.holds + tally.refuted + tally.unjudged + tally.restarts > 0
    return (
      <>
        <PageHeader
          title="the supervisor"
          subtitle="what it has concluded about the pipeline, and what its critic made of it"
          back={BACK}
          findingsOpen={data.open}
        />
        <TabRow items={items} trailing={DEPARTURES} />
        <div style={GUTTER}>
          <FindingTally
            holds={tally.holds}
            refuted={tally.refuted}
            unjudged={tally.unjudged}
            restarts={tally.restarts}
          />
          {/* Nothing cut renders nothing — the component decides that, and the tally above has
              already counted the zero. */}
          <RestartLog restarts={restarts.map(toRestart)} defaultOpen={defaultOpen} />
          {cards.map(finding => (
            // Keyed by the position in the file, which is the one number that does not move when a
            // critic answers and the card changes group.
            <FindingCard
              key={finding.index}
              finding={finding}
              markers={NO_SLUGS}
              defaultOpen={defaultOpen}
            />
          ))}
        </div>
        {cards.length === 0 && reported ? (
          <EmptyNote>
            Nothing reported. The supervisor has been at work — the restarts above are what it has
            done, not what it has concluded — and a findings list with nothing on it is the good
            outcome, not a page that failed to load.
          </EmptyNote>
        ) : null}
      </>
    )
  }

  // The record, or one agent's share of it. `supervisorAgent()` (650-659) filters on an exact
  // agent-name match against the two tab values, which is why an event from any third supervisor
  // agent shows up only under "the record" — repeated here so the tab means the same thing whether
  // or not the server has already narrowed the payload.
  const all = data.events.map(toEvent)
  const events = view === 'trace' ? all : all.filter(event => event.agent === view)
  const title = view === 'trace' ? "the supervisor's record" : view
  return (
    <>
      <PageHeader
        title={title}
        subtitle={`${events.length.toLocaleString()} event(s), newest first`}
        back={BACK}
        findingsOpen={data.open}
      />
      <TabRow items={items} trailing={DEPARTURES} />
      {events.length === 0 ? (
        <EmptyNote>
          Nothing here yet. The supervisor looks on its own schedule, not on yours, and this page
          does not refresh itself — reload it when you want to know what it has said since.
        </EmptyNote>
      ) : (
        // NEWEST FIRST, unlike a prove's trace, and for a stated reason (643-649): a prove is read
        // after it settles and its story runs forwards, while the supervisor is read while it is
        // running and the question is always what it just said.
        //
        // `showMarker` is on because these rows come from every prove the supervisor looked at, and
        // which one a progress line is about is the first thing a reader needs. `cursor` is absent:
        // this feed takes no fragments (see the fetch above).
        <EventFeed
          events={events}
          order="newest-first"
          showMarker
          markerHrefFor={markerHrefFor}
          defaultOpen={defaultOpen}
          feedbackBack={self}
        />
      )}
    </>
  )
}

/**
 * `useSearchParams()` reads something that does not exist when this zone is exported.
 *
 * The query is a browser fact and the build has no browser, so Next refuses to prerender a page that
 * reads it outside a boundary — without this the whole static export fails, not just this route. The
 * fallback is the markup the exported HTML holds until the browser tells the page which view it is
 * on, so it says the same thing the loading state says rather than nothing.
 */
export default function SupervisorRoute() {
  return (
    <Suspense
      fallback={
        <PageHeader
          title="the supervisor"
          subtitle="reading what it has said…"
          back={BACK}
          findingsOpen={0}
        />
      }
    >
      <SupervisorScreen />
    </Suspense>
  )
}
