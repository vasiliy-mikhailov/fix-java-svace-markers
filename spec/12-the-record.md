# 12. The record on disk

There is no database in this program. A shared volume — `/results` by default, and every process here
mounts the same one — holds everything: what each prove was asked, what it answered, what the builds
did, what it settled as, what a person thought of it afterwards, and the settings the next prove will
read. The files are the record, and every rule below exists because a reader, a pool or a dashboard
had to answer a question from them and could not.

Vocabulary used in this chapter:

- **marker** — one line of `repo|file|line|checker` from the analyser. The whole string is the
  marker **key**.
- **id / slug** — the directory name a marker gets. Derived from the key; see *The slug* below.
- **lane** — one attempt at one marker: the directory `m/<id>/` and everything in it.
- **trace** — the append-only file of every event in a lane, `trace.jsonl`.
- **settlement** — one line of the journal `settlements.jsonl`, in the dashboard's column names.
- **disposition** — the state a marker ends in: one of seven words.
- **the live file** — `*.jsonl.live`, a view of an answer in progress. Not part of the record.
- **claim** — a directory under `claims/` whose existence means a prover has taken that marker.

---

## The volume, in full

```
/results/
  markers.txt                     the queue: repo|file|line|checker, one per line
  markers-before-<millis>.txt     the queue that was replaced, kept beside it
  source.zip                      an uploaded tree; when present nothing is cloned
  git-credentials                 git's own store, rw------- (SECRET)
  jdk                             one line: 25 | 21 | 17 | 11 | 8
  workers                         one line: how wide the pool runs
  model                           model settings, key=value lines, rw------- (SECRET)
  prompts/<agent>.txt             a prompt override; absent means the built-in
  spec/                           this specification, copied in on every container start
                                  (`model` and `prompts/` are relocatable by $TUNING and
                                   $PROMPTS; the root itself by $RESULTS)

  claims/<id>/                    a directory; its existence IS the claim
  postponed/<id>                  a file; its content is why; its existence is the flag
  m/<id>/                         one lane — one attempt at one marker
      trace.jsonl                 every event, appended
      settlements.jsonl           one line per stage, appended
      trace.jsonl.live            the answer in progress, OVERWRITTEN
      summary.txt                 the interpreter's account, written once when settled
      slice.log                   the shell's own log for this lane
  dead/<id>.attempt-N/            a lane the pool gave up on, moved aside whole
  dead/<id>.restart-N/            a lane the supervisor restarted, moved aside whole
  dead/<id>.before-postponing/    a lane set aside when the marker was postponed

  settlements.jsonl               single-marker `prove` mode writes here, not under m/
  trace.jsonl                     likewise
  trace.jsonl.live                likewise
  feedback.jsonl                  the labelled corpus; the dashboard appends
  restarts.jsonl                  every restart, who ordered it and why
  overwatch.jsonl                 the watcher's findings and their judgements
  overwatch-trace.jsonl           the watcher's own trace (+ .live)
  overwatch-settlements.jsonl     the watcher's own journal
  overwatch.log                   the watcher's stdout, redirected by the shell
  chat.jsonl                      the conversation with the supervisor
  chat-trace.jsonl                the chat agent's trace (+ .live)
  chat-settlements.jsonl          the chat agent's journal
  dashboard-trace.jsonl           the dashboard's own trace (+ .live)
  dashboard-settlements.jsonl     the dashboard's own journal
  cases.jsonl                     model-test cases, seeded from a trace
  model-test-trace.jsonl          the model-test run's trace
  model-test.jsonl                the model-test run's journal
```

**Every trace-and-journal pair above is one `JsonlTrace`.** The class takes two paths and a marker
string, in that order; the watcher, the chat agent, the dashboard and the model-test runner each
construct one with their own file names and pass their own word (`overwatch`, `chat`, `dashboard`,
`model-test`) where a prove passes the marker key. Nothing about the format changes. The model-test
runner is the one whose journal is not called `*-settlements.jsonl` — it is `model-test.jsonl`, and
it is the same file in the same shape.

---

## One directory per marker, not per worker

**Parallel provers do not share a file.** Appending from four processes looks safe — `O_APPEND` makes
the offset update atomic — but a line here can be sixty kilobytes of prompt, and a write that large
is not one syscall. Two workers interleave mid-line and *both* records are lost, in a corpus whose
whole purpose is to be read later.

**The directory is named for the marker and not for the worker.** The pool is a pool, not a
partition: it hands the next marker to whichever prover is free, so a worker index names nothing a
reader wants and changes from run to run. A reader looking for a marker finds it by what it proved.

**A reader assembles the run by concatenation.** For any file at the root, the dashboard reads the
root copy and then every `m/<id>/<same basename>`, directories sorted by name:

```java
List<String> lines(Path file)   // read(file) ++ for each dir in sorted(m/): read(dir/file.name)
```

Absent is not an error — a run that has settled nothing yet is the normal first state. Blank lines
are dropped on the way through, because `/api/settlements`, `/api/trace` and `/api/feedback` serve
the result as `"[" + join(",", lines) + "]"` and one blank line would make that invalid JSON.

**Concatenating four lanes gives four ordered runs, not one.** Whatever wants a single story sorts by
`at` afterwards; that is what the stamp is for.

**Grouping is by key, keeping the last.** Both journals and both readers rely on this: settlements
are appended per stage, so the last line naming a `suspicion_key` is that marker's current state.

### The slug

```
id = key after the last '/'  →  every char outside [A-Za-z0-9._-] replaced with '_'  →  first 80 chars
```

Java (`Supervisor.slug`) and shell (`entrypoint.sh`'s `slug()`, a `sed` and a `cut -c1-80`) implement
this separately and **must agree exactly**. The shell names the lane directory and the worktree; Java
finds the lane again to restart, postpone or summarise it. The worktree is `tree-<id>`, and that
string is also how the supervisor kills a prove (`pkill -f "tree-<id> "`).

---

## Appended, overwritten, moved, deleted

**Everything that is evidence is appended and never rewritten.** Every trace, every settlements
journal (`settlements.jsonl` at the root and in each lane, `overwatch-settlements.jsonl`,
`chat-settlements.jsonl`, `dashboard-settlements.jsonl`, and `model-test.jsonl`, which is one under
another name), `feedback.jsonl`, `restarts.jsonl`, `overwatch.jsonl`, `chat.jsonl` and `cases.jsonl`
are opened `CREATE, APPEND` and written one line at a time.

**A prove appends, which is why a retry may not reuse a lane.** Retrying on top of the old trace
makes one prove that changed its mind — two reproducers, two verdicts, no line between them — rather
than two attempts with a line between them. So before a marker is proved again the lane is *moved*:

| Who | Where it goes | Counted by |
| --- | --- | --- |
| pool, prove ended without settling | `dead/<id>.attempt-N` | `Pace.tries` — the pool's allowance |
| supervisor or a person, restart | `dead/<id>.restart-N` | `restarts.jsonl` — the agent's allowance |
| pool, marker postponed then taken again | `dead/<id>.before-postponing` | neither |

`N` is the existing count plus one, so the first archive of either kind is `attempt-1` / `restart-1`.
`before-postponing` carries no number.

**An archive suffix must be lower-case, and that is load-bearing.** A directory in `dead/` belongs to
marker `<id>` only if it starts with `<id>.` *and* the rest matches `[a-z][a-z0-9-]*`. Prefix alone
is not enough, because a checker name can be a prefix of another: `TAINTED_PTR` matches
`TAINTED_PTR.COOKIE.abandoned`, so one marker would absorb another's history and report time it never
spent. Every checker segment is upper-case; every suffix here is not.

**Two matchers over `dead/`, and they must stay two.** "How many attempts has this marker had" and
"how much time has it cost" match every suffix (`[a-z][a-z0-9-]*`, above). "How many times has the
POOL given up on it" — the count that decides whether the marker is ever handed out again — matches
only `<id>\.attempt-[0-9]+`. A supervisor restart and a postponement are somebody choosing to spend
another go on this marker; a pool retry is the marker having failed on its own. Counting them
together let two supervisor restarts exhaust the pool's allowance for a marker the pool had tried
once, and the marker went quiet for the rest of the run with nothing saying why.

**Nothing that is evidence is overwritten.** What is overwritten is the live file (below),
`summary.txt` (written once per lane), and the inputs a person edits: `jdk`, `workers`, `model`,
`prompts/<agent>.txt`, `git-credentials`, `source.zip`. `markers.txt` is *replaced*, and the queue it
replaced is kept as `markers-before-<millis>.txt`.

**Nothing that is evidence is deleted either.** Deletion is confined to a claim, a `postponed/<id>`
flag, the chat's live file once its answer is recorded properly, and a setting a person reverts
(`model`, `prompts/<agent>.txt`, `git-credentials`, `source.zip`). `Supervisor` also deletes the
lane directory on a restart — *after* moving it to `dead/`, because the record of the attempt must
outlive the attempt. **The archive must not be under `m/`.** It used to be kept beside the live lanes
as `m/<id>.restart-1`, and the pool decides whether a marker still needs proving by grepping every
`m/*/settlements.jsonl` for its key — so the kept record answered on the dead prove's behalf: the
claim was released, the marker was skipped, and the restart did nothing at all while reporting that
it had. A supervisor whose one action is silently a no-op is worse than one with no actions.

---

## The trace

One JSON object per line, all values quoted strings, keys written raw and never escaped. Every row
carries the same three fields first, in this order:

```json
{"at":"1754812345678","marker":"<key or the writer's own word>","kind":"<kind>", …}
```

**`at` is on every event, or the record can say what happened and never how long it took** — and "how
long" is half of what anyone reads a trace for. It is `System.currentTimeMillis()` as a decimal
string. `Pace` reads the first and last `at` in a lane to get its duration; a row with no parseable
`at` is skipped rather than fatal.

**`marker` is the writer's own, not the argument.** `settled`, `failed`, `progress` and `priced` all
take a marker key as a parameter, but that parameter never reaches the trace row: `settled`, `failed`
and `progress` pass it on to the settlement journal, and `priced` does not use it at all. The
`marker` field is whatever the `JsonlTrace` was constructed with.

### Every event kind

| kind | fields after the envelope | written by |
| --- | --- | --- |
| `asked` | `agent`, `prompt`, `reply` | every agent call, once, after it returns |
| `thought` | `agent`, `text` | the streaming client, at most once per model call |
| `tool` | `agent`, `tool`, `arguments`, `result` | the tool wrapper, per call, including throws |
| `built` | `phase`, `infra`, `passed`, `summary` | `Prove`, per build |
| `settled` | `state`, `because`, `red`, `green` | `Prove`, once |
| `failed` | `cause`, `stack` | `Prove` and any loop that catches |
| `priced` | `minutes`, `itemisation` | `Prove`, once, on every path |
| `progress` | `note` | anything that wants to be watched |
| `system` | `prompt` | the runtime, when it assembles a system prompt |

`phase` on a `built` row is `red` or `green`, so a reader of a settlement can tell which build they
are looking at.

- `infra`, `passed`, `red`, `green` are the strings `"true"`/`"false"` — **quoted**, unlike the
  booleans in a settlement row. A rebuilder must not "fix" one to match the other; readers of each
  file were written against what that file holds.
- `agent` is the bare agent name (`reproducer`, `fix-critic`, `verdict`, …). The runtime is given
  `"agent:" + name` for its own purposes; that string does not reach the trace.
- `prompt` on an `asked` row is the whole thing: the agent's system prompt, then `\n\n---\n\n`, then
  the task. **In full, both of them.** Truncating here would save disk and cost the corpus: prompt
  tuning replays a recorded `(prompt, reply)` pair and scores the reply, so a trace that abbreviates
  either one is a trace nothing can be trained from.
- `reply` is `""` when the model returned null. An agent that answers with tool calls and no content
  returns null, and a map that rejects nulls turns that into a `NullPointerException` carrying no
  message — recorded as an infra failure for a marker whose model was working perfectly. Nineteen
  proves died that way. **An empty answer is a judgement ("it had nothing to say") and is recorded as
  one.**
- `stack` on a `failed` row is `cause`, then one `\n  at <frame>` per stack frame, then
  `\ncaused by <cause>` if there is one. `"NullPointerException: null"` names no line and no cause,
  and a record that cannot locate its own failure sends a reader to a container log that does not
  have it.
- `thought` is written when a call returns and only if there is reasoning to write, so a `thought`
  row is absent rather than empty. **A call that never returns must still leave what it was saying**:
  when the streaming client gives up on silence or hits the ceiling it writes one more `thought`,
  `[<why>; this is the reasoning as far as it got]\n\n<the text>`. A thought recorded only on return
  meant that eight of the ten proves that died in one run died exactly there: the record held the
  exception and not one word of what the model had been generating for thirty minutes, which makes
  "it looped" a guess.
- `tool` payloads come from the executor, in full; a tool that throws is recorded as
  `threw <SimpleName>: <message>` and then rethrown, because the agent must still see its own failure
  and a reader must still see that it happened. The library's own listener truncates everything it
  reports and is therefore recorded from only for one thing: reading `"path"` out of a `write_file`
  call, which is how the program knows which test class to run.
- **The written test's name is taken from the `write_file` path, and any test source root counts.**
  The path is remembered only when it contains `src/test/`, `src/it/` or `src/integrationTest/` *and*
  ends in `Test.java`; what is kept is the basename without `.java`. Accepting only `src/test/`
  rejected a project that puts integration tests under `src/it`, so the runner was told no test had
  been named and reported infra for a file that was sitting on disk.

**One object is both the trace and the flow listener, on purpose.** The runtime takes a listener for
the tool calls it makes on an agent's behalf and `Prove` reports the stages and the builds; handing
both to the same instance is what puts a run in one file in one order. Two sinks would put the tool
calls in one place and the reasons in another, and the interesting question is always which tool call
led to which answer.

**A trace that cannot be written must not end a prove that is otherwise fine.** The write is wrapped;
an `IOException` prints `trace: <message>` to stderr and the prove continues — but it *does* print,
because a silently absent trace is worse than a loud one.

---

## The settlements journal

The column names are a dashboard's, so a viewer needs no mapping layer and every filter and counter
written against it keeps working. Fourteen fields, in this order:

```json
{"suspicion_key":"…","repo":"…","file":"…","svace_checker":"…","title":"…",
 "state":"…","verdict_kind":"…","verdict_text":"…",
 "red_verified":true,"green_verified":false,
 "test_path":"","test_code":"","fix_diff":"","infra_reason":""}
```

- `red_verified` and `green_verified` are **unquoted JSON booleans**. Everything else is a quoted
  string.
- `verdict_kind` is a copy of `state`.
- `title` is `checker + " at " + basename(file)`.
- `repo`, `file` and `svace_checker` are split out of the key: field 0, field 1, and everything after
  the last `|`.
- `test_path`, `test_code`, `fix_diff` and `infra_reason` are **always empty**: `Settlement.note` is
  the only place in the program where a settlement is ever constructed, and it passes `""` for all
  four. The empty ones are the honest part — they come from machinery this program does not have, and
  leaving them blank says so, where inventing values would put numbers on a dashboard that nothing
  computed. (The dashboard's schema has further columns still — `value_score`, `versions`, `jdk` —
  that this program does not emit at all.)

**A line per stage, not per prove.** A prove takes tens of minutes and a record written only at the
end leaves a reader with nothing to look at for all of it. Three trace methods also journal:

| trace call | journal `state` | journal `verdict_text` |
| --- | --- | --- |
| `progress(key, note)` | `proving` | the note |
| `failed(key, cause)` | `infra` | `<SimpleName>: <message>` |
| `settled(key, state, because, red, green)` | the state | the whole account |

`red` and `green` are carried from what the runner reported, never from what the disposition implies.
`reproduced` and `verified/pr-ready` both mean red failed, and only one of them means green passed —
recording these as anything other than what the runner said puts a claim in the record that nobody
made.

**Only a `settled` row carries meaningful flags.** The two-argument `note` used by `progress` and
`failed` writes `red_verified:false, green_verified:false` unconditionally, whatever the run had
already observed. A reader must take red and green from the row whose `state` is a disposition, not
from the last row for the key.

The account handed to `settled` opens with the disposition on its own line
(`<disposition>\n\n<argument>`), which is where `state` comes from — so `verdict_text` repeats the
state as its first line by construction.

**The seven dispositions.** These, and only these, mean a marker has an answer:

```
false-positive  by-design  unprovable  reproduced  needs-review  verified/pr-ready  verified/pr-rejected
```

The pool greps for exactly that set:

```
'"state":"(false-positive|by-design|unprovable|reproduced|needs-review|verified/pr-ready|verified/pr-rejected)"'
```

**Everything else — `proving`, `infra`, an empty file, no file — is a prove that did not finish, and
a marker that did not finish goes back in the queue.** This used to be written the other way round,
as "anything that is not `proving`", which made `infra` — the state a prove writes when it *throws* —
count as an answer. A prove killed by the tool ceiling therefore retired its own marker and nothing
revisited it. Readers in Java state the same rule as a list of negatives — `Pace`, `Dashboard` and
`Interpreter` as `!blank && !proving && !infra && !queued`, `Overwatch` as
`!proving && !infra && !FAILED && !queued` — for the same reason: a disposition added to `Prove` and
forgotten here would read as unsettled forever, whereas a new not-an-answer state reads as settled
once and is noticed.

**Two states exist only in readers.** `queued` and `interrupted` are never written by anything. The
dashboard synthesises `queued` for a marker in `markers.txt` with no settlement row, and
`interrupted` for a row that says `proving` while no claim directory exists for its slug — a
settlement row saying `proving` only means a prove once started, and the row survives a container
replaced under it, so every interrupted marker would otherwise read as busy forever. `queued` is
nevertheless named in every negative list above, because a reader that met one on disk must not read
it as an answer; `interrupted` never reaches a file, so it never has to be.

**A journal that cannot be written must not end a prove, and must never replace the failure it was
called to record.** `Settlement.note` catches `IOException` *and* `RuntimeException` and prints
`could not journal <state>: <e>` to stderr. On the failure path this matters twice over: the call
that reports the crash must not itself crash on top of it.

**The pool matches a marker by its literal key** (`grep -F '"suspicion_key":"<marker>"'`) against the
raw line from `markers.txt`. A key containing a character that the escaper rewrites (`"`, `\`, tab,
newline) would be written escaped and would never match, and the marker would be proved forever.
(unverified — no marker in use contains one, and nothing in the code guards it.)

---

## The live file

**The only file a `Trace` overwrites rather than appends to, and it is not evidence.** Everything
else a trace writes is a line added to a file; this one is replaced whole, every time.

```
<path of the trace>.live       e.g. m/<id>/trace.jsonl.live, chat-trace.jsonl.live
```

```
<agent name>\n
<millis when it was written>\n
<the whole answer so far, raw>
```

Beside the trace and named after it, so a reader finding `trace.jsonl.live` knows exactly which prove
it belongs to and that it is not part of the record.

- **Written at most every 700 ms.** A token arrives every few milliseconds; slower than a token,
  faster than a reader blinks.
- **Replaced wholesale, never appended.** It holds one answer in progress.
- **Its write failures are swallowed in silence.** A view nobody can write is a view nobody sees, and
  it must never cost the prove — it is the only *writer* here whose `IOException` is discarded
  without even a line on stderr. Every other writer prints.
- **Nothing downstream may read a settlement out of it.** Everything else is written when a call
  *ends*, which is right for a record and useless for watching: a reasoning turn can run for minutes
  and for those minutes the trace shows the agent doing nothing at all. This is the only method that
  fires mid-call.
- **A reader must show it only for a prove that is still running.** When the call ends, `thought` and
  `asked` write the real thing and this becomes a stale copy of it; the dashboard therefore checks
  the lane's `settlements.jsonl` first and refuses to render a live panel for a settled lane. A panel
  that went on showing it would be a live view that is quietly a museum. The chat path goes further
  and deletes the file once the answer is recorded properly.
- **Readers parse it defensively.** Fewer than two newlines means "nothing yet"; a read that throws
  because the file is being rewritten underneath is ignored and retried on the next poll (2 s). The
  page shows the last 4 000 characters — the *end*, because opening on the beginning would show the
  same paragraph for four minutes.

---

## Escaping, and the reader that undoes it

**One escaper for every file this program writes.** `Settlement.escape` is the only one, and the
trace, the feedback corpus, the seeded cases and the three hand-built line writers (`restarts.jsonl`,
`overwatch.jsonl`, `chat.jsonl`) all call it.

```
"   → \"        \n  → \n
\   → \\        \r  → \r
                \t  → \t
any c < 0x20    → \u%04x (lower-case hex)
everything else passes through unchanged (including non-ASCII, which is written as UTF-8)
```

**The serialisers are hand-rolled, and that is a decision.** A library is on the classpath. The
settlement is the *last* thing written before the process exits, and a serialiser that throws on an
unexpected value would lose the whole prove — hours of model calls and two Maven builds — rather than
the field it could not render.

**Reading is a scan, not a parse.** `Json.field(line, key)` returns one field, or `""`. Staying a scan
is deliberate: a malformed line costs the field, where a parser would refuse the whole file and take a
dashboard or a test run down with it. A truncated line costs the field it was cut in and nothing else.

There are **two copies of this scan** — `Json.field` and `Dashboard.field` — and they are identical
line for line. They must stay identical: the unquoted-value bug below had to be fixed in both.

**A value is not always quoted.** The scan skips to the colon, skips spaces, and if the next character
is not `"` it takes everything up to the next `,` or `}`. Without that, scanning for the next quote
skipped straight over `true` and found the *following key's* quote instead — which is why
`red_verified` read as empty for every marker that had genuinely gone red, and the semaphore on the
page never lit while the data behind it was correct all along. `Feedback` writes an unquoted int for
the same reason it matters.

**The reader undoes `\n`, `\t`, `\r`, and treats any other backslash pair as the second character.**
It does **not** decode `\uXXXX`; a control character written by the escaper does not come back. Nothing
in the program depends on one surviving.

Because every value has its quotes escaped, the scan for `"<key>":` cannot match inside a value.

---

## The feedback corpus

The one thing the trace cannot hold is whether a reply was any good. Nothing in the run knows: the
build settles whether a test compiles and reproduces, not whether it was the test a reviewer wanted.
That judgement comes from a person, and `feedback.jsonl` is where it lands — one line per rated
answer, appended by the dashboard's `/feedback` handler from a plain form and a 303 back to the page
the reader was on.

```json
{"marker":"…","agent":"reproducer","event":7,"note":"…","at":"1754812345678",
 "prompt":"…","reply":"…"}
```

- `event` is an **unquoted int**: the position of the `asked` entry in the event list the page was
  rendering, sorted by `at`. That list is one marker's events on `/marker?k=…` and every marker's on
  `/trace`, so the number is provenance rather than a join key — which costs nothing, because the
  prompt and the reply are carried in full and nothing has to go back to the trace to use the row.
- **It points at one event, not at a marker.** Prompt tuning optimises ONE agent's prompt, so a
  complaint filed against a whole prove cannot be attributed: a marker that settled badly may have
  had a fine reproducer and a careless skeptic.
- **`prompt` and `reply` are carried in full, not referenced.** A row here is a complete training
  example, so a tuning run needs this file and nothing else. Keeping only an index would make the
  corpus depend on a trace that is rotated, regenerated from a re-run, or simply larger than anyone
  wants to ship — and a training set whose inputs live somewhere else is one bad path away from being
  unlabelled. It costs duplication, and duplication is the cheap half of that trade.
- A malformed post costs a row, never the page: the writer catches and prints `feedback: <message>`.

---

## The run-level files

**`restarts.jsonl`** — written by `Supervisor`, one line per restart, and it is the *count* that
enforces the limit of two per marker:

```json
{"at":"…","id":"<slug>","marker":"<key>","attempt":"2","killed":"true","why":"…"}
```

All values quoted, including `attempt` and `killed`. Only `marker` and `why` go through the escaper;
`id` is already a slug and the other two are a number and a boolean rendered as text. The count is
`id` matched exactly, so a rebuilder must write the slug here and not the key.

**The count is read from the file rather than
held in memory, because the supervisor is restarted too.** If the file cannot be read the reader
returns the limit, not zero: a log that cannot be read is not a licence, and letting an unreadable
file lift the limit is the one direction this must not fail in. Both `IOException` and
`UncheckedIOException` are caught — `Files.lines` opens eagerly and reads lazily, so an unreadable
file throws from inside `count()`, and catching only the checked one let an unreadable log read as
zero restarts and lift the limit entirely.

**`overwatch.jsonl`** — one line per finding the watcher raised and what its critic made of it:

```json
{"at":"…","verdict":"holds|refuted|unjudged","finding":"…","judgement":"…"}
```

`verdict` is `refuted` if the judgement contains that word, `holds` otherwise, and **`unjudged` when
the critic answered nothing** — a finding the critic never judges still reaches the record, so an
unreachable critic cannot suppress a warning.

**`chat.jsonl`** — the conversation, oldest first:

```json
{"at":"…","who":"you|supervisor","text":"…"}
```

**The question is written down before the answer is attempted**, so a dashboard that dies mid-reply
leaves a record of what was asked rather than nothing at all; the page can then see a question with no
answer under it and say so. Every way the answer can fail — including a thrown `Error` — becomes a
`supervisor` turn rather than a missing one. A line with no `who` is skipped; an unreadable file shows
as an empty conversation rather than an error page.

**`m/<id>/summary.txt`** — the interpreter's account of one lane, written once, only for a lane that
has settled, and only if a second agent checked it. Two lengths in one file, split where it is
written rather than where it is read:

```
<one short sentence for the table>
<blank line>
<the full account>
```

Readers split on the first blank line — first paragraph short, the rest full, and a file with no
blank line in it is both. Parsing here means the dashboard never sees the `SHORT:` label the critic
was asked for, so a critic that forgets the shape cannot leak an instruction onto the page.

The writer finds that label by scanning the critic's answer **from the last line backwards**, not the
first: a model asked for a shape sometimes delivers it twice — once as a rehearsal and once for real
— and splitting on the first occurrence put the second copy inside the long form, so the account
opened by repeating the line the reader had just read in the table. Absent any `SHORT:`, the short
form is everything up to the first `". "`. A paragraph of the full account identical to the short
form is dropped for the same reason.

**Silence withholds**: if the critic returns nothing, no file is written and the table falls back to
the verdict's own words, which are at least demonstrably somebody's rather than an account nothing
checked.

**`m/<id>/slice.log`** — the shell's, not Java's: the `=== [n] <marker>` header, any worktree failure,
and the prove's stdout and stderr appended.

**`markers.txt`** — the queue, `repo|file|line|checker`, one per line, taken in the order given. It is
validated in full before it replaces anything, and the previous queue is moved to
`markers-before-<millis>.txt` rather than deleted: a settled marker is matched by its key, so replacing
the queue does not invalidate the record — but it does make the old queue the only explanation for
results that name markers the new one has never heard of.

**`claims/<id>/` is a directory, and the claim is the `mkdir`.** It is the one filesystem operation
that is atomic and tells the loser it lost, so two provers cannot take the same marker and no lock
file is left behind by a process that died holding it. **The claim is released when the prove ends.**
A claim that outlives its prove silently repeals the disposition rule above: `settled` was taught to
say NO for a marker that ended in `infra` precisely so the pool would take it again, and then
`mkdir claims/$id || continue` skipped it because the claim from the dead attempt was still there —
every marker whose prove threw was retired by the very gate that exists to stop double-proving.
The converse holds for readers: **claimed is not running.** A release is an `rm -rf` after a JVM
exits and the watcher reads on a timer, so the two overlap; reading a claim as proof of a running
prove had the watcher reporting settled markers as stalled for a thousand minutes, twice, with its
critic correctly refuting the finding both times.

**`postponed/<id>`** — a file whose content is the reason and whose *existence* is the flag. Deleting
it is how a marker is resumed; there is nothing else to resume, because a prove is a JVM
mid-conversation with a model and nothing persists it. What a postponement saves is the slot.

**`jdk`** — one line, one of `25 21 17 11 8`. Anything else, or unreadable, reads as `25`. `25` means
"leave `JAVA_HOME` alone"; the others mean `/opt/java/<n>`.

**`workers`** — one line, an integer, clamped to `1..16` on both sides. **A width that cannot be read
is not zero**: Java reads an unreadable or absent file as `4`, so a typo in a one-line file cannot
stop the pool dead. The shell clamps independently of Java, and neither trusts the other — a file
edited by hand or left behind by an older version must not be able to start ninety JVMs against one
GPU. (The shell strips everything but digits, falls back to the width `slice` was invoked with —
itself defaulting to `4` — then forces anything below 1 to `4` and anything above 16 to `16`. **It
re-reads the file every time round the loop**, so a run can be widened while it is going; the width
used to be an argument fixed for the life of the pool, and changing it meant killing the pool, which
orphans every claim in flight.)

**`prompts/<agent>.txt`** — a full replacement for one agent's built-in prompt. There is no merge: a
prompt half from the code and half from a file is a prompt nobody can read in one place. The filename
is flattened from a form field — everything outside `[A-Za-z0-9._-]` dropped, leading dots dropped,
blank becomes `unnamed` — so `../../etc/passwd` becomes a long ugly filename in this directory and
cannot become a path out of it. **A prompt that cannot be read is not an empty prompt**: an
unreadable override falls back to the built-in, because the alternative is an agent running with no
instructions at all and answering something.

**`model`** — the tuning file, `key=value` per line, `rw-------`:

```
model=…
base_url=…
temperature=0
max_tokens=0
patience_minutes=4
ceiling_minutes=240
api_key=…            (never in the map the page renders and echoes back)
```

Every value is clamped on the way *out*, not on the way in, so a file edited by hand cannot put the
pipeline somewhere the code does not expect:

| key | clamp | absent or unparseable falls back to |
| --- | --- | --- |
| `model` | — | `$QWEN_MODEL`, else blank |
| `base_url` | — | `$QWEN_BASE_URL`, else blank |
| `temperature` | `0 … 2` | `0.0` |
| `max_tokens` | `0 … 200000` | `0` (no cap) |
| `patience_minutes` | `1 … 120` | `4` |
| `ceiling_minutes` | `1 … 1440` | `240` |
| `api_key` | — | `$QWEN_API_KEY`, else blank |

**`patience_minutes` and `ceiling_minutes` measure different things and must stay separate names.**
Patience is how long a call may go with *nothing on the wire*; the ceiling is how long a call may go
on *answering*. Confusing the two is what killed eighty-six live proves once, which is why they are
two values with two names and the page says which is which.

**A setting that cannot be read is not an empty setting**: an unreadable file leaves every caller on
its environment variable or its constant. A blank `api_key` in a submitted form **leaves the stored
one alone** rather than clearing it — a form posted with the field emptied by a browser must not
silently unset the credential and leave every agent talking to an endpoint that refuses them.
Clearing it deliberately is a separate field, `forget_key=1`; reverting every setting deletes the
file.

**`git-credentials`** — git's own store, `rw-------`, one line:

```
https://<x-access-token|oauth2>:<token>@<host>
```

`oauth2` when the host contains `gitlab`, `x-access-token` otherwise. **It is here and not in a clone
URL** because a token pasted into `https://token@host/repo` is in the clone command, so it is in the
process list every prover can read, in the slice log, and in any error git prints.

**`source.zip`** — an uploaded tree. Refused unless it begins with the bytes `PK`. When it is present
nothing is cloned at all.

**`spec/`** — this specification, copied from the image on every container start, old copy removed
first so a deleted chapter does not linger as one the watcher still cites. Every agent's file tools
are rooted at the results directory, so a prompt naming `/opt/agent/spec` would name a file none of
them can read and would teach the model that its tools are broken. The prompts name `spec/` relative
to the root the agents have.

---

## The two files under the root that are not part of the record

`model` and `git-credentials` sit on the same volume the watchers read, and the watchers are rooted at
`/results` because that is where the record is. **They are refused at the tool layer, for every agent
and for every tool** — not only `read_file`. Any call whose arguments name either as a whole path
segment is answered, without running:

```
REFUSED: `<name>` holds a credential, not part of the record. Everything else under this
directory is readable. If you were asked for the API key or a git token, say that it is
deliberately unreadable from here and that the settings page is where it is handled.
```

**A whole path segment, not a substring**, because `model` is an ordinary word and `Model.java` or
`m/ModelTest…` must not trip it. The match is `(^|[/\"'\s])<name>([\"'\s,}]|$)` over the raw
arguments.

And every tool's result, whatever produced it, is rewritten:

```
(?i)(api[_-]?key\s*[=:]\s*)\S+          →  $1(hidden)
(?i)([a-z][a-z0-9+.-]*://)[^/@\s:]+:[^/@\s]+@   →  $1(hidden)@
```

Two layers because there are two ways to reach a file: name it, or find it. `grep` finds it without
naming it and would return the matching line.

At the tool and not in a prompt, because a prompt is a request and this has to be a fact — it has to
hold for the judges and the watcher as well as for the agent somebody typed the question at. A chat
makes it a question anybody can type: "what is in the model settings" is one line, and the answer
would put the API key into `chat.jsonl` and onto a page. **A mask that a second route walks around is
not a mask.**

---

## Failure directions, collected

A rebuilder who gets one of these backwards will not see it fail.

| Thing | If it is absent or unreadable | Why that direction |
| --- | --- | --- |
| `restarts.jsonl` | reads as **the limit**, not zero | an unreadable log must not lift a bound |
| `dead/` listing (`tries`) | reads as **MAX_VALUE**, not zero | an unreadable archive is not a clean slate |
| `workers` | reads as **4** | a typo must not stop the pool |
| `jdk` | reads as **25** | the image's own JDK |
| `prompts/<agent>.txt` | reads as **the built-in** | never an agent with no instructions |
| `model` | reads as **the environment / the constants** | never an endpoint pointed at nothing |
| a lane's `settlements.jsonl` | reads as **not settled** | an unsettled marker goes back in the queue |
| an undeletable claim directory | reads as **still claimed** | proved once late beats proved twice at once |
| the live file | reads as **nothing yet** | it is a view; it may never decide anything |
| a trace write that throws | **prove continues**, stderr line | a lost trace must not cost a settlement |
| a journal write that throws | **prove continues**, stderr line | and must not replace the failure it records |
| the interpreter's critic answering nothing | **no `summary.txt`** | silence withholds; the record is the fallback |
| the watcher's critic answering nothing | **finding recorded `unjudged`** | silence must not suppress a warning |
| the watcher's critic ordering nothing | **no restart** | silence must not kill anything either |
