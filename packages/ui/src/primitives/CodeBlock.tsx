import type { ReactNode } from 'react'
import { BLOCK } from './block'

export type CodeBlockProps = {
  code: string
  /**
   * Colour it as this language, or leave it uncoloured.
   *
   * The Java's `block()` (2618-2622) ran `colourJava()` over EVERYTHING it was given, whatever the
   * file actually was — a Kotlin or XML fragment came out with `int` and `class` highlighted
   * wherever they happened to appear as words. This prop is here so that stops being silent:
   * absent means no colouring, which is the honest render for a language nothing here can lex.
   */
  language?: 'java'
}

/**
 * Java's words, its strings and its comments. Three colours, which is what a reader uses.
 *
 * ONE PASS, IN ONE ALTERNATION (Java `JAVA` pattern 2665-2679). A keyword inside a string has to
 * stay inside the string and a quote inside a comment must not open one; colouring by running four
 * separate replacements over the same text is how `// the "public" API` comes out with half a
 * comment and a stray keyword in it.
 *
 * WHAT PROTECTS A STRING IS CONSUMPTION, NOT THE ORDER OF THE BRANCHES — and this comment claimed
 * the opposite. A global pattern finds the earliest match, takes the WHOLE token there, and resumes
 * after it, so the quote seven characters into a `//` comment is never offered to the scanner and
 * `public` is never a starting position at all.
 *
 * The order is not doing that work, because there is none to do: the four branches have disjoint
 * opening characters — `/`, a quote, a letter, a digit — and alternation order only decides ties at
 * the same starting index. There are none. Checked by running this alternation and its exact reverse
 * over adversarial inputs including `// the "public" API`, a keyword inside a string, a quote inside
 * a block comment, an escaped quote, and hex, underscored and exponent literals: identical tokens
 * from both orders, every time.
 *
 * THE ORDER STAYS ANYWAY, as the right default for the first branch anyone adds whose opening
 * character overlaps an existing one — an annotation, a text block. But a reader who believes order
 * is the mechanism will reorder branches the day a string breaks, and the branches are not where the
 * bug will be.
 */
const JAVA =
  /(?<comment>\/\/[^\n]*|\/\*[\s\S]*?\*\/)|(?<string>"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(?<word>\b(?:abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|var|void|volatile|while|true|false|null|record|sealed|yield)\b)|(?<number>\b\d[\w.]*)/g

/** The four token colours, from `domain.css`. A colour never appears here as a literal. */
const COLOUR = {
  comment: 'var(--code-comment)',
  string: 'var(--code-string)',
  word: 'var(--code-keyword)',
  number: 'var(--code-number)',
} as const

type TokenKind = keyof typeof COLOUR

function kindOf(groups: Record<string, string | undefined>): TokenKind {
  return groups['comment'] !== undefined
    ? 'comment'
    : groups['string'] !== undefined
      ? 'string'
      : groups['word'] !== undefined
        ? 'word'
        : 'number'
}

function colourJava(source: string): ReactNode[] {
  const out: ReactNode[] = []
  let at = 0
  // `matchAll` NEVER WRITES THE SHARED CURSOR, which is a sharper purchase than re-entrancy and is
  // not what this comment used to claim. It does not RESET `lastIndex` either: per spec it clones
  // the pattern, copies the current `lastIndex` into the clone, and advances only the clone. One
  // `exec` anywhere leaves the cursor mid-source, and the next scan copies it in and returns fewer
  // tokens, or none — with no error, from a file that is not the file that looks wrong.
  //
  // So every block starts at zero only because nothing ever moves it off zero, and the rule that
  // falls out is the one worth writing down: THIS PATTERN IS ONLY EVER HANDED TO `matchAll`. Never
  // `exec`, never `test`, by anyone. `the-lexer-is-only-ever-scanned` holds that, and demonstrates
  // the failure rather than asserting it.
  for (const match of source.matchAll(JAVA)) {
    // A match always carries both; the fallbacks are for the type, which cannot know that.
    const text = match[0] ?? ''
    const start = match.index ?? at
    if (start > at) {
      out.push(source.slice(at, start))
    }
    const kind = kindOf(match.groups ?? {})
    out.push(
      <span
        key={start}
        style={{ color: COLOUR[kind], fontStyle: kind === 'comment' ? 'italic' : 'normal' }}
      >
        {text}
      </span>,
    )
    at = start + text.length
  }
  out.push(source.slice(at))
  return out
}

/**
 * A block of source, escaped by React and coloured by what it is.
 *
 * Blank renders NOTHING (Java `block()` returned "" for null or blank). A marker whose tree could
 * not be read has no flagged source, and an empty bordered box claims there is source and that it
 * is empty — a different and wrong statement.
 */
export function CodeBlock({ code, language }: CodeBlockProps) {
  if (code.trim().length === 0) {
    return null
  }
  return <pre style={BLOCK}>{language === 'java' ? colourJava(code) : code}</pre>
}
