import { expect, test } from '@playwright/test'

/**
 * THE RECORD SHOWS EVERYTHING IT HOLDS, IN THE ORDER IT HAPPENED.
 *
 * The owner asked twice where the prompt was and both times the record HAD it — the page was the
 * problem, not the recording. So this drives the real page against the real Java over the fixture
 * and checks the two things a reader actually depends on: that nothing the record holds is missing
 * from the screen, and that what is on the screen is in the order it happened.
 *
 * Every assertion here stands for something that was wrong on a live page:
 *
 *   - the system prompt behind a shut `<details>` labelled "system (4072 chars)", on a page whose
 *     whole job that day was showing what an agent was asked;
 *   - a 16,680-character task drawn whole, because the clip counted NEWLINES and a checker note is
 *     paragraphs — so the bound that was supposed to make a lane scannable never bit;
 *   - "5,820 in / 3,745 out / 56.9s" printed ABOVE the thinking it had paid for, because the
 *     listener fires when the response lands and the content is written after it;
 *   - sixty-one rows carrying fifty-six React keys, because `agent:kind:<ms>` names both halves of
 *     one request when the connector writes them in the same millisecond.
 */

const KEY =
  'https://github.com/WebGoat/WebGoat.git|src/main/java/org/owasp/webgoat/lessons/sqlinjection/introduction/SqlInjectionLesson5b.java|41|TAINTED_PTR'

const RECORD = `/marker?k=${encodeURIComponent(KEY)}&a=trace`
/**
 * The same page with everything asked for in full.
 *
 * CLIPPED IS THE DEFAULT NOW, so the two tests below that check clipping use the plain URL. They
 * used to append `&fold=1`, and when the parameter was replaced they kept passing — against a page
 * whose default had become the thing they were asking for. A test that passes because the URL it
 * builds no longer means anything is a test that has stopped looking.
 */
const OPENED = `${RECORD}&open=1`

/** The feed's rows, in the order the page drew them. */
async function rows(page: import('@playwright/test').Page) {
  const feed = page.locator('[data-events]').first()
  await expect(feed).toBeVisible()
  return feed.locator(':scope > *')
}

/**
 * WHAT EACH ROW HOLDS, READ FROM THE DOM RATHER THAN FROM THE PAINT.
 *
 * The feed is uncapped on purpose and pays for it with `content-visibility: auto`, so the browser
 * skips layout for every row nobody has scrolled to. Those rows are present, findable by
 * find-in-page and addressable by `#e-<id>` — and their `innerText` is empty, because innerText is
 * a question about rendering. Asking it here measures where the viewport happens to be and reports
 * it as a record with three rows in it.
 */
async function contents(page: import('@playwright/test').Page) {
  const feed = await rows(page)
  return feed.evaluateAll(nodes => nodes.map(n => (n.textContent ?? '').replace(/\s+/g, ' ')))
}

test.describe('the record tab', () => {
  test('draws every row the record holds for this marker', async ({ page }) => {
    const seen: string[] = []
    page.on('response', r => {
      if (r.url().includes('/api/')) seen.push(r.url())
    })
    await page.goto(RECORD)
    const feed = await rows(page)
    await expect.poll(() => feed.count()).toBeGreaterThan(40)

    // THE PAGE'S OWN ACCOUNT OF WHAT IT READ. It used to say "N of them are this marker's",
    // because it was built from a window over the RUN and filtered — so it could only report the
    // part of the record it happened to have. It reads this marker's own file now, so the only
    // honest sentence is the whole count.
    const footer = await page.getByText(/That is the whole record/).innerText()
    const held = Number(/(\d+) event\(s\)/.exec(footer)?.[1] ?? '0')
    expect(held, 'the page must say how much of the record it holds').toBeGreaterThan(40)
    expect(await feed.count(), 'every row it holds is drawn').toBeGreaterThanOrEqual(held)
    expect(seen.some(u => u.includes('/api/marker/record')),
      'the record comes from the marker\'s own lane, not from a window over the run').toBe(true)
  })

  test('shows what was sent to the model, without opening anything', async ({ page }) => {
    await page.goto(RECORD)
    await rows(page)
    // The connector records the standing prompt and the task as they went. Both must be on the page
    // as text, not as a label and a byte count — those answer "how much" and never "is this it".
    const texts = await contents(page)
    const joined = texts.join('\n')
    expect(joined).toContain('JUDGE THIS AS IF IT WERE ABOUT TO SHIP')
    expect(joined).toContain('WHAT TAINTED_PTR REPORTS')
    expect(joined, 'the role is what tells one of these from another').toContain('system')
    expect(joined).toContain('user')
  })

  test('clips a long body rather than drawing it whole', async ({ page }) => {
    await page.goto(RECORD)
    await rows(page)
    // The task is one enormous line, which is the case the line-count bound waved through.
    const shown = await page.locator('pre').filter({ hasText: 'WHAT TAINTED_PTR REPORTS' }).first().innerText()
    expect(shown.length, 'a body drawn whole is a lane that cannot be scanned').toBeLessThan(2000)
    expect(shown, 'and the opening is still readable').toContain('WHAT TAINTED_PTR REPORTS')
    await expect(page.getByRole('button', { name: /show all/ }).first()).toBeVisible()
  })

  test('and the whole body is one click away, never truncated for good', async ({ page }) => {
    await page.goto(RECORD)
    await rows(page)
    // THE CONTROL THAT BELONGS TO THIS BODY, not the first one on the page: several rows clip, and
    // clicking somebody else's expander proves only that the page has more than one.
    const fold = page
      .locator('div')
      .filter({ has: page.locator('pre', { hasText: 'WHAT TAINTED_PTR REPORTS' }) })
      .last()
    const pre = fold.locator('pre').first()
    const clipped = (await pre.innerText()).length
    await fold.getByRole('button', { name: /show all/ }).click()
    await expect.poll(async () => (await pre.innerText()).length).toBeGreaterThan(clipped)
  })

  test('and `open=1` gives the whole body with no control left to press', async ({ page }) => {
    await page.goto(OPENED)
    await rows(page)
    const pre = page.locator('pre').filter({ hasText: 'WHAT TAINTED_PTR REPORTS' }).first()
    const shown = (await pre.innerText()).length
    expect(shown, 'the reader asked for everything and got a clip').toBeGreaterThan(2000)
  })

  test('puts what a call cost AFTER the reasoning it paid for', async ({ page }) => {
    await page.goto(RECORD)
    const texts = await contents(page)
    const thought = texts.findIndex(t => t.includes('Let me analyze this marker'))
    const cost = texts.findIndex(t => /5,820 in \/ 3,745 out/.test(t))
    expect(thought, 'the reasoning must be on the page').toBeGreaterThan(-1)
    expect(cost, 'and so must its price').toBeGreaterThan(-1)
    expect(cost, 'a record ordered by when a line was WRITTEN rather than by what happened put the '
      + 'bill above the work').toBeGreaterThan(thought)
  })

  test('says plainly when a generation was cut off at the cap', async ({ page }) => {
    await page.goto(RECORD)
    // LENGTH means the record above it is incomplete. It used to be indistinguishable from a model
    // that had finished, and every truncation was found by a human noticing a reply stopped
    // mid-sentence.
    const texts = await contents(page)
    expect(texts.some(t => t.includes('was cut off at the cap')),
      'LENGTH means the record above it is incomplete, and it used to be indistinguishable from a '
        + 'model that had finished').toBe(true)
  })

  test('is in the order it happened, oldest first', async ({ page }) => {
    await page.goto(RECORD)
    // ON THE IDS, NOT THE TEXT. Each row's id carries its kind, and the text does not distinguish
    // them: the `asking` row quotes the standing prompt and the `sent` row quotes it again, so
    // searching for a sentence finds whichever of the two came first and calls it either one.
    const feed = await rows(page)
    const kinds = await feed.evaluateAll(nodes =>
      nodes.map(n => ((n as HTMLElement).id.split(':')[2] ?? '')),
    )
    const first = (kind: string) => kinds.indexOf(kind)

    // WHAT WENT DOWN THE WIRE COMES FIRST, then what came back. There is no third row quoting the
    // question: the harness used to write its OWN copy of the task before the call, so an exchange
    // opened with fsm's version of the prompt and the real one arrived underneath it.
    expect(first('sent'), 'the prompt, as it was actually sent').toBeGreaterThan(-1)
    expect(first('thought'), 'then the reasoning it caused').toBeGreaterThan(first('sent'))
    expect(first('metered'), 'then what that answer cost').toBeGreaterThan(first('thought'))
    expect(kinds.indexOf('asking'), 'and nothing quoting the prompt ahead of it').toBe(-1)

    // AND THE WHOLE FEED IS OLDEST-FIRST, which is the claim the page makes in its own heading.
    const stamps = await feed.evaluateAll(nodes =>
      nodes.map(n => Number((n as HTMLElement).id.split(':')[3] ?? '0')).filter(n => n > 0),
    )
    const sorted = [...stamps].sort((a, b) => a - b)
    expect(stamps, 'a row out of order is a reader building the wrong story').toEqual(sorted)
  })

  test('gives every row its own identity, so a live append cannot swap two', async ({ page }) => {
    await page.goto(RECORD)
    const feed = await rows(page)
    const ids = await feed.evaluateAll(nodes => nodes.map(n => (n as HTMLElement).id))
    // The two `sent` rows of one request are written in the same millisecond by the same agent, so
    // `agent:kind:<ms>` named both. React tolerates a duplicate key on a first paint and stops the
    // moment the list grows — which it now does, on every event the stream pushes.
    const withIds = ids.filter(i => i !== '')
    expect(withIds.length, 'the rows carry ids at all').toBeGreaterThan(40)
    expect(new Set(withIds).size, 'no two rows share an identity').toBe(withIds.length)

    // AND EVERY ROW CARRIES SOMETHING. A duplicate key does not blank a row on the first paint; it
    // swaps two of them on the next append, which is unobservable here and is why the identity
    // above is asserted directly rather than through what it would look like when it broke.
    const texts = await contents(page)
    expect(texts.filter(t => t.trim() === '').length, 'no row is empty').toBe(0)
  })
})
