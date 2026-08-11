# Proving static-analysis markers

A marker claims a defect at a file and a line. A prove either demonstrates it or argues it away.

```
mvn -q -DskipTests package -f agent

QWEN_BASE_URL=… QWEN_API_KEY=… QWEN_MODEL=… \
  docker run -p 8085:8085 -v results:/results fsm-agent prove 'repo|file|line|checker'
```

`entrypoint.sh` takes `prove`, `slice <markers> [concurrency]`, `test [cases]`, `seed [cases]` and
`dashboard`.

## The chain

Nine agents. Four producer/critic pairs and a verdict writer, called in a fixed order by
`Prove.prove()` — the order is Java, not a paragraph an agent can rewrite.

```
reproducer  → RED ──→ proof-critic ─┐   (reducible → reproducer, once)
                                    ↓
fixer       → GREEN → fix-critic ───┤   (over-fit | regression-risk → fixer, once)
                                    ↓
                      pr-maker  → pr-critic        (redo → pr-maker, once)
                      verdict                      (only where execution settled nothing)
                      estimator → estimator-critic (redo → estimator, once)
```

**The build is the arbiter.** A test that fails before the patch and passes after it is the whole
standard of proof. `Runner` has three outcomes, not two: a build that produced no test result is
never evidence, because in the RED phase a failing test is the goal and a compile error would
otherwise read as success.

**Producers may run their own tests; judges may not.** The rule is that a certification must not
manufacture the evidence it certifies — not that a producer should work blind. The RED and GREEN that
*decide* a marker are still the ones `Prove` runs between stages.

**Two write, seven judge**, and the split decides the tools. The reproducer gets `write_file` but not
`edit_file`, so it can never make its own test pass; the fixer gets `edit_file` but not `write_file`,
because a new file is not a patch. Everyone gets `grep` and `glob`: a model asking for a tool that
does not exist does not degrade, it throws and the prove ends.

**Silence has a direction.** An objection must be raised to bite, so an unreachable proof-critic
waives and the test stands. A certificate must be given to bite, so an unreachable fix-critic or
pr-critic blocks the pull request.

**Dispositions are computed where the builds settled them.** The verdict agent is asked only where
they settled nothing — a declined proof, or a test that passed before any patch.

## Running many

`slice <markers> [n]` proves every marker, at most `n` at a time. A pool, not a partition: whichever
prover is free takes the next marker, so the run ends when the work does rather than when the
unluckiest slice does. The claim is a `mkdir` — atomic, and it tells the loser it lost. Each prove
gets its own `git worktree`, thrown away afterwards.

It resumes: a marker already settled anywhere is skipped.

## The record

| file | what it is |
|---|---|
| `results/m/<marker>/trace.jsonl` | every prompt, reply, tool call and build, stamped, in full |
| `results/m/<marker>/settlements.jsonl` | one line per stage boundary; the last is the disposition |
| `results/feedback.jsonl` | a person's judgement of one answer, carrying its prompt and reply |
| `results/cases.jsonl` | model-test cases |

`Trace` is injected once and handed to every agent, so nothing prints or appends on its own. Tool
payloads are recorded at the executor, before the library's own truncation.

## The dashboard

`dashboard` serves the record. Server-sent events push when the counts move; a marker page appends
the new events rather than reloading, so nothing you have open closes.

Per marker: a tab for each agent showing its **final** answer with superseded attempts folded
beneath, the test it wrote, a semaphore for whether RED reproduced and GREEN held, what the machine
spent and what a person would have. Feedback is a text box on any answer.

## Model tests

`test [cases]` replays an agent against an input it has seen and asserts **what the chain does with
the reply** — `loopback:yes|no`, `number:N±T`, `verdict:<word>` — not the wording, which is where two
runs at temperature 0 legitimately differ. `seed` turns a trace into cases; they are only as good as
the run they came from, so seed from one you have read.

## What it does not do

- **Clone or choose a JDK** beyond the checkout the entrypoint makes.
- **Bound cost per marker** beyond a token cap and a tool ceiling.
- **Push a pull request.** `pr-maker` decides; nothing acts on it.

## Status

`examples/webgoat/markers.txt` is the queue: **356 markers**, 48 checker families, 241 in lesson code.
Of those, **282 are `src/main`** — exactly the set an earlier system proved, recorded in
`examples/webgoat/results-282.csv`. The other 74 are `src/it` and `src/test`, which that system
excluded and which have no prior result to compare against.

2,009 lines of Java across 14 files.
