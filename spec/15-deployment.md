# 15. The image and the deploy

One image, one entrypoint, three roles. The dashboard, the supervisor and every prover are the same
`fsm-agent` image started with a different first argument, sharing one results volume.

Vocabulary used in this chapter:

- **prove** — one JVM, one marker, from the brief to a settled disposition (`tech.mikhailov.fsm.agent.Prove`).
- **marker** — one line of `repo|file|line|checker`; the queue is a file of them.
- **pool** — the `slice` shell loop that proves a queue, at most *n* at a time.
- **claim** — `results/claims/<marker-id>`, a directory whose `mkdir` is how a prover takes a marker.
- **the record** — everything under the results volume: traces, settlements, dead attempts, the queue.
- **the subject** — the third-party project the markers are about; its build runs inside this container.

---

## The image is built from the repository root

**The Docker context is the project, not `agent/`.** The Dockerfile lives at `agent/Dockerfile` and
every path inside it therefore carries the `agent/` prefix:

```
docker build -f agent/Dockerfile -t fsm-agent:latest .
```

**Why the root.** `spec/` is at the project root and has to reach the image (see *The spec travels
with the code*, below), and Docker will not `COPY` above its context. It was `cd agent && docker
build .` until 2026-08-13; a rebuilder who restores that context will find `COPY agent/pom.xml`
failing, and "fixing" it by dropping the prefix silently loses the spec.

**`COPY spec` fails the build when `spec/` is missing.** That is the intended direction — an image
without the specification is an image whose supervisor and chat agent are told to read chapters that
do not exist — but it means **the spec must be committed and pushed before a host build**, because
the host builds from `origin/main` and not from anybody's working tree.

---

## Stage one: the builder

```dockerfile
FROM maven:3.9-eclipse-temurin-25 AS builder
```

`maven:3.9-eclipse-temurin-25` supplies JDK 25, Maven and `git`. **Nothing is installed with `apt`
anywhere in this file.**

### langchain4j-deepagents is built from source, in the build

```dockerfile
ARG DEEPAGENTS_REPO=https://github.com/udayogra/langchain4j-deepagents.git
ARG DEEPAGENTS_REF=main
RUN git clone --depth 1 --branch ${DEEPAGENTS_REF} ${DEEPAGENTS_REPO} /src/deepagents \
    && sed -i 's/\.maxSequentialToolsInvocations(25)/.maxSequentialToolsInvocations(Integer.MAX_VALUE)/' \
        /src/deepagents/src/main/java/com/deepagents/langchain4j/subagents/SubAgentRuntime.java \
    && grep -q 'maxSequentialToolsInvocations(Integer.MAX_VALUE)' \
        /src/deepagents/src/main/java/com/deepagents/langchain4j/subagents/SubAgentRuntime.java \
    && mvn -q -f /src/deepagents -DskipTests install
```

**The artifact is not on Maven Central**, and it is built here rather than vendored as a jar so that
the commit which produced it is in the log of this build. The dependency the agent pom declares is
`com.deepagents:langchain4j-deepagents:0.1.0-SNAPSHOT`, installed into the builder's local
repository by this `RUN`.

`--branch main` pins a branch, not a commit: **two builds of the same repository commit can embed
different deepagents.** That is deliberate provenance-in-the-log, not reproducibility; `DEEPAGENTS_REF`
is a build ARG precisely so a rebuilder can pin it.

### The patched tool-call literal, and the grep that fails the build

**`SubAgentRuntime` hardcodes a ceiling of 25 sequential tool calls as a literal — `bipush 25` — so
it cannot be configured, only patched.**

The failure it caused: twenty-five calls is not many for an agent that reads a class, its callers and
its tests before writing anything, and **the ceiling does not degrade — it throws, and the prove
ends**. Markers that hit it were lost to arithmetic rather than to anything about the marker.

Three properties of the patch, each of which a rebuilder can destroy by "simplifying" it:

1. **It is applied at the source, before the install** — not worked around in this project's code.
   The way around it is to stop using `SubAgentRuntime` and rebuild the tool loop, which is the thing
   this program exists not to do.
2. **It is in the Dockerfile so it is visible in the build.** A patch nobody sees in the build output
   is a patch nobody knows about.
3. **The `grep -q` immediately after the `sed` is the whole safety.** `sed` succeeds when it matches
   nothing. If upstream renames the method or makes the ceiling a parameter, the substitution stops
   applying and, without the grep, the image would quietly go back to a 25-call budget — every
   long-reading agent dying again, in production, with no line in the build log to say why. The grep
   turns that into a failed build.

**Nothing about this ceiling is enforced at runtime.** With the ceiling patched out, nothing ends a
tool loop except its being visible: `Overwatch.digest()` counts `tool` events **per agent** and prints
them on the marker's row as `t<n>`, in the field the digest header describes as
`agent=answers/chars-of-last (empty marked !, tool calls marked t)`. The comment on that counter says
why it exists — "the ceiling that used to stop a tool loop is gone … but with no ceiling a loop has
nothing to end it except this being visible."

### The agent jar

```dockerfile
WORKDIR /src/agent
COPY agent/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY agent/src ./src
RUN mvn -q -B -DskipTests package \
    && mvn -q -B dependency:copy-dependencies -DoutputDirectory=target/lib
```

**The pom is copied alone first** so a source-only change does not re-resolve every dependency.

**`-DskipTests`: the image build never runs the test suite.** A green `docker build` says nothing
about the tests; running them (`mvn -f agent test`) is a step before pushing, not a gate the image
enforces.

The agent targets **Java 21** (`maven.compiler.release` in `agent/pom.xml`) and runs on 25. The
runtime classpath is the jar plus every dependency copied beside it:

```
CP="/opt/agent/agent.jar:/opt/agent/lib/*"
```

---

## The five JDKs

```dockerfile
FROM eclipse-temurin:8-jdk   AS jdk8
FROM eclipse-temurin:11-jdk  AS jdk11
FROM eclipse-temurin:17-jdk  AS jdk17
FROM eclipse-temurin:21-jdk  AS jdk21
```

```dockerfile
COPY --from=jdk8  /opt/java/openjdk /opt/java/8
COPY --from=jdk11 /opt/java/openjdk /opt/java/11
COPY --from=jdk17 /opt/java/openjdk /opt/java/17
COPY --from=jdk21 /opt/java/openjdk /opt/java/21
```

**They are not about compiling.** `javac` 25 targets `--release` 8 through 25 — measured, and
`--release 8` produces class file major version 52. **They are about what the subject's tests run
on.** Surefire forks a JVM from `JAVA_HOME` and finds 25 there whatever the bytecode says, and a
project written before 2018 meets strong encapsulation, removed APIs and bytecode libraries that
cannot read class file 69.

**Every one of those failures arrives as "the build produced no test result".** That is the `infra`
outcome this program is right to refuse as evidence — and refusing it costs the marker anyway. Five
JDKs are what stops a marker in an old project from being unprovable for a reason that has nothing to
do with the marker.

**Copied from the official images, not installed.** `apt` would pull whatever Debian happens to
package, which is not every version and not the same build; these are the Temurin releases
themselves, the copy is reproducible, and it needs no package index at build time. **The cost is
about a gigabyte of image**, and that is the stated price.

**And they are selectable, or they would be a gigabyte of nothing.** The choice is one file on the
results volume, read per build:

| `results/jdk` | `Subject.jdk()` | `Subject.javaHome()` |
|---|---|---|
| absent, unreadable, or junk | `25` | `""` |
| `25` | `25` | `""` |
| `21` / `17` / `11` / `8` | as written | `/opt/java/<n>` |
| anything else (e.g. `14`) | `25` | `""` — `saveJdk` refuses to write it |

**`saveJdk` refuses silently**: given a value not in `JDKS` it returns without writing and without
error, so a caller that asked for `14` gets no complaint and the builds stay on 25.

**Blank means "leave the environment alone", and blank is what 25 means.** The base image's JDK is at
a path this program did not choose and must not hard-code; pointing `JAVA_HOME` at a directory that
does not exist makes every build fail in a way that reads like the project rather than like the
setting.

**`JAVA_HOME` and the `PATH`, both or neither.** `Shell.runWith` sets `JAVA_HOME` and prepends
`<javaHome>/bin` to `PATH`. Setting only the first leaves `java` on the `PATH` resolving to the
image's own, so a build would compile under one JDK and test under another — again failing in a way
that reads like the project. Both `Maven` and `Gradle` go through `Shell.runWith(checkout,
Subject.javaHome(RESULTS), …)`.

`JDKS` is `List.of("25", "21", "17", "11", "8")` — newest first, and it is also the allow-list:
**a JDK not in the image cannot be written into the file.**

---

## Stage two: the runtime

```dockerfile
FROM maven:3.9-eclipse-temurin-25
```

The same base, so `mvn`, `git` and JDK 25 are runtime dependencies as well as build ones: **the
runner shells out to `mvn` in the checkout, and a prove starts by cloning.** `jar` (from the JDK)
extracts an uploaded `source.zip`; `unzip` is *not* in the image and is only a fallback the entrypoint
tries second.

**JDK 25 is here because the subject needs it, not because the agent does.** The agent targets 21;
WebGoat declares `<java.version>25</java.version>`, and the RED and GREEN builds run inside this
container against the subject's own pom. A JDK the subject cannot compile under turns every marker
into "no test executed" — **the one failure that looks like a verdict**.

The four `COPY --from=jdk*` lines above are the first instructions of this stage; the rest follows in
this order:

```dockerfile
RUN useradd --create-home --uid 10001 prover
WORKDIR /work
COPY --from=builder /src/agent/target/agent-*.jar /opt/agent/agent.jar
COPY --from=builder /src/agent/target/lib         /opt/agent/lib
COPY agent/entrypoint.sh                          /opt/agent/entrypoint.sh
COPY spec                                         /opt/agent/spec
RUN chmod +x /opt/agent/entrypoint.sh && chown -R prover:prover /work
RUN mkdir -p /results /work/checkouts /home/prover/.m2 \
    && chown -R prover:prover /results /work /home/prover/.m2
USER prover
ENV RESULTS=/results \
    CHECKOUTS=/work/checkouts
VOLUME ["/results", "/home/prover/.m2"]
EXPOSE 8087
ENTRYPOINT ["/opt/agent/entrypoint.sh"]
CMD ["dashboard"]
```

**It does not run as root.** A prove clones a stranger's repository and runs its build; that build is
arbitrary code and should not be arbitrary code as uid 0. uid 10001, user `prover`.

**Every volume path is created and owned before it is declared.** Docker takes a fresh named volume's
ownership from the image directory underneath it — and creates it root-owned when there is nothing
there. Since the container does not run as root, a volume declared on a missing path is a volume its
own process cannot write.

The incident: `/results` was fixed this way and `/home/prover/.m2` was not, so the cache volume
mounted unwritable by the process that needed it. **Maven does not fail on an unwritable local
repository — it re-resolves every build**, which reads as slowness rather than as a permission error
and is invisible in the trace because the build simply takes longer.

---

## The spec travels with the code

```sh
if [ -d /opt/agent/spec ]; then
    rm -rf "$RESULTS/spec" 2>/dev/null || true
    mkdir -p "$RESULTS/spec" 2>/dev/null || true
    cp -R /opt/agent/spec/. "$RESULTS/spec/" 2>/dev/null || true
fi
```

**An agent's file tools are rooted at one directory and can open nothing outside it.** For the agents
that read the record — `interpreter`, `interpreter-critic`, `overwatch`, `overwatch-critic`, `chat` —
that root is the results directory (`Tools.reading(results, …)`, `Tools.asking(results, …)`,
`Tools.supervising(results, …)`); for the agents inside a prove it is the checkout instead. Either
way, `/opt/agent/spec` is outside every root, so a prompt naming that path would name a file none of
them can read and would teach the model to report that its tools are broken.

So the spec is copied in at start-up and the prompts name `spec/` relative to the root the agents
have. `spec/README.md` is named in `Agents.java` — to the watcher and to the chat — as the index that
"says which chapter answers what", to be read *before* answering any question about how something is
*supposed* to work rather than reasoned out from the traces.

**Refreshed on every start, not copied once**, so a deploy updates it — and **the old copy is removed
first**, so a chapter deleted upstream does not linger as a chapter the watcher still cites.

**Failure direction:** every step is `|| true`, and the removal happens before the copy. A failed copy
leaves the agents with *no* spec rather than a stale one, while the prompts still point at `spec/`.
That direction is chosen: no chapter is better than a chapter that no longer describes the code.

---

## What persists, and what does not

| path | volume | what it holds | survives a redeploy |
|---|---|---|---|
| `/results` | `fsm-results` | the whole record and every setting (below) | **yes** |
| `/home/prover/.m2` | `fsm-m2` | Maven's local repository for the subject's builds | **yes** |
| `/work/checkouts` | none — container filesystem | the reference clone and per-marker worktrees | **no** |
| `/opt/agent` | image | jar, `lib/`, entrypoint, `spec/` | replaced by the new image |

**Mount `/results` or the run has proved nothing anybody can read.** The trace and the settlements
are the record.

**Mount `/home/prover/.m2` as well.** Otherwise it lives in the container filesystem, every recreation
starts from an empty local repository, and the next build re-downloads the subject's whole dependency
tree — minutes per marker, paid again on every redeploy.

**Checkouts are deliberately not a volume.** A worktree is thrown away after each prove anyway, and
the reference clone is re-made by the next `slice`. One reader-facing consequence: the dashboard reads
the flagged source line out of `CHECKOUTS/<repo-name>/<file>`, so immediately after a redeploy — before
the pool has cloned — the flagged-line panel is empty. That path returns `""` on any failure rather
than refusing to start.

What lives on the results volume, all of it surviving every deploy:

```
markers.txt                     the queue, one `repo|file|line|checker` per line
markers-before-<millis>.txt     the queue an upload replaced, kept beside the new one
jdk                             which JDK the subject's builds run on
workers                         pool width, re-read at the top of every loop (1..16)
model                           the model settings, chmod 600 — named by a tool: REFUSED
git-credentials                 git's own store, chmod 600 — named by a tool: REFUSED
source.zip                      an uploaded tree; while present nothing is cloned
prompts/<agent>.txt             prompt overrides; absent means the code's text block
spec/                           refreshed from the image on every container start
claims/<marker-id>/             one directory per prove in flight
m/<marker-id>/                  trace.jsonl, trace.jsonl.live, settlements.jsonl, slice.log, summary.txt
dead/<marker-id>.<why>          <why> is attempt-N, restart-N or before-postponing
postponed/<marker-id>           set aside by the supervisor
trace.jsonl / settlements.jsonl the ROOT pair: where a single `prove` writes, since that mode passes
                                $RESULTS as the results dir while the pool passes $RESULTS/m/<id>
overwatch.jsonl / overwatch-trace.jsonl / overwatch-settlements.jsonl
restarts.jsonl                  every restart, with the reason
chat.jsonl / chat-trace.jsonl / chat-settlements.jsonl
dashboard-trace.jsonl / dashboard-settlements.jsonl
feedback.jsonl / cases.jsonl / model-test.jsonl / model-test-trace.jsonl
overwatch.log                   only in `serve` mode
slice.log                       only when the deploy redirects the pool's stdout here (see below)
```

`model` and `git-credentials` sit in the same directory the agents' file tools are rooted at, so the
guard is at the tool layer and has two halves: **naming either one is `REFUSED`, and the shapes they
hold (`api_key=…`, `https://user:token@host`) are redacted out of every tool result whatever produced
them** — because `read_file` names a file and `grep` finds it without naming it.

**`Dashboard`'s first argument may be either the results directory or a `.jsonl` path**: an argument
ending in `.jsonl` is taken as the settlements file, anything else has `settlements.jsonl` resolved
under it, and the results root the dashboard uses for everything else is that file's parent. The
entrypoint passes `$RESULTS/settlements.jsonl`, which is why `dashboard-trace.jsonl` and
`dashboard-settlements.jsonl` land at the results root.

**The volume outlives every fix.** A bug in what the pool writes there survives the deploy that fixes
it, so a fix has to reach back over what is already on disk — this is why the pool sweeps claims left
by *earlier* runs rather than only releasing its own.

---

## Environment variables

| name | read by | default | absent or blank |
|---|---|---|---|
| `QWEN_BASE_URL` | `Tuning.baseUrl()` | image sets none | the settings file's `base_url` is tried first; with neither, `Prove.model` throws `no endpoint: set QWEN_BASE_URL or the model settings` and every agent call fails |
| `QWEN_MODEL` | `Tuning.model()` | none | settings' `model` first; blank is passed on and the endpoint decides |
| `QWEN_API_KEY` | `Prove.model` directly, via `env(…)` | none | throws `QWEN_API_KEY is not set` — **every prove, the supervisor and the chat all fail** |
| `RESULTS` | `Maven`, `Gradle`, `entrypoint.sh` | `/results` (image `ENV`) | falls back to `/results` |
| `CHECKOUTS` | `Dashboard`, `entrypoint.sh` | `/work/checkouts` (image `ENV`) | falls back to `/work/checkouts` |
| `PROMPTS` | `Prompts.WHERE` | `/results/prompts` — **literal, not derived from `RESULTS`** | overrides are not found; built-in prompts are used |
| `TUNING` | `Tuning.WHERE` | `/results/model` — **literal, not derived from `RESULTS`** | settings fall back to environment and constants |
| `PORT` | `entrypoint.sh` only (`serve`, `dashboard`) | `8087` | `8087` |
| `REPO` | `entrypoint.sh` `test` mode only | `https://github.com/WebGoat/WebGoat.git` | the default |

**`PROMPTS` and `TUNING` default to literal `/results/...` paths.** Moving `RESULTS` without also
moving these two splits the settings away from the record: the run keeps working, silently, under the
code's built-in prompts and the environment's model.

**The API key is required in the environment even though the settings page can hold one.**
`Tuning.apiKey()` reads `results/model` first and the environment second, and the settings page shows
and edits it — but the model builder in `Prove.model` bypasses `Tuning` for this one value and calls
`env("QWEN_API_KEY")` directly, which throws `QWEN_API_KEY is not set` when it is unset **or blank**.
**A deployment that sets the key only from the page has no working agents.**

That is deliberate and recorded at the call site: *"THE KEY IS NOT A SETTING. Everything else here is
a parameter; a credential is not, and it stays where a deploy put it."* The two readers disagree on
purpose — `Tuning.apiKey()` exists so the page can show whether a key is set and where it came from
(`Tuning.keyed()`, `Tuning.keyFrom()` answers "the environment" or "this page"), not so a prove can
run on one typed into a form.

**Two more, set only by the build.** `agent/pom.xml` gives Surefire `PROMPTS=target/test-prompts` and
`TUNING=target/test-model`, because `Prompts` and `Tuning` fix their directory at class load. Without
them the prompt tests skip on every machine where `/results` does not exist — three tests that pass by
not running.

---

## The entrypoint and its modes

```
usage: prove 'repo|file|line|checker' | slice <markers> [concurrency] | serve [seconds]
     | overwatch [seconds] | test [cases] | seed [cases] | dashboard
```

`bash`, not `sh`: **the pool waits on `wait -n` and counts with `jobs -p`**, neither of which is POSIX.
A pool built without them either polls on a sleep or spawns everything at once. The script runs under
`set -eu`.

The case falls through to `dashboard` when `$1` is unset (`case "${1:-dashboard}"`), and anything
unrecognised prints the usage above to stderr and **exits 2**.

| argument | process | notes |
|---|---|---|
| `dashboard` | `exec Dashboard $RESULTS/settlements.jsonl ${PORT:-8087}` | the image `CMD`, and the default when no argument is given |
| `serve [seconds]` | `Overwatch $RESULTS ${2:-900}` in the background (→ `$RESULTS/overwatch.log`), then `exec Dashboard` | one container for both |
| `overwatch [seconds]` | `exec Overwatch $RESULTS ${2:-900}` | supervisor alone; the `Interpreter` lane-watch runs in this process too |
| `slice <markers> [n]` / `parallel` | the pool | `n` re-read from `results/workers` each iteration; **`parallel` is an accepted alias** |
| `prove '<marker>'` | `exec Prove "$dir" "$marker" "$RESULTS"` | checks out first; note the results dir is `$RESULTS` itself, not `m/<id>` |
| `test [cases]` | `exec ModelTest "$dir" "${2:-$RESULTS/cases.jsonl}" "$RESULTS"` | checks out `${REPO:-https://github.com/WebGoat/WebGoat.git}` first |
| `seed [cases]` | `ModelTest --seed "$RESULTS/trace.jsonl" "${2:-$RESULTS/cases.jsonl}"` | not `exec`ed; seeds from the **root** trace, which is the one a single `prove` writes |

**The pool width is read, not passed.** `slice`'s third argument is captured once as `asked`
(`asked="${3:-4}"`) — captured outside the `width()` function on purpose, because inside it `$3` is
the *function's* third argument, of which there is never one, and the fallback would silently have
become the empty string. Then each time round the loop:

```sh
w=$(cat "$RESULTS/workers" 2>/dev/null | tr -cd '0-9')   # digits only
[ -n "$w" ] || w="$asked"                                 # unreadable/empty -> the argument
[ "$w" -ge 1 ] 2>/dev/null || w=4                         # below 1 -> 4, not 1
[ "$w" -le 16 ] 2>/dev/null || w=16
```

**Clamped here as well as in `Workers.java`, and both are meant.** This is the side that starts JVMs,
and a `workers` file edited by hand or left behind by an older version must not be able to start
ninety of them against one inference endpoint. The two sides do not agree at the bottom of the range —
`Workers.clamp` raises `0` to `LEAST` = 1, the shell raises it to 4 — and neither trusts the other.

**`serve` is the asymmetry that matters.** The supervisor is backgrounded and the dashboard is
`exec`ed: a watcher that dies must not take the record with it, and a dashboard that dies *should* end
the container so the restart policy brings both back. They were two containers off one image once —
two things to deploy, two sets of environment to keep in step, and one of them silently missing its
`PROMPTS` and `TUNING` for a deploy or two.

**Ports do not agree across the three places they appear, and the argument wins.** `EXPOSE 8087` and
the entrypoint's `${PORT:-8087}` are the image's defaults; `README.md` shows `-p 8085:8085`; the
deployed container sets `-e PORT=8085`. The port the server binds is whatever the entrypoint passes as
the second argument to `Dashboard`.

---

## The deploy

Not recorded anywhere in the repository — it lives on the deployment host — so it is written down
here. Host alias `mh`, container name `fsm`, image tag `fsm-agent:latest`.

### Build, from `origin/main`

The host keeps its own clone at `~/fsm-agent` and **builds from `origin/main`, never from a local
tree**:

```sh
ssh mh 'cd ~/fsm-agent && git fetch -q origin && git reset -q --hard origin/main \
  && docker build -q -f agent/Dockerfile -t fsm-agent:latest . \
  && echo "built $(git -C ~/fsm-agent rev-parse --short HEAD)"'
```

**So: push first, and check that `origin/main` matches your `HEAD`.** The incident: a push that failed
on a transient key error left the host rebuilding the previous commit, and **the deploy reported
success** — a build of the wrong code is still a successful build. **The echoed short SHA is the
check**, and it is the reason the `echo` is in the command.

Two things that fail this build, both on purpose: a missing `spec/` (`COPY spec`), and an upstream
`SubAgentRuntime` that no longer matches the `sed` (the `grep -q`).

### Restart

One container serves the dashboard and hosts the pool; the pool is a separate `slice` process started
inside it.

```sh
ssh mh 'set -e
KEY=$(docker inspect fsm --format "{{range .Config.Env}}{{println .}}{{end}}" | grep ^QWEN_API_KEY= | cut -d= -f2-)
docker exec fsm sh -c "pkill -f \"entrypoint.sh slice\"; sleep 1; pkill -f tech.mikhailov.fsm.agent.Prove" 2>/dev/null || true
sleep 3
docker rm -f fsm >/dev/null
docker run -d --name fsm --network proxy-net --restart unless-stopped \
  -v fsm-m2:/home/prover/.m2 -v fsm-results:/results \
  -e QWEN_BASE_URL=http://inference-vllm:8000/v1 -e QWEN_API_KEY="$KEY" \
  -e QWEN_MODEL=qwen-3.6-27b-nvfp4 -e PORT=8085 -e CHECKOUTS=/work/checkouts \
  fsm-agent:latest dashboard >/dev/null
sleep 5
docker exec -d fsm sh -c "/opt/agent/entrypoint.sh slice /results/markers.txt 4 >> /results/slice.log 2>&1"'
```

Five properties of that sequence:

**The key is read out of the old container's environment rather than retyped**, so it never appears in
a command line, a shell history or a terminal scrollback. It must therefore be read **before**
`docker rm -f`.

**That read is not fail-safe.** The exit status of the pipeline is `cut`'s, which succeeds even when
`grep` matched nothing, so `set -e` does not stop a deploy whose `KEY` came out empty — and an empty
key starts a container in which every agent call throws `QWEN_API_KEY is not set`. A rebuilder should
check `KEY` is non-empty before the `docker run`.

**The pool is killed politely first**, `slice` before `Prove`, so the shell loop does not immediately
hand the next marker out while its provers are dying.

**The pool's own stdout goes to `/results/slice.log`**, appended. That is a different file from the
per-marker `m/<id>/slice.log` the pool writes inside each prove's lane; the root one is the deploy's
redirect, not the code's.

**This sequence runs `dashboard`, not `serve`.** Both spellings exist for that reason. Under
`dashboard` there is no `Overwatch` process in the container — no findings, no `restart_prove`, no
`postpone_prove` and no lane summaries, since the `Interpreter` runs inside `Overwatch`. If the
supervisor is meant to be running, either the container's argument is `serve` or an `overwatch`
process is started beside the pool.

The dashboard is published at `fix-java-svace-markers.mikhailov.tech`, behind basic auth, over the
external `proxy-net` network; the reverse proxy is not part of this repository. Basic auth is
load-bearing: **the settings page renders the API key into the page source** so that reveal-and-copy
can work.

### What a redeploy does to a run in flight

**Killing the pool orphans every claim it held.** That is survivable, and the machinery that makes it
survivable is at the top of the next `slice`:

- Each claim under `results/claims/` is checked with `pgrep -f "tree-$held "`. **A claim with a live
  process behind it is kept**, so a second pool started alongside is not robbed.
- A claim with no process, whose lane has not settled, has its record moved to
  `dead/<id>.attempt-N` (N is `Pace --tries` + 1) — because `Prove` appends, and a retry landing on
  the old trace reads as one prove that changed its mind — and then the claim is removed. The claim
  is removed **either way**: settled or archived, it does not survive the sweep.
- "Settled" here means the lane's `settlements.jsonl` matches the disposition regex, which names the
  seven states this program decides and nothing else:
  `"state":"(false-positive|by-design|unprovable|reproduced|needs-review|verified/pr-ready|verified/pr-rejected)"`.
  **`infra` is not on that list**, deliberately: it is what a prove writes when it *throws*, and
  counting it as an answer once retired every marker whose prove died.
- Three unsettled attempts (`TRIES=3`) and the marker is left for a person.

Failure direction of the sweep: **absent or failing `pgrep` reads as "nothing is behind this claim"**,
which releases claims that a concurrent pool is actively holding — costing a marker proved twice
rather than a marker lost, but worth knowing before removing `procps` from the base image.
*(That `pgrep` is present in `maven:3.9-eclipse-temurin-25` is assumed by the code and not asserted
anywhere — unverified here.)*

**A redeploy does not invalidate the record.** Settlements are matched by `suspicion_key`, which *is*
the marker string `repo|file|line|checker` (`Settlement.row()` puts `markerKey` there); the results
volume is untouched, and a marker already settled **in any lane** — `grep -rlF` across
`$RESULTS/m/*/settlements.jsonl` — is skipped by the next pass, whatever directory proved it.

**Things that do not need a redeploy, and must not get one.** The model settings, the prompts, the
pool width, the queue, the credential, the JDK and the source zip are all files on the results volume,
read per prove or per loop iteration. Changing any of them by rebuilding an image kills the pool,
which orphans every claim in flight — the failure that made them files in the first place.
