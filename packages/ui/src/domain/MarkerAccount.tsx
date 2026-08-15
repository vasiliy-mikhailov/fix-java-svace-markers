import { Prose } from '../primitives'

export type MarkerAccountProps = {
  /**
   * THE SECOND HALF OF `summary.txt`, AND ONLY THE SECOND HALF.
   *
   * `summary()` (1541-1554) splits the file at the first blank line: `[0]` is the one-line headline
   * the markers table shows, `[1]` is this account. WITH NO BLANK LINE BOTH HALVES ARE THE WHOLE
   * FILE, so a lane interpreter that wrote a single paragraph made the list and this page say the
   * same thing twice — and neither page could tell, because both were handed the same array and
   * picked an index out of it.
   *
   * So the payload sends two named fields and this component is handed the second one. It cannot
   * re-split and cannot re-derive; if the two are identical that is now visible in the response,
   * where it can be asserted on.
   */
  text: string
}

/**
 * The lane interpreter's account of what happened to this marker.
 *
 * BLANK RENDERS NOTHING. `Marker.summary` is `string | null` — "absent until it has run" — and an
 * empty bordered paragraph claims the interpreter ran and had nothing to say. The sentence for the
 * absence belongs to the screen, which knows whether the prove is still going (see `EmptyNote`: the
 * copy is never a prop default, because the default is what gets shown on the page nobody wrote
 * copy for).
 */
export function MarkerAccount({ text }: MarkerAccountProps) {
  if (text.trim().length === 0) {
    return null
  }
  return <Prose>{text}</Prose>
}
