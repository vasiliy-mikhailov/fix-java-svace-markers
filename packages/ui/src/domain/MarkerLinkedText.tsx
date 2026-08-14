import type { ReactNode } from 'react'
import type { MarkerKey } from '@fsm/types'
import type { Style } from '../primitives'

/** The directory name a marker gets — `Supervisor.slug(key)`. A key is not one and neither is a path. */
type Slug = string

export type MarkerLinkedTextProps = {
  /** RAW agent prose. Never pre-escaped and never pre-linked — see below. */
  text: string
  /**
   * Slug → the key it was made from, LONGEST SLUG FIRST.
   *
   * A `Map` and not an object because the order is part of the value (Java `slugs()` 744-757 sorts
   * by length before it builds the map). Object key order is only guaranteed for non-integer-like
   * keys and a slug is `[A-Za-z0-9._-]+`, which can be all digits.
   */
  markers: ReadonlyMap<Slug, MarkerKey>
}

const LINK: Style = { color: 'var(--accent-primary)', textDecoration: 'none' }

/** Everything a slug cannot contain but a caller might. A `.` in a pattern matches any character. */
function quoted(slug: string): string {
  return slug.replace(/[.*+?^${}()|[\]\\-]/g, '\\$&')
}

/**
 * One alternation, longest branch first, which is what makes the longest match win.
 *
 * A regex alternation tries its branches left to right at each position, so ordering the branches by
 * length is the same rule as `slugs()`'s sort — and it is applied HERE rather than trusted to the
 * caller, because the whole correctness argument rests on it. `Foo_java_82_NULL_DEREF` contains
 * `Foo_java_82_NULL`, and getting this wrong does not fail: it links the first half of a marker's
 * name to the wrong marker and leaves the rest as text.
 *
 * `sort` is stable in every engine since ES2019, so the map's own order survives among equal lengths.
 */
function anywhere(slugs: readonly Slug[]): RegExp | null {
  const usable = slugs.filter((s) => s.length > 0).sort((a, b) => b.length - a.length)
  return usable.length === 0 ? null : new RegExp(usable.map(quoted).join('|'), 'g')
}

/**
 * AGENT PROSE WITH THE MARKERS IT NAMES MADE REACHABLE.
 *
 * A finding says "3 markers" and names them as the directory slugs it read; a reader who wants to
 * check the claim then has to copy a slug, guess the key it came from and paste it into a URL. The
 * names are already exact — this only makes them what they look like.
 *
 * ESCAPE FIRST, LINK SECOND (Java comment 725-726). That ordering is the only thing that stopped a
 * model's answer from putting markup on the page, and it is the whole reason `linked()` (728-741)
 * worked on an already-escaped string. In React the escaping is not a step: this builds an array of
 * text nodes and `<a>` elements and React escapes each one on its way out. `dangerouslySetInnerHTML`
 * here — to reuse a server-rendered blob, say — reopens exactly the hole the ordering closed, and it
 * would reopen it for text written by a language model, which is the least trustworthy string in the
 * system.
 *
 * The U+0000/U+0001 sentinels the Java wove through this (735-740) were bookkeeping for repeated
 * `String.replace` — marking what was already linked so a second pass could not relink the inside of
 * an `href`. A single left-to-right scan never revisits what it has emitted, so they are gone rather
 * than ported.
 *
 * An empty map renders the text unchanged. That is the normal state before a run starts (no
 * `markers.txt` yet), not a failure, and it must not look like one.
 */
export function MarkerLinkedText({ text, markers }: MarkerLinkedTextProps) {
  const pattern = anywhere([...markers.keys()])
  if (pattern === null) {
    return <>{text}</>
  }
  const out: ReactNode[] = []
  let at = 0
  // `matchAll` gives a fresh iterator and needs the /g flag, so two of these on one page cannot
  // share a `lastIndex` the way `exec` on a module-level pattern would.
  for (const match of text.matchAll(pattern)) {
    const slug = match[0] ?? ''
    const start = match.index ?? at
    if (start > at) {
      out.push(text.slice(at, start))
    }
    const key = markers.get(slug)
    out.push(
      key === undefined ? (
        slug
      ) : (
        // The href carries the KEY, not the slug: the slug names a directory and `/marker?k=` wants
        // the marker's identity. They differ by exactly the characters slugging replaced.
        <a key={start} href={`/marker?k=${encodeURIComponent(key)}`} style={LINK} className="hover:underline">
          {slug}
        </a>
      ),
    )
    at = start + slug.length
  }
  out.push(text.slice(at))
  return <>{out}</>
}
