# fix-java-bugs — n8n fleet

A plain **n8n workflow** that orchestrates the bug-finding fleet with **Docker**,
replacing the bash `head_watchdog.sh`/`head_worker.sh`. One container == one repo;
the fleet holds up to `MAX` running and drains a queue seeded from **synthetic**
and **warm** repos.

```
n8n (Schedule Trigger, every 2 min)
   └─ HTTP Request  →  dispatch service (:8080)
                          └─ dispatch.sh:  count running fjbn8n-* containers
                             while running < MAX and queue not empty:
                               pop a repo → docker run -d --name fjbn8n-<repo> bugfind <repo>
```

n8n stays a pure scheduler (Schedule + HTTP Request, no shell) — the docker/queue
work lives in a tiny `dispatch` sidecar that has the socket. Each `bugfind`
container runs a fresh, stdlib-only FIND → PROVE → FIX per Java file against the
vLLM endpoint and writes `results_n8n/<repo>.json`.

## Pieces

| Path | What |
|---|---|
| `bugfind/find_bugs.py` + `Dockerfile` | fresh per-repo pipeline (python + git, no OpenHands). Bounds Qwen thinking with `enable_thinking`/`thinking_budget`. |
| `dispatch.sh` | POSIX-sh "maintain MAX containers, drain the queue" step (flock-guarded, idempotent). |
| `dispatch-server/` | ~20-line HTTP wrapper around `dispatch.sh` (has the docker socket). |
| `n8n/workflow.json` | Schedule → HTTP Request(`http://dispatch:8080/`) → Status. |
| `n8n/docker-compose.yml` | `dispatch` + stock `n8n` (port **5679**; 5678 is taken by another n8n). |
| `seed.sh` + `warm_repos.txt` | build `queue.txt` = `synth:*` (from `synth_bench.py`) + warm repos. |

## Repo forms in the queue

- `owner/repo`   — shallow-cloned from GitHub (warm repos).
- `synth:<name>` — local planted-bug repo at `$WORK/data/repos/<name>` (from `synth_bench.py`); ground truth in `results/synth_oracle.json`.

## Run (on mh)

```bash
cd ~/fix-java-bugs/n8n-fleet
docker build -t bugfind:latest bugfind/          # per-repo image
#   .env has QWEN_BASE_URL / QWEN_API_KEY / QWEN_MODEL / MAX_FILES / PROVE_TOP / THINK_BUDGET
bash seed.sh                                      # queue.txt = synthetic + warm
cd n8n
docker run --rm -v "$PWD/n8n-data":/d alpine chown -R 1000:1000 /d   # n8n runs as uid 1000
docker compose up -d --build                      # dispatch + n8n
docker exec fjb-n8n n8n import:workflow --input=/data/n8n/workflow.json
docker exec fjb-n8n n8n update:workflow --id=fjbdispatcher001 --active=true
docker restart fjb-n8n                             # register the schedule
# UI: http://<host>:5679   (the workflow then runs itself every 2 min)
```

Tune: `MAX` (compose, concurrent containers), `MAX_FILES` / `PROVE_TOP` /
`THINK_BUDGET` (`.env`). Results land under `~/fix-java-bugs/results_n8n/` for
downstream (human) verification. The vLLM endpoint is the public `QWEN_BASE_URL`,
so the `bridge` network gives containers both GitHub and inference reachability.

## Environment notes (hard-won)

- The `n8nio/n8n:latest` image is **hardened Alpine**: no package manager, runs as
  **uid 1000**, task **runners are mandatory** in 2.30 (`N8N_RUNNERS_ENABLED` is
  ignored). The **Execute Command** node is unreliable under that setup — hence the
  HTTP-Request → dispatch-sidecar design.
- `n8n-data` must be writable by uid 1000 or workflows don't persist across restart.
