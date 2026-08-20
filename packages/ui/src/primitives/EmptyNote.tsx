/**
 * WHAT A PAGE SAYS WHEN THERE IS NOTHING ON IT, from `ratchet-ui`.
 *
 * This is the one pair where neither version could do anything the other could not, so it went to
 * the sibling's — an italic line rather than a forty-eight-pixel block. Most of the call sites here
 * are inline notes beside content, where the compact form reads better; the two that are genuine
 * whole-page empty states re-add the room with a wrapper, which is a fact about those pages rather
 * than about this primitive.
 *
 * <p>WHAT SURVIVED IS THE ARGUMENT. The shared component has no default copy either and takes only
 * `children`. Three passes here proposed `EmptyRun`, `EmptyTrace` and `EmptyNote` with the sentence
 * baked into each, and the sentences were the only thing that differed — a default sentence is the
 * one shown on a screen nobody wrote copy for, and a wrong reassurance is worse than a blank page.
 * Which is what happened: `/trace` hard-coded "Nothing traced for this marker" and showed it on the
 * whole-trace view, where there is no marker and nothing traced anywhere. The shared file carries
 * that reasoning in its own comment, because it is the thing most likely to be undone by somebody
 * who has not read it.
 */
export { EmptyNote, type EmptyNoteProps } from 'ratchet-ui/components'
