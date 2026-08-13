# 08. The supervisor

Every agent inside a **prove** — one JVM, one **marker**, one settlement — is handed one marker and
cannot know that the answer it is about to give is the fortieth identical one. That is the right
scope for proving a defect and the wrong scope for noticing that the pipeline has developed a habit:
a critic answering `sound` in one word thirty times, a reproducer whose tests keep passing before any
patch, a checker family that always settles the same way whatever the code says. Each of those was
found by a person reading a finished run, which is the expensive way and the late way.

The supervisor is the pair that watches the run instead of a marker: `overwatch` produces findings,
`overwatch-critic` judges them and is the only agent in the program that may act on the run rather
than describe it.

```
digest → overwatch → finding(s) → overwatch-critic → holds | refuted | unjudged
                                                     (and may restart_prove / postpone_prove)
```

**It is its own process, not a stage in a prove.** `entrypoint.sh overwatch [seconds]` runs
`tech.mikhailov.fsm.agent.Overwatch`; `entrypoint.sh serve` runs it in the background with the
dashboard in the foreground. An agent that could see the whole run from inside a prove would be an
agent that could rewrite the order it runs in.

```
overwatch <results> [seconds between passes]     # defaults: /results, 900
```

Its own trace is `<results>/overwatch-trace.jsonl` and `<results>/overwatch-settlements.jsonl`, both
written with `overwatch` in the `marker` field. Under `serve` its stdout and stderr are appended to
`<results>/overwatch.log`, which is not part of the record.

**A watcher that dies is worse than one that misses a pass.** The loop body — one `Overwatch.pass`
then one `Interpreter.pass` (chapter on the lane-level watch) — is wrapped so that a `RuntimeException`
is recorded with `trace.failed("overwatch", …)` and costs that pass only. The run it is watching takes
hours; a model call that fails must not take the loop with it. The asymmetry in `serve` is the same
rule at container level: the supervisor is backgrounded so its death does not take the record with it,
and the dashboard is `exec`'d in the foreground so its death ends the container and the restart policy
brings both back.

### One pass, exactly

`Overwatch.pass(supervisor)` does this and nothing else, writing a progress event at every step:

1. build the digest. Blank → progress `nothing has run yet`, and the pass ends.
2. progress `reading the run`, then ask the watcher, with the digest as the whole of the task:
   ```
   Here is the run as it stands. Report what is going wrong with the PIPELINE.

   <digest>
   ```
3. a null or blank reply → progress `the watcher had nothing to say`, and the pass ends. **A silent
   watcher records nothing**; only the critic's silence is recorded as a verdict.
4. split the reply into findings, progress `<n> finding(s) to judge`.
5. for each finding, in order, ask `overwatch-critic` and write the line.

**Each finding gets a freshly constructed critic runtime**, because `Agents.overwatchCritic` is called
inside the loop and a runtime holds its own conversation. Two findings never share memory, so the
second is not judged in the light of the first.

---

## What the pair may touch

**Neither agent has a checkout and neither can build anything.** Their file tools are rooted at the
results directory, because their evidence is traces and not source. The runner handed to `Agents` in
the supervisor's process refuses every build in the same words, from the same place:

```java
Runner nothingToBuild = (phase, test) -> new Runner.Result(true, false,
        "the supervisor does not build; it reads what the provers built");
```

`Runner.of` would refuse a tree with no build file, correctly — and handing the supervisor a checkout
instead would give a watcher a build it has no business running. **A supervisor that can run tests can
manufacture the evidence it supervises.**

| Agent | Tools | May act |
|---|---|---|
| `overwatch` | `list_dir`, `read_file`, `grep`, `glob`, rooted at `<results>` | no |
| `overwatch-critic` | the same, plus `restart_prove` and `postpone_prove` | yes |

`Agents.overwatch(Path results, Supervisor supervisor)` accepts a supervisor and **does not wire it
into any tool** — it builds `Tools.reading(...)`. Only `Agents.overwatchCritic` calls
`Tools.supervising(...)`, which is `list_dir` + `read_file` + `grep` + `glob` + the two levers. The
producer reports; the critic acts. `chat` (chapter on asking the watcher something) has the same
subject, the same `digest()` and the same read-only tools, and holds no levers at all, deliberately:
a question must not be able to end as a killed prove because a model read it as a request.

`<results>/model` and `<results>/git-credentials` sit in the same tree and are refused by name and
redacted out of every tool result, for every agent — see the tools chapter.

**Silence is fail-safe in both directions here, and they are different directions.**

- A finding the critic never judges still reaches `overwatch.jsonl` with `"verdict":"unjudged"`, so an
  unreachable critic **cannot suppress a warning**.
- A restart the critic never orders does not happen, so an unreachable critic **cannot kill anything**
  either.

Getting either backwards is silent: the first would let a dead endpoint hide a pattern, the second
would let one kill proves.

---

## The digest: counted, not summarised

**Every number in the digest is something this program observed and can be checked against the file it
came from.** Three hundred traces do not fit in a prompt, and asking a model to summarise them would be
asking the thing under watch to summarise the evidence about itself. So the digest counts: builds,
answers, empty replies, the length of each judge's last word, whether a test was written, how long
since anything happened. **The digest says where to look and is not itself the evidence** — the watcher
opens the traces it wants with its own tools.

`Overwatch.digest()` reads `<results>/m/` — one directory per marker, named by the slug — in sorted
path order, and returns `""` both when `m/` is not a directory and when listing it throws. A blank
digest ends the pass with `nothing has run yet`: a watcher woken before the run starts must say nothing
rather than report a clean run, and a watcher whose results tree has gone must not either.

### The header

```
THE RUN: <started> of <queued> queued marker(s) started (<queued-started> not yet begun). \
<pace sentence>Settlements so far: <state>=<n> <state>=<n> …

One line per marker. Fields: id | state | builds(phase:outcome) | agent=answers/chars-of-last \
(empty marked !, tool calls marked t) | test? | idle=minutes since its last event.
The traces are under <results>/m/<id>/trace.jsonl — read the ones that look wrong.

```

`\` marks a wrap for this page: the header is one long line, a blank line, two long lines, a blank
line, then the rows.

**Started is not queued, and a number with no denominator beside it gets read as the total.** The
digest said only the first. Asked how many markers there were, the watcher answered "82" and cited
this line correctly — 82 being the directories under `m/`, and the queue being 356. `queued` counts
non-blank lines of `<results>/markers.txt`; if that file cannot be read the count is `0` and the
header falls back to `<started> marker(s) started. `, saying nothing about the total rather than
guessing one.

The pace sentence is one of two:

- `A marker that settles takes about <typical> minutes. Anything running far past that is holding a
  quarter of the pool while the queue waits — postpone_prove sets it aside and the pool proves it once
  everything else is done. `
- `Too few markers have settled to say what one usually takes. ` (when `Pace.typical` is 0)

`Settlements so far:` tallies every marker's state, including the ones past the naming limit.

### One row per marker

The first **400** directories get a row (`NAMED`); after that they are counted in the tally only.

```
<id> | <state> | <builds|no builds> | <answers|no answers> | <test written|NO TEST> | idle=<m>m
```

with, appended in this order when they apply:

| Suffix | When |
|---|---|
| `  <-- QUIET, still claimed` | a claim exists **and** the state is not settled **and** idle > 20 minutes |
| `  <-- DIED: <cause>` | the trace holds a `failed` event; `<cause>` is its **first line only** |
| `\n      <-- <pace note>` | `Pace.outlier` has something to say (below) |

Every field, and where it comes from — all of it read from `<results>/m/<id>/trace.jsonl` and
`<results>/m/<id>/settlements.jsonl`:

| Field | Built from | Notes |
|---|---|---|
| `id` | the directory name | the pool's slug of the marker key |
| `state` | last non-blank, non-`proving` `"state"` in `settlements.jsonl`; `proving` if none | starts as `proving`, becomes `unreadable` if `trace.jsonl` cannot be read, is then overwritten by `settlements.jsonl` if that file has a state; finally `FAILED` if any `failed` event was seen, which overrides all of the above |
| `builds` | one `<phase>:<outcome>` per `built` event, space-joined | outcome is `never-ran` when `"infra":"true"`, else `passed` / `failed` from `"passed"` |
| `answers` | one `<agent>=<n>/<chars>` per agent, space-joined | `<n>` = `asked` events for that agent; `<chars>` = length of its **last** reply |
| | | `!<k>` appended when `k` of those replies were empty |
| | | `t<c>` appended when the agent made `c` `tool` calls |
| `test` | true if any `tool` event has `"tool":"write_file"` | prints `test written` / `NO TEST` |
| `idle` | `(now − max("at")) / 60000` | `0` when the trace has no parsable stamp |
| claimed | `<results>/claims/<id>` is a directory | only used for the QUIET flag |

Agents appear in first-answer order, then agents that only made tool calls. **An agent that only used
tools still appears** — it used to be listed only if it had answered, so an agent stuck before its
first answer was invisible.

**A prove that died must not read as one still working.** Without the `failed` case the state stayed
`proving` and the marker looked merely quiet, which is the one thing the supervisor is here to notice
and the one thing it could not see. The cause travels with the fact, because "no token in four
minutes" and "still generating after thirty" are different failures and only one of them is the
endpoint's. The stack does not: it belongs in the trace the watcher opens, not in three hundred rows.

**Tool calls are counted per agent because nothing else stops a loop any more.** The 25-call ceiling
that used to end a runaway is gone — it was a literal in `SubAgentRuntime`
(`.maxSequentialToolsInvocations(25)`) and the Dockerfile `sed`s it to `Integer.MAX_VALUE` before the
install. Twenty-five sequential calls is not many for an agent reading a class, its callers and its
tests, and a prove lost to that budget was lost to nothing about its marker; the ceiling did not
degrade, it threw and ended the prove. With no ceiling, `reproducer=0/0t140` is the only thing that
shows the shape of a loop, which is why the digest counts them and why an agent with no answers at all
still gets a row.

**Settled is stated as what it is NOT:**

```java
boolean settledState() {
    return !state.equals("proving") && !state.equals("infra")
            && !state.equals("FAILED") && !state.equals("queued");
}
```

A disposition added to `Prove` and forgotten here would read as unsettled forever; a new
not-an-answer state reads as settled once and is noticed. The negative list fails in the direction
that gets caught.

**A marker that has answered is not a marker gone quiet, however long its claim lingers.** The pool
releases a claim when its prove ends, but a release is an `rm -rf` after a JVM exits and the digest is
read on a timer, so the two overlap. Reading a claim as proof of a running prove had the watcher
reporting settled markers as stalled for a thousand minutes, twice, and its critic refuting the
finding both times — a whole pass spent on markers that were finished. Hence the three-way condition
on the QUIET flag.

---

## A finding is not a paragraph

The watcher is told, in its prompt, to open every finding with a line reading exactly:

```
## Finding: <the pattern in one sentence>
```

`Overwatch.split` cuts the report on that heading, case-insensitively, on a line whose leading
whitespace has been stripped. Then:

- a fragment shorter than **80 characters** is dropped;
- a fragment that does not contain `## finding` (lower-cased) is dropped — this removes the preamble;
- if nothing survives, **the whole report becomes one finding**, because better a whole claim judged
  once than several fragments judged separately, and a watcher saying the run is clean is itself the
  answer.

The heading requirement lives in the watcher's prompt and the split lives in code, and the prompt is
data (`Prompts.effective`) that a person can replace from the settings page. **The fallback is what
holds that seam together**: a prompt edited to drop the heading does not lose the report, it collapses
to one finding per pass.

**This split on blank lines once, and it cost the first real finding.** The watcher named four markers
whose tests pass on unfixed code, with a count and a traced cause in a prompt — a heading, a count, a
list of examples and an explanation. Splitting on paragraphs turned that into four claims none of
which contained the claim; the critic refuted `**Examples:** …` twice, correctly, because a list of
file names asserts nothing. **The boundary must be structure the watcher was told to produce, which is
checkable, rather than a guess about how a model formats prose.**

Each finding is judged alone, so that the critic judges one claim rather than agreeing with a mood.
The critic's task is:

```
The watcher raised this about the run. Judge it, and act only if a prove is stuck.

<finding>

---

The run as it stands:

<digest>
```

### The record

One line per judged finding, appended to `<results>/overwatch.jsonl`:

```json
{"at":"<epoch millis>","verdict":"holds|refuted|unjudged","finding":"<escaped>","judgement":"<escaped>"}
```

The verdict is decided here, not by the model:

- empty or null judgement → `unjudged`;
- otherwise, the judgement lower-cased **containing** the substring `refuted` → `refuted`;
- otherwise → `holds`.

Note what falls out of that: an agent that answers with tool calls and no prose returns `""`, so **a
critic that pulls a lever and says nothing records `unjudged`, not `holds`.** The action is in
`restarts.jsonl` and the finding is still marked as unchecked, which is the right way round — the
record must not claim a judgement nobody wrote.

`refuted` is matched as a substring of the **whole** judgement, not of its first line, even though the
critic is told to answer `holds` or `refuted` on a line of its own. A judgement that reasons its way to
`holds` while mentioning the word anywhere is recorded `refuted`. This is the reason the banner still
renders refuted findings instead of dropping them: a verdict decided by substring is not a verdict
worth hiding a finding on.

A file that cannot be written costs the finding a record and produces the progress note
`finding not recorded: <message>`; it does not end the pass.

The dashboard's banner reads this file and shows `holds` first, `unjudged` next because nobody has
looked, `refuted` last and greyed. **Refuted findings are shown rather than dropped**: hiding them made
twenty-two closed and open complaints look like twenty-two open ones and hid the one thing a reader
needs — whether anybody has checked this yet.

---

## The two levers

**They are for two different failures and the distinction is the whole point.**

| | `restart_prove` | `postpone_prove` |
|---|---|---|
| For a prove that is | **BROKEN** — died of something a fresh attempt would not hit (an endpoint that dropped, a worktree that was not there) | **WORKING** and simply taking much longer than the others |
| Effect on the queue | hands the marker straight back | frees the slot; the marker is proved again once the queue is otherwise done |
| Counted | at most `Supervisor.LIMIT` = 2 per marker, ever | not counted |
| Arguments | `marker` (the full key), `why` (one sentence) | the same |

Each is described to the model as what it is, because a tool whose description undersells it gets used
as though it were cheap. `restart_prove` opens with the literal word `DESTRUCTIVE.` and states the
limit inline (`A marker may be restarted at most 2 time(s), ever.`), interpolated from
`Supervisor.LIMIT` so the description cannot drift from the enforcement. `postpone_prove` is not called
destructive but is required to admit the same loss: *what comes back later is a fresh attempt, not a
continuation: nothing persists a conversation with a model, so the work so far is lost and only the
slot is saved.* Both descriptions name the other tool, so the model that reached for the wrong one is
told which is the right one in the same breath.

**Never because you disagree with an answer**: re-proving a marker until it agrees with you is not
supervision, and a settlement is evidence even when it is wrong.

Restarting a merely-slow prove changes nothing — it will take just as long again — while leaving it
running costs a quarter of the pool while the whole queue waits behind it.

### What a restart is, exactly

An agent is a synchronous call inside `SubAgentRuntime`: there is no process behind it to kill and no
mailbox to drain, so "restart the reproducer" is not a thing that can be done. **A prove is a process,
and the process is the supervised unit** — the Erlang answer rather than a departure from it: you do
not restart a function, you restart the process that owns it, and it comes back with nothing carried
over.

`Supervisor.restart(markerKey, why)`, in order:

1. `id = slug(markerKey)`; blank → `REFUSED: no marker named. Give the full marker key, as it appears
   in the record.`
2. count the restarts already recorded; at or over `LIMIT` → refuse (below), **changing nothing on
   disk**.
3. if neither `m/<id>` nor `claims/<id>` is a directory → `REFUSED: nothing named <id> is running or
   has run. Check the marker key against the record before restarting something.`
4. kill: `pkill -f "tree-<id> "` — the worktree name the pool gave it, with the trailing space. `killed`
   is `exit == 0`.
5. lift any postponement (`Pace.resume`); a failure here is a progress note, not an abort.
6. move `m/<id>` to `dead/<id>.restart-<n>`.
7. delete `m/<id>` and `claims/<id>`, recursively and best-effort.
8. append to `restarts.jsonl` and write the progress note `RESTARTED (<n> of <LIMIT>): <why>`.
9. return `RESTARTED <id> (<n> of <LIMIT>). ` then `The running prove was killed. ` or `No prove was
   running. `, then `Its record is kept as <id>.restart-<n>; the pool will take the marker again on
   its next pass.`

Steps 4–8 are unconditional once 1–3 pass: a `pkill` that matched nothing, a `keep` that could not
move the directory and a `record` that could not be written each leave a progress note and the sequence
carries on. **Only the two refusals stop it**, and both stop it before anything is touched.

**The record of the attempt outlives the attempt.** Deleting the trace along with the tree would erase
the evidence the restart was ordered on, and the next reader would find a marker that had simply been
proved twice with no account of why.

**But it must leave `m/`, which is the one place it must not stay.** This kept the record beside the
live ones as `m/<id>.restart-1`, and the pool decides whether a marker still needs proving by grepping
every `m/*/settlements.jsonl` for its key. So the kept record answered on the dead prove's behalf: the
claim was released, the marker was skipped, and **the restart did nothing at all while reporting that
it had**. A supervisor whose one action is silently a no-op is worse than one with no actions.

`<results>/restarts.jsonl`, one line per restart, every value a string:

```json
{"at":"<epoch millis>","id":"<slug>","marker":"<escaped key>","attempt":"<n>","killed":"<true|false>","why":"<escaped>"}
```

**A delete that fails leaves the directory, and the claim check then reads it as still claimed.** That
is safe in the only direction that matters: the marker is not re-proved, rather than proved twice at
once.

### The slug

```java
static String slug(String markerKey) {
    int slash = markerKey.lastIndexOf('/');
    String tail = slash < 0 ? markerKey : markerKey.substring(slash + 1);
    String cleaned = tail.replaceAll("[^A-Za-z0-9._-]", "_");
    return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
}
```

**It must match `entrypoint.sh`'s `sed 's|.*/||; s|[^A-Za-z0-9._-]|_|g' | cut -c1-80` exactly.** A
supervisor computing a different name deletes nothing and reports success. For the marker
`https://github.com/WebGoat/WebGoat.git|src/main/java/…/xxe/Ping.java|34|FB.DM_DEFAULT_ENCODING` the
slug is `Ping.java_34_FB.DM_DEFAULT_ENCODING`.

### Setting one aside

`Supervisor.postpone(markerKey, why)`, in order: slug the key (blank → `REFUSED: no marker named.`);
if `Pace.postponed` already says yes, answer `already postponed; it will be proved when the queue is
done.` and stop, so **nothing happens twice**; write `<results>/postponed/<id>` containing
`why.strip()` and a newline (a failure here is `REFUSED: could not set it aside: <message>` and
**nothing is killed**); kill the prove; delete the claim. It does **not** move `m/<id>` aside and does
not check the restart limit. On success:

```
SET ASIDE <id>. [The prove was killed. ]Its slot is free and the queue moves on; it is proved again
once everything else is done. What comes back is a fresh attempt, not a continuation — nothing
persists a conversation with a model.
```

`Pace.postpone` and `Pace.resume` each write a progress note against the marker id — `postponed: <why>`
and `resumed` — so the file on disk is never the only account of who set a marker aside.

The honest name is pause and the honest caveat is that it is not a suspension: a prove is a JVM
mid-conversation with a model, its state is in the runtime's memory and nothing persists it. **What is
saved is not the work — it is the SLOT**, which is the thing that was actually being wasted while three
hundred markers waited behind one.

The pool reads the postponed set when the queue is otherwise done (`Pace --list-postponed`), clears the
file and the claim, puts any surviving `m/<id>` aside as `dead/<id>.before-postponing` — because
`Prove` **appends**, and without that the second attempt lands on top of the first in one
`trace.jsonl` and reads as a single prove that changed its mind — and proves the marker again with the
pool to itself. A marker postponed again during that pass stays postponed: the second time it is not
competing with anything and taking long is all it is doing.

### There is no resume

**Because there is nothing to resume.** A postponed marker comes back by itself when the queue is done;
wanting it sooner is `restart_prove`, which is why `restart` lifts a postponement as part of doing its
job. A separate `resume` would be a second name for proving again from scratch, carrying a promise of
continuation it cannot keep. The prompt says it, the tool description says it, and a test asserts that
a restart actually clears `postponed/<id>`.

---

## The limit, and why it is in a file

```java
/** How many times one marker may be restarted, ever. */
static final int LIMIT = 2;
```

**Two, because the first restart tests "was that transient" and the second tests "was the first restart
the fix".** A third answers nothing the first two did not, and a marker that has failed three times is
a finding for a person, not a process to cycle.

**An agent asked to be sparing will be sparing until the run that it is not.** A supervisor that can
restart without bound is a loop that looks like progress: kill, re-prove, find the same anomaly, kill
again — a run that never finishes while every individual decision in it reads as reasonable. So the
count is read from `restarts.jsonl`, a file that outlives the process, **because the supervisor is
restarted too**. The tool description repeats the rule so the model does not waste a turn discovering
it; the enforcement is not there and not in the prompt.

The refusal names the alternative, because an agent told only "no" tries a different phrasing:

```
REFUSED: <id> has been restarted <already> time(s), which is the limit — and every restart threw away
the record of how long it had already taken, so it has cost <total> minutes across <n> attempts rather
than the one you can see. Restarting it again resets that count and changes nothing else. If it is
WORKING and merely slow, postpone_prove sets it aside and the pool proves it when the queue is done;
if it is broken in a way a fresh attempt will not fix, that is a finding to report and not a process
to cycle.
```

`<already>` is the count read back, not the constant: it is `>= LIMIT` and can exceed it, because a
person's `reprove` appends the same rows. `<total>` is `Pace.totalMinutes(results, id)` and `<n>` is
`Pace.attempts(results, id)`.

**A log that cannot be read is not a licence.** `restarts(id)` returns `0` when `restarts.jsonl` does
not exist — a run that has restarted nothing has restarted nothing — but `LIMIT` the moment the file
exists and will not be read:

```java
} catch (IOException | java.io.UncheckedIOException unreadable) {
    return LIMIT;
}
```

Reporting zero there would let an unreadable file lift the limit, which is the one direction this must
not fail in. **Absent means zero, unreadable means exhausted, and the two must not be collapsed.**

**Both exceptions**, because `Files.lines` does not fail where it is called: it opens eagerly and reads
lazily, so a file that cannot be read throws `UncheckedIOException` from inside `count()`. Catching
only the checked one let an unreadable log read as zero restarts and lifted the limit entirely.

### The same thing, ordered by a person

`Supervisor.reprove(markerKey, why)` is the dashboard's button: kill, keep the record aside, delete,
release the claim, record — **with no `LIMIT` check**, because a person pressing a button four times is
four decisions rather than a runaway. It keeps the two guards that are about naming rather than
budget: a blank slug answers `REFUSED: no marker named.` and an unknown one `REFUSED: nothing named
<id> has run.` It does **not** lift a postponement. The reason is stored prefixed
`asked for by a person — <why>`, because the next reader needs to know this marker was proved twice and
on whose say-so. It returns `queued again`.

`POST /reprove` with form fields `marker` and `why` (default `no reason given`) runs it under a
dashboard-owned trace (`dashboard-trace.jsonl` / `dashboard-settlements.jsonl`) and redirects 303 to
`/marker?k=<marker>&a=prompts`. The dashboard offers it where it is most obviously needed: a marker
whose prompts have been edited since it was proved reached its answer under instructions nobody is
using any more.

Note what the ledger does and does not distinguish: `reprove` performs no limit check, but it appends
the **same** `restarts.jsonl` row, and `restarts(id)` counts every row carrying that id whoever ordered
it. A person's reprove therefore does raise the number the agent's next `restart_prove` is measured
against.

---

## Pace: what a marker usually takes

**A fixed cap is the wrong instrument.** Thirty minutes is only "too long" because most markers finish
in five, and the moment the model, the endpoint or the subject changes that number is either strangling
ordinary work or letting a stuck prove run all day. The question is never "has it been half an hour",
it is "is this one taking much longer than the others" — and the others are measurable.

```java
static final int MUCH_LONGER  = 4;   // times the typical duration
static final int ENOUGH       = 8;   // settled markers before there is a typical at all
static final int NEVER_BEFORE = 20;  // minutes; nothing is an outlier under this
```

**`typical` is the median minutes of the settled lanes in this run, or 0 while there are fewer than
eight of them.** Precisely: over `m/*`, keep the settled lanes whose `minutes` is `> 0`, sort, and
return `took.get(took.size() / 2)` — the upper of the two middles on an even count. A lane whose trace
has no parsable stamp measures 0 and is therefore not one of the eight. `typical` is also 0 when `m/`
is missing or cannot be listed.

- **Median rather than mean**, because one prove that ran for four hours would drag a mean far enough to
  make itself look ordinary — precisely the case this exists to catch.
- **The floor matters more than the multiple.** Early in a run the median comes from two or three
  markers and can be a minute, which would set aside everything. Four times one minute is four minutes,
  and postponing a marker a quarter of an hour in is churn rather than scheduling.
- **Only settled lanes count.** A run where nothing has finished has no typical duration; taking the
  unfinished ones as evidence would make every long prove look normal. Settled here means the
  `settlements.jsonl` holds a state that is not blank, `proving`, `infra` or `queued`.
- **One attempt, not the total.** What "typical" answers is how long a marker needs *when it works*, and
  a marker that works needs one attempt. Summing archived attempts folds in operational churn — a
  container restarted mid-prove is not the marker being slow — and it did: **the median went from 8
  minutes to 744** the moment this counted every attempt, which would have made an outlier impossible.

`Pace.minutes(lane)` is first stamp to last stamp in that lane's `trace.jsonl`; `Pace.sinceStart(lane)`
is first stamp to now. A trace with no parsable `"at"` yields 0 and is skipped.

### The clock belongs to the marker, not the attempt

**`totalMinutes(results, id)` sums the current lane and every archived attempt for that marker; the
median deliberately does not.** `restart_prove` moves the trace to `dead/` and the next attempt starts a
new one, so a marker could burn seventy-nine minutes, be restarted, burn seventy-nine more, and read as
seventy-nine every single time. **The one tool the supervisor was reaching for was resetting the
measurement that would have told it to reach for the other one** — a marker could never look slow,
however long it actually took.

The two halves are measured differently and it matters: the live lane `m/<id>` contributes
`sinceStart` (its first stamp to **now**), each archived directory contributes `minutes` (its first
stamp to its last). A lane still on disk therefore keeps accruing against the clock whether or not it
has settled.

So `dead/<id>.restart-N` and `dead/<id>.before-postponing` are this marker's history and the time in
them was spent on this marker. `attempts(results, id)` counts them plus the current lane.

**An archived directory belongs to this marker only if what follows the id is an archive suffix.**
Prefix alone is not enough: `TAINTED_PTR` is a prefix of `TAINTED_PTR.COOKIE.abandoned`, so one marker
would absorb another's history and report time it never spent.

```java
private static boolean mine(String archived, String id) {
    if (!archived.startsWith(id + ".")) {
        return false;
    }
    String suffix = archived.substring(id.length() + 1);
    return suffix.matches("[a-z][a-z0-9-]*");   // archive suffixes are lowercase;
}                                               // every checker segment is not
```

That regex is what makes `restart-2`, `attempt-3`, `before-postponing` and `interrupted` this marker's
history and `COOKIE.abandoned` somebody else's. `mine` governs `totalMinutes` and `attempts`; `tries`
uses its own stricter pattern below.

### Tries versus attempts

**`tries` is how many times the POOL gave up on this marker; `attempts` is every go it has had.** They
are different numbers and the difference decides whether a marker is ever tried again.

```java
// tries: dead/<id>.attempt-<N> only
d.getFileName().toString().matches(Pattern.quote(id) + "\\.attempt-[0-9]+")
```

A supervisor restart and a postponement are somebody **choosing** to spend another go on this marker; a
pool retry is the marker **having failed to settle** on its own. Counting them together let two
supervisor restarts exhaust the pool's allowance (`TRIES=3` in `entrypoint.sh`) for a marker the pool
had only ever tried once, and the marker went quiet for the rest of the run with nothing saying why.

**An unreadable archive is not a clean slate:** `tries` returns `Integer.MAX_VALUE` when `dead/` exists
and cannot be listed, so a directory that cannot be read leaves a marker alone rather than handing a
broken one unlimited goes at the pool. A `dead/` that does not exist yet returns `0` — nothing has been
archived, so nothing has been given up on. Note the opposite polarity to `attempts` and `totalMinutes`,
which return what they have so far on the same failure; those only inform a sentence, while `tries`
gates work.

### What the digest says about a slow prove

`Pace.outlier` returns `""` unless `typical > 0` **and** `spent >= NEVER_BEFORE` **and**
`spent >= typical * MUCH_LONGER`. Otherwise:

```
TAKING MUCH LONGER THAN THE OTHERS: <spent> minutes on this marker[ across <n> attempts (<going> in
the current one) — restarting it again would reset that count without changing anything, which is how
it stayed invisible], against a median of <typical> for the markers that have settled. It is not
necessarily stuck — but it is holding a quarter of the pool while the queue waits, and it can be
postponed and picked up once the rest is done.
```

`<spent>` is `totalMinutes`, `<going>` is `sinceStart` of the current lane, and the bracketed clause
appears only when `attempts > 1`. The whole sentence is prefixed with `\n      <-- ` and appended under
the marker's row.

**"Not necessarily stuck" is load-bearing.** It is a slot problem rather than a fault, and saying
otherwise invites the wrong tool.

### The shell asks the same code

The pool is bash and the rule lives in Java, so `Pace` has a `main` and the shell shells out. **One
implementation, printed** — a rule with a second copy in shell drifts from the one that is tested, and
the shell's copy is the one that starts and stops processes.

```
Pace --postponed <results> <id>     # yes | no
Pace --list-postponed <results>     # one id per line, sorted
Pace --typical <results>            # minutes, or 0
Pace --tries <results> <id>         # integer
```

`<results>` defaults to `/results` when omitted; anything else prints the usage line to stdout. The
pool consumes these as strings — `[ "$tries" -ge "$TRIES" ] 2>/dev/null` — so a usage line where a
number was expected fails the comparison and the marker is proved rather than skipped.

`postponed/<id>` is written with the id stripped of anything outside `[A-Za-z0-9._-]` (removed, not
substituted); ids arriving from `slug` already satisfy that, so the two names agree.

---

## On disk

Everything the supervisor reads or writes, under the results root:

| Path | Written by | Read by |
|---|---|---|
| `m/<id>/trace.jsonl` | the prove | digest, `Pace`, the watcher's `read_file` |
| `m/<id>/settlements.jsonl` | the prove | digest (state), `Pace` (settled?), the pool |
| `claims/<id>/` | the pool (`mkdir`, atomic) | digest (QUIET), `restart`, `postpone` |
| `markers.txt` | whoever queued the run | digest (denominator) |
| `dead/<id>.restart-<n>` | `Supervisor.restart` / `reprove` | `Pace.totalMinutes`, `attempts` |
| `dead/<id>.attempt-<n>` | the pool's `release` and stale-claim sweep | `Pace.tries` (**only** this suffix), `totalMinutes`, `attempts` |
| `dead/<id>.before-postponing` | the pool's end-of-queue pass | `Pace.totalMinutes`, `attempts` |
| `postponed/<id>` | `Pace.postpone` | the pool (which also deletes it at end of queue), `Pace.postponed`, `Pace.allPostponed` |
| `restarts.jsonl` | `Supervisor.record` | the restart limit, the dashboard |
| `overwatch.jsonl` | `Overwatch.write` | the dashboard banner, `/overwatch`, and `chat`, whose prompt names it |
| `overwatch-trace.jsonl`, `overwatch-settlements.jsonl` | the supervisor's own trace | the dashboard |
| `overwatch.log` | `entrypoint.sh serve` redirection | nothing in the program; no prompt names it |
| `spec/` | `entrypoint.sh` copies it in on every start (`rm -rf` then `cp -R /opt/agent/spec/.`) | `overwatch` and `chat`, by prompt |

The spec is copied into the results tree because every agent's file tools are rooted there: a prompt
naming `/opt/agent/spec` would name a file none of them can read and would teach the model that its
tools are broken. Both prompts that name it name `spec/README.md` as the index, so **the copied tree
must contain a README.md that says which chapter answers what**, or the first thing the watcher does
with the instruction is fail to follow it.

**The watcher is told to read the chapter before reporting that a part it has not checked the intent of
is broken** — several of the rules here look like bugs until you know the failure they were written
for, and a finding that a deliberate design is a fault costs a working prompt a rewrite, which is worse
than a missed finding. The prompt names three of them: a critic whose silence permits, a marker
deliberately re-queued, a bound that measures silence rather than elapsed time. `overwatch-critic` is
**not** told about `spec/` — it is told how RED and GREEN work instead, because its job is to judge one
claim rather than to audit a design.

## Failure directions, in one place

| If this is absent or unreadable | The result is | Never the other way, because |
|---|---|---|
| the critic (no answer) | finding recorded `unjudged`; no lever pulled | an unreachable critic must not suppress a warning, nor authorise a kill |
| the watcher (no answer) | progress `the watcher had nothing to say`; nothing recorded | the guard sits before `split`, whose fallback would otherwise turn an empty report into one empty finding to be judged |
| `restarts.jsonl` **unreadable** | `restarts()` returns `LIMIT` — the restart is refused | an unreadable log must not lift the limit |
| `restarts.jsonl` **absent** | `restarts()` returns 0 — the restart proceeds | a run that has restarted nothing has restarted nothing |
| `dead/` **unlistable** | `tries()` returns `Integer.MAX_VALUE` — the pool leaves the marker alone | an unreadable archive must not hand a broken marker unlimited goes |
| `dead/` **absent** | `tries()` returns 0 — the pool gives the marker its full `TRIES` | nothing archived means nothing given up on |
| `postponed/<id>` cannot be written | `postpone` refuses and **kills nothing** | a prove killed with no record that it was set aside is a prove lost, not postponed |
| fewer than 8 settlements | `typical` is 0 and nothing is an outlier | a median of three markers would set aside everything |
| `markers.txt` | no denominator in the header | a guessed total is read as fact |
| `m/` | blank digest, `nothing has run yet` | a watcher woken early must not report a clean run |
| a directory that will not delete | reads as still claimed; the marker is not re-proved | better one marker skipped than two provers on one marker |
| a model call that throws | one pass lost, the loop lives | the run takes hours; the watcher must outlast a bad call |
