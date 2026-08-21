// THE HEADING IS THE LIBRARY'S, AND THE MARGIN STAYS OURS. Our four copies of these five
// declarations all omitted `fontWeight`, which is not a declaration that drifted but one nobody
// made: an `h3` with no weight takes the browser's bold, so ours drew at 700 beside the
// sibling's 500. The shared constant names the weight; only the margin is per-site.
import { HEADING } from 'ratchet-ui/components'
import { DiffBlock } from '../primitives'

export type FixDiffProps = {
  /** `fix_diff` from `settlements.jsonl`. A unified diff, exactly as the fixer produced it. */
  patch: string
}

/**
 * The patch.
 *
 * BUG NOT PORTED (#9, and the worst dependency anywhere on this dashboard: `marker()` 1911-1930).
 * The patch was never recorded as an artefact, so the page SCRAPED IT OUT OF THE TEXT OF
 * FIX-CRITIC'S PROMPT — everything between the heading `WHAT IT ACTUALLY CHANGED` and whichever of
 * `\nThe patch changes ` or `\nTHE PATCH DOES NOT TOUCH` came first. Reword either sentence in a
 * prompt file, which is a thing this project does through a settings page on purpose, and the fix
 * silently vanishes from the one screen that exists to show it. Nothing fails; the page just stops
 * saying what was changed.
 *
 * `settlements.jsonl` has `fix_diff`. It is served, and the scrape is gone.
 *
 * Blank renders nothing: `DiffBlock` returns null for a blank patch, because an empty bordered box
 * says a fix produced a patch and that the patch was empty.
 */
export function FixDiff({ patch }: FixDiffProps) {
  if (patch.trim().length === 0) {
    return null
  }
  return (
    <section>
      <h3 style={{ ...HEADING, margin: '12px 0 0' }}>what the fix changed</h3>
      <DiffBlock patch={patch} />
    </section>
  )
}
