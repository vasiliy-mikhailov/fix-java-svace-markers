import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'

import { Prose } from '../src/primitives'

/**
 * THE COLUMN SHOWED ASTERISKS, AND THE REASON IT HAD ANY WAS A DIFFERENT BUG.
 *
 * The marker table's account was rendered inside a plain `<p>`, so `**bold**` and backticked file
 * paths reached the reader as punctuation. It was noticed because that column was full of markdown —
 * and it was full of markdown because `Interpreter` was writing the VERIFIER's audit report there
 * instead of the summary, and an audit is exactly the shape a model writes with headings and bullets.
 *
 * Both are fixed. This holds the rendering half, and the property that matters most about it:
 *
 * THE TEXT IS UNTRUSTED. It is written by a language model that has just read a repository somebody
 * else controls, and it quotes that repository. A renderer using `dangerouslySetInnerHTML` would put
 * whatever a summary quoted onto the page — so this builds React elements, which cannot inject
 * markup by construction, and the test that says so is the one to keep if any are dropped.
 */
describe('the account a model wrote', () => {
  it('renders bold and code rather than showing their punctuation', () => {
    const html = renderToStaticMarkup(
      <Prose>{'The result of `getConnection()` is closed in a **try-with-resources**.'}</Prose>,
    )
    expect(html).toContain('<code')
    expect(html).toContain('getConnection()')
    expect(html).toContain('<strong>try-with-resources</strong>')
    // The punctuation itself is gone — that is the whole complaint.
    expect(html).not.toContain('**')
    expect(html).not.toContain('`')
  })

  it('cannot put markup on the page, whatever the summary quotes', () => {
    // A SUMMARY QUOTES THE SUBJECT'S SOURCE. If the flagged file contains a script tag, the agent
    // may quote it faithfully, and faithfully is exactly the problem.
    const nasty = 'The lesson renders <script>alert(1)</script> and <img src=x onerror=alert(1)>.'
    const html = renderToStaticMarkup(<Prose>{nasty}</Prose>)
    // WHAT MATTERS IS THAT NOTHING IS A TAG. The characters `onerror=` still appear — as text,
    // inside an escaped `&lt;img …&gt;` — and asserting on the string rather than on the markup was
    // this test failing itself: inert text that reads like an attribute is not an attribute.
    expect(html).not.toContain('<script>')
    expect(html).not.toContain('<img')
    expect(html).toContain('&lt;script&gt;')
    expect(html).toContain('&lt;img')
  })

  it('keeps paragraphs and turns a dash list into a list', () => {
    const html = renderToStaticMarkup(
      <Prose>{'First paragraph.\n\n- one thing\n- another thing'}</Prose>,
    )
    expect(html).toContain('<p')
    expect(html).toContain('<ul')
    expect((html.match(/<li/g) ?? []).length).toBe(2)
  })

  it('joins a wrapped line rather than breaking it', () => {
    // A single newline inside a paragraph is where the writer's line ended, not a break it meant.
    const html = renderToStaticMarkup(<Prose>{'a sentence that\nwrapped here'}</Prose>)
    expect(html).toContain('a sentence that wrapped here')
  })

  it('shows what it does not understand rather than swallowing it', () => {
    // A renderer that silently drops the syntax it cannot handle loses a sentence and never says
    // which one. Unhandled markup stays visible as the author typed it.
    const html = renderToStaticMarkup(<Prose>{'a [link](http://x) and ## a heading'}</Prose>)
    expect(html).toContain('[link](http://x)')
    expect(html).toContain('## a heading')
  })

  it('renders an unterminated backtick as text instead of eating the rest', () => {
    const html = renderToStaticMarkup(<Prose>{'the field `SECRETS is package-private'}</Prose>)
    expect(html).toContain('SECRETS is package-private')
  })
})
