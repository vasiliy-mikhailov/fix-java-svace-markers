import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test } from '@playwright/test'

/**
 * A CLASS THAT MOVED INTO `node_modules` AND STOPPED BEING EMITTED.
 *
 * `Pill` renders `className="animate-pulse"` on the dot that marks a row still moving. It is the
 * only Tailwind utility in `packages/ui`, and when `Pill` came from `ratchet-ui` instead of from
 * this repository the class moved somewhere `globals.css` does not scan — its two `@source` globs
 * cover `../app` and `packages/ui/src`, and neither covers `node_modules`.
 *
 * <p>NOTHING FAILS WHEN THAT HAPPENS. Tailwind does not warn about a glob matching nothing, the
 * build succeeds, every test passes, and the dot simply stops moving. It is not even in the exported
 * HTML to notice: a pulsing pill exists only at run time, from fetched data, so there is nothing at
 * build time that could have told anyone.
 *
 * <p>So this reads the built stylesheet. `ratchet-ui` cannot catch this in its own CI — the failure
 * is in the consumer's build — which makes it exactly the kind of thing the consumer has to hold.
 *
 * <p>WHAT THIS DOES NOT PROVE, SAID PLAINLY. Deleting the `@source` line and rebuilding from clean
 * leaves the utility in the stylesheet anyway, so the failure the adoption guide measured on the
 * sibling's tree could not be reproduced here — Tailwind is finding the class by some route this
 * repository has and that one did not. The extra glob stays because it is correct and costs
 * nothing, but it is not what this test is holding. What it holds is the OUTPUT: the dot's utility
 * reaches the built stylesheet. That property is worth keeping however it is currently satisfied,
 * and the day it stops being satisfied is the day somebody needs to know.
 *
 * <p>The first version of this test passed with the glob deleted for a much stupider reason: the
 * comment in `globals.css` explaining the class spelled the class, and Tailwind scans that file, so
 * the prose emitted it. That is fixed there and is worth remembering — a guard can be satisfied by
 * the sentence describing it.
 */
test('the pulsing dot is still in the built stylesheet', () => {
  const chunks = join(process.cwd(), 'out', '_next', 'static', 'chunks')
  // NOTE THE DIRECTORY: Next 16 writes the stylesheet to `static/chunks`, not `static/css`.
  const css = readdirSync(chunks)
    .filter(f => f.endsWith('.css'))
    .map(f => readFileSync(join(chunks, f), 'utf8'))
    .join('\n')

  expect(css.length, 'no stylesheet was built, so this proves nothing').toBeGreaterThan(0)
  expect(css, 'add `@source "../../../packages/ui/node_modules/ratchet-ui/dist";` to globals.css — '
    + 'and note it is `dist`, not `src`: pnpm ships only what `files` names, so a glob at `src` '
    + 'scans one stylesheet, finds no class, and fails in exactly the same silence')
    .toContain('.animate-pulse')
  expect(css, 'the utility is emitted but its keyframes are not').toContain('@keyframes pulse')
})
