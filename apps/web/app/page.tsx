'use client'

import { useEffect, useState } from 'react'
import {
  EmptyNote,
  ExportLink,
  Loaded,
  PageHeader,
  type ProjectEntry,
  ProjectRegistry,
  RelativeTime,
  RunProgress,
  StateCounts,
} from '@fsm/ui'
import type { MarkerState } from '@fsm/types'

import { href, live, projectUrl, read } from '../lib/api'

/**
 * THE REGISTRY — the first of three levels, and the page everybody opens.
 *
 * <p>IT WAS 857 ROWS AND 3,863,289 BYTES, REFETCHED EVERY FIFTEEN SECONDS. One list of every marker
 * of every project, each carrying whatever a model had written about it, with nothing on the page
 * naming either project and the largest module in the run — 416 markers, half of it —
 * indistinguishable from the twelve holding one. Now: which projects, and how far has each got.
 * 860 bytes, seven milliseconds, and the whole document arrives on a stream rather than being asked
 * for on a timer.
 *
 * <p>THE RUN-WIDE BLOCK STAYS ABOVE THE TABLE. `RunProgress` and `StateCounts` describe the run and
 * not any one project, and the last-event line is the only thing on the page that says the thing is
 * alive — a reader who has to open a project to find out whether anything is happening has been
 * given a worse page in exchange for a shorter one.
 *
 * <p>NO CRUMB. This is where back goes.
 */

type ApiProject = {
  name: string
  repo: string
  jdk: string
  markers: number
  decided: number
  demonstrated: number
  modules: number
  countsByState: Record<string, number>
}

type ApiRegistry = {
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
  projects: ApiProject[]
}

function toEntry(project: ApiProject): ProjectEntry {
  return {
    name: project.name,
    repo: project.repo,
    jdk: project.jdk,
    markers: project.markers,
    decided: project.decided,
    demonstrated: project.demonstrated,
    modules: project.modules,
    // BUILT HERE, because only the app knows the base path — see `ProjectEntry.href`.
    href: projectUrl(project.name),
  }
}

export default function RegistryScreen() {
  const [data, setData] = useState<ApiRegistry | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [reachable, setReachable] = useState(true)

  useEffect(() => {
    let alive = true
    // THE FIRST PAINT IS STILL A READ, and that is not belt-and-braces. A page that only subscribes
    // renders its loading state forever wherever `EventSource` is absent — which is every vitest
    // run, because happy-dom ships none — and it would do the same behind any proxy that buffers an
    // event stream. The server answers this from the SAME snapshot the stream pushes, so the first
    // paint and the first frame are byte-identical and nothing flickers.
    read<ApiRegistry>('/api/projects')
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
    // AND THEN THE SERVER TELLS. The frame IS the document — a couple of kilobytes — so there is no
    // merge to get wrong, no ordering to preserve and nothing to go and fetch. The fifteen-second
    // timer that used to live here pulled 3.86 MB each time to redraw a summary of two rows.
    const stop = live(
      '/api/projects/stream',
      {
        run: frame => {
          if (alive) {
            setData(frame as ApiRegistry)
            setFailed(null)
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
  }, [])

  if (failed !== null || data === null) {
    return (
      <Loaded
        what="run"
        failed={failed}
        value={data}
        header={
          <PageHeader title="projects" subtitle="what this run is about" findingsOpen={0} />
        }
      >
        {() => null}
      </Loaded>
    )
  }

  const { run, projects } = data
  return (
    <>
      <PageHeader
        title="projects"
        subtitle={
          <>
            {`${projects.length} project(s) · ${run.total} marker(s) · `}
            {`${run.traceEvents.toLocaleString()} trace event(s)`}
            {run.lastEventAt > 0 ? (
              <>
                {' · last event '}
                <RelativeTime at={run.lastEventAt} variant="conversation" />
              </>
            ) : null}
          </>
        }
        findingsOpen={run.findingsOpen}
        extra={
          <ExportLink
            href={href('/api/markers.csv')}
            title="download every marker as CSV — test, patch and verdict in their own columns"
          />
        }
      />
      {/*
        NEVER REPLACING WHAT IS ALREADY ON SCREEN. A dropped stream does not make the last numbers
        wrong, it makes them old — so this says so quietly and leaves them up, which is the rule the
        live panel already follows. Without it a stream that dies is invisible: the page keeps
        showing plausible figures and the clock beside "last event" keeps ticking.
      */}
      {reachable ? null : (
        <div style={{ padding: '0 24px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
          the stream dropped — this is the last thing it said
        </div>
      )}
      {projects.length === 0 ? (
        <div style={{ padding: '48px 24px' }}>
          <EmptyNote>No markers queued and no prove has run.</EmptyNote>
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
          <ProjectRegistry projects={projects.map(toEntry)} />
        </>
      )}
    </>
  )
}
