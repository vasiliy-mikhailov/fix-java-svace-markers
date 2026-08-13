import { configDefaults, defineConfig } from 'vitest/config'

/**
 * VITEST OWNS `tests/`; PLAYWRIGHT OWNS `e2e/`. THEY MUST NOT COLLECT EACH OTHER.
 *
 * Vitest's default pattern is every `*.spec.ts` under the package, which swept up the Playwright
 * suite the moment it was added. Playwright's `test()` throws when it is called by anything other
 * than its own runner, so `pnpm -r test` has been failing on a file that is not broken and is not
 * even meant to run there — 14 real tests passing, one red mark, exit 1.
 *
 * That is worse than a missing test. The e2e suite exists to run every round; a run that is red for
 * a reason everybody has learned to ignore is a run whose next real failure gets ignored with it.
 */
export default defineConfig({
  esbuild: { jsx: 'automatic' },
  test: {
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})
