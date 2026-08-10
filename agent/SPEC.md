# The prove, as a deep agent

One marker in, one settlement out.

## What a prove is

Three facts and two builds:

1. A marker says *this file, this line, this checker, this claim.*
2. A test that **fails because of the defect** is the only evidence the claim is real.
3. A patch that makes the same test **pass** is the only evidence the defect is fixable.

Everything else is judgement about whether that evidence is worth acting on.

## The six

Each is a `SubAgentRuntime` with its own system prompt, its own tools, and its own closed set of
answers. There is no orchestrator: an agent asked to follow an order it can rewrite will rewrite it,
so `Prove.prove()` calls these in sequence.

| agent | does | tools | answers |
|---|---|---|---|
| `reproducer` | writes a JUnit test that must fail | `list_dir` `read_file` `write_file` | a file |
| `proof-critic` | judges what the test observes | `list_dir` `read_file` | `necessary` \| `reducible` |
| `fixer` | patches the defect | `list_dir` `read_file` `edit_file` | a file edit |
| `fix-skeptic` | certifies the patch | `list_dir` `read_file` | `sound` \| `over-fit` \| `regression-risk` |
| `pr-curator` | decides whether to propose it | `list_dir` `read_file` | `make` \| `reject` |
| `verdict` | argues what execution could not settle | `list_dir` `read_file` | `false-positive` \| `by-design` \| `unprovable` |

**Two write and four judge**, and the split decides the tools. A writer's output is checked by the
compiler and the build. A judge's answer is branched on, so it is read-only — a certification that
can edit its subject certifies nothing. The reproducer gets `write_file` but not `edit_file`, so it
can never make its own test pass; the fixer gets `edit_file` but not `write_file`, because creating a
new file is not patching a defect.

## The arbiter

`Runner` — `Maven` if there is a pom, `Gradle` if there is a build script. **No agent can invoke it.**
A tool is something a model chooses to call, and whether RED runs before the patch is not a choice, so
`Prove` runs the build and hands the result to the next agent as text.

`Runner.Result` has **three** outcomes, not two: `infra` is separate from `passed`. A build that
produced no test result is never evidence — in the RED phase a failing test is the goal, so a compile
error would otherwise read as success. Maven answers "did a test execute" from surefire's `Tests run:`
line; Gradle answers it from whether `build/test-results/test/` gained a report.

## The order

In Java, in `Prove.prove()`, where nothing can reorder it:

```
reproducer → RED (must fail) → proof-critic → fixer → GREEN (must pass)
           → fix-skeptic → pr-curator
```

**RED runs before the critic.** A test that does not compile cannot be over-mocked in any interesting
way, and a test that does not go red proves nothing whatever its mocks look like — grading it first
spends a model call on something no build has agreed exists.

**The compiler is a critic too, and a free one.** When RED fails to build, its error goes back to the
reproducer verbatim.

Two loops, both re-asking a **producer**, never a judge, both quoting the objection back — a producer
told only "try again" produces the same thing. Every rewrite is rebuilt: a rewrite that stops
reproducing settles `needs-review` rather than proceeding.

Evidence is assembled once and every downstream call gets it, so a retry can never be poorer than the
call it replaces.

## What silence means

One rule, applied per agent. An **objection** must be raised to bite; a **certificate** must be given
to bite.

| agent | role | silent → |
|---|---|---|
| `proof-critic` | objects | ACCEPT — an unreachable critic must not cost a proof nobody faulted |
| `fix-skeptic` | certifies | REJECT — silence is not approval for a patch |
| `pr-curator` | certifies | REJECT — nothing unreviewed reaches a stranger's repository |
| `verdict` | argues | LEAVE — settle nothing |

`rejects()` returns true on anything that is not the word `sound`, so an unreadable answer and no
answer certify equally little.

## The record

`Settlement` appends one line of JSON per prove to `results/settlements.jsonl`, in a dashboard's
column shape — `suspicion_key`, `state`, `verdict_text`, `red_verified`, `green_verified`,
`test_code`, `fix_diff`, `infra_reason`. One line per prove and not one file per marker, because a
marker legitimately proves more than once and a file named after it keeps only the last attempt.

Dispositions are **computed** where the builds established them. The verdict agent is asked only
where they established nothing — a declined proof, or a test that passed before any patch — because
that is the one case with something to argue.

## What is given up

Stated here so nobody discovers it by losing a verdict.

- **Per-stage temperature.** `DeepAgent.create` resolves one `ChatModel` for every sub-agent and
  `SubAgentDefinition` has no model field, so a reply that is branched on cannot be called at 0 while
  a written file is sampled warmer. Everything runs at 0. Safe direction, but the writers are colder
  than a prose task wants.
- **Receipts.** Nothing distinguishes "the judge approved" from "the judge was unreachable and the
  default accepted". `DeepAgentFlowListener` truncates every payload it reports.
- **A tool budget that is not ours.** `SubAgentRuntime` hardcodes 25 sequential tool calls. The
  flagged source is therefore handed over in the brief rather than fetched, so file tools are spent
  on what nobody anticipated.
- **Unattended operation.** No queue, no lease, no single-flight, no retry across restarts. This
  proves one marker when asked.

## The one domain rule

A repository may be deliberately vulnerable teaching code. If the defect IS the lesson, the settlement
is `by-design` and no pull request is proposed, however correct the patch is — patching a lesson makes
it unsolvable.
