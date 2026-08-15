import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'

import { AnsweredEvent, standingOf, taskOf } from '../src/domain/TraceEvent'

/**
 * THE RECORD WAS A COLUMN OF ANSWERS TO QUESTIONS NOBODY COULD SEE.
 *
 * `trace.asked` records `systemPrompt + "\n\n---\n\n" + task`, and the trace page folded the whole
 * thing behind one summary reading "the prompt it was given (17431 chars)", collapsed. So the small
 * specific half — the one thing this agent was asked, which is what distinguishes this event from
 * every other — was hidden behind the large repeated half, which is identical on every call that
 * agent makes and already has its own tab.
 *
 * Reading a prove therefore meant clicking seventeen folds and scrolling past the same standing
 * instructions seventeen times. The task is shown now; the standing prompt stays folded.
 */
describe('an answered event', () => {
  const STANDING = 'You write ONE JUnit test that fails because of the defect the marker names.'
  const TASK = 'MARKER: Assignment5.java:44 TAINTED_PTR\nThe flagged source:\n  44:  sql += login;'
  const RECORDED = `${STANDING}\n\n---\n\n${TASK}`

  it('splits a recorded prompt into what changes and what does not', () => {
    expect(taskOf(RECORDED)).toBe(TASK)
    expect(standingOf(RECORDED)).toBe(STANDING)
  })

  it('splits on the FIRST separator, because a task quotes diffs', () => {
    // A brief carries build logs and `git diff` output, and both contain `---` of their own.
    // Splitting on the last would swallow the question into the answer.
    const withDiff = `${STANDING}\n\n---\n\n${TASK}\n\n---\n\n--- a/Foo.java\n+++ b/Foo.java`
    expect(standingOf(withDiff)).toBe(STANDING)
    expect(taskOf(withDiff)).toContain('--- a/Foo.java')
  })

  it('treats a prompt with no separator as all task, never losing it', () => {
    // Some rows predate the convention. Showing the whole thing is right; showing nothing is not.
    expect(taskOf('just a prompt')).toBe('just a prompt')
    expect(standingOf('just a prompt')).toBe('')
  })

  it('shows the task beside the reply, not behind a fold', () => {
    const html = renderToStaticMarkup(
      <AnsweredEvent
        agent="reproduce-doer"
        reply="no test"
        prompt={RECORDED}
        eventId="e1"
        marker="m"
        back="/"
        defaultOpen={false}
      />,
    )
    const task = html.indexOf('TAINTED_PTR')
    const reply = html.indexOf('no test')
    expect(task, 'the task must be on the page without opening anything').toBeGreaterThan(-1)
    expect(task, 'and above the reply, because that is the order they happened in').toBeLessThan(reply)
  })

  it('keeps the standing prompt folded, and does not repeat it in the open', () => {
    const html = renderToStaticMarkup(
      <AnsweredEvent
        agent="reproduce-doer"
        reply="no test"
        prompt={RECORDED}
        eventId="e1"
        marker="m"
        back="/"
        defaultOpen={false}
      />,
    )
    // It is the same text on every call this agent makes; open by default it would be the page.
    expect(html).toContain('the standing prompt underneath it')
    expect((html.match(/You write ONE JUnit test/g) ?? []).length,
      'the standing half must appear once, inside the fold').toBe(1)
  })
})
