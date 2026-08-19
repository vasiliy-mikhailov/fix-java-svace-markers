import { expect, test } from '@playwright/test'

/**
 * THE STRIP IS THE PROGRAM, SERVED.
 *
 * The chain a reader sees used to be a list in TypeScript with a second list of stage labels beside
 * it, neither of which anything could check against the run. `/api/marker` now sends `chain`, walked
 * off the tree `Prove` executes, and the page draws that.
 *
 * The point is not that five boxes appear. It is that they appear BECAUSE the server said so — so
 * these assert the page against the ENDPOINT rather than against a constant compiled into it, and
 * that a chain the server does not send is a strip the page does not draw.
 *
 * ON LINKS, NOT ON WORDS. A stage title is prose that appears elsewhere on the page; the tab link
 * carrying an agent's own name is structural, and it is what a reader clicks.
 */

const KEY =
  'https://github.com/WebGoat/WebGoat.git|src/main/java/org/owasp/webgoat/lessons/sqlinjection/introduction/SqlInjectionLesson5b.java|41|TAINTED_PTR'

const MARKER = `/marker?k=${encodeURIComponent(KEY)}`

type Served = { chain?: { title: string; steps: { name: string; role: string }[] }[] }

async function chain(request: import('@playwright/test').APIRequestContext): Promise<Served> {
  return (await request.get(`/api/marker?k=${encodeURIComponent(KEY)}`)).json()
}

test.describe('the chain strip', () => {
  test('is the chain the endpoint sent, agent for agent', async ({ page, request }) => {
    const served = await chain(request)
    const titles = (served.chain ?? []).map(s => s.title)
    expect(titles, 'the endpoint has to send it, or the page has nothing to draw')
      .toEqual(['reproduce', 'fix', 'propose', 'argue', 'price'])

    await page.goto(MARKER)
    // The document is fetched after the page mounts, so nothing is drawn on arrival.
    await expect(page.locator('a[href*="a=reproduce-planner"]').first()).toBeVisible()

    for (const stage of served.chain ?? []) {
      for (const step of stage.steps) {
        await expect(page.locator(`a[href*="a=${step.name}"]`).first(),
          `${step.name} is in the served chain and not linked from the page`).toBeVisible()
      }
    }
  })

  test('draws nothing the endpoint did not send', async ({ page, request }) => {
    const served = await chain(request)
    const names = (served.chain ?? []).flatMap(s => s.steps.map(t => t.name))
    await page.goto(MARKER)
    await expect(page.locator('a[href*="a=reproduce-planner"]').first()).toBeVisible()

    // Every agent tab on the page is one the server named. A page drawing from its own list would
    // keep linking an agent after it was renamed in the Java, and the link would 404 silently.
    const linked = await page.locator('a[href*="a="]').evaluateAll(nodes =>
      nodes.map(n => new URL((n as HTMLAnchorElement).href).searchParams.get('a') ?? ''))
    const stageTabs = linked.filter(a => /-(planner|doer|verifier)$/.test(a))
    for (const tab of stageTabs) {
      expect(names, `${tab} is linked and the server never named it`).toContain(tab)
    }
  })

  test('a chain the server does not send is a strip the page does not draw', async ({ page }) => {
    // NO FALLBACK, DELIBERATELY. A default would be this package keeping a constant about a program
    // it does not contain, and the failure would be silent: the field stops arriving and the page
    // quietly draws yesterday's chain with nothing red.
    await page.route('**/api/marker?*', async route => {
      const response = await route.fetch()
      const body = await response.json()
      delete body.chain
      await route.fulfill({ response, body: JSON.stringify(body) })
    })
    await page.goto(MARKER)
    await expect(page.getByText('all markers')).toBeVisible()
    await expect(page.locator('body')).toContainText('TAINTED_PTR')
    expect(await page.locator('a[href*="a=reproduce-planner"]').count(),
      'the strip is absent rather than stale').toBe(0)
  })
})
