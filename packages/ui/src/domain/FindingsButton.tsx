import type { Style } from '../primitives'

export type FindingsButtonProps = {
  /**
   * How many findings still stand: `holds` PLUS `unjudged`, never `holds` alone.
   *
   * `holding()` (Dashboard 850-859) counts both and excludes only `refuted`, for a reason worth
   * keeping in the type: a finding the critic never reached is not one it dismissed. Counting only
   * `holds` would let a critic that could not be reached suppress a warning by failing — the
   * failure mode where the quietest page is the broken one.
   */
  open: number
}

/**
 * The link is drawn at every count; only the NUMBER is suppressed at zero (Dashboard 2439-2443).
 * A badge reading zero on a clean run is a control that teaches a reader to ignore it, and this one
 * has to still mean something on the day it says nineteen.
 */
const LINK = (some: boolean): Style => ({
  position: 'relative',
  display: 'inline-flex',
  alignItems: 'center',
  fontSize: '1.25rem',
  lineHeight: 1,
  padding: '0.2rem 0.35rem',
  borderRadius: '5px',
  textDecoration: 'none',
  // Amber only while something stands. `--verdict-holds` and not `--state-needs-review`: the badge
  // counts the supervisor's findings, which are a different vocabulary from a marker's disposition
  // even where the two tokens happen to resolve to the same amber today.
  color: some ? 'var(--verdict-holds)' : 'var(--text-tertiary)',
})

const BADGE: Style = {
  position: 'absolute',
  top: '-0.15rem',
  right: '-0.3rem',
  background: 'var(--verdict-holds)',
  color: 'var(--bg-canvas)',
  borderRadius: '999px',
  padding: '0 0.3rem',
  fontSize: '0.62rem',
  fontWeight: 600,
  lineHeight: 1.35,
  minWidth: '1rem',
  textAlign: 'center',
}

/**
 * WHAT THE SUPERVISOR HAS FOUND, AS A COUNT ON EVERY PAGE.
 *
 * The findings were a banner on the front page, and they were there for a reason worth not losing:
 * reported, judged, and left unread for ten hours while somebody rediscovered the same faults by
 * hand. Moving them behind a click is exactly how that happened the first time — so the number
 * comes to the header, where it is on all ten screens rather than only on the one the banner
 * reached.
 *
 * CONSEQUENCE FOR THE API, and it is not small: every screen's payload has to carry `openFindings`.
 * The Java could pretend otherwise because `findingsButton()` reached a module-level `root` (2438)
 * and re-read `overwatch.jsonl` itself; `head()` took no results directory, so the count was
 * smuggled in through a global. A component cannot do that and must not: props in, markup out.
 */
export function FindingsButton({ open }: FindingsButtonProps) {
  const some = open > 0
  const what = some
    ? `${open} finding(s) the critic has not dismissed`
    : 'what is wrong with the pipeline'
  return (
    <a href="/overwatch" style={LINK(some)} title={what} aria-label={what}>
      <span aria-hidden="true">{'⚠'}</span>
      {/* The count is already in the accessible name; the badge is the same fact, drawn. */}
      {some ? (
        <span style={BADGE} aria-hidden="true">
          {open}
        </span>
      ) : null}
    </a>
  )
}
