#!/usr/bin/env node
/**
 * WHAT `next build` PRODUCED, CHECKED BEFORE ANYBODY SHIPS IT.
 *
 * Everything held here is a thing this app can lose with NO ERROR ANYWHERE, whose only witness is a
 * file in `out/`. The build succeeds, the suite is green, the page renders, and something is quietly
 * gone. There is no source file in which any of these is visible — only the emitted output has both
 * halves of the disagreement.
 *
 * IT RUNS FROM `pnpm build`, WHICH IS THE POINT. The e2e suite covers some of the same ground and
 * covers it well, but it has to be remembered, and it never runs during the image build — which is
 * exactly where the asset-prefix fault below actually shipped.
 *
 * Each check names what broke and what to do about it. A gate that only says `false` sends the next
 * reader to the wrong place.
 */
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs'
import { join, dirname, resolve, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const WEB = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const OUT = join(WEB, 'out')
const GLOBALS = join(WEB, 'app', 'globals.css')

/**
 * THE ONE PLACE IN THIS REPOSITORY THAT WRITES THE UTILITY'S NAME, and it is written in halves.
 *
 * Tailwind's automatic source detection scans every readable file in the project, so a file that
 * spells a class name EMITS it. That is not hypothetical: the guard for this exact utility passed
 * with its own `@source` line deleted, because the spec asserting it spelled it twice. Check 1b
 * below is what keeps that from coming back, and it cannot be written by a file it would flag.
 */
const UTILITY = ['animate', 'pulse'].join('-')

const problems = []
const fail = (check, what, todo) => problems.push({ check, what, todo })

function builtCss() {
  const chunks = join(OUT, '_next', 'static', 'chunks')
  if (!existsSync(chunks)) return ''
  return readdirSync(chunks)
    .filter(f => f.endsWith('.css'))
    .map(f => readFileSync(join(chunks, f), 'utf8'))
    .join('\n')
}

function walk(dir, hit) {
  if (!existsSync(dir)) return
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === '.git') continue
    const path = join(dir, name)
    if (statSync(path).isDirectory()) walk(path, hit)
    else hit(path)
  }
}

const css = builtCss()
const globals = existsSync(GLOBALS) ? readFileSync(GLOBALS, 'utf8') : ''

// ── 1. THE TAILWIND UTILITY THAT VANISHES ────────────────────────────────────────────────────
// `Pill` puts this on the dot marking a row still moving. When `Pill` came from `ratchet-ui`
// instead of from this repository the class moved into `node_modules`, which no glob covered.
// Tailwind does not warn about a glob matching nothing. The dot simply stops moving, and it is not
// even in the exported HTML to notice — a pulsing pill exists only at run time from fetched data.
if (css.length === 0) {
  fail(1, 'no stylesheet was built at all', 'every check over the CSS below is vacuous; fix the build first')
} else {
  if (!css.includes(`.${UTILITY}`)) {
    fail(1, `the ${UTILITY} utility is not in the built stylesheet`,
      'add `@source "../../../packages/ui/node_modules/ratchet-ui/dist";` to app/globals.css — and '
      + 'note it is `dist`, not `src`: pnpm ships only what `files` names, so a glob at `src` scans '
      + 'one stylesheet, finds no class, and fails in the same silence')
  }
  if (!css.includes('@keyframes pulse')) {
    fail(1, 'the utility is emitted but its keyframes are not',
      'the class without its animation is a dot that does not move; check the Tailwind version')
  }
}

// ── 1b. IS CHECK 1 STILL EVIDENCE OF ANYTHING? ───────────────────────────────────────────────
// The check this repository most needs, because check 1 passed for a year while being worthless.
// Two conditions, and either alone rots: detection must be off, and no scanned tree may name the
// literal — a file that names it emits it, and check 1 then greps for what the grep itself caused.
if (!globals.includes('source(none)')) {
  fail('1b', 'automatic source detection is on in app/globals.css',
    'write `@import "tailwindcss" source(none);` — with detection on, Tailwind scans every readable '
    + 'file in the project, so any file naming a class emits it and check 1 proves nothing')
}
const globs = [...globals.matchAll(/@source\s+"([^"]+)"/g)].map(m => resolve(WEB, 'app', m[1] ?? ''))
for (const tree of globs) {
  walk(tree, path => {
    if (path.endsWith('.map')) return
    let text
    try { text = readFileSync(path, 'utf8') } catch { return }
    // The package's own dist is SUPPOSED to contain it — that is the whole point of scanning it.
    if (tree.includes('ratchet-ui')) return
    if (text.includes(UTILITY)) {
      fail('1b', `${relative(WEB, path)} names the utility, and it is inside a scanned tree`,
        'that file emits the class, so check 1 greps for something it caused itself; assemble the '
        + 'name from parts or move the file out of the glob')
    }
  })
}

// ── 2. tokens.css NOT REACHING THE BUNDLE ────────────────────────────────────────────────────
// An `@import` resolving to nothing is not an error in Tailwind. Every colour then falls back to
// whatever `var()` does with an undefined custom property — `inherit` for colour, `transparent` for
// background — so the page renders black on white and looks like a stylesheet still loading.
for (const token of ['--bg-card', '--text-primary', '--build-red']) {
  if (css.length > 0 && !css.includes(token)) {
    fail(2, `${token} is not in the built stylesheet, so tokens did not reach the bundle`,
      'check the `@import "@fsm/ui/tokens.css"` and `domain.css` lines in app/globals.css resolve — '
      + 'an import that resolves to nothing is silent, and every colour falls back to inherit')
  }
}

// ── 3. THE ASSET PREFIX DISAGREEING WITH WHAT THE JAVA SERVES ────────────────────────────────
// This one actually shipped: the image built with BASE_PATH=/ui, left over from when both UIs were
// up, against a server serving the root. `index.html` still arrives and `/api/*` still answers, so
// the page renders and every script and stylesheet 404s.
const base = (process.env.BASE_PATH ?? '').replace(/\/$/, '')
const index = join(OUT, 'index.html')
if (!existsSync(index)) {
  fail(3, 'out/index.html does not exist', 'the export produced no entry point; check `output: export`')
} else {
  const html = readFileSync(index, 'utf8')
  const refs = [...html.matchAll(/(?:src|href)="(\/[^"]*)"/g)].map(m => m[1] ?? '')
  if (refs.length === 0) {
    fail(3, 'the exported index references no local assets at all',
      'a page with no scripts is a page that will render and do nothing')
  }
  for (const ref of refs) {
    if (base && !ref.startsWith(base)) {
      fail(3, `${ref} does not carry the base path ${base}`,
        'the bundle was built for a different mount point than the one it will be served from')
    }
    // AND THE FILE HAS TO BE THERE. A prefix that agrees with the server and names nothing is the
    // same 404 by another route.
    const onDisk = join(OUT, ref.slice(base.length))
    if (!ref.startsWith('/api/') && !existsSync(onDisk)) {
      fail(3, `${ref} is referenced by the exported index and is not in out/`,
        'the bundle refers to a chunk that was not emitted or not copied')
    }
  }
}

// ── 4. A ROUTE THAT DID NOT BECOME A FILE ────────────────────────────────────────────────────
// `Dash.page()` is a file lookup and not a route table, so a page that failed to export is a 404
// with nothing in any log to say a page was expected.
const pages = []
walk(join(WEB, 'app'), path => {
  if (path.endsWith(`${'page'}.tsx`)) pages.push(path)
})
for (const page of pages) {
  const route = relative(join(WEB, 'app'), dirname(page))
  const expected = route === '' ? join(OUT, 'index.html') : join(OUT, route, 'index.html')
  if (!existsSync(expected)) {
    fail(4, `${relative(WEB, page)} did not export to ${relative(WEB, expected)}`,
      'the server looks up a file, so a route that produced none is a 404 nothing logs')
  }
}

// ── the report ───────────────────────────────────────────────────────────────────────────────
const checks = ['1', '1b', '2', '3', '4']
if (problems.length === 0) {
  console.log(`  verify-export: ${checks.length} checks over out/ — ${pages.length} routes, `
    + `${Math.round(css.length / 1024)}kB of css, all good`)
  process.exit(0)
}
console.error(`\n  verify-export FAILED — ${problems.length} problem(s) in out/\n`)
for (const p of problems) {
  console.error(`  [check ${p.check}] ${p.what}`)
  console.error(`               → ${p.todo}\n`)
}
process.exit(1)
