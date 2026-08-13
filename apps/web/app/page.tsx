'use client'

import { useEffect, useState } from 'react'
import {
  MarkerTable,
  PageHeader,
  RunProgress,
  StateCounts,
  EmptyNote,
  type MarkerRowData,
} from '@fsm/ui'
import type { MarkerState, Severity } from '@fsm/types'

import { read } from '../lib/api'

/**
 * THE MARKERS SCREEN — the first page of the port, and the one everybody opens.
 *
 * <p>It fetches on the client, because the zone is statically exported: there is no server here to
 * render against, and every value on the page is a projection of files the Java side already holds.
 *
 * <p>THE ADAPTER IS THE POINT OF THIS FILE. The components were specified from the Java they replace
 * and the API from the same reading, and the two names for a thing drifted apart in a couple of
 * places — `redVerified`/`greenVerified` on the wire against a `flags` record in the component,
 * `summary` against `headline`. Reconciling that HERE rather than in either half is deliberate: the
 * API sends what the record holds, the component takes what it draws, and the screen is the one
 * place allowed to know both vocabularies.
 */

type ApiMarker = {
  key: string
  id: string
  repo: string
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

type ApiIndex = {
  run: {
    total: number
    settled: number
    beganAt: number
    serverNow: number
    traceEvents: number
    humanMinutes: number
    findingsOpen: number
    countsByState: Record<string, number>
  }
  markers: ApiMarker[]
}

/**
 * One wire marker as the table wants it.
 *
 * <p>`flags` is null until a settling row exists, which is not the same as both lamps being off:
 * `Settlement.note` writes false on every `proving` stage row, so a marker whose RED has not run
 * would otherwise read as a test that ran and did not fail. The API sends null for exactly that
 * reason and the null survives to here rather than being defaulted away.
 */
function toRow(m: ApiMarker): MarkerRowData {
  return {
    key: m.key,
    repo: m.repo,
    file: m.file,
    line: String(m.line),
    checker: m.checker,
    severity: (m.severity as Severity | null) ?? null,
    state: m.state as MarkerState,
    flags: m.hasSettlement ? { red: m.redVerified, green: m.greenVerified } : null,
    events: m.events,
    spanMs: m.spanMs,
    humanMinutes: m.humanMinutes,
    // The interpreter's account is the headline when there is one; the row falls back to the
    // argument and then to the last thing said, which is what the Java's own column did.
    headline: m.summary,
    verdictText: m.verdictText,
    lastNote: m.lastNote,
    flagged: null,
  }
}

export default function MarkersScreen() {
  const [data, setData] = useState<ApiIndex | null>(null)
  const [failed, setFailed] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    const load = () =>
      read<ApiIndex>('/api/index')
        .then(d => {
          if (live) {
            setData(d)
            setFailed(null)
          }
        })
        .catch((e: unknown) => {
          if (live) {
            setFailed(e instanceof Error ? e.message : String(e))
          }
        })
    void load()
    // The run moves while somebody watches it. Fifteen seconds is the Java page's own cadence.
    const timer = setInterval(() => void load(), 15_000)
    return () => {
      live = false
      clearInterval(timer)
    }
  }, [])

  if (failed !== null) {
    return (
      <>
        <PageHeader title="markers" subtitle="could not read the run" findingsOpen={0} />
        <EmptyNote>{failed}</EmptyNote>
      </>
    )
  }
  if (data === null) {
    return (
      <>
        <PageHeader title="markers" subtitle="reading the run…" findingsOpen={0} />
      </>
    )
  }

  const { run, markers } = data
  return (
    <>
      <PageHeader
        title="markers"
        subtitle={`${run.total} marker(s) · ${run.traceEvents.toLocaleString()} trace event(s)`}
        findingsOpen={run.findingsOpen}
      />
      {markers.length === 0 ? (
        <EmptyNote>No markers queued and no prove has run.</EmptyNote>
      ) : (
        <>
          <RunProgress
            total={run.total}
            settled={run.settled}
            beganAt={run.beganAt}
            now={run.serverNow}
          />
          <StateCounts
            counts={run.countsByState as Partial<Record<MarkerState, number>>}
            humanMinutes={run.humanMinutes}
          />
          <MarkerTable markers={markers.map(toRow)} />
        </>
      )}
    </>
  )
}
