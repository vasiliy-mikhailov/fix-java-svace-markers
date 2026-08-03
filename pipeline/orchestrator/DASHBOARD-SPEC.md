# The dashboard, specified

What `orchestrator/src/main/resources/static/{index.html,app.js,style.css}` is **supposed** to render,
and what the server is **supposed** to hand it. Written from the sources, but written as a
specification: a browser test is checked against this file, not against the implementation it was
derived from.

**Why this file is not in `static/`.** Everything under `src/main/resources/static/` is served by Boot
at the web root and shipped inside the jar — a spec there is downloadable at
`http://host:8085/DASHBOARD-SPEC.md` from behind whatever basic-auth Caddy has, and it grows the
deployed asset for no reason. It belongs beside `README.md`, which is where the endpoint table it
extends already lives.

**The bar this exists to enforce.** Every defect this page has shipped returned HTTP 200. A duplicate
`const` blanked the whole page; `/api/bug` went missing and 50 proven markers rendered "not proven
yet"; `/ws/info` 404'd through a proxy and live push silently became polling; a prove that settled
nothing was painted green. So the assertions this file supports are: **the page renders real data, the
numbers on screen match the database, the interactive paths open real content, a broken script fails
the test, and the states that must look different do look different.**

---

## 1. The document

`index.html` is 47 lines and declares the whole layout. It loads `style.css` and, at the very end,
`app.js` as an external script (`index.html:47`) — there is no inline script any more, which is what
the duplicate-`const` blank-page defect was in.

| Element | id | Panel |
|---|---|---|
| `<h1>` | — | static title `fix-java-svace-markers-spring · live` |
| header stamp | `ts` | live/poll indicator, §3 |
| stage banner | `stage-banner`, `stage-name`, `stage-detail`, `current` | §4 |
| outcome tiles | `stats` | §5 |
| effort | `work`, `workbasis`, `workdetail` | §6 |
| verdicts | `verdicts`, `verdictcount` | §7 |
| markers | `suspicions`, `suscount` | §8 |
| proven red→green | `bugs`, `bugcount` | §9 |
| activity | `activity` | §10 |
| errors | `errors`, `errcount` | §11 |
| modal | `modalbg`, `mtitle`, `mtabs`, `mbody` | §12 |

Initial paint: `tick()` (one `GET api/state`), then `liveConnect()`, then a 3-second `liveLoop`
interval, then one `renderLive()` and one `renderErrors()` — `app.js:727-735`. Every one of those runs
on load; a page that renders none of the placeholders has a script error.

`Escape` closes the modal (`app.js:16`). Clicking the modal backdrop closes it (`index.html:40`).

---

## 2. Endpoints

Everything the page fetches is a **relative** path (`api/state`, not `/api/state`), so the page works
under a proxy prefix such as `/dashboard/`.

| Method | Path | Called from | Contract |
|---|---|---|---|
| `GET` | `/` `/index.html` `/app.js` `/style.css` | browser | Boot static resources |
| `GET` | `api/state` | `tick()` `app.js:150` | the whole view — §13 |
| `GET` | `api/bug?key=<dedup_key>` | `showInvestigation()` `app.js:34` | one artifact; **`{}` + 200** when there is none, never 404 |
| `GET` | `api/source?repo=&branch=&file=&line=` | `loadMarkerSource()` `app.js:61` | a source window; **200 even on failure**, with `error` in the body |
| `GET` | `api/live` / `api/dialogs` / `api/dialog` | `renderLive()` `app.js:577`, `showDialog()` `app.js:27` | retired; answers `{"dialogs":[],"dialog":""}` |
| `GET` | `api/methods?file=` | `showFile()` `app.js:19` | retired; answers `{"methods":[]}` |
| `GET` | `api/errors` | `renderErrors()` `app.js:607` | retired; answers `{"errors":[]}` |
| `WS` | `ws` (STOMP; SockJS also registered) | `liveConnect()` `app.js:656` | §14 |

Not called by the page, but part of the deployment's contract:

| Method | Path | What |
|---|---|---|
| `POST` | `/api/prove` | drain the backlog — `202` + execution id, or `409` |
| `POST` | `/api/prove/marker` | prove ONE marker — `{"dedup_key":"…"}`; `202`/`409`/`400` |
| `POST` | `/api/ingest` | replace the backlog from a Svace CSV; `202`/`409`/`400` |
| `GET` | `/healthz` | plain-text `ok`, or **503** `down: <reason>`, backed by `SELECT COUNT(*) FROM suspicions` |
| `GET` | `/actuator/health` | the same probe per component |

**Rules that hold for every dashboard read:**

- `Cache-Control: no-store` on every response (`DashboardController.NO_STORE`). A proxy holding a
  five-second-old `/api/state` produces a dashboard that is silently behind.
- A read that throws becomes **500 with `{"error": "…"}`**, never a dead page
  (`DashboardController.failed`).
- `jget()` (`app.js:6`) swallows every transport and parse failure and returns `null`. **This is why an
  empty answer must be a 200 and not a 404** — the page cannot tell the two apart. A test that only
  checks status codes checks nothing here.

---

## 3. Header stamp (`#ts`)

Text is `stamp(what)` = `(connected ? "live · " : "") + what + " " + <local time>` (`app.js:636`).

| When | Text |
|---|---|
| before the first render | `connecting…` (from `index.html:6`) |
| every `render()` | `updated <time>` — or `live · updated <time>` while the socket is up |
| STOMP `CONNECTED` | `live since <time>` |
| `/topic/markers` frame | `live · marker <to> · <time>` |
| `/topic/progress` frame (not `tick`) | `live · <event>[ · <step>][ · <n> written ·] <time>` |

`live · ` is the only place the page says whether it is being pushed to or is polling.

---

## 4. Stage banner

Three fields, all derived from the snapshot — never from the live topics, deliberately, so there is one
answer to "what stage is this run in" (`app.js:713-715`).

| Field | Value |
|---|---|
| `#stage-name` | `proving` if any `activity` row has `status === "running"`; else `idle` if markers remain; else `complete` |
| banner class | `stage active` (blue border, blinking `●`) / `stage` / `stage done` (green) |
| `#stage-detail` | `<repo> · Svace markers · one at a time under the runner lease`, repo from `suspicions[0].repo` |
| `#current` | `<n> marker(s) still to settle` / `every marker settled` / `no markers ingested yet — POST /webhook/ingest` |

`#current` is also written by the `/topic/counts` handler (`app.js:708`) — see **GAP 3**; the two
writers do not agree on what `n` is.

---

## 5. Outcome tiles (`#stats`) — the header counts

Eight `.card` tiles, each `label / big value / note`, computed **in the browser** from
`state.suspicions` (`app.js:167-176`).

| Tile | Value | Note |
|---|---|---|
| **Run progress** (wide, progress bar) | `settled / total` | `<pct>% settled` |
| **Queued** | count of `status = new` | not yet attempted |
| **Proven** | count of `status = verified` | `<n> verified red→green` |
| **Refuted** | count of `status = false_positive` | claim does not hold |
| **By design** | count of `status = by_design` | intentional, nothing to fix |
| **Unprovable** | count of `status = unprovable` | no test would compile |
| **Reproduced** | count of `status = reproduced` | real, fix not found |
| **Infra stuck** | count of `status = infra_stuck` | never became testable |

Definitions as the page computes them (`app.js:163-166`):

- `settled = suspicions.length − count(new)` — **so `proving` counts as settled here.** See **GAP 2**.
- `pct = round(settled / total × 100)`; `0` when the backlog is empty.
- `pending = count(new) + count(infra_stuck)` — the banner's number, a *different* set. See **GAP 4**.
- The "verified red→green" note counts artifacts with `yes(red_verified) && yes(green_verified)`, over
  `state.bugs` — a different table from the tile's own value.

`yes(v)` (`app.js:12`) treats `true`, `1`, `"1"` and `"true"` as true. It exists because the same page
was fed sqlite `0/1` and Spring JSON booleans, and `String(v)==='1'` was right for exactly one of them.

**There is no tile for `rejected` and none for `proving`.** See **GAP 5**.

---

## 6. Effort (`#work`, `#workbasis`, `#workdetail`)

Read straight off `state.work`, which is `WorkModel.Metrics` serialised by component name. **Only
machine time is measured**; the human figures are itemised estimates and the page says so in two
places, because the FTE multiple is the number people quote in an efficiency argument.

| Tile | Key | Meaning |
|---|---|---|
| Human-equivalent work | `humanHours` | Σ per-marker estimate, charged **by outcome** |
| Machine time | `machineHours` | measured wall-clock of every **finished prover** execution |
| Human FTE equivalent | `fte` | `humanHours ÷ machineHours`, rendered `<n>×`; `–` when not measured |
| ETA | `etaSec` | `(machineSec ÷ settled) × remaining`, at this run's observed rate |
| Markers settled | `settled / totalMarkers` | `<remaining> remaining` |

`hrs()` prints `<10h` to one decimal and ≥10h rounded. `eta()` prints `Dd Hh` or `Hh`. `null` renders
as an em dash — never `0`, because a zero there is a claim.

**`#workbasis`** — `over <fteBasis> settled marker(s) · all machine time charged, including <retryHours>
of retries`.

**`#workdetail`** — prints the itemised rates back to the reader from `work.humanMin`, in insertion
order, so the arithmetic can be checked against the sentence:

| item | minutes | charged when |
|---|---|---|
| `triage` | 10 | always |
| `assess` | 20 | always |
| `write_test` | 45 | outcome ∈ `pr_ready`, `pr_rejected`, `needs_review`, `fix_failed` |
| `verify` | 15 | same set |
| `write_fix` | 40 | outcome ∈ `pr_ready`, `pr_rejected`, `needs_review` |
| `rebut` | 25 | outcome ∈ `false_positive`, `by_design`, `unprovable`, `undetermined`, `not-a-bug` |

and closes with `fteSettledOnly` — what the multiple *would* read if only the executions that settled
something were charged. It is quoted precisely so the honest figure cannot be mistaken for it.

Server-side rules that make those numbers meaningful:

- **Unsettled = `{new, proving}`** (`WorkModel.UNSETTLED`). A marker in flight costs nothing yet.
- **The artifact's `state` wins; the marker's `status` is the fallback** for a marker settled without
  an artifact (an infra write-off never reaches `bugs`).
- **Only prover executions count** — the ingester settles no marker.
- **No LIMIT on the execution history.** Capping it at 400 rows once reported 5.8 machine hours instead
  of 26.6, turning a defensible 9.5× into a fictional 43×.
- **Attribution is by containment, not by division**: the execution whose window contains a marker's
  observed `updatedAt` is the one that settled it. A marker with no observation is left out of the
  measured window rather than handed an average — which is why `fsm.live.enabled=false` costs the
  retry split.
- `fte`, `fteSettledOnly`, `etaSec` are boxed/nullable; `null` means "not measured".

---

## 7. Verdicts (`#verdicts`, `#verdictcount`)

Rows: `state.bugs` filtered to those with non-blank `verdict_text` (`app.js:221`). A verdict is a
first-class output — a marker that yields no PR must still yield an argued rebuttal.

Header: `<n> of <settled> settled markers · <total> total`, plus
`· <k> NOT ARGUED (verdict stage switched off)` when any row carries `verdict_status === "skipped"`.
That clause is load-bearing: without it, a run made cheap on purpose looks exactly like a half-dead
model endpoint.

Columns: `severity · checker · file · line · category · anchor · claim · kind · verdict`.

- `file` renders through `pkg()` — `src/main/java/` stripped, folders dimmed, class highlighted — and
  appends **`marker row gone — re-ingested since`** in red when `marker_orphaned` is true.
- `kind` is a coloured pill. Every kind the engine can produce is mapped, so an unmapped one stays
  visible as amber rather than blending in:

| kind | colour | source |
|---|---|---|
| `true-positive` | green | `ExecVerdict` |
| `true-positive-unfixed` | amber | `ExecVerdict` |
| `needs-review` | red | `ExecVerdict` |
| `undetermined` | red | `ExecVerdict` |
| `false-positive` | blue | argued (model) |
| `by-design` | green | argued (model) |
| `unprovable` | amber | argued (model) |
| anything else | amber | fallback |

- **A `skipped` row is never rendered as a finished verdict.** The pill becomes a dim `not argued`
  with the composed kind beneath it, and the verdict cell is prefixed with *"the verdict stage was
  switched off — composed from the run, nobody argued this marker"*. A `skipped` row that still has
  text is the exhausted-build route, whose wording is character-for-character what a marker gets when
  the endpoint is down — which is exactly why it must be labelled.

Empty state: `no verdicts yet — every settled marker gets one, whatever the outcome`, or, when some
are skipped, `no verdicts — the verdict stage is switched off for this run, so <k> marker(s) settled
with no argument written`.

---

## 8. Markers (`#suspicions`, `#suscount`)

Every row of `state.suspicions`, in the order the server returns them. Header: `<n> rows`.

Columns: `sev · checker · category · file:line · anchor · status`. Severity is a `.sev-*` class
(`high`/`medium`/`low` — always one of the three, `ParseMarkers` grades unknown severities `low`) with
the **reported** `svace_severity` as its text. `file:line` prefers `svace_line` over `line`. `anchor`
shows `<name>()` or falls back to the `anchor_status`. A non-blank `note` is appended under the status,
clipped to 180 characters.

**Clicking a row opens the investigation modal** (`showInvestigation(DASH.suspicions[i])`).

### The marker status vocabulary — every state a marker can be rendered in

Written by `Verdict`; the orchestrator never invents one.

| status | meaning | tile | colour |
|---|---|---|---|
| `new` | queued, never attempted (or requeued after an infra failure / a non-reproduction) | Queued | blue |
| `proving` | claimed, in flight — **not an outcome**, erased on restart | none | none |
| `verified` | reproduced and fixed (`pr_ready` / `needs_review` / `pr_rejected`) | Proven | green |
| `reproduced` | real, fix not found (`fix_failed`) | Reproduced | none |
| `false_positive` | tested, the claim does not hold | Refuted | none |
| `by_design` | the claim holds, the code is deliberate | By design | none |
| `unprovable` | nothing ever compiled, so it could not be tested | Unprovable | none |
| `infra_stuck` | retry ceiling reached; never became testable | Infra stuck | none |
| `rejected` | retired with no argument — verdict stage off, verdict call failed, or a routing `[gap]` | **none** | dim |

The `note` distinguishes the three ways into `rejected`: `[skipped]…`, `[verdict] the verdict call
FAILED…`, and `[gap] … the verdict stage does not route this state`. Those three prefixes must keep
their distinct meanings; the `[gap]` one names a defect in `Verdict.java` and nothing else may use it.

See **GAP 5** (no tile for `rejected`) and **GAP 6** (six of nine statuses render uncoloured).

---

## 9. Proven red→green (`#bugs`, `#bugcount`)

Gated on `state.prover_built`. False renders the dashed placeholder *"Prover not built yet — lights up
when the suspicion→test→red→fix→green loop is wired."*; true renders the table or `no proven bugs yet`.

Columns: `file · title · red · green · state`, where red/green are `redgreen(v)` — a green `●` or a red
`○`. Clicking a row opens the same modal via `showBug()`, which looks the marker up by
`dedup_key === suspicion_key` and falls back to the artifact row itself.

### The artifact state vocabulary — every state an artifact can be rendered in

`bugs.state` is `MarkerState`'s wire vocabulary **or** one of three spellings `Verdict` writes over it.
The PR-maker tab (§12) is what renders it:

| state | badge | note the tab shows |
|---|---|---|
| `pr_ready` | green **PR READY** | worth opening upstream |
| `pr_rejected` | red **PR REJECTED** | proven bug, but not PR-worthy for this repo |
| `needs_review` | amber **NEEDS REVIEW** | the fix skeptic flagged the fix, so the PR maker was skipped |
| `infra_error` | amber **INFRA ERROR** | the pipeline failed to run this one; **not** a judgement about the code |
| `fix_failed`, `not_reproduced`, `not-a-bug`, `infra_stuck`, `false_positive`, `by_design`, `unprovable`, anything unknown | grey pill with the raw state | not PR-ready |

`bugs.verdict_status` is a separate column with one job: keeping apart three rows that are otherwise
identical — a model that was asked and said nothing, a model that was never reached (`infra_reason`
carries `verdict writer never answered: …`), and a question nobody asked (`verdict_status = skipped`).

---

## 10. Activity (`#activity`)

The 20 most recent job executions, newest first. Columns: `dot + start time · flow · file · status ·
took`.

- `flow` is `prover` / `ingest` / the raw job name — classified **by substring**, so a rename shows up
  under its own name rather than vanishing.
- `file` is **always empty**; see **GAP 14**.
- `took` is `dur(seconds)` → `Dd HHh` / `Hh MMm` / `Mm SSs` / `Ss`; blank while a run is still going.
- start time is parsed as `new Date(s.replace(' ','T')+'Z')`, so the server must send
  `yyyy-MM-dd HH:mm:ss.SSS` in **UTC with no zone suffix**.

### The run status vocabulary — every state a run can be rendered in

`runStatusOf()` maps Spring Batch's `BatchStatus`, then corrects it with the item counts:

| status | from | colour |
|---|---|---|
| `running` | `STARTED`, `STARTING` | amber |
| `success` | `COMPLETED` **with** `itemsWritten > 0` | green |
| `settled-nothing` | `COMPLETED`, prover job, `itemsWritten = 0`, `itemsRead > 0` | **amber** |
| `idle` | `COMPLETED`, prover job, nothing read at all | dim |
| `error` | `FAILED`, `ABANDONED` | red |
| anything else | lower-cased and passed through | uncoloured, which is visible |

**`settled-nothing` is the fix for a shipped defect.** `COMPLETED` means "the step reached the end of
its input", which for a fault-tolerant step includes "every item was skipped" — so with the runner and
the model on dead ports, twelve consecutive executions logged `COMPLETED` and the panel was solid
green while the pipeline proved nothing. It is not `error` either: inside the skip budget one 502 over
282 markers is a normal event, and red must keep its meaning.

The correction applies **only to prover runs**. `ingest` is a tasklet — it clears two tables and
inserts 282 rows with no chunk at all, so its write count is structurally zero and the rule would paint
every successful ingest as a run that achieved nothing.

`BatchLiveListener` pushes the **same** vocabulary on `/topic/progress`, so the socket and the panel
cannot disagree.

---

## 11. Errors (`#errors`, `#errcount`)

Permanently empty by construction — there is no failed-execution feed. `#errcount` reads
`none` and the panel reads `no recent errors`. What actually failed for a marker is on the marker
(`note`, `infra_reason`), because a FAILED job execution is already one row in the activity panel and
reconstructing an errors table from it would report one row per run rather than one row per thing that
went wrong. Called exactly once, on load, so the placeholder is painted. See **GAP 7** and **GAP 12**.

---

## 12. The investigation modal

Opened from a Markers row or a Proven row. Title is `<file>:<line>` + checker + the first 90 characters
of the description. `Escape` or the backdrop closes it. The modal fetches `api/bug?key=<dedup_key>`
once; `{}` back means no artifact.

**Tabs when there is no artifact (`bug.suspicion_key` absent):** one tab, `Reproducer / Fixer / PR`,
reading *"not proven yet — run the prover to generate a test, fix + PR decision"*. This is the exact
string that was wrongly shown for 50 proven markers when `/api/bug` went missing, so a test that
asserts this string must also assert it is **absent** for a marker that has an artifact.

**Tabs when there is an artifact:** four.

| Tab | Label suffix | Content |
|---|---|---|
| Marker | — | §12.1 |
| Reproducer | `●` if `yes(red_verified)` else `○` | stage version line, `fails-before-fix` dot, `jdk`, then `test_path` and syntax-highlighted `test_code` |
| Fixer | `●` if `yes(green_verified)` else `○` | version line, red/green dots, then `fix_diff` parsed as `[{path, old_str, new_str}]` and rendered per file as `-`/`+` lines; unparseable JSON falls back to the raw text |
| PR maker | `●` `pr_ready`, `⛔` `pr_rejected`, else `○` | version line, the state badge from §9, `infra_reason` when present, `pr_title`, `pr_body` |

### 12.1 The Marker tab

Three blocks, plus two conditionals.

1. **Svace report row** — `severity` (coloured, showing `svace_severity`), `checker`, `claim`, `file`,
   `line`, `category`, `marker id`. Every field prefers the marker's value and falls back to the
   artifact's.
2. **Where it landed in the checked-out tree** — `anchor`, `confidence`, `status`, `attempts`, `note`.
   `confidence` is `anchor_status`, coloured, with a sentence explaining it:

   | `anchor_status` | colour | sentence |
   |---|---|---|
   | `exact` | green | the reported line falls inside this method in the checked-out tree |
   | `no-method` | amber | the line is a field, annotation or import — with Lombok the accessor Svace flagged is generated and has no source form |
   | `unresolved` | red | the line is past the end of the file as checked out — the file changed since the scan |
   | `pending` | dim | not resolved yet; the prover re-anchors when it fetches the source |

3. **The skipped banner**, when `verdict_status === "skipped"` — amber *"Verdict — NOT WRITTEN (stage
   switched off)"*. It comes **before** any verdict text and is shown **even when there is text**,
   because the exhausted-build route keeps its composed "NOT SETTLED" wording and that text is
   character-for-character what a marker gets when the endpoint is down.
4. **The verdict**, when `verdict_text` is non-blank — headed `Verdict — <kind>`, or `Composed from the
   run, not argued` when skipped.
5. **Source at the marker** — a placeholder that renders immediately and is filled asynchronously.

### 12.2 Source at the marker

`GET api/source?repo=&branch=&file=&line=` against the **prover's cached read-only checkout** —
the same tree the prover tested, not a second GitHub fetch, and it costs no API rate limit.

- 29 lines: 14 either side of the marker. `lines` arrives as `[absoluteLineNumber, text]` pairs so real
  line numbers render and the flagged one can be marked with `▶` on a red background.
- Footer: `<file> · <total> lines · from the runner's checkout, the same tree the prover tested`, plus
  `· file truncated` when `truncated`.
- **`past_eof`** renders a red banner: *"line N is past the end of this file (T lines) — the file
  changed since the scan, so the window below is its tail"*. Reported, never silently clamped: the
  scanned commit is unknown, so drift is exactly what a reviewer needs to see.
- **Any failure is a 200 with `error`**, rendered as `source unavailable — <reason>` in place of the
  code, keeping the rest of the tab. A missing `repo` or `file` answers `repo and file are required`;
  a dead prover answers `the runner at <url>/fs/read_file could not be read: <Type: message>` —
  the exception type is always included, because a bare `ConnectException` carries no message and
  "source unavailable — " followed by nothing is what a reader would otherwise see.
- `line` is parsed leniently server-side: missing, blank or non-numeric is **0, not a 400**, and `26.0`
  is 26. A `@RequestParam int` would 400, and `jget()` would swallow that into an unexplained "source
  unavailable".

---

## 13. `/api/state` — the payload

One read per call, shared by every section, so the counts, the tables and the effort model all describe
the same instant.

```
{ scan: {status:"idle", repo:<suspicions[0].repo>|null},
  files: [],                       // always empty; there is no file scan
  suspicions: [ …Suspicion.toMap() ],
  bugs:       [ …Bug.toMap() + MARKER_COLS + marker_orphaned ],
  activity:   [ {wf,status,started,file,dur} × ≤20 ],
  work:       WorkModel.Metrics,
  prover_built: bool }
```

`scan` and `files` are retired keys kept because the payload shape is shared with the sibling
dashboard; the page reads neither.

**The join.** Every artifact carries the marker it answers, because a verdict is only reviewable next
to its question — severity, checker, file and line *are* the Svace report. `MarkerColumns.ALL`, in
order: `svace_severity, svace_checker, svace_line, marker_id, category, severity, line, class_name,
method, anchor, anchor_status, description, evidence, status, prove_attempts, note`. Two rules:

- **The artifact always wins.** A column the artifact owns is never clobbered — today that is
  `bugs.svace_checker`, which `Verdict` writes; a re-ingest that changed the checker name must not
  rewrite history on a verdict already argued. Blank means absent/null/whitespace — a numeric `0` is
  kept.
- **A missing marker is a fact.** The two tables are deliberately not joined by a foreign key so
  evidence survives a re-scan. The columns fill with null and `marker_orphaned` goes true.
- **A blank key is not a key.** A marker or artifact with an empty `dedup_key`/`suspicion_key` is
  orphaned rather than joined to whatever else has no key — otherwise `/api/bug?key=` would hand back
  some artifact's diff and drafted PR for any keyless marker.

`prover_built` is `bugs.count()` succeeding. False switches the page to "Prover not built yet" instead
of "no proven bugs yet" — the honest answer to "has the prover produced anything" when the place it
would produce it into is unreachable. Under the shipped profile `schema.sql` creates the table on every
start, so it is normally true.

---

## 14. The live channel

STOMP over a raw WebSocket at `<page dir>/ws`; the client is hand-written (three frames: `CONNECT`,
`SUBSCRIBE`, `MESSAGE`) rather than 200 KB of vendored SockJS/stomp.js, because the page is three
static files with no build step. Heart-beats are negotiated **off** (`heart-beat:0,0`) — there is
nothing in the page to answer them with.

On `CONNECTED` the page subscribes to four topics and sends `/app/refresh`, so the first paint comes
over the socket.

| Topic | Payload | What the page does with it |
|---|---|---|
| `/topic/state` | the whole `/api/state` document, byte for byte | `render(d)` — the **same** function the poll calls, so the two transports cannot drift |
| `/topic/counts` | `{byStatus, byState, total, queued, proving, settled, remaining, bugs, pct, at}` | rewrites `#suscount`, `#bugcount`, `#current` only — **never** a table, because the counts document carries no rows and rebuilding one would be a second, disagreeing view |
| `/topic/markers` | `{dedupKey, from, to, note, at}` — `from` null on first sight | stamps the header line only; a transition is news, not a view |
| `/topic/progress` | `{event, at, …}` where `event ∈ job.started, job.finished, step.started, step.finished, chunk, tick` | stamps the header; `tick` is swallowed |

Who publishes:

- **`LiveWatcher`** polls `SELECT dedup_key, status FROM suspicions` every `fsm.live.watch-ms` (2 s).
  On a change it records the observation in `marker_progress` **before** announcing it, pushes
  `/topic/markers` per marker, then `pushCounts()` and `pushStateIfWatched()`. It is the **only writer**
  of `marker_progress`, which is what makes the effort model's times observed rather than claimed.
  - **The first pass stamps nothing and announces nothing.** A marker settled three hours before this
    process started did not change state now; recording it would place it inside whichever prover run
    happens to be going and build the retry split on an invented timestamp.
  - **A marker that vanished produces nothing** — a re-ingest is not a transition.
  - It is a poll because a transition written by a path nobody thought about (a manual SQL fix, the
    startup reconciliation) must still be seen.
- **`BatchLiveListener`** registers itself onto every `AbstractJob`/`AbstractStep` in the context, so a
  job cannot be added without being reported. Job start/finish and step finish also push counts and a
  snapshot; `afterChunk` pushes counts only, because the marker's own transition is what the watcher is
  about to push.
- **The tick** goes out every `fsm.live.tick-ms` (5 s) **only while someone is attached**. It is not
  decoration: it is the client's staleness signal, and without it a half-open socket is
  indistinguishable from a quiet run.

Client-side degradation: `liveLoop` runs every 3 s and calls `tick()` (the REST poll) **only** when
there is no connection or no frame has arrived for `STALE_MS = 20000`. `onclose` reconnects after 3 s;
`onerror` just closes, so the reconnect is scheduled in one place.

Server-side limits: outbound `32 MB` (the state document with 282 markers' CLOBs is past the 512 KB
default, and the default closes the session with no error anywhere), inbound pinned at `64 KB`, send
time limit `20 s` so a suspended tab is dropped rather than buffered forever. Origins are
`setAllowedOriginPatterns("*")` — an origin check that rejects a tunnel fails as a socket that never
connects, i.e. as a page that silently reverts to polling, and every destination is read-only public
run state.

**Nothing in the publisher may throw at its caller.** Its callers are the batch listeners — that is,
the prove itself — and a marker must never be requeued because a web socket had a bad day.

---

## 15. What a browser test must be able to fail

Derived from the four shipped defects, stated as assertions this spec supports:

1. **A broken script fails the test.** `#stats` must contain eight `.card`s with numeric values and
   `#ts` must not still read `connecting…`. A blank page returns 200 from every endpoint.
2. **The numbers match the database.** Every tile in §5 is a `COUNT(*) … GROUP BY status`; `#suscount`
   is `COUNT(*) FROM suspicions`; `#bugcount` is `COUNT(*) FROM bugs`; the Verdicts header's first
   number is `COUNT(*) FROM bugs WHERE TRIM(verdict_text) <> ''`.
3. **The interactive paths open real content.** A marker with an artifact must open **four** tabs, and
   the Marker tab must carry its checker, its line and either source or a named reason. The string
   "not proven yet" must appear for a marker with no artifact and must **not** appear for one with an
   artifact.
4. **States that must look different do look different.** A `settled-nothing` activity row must not be
   the same colour as a `success` one; a `skipped` verdict must render the `not argued` pill and the
   amber banner, not a kind pill. §8 lists the statuses where this currently does **not** hold.
5. **A dead socket keeps the page live.** With the WebSocket blocked, the page must still update within
   ~3 s from the poll — the `live · ` prefix disappears and `updated <time>` keeps advancing.

---

*Gaps between this spec and the code are listed separately, with file:line, in the review that
produced this file. Where a gap is listed, the code — not this document — is the authority on what
happens today.*
