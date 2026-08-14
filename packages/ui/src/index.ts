/**
 * THE PACKAGE'S ONE PUBLIC SURFACE. Two tiers, in dependency order.
 *
 * `./primitives` knows nothing about markers; `./domain` is everything that does. The two barrels
 * stay separate below rather than being flattened into a hand-written list here, because the tier a
 * component belongs to is a fact worth being able to read off an import — and because the split is
 * what stops a `Pill` growing a `disposition` prop the next time somebody is in a hurry.
 *
 * Star re-exports are safe at THIS level and are not inside the barrels: the two tiers share no
 * exported name (a `MarkerAccount` is not an `Account`, a `FindingTally` is not a `Tally`), so
 * there is nothing here for ESM to drop as ambiguous. Within a tier, where two components can
 * plausibly want one name, the barrels name every export instead.
 */

export * from './primitives'
export * from './domain'
