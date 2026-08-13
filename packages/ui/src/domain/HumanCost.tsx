import type { Style } from '../primitives'

export type HumanCostProps = {
  /**
   * What the estimator said a person would have spent, in minutes, summed over every attempt.
   *
   * ZERO IS TWO ANSWERS AND THEY LOOK THE SAME: never priced, and priced at nothing. `num()` (2388)
   * turns an estimator that answered in prose into 0, so the field cannot tell them apart either —
   * and an em dash for both is the honest render. A "0m" here would be this component inventing a
   * measurement out of a parse failure.
   */
  minutes: number
}

const QUIET: Style = { color: 'var(--text-tertiary)', fontSize: '11px' }

/**
 * Minutes as a person reads them — Java `hm()` 2377-2379.
 *
 * ONE FORMAT ON THE SCREEN, AND THIS IS IT (catalogue bug #11, the second half). The markers page
 * printed the same quantity two ways: the human-equivalent tile in the counts strip was always
 * `Xh Ym` (1706-1708) and this column dropped the hours under an hour, so 45 minutes read "0h 45m"
 * at the top of the page and "45m" in the table below it. Both are defensible; having both is not,
 * because the tile is a total of the column and a reader comparing them has to translate first.
 *
 * The pick is `hm()`, and it is enforced by construction rather than by discipline: `StateCounts`
 * renders its tile with this very component, so there is no second formatter to keep in step.
 * "0h 45m" loses to "45m" because the leading zero is a unit that is not there.
 */
function hm(minutes: number): string {
  return minutes < 60 ? `${minutes}m` : `${Math.trunc(minutes / 60)}h ${minutes % 60}m`
}

/**
 * WHAT THIS WOULD HAVE COST A PERSON, in the last column of the markers table and in the run's total.
 *
 * The figure is an agent's estimate of human work avoided, not machine time — {@link TimeSpent} is
 * the machine. They sit next to each other because the pair is the argument for the whole pipeline,
 * and they are different components because one is measured and the other is claimed.
 */
export function HumanCost({ minutes }: HumanCostProps) {
  return minutes > 0 ? <>{hm(minutes)}</> : <span style={QUIET}>—</span>
}
