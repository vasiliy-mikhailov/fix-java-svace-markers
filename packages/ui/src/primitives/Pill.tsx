import type { ReactNode } from 'react'
import type { Style } from './style'

/**
 * SIX TONES, AND NOT ONE OF THEM IS A STATE.
 *
 * A `Pill` does not know what a disposition is. `StateBadge` and `VerdictPill` map their own
 * vocabulary onto these; nothing else may pass a tone straight through from a payload, because a
 * tone arriving over the wire is a colour decision made by a server that cannot be tested for it —
 * which is exactly how the Java ended up sending `verified/pr-ready` to the infra red.
 */
export type PillTone = 'good' | 'warn' | 'quiet' | 'alarm' | 'running' | 'aside'

export type PillProps = {
  tone: PillTone
  href?: string
  children: ReactNode
}

/**
 * Tone → token. The names on the right are this domain's, from `domain.css`; the names on the left
 * are what a pill is FOR, and the gap between the two columns is the only place a colour decision
 * lives.
 */
const TONE: Record<PillTone, string> = {
  good: 'var(--state-verified-pr-ready)',
  warn: 'var(--state-needs-review)',
  quiet: 'var(--state-false-positive)',
  alarm: 'var(--state-infra)',
  running: 'var(--state-proving)',
  aside: 'var(--state-by-design)',
}

function pillStyle(tone: PillTone): Style {
  return {
    // Set once and referred to three times, so the text, the wash and the edge cannot drift apart.
    '--pill-tone': TONE[tone],
    display: 'inline-block',
    padding: '2px 9px',
    borderRadius: '20px',
    fontSize: '11px',
    whiteSpace: 'nowrap',
    textDecoration: 'none',
    color: 'var(--pill-tone)',
    background: 'color-mix(in srgb, var(--pill-tone) 14%, transparent)',
    border: '1px solid color-mix(in srgb, var(--pill-tone) 32%, transparent)',
  }
}

/**
 * The pill every state, verdict and severity is shown in. The one purely presentational primitive
 * in the catalogue.
 *
 * `running` keeps the Java's pulsing dot (CSS `.proving::before`, an animation on `content`),
 * because a page that is live has to say which row is MOVING and a static blue pill does not. The
 * animation is a Tailwind utility rather than a token — there is no token for motion — and the
 * portal's `prefers-reduced-motion` rule already switches it off for readers who asked.
 */
export function Pill({ tone, href, children }: PillProps) {
  const body = (
    <>
      {tone === 'running' ? (
        <span className="animate-pulse" aria-hidden="true">
          {'● '}
        </span>
      ) : null}
      {children}
    </>
  )
  if (href === undefined) {
    return <span style={pillStyle(tone)}>{body}</span>
  }
  return (
    <a href={href} style={pillStyle(tone)}>
      {body}
    </a>
  )
}
