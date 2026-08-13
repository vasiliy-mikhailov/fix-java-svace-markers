# 09. The lane-level watch

A **lane** is one marker's whole journey through a prove: the claim, every agent that answered,
every build that ran or failed to run, every loop back, and the **disposition** it ended on. A
**digest** is a record assembled by counting and quoting rather than by asking a model to summarise
— every line of it is something the program observed. A **summary** is plain English about one lane
for a person: one line for a table, and a short account underneath it.

**No agent inside a prove can see its own lane.** Each of the ten in the chain is handed one stage
and answers it; none of them sees the build that never ran, the critic that sent the fix-doer back, or
the judge that answered in one word. The settlement records only where the marker ended. So the lane
is a supervisor's subject — one level below the run-level watch (the `overwatch` pair), whose subject
is the pipeline rather than the marker.

**The failure this exists for.** The `what happened` column of the marker table used to show the
verdict agent's first sentence. That sentence is an argument addressed to the next agent, not an
account addressed to a reader: *"false-positive — the claim does not hold in this code"* names the
word and says nothing about whether anything was executed, how it was reached, or whether to believe
it. A reader who wanted that had to open the marker and read a trace.

---

## Where it runs, and when

The whole component is one package-private class,
`agent/src/main/java/tech/mikhailov/fsm/agent/Interpreter.java`:

```java
Interpreter(Path results, Agents agents, Trace trace)
void pass()                 // one cycle: select, digest, ask the pair, write
String lane(Path dir)       // the digest for one lane, package-visible so a test can read it
```

It runs **in the supervisor's process**, in the same loop as the run-level watch, in
`Overwatch.main`:

```java
Overwatch overwatch = new Overwatch(results, agents, trace);
Interpreter interpreter = new Interpreter(results, agents, trace);
while (true) {
    try {
        overwatch.pass(supervisor);
        interpreter.pass();
    } catch (RuntimeException failed) {
        trace.failed("overwatch", failed);
    }
    Thread.sleep(Duration.ofSeconds(every).toMillis());
}
```

`every` is `args[1]`, default **900 seconds**. Consequences a rebuilder must keep:

- **Same process, because it has the same subject** — the record, not the code — and because a prove
  must not wait on a paragraph written about itself. Nothing in a prove blocks on a summary.
- **The two passes share one try block.** A run-level pass that throws costs that cycle's summaries
  as well: a watcher that dies is worse than one that misses a pass.
- The supervisor's agents are constructed against a `Runner` that refuses to build:
  `"the supervisor does not build; it reads what the provers built"`. A supervisor that can run tests
  can manufacture the evidence it supervises.

**Only settled lanes are interpreted.** A lane is not a story until it has an ending, and
interpreting one mid-flight spends two model calls on a paragraph the next stage invalidates.

**A lane is summarised exactly once.** The presence of `summary.txt` is the whole idempotence check:
356 markers times two model calls is not a thing to repeat every fifteen minutes.

### Selecting the lanes

```java
/** How many lanes to interpret per pass, so a backlog does not starve the run-level watch. */
private static final int PER_PASS = 8;
```

`waiting()` lists `results/m`, keeps directories, **sorts them**, and keeps those that

1. have **no** `summary.txt`, and
2. have a non-blank `state()`.

`pass()` then takes the first `min(8, size)` and interprets each. Eight per pass so a backlog of
settled lanes does not starve the run-level watch — at the default 900-second sleep that is at most
32 summaries an hour.

The sort key is the path (`Path` natural order), not a timestamp. The code calls this "oldest
first"; a rebuilder should know it is alphabetical by marker directory name.

**If `results/m` is not a directory, or `Files.list` throws, `waiting()` returns empty** and the pass
does nothing. Nothing is interpreted and nothing is written — the same direction as every other
absence here.

### What counts as settled

```java
private String state(Path lane) {
    String found = "";
    for (String line : read(lane.resolve("settlements.jsonl"))) {
        String state = Json.field(line, "state");
        if (!state.isBlank() && !state.equals("proving") && !state.equals("infra")
                && !state.equals("queued")) {
            found = state;
        }
    }
    return found;
}
```

**The disposition is stated as what it is not.** `proving`, `infra` and `queued` mean a prove is
running, threw, or has not begun; every other word in that field is a disposition this program
decided. `Dashboard.settled(Path)` excludes exactly the same three words (it answers yes/no on the
first qualifying row; this one keeps looking so it can return *which* disposition, and the two never
disagree about settled-or-not).

**`infra` is the word that matters, and it is excluded on purpose.** `infra` is what the record gets
when a prove *throws*: `Trace.failed` writes a `failed` event and an `infra` settlement row. The
pool's own settled-gate once treated "any state but `proving`" as an answer, so a prove killed by the
tool ceiling retired its own marker and nothing ever revisited it. A lane whose only non-`proving`
row is `infra` is owed another attempt, not a paragraph about how nothing happened.

Elsewhere in the program the same question is asked with a different rule, and a rebuilder should not
unify them: the pool (`entrypoint.sh`) matches a **positive** list of the seven dispositions
(`false-positive|by-design|unprovable|reproduced|needs-review|verified/pr-ready|verified/pr-rejected`),
while `Registry.states()` and the run-level digest's per-marker state exclude only `proving`.

The **last** qualifying row wins, and `infra` rows are skipped rather than overriding — a marker that
settled and then had an infra row appended still reads as settled. Blank means still running, and the
lane is skipped every pass until it settles.

An unreadable or absent `settlements.jsonl` reads as no lines at all, so `state()` is blank and the
lane is not interpreted. **Every read failure in this class falls toward "no summary".**

---

## The digest: counting and quoting

`Interpreter.lane(Path dir)` reads `m/<id>/trace.jsonl` and renders it. **It never asks a model to
summarise the trace** — a summary of the evidence is not the evidence, and the point of the pair
below is that something read the record itself.

If `trace.jsonl` is missing or empty the digest is blank and the lane is skipped with no model call
(it will be re-listed every pass, which costs a `Files.list` and nothing else).

Exact shape:

```
THE MARKER: <checker> at <file> line <line>
WHERE IT ENDED: <disposition>

THE LANE, in order:

[<agent> answered]
<up to 1200 characters of the reply>

[a test file was written]

[BUILD red] the test FAILED

[BUILD green] the test PASSED

Every agent above saw only its own stage. You are the first to see all of it.
```

Built as:

```java
return "THE MARKER: " + (p.length > 3 ? p[3] + " at " + p[1] + " line " + p[2] : marker)
        + "\nWHERE IT ENDED: " + state(dir)
        + "\n\nTHE LANE, in order:\n" + b
        + "\nEvery agent above saw only its own stage. You are the first to see all of it.\n";
```

`marker` is the first non-empty `marker` field in the trace, split on `|` (the key is
`repo|file|line|checker`); with fewer than four parts the raw key is printed instead. `state(dir)` is
read again here, from `settlements.jsonl`, not carried over from `waiting()`.

### The four event renderings, and nothing else

| trace `kind` | condition (the `built` three are tested in this order) | emitted (each preceded by a newline) |
|---|---|---|
| `asked` | always | `[<agent> answered]\n<cut(reply)>\n` |
| `built` | 1st: `infra` is the string `"true"` | `[BUILD <phase>] did not run at all — nothing was learned\n` |
| `built` | 2nd: `passed` is the string `"true"` | `[BUILD <phase>] the test PASSED\n` |
| `built` | otherwise | `[BUILD <phase>] the test FAILED\n` |
| `tool` | `tool` equals `write_file` | `[a test file was written]\n` |
| `thought`, `progress`, `priced`, `settled`, `system`, `failed`, other tools | — | nothing |

**`infra` is tested before `passed`,** and reversing them is the silent catastrophe: a build that
never ran carries `passed:false`, so a `passed`-first branch would print `the test FAILED` for a
marker where nothing was executed.

`<phase>` is whatever the event's `phase` field says — `red` and `green` in practice — and is not
translated here. The em dashes in `did not run at all — nothing was learned` and in
`(nothing at all — it answered with silence)` are both U+2014.

`cut` bounds one reply:

```java
/** How much of an agent's answer the lane digest carries. Enough to characterise, not to quote. */
private static final int SAY = 1_200;

String flat = reply == null ? "" : reply.strip();
if (flat.isEmpty()) {
    return "(nothing at all — it answered with silence)";
}
return flat.length() <= SAY ? flat : flat.substring(0, SAY) + " …";
```

The truncation marker is a space followed by U+2026.

### Three renderings that must not be simplified

**A build that produced no test result is not a failing test.** `infra:true` means the build never
ran — a missing JDK, a broken worktree, a Maven that could not resolve. Rendering it as `FAILED`
would put "a test failed" in the summary of a marker where nothing was executed at all, and *nothing
was executed* is the single most important thing a reader of these summaries is owed.

**A test that passed before any patch is reported as passing, not as a reproduction.** `[BUILD red]
the test PASSED` is the shape of a characterisation test — `assertThrows` for the very exception the
defect throws passes on unfixed code. The strongest thing this pipeline can say is a test that
failed then passed; the digest has to let the reader tell that this is not that.

**An empty reply is a judgement, not an absent stage.** Rendering an empty reply as an empty section
makes a stage that answered with silence look like a stage that never ran. Those are different facts
and the interpreter branches on them, so the digest names the first one out loud.

---

## The pair, and the direction of its silence

```
interpreter → draft → interpreter-critic → the text that ships
```

Both get `Tools.reading(results, trace, <agent>)` — `list_dir`, `read_file`, `grep`, `glob`, rooted at
the results directory, with `results/model` and `results/git-credentials` refused by name and their
shapes redacted from every result. Neither can write through a tool, and neither gets the two
registry tools: **the only file either agent causes to exist is `summary.txt`, and Java writes it.**
(Their prompts and replies do land in `overwatch-trace.jsonl` — written by the trace, not by them.)

The producer's task is **the digest and nothing else** — no framing, no instruction appended to it.
If the digest is blank, `interpret()` returns before the producer is called. The critic's task is
exactly:

```
The summary written for this marker:

<draft>

---

The record it was written from:

<digest>
```

**The critic's text is what ships. The producer's draft appears nowhere.** It is in
`overwatch-trace.jsonl` as an `asked` event and on no page. A summary is the one thing on the page a
reader takes at face value, so the version shown is the one read against the record by something
that was not trying to write it.

**Its silence WITHHOLDS.** This is the fail-safe direction and getting it backwards is a silent
catastrophe:

```java
if (checked == null || checked.isBlank()) {
    trace.progress(lane.getFileName().toString(), "summary written but not checked; not shown");
    return;
}
```

No `summary.txt` is written, the table falls back to the verdict agent's own words, and the lane is
picked up again on a later pass. Writing the unchecked draft instead would put the one thing a reader
trusts on the page with nothing behind it. Compare the run-level pair, whose critic's silence
*permits* a finding to reach the record marked unjudged: an objection must be raised to bite, a
certificate must be given to bite, and this critic is certifying.

The producer's own silence is the same shape: a blank draft returns before the critic is called, and
nothing is written.

### One lane's failure is one lane's

Each `interpret(lane)` is wrapped in its own `try`; a `RuntimeException` is recorded with
`trace.failed(<lane directory name>, cause)` and the pass moves to the next lane. A missing summary
costs a row its plain English and nothing else, because the fallback is the record.

### What the pair writes to the record

Both agents' calls are traced through the **supervisor's** `JsonlTrace`, whose marker field is
`overwatch`. So:

- model calls land in `results/overwatch-trace.jsonl` as `asked` events with
  `agent: "interpreter"` / `"interpreter-critic"`, **not** in the lane's own `trace.jsonl`. Every row
  in that file carries `"marker":"overwatch"`, whatever lane provoked it;
- `trace.progress(lane, note)` writes a `progress` row to `overwatch-trace.jsonl` **and** a
  `"state":"proving"` row to `overwatch-settlements.jsonl` with
  `suspicion_key` = the lane's *directory name*;
- `trace.failed(lane, cause)` writes a `failed` row to `overwatch-trace.jsonl` **and** a
  `"state":"infra"` row to `overwatch-settlements.jsonl`, again keyed by the directory name.

That last one is why the file names matter. The dashboard aggregates a record file by reading the
named file at the results root **plus every `m/<id>/` file of the same name** — so the marker index
reads `results/settlements.jsonl` plus every `m/<id>/settlements.jsonl`, and never
`overwatch-settlements.jsonl`. An interpreter that failed on a lane would otherwise be writing
`infra` rows into the same stream the pool uses to decide whether a marker still needs proving.
**The interpreter cannot change a marker's state, and its own words are not in the record it reads**
— re-interpreting a lane can never feed on the previous summary.

At the start of a pass with work to do it writes, once, before the eight:

```
progress  interpreter  "<n> lane(s) without a summary"
```

`<n>` is the size of the whole backlog, not the number about to be interpreted, so the note is also
the queue depth.

---

## `summary.txt`: two lengths, one file

```
results/m/<marker-slug>/summary.txt
```

`<marker-slug>` is `Supervisor.slug(markerKey)`: the text after the last `/`, every character outside
`[A-Za-z0-9._-]` replaced with `_`, truncated to 80 characters — the same slug the pool's
`entrypoint.sh` uses for the directory.

**The file is the short line, a blank line, and the full account.** Nothing else is structured:

```
Settled by-design on an argument, with nothing executed.

No test was written, so nothing ran. The verdict agent argued the encoding is deliberate,
citing the lesson text.
```

**Two jobs, not two lengths of one thing.** The short line decides whether to open a row out of 356;
the full account answers what happened once you have. Truncating the second into the first gives a
table of sentences that all begin the same way and stop before the part that distinguishes them.

### The split happens where it is written, not where it is read

The critic is asked for a labelled shape:

```
SHORT: one sentence, under 140 characters, that would let a reader skimming a table
of 356 rows decide whether to open this one. What was concluded and on what strength
of evidence. Not the checker's name, not the state word on its own.

(a blank line, then two to four sentences: what was claimed, what was actually run and
what it showed, what was concluded and on what grounds, and anything a reader should
know before trusting it.)
```

`Interpreter.write` parses that label and consumes it. The dashboard splits on the first blank line
and **never sees `SHORT:`**, so a critic that forgets the shape cannot leak an instruction onto the
page: the whole answer becomes the long form and its first sentence becomes the short one.

### The rule, exactly

1. Strip the whole answer.
2. Scan the lines **from the last to the first**. For each, strip a leading run of `` [*_`#\s] ``
   (markdown emphasis, bullets, headings). If what remains starts with `SHORT:`, case-insensitively,
   at position 0:
   - the short form is the rest of that line with leading and trailing `` [*_`\s] `` removed;
   - the full form is every line **after** it, joined with `\n` and stripped;
   - stop scanning.
3. If the short form is still blank — no label at all, or a label with nothing after it — take it
   from the full form as it now stands: `int stop = full.indexOf(". ")`, and the short form is
   `full.substring(0, stop + 1)` when `stop > 0`, else the whole of `full`. Note that this keeps the
   period and drops the space, and that when no label was found `full` is still the whole answer.
4. If the full form is non-blank, split it on `\R\s*\R`, strip each paragraph, and drop any paragraph
   that `equalsIgnoreCase` the short form. Rejoin the survivors with `\n\n` and strip.
5. If the full form is now blank, it becomes the short form.
6. Write `shortForm + "\n\n" + full`.

**The last label, not the first.** A model asked for a shape sometimes delivers it twice — once as a
rehearsal and once for real. This was observed on the first summary the pair ever wrote: splitting on
the first occurrence put the second copy inside the long form, so the account opened by repeating the
line the reader had just read in the table. The last one is the one it meant. A consequence a
rebuilder must accept: **everything before the last `SHORT:` is discarded**, including an account
written above the label.

**And the line itself never appears twice.** Step 4 exists because a critic that obeys the shape and
then opens its account with the same sentence has written a paragraph the reader has already read.

**The fallback yields both, rather than neither.** A row with no summary at all falls silently back
to the verdict's own words, and nobody can tell whether the pair ran. One sentence is worse than a
good short line and far better than that.

The reader is the mirror image, and hard-codes the blank line:

```java
String all = Files.readString(file).strip();
int gap = all.indexOf("\n\n");
return gap < 0 ? new String[] {all, all}
        : new String[] {all.substring(0, gap).strip(), all.substring(gap + 2).strip()};
```

`[0]` is the table line; `[1]` is the account. Both are `""` when the file is absent or unreadable.
Because the boundary is the first blank line, **the short form must not contain one** — the labelled
path guarantees that (it is a single line); the no-label fallback does not, so an answer whose first
sentence spans a blank line would be cut at it.

---

## Where each is shown

### The short line — the marker table, `what happened` column

Read as `summary(results, key)[0]`. The cell has three states:

| condition | what the cell shows |
|---|---|
| no `verdict_text` and no progress/settled/failed note | `—` |
| no `verdict_text`, some note | that note, one line, cut to 150 characters |
| `verdict_text` present | a `<details>` fold whose visible `<summary>` is the short line, and whose body is the flagged source with context and the raw `verdict_text` |

When `summary.txt` is missing the fold's visible line falls back to `firstSentence(verdict_text)` —
the verdict's own words with the leading state word (`false-positive`, `by-design`, `unprovable`,
`verified/pr-ready`, `sound`, `redo`, …) stripped off. **That fallback is the reason the whole
component is optional**: the table is never empty because the interpreter is behind, an override
prompt is broken, or the endpoint is down.

Not shortened, and not replacing anything: the argument stays underneath in the fold, because a
summary is a *reading* of the record and the record is the record. Both halves go through `esc()`,
so a summary containing markup cannot inject into the page.

### The account — the marker's `summary` tab

`/marker?k=<key>` with no `a=` parameter. Read as `summary(results, key)[1]`, escaped, and rendered
as a `div.ev.asked` labelled `what happened` / `read against the record`. It sits **above the
artefacts** and below the claim, in the order a person asks the question:

1. `the claim` — the checker name, its note (the first paragraph of the classpath resource
   `/checkers/<CHECKER>.txt`, shipped from `agent/src/main/resources/checkers/`), file and line
2. `the code` — the flagged line with four lines either side (`AROUND = 4`)
3. **`what happened` — this account**
4. `what was run` — each build in words (*"Before any patch: the test failed. (This is what it was
   meant to do.)"*)
5. `the test` — the `write_file` content
6. `the fix` — the diff, recovered from the fix-verifier's prompt

If the account is blank the block is omitted entirely; nothing else on the tab changes. Everything
else on that tab is evidence, and evidence is what you read after you know what you are looking at.

The dashboard never names the interpreter, imports nothing from it and calls nothing in it. The only
coupling is the path and the blank line.

### Both halves at once — the chat agent's `marker_record` tool

`Registry.one(results, marker)` reads the whole of `m/<id>/summary.txt`, stripped, and prints it
under a heading that says where it came from:

```
SUMMARY (the lane interpreter's, already judged by its critic):
<the file, both parts, verbatim>
```

When the file is absent it prints `(none — nothing has interpreted this lane yet)` (U+2014), and the record it
returns still names `m/<id>/trace.jsonl` as the thing to read when the summary does not answer the
question. `Registry` is reachable only through `Tools.asking(...)`, which only the `chat` agent gets;
it is read-only, so a chat that reads a summary cannot act on one.

This is a third reader and it is why the file's blank-line convention is not purely a dashboard
concern: the chat agent sees the separator too, unparsed.

---

## Failure directions, all in one place

| what is absent or broken | what happens | why that direction |
|---|---|---|
| `results/m` absent, or `Files.list` throws | `waiting()` returns empty; the pass does nothing | a run that has proved nothing has nothing to interpret |
| the lane has not settled | not interpreted at all | a lane is not a story until it has an ending |
| `settlements.jsonl` missing or unreadable | `state()` blank, so the lane reads as unsettled and is skipped | an unreadable record must not be summarised as if it had been read |
| the lane's only non-`proving` rows are `infra` | `state()` blank, not interpreted | `infra` is a prove that threw and is owed another attempt, not an answer |
| `trace.jsonl` missing, empty or unreadable | digest blank, **no model call**, retried next pass | nothing to characterise, and silence is cheaper than a guess |
| the producer answers blank | nothing written, retried next pass | there is nothing for the critic to check |
| the **critic** answers blank | nothing written, retried next pass, `progress` note `summary written but not checked; not shown` | the shown text must have been read against the record by something that did not write it |
| either agent throws | that lane recorded via `trace.failed`, the other seven continue | one lane's failure is one lane's |
| `summary.txt` cannot be written | `progress` note `summary not written: <message>`, no exception | a page decoration must never cost the loop |
| `summary.txt` absent at display time | table shows `firstSentence(verdict_text)`; the account block is omitted | the record is the fallback, and it is demonstrably somebody's words |
| the whole supervisor is down | every row falls back to the verdict's first sentence | the pipeline's decisions do not depend on this component at all |

**Nothing downstream branches on a summary.** No settlement, no disposition, no restart and no pull
request reads `summary.txt`. It has exactly three readers — the two dashboard call sites
(`summary(results, key)[0]` in the table, `[1]` on the marker tab) and `Registry.one`, which puts it
in front of the chat agent and cannot act on it. That is what makes every failure above safe.

---

## Interaction with restart and postpone

`Supervisor.restart` and `Supervisor.reprove` both call `keep(...)`, which moves `m/<id>` to
`dead/<id>.restart-<n>` and takes `summary.txt` with it. The pool does the same thing under a
different name for a postponed marker: before proving it again after the queue, `entrypoint.sh`
moves `m/<id>` to `dead/<id>.before-postponing`, and its unattended sweep moves an unfinished lane to
`dead/<id>.attempt-<n>`. (`Supervisor.postpone` itself only kills the prove and deletes the claim;
the directory move happens when the marker comes back.)

In every case the fresh attempt starts with no summary and is interpreted again once it settles —
correctly, because it is a different lane with a different record. **A summary must never outlive the
attempt it describes**, and it does not, because it lives inside the attempt's own directory rather
than in a file keyed by marker.

The archived directories are under `dead/`, not `m/`, so `waiting()` never lists them: an archived
lane is not re-interpreted, and its old summary is never shown — `Dashboard.summary` and
`Registry.one` both look only in `m/<id>/`.

---

## Prompts and overrides

`interpreter` and `interpreter-critic` are two of the four in `Agents.WATCH`:

```java
static final java.util.List<String> WATCH = java.util.List.of(
        "overwatch", "overwatch-critic", "interpreter", "interpreter-critic");
```

Both appear on `/settings` under the heading *watching the run* — the settings page groups by
`Agents.CHAIN.contains(agent)` and everything else falls under that one heading — editable like every
other prompt. An override is `$PROMPTS/<agent>.txt` (`Prompts.WHERE`, default `/results/prompts`) and
**replaces the built-in entirely** — there is no merge. Unlike the chain's prompts, which take effect
on the next marker a prover starts, these take effect on the **next lane interpreted**: both runtimes
are constructed inside `interpret()`, per lane, and read the override at construction.

**An unreadable override is not an empty prompt.** `Prompts.saved` returns blank on any read failure,
which falls back to the built-in — the only safe direction, because the alternative is an agent
running with no instructions at all and answering something anyway.

What the two prompts must keep, whatever else is edited:

- The producer writes **two or three sentences** — no headings, no bullets, no markdown, no preamble
  — for a working developer who has never seen this pipeline and will not read a trace. In order and
  only where it applies: what the checker claimed in ordinary words; **whether anything was actually
  executed and what it showed**; what was concluded and on what grounds; and anything a reader would
  want before trusting it (a stage that never ran, a loop back, a judge that answered in one word, a
  test that passed when it was supposed to fail).
- It writes what the record shows rather than what would make a tidy story, and does not supply a
  reason the record does not give.
- This pipeline's vocabulary is not the reader's: not *"the RED build"* but *"a test written to fail
  on the unfixed code"*; not *"by-design"* but *"the code is deliberately like this because a lesson
  depends on it"*.
- The critic checks the draft against the record for four things — claiming execution that did not
  happen, more confidence than the record supports, omitting the thing a reader would most want to
  know, and jargon — and then **writes the summary itself, in two parts**, corrected where the draft
  was wrong and kept where it was right; not a critique and not a list of corrections, with nothing
  before or after the shape, and no mention of the draft, of itself, or of the instruction.

Both prompts are recorded in `Agents.BUILT_IN` at runtime construction, which is how `/settings` can
show what an override is replacing without an inference endpoint being up.
