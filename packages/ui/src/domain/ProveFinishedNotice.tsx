import { EmptyNote } from '../primitives'

/**
 * Nothing today, and the empty shape is deliberate rather than an oversight — see the component.
 */
export type ProveFinishedNoticeProps = Record<string, never>

/**
 * WHAT A LIVE VIEW SAYS WHEN THERE IS NOTHING LIVE ABOUT IT (live() 845-848).
 *
 * The `.live` file OUTLIVES the prove that wrote it. When the call ends the real trace rows are
 * written and this file becomes a stale copy of the last thing anyone said, so a view that kept
 * rendering it would be a live view that is quietly a museum — showing an agent mid-sentence hours
 * after it stopped, with a clock beside it counting up.
 *
 * THE TEST IS A COMPLEMENT, NOT A MATCH, and it belongs on the server (ApiLive.settled(), which is
 * `settled()` 891-900 unchanged). Finished means: not blank, not `proving`, not `infra`, not
 * `queued`. Written the other way round — a match against the seven dispositions — a state nobody
 * has thought of yet would read as still running, and a live view of a prove that is over never
 * stops polling. The direction of the guess is the whole point of writing it as a complement.
 *
 * It is a component and not a sentence typed into a screen because the sentence is a domain claim
 * about that file, and every screen that watches a prove owes the reader the same one.
 *
 * "The tabs above" links to nothing, here as in the Java. The tab row is the only exit and it
 * belongs to the screen, not to this notice — which is why the wording points at it rather than
 * offering a link this component has no key to build.
 */
export function ProveFinishedNotice() {
  return (
    <EmptyNote>
      This prove has finished. What it said is on the tabs above; this view is only for one still
      running.
    </EmptyNote>
  )
}
