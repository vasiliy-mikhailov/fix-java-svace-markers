# The specification, by chapter

A static analyser has produced a list of claims about a Java repository. Each claim — a **marker** —
is one line, `repo|file|line|checker`, asserting a defect at that file and that line. This program
decides them one at a time. A **prove** is one JVM against one marker: it clones the subject, hands
ten language-model agents a fixed brief in an order written in Java, and tries to make the build
contradict itself — a test that FAILS before a patch (**RED**) and PASSES after it (**GREEN**).
Where that succeeds the marker is settled by execution and no model is asked what it means; where
nothing could be executed an agent argues, and the record says plainly that nothing ran, so a reader
can tell the two apart afterwards. A shell pool runs several proves at once over a queue of a few
hundred markers, a supervisor pair watches the run and may restart or set aside a prove, an
interpreter pair turns each finished lane into plain English, and a single-class dashboard serves
the whole record out of one shared volume. There is no database: the files are the record.

Everything here is a contract. Where a rule looks over-elaborate, the paragraph under it names the
run in which the simpler version was wrong — most rules in this system exist because something went
wrong once, and a rebuilder who does not know which failure a rule prevents will simplify it back
into the bug.

---

## Reading order

| Chapter | Title | What it answers |
|---|---|---|
| [`01-purpose-and-proof.md`](01-purpose-and-proof.md) | Purpose, and the standard of proof | What a marker is, how its four fields are parsed and slugged, what RED and GREEN must demonstrate, the seven dispositions spelled exactly, how a judge's word becomes a state, and why `infra` is not one of them |
| [`02-the-chain.md`](02-the-chain.md) | The chain: ten agents in a fixed order | The five producer/critic pairs, the exact order `Prove.run()` executes with every exit state, all eight loopbacks and their one-re-ask bounds, and which absence waives and which withholds |
| [`03-the-build.md`](03-the-build.md) | The build is the arbiter | The three-outcome `Result`, the Maven and Gradle commands and the two different tests for "a test executed", who may run a build and who may not, JDK selection, and the 30-minute bound |
| [`04-the-brief.md`](04-the-brief.md) | What a prove hands an agent | The one block of text prefixed to every prompt: the checker note, the `src/it` warning, numbered source, sibling tests, and the inadmissibility block on the two argued paths |
| [`05-checker-notes.md`](05-checker-notes.md) | Checker notes | How a per-checker note is laid out, the exact sentences `Checkers` emits when the line does or does not hold the construct, what a good note must contain, and why a missing note is safe while a wrong one is invisible |
| [`06-tools-and-the-fence.md`](06-tools-and-the-fence.md) | Tools, and who is allowed what | The tool sets, exactly which of the fifteen agents holds each and at which root, why the reproducer writes and the fixer edits, and the two-layer credential fence |
| [`07-the-model.md`](07-the-model.md) | Talking to the model | How a prove reaches the endpoint: the streamed model, patience versus ceiling, the reasoning-field mismatch and its three sources in trust order, every tunable with its clamp, and where the API key lives |
| [`08-the-supervisor.md`](08-the-supervisor.md) | The supervisor | The run-level watcher pair: its counted digest, the `## Finding:` split, its two levers and their on-disk effects, the two-restart limit, and Pace's relative outlier rule |
| [`09-the-lane-watch.md`](09-the-lane-watch.md) | The lane-level watch | How one settled lane becomes plain English: which lanes are selected, the counted-and-quoted digest, the `SHORT:` split rule for `summary.txt`, and the two places the short line is read |
| [`10-the-chat.md`](10-the-chat.md) | Asking the supervisor | How a person's question about a run is answered by a read-only agent in the dashboard's process: the two log formats, the write order, the one-at-a-time flag, and the restarted-mid-answer case |
| [`11-the-pool.md`](11-the-pool.md) | The pool, the queue and claims | Every entrypoint mode, the slice loop step by step, the claim as an atomic `mkdir` with the lifetime the gate-repealed-a-gate incident bought, the three-try bound, the sweep and the postponed pass |
| [`12-the-record.md`](12-the-record.md) | The record on disk | The complete results volume: every file, who writes it, whether it is appended, overwritten, moved or deleted, the exact JSON shapes, the one escaper and the scanning reader that undoes it |
| [`13-settings-as-data.md`](13-settings-as-data.md) | Settings as data | Why every setting is a file read per prove rather than an environment variable, then each one exactly — model, prompts, workers, subject, JDK — and the POST dispatch that saves them |
| [`14-the-dashboard.md`](14-the-dashboard.md) | The dashboard | Every route and what it renders, the page skeleton and formats, the three live mechanisms, fold persistence, the escape-once rule, and the fact that the server authenticates nobody |
| [`15-deployment.md`](15-deployment.md) | The image and the deploy | How the image is built (deepagents patched at source, five JDKs, non-root), what persists on the volumes and what dies with the container, every environment variable, and the two-step host deploy |
| [`16-invariants-and-tests.md`](16-invariants-and-tests.md) | What the tests hold | The testing philosophy, the meta-rule that a check passing on both answers is not a check, all 33 JUnit classes and 2 shell scripts with the incident behind each, the pinned constants, and a table of asserted failure directions |

Chapters 01–05 are the standard of proof and what an agent is told. 06–07 are what an agent can
reach. 08–10 are the watchers. 11–15 are the machinery around a prove. 16 is what is nailed down.

---

## If you are rebuilding this

These are the ones that are silent when they are wrong. Each is stated in full in its chapter; each
was a real failure before it was a rule.

**A build has three outcomes, not two.** `infra` — the build produced no test result at all — is
never evidence, in either phase. Collapse it into `passed` and a RED compile error reads as success,
because in the RED phase a failing test IS the goal. The test for "a test executed" is per build
tool and is never the exit code: Maven's literal `Tests run:` in the output, Gradle's mtime under
`build/test-results/test` increasing. → [03](03-the-build.md), [01](01-purpose-and-proof.md)

**`infra` is not a disposition, and a marker whose prove ended in `infra` goes back in the queue.**
`settled` is a grep for exactly the seven dispositions and nothing else. It used to be "any state
that is not `proving`", which retired every marker whose prove threw. → [01](01-purpose-and-proof.md),
[11](11-the-pool.md)

**A claim lasts exactly as long as its prove.** Created immediately before the prove is forked,
removed on every exit path. When nothing removed it, the `mkdir claims/$id || continue` gate silently
repealed the `settled` gate three lines above it — both gates read correctly on their own, only their
order was wrong, so reading either one found nothing. → [11](11-the-pool.md)

**An objection must be RAISED to bite; a certificate must be GIVEN to bite.** So an absent objector
waives and the work stands (`proof-critic`, `pr-critic`, `verdict-critic`, `estimator-critic`), and
an absent certifier withholds and nothing is enforced (`fix-critic`, `pr-maker`). Getting one
backwards ships uncertified patches, or turns a stated verdict into no verdict at all. A judge's own
blank or unreadable reply **rejects** — silence certifies nothing. → [02](02-the-chain.md),
[16](16-invariants-and-tests.md)

**A blank reply is not a decline.** Treating one as a decline let 53 empty answers out of 133 pass
for judgements. The decline is the token `no test`, named verbatim in the prompt and matched as a
case-insensitive substring of the whole reply. And nothing is built until a file exists on disk.
→ [01](01-purpose-and-proof.md), [02](02-the-chain.md)

**The verdict is the word that is DECLARED, not any word that appears.** Scan lines for one that IS
an allowed word after stripping markup, last such line wins; only with nothing declared fall back to
the earliest mention anywhere. Searching the text for a rejection read `**reject**` as a `make` and
read a `fix-critic`'s "not over-fit" as a rejection. And **the state follows the argument, not the
branch that asked for it.** → [01](01-purpose-and-proof.md), [02](02-the-chain.md)

**A RED that passes has demonstrated nothing, and the agent told about it must be the one that can
rewrite the test.** 16 of the 33 markers that reached a build had their first RED pass and 13 of them
settled on it, every one argued from a build that showed nothing — because the fact was routed to the
verdict agent, which cannot write a test, and never to the reproducer, which can.
→ [03](03-the-build.md), [01](01-purpose-and-proof.md)

**Which test class is built comes from what the reproducer WROTE, not from what it said** — the last
`write_file` under `src/test/`, `src/it/` or `src/integrationTest/` ending in `Test.java` — and the
name is remembered for the whole prove, never per reply, so GREEN is guaranteed to run the class RED
ran. → [01](01-purpose-and-proof.md), [03](03-the-build.md)

**The marker's file field is truncated to a source root before it is resolved against a checkout,
and the settlement still records the raw field.** The analyser reports the path CI compiled; resolved
as-is it escapes the worktree and every marker in the report becomes an infrastructure failure.
→ [01](01-purpose-and-proof.md), [04](04-the-brief.md)

**Patience and ceiling measure different things and must stay two settings with two names.** Patience
bounds silence on the wire (4 minutes); the ceiling bounds elapsed answering time (240 minutes).
Collapsing them into one "timeout" killed eighty-six live proves and blamed a healthy endpoint in the
record for the caller's arithmetic. → [07](07-the-model.md), [13](13-settings-as-data.md)

**Every absence has a chosen direction, and it is not always "empty".** An unreadable prompt override
falls back to the built-in, never to an empty prompt. An unreadable `workers` file is 4, never 0. An
unreadable `restarts.jsonl` **REFUSES** the restart — the count may never read as zero. A tree with
no build file throws by name rather than guessing Maven. `markers.txt` missing reads as
`empty or unreadable`, never `0 markers`. The full table is in
[16](16-invariants-and-tests.md#failure-direction-asserted); the per-subsystem ones are at the end of
most chapters.

**The lane interpreter's silence WITHHOLDS.** If the interpreter-critic returns nothing, no
`summary.txt` is written and the table falls back to the verdict's first sentence. Writing the
producer's draft instead would publish an unreviewed account as the marker's summary.
→ [09](09-the-lane-watch.md)

**A run may not cite itself.** By the time the verdict agent reads the tree it holds this run's test
and this run's patch; thirteen settlements rested on citing them. `git status --porcelain` is the
line between ours and theirs, and its own failure direction is toward permitting — an empty
inadmissible list, never an invented one. → [01](01-purpose-and-proof.md), [04](04-the-brief.md)

**Capability is a fact of the tool map, never a sentence in a prompt**, and the credential fence has
two layers because there are two ways to reach a file: refusal by whole path segment when a tool
names it, and redaction of every result when a search reaches it without naming it.
→ [06](06-tools-and-the-fence.md)

**Set `JAVA_HOME` and the PATH together, or neither; blank must not blank them.** The JDK choice is
about the JVM Surefire forks, not about compiling — and `25` is stored as blank, meaning "leave the
environment exactly as it was". → [03](03-the-build.md), [15](15-deployment.md),
[13](13-settings-as-data.md)

**Every parallel prove writes its own files under `m/<id>/`, and nothing that is evidence is ever
rewritten.** Sharing one file looks safe — `O_APPEND` makes the offset update atomic — but a line
here can be sixty kilobytes of prompt, a write that large is not one syscall, and two workers
interleaving mid-line lose *both* records. The directory is named for the marker rather than the
worker, and a retry moves the old lane to `dead/` rather than appending to it.
→ [12](12-the-record.md), [11](11-the-pool.md)

---

## Where the agents read this

The image carries `spec/` at `/opt/agent/spec`, and `entrypoint.sh` copies it into the results volume
on **every** container start, before any mode runs:

```sh
if [ -d /opt/agent/spec ]; then
    rm -rf "$RESULTS/spec" 2>/dev/null || true
    mkdir -p "$RESULTS/spec" 2>/dev/null || true
    cp -R /opt/agent/spec/. "$RESULTS/spec/" 2>/dev/null || true
fi
```

Every agent's file tools are rooted at one directory and can open nothing outside it — the results
directory for the agents that read the record (`overwatch`, `overwatch-critic`, `chat`,
`interpreter`, `interpreter-critic`), the checkout for the agents inside a prove. `/opt/agent/spec`
is outside every one of those roots, so the prompts name `spec/` relative to the root the agent has,
and this file is named to the supervisor and to the chat agent as the index that says which chapter
answers what — to be read before answering any question about how something is *supposed* to work,
rather than reasoned out from the traces.

It is refreshed on every start rather than copied once, so a deploy updates it, and the old copy is
removed **first**, so a chapter deleted upstream does not linger as a chapter the watcher still
cites. Every step is `|| true`: a failed copy leaves the agents with no spec rather than a stale one.
That direction is chosen — no chapter is better than a chapter that no longer describes the code.
