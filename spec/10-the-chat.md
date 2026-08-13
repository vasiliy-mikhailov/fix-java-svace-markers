# 10. Asking the supervisor

A **prove** is one JVM proving one **marker** (`repo|file|line|checker`), and it can see nothing but
its own marker. The **watcher** — the `overwatch` agent — is the one thing that sees the whole run;
it wakes on a timer, reads a **digest** (counts computed from every started marker's trace, never a
model's summary of them), reports patterns, and sleeps. A **turn** is one thing said by one side of
a conversation. A **disposition** is the settled state of a marker.

This chapter is the conversation: a person types a question on the dashboard, and an agent with the
watcher's subject and the watcher's view of the run answers it, in the dashboard's process, with
reading tools, two purpose-built readers of the record, and nothing that acts.

---

## What must be true

- **The answer is produced in the dashboard's process, not by the watcher loop.**
- **The asking agent has read tools only** — six of them — and no restart, no postpone, no build, no
  write.
- **The two questions it was reconstructing out of files are answered by tools instead**:
  `list_markers` and `marker_record`, both read-only. **The counts `list_markers` returns are EXACT
  and complete even when the rows are capped, and a capped listing says how many it left out.** *A
  listing that stopped at sixty rows in silence would be the same bug behind a better name.*
- **`queued` is a state**, not an absence: a marker in `markers.txt` with no directory under `m/`.
  It is counted like any other. *Counting only the directories is how 82 got reported as the size of
  a 356-marker queue.*
- **A marker name that matches several markers is refused with the candidates, never guessed.**
- **The question is on disk before the answer is attempted.** Always, and before the answering
  thread is started.
- **One question is answered at a time**, process-wide, and **the flag that says so lives in memory
  and never on disk.**
- **Every way the answer can fail becomes a turn**, including a configuration error.
- **A question with nothing under it and nothing running is reported as a restart, not as
  thinking.**
- **Nothing downstream reads `chat.jsonl`.** It is a record, not a mechanism, and a conversation
  that cannot be read is shown as empty rather than as an error page.
- **The credential fence is in the tool map, not in the prompt**, and it holds for every agent that
  gets file tools — not only this one.
- **A model reply is escaped before it is linked**, never the other way round.

---

## Why the dashboard answers and the watcher does not

The watcher is a loop in another process (`entrypoint.sh overwatch`, default 900 seconds between
passes). Posting a question into that loop would mean waiting up to a quarter of an hour for a
reply, and — in the file's own words — *a conversation with a fifteen-minute floor is not one*.

So the answer is produced **here**, in the dashboard's own process, by an agent with a prompt of its
own, over the same digest, with the watcher's read-only tools and two more that are also read-only.
(The code's javadoc says "the same prompt"; `Agents.chat` and `Agents.overwatch` have separate
built-ins, and the difference between them is the last section of this chapter.)

**Nothing is shared with the watcher but the results directory**, which is what both of them are
actually looking at. **The watcher is untouched — it does not know this exists, and a question
cannot make it miss a pass.** A rebuilder who "simplifies" this by handing questions to the watcher
loop buys a fifteen-minute latency and couples a person's typing to the supervision of a live run.

Both halves normally run in one container (`entrypoint.sh serve [seconds]`): `Overwatch` in the
background with its stdout to `$RESULTS/overwatch.log`, `Dashboard` in the foreground via `exec`.
**The asymmetry is deliberate and directional.** *A supervisor that dies must not take the record
with it — its own loop already survives a failed pass, and if the process goes the dashboard keeps
serving what is there. A dashboard that dies SHOULD end the container, so the restart policy brings
both back.*

---

## Read-only, and the three reasons

The agent is `chat`, built by `Agents.chat(results)` with `Tools.asking(results, trace, "chat")`.
`asking` is `reading` plus two:

```java
static Map<ToolSpecification, ToolExecutor> asking(Path results, Trace trace, String agent) {
    Map<ToolSpecification, ToolExecutor> tools = only(results, Set.of("list_dir", "read_file"));
    tools.putAll(registry(results));
    return recorded(tools, trace, agent);
}
```

`only` adds `grep` and `glob` to whatever it is asked for, so those four are the file tools;
`registry` adds `list_markers` and `marker_record`; `recorded` wraps all six so every call and its
result reach `chat-trace.jsonl`. **The set is exactly six, all of them reading.** The test names them
rather than counting them, so a seventh arriving by accident fails the assertion instead of passing
a count:

```java
assertEquals(List.of("glob", "grep", "list_dir", "list_markers", "marker_record", "read_file"),
        names, names.toString());
```

and then asserts that none of `restart_prove`, `postpone_prove`, `write_file`, `edit_file`,
`run_test` is among them — *the introspection is additive; the fence is unchanged.*

**`chat` is the only agent that gets `asking`.** `overwatch`, the interpreter pair and every judge
get `reading`'s four; the reproducer gets `writing` and the fixer `patching`; `overwatch-critic`
gets `supervising`. Three separate refusals stack on this one.

`only` counts what it kept and **throws `IllegalStateException` at construction if it is not
`names.size() + 2`**:

```
expected [list_dir, read_file] plus grep and glob but got <what was found>
```

The tool names are the library's, so a rename upstream would silently strip a capability. **Search is
given, not argued with, and a missing tool must fail loudly rather than quietly** — *a model asking
for a tool that does not exist does not fall back, it throws and the prove ends. Two markers were
lost that way: one to grep before this had one, one to glob after a prompt sentence was written to
talk a model out of wanting it.*

**1. No actions.** `restart_prove` and `postpone_prove` belong to `overwatch-critic` and to nothing
else, because that agent's **silence refuses to act** — an unreachable critic cannot authorise a
kill. A chat box holding the same tools routes around that:

> "what's happening with LessonMenuService?" is a question, and it must not be able to end as a
> killed prove because the model read it as a request.

So this one answers, names the button, and the person presses it. The prompt closes with it:

```
YOU CANNOT CHANGE ANYTHING. You have no tools but reading. If the answer is that a prove should be
restarted or set aside, say so and say why; the person has buttons for both on the marker's own
page. Do not claim to have done it.
```

The prompt says "the person has buttons for both on the marker's own page", and the page is
narrower than that. `Dashboard.java` has exactly one `<form … action='/reprove'>`, on the marker's
**prompts tab**, and it is rendered **only when at least one prompt used by that marker has been
edited since it was proved** — with `why` prefilled as `prompts changed: <agent>, <agent>`. There is
no `postpone` route and no postpone control anywhere in the dashboard. A rebuilder should keep the
prompt sentence (the point of it is that the agent must not claim to have acted) and know that the
restart button is conditional.

`POST /reprove` takes `marker` and `why`, calls
`new Supervisor(here, …).reprove(marker, why)` — `why` defaults to `no reason given` — and redirects
303 to `/marker?k=<enc(marker)>&a=prompts`. **A restart ordered from the page is not counted against
`overwatch-critic`'s two per marker**, because somebody who has read the page and pressed a button is
making a decision rather than looping.

**2. No build.** The `Agents` instance is constructed with a `Runner` that refuses, in the same
words the supervisor's own runner uses:

```java
new Agents(results, trace, (phase, test) -> new Runner.Result(true, false,
        "the supervisor does not build; it reads what the provers built"));
```

`Result(infra=true, …)` means *the build produced no test result at all* — never evidence, in either
phase. The reason is the reason the supervisor has no checkout: **an agent that can run the tests
can manufacture the evidence it is describing.**

**3. No secrets.** `Tools.only(...)` returns `withoutSecrets(kept)`, so **all four file tools are
wrapped before any agent gets them** — not just `read_file`, and not just this agent's. (The two
registry tools are put in *after* `only` has returned, so they are not behind that wrapper; why they
do not need to be is in the next section.) `SECRET` is
`Set.of("model", "git-credentials")`: the tuning file (`$TUNING`, default `/results/model`, which
holds `api_key=…`) and git's credential store.

**Two layers, because there are two ways to reach a file: name it, or find it.**

*Layer one — named.* Any of the four file tools whose `arguments` string names a secret returns,
instead of executing:

```
REFUSED: `<name>` holds a credential, not part of the record. Everything else under this directory
is readable. If you were asked for the API key or a git token, say that it is deliberately
unreadable from here and that the settings page is where it is handled.
```

The name is matched **as a whole path segment** against the raw arguments JSON — a segment being
what sits between separators, quotes or whitespace:

```java
args.matches("(?s).*(^|[/\\\\\"'\\s])" + Pattern.quote(secret) + "([\"'\\s,}]|$).*")
```

So `model`, `./model`, `/results/model` and `../results/model` are all refused, and **`Model.java`
and `m/ModelTest.java_9_Y/trace.jsonl` still read.** *`model` is an ordinary word. A guard matching
it as a substring would refuse every marker on a class called Model, which is a fence that has eaten
the job it was protecting.*

*Layer two — found.* `grep` reaches a file without naming it and returns the matching **line**, so
refusing by name does nothing there. Every result from every wrapped tool therefore goes through
`redact`:

```java
result.replaceAll("(?i)(api[_-]?key\\s*[=:]\\s*)\\S+",             "$1(hidden)")
      .replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@", "$1(hidden)@")
```

`null` and empty pass through untouched.

The exposure is older than the chat — the watchers were always rooted at `/results` — but **a chat
makes it a question somebody can type.** "What is in the model settings" is one line, and the answer
would put the API key into `chat.jsonl` and onto a page: the same key the settings form masks and
reveals only on a button. *A mask that a second route walks around is not a mask.* The guard is at
the tool layer and not in a prompt, because a prompt is a request and this has to be a fact.

The chat prompt states it too, so the model can answer the question instead of reporting broken
tools:

```
Two files there are NOT part of the record and will refuse to open: the model settings and git's
credential store. They hold an API key and a repository token. If you are asked for either, say it
is deliberately unreadable from here.
```

---

## The two questions it was reconstructing

Asked how many markers were in the queue, an agent holding only the four file tools took the only
route it had — `grep` across every `m/*/settlements.jsonl` — and answered:

> at least 60 markers (the grep output was suppressed after showing 60 matches, so the actual count
> is higher)

Truthful, careful about its own limits, and not the number. **The queue held 356.** Nothing in that
was the model's fault: *counting hundreds of files with a tool that returns matching LINES is the
wrong instrument, and a prompt telling it to count more carefully does not make it the right one.*

So the two questions it was reconstructing are answered directly, by `Registry` — **what is in the
queue and what state each marker is in**, and **what happened to one marker**. Both are things the
dashboard already computes for its own pages; the agent had no way to ask for them.

**This moves the failure rather than fixing it unless the new tool is exact where the old one was
not.** Hence the invariant that shapes both: nothing here truncates silently. The count is the true
count, it is stated *before* any rows, and a capped listing says how many it left out.

`Registry` is read-only, like everything else this agent holds. It reports the record; it cannot
touch it. It is not wrapped in `withoutSecrets` and does not need to be: **nothing a caller passes
becomes a path outside `m/`** — the argument is run through `Supervisor.slug`, which keeps only what
follows the last `/` and replaces every character outside `[A-Za-z0-9._-]` — and the file names it
reads are fixed (`markers.txt`, `m/<id>/settlements.jsonl`, `m/<id>/summary.txt`, and what `Pace`
reads). Neither secret is reachable under any of those names. *(One bounded quirk: `.` and `-` survive
the slug, so `marker_record("..")` resolves to the results directory itself and reports the run-level
`settlements.jsonl` and `summary.txt`. Those are part of the record; the fence is not crossed.)*

### `list_markers(state?, checker?, limit?)` → `Registry.list(results, state, checker, limit)`

Described to the model as *"the queue and the state of every marker in it. Returns exact counts by
state first — those are always complete — then one row per matching marker. USE THIS RATHER THAN grep
OVER settlements.jsonl: grep returns matching lines and stops, which reports a partial count as a
total."*

The queue is `markers.txt`, read whole, blank lines dropped and each line `strip`ped. **An unreadable
or missing `markers.txt` is said plainly and is not reported as zero:**

```
The queue is empty or unreadable: no markers.txt under the results directory.
```

*`0 markers` would be a confident wrong answer about the size of the run.*

Each queue line's state is `states(results)`: for every directory under `m/`, **the last settlement
line whose `state` is not `proving`**, defaulting to `proving` when there is none — the same rule the
pool and the dashboard use. A marker in the queue with **no directory under `m/` is `queued`**.
`infra` is reported as itself and never folded into a disposition, *because it means the prove THREW
and the marker is owed another attempt; reporting it as settled tells a reader the opposite.*

The output, in this order and no other:

```
<n> marker(s) in the queue. By state: <state>=<n> <state>=<n> …
[<m> match(es) the filter (state=<s> checker~<c>).]
<blank line>
<id>  |  <state>  |  <checker>
…
[
... <k> more not shown (<rows> of <m>). The counts above are complete; narrow with `state` or
`checker`, or raise `limit`, rather than treating this list as the total.
]
```

- **The totals come first and always**, over the whole queue, `TreeMap`-ordered by state name — *they
  are the answer to most questions asked here and they are exact whatever the rows below do.*
- **The totals do not move under a filter.** They count the queue; the `match` line counts the
  filter. Two different questions, both answered.
- `state` matches with `equalsIgnoreCase`; `checker` is a case-insensitive `contains` against the
  fourth `|`-field of the marker key. Blank means "all".
- Rows are `id  |  state  |  checker` — two spaces either side of each bar — where `id` is
  `Supervisor.slug(marker)`, the directory name under `m/`, which is the form the page turns into a
  link.
- The cap is `Math.max(1, limit)`, so a zero or negative `limit` still returns one row.
- **`Registry.ROWS = 60`** is the default cap — the same number the grep answer stopped at.
- Counting is over the queue only, so a lane under `m/` whose marker is not in `markers.txt` appears
  in no count and no row.

Two details a rebuilder will otherwise "fix" into something else:

- The filter line reads `1 matches` and `2 match` — `matched == 1 ? "es" : ""` is inverted. Cosmetic,
  and stated here so it is recognised as known rather than rediscovered as a bug in the counting.
- **`limit` is declared to the model as an integer property but read with `Tools.field`, a string
  scan that only returns quoted values.** A model emitting `{"limit": 200}` yields `""` and falls
  back to `ROWS`; `{"limit":200,"state":"proving"}` yields the literal `"state"`, which
  `NumberFormatException`s and also falls back. Only `{"limit":"200"}` is honoured. The failure
  direction is safe *because the capped listing announces its own cap* — the reader gets a truthfully
  shorter list, never a wrong total.

### `marker_record(marker)` → `Registry.one(results, wanted)`

Described as *"one marker: its key, checker, state, how long it took across how many attempts, why it
settled the way it did, and the lane interpreter's summary of what happened. Use this before reading
a trace — the trace is tens of thousands of characters and this is the part that answers most
questions."* `marker` is the only required parameter of either tool.

**It returns the LANE INTERPRETER'S summary, not the trace** (chapter 9): text already written and
already passed by `interpreter-critic`, since `Interpreter` writes `summary.txt` only from the
critic's checked version. *Handing over the trace instead would spend sixty thousand characters
answering "what happened to this one".* The record ends by **naming the trace path for what the
summary does not answer**:

```
The full record is `m/<id>/trace.jsonl` — every prompt, reply, tool call and build. Read it when the
summary does not answer what you were asked.
```

Resolution, in order — **a slug, a full key, or an unambiguous fragment**:

1. blank (or null) ⇒

   ```
   Name a marker: its id (as `list_markers` prints it) or its full key.
   ```

2. `Supervisor.slug(asked)` names a directory under `m/` ⇒ that id. (A full marker key slugs to its
   own id, so `repo|src/…/File1.java|1|FB.EI_EXPOSE_REP2` resolves.)
3. otherwise scan the queue: an id equal ignoring case ⇒ that id; else collect every marker whose id
   *or* whose full key contains `asked`, case-insensitively.
4. exactly one hit ⇒ that one. **More than one ⇒ refused, with up to six candidates named:**

   ```
   `<asked>` matches several markers: <id>, <id>, … . Name one exactly.
   ```

   *Guessing which of three the reader meant produces an answer about the wrong marker, which is
   indistinguishable from an answer about the right one.*
5. none ⇒

   ```
   No marker matches `<asked>`. Use list_markers to see the ids; they look like
   `Ping.java_34_FB.DM_DEFAULT_ENCODING`.
   ```

   (one line in the output; wrapped here)

The body, with each block omitted when it has nothing in it:

```
MARKER <id>
key      <the markers.txt line>
checker  <field 4>
file     <field 2>:<field 3>
state    <state>
took     <n> minute(s) across <n> attempt(s)

WHY IT SETTLED SO (from settlements.jsonl):
<the `because` of the last non-proving settlement>

SUMMARY (the lane interpreter's, already judged by its critic):
<summary.txt, or `(none — nothing has interpreted this lane yet)`>

The full record is `m/<id>/trace.jsonl` — …
```

- The `key`/`checker`/`file` lines are omitted when no queue line slugs to this id — a lane can
  outlive its marker in `markers.txt`.
- **A marker with no directory under `m/` stops after `state`**, with
  `queued — nothing has been proved for it yet; there is no record.` *Which is different from a
  marker that ran and decided nothing.*
- `took` is `Pace.totalMinutes` and `Pace.attempts`, both of which **add the archived attempts under
  `dead/`** — the clock belongs to the marker, not to the attempt (chapter 8).
- The state here defaults to `proving`, not `queued`: the directory exists, so something started it.

### What holds it

`TheQuestionsItWasReconstructingTest` — nine cases, and each one is an assertion about the failure
rather than about the format: the exact total is the first thing printed and a capped list says it is
capped; a marker with no lane counts as `queued`; `infra` is shown as itself; the totals stay whole
under a filter; the record carries the summary and names the trace; a full key and an unambiguous
fragment both resolve; an ambiguous one is refused; a queued marker says it has no record; an
unreadable queue is said plainly rather than reported as zero. The last case, `wired`, asserts the
six tool names on `Tools.asking` and the absence of the five that act.

---

## The files

All of them sit directly in the results directory, beside the run they are about.

| Path | Written by | Shape |
| --- | --- | --- |
| `chat.jsonl` | `Chat.append` | one JSON object per line, appended, never rewritten |
| `chat-trace.jsonl` | `JsonlTrace` (marker `chat`) | the agent's prompts, replies, thoughts and tool calls |
| `chat-settlements.jsonl` | nothing, on this path | named in the `JsonlTrace` constructor and never written: only `settled`, `failed` and `progress` reach `Settlement.note`, and the chat path calls none of them. Expect the file to be absent. |
| `chat-trace.jsonl.live` | `JsonlTrace.streaming` | the partial answer, whole-file overwrite as tokens arrive |

The trace is constructed as
`new JsonlTrace(results.resolve("chat-trace.jsonl"), results.resolve("chat-settlements.jsonl"), "chat")`.

`Chat.where(results)` is `results/chat.jsonl`; `Chat.live(results)` is
`results/chat-trace.jsonl.live`, **hard-coded**. `JsonlTrace.streaming` independently *derives* its
path as `trace.resolveSibling(trace.getFileName() + ".live")`. **The two are separate literals and
must agree**; if they drift, the live panel is permanently empty while an answer is being written and
nothing else fails, which is the kind of silence nobody reports.

### `chat.jsonl`

Exactly one line per turn, written with `CREATE, APPEND`:

```
{"at":"<System.currentTimeMillis()>","who":"you|supervisor","text":"<escaped>"}
```

- `at` is milliseconds **as a quoted string**.
- `who` is `you` for the person and `supervisor` for the model. `Turn.mine()` is
  `"you".equals(who)`; anything else renders as the supervisor.
- `text` is escaped by `Settlement.escape`: `"` `\` → escaped, newline/CR/tab → `\n` `\r` `\t`, any
  other character below `0x20` → `\u00xx`.

Read back by `Chat.turns`, oldest first:

- not `Files.isReadable` ⇒ empty list, no error.
- blank lines dropped; **any line whose `who` is empty is dropped** — that is the only validity test.
- `at` parsed with `Long.parseLong(s.strip())` and a fallback of `0`.
- `IOException | RuntimeException` ⇒ return what has been collected so far. A directory where the
  file should be gives an empty list rather than a 500 page.

Fields come out through `Json.field`, which is **a scan and not a parser — a malformed line costs
the field, never the file**, because a parser would refuse the whole file and take the dashboard down
with it. Note the one asymmetry: `Json.field` decodes `\n`, `\t`, `\r` and passes any other
backslash-escape through as the escaped character, but has **no `\uXXXX` case** — so a control
character other than those three does not survive the round trip that `Settlement.escape` wrote it
into. Nothing branches on the text, so it costs display only.

### `chat-trace.jsonl.live`

Not JSONL despite the name it is derived from. Three parts, whole-file overwrite, and
`JsonlTrace.streaming` returns without writing if fewer than `LIVE_EVERY_MS = 700` milliseconds have
passed since the last write:

```
<agent name>\n<millis>\n<everything the model has emitted so far>
```

The dashboard's `panel(who, file, open)` reads it with two `indexOf('\n')` calls and requires the
second newline at index > 0; otherwise it keeps nothing. It shows the **last `LIVE_TAIL = 4000`
characters** — the tail, because a reasoning turn runs to tens of thousands of characters and opening
on the beginning shows the same paragraph for four minutes.

The chat page calls it as `panel("supervisor", Chat.live(results), true)`, so the fold is
`<details class=stream open id='live-supervisor'>` and its summary reads

```
supervisor · <agent name from line 1> · <n>s ago
```

with `who` truncated to 46 characters, the middle segment omitted when line 1 is empty, and the last
segment being `quiet <n>m` once the timestamp is more than 90 seconds old. **A file that is absent,
unreadable or caught mid-rewrite is not an error**: `text` stays empty, the age segment reads
`nothing yet`, the body is a single `…`, and the page refreshes three seconds later.

---

## The order of writes, which is the point

`Chat.ask(results, question)`:

1. `null` ⇒ `""`, otherwise `strip()`; empty ⇒ return `""` and record nothing. *An empty textarea
   submitted by a stray Enter should not put a blank line in the record and spend a model call
   on it.*
2. `ASKING.compareAndSet(false, true)`; lost ⇒ return `"still answering the last one"`.
3. **Append the `you` turn.** If that throws, clear `ASKING` and return
   `"could not write the question down: " + message`.
4. Start a **daemon** thread named `chat` running `answer(results, asked)`.
5. Return `""`.

**The question is written down before the answer is attempted, so a dashboard that dies mid-reply
leaves a record of what was asked rather than nothing at all.** The page can then see a question
with no answer under it and say so, which is the honest state.

The thread is a daemon **so a reply in flight cannot keep the container up when the dashboard is
told to stop. What is lost is one answer, and the question it belongs to is already on disk.**

Reversing steps 3 and 4 — answering first and recording the pair at the end — is the simplification
that destroys the restart-detection in the section below, because there is then no difference on
disk between a question that was never asked and one whose answer was killed.

---

## One at a time

`private static final AtomicBoolean ASKING` — process-wide, because there is one dashboard.
`Chat.answering()` reads it.

Not for correctness. Two answers would interleave in nothing, since each appends a whole line. The
reason is:

> the second question would be asked without the first one's answer in front of it, and a
> conversation where the replies arrive out of order reads as a model that has lost the thread.

A person who asks again while one is in flight is told to wait — the string
`still answering the last one`, shown on the page. The page also disables the textarea and the
button while `answering()` is true, so the flag is enforced twice: once in the UI and once in
`ask`.

**The flag lives in memory and never on disk.** That is the fail-safe direction: a restarted
dashboard comes up with `ASKING == false`, so the state it lands in is "no answer came back" rather
than "still answering" forever. Persisting this flag is a silent catastrophe — the page would wait
on an answer no process is writing, for the life of the deployment.

---

## The prompt

Rebuilt from disk on every question, because the run moves.

```
The run as it stands:

<digest>


---

What has been said so far:

THEY ASKED: <text>

YOU ANSWERED: <text>

…

---

THEY ASK: <the question just recorded>
```

Exactly:

- The digest comes from `new Overwatch(results, null, null).digest()` — the same counting the
  watcher uses, constructed with **null agents and null trace** purely to call it, because `digest()`
  touches neither. It returns blank when `results/m` is not a directory *or cannot be listed*, and
  blank becomes the single line `Nothing has run yet — there are no markers to report on.\n` in place
  of the `The run as it stands:` block; otherwise `"The run as it stands:\n\n" + digest + "\n"`.
- **The `What has been said so far:` block is omitted entirely when there are no earlier turns** —
  the separator with it. A first question is digest, `\n---\n\nTHEY ASK: `, question.
- **`KEEP = 20` earlier turns**, taken as `subList(max(0, n-1-KEEP), max(0, n-1))` — the last turn
  is the question just appended, so it is asked below rather than twice. Twenty *turns*, not twenty
  exchanges.
- Prefixes are literal: `THEY ASKED: ` for a turn where `who == "you"` and `YOU ANSWERED: ` for
  every other turn, each turn's text followed by `\n\n`. **The naming is from the model's point of
  view and inverted relative to `who`** — a rebuilder who maps `you` to `YOU ANSWERED` hands the
  model its own questions as its own answers.

Why twenty: the digest is already the large part of this prompt — three hundred markers, several
tens of kilobytes — and beyond twenty turns *the oldest of it is about markers that have since
settled, which is worse than absent because it reads as current.*

The agent's system prompt is `Prompts.effective("chat", <built-in>)`, so an override at
`$PROMPTS/chat.txt` — `PROMPTS` defaulting to `/results/prompts` — replaces it entirely. Read at
construction, which here means on every question, so an edit takes effect on the next one.

**An override that cannot be read falls back to the built-in, never to an empty prompt.**
`Prompts.saved` swallows `IOException | RuntimeException` and returns blank, and blank means
"built-in". *That is the only safe direction: the alternative is an agent running with no
instructions at all and answering something.*

The built-in opens by telling it what the digest is and is not, and the sentence is the boundary
between this prompt's two halves:

```
You are given a digest of every marker that has STARTED — its state, what its builds did, how many
times each agent answered and how long, whether a test was written, how it settled — and then the
conversation so far. The digest tells you WHERE TO LOOK. It is not the evidence, and it is not the
whole queue.
```

Then the two tools, **named before the files and told to be reached for first**, because the whole
point of adding them was that the model would otherwise reconstruct their answers out of the files
below:

```
TWO TOOLS ANSWER MOST QUESTIONS ASKED HERE. Reach for them first.

  list_markers(state?, checker?, limit?)  the queue and the state of every marker in
                                          it. The counts it returns are EXACT and
                                          complete even when the rows are capped.
  marker_record(marker)                   one marker: key, checker, state, what it
                                          cost, why it settled so, and the lane
                                          interpreter's summary of what happened.

DO NOT COUNT MARKERS WITH grep. It returns matching lines and stops, so a count taken
from it is a floor and not a total — that mistake has already been made here, and the
answer given was "at least 60" when the queue held 356.
```

**The prohibition carries its own incident.** *A rule stated without the failure behind it is a rule
the next reader deletes as superstition — and this one has to outrank a sentence further down the
same prompt that still recommends `grep` for counting, so it is stated first and in capitals.*

Only then does the built-in name what is in the results directory, because *a question is usually
answerable from one of these and you will not guess the names*:

```
YOU CAN ALSO READ THE RESULTS DIRECTORY, with read_file, list_dir, grep and glob. What is in it,
for the questions the two tools above do not answer:

  markers.txt                     the WHOLE QUEUE, one marker per line as
                                  `repo|file|line|checker`. This is the list to read
                                  when asked what markers there are, how many, or
                                  which of a checker family are queued — the digest
                                  only covers the ones that have started.
  m/<marker>/trace.jsonl          every prompt, reply, tool call and build, in full
  m/<marker>/settlements.jsonl    one line per stage; the last is the disposition
  m/<marker>/slice.log            what the pool's shell said while proving it
  dead/<marker>.<why>             attempts that were restarted, postponed or failed
  overwatch.jsonl                 findings you have raised before, and their judgements
  restarts.jsonl                  every restart, with the reason given
  chat.jsonl                      this conversation
  spec/                           THE SPECIFICATION OF THIS PIPELINE, by chapter.
                                  `spec/README.md` is the index and says which
                                  chapter answers what. Read the relevant one before
                                  answering any question about how something is
                                  SUPPOSED to work — what a disposition means, why a
                                  critic's silence means what it does, what the pool
                                  does with a claim. Do not reason it out from the
                                  traces when it is written down.
```

Two of those are load-bearing for a rebuilder:

- **`markers.txt` is named separately from the digest** because the digest only covers markers that
  have *started*. Asked how many markers there were, the watcher once answered "82" and cited its
  digest line correctly — 82 being the directories under `m/`, the queue being 356. `list_markers`
  is now the instrument for that question and counts the queue rather than the directories; the file
  stays named because the tool is not the only way to read it.
- **`spec/` is reachable at all only because `entrypoint.sh` copies it there.** Every agent's file
  tools are rooted at the results directory, so a prompt naming `/opt/agent/spec` would name a file
  none of them can read *and would teach the model to report that its tools are broken*. The copy
  is `rm -rf "$RESULTS/spec"`, `mkdir -p`, then `cp -R /opt/agent/spec/. "$RESULTS/spec/"` on
  **every** start — refreshed so a deploy updates it, and the old one removed first **so a chapter
  deleted upstream does not linger as a chapter the agent still cites.** The block is guarded by
  `[ -d /opt/agent/spec ]` and every step ends `|| true`: *a spec that cannot be copied costs the
  agent a reference and must not stop the container from proving anything.*

The next paragraph tells it to check rather than estimate, which is what makes `grep` worth having:

```
Read before you assert. Quote the words you found. Counting lines in a file beats estimating from
the digest, and `grep` over `m/*/settlements.jsonl` answers most "how many settled as X" questions
exactly.
```

**That last clause and the `DO NOT COUNT MARKERS WITH grep` block above it are in tension, and both
are in the prompt as it stands.** The prohibition is stated first, in capitals, with the incident
attached; `grep`'s counts are a floor, and `list_markers(state=…)`'s are exact. *A rebuilder
reconciling the two by deleting the prohibition deletes the only sentence that names the failure,
and gets "at least 60" back.*

The prompt also fixes how markers are named, and that is a UI contract, not a style note:

```
Refer to markers by the directory name the digest uses
(`LessonMenuService.java_64_FB.GC_UNRELATED_TYPES`) — the page turns those into links to the
marker, so naming one exactly is how you show your work.
```

`Dashboard.linked(text, markers)` **escapes first and links second**, so nothing a model wrote can
put markup on this page. It then replaces each slug with

```html
<a href='/marker?k=<url-encoded marker key>'>slug</a>
```

wrapping each replacement in `U+0000` / `U+0001` sentinels that are stripped at the end — two passes
would otherwise relink a slug that is a prefix of another inside the `href` just written.

The slug map is `Dashboard.slugs(results/markers.txt)`: a `LinkedHashMap` from slug to the marker key
it came from, built after **sorting the marker keys longest-first**, so a slug containing a shorter
one is linked whole rather than having its middle replaced. The slug rule is `Supervisor.slug`:
everything after the last `/`, every character outside `[A-Za-z0-9._-]` replaced with `_`, cut to 80
— *the same rule `entrypoint.sh` names claim and lane directories by, and it has to match exactly.*

---

## The route

Registered like every other route, through the `guarded` wrapper that turns a thrown handler into a
500 page with a stack in it.

```
POST /chat   q=<question>
  → said = Chat.ask(here, form(e).getOrDefault("q", ""))
  → 303 to "/chat"                      when said.isBlank()
  → 303 to "/chat?said=" + enc(said)    otherwise
GET  /chat   [?said=<message>]
  → chat(here, slugs(results/markers.txt), query(e, "said"))
```

`here` is the results directory — the parent of the settlements path the dashboard was started with,
or `.` when it has none.

**The POST is answered with a redirect** so that the refresh which watches for the reply cannot
re-post the question: *the reply to a POST here is the page, and a page that re-asks itself every
three seconds would be a question asked twenty times before its first answer arrived.*

The form is `application/x-www-form-urlencoded` with one field, `q`.

**Enter sends; Shift-Enter is a new line.** `SEND_ON_ENTER` is one script, appended after the form,
listening on the document:

```html
<script>document.addEventListener('keydown',function(e){
  if(e.key!=='Enter'||e.shiftKey)return;
  var t=e.target;
  if(!t||t.tagName!=='TEXTAREA'||t.name!=='q')return;
  var f=t.form; if(!f||t.disabled)return;
  if(!t.value.trim()){e.preventDefault();return;}
  e.preventDefault(); f.submit();
});</script>
```

*Which way round to put those is decided by what the box is for. This is a chat, so almost every
message is one line and reaching for a button after each is the whole friction; a multi-line question
is the rare case and keeps the modifier.* The placeholder says so — `ask about the run… (enter
sends)` — because a box that submits on Enter and does not say it will costs somebody a half-written
question.

Four guards, each of which is a bug if dropped:

- **`e.shiftKey`** returns early and calls no `preventDefault`, so Shift-Enter reaches the textarea
  as an ordinary newline and never sends.
- **the target must be a `TEXTAREA` named `q`**, so the listener being on `document` does not make
  Enter submit from anywhere else on the page.
- **`t.disabled`** — *the form is disabled while an answer is coming, and a keypress must not post a
  question the page has just said it will not take.*
- **a blank value is swallowed** (`preventDefault` and return), so Enter on an empty box neither
  posts nor inserts a newline. `Chat.ask` refuses a blank question anyway; this stops the round trip.

`f.submit()` and not `f.requestSubmit()`: there is no submit handler to run and no client-side
validation on this form.

Every page built through `Dashboard.head()` carries the entrance — `✉` (✉), emitted immediately
after the settings gear `⚙` (⚙), both absolutely positioned in the header corner:

```html
<a class=gear href='/settings' title='settings'>⚙</a>
<a class='gear ask' href='/chat' title='ask the supervisor'>✉</a>
```

(the characters themselves, `U+2699` and `U+2709`, not HTML entities)

*It was a text link at the bottom of the list, under 356 rows, which is a link nobody has: a reader
who does not already know the page exists will not scroll past the whole run to discover it.* Next to
the gear, because both are "leave this page and do something".

---

## The page, and its three states

`Dashboard.chat(results, markers, said)` reads `Chat.answering()` **once, into a local**, and emits,
in this order:

1. `<meta http-equiv=refresh content=3>` — only when answering, and before everything else.
2. `head("ask the supervisor", <sub>, "all markers")` — style, scripts, header, title.
3. `<div class=chat>`, the empty-transcript note if `Chat.turns(results)` is empty, then every turn.
4. Inside the same `div`: the live panel when answering, **else** the restarted-mid-answer note when
   `Chat.unanswered(results)`, else nothing. `</div>`.
5. The `said` note, when `said` is non-blank.
6. The form, then `SEND_ON_ENTER`.

The header's subtitle is the standing statement of the fence:

```
the agent that watches this run, over the whole record. It reads; it cannot restart or set
aside a prove &mdash; those are buttons on a marker's own page.
```

**Answering.** The meta refresh, the live panel (`panel("supervisor", Chat.live(results), true)`),
and both controls disabled with the textarea placeholder `answering the last one…` (`ask about the
run… (enter sends)` otherwise).

A meta refresh is a blunt instrument and *it is the right one here: it needs no script, it cannot
double-post because the question was answered with a redirect, and there is nothing on the page to
lose while it fires — the box is empty and the person is waiting.* **When the answer lands the
refresh stops**, and the page holds still while they read it.

The dashboard's server-sent-event stream is not what drives this. `/events` polls two line counts
every two seconds and emits

```
data: {"trace":<lines of results/trace.jsonl>,"settled":<lines of results/settlements.jsonl>}
```

**Neither of those files is the one an answer in progress moves** — the chat agent writes
`chat-trace.jsonl` and `chat-trace.jsonl.live`. So the stream would go quiet for exactly the four
minutes worth watching. `head()` does put the `/events` `EventSource` script on this page like every
other, and it is inert here: the script's non-list branch returns unless the body declares a
`data-events` cursor, and only the trace views emit one.

**Restarted mid-answer.** `Chat.unanswered(results)` is:

```java
List<Turn> said = turns(results);
return !said.isEmpty() && said.get(said.size() - 1).mine() && !answering();
```

— the last thing said was a question, and nothing in this process is answering it. Which happens
when **the dashboard was restarted mid-reply: the container is redeployed often and an answer takes
minutes.** The page says so:

```html
<div class=k>No answer came back &mdash; the dashboard restarted while it was being written. Ask again.</div>
```

**Distinguishing it from "still thinking" is the difference between a page that says wait and a
page that waits forever.** Left unsaid, the page reads as still thinking and never stops. This is
the single most important line in the chapter to get right: the two states look identical on disk
(a `you` turn with nothing after it) and are told apart *only* by the in-memory flag, which is
exactly why the flag must not be persisted and why the question must be written before the attempt.

**Idle.** Neither of the above: the form is live and nothing is appended after the transcript.

Separately from the three states, an **empty transcript** — the test is on `turns` being empty, not
on being idle — gets

```
Nothing asked yet. It can see every marker's state, builds, answers and settlement, and can open
any trace to check before it answers.
```

A non-blank `said` query parameter is rendered between the transcript and the form as
`<div class=k style='padding:0 24px 8px'>` — that is where `still answering the last one` and
`could not write the question down: …` appear. It is `esc`aped and **not** linked.

Each turn renders as:

```html
<div class='say mine|theirs'>
  <div class=who>you|supervisor <span class=k>&middot; <ago></span></div>
  <div class=said><!-- escaped, then linked --></div>
</div>
```

`ago` is omitted when `at == 0`; otherwise `<90s` → `Ns ago`, `<5400s` → `Nm ago`, else `Nh ago`.
`.said` is `white-space:pre-wrap` with `overflow-wrap:anywhere`, so the model's own line breaks
survive — which is why the reply is stripped at both ends before it is stored.

And the form:

```html
<form class=ask method=post action='/chat'>
  <textarea name=q rows=3 autofocus placeholder='…'[ disabled]></textarea>
  <button[ disabled]>ask</button>
</form>
```

---

## Every failure is a turn

`Chat.answer` wraps **everything** in one `try` — building the `JsonlTrace`, building the `Agents`,
building the `chat` agent (which is where a missing endpoint throws), building the prompt (which is
where the digest is computed), and the model call — catches **`RuntimeException | Error`**, and writes
the failure as the supervisor's turn:

```
could not answer: <throwable.toString()>
```

`Error` too: *an OutOfMemoryError from a large digest would otherwise take the flag with it and
wedge the page.* A thrown exception here would leave a question with nothing under it **and a flag
stuck on**, so the page would say "still answering" for the rest of the process's life. Every
outcome becomes a turn, **including the ones that are somebody's configuration being wrong — which
is the case a person most needs to see, because it is the one they can fix** (a blank `base_url`
throws `no endpoint: set QWEN_BASE_URL or the model settings`, and that sentence reaches the page).

An empty reply is not an error. `Agents.runtime` already turns a null model reply into `""` — *an
agent that answers with tool calls and no content returns null; that is an empty judgement, not a
failure* — and `Chat.answer` defends against it a second time. `null` or blank becomes

```
(nothing came back — the model answered with silence)
```

The reply is `strip()`ped, because the answer is rendered pre-wrap and a reply opening with two
newlines *renders as a reply that starts an inch below its own name. Inside the text they are the
author's; at the ends they are an artefact of how it was generated.*

### Failure directions, all of them

| Situation | What happens | Why that direction |
| --- | --- | --- |
| `chat.jsonl` unreadable, a directory, or malformed | `turns()` returns what it had, which for a failed read is empty | *A conversation that cannot be read is shown as empty rather than as an error page. The file is a record, not a mechanism: nothing downstream depends on it.* |
| Question cannot be appended | flag cleared, no thread started, message shown | Nothing was promised, so nothing may be left pending |
| Model call throws anything | recorded as a `supervisor` turn | A stuck flag is worse than a bad answer |
| Reply cannot be appended | nothing is shown; **flag still cleared in `finally`** | "Nothing left to tell them with. The flag still has to come off." |
| Dashboard dies mid-answer | question on disk, flag gone with the process | Page reports the restart instead of waiting forever |
| `.live` file cannot be deleted | stale partial may show briefly | *It is only a display artefact; the recorded turn is the truth* |
| Endpoint unreachable | the sentence reaches the page as an answer | The one failure a person can act on |
| `prompts/chat.txt` unreadable | the built-in prompt is used | *An agent running with no instructions at all would answer something* |
| A secret is named or matched | `REFUSED: …`, or the line redacted to `(hidden)` | The fence is a tool map, not a prompt: it holds for the judges and the watcher too |
| A tool the library renamed | `only` throws `IllegalStateException`; here that is inside `answer`'s `try`, so it becomes a `could not answer: …` turn, and `Agents.builtIn` swallows it at start-up | Loud beats silent: *a model asking for a tool that does not exist does not fall back, it throws* |
| `markers.txt` missing or unreadable | `list_markers` says `The queue is empty or unreadable: no markers.txt under the results directory.`; `marker_record` can then resolve only by an existing `m/<id>` directory | *`0 markers` is a confident wrong answer about the size of the run; "I could not read the queue" is not* |
| `m/` absent or unlistable | `states()` returns empty, so every queue marker counts as `queued` | The total stays right and no marker is claimed to have settled |
| A lane with no `settlements.jsonl` | `proving` in both tools | It has a directory, so something started it |
| A lane whose marker is not in `markers.txt` | invisible to `list_markers`, reachable by `marker_record` (its slug names the directory), with the `key`/`checker`/`file` lines omitted | The listing is of the queue; the record is of a lane |
| `summary.txt` absent or unreadable | `(none — nothing has interpreted this lane yet)`, and the trace path is still named | *A missing summary costs a lane its plain English and nothing else, because the fallback is the record* |
| An ambiguous `marker` argument | refused, with up to six candidates | *An answer about the wrong marker is indistinguishable from an answer about the right one* |
| A registry tool throws | not a normal path — every read in `Registry` and `Pace` catches its own IO failure and returns blank, empty or zero — but if one did, `recorded` writes `threw <Class>: <message>` to `chat-trace.jsonl` and **rethrows** | An agent must still see its own failure, and a reader must still see that it happened |

The `finally` block does two things in order: `ASKING.set(false)`, then
`Files.deleteIfExists(live(results))`. **The partial must be deleted**, because it is a copy of what
is now recorded properly and *leaving it makes the page show the last answer as though it were still
arriving.*

---

## Where `chat` sits among the agents

There are **fifteen prompts**: `CHAIN`'s ten, `WATCH`'s four, `ASKED`'s one.

`Agents.ASKED = List.of("chat")` — its own list, after `CHAIN` (the ten that run inside a prove:
`reproducer`, `proof-critic`, `fixer`, `fix-critic`, `pr-maker`, `pr-critic`, `verdict`,
`verdict-critic`, `estimator`, `estimator-critic`) and `WATCH` (`overwatch`, `overwatch-critic`,
`interpreter`, `interpreter-critic`). `ORDER` is `CHAIN`, then `WATCH`, then `ASKED`, flattened —
so `chat` is last, and the comment says why: **the one that speaks only when spoken to. Last, because
it runs on nobody's schedule.**

That ordering is what the prompts page renders in — *pipeline order, not the hash's: a page of
prompts sorted alphabetically puts `estimator-critic` first and `reproducer` eleventh, which is the
reverse of how anybody thinks about this.* It is one list rather than three because **three copies of
an order drift and the drift is invisible**: *the marker tabs were missing `verdict-critic` entirely,
so an agent that can send a settlement back for rework had no page of its own and nobody noticed.*

`chat`'s built-in prompt reaches the editor the same way every other one does. `Agents.builtIn`
constructs each runtime purely to collect its text, `runtime()` records the built-in **before**
anything can throw, and each construction is wrapped in `catch (RuntimeException)` — **a reader of
the prompts page needs no inference endpoint to be up.** An override at `/results/prompts/chat.txt`
then replaces the built-in entirely.

Its subject is the watcher's subject and its tools are the watcher's four plus `list_markers` and
`marker_record`; the one difference that decides its whole shape is that **it is asked rather than
scheduled.** The watcher reports what it finds worth reporting every fifteen minutes; this answers
the question actually in front of somebody, now, *which is usually narrower than a pattern and often
just "what is this marker doing"*.

The two extra tools follow from that difference rather than contradicting it. **A watcher chooses
what to report and reports it from its digest; an asked agent is handed a question that may be about
anything, including the part of the run the digest does not cover** — the queue. Both of the counting
incidents (the "82", the "at least 60") happened when something was asked how big the queue was;
`chat` is where that question arrives now. *Whether `overwatch` should hold the two tools as well is
open — today it does not.* The guard that matters is in `AskingTheWatcherSomethingTest`, which reads
`Agents.chat`'s source and allows `Tools.asking(` **or** `Tools.reading(` while forbidding
`Tools.supervising(`:

> `asking` is `reading` plus `list_markers` and `marker_record`, both read-only. What the fence
> forbids is the SUPERVISING set, and naming the allowed sets explicitly is what made this guard fire
> when the wiring moved from one to the other — which is the point of it.

Two instructions in its prompt exist for that difference:

```
SAY WHEN YOU DO NOT KNOW, and say what you would have to read to find out. A confident wrong answer
about a run costs more than a slow one, because the person asking cannot tell them apart and will
act on it.

Answer in a few sentences unless asked for more. This is a conversation, not a report: no headings,
no numbered findings, no restating of the question. If the honest answer is one line, give one line.
```

The watcher's format rule (`## Finding: …`, one heading per pattern, judged one at a time) does
**not** apply here: nothing parses a chat answer.
