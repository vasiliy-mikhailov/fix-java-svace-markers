'use client'

import { useEffect, useState } from 'react'
import { ProgressBar, Tally, type Style } from '../primitives'
import { clock } from './clock'

export type RunProgressProps = {
  /** Markers the run was given. 0 means there is no run — a single prove, or nothing queued yet. */
  total: number
  /**
   * Markers that have STOPPED, which is not the same as markers that were decided.
   *
   * Everything except `proving`, `queued` and `interrupted` (Run.isSettled) — so `infra` counts. A
   * prove that threw has stopped even though it decided nothing, and leaving it out would make the
   * bar creep backwards as the tooling failed. That it is not a disposition is the state column's
   * problem, not this bar's.
   */
  settled: number
  /**
   * Of the settled, how many had something EXECUTED — a test that failed on the code as it stood.
   *
   * The rest were closed by argument: a model wrote a convincing paragraph about one commit. Both
   * used to increment `settled` alone, so a page reading "343 settled" reported 132 markers closed
   * on prose as though they had been shown. An argument is not worthless; it is different evidence,
   * and it stops being true the moment three lines change somewhere else.
   */
  demonstrated: number
  /** Epoch ms of the earliest trace event. 0 = nothing has run; not "the epoch". */
  beganAt: number
  /**
   * The SERVER's clock when the payload was built.
   *
   * Elapsed is measured against this and not against `Date.now()`, because a browser whose clock is
   * ten minutes fast would otherwise add ten minutes to every run it looked at. The browser is used
   * only for the DIFFERENCE since this component mounted, which is a stopwatch and needs no
   * agreement about what time it is.
   */
  now: number
}

/** Java's `.counts` (CSS 46): the strip of boxes under the header. */
const STRIP: Style = { display: 'flex', flexWrap: 'wrap', gap: '8px', padding: '14px 24px' }

/**
 * HOW FAR IN, HOW LONG IT HAS TAKEN, AND HOW LONG IS LEFT.
 *
 * IT TAKES `beganAt`, NOT `elapsed`. The Java baked `System.currentTimeMillis() - began` into the
 * markup (1673), so the clock moved only when the whole page was rebuilt — which it was, every two or
 * three seconds, by accident of how that page worked. A React component re-renders when its payload
 * changes, and a run's payload does not change while every prover is thinking; the number would sit
 * still for four minutes at exactly the moment somebody is watching it to see whether anything is
 * alive. So the component holds the start and ticks itself.
 *
 * THE ETA IS EXTRAPOLATION AND IS LABELLED AS ONE (Java doc 2258-2264): settled over elapsed, applied
 * to what is left. It is honest only while markers cost about the same, and they do not — one the
 * reproducer declines costs a minute, one that goes red, green and two rounds with a skeptic costs
 * twenty. It is shown because a wrong estimate that converges beats no estimate, and it says
 * "extrapolated" so nobody plans around it.
 */
export function RunProgress({ total, settled, demonstrated, beganAt, now }: RunProgressProps) {
  // Both seeded from the same instant, so the first client render matches the server's markup
  // exactly and hydration has nothing to correct. The pair is a stopwatch: only their difference is
  // ever used, never either one as a wall clock.
  const [mountedAt] = useState(() => Date.now())
  const [browserNow, setBrowserNow] = useState(mountedAt)

  useEffect(() => {
    if (beganAt <= 0) {
      return
    }
    // One second, and it stays one second however long the run has been going: `clock()` shows
    // seconds for the first minute and this is the number a reader watches to decide whether the run
    // has died. A page has one of these on it, not sixty, so the cost is a timer.
    const timer = setInterval(() => setBrowserNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [beganAt])

  const elapsed = beganAt <= 0 ? 0 : Math.max(0, now - beganAt + (browserNow - mountedAt))

  // TWO DEGENERATE BRANCHES, BOTH LOAD-BEARING (2269-2272). A single prove has no queue, so there is
  // no proportion to draw and a bar would claim a denominator this component was not given; but the
  // time it has been running is still worth saying. With neither, there is nothing to say at all —
  // and an empty strip of boxes reads as a run that has begun and reported nothing.
  if (total <= 0) {
    return elapsed <= 0 ? null : (
      <div style={STRIP}>
        <Tally value={clock(elapsed)} label="elapsed" />
      </div>
    )
  }

  const pct = Math.min(100, Math.trunc((settled * 100) / Math.max(1, total)))
  // Integer division first, exactly as the Java does it (2274-2275): the average is truncated to
  // whole milliseconds before it is multiplied out, so this cannot drift from the figure the old page
  // showed while both are running side by side.
  const eta =
    settled > 0 && settled < total
      ? clock(Math.trunc(elapsed / settled) * (total - settled))
      : '—'

  return (
    <>
      <ProgressBar pct={pct} />
      <div style={STRIP}>
        <Tally value={`${settled} / ${total}`} label={`${pct}% settled`} />
        {/* THE SPLIT IS THE POINT. Two markers in the same `settled` count can mean "a test failed
            before the patch and passed after" or "nobody ran anything and the argument read well",
            and only one of those survives somebody editing the file next week. */}
        <Tally value={`${demonstrated}`} label="shown by a test" />
        <Tally value={`${settled - demonstrated}`} label="argued only" />
        <Tally value={clock(elapsed)} label="elapsed" />
        <Tally value={eta} label="eta, extrapolated" />
      </div>
    </>
  )
}
