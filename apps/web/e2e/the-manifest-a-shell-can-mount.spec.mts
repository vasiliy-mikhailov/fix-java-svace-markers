import { expect, test } from '@playwright/test'

/**
 * `/.well-known/microfrontend.json`, NOT `/api/manifest` — the manifest is a well-known document a
 * shell fetches before it knows anything else about this zone, so it is deliberately outside the
 * API surface the manifest itself then describes.
 */
const MANIFEST = '/.well-known/microfrontend.json'
import { checkManifest, describe as describeProblems, type Manifest } from '@fsm/types'

/**
 * THE ONE CLASS OF FAILURE A TYPE CANNOT CATCH.
 *
 * `Zone.manifest()` is a hand-formatted Java text block: four nav items, one badge definition, eight
 * string fields. Everything in it can be the right type and the document can still be wrong in the
 * way that matters — a nav item naming a badge the manifest does not define. The shell follows the
 * name, gets `undefined`, polls nothing, and draws no count. Nothing errors. Nobody finds out until
 * somebody notices a number that never appears.
 *
 * WHAT GUARDED IT BEFORE WAS BRACE COUNTING. `MountedBySomebodyElseTest` checks field presence by
 * substring and proxies "this is valid JSON" with
 * `assertEquals(count(manifest, '{'), count(manifest, '}'))`. That passes for a document with every
 * brace in the wrong place, and it cannot see the badge cross-reference at all.
 *
 * `checkManifest` is `ratchet-ui`'s and it does exactly that cross-check. Adopting it cost a
 * dependency line, because the package's root entry reaches no React — a promise it makes so that a
 * server or a test can use the wire half without resolving a React version.
 *
 * AGAINST THE RESPONSE THE SERVER REALLY SENT, not a fixture of it. A validator run over a fixture
 * checks that somebody once wrote a correct document down.
 */
test('serves a manifest a shell can mount', async ({ page }) => {
  const response = await page.request.get(MANIFEST)
  expect(response.status(), '/api/manifest must answer at all').toBe(200)

  const manifest = (await response.json()) as Manifest
  expect(describeProblems(checkManifest(manifest))).toBe('no problems')
})

test('and every badge the manifest promises is actually answered', async ({ page }) => {
  // THE HALF `checkManifest` CANNOT SEE, because it is a fact about a different endpoint. The
  // manifest says where a count comes from; only the count's own endpoint can say whether it does.
  const manifest = (await (await page.request.get(MANIFEST)).json()) as Manifest
  const named = Object.entries(manifest.badges)
  expect(named.length, 'a manifest with no badges makes no promises to check').toBeGreaterThan(0)

  for (const [name, badge] of named) {
    const answer = await page.request.get(badge.endpoint)
    expect(answer.status(), `${name} points at ${badge.endpoint}`).toBe(200)
    const body = (await answer.json()) as Record<string, unknown>
    expect(body, `${name} names field "${badge.field}", which ${badge.endpoint} does not answer`)
      .toHaveProperty(badge.field)
  }
})
