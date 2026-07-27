# fix-java-svace-markers — handoff

**Written 2026-07-27. For a fresh session picking this up. This is a to-do list, ordered.**

## The goal

Turn a **Svace static-analysis report** into two useful outputs per marker:
1. a **proven fix + drafted PR** — a test that fails on the unpatched code (red), a fix that makes it pass (green); or
2. a **justified false-positive verdict** — written text explaining why the marker doesn't hold, when no failing test can be written.

**This repo already _is_ that pipeline** — a copy of [fix-java-bugs](https://github.com/vasiliy-mikhailov/fix-java-bugs), cloned and running-ready (see "Already done" below). You are **not** forking, taking, or rebuilding anything. The entire remaining job is to **swap its input and add one output**: make Svace markers the suspicion list in place of the LLM suspector, and emit a written false-positive verdict when a marker won't reproduce. The prove loop (reproduce → fix → PR) stays as-is.

## Already done — do NOT redo

- This repo is cloned in all three places: GitHub `vasiliy-mikhailov/fix-java-svace-markers` (private), mh `~/fix-java-svace-markers`, local `/Users/vmihaylov/projects/fix-java-svace-markers`. **You start from an existing checkout.**
- It is a faithful, unmodified copy of the fix-java-bugs `n8n-fleet` pipeline. Nothing Svace-specific is built yet.
- The input fixture is committed: `data/svace/webgoat-markers-356.csv` (356 WebGoat markers).
- WebGoat is **not** cloned by hand — the java-runner clones it automatically on first run.

---

## Step 0 — Get two answers from the user (blocks Steps 4 & 5)

You cannot finish without these, so ask first:
1. **The Svace endpoint** — what is it (REST API / self-hosted Svace server / marker-detail URL)? Auth? What does a single-marker detail response contain (message, taint trace, source→sink)? This is what makes false-positive rebuttals specific instead of guesses.
2. **The WebGoat commit** the markers were scanned against (the GitLab `drit_digital_trace/owasp-webgoat` mirror). Marker `File:Line` only maps if you check out that exact commit.

Steps 1–3 can proceed in parallel while you wait.

## Step 1 — Stand up a namespaced stack on mh

Both pipelines share a host and **will collide** unless renamed. Fork the runtime identity before `docker compose up`:
- Containers `fjb-n8n / fjb-java-runner / fjb-dashboard` → `fsm-n8n / fsm-java-runner / fsm-dashboard`.
- n8n port `5679` → a free port (e.g. `5680`); dispatch server port too.
- n8n workflow IDs (`fjbprover00001`, `fjbsuspector0001`, `fjborchestr0001`, `fjbdedup000001`, `fjbsetup00000001`) → new IDs, in the `gen_*.py` files.
- Docker volumes + the `/cache` dir + the n8n-data dir → separate names, so the two stacks never share a build workspace or DB.
- Create `.env` on mh from `.env.example` (real `QWEN_*` + `GITHUB_TOKEN`).
- Add a Caddy block: `fix-java-svace-markers.mikhailov.tech` → this stack's dashboard + n8n, with its own basic_auth. (fix-java-bugs uses the `inference-caddy` container.)

**Done when:** the new n8n is reachable on its own port, the dashboard answers at `fix-java-svace-markers.mikhailov.tech`, and it does not touch fix-java-bugs' containers/DB.

## Step 2 — Initialize the schema

- Run the `setup` workflow to create the Data Tables. A fresh n8n mints **new** table IDs.
- Paste those new IDs into the generators (`SUSPICIONS_TABLE`, `BUGS_TABLE`, etc.) and regenerate.
- Extend the schema for markers: add `svace_checker`, `svace_severity`, `marker_id` to suspicions; add a `verdict_text` column (and a `false_positive` state) to bugs.

**Done when:** the tables exist, are empty, and the generators reference the new IDs.

## Step 3 — Build the marker ingester (replaces suspector + dedup)

Replace file-walking + LLM detection with a CSV reader. A webhook that takes `{csv_path, repo, commit}` and upserts **one suspicion per marker**:
- **Normalize the path.** Strip the CI prefix `/builds/gitlab/drit_digital_trace/owasp-webgoat/` → repo-relative `src/main/java/...`.
- **Map checker → category + a one-line meaning** so the reproducer knows what to target. Starter map (from the 356-marker report):
  - `PROC_USE.VULNERABLE`, `FB.COMMAND_INJECTION` → command injection
  - `HANDLE_LEAK*`, `FB.OBL_UNSATISFIED_OBLIGATION`, `FB.ODR_OPEN_DATABASE_RESOURCE` → resource leak
  - `DEREF_OF_NULL*`, `DEREF_AFTER_NULL`, `FB.NP_NULL_ON_SOME_PATH*` → null deref / NPE
  - `FB.PATH_TRAVERSAL_IN`, `TAINTED_PTR*` → path traversal / taint
  - `FB.HARD_CODE_PASSWORD` → hardcoded secret · `FB.PREDICTABLE_RANDOM`, `FB.DMI_RANDOM_USED_ONLY_ONCE` → weak randomness
  - `FB.EI_EXPOSE_REP*` → mutable-state exposure · `FB.DM_DEFAULT_ENCODING` → default encoding
- One row each: `{file, line, category, svace_checker, svace_severity, status:'new', title, marker_id}`.
- Drop `dedup` (Svace already de-duplicates). Decide whether to skip `src/test`/`src/it` markers (parent only touches `src/main`; the report has 74 test markers).

**Done when:** ingesting `data/svace/webgoat-markers-356.csv` produces the expected suspicion rows with correct file/line/category (282 for main-only).

## Step 4 — Prove one marker end-to-end

The prover already drains `status='new'` one-at-a-time under the lease — keep it. Only adapt the **reproducer prompt** to include the Svace checker + its meaning + the exact line, so it targets *that* claim.

**Done when:** one marker flows all the way to a `bugs` row — either `pr_ready`/`needs_review` (red→green) or a real verdict.

## Step 5 — Add the false-positive verdict branch (the new bit)

Today a marker that won't reproduce lands as `not_reproduced`. Add a proper terminal outcome:
- **Trigger:** reproducer returns `can_prove=false`, OR the build **succeeds** but the test passes on unpatched code after N tries. (A build that never compiled is still infra → retry, not a verdict — the parent already distinguishes this.)
- **Produce `verdict_text`:** an LLM call given the source + the marker + the Svace trace (Step 0's endpoint), explaining *why the flagged path can't exhibit the bug* — the guard already present, the unreachable branch, the upstream sanitizer. A rebuttal specific enough for a human to accept or reject, not a shrug.
- **Enrich via the Svace endpoint:** the CSV is thin (checker + line only). Fetch the marker's message + taint trace so the rebuttal argues against Svace's actual reasoning.

**Done when:** a non-reproducible marker yields a specific written rebuttal citing the trace, stored as `false_positive` + `verdict_text`.

## Step 6 — Dashboard

- Retarget `dashboard/dashboard.py` to `fix-java-svace-markers.mikhailov.tech`.
- Add a per-marker **verdicts** view: `pr_ready` / `needs_review` / `false_positive (+ text)` / `not_reproduced` / `infra`. The false-positive text is a first-class output, not a footnote.

---

## Reference

### What you reuse vs. replace (in `n8n-fleet/`)
- **Reuse unchanged:** `java-runner/` (clone, symbol-resolve, compile & run, JDK auto-detect, lease endpoints); the prover chain in `gen_prover.py` (reproducer → fixer → skeptic → PR maker), its runner lease, its robust JSON extractor.
- **Replace:** `gen_suspector.py` + `gen_orchestrator.py` (LLM detection + file-walking) → marker ingester. **Drop:** `gen_dedup.py`.
- **Extend:** `gen_setup.py` (schema), `dashboard/dashboard.py` (subdomain + verdicts panel), `versions.py` (bump stage versions).

### Gotchas carried from the parent (all still apply)
- **Deploy = import + publish + restart n8n**, and the **restart kills any in-flight run** (Data Table rows survive). Plan around it.
- **A rebuilt image needs `docker compose up -d --no-deps <svc>`** — `docker restart` reuses the OLD image.
- **WebGoat needs JDK 25.** The runner auto-detects `release version N not supported` and retries with the right JDK (8–25 in the `bjv-alljdk` image).
- **Two clones, never one:** read from `/cache/fs/<repo>` (never mutated), build/patch in `/cache/<repo>` (separate).
- **Runner lease** (`POST /lease` / `/lease/release`) keeps proving serial; the empty-queue path must always release it (there's a `Has suspicion?` gate for that); TTL 1800s.
- **The fixer must never edit `src/test`** (structural guard) — that's what stops a fix from grading its own test.
- **Robust JSON extractor** (`REL_JSON_FN` in `gen_prover.py`) — reuse it for any LLM reply parsing; the naive `indexOf('{')…lastIndexOf('}')` will bite you.
- **PRs are only ever drafted, never auto-opened, and carry NO AI attribution.**
- **n8n Data Table filter reads `keyValue`, not `value`.** Qwen needs a generous `max_tokens` (thinking counts against it; parent uses 32k).

### Scale
356 markers, serial under the lease at ~minutes each = many hours per full run. Consider proving by severity first (3 Critical, 61 Major), or a small pool of isolated build clones + a lease pool if throughput matters.
