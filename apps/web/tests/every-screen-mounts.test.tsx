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
import project from './payloads/project.json' with { type: 'json' }
import projects from './payloads/projects.json' with { type: 'json' }
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
  '/api/projects': projects,
  '/api/project': project,
  '/api/live': live,
  '/api/settings/prompts': settingsPrompts,
  '/api/settings/run': settingsRun,
  '/api/settings/model': settingsModel,
  '/api/settings/subject': settingsSubject,
}

let asked: string[] = []

/**
 * WHICH STREAMS A SCREEN OPENED, and until now nothing could see them.
 *
 * happy-dom ships no `EventSource`, so `live()` returned its no-op teardown and every subscription
 * in this app was silently untested — a screen converted to a stream would have rendered its
 * loading state forever here and stayed green. Worse in both directions: on a runtime that DOES
 * supply the global the guard stops guarding and the same suite behaves differently with no code
 * change. So the stub is the pin, not merely the enabler.
 */
let subscribed: string[] = []

class Stub {
  static handlers = new Map<string, Record<string, (event: unknown) => void>>()
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null

  constructor(readonly url: string) {
    subscribed.push(url)
    Stub.handlers.set(url, {})
  }

  addEventListener(name: string, handle: (event: unknown) => void) {
    const own = Stub.handlers.get(this.url) ?? {}
    own[name] = handle
    Stub.handlers.set(this.url, own)
  }

  close() {
    Stub.handlers.delete(this.url)
  }
}

beforeEach(() => {
  asked = []
  subscribed = []
  Stub.handlers.clear()
  vi.stubGlobal('EventSource', Stub)
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
  it('the registry draws every project the run is about', async () => {
    await mount(() => import('../app/page'))
    await waitFor(() => expect(screen.getByText(/marker\(s\)/)).toBeTruthy(), { timeout: 4000 })
    // IT READS THE REGISTRY, NOT THE INDEX. That is the whole change: 860 bytes against 3,863,289
    // to draw a summary of two rows, and the reason this page could stop polling at all.
    expect(asked.some(u => u.includes('/api/projects'))).toBe(true)
    expect(asked.some(u => u.includes('/api/index'))).toBe(false)
    for (const named of projects.projects) {
      expect(document.body.textContent, `${named.name} is in the record`).toContain(named.name)
    }
    expect(document.body.textContent).toContain(String(projects.run.total))
  })

  it('the registry stops asking and starts being told', async () => {
    await mount(() => import('../app/page'))
    await waitFor(() => expect(subscribed.length).toBeGreaterThan(0), { timeout: 4000 })
    expect(subscribed.some(u => u.includes('/api/projects/stream'))).toBe(true)
    // ONE STREAM PER PAGE. `com.sun.net.httpserver` speaks HTTP/1.1, so every EventSource is one of
    // the browser's six sockets to this origin and is held for an hour; a page that opens one per
    // effect exhausts them and its next ordinary fetch queues forever, with nothing in any log.
    expect(subscribed.length).toBe(1)
  })

  it('nothing on the registry asks again on a timer', async () => {
    // THE CLOCK GOES ON AFTER THE MOUNT, NOT BEFORE IT. `waitFor` is itself driven by timers, so
    // faking them first freezes the very wait that gets the page onto the screen — the test then
    // times out and, worse, leaves the fake clock installed for every test after it.
    await mount(() => import('../app/page'))
    await waitFor(() => expect(screen.getByText(/marker\(s\)/)).toBeTruthy(), { timeout: 4000 })
    const first = asked.length
    vi.useFakeTimers()
    try {
      await vi.advanceTimersByTimeAsync(60_000)
    } finally {
      vi.useRealTimers()
    }
    expect(asked.length, 'the poll is gone: the server tells, four times over').toBe(first)
  })

  it('one project draws its own modules', async () => {
    await mount(() => import('../app/project/page'), 'p=ca2_back')
    await waitFor(() => expect(asked.some(u => u.includes('/api/project?'))).toBe(true))
    await waitFor(
      () => expect(document.body.textContent).toContain('ca2-client/ca2-messages-client'),
      { timeout: 4000 },
    )
    expect(subscribed.some(u => u.includes('/api/project/stream'))).toBe(true)
    // A SINGLE-MODULE PROJECT MUST STILL SAY WHICH MODULE. `MarkerGroups` collapses that case to a
    // bare table, which is right on the whole-run page and wrong here — hence a second component.
    expect(document.body.textContent).not.toContain('WebGoat')
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
      [() => import('../app/project/page'), 'p=ca2_back'],
    ] as const) {
      asked = []
      subscribed = []
      await mount(load as never, q)
      for (const url of asked) {
        const path = url.split('?')[0] ?? url
        expect(BY_PATH[path], `${path} is not an endpoint this system serves`).toBeDefined()
      }
      // A MISTYPED STREAM PATH MUST FAIL THE WAY A MISTYPED FETCH DOES. It would otherwise be
      // invisible: an EventSource to a 404 fires `error` and the page simply never updates.
      for (const url of subscribed) {
        const path = (url.split('?')[0] ?? url).replace(/\/stream$/, '')
        expect(BY_PATH[path], `${path} is not an endpoint this system serves`).toBeDefined()
      }
      cleanup()
    }
  })
})
