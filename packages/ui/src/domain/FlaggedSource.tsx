import { BLOCK } from '../primitives/block'
import type { Style } from '../primitives/style'
// Aliased only because the component and the record it renders have the same name, and one file
// cannot read as if they were the same thing. The record is declared once, in `records.ts`, because
// `MarkerRow` hands this payload straight through and a component that redeclares its neighbour's
// shape is how a field gets renamed on one side of a boundary and ignored on the other.
import type { FlaggedSource as Source } from './records'

export type FlaggedSourceProps = {
  /**
   * Null ⇒ renders nothing. A missing checkout is a missing convenience, not an error, and Java was
   * right to return "" rather than fail the page for it (2578-2580).
   *
   * WHOEVER BUILDS `lines` MUST READ BLANK LINES (Java 2570-2574). Not the JSONL reader, which drops
   * them: it is right for a record where a blank line is nothing and wrong for source, where a blank
   * line is line 79 and every number after it shifts. It put `public ResponseEntity
   * getProfilePicture` four lines below the truth and that was nearly written up as a finding about
   * the analyser. Shipping lines as data moves that hazard to the builder; it does not remove it.
   */
  source: Source | null
}

const ROW: Style = { display: 'block' }

/**
 * The flagged line, in the palette's own word for it.
 *
 * NOT A `">> "` GLUED INTO THE TEXT. Java marked it by prefixing the string (2586) and the mark was
 * therefore indistinguishable from source that happened to start with two angle brackets. The mark
 * is now `flagged`, a number, compared against the line's own number. The glyph stays as well as
 * the wash, because a highlight that exists only as a colour is no highlight in a greyscale
 * printout, and because the wash is lost the moment anybody copies these lines into a comment.
 */
const FLAGGED_ROW: Style = {
  ...ROW,
  background: 'var(--code-flagged-bg)',
  color: 'var(--text-primary)',
}

const GUTTER: Style = { color: 'var(--text-tertiary)' }

/**
 * The sentence that is the difference between trusting a line number and knowing not to. It is a
 * comparison, not prose — see the component.
 */
const STALE: Style = {
  margin: '4px 0 8px',
  fontSize: '11.5px',
  color: 'var(--state-needs-review)',
}

/**
 * THE LINE THE ANALYSER FLAGGED, WITH THE CODE AROUND IT. Java `flagged()` 2561-2597.
 *
 * A verdict about `ProfileUploadBase.java:82` is not checkable without line 82. The argument names
 * it, quotes fragments of it and reasons about it, and before this a reader had to open the marker,
 * open the reproducer's prompt and scroll to find the four lines the whole thing is about. They are
 * four lines. They belong next to the verdict.
 *
 * THE WINDOW IS ±4 AND IS DECIDED UPSTREAM (`AROUND` 2547): enough to see the statement the flagged
 * line is part of. This component renders the window it is handed and does not resize it, so a
 * reader who wants more asks the server, not the DOM.
 *
 * THERE IS NO OFFSET ARITHMETIC HERE, and that is the point of `SourceLine` carrying its own `n`.
 * The shape this replaced was `{firstLine, flaggedLine, fileLength, lines[]}` — an offset beside an
 * array — which makes a window starting at line 1 and a window starting at line 78 need the same
 * sum done correctly in every place that renders one.
 *
 * NO SYNTAX COLOURING, DELIBERATELY. Java ran `colourJava()` over this blob whatever the file was
 * (2653), so a Kotlin or XML fragment came out with `int` and `class` highlighted wherever those
 * words happened to fall. `FlaggedSource` carries no language, so there is nothing here that could
 * honestly claim one — `CodeBlock`'s `language` prop is opt-in for exactly this reason. Wrapping a
 * language into the payload is the way to get colour back; guessing is not.
 *
 * The shell is `BLOCK`, shared with `CodeBlock` and `DiffBlock`, rather than a fourth copy of the
 * same eight declarations. `white-space: pre` there is load-bearing: a wrapped line no longer lines
 * up with its number, and lining up is the entire point of showing the neighbours.
 */
export function FlaggedSource({ source }: FlaggedSourceProps) {
  if (source === null) {
    return null
  }
  const { lines, flagged, fileLines } = source
  // THE MARKERS CAME OFF AN OLDER REVISION and some of them point past the end of the file as it
  // stands now. Java appended that sentence to the code blob (2589-2595), where it was text among
  // text and a reader had to notice it; it is a comparison of two numbers the payload already
  // carries, and it renders as its own paragraph, outside the source.
  const stale = fileLines !== null && flagged > fileLines
  if (lines.length === 0 && !stale) {
    return null
  }
  // Line 998 and line 1002 in the same window must not shunt each other out of alignment, so the
  // gutter is as wide as its widest number. `flagged` is in the max because the window can end
  // before it and still be the right window — that is exactly what `stale` means.
  const width = String(Math.max(flagged, ...lines.map(line => line.n))).length
  return (
    <>
      {lines.length === 0 ? null : (
        <pre style={BLOCK}>
          {lines.map(line => {
            const isFlagged = line.n === flagged
            return (
              <span key={line.n} style={isFlagged ? FLAGGED_ROW : ROW}>
                {/* The gutter is always non-empty, which is what keeps a blank source line a
                    visible row: an empty block box in a `<pre>` has no height, and a blank line
                    that silently disappears is the same lie as a reader that drops it. */}
                <span style={GUTTER}>{`${isFlagged ? '>>' : '  '} ${String(line.n).padStart(width)}  `}</span>
                {line.text}
              </span>
            )
          })}
        </pre>
      )}
      {stale ? (
        <p style={STALE}>
          line {flagged} — THIS FILE HAS {fileLines}. The analyser ran against an older revision.
        </p>
      ) : null}
    </>
  )
}
