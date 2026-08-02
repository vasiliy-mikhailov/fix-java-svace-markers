# Running it with Docker — three ways

The [README](README.md) quickstart builds the images from source. That is the right default, but it is
not the only option and it is the slowest one: the runner image provisions five JDKs and Maven, so a
cold build is long and needs network access to Adoptium, Apache and Maven Central.

Pick by what the target machine actually has.

| | needs | build time | image transfer |
|---|---|---|---|
| **A. Build from source** | git, Docker, internet | long (cold), minutes (warm) | none |
| **B. Hand over saved images** | Docker only | none | ~2.6 GB |
| **C. Registry** | Docker, a registry | once, by you | pull |

All three end at the same place: three containers, `http://localhost:8085`.

---

## A. Build from source

The README quickstart. Use it when the machine has git and internet.

```bash
git clone git@github.com:vasiliy-mikhailov/fix-java-svace-markers.git
cd fix-java-svace-markers/pipeline/deploy
cp .env.example .env          # fill in QWEN_* and GITHUB_TOKEN
docker compose build
docker compose up -d
```

**If your Maven repository is a mirror on a private Docker network**, `docker compose build` cannot
reach it — Compose cannot join a network at build time, and BuildKit rejects `--network` for custom
networks. Use the legacy builder, from the reactor root:

```bash
cd ../..                      # the repo root
DOCKER_BUILDKIT=0 docker build --network <your-net> \
  --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ \
  -f pipeline/orchestrator/Dockerfile -t fsm-orchestrator:latest .
```

Repeat for `pipeline/engine/Dockerfile` and `pipeline/runner/Dockerfile`. The build context is the
**reactor root**, not the module directory: `orchestrator` and `runner` resolve
`tech.mikhailov.fsm:engine` from the reactor and nothing publishes that jar.

---

## B. Hand over saved images — no build, no source, no internet

Best for giving the pipeline to someone who should not have to care how it is built, or for a machine
with no route to Maven Central.

**You, once:**

```bash
docker save fsm-engine:latest fsm-orchestrator:latest fsm-runner:latest \
  | gzip > fsm-images.tar.gz          # ~2.6 GB uncompressed
```

Send them that file, plus three small things from the repo:

```
pipeline/deploy/docker-compose.yml
pipeline/deploy/.env.example
data/svace/<your-report>.csv          # or their own
prompts/                              # optional: only if they want to edit prompts
```

**Them:**

```bash
docker load < fsm-images.tar.gz
docker images | grep fsm-            # fsm-engine, fsm-orchestrator, fsm-runner

mkdir -p fsm/pipeline/deploy fsm/data/svace fsm/prompts
# put docker-compose.yml + .env.example in fsm/pipeline/deploy/, the CSV in fsm/data/svace/
cd fsm/pipeline/deploy
cp .env.example .env                 # fill in QWEN_* and GITHUB_TOKEN
docker compose up -d --no-build      # --no-build is the point: use the loaded images
```

**Why the directory layout matters.** Compose mounts `../../` as `/data` (read-only) and
`../../feedback` as `/data/feedback` (writable). Those are relative to the compose file, so the
compose file must sit **two levels below** the directory holding `data/` and `prompts/`. Keep the
`pipeline/deploy/` shape and it works; flatten it and the ingest will not find the CSV.

`--no-build` is not optional. Without it Compose sees a `build:` section, tries to build, and fails on
a source tree that was never sent.

---

## C. A registry

The right answer if more than one person runs this.

```bash
# you, once per change
docker tag fsm-engine:latest       ghcr.io/<org>/fsm-engine:latest
docker tag fsm-orchestrator:latest ghcr.io/<org>/fsm-orchestrator:latest
docker tag fsm-runner:latest       ghcr.io/<org>/fsm-runner:latest
docker push ghcr.io/<org>/fsm-engine:latest      # …and the other two
```

Then in `docker-compose.yml`, change each `image:` to the registry path and **delete that service's
`build:` block** — leaving it means `docker compose build` silently rebuilds over the pulled image.

```bash
docker compose pull && docker compose up -d
```

Tag something other than `latest` if you care which version is running. `latest` gives you no way to
answer "what is deployed?" after the fact.

---

## Once it is up, whichever way you got there

```bash
curl -s localhost:8085/healthz        # -> ok   (a real query against the marker table)

curl -s -X POST localhost:8085/api/ingest -H 'Content-Type: application/json' -d '{
  "csvPath": "/data/data/svace/webgoat-markers-356.csv",
  "repo": "WebGoat/WebGoat", "branch": "main", "pathPrefix": "src/main/java/"}'

curl -s -X POST localhost:8085/api/prove
```

Dashboard at **http://localhost:8085/**.

`csvPath` is a path **inside the container**. `/data` is the repo root, so a report at
`data/svace/x.csv` is `/data/data/svace/x.csv`. That doubled `data` is not a typo.

---

## The model endpoint, which is where this usually goes wrong

The orchestrator makes all five model calls. `QWEN_BASE_URL` must be reachable **from inside the
orchestrator container**, not from your shell.

- Model on the host: `http://host.docker.internal:8000` (Docker Desktop) or the host's LAN address.
- Model in another Compose stack: put the orchestrator on that stack's network — see the `networks:`
  block in `docker-compose.yml`, which already carries a long comment about exactly this.

Check it the way that answers the question:

```bash
docker exec fsm-orchestrator curl -s -o /dev/null -w '%{http_code}\n' "$QWEN_BASE_URL"
```

Any status — 200, 401, 404 — means the route exists. Only "connection refused" or "could not resolve"
is a wiring failure.

**This matters more than it looks.** The three judging stages fail *closed*: if the model is
unreachable they return a safe default and answer HTTP 200. You get a green run history, no errors, and
every marker settling as `needs_review` with a downgraded verdict nobody made. That failure has cost
this project a full run more than once, which is why the orchestrator names every missing variable in a
`WARN` on the way up — read that line rather than assuming a healthy start means a working one.

---

## Things that will bite, in the order they usually do

**`.env` is gitignored.** It never moves with a `git pull`, a `git clone` or a `docker save`. A stack
started without it interpolates every credential to empty and then fails closed as above.

**`FSM_DB_PATH` must be on a volume.** The application default `./data/fsm` is the container's writable
layer, discarded on the next `up -d`: the stack serves, accepts an ingest, runs for hours and then
reads zero, with nothing red at any point. The compose file sets it correctly — if you deploy some
other way, set it yourself.

**`FSM_FEEDBACK=true` writes as uid 10002.** If the host directory is owned by someone else the service
says so on startup and records nothing. `chown 10002:10002 feedback/`.

**One prover at a time.** `/run_test` is serialised inside one process around one workspace per
repository, and two processes share no lock. Do not run a second orchestrator against the same runner.

---

## Stopping, and what survives

```bash
docker compose down                    # keeps the volumes — markers, verdicts, drafted PRs all survive
docker compose down -v                 # DESTROYS them
```

The volumes pin their physical names (`fsm_fsm-orchestrator-state`, `fsm_fsm-runner-cache`), so renaming
the project or moving the directory does **not** abandon them. `down -v` is the only thing here that
deletes a completed run.
