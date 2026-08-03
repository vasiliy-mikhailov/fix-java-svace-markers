# Worked example — WebGoat, 282 markers

The input, the output, and what to expect if you run it yourself. This is a real run, not a sample:
from empty volumes on 2026-08-01, ~28 hours unattended.

| file | what it is |
|---|---|
| `input-markers.csv` | the Svace report, 356 rows, exactly as the scanner emitted it |
| `results-282.csv` | one row per marker: what the pipeline decided and why |

---

## The input

Four columns, and the paths are the scanner's, not yours:

```csv
Severity,Checker,File,Line
"Normal","COLLECTION.WRONG_ARG_TYPE","/builds/gitlab/drit_digital_trace/owasp-webgoat/src/main/java/org/owasp/webgoat/container/service/LessonMenuService.java","64"
```

356 rows in, **282 markers** out. The 74 dropped are test files — `pathPrefix: "src/main/java/"` excludes
them, because a marker in a test is not a defect in the product. Nothing else is filtered.

Note the absolute CI path. The ingester keys on the `src/main/java/` segment, so a report from any build
machine works without rewriting.

---

## What came out

```
verified        104     a test failed red on the unpatched code and went green with the fix
false_positive  106     the test ran and PASSED unpatched — the marker does not describe a defect
unprovable       26     no test ever compiled; recorded as not-proven, never as an exoneration
reproduced       24     red established, but no source-only fix went green
by_design        19     the behaviour is intentional, argued from the source
infra_stuck       3     the pipeline could not get far enough to judge — retried, then parked
```

and, per artifact:

```
pr_ready         87     a drafted pull request, reviewed by the skeptic and the curator
false_positive  106
unprovable       26
fix_failed       24
by_design        19
needs_review     12     proven, but the diff or the test is not trustworthy enough to propose
pr_rejected       5     proven and fixed, but the curator declined to propose it
infra_error       3
```

**All 282 markers settled with a written verdict. 87 drafted PRs.**

### The number that matters

**104 markers carry both `red_verified` and `green_verified` — the same 104 that are `verified`.** That
is not a coincidence and it is the point of the whole design: `verified` is not a label the model chose,
it is the count of markers where the *runner* observed a genuine failing test before the patch and a
genuine passing one after, parsed from JUnit XML rather than scraped from console output.

`false_positive` being the largest bucket is the expected shape for a static analyser on a real
codebase, and it is where most of the value is: 106 markers a human does not have to read, each with an
argued explanation rather than a dismissal.

---

## Reading `results-282.csv`

| column | meaning |
|---|---|
| `status` | the marker's terminal state (the six above) |
| `state` | the artifact's state (the eight above) — finer-grained |
| `red_verified` / `green_verified` | what the runner actually observed. Both true = proven |
| `verdict_kind` | `true-positive`, `false-positive`, `by-design`, `unprovable` |
| `verdict_text` | the argument, truncated to 300 chars here; full text is in the dashboard |
| `anchor_status` | whether the marker's line still resolves to a method after re-anchoring |
| `prove_attempts` | 2 for most non-reproducing markers — the pipeline takes a second sample before settling |
| `test_path` | the JUnit test that was written, where it was written |
| `pr_title` | populated on every row, but only means *a PR was drafted* when `state` is `pr_ready` or `pr_rejected`. Elsewhere it is the marker's own title (`false_positive`) or a title drafted before the prove failed (`unprovable`). **Read `state`, not the presence of a title.** |

`anchor_status` is worth a look. Svace reports a line number against the tree it scanned; the repository
has moved since. The pipeline re-anchors each marker to a *method* and records whether it could —
`no-method` — 39 of the 282 — means the line no longer sits inside one, a fact about the report's age rather than a
failure.

---

## Reproducing it

```bash
cd pipeline/deploy && cp .env.example .env    # QWEN_* and GIT_TOKEN
docker compose up -d

# send the report in the request — this is the shape to copy for your own reports,
# and it needs no access to any volume the container reads
curl -s -X POST localhost:8085/api/ingest \
  -F 'csv=@input-markers.csv' \
  -F 'repo=WebGoat/WebGoat' -F 'branch=main' -F 'path_prefix=src/main/java/'

curl -s -X POST localhost:8085/api/prove
```

This example's report also ships in the repository, so it can equally be named by the path it already
has inside the container — `-d '{"csvPath": "/data/data/svace/webgoat-markers-356.csv", …}'`. That only
works because the file is on the `/data` mount; your own report will not be, which is why the upload
above is the form worth learning.

**The ingest is safe to re-run, and you will want to.** This run is roughly a day long and the
container will be restarted inside it — for a deploy, after an OOM, on a host reboot. Re-running the
command above **adds**: every marker already in the backlog keeps its status, its verdict, its
artifact and its attempt count, so nothing you have paid for is lost, and a marker the report raises
that is not yet queued is picked up. `curl -s localhost:8085/api/ingest/last` says exactly what the
last one did (`"added": 0, "kept": 282`).

To start these 282 markers over from scratch, ask for it and name the number of settled markers you
are throwing away — `-F 'reset=true' -F 'reset_confirm=<n>'`. Send the wrong number and the refusal
tells you the right one. Comments you wrote on a verdict survive either way.

Roughly a day at ~10 markers/hour, most of it Maven. Watch it at `http://localhost:8085/`.

**You will not get these numbers exactly.** Five model calls per marker means run-to-run variance, and
the repository moves. What should hold is the *shape*: `false_positive` largest, `verified` close
behind, `needs_review` small, and every marker settling with a verdict. On the validated comparison used
to accept these numbers, `false_positive` (106) and `by_design` (19) matched exactly while `verified`
moved by 3 — that is the size of drift to expect.

If `needs_review` is large, the model endpoint is probably unreachable: the judging stages fail *closed*
and answer HTTP 200, so the run history stays green while nothing is really being judged. See the model
endpoint section in [DOCKER.md](../../DOCKER.md).

---

## One marker, end to end

`AttackResult.java:19`, `FB.EI_EXPOSE_REP` — a getter returning a reference to internal mutable state.

1. **Reproducer** wrote `AttackResultFsmProofTest`, which mutates the list returned by the getter and
   asserts the object's own state changed.
2. **run_test** on unpatched code: `tests=2 failures=2`, `test_executed: true`, `BUILD FAILURE`. Red.
3. **Fixer** returned defensive copies in the constructor and the getter.
4. **run_test** with the patch: `tests=1 failures=0`, `BUILD SUCCESS`. Green.
5. **Skeptic** checked the test actually exercises the fix rather than passing vacuously.
6. **PR maker** drafted the title and body. **Nothing was pushed.**

That marker is `pr_ready` in the CSV. Its test source, fix diff and PR body are all on its dashboard
modal, and this exact sequence was replayed independently against the runner to confirm the red is a
real red — not an assertion in a database.
