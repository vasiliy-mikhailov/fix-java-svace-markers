import type { Severity } from '@fsm/types'
import type { Style } from '../primitives'

export type SeverityBadgeProps = {
  /**
   * NULL IS A REAL ANSWER AND NOT A MISSING PROP.
   *
   * Severity is not in the marker key — a key is `repo|file|line|checker`, and adding a fifth field
   * would change every key and re-prove the whole queue to display one word. So it is reference
   * data, read from `results/severities.tsv` and joined on `basename|line|checker` (Dashboard
   * 832-841, 1734). That file covers the 282 markers the analyser run reported; the other 74 are
   * `src/it` and `src/test`, which it excluded.
   *
   * They get an em dash rather than a guess: a table that prints Minor for everything it does not
   * know about is worse than one that admits the gap. Hence `Severity | null` and not `Severity?` —
   * "unknown" is a value this component renders, not a call it can be spared.
   *
   * WORTH KNOWING AT THE JOIN, not here: the key uses the file BASENAME, not its path, so two
   * same-named files in different packages collide and can inherit each other's severity. This
   * component cannot see that and does not pretend to.
   */
  severity: Severity | null
}

/**
 * Severity → token, spelled out rather than computed.
 *
 * The Java lowercased the value into a class name (1750), which is why `.sev.normal` sits in the
 * stylesheet (CSS 60) for a severity nothing writes: a computed name fails silently, both when the
 * value is dead and when it is new. A table means a severity nobody added a token for is a
 * compile error.
 */
const TOKEN: Record<Severity, string> = {
  Critical: 'var(--severity-critical)',
  Major: 'var(--severity-major)',
  Normal: 'var(--severity-normal)',
  Minor: 'var(--severity-minor)',
}

function chip(severity: Severity): Style {
  return {
    // Set once, read three times, so text, wash and edge cannot drift apart.
    '--severity-tone': TOKEN[severity],
    display: 'inline-block',
    fontSize: '11px',
    padding: '2px 6px',
    // Square-ish, unlike a state's 20px pill: the two sit in adjacent columns of the same row and
    // the shape is what tells a scanning reader which column they are in.
    borderRadius: '3px',
    whiteSpace: 'nowrap',
    color: 'var(--severity-tone)',
    background: 'color-mix(in srgb, var(--severity-tone) 14%, transparent)',
  }
}

const ABSENT: Style = { color: 'var(--text-tertiary)' }

/**
 * What the analyser thought a marker was worth, in the first column of the markers list.
 *
 * IT PICKS ITS OWN COLOUR AND NEVER TAKES A CLASS. The Java passed `class='sev <lowercased>'` from
 * the row-rendering loop, so the colour logic lived in a string concatenation inside a table and
 * could not be tested apart from the table.
 *
 * The absent case is drawn as bare text, not as a chip in the default grey the Java used (CSS 56):
 * a chip is the shape this page uses to say "here is a value", and drawing one around a dash
 * asserts that the analyser answered when it never looked.
 */
export function SeverityBadge({ severity }: SeverityBadgeProps) {
  if (severity === null) {
    return (
      <span style={ABSENT} title="not in severities.tsv — the analyser run did not cover this file">
        {'—'}
      </span>
    )
  }
  return <span style={chip(severity)}>{severity}</span>
}
