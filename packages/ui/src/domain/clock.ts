/**
 * HOW LONG THE MACHINE TOOK, IN ONE FORMATTER — Java `clock()` 2381-2385.
 *
 * Two components print a duration on the markers screen: {@link TimeSpent} for one marker's span and
 * `RunProgress` for the run's elapsed and its eta. In the Java they called the same static method, so
 * they could not disagree; two hand-rolled copies in two `.tsx` files is exactly the arrangement that
 * left this codebase with two `ago` formatters that crossed into minutes at different seconds and two
 * human-minute formats on one screen (catalogue bug #11). One copy, in a module of its own, so a
 * third caller has somewhere obvious to import it from.
 *
 * The shape is the Java's and is deliberately not `Intl.RelativeTimeFormat`: `2m 14s` and `1h 07m`
 * are read off a table of runs, and a formatter that rounds `74s` to "a minute" makes two markers
 * that differ by a factor of two look alike.
 *
 * `Math.trunc`, not `Math.floor`: Java integer division truncates toward zero, so a negative input —
 * which means a clock that went backwards, not time running in reverse — comes out as `0s` here too
 * rather than `-1s`.
 */
export function clock(millis: number): string {
  const s = Math.trunc(millis / 1000)
  if (s < 60) {
    return `${s}s`
  }
  if (s < 3600) {
    return `${Math.trunc(s / 60)}m ${s % 60}s`
  }
  return `${Math.trunc(s / 3600)}h ${Math.trunc((s % 3600) / 60)}m`
}
