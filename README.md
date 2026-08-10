# Proving static-analysis markers

One marker in, one settlement out.

    mvn -q -DskipTests package -f agent
    QWEN_BASE_URL=… QWEN_API_KEY=… QWEN_MODEL=… \
      java -cp agent/target/agent-0.1.0-SNAPSHOT.jar:$(cat cp.txt) \
        tech.mikhailov.fsm.agent.Prove <checkout> 'repo|file|line|checker'

Six agents, each with its own tools; the build is the only arbiter. See [agent/SPEC.md](agent/SPEC.md).

## What was here before

A 14,220-line Java/Spring pipeline that settled 277 of 279 WebGoat markers in 10.8 hours. It was
removed on the `agent-only` branch; `git log main` has all of it. What replaced it is 491 lines,
because the model and its tools now do directly what that code did by hand: HTTP and streaming,
JSON, prompt assembly, reply parsing, and a fourteen-stage chain.

## What has NOT been replaced, and is simply gone

Say so plainly rather than discover it later:

- **The queue.** No claim, no lease, no single-flight, no requeue-on-infra. The old `SuspicionDao`
  held a marker with a 16-attempt CAS so two provers could not take the same one and a restart did
  not re-prove two hundred. This proves one marker when asked.
- **The dashboard.** `Settlement` writes rows in the `bugs` shape the old dashboard read, so wiring
  one back is reading a JSONL — but nothing reads it today.
- **Clone and JDK selection.** The agent assumes a checkout exists and that `mvn` works in it.
- **The 30,311 frozen differential cases.** They pinned the parsers and input builders, which no
  longer exist. They cannot be regenerated; `git log main` is the only copy.

## Status

The agent has completed **zero** end-to-end proves. The last smoke reached GREEN and stopped on a
fixer patch that did not compile — the reproducer gets the compiler's error back, the fixer does not.
The old pipeline's 471 recorded proves in `examples/results-282.csv` are the comparison to beat.
