import type { AgentName } from '@fsm/types'
import { Disclosure, RelativeTime, type Style } from '../primitives'
import { StreamTail } from './StreamTail'

export type StreamPanelProps = {
  /**
   * WHO IS SPEAKING, AND IT IS NOT ALWAYS A MARKER.
   *
   * `panel()` is given a marker slug and `m/<slug>/trace.jsonl.live` from `live()` (899) and
   * `proving()` (927) — but `chat()` (1031) hands it the literal string `"supervisor"` over
   * `chat-trace.jsonl.live`, a file that belongs to no marker at all. So this is a plain string: not
   * a `MarkerId`, never linked to `/marker`, and never used to build a marker URL. Typing it as a
   * slug would compile and then send a reader to `/marker?k=supervisor`.
   */
  who: string
  /** Null when the file has not parsed yet — see the torn-read note on the component. */
  agent: AgentName | null
  /**
   * Epoch milliseconds of the last write; 0 means nothing has arrived yet.
   *
   * A NUMBER, NEVER A FORMATTED STRING. The Java could send "quiet 3m" because the same function
   * drew the page and it redrew every two seconds, so the words were current by accident. A
   * component handed those words freezes at "12s ago" for the four minutes an agent spends
   * thinking — which is precisely the stretch this panel exists to report.
   */
  at: number
  text: string
  truncated: boolean
  /** Every one of the three Java call sites passes true; the parameter has no `false` caller. */
  defaultOpen?: boolean
}

/**
 * How much of `who` the summary line shows (panel() 920).
 *
 * A slug runs to 80 characters and the agent and the clock have to fit beside it. The cut is on the
 * LABEL only: the fold's id and its React key carry `who` whole, because two slugs sharing a
 * 46-character prefix are two different provers and the bookkeeping must not confuse them.
 */
const LABEL_MAX = 46

/**
 * THE BOX TRAVELS WITH THE COMPONENT, and that is a fix rather than a coincidence.
 *
 * The Java's rules were scoped `.live details.stream` (CSS 88-96) while `/chat` renders this panel
 * inside `.chat` — so on that one route the fold had no border, no background, no padding and no
 * disclosure triangle. Nobody chose that; it fell out of where the selector was scoped. Styling the
 * component itself means the panel looks the same on both screens, so when someone notices /chat has
 * changed, this is the reason.
 */
const PANEL: Style = {
  border: '1px solid var(--border-soft)',
  borderRadius: '6px',
  background: 'var(--bg-card)',
  padding: '0 10px',
  margin: '5px 0',
}

const NAME: Style = { color: 'var(--text-secondary)' }

/**
 * One fold: who is speaking, which agent it is, how long since the last token, and the tail of what
 * they have written.
 *
 * A TORN READ IS NOT AN ERROR. The `.live` file is rewritten wholesale every 700ms and read at a
 * different cadence, so a read that lands mid-write returns fewer than two newlines and the parse is
 * deliberately all-or-nothing: `agent=null, at=0, text=""` (panel() 906-919, ApiLive.panel()). That
 * arrives here and must render as "nothing yet" — an error banner for a file that will be whole
 * again in two seconds trains the reader to ignore error banners.
 */
export function StreamPanel({
  who,
  agent,
  at,
  text,
  truncated,
  defaultOpen = true,
}: StreamPanelProps) {
  const short = who.length > LABEL_MAX ? `${who.slice(0, LABEL_MAX)}…` : who
  const summary = (
    // `title` because the label may be cut and the whole name is what a reader would search for.
    <span title={who}>
      <span style={NAME}>{short}</span>
      {agent === null ? null : ` · ${agent}`}
      {' · '}
      {/* ONE LADDER FOR BOTH CLOCKS. `ago()` (1023-1029) and this panel's inline rule (924-926)
          both crossed into minutes at 90 seconds and worded it differently, so at 100 seconds the
          chat said "1m ago" and the panel said "quiet 1m" and a reader with both open could not
          tell whether they were looking at the same moment. `RelativeTime` shares the rungs and
          keeps only the wording apart, because the two are answering different questions: age
          there, silence here. */}
      <RelativeTime at={at} variant="stream" />
    </span>
  )
  return (
    <div style={PANEL}>
      {/* `live-<who>` IN FULL, which is the id the Java emitted (930) and the key its poller used to
          put a collapsed fold back after replacing the box. Nothing here is keyed by position: on a
          pool view the panels come and go as provers finish, and a fold remembered by index would
          reopen somebody else's stream. */}
      <Disclosure id={`live-${who}`} defaultOpen={defaultOpen} summary={summary}>
        <StreamTail text={text} truncated={truncated} />
      </Disclosure>
    </div>
  )
}
