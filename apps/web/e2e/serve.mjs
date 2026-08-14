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
import { cpSync, existsSync, mkdtempSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repo = resolve(here, '../../..')
const classes = join(repo, 'agent/target/classes')
const out = join(repo, 'apps/web/out')
const port = process.env.E2E_PORT ?? '8188'

// A COPY, BECAUSE THE DASHBOARD WRITES. Asking a question appends to chat.jsonl, feedback and reprove
// append too — pointed at `e2e/fixture/` those land in the repository, so the suite would dirty its own
// input and the second run would not be testing what the first one did. The copy is thrown away with
// the process, which also means a test may exercise a WRITE path; against the checked-in directory the
// only safe tests are the read-only ones, and the bug that prompted this was on a write path.
const pristine = join(here, 'fixture')
const fixture = mkdtempSync(join(tmpdir(), 'fsm-e2e-'))
cpSync(pristine, fixture, { recursive: true })
process.on('exit', () => rmSync(fixture, { recursive: true, force: true }))

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
  env: {
    ...process.env,
    WEB_ROOT: out,
    CHECKOUTS: join(fixture, 'checkouts'),
    // A MODEL THAT REFUSES INSTANTLY. Port 1 is closed, so anything asking a model fails in
    // milliseconds with a connection refused instead of hanging on a real endpoint or, worse,
    // reaching one — the suite must not spend tokens and must not need a network. The failure is the
    // point for the chat test: what matters is what a person is shown when an answer cannot be had.
    QWEN_BASE_URL: 'http://127.0.0.1:1/v1',
    QWEN_API_KEY: 'e2e-not-a-key',
    QWEN_MODEL: 'e2e',
  },
  stdio: 'inherit',
})
java.on('exit', code => process.exit(code ?? 0))
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => java.kill(sig))
