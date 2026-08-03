# Running it with Docker — three ways

One image, one container. The [README](README.md) quickstart builds it from source; that is the right
default and it is the slowest one, because the image provisions five JDKs and Maven and a cold build
needs network access to Adoptium, Apache and Maven Central.

Pick by what the target machine actually has.

| | needs | build time | image transfer |
|---|---|---|---|
| **A. Build from source** | git, Docker, internet | long (cold), minutes (warm) | none |
| **B. Hand over a saved image** | Docker only | none | ~1.8 GB |
| **C. Registry** | Docker, a registry | once, by you | pull |

All three end at the same place: one container, `http://localhost:8085`.

---

## A. Build from source

```bash
git clone git@github.com:vasiliy-mikhailov/fix-java-svace-markers.git
cd fix-java-svace-markers/pipeline/deploy
cp .env.example .env             # fill in QWEN_* and GIT_TOKEN
docker compose up -d --build
```

That is the whole of it. `docker compose ps` shows **one** container, `fsm`.

**If your Maven repository is a mirror on a private Docker network**, `docker compose build` cannot
reach it — Compose cannot join a network at build time, and BuildKit rejects `--network` for custom
networks. Use the legacy builder, from the reactor root:

```bash
cd ..                         # pipeline/
DOCKER_BUILDKIT=0 docker build --network <your-net> \
  --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ \
  -t fsm:latest .
```

That build argument only affects **the image's own build**. Which repository the *analysed projects*
resolve from is a separate, runtime setting — see "The Maven mirror" below.

---

## B. Hand over a saved image — no build, no source, no internet

Best for giving the pipeline to someone who should not have to care how it is built, or for a machine
with no route to Maven Central.

**You, once:**

```bash
docker save fsm:latest | gzip > fsm-image.tar.gz          # ~1.8 GB uncompressed
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
docker load < fsm-image.tar.gz
docker images | grep fsm             # fsm  latest

mkdir -p fsm/pipeline/deploy fsm/data/svace fsm/prompts
# put docker-compose.yml + .env.example in fsm/pipeline/deploy/, the CSV in fsm/data/svace/
cd fsm/pipeline/deploy
cp .env.example .env                 # fill in QWEN_* and GIT_TOKEN
docker compose up -d --no-build      # --no-build is the point: use the loaded image
```

**Why the directory layout matters.** Compose mounts `../../` as `/data` (read-only) and
`../../feedback` as `/data/feedback` (writable). Those are relative to the compose file, so the compose
file must sit **two levels below** the directory holding `data/` and `prompts/`. Keep the
`pipeline/deploy/` shape and it works; flatten it and the ingest will not find the CSV.

`--no-build` is not optional. Without it Compose sees a `build:` section, tries to build, and fails on
a source tree that was never sent.

### …or one `docker run`, if you would rather not have a compose file at all

It works. What the flags buy:

```bash
docker run -d --name fsm -p 127.0.0.1:8085:8085 \
  -e QWEN_BASE_URL=... -e QWEN_API_KEY=... -e QWEN_MODEL=... -e GIT_TOKEN=... \
  -v fsm_fsm-orchestrator-state:/state \
  -v fsm_fsm-runner-cache:/cache \
  -v "$PWD":/data:ro \
  fsm:latest
```

- `/state` — the H2 database. **Without it a run is lost on the next restart, silently**: the container
  starts, serves, accepts an ingest, works for hours and then reads zero.
- `/cache` — the checkouts and their `target/` trees. Without it every restart re-clones everything.
- `/data` — the repository root, so the ingest can read `data/svace/*.csv` and the five prompts.

Compose is still the better answer for anything long-lived, because those volumes, the env file and the
writable `feedback/` bind are exactly the things you do not want to retype correctly every time. But
nothing in the image requires it.

---

## C. A registry

The right answer if more than one person runs this.

```bash
docker tag fsm:latest ghcr.io/<org>/fsm:latest
docker push ghcr.io/<org>/fsm:latest
```

Then in `docker-compose.yml`, change `image:` to the registry path and **delete the `build:` block** —
leaving it means `docker compose build` silently rebuilds over the pulled image.

```bash
docker compose pull && docker compose up -d
```

Tag something other than `latest` if you care which version is running. `latest` gives you no way to
answer "what is deployed?" after the fact.

---

## Once it is up, whichever way you got there

```bash
curl -s localhost:8085/healthz        # -> ok   (a real query against the marker table)

# the report travels IN the request — no mount, no volume, no shell on the host
curl -sS -X POST localhost:8085/api/ingest \
  -F 'csv=@svace-report.csv' \
  -F 'repo=WebGoat/WebGoat' -F 'branch=main' -F 'path_prefix=src/main/java/'

curl -s -X POST localhost:8085/api/prove
```

Dashboard at **http://localhost:8085/**.

**Re-run both as often as you like.** This matters here more than anywhere: a drain is 6–26 hours and
this container gets redeployed, rebooted and crash-looped inside that window, so re-running the ingest
afterwards is the normal thing to do. It **adds** — a marker already in the backlog keeps its status,
its verdict, its artifact and its attempt count, and nothing is discarded. The `202` says so before
anything happens (`"mode": "additive", "discards": 0`), and `GET /api/ingest/last` says what the run
actually did (`"added": 14, "kept": 268`) after the log is long gone.

To discard the backlog and rebuild it from the report, say so *and* say how much you are destroying:

```bash
curl -sS -X POST localhost:8085/api/ingest \
  -F 'csv=@svace-report.csv' -F 'repo=WebGoat/WebGoat' -F 'branch=main' \
  -F 'reset=true' -F 'reset_confirm=268'      # 268 = the number of SETTLED markers
```

Send the wrong number, or none, and the request is **refused with the right one in the message** — the
refusal doubles as the dry run. Nothing is asked for when nothing has settled. Comments people wrote
survive both paths. A deployment whose backlog is disposable can set `FSM_INGEST_RESET=true` once, and
then every ingest resets with no token; it is announced in the boot log on every start, and a request
can still override it with `-F 'reset=false'`.

### …and the same thing against a GitLab

```bash
curl -sS -X POST localhost:8085/api/ingest \
  -F 'csv=@svace-report.csv' \
  -F 'repo=https://gitlab.company.internal/platform/payments-api.git' \
  -F 'branch=main' \
  -F 'path_prefix=/builds/gitlab/platform/payments-api/'
```

`repo` may be `owner/name`, `group/sub/project`, a full `https://`/`ssh://` clone URL, the
`git@host:group/project.git` shorthand, or a bare `host/group/project`. **`branch` is required for
anything that is not `owner/name`** — a blank branch is resolved through the GitHub API, which cannot
answer for another host, and the request is refused rather than failing 282 times six hours later.

Set `FSM_GIT_HOST=gitlab.company.internal` and a bare `group/project` means that server instead;
`GIT_TOKEN` is the credential for it. Nothing else changes: source for the prove is read out of the
checkout the prove already makes, so no host-specific API is involved.

### The three ways to hand over the report

| | when |
|---|---|
| `-F 'csv=@report.csv'` | **the default.** Streamed to disk, never escaped into JSON, bounded at 32 MiB (`FSM_INGEST_MAX_CSV_BYTES`) |
| `{"csv_text": "Severity,Checker,…"}` | a small report, or one being pasted into a script |
| `{"csvPath": "/data/data/svace/x.csv"}` | the report is already on the `/data` mount |

`csvPath` is a path **inside the container**. `/data` is the repo root, so a report at
`data/svace/x.csv` is `/data/data/svace/x.csv`. That doubled `data` is not a typo — and it is exactly
the assumption the other two remove: before them, ingesting anything required write access to a volume
this container shares, which a client with a report on a laptop does not have.

An oversized report is **413, never truncated**: half a report is a backlog silently missing markers.

---

## The Maven mirror, which is a runtime setting

The container builds the projects under analysis. Where it resolves their dependencies from is one
environment variable:

```
MAVEN_MIRROR_URL=                                                      # -> Maven Central. The default.
MAVEN_MIRROR_URL=https://nexus.example.com/repository/maven-public/    # -> that mirror
```

It takes effect on the next `docker compose up -d` — **no rebuild, on an image you did not build.** The
process writes a `settings.xml` onto the cache volume at start-up and hands it to every `mvn` with `-s`;
the boot log says which is in force:

```
[runner] in-process, cache /cache, maven central (no MAVEN_MIRROR_URL)
[runner] in-process, cache /cache, maven /cache/maven-settings.xml
```

**Use a public hostname, and never bake one into the image.** A compose-internal name like `nexus:8081`
only resolves inside the Docker network that stack is on, which is exactly why it cannot be a default.
And a mirror of `*` in a `settings.xml` inside the image is not a cache in front of Central; it is *the
only repository Maven will talk to*. On any machine where that hostname does not resolve, the container
starts, answers `/healthz`, clones the repository — and then fails every build in hundreds of lines about
unresolvable artifacts, which reads as a broken project rather than as an image carrying somebody else's
infrastructure.

---

## The model endpoint, which is where this usually goes wrong

`QWEN_BASE_URL` must be reachable **from inside the container**, not from your shell.

- Model on the host: `http://host.docker.internal:8000` (Docker Desktop) or the host's LAN address.
- Model in another Compose stack: put this container on that stack's network. Copy
  `pipeline/deploy/docker-compose.override.yml.example` to `docker-compose.override.yml` — Compose
  merges it automatically — and edit the network name.

Check it the way that answers the question:

```bash
docker exec fsm curl -s -o /dev/null -w '%{http_code}\n' "$QWEN_BASE_URL"
```

Any status — 200, 401, 404 — means the route exists. Only "connection refused" or "could not resolve"
is a wiring failure.

**This matters more than it looks.** The three judging stages fail *closed*: if the model is
unreachable they return a safe default and answer HTTP 200. You get a green run history, no errors, and
every marker settling as `needs_review` with a downgraded verdict nobody made. That failure has cost
this project a full run more than once, which is why the container names every missing variable in a
`WARN` on the way up — read that line rather than assuming a healthy start means a working one.

---

## Things that will bite, in the order they usually do

**`.env` is gitignored.** It never moves with a `git pull`, a `git clone` or a `docker save`. A stack
started without it interpolates every credential to empty and then fails closed as above.

**`FSM_DB_PATH` and `CACHE` must be on volumes.** The application defaults (`./data/fsm`,
`./data/cache`) are the container's writable layer, discarded on the next `up -d`: the stack serves,
accepts an ingest, runs for hours and then reads zero, with nothing red at any point. The compose file
sets both correctly — if you deploy some other way, set them yourself.

**No `external:` networks in the committed compose file, and please keep it that way.** An `external:
true` network is a name that exists on one machine; everywhere else `docker compose up -d` dies on its
first line with *"network mvn-cache declared as external, but could not be found"* — after a build that
had succeeded, with nothing started. Host-specific wiring goes in `docker-compose.override.yml`, which
is gitignored and which Compose merges automatically; the committed example is the template.

**`FSM_FEEDBACK=true` writes as uid 10002.** If the host directory is owned by someone else the service
says so on startup and records nothing. `chown 10002:10002 feedback/`.

**One prover at a time.** Proving a marker (`/run_test` internally: clone, patch, build) is serialised inside one process around one workspace per
repository, and two processes share no lock. Do not run a second container against the same cache
volume.

**The engine service is opt-in.** `docker compose --profile engine up -d engine` publishes the
judgement on `localhost:8092`, one stage per request, so you can reproduce a single stage against a
request you wrote by hand instead of a 6-26 hour run around it. Nothing in a run calls it, and it holds
no secrets — the model endpoint, the Svace endpoint and the GitHub token all arrive in the request.

**Want the prover in its own container?** `FSM_RUNNER_MODE=http` plus `FSM_RUNNER_URL` gives you that
split. It is a real trade: `/run_test` runs third-party build scripts, and a container boundary between
them and this pipeline's credentials is stronger than the denylist that protects them inside one process.

**You supply the runner, and the same image is one.** There is no `runner` service and no separate
runner image, but the fat jar carries the module, so Boot's `PropertiesLauncher` starts it:

```bash
docker run -d --name fsm-split-runner --network fsm_default -e PORT=8090 -e CACHE=/cache \
  --entrypoint env fsm:latest LC_ALL=C.UTF-8 java \
  -Dloader.main=tech.mikhailov.fsm.runner.Runner \
  -cp /app/fsm.jar org.springframework.boot.loader.launch.PropertiesLauncher

FSM_RUNNER_MODE=http FSM_RUNNER_URL=http://fsm-split-runner:8090 docker compose up -d
docker compose logs fsm | grep '\[runner\]'
# [runner] http://fsm-split-runner:8090/run_test, up to 5400s per prove, 3 connect attempt(s)
```

**The same trap applies to `FSM_GIT_HOST` and `GIT_TOKEN`,** which is why both are listed in the
committed compose file with empty defaults — a Guild's own git server must be selectable without
editing the file, and a host that silently fell back to `github.com` would clone
`github.com/<their-group>/<their-project>`: a well-formed URL, a 404, and every marker filed as an
infrastructure failure hours in. `DeploymentTest` pins them.

**Read that last line rather than trusting the command.** Compose passes a variable to the process only
if it is listed under `environment:`; a name set in your shell or in `.env` otherwise reaches Compose's
own interpolation and stops there. So with the mode listed and the address not, this pair switches the
mode and **silently drops the address**, and every prove goes to `HttpRunnerClient.DEFAULT_BASE_URL`,
which nothing in this stack serves — surfacing hours into a run as an infrastructure failure.
`DeploymentTest` pins the two to travel together.

---

## Stopping, and what survives

```bash
docker compose down                    # keeps the volumes — markers, verdicts, drafted PRs all survive
docker compose down -v                 # DESTROYS them
```

The volumes pin their physical names (`fsm_fsm-orchestrator-state`, `fsm_fsm-runner-cache`), so renaming
the project or moving the directory does **not** abandon them. Keep the pins: without them Compose
derives the real name from the project name, and a rename then makes it look for a volume that does not
exist, create it empty, and serve an empty backlog with nothing red. `down -v` is the only thing here
that deletes a completed run.
