import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test } from '@playwright/test'

/**
 * A CLASS THAT MOVED INTO `node_modules` AND STOPPED BEING EMITTED.
 *
 * `Pill` puts Tailwind's pulse utility on the dot that marks a row still moving. It is the only
 * Tailwind animation in `packages/ui`, and when `Pill` came from `ratchet-ui` instead of from this
 * repository the class moved somewhere `globals.css` did not scan.
 *
 * NOTHING FAILS WHEN THAT HAPPENS. Tailwind does not warn about a glob matching nothing, the build
 * succeeds, every test passes, and the dot simply stops moving. It is not even in the exported HTML
 * to notice: a pulsing pill exists only at run time, from fetched data.
 *
 * THIS TEST USED TO PASS IN EXACTLY THE STATE IT EXISTS TO CATCH, and its own header said so without
 * being able to explain it: deleting the `@source` line left the utility in the stylesheet anyway.
 * The route was Tailwind's automatic source detection, which scans every readable file in the
 * project — including THIS ONE, which spelled the utility twice, once in prose and once in the
 * assertion. The test emitted the class it then grepped for.
 *
 * Two things fix that and both are asserted below, because either alone rots:
 *
 *   - `source(none)` in `globals.css`, so only the three explicit globs are scanned;
 *   - the literal never written in any scanned tree — and never in this file either, which is why
 *     it is assembled from parts below. Today `apps/web/e2e` happens to sit outside every glob, so
 *     spelling it here would be safe; it would stop being safe the moment somebody adds a glob, and
 *     nothing would say so.
 *
 * Measured, on this tree: with the dist glob deleted and detection on, the class is present and this
 * test passes — vacuous. With the glob deleted and `source(none)` on, the class is absent and it
 * fails. That is the control, and it is the reason the assertions below are worth anything.
 */

/** Never written whole, in any file a glob could ever reach. See the note above. */
const UTILITY = ['animate', 'pulse'].join('-')

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
    .toContain(`.${UTILITY}`)
  expect(css, 'the utility is emitted but its keyframes are not').toContain('@keyframes pulse')
})

test('and the grep above still means something', () => {
  // WITHOUT THIS, THE TEST ABOVE IS ONE EDIT FROM GOING QUIET AGAIN. It asserts a property of the
  // BUILD CONFIGURATION rather than of the output, which is the only way to keep a grep honest.
  const css = readFileSync(join(process.cwd(), 'app', 'globals.css'), 'utf8')
  expect(css, 'automatic source detection scans the whole project, including the file asserting '
    + 'this — so the utility would be emitted by the test that greps for it')
    .toContain('source(none)')
})
