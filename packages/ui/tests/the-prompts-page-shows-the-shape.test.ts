import { describe, expect, it } from 'vitest'
import { ASKED, CHAIN, WATCH } from '@fsm/types'

import { AGENT_GROUP_SAYS } from '../src/domain/AgentGroupHeading'
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
    expect(AGENT_GROUP_SAYS.watch.account).toContain(`${['no', 'one', 'two', 'three', 'four'][WATCH.length]} that look`)
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

  it('gives no stage to an agent that takes no part in a prove', () => {
    // A WATCHER LAID OUT AS A THIRD OF A TRIPLE would say it was part of one. It shares the grid —
    // one card width for the whole page — but not the heading.
    for (const agent of [...WATCH, ...ASKED]) {
      expect(stageOf(agent), `${agent} does not run in a prove`).toBeNull()
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
