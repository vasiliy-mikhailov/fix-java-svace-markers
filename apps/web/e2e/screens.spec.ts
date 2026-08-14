import { expect, test, type ConsoleMessage, type Page } from '@playwright/test'

/**
 * WHAT ONLY A BROWSER CAN SEE.
 *
 * <p>Two bugs in this port were invisible to every other check. A page typed its payload with an
 * envelope the endpoint does not send and threw on the first destructure — it compiled, it
 * prerendered, and the route returned 200. And the zone, mounted alongside the pages it replaces,
 * served those pages instead: six routes answering 200 with the wrong application entirely.
 *
 * <p>Both were found by looking at what was actually on the screen. That is what these do, and it is
 * why they run the real Java over a real record rather than a mocked API — the failures were the
 * wire disagreeing with the page, which a mock cannot reproduce because a mock is written to agree.
 */

/** Console errors, collected per test, so a screen cannot fail quietly. */
function watchConsole(page: Page): string[] {
  const bad: string[] = []
  page.on('console', (m: ConsoleMessage) => {
    if (m.type() === 'error') {
      bad.push(m.text())
    }
  })
  page.on('pageerror', e => bad.push(`uncaught: ${e.message}`))
  return bad
}

const SCREENS = [
  { path: '/', name: 'markers' },
  { path: '/trace/', name: 'the whole trace' },
  { path: '/overwatch/', name: 'the supervisor' },
  { path: '/chat/', name: 'the chat' },
  { path: '/settings/', name: 'settings' },
] as const

for (const screen of SCREENS) {
  test(`${screen.name} renders, with nothing thrown`, async ({ page }) => {
    const errors = watchConsole(page)
    await page.goto(screen.path)

    // WAIT FOR THE LOADING STATE TO GO, not for the page to have some text on it.
    //
    // Every screen fetches in an effect, so the document is complete — and the header, the icons and
    // the word "markers" are already painted — long before any data arrives. The first version of
    // this waited for forty characters of body text and got them from the loading state itself, then
    // asserted the page was not loading. It failed against a page that renders perfectly well.
    // WAIT FOR CONTENT, POSITIVELY. Twice now this waited for the wrong thing: first for forty
    // characters of body text, which the loading state supplies on its own, then for the loading
    // text to disappear — and a screen whose loading wording differs satisfies that the instant it
    // opens. Both passed the wait and asserted against an empty page.
    //
    // So the condition is what a rendered screen actually has: real content, and no loading or
    // failure wording anywhere in it.
    await page.waitForFunction(() => {
      const t = (document.body.textContent ?? '').replace(/\s+/g, ' ').trim()
      if (/reading the run|reading what it has said|loading/i.test(t)) return false
      return t.length > 120
    }, undefined, { timeout: 15_000 })

    const text = (await page.locator('body').innerText()).toLowerCase()
    expect(text, `${screen.name} says it could not read`).not.toContain('could not read')

    expect(errors, `${screen.name} logged errors: ${errors.join(' | ')}`).toEqual([])
  })
}

test('the markers table draws the run the API reports', async ({ page }) => {
  const errors = watchConsole(page)
  const api = await (await page.request.get('/api/index')).json()
  await page.goto('/')
  await page.waitForFunction(() => document.querySelectorAll('tr').length > 1, undefined, {
    timeout: 15_000,
  })

  // A row per marker. Not "some rows" — the count is the assertion, because a table that silently
  // drops the tail looks exactly like a table that does not.
  const rows = await page.locator('tbody tr').count()
  expect(rows, 'one row per queued marker').toBe(api.run.total)

  // And the totals on the page are the record's own, not a recount the client did differently.
  await expect(page.getByText(String(api.run.total), { exact: false }).first()).toBeVisible()
  expect(errors).toEqual([])
})

test('a state the record holds reaches the screen', async ({ page }) => {
  const api = await (await page.request.get('/api/index')).json()
  await page.goto('/')
  await page.waitForFunction(() => document.querySelectorAll('tr').length > 1, undefined, {
    timeout: 15_000,
  })
  const shown = (await page.locator('body').innerText())
  for (const state of Object.keys(api.run.countsByState)) {
    expect(shown, `${state} is in the record and must be on the page`).toContain(state)
  }
})

test('a question that fails is answered in words, not in a stack trace', async ({ page }) => {
  // WHAT A PERSON WAS SHOWN: they asked "show me number of markers verified in last 5 hours" and got
  // back `could not answer: java.lang.IllegalArgumentException: text cannot be null or blank`. The
  // cause was a tool reading a zero-byte file (pinned in AnEmptyFileIsAnAnswerTest); this pins the
  // OTHER half, which is that no failure whatsoever reaches the page as a Java class name. That half
  // needs a browser and a real server: the reply is composed in Java, written to a file, and read
  // back by a poll, and nothing below the whole round trip can tell you what the reader ends up with.
  //
  // The fixture server's model points at a closed port, so the ask fails immediately and always — the
  // test is about presentation, and any failure at all is the right input for it.
  const asked = `e2e ${Date.now()} how many markers verified in the last 5 hours`
  const posted = await page.request.post('/chat', { form: { q: asked } })
  expect(posted.ok(), 'POST /chat').toBe(true)

  await expect
    .poll(
      async () => {
        const chat = await (await page.request.get('/api/chat')).json()
        const mine = chat.turns.findIndex((t: { text: string }) => t.text === asked)
        // The reply is the turn after the question; `answering` drops when it has been written.
        return mine >= 0 && !chat.answering ? (chat.turns[mine + 1]?.text ?? null) : null
      },
      { timeout: 30_000, message: 'the supervisor never wrote a turn for the question' },
    )
    .not.toBeNull()

  const chat = await (await page.request.get('/api/chat')).json()
  const reply: string = chat.turns[chat.turns.findIndex((t: { text: string }) => t.text === asked) + 1]
    .text

  for (const leak of ['java.lang.', 'Exception', 'at tech.mikhailov', '\tat ']) {
    expect(reply, `a person asked a question and was shown "${leak}"`).not.toContain(leak)
  }
  expect(reply.length, 'a failure with nothing in it is the blank page again').toBeGreaterThan(20)

  // And it must reach the SCREEN, not just the endpoint — the page renders turns itself.
  await page.goto('/chat/')
  await expect(page.getByText(asked)).toBeVisible({ timeout: 15_000 })
  const body = await page.locator('body').innerText()
  expect(body, 'the chat screen is showing a Java class name').not.toContain('java.lang.')
})

test('a chip on the chain says its role, and still names its agent', async ({ page }) => {
  // A HELPER THAT PASSES ITS OWN TEST CAN STILL BE UNWIRED. `roleWord` was unit-tested green, the
  // caller passed it, the prop was on the type — and the component went on rendering the full name,
  // because the one line that reads the prop had never been changed. Typecheck was happy: an unused
  // prop is legal. Only something that looks at the rendered chip can tell the difference.
  const api = await (await page.request.get('/api/index')).json()
  await page.goto(`/marker/?k=${encodeURIComponent(api.markers[0].key)}`)
  const nav = page.locator('nav[aria-label="the chain"]')
  await expect(nav).toBeVisible({ timeout: 15_000 })

  // The stage is named on the box, so the chip carries the role and nothing else.
  for (const [role, word] of [['planner', 'plan'], ['doer', 'do'], ['verifier', 'verify']]) {
    const chip = nav.locator(`a[title^="reproduce-${role} "]`)
    await expect(chip, `the reproduce-${role} chip`).toHaveCount(1)
    // `textContent` with the count stripped, because the run count is a child of the same anchor.
    const text = ((await chip.textContent()) ?? '').replace(/[0-9]+$/, '').trim()
    expect(text, 'a chip under REPRODUCE repeating the word REPRODUCE').toBe(word)
  }

  // NOTHING IS LOST BY SHORTENING: the full name is still what the chip is titled and where it
  // goes, so the agent stays findable by hover and reachable by click.
  const planner = nav.locator('a[title^="reproduce-planner "]')
  expect(await planner.getAttribute('href')).toContain('reproduce-planner')
})

test('the findings badge agrees with the endpoint that feeds it', async ({ page }) => {
  const badges = await (await page.request.get('/api/badges')).json()
  await page.goto('/')
  await page.waitForFunction(
    () => (document.body.textContent ?? '').length > 40, undefined, { timeout: 15_000 })

  // The badge is drawn only when something stands — a badge reading zero teaches a reader to ignore
  // it, and it has to still mean something on the day it says nineteen.
  if (badges.findings > 0) {
    await expect(page.getByText(String(badges.findings), { exact: true }).first()).toBeVisible()
  }
})

test('every screen is the zone, not the pages it replaced', async ({ page }) => {
  // THE BUG THIS EXISTS FOR. A route registered at `/ui/` beat the zone's own `/ui`, and six routes
  // answered 200 with the old Java dashboard. The status code said nothing; the body said all of it.
  for (const screen of SCREENS) {
    const response = await page.request.get(screen.path)
    expect(response.status(), screen.path).toBe(200)
    const body = await response.text()
    expect(body, `${screen.path} served the old dashboard`).not.toContain('box-sizing:border-box')
    expect(body, `${screen.path} is not the exported zone`).toContain('_next')
  }
})

test('an unknown path is the zone\'s own 404', async ({ page }) => {
  const response = await page.request.get('/no-such-page')
  expect(response.status()).toBe(404)
})

test('everything the page references actually resolves', async ({ page }) => {
  // THE TEST THIS SUITE DID NOT HAVE, and the bug it would have caught in a second.
  //
  // The image built the export with BASE_PATH=/ui, left over from when both UIs were up. Every
  // asset URL and every fetch in the bundle then carried that prefix, against a server serving the
  // root — so the page loaded and everything it asked for 404'd. The suite stayed green because it
  // builds the export itself and never sees the image's build.
  //
  // Watching the responses rather than the markup catches it whatever the cause: a wrong base path,
  // a renamed route, a bundle referring to a chunk that was not copied in.
  const missing: string[] = []
  page.on('response', r => {
    if (r.status() >= 400) missing.push(`${r.status()} ${new URL(r.url()).pathname}`)
  })
  for (const screen of SCREENS) {
    await page.goto(screen.path)
    await page.waitForLoadState('networkidle')
  }
  expect(missing, `the page asked for things that are not there: ${missing.join(', ')}`).toEqual([])
})

test('the bundle is built for the path it is served from', async ({ page }) => {
  // The same fault, caught statically: an asset prefix that is not where the server serves assets
  // means a page that renders and does nothing.
  const html = await (await page.request.get('/')).text()
  const refs = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map(m => m[1] ?? '')
  const local = refs.filter(u => u.startsWith('/'))
  expect(local.length, 'the export references its own assets').toBeGreaterThan(0)
  for (const url of local) {
    const response = await page.request.get(url)
    expect(response.status(), `${url} is referenced by / and is not served`).toBeLessThan(400)
  }
})
