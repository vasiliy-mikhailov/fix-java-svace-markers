import { Fragment, type ReactNode } from 'react'
import type { Style } from './style'

export type ProseProps = {
  /** What an agent wrote. Plain prose by instruction; markdown by habit. */
  children: string
  quiet?: boolean
}

const BLOCK: Style = { margin: '.5rem 0 .2rem', fontSize: '.95rem', lineHeight: 1.7, maxWidth: '52em' }

const CODE: Style = {
  fontFamily: 'var(--font-mono, ui-monospace, monospace)',
  fontSize: '.9em',
  background: 'var(--bg-panel)',
  border: '1px solid var(--border-strong)',
  borderRadius: '4px',
  padding: '0 .25em',
}

const ITEM: Style = { margin: '.15rem 0 .15rem 1.1rem' }

/**
 * MARKDOWN AN AGENT WROTE, SHOWN AS MARKDOWN.
 *
 * <p>The prompts ask for plain prose and mostly get it — but a model reaches for `**bold**` and
 * backticks the way anyone does, and a page that renders those literally shows a reader asterisks
 * around the word the writer was emphasising. Worse where it matters most: a file path or a method
 * name in backticks is precisely the part somebody is scanning for.
 *
 * <p>NO LIBRARY AND NO `dangerouslySetInnerHTML`. This text is written by a language model reading a
 * repository somebody else controls, so it is UNTRUSTED — a summary quoting a file that contains a
 * `<script>` tag would put it on the page. React elements are built instead, which cannot inject
 * markup by construction, and the cost is that only the subset agents actually produce is handled:
 * paragraphs, `**bold**`, `` `code` ``, and `-` bullets.
 *
 * <p>Anything else is shown verbatim rather than swallowed. A renderer that silently drops what it
 * does not understand is one that loses a sentence and never says which.
 */
function inline(text: string, key: string): ReactNode[] {
  const out: ReactNode[] = []
  // One pass, alternating: `code` wins over **bold** because a path may contain asterisks.
  const pattern = /`([^`]+)`|\*\*([^*]+)\*\*/g
  let at = 0
  let m: RegExpExecArray | null
  let n = 0
  while ((m = pattern.exec(text)) !== null) {
    if (m.index > at) {
      out.push(text.slice(at, m.index))
    }
    if (m[1] !== undefined) {
      out.push(
        <code key={`${key}c${n}`} style={CODE}>
          {m[1]}
        </code>,
      )
    } else {
      out.push(<strong key={`${key}b${n}`}>{m[2]}</strong>)
    }
    at = m.index + m[0].length
    n += 1
  }
  if (at < text.length) {
    out.push(text.slice(at))
  }
  return out
}

export function Prose({ children, quiet = false }: ProseProps) {
  const style: Style = quiet
    ? { ...BLOCK, fontSize: '.74rem', lineHeight: 1.6, color: 'var(--text-tertiary)' }
    : { ...BLOCK, color: 'var(--text-secondary)' }
  // A blank line separates paragraphs; a single newline inside one is a wrap the writer did not mean.
  const blocks = children.split(/\n\s*\n/).filter(b => b.trim().length > 0)
  return (
    <>
      {blocks.map((block, i) => {
        const lines = block.split('\n').map(l => l.trim())
        const bullets = lines.filter(l => /^[-*]\s+/.test(l))
        if (bullets.length > 0 && bullets.length === lines.length) {
          return (
            <ul key={`p${i}`} style={{ ...style, paddingLeft: 0, listStyle: 'disc' }}>
              {lines.map((l, j) => (
                <li key={`p${i}i${j}`} style={ITEM}>
                  {inline(l.replace(/^[-*]\s+/, ''), `p${i}i${j}`)}
                </li>
              ))}
            </ul>
          )
        }
        return (
          <p key={`p${i}`} style={style}>
            {inline(lines.join(' '), `p${i}`)}
          </p>
        )
      })}
    </>
  )
}
