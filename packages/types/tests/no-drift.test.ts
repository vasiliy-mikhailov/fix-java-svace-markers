import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

import { AGENTS, CHAIN, DISPOSITIONS, TRACE_KINDS, VERDICTS, WATCH } from '../src/index.js'

/**
 * THESE TYPES DESCRIBE FILES THIS PACKAGE DOES NOT WRITE.
 *
 * Java writes the record; TypeScript only renders it. So every list here is a copy, and a copy drifts
 * silently: the UI keeps compiling, keeps rendering, and is simply wrong about a state nobody told it
 * existed. That is not hypothetical — `system` is a real trace kind that reading the field names
 * would have missed, and `infra` counting as a settlement once retired markers whose prove had
 * thrown.
 *
 * So the Java is read here and compared. If somebody adds a disposition to `Prove` and not to this
 * file, this fails and names it, instead of the dashboard quietly showing a blank cell.
 */

// ANCHORED AT THE REPOSITORY ROOT, not by counting `..` from here. Counting was wrong on the first
// try and the failure was an ENOENT four directories away from the mistake.
const ROOT = join(__dirname, '..', '..', '..')
const JAVA = join(ROOT, 'agent', 'src', 'main', 'java', 'tech', 'mikhailov', 'fsm', 'agent')
const ENTRYPOINT = join(ROOT, 'agent', 'entrypoint.sh')

const read = (file: string): string => readFileSync(join(JAVA, file), 'utf8')

describe('the types do not drift from the Java that writes the record', () => {
  it('has exactly the dispositions the pool greps for', () => {
    // entrypoint.sh is the authority: this regex is what decides a marker is finished. A
    // disposition missing from it is proved forever; one missing from us renders as nothing.
    const shell = readFileSync(ENTRYPOINT, 'utf8')
    const line = shell.split('\n').find(l => l.includes('DISPOSITIONS='))
    expect(line, 'entrypoint.sh no longer declares DISPOSITIONS').toBeDefined()
    // BOTH DIRECTIONS, as sets. Iterating our own list and checking each is in the shell only
    // catches us having an extra — the drift that matters is the shell gaining one we do not know,
    // and that check would have passed happily while the UI rendered a blank cell for it.
    const inShell = line!.slice(line!.indexOf('(') + 1, line!.lastIndexOf(')')).split('|')
    expect(inShell.length, `parsed nothing out of: ${line}`).toBeGreaterThan(3)
    expect([...inShell].sort()).toEqual([...DISPOSITIONS].sort())
  })

  it('has every trace kind JsonlTrace writes', () => {
    const written = [...read('JsonlTrace.java').matchAll(/write\("([a-z]+)"/g)].map(m => m[1])
    const missing = [...new Set(written)].filter(k => !TRACE_KINDS.includes(k as never))
    expect(missing, `JsonlTrace writes kinds this package does not know: ${missing}`).toEqual([])
  })

  it('has the agents in the order Prove calls them', () => {
    // AGAINST THE TREE, BECAUSE THE LIST IS GONE. `Agents.CHAIN` used to be fifteen string
    // literals and this read them; it is `Prove.chain()` now, walked off the object the runtime
    // executes. Reading the old place would have matched whatever strings happened to come next —
    // it found WATCH's — which is a guard that passes by looking at the wrong thing.
    const tree = read('Prove.java')
    const from = tree.indexOf('private Agent everything()')
    // To the end of the method, not a guessed window: a fixed slice cut `price` off and the guard
    // reported twelve agents where the tree has fifteen.
    const everything = tree.slice(from, tree.indexOf('/** Writes a test', from))
    const stages = [...everything.matchAll(/Flow\.(?:code|when)\("([a-z]+)"/g)]
      .map(m => m[1])
      .filter(name => name !== 'prove')
    const inJava = stages.flatMap(s => [`${s}-planner`, `${s}-doer`, `${s}-verifier`])
    expect(inJava).toEqual([...CHAIN])

    // WATCH is still a hand-written list: those six watch a RUN rather than a marker, so they are
    // not nodes in any prove's tree and there is nothing to walk them off.
    const agents = read('Agents.java')
    const watchBlock = agents.slice(agents.indexOf('List<String> WATCH'))
    const watchJava = [...watchBlock.slice(0, 220).matchAll(/"([a-z-]+)"/g)].map(m => m[1])
    expect(watchJava.slice(0, WATCH.length)).toEqual([...WATCH])
  })

  it('names every agent that has a prompt, because each one gets a tab', () => {
    // A prompt with no name here is an agent whose page the UI cannot draw. verdict-critic was
    // missing from the marker tabs once for exactly this reason and nobody noticed.
    const order = read('Agents.java')
    for (const agent of AGENTS) {
      expect(order, `${agent} is not in Agents.java`).toContain(`"${agent}"`)
    }
  })

  it('has the verdicts the supervisor actually writes', () => {
    const java = read('Overwatch.java') + read('Dashboard.java')
    for (const v of VERDICTS) {
      expect(java, `nothing in Java writes or reads the verdict "${v}"`).toContain(`"${v}"`)
    }
  })

  it('treats infra as unsettled, which is the whole point', () => {
    expect(DISPOSITIONS).not.toContain('infra' as never)
  })
})
