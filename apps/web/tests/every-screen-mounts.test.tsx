// @vitest-environment happy-dom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import badges from './payloads/badges.json' with { type: 'json' }
import chat from './payloads/chat.json' with { type: 'json' }
import events from './payloads/events.json' with { type: 'json' }
import index from './payloads/index.json' with { type: 'json' }
import live from './payloads/live.json' with { type: 'json' }
import marker from './payloads/marker.json' with { type: 'json' }
import overwatch from './payloads/overwatch.json' with { type: 'json' }
import settingsModel from './payloads/settingsModel.json' with { type: 'json' }
import settingsPrompts from './payloads/settingsPrompts.json' with { type: 'json' }
import settingsRun from './payloads/settingsRun.json' with { type: 'json' }
import settingsSubject from './payloads/settingsSubject.json' with { type: 'json' }

/**
 * EVERY SCREEN, MOUNTED, AGAINST PAYLOADS THE RUNNING SYSTEM PRODUCED.
 *
 * <p>The pages compile and the routes prerender, and neither of those is the question. They are
 * client components: everything they show arrives in an effect, so a build that succeeds has
 * exercised none of the code that matters. The agents who wrote them said so plainly — "no effect
 * ever ran against a real payload" — and this is that.
 *
 * <p>The payloads under `payloads/` are verbatim captures from the live record: 356 markers, real
 * prose with quotes and newlines in it, real absent values. Not fixtures written to match the code.
 * This port has been bitten twice by a test whose fixture agreed with the bug.
 *
 * <p>What is asserted is deliberately shallow — that each screen mounts, calls the endpoints it
 * should, and puts the record's own numbers on the page. Depth belongs in the component tests; what
 * cannot be got any other way is the knowledge that the wiring is real.
 */

const BY_PATH: Record<string, unknown> = {
  '/api/index': index,
  '/api/events': events,
  '/api/overwatch': overwatch,
  '/api/chat': chat,
  '/api/badges': badges,
  '/api/marker': marker,
  '/api/live': live,
  '/api/settings/prompts': settingsPrompts,
  '/api/settings/run': settingsRun,
  '/api/settings/model': settingsModel,
  '/api/settings/subject': settingsSubject,
}

let asked: string[] = []

beforeEach(() => {
  asked = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    const path = url.split('?')[0] ?? url
    asked.push(url)
    const body = BY_PATH[path]
    if (body === undefined) {
      return Promise.resolve(new Response('null', { status: 404 }))
    }
    return Promise.resolve(
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** Next's client hooks, which have no router in a bare render. */
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(globalThis.__q ?? ''),
  useRouter: () => ({ push: () => {}, replace: () => {} }),
  usePathname: () => '/',
}))

declare global {
  // eslint-disable-next-line no-var
  var __q: string | undefined
}

async function mount(load: () => Promise<{ default: () => React.ReactNode }>, query = '') {
  globalThis.__q = query
  const Page = (await load()).default
  render(<Page />)
  // Every screen fetches in an effect; wait for the first one to land.
  await waitFor(() => expect(asked.length).toBeGreaterThan(0), { timeout: 4000 })
}

describe('every screen mounts against the real record', () => {
  it('the markers table draws the run', async () => {
    await mount(() => import('../app/page'))
    await waitFor(() => expect(screen.getByText(/marker\(s\)/)).toBeTruthy(), { timeout: 4000 })
    expect(asked.some(u => u.includes('/api/index'))).toBe(true)
    // The record says 356; the page must not round, sample or sort it away.
    expect(document.body.textContent).toContain('356')
  })

  it('the whole trace draws its events', async () => {
    await mount(() => import('../app/trace/page'))
    await waitFor(() => expect(asked.some(u => u.includes('/api/events'))).toBe(true))
    expect(document.body.textContent?.length ?? 0).toBeGreaterThan(200)
  })

  it('the supervisor draws its findings', async () => {
    await mount(() => import('../app/overwatch/page'))
    await waitFor(() => expect(asked.some(u => u.includes('/api/overwatch'))).toBe(true))
    expect(document.body.textContent?.length ?? 0).toBeGreaterThan(100)
  })

  it('the chat draws the conversation', async () => {
    await mount(() => import('../app/chat/page'))
    await waitFor(() => expect(asked.some(u => u.includes('/api/chat'))).toBe(true))
    expect(document.body.textContent?.length ?? 0).toBeGreaterThan(50)
  })

  it('settings draws its tabs', async () => {
    await mount(() => import('../app/settings/page'))
    await waitFor(() => expect(asked.some(u => u.includes('/api/settings'))).toBe(true))
    expect(document.body.textContent?.length ?? 0).toBeGreaterThan(50)
  })

  it('one marker draws its summary', async () => {
    const key = (index as { markers: { key: string }[] }).markers[0]!.key
    await mount(() => import('../app/marker/page'), `k=${encodeURIComponent(key)}`)
    await waitFor(() => expect(asked.some(u => u.includes('/api/marker'))).toBe(true))
    expect(document.body.textContent?.length ?? 0).toBeGreaterThan(100)
  })

  it('no screen asks for an endpoint that does not exist', async () => {
    for (const [load, q] of [
      [() => import('../app/page'), ''],
      [() => import('../app/trace/page'), ''],
      [() => import('../app/overwatch/page'), ''],
      [() => import('../app/chat/page'), ''],
      [() => import('../app/settings/page'), ''],
    ] as const) {
      asked = []
      await mount(load as never, q)
      for (const url of asked) {
        const path = url.split('?')[0] ?? url
        expect(BY_PATH[path], `${path} is not an endpoint this system serves`).toBeDefined()
      }
      cleanup()
    }
  })
})
