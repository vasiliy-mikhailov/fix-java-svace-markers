/**
 * THE DOMAIN, AS THE RECORD ACTUALLY HOLDS IT.
 *
 * Every value here was read out of the Java that writes it, not invented to look tidy. Where a name
 * is odd, the oddness is real and the comment says why — a type that quietly renames a state would
 * make the UI disagree with the files it is rendering, and the files are the record.
 */

/**
 * What a prove can DECIDE. Seven, and no more: `entrypoint.sh` greps for exactly these to know a
 * marker is finished, so a disposition added here and not there would be proved forever.
 */
export const DISPOSITIONS = [
  'false-positive',
  'by-design',
  'unprovable',
  'reproduced',
  'needs-review',
  'verified/pr-ready',
  'verified/pr-rejected',
] as const
export type Disposition = (typeof DISPOSITIONS)[number]

/**
 * States that are NOT an answer.
 *
 * `infra` is the one that matters: it is written when a prove THROWS, and it used to count as a
 * settlement — so a prove killed by its own tooling retired its marker. It is not a disposition and
 * nothing may treat it as one.
 *
 * `queued` is synthesised by the dashboard for a marker in the queue with no lane on disk; nothing
 * writes it. `interrupted` likewise: a lane that says `proving` whose claim directory is gone.
 */
export const UNSETTLED = ['proving', 'infra', 'queued', 'interrupted'] as const
export type Unsettled = (typeof UNSETTLED)[number]

export type MarkerState = Disposition | Unsettled

export function isDisposition(state: string): state is Disposition {
  return (DISPOSITIONS as readonly string[]).includes(state)
}

/** What the supervisor's critic made of a finding. Silence leaves it `unjudged`, deliberately. */
export const VERDICTS = ['holds', 'unjudged', 'refuted'] as const
export type Verdict = (typeof VERDICTS)[number]

/**
 * The kinds of line in a trace. `system` is real and easy to miss.
 *
 * `asked` carries the whole prompt AND the whole reply, which is why a trace runs to tens of
 * thousands of characters and why anything rendering one has to think about length.
 */
export const TRACE_KINDS = [
  'asked',
  'thought',
  'tool',
  'built',
  'settled',
  'failed',
  'progress',
  'priced',
  'system',
] as const
export type TraceKind = (typeof TRACE_KINDS)[number]

/** The ten that run inside a prove, in the order `Prove` calls them. Five producer/critic pairs. */
export const CHAIN = [
  // FIVE STAGES, THREE ROLES EACH: the planner decides how the stage should be approached, the doer
  // makes it with that plan in front of it, and the verifier judges the work — and may send the
  // fault back to either one. `redo` returns to the doer; `replan` says the approach was wrong and
  // goes back to the planner.
  //
  // EVERY NAME SAYS ITS ROLE. The record written before this change carries the old ones —
  // reproducer, proof-critic, fixer — and the marker page appends any agent it does not recognise,
  // so 356 markers of history stay readable rather than losing their tabs.
  'reproduce-planner', 'reproduce-doer', 'reproduce-verifier',
  'fix-planner', 'fix-doer', 'fix-verifier',
  'propose-planner', 'propose-doer', 'propose-verifier',
  'argue-planner', 'argue-doer', 'argue-verifier',
  'price-planner', 'price-doer', 'price-verifier',
] as const

/** The four that watch a run rather than run in one, then the one that answers when asked. */
export const WATCH = ['overwatch', 'overwatch-critic', 'interpreter', 'interpreter-critic'] as const
export const ASKED = ['chat'] as const

export const AGENTS = [...CHAIN, ...WATCH, ...ASKED] as const
export type AgentName = (typeof AGENTS)[number]

/**
 * Severity does NOT come from the marker key.
 *
 * A key is `repo|file|line|checker` and carries no severity; it is joined in from
 * `results/severities.tsv` by `file|line|checker`. So it can legitimately be absent, and a UI that
 * assumes every marker has one will render an empty cell it did not plan for.
 */
export const SEVERITIES = ['Critical', 'Major', 'Minor'] as const
export type Severity = (typeof SEVERITIES)[number]

/** `repo|file|line|checker`. The identity of a marker everywhere in this system. */
export type MarkerKey = string

/**
 * The directory name a marker gets, and the id every API path uses.
 *
 * Made by taking everything after the last `/`, replacing anything outside `[A-Za-z0-9._-]` with an
 * underscore and cutting to 80. The shell computes it with `sed` and Java computes it again in
 * `Supervisor.slug`; a third implementation that disagrees would name directories nobody reads.
 */
export type MarkerId = string

export type Marker = {
  id: MarkerId
  key: MarkerKey
  file: string
  line: number
  checker: string
  severity: Severity | null
  state: MarkerState
  /** Minutes across every attempt, archived ones included — not just the attempt you can see. */
  tookMinutes: number
  attempts: number
  events: number
  /** The lane interpreter's one-line account, checked by its critic. Absent until it has run. */
  summary: string | null
}

export type Finding = {
  /** Position in `overwatch.jsonl`. The findings page groups by verdict, so display order is not
   *  the file's, and an anchor computed from display position would move when a critic answered. */
  position: number
  title: string
  body: string
  verdict: Verdict
  markers: MarkerId[]
}

export type TraceEvent = {
  at: number
  kind: TraceKind
  agent: AgentName | null
  /** Present on `asked`. Both are held in full; the record is the corpus. */
  prompt?: string
  reply?: string
  tool?: string
  args?: string
  result?: string
  phase?: string
  /** A build that produced no test result is never evidence — not a pass and not a failure. */
  infra?: boolean
  summary?: string
  cause?: string
  note?: string
}

/** One side of the conversation with the supervisor. */
export type ChatTurn = {
  at: number
  who: 'you' | 'supervisor'
  text: string
}
