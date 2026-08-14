import type { MarkerKey } from '@fsm/types'
import type { Style } from '../primitives'

export type MarkerIdentityProps = {
  markerKey: MarkerKey
  /**
   * The path as the settlement row has it, else the second field of the key.
   *
   * THE CALLER RESOLVES THAT, NOT THIS COMPONENT (Java `index()` 1720-1726). A queued marker has no
   * settlement row and used to render an all-but-empty cell; the key is `repo|file|line|checker` and
   * is always present, so there is always something to show. Nothing here branches on whether a
   * marker has been reached — there is no prop with which it could.
   */
  file: string
  /** A string, because it comes out of the key by splitting on `|` and may be anything at all. */
  line: string
  checker: string
}

const LINK: Style = { color: 'var(--accent-primary)', textDecoration: 'none' }

/** Java's `.k` (CSS 52): the small grey line. Two of them, under the name. */
const QUIET: Style = { color: 'var(--text-tertiary)', fontSize: '11px' }

/**
 * WHAT AND WHERE, in the second column of the markers table.
 *
 * `B.java:82`, linked to the marker's own page, with the checker under it and the package tail under
 * that. Three facts in the width of one, ordered by how a reader uses them: which file, what was
 * complained about, and — only when the first two are not enough — where in the tree.
 *
 * THE DIRECTORY LINE EXISTS BECAUSE FILENAMES COLLIDE. Two markers in one run are routinely both
 * `LessonPage.java`, and a table where two rows read identically is a table you have to open both
 * rows to use. `src/main/java/` and `src/test/java/` come off it (1732) because every path in a Maven
 * tree starts with one of them, so those twelve characters distinguish nothing and push the part that
 * does off the end of the column.
 */
export function MarkerIdentity({ markerKey, file, line, checker }: MarkerIdentityProps) {
  const name = file.slice(file.lastIndexOf('/') + 1)
  const directory = file.includes('/') ? file.slice(0, file.lastIndexOf('/')) : ''
  // `replaceAll`, because Java's two-CharSequence `replace` is itself replace-all — a path with
  // `src/main/java/` twice in it (a nested checkout) would keep the second one otherwise.
  const tail = directory.replaceAll('src/main/java/', '').replaceAll('src/test/java/', '')
  return (
    <>
      {/*
       * A ROOT-RELATIVE URL, which is right standalone and wrong the moment a shell mounts this zone
       * under a prefix — the same hazard `apps/web/lib/api.ts` exists to close for fetches. The
       * props fix the marker's identity and not its address, so the base path has to reach this
       * component some other way (a provider, or the shell rewriting hrefs); until it does, this is
       * the one link on the screen that is built rather than passed, and it is built here so there
       * is exactly one place to change.
       */}
      <a href={`/marker?k=${encodeURIComponent(markerKey)}`} style={LINK} className="hover:underline">
        {line === '' ? name : `${name}:${line}`}
      </a>
      {checker === '' ? null : <div style={QUIET}>{checker}</div>}
      {tail === '' ? null : <div style={QUIET}>{tail}</div>}
    </>
  )
}
