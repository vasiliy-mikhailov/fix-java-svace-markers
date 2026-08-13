import { Pill, type PillTone, type Style } from '../primitives'

/**
 * One build, as the record holds it.
 *
 * `phase` IS LOWERCASE ON DISK. `marker()` compares `field(e,"phase").equals("red")` (1865) and
 * `one()` uppercases only for display (2203); three earlier passes typed it `"RED"` and would have
 * matched nothing. `passed` and `infra` are written UNQUOTED — real JSON booleans, never `"true"` —
 * which is the whole reason `field()` grew its unquoted branch (2707-2722).
 */
export type BuildOutcome = { phase: 'red' | 'green'; passed: boolean; infra: boolean }

export type BuildOutcomesProps = { builds: BuildOutcome[] }

const LIST: Style = { margin: '12px 0', display: 'grid', gap: '6px' }

const ROW: Style = { display: 'flex', gap: '8px', alignItems: 'baseline', flexWrap: 'wrap' }

const SAYS: Style = { fontSize: '12.5px', color: 'var(--text-secondary)' }

type Reading = { tone: PillTone; says: string }

/**
 * THE STATE THAT MEANS ITS OPPOSITE.
 *
 * `red` is the run BEFORE the patch and it is supposed to FAIL: a test that fails before the fix
 * and passes after it is the entire standard of proof here. So a red that PASSED has demonstrated
 * nothing — it is the worst outcome on this list and the one that reads like good news everywhere
 * else in software. Lines 1872-1877 are the only place on the whole dashboard that says so, and
 * they said it in English, in the middle of a page builder. It is said here instead, so the wording
 * can be improved without redeploying the process that writes the record.
 *
 * `infra: true` IS A THIRD OUTCOME. Not a pass and not a failure — the build never reached a test
 * result, so `passed` alongside it is not a claim about the code at all, and infra is therefore
 * tested first. This is the same distinction that made `infra` a non-disposition: a prove killed by
 * its own tooling used to retire its marker as decided.
 */
function reading({ phase, passed, infra }: BuildOutcome): Reading {
  if (infra) {
    return {
      tone: 'alarm',
      says:
        phase === 'red'
          ? 'the build before the patch never reached a test result — that is the tooling failing, and it is neither a reproduction nor a refutation'
          : 'the build after the patch never reached a test result — that is the tooling failing, and it proves nothing about the fix',
    }
  }
  if (phase === 'red') {
    return passed
      ? {
          tone: 'alarm',
          says:
            'the test PASSED before the patch, so it reproduces nothing — whatever it checks, it is not this marker',
        }
      : {
          tone: 'good',
          says: 'the test failed before the patch, which is what a reproduction is',
        }
  }
  return passed
    ? { tone: 'good', says: 'the test passed after the patch' }
    : { tone: 'alarm', says: 'the test still fails after the patch' }
}

/**
 * Every build this marker ran, in the order they were run, each with what it actually proved.
 *
 * Record order and not sorted: red then green is the argument, and a list sorted by outcome would
 * put the two halves of one attempt in different places.
 *
 * Nothing built yet renders nothing — the screen owns the sentence for that absence.
 */
export function BuildOutcomes({ builds }: BuildOutcomesProps) {
  if (builds.length === 0) {
    return null
  }
  return (
    <div style={LIST}>
      {builds.map((build, index) => {
        const { tone, says } = reading(build)
        return (
          // Keyed by position: the builds of one marker are an append-only sequence and two
          // attempts legitimately produce two identical rows.
          <div key={index} style={ROW}>
            <Pill tone={tone}>{build.phase.toUpperCase()}</Pill>
            <span style={SAYS}>{says}</span>
          </div>
        )
      })}
    </div>
  )
}
