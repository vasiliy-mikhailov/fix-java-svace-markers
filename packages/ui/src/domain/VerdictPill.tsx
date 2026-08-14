import type { Verdict } from '@fsm/types'
import type { Style } from '../primitives'

export type VerdictPillProps = { verdict: Verdict }

/**
 * VERDICTS HAVE THEIR OWN TOKENS, AND THAT IS THE POINT OF THIS FILE.
 *
 * `reported()` (Dashboard 800-803) borrowed marker dispositions for a supervisor's verdict:
 * `holds → settled`, `unjudged → needs-review`, `refuted → infra`. The last one is the bug worth
 * naming: `infra` everywhere else in this app means "the prove threw, nothing ran" — the OPPOSITE
 * of "the critic read this and knocked it down". A refuted finding is the system working.
 *
 * The borrowing also drifted, as borrowed vocabulary does: the same three words are drawn a second
 * time in the front-page banner (CSS 75-78) as red / amber / grey, so `holds` was green in one
 * place and red in the other. `--verdict-*` exists so there is one answer.
 *
 * WHY THIS DOES NOT GO THROUGH `Pill`, despite markup that is nearly identical: `Pill`'s six tones
 * resolve to `--state-*` tokens, so routing a verdict through it would silently re-borrow the
 * marker vocabulary this component exists to stop borrowing. The shape differs too, and
 * deliberately — a fixed-width, square-cornered label so the three verdicts line up as a column in
 * the findings list (the Java's `.v`, CSS 73-74), where a marker's round pill never appears.
 */
const TOKEN: Record<Verdict, string> = {
  holds: 'var(--verdict-holds)',
  unjudged: 'var(--verdict-unjudged)',
  refuted: 'var(--verdict-refuted)',
}

/** What each word means, for a reader who has not read `holding()`. */
const MEANS: Record<Verdict, string> = {
  holds: 'the critic read this and it survived',
  unjudged: 'nobody has judged this yet — it counts as open',
  refuted: 'the critic knocked this down',
}

function pill(verdict: Verdict): Style {
  return {
    // Set once, read three times, so text, wash and edge cannot drift apart.
    '--verdict-tone': TOKEN[verdict],
    display: 'inline-block',
    minWidth: '4.7rem',
    padding: '0 0.3rem',
    borderRadius: '3px',
    fontSize: '0.68rem',
    textAlign: 'center',
    whiteSpace: 'nowrap',
    color: 'var(--verdict-tone)',
    background: 'color-mix(in srgb, var(--verdict-tone) 14%, transparent)',
    border: '1px solid color-mix(in srgb, var(--verdict-tone) 32%, transparent)',
  }
}

/**
 * What the supervisor's critic made of one finding.
 *
 * All three stay on the page and none of them is hidden: what the watcher wrongly worries about is
 * itself a thing to fix in the watcher. Only the COUNT in the header drops `refuted`
 * (`FindingsButton`), because a number that only ever grows is a number nobody reads twice.
 */
export function VerdictPill({ verdict }: VerdictPillProps) {
  return (
    <span style={pill(verdict)} title={MEANS[verdict]}>
      {verdict}
    </span>
  )
}
