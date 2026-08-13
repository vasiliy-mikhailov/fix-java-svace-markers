# 06. Tools, and who is allowed what

**Capability in this program is a fact of the tool map, never a sentence in a prompt.** A prompt is a
request; a tool map is what the model can actually reach. Every rule in this chapter is enforced by
which executor is present in a `Map<ToolSpecification, ToolExecutor>` at the moment a runtime is
constructed, and none of it is enforced by asking.

Vocabulary used below: a **marker** is one line `repo|file|line|checker` — the analyser's claim that
a defect sits at that file and line. A **prove** is one JVM proving one marker. **RED** is the build
of the test before any patch and **GREEN** the build of the same test after one; a marker is proved
only by a RED that fails and a GREEN that passes. A **claim** is the directory under
`results/claims/<id>` by which the pool marks a marker as taken. A **digest** is the computed
per-marker summary handed to the watchers.

---

## Where tools come from, and why they are scoped here

`Tools` is the only place a tool map is built. `Agents.runtime` is the only place a
`SubAgentRuntime` is constructed, and it takes the map explicitly:

```java
SubAgentRuntime runtime = new SubAgentRuntime(Prove.model(name, trace), prompt, tools,
        "agent:" + name, ToolInvocationLogMode.NONE, trace);
```

**Nothing in this program uses `DeepAgentConfig.additionalTools`.** The library's own javadoc gives
the assembly order as "built-in file tools (if enabled), then shared `additionalTools`, then
per-definition `extraTools`" — so a capability granted there is granted to whoever asks first,
including a judge running the build whose output it is meant to be reading. Tools are scoped per
agent, by name, in `Tools`.

`FileToolFactory.build(new WorkspaceFileOperations(root))` builds all four file tools. Each set below
keeps only what that agent's job needs:

| Built-in | JSON parameters | Kept by |
|---|---|---|
| `list_dir` | `path` | every set |
| `read_file` | `path` | every set |
| `write_file` | `path`, `content` | `writing` only |
| `edit_file` | `path`, `old_string`, `new_string`, `replace_all` | `patching` only |

Library-side facts a rebuilder inherits from `WorkspaceFileOperations`: the root is stored
`toAbsolutePath().normalize()`; a relative `path` resolves against it, an absolute one is used as
given, both are normalized and must still `startsWith(root)`; reads are refused above 512,000 bytes
with `File too large (>512KB); use a smaller path or split.`

**The built-in file tools never throw — every failure comes back as a string the agent reads.** A
path that escapes the workspace returns `Error: Path escapes workspace: <path>`, a missing file
`Not a file: <path>`, an `edit_file` whose `old_string` is absent
`Error: old_string not found in file (must match exactly, including whitespace).`, and one that
matches twice without `replace_all`
`Error: old_string is not unique; provide more context or use replace_all=true.` The same holds for
`grep` and `glob` below. This matters downstream: the throw path in `recorded(...)` is for a
`RuntimeException` out of the runtime, not for a file operation that went wrong.

The per-agent ceiling on sequential tool calls is `maxSequentialToolsInvocations(25)`, hardcoded
inside `SubAgentRuntime.run`. That ceiling is why the brief hands the flagged source over rather than
making the agent fetch it, and why `grep` exists at all — one call against a whole tree instead of a
read per candidate.

---

## The five sets

```java
static Map<ToolSpecification, ToolExecutor> reading(Path root, Trace trace, String agent)
static Map<ToolSpecification, ToolExecutor> asking(Path results, Trace trace, String agent)
static Map<ToolSpecification, ToolExecutor> writing(Path root, Runner runner, Trace trace, String agent)
static Map<ToolSpecification, ToolExecutor> patching(Path root, Runner runner, Trace trace, String agent)
static Map<ToolSpecification, ToolExecutor> supervising(Path results, Supervisor supervisor, Trace trace, String agent)
```

| Set | Contents | Rooted at |
|---|---|---|
| `reading` | `list_dir`, `read_file`, `grep`, `glob` | the checkout, or `results` for the watchers |
| `asking` | `list_dir`, `read_file`, `grep`, `glob`, **`list_markers`**, **`marker_record`** | `results` |
| `writing` | `list_dir`, `read_file`, **`write_file`**, `grep`, `glob`, **`run_test`** | the checkout |
| `patching` | `list_dir`, `read_file`, **`edit_file`**, `grep`, `glob`, **`run_test`** | the checkout |
| `supervising` | `list_dir`, `read_file`, `grep`, `glob`, **`restart_prove`**, **`postpone_prove`** | `results` |

**Three of the five can change something, and each changes a different thing.** `writing` creates a
file in the checkout, `patching` edits one, and `supervising` acts on a running prove — kills it,
deletes its results, releases its claim. **The other two change nothing at all.** `reading` and
`asking` are both entirely read-only, and `asking` differs from `reading` only by two more questions
it may ask about the record: the set that gained tools is not the set that gained reach.

The `agent` argument is the label the trace records the call under. It must be the same string the
runtime is built with, or a reader cannot tell which agent made which call.

---

## Who gets what

Fifteen agents are constructed in this program — `Agents.CHAIN`'s ten inside a prove, `Agents.WATCH`'s
four watching a run from outside one, `Agents.ASKED`'s one that speaks only when a person asks it
something. Eleven get `reading`, one gets `asking`, one gets `writing`, one gets `patching`, one gets
`supervising`.

| Agent | Set | Root |
|---|---|---|
| `reproducer` | `writing` | checkout |
| `proof-critic` | `reading` | checkout |
| `fixer` | `patching` | checkout |
| `fix-critic` | `reading` | checkout |
| `pr-maker` | `reading` | checkout |
| `pr-critic` | `reading` | checkout |
| `verdict` | `reading` | checkout |
| `verdict-critic` | `reading` | checkout |
| `estimator` | `reading` | checkout |
| `estimator-critic` | `reading` | checkout |
| `overwatch` | `reading` | `results` |
| `overwatch-critic` | `supervising` | `results` |
| `interpreter` | `reading` | `results` |
| `interpreter-critic` | `reading` | `results` |
| `chat` | `asking` | `results` |

**Exactly two agents can change a file, and the seven `-critic` judges are not among them.** A judge
that cannot write cannot edit the thing it is certifying; a critic that cannot run the build cannot
manufacture the evidence it is judging. That is the whole reason the split exists — not tidiness.

The watchers (`overwatch`, `overwatch-critic`, `interpreter`, `interpreter-critic`, `chat`) are
rooted at `results` rather than a checkout because **their evidence is traces, not source.** Both
processes that construct them hand `Agents` a `Runner` that cannot build anything — named in
`Overwatch.main`, written inline in `Chat.answer`, identical text either way:

```java
Runner nothingToBuild = (phase, test) -> new Runner.Result(true, false,
        "the supervisor does not build; it reads what the provers built");
```

`Runner.Result` is `(infra, passed, summary)`, so **the stub reports `infra = true` and
`passed = false`** — "the build never ran", never "the test passed". Getting that pair round the
wrong way would hand a watcher a runner that certifies everything.

`Runner.of` would refuse a tree with no build file, correctly (it throws
`no pom.xml and no build.gradle in <checkout> — nothing can run the test`); handing a watcher a
checkout instead would give it a build it has no business running. **A supervisor that can run tests
can manufacture the evidence it supervises.** The stub refuses in the same words, from the same
place, for the same reason — and it holds even though nothing in `reading` or `asking` can call a
runner. The stub is belt and braces on a set that has no `run_test` in it at all; keep both, because
the cheap half is the one that survives somebody adding a tool.

---

## Why the reproducer gets `write_file` but not `edit_file`

The reproducer needs `write_file` for the test. **It does not get `edit_file`: a reproducer that can
edit source can make its own test pass, which is the one thing the whole program exists to prevent.**

Two consequences downstream depend on that absence and will break silently if it is granted:

1. **A green RED is a certain fact, not a heuristic.** When the first RED build passes, the chain can
   state without qualification that the tree the build ran against IS the revision the marker was
   raised against — because the reproducer holds no `edit_file` and no fixer has run yet. The re-ask
   says so in those words: *"you cannot edit source and no patch has been applied"*. This mattered:
   across one run, **16 of the 33 markers that reached a build had their first RED pass, and 13 of
   them settled on it** — six `by-design`, seven `false-positive`, every one argued from a build that
   showed nothing.
2. **The written test's class name is read out of the `write_file` argument.** `JsonlTrace` captures
   the path from the library's tool-invocation callback and keeps it as the test to build:

   ```java
   boolean underTests = path.contains("src/test/") || path.contains("src/it/")
           || path.contains("src/integrationTest/");
   if (underTests && path.endsWith("Test.java")) { … }
   ```

   What is kept is the simple class name — `path.substring(path.lastIndexOf('/') + 1)` with
   `.java` cut — and only `write_file` calls update it, which is why the fixer's `edit_file` can
   never change which test gets built.

   Which test to run is a fact, not an inference: reading a class name out of the reproducer's prose
   picks whichever name came last, and a reply that explains itself mentions the harness it borrowed
   as readily as the test it wrote. A reproducer that could `edit_file` its way to a test would leave
   no such record, and the runner would be told no test was named.

   **All three source roots, not just `src/test/`.** A project puts integration tests under `src/it`,
   and a marker raised in one of those is answered by a test written beside it — which an earlier
   version rejected, so the runner was told no test had been named and reported infra for a file that
   was sitting on disk.

## Why the fixer gets `edit_file` but not `write_file`

The fixer needs `edit_file` for the source. **It does not get `write_file`: creating a new file is
not patching a defect, and a fixer that "fixes" a marker by writing a second test is then something a
judge has to catch in prose.** The tool map catches it instead — the call is not available.

---

## `run_test`, and why only producers have it

**No judge gets the runner, and both producers do.**

```java
ToolSpecification.builder()
    .name("run_test")
    .description("Compile and run one test class, and return what the build said. Use it "
            + "to check that what you wrote compiles and fails for the reason you intend. "
            + "This is for your own benefit; the run that decides the marker is made "
            + "elsewhere.")
    .parameters(JsonObjectSchema.builder()
            .addStringProperty("test", "the test class to run, e.g. ServersTest")
            .required("test")
            .build())
    .build();
```

The rule it protects is that **a certification must not manufacture the evidence it certifies** — not
that a producer should work blind. A reproducer that can run what it wrote finds its own compile
error in seconds instead of spending a round trip through the chain to be told; the same for a fixer
whose patch does not build.

**The invariant is unchanged by giving producers a runner: the RED and GREEN that COUNT are the ones
`Prove` runs between stages.** Three things keep that true and a rebuilder must keep all three:

- `run_test` calls `runner.run("check", …)`. The phase is `check`, not `red` or `green`.
- It calls the runner directly, **not** through `Prove.built(…)`, so it writes no `built` entry in
  the trace, adds nothing to the builds ledger, and cannot move `redOk` / `greenOk`. It is recorded
  only as a `tool` entry.
- The reproducer cannot edit source, so it cannot make its own test pass by changing the subject.

What a producer learns from its own run is feedback, not evidence.

### The reply format, and the word that means its opposite

```java
return (r.infra() ? "DID NOT RUN" : r.passed()
        ? "PASSED — WHICH IS A FAILURE HERE." + Prove.GREEN_RED
        : "FAILED") + "\n" + r.summary();
```

So the first token of the reply is one of exactly three:

| `Runner.Result` | First line the agent reads |
|---|---|
| `infra == true` | `DID NOT RUN` |
| `passed == true` | `PASSED — WHICH IS A FAILURE HERE.` followed by the whole of `Prove.GREEN_RED` |
| otherwise | `FAILED` |

then a newline and `r.summary()`, whose own first line is the runner's verdict prefixed with the
phase — `check: PASSED`, `check: FAILED`, `check: no test executed`, `check: the build did not
finish in time`. A call whose `test` argument is missing or blank reaches the runner as `""`, which
is an infra result (`check: no test class was named, so nothing ran`) and reads back as
`DID NOT RUN` — **never as a pass.**

**"PASSED" means its opposite here, and the word reached the agent bare.** A reproducer reading
`PASSED` after running the test it just wrote reads success; what happened is that its test is green
on the defect. Told at the moment it happens, the agent can still fix it — a round trip later, only
the verdict agent hears, and the verdict agent cannot rewrite a test. `Prove.GREEN_RED` is shared
between this tool and the chain's re-ask so the agent is told the same thing at the same moment
however it found out. The bare word still leads the line, so anything matching on `PASSED` keeps
working, with its meaning attached.

---

## `grep` and `glob`: search is given, not argued with

**Both go to every agent, judges included, in every set.** `only(...)` adds them unconditionally
after filtering the built-ins.

The reason is a failure direction, not a convenience: **a model asking for a tool that does not exist
does not fall back — the runtime treats an unknown tool name as a hallucination and throws, and the
prove ends.** Two markers were lost that way: one to `grep` before this program had one, one to
`glob` after a prompt sentence was written to talk a model out of wanting it. **Prompt text does not
win that argument; a twenty-line tool does.**

```
grep    pattern (required)  a literal string or Java regular expression
        glob    (optional)  filename filter, e.g. *.java
glob    pattern (required)  a path glob, e.g. **/*Test.java
```

Each description points at the other, because the two failures are "read twenty files to find a
definition" and "grep the tree for a filename":

> **grep** — Search file CONTENTS for a literal string or regular expression, optionally filtered by
> filename. Returns matching file:line pairs, and is cheaper than reading files to find a definition.
> To find files by NAME rather than content, use glob.

> **glob** — Find files by PATH pattern — e.g. \*\*/\*Test.java, src/it/\*\*, \*\*/pages/\*.java.
> Returns matching paths. Use grep to search file contents.

Exact behaviour, both bounded so that a pattern matching everything cannot return the repository:

| | `grep` | `glob` |
|---|---|---|
| Walks | `Files.walk(root)`, regular files only | same |
| Skips | any **absolute** path containing `/.git/` or `/target/` | same |
| Cap | 60 matches, then `… more matches suppressed; narrow the pattern` | 200 paths, then `… more suppressed; narrow the pattern` |
| Empty pattern | `no pattern given` | `no pattern given` |
| Bad pattern | invalid regex falls back to `Pattern.quote(pattern)` — a literal search | `not a glob: <message>` |
| No hits | `no matches` | `no files match <pattern>` |
| Walk failure | `search failed: <message>` | `glob failed: <message>` |
| Hit line | `<path relative to root>:<line>: <line text, stripped>` | `<path relative to root>` |

Neither ever returns more than its cap of hit lines. The wording of the two suppression notes
differs, and so does when they appear: `glob` appends its note the moment the 200th path is taken,
`grep` appends its note at the top of the next file it visits after the 60th match — so a search
whose sixtieth hit is in the last file it walks returns 60 lines and no note. Sixty lines is still
the ceiling either way.

`grep`'s optional `glob` filters on the **file name only**, converted to a regex by
`.replace(".", "\\.").replace("*", ".*")`. A file that cannot be read as text is not a match
(`IOException` and `UncheckedIOException` are both swallowed per file — the second one matters,
because `Files.readAllLines` on a binary file fails lazily).

`glob`'s pattern is prefixed with `**/` unless it already starts with `**` or `/`, then matched
against both the root-relative path and the absolute path.

---

## The asking set: two questions that were being reconstructed

**A search tool must never be the instrument a count is taken with.** Asked how many markers were in
the queue, an agent holding only the four file tools took the honest route — `grep` over every
`m/*/settlements.jsonl` — and answered *"at least 60 markers (the grep output was suppressed after
showing 60 matches, so the actual count is higher)"*. Truthful, careful about its own limits, and not
the number: **the queue held 356.** Counting three hundred files with a tool that returns matching
LINES is the wrong instrument, and no prompt makes it the right one.

`asking` is `reading` over `results` plus two tools that answer those questions directly, from what
the dashboard already computes for its own pages:

```java
static Map<ToolSpecification, ToolExecutor> asking(Path results, Trace trace, String agent) {
    Map<ToolSpecification, ToolExecutor> tools = only(results, Set.of("list_dir", "read_file"));
    tools.putAll(registry(results));
    return recorded(tools, trace, agent);
}
```

Both are read-only — they report the record and cannot touch it — so **the fence is unchanged and the
addition is additive.** Neither executor holds any policy: each reads its arguments and calls one
static method on `Registry`, which is where every rule below lives.

```java
tools.put(list, (request, memoryId) -> {
    String args = request.arguments();
    int limit = (int) num(field(args, "limit"), Registry.ROWS);
    return Registry.list(results, field(args, "state"), field(args, "checker"), limit);
});
tools.put(one, (request, memoryId) ->
        Registry.one(results, field(request.arguments(), "marker")));
```

### `list_markers` — the queue, and the state of every marker in it

No parameter is required; all three only narrow. `Registry.ROWS` is **60**.

| Parameter | Schema | Meaning |
|---|---|---|
| `state` | `addStringProperty` | only markers in this state. **`equalsIgnoreCase` — an exact match, not a prefix**: `state=verified` returns no rows, `state=verified/pr-ready` returns them |
| `checker` | `addStringProperty` | only markers whose checker **contains** this, case-insensitively |
| `limit` | `addIntegerProperty` | how many rows. Default `Registry.ROWS` |

The description handed to the model, verbatim — the last sentence is the load-bearing one, because
the tool exists to displace a habit the model already has:

> The queue and the state of every marker in it. Returns exact counts by state first — those are
> always complete — then one row per matching marker. USE THIS RATHER THAN grep OVER
> settlements.jsonl: grep returns matching lines and stops, which reports a partial count as a total.

The `state` parameter's own description is where the model learns what is nameable, so it enumerates
them: `proving, queued, by-design, false-positive, unprovable, reproduced, needs-review,
verified/pr-ready, verified/pr-rejected, infra. Omit for all.`

What it returns:

```
<n> marker(s) in the queue. By state: by-design=1 infra=1 queued=2 verified/pr-ready=1 
<m> match the filter (state=<state> checker~<checker>).

<id>  |  <state>  |  <checker>
…

... <k> more not shown (<rows> of <m>). The counts above are complete; narrow with `state` or
`checker`, or raise `limit`, rather than treating this list as the total.
```

Format, exactly: states are printed in `TreeMap` order (alphabetical), each as `<state>=<n>` followed
by a trailing space, the last one included. The filter line appears only when `state` or `checker`
was given, and its verb is literally `match` unless exactly one marker matched, in which case it is
`matches`; `state=` is present only when `state` was given and `checker~` only when `checker` was.
A blank line separates the counts from the rows. `<id>` is the lane directory name —
`Supervisor.slug` of the key, the same string the dashboard links and the pool creates `m/<id>` under
— and the three columns are separated by two spaces either side of a `|`. Rows are capped at
`Math.max(1, limit)`, so a `limit` of zero still returns one row rather than none.

**The counts are exact, complete, and computed before anything that can be capped.** `Registry.list`
walks `markers.txt` — the whole queue, one `repo|file|line|checker` per line, not the directories
under `m/` — and gives every marker a state:

| The marker's lane | State reported |
|---|---|
| no directory under `m/` | `queued` |
| a directory, but no settlement that is not `proving` | `proving` |
| a directory with settlements | the **last** state in `settlements.jsonl` that is not `proving` |

Three rules a rebuilder must keep, each of them a failure that has happened:

- **The total is the first thing printed and is never filtered.** A filter changes the rows and the
  match count; the by-state totals still describe the whole queue. That is what makes the two numbers
  answer different questions instead of quietly replacing one another.
- **A capped listing says it is capped, and by how much.** A listing that stops silently is read as
  the whole set — the same bug behind a better name, and the reason a new tool would otherwise have
  moved the failure rather than fixed it.
- **Markers that have never run are counted as `queued`.** Counting only the directories under `m/`
  is exactly how 82 got reported as the size of a 356-marker queue. And `infra` is reported as
  itself, never folded into a disposition: it means the prove THREW and the marker is owed another
  attempt.

Consequently the totals are over `markers.txt` alone. A directory under `m/` whose slug is in no
queue line contributes to nothing — not the total, not the by-state counts, not the rows.

An unreadable or absent `markers.txt` returns, instead of everything above,
`The queue is empty or unreadable: no markers.txt under the results directory.` — never `0
markers`, which is a confident wrong answer about the size of the run.

The chat prompt says the same thing a second time — it lists both tools with their signatures under
`TWO TOOLS ANSWER MOST QUESTIONS ASKED HERE. Reach for them first.`, and then
`DO NOT COUNT MARKERS WITH grep`, quoting the "at least 60" answer and the 356 it was wrong by.
**That is a nudge, not the fence.** The rule that makes the count right lives in `Registry`, which
computes it before any cap; the tool description and the prompt only get the model to ask.

### `marker_record` — one marker, from the summary rather than the trace

| Parameter | Schema | Meaning |
|---|---|---|
| `marker` | `addStringProperty`, **required** | the id as `list_markers` prints it, e.g. `Ping.java_34_FB.DM_DEFAULT_ENCODING`, or the full marker key |

> One marker: its key, checker, state, how long it took across how many attempts, why it settled the
> way it did, and the lane interpreter's summary of what happened. Use this before reading a trace —
> the trace is tens of thousands of characters and this is the part that answers most questions.

**It returns the LANE INTERPRETER'S summary, not the trace**, and names the trace path for what the
summary does not answer. The summary is already written and already judged by its critic; handing
over the trace instead would be handing over sixty thousand characters to answer "what happened to
this one", against a tool budget of 25 calls and a context that has to hold the digest as well.

`Registry.one` resolves the argument before it reads anything, in this order:

1. `Supervisor.slug(marker)`, if `m/<slug>` is a directory — so a lane that exists resolves without
   consulting the queue at all.
2. otherwise, walking `markers.txt` in queue order: a slug that `equalsIgnoreCase` the asked-for one
   wins immediately.
3. otherwise, every marker whose **id or full key** contains the asked-for text, case-insensitively.
   Exactly one hit resolves.

**An ambiguous fragment is REFUSED with the candidates rather than guessed** — an answer about the
wrong marker is indistinguishable from an answer about the right one. The four non-record replies:

| Case | Reply |
|---|---|
| `marker` blank or absent | ``Name a marker: its id (as `list_markers` prints it) or its full key.`` |
| nothing matches | ``No marker matches `<asked>`. Use list_markers to see the ids; they look like `Ping.java_34_FB.DM_DEFAULT_ENCODING`.`` |
| more than one matches | ``` `<asked>` matches several markers: <ids>. Name one exactly.``` — the first **six** hits, joined with `, ` |
| resolved, but no `m/<id>` directory | the `MARKER` / `key` / `checker` / `file` header below, then `state    queued — nothing has been proved for it yet; there is no record.` and nothing else |

The record itself:

```
MARKER <id>
key      <repo|file|line|checker>
checker  <the 4th |-field>
file     <the 2nd |-field>:<the 3rd |-field>
state    <the last non-proving settlement's state, or `proving`>
took     <n> minute(s) across <n> attempt(s)

WHY IT SETTLED SO (from settlements.jsonl):
<the last non-proving settlement's `because`>

SUMMARY (the lane interpreter's, already judged by its critic):
<summary.txt, or `(none — nothing has interpreted this lane yet)`>

The full record is `m/<id>/trace.jsonl` — every prompt, reply, tool call and build. Read it when the
summary does not answer what you were asked.
```

Four details that are contracts, not layout:

- **The `key`, `checker` and `file` lines appear only when the id is found in `markers.txt`.** A lane
  with no queue line still reports its state, cost, settlement and summary under a bare `MARKER <id>`
  header rather than failing.
- **The default state here is `proving`, not `queued`** — the opposite of `list_markers`. Both are
  right and the difference is the point: this branch is reached only when `m/<id>` exists, and a lane
  that exists is a prove that started.
- **`took` counts the marker, not the attempt.** `Pace.totalMinutes` adds the current lane's elapsed
  time to every archived attempt under `dead/<id>.<suffix>` — the suffix has to match
  `[a-z][a-z0-9-]*`, so `TAINTED_PTR` does not absorb `TAINTED_PTR.COOKIE`'s history — and
  `Pace.attempts` counts those archives plus the current lane. A restarted marker that reads as the
  length of its latest attempt is the measurement telling the supervisor to restart it again.
- **`WHY IT SETTLED SO` is omitted when the last non-`proving` settlement carries no `because`.** The
  `SUMMARY` block is never omitted; it says `(none — …)` instead, because an absent section reads as
  an oversight and a stated absence reads as a fact.

### One parsing consequence

Both executors read their arguments with `Tools.field`, which returns only *quoted* runs. `limit` is
declared with `addIntegerProperty`, so a model that emits `{"limit":10}` unquoted yields `""` — or,
when another key follows, that key's text — `num(...)` falls back, and the listing uses
`Registry.ROWS` rows. Only `{"limit":"10"}` moves it. `num` parses a `double` and the result is cast,
so `"10.9"` is 10 rows and any non-number is the default. **The counts are unaffected by all of
this**, which is the whole point of computing them before any cap.

### What holds it

`TheQuestionsItWasReconstructingTest` asserts the invariants above against a synthesised results
directory, one test each: `countIsExact` (a 301-marker queue listed with `limit=10` opens with
`301 marker(s) in the queue` and contains `291 more not shown`), `everyState` (`queued=2` for markers
with no lane, and `infra=1` reported as itself), `filters` (the totals stay whole under a filter),
`oneRecord` (the reply carries the summary and names `trace.jsonl`), `resolving`, `ambiguous`,
`neverRan`, `noQueue`, and `wired` for the tool map. **A listing that quietly stopped at sixty rows
would be the same bug behind a better name**, so the tests that matter here are the ones asserting
the cap announces itself — not the ones asserting the tool returns something.

---

## The supervising set: the only agent that may act

`overwatch-critic` is the only agent in this program that can act on the run rather than describe it,
and the only one whose subject is the other agents. `supervising` builds the same four read-only
tools over `results` that `reading` would — it calls `only(results, {list_dir, read_file})` itself
rather than delegating — and `putAll`s two levers on top.

```
restart_prove   marker  the full marker key, exactly as the record has it
                why     one sentence: what is wrong that a restart fixes
postpone_prove  marker  the full marker key, as the record has it
                why     one sentence: what makes this one an outlier
```

Both descriptions are given to the model verbatim, and both are load-bearing — **a tool whose
description undersells it gets used as though it were cheap.**

`restart_prove`:

> DESTRUCTIVE. Kill the running prove for one marker, delete its results, and release its claim so
> the pool proves it again from a clean worktree. Nothing of the attempt survives except its trace,
> kept aside for reading. A marker may be restarted at most 2 time(s), ever. Use it for a prove that
> is STUCK or that failed for a reason a fresh attempt would not hit — not for one whose answer you
> disagree with, which is a finding to report and not a process to cycle. It also lifts a
> postponement, so it is how you prove a postponed marker now rather than waiting for the end of the
> queue.

`postpone_prove`:

> Postpone one marker: kill its prove and free its slot. The queue moves on and this marker is proved
> again once everything else is done. For a prove that is WORKING but taking much longer than the
> others — not for one that is broken, which is what restart_prove is for. What comes back later is a
> fresh attempt, not a continuation: nothing persists a conversation with a model, so the work so far
> is lost and only the slot is saved. To prove it NOW instead of at the end, use restart_prove —
> there is no separate resume, because there is nothing to resume.

The `2` in the first description is interpolated from `Supervisor.LIMIT`. **The limit is enforced in
`Supervisor`, not in the tool and not in the prompt** — the description exists so the model does not
waste a turn discovering the rule. An agent told to be sparing is sparing until the run that it is
not, and a supervisor that can restart without bound is a loop that looks like progress: kill,
re-prove, find the same anomaly, kill again.

The two executors are one line each and hold no policy:

```java
supervisor.restart(Json.field(request.arguments(), "marker"), Json.field(request.arguments(), "why"));
supervisor.postpone(Json.field(request.arguments(), "marker"), Json.field(request.arguments(), "why"));
```

The `marker` string the model gives is passed through raw; `Supervisor.slug` turns it into the
directory name (`m/<id>`, `claims/<id>`) the pool used. Every refusal comes back from `Supervisor` as
text the agent reads, beginning `REFUSED:` and saying what to do instead — an agent told only "no"
tries a different phrasing. The refusals themselves are chapter 08's. What belongs here is that
**neither executor holds any of that policy**: the count is read from `restarts.jsonl`, a file that
outlives the process because the supervisor is restarted too, and a limit living in the tool map
would be a limit that dies with the JVM that built it.

### Why `chat` does not get these

`chat` has the same subject as `overwatch` and read-only tools only, and the difference is the
failure direction. **`overwatch-critic`'s silence REFUSES TO ACT**, in both of the ways an agent can
be silent: an EMPTY answer is written to `overwatch.jsonl` with `verdict` `unjudged`, so a finding
the critic did not judge still reaches the record instead of being suppressed; a THROW is caught by
the loop in `Overwatch.main`, which records `trace.failed` and costs that pass. **Neither absence can
produce an action** — a restart the critic never orders does not happen.

A question box holding the same tools fails the other way. *"What's happening with
LessonMenuService?"* is a question, and it must not be able to end as a killed prove because the
model read it as a request. So `chat` answers, names the button, and the person presses it.

Three assertions hold that line, and they are worth keeping all three:

- `AskingTheWatcherSomethingTest.readsOnly` — the sorted names of `Tools.reading` are exactly
  `glob, grep, list_dir, read_file`, and contain none of `restart_prove`, `postpone_prove`,
  `write_file`, `edit_file`, `run_build`.
- `TheQuestionsItWasReconstructingTest.wired` — the sorted names of `Tools.asking` are exactly
  `glob, grep, list_dir, list_markers, marker_record, read_file`, and contain none of
  `restart_prove`, `postpone_prove`, `write_file`, `edit_file`, `run_test`. **The introspection is
  additive; the fence is unchanged.**
- `AskingTheWatcherSomethingTest.wiredToTheReadingSet` — reads `Agents.java`, takes the text of the
  `Agent chat(` method, and requires it to contain `Tools.asking(` or `Tools.reading(` and **not**
  `Tools.supervising(`. **The fence is that line.** Naming the permitted sets explicitly rather than
  one of them is what let the wiring move from `reading` to `asking` without the guard either firing
  spuriously or going quiet.

The source-reading half exists because constructing the runtime would need a live model endpoint, and
a fence that can only be checked when an endpoint is up is not checked.

The chat prompt agrees with the map in its own words — `YOU CANNOT CHANGE ANYTHING` is asserted to
be in it, because an agent whose prompt claims a lever it does not hold will report having pulled it
and be believed.

A person's route to the same mechanism is the marker page's `POST /reprove` form, whose handler
builds a `Supervisor` over the results root (tracing to `dashboard-trace.jsonl`) and calls
`Supervisor.reprove` — the same kill, keep, release. **`Supervisor.reprove` has no limit check of its
own**, so a person is never refused for having pressed the button before: somebody who has read the
page and pressed a button is making a decision rather than looping.

It is one ledger, though, and that is where intent and behaviour part company. `reprove` ends with the
same `record(...)` call `restart` does — a line in `restarts.jsonl` carrying that marker's id, with
`why` prefixed `asked for by a person — ` — and `restart`'s guard is `restarts(id) >= LIMIT`, which
counts *every* line with that id whoever wrote it. **So a person's presses do consume
`overwatch-critic`'s two**: after two reprovals from the page, `restart_prove` on that marker is
refused. Only the refusal is one-sided, not the counting. `Dashboard`'s comment on the route and an
earlier draft of this chapter both said it was "not counted against that agent's two"; the code says
otherwise, and the code is what runs. A rebuilder who wants the documented behaviour has to give
`reprove` a separate log or a `by` field the guard filters on — not merely repeat the sentence.

There is no dashboard route for `postpone`.

---

## The credential fence

```java
private static final Set<String> SECRET = Set.of("model", "git-credentials");
```

Two files under the results root are not part of the record: the model settings (`model`, holding
`api_key=…`) and git's credential store (`git-credentials`, holding
`https://user:token@host`). The watchers are rooted at `/results` because that is where the record is
— every marker's trace, its settlements, the archived attempts — and those two files live in the same
directory, so they have always been inside the reach of any agent with `read_file`. Nothing asked for
them and nothing surfaced them, so it stayed theoretical.

**A chat makes it a question somebody can type.** "What is in the model settings" is one line, and
the answer would put the API key into `chat.jsonl` and onto a page — the same key the settings form
deliberately masks and reveals only on a button. **A mask that a second route walks around is not a
mask.**

### Two layers, because there are two ways to reach a file

**Name it, or find it.**

**Layer 1 — refusal by name, before the executor runs.** `read_file` names the file and is refused.
The check runs against the raw arguments JSON of *every* tool in the set:

```java
args.matches("(?s).*(^|[/\\\\\"'\\s])" + Pattern.quote(secret) + "([\"'\\s,}]|$).*")
```

which is, as a regex: the secret's name bounded on the left by start-of-string, `/`, `\`, `"`, `'` or
whitespace, and on the right by `"`, `'`, whitespace, `,`, `}` or end-of-string. **Matched as a whole
path segment, not as a substring, and case-sensitively.** `model` is an ordinary word: a guard
matching it as a substring would refuse every marker on a class called `Model` — a fence that has
eaten the job it was protecting. `m/ModelTest.java_9_Y/trace.jsonl` must still read.

The refusal is returned as the tool's result, verbatim:

```
REFUSED: `<name>` holds a credential, not part of the record. Everything else under this directory
is readable. If you were asked for the API key or a git token, say that it is deliberately
unreadable from here and that the settings page is where it is handled.
```

It is a sentence the agent can act on, not a dead end — the agent is told what to say to whoever
asked. Every spelling of the path is refused by the same rule: `model`, `./model`, `/results/model`,
`../results/model`, `./git-credentials`.

**Layer 2 — redaction of every result, whatever produced it.** `grep` reaches a file *without naming
it* and would return the matching LINE, so refusing by name does nothing there. The shapes those two
files hold are therefore stripped from every result of every tool in the set:

```java
result.replaceAll("(?i)(api[_-]?key\\s*[=:]\\s*)\\S+", "$1(hidden)")
      .replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@", "$1(hidden)@");
```

So `api_key=sk-…` becomes `api_key=(hidden)` and `https://user:token@github.com` becomes
`https://(hidden)@github.com`. A test that only asserts the key is absent would pass just as happily
when the search matched nothing at all, so `ACredentialIsNotPartOfTheRecordTest` also asserts the
result **contains `(hidden)`** — proof that the search really reached the line and the guard is what
stopped it. That shape of check — asserting the guard fired, not merely that the bad thing is absent
— is what two earlier bugs got through by omitting.

### At the tool, not in a prompt

**A prompt is a request; this has to be a fact.** It is applied inside `only(...)`, which every set
goes through, so it holds for the watcher, for the judges and for the chat alike rather than only for
the agent that prompted the question. The exposure is older than the chat; the chat only made it
something a person could ask for in one line.

The chat prompt *also* says the two files will refuse to open. That is not the fence — it is the
agent being told the truth so it does not report a refusal as a failure.

### Failure direction, and what the fence does not cover

- **It over-refuses rather than under-refuses.** The match is on the argument text, not on a resolved
  path, so `grep` for the literal pattern `model` is refused too. That direction is deliberate; the
  inverse — a resolved-path check that a second spelling walks around — is the failure being avoided.
- **The fence wraps only what `only(...)` returns.** `run_test`, `restart_prove`, `postpone_prove`,
  `list_markers` and `marker_record` are all added with `putAll` *after* `only` has run, and are
  outside both layers. None of them can return an arbitrary file. Every path the two `Registry` tools
  open is fixed: `markers.txt` at the root, `m/<id>/settlements.jsonl`, `m/<id>/summary.txt`, and —
  through `Pace` — the `at` timestamps of `m/<id>/trace.jsonl` and of `dead/<id>.<suffix>/trace.jsonl`;
  the only directories they list are `m/` and `dead/`. The one argument-derived component is `<id>`,
  which is `Supervisor.slug` output: the segment after the last `/`, everything outside
  `[A-Za-z0-9._-]` replaced with `_`, truncated to 80 characters. Neither secret file sits under `m/`
  or `dead/`, so neither is reachable through them. **If you add a tool that can reach an arbitrary
  path, add it inside `only` or wrap it in `withoutSecrets` yourself** — the wrapping is positional,
  and nothing fails loudly if you forget.
- The library's own root confinement is a separate, weaker guarantee: it stops a path escaping the
  workspace, and both secrets are *inside* the workspace. Do not rely on it for this.

---

## Every call is recorded, in full, at the executor

`recorded(...)` wraps every executor in every set, and is applied last — **outermost, with the
credential guard inside it**:

```
recorded( only(…)=withoutSecrets(built-ins + grep + glob)  +  run_test / restart_prove / postpone_prove )
```

That nesting is load-bearing in one direction. The trace sees what `withoutSecrets` returned — the
`REFUSED:` sentence, or a result with `(hidden)` already substituted — so **no tool RESULT can carry
a credential into the trace.** (The `arguments` field is recorded raw, which is safe only because
nothing readable from here can put a credential in one.) Wrap them the other way round and the fence
still holds for the agent while the API key is written, in full, into the record the watchers are
rooted at and the dashboard renders.

```java
try {
    String result = executor.execute(request, memoryId);
    trace.tool(agent, spec.name(), request.arguments(), result);
    return result;
} catch (RuntimeException e) {
    trace.tool(agent, spec.name(), request.arguments(),
            "threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
    throw e;
}
```

**A tool that throws is recorded as having thrown and then rethrown**: an agent must still see its
own failure, and a reader must still see that it happened.

The recording is here rather than at the library's listener because the library reports tool calls
through `DeepAgentFlowListener` with `truncateForLog` already applied — both arguments and result cut
to 8000 characters and stamped `... (truncated, total N chars)`. Fine for watching and useless for
reading. **The argument to `write_file` IS the test, and it is precisely what the cut takes.**
Recording at the executor catches the payload before anything shortens it.

The runtime is therefore constructed with `ToolInvocationLogMode.NONE`, and
`JsonlTrace.onToolInvocation` keeps nothing except the `write_file` path it needs for the test class
name. **`NONE` suppresses the library's own log lines; it does NOT suppress the listener callback** —
`ToolInvocationLogger.wrapAll` skips wrapping only when the mode is `NONE` *and* the listener is
null. A rebuilder who reads `NONE` as "no callback" gets a `testWritten` that is always empty, which
is not an error anywhere: the runner is simply told no test was named and reports infra for a file
sitting on disk.

One line per call, appended to the trace:

```json
{"at":"<epoch millis>","marker":"<subject>","kind":"tool","agent":"<agent name>","tool":"<tool name>","arguments":"<arguments JSON, in full>","result":"<result, in full>"}
```

`at`, `marker` and `kind` are written first, in that order, by `JsonlTrace.write`; the rest is the
event's own fields. `marker` is whatever the trace was constructed with — the full
`repo|file|line|checker` key inside a prove (`<results>/trace.jsonl`), and the literal `overwatch`,
`chat` or `dashboard` in the three processes that watch one (`overwatch-trace.jsonl`,
`chat-trace.jsonl`, `dashboard-trace.jsonl`, all in the results root).

A trace that cannot be written prints `trace: <message>` to stderr and the call proceeds. **A trace
that cannot be written must not end a prove that is otherwise fine.**

---

## Construction fails loudly when a name moves

```java
if (kept.size() != names.size() + 2) {
    throw new IllegalStateException(
            "expected " + names + " plus grep and glob but got " + kept.keySet());
}
```

The four built-in names belong to the library. **A rename upstream would silently strip a capability
and an agent would quietly stop being able to do its job** — the reproducer with no `write_file` is
an agent that always answers "I wrote the test" and never wrote one. The `+ 2` is `grep` and `glob`.
Failing at construction is the whole point: the alternative failure is a run that completes and
proves nothing.

---

## Argument parsing

Tools defined in this file read their arguments with hand-rolled scanners rather than a JSON parser.
`run_test`, `grep` and `glob` use the private `Tools.field(json, key)`; `restart_prove` and
`postpone_prove` use `Json.field(json, key)`. Both return the next quoted run and both return `""`
when they find nothing, so **a missing or malformed argument costs the field, not the call.** They
differ in two ways a rebuilder should not smooth over:

- `Tools.field` looks for `"key"` and then the first `:` after it. `Json.field` looks for `"key":`.
- `Json.field` also reads UNQUOTED values (booleans, ints) up to the next `,` or `}`, and unescapes
  `\n`, `\t`, `\r`, with any other escaped character passing through as itself. `Tools.field` does
  neither. The unquoted case is not cosmetic: scanning for the next quote past an unquoted value
  finds the FOLLOWING key's quote instead, which is why `red_verified` read as empty for every marker
  that had genuinely gone red, and the semaphore never lit.

Costing the field rather than the call is the same trade the trace readers make, and for the same
reason: refusing the whole thing takes down more than it saves.

---

## Summary of failure directions

| Thing | If it is absent, unreachable or got backwards | Why that direction |
|---|---|---|
| `grep` / `glob` in a set | the model hallucinates a tool name, the runtime throws, **the prove ends** | which is why they are in every set, unconditionally |
| a built-in whose name moved | `IllegalStateException` at construction, before any model call | a stripped capability must not be discoverable only from a run that proved nothing |
| the credential guard | present, it over-refuses harmless arguments and never reveals; removed, or a tool added outside `only(...)`, and the API key reaches the agent, `chat.jsonl` and the page | a mask a second route walks around is not a mask |
| `recorded` wrapped INSIDE the guard rather than outside | the agent sees `(hidden)` and the trace holds the key | the record is the thing the watchers read; it must not be the leak |
| `run_test` | a producer works blind and pays a round trip for a compile error | it is feedback; the deciding builds are `Prove`'s |
| the watchers' stub `Runner` | with `infra=false, passed=true` it would certify every build it never ran | a supervisor that can run tests can manufacture the evidence it supervises |
| `ToolInvocationLogMode.NONE` read as "no listener callback" | `testWritten` stays empty, the runner is told no test was named, **and it reports infra for a file sitting on disk** | nothing fails loudly; the prove just proves nothing |
| `list_markers` / `marker_record` | the chat counts with `grep` instead and reports **a floor as a total** — "at least 60" for a queue of 356 | a search tool is the wrong instrument for a count, and no prompt makes it the right one |
| `restart_prove` / `postpone_prove` | `overwatch-critic` cannot act; **nothing is killed** | an unreachable critic must not be able to authorise a kill |
| the same two on `chat` | never granted | a question must not be able to end as a killed prove |
| `restarts.jsonl` unreadable | counted as `LIMIT`, so the restart is **refused** | a log that cannot be read is not a licence |
