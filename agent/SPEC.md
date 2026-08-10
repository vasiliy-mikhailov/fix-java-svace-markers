# The prove, as a deep agent

One marker in, one settlement out. Everything the old pipeline did in code, the agent does with tools.

## The claim this rests on

The 14 220 lines of the existing pipeline are almost entirely **work the model and its tools now do
directly**:

| what the old code did | what replaces it |
|---|---|
| hand-rolled JSON (`Json`, `JsonExtract`) | the library's, arriving transitively |
| an HTTP client, SSE streaming, backoff (`HttpTransport`, `HttpLlmClient`) | `OpenAiChatModel` |
| getting JSON out of prose (`ParseTest`, `ParseFix`, brace-slicing) | tool calling |
| serialising source into prompt strings (`BuildReproduceInput`, `BuildFixInput`) | `read_file` |
| a linear chain with two hand-written retry loops (`ProveChain`) | `write_todos` + `task` |
| a state machine deciding what a marker became (`RecordOutcome`, `Verdict.Route`) | the verdict sub-agent says so |
| an H2 table, a DAO, Spring Batch, a STOMP dashboard | one file per marker in the workspace |

None of that is essential to *proving a marker*. It is essential to running a **service** that proves
markers unattended — which is a different program, and the one that grew to 14k lines.

## What a prove actually is

Three facts and two builds:

1. A marker says: *this file, this line, this checker, this claim.*
2. A test that **fails because of the defect** is the only evidence the claim is real.
3. A patch that makes the same test **pass** is the only evidence the defect is fixable.

Everything else is judgement about whether the evidence is worth acting on.

## The agent

**One orchestrator.** Given a marker and a checkout, it plans with `write_todos`, delegates with
`task`, and writes `verdict.json` when it is done. It takes no decision itself that a sub-agent is
named for.

**Six sub-agents**, each with one job and a closed set of answers:

| sub-agent | does | answers |
|---|---|---|
| `reproducer` | writes a JUnit test that must fail | a file |
| `proof-critic` | judges whether the test's mocking is avoidable | `necessary` \| `reducible` |
| `fixer` | patches the defect | a file edit |
| `fix-skeptic` | certifies the patch | `sound` \| `over-fit` \| `regression-risk` |
| `pr-curator` | decides whether to propose it | `make` \| `reject` |
| `verdict` | writes the argument and names the settlement | `verified` \| `reproduced` \| `false-positive` \| `by-design` \| `unprovable` |

**Two tools.** `read_file` / `write_file` / `edit_file` / `list_dir` come with the library and operate
on the checkout. The one tool this project adds is `maven`, and it is the **arbiter**: no plan gets a
marker past a build that did not fail before the patch and pass after it.

## The order

Not the model's to choose. It lives in the orchestrator instructions:

```
reproducer → proof-critic → mvn RED (must fail) → fixer → mvn GREEN (must pass)
           → fix-skeptic → pr-curator → verdict
```

Two loops, both re-asking a **producer** and never the judge, both quoting the judge's reason back:

- `proof-critic` says `reducible` → `reproducer`, once, quoting `critic_reason`
- `fix-skeptic` says `over-fit` or `regression-risk` → `fixer`, once, quoting `skeptic_reason`

A judge told "try again" changes nothing; a producer told *what was wrong* might.

## What silence means

Each judge fails in a direction, and the directions differ on purpose:

- an unreachable **critic** ACCEPTS — it must not cost a proof nobody faulted
- an unreachable **skeptic** REFUSES — silence is not approval for a patch
- an unreachable **curator** REFUSES — the old pipeline's `make`-on-failure is a defect, not a design
- an unreachable **verdict** LEAVES the marker alone

## The record

One `verdict.json` per marker in the workspace. It carries the marker, the test, the patch, both
build results, every sub-agent's answer, and the settlement. That file is the artifact, the audit
trail, and the training corpus — no database, no schema version, no DAO.

## What is deliberately given up

Stated here so nobody discovers it by losing a verdict.

- **Per-stage temperature.** `DeepAgent.create` resolves one `ChatModel` for every sub-agent. The old
  rule — a reply that is branched on is called at 0 — becomes *everything* at 0. Safe direction; the
  two writers are colder than they were.
- **The order is prose.** The old chain enforced it structurally: fourteen statements, no branches.
  Here it is a paragraph the model is asked to follow.
- **Receipts.** Nothing distinguishes "the judge approved" from "the judge was unreachable and the
  default accepted". The old pipeline had booleans for this; the flow listener truncates.
- **Unattended operation.** No queue, no lease, no single-flight, no retry-across-restarts. This
  proves one marker when asked. A service that drains 282 of them unattended is the other program.

## Not in scope

WebGoat is deliberately vulnerable teaching code. If the defect IS the lesson, the settlement is
`by-design` and no pull request is proposed — this is the one domain rule the agent must know, and it
is one sentence in the instructions rather than a routing table.
