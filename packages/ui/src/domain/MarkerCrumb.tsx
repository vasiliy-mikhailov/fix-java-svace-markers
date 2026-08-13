import type { Style } from '../primitives'

export type MarkerCrumbProps = {
  /**
   * WHATEVER THE EVENT'S `marker` FIELD HELD, which is not always a marker key.
   *
   * On a prove's trace it is `repo|file|line|checker`. On the supervisor's record it is the literal
   * `overwatch`, or a directory slug the watcher was looking at. That is why the type is `string` and
   * not `MarkerKey`: this component is shown a field, not an identity, and pretending otherwise is
   * what produced the bug `href` fixes.
   */
  marker: string
  /**
   * REQUIRED, AND NULLABLE ON PURPOSE (catalogue #6-adjacent, `one()` 2216-2220).
   *
   * The Java built `/marker?k=<the field>` unconditionally, so every crumb on `/overwatch?a=trace`
   * pointed at a marker page for a marker that cannot exist — a link that 404s on a page whose whole
   * job is to be trustworthy about the record. The fix is in the PAYLOAD: the server carries the real
   * key when the event has one and `null` when it does not. An optional prop would have let one
   * screen forget, and a `showLink` flag would have made the caller decide a fact it does not own.
   */
  href: string | null
}

/** Java's `.k` (CSS 52), the small grey line an event hangs under. */
const CRUMB: Style = { color: 'var(--text-tertiary)', fontSize: '11px' }

const LINK: Style = { color: 'var(--accent-primary)', textDecoration: 'none' }

/**
 * WHICH MARKER THIS EVENT BELONGS TO, above the event, on the views that mix markers together.
 *
 * THE TEXT IS NOT A FILENAME AND NOT A SLUG. It is everything after the last `/`, so
 * `repo|src/main/java/Foo.java|82|CHECKER` reads `Foo.java|82|CHECKER` — the tail of a path that
 * happens to be in the middle of the key, and a different truncation from `slug()` (621-625), which
 * also replaces the punctuation. It looks like an accident and is not: `marker()`'s own page title
 * (1815) is the same expression, so the crumb you click and the heading you land on say the same
 * words. Turning this into a filename silently makes two screens disagree about what a marker is
 * called, and the check to do first is that title.
 *
 * Not a link when there is no key to link to. See {@link MarkerCrumbProps.href}.
 */
export function MarkerCrumb({ marker, href }: MarkerCrumbProps) {
  const label = marker.slice(marker.lastIndexOf('/') + 1)
  return (
    <div style={CRUMB}>
      {href === null ? (
        label
      ) : (
        <a href={href} style={LINK} className="hover:underline">
          {label}
        </a>
      )}
    </div>
  )
}
