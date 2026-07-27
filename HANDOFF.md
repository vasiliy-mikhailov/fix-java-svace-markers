# fix-java-svace-markers — handoff

**Written 2026-07-27. For a fresh session picking this up.**

## The idea in one paragraph

Take the [fix-java-bugs](https://github.com/vasiliy-mikhailov/fix-java-bugs) pipeline and **swap its input**. Instead of an LLM *suspector* generating suspicions, feed it a **Svace marker list** (a static-analysis report) plus the target repo (WebGoat). For each marker, run the existing **red → fix → green → PR** loop: write a test that fails on the unpatched code, write the fix, prove it passes, draft a PR. **If a failing test is impossible to write** (the bug can't be reproduced), don't just drop it — **emit a written explanation of why the marker is a false positive**, optionally enriched by **calling a Svace endpoint** for the marker's full detail (message, trace, checker rationale) to argue the rebuttal against.

So this project turns a Svace report into two useful outputs per marker:
1. **A proven fix + PR draft** (red→green), or
2. **A justified false-positive verdict** (why the finding doesn't hold), citing the marker's own trace.

## What already exists (this fork = the starting point)

This repo is a **faithful copy of the fix-java-bugs `n8n-fleet`** — the detect → dedup → prove pipeline, unmodified. Nothing Svace-specific is built yet. The prove loop (reproducer → fixer → skeptic → PR maker) is exactly what you'll reuse; the suspector + dedup are what you'll replace.

Locations (all three set up):
- **GitHub:** `vasiliy-mikhailov/fix-java-svace-markers` (private)
- **mh:** `~/fix-java-svace-markers`
- **local:** `/Users/vmihaylov/projects/fix-java-svace-markers`
- **Subdomain (in delivery):** `fix-java-svace-markers.mikhailov.tech` — the dashboard for this project.

### The code you're reusing (`n8n-fleet/`)
- `n8n/agentic/gen_prover.py` → generates `workflow_prover.json`. **This is the heart you keep.** The reproducer/fixer/skeptic/PR-maker chain, the runner lease, the robust JSON extractor, the JDK auto-detect handling.
- `n8n/agentic/gen_orchestrator.py` → the scan driver (enumerate files → loop → call suspector → dedup). **You'll gut most of this** — marker ingestion replaces file-walking.
- `n8n/agentic/gen_suspector.py` → the LLM detector. **You'll replace this** with a marker ingester (or bypass it entirely).
- `n8n/agentic/gen_dedup.py` → **probably drop** (Svace markers are already de-duplicated by the tool).
- `n8n/agentic/gen_setup.py` → the Data Table schemas. **You'll extend the `suspicions`/`bugs` schema** with marker fields (checker, svace_severity, marker_id, verdict_text).
- `n8n/agentic/versions.py` → single source of truth for stage versions.
- `java-runner/runner.py` + `NavServer.java` → the sidecar: clone, symbol-resolve, compile & run tests. **Reuse as-is** (the JDK auto-detect and lease endpoints are already there).
- `dashboard/dashboard.py` → the read-only dashboard. **Reuse, retarget** to the new subdomain and add a "false-positive verdicts" panel.

A full explanation of how the parent pipeline works is in the fix-java-bugs artifact (the "how the pipeline works" explainer) and `n8n-fleet/README.md`.

## What to build

### 1. Marker ingestion (replaces suspector + dedup)
- **Input:** the Svace CSV. Columns: `Severity,Checker,File,Line`. Example row:
  `"Major","DEREF_AFTER_NULL","/builds/gitlab/.../src/main/java/org/owasp/webgoat/container/LessonTemplateResolver.java","61"`
- **Normalize the path.** Markers use the GitLab CI build path `/builds/gitlab/drit_digital_trace/owasp-webgoat/src/main/java/...`. Strip to repo-relative `src/main/java/...` so it matches the GitHub checkout.
- **Map checker → category + hint.** The CSV is thin — the checker name is the only signal for *what kind* of bug. Build a `checker → {category, what-it-means}` map so the reproducer knows what to target. Starter mapping (from the WebGoat report of 356 markers):
  - `PROC_USE.VULNERABLE`, `FB.COMMAND_INJECTION` → command injection
  - `HANDLE_LEAK*`, `FB.OBL_UNSATISFIED_OBLIGATION`, `FB.ODR_OPEN_DATABASE_RESOURCE` → resource leak
  - `DEREF_OF_NULL*`, `DEREF_AFTER_NULL`, `FB.NP_NULL_ON_SOME_PATH*` → null dereference / NPE
  - `FB.PATH_TRAVERSAL_IN`, `TAINTED_PTR*` → path traversal / taint
  - `FB.HARD_CODE_PASSWORD` → hardcoded secret
  - `FB.PREDICTABLE_RANDOM`, `FB.DMI_RANDOM_USED_ONLY_ONCE` → predictable randomness / crypto
  - `FB.EI_EXPOSE_REP*` → mutable internal state exposure
  - `FB.DM_DEFAULT_ENCODING` → platform-default encoding
  - `COLLECTION.WRONG_ARG_TYPE`, `FB.GC_UNRELATED_TYPES` → collection/type misuse
- Each marker becomes one `suspicion` row: `{file, line, category, svace_checker, svace_severity, status:'new', title:"<checker> at <file>:<line>", marker_id}`.
- **Ingestion path:** a webhook that takes the CSV (or a path to it) + the repo + branch/commit, parses it, and upserts one suspicion per marker. No LLM needed here.

### 2. Prove loop (reuse, lightly adapt)
- The prover already drains `status='new'` suspicions one at a time under the lease. It mostly works unchanged. Adaptations:
  - The reproducer prompt should include the **Svace checker + its meaning + the exact line**, so it writes a test targeting *that* specific claim, not a re-derived one.
  - Keep the isolation (build in `/cache/<repo>`, never the read clone), the lease, the JDK auto-detect, the robust JSON extractor.

### 3. False-positive justification (the new branch)
- Today, a marker whose test never goes red lands as `not_reproduced` / `infra_error`. **Add a real terminal state: `false_positive` with a `verdict_text`.**
- Trigger: reproducer returns `can_prove=false`, OR N reproduce attempts never achieve red **while the build succeeds** (distinguish from a build failure — that's still infra, retry). A test that compiles and *passes* on unpatched code is evidence the marker may not hold.
- Generate `verdict_text`: an LLM call that, given the source, the marker, and (if available) the Svace trace, explains **why the flagged path cannot actually exhibit the bug** — the guard that already exists, the unreachable branch, the sanitizer upstream, etc. This is a rebuttal, not a dismissal: it should be specific enough that a human reviewer can accept or reject it.
- **The Svace endpoint (OPEN — see below).** The user wants to "call a svace endpoint to help fill this text out against the marker." The thin CSV lacks the message + trace; Svace's own service has them. Fetch the marker's full detail (checker rationale, the tainted-data trace, the source/sink) and feed it into the justification prompt so the rebuttal argues against Svace's actual reasoning, not a guess.

### 4. Dashboard
- Retarget to `fix-java-svace-markers.mikhailov.tech`.
- Add a **verdicts** view: per marker → `pr_ready` / `needs_review` / `false_positive (+ text)` / `not_reproduced` / `infra`. The false-positive text is a first-class output here, not a footnote.

## Coexistence with fix-java-bugs on mh (do this FIRST)

Both projects would run on the same host and **will collide** unless renamed. Before `docker compose up`, rename every shared identifier:
- **Container names:** `fjb-n8n`, `fjb-java-runner`, `fjb-dashboard` → e.g. `fsm-n8n`, `fsm-java-runner`, `fsm-dashboard`.
- **Ports:** n8n is on `5679` → pick a free port (e.g. `5680`). Check the dispatch server port too.
- **n8n workflow IDs:** `fjbprover00001`, `fjbsuspector0001`, `fjborchestr0001`, `fjbdedup000001`, `fjbsetup00000001` → new IDs. (They're hardcoded in the `gen_*.py` files and cross-referenced in webhook calls.)
- **Data Table IDs:** the suspicions/bugs/scan_files/method_runs table IDs are hardcoded in the generators (`SUSPICIONS_TABLE`, `BUGS_TABLE`, etc.). A fresh n8n instance will mint new ones — run setup first, then paste the new IDs in.
- **Webhook paths:** `/webhook/scan`, `/webhook/prove`, `/webhook/dedup`, `/webhook/setup` are fine to reuse *if* on a separate n8n instance/port; otherwise namespace them.
- **Volumes/caches:** separate the `/cache` volume and the n8n-data dir so the two pipelines don't share build workspaces or the DB.
- **Caddy:** add a block routing `fix-java-svace-markers.mikhailov.tech` → the new dashboard + n8n, with its own basic_auth. (fix-java-bugs uses the `inference-caddy` container.)

Simplest path: treat this as a **second, fully-namespaced stack** (`fsm-*` everywhere, own port, own volumes) on the same host. Everything is parameterized in the `gen_*.py` generators, so a find-and-replace pass plus a fresh `setup` run gets you there.

## Environment & hard-won gotchas (carried from the parent project)

- **Deploying a workflow change = import + publish + restart n8n**, and **the n8n restart kills any in-flight run** (the scan/prove executions die; the Data Table rows survive). Plan deploys around this.
- **A recreated container is required to pick up a rebuilt image** — `docker restart` reuses the OLD image. Use `docker compose up -d --no-deps <svc>`.
- **WebGoat needs JDK 25.** The runner auto-detects `release version N not supported` and retries with the right JDK (8–25 available in the `bjv-alljdk` image). Marker line numbers are **tied to the exact WebGoat commit Svace scanned** (the GitLab `drit_digital_trace/owasp-webgoat` mirror) — pin that commit when cloning from GitHub, or File:Line won't line up.
- **Two clones, never one:** suspector/reader uses `/cache/fs/<repo>` (read-only); the prover builds & patches in `/cache/<repo>` (separate). Keep this.
- **The runner lease** (`POST /lease` / `/lease/release`, in `runner.py`) serializes proving to one-at-a-time. The empty-queue path must always release the lease (there's a `Has suspicion?` gate for exactly this). TTL is 1800s.
- **Robust JSON extractor** (`REL_JSON_FN` in `gen_prover.py`, `parseVerdict` in `gen_suspector.py`): fenced → key-anchor → both-ends → delimiter-stack repair. Reuse it for any new LLM reply parsing; the naive `indexOf('{')…lastIndexOf('}')` will bite you.
- **The fixer must never edit test files** (structural guard drops edits under `src/test`). Preserve this — it's what stops a fix from grading its own test.
- **Upstream PRs carry NO AI attribution.** PRs are only ever *drafted*, never auto-opened.
- **n8n Data Table filter reads `keyValue`, not `value`.**
- Qwen (via vLLM) needs `extra_body` `enable_thinking` + a token ceiling; thinking tokens count against `max_tokens`, so cap generously (32k in the parent) or a large reply truncates mid-JSON.

## Open questions for you / the user

1. **The Svace endpoint.** What is it — Svace's REST API, a self-hosted Svace server, or a scraper of the marker web UI? What auth? What does a single-marker detail response contain (message, trace, source/sink)? This defines how good the false-positive rebuttals can be. *Get this from the user before building step 3.*
2. **Which WebGoat commit** do the markers correspond to? Needed to make File:Line map correctly.
3. **Do markers in `src/test` / `src/it` get proven** or ignored? (The parent only ever touches `src/main`.) The report has 74 test/integration markers.
4. **Volume of work:** 356 markers, ~1 prove per few minutes, serial under the lease. That's many hours per full run. Consider prioritizing by severity (3 Critical, 61 Major first) or parallelizing the build workspace (multiple isolated clones + a small lease pool) if throughput matters.

## First steps for the next session

1. Get the Svace endpoint details + the WebGoat commit from the user (blocks steps 1 & 3).
2. Namespace the stack (`fsm-*`, new port, new volumes) so it coexists with fix-java-bugs on mh.
3. Bring up n8n + java-runner + dashboard fresh; run `setup`; capture the new Data Table IDs.
4. Build the marker ingester (CSV → suspicions); confirm one marker flows into the existing prove loop end-to-end.
5. Add the `false_positive` + `verdict_text` branch; wire the Svace endpoint enrichment.
6. Point the dashboard at the new subdomain; add the verdicts panel.
