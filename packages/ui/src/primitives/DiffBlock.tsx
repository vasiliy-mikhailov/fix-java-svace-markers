import { Fragment } from 'react'
import { BLOCK } from './block'

export type DiffBlockProps = { patch: string }

/**
 * WHAT EACH LINE DOES, WHICH IS THE ONLY THING A DIFF NEEDS (Java `colourDiff()` 2631-2642).
 *
 * Added, removed, or where — and all three are the first character. Nothing here parses the code,
 * because a patch is read for its shape before it is read for its content.
 *
 * The order of these tests is the whole of the correctness: `+++` and `---` are file headers and
 * begin with `+` and `-`, so a patch coloured by "starts with +" first paints its own header green
 * and its other header red, and every diff opens looking like a change to two files.
 */
function colourOf(line: string): string | null {
  if (line.startsWith('+++') || line.startsWith('---')) {
    return 'var(--code-comment)'
  }
  if (line.startsWith('@@')) {
    return 'var(--code-hunk)'
  }
  if (line.startsWith('+')) {
    return 'var(--code-added)'
  }
  if (line.startsWith('-')) {
    return 'var(--code-removed)'
  }
  return null
}

/**
 * A unified diff, escaped by React and coloured line by line. See `block.ts` for why the escaping
 * is here and cannot be anywhere else.
 *
 * Blank renders nothing — a fix that produced no patch has no diff, and an empty bordered box says
 * there was one and that it was empty.
 */
export function DiffBlock({ patch }: DiffBlockProps) {
  if (patch.trim().length === 0) {
    return null
  }
  // Split keeping empty trailing pieces: a blank line inside a patch is context and removing it
  // shifts every line after it away from the line number the hunk header just promised.
  const lines = patch.split('\n')
  return (
    <pre style={BLOCK}>
      {lines.map((line, index) => {
        const colour = colourOf(line)
        return (
          <Fragment key={index}>
            {colour === null ? line : <span style={{ color: colour }}>{line}</span>}
            {index < lines.length - 1 ? '\n' : ''}
          </Fragment>
        )
      })}
    </pre>
  )
}
