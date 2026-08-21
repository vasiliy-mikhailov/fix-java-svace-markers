import type { ReactNode } from 'react'
import { EmptyNote } from './EmptyNote'
import type { Style } from './style'

/** The inset a note needs when it sits under a full-bleed header. */
const GUTTER: Style = { padding: '0 24px' }

export type LoadedProps<T> = {
  /**
   * THE NOUN, BARE, and this supplies the article: `run`, `record`, `marker`, `conversation`.
   *
   * It appears in both sentences, which is the point — a screen that says "Reading the queue…"
   * while it waits and "The subject could not be read" when it fails has told the reader about two
   * different things, and left them to work out that it is one.
   */
  what: string
  /** What the failure sentence calls it, when that is not "the {what}". */
  subject?: string
  /** The message from the failed read, or null while nothing has failed. */
  failed: string | null
  /** What was read, or null until it arrives. */
  value: T | null
  /**
   * The page header, for a screen that is a whole page rather than a card inside one.
   *
   * A PAGE KEEPS ITS HEADER UP THROUGH BOTH WAITS. A reader who follows a link and gets a blank
   * document cannot tell a slow read from a broken one, and has nothing to go back with. Passing it
   * also insets the note to the page gutter, because a note under a full-bleed header would
   * otherwise be the only thing on the page holding the left edge.
   */
  header?: ReactNode
  children: (value: T) => ReactNode
}

/**
 * THE THREE STATES OF A THING BEING READ, in the one order that is correct.
 *
 * <p>Borrowed from the sibling harness, which extracted it after writing it out nine times across
 * five files. This repository had it nine times across six — a pair of early returns on four
 * screens, a nested ternary on the fifth, and four more inside the settings tabs — with the wording
 * drifting between them: only the marker page ever said what it was waiting FOR, so the other five
 * showed a bare header and nothing else while they read.
 *
 * <p>FAILURE BEATS EMPTINESS. A read that failed also has no value, so testing the value first
 * describes every failure as a wait, and a reader waits for something that is never coming. Every
 * copy here happened to have that order right; the sibling's javadoc records one of its own that
 * did not, and it is the reason this is a component rather than a snippet.
 *
 * <p>EMPTINESS IS STATED RATHER THAN DRAWN AS NOTHING, which is {@link EmptyNote}'s argument and
 * this inherits it: a page that renders nothing while it waits cannot be told from a page that
 * finished and found nothing.
 */
export function Loaded<T>({ what, subject, failed, value, header, children }: LoadedProps<T>) {
  if (failed === null && value !== null) {
    return <>{children(value)}</>
  }
  const note = (
    <EmptyNote>
      {failed === null
        ? `Reading the ${what}…`
        : `${subject ?? `The ${what}`} could not be read: ${failed}`}
    </EmptyNote>
  )
  if (header === undefined) {
    return note
  }
  return (
    <>
      {header}
      <div style={GUTTER}>{note}</div>
    </>
  )
}
