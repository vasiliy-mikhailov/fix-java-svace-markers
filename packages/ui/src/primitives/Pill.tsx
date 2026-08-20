/**
 * THE PILL, FROM `ratchet-ui`.
 *
 * Same six tones under the same names, the same `--pill-tone` set-once-read-three-times, the same
 * `color-mix` wash and edge, the same pulsing dot. The whole difference was the TONE map's
 * right-hand column, which now lives in `domain.css` where the palette belongs — plus `title`,
 * which the shared one has and this repository's did not.
 *
 * THE PULSING DOT IS A TAILWIND UTILITY, and `globals.css` has a `@source` line for the installed
 * package because of it. Without that line the class is simply not emitted, the dot stops moving,
 * and nothing anywhere fails.
 */
export { Pill, type PillProps, type PillTone } from 'ratchet-ui/components'
