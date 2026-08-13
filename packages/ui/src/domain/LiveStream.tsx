'use client'

import { useEffect, useRef, useState } from 'react'
import type { AgentName, MarkerId, MarkerKey } from '@fsm/types'
import { EmptyNote, type Style } from '../primitives'
import { ProveFinishedNotice } from './ProveFinishedNotice'
import { StreamPanel } from './StreamPanel'

/**
 * One answer of `GET /api/live?k=<suspicion_key>` (ApiLive.live), decoded.
 *
 * NULL IS NOT A SHORTER ZERO on this wire, and the three nullable fields are the reason: a file that
 * has not parsed yet has told us nothing about any of them, while 0 is a real instant — a client
 * subtracting it renders fifty-six years of silence. The Java page could use 0 as a sentinel because
 * the same function drew the words. Turning null into {@link StreamPanelProps.at}'s 0 is this
 * component's job and happens once, below.
 */
export type LiveView = {
  /** The suspicion key this payload answers for. Echoed back so a stale reply can be recognised. */
  marker: MarkerKey
  /** `Supervisor.slug(marker)` — the directory name, and what the panel shows as `who`. */
  slug: MarkerId
  /** The complement of blank/proving/infra/queued, resolved server-side. See ProveFinishedNotice. */
  settled: boolean
  agent: AgentName | null
  at: number | null
  text: string | null
  /** The text was cut to the last 4000 characters before it was sent. */
  truncated: boolean
  /** The server's clock at the moment it answered, so the client can tick without trusting its own. */
  serverNow: number
}

export type LiveStreamProps = {
  markerKey: MarkerKey
  /**
   * HOW TO ASK, SUPPLIED BY THE SCREEN. Nothing in this package fetches.
   *
   * Not merely house style: `/api/live?k=` written into a component works standalone and 404s the
   * moment a shell mounts this tool under a prefix — at runtime, in the browser, on somebody else's
   * deployment. The prefix is baked at build and applied in exactly one place (`apps/web/lib/api.ts`),
   * and a component that reaches past that place is a second place.
   *
   * The signal is honoured on unmount, so a panel left behind by a navigation stops asking.
   */
  load: (markerKey: MarkerKey, signal: AbortSignal) => Promise<LiveView>
  /**
   * The payload the screen already had, so the first paint is not two seconds of nothing. Absent is
   * a legitimate state — it renders as "asking", which is what is happening.
   */
  initial?: LiveView
  intervalMs?: number
}

const LIVE: Style = { margin: '10px 0' }

/**
 * A failed request is not a quiet agent, and this is the only place that says so in colour.
 *
 * Loud only when there is nothing else on screen — at that point the reader has never seen an
 * answer, and the difference between "the server is not answering" and "the agent has not spoken"
 * is the difference between a broken page and a working one.
 */
const LOST: Style = { padding: '48px 24px', color: 'var(--danger)' }

/** Beneath a panel that still has content, the same fact is a footnote and not an alarm. */
const STALE: Style = { margin: '4px 2px', fontSize: '11px', color: 'var(--text-tertiary)' }

/**
 * WHAT THIS PROVE IS SAYING RIGHT NOW, ASKED AGAIN EVERY TWO SECONDS.
 *
 * POLLED, NOT PUSHED, ON PURPOSE (Dashboard 460-463). `/events` fires when the trace and settlement
 * counts move — and an agent reasoning for four minutes moves no counts, which is exactly the
 * stretch this screen exists to cover. A push feed would go silent for the whole of it.
 *
 * THREE ANSWERS, NOT ONE BLANK BOX. `/live` with an empty `k` returned 200 with an empty body and
 * the poller wrote that straight into its container (live() 837-852, poll() 319-331): "no marker was
 * asked for" and "the request failed" produced the same picture, and so did a working page about a
 * silent agent. All three are separate here, and a poll that does not come back never blanks a panel
 * that has content — the last answer stays on screen with a line saying it is the last one.
 *
 * FOLD STATE IS NOT IN SESSION STORAGE (315-318) and does not need to be: the Java replaced the
 * container's innerHTML every two seconds, so it read the open/closed bits off the DOM before the
 * swap and put them back after. React re-renders instead of replacing, so a `Disclosure`'s open bit
 * survives on its own — as long as the element keeps its key. The key is `who`, never a position:
 * on a pool-wide list keyed by index, one prove finishing would hand its fold to its neighbour.
 */
export function LiveStream({ markerKey, load, initial, intervalMs = 2000 }: LiveStreamProps) {
  const [received, setReceived] = useState<{ view: LiveView; skew: number } | null>(
    // A build-time `initial` carries the BUILD's idea of now, so no skew is applied to it; the
    // first successful poll replaces both the payload and that assumption a second or two later.
    initial === undefined ? null : { view: initial, skew: 0 },
  )
  const [reached, setReached] = useState(true)

  // A screen that writes `load={(k, s) => read(...)}` inline hands us a new function every render,
  // and a poll effect that depended on it would tear itself down and restart on every tick it
  // caused. The loop depends on the marker, not on the closure that fetches it.
  const latest = useRef(load)
  useEffect(() => {
    latest.current = load
  })

  const shown = received !== null && received.view.marker === markerKey ? received : null
  const settled = shown !== null && shown.view.settled

  useEffect(() => {
    // A prove that has finished will not become unfinished. The `.live` file it left behind would
    // answer forever, which is how a live view becomes a museum with a ticking clock on it.
    if (markerKey === '' || settled) {
      return
    }
    let live = true
    const abort = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined

    const tick = async () => {
      let again = true
      try {
        const next = await latest.current(markerKey, abort.signal)
        if (!live) {
          return
        }
        // HOW FAR THIS BROWSER'S CLOCK IS FROM THE SERVER'S, measured at the moment the payload
        // landed. `at` is stamped by the machine that wrote the file and the age beside it is
        // counted by a laptop that may be minutes out; without this correction an agent that spoke
        // ten seconds ago reads "quiet 4m" and a reader goes looking for a prove that is not stuck.
        setReceived({ view: next, skew: Date.now() - next.serverNow })
        setReached(true)
        again = !next.settled
      } catch (unreachable) {
        if (!live) {
          return
        }
        // The panel keeps whatever it last had. A network blip at a two-second poll is not news
        // about the prove, and blanking the box would report it as one.
        setReached(false)
      }
      // Chained rather than `setInterval`: a poll that takes longer than the interval must not have
      // a second one launched on top of it. Four thousand characters every two seconds is enough
      // traffic to make that happen on a slow link.
      if (live && again) {
        timer = setTimeout(() => {
          void tick()
        }, intervalMs)
      }
    }

    void tick()
    return () => {
      live = false
      abort.abort()
      clearTimeout(timer)
    }
  }, [markerKey, intervalMs, settled])

  if (markerKey === '') {
    return (
      <EmptyNote>
        No marker was asked for, so there is nothing to watch. A prove&rsquo;s live view opens from
        its own row on the index, while it is still running.
      </EmptyNote>
    )
  }

  if (shown === null) {
    return reached ? (
      <EmptyNote>Asking what this prove is saying&hellip;</EmptyNote>
    ) : (
      <p style={LOST}>
        The dashboard did not answer, so nothing here is known yet &mdash; this is a request that
        failed, not an agent being quiet. Still asking.
      </p>
    )
  }

  return (
    // No `id=live` and no `data-live` attribute. That pair existed so one script could find the box
    // by id and read its own endpoint off it, serving two pages without either knowing about the
    // other (1830-1832, 320-321). A component that owns its own poll and is handed `load` needs
    // neither, and neither is a hook anything else may key on.
    <div style={LIVE}>
      {shown.view.settled ? (
        <ProveFinishedNotice />
      ) : (
        <StreamPanel
          key={shown.view.slug}
          who={shown.view.slug}
          agent={shown.view.agent}
          at={shown.view.at === null ? 0 : shown.view.at + shown.skew}
          text={shown.view.text ?? ''}
          truncated={shown.view.truncated}
        />
      )}
      {reached ? null : (
        <p style={STALE}>the last poll did not come back &mdash; this is the last one that did</p>
      )}
    </div>
  )
}
