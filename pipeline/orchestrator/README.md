# fsm-orchestrator

The schedule, the single-flight queue, the two tables, the run history and the dashboard, in one Spring
Boot process.

It owns those and **nothing else**: every judgement (the anchoring, the realness score, the verdict
routing, the infra-versus-verdict state machine) lives in `engine` and is called here **in-process, as a
library**, through the same pure functions the engine's own tests pin. That is why `engine` is a compile
dependency and not a base URL, and why the `fsm-engine` container is not in this service's dependency
list — nothing in a run calls it.

> **THE PROVER IS IN THIS PROCESS TOO.** `fsm.runner.mode` is `local` by default: `RunnerClient` is
> `LocalRunnerClient`, which calls `tech.mikhailov.fsm.runner.LocalRunner` directly — the same clone, the
> same RED and GREEN builds, the same single-flight queue, no HTTP. Where an `fsm-runner` address appears
> below, read "the prover": in the default shape there is no such container. `FSM_RUNNER_MODE=http`
> selects the split shape and `FSM_RUNNER_URL` with it, and the retry and timeout knobs documented here
> apply to that mode only.
>
> Two consequences worth knowing here: `CACHE` must point at a mounted volume for the same reason
> `FSM_DB_PATH` must, and the container runs as uid **10002** with both `/state` and `/cache` under it.

```
POST /api/ingest        ──►  ingest job   ──►  suspicions (the backlog)
                                                   │
schedule tick (60s)  ─┐                            ▼
POST /api/prove       ├──►  prove job  ──►  claim one marker
POST /api/prove/marker┘                     ├─ GitHub contents  (source)
--fsm.prove.marker    ┘                     ├─ model            (reproducer, fixer)
                                            ├─ the prover       (RED build, GREEN build, in-process)
                                            ├─ model            (skeptic, PR maker, verdict)
                                            └─►  bugs + suspicions.status
```

---

## Run it

### Locally, against nothing

```bash
cd pipeline
mvn -B test                       # engine + orchestrator; no network, no container, no model
mvn -pl orchestrator spring-boot:run
# http://localhost:8085  — the dashboard, /api/state, /healthz
```

With no `QWEN_*` and no `GIT_TOKEN` it starts, warns which variables are missing **by name**, and
fails closed on anything that needs them. The database is `orchestrator/data/fsm.mv.db`
(gitignored) — see [the H2 path](#the-h2-path-warning) before you deploy that default anywhere.

### In compose, on the deployment host

```bash
cd pipeline/deploy
cp .env.example .env                 # then fill in QWEN_* and GIT_TOKEN
docker compose up -d --build         # ONE service, `fsm` — this module, with engine and runner inside it
docker compose logs -f fsm
```

**There is no `orchestrator` service to name.** `deploy/docker-compose.yml` declares one running
service and it is called `fsm`; `docker compose up -d orchestrator` fails with *no such service*. The
image build runs all THREE modules' test suites.

On a host where Central is slow, and only with the Nexus network — `docker compose build` cannot join a
network and BuildKit rejects custom network modes, so this is the legacy builder from the reactor root:

```bash
cd pipeline
DOCKER_BUILDKIT=0 docker build --network mvn-cache \
  --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ \
  -t fsm:latest .
```

That argument governs the **image's own** build. Which repository the *analysed* projects resolve from
is the `MAVEN_MIRROR_URL` environment variable on the running container — no rebuild, and it works on an
image you did not build.

Published on `127.0.0.1:8085` only. The dashboard has no authentication of its own; public access is a
reverse proxy with auth in front, routing `/dashboard/` here.

### Exactly one prover, and that is a property to protect

`deploy/docker-compose.yml` declares ONE running service, `fsm` (plus `engine` behind a profile, which
nothing in a run calls), so this process is the only prover in the stack and its single-flight guarantee
is the whole of the mutual exclusion.

**Do not add a second one.** The prover serialises `/run_test` around ONE cached workspace *per
process*, so a second prover `reset --hard`s and patches the tree the first is building in, with no lock
between them, and nothing anywhere goes red — the result is a green prove about the wrong code.
`DeploymentTest` pins the service list for exactly this reason; it is the only place the compose file is
checked at all.

---

## Prove one marker

**The question this answers: "marker X settles wrong — reproduce it."** Before this route existed there
was no way to ask. `claimNext()` takes the lowest `dedup_key` that is `new`, so the only options were to
re-ingest and wait for a 26-hour drain to reach X — and X has usually **already settled**, which is how
anyone knows it is wrong, and a settled marker is never handed out by the queue at all.

Three ways in, all equivalent: they launch the same job with a `dedupKey` job parameter, and the reader
claims **that** marker whatever status it is in.

### From a terminal, against a running orchestrator

```bash
curl -sS -XPOST http://localhost:8085/api/prove/marker \
     -H 'Content-Type: application/json' \
     -d '{"dedup_key":"WebGoat/WebGoat|src/main/java/org/owasp/webgoat/A.java|42|DEREF_OF_NULL"}'
# {"started":true,"job":"prove","executionId":41,"reason":"started","dedupKey":"WebGoat/…"}
```

Single-quote the JSON. The key contains `|`, and an unquoted one is a shell pipe — which does not fail,
it sends a *different* key and the run then fails on a marker you never asked about.

`202` started, `409` a prove is already running (wait for it, or stop it), `400` no `dedup_key`. The
`dedupKey` in the reply is what actually arrived, which is how you find out your shell ate the `|`.

### From a cold start, with a debugger attached

```bash
cd pipeline
mvn -pl orchestrator spring-boot:run -Dspring-boot.run.arguments="\
  --fsm.prove.marker=WebGoat/WebGoat|src/main/java/org/owasp/webgoat/A.java|42|DEREF_OF_NULL \
  --fsm.prove.schedule-enabled=false"
```

One command, one marker, nothing else touched. `--fsm.prove.schedule-enabled=false` is not optional
book-keeping: without it the tick thirty seconds later starts a drain of the whole backlog behind the
marker you are looking at. To attach a debugger, add
`-Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"`
and put a breakpoint in `ProveProcessor#process` — the entire chain is one method, in order.

In compose, the same flag is `FSM_PROVE_MARKER` in `.env` (with `FSM_PROVE_SCHEDULE=false`).

### What it does, exactly

| | drain (`/api/prove`, schedule) | one marker (`/api/prove/marker`, `--fsm.prove.marker`) |
|---|---|---|
| which marker | lowest `dedup_key` with status `new` | **the one you named** |
| already settled? | never claimed | **claimed and re-proved** |
| already `proving`? | never claimed | refused, loudly — one workspace |
| not in the backlog? | n/a | run **FAILS**, with the key in the cause |
| how many | `fsm.prove.max-markers-per-run` (0 = all) | exactly one, then the run ends |

Re-proving is **destructive**: the new verdict replaces the old one in both tables. That is what
"reproduce it" means. If the prove hits an infra failure the marker goes back to `new` with its attempt
count untouched, like any other.

A marker that is not there fails the run rather than completing with nothing proved — a `COMPLETED`
execution with a write count of zero is indistinguishable from a drain of an empty queue, and a developer
running one prove cannot act on that. The key is in the cause of the step's
`NonSkippableReadException`; `docker compose logs fsm | grep MarkerNotClaimed` finds it.

---

## The endpoints

| Method | Path | What |
|---|---|---|
| `POST` | `/api/prove` | drain the backlog. No body. `202` + execution id, or `409`. |
| `POST` | `/api/prove/marker` | prove ONE marker. `{"dedup_key": "…"}`. |
| `POST` | `/api/ingest` | replace the backlog from a Svace CSV. `{"repo": "owner/name", …}`. **Clears both tables**, in one transaction, so a refusal costs nothing. |
| `GET` | `/api/state` | the whole dashboard view: markers, artifacts, activity, effort, progress. |
| `GET` | `/api/bug?key=…` | one artifact. `{}` and a 200 when there is none — never a 404. |
| `GET` | `/api/source?repo=…&file=…&line=…` | a window of source from the runner's cached checkout. |
| `GET` | `/healthz` | plain-text liveness, backed by a real query. |
| `GET` | `/actuator/health` | the same probe, per component. Details off unless `FSM_HEALTH_DETAILS=always`. |
| `WS` | `/ws` (STOMP, SockJS too) | `/topic/state`, `/topic/counts`, `/topic/markers`, `/topic/progress` — pushed while a browser is attached. |

An ingest is refused while a prove is running: it clears the table the prove is working in, and the
prove would then settle a row that no longer exists — 45 minutes of prover time for an artifact nothing
explains.

---

## Configuration

Everything under `fsm` binds to **one** class, `config/FsmProperties.java`, and every key names the code
that reads it. **One claimant is the rule, not a coincidence.** If a second `@ConfigurationProperties`
class claims the prefix with a different shape, Boot binds each separately and ignores what it does not
recognise, so knobs bound only to the loser reach nothing at all, silently — a configured value that
never took effect and never said so. `FsmPropertiesTest` fails the build if a second claimant appears,
and asserts each key arrives at its consumer.

Set them as environment variables in compose, or as `--fsm.…=` arguments locally.

### Credentials — process environment only, never a yaml file

| Variable | Required | Unset means |
|---|---|---|
| `QWEN_BASE_URL`, `QWEN_API_KEY`, `QWEN_MODEL` | yes | every model call fails as `undefined/chat/completions`; named in a WARN on start-up |
| `GIT_TOKEN` (`GITHUB_TOKEN` is read when it is unset) | yes | clones and reads run anonymously: no private repositories at all, which fails as markers recorded against files nobody could read |
| `SVACE_BASE_URL`, `SVACE_TOKEN` | no | `Verdict` argues from the code instead of failing |

`Secrets` is the only reader of the process environment, and it deliberately does not go through
Spring's `Environment` — so a key pasted into `application.yml` would not even take effect.

### The pipeline

| Property | Env | Default | Read by |
|---|---|---|---|
| `fsm.runner.base-url` | `FSM_RUNNER_URL` | `http://fsm-runner:8090` | `HttpRunnerClient`, `SourceWindowService` |
| `fsm.runner.timeout` | `FSM_RUNNER_TIMEOUT` | `PT90M` | `HttpRunnerClient`, `ProveProcessor` — **the only knob for this number.** One `/run_test` is a clone plus two Maven builds at the runner's own 20-minute ceiling each. Lowering it does **not** fail safely: the request is abandoned while a build that was going to succeed is still running, and the marker is requeued having burnt the shared workspace. |
| `fsm.runner.connect-attempts` | `FSM_RUNNER_CONNECT_ATTEMPTS` | `3` | `HttpRunnerClient` — the **connect only**. A connection never established means the runner never saw the request, so nothing runs twice. Any failure past the connect is reported once. |
| `fsm.runner.connect-retry-delay-ms` | `FSM_RUNNER_CONNECT_RETRY_MS` | `3000` | `HttpRunnerClient` |
| `fsm.github.api-base-url` | `FSM_GITHUB_API` | `https://api.github.com` | `GithubSourceClient`, `GithubRepoLookup` — both, or branches resolve against a different host from files |
| `fsm.github.timeout-ms` | `FSM_GITHUB_TIMEOUT_MS` | `60000` | `GithubSourceClient` |
| `fsm.github.attempts` | `FSM_GITHUB_ATTEMPTS` | `3` | `GithubSourceClient` — the node's `maxTries`. 429 and 5xx are retried; 401/403/404 never are. |
| `fsm.github.retry-delay-ms` | `FSM_GITHUB_RETRY_MS` | `3000` | `GithubSourceClient` |
| `fsm.llm.attempts` | `FSM_LLM_ATTEMPTS` | `2` | `HttpLlmClient#complete` — a dropped connection only. An endpoint that **answered** is never retried, nor is a completion that ran out of clock. |
| `fsm.llm.retry-delay-ms` | `FSM_LLM_RETRY_MS` | `3000` | `HttpLlmClient` |
| `fsm.feedback.enabled` | `FSM_FEEDBACK` | `false` | `FeedbackStore` — **opt-in.** See [The feedback store](#the-feedback-store). |
| `fsm.feedback.path` | `FSM_FEEDBACK_PATH` | `/data/feedback/gepa-feedback.jsonl` | `FeedbackStore` — the file, not its directory. Needs the writable mount; see below. |
| `fsm.prove.schedule-enabled` | `FSM_PROVE_SCHEDULE` | `true` | `ProveScheduler` |
| `fsm.prove.schedule-delay` | `FSM_PROVE_INTERVAL` | `PT60S` | `ProveScheduler` — not a throughput knob; the job drains the queue itself |
| `fsm.prove.schedule-initial-delay` | `FSM_PROVE_INITIAL_DELAY` | `PT30S` | `ProveScheduler` |
| `fsm.prove.marker` | `FSM_PROVE_MARKER` | *(empty)* | `SingleMarkerRunner` — [prove one marker](#prove-one-marker) |
| `fsm.prove.min-attempts` | `FSM_PROVE_MIN_ATTEMPTS` | `2` | `ProveProcessor` → `Verdict`. How many non-reproductions are worth a "this marker is wrong". |
| `fsm.prove.verdict-enabled` | `FSM_PROVE_VERDICT` | `true` | `ProveProcessor` → `Verdict`. Whether a marker that will not reproduce is **argued** — see [a cheaper run while tuning prompts](#a-cheaper-run-while-tuning-prompts) |
| `fsm.prove.max-markers-per-run` | `FSM_PROVE_MAX_MARKERS` | `0` (drain) | `SuspicionReader` |
| `fsm.prove.skip-limit` | `FSM_PROVE_SKIP_LIMIT` | `25` | `BatchConfig` — infra failures before the execution gives up |
| `fsm.prove.max-infra-strikes` | `FSM_PROVE_MAX_INFRA_STRIKES` | `3` | `ClaimReleaseListener` — consecutive never-answered proves before a marker is parked |
| `fsm.live.enabled` | `FSM_LIVE` | `true` | `LiveWatcher` — off costs only the Effort panel's retry split |
| `fsm.live.watch-ms` / `tick-ms` | `FSM_LIVE_WATCH_MS` / `FSM_LIVE_TICK_MS` | `2000` / `5000` | `LiveWatcher` |
| `server.port` | `FSM_PORT` | `8085` | Boot |
| `management.endpoint.health.show-details` | `FSM_HEALTH_DETAILS` | `never` | Boot — details name the database and its version to anyone who asks |

### A cheaper run while tuning prompts

`FSM_PROVE_VERDICT=false` (`fsm.prove.verdict-enabled`) stops a marker that will not reproduce from
being **argued**. It is for iterating on the reproducer's and the fixer's prompts, where the rebuttal is
an unbounded model call per marker written against prompts that are about to change again.

**It skips the call, not the stage.** `Verdict` also decides the suspicion's next status for every
state, decides the retry, composes the verdicts for markers settled *by execution* (no model call at
all), and carries the anchor onto the row. None of that costs anything, and a marker whose status is
never computed sits in `new` for ever — so the stage always runs. Off, exactly one model call is
skipped, on exactly the three routes that make one:

| route | with the verdict on | with it off |
|---|---|---|
| `not_reproduced` | argued → `false_positive` / `by_design` / `unprovable` | stays `not_reproduced`, settles `rejected` |
| `not-a-bug` | argued → same three | stays `not-a-bug`, settles `rejected` |
| exhausted build (`infra_error` at the ceiling, nothing ever compiled) | argued → `unprovable` | keeps its composed *"NOT SETTLED"* verdict, settles `infra_stuck` |
| `pr_ready`, `pr_rejected`, `needs_review`, `fix_failed`, real `infra_stuck` | composed, no model call | **identical** |

**What it costs, exactly:** the rebuttal text and kind on those three routes, and with them the state
rewrite to `false_positive` / `by_design` / `unprovable`. Nothing else. The reproducer's sampling
budget (`min-attempts`) is untouched — the second sample is still taken, because that is the prompt
being tuned.

**Nothing is stranded and nothing is faked.** Every marker still settles. The rows that were not argued
carry `bugs.verdict_status = 'skipped'` and a note starting `[skipped]` — never `[gap]`, which has to
keep meaning "a state the verdict stage does not route", and never the `verdict writer never answered`
wording on `infra_reason`, which has to keep meaning the endpoint was unreachable. The dashboard prints
the count in the verdicts header and banners the marker panel rather than rendering a skipped verdict as
a finished one.

To argue them afterwards, turn it back on and put them back on the queue:

```sql
UPDATE suspicions SET status = 'new' WHERE note LIKE '[skipped]%';
```

---

## The feedback store

**Off by default.** `FSM_FEEDBACK=true` turns it on.

### What it is

One line of JSON per **settled** marker, appended to `feedback/gepa-feedback.jsonl` at the repository
root, accumulating across runs. Each line is that marker's whole prove:

| Section | What is in it |
|---|---|
| `marker` | identity and every input: checker, severity, file, line, class, method, the Svace description, `prove_attempts`, plus the `anchor` and `anchor_status` the re-anchoring resolved |
| `code_in` | the source the stages actually saw, whole, with the line and the enclosing method that were put in front of them |
| `stages` | the five model calls — `reproducer`, `fixer`, `fix_skeptic`, `pr_maker`, `verdict` — each with the **resolved prompt** that was sent, the **raw reply**, and the parsed result its node extracted |
| `code_out` | the test source and its path, the fix as `old_str`/`new_str` per file, the PR title and body |
| `execution` | both `run_test` replies verbatim — summaries, `test_executed`, `compile_error`, `edit_errors`, `applied_files`, `jdk` — and `red_verified` / `green_verified` / `proven` |
| `judgement` | realness score and its reasons, skeptic verdict, `pr_decision`, terminal state, verdict text and kind, `infra_reason`, and whether the marker settled |
| `feedback` | the critiques (below) |

### What a critique is

Concrete, mostly negative criticism of **one stage's output on one marker**, with a short stable
`kind` so recurrences can be **counted**. `"too many mocks" said once is an opinion; said forty times it
is the evidence that the reproducer's brief should say "prefer real collaborators".`

Almost all of it is **harvested** — the pipeline already computes these and throws them away after one
log line:

| `kind` | Stage it is filed against | Where it comes from |
|---|---|---|
| `excessive_mocking` | reproducer | `TestRealness`: "*N* stub/mock setup(s) for collaborators" — the count is in `context.stubs` |
| `no_state_assertion` | reproducer | `TestRealness`: "asserts only on interactions (verify)" |
| `mocks_subject_under_test` | reproducer | `TestRealness`: the class under test is itself mocked |
| `never_exercises_subject` | reproducer | `TestRealness`: it is never constructed and no static is called |
| `test_did_not_compile` | reproducer | the RED build ran no test |
| `test_did_not_reproduce` | reproducer | the test ran on unpatched code and passed |
| `reply_unparseable` | reproducer / fixer / fix_skeptic | `Parse test`, `Parse fix`, the skeptic's own whitelist |
| `edits_outside_allowed_file` | fixer | `Parse fix`'s source-only allowlist (`fix_rejected`) |
| `edit_not_applied` | fixer | the runner's `edit_errors` |
| `no_edit_applied` | fixer | a claimed fix that changed no file |
| `patch_did_not_compile` | fixer | the GREEN build's `compile_error` |
| `fix_did_not_pass_the_test` | fixer | green build ran the test and it still failed |
| `fix_overfit`, `fix_regression_risk` | **fixer** (source: `fix_skeptic`) | the skeptic's objection, verbatim |
| `pr_rejected` | pr_maker | the curator's stated reason for declining |
| `unrecognised_decision` | pr_maker | a decision that is neither `make` nor `reject` |
| `verdict_produced_no_text` | verdict | the writer answered and argued nothing |

Exactly one is **new**: `pr_draft_incomplete` — the curator answered `make` and left the title or the
body empty. Nothing checks that today, and `Record outcome` falls back to the marker's own title, so the
pull request goes out with a Svace marker id for a subject line.

Every entry carries **two** attributions. `stage` is whose output is being criticised — the prompt you
would edit — and `source` is who noticed. They differ routinely: the skeptic raises *over-fit*, the
**fixer** wrote it.

**Infra is never a critique.** A refused connection, an unresolved branch, a source fetch that returned
nothing, a build killed at its timeout, a judging call that never answered: all recorded, under
`execution` and `judgement`, none of them in `feedback`. A prompt cannot be edited to fix a dead
endpoint, and a count that included them would report the worst day the network had as the worst prompt
in the file.

### The format, and why it is not one JSON document

Newline-delimited JSON. Line 1 is a header saying what the file holds and how to read it; every later
line is one complete record. A single document would have to be read back, re-serialised and rewritten
per marker — quadratic in exactly the thing being accumulated, and it would not fail, it would get
slower for weeks and then stop finishing inside a prove.

- **Creation** is temp file + `ATOMIC_MOVE`, so the file is never observed existing without its header.
- **Appending** is one locked, fsynced, newline-terminated write. A record is only a record once its
  newline is on disk, so a kill mid-write leaves a fragment with no newline — the next append truncates
  it away, and a reader is told by the header to skip a trailing partial line.
- **Reading it while a run is going is safe.** `tail -f`, or `grep`, or:

```bash
# how often each complaint recurs, across every run in the file
jq -r 'select(.feedback) | .feedback[] | "\(.stage)\t\(.kind)"' feedback/gepa-feedback.jsonl \
  | sort | uniq -c | sort -rn
```

### Reading it: the dashboard's "Team guidance" panel

The `jq` above is the answer without a browser. `CritiqueIndex` is the read path — the panel at the top
of the dashboard, and `/api/feedback` behind it.

- **It reads the JSONL, not a copy in H2.** The file accumulates **across runs** and outlives the
  database: H2 lives under `FSM_DB_PATH` and is wiped by a fresh deploy or a move to another host, so a
  table would report this database's complaints and silently omit every earlier one — and "forty times
  over four runs" *is* the feature. Writing to both would also create a state where the file has a
  record the table does not, and the panel and the file are exactly the two things an operator diffs. A
  file that predates the panel is readable today with no migration.
- **A poll never re-reads it.** The index keeps a byte offset and a small projection: a refresh parses
  only the bytes past the offset, folds each record into counts, and throws the record away, so every
  line is parsed **once in the lifetime of the process**. `/api/feedback` is then a walk over a few
  dozen map entries. A pass is capped at 8 MB (an inherited multi-gigabyte file catches up over several
  polls, and the document says `complete: false` until it has), passes are at least 2 s apart, and a
  request that lands mid-pass serves the projection as it stands rather than queueing behind file I/O.
  `CritiqueIndexTest` asserts this on `bytesRead()` and not on the counts — a reader that re-parsed the
  whole file every poll would produce an identical document and simply get slower every day.
- **Only the complaint is kept in memory.** Prompts, replies and source never survive the fold.
- **Off, empty, clean and unreadable are four different sentences.** The store ships **off**, so the
  likeliest render of this panel has no rows for a reason that is not "nothing was wrong". The panel
  always states which it is: switched off (naming `FSM_FEEDBACK`), on with nothing settled yet, records
  with not one complaint in them, or a file that could not be read. None of them is an empty list.
- **The panel groups by `kind`, ordered by recurrence**, showing occurrences *and* distinct markers
  (eleven from one re-proved marker is a bad marker, not a bad prompt), a verbatim example, the markers
  behind it — each opening that marker's modal — and **which `prompts/*.txt` to edit**, derived from
  `PromptSource.Stage` so `fix_skeptic` → `prompts/fix-skeptic.txt` cannot drift.
- **Per marker**, `/api/feedback/marker?key=…` puts that marker's own critiques on its modal, beside
  the test and the diff they are about, each with how often the same kind has recurred elsewhere.

### The other half: what a PERSON thinks, written on the artifact

Everything above is the machine's opinion of the machine's output, and it is very good at the things a
machine can decide. *"I don't like too many mocks, this one and this one are redundant"* is not one of
them: **which** two mocks is a judgement about a specific test, made by somebody reading it. So the
dashboard's marker modal carries a comment box on **the tab whose output is being criticised** —
Marker (the verdict), Reproducer, Fixer, PR maker — and the tab fills in the stage, because the stage
is the answer to *whose prompt has to change* and nobody should have to pick it out of a list. It posts
to `POST /api/comment` and reads `GET /api/comment?key=…`; the marks on the tables come from
`GET /api/comments/index`, which is the server's own `GROUP BY` over the whole table — two numbers and
a map of counts, no comment bodies. Deliberately **not** `GET /api/comments`: that is a *page*, bounded
by `PAGE_MAX`, and a mark computed from a page leaves every marker whose comments fell off the end
looking exactly like a marker nobody has ever written about. `GET /api/comments` remains the read-and-
export endpoint and reports `total_comments` beside the page's own `count`, so a truncated answer is
distinguishable from a complete one. The vocabularies (`stages`, `known_kinds`) and the length limits
are read off those answers and never copied into `app.js`, so a kind added to `CritiqueKind` reaches
the box on the next page load — and the *field names* are pinned: `app.js` reads one name per field and
`ThePageAndTheApiAgreeOnEveryFieldNameTest` checks each one against `MarkerComment.toMap()`, because a
rename absorbed by a tolerant reader renders as a blank column and never as a failure.

Four things about the box are load-bearing, and each is a browser test in
`ui/APersonCanWriteACommentOnAVerdictTest`, because none of them is visible from the server — in all
four the request either succeeds or is never made:

- **It works from the keyboard alone.** The modal takes the focus when it opens, the tabs are
  focusable and answer Enter/Space, and `Ctrl`/`⌘`+`Enter` posts. A box that needs a mouse to reach is
  a box nobody writes a paragraph in.
- **The three-second poll cannot eat a draft.** Every keystroke is kept in a map outside the DOM,
  keyed by marker *and* stage, so a repaint — or a look at the diff on the next tab — restores what
  was typed instead of clearing it. A comment box that loses text is worse than none, because it is
  trusted.
- **A refused write keeps every character and says why.** The box is cleared on a `2xx` and on nothing
  else; the refusal's `reason` is shown, not its slug. A write that stored the row but not the durable
  journal reports that too — amber, not red, so the red box keeps meaning *nothing was recorded*.
- **A commented marker is marked in the markers and verdicts tables** — the count, inside the status
  and kind cells rather than in a new column, so *"which previous markers have negative comments"* is a
  glance rather than 282 modals. It is not a column on purpose: `EveryPanelIsReadableAtProductionVolumeTest`
  measures `scrollWidth` against `clientWidth` at 1440×900 over 282 live-shaped rows, and this page has
  already shipped a table whose last two columns were laid out 911 px past the right-hand edge.

### What is bounded, and what is not

**Only the build logs**, at 8 000 characters, kept from the **end** where javac and surefire put the
line that matters. Nothing else: not a prompt, not a reply, not the source. A Maven reactor with a cold
cache prints megabytes of download lines per build, twice per marker, and none of it is evidence about a
model. Bounding a prompt or a reply would delete the reason the file exists.

### It contains prompts, replies and third-party source

It is **gitignored** (`feedback/*`, with `!feedback/.gitkeep`) for the same reason the H2 store is, and
the file's own first line says so — a warning that lives only in a README is a warning nobody holding
the file ever reads. Do not commit it, publish it or paste it into an issue.

### Deploying it

`deploy/docker-compose.yml` binds the repository at `/data` **read-only** and adds a second, **writable**
bind over one directory of it:

```yaml
      - ../../:/data:ro                    # the repository, read-only, as before
      - ../../feedback:/data/feedback      # …and this one directory, writable
```

The whole repo is deliberately **not** made writable: this service clones and patches third-party code
and runs Maven over it.

The directory must be writable by **uid 10002**, the unprivileged user the image runs as. A bind mount
carries the host's ownership, so on a fresh checkout:

```bash
sudo chown 10002:10002 feedback
```

The store probes this on the way up and logs an `ERROR` naming the fix if it cannot write — rather than
a warning per marker into 26 hours of output nobody is reading.

### It cannot fail a prove

`FeedbackStore.append` swallows and logs. A full disk, a read-only mount or a value that will not
serialise costs one `WARN` line and nothing else — the chain's central rule is that every state reaches
a settled suspicion with a recorded outcome, and a diagnostic is never allowed to strand a marker.
Records are written **after** the verdict and **never** on the `InfraFailure` path: a marker whose
question was never asked has nothing to say about a prompt.

---

## The H2 path warning

**`FSM_DB_PATH` must point at a mounted volume, and the default does not.**

`application.yml` defaults the database to `./data/fsm` — relative to the working directory. That is
right for `mvn spring-boot:run` and it is a **data-loss bug in a container**, because the working
directory there is a writable layer thrown away with the container. A run is 282 markers and 6–26 hours
of model and build time, and losing it is completely silent: the service starts, serves, accepts an
ingest, runs, and simply reads zero after the next `docker compose up -d`. Nothing is red at any point.

So:

- `pipeline/Dockerfile` sets `FSM_DB_PATH=/state/fsm` and prepares `/state` owned by the service uid
  (10002), and does the same for `CACHE=/cache`, which the in-process prover needs for exactly the same
  reason;
- `docker-compose.yml` mounts the **named volume** `fsm-orchestrator-state` there, restates the variable
  next to it, and declares the volume at the bottom of the file.

A bind mount would also work and would put a live database inside the git worktree, which is why the
volume is named. `DeploymentTest` asserts all of that against the compose file, because "the deployment
is wrong" is not something any amount of unit testing can otherwise see.

To throw a run away on purpose:

```bash
docker compose down && docker volume rm fsm_fsm-orchestrator-state
```

### Looking inside the database

The database is **not** reachable over the network by default, and that is deliberate. Do not put
`AUTO_SERVER=TRUE` back on the JDBC URL: H2 implements it by starting a TCP server with
`-tcpAllowOthers` bound to the **wildcard** address, reachable as `sa` with an empty password, read and
write, in front of every marker and every drafted PR body. `H2ExposureTest` pins that the shipped
profile opens no port.

Read-only, any time, no configuration:

```bash
curl -sS localhost:8085/api/state | python3 -m json.tool | head -40
curl -sS 'localhost:8085/api/bug?key=WebGoat/WebGoat|src/main/java/A.java|42|SIZE'
```

A real SQL client against a **running** orchestrator, when those are not enough:

```bash
# in .env — both are required; asking for the server without a password refuses to start
FSM_DB_AUTO_SERVER=true
FSM_DB_PASSWORD=<something>
```

The server is pinned to `127.0.0.1`, so the way in is a tunnel rather than the network:

```bash
ssh -L 9092:127.0.0.1:<port> <host>     # <port> is the `server=` line inside /state/fsm.lock.db
```

Offline (the service stopped), the volume is an ordinary file:

```bash
docker run --rm -v fsm_fsm-orchestrator-state:/state debian:bookworm-slim ls -la /state
```

---

## How it is tested, and what the tests are for

`cd pipeline && mvn -B test` runs both modules. Nothing needs a network, a container or a model.

The suite is not about coverage; each test pins a behaviour whose failure is **silent in production**,
which is this pipeline's whole failure mode — a green run that decided nothing:

- `ClientContractTest` — which failures are thrown and which are returned, against a real socket. A
  dead endpoint recorded as "the model declined" retires a real defect as `not-a-bug`.
- `ProveJobTest`, `VerdictRoutingTest`, `FailClosedTest` — the chain end to end against a scripted
  network, including that a failed model call cannot produce a pull request.
- `FsmPropertiesTest` — one class owns the `fsm` prefix, and every surviving knob reaches its consumer.
- `OnDiskDatabaseTest`, `H2ExposureTest` — the shipped profile really is on disk, and opens no port.
- `DeploymentTest` — the compose service sets `FSM_DB_PATH` under a named volume and can resolve every
  name its outbound calls need.

---

## Browser tests — **not** in `mvn test`, and here is how to run them

> `mvn -B test` **does not run the eight browser tests.** They need a real browser, the browser comes
> from a container image, and a machine with no container runtime must still get a green build. Every
> ordinary build prints one line saying so — grep the log for `[ui] NOT RUN IN THIS BUILD` — and
> `TheBrowserSuiteIsNotInThisBuildTest` fails the build if that notice ever goes stale.

```bash
cd pipeline
orchestrator/playwright/run.sh                                # build + run, the whole suite
orchestrator/playwright/run.sh -Dtest=ThePageIsNotBlankTest   # one class

# …or the two commands it wraps
docker build -f orchestrator/playwright/Dockerfile -t fsm-orchestrator-ui:latest .
docker run --rm --shm-size=1g fsm-orchestrator-ui:latest
```

On the deployment host, with the Nexus network: `MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ orchestrator/playwright/run.sh`.

### Why they exist

**Every defect this dashboard has ever shipped returned HTTP 200.** Every server-side check stayed
green while the page was broken:

1. a duplicate `const settled` made the whole script a `SyntaxError`, so the browser discarded it and
   rendered a **blank page**. Every endpoint answered 200 throughout. Nobody noticed for hours.
2. `/api/bug` went missing. The marker modal said "not proven yet" for 50 markers that *were* proven,
   because the page's `jget()` swallows a 404 and renders the empty state.
3. `/ws` 404s through Caddy's `handle_path` while working on the container's own port, so live push
   silently degrades to the polling fallback. The page still "works", it just never updates by itself.
4. a prove that settled **nothing** was painted green in the activity panel (`COMPLETED` → `success`),
   so a total outage rendered as an unbroken run of healthy executions.

So the bar is not "the page loads". It is: the page renders real data, the numbers on screen match the
database, the interactive paths open real content, a broken script **fails the test**, and the states
that must look different **do** look different.

| test | what it catches |
| --- | --- |
| `ThePageIsNotBlankTest` | defect 1. Asserts rendered content — table rows, header tiles, `typeof render` — never a status code, and fails on **any** browser console or page error. A second test reintroduces the duplicate declaration and requires the suite to go red for it. |
| `TheNumbersOnScreenMatchTheDatabaseTest` | the page does its *own* arithmetic in `render()`; every expected value is asked of the DAO rather than written down, so "a number is displayed" cannot pass for "the right number". |
| `TheMarkerModalShowsARealVerdictTest` | defect 2. Clicks through to a proven marker and reads the verdict, the test source and the fix diff off the screen — then opens one with no artifact and requires the empty state, *not* the previous marker's content. |
| `EveryEndpointThePageCallsAnswersTest` | defect 2, generically: records every response the browser received during a full interaction pass and fails on any 404/5xx, plus asserts the pass actually reached `/api/bug` and friends so it cannot pass by fetching nothing. |
| `ASkippedVerdictIsNotAFinishedOneTest` | `verdict_status='skipped'` must render the "NOT WRITTEN (stage switched off)" banner and never as an argued verdict — including the exhausted-build row, whose composed text reads exactly like a conclusion. |
| `AnExecutionThatSettledNothingIsNotGreenTest` | defect 4. A `COMPLETED` prover run with 0 written must not carry the success styling; one that settled a marker must. Compares **computed colours**, because the CSS rule for the amber dot went missing once and the warning became an invisible 8px gap. |
| `ThePageWorksUnderAPathPrefixTest` | defect 3. Mounts the app under `/dashboard` and requires the socket to actually **connect** from there, every fetch to resolve under the prefix, and every URL literal in `app.js` to be relative. |
| `TheGuidancePanelGroupsRecurringComplaintsTest` | the accumulated criticism is **grouped by `kind` and ordered by recurrence**, with occurrences and *distinct markers* as separate numbers, a quotable example, and the `prompts/*.txt` file each complaint belongs to. Also pins the two "no rows" states that have data behind them — nothing recorded yet, and recorded-and-clean — as different sentences. |
| `FeedbackSwitchedOffNeverLooksLikeNoComplaintsTest` | the store is **opt-in and ships off**, so the likeliest render of this panel has zero rows for a reason that is not "nothing was wrong". Its own Spring context, and it asserts the property actually took effect before asserting anything else. |
| `AMarkersOwnCriticismIsOnItsModalTest` | one marker's own critiques on its modal, verbatim, with the prompt each belongs to and how often the same kind has recurred — and an uncriticised marker showing **its** empty state rather than the last marker's complaints. |

### How it is wired

- **The image**: `orchestrator/playwright/Dockerfile`, based on `mcr.microsoft.com/playwright/java`,
  pinned to the same version as the `playwright` dependency in `pom.xml`. Browsers and their system
  libraries come from the image; Temurin 25 is added on top because the image's own JDK is not 25 and
  this module targets 25. There is no per-machine browser decision and no "skipped because none
  installed" path anywhere in the suite.
- **Not a compose service.** `deploy/docker-compose.yml` declares exactly one running service and
  `DeploymentTest` pins that. A test suite starts, asserts and exits — `docker run --rm` is the
  shape that fits, and it keeps the service list a statement about what is deployed.
- **Excluded by tag, not by path**: `@Tag("ui")`, `<excludedGroups>` by default, `-Pui` to invert the
  filter and run the browser suite alone. The classes still live in `src/test/java` and are **compiled
  by every build**, so they cannot rot out of step with `DashboardService`.
- **Self-contained**: `@SpringBootTest(webEnvironment = RANDOM_PORT)` against a file-backed H2 in a
  throwaway directory, seeded through the real DAOs (`Seeds`). It does not touch the live stack, the
  runner or the engine, and needs no network beyond its own app.
