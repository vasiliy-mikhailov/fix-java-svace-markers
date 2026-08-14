'use client'

import { useEffect, useState } from 'react'

export type RelativeTimeProps = {
  /** Epoch milliseconds. `num()` (2408) turns an unparseable one into 0 — see `variant`. */
  at: number
  /**
   * WHICH QUESTION THE NUMBER ANSWERS, and it is the only difference left between the two.
   *
   * `conversation` reports AGE: how long ago this turn was said. `stream` reports SILENCE: how long
   * an agent has been thinking without saying anything, which is why 0 there is "nothing yet" and
   * not the first second of 1970 — nothing has been said at all.
   */
  variant: 'conversation' | 'stream'
}

/**
 * ONE LADDER, SHARED, because the two formatters used to disagree about where the rungs were.
 *
 * `ago()` (1071-1077) crossed into minutes at 90 seconds; `panel()`'s inline rule (969-975) crossed
 * at 90 too but wrote it differently — so at 100 seconds one screen said "1m ago" and the other
 * "quiet 1m", and a reader with both open could not tell whether they were looking at the same
 * moment. That difference was an accident. The wording difference is not, and it survives below.
 *
 * THE DAY RUNG IS NEW. `ago()` stopped at hours with no unit above them, so a tab left open
 * overnight reported "31h ago" — technically true and useless, and the number this exists to report
 * is one a person reads at a glance.
 */
function rung(seconds: number): string {
  if (seconds < 90) {
    return `${seconds}s`
  }
  if (seconds < 5400) {
    return `${Math.floor(seconds / 60)}m`
  }
  if (seconds < 86400) {
    return `${Math.floor(seconds / 3600)}h`
  }
  return `${Math.floor(seconds / 86400)}d`
}

function say(at: number, seconds: number, variant: 'conversation' | 'stream'): string {
  if (variant === 'conversation') {
    return `${rung(seconds)} ago`
  }
  if (at <= 0) {
    return 'nothing yet'
  }
  // Under the first rung it is not silence yet, it is just recent — and "quiet 12s" reads as an
  // alarm about a pause nobody would have noticed.
  return seconds < 90 ? `${seconds}s ago` : `quiet ${rung(seconds)}`
}

/** Clock skew and a clock set backwards both produce a negative age; neither is "in 3 seconds". */
function elapsed(at: number, now: number): number {
  return Math.max(0, Math.floor((now - at) / 1000))
}

/**
 * How long ago, and it has to TICK.
 *
 * The Java recomputed this on every render and re-rendered the whole page every two to three
 * seconds, so the number was always current by accident. A React component that re-renders only
 * when its payload changes freezes at "12s ago" for the four minutes an agent spends thinking —
 * which is precisely the stretch this number exists to report, and the reader concludes the page
 * has died.
 *
 * So it owns a timer, and the timer slows down as the number does: once past the second rung a new
 * reading a quarter-minute late is indistinguishable from an exact one, and a page with sixty of
 * these on it should not wake sixty times a second to say so.
 */
export function RelativeTime({ at, variant }: RelativeTimeProps) {
  const [now, setNow] = useState(() => Date.now())
  const seconds = elapsed(at, now)

  useEffect(() => {
    if (at <= 0) {
      return
    }
    const since = elapsed(at, Date.now())
    const wait = since < 90 ? 1000 : since < 5400 ? 15000 : 60000
    const timer = setTimeout(() => setNow(Date.now()), wait)
    return () => clearTimeout(timer)
  }, [at, now])

  return (
    // The markup is written at build time by a static export, so the first paint carries the
    // BUILD's idea of now and hydration corrects it. That mismatch is the intended behaviour of a
    // clock, not a bug React needs to warn about.
    <time
      suppressHydrationWarning
      {...(at > 0 ? { dateTime: new Date(at).toISOString() } : {})}
      style={{ color: 'var(--text-tertiary)' }}
    >
      {say(at, seconds, variant)}
    </time>
  )
}
