# 09. The lane-level watch

A **lane** is one marker's whole journey through a prove: the claim, every agent that answered,
every build that ran or failed to run, every loop back, and the **disposition** it ended on. A
**digest** is a record assembled by counting and quoting rather than by asking a model to summarise
— every line of it is something the program observed. A **summary** is plain English about one lane
for a person: one line for a table, and a short account underneath it.

**No agent inside a prove can see its own lane.** Each of the fifteen in the chain is handed one stage
and answers it; none of them sees the build that never ran, the verifier that sent the fix-doer back,
or the judge that answered in one word. The settlement records only where the marker ended. So the
lane is a supervisor's subject — one level below the run-level watch (the `overwatch` triple), whose
subject is the pipeline rather than the marker.

**Its subject is the marker, not this pipeline.** The summary says whether the Svace finding is real,
for two readers at once: a developer deciding whether to take the fix into their code, and an
application-security reader deciding whether it was ever exploitable. What this pipeline did to the
marker appears only as the strength of the evidence — what was actually executed.

**The failure this exists for.** The `what happened` column of the marker table used to show the
argue-doer's first sentence. That sentence is an argument addressed to the next agent, not an
account addressed to a reader: *"false-positive — the claim does not hold in this code"* names the
word and says nothing about whether anything was executed, how it was reached, or whether to believe
it. A reader who wanted that had to open the marker and read a trace.

---

## Where it runs, and when

The whole component is one package-private class,
`agent/src/main/java/tech/mikhailov/fsm/agent/Interpreter.java`:

```java
Interpreter(Path results, Agents agents, Trace trace)
void pass()                 // one cycle: select, digest, ask the triple, write
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
- **Six agents in one process**, since both watches are triples now and both are built from the same
  `Agents` instance over the same results root.
- The supervisor's agents are constructed against a `Runner` that refuses to build:
  `"the supervisor does not build; it reads what the provers built"`. A supervisor that can run tests
  can manufacture the evidence it supervises.

**Only settled lanes are interpreted.** A lane is not a story until it has an ending, and
interpreting one mid-flight spends three model calls on a paragraph the next stage invalidates.

**A lane is summarised exactly once.** The presence of `summary.txt` is the whole idempotence check:
356 markers times three model calls is not a thing to repeat every fifteen minutes.

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
summarise the trace** — a summary of the evidence is not the evidence, and the point of the triple
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

## The triple, and the direction of its silence

```
digest → interpreter-planner → fact sheet → interpreter-doer → draft → interpreter-verifier
                                                                        → the text that ships
```

All three get `Tools.reading(results, trace, <agent>)` — `list_dir`, `read_file`, `grep`, `glob`,
rooted at the results directory, with `results/model` and `results/git-credentials` refused by name
and their shapes redacted from every result. None can write through a tool, and none gets the two
registry tools: **the only file any of them causes to exist is `summary.txt`, and Java writes it.**
(Their prompts and replies do land in `overwatch-trace.jsonl` — written by the trace, not by them.)

**The gathering is a stage of its own because both ways this goes wrong are failures of evidence,
not of prose**: describing a patch nobody wrote down, and reporting an argument as though something
had been executed. A writer working from the whole lane at once answers neither question out loud. A
writer working from a fact sheet has to.

`interpreter-planner`'s task is **the digest and nothing else** — no framing, no instruction appended
to it. If the digest is blank, `interpret()` returns before it is called. It answers a sheet of ASCII
labels, one per line: `VERDICT`, `EVIDENCE`, `CLAIM`, `VECTOR`, `CAUSE`, `PROOF`, `DIFF`, `WATCH`.
Three of those carry the weight:

- **`EVIDENCE`** is one of five phrases — *a test failed before the patch and passed after*, *a test
  failed and nothing was patched*, *a test passed before any patch*, *a build never ran*, *nothing was
  executed — argued only*. It is the strength of every line under it and it is the line a reader skips.
- **`DIFF`** is quoted from the record or it is `not in the record`. The patch is written down in
  exactly two places — `fix_diff` on the settlement row, and the fix-verifier's prompt in
  `trace.jsonl` under the heading WHAT IT ACTUALLY CHANGED — and the planner is told to look in both.
  *A green build proves a change existed, not what it was, and a diff reconstructed from the prose
  around it is worse than an absent one.*
- **`VECTOR`** is `not established by this record` unless the record established that the flagged line
  is reachable from untrusted input. Reachability is a claim about the world and the lane either paid
  for it or did not.

**`not in the record` is a finding and not a gap to fill in.** Everything after the planner may say
only what the sheet establishes, so a fact supplied to make the sheet look complete becomes a sentence
on the page where somebody decides whether to take a patch into their repository.

`interpreter-doer`'s task is the sheet, then the digest:

```
The facts established from this marker's record:

<sheet>

---

The record itself:

<digest>
```

`interpreter-verifier` gets all three — the draft, the sheet it was written from, and the record —
and is the only thing that reads the summary against the record:

```
The summary written for this marker:

<draft>

---

The facts it was written from:

<sheet>

---

The record itself:

<digest>
```

**The verifier's reply is what `write()` parses, and the doer's draft appears nowhere.** The draft is
in `overwatch-trace.jsonl` as an `asked` event and on no page. A summary is the one thing on the page
a reader takes at face value, so what is shown has been through the agent that was not trying to
write it.

**A seam a rebuilder must not paper over.** The verifier's prompt tells it not to rewrite the summary
— *the pair this replaced had the judge write the version that shipped, which is a judge marking its
own text* — and asks for `sound`, `redo` or `replan` on its own line with one paragraph under it, of
which `redo` faults the WRITING and `replan` faults the FACTS. That distinction is the one the chain's
triples are built on: *a writer told "this is wrong" rewrites the same claims in different words,
because rewriting is the only move it has.* But `Interpreter.interpret` has no loop back for either
word — it asks each agent once and hands the verifier's whole reply to `write()`, the way it handed
the critic's before. **Where the prompt and the code disagree the code is the fact**, and the fact is
that whatever the verifier says is what reaches `summary.txt`: a reply in the shape the prompt asks
for lands on the page through the no-label fallback below, verdict word first.

**Its silence WITHHOLDS.** This is the fail-safe direction and getting it backwards is a silent
catastrophe:

```java
if (checked == null || checked.isBlank()) {
    trace.progress(marker, "summary written but not checked; not shown");
    return;
}
```

No `summary.txt` is written, the table falls back to the settlement's own words, and the lane is
picked up again on a later pass. Writing the unchecked draft instead would put the one thing a reader
trusts on the page with nothing behind it. Compare the run-level triple, whose verifier's silence
*permits* a finding to reach the record marked unjudged: an objection must be raised to bite, a
certificate must be given to bite, and this verifier is certifying.

**The other two silences are the same shape and are noted differently.** A blank sheet ends the lane
with the progress note `interpreter-planner said nothing; lane left for the next pass` — a lane
skipped, not a summary guessed. A blank draft returns with no note at all. Nothing is written in
either case and the lane comes back on a later pass.

Each stage writes a progress note before it is asked: `interpreter-planner: establishing what the
record holds`, `interpreter-doer: writing it for a developer and for security`,
`interpreter-verifier: checking it against the record`.

### One lane's failure is one lane's

Each `interpret(lane)` is wrapped in its own `try`; a `RuntimeException` is recorded with
`trace.failed(<lane directory name>, cause)` and the pass moves to the next lane. A missing summary
costs a row its plain English and nothing else, because the fallback is the record.

### What the triple writes to the record

All three agents' calls are traced through the **supervisor's** `JsonlTrace`, whose marker field is
`overwatch`. So:

- model calls land in `results/overwatch-trace.jsonl` as `asked` events with
  `agent: "interpreter-planner"` / `"interpreter-doer"` / `"interpreter-verifier"`, **not** in the
  lane's own `trace.jsonl`. Every row in that file carries `"marker":"overwatch"`, whatever lane
  provoked it;
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
DELIBERATE — the encoding is chosen for the lesson, and nothing was executed to test it.

No test was written, so nothing ran. The settlement argued the encoding is deliberate,
citing the lesson text.
```

**Two jobs, not two lengths of one thing.** The short line decides whether to open a row out of 356;
the full account answers what happened once you have. Truncating the second into the first gives a
table of sentences that all begin the same way and stop before the part that distinguishes them.

### The split happens where it is written, not where it is read

The shape asked for is **JSON with exactly two keys**, nothing before or after the object:

```
{"short": "…", "full": "…"}
```

`short` is under 140 characters and **opens with the verdict word** — `CORRECT`, `FALSE POSITIVE`,
`DELIBERATE` or `UNDECIDED` — then a space, an em dash, a space, then one sentence saying what the
verdict rests on. `full` is four to seven sentences, rendered by the page as ONE PARAGRAPH: every line
break inside it collapses, so the structure lives in the order of the sentences and not in bullets.

**A JSON key is not prose and does not get translated.** The shape used to be a `SHORT:` line, which
works while the prompt is in English and fails silently the moment it is not: a Russian prompt
produced `КРАТКОЕ ИЗЛОЖЕНИЕ:`, no `SHORT:` was found, and the fallback made the whole first sentence —
label and all — the line in the table, leaving it duplicated in the account below. Both halves wrong,
nothing reported.

**NEVER A COLON AFTER THE VERDICT WORD**, and that is not a style rule: the no-label fallback below
strips a short colon-terminated opener as a label, so `CORRECT: …` would lose the verdict word on
exactly the path where parsing has already failed. The em dash survives it.

The dashboard splits on the first blank line and **never sees either shape**, so an agent that forgets
it cannot leak an instruction onto the page: the whole answer becomes the long form and its first
sentence becomes the short one.

### The rule, exactly

1. Strip the whole answer.
2. **JSON first.** Take everything from the first `{` to the last `}`; if there is no such span, skip
   to 3. Read `short` and `full` out of it with `Json.field` — a scan, tolerant of a fence, a preamble
   or a sentence afterwards, and strict about the two keys. If both are blank, skip to 3. Otherwise
   **one of the two is enough to proceed and the other is derived rather than left empty**: a blank
   `full` becomes the short form (*a summary with no account reads as a marker nobody looked at, which
   is not what happened*), and a blank `short` becomes the full form up to and including its first
   `". "` period.
3. If the short form is still blank, scan the lines **from the last to the first**. For each, strip a
   leading run of `` [*_`#\s] `` (markdown emphasis, bullets, headings). If what remains starts with
   `SHORT:`, case-insensitively, at position 0:
   - the short form is the rest of that line with leading and trailing `` [*_`\s] `` removed;
   - the full form is every line **after** it, joined with `\n` and stripped;
   - stop scanning.
4. If the short form is *still* blank — no JSON, no label, or a label with nothing after it — take it
   from the full form as it now stands: `int stop = full.indexOf(". ")`, and the short form is
   `full.substring(0, stop + 1)` when `stop > 0`, else the whole of `full`. Note that this keeps the
   period and drops the space, and that when nothing was parsed `full` is still the whole answer.
   Then, **on this path only**, a leading label in any script comes off the front:
   `replaceFirst("^\\s*[^.!?:\\n\\r]{0,40}:\\s+", "")` — short, colon-terminated, no sentence
   punctuation before it. It costs a legitimate opener like `Note: …` its first word, which is a
   smaller harm than a heading in a column of three hundred rows.
5. If the full form is non-blank, split it on `\R\s*\R`, strip each paragraph, and drop any paragraph
   that `equalsIgnoreCase` the short form. Rejoin the survivors with `\n\n` and strip.
6. If the full form is now blank, it becomes the short form.
7. Write `shortForm + "\n\n" + full`.

**The label reader stays, because prompts already written should not break.** An override at
`$PROMPTS/interpreter-verifier.txt` asking for the old shape still produces a correctly split file.

**The last label, not the first.** A model asked for a shape sometimes delivers it twice — once as a
rehearsal and once for real. This was observed on the first summary this ever wrote: splitting on
the first occurrence put the second copy inside the long form, so the account opened by repeating the
line the reader had just read in the table. The last one is the one it meant. A consequence a
rebuilder must accept: **everything before the last `SHORT:` is discarded**, including an account
written above the label.

**And the line itself never appears twice.** Step 5 exists because an agent that obeys the shape and
then opens its account with the same sentence has written a paragraph the reader has already read.

**The fallback yields both, rather than neither.** A row with no summary at all falls silently back
to the settlement's own words, and nobody can tell whether the triple ran. One sentence is worse than
a good short line and far better than that.

The reader is the mirror image, and hard-codes the blank line:

```java
String all = Files.readString(file).strip();
int gap = all.indexOf("\n\n");
return gap < 0 ? new String[] {all, all}
        : new String[] {all.substring(0, gap).strip(), all.substring(gap + 2).strip()};
```

`[0]` is the table line; `[1]` is the account. Both are `""` when the file is absent or unreadable.
Because the boundary is the first blank line, **the short form must not contain one** — the labelled
path guarantees that (it is a single line) and the JSON path asks for one sentence under 140
characters; the no-label fallback guarantees nothing, so an answer whose first sentence spans a blank
line would be cut at it.

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
| the **planner** answers blank | nothing written, retried next pass, `progress` note `interpreter-planner said nothing; lane left for the next pass` | a lane skipped, not a summary guessed from a sheet nobody established |
| the **doer** answers blank | nothing written, retried next pass, no note | there is nothing for the verifier to check |
| the **verifier** answers blank | nothing written, retried next pass, `progress` note `summary written but not checked; not shown` | the shown text must have been read against the record by something that did not write it |
| any of the three throws | that lane recorded via `trace.failed`, the other seven continue | one lane's failure is one lane's |
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

`interpreter-planner`, `interpreter-doer` and `interpreter-verifier` are three of the six in
`Agents.WATCH`:

```java
static final java.util.List<String> WATCH = java.util.List.of(
        "overwatch-planner", "overwatch-doer", "overwatch-verifier",
        "interpreter-planner", "interpreter-doer", "interpreter-verifier");
```

All six appear on `/settings` under the heading *watching the run* — the settings page groups by
`Agents.CHAIN.contains(agent)` and everything else falls under that one heading — editable like every
other prompt, and grouped there as the two triples they are. An override is `$PROMPTS/<agent>.txt`
(`Prompts.WHERE`, default `/results/prompts`) and **replaces the built-in entirely** — there is no
merge. Unlike the chain's prompts, which take effect on the next marker a prover starts, these take
effect on the **next lane interpreted**: all three runtimes are constructed inside `interpret()`, per
lane, and read the override at construction.

**An unreadable override is not an empty prompt.** `Prompts.saved` returns blank on any read failure,
which falls back to the built-in — the only safe direction, because the alternative is an agent
running with no instructions at all and answering something anyway.

What the three prompts must keep, whatever else is edited:

- **The planner writes no summary at all.** It answers the fact sheet, two sentences per line at most,
  and it is the only one of the three that goes and reads `trace.jsonl` and `settlements.jsonl` in
  full — the lane digest it is given is abridged at 1200 characters a reply and **does not carry the
  patch at all**. It has no source tree, so it may not describe a line it has not seen quoted.
- **The doer writes for two readers at once** — a developer deciding whether to take the fix, and an
  application-security reader deciding whether this was ever exploitable — from the sheet, in the
  JSON shape above. Anything on neither the sheet nor the lane is NOT IN THE RECORD. Where the sheet
  says the diff is not in the record it says a change was made and the record does not hold it:
  **never describe a patch nobody wrote down.** A false positive says *why it cannot happen here* —
  the guard, the validation, the branch nothing reaches — because a false positive without that
  second half has told a security reader to trust a word.
- **Do not credit this pipeline with evidence it does not have.** Where nothing was executed, that IS
  the summary — *settled on an argument, with nothing run* — said plainly rather than the argument
  repeated as though it were a finding. Where a test passed before any patch, say so: it documented
  the behaviour, it did not observe a defect.
- This pipeline's vocabulary is not the reader's: not *"the RED build"* but *"a test written to fail
  on the unfixed code"*; not *"by-design"* but *"the code is deliberately like this"*.
- **The verifier judges and does not rewrite.** It checks the verdict word against what actually ran,
  the described patch against `fix_diff` and the fix-verifier's prompt, a reachability claim against
  the record, what was left out, a false positive that only says so, and the shape. Its two loop-back
  words are `redo` for the writing and `replan` for the facts, and the difference matters more than it
  looks: *a writer told "this is wrong" rewrites the same claims in different words, because rewriting
  is the only move it has.* A complaint it cannot make concrete is not one anybody can act on — if the
  only fault is that it would have written it differently, the answer is `sound`.

All three prompts are recorded in `Agents.BUILT_IN` at runtime construction, which is how `/settings`
can show what an override is replacing without an inference endpoint being up.
