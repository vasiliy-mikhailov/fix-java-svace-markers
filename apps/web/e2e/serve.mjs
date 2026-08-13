/**
 * THE REAL STACK, BOOTED FOR THE BROWSER TESTS.
 *
 * Playwright drives what the container serves: the Java dashboard answering /api out of a fixture
 * record, and the same static export the image ships. Not a mock — a mocked API cannot catch the two
 * bugs this port has already had, both of which were the wire disagreeing with the page.
 *
 * The fixture is a trimmed copy of a real run: six markers chosen to cover six settlement states,
 * their traces cut to forty events each, plus real findings and a real conversation.
 */
import { spawn } from 'node:child_process'
import { existsSync, readdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repo = resolve(here, '../../..')
const classes = join(repo, 'agent/target/classes')
const out = join(repo, 'apps/web/out')
const fixture = join(here, 'fixture')
const port = process.env.E2E_PORT ?? '8188'

for (const [what, path] of [['the Java classes', classes], ['the static export', out]]) {
  if (!existsSync(path)) {
    console.error(
      `e2e: ${what} are missing at ${path}.\n` +
      `     Run:  (cd agent && mvn -q compile)  and  pnpm --filter @fsm/web build`)
    process.exit(1)
  }
}

// The classpath is whatever Maven resolved. Reading it here rather than hard-coding a list means a
// new dependency does not silently break the e2e run with a NoClassDefFoundError.
const lib = join(repo, 'agent/target/lib')
const jars = existsSync(lib) ? readdirSync(lib).map(j => join(lib, j)) : []
const cp = [classes, ...jars].join(':')

const java = spawn('java', ['-cp', cp, 'tech.mikhailov.fsm.agent.Dashboard',
  join(fixture, 'settlements.jsonl'), port], {
  env: { ...process.env, WEB_ROOT: out, CHECKOUTS: join(fixture, 'checkouts') },
  stdio: 'inherit',
})
java.on('exit', code => process.exit(code ?? 0))
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => java.kill(sig))
