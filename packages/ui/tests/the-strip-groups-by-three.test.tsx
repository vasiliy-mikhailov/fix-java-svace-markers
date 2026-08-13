import { describe, expect, it } from 'vitest'
import { CHAIN } from '@fsm/types'

import { STAGES } from '../src/domain/ChainStrip'

/**
 * GROUPING BY THE WRONG NUMBER DOES NOT FAIL — IT DRAWS.
 *
 * <p>The chain went from five producer/critic pairs to five planner/doer/verifier triples, and this
 * strip kept stepping by two. So REPRODUCE held the planner and the doer, FIX held the
 * reproduce-verifier and the fix-planner, PRICE held two agents belonging to neither stage — every
 * label wrong from the second one on, every tab still clickable, and nothing anywhere reporting it.
 *
 * <p>It was found in a screenshot. Nothing else could have found it: the types were satisfied
 * (`ChainAgent` is any name in the chain, and every one of those pairs was two of them), the page
 * rendered, and the counts on the chips were real. A test that asserted "five stages appear" would
 * have passed too.
 *
 * <p>So what is pinned here is the ARITHMETIC against the list it groups, rather than the drawing.
 */
describe('the chain strip', () => {
  it('accounts for every agent in the chain, exactly once', () => {
    const shown = STAGES.flatMap(s => [s.planner, s.doer, s.verifier])
    expect(shown).toEqual([...CHAIN])
    expect(new Set(shown).size, 'an agent in two stages is a grouping that has slipped').toBe(
      shown.length,
    )
  })

  it('leaves no remainder, so no stage is short of a role', () => {
    expect(CHAIN.length % 3, `${CHAIN.length} agents does not divide into stages of three`).toBe(0)
    expect(STAGES).toHaveLength(CHAIN.length / 3)
  })

  it('puts the three roles of a stage in the stage that owns them', () => {
    // THE NAMES CARRY THE ANSWER, which is the whole reason they were renamed. A strip that groups
    // correctly has every chip in a box whose label is that chip's own prefix; the pair-shaped one
    // failed this on four of five stages.
    for (const stage of STAGES) {
      for (const agent of [stage.planner, stage.doer, stage.verifier]) {
        expect(agent, `${agent} is drawn under ${stage.label}`).toContain(stage.label)
      }
    }
  })

  it('names each role by its suffix, in planner-doer-verifier order', () => {
    for (const stage of STAGES) {
      expect(stage.planner.endsWith('-planner'), stage.planner).toBe(true)
      expect(stage.doer.endsWith('-doer'), stage.doer).toBe(true)
      expect(stage.verifier.endsWith('-verifier'), stage.verifier).toBe(true)
    }
  })
})
