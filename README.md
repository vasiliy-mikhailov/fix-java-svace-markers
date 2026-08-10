# Proving static-analysis markers

One marker in, one settlement out.

```
mvn -q -DskipTests package -f agent
QWEN_BASE_URL=… QWEN_API_KEY=… QWEN_MODEL=… \
  java -cp agent/target/agent-0.1.0-SNAPSHOT.jar:$(cat cp.txt) \
    tech.mikhailov.fsm.agent.Prove <checkout> 'repo|file|line|checker'
```

A marker claims a defect at a file and a line. The prove either demonstrates it or argues it away.

## How

Six agents, each with its own tools, called in a fixed order:

```
reproducer → RED → proof-critic → fixer → GREEN → fix-skeptic → pr-curator
```

**The build is the only arbiter.** A test that fails before the patch and passes after it is the
whole standard of proof; no agent may invoke the runner, because whether RED runs before the patch is
not a decision.

**Two agents write, four judge.** A writer's output is checked by the compiler and the build, so it
gets file access — the reproducer may create a test, the fixer may edit source, neither may do the
other's. A judge answers from a closed word set with read-only access, because a certification that
can edit its subject certifies nothing.

**Silence has a direction.** An objection must be raised to bite, so an unreachable critic waives and
the test stands. A certificate must be given to bite, so an unreachable skeptic or curator blocks the
pull request.

Settlements append to `results/settlements.jsonl`, one line per prove, in a dashboard's column shape.

See [agent/SPEC.md](agent/SPEC.md).

## What it does not do

- **Drain a queue.** No claim, no lease, no single-flight, no requeue. It proves one marker when asked;
  two copies pointed at the same marker will both prove it.
- **Clone, or choose a JDK.** It assumes a checkout exists and that the build tool works in it.
- **Show anything.** `Settlement` writes rows a dashboard can read; nothing reads them yet.

## Status

Zero completed end-to-end proves. The last run reached GREEN and stopped on a fixer patch that did
not compile — the reproducer gets the compiler's error back, the fixer does not.

`examples/results-282.csv` holds 471 recorded proves from a previous system over the same markers.
That is the comparison.
