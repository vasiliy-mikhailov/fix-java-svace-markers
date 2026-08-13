import { describe, expect, it } from 'vitest'
import { CHAIN } from '@fsm/types'

import { roleWord, STAGES } from '../src/domain/ChainStrip'

/**
 * A CHIP THAT SAYS THE STAGE SAYS NOTHING, BECAUSE THE BOX ALREADY SAID IT.
 *
 * Under a heading reading REPRODUCE, three chips reading `reproduce-planner`, `reproduce-doer` and
 * `reproduce-verifier` spend most of their width repeating the heading. Across five stages that is
 * the word said twenty times, and the part that distinguishes one chip from another — the role —
 * is the short tail of each.
 *
 * So the chip shows the role. What is pinned here is that the role it shows is ITS OWN: the word
 * comes from the agent's suffix rather than from where the chip sits, because a positional list
 * survives a reordering looking right and pointing at the wrong agent. That is the exact shape of
 * the bug that grouped fifteen agents in twos — arithmetic about position, silent once the list
 * moved — and it was found by a person looking at a screenshot, not by anything here.
 */
describe('the word on a chip', () => {
  it('is a verb for each of the three roles', () => {
    expect(roleWord('reproduce-planner')).toBe('plan')
    expect(roleWord('reproduce-doer')).toBe('do')
    expect(roleWord('reproduce-verifier')).toBe('verify')
  })

  it('says the same word for a role whatever stage it is in', () => {
    // THE POINT OF THE SHORTENING: the stage is on the box, so `plan` under FIX and `plan` under
    // PRICE are not a collision — they are the column that makes the five stages scan as one shape.
    for (const stage of STAGES) {
      expect(roleWord(stage.planner), stage.label).toBe('plan')
      expect(roleWord(stage.doer), stage.label).toBe('do')
      expect(roleWord(stage.verifier), stage.label).toBe('verify')
    }
  })

  it('leaves no agent in the chain without a word', () => {
    for (const agent of CHAIN) {
      expect(roleWord(agent), `${agent} shows its full name, so its suffix is not a known role`)
        .not.toBe(agent)
    }
  })

  it('never shows a word that is longer than the name it replaced', () => {
    // A guard against the shortening quietly becoming a lengthening if the words are ever edited:
    // the whole reason the chip is short is that the strip holds fifteen of them on one line.
    for (const agent of CHAIN) {
      expect(roleWord(agent).length, agent).toBeLessThan(agent.length)
    }
  })

  it('falls back to the full name rather than to a blank chip', () => {
    // A SIXTH ROLE, or a watcher drawn on a strip by mistake, must be visible and clickable rather
    // than an empty box that looks like a rendering fault.
    expect(roleWord('overwatch')).toBe('overwatch')
    expect(roleWord('interpreter-critic')).toBe('interpreter-critic')
  })
})
