/**
 * THE TALLY, FROM `ratchet-ui`, and it is a strict superset of what was here.
 *
 * It adds `tone` — `plain` by default, which is what every existing call site already is and
 * resolves to the colour `body` already sets, so nothing on the page moves. `FindingTally` is the
 * obvious customer for the tone: holds, unjudged, refuted.
 *
 * `STRIP` comes with it, and this repository kept two byte-identical private copies of it. Note
 * that `FindingTally`'s own STRIP is a DIFFERENT object — no page gutter — and stays private.
 */
export { STRIP, Tally, type TallyProps } from 'ratchet-ui/components'
