# 14. The dashboard

One class, `Dashboard.java`, on `com.sun.net.httpserver`. No template engine, no framework, no
database, no static assets — every page is a string built per request from the files on disk.

Vocabulary used below. A **marker** is one static-analysis complaint, keyed `repo|file|line|checker`.
A **prove** is one JVM taking one marker through the chain; it appends to its own
`results/m/<slug>/trace.jsonl` and finishes by writing a **settlement** whose `state` (the marker's
**disposition**) is the answer. The seven final dispositions are `false-positive`, `by-design`,
`unprovable`, `reproduced`, `needs-review`, `verified/pr-ready`, `verified/pr-rejected`; `proving`,
`infra` and `queued` are the unfinished ones. **RED** is the build that must fail before any patch;
**GREEN** is the same test passing after it. A **claim** is a directory a prover creates before it
works. A **lane** is one marker's whole journey — every build, every agent, every loop back — and
the interpreter is the agent that reads a settled lane and writes `m/<slug>/summary.txt`. A
**finding** is something the supervisor said is wrong with the pipeline, and its **verdict** —
`holds`, `refuted`, `unjudged` — is what its critic made of it. A **fold** is a `<details>` element.
A **fragment** is a partial page the live script asks for and appends; a **cursor** is the count of
events a page already holds, which is how it asks for only the rest.

```
java -cp … tech.mikhailov.fsm.agent.Dashboard [results-dir | settlements.jsonl] [port]
```

`args[0]` defaults to `results`; if it does not end in `.jsonl` the code resolves
`settlements.jsonl` under it. Everything else is a sibling of that file: `trace.jsonl`,
`feedback.jsonl`, `overwatch.jsonl`, `restarts.jsonl`, `overwatch-trace.jsonl`,
`overwatch-settlements.jsonl`, `markers.txt`, `severities.tsv`, `chat.jsonl`,
`chat-trace.jsonl.live`, `claims/`, `m/`, and the two the dashboard's own agent constructions write,
`dashboard-trace.jsonl` and `dashboard-settlements.jsonl`. (`overwatch-settlements.jsonl` is passed
to the `/overwatch` renderer and never read; the findings, the restarts and the supervisor trace are
the three it uses.) Port defaults to `8087`. `entrypoint.sh` runs it as
`Dashboard "$RESULTS/settlements.jsonl" "${PORT:-8087}"` under both the `serve` and `dashboard`
arguments.

---

## What must be true

- **It reads the files on every request and holds nothing.** A prove appends; a refresh shows it.
  There is no cache, so there is nothing that can disagree with the record. The single exception is
  `BUILT_INS`, the code's own prompts, collected once at start-up because they are the code and cannot
  change while the process runs.
- **A missing or unreadable file is a normal state, never an error.** Every read returns an empty
  list, and the page says "nothing yet".
- **Every handler is wrapped**, and an exception becomes a 500 page carrying the stack.
- **Every request gets its own thread**, because one route blocks for half an hour.
- **Text is escaped exactly once, by the code that emits it**, never by the caller.
- **A page that does not declare a cursor does not accept fragments.**
- **What the reader opened stays open and where they were scrolled stays put**, across every live
  update and every reload.
- **A live panel is shown only for a prove that is still running.** When one ends, the panel says so
  instead of showing the last thing it said.
- **An unjudged finding is shown, and shown as unjudged.** A critic that could not be reached must
  not be able to suppress a warning.
- **The dashboard authenticates nobody.** See *The one auth note*.

---

## Process shape

**One thread per request, from a cached pool of daemon threads named `dashboard`.** With the default
executor every handler runs on the dispatcher thread, so a single reader holding `/events` open stops
the server answering anything at all — the pages, the API and the next reader's stream included.
Cached rather than fixed: streams are idle almost always, and a fixed pool of *n* stops serving at
the *n+1*th reader.

**Every route is registered through the same helper, which wraps the handler.**

```java
route(server, path, handler)  →  server.createContext(path, guarded(handler))
```

`guarded` catches `IOException | RuntimeException | Error` and answers 500 with the stylesheet, a
header reading *this page broke*, the request URI and the full stack in a `<pre>`. This exists
because `HttpServer` answers an exception from a handler by closing the connection: no status, no
body, no log line. An empty reply in twenty milliseconds reads exactly like a page too big to build,
and sent the author looking at response sizes and memory limits for an
`UnsupportedOperationException` thrown by sorting an immutable list. **A failure that cannot locate
itself sends whoever is reading to a container log that does not have it.**

**Contexts are prefixes and `/` is the fallback.** Anything not matched by a longer context is
answered by the index. `/live` was once created outside the `route` helper and did not take effect;
every request for it fell through to `/`, and the poller wrote a copy of the entire index page —
header, banner, table — inside the index page. **Register every path the same way.**

`send(exchange, type, body)` sets `Content-Type`, sends 200 with the exact UTF-8 byte length, and
closes.

---

## The routes

| Path | Method | Renders |
|---|---|---|
| `/` | GET | The index: warnings banner, progress, counts, one row per marker |
| `/marker` | GET | One marker, by tab: `k` = key, `a` = tab, `fold=1`, `from=N` |
| `/trace` | GET | Every event of every marker, oldest first |
| `/overwatch` | GET | The supervisor: findings (default), `a=overwatch`, `a=overwatch-critic`, `a=trace` |
| `/chat` | GET/POST | The conversation with the supervisor; POST asks and 303s to `/chat?said=…` |
| `/settings` | GET/POST | `a=` prompts (default), `run`, `model`, `subject`; POST saves |
| `/prompts` | GET | 301 → `/settings`. A shipped URL, cheap to keep and rude to break |
| `/reprove` | POST | `Supervisor.reprove(marker, why)`, 303 → `/marker?k=…&a=prompts` |
| `/feedback` | POST | Appends one labelled example, 303 → the `back` field (or `/`) |
| `/live` | GET | The live panel for `k`, as an HTML fragment for the poller |
| `/events` | GET | Server-sent events: the two counts, when they move |
| `/api/settlements` | GET | `[` + the settlement lines, joined by `,` + `]` |
| `/api/trace` | GET | The same, over `trace.jsonl` |
| `/api/feedback` | GET | The same, over `feedback.jsonl` — the corpus, ready to train on |

The three `/api/*` routes do not parse anything: they concatenate the raw JSONL lines with commas and
wrap them in brackets, `application/json`. A malformed line goes out as it is.

`/marker?a=…` takes one of: empty (the summary), any agent name in `Agents.CHAIN`, `live`, `prompts`,
`trace`. Anything else falls into the agent-tab renderer and reports that that agent has not run for
this marker — an unknown tab is an empty tab, never an error.

---

## Every page's skeleton

**There is no doctype, `<html>`, `<head>` or `<body>` tag anywhere.** A page is:

```
<style>…CSS…</style>{LIVE script}{KEEP_OPEN script}
<header>
  <a class=gear href='/settings' title='settings'>⚙</a>
  <a class='gear ask' href='/chat' title='ask the supervisor'>✉</a>
  [<a class=crumb href='/'>← {esc(back)}</a>]
  <h1>{esc(title)}</h1><div class=sub>{sub}</div>
</header>
…content…
```

The browser synthesises `body`, which is what the live script writes into and what carries the
cursor. **`head()` escapes the title and does not escape the sub-line** — the sub-line is HTML by
contract, and callers that put a marker key or a state in it escape it themselves.

The gear and the envelope are on every page, in the same corner: both are "leave this page and do
something". **Back is a blue crumb above the title, not a tab.** As the last item of a grey tab row,
the one control every page has looked like another destination rather than the way out; and before
that it was a text link at the foot of the index, under 356 rows, which is a link nobody has.

---

## The index

Built from the queue (`markers.txt`), the settlements, the trace, the `claims/` directory,
`overwatch.jsonl` for the banner, `severities.tsv` for the first column and each lane's
`summary.txt` for its row. All of it on every request.

The settlement rows are folded into a map keyed by `suspicion_key`, **last line for a key wins** —
the record is append-only, so the newest row is the current one.

**State comes from the claim, not from the settlement row.** A settlement saying `proving` only means
a prove once started; the row survives a container that was replaced under it, and every interrupted
marker then reads as busy forever.

```
row says proving  +  claims/<slug> exists   → proving
row says proving  +  no claim                → interrupted
no row            +  claims/<slug> exists    → proving   (taken seconds ago)
no row            +  no claim                → queued
otherwise                                    → the row's state
```

`slug(key)` is everything after the last `/`, with `[^A-Za-z0-9._-]` replaced by `_`, cut to 80
characters. **It has to match `entrypoint.sh` and `Supervisor.slug` exactly: a claim this cannot find
reads as a marker nobody is working on.**

Above the table, in order: the findings banner, the progress bar, the counts, then the table.

- Progress: `settled/total`, percent, elapsed, and an ETA of `elapsed / settled * (total - settled)`.
  `total` is every marker known — queued plus settled; `settled` is every state that is **not**
  `proving`, `queued` or `interrupted`, so an interrupted marker counts against the run rather than
  for it. `elapsed` runs from the earliest `at` in the whole trace. With nothing settled the ETA is
  `—`. **The ETA is labelled "extrapolated" because it is honest only while the markers are alike,
  and they are not** — one the reproduce-doer declines costs a minute, one that goes red, green and two
  rounds with a skeptic costs twenty. Shown because a wrong estimate that converges beats none.
- Counts: one tile per state, in alphabetical order of the state name, plus a *human-equivalent* tile
  summing every `priced` event's `minutes` — shown only when that sum is above zero. An estimator
  that answered in prose contributes nothing rather than a guess.

The table is six columns, and **one row reads as one sentence, left to right**:

```
severity | marker | state | what happened | took | a person would have
```

- *severity* is reference data, joined from `severities.tsv` beside the settlements — tab-separated
  `basename<TAB>line<TAB>checker<TAB>severity`, looked up by `basename|line|checker`. The marker key
  carries no severity and adding a fifth field would change every key, re-proving the whole queue to
  display one word. It covered 282 of the markers of that analyser run; the other 74 are `src/it`
  and `src/test`, which it excluded, and **they get `—` rather than a guess: a table that prints
  Minor for everything it does not know about is worse than one that admits the gap.**
- *marker* is `basename:line`, linked to `/marker?k=<enc(key)>`, with the checker and the package
  directory under it (`src/main/java/` and `src/test/java/` stripped). File and checker come from the
  settlement row's `file` and `svace_checker` when there is a row, and from fields 2 and 4 of the key
  when there is not — **a marker the run has not reached yet still has to render**, and the key always
  carries them. The line number always comes from the key.
- *state* is a `<span class='s …'>` — except `proving`, which is a link to `?a=live`. **Every other
  word in that column is a conclusion and reads fine as text; that one is a question, and the answer
  is one page away.** Under it sits the semaphore.
- *what happened* is the settlement's `verdict_text`, inside a fold whose summary is the lane summary
  (the first paragraph of `m/<slug>/summary.txt`) if the interpreter wrote one and
  `firstSentence(verdict_text)` otherwise, with the flagged source lines above the argument. A marker
  with no `verdict_text` yet falls back to the last thing said about it in the trace — a `progress`
  note's `note`, a `settled` event's `because` or a `failed` event's `cause`, whichever came last —
  reduced to its first line that is neither empty nor `---`, cut to 150 characters, and rendered
  unfolded. Nothing at all in either place renders `—`. **Not shortened when it is a real argument: a
  reason cut at two hundred characters is a reason nobody can check.**
- *took* is wall-clock between that marker's first and last trace event, plus its event count.
- *a person would have* is the summed priced minutes.

### The semaphore

Two dots, from `red_verified` and `green_verified` on the settlement row.

```
lit    the build said so
dim    it was reached and did not happen — a RED that passed, a GREEN that failed
hollow it was never got to
```

`reachedRed` = the state is not `queued` and not `not-a-bug`; `reachedGreen` = red is `true`.
**Hollow is a different answer from "no": a marker the reproduce-doer declined never had a red to fail.**

### firstSentence

The closed fold's line, and it is not a truncation — the whole argument is one click away on the same
page. The naive first sentence was mostly not one: an argument opens by repeating the word it settled
on ("false-positive false-positive The static analyzer claims…") and then clears its throat ("Looking
at this issue: 1."), so two of every three rows summarised to nothing.

So, in order:

```
flat = why.strip()
         .replaceAll("[*`#>]", " ")      // markdown marks out
         .replaceAll("\\s+", " ")        // runs of whitespace collapsed
         .replaceAll("\\s+([:;,.])","$1")// space before :;,. removed
         .strip()
```

The last of those is not cosmetic: stripping the marks leaves `**The bug**: Line 91` reading as
`The bug : Line 91`, which looks like a typo in a column meant to be scanned.

Then strip leading repetitions (case-insensitive, `word + " "`, repeatedly) of any verdict word —
`false-positive`, `by-design`, `unprovable`, `reproduced`, `needs-review`, `verified/pr-ready`,
`verified/pr-rejected`, `make`, `reject`, `sound`, `redo`, `over-fit`, `regression-risk`,
`necessary`, `reducible` — split on `(?<=[.!?])\s+`, and take the first sentence that is at least 40
characters, does not end in `:`, and does not match `(?i)looking at .{0,40}`. Cap 240 with an
ellipsis. Where nothing qualifies the reader gets the flattened opening as it stands, capped the same
way.

### The findings banner

**The supervisor found all of it ten hours before anyone read it.** The runaway reproduce-doer, the
passing REDs and the whole `DM_DEFAULT_ENCODING` family were each reported, judged, marked `holds` —
and then rediscovered from scratch by a person who never opened `/overwatch`. **A warning nobody is
shown is a warning nobody has**, so it goes at the top of the page everybody already has open.

- Order: `holds`, then `unjudged`, then `refuted`; within each, **newest first** — the file is walked
  backwards. A finding with a blank `verdict` field counts as `unjudged`.
- No findings at all → the banner is the empty string and the page starts at the progress bar.
- The heading is `<b>N finding(s) from the supervisor</b> — ` followed by the tally, `n hold · n
  unjudged · n refuted`, and the whole heading is a link to `/overwatch`.
- **Refuted findings stay on the banner.** Leaving them off as noise was wrong twice over: it made a
  list of twenty-two closed and open complaints look like twenty-two open ones, and it hid the one
  thing a reader needs in order to decide where to spend ten minutes — whether anybody has checked
  this yet.
- **The box is not the alarm.** A red banner on every run teaches a reader to see past it. The border
  is tinted (`.alarm.some-hold`) only when at least one finding holds, and only the `n hold` figure in
  the tally is red (`<span class=hold>`). Each item's verdict chip has a class of its own —
  `.v.holds` red, `.v.unjudged` amber, `.v.refuted` grey — so refuted lines recede rather than
  disappear.
- The first `SHOWN = 5` are listed; the rest go in `<details id=more class=rest>` and **open where
  they are**. "and 17 more" used to be a link to the findings page, so reading them meant leaving the
  run you were watching; every line had already been read from the file anyway.
- Each item links to `/overwatch#f<i>` where **`i` is the finding's position in `overwatch.jsonl`,
  not its position on the page.** The findings page groups by verdict, so an anchor computed from
  render order would move the moment a critic answered.
- Item text is the finding's first line with a leading `^[#*\s]*(Finding:)?\s*` stripped — these were
  written as markdown for a page that renders none, and a banner reading "## Finding:" five times
  says nothing five times.

---

## One marker

`tabs()` renders the chain from `STAGES`, a list of five `{label, producer, critic}` triples in call
order:

```java
{"reproduce","reproduce-doer","reproduce-verifier"}  {"fix","fix-doer","fix-verifier"}
{"propose","propose-doer","propose-verifier"}         {"argue","verdict","argue-verifier"}
{"price","estimator","price-verifier"}
```

**Those ten names must be exactly `Agents.CHAIN`, in `Agents.CHAIN`'s order, paired producer then
critic.** This is a second copy of an order that lives in `Agents.CHAIN`, and the drift is invisible:
the tab row was once missing `argue-verifier` entirely, so the agent that can send a settlement back
for rework had no page of its own and nobody noticed. (`Dashboard.AGENTS = Agents.CHAIN` exists but is
referenced only from a javadoc link; nothing derives the tabs from it.)

```
summary | reproduce(reproduce-doer→reproduce-verifier) | fix(fix-doer→fix-verifier) |
propose(propose-doer→propose-verifier) | argue(verdict→argue-verifier) |
price(estimator→price-verifier) | live | prompts | the record | the supervisor | settings
```

Each chip carries how many times that agent answered. **A greyed-out stage did not run, and that is
usually the most informative thing on the page** — a marker settled at `argue` never reached a fix-doer.
A `↺` between a producer and its critic means the producer answered more than once, which nothing
else in this chain can cause: the critic objected and `Prove` asked it to go again, the loop the
chain allows exactly once per stage.

### The summary tab

**One page that answers the whole question, in the order a person asks it**, and nothing else:

1. **the claim** — the checker name, plus the first paragraph of
   the classpath resource `/checkers/<CHECKER>.txt` (the first line is skipped — it is the note's
   regex, chapter 5 — and the checker name is filtered to `[A-Za-z0-9._-]` before it becomes a
   resource path; **only the first paragraph, because the rest of that file is how to DEMONSTRATE
   the defect, which is an instruction to an agent and not an explanation to a person**). No such
   resource → the exact sentence "This pipeline has no note for X, so what it means here is whatever
   the agents below took it to mean.", with X the checker name as the key gave it; a resource that
   throws while being read → the empty string, and the paragraph is simply blank. Then the file and
   line. A reader used to have to carry the checker name and line number in their head from the
   table they came from.
2. **the code** — the flagged line with four lines either side, syntax-coloured. Absent entirely when
   `flagged()` returns blank.
3. **what happened** — the second half of `m/<slug>/summary.txt`, if the interpreter wrote one.
   `summary()` splits that file on the first `\n\n`: `[0]` is the line the index's fold uses, `[1]` is
   the account this section prints, and a file with no blank line in it yields the whole text for
   both. Unreadable or missing → both blank and the section is absent. **Two jobs, not two lengths of
   one:** the short line decides whether to open a row out of 356, the long one answers what happened
   once you have, and truncating the second into the first gives a table of sentences that all begin
   the same way and stop before the part that distinguishes them.
4. **what was run** — one line per `built` event, in words rather than phase names, because "red"
   and "green" are this pipeline's vocabulary and mean the opposite of what a reader expects:

   ```
   Before any patch: the test failed.  (This is what it was meant to do.)
   Before any patch: the test passed.  (It was meant to fail here — a test that passes on the
                                        unfixed code has not demonstrated anything.)
   After the patch: the test passed.
   {either phase, infra=true}: the build produced no test result at all, so nothing was learned.
   ```
5. **the test** — the `content` argument of the last `write_file` tool call whose content is not
   blank, headed with its `path` and *written to fail on the unfixed code*. **In full, never cut:**
   the argument to `write_file` IS the test, and shortening it shows the path and hides the only thing
   worth reading. Reading the artefact the whole prove turns on out of a tool argument on another tab
   is work a reader should not have to do.
6. **the fix** — the unified diff, recovered from the last `fix-verifier` `asked` event whose prompt
   contains `WHAT IT ACTUALLY CHANGED`: from the newline after that heading to the line beginning
   `\nThe patch changes ` (or, failing that, `\nTHE PATCH DOES NOT TOUCH`), stripped, and rendered
   through `diff()`. Where neither terminator is present the rest of the prompt is taken. Java
   computes the diff and hands it to that critic; **nothing recorded it as an artefact**, so the one
   thing a reader most wants to see could be read only by scrolling a prompt.

**No agent transcripts here.** That was the summary when there was nothing else to be: ten final
answers in call order, which is a pile of arguments addressed to each other and never an account of
the marker. Each is one click away on its own tab.

### An agent tab

Last answer first, in full — headed `answered` when the agent answered once and `final answer,
attempt N` when it answered more — because the chronological trace is the wrong shape for "what did
the fix-doer end up saying": a reproduce-doer that answered twice buries its final test under the rejected
one and eighty tool calls. Beneath it, every earlier answer as an `attempt N, superseded` block
holding a fold of what it said and a fold of the prompt, **counted down from the most recent
superseded attempt to the first**, so the closest thing to the final answer sits nearest to it.

Then the reasoning (`thought` events, **newest turn first — the last thought is the one that produced
the answer above it**), labelled `what it worked through` when there is one and `what it worked
through, turn N` when there are several; then one fold of every tool call with what it returned. "It
grepped for Serializable" and "it grepped for Serializable and got nothing" are different stories
about the same answer.

**An agent mid-answer has not "not run".** It reads and greps for minutes before its first token, so
three states, not two:

- no answers, no tool calls, no thoughts → `X has not run for this marker`;
- no answers, no tool calls, but thoughts → a `thinking` header with the reasoning under it;
- no answers but *n* tool calls → `working — n tool call(s), no answer yet`, with the reasoning and a
  `what it has reached for` fold underneath.

Answers and thoughts are matched with `field(e,"agent").equals(agent)`; tool calls are matched with
`endsWith(agent)`.

### The prompts tab

**What each agent was actually told when this marker was proved, and whether that is still what it
would be told.** Recoverable without recording anything new: the `asked` event carries the whole
prompt, and the task is appended after `\n\n---\n\n`, so the part before that separator *is* the
system prompt that agent ran under. First `asked` per agent wins.

Compared against `Prompts.effective(agent, BUILT_INS[agent])` with `Prompts.same`. Where any differ,
an alarm names them and offers a form posting to `/reprove` with
`why = "prompts changed: a, b"`. **A settlement is only as good as the prompt that produced it.**

`BUILT_INS` is collected once at start-up by `Agents.builtIn(here, …)` with a throwaway trace at
`dashboard-trace.jsonl` / `dashboard-settlements.jsonl` and a runner that refuses to build ("the
dashboard does not build"). The dashboard constructs no agents of its own, so without this it could
show what an agent is being told and not what it would be told if the override were removed.

### The record tab

`/marker?k=…&a=trace` and `/trace` are the same renderer; the second passes an empty key, which also
prefixes each event with a link to its marker. Events from every worker are concatenated and **sorted
by the `at` stamp — four workers' files give four ordered runs, not one story, and the stamp is what
makes them one again.** One renderer serves both the first build and every fragment, because two
renderers drift and the one that drifts is always the live path: it is the one nobody looks at
directly.

Per `kind`:

| kind | shown |
|---|---|
| `asked` | agent, `answered`, the `reply` in a `<pre>`, a fold `the prompt it was given`, and a rating form |
| `thought` | agent, `thought`, a fold `what it worked through` over `text` |
| `tool` | agent, `tool` name, folds `arguments` and `what it returned` over `result` |
| `built` | `phase` upper-cased, and `never ran` (`infra=true`) / `passed` / `failed`, fold `build output` over `summary` |
| `progress` | `· {note}` |
| `settled` | the state as a pill, then `because` |
| `priced` | `{minutes} min`, human-equivalent, then `itemisation` |
| `failed` | `failed`, then `cause` |
| anything else | the kind, as text |

Folds are open by default and `?fold=1` collapses them: **a fold saves scrolling and costs a click on
every single thing a reader came to look at, and reading a prove is reading the prompts.** The link
at the top toggles between "fold the long parts" and "open everything". Fold labels are
`{label} ({n} chars)` and an empty body renders no fold at all.

---

## The supervisor's pages

`/overwatch` — findings, worst first: `holds`, then `unjudged`, then `refuted`, and **within each
verdict in file order, oldest first** — the opposite of the banner, which walks each group backwards.
Each carries its `finding` (through `linked()`), a verdict pill (`holds` → the `settled` class,
`refuted` → `infra`, anything else → `needs-review`), a fold `what the critic said` over `judgement`,
and a rating form. The block itself is styled `tool` (grey) for `refuted` and `asked` for everything
else. Each carries `id='f<i>'` matching the banner's anchor, where `i` is again the position in
`overwatch.jsonl`. The sub-line reads `n hold, n refuted, n unjudged · n prove(s) restarted`.

`restarts.jsonl` is summarised above them, inside a fold labelled `n restart(s)` under the heading
*the tree was cut here*, one entry per line:

```
{id}  attempt {attempt}  killed={killed}
    {why}
```

**An unjudged finding is shown, and shown as unjudged.** A critic that could not be reached must not
be able to suppress a warning, and a reader deciding what to act on needs to know which of the two
they are looking at.

`?a=overwatch` and `?a=overwatch-critic` show one agent's events; `?a=trace` shows the whole
supervisor record. All three are **newest first**, unlike a marker's tabs: a prove is read after it
settles and its story runs forwards; the supervisor is read while it is running, and the question is
always what it just said.

**The supervisor's record is not trimmed and not capped** — eight megabytes after an afternoon. A
page that quietly shows part of a record reads as the record, and every cut this program has made to
a payload for a reader's convenience later turned out to remove the field somebody needed. Sorting it
must copy the list first: `read()` hands back an immutable one, and sorting that threw out of the
handler and produced the empty twenty-millisecond reply described above.

### Linking slugs

Findings and chat replies name markers by directory slug. `linked(text, markers)` **escapes first and
links second, so nothing an agent wrote can put markup on the page.** The map it is given comes from
`slugs(markers.txt)`: every queued marker key, `Supervisor.slug(key)` → key, inserted with **the
longest marker keys first**, so a slug containing a shorter one is linked whole rather than having its
middle replaced. Each replacement is wrapped in `U+0000` / `U+0001` sentinels — a later pass would
otherwise rewrite the `href` of a slug that is a prefix of another. The sentinels are stripped at the
end.

---

## Settings

`/settings` GET dispatches on `a`: `run`, `model`, `subject`, else the prompts editor. POST:

- multipart → `subjectPosted` (the subject: markers, token, JDK, zip), **answered in place rather
  than redirected, because what a reader needs after an upload is which lines were wrong**. A leading
  `!` on the reply means it was refused, and renders as "refused" instead of "done".
- `setting=model` → `Tuning.revert()` when the form carries a `revert` key at all, else
  `Tuning.save(form)`; 303 → `?a=model`
- `setting=workers` → `Workers.save(here, num(workers))`, 303 → `?a=run`
- otherwise → `Prompts.revert(agent)` when the form carries `revert`, else
  `Prompts.save(agent, prompt)`; 303 → `/settings#<agent>`

**`revert` is tested by presence, not by value**, in both cases: it is the name of a submit button, so
it appears in the body only when that button was the one pressed.

A failed write is swallowed; **the page redraws with what is on disk, which is the honest reply.**

The prompts editor lists agents in `Agents.ORDER`, then anything in `BUILT_INS` the order does not
name, sorted, at the end — **so a new agent is visible before it is listed rather than invisible
until somebody remembers** — and then drops every name `BUILT_INS` has no text for, so an agent that
could not be constructed at start-up is left out rather than shown with an empty box. The order must
come from the list and not from `BUILT_INS`: it is a `ConcurrentHashMap` because a handler thread
writes it, and `putAll`ing an ordered map into one throws the order away, which is exactly what it
did. The list is split under two headings — `the chain, in the order Prove calls it` for names in
`Agents.CHAIN`, `watching the run` for the rest. An override replaces the built-in entirely and the
code's own text stays available in a fold. **An edit takes effect on the next marker, not on the
next deploy** — a prove is a fresh process and reads the override when it constructs its agents.

Semantics of the settings themselves are chapter 13; what belongs here is the two sentences the pages
exist to carry:

- **the two model bounds are separate fields with separate sentences**, because confusing them killed
  eighty-six live proves: `patience_minutes` is how long the wire may be SILENT, `ceiling_minutes` is
  how long an answer may go on ARRIVING.
- the JDK selector's prose says it is **not about compiling** — javac 25 targets 8 through 25 — but
  about what the subject's tests run on, because every way that goes wrong arrives as "the build
  produced no test result", which is never taken as evidence and costs the marker anyway.

One markup detail a rebuilder will copy without noticing: on `?a=model` the `api_key` input and the
`forget_key` checkbox are emitted **before** the `<form method=post action='/settings'>` element
opens, so as written they are not descendants of the form the browser submits. `Tuning.save` reads
both keys from the posted map when they arrive. *(Read from the markup; not confirmed in a browser.)*

---

## Chat

`/chat` POST calls `Chat.ask(here, q)` and **answers with a 303**, because the page refreshes itself
while an answer is coming and a POST answered with a page would be a question asked twenty times
before its first answer arrived. `Chat.ask` returns a sentence for the person — blank when the
question was taken, `still answering the last one` or a write failure otherwise — and the redirect
carries it: `Location: /chat` when blank, `/chat?said=<enc>` when not. The GET renders that `said`
value, escaped, above the box.

While `Chat.answering()`, the page emits `<meta http-equiv=refresh content=3>`, disables the textarea
and the button, and shows a live panel over `chat-trace.jsonl.live` — the same `panel()` a prove's
live tab uses, under the name `supervisor`. **A meta refresh is a blunt instrument and it is the right
one here:** it needs no script, it cannot double-post, and there is nothing on the page to lose while
it fires — the box is empty and the person is waiting. When the answer lands the refresh stops and the
page holds still.

Turns are rendered `.say.mine` / `.say.theirs`; the text goes through `linked()`. `.said` is
`white-space: pre-wrap` **and** `overflow-wrap: anywhere` — an answer is prose with the model's own
line breaks in it, and `<pre>` alone would run a paragraph off the side of the page.

**The state that looks like the other one:** a question with nothing under it and no answer running
means the dashboard restarted mid-answer, which happens on every deploy. Left unsaid, the page reads
as still thinking and never stops, so it says "No answer came back — the dashboard restarted while it
was being written. Ask again."

`SEND_ON_ENTER` posts on Enter and keeps Shift-Enter for a newline, guarded on the textarea not being
disabled: **a keypress must not post a question the page has just said it will not take.**

---

## Live: three mechanisms, on purpose

### 1. SSE — `/events`

One long-lived connection per reader. Every 2000 ms the handler counts `lines(trace)` and
`lines(settlements)` — the base file plus every per-marker copy, the same concatenation the pages
read — and when either count has moved it writes

```
data: {"trace":<n>,"settled":<n>}\n\n
```

and otherwise

```
: ping\n\n
```

— a comment, because a proxy in front of this (Caddy) times idle connections out. Headers:
`text/event-stream`, `Cache-Control: no-cache`, `Connection: keep-alive`, `sendResponseHeaders(200, 0)`.
The remembered counts start at `-1`, so **the first tick of every connection always sends the
counts** — a page that has just loaded, or an `EventSource` that has just reconnected, gets the
current state within two seconds rather than waiting for the next change. Writes are flushed each
tick, and the sleep comes after the write.
**Bounded at 900 ticks (30 minutes), then closed**: a reader who shut the tab should not hold a
thread for the run's life, and `EventSource` reconnects on its own. An `IOException` or an interrupt
ends the loop silently — the reader navigated away, and there is nothing to clean up or log.

This replaced a 15-second meta refresh, which is why the fold-restoring script exists at all: **a
full reload throws away everything the reader had open and where they were.**

### 2. What the client does with an event

```js
isList = location.pathname === '/' || location.pathname === '/dashboard/'
```

- **The list re-renders wholesale.** It has no reader state in it, so: fetch the same URL with
  `X-Fragment: 1`, replace `document.body.innerHTML`, call `window.__keepOpen()`, restore `scrollY`.
- **Every other page appends, or ignores the event.** If `document.body.dataset.events` is
  `undefined` the script returns immediately. **A page that does not declare a cursor does not do
  fragments** — only the trace views end with one; the prompts page, the settings pages and the
  supervisor pages ignore the `X-Fragment` header entirely and answer with the whole page, and this
  branch appended it. One copy of `/prompts` per event, which is what it looked like.
- Otherwise: if `n.trace <= seen`, nothing to do; else fetch `here + ('?'|'&') + 'from=' + seen` with
  `X-Fragment: 1`; **a blank reply is dropped without touching the cursor**; a non-blank one is
  `insertAdjacentHTML('beforeend', html)` and `dataset.events = n.trace`.

Server side, `fragment(e)` is the presence of the `X-Fragment` header. **A fragment is only what is
new**: `events()` renders indices `from`…end and nothing else, because the page already holds
everything before `from` with whatever the reader opened still open, and sending it again would close
all of it. `from` is ignored on a full-page render — the whole list is drawn from index 0. `cursor(n)`
emits `<script>document.body.dataset.events=n</script>` — **including when there are no events at
all.** That branch used to return before setting one, so a marker page opened before its first event
declared itself unable to take fragments and stayed empty for the whole prove.

**Every marker view except `a=trace` returns the empty string for a fragment request**
(`fragment && !agent.equals("trace")`, tested before anything is read), belt to the cursor's braces:
an agent tab shows one answer, not a stream, so a live update would have to re-render it rather than
append.

Known edge, read from the code and not confirmed against a running instance: the cursor a page
declares is the number of events *on that page*, while `n.trace` is the number of lines in the
*whole* trace, and the script assigns `n.trace` to the cursor after appending. On `/trace` those two
numbers are identical. On `/marker?a=trace` they are not, so after the first append the cursor is a
global count, subsequent fetches ask for a range past the end of that marker's events, the empty
reply returns early — and the page stops appending until it is reloaded.

### 3. Polling — `/live`

**Polled rather than pushed, because the event stream fires when the counts move and an agent
reasoning for four minutes moves no counts** — which is precisely the stretch worth watching. The
poller in `KEEP_OPEN` runs every 2000 ms, finds `#live`, fetches `box.dataset.live || '/live'` and
replaces the container's HTML. **The container carries its own URL**, so the same poller serves any
page without either knowing about the other. Today only `/marker?k=…&a=live` has one:
`<div id=live class=live data-live='/live?k=…'>`.

`/live?k=<key>` returns:

- nothing at all when `k` is empty;
- "This prove has finished. What it said is on the tabs above; this view is only for one still
  running." when `m/<slug>/settlements.jsonl` holds any state that is not blank and not `proving`,
  `infra` or `queued`. **Stated as the complement of the three unfinished states, not as a list of the
  finished ones** — the pool's own test is a grep for the seven dispositions, and the two agree on
  everything the chain writes while this one also stops a state nobody has thought of yet from reading
  as still running;
- otherwise one panel over `m/<slug>/trace.jsonl.live`.

**A live file is only shown for a prove that is still claimed.** When a prove ends the file stays
behind holding its last answer, and a panel that went on showing it would be a live view that is
quietly a museum.

The `.live` file is the one thing in the record that is overwritten rather than appended, and its
format is exactly:

```
<agent>\n<epoch-millis>\n<everything the model has said so far>
```

The panel needs both newlines to parse; anything else leaves agent, stamp and text empty. It shows
**the last 4000 characters (`LIVE_TAIL`), not the first — a reasoning turn runs to tens of thousands
of characters and the end is where it is now.** Opening on the beginning would show the same paragraph
for four minutes. A read that fails is swallowed: the file is being rewritten as we read it and the
next poll is two seconds away.

```html
<details class=stream[ open] id='live-{esc(who)}'><summary>{who, first 46 chars}[ · agent][ · age]</summary>
<pre>{tail, or … when blank}</pre></details>
```

The `id` carries `who` in full while the summary truncates it, so the poller's open/closed bookkeeping
keys on the whole name. Age reads "nothing yet" with no stamp (`at == 0`), "quiet Nm" past 90
seconds, "Ns ago" otherwise; the agent and age parts are each wrapped in `<span class=k>· …</span>`.

**No live panel on the index.** Four provers' streams there meant four folds nobody had asked for
above a table of 356 rows, none of them the marker the reader came for; the supervisor's own stream
followed them off, because the findings banner says what it has *concluded*, and a paragraph of it
thinking out loud is a slower way to learn less.

---

## Fold persistence

`KEEP_OPEN` is the only other script on any page.

```
sessionStorage key:  'open:' + location.pathname + location.search      → JSON array of fold names
                     'open:' + …                 + ':y'                 → scrollY
fold name:           d.id || ('#' + index among document.querySelectorAll('details'))
```

Session storage rather than local, **so opening a second marker in another tab does not inherit this
one's state.**

- Restore runs on `DOMContentLoaded` (or immediately if the document is already parsed).
- Save is a capturing `toggle` listener on `document`, so it survives any subtree being replaced.
- Scroll is saved on every `scroll` event, passive, and restored by `restore(false)`.
- `window.__keepOpen = function(){ restore(true) }` is how the index's wholesale swap asks for its
  folds back —
  **nothing else fires after `innerHTML`: not `DOMContentLoaded`, and not scripts in the replacement,
  which `innerHTML` does not execute.** The scroll is the caller's to keep.
- The `#live` poller does *not* go through session storage. Those folds are replaced every two
  seconds, so their open/closed state is read off the page by `id` before the swap and put back
  after.

**Two bugs lived here, and both were silent.** The restore ran inline at the top of the document,
where `querySelectorAll('details')` matches nothing because none of them are parsed yet — so every
fold was faithfully SAVED and never once put back. And the key was a fold's position among all folds
on the page, which on the index moves every time a marker settles, so even a restore that ran would
have opened somebody else's row. Hence: **after parsing, and keyed by `id` wherever a fold has one.**

---

## Escaping, and where markup injection came from

```java
esc(s) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
enc(s) = URLEncoder.encode(s, UTF_8)      // every URL parameter, including marker keys
```

**Escape once, at the emitter.** `code()` used to concatenate its argument into the page unescaped,
and one caller handed it a `git diff` straight out of the repository — so a patch touching a line
containing `<` wrote markup into this page. The fix was not to escape at that call site but to move
escaping inside `code()`/`diff()`, **because escaping in one place is what makes the bug impossible
to reintroduce by adding a caller.**

The same rule, stated as obligations:

- `head()` escapes the title; the sub-line is HTML and the caller escapes it.
- `linked()` escapes before it inserts anchors; so does `item()` in the banner.
- `colourJava` / `colourDiff` escape each span and each gap between spans; **their callers must not.**
- `fold()`, `hidden()`, `dot()`, `chip()` and every state or severity class escape their own inputs.
- `css(state)` returns the state only if it matches `[a-z-]+`, and `"infra"` otherwise — **arbitrary
  text from a record cannot become a class name.** The rule is deliberately strict rather than clever,
  and it catches real states too: `verified/pr-ready` and `verified/pr-rejected` contain a `/`, so
  they are styled `infra` like anything unrecognised.

`esc` does not touch quotes, and every attribute carrying a dynamic value is single-quoted. A value
containing an apostrophe therefore ends its attribute early; URLs are safe because they go through
`enc`, which percent-encodes `'`.

---

## Syntax colouring

Two block renderers, both wrapping `<pre class=flagged>`:

```java
code(lines) → colourJava   diff(lines) → colourDiff   // blank input → "" , no empty <pre>
```

**`colourJava` is one pass over one alternation**, `Pattern.DOTALL`, with named groups in this order:

```
comment  //[^\n]*  |  /\*.*?\*/                     → <span class=c>
string   "(?:\\.|[^"\\])*"  |  '(?:\\.|[^'\\])*'    → <span class=s>
word     \b(abstract|assert|…|record|sealed|yield)\b → <span class=k>
number   \b\d[\w.]*                                  → <span class=n>
```

Order in the alternation is the whole design: **a keyword inside a string stays inside the string and
a quote inside a comment does not open one.** Colouring by running four separate replacements over
the same text is how `// the "public" API` comes out with half a comment and a stray keyword in it.

`colourDiff` classifies by first character only — `+++`/`---` → `df`, `@@` → `dh`, `+` → `da`, `-` →
`dr` — and nothing parses the code, because **a patch is read for its shape before it is read for its
content.** The `+++`/`---` test must come first or file headers colour as additions and deletions.

### The flagged lines

`flagged(CHECKOUTS, repo, file, line)` reads `${CHECKOUTS:-/work/checkouts}/<repo-basename minus
.git>/<file>` and returns `AROUND = 4` lines either side:

```
   78  …
>> 82      return ResponseEntity.ok(…);
   86  …
```

- **It uses `Files.readAllLines`, not the `read()` helper, which drops blank lines.** `read()` is
  right for JSONL, where a blank line is nothing, and wrong for source, where a blank line is line 79
  and every number after it shifts. It put `public ResponseEntity getProfilePicture` at line 82 of
  `ProfileUploadBase`, four lines below the truth, and that was very nearly written up as a finding
  about the analyser.
- Past the end of the file it appends `>> line N — THIS FILE HAS M. The analyser ran against an older
  revision.` **That sentence is the difference between a reader trusting the line number and a reader
  knowing not to.**
- **Any failure returns the empty string and the section is simply absent** — a line number that is
  not an integer, no tree at that path, an unreadable file, an empty file, or a line number below 1.
  This is a convenience for a reader; a dashboard that will not start because a tree is missing would
  be a poor trade.

---

## Reading the record

```java
lines(file)  = read(file) ++ every read(results/m/*/<same filename>), directories sorted
read(file)   = Files.readAllLines, blank lines dropped, [] on any IOException
```

**Parallel provers do not share a file.** Appending from four processes looks safe — `O_APPEND` makes
the offset update atomic — but a line here can be sixty kilobytes of prompt, and a write that large
is not one syscall. Two workers interleave mid-line and both records are lost, in a corpus whose
whole purpose is to be read later. So each prove writes `results/m/<slug>/trace.jsonl` and this reads
them all. One directory per **marker**, not per worker: the pool hands the next marker to whichever
prover is free, so a worker index names nothing a reader wants.

`field(json, key)` is a scan, not a parser: **a malformed line costs one blank cell, where a parser
would refuse the whole page.** It must handle unquoted values — `Settlement` writes booleans and
`Feedback` writes an int unquoted, and scanning for the next quote skipped past them and found the
*following key's* quote instead. That is why `red_verified` read as empty for every marker that had
genuinely gone red, **and the semaphore never lit.** Escapes `\n`, `\t`, `\r` become real characters;
any other `\x` becomes `x`.

`num(s)` returns 0 for anything unparseable — a malformed field must not take the page down.

---

## Feedback

Every `asked` event rendered by a trace view — a marker's record, `/trace`, and the supervisor's own
pages, which all go through `one()` — carries a rating form, and so does the final answer on an agent
tab. The superseded attempts on an agent tab do not; they are folds of reply and prompt and nothing
else.

```html
<form class=rate method=post action='/feedback'>
  <input type=hidden name='marker' …><input type=hidden name='agent' …>
  <input type=hidden name='event' …><input type=hidden name='back' …>
  <input type=hidden name='prompt' …><input type=hidden name='reply' …>
  <textarea name=note rows=4></textarea><button>save</button>
</form>
```

`event` is the index of that event **within the list the page rendered** — stable because events are
only ever appended. That is not one global numbering: a trace view numbers within its `at`-sorted
list, an agent tab numbers within that marker's events in file order. The label points at one `asked`
entry, because prompt tuning optimises one agent's prompt and a complaint filed against a whole prove
cannot be attributed — a marker that settled badly may have had a fine reproduce-doer and a careless
skeptic. `back` is the URL the reader is on, and the handler 303s straight to it, so
rating six answers on a marker is six clicks and no navigation. **The prompt and the reply ride along
as hidden fields** so the row written is a complete training example without a second read of the
trace. The row is `{"marker","agent","event"(int),"note","at","prompt","reply"}` appended to
`feedback.jsonl`; a malformed post costs a row, never the page.

**The body is read exactly once**, before the redirect is built — it is a stream, and a second read
returns nothing, which would lose the page the reader was on. A request that is not a POST parses as
an empty form, writes no row, and is redirected to `/`.

Findings on `/overwatch` carry the same form with `marker = agent = "overwatch"` and the finding and
judgement in place of prompt and reply.

---

## Re-proving from the page

`/reprove` POSTs `marker` and `why` and calls `Supervisor.reprove(marker, why)` with a trace at
`dashboard-trace.jsonl` / `dashboard-settlements.jsonl`, then 303s to
`/marker?k=<enc(marker)>&a=prompts`. A missing `marker` field is the empty string and a missing `why`
is recorded as `no reason given` — **the reason is written down whatever happens, because a re-prove
with no reason on the record is indistinguishable from a loop.** It is
**the same mechanism the supervisor's critic uses** — kill it, keep its record aside, release the
claim, let the pool take it — **and it is not counted against that agent's two restarts, because
somebody who has read the page and pressed a button is making a decision rather than looping.**

That sentence was false for as long as it existed. Both paths append to `restarts.jsonl` and the
limit counted every line with the marker's id, so two presses here spent the supervisor's whole
allowance on a marker it had never touched. The recorded line now carries `by`, and the guard counts
only `by != person`. See [06 — tools and the fence](06-tools-and-the-fence.md) for the guard and for
the archive-naming collision that separating the counters then caused.

---

## The one auth note

**The dashboard has no authentication, no session, no CSRF token and no origin check.** Anyone who
can reach the port can read every trace, edit every prompt, replace the marker queue, upload a source
zip, change the model and the endpoint, restart a prove — and read two secrets in plain text:

- `/settings?a=model` renders `Tuning.apiKey()` into a `<input type=password value='…'>`, because the
  reveal (👁) and copy (📋) buttons only work if the value is in the page.
- `/settings?a=subject` renders `Subject.token(results)` — the git credential — the same way.

The comment in `Tuning` states the trade plainly: *their key, their box, behind basic auth*, and the
consequence is worth stating rather than hiding — the value is in that page's source, so it is in
whatever caches or screenshots that page. **Basic auth lives in the reverse proxy in front of this
container and is not part of this repository** (chapter 15). A rebuilder who serves this port
directly has published the key.

The two mitigations that *are* in this repository, and must stay:

- a blank `api_key` submission **leaves the stored key alone** rather than clearing it (`Tuning.save`
  handles the key by name, outside the loop that writes the ordinary parameters, and only writes it
  when it is non-blank; `forget_key=1` is the one thing that removes it) — a browser that clears the
  field cannot silently unset it and leave every agent talking to an endpoint that refuses them;
- tool results are scrubbed before they can reach a page — `Tools.redact` rewrites
  `(?i)(api[_-]?key\s*[=:]\s*)\S+` to `$1(hidden)` and `scheme://user:token@host` to
  `scheme://(hidden)@host`, and `read_file` on the `model` or `git-credentials` path segment is
  refused outright. **At the tool layer and not in a prompt: a prompt is a request, this has to be a
  fact, and a mask that a second route walks around is not a mask.**
