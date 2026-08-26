'use client'

import { Suspense, useEffect, useState } from 'react'
import { useSearchParams } from 'next/navigation'
import {
  EmptyNote,
  Loaded,
  type MarkerRowData,
  PageHeader,
  ProjectModules,
  RelativeTime,
  RunProgress,
  StateCounts,
} from '@fsm/ui'
import type { MarkerState, Severity } from '@fsm/types'

import { href, live, read } from '../../lib/api'

/**
 * ONE PROJECT — the second of three levels: its modules, and its markers under them.
 *
 * <p>NARROWED ON THE SERVER, NOT IN THE BROWSER. `/api/project?p=` sends only this project's
 * markers; filtering `/api/index` here would mean downloading 857 of them and 3.86 MB to draw 501,
 * and it gets worse with every project queued. ca2_back answers in 135 ms against 1.19 s for the
 * whole run, and every per-marker number in it was checked against `/api/index`, marker for marker,
 * before this page was written.
 *
 * <p>TWO FRAMES, AND THE REASON IS THE SIZE OF THIS ONE. The registry's frame can simply BE the
 * document because it is 860 bytes. A project's is hundreds of kilobytes, so it is pushed only when
 * the run's CONTENT changes — a marker actually settling — while the fact that a prove is awake
 * arrives on a `tick` frame of two numbers. Nothing here polls.
 */

type ApiMarker = {
  key: string
  id: string
  repo: string
  project: string
  module: string
  file: string
  line: number
  checker: string
  severity: string | null
  state: string
  hasSettlement: boolean
  redVerified: boolean | null
  greenVerified: boolean | null
  events: number
  spanMs: number
  humanMinutes: number
  summary: string
  verdictText: string
  lastNote: string
}

type ApiProject = {
  project: string
  run: {
    total: number
    settled: number
    demonstrated: number
    beganAt: number
    serverNow: number
    traceEvents: number
    lastEventAt: number
    humanMinutes: number
    findingsOpen: number
    countsByState: Record<string, number>
  }
  markers: ApiMarker[]
}

/** The adapter, the same one `renders-the-real-run.test.tsx` keeps a copy of and says so. */
function toRow(m: ApiMarker): MarkerRowData {
  return {
    key: m.key,
    repo: m.repo,
    project: m.project,
    module: m.module,
    file: m.file,
    line: String(m.line),
    checker: m.checker,
    severity: (m.severity as Severity | null) ?? null,
    state: m.state as MarkerState,
    flags: m.hasSettlement ? { red: m.redVerified, green: m.greenVerified } : null,
    events: m.events,
    spanMs: m.spanMs,
    humanMinutes: m.humanMinutes,
    headline: m.summary,
    verdictText: m.verdictText,
    lastNote: m.lastNote,
    flagged: null,
  }
}

function ProjectScreen() {
  const params = useSearchParams()
  const project = params.get('p') ?? ''
  const [data, setData] = useState<ApiProject | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [awake, setAwake] = useState(0)
  const [reachable, setReachable] = useState(true)

  useEffect(() => {
    let alive = true
    read<ApiProject>(`/api/project?p=${encodeURIComponent(project)}`)
      .then(d => {
        if (alive) {
          setData(d)
          setFailed(null)
        }
      })
      .catch((e: unknown) => {
        if (alive) {
          setFailed(e instanceof Error ? e.message : String(e))
        }
      })
    const stop = live(
      `/api/project/stream?p=${encodeURIComponent(project)}`,
      {
        project: frame => {
          if (alive) {
            setData(frame as ApiProject)
            setFailed(null)
          }
        },
        // THE HEARTBEAT, SEPARATELY, so the age beside "last event" keeps moving between
        // settlements without re-sending every marker on the page to do it.
        tick: frame => {
          if (alive) {
            setAwake((frame as { lastEventAt: number }).lastEventAt)
          }
        },
      },
      up => {
        if (alive) {
          setReachable(up)
        }
      },
    )
    return () => {
      alive = false
      stop()
    }
  }, [project])

  if (failed !== null || data === null) {
    return (
      <Loaded
        what="project"
        failed={failed}
        value={data}
        header={
          <PageHeader
            title={project === '' ? 'a project' : project}
            subtitle="the modules of one project"
            back={{ label: 'all projects', href: href('/') }}
            findingsOpen={0}
          />
        }
      >
        {() => null}
      </Loaded>
    )
  }

  const { run, markers } = data
  // A `?p=` A READER CAN EDIT IS A `?p=` A READER CAN MISTYPE, and an empty table would read as a
  // project with nothing in it rather than as a project that does not exist.
  const unknown = markers.length === 0
  const latest = Math.max(awake, run.lastEventAt)
  return (
    <>
      <PageHeader
        title={project === '' ? 'a project' : project}
        subtitle={
          <>
            {`${run.total} marker(s) · ${run.traceEvents.toLocaleString()} trace event(s)`}
            {latest > 0 ? (
              <>
                {' · last event '}
                <RelativeTime at={latest} variant="conversation" />
              </>
            ) : null}
          </>
        }
        back={{ label: 'all projects', href: href('/') }}
        findingsOpen={run.findingsOpen}
      />
      {reachable ? null : (
        <div style={{ padding: '0 24px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
          the stream dropped — this is the last thing it said
        </div>
      )}
      {unknown ? (
        <div style={{ padding: '48px 24px' }}>
          <EmptyNote>
            {project === ''
              ? 'No project named in the address.'
              : `No project called ${project} is in this run's queue.`}
          </EmptyNote>
        </div>
      ) : (
        <>
          <RunProgress
            total={run.total}
            settled={run.settled}
            demonstrated={run.demonstrated}
            beganAt={run.beganAt}
            now={run.serverNow}
          />
          <StateCounts
            counts={run.countsByState as Partial<Record<MarkerState, number>>}
            humanMinutes={run.humanMinutes}
          />
          <ProjectModules markers={markers.map(toRow)} />
        </>
      )}
    </>
  )
}

/**
 * THE QUERY STRING IS READ IN A CHILD, and the boundary is not decoration — the same reason the
 * marker page gives: this zone is statically exported, so `useSearchParams()` has nothing to answer
 * with until the page is in a browser, and without the boundary the export fails at build.
 */
export default function ProjectPage() {
  return (
    <Suspense
      fallback={
        <PageHeader
          title="a project"
          subtitle="reading the address…"
          back={{ label: 'all projects', href: href('/') }}
          findingsOpen={0}
        />
      }
    >
      <ProjectScreen />
    </Suspense>
  )
}
