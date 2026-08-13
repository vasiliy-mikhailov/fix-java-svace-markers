# Running it

`fix-java-svace-markers` takes static-analysis markers on a Java project — a file, a line, a checker
name — and tries to **prove** each one: write a JUnit test that fails because of the defect, patch it,
show the same test passing. A marker that cannot be proved is argued down instead (`false-positive`,
`by-design`, `unprovable`), and a run's whole record is readable on a dashboard.

- **Image:** `vasiliymikhailov/fsm-agent`
- **Source:** <https://github.com/vasiliy-mikhailov/fix-java-svace-markers>
- **Why it is built this way:** [`README.md`](README.md) is the tour, [`spec/`](spec/) is the contract.

---

## Read this before you expose the port

**The dashboard has no authentication, no session, and no CSRF token.** Anyone who can reach the port
can read every trace, edit every prompt, replace the marker queue, change the model — and read two
secrets in plain text, because `/settings` renders them into the page so the reveal and copy buttons
can work:

- your inference API key
- your git credential, if you have set one for a private repository

That is a deliberate trade — *your key, your box* — and it only holds if the box is yours. **Put a
reverse proxy with authentication in front of it, or bind the port to localhost.** Serving `8087`
straight to the internet publishes your API key.

**It also runs a stranger's build.** Proving a marker clones the subject repository and runs its test
suite, which is arbitrary code execution by design. The image runs as an unprivileged user
(`uid 10001`), which is a floor and not a sandbox. Run it on a host you can afford to lose, or in a
VM.

---

## What you need

- Docker.
- **An OpenAI-compatible chat endpoint.** No model ships in the image. Anything that speaks
  `/v1/chat/completions` works — vLLM, llama.cpp's server, Ollama's OpenAI shim, or a hosted API. It
  was built and run against vLLM serving Qwen, with `--reasoning-parser qwen3`.
- A long context window. A prove hands the model whole files and whole traces; 32k is a floor and
  more is better.

Nothing else. The five JDKs the subject's build might need (8, 11, 17, 21, 25) are already in the
image.

**The published image is `linux/amd64` only.** On Apple Silicon or another arm64 host it runs under
emulation — Docker warns, it works, and it is slow enough to matter when the thing you are waiting on
is a Maven build. Build it natively there instead (see [Building from source](#building-from-source)),
which also gets you an arm64 image.

---

## Run it

Start with the dashboard, which needs no model to render what is already there:

```bash
docker run -d --name fsm \
  -p 127.0.0.1:8087:8087 \
  -v fsm-results:/results \
  -v fsm-m2:/home/prover/.m2 \
  -e QWEN_BASE_URL=http://your-endpoint:8000/v1 \
  -e QWEN_API_KEY=your-key \
  -e QWEN_MODEL=your-model \
  vasiliymikhailov/fsm-agent:latest dashboard
```

Open <http://127.0.0.1:8087>. It will be empty — nothing has been proved yet.

### Prove one marker

```bash
docker exec fsm /opt/agent/entrypoint.sh prove \
  'https://github.com/WebGoat/WebGoat.git|src/main/java/org/owasp/webgoat/lessons/xxe/Ping.java|34|FB.DM_DEFAULT_ENCODING'
```

One marker takes roughly 8–12 minutes: it clones the repository, asks a chain of agents for a test,
builds it red, patches, builds it green, and argues a disposition. Watch it live on the dashboard.

### Prove a queue

A queue is a text file, one marker per line, `repo|file|line|checker`:

```
https://github.com/WebGoat/WebGoat.git|src/main/java/org/owasp/webgoat/lessons/xxe/Ping.java|34|FB.DM_DEFAULT_ENCODING
https://github.com/WebGoat/WebGoat.git|src/main/java/.../Assignment5.java|44|TAINTED_PTR
```

The repository ships one: [`examples/webgoat/markers.txt`](examples/webgoat/markers.txt), 356 markers
against OWASP WebGoat. Upload your own at `/settings` → **subject**, or copy a file in:

```bash
docker cp markers.txt fsm:/results/markers.txt
docker exec -d fsm /opt/agent/entrypoint.sh slice /results/markers.txt 4
```

The trailing `4` is how many markers to prove at once. It is re-read from `/results/workers` at every
iteration, so `/settings` can widen or narrow a run **while it is going**.

### Everything in one container

`serve` runs the dashboard in the foreground and the supervisor behind it:

```bash
docker run -d --name fsm --restart unless-stopped \
  -p 127.0.0.1:8087:8087 \
  -v fsm-results:/results -v fsm-m2:/home/prover/.m2 \
  -e QWEN_BASE_URL=... -e QWEN_API_KEY=... -e QWEN_MODEL=... \
  vasiliymikhailov/fsm-agent:latest serve
```

Then start the pool inside it with the `slice` command above.

---

## Modes

`ENTRYPOINT` is `/opt/agent/entrypoint.sh`; the default command is `dashboard`.

| command | what it does |
|---|---|
| `dashboard` | serve the record on `$PORT` (default 8087). No model needed. |
| `serve [seconds]` | the dashboard **and** the supervisor, one container. `seconds` between supervisor passes, default 900. |
| `prove '<marker>'` | prove one marker and exit. |
| `slice <file> [n]` | prove every marker in a queue file, `n` at a time (1–16, default 4). |
| `overwatch [seconds]` | the supervisor alone. |
| `test [cases]` | replay recorded model-test cases. |
| `seed [cases]` | seed test cases from a trace. |

## Environment

| variable | meaning |
|---|---|
| `QWEN_BASE_URL` | the OpenAI-compatible endpoint, ending in `/v1`. **Required.** |
| `QWEN_API_KEY` | sent as the bearer token. Use any non-empty value if your endpoint ignores it. |
| `QWEN_MODEL` | the model name to ask for. |
| `PORT` | dashboard port, default `8087`. |
| `RESULTS` | where the record goes, default `/results`. |
| `CHECKOUTS` | where subject clones go, default `/work/checkouts`. |

The name `QWEN_*` is historical — any OpenAI-compatible endpoint works.

**All four model settings are editable at `/settings` without a restart**, and what is set there wins
over the environment. A prove is a fresh process per marker, so a change takes effect on the next
marker and disturbs nothing already running. The same is true of every agent's prompt.

## Volumes

| path | why it matters |
|---|---|
| `/results` | the whole record — traces, settlements, prompts, settings, the queue. **Mount this or you lose the run.** |
| `/home/prover/.m2` | the Maven cache. Without it every recreation re-downloads the subject's whole dependency tree — minutes per marker, paid again on every redeploy. |
| `/work/checkouts` | subject clones. Rebuildable; mount only if you want them to survive. |

---

## Building from source

The build context is the **repository root**, not `agent/`, because the Dockerfile copies `spec/`:

```bash
git clone https://github.com/vasiliy-mikhailov/fix-java-svace-markers
cd fix-java-svace-markers
docker build -f agent/Dockerfile -t fsm-agent:latest .
```

Tests without Docker (needs Maven and a JDK 21 or newer — the pom targets `release 21`):

```bash
cd agent && mvn test
```

---

## Where things end up

```
/results
  markers.txt                     the queue
  m/<marker>/trace.jsonl          every prompt, reply, tool call and build, in full
  m/<marker>/settlements.jsonl    one line per stage; the last is the disposition
  m/<marker>/summary.txt          a readable account of that marker
  dead/<marker>.<why>             attempts that were restarted, postponed or failed
  overwatch.jsonl                 the supervisor's findings, and their judgements
  chat.jsonl                      your conversation with the supervisor
  spec/                           the specification, copied in so the agents can read it
```

Everything there is line-delimited JSON. `GET /api/settlements`, `/api/trace` and `/api/feedback`
return it as JSON arrays if you would rather not read files.

## Asking it things

`/chat` (the ✉ beside the gear) puts a question to the agent that watches the run — how a checker
family is settling, why one marker took an hour, what a stage actually said. It reads the record and
cannot change it.
