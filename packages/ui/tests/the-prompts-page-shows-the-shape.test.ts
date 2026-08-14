import { describe, expect, it } from 'vitest'
import { ASKED, CHAIN, WATCH } from '@fsm/types'

import { AGENT_GROUP_SAYS, spelt } from '../src/domain/AgentGroupHeading'
import { STAGES } from '../src/domain/ChainStrip'
import { stageOf } from '../src/domain/PromptStage'

/**
 * A NUMBER WRITTEN AS A WORD IS A COPY OF A FACT.
 *
 * The prompts page carried the caption "ten agents, five producer-and-critic pairs" for the nine
 * hours after the chain became fifteen agents in five planner/doer/verifier triples — printed above
 * the fifteen boxes that disproved it, on the one page whose whole purpose is editing those prompts.
 * It was the third copy of the chain's shape to go stale in a day: the strip grouped by twos, the
 * progress notes named agents that no longer existed, and this.
 *
 * Every one of those was found by a person looking at a screenshot. So the caption is COUNTED now,
 * and what is pinned here is that it is still counted — a caption that has been typed back in is a
 * caption that will be wrong again.
 */
describe('the prompts page', () => {
  it('says how many agents there are by counting them', () => {
    const said = AGENT_GROUP_SAYS.chain.account
    expect(said, 'the chain caption').toContain('fifteen agents')
    expect(said).toContain('five planner/doer/verifier triples')
    // AND THE COUNTS ARE THE LIST'S. If CHAIN grows a sixth stage this assertion is what fails, in a
    // test named for the page, rather than the caption quietly lying for another nine hours.
    expect(CHAIN.length).toBe(15)
    expect(said).not.toContain('producer')
    expect(said).not.toContain('pairs')
  })

  it('says how many watchers there are by counting them', () => {
    // THE LADDER IS SHARED. This test kept its own copy of the number words, which stopped at
    // four — so the day the watchers became two triples the caption was right and the test was
    // wrong, which is the same two-copies fault the caption itself was written to end.
    expect(AGENT_GROUP_SAYS.watch.account).toContain(`${spelt(WATCH.length)} that look`)
  })

  it('puts every chain agent in the stage its own name gives', () => {
    for (const stage of STAGES) {
      for (const agent of [stage.planner, stage.doer, stage.verifier]) {
        expect(stageOf(agent), agent).toBe(stage.label.toLowerCase())
      }
    }
  })

  it('accounts for all fifteen, three to a stage', () => {
    const counts = new Map<string, number>()
    for (const agent of CHAIN) {
      const at = stageOf(agent)
      expect(at, `${agent} runs in a prove and belongs to a stage`).not.toBeNull()
      counts.set(at!, (counts.get(at!) ?? 0) + 1)
    }
    expect([...counts.values()], 'a stage that is not three is a stage missing a role').toEqual(
      Array.from({ length: CHAIN.length / 3 }, () => 3),
    )
  })

  it('groups the watchers into their own triples, because that is what they are', () => {
    // THEY USED TO HAVE NO STAGE and the assertion here was that they had none. They are two triples
    // now — overwatch and interpreter, each planner/doer/verifier — and a page that grouped five of
    // the seven and left two as a loose column would be drawing the old shape. What still separates
    // them is the heading above them, which says they take no part in a prove.
    const stages = new Set(WATCH.map(a => stageOf(a)))
    expect([...stages].sort()).toEqual(['interpreter', 'overwatch'])
    for (const agent of WATCH) {
      expect(stageOf(agent), `${agent} belongs to a stage`).not.toBeNull()
    }
  })

  it('leaves the agent that is asked rather than scheduled ungrouped', () => {
    // `chat` is not `<stage>-<role>` and is not a triple. It falls through to null and renders in
    // the flow, which is what it is: one thing, not a third of one.
    for (const agent of ASKED) {
      expect(stageOf(agent), `${agent} is neither a stage nor a role`).toBeNull()
    }
  })

  it('keeps the stages contiguous in the order the page is given', () => {
    // THE GROUPING NEVER REORDERS. The page emits a section when the stage CHANGES, which is only
    // correct while a stage's three agents are adjacent in ORDER — the property that also lets an
    // agent this build has never heard of stay where the server put it.
    const seen: string[] = []
    for (const agent of CHAIN) {
      const at = stageOf(agent)!
      if (seen[seen.length - 1] !== at) {
        seen.push(at)
      }
    }
    expect(seen.length, 'a stage appears twice, so its agents are not adjacent').toBe(
      new Set(seen).size,
    )
    expect(seen).toEqual(STAGES.map(s => s.label.toLowerCase()))
  })
})
