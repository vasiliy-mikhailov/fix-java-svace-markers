# 13. Settings as data

**Every setting this program has is a file on the results volume, read at the moment it is used, by
the process that uses it.** Not an environment variable, not a Java constant, not a field cached at
class load. The one exception is deliberate and named below (`QWEN_API_KEY`, at the model call
site), and it is the exception that proves the shape of the rule.

Where those files *are* is still environment (`RESULTS`, `TUNING`, `PROMPTS`, `PORT`, `CHECKOUTS`),
fixed for the life of a process. Locating a file and setting a value are different things, and only
the second one is what this chapter says must not be an environment variable.

Vocabulary for this chapter. A **marker** is one line `repo|file|line|checker`. A **prove** is one
JVM proving one marker; the pool starts a fresh one per marker. A **claim** is the directory
`results/claims/<id>` by which the pool marks a marker as taken — it exists for exactly as long as
its prove. The **results root** is the volume every process here mounts (`/results` by default,
`RESULTS` in the shell and in `Maven`/`Gradle`). **The effect boundary** is the phrase the pages
use: *takes effect on the next marker a prover starts*.

---

## Why a file per prove, and not an environment variable

The model name, the endpoint, the two bounds, the pool width and the marker queue were all
environment variables, Java constants, shell arguments or files baked into the image.

**Changing any of them meant recreating a container or building an image, and both of those kill the
pool.** Killing the pool orphans every claim in flight: each dead prove leaves a claim directory
with no process behind it, and — before the sweep existed — those markers were then skipped forever
by the very gate that stops two provers taking the same marker. The cost of a settings change was
measured in stranded markers.

The property that makes files work here: **a prove is a fresh process per marker.** So a file read
at construction time takes effect on the next marker and disturbs nothing running. The read points,
and the exact moment each takes effect:

| Setting | Read by | Read when | Takes effect |
|---|---|---|---|
| model, endpoint, temperature, cap, bounds | `Prove.model(agent, trace)` | once per agent constructed inside a prove | the next marker a prover starts |
| prompt override | `Agents.runtime(...)` via `Prompts.effective` | once per agent constructed inside a prove | the next marker a prover starts |
| pool width | `entrypoint.sh` `width()` | at the top of every loop iteration, and again in the wait loop | as the next marker starts |
| JDK | `Subject.javaHome(RESULTS)` in `Maven`/`Gradle` | on every build invocation | the next build |
| markers, credential, zip | `entrypoint.sh` `checkout()` and the pool's read of `$2` | at pool start / per checkout | the next run, or the next checkout |

**Nothing is cached across a prove and nothing is pushed to a running one.** A prove already running
keeps the tree, the prompt and the endpoint it was given. That is stated on every settings tab
because a reader who expects a live change would otherwise read the next few settlements wrong.

---

## The failure direction, which is the same in every reader

**An unreadable setting is not an empty setting.** Every reader in this chapter catches
`IOException | RuntimeException` — both, because a malformed file throws the second and a missing one
throws the first — and returns a named fallback rather than propagating. For everything that steers
the pipeline the fallback is *the behaviour that was already happening*, never "unset":

| Reader | Cannot read → | Why that direction |
|---|---|---|
| `Tuning.stored()` | `Map.of()`, so every getter falls back to env then constant | an unreadable file leaves the pipeline exactly where it was rather than pointing it at nothing |
| `Prompts.saved(agent)` | `""`, so `effective` returns the built-in | an agent with no instructions does not fail, it answers something, and a run of those is indistinguishable from a run that went badly |
| `Workers.of(results)` | `DEFAULT` (4) | returning nothing would stop the pool dead over a typo in a one-line file |
| `Subject.jdk(results)` | `"25"` | the image's own JDK, which is the state before anybody chose |
| `Subject.count(results)` | `0` | both callers are the subject page and the reply after an upload; no gate anywhere runs off this number |
| `Subject.repos(results)` | what was collected so far | the partial list is still a true list of repositories the queue names |
| `Subject.tokenHost` / `token` | `""` | a malformed credential file reads as no credential, not as a garbage one |

The last three are read only for display, which is why blank is allowed to mean blank there. The
first four are the ones a wrong direction corrupts a run through — the model settings and the prompt
directly inside a prove, the JDK through every build, and the width through the shell's copy of the
same rule (below), which is the one that actually starts JVMs.

Three of these readers check `Files.isReadable` *before* trying — `Tuning.stored`, `Prompts.saved`,
`Workers.of` — and an unreadable path takes the same route as a thrown read. A **directory** where the
file should be is the case the tests actually exercise (`WhatTheModelIsAskedForTest.unreadable`
creates one at `Tuning.WHERE`): it passes the readability check and throws on the read, and it must
still land on the fallback rather than on a stack trace.

Getting one of these backwards is silent: a blank prompt, a zero width or an empty model name
produces a run that looks like a run and means nothing.

---

## The settings volume

Every path below is relative to the results root. Two of them are named in `Tools.SECRET`, which is
why the basenames matter and cannot be renamed casually — see chapter 06.

| Path | Shape | Written by | Read by | Located how |
|---|---|---|---|---|
| `model` | `key=value` lines, mode `rw-------` | `/settings` → the model | `Tuning` (every prove) | `$TUNING`, default `/results/model` |
| `prompts/<agent>.txt` | the whole prompt, plain text | `/settings` → prompts | `Prompts.effective` (every prove) | `$PROMPTS`, default `/results/prompts` |
| `workers` | one integer and a newline | `/settings` → the run | `Workers.of`, and `entrypoint.sh` `width()` | `results.resolve(...)` / `$RESULTS` |
| `jdk` | one of `25 21 17 11 8` and a newline | `/settings` → the subject | `Subject.jdk` via `Maven`/`Gradle` | `results.resolve(...)` |
| `markers.txt` | one marker per line | `/settings` → the subject | the pool (`slice $2`), `Overwatch`, the dashboard | `results.resolve(...)` / the pool's argument |
| `markers-before-<epochMillis>.txt` | the queue that was replaced | `Subject.saveMarkers` | a person | `results.resolve(...)` |
| `git-credentials` | one git credential line, mode `rw-------` | `/settings` → the subject | `git config credential.helper store --file=…` | `results.resolve(...)` / `$RESULTS` |
| `source.zip` | the uploaded tree | `/settings` → the subject | `entrypoint.sh` `checkout()` | `results.resolve(...)` / `$RESULTS` |

**The last column is not decoration.** `model` and `prompts/` are located by their own environment
variables with absolute defaults; everything else is resolved against a `Path results` the caller
hands in — the dashboard passes the settlements file's parent, `Maven` and `Gradle` pass
`getenv("RESULTS", "/results")`, the shell passes `$RESULTS`. In a deployment all of these are the
same directory. Point the dashboard at a settlements file somewhere else and the two halves come
apart: the prompts and the model stay at `/results` while the queue, the width and the credential
follow the argument.

The two locations are overridable for tests and only for tests:

```java
static final Path Tuning.WHERE  = Path.of(getenv("TUNING",  "/results/model"))    // a FILE
static final Path Prompts.WHERE = Path.of(getenv("PROMPTS", "/results/prompts"))  // a DIRECTORY
```

**Both are `static final`, resolved once at class load.** The freshness this chapter is about comes
from the process being fresh, not from re-reading the environment: it is the *contents* that are read
per use, never the location. That is why the override has to be an environment variable set before
the JVM starts, and why a test cannot simply assign a temp directory.

Surefire therefore sets them in `pom.xml` — `TUNING` to `${project.build.directory}/test-model`,
`PROMPTS` to `${project.build.directory}/test-prompts`. Without that the prompt tests
`assumeTrue(writable())` against `/results/prompts` and skip on every machine except the container —
three tests that pass by not running.

---

## The model: `results/model`

One `key=value` per line. Parsing splits at the **first** `=` with the key stripped and the value
stripped; a line with no `=` at index > 0 is ignored. Writing replaces newlines inside a value with
a space, so a value can never become two settings.

```
model=Qwen3-Coder-30B
base_url=http://vllm:8000/v1
temperature=0
max_tokens=0
patience_minutes=4
ceiling_minutes=240
api_key=…
```

Every value is **clamped on the way out, not on the way in** — a file edited by hand or left behind
by an older version must not be able to put the pipeline somewhere the code does not expect.

| Key | Type | Clamp | Fallback when blank/absent/junk |
|---|---|---|---|
| `model` | text | — | `$QWEN_MODEL`, then `""` |
| `base_url` | text | — | `$QWEN_BASE_URL`, then `""` |
| `temperature` | number | `[0, 2]` | `Tuning.TEMPERATURE` = `0.0` |
| `max_tokens` | number | `[0, 200000]`, integer | `Tuning.MAX_TOKENS` = `0` |
| `patience_minutes` | number | `[1, 120]`, minutes | `4` |
| `ceiling_minutes` | number | `[1, 1440]`, minutes | `240` |
| `api_key` | text | — | `$QWEN_API_KEY`, then `""` |

Junk is not a value: `Double.parseDouble` failing returns the fallback, so `temperature=warm` leaves
temperature at 0 rather than at nothing. A blank string is not a value either — `model=  ` reads as
"unset" and falls back to the environment, because an empty model name is not a model name.

**`base_url` blank is fatal, on purpose.** `Prove.model` throws
`IllegalStateException("no endpoint: set QWEN_BASE_URL or the model settings")` rather than calling
something that is not there. The scheme then decides the HTTP version: `https://` → HTTP/2,
anything else → HTTP/1.1, because offering `Upgrade: h2c` to a cleartext vLLM gets it accepted and
the body is then lost.

**`max_tokens` 0 means unset, not "a cap of zero".** The builder is only given `maxTokens` when the
value is above zero. A cap is not a smaller number, it is a different behaviour: the last one
bounded a stall by truncating the reasoning that caused it, mid-thought.

**Temperature is 0 because these agents certify.** A judge that answers differently on the same
evidence twice is not a judge, and every loopback in this chain replays a decision. It is editable
because a run producing the same wrong answer every time is sometimes worth shaking — that is a
diagnostic, not a setting to leave changed.

### The two bounds are two settings, and confusing them killed eighty-six proves

```java
static Duration patience()  // how long a call may go with NOTHING on the wire
static Duration ceiling()   // how long a call may go on ANSWERING
```

`patience` is handed to the streaming client as its timeout; `ceiling` is handed to `Thinking`
alongside it. One measures silence, the other measures speech that gets nowhere. **A single
"timeout" that measures the second while reading like the first reported a healthy endpoint as dead
eighty-six times**, and each one cost a marker that was fine. They therefore have separate names,
separate clamps, separate sentences on the page (`silence, minutes` and `generation, minutes`), and a
test whose opening sentence is the incident — `WhatTheModelIsAskedForTest`, `twoBounds`. A rebuilder
who merges them back into one number reintroduces exactly that bug, and it will look like an endpoint
problem.

### Saving

`Tuning.save(Map<String,String> given)` starts from what is on disk and:

- writes only the keys in `all()` — `model`, `base_url`, `temperature`, `max_tokens`,
  `patience_minutes`, `ceiling_minutes` — and only those the form actually mentioned (`given.get(name)
  != null`, so a *present but empty* field is written as empty and an absent one is left alone), so
  **a form that posts one field must not blank the rest**;
- strips each value it writes;
- keeps any other key already in the file untouched;
- handles `api_key` **by name, outside that loop**, and only when non-blank;
- removes `api_key` when the form carries `forget_key=1` — **after** the put, so forgetting wins over
  a value submitted in the same request;
- creates the parent directory, writes every pair as `key=value\n` with newlines inside a value
  replaced by a space, then chmods the file `rw-------`, tolerating a filesystem without POSIX
  permissions (the file is no less correct, only less private).

`all()` returns **effective** values — file, then environment, then constant, each clamped and
`temperature` rendered by `trim` (`0` rather than `0.0`, `0.7` rather than `0.7000000000000001`).
Two consequences a rebuilder should expect rather than discover: the page's boxes show the
environment's values when no file exists, so pressing save on an untouched page writes them into the
file and `edited()` becomes true; and because the boxes carry clamped values, a re-save normalises a
hand-edited out-of-range file to its clamp.

`Tuning.revert()` deletes the file; `Tuning.edited()` is `Files.exists(WHERE)` and is what the page
uses to decide between "edited — the environment's values are underneath" and "every value is the
environment's or the code's".

`Tuning.all()` is a `LinkedHashMap` in page order, and **`api_key` is deliberately not in it.**
That map is rendered into plain fields and echoed back on every save; a credential must not be swept
along by a loop written for parameters. A test asserts not only that `api_key` is absent but that no
key in `all()` even contains the substring `key`.

### The key

The key was left out of the page on the argument that a credential is not a parameter. The owner of
this deployment asked for it — their key, their box, behind auth — so it is there, masked, with a
reveal button and a copy button. **The cost is written down rather than hidden:** reveal and copy
need the value in the page, so it is in that page's source and in whatever caches or screenshots
that page. That is why the field is a password by default and why the page needs authentication in
front of it.

- Blank submission **leaves the stored key alone** rather than clearing it. A browser that empties
  the field must not be able to silently unset the credential and leave every agent talking to an
  endpoint that refuses them. Forgetting it is a separate checkbox, `forget_key=1`.
- `Tuning.keyed()` is "a key is set at all"; `Tuning.keyFrom()` returns the literal strings
  `"this page"` or `"the environment"` — which is the thing a reader needs before changing one.
- The file is `rw-------` because it sits on a volume three containers mount and nothing else in it
  is a secret.
- `Tools`' own secret set — `Set.of("model", "git-credentials")`, private to that class — refuses
  `read_file` on either by path segment and redacts their shapes (`api_key=…`, `scheme://user:token@`)
  out of every other tool's results, `grep` included. A mask that a second route walks around is not
  a mask.

**Two things the current source does not do, stated because a rebuilder will otherwise "fix" the
wrong side.** Both are verifiable in the tree as it stands:

1. `Prove.model` passes `.apiKey(env("QWEN_API_KEY"))` and `env` throws
   `IllegalStateException("QWEN_API_KEY is not set")` when it is absent. `Tuning.apiKey()` is read
   only by the dashboard, for display. **A key stored through the page therefore does not reach the
   model client, and an empty `QWEN_API_KEY` fails the prove regardless of what the page shows.**
2. In `Dashboard.theModel()` the `api_key` input and the `forget_key` checkbox are emitted *before*
   the `<form method=post action='/settings'>` tag that carries `setting=model`, and they have no
   `form=` attribute — so a browser does not submit them. `Tuning.save` handles both correctly if
   they arrive; nothing in the page as written makes them arrive.

---

## The prompts: `results/prompts/<agent>.txt`

**An override replaces the built-in entirely. There is no merge**, because a prompt half from the
code and half from a box is a prompt nobody can read in one place — which is the failure this whole
program exists to avoid.

```java
static String effective(String agent, String builtIn)  // saved(agent) if non-blank, else builtIn
static String saved(String agent)                      // the override as stored, RAW, or ""
static void   save(String agent, String prompt)        // mkdir -p WHERE; writes prompt.strip() + "\n"
static void   revert(String agent)                     // deletes the file
static boolean same(String one, String other)          // same instruction?
```

Why it exists: **sixteen of the faults found in one run were "the prompt says nothing about this"**
— each a paragraph somebody could have written in a minute and could not, because it was a Java text
block behind an edit, a build, an image and a redeploy.

The overrides live with the results and not in the image, because that is the volume every prover,
the supervisor and the dashboard already share — and because a prompt that produced a settlement
belongs beside the settlement it produced.

**Blank is not an instruction.** `save` strips, so an emptied textarea lands as a file containing
only a newline, `saved()` reports it blank, and `effective` returns the built-in. There is no way
through this API to give an agent no instructions.

**The agent name is flattened, not trusted.** It arrives from a form field:

```java
String name = agent.replaceAll("[^A-Za-z0-9._-]", "").replaceAll("^[.]+", "");
return WHERE.resolve((name.isBlank() ? "unnamed" : name) + ".txt");
```

Dropping the separators is what makes traversal impossible: `../../etc/passwd` becomes a long ugly
filename that is a direct child of the prompts directory and cannot become a path out of it. A
sanitiser that rejected or replaced traversal would still be resolving a path; this one cannot
produce one.

**`same()` compares instructions, not bytes.** `normalise` maps `null` to `""`, converts `\r\n` to
`\n`, strips, then removes `[ \t]+` before each newline — in that order. A text block, a textarea and a
file disagree about trailing whitespace and line endings, and none of those differences changes what
an agent is told — comparing raw would report every prompt as edited the moment it was saved
unchanged. This is what the per-marker prompts tab uses to say "changed since this ran".

The editable names are `Agents.ORDER`, in pipeline order rather than alphabetical:

```
CHAIN : reproducer, proof-critic, fixer, fix-critic, pr-maker, pr-critic,
        verdict, verdict-critic, estimator, estimator-critic
WATCH : overwatch, overwatch-critic, interpreter, interpreter-critic
ASKED : chat
```

The editor builds its list in three steps: `ORDER` first; then anything in `BUILT_INS` that `ORDER`
does not name, sorted, so a newly added agent is visible before it is listed rather than invisible
until somebody remembers; then `removeIf(a -> !builtIns.containsKey(a))`, so **a name with no
built-in collected behind it does not appear at all.**

`BUILT_INS` is populated once at dashboard start-up (`Agents.builtIn`) by constructing every runtime
and throwing it away; a `SubAgentRuntime` makes no model call until it is run. `Agents.runtime`
records the built-in into the map **before** the line that builds a model, and the collector swallows
the `RuntimeException` from a missing endpoint — so reading what an agent is told never requires an
inference endpoint to be up. Those two rules meet at the `removeIf`: record the prompt *after*
building the model instead, and on any box where `QWEN_BASE_URL` is unset the map stays empty, the
`removeIf` empties the list, and the prompts page renders no agents at all rather than an error.

The order in `ORDER` is also what the per-marker tabs and the collector use. **One list, not three:**
the tabs were once a separate copy and were missing `verdict-critic` entirely, so the agent that can
send a settlement back for rework had no page and nobody noticed.

---

## The width: `results/workers`

```java
Workers.DEFAULT = 4;  Workers.LEAST = 1;  Workers.MOST = 16;
static int of(Path results)                   // clamp(parse(file)) or DEFAULT
static void save(Path results, int workers)   // writes clamp(workers) + "\n"
```

Sixteen at the top because the provers share one inference endpoint: past that they are not proving
markers faster, they are queueing on the same tokens and every answer gets slower. Four is what a
run has been doing.

**The bound exists twice, in Java and in the shell, and both are meant.** Java is what a person
types at; the shell is what starts JVMs, and a file edited by hand or left behind by an older
version must not be able to start ninety JVMs against one GPU. Neither trusts the other. The shell's
copy, which the pool re-evaluates at the top of each iteration and again inside the wait loop:

```bash
asked="${3:-4}"          # captured OUTSIDE the function: inside it, $3 is the FUNCTION's third
width() {                # argument, which there never is one of, so the fallback would be ""
    w=$(cat "$RESULTS/workers" 2>/dev/null | tr -cd '0-9')
    [ -n "$w" ] || w="$asked"
    [ "$w" -ge 1 ] 2>/dev/null || w=4
    [ "$w" -le 16 ] 2>/dev/null || w=16
    echo "$w"
}
```

Behaviour a rebuilder must reproduce (`src/test/width_test.sh` asserts each): no file → 4; `8` → 8;
`99` → 16; `0` → 4; `x` → 4; `" 6 "` → 6. Note that the two clamps differ in one corner and that is
harmless: an unparseable file gives 4 on both sides, an out-of-range *number* gives `LEAST`/`MOST`
in Java and 4/16 in the shell, and both are inside `[1,16]`.

The dashboard's save path parses with a helper that returns `0` for junk, and `Workers.save` clamps
that to `LEAST`, so a mistyped form field narrows the pool to 1 rather than widening it.

**Lowering the width does not stop a prove that is already running.** The pool simply stops
replacing finished provers until the count is back under the number.

---

## The subject: what is being proved, and how to reach it

Three ways in, and **they are not alternatives to each other**: the markers say which defects, the
credential says how to reach a private repository, the zip is for a tree that is not in a reachable
repository at all.

### The markers, validated before they replace anything

**A queue with a bad line does not fail at upload — it fails eight hours later as one marker that
never ran, and a reader has no way to tell that from a marker that ran and decided nothing.** So
`Subject.complaints(text)` reads the whole file first and `saveMarkers` refuses a file with any
complaint rather than half-taking it.

Per non-blank line, in this order, at most one complaint per line:

| Test | Complaint |
|---|---|
| `line.split("\\|").length != 4` | `line N: K field(s), not 4 — it is repo\|file\|line\|checker` |
| field 3 not `\d+` | `line N: "…" is not a line number` |
| field 1 not `https?://\S+` or `/\S*` or `\S+\.zip` | `line N: "…" is not a repository URL` |

- `N` is the 1-based index in the file, including blank lines, because a queue is hundreds of lines
  and "invalid format" sends somebody reading all of them.
- Quoted values longer than 40 characters are cut with a trailing `…`.
- Complaints stop at 12 and append `… and possibly more; fix these first` — at most 13 entries.
- No non-blank line at all → the single complaint `no markers in it at all`, because a run with no
  markers looks exactly like a run that has finished.
- Splitting drops trailing empty fields, so `repo|file|line|` is three fields and is refused.

On acceptance, **the queue that was running is kept**: the old `markers.txt` is moved to
`markers-before-<System.currentTimeMillis()>.txt` and the new text is written stripped, with one
trailing newline. A settled marker is matched by its key, so replacing the queue does not invalidate
the record — but it does make the old queue the only explanation for results naming markers the new
one has never heard of.

`Subject.count` is the number of non-blank lines. `Subject.repos` is the first field of every line,
stripped, non-blank, distinct, **in file order** — that is what the page shows and what a credential
has to be able to reach.

### The credential, which does not go into a URL

```java
static void saveToken(Path results, String host, String token)
```

Writes one line to `results/git-credentials`, then chmods `rw-------`:

```
https://<user>:<token>@<host>\n
```

Always `https://`, whatever the host. `<token>` and `<host>` are stripped as they are written;
`<user>` is `oauth2` when the host string contains `gitlab`, otherwise `x-access-token`. **A blank
token writes nothing at all** — it returns before the write, so a previous credential survives an
empty submission rather than being replaced by an entry that authenticates as nobody. The dashboard
refuses a submission missing either field before this is reached.

**A token pasted into `https://token@host/repo` is in the clone command** — so it is in the process
list every prover can read, in `slice.log`, and in anything git prints on failure. Git has a store
for exactly this, and `entrypoint.sh` points git at it when the file exists:

```bash
git config --global credential.helper "store --file=$RESULTS/git-credentials"
```

Reading it back for the page: `tokenHost` is everything after the **last** `@` (so a token
containing `@` still parses), and `token` is between the first `:` at or after index 8 and that last
`@`. Either returning `""` means "no credential", never a partial one.

### Which JDK the subject's tests run on

```java
static final List<String> JDKS = List.of("25", "21", "17", "11", "8");
static String jdk(Path results)       // the file's value if it is in JDKS, else "25"
static String javaHome(Path results)  // "" for 25, else "/opt/java/" + chosen
static void saveJdk(Path results, String chosen)  // ignores anything not in JDKS
```

**This is not about compiling.** javac 25 targets every one of these. It is about what the subject's
tests *run* on: Surefire forks a JVM from `JAVA_HOME`, so a project written for 8 executes on 25
unless it is pointed elsewhere, and there it meets strong encapsulation, removed APIs and bytecode
libraries that cannot read a class file this new. Each of those arrives as "the build produced no
test result", which is never taken as evidence and costs the marker anyway.

**Blank rather than a path for 25**, because the base image's JDK sits at a path this program did not
choose and must not hard-code — leaving `JAVA_HOME` alone is how a build gets it. `Shell.runWith`
sets both `JAVA_HOME` **and** prepends `$javaHome/bin` to `PATH` when the value is non-blank:
setting only the first leaves `java` on the PATH resolving to the image's own, so a build would
compile under one JDK and test under another, and fail in a way that reads like the project rather
than like the setting.

**A JDK not in `JDKS` is dropped on the floor, not written.** `saveJdk` returns without touching the
file, so `jdk()` keeps answering what it answered before — pointing `JAVA_HOME` at a directory that
does not exist would make every build fail in a way that reads like the project rather than like the
setting. `jdk()` re-checks membership on read as well, so a hand-edited `jdk` file saying `14` or
`eight` still builds on 25.

The paths are real: the image is `maven:3.9-eclipse-temurin-25`, and 8, 11, 17 and 21 are copied in
from the matching `eclipse-temurin` images to `/opt/java/8`, `/opt/java/11`, `/opt/java/17`,
`/opt/java/21`. **There is no `/opt/java/25`** — that is the whole reason 25 maps to blank.

`Maven` and `Gradle` read this from `getenv("RESULTS", "/results")` — the results **root**, not the
per-marker lane directory a prove writes into.

### The source zip

`results/source.zip`. **While one is present it IS the subject and nothing is cloned** — no network
call at all. `entrypoint.sh checkout()` re-extracts when the checkout is missing or the zip is newer
(`-nt`), using `jar xf` with `unzip -q` only as a fallback: a jar is a zip, the JDK is already in the
image, and adding a package to avoid noticing that would be a dependency bought with nothing. A zip
holding one directory is unwrapped when there is no `pom.xml`/`build.gradle` at the top, so marker
paths starting at `src/` resolve against the tree rather than one above it. A tree with no `.git`
gets `git init`, `add -A` and one commit, because the worktree machinery wants a repository.

Removing the zip (`forget`) restores cloning.

---

## Uploads: multipart, by hand

`HttpServer` parses a query string and nothing else, so `Upload` splits `multipart/form-data`
itself, and does exactly that much.

```java
static boolean isMultipart(HttpExchange e)          // Content-Type lowercased startsWith multipart/form-data
static Map<String, byte[]> parts(HttpExchange e)    // field name → raw bytes
static String text(Map<String, byte[]> parts, String name)  // UTF-8, "" when absent
static final int LIMIT = 64 * 1024 * 1024;
```

The algorithm, exactly:

1. Find `boundary=` case-insensitively in the Content-Type; missing header or missing `boundary=`
   → **no parts, an empty map, and no exception**. Strip surrounding quotes, then whitespace, from
   the value. (An empty map is what makes `subjectPosted` answer `!nothing to do.` rather than fail.)
2. Read the whole body into memory in 16KB chunks. **Scanned, not streamed, and bounded** — a
   settings form carries a marker list or a source archive and both fit in memory; streaming would
   be the right answer for an upload this program does not have. Past `LIMIT` it throws
   `IOException("more than 64MB; refused")`, which is what stops a browser pointed at a DVD image
   from taking the dashboard down. The message is built as `"more than " + (LIMIT/1024/1024) + "MB; refused"`.
3. Delimiter is `("--" + boundary)` in ISO-8859-1. Walk occurrences pairwise; a part with no
   following delimiter ends the walk (the closing `--boundary--` provides the last one).
4. Within a part, find `\r\n\r\n`; it must be found, be at index > 0, and fall before the next
   delimiter, or the part is skipped. The bytes before it are headers read as ISO-8859-1; the field
   name is what lies between `name="` and the next `"`.
5. The value is the bytes from just after the blank line to **two before** the next delimiter —
   those two are the CRLF that belongs to the boundary and not to the content. The length is floored
   at zero.
6. A part whose headers yield no name is skipped. Repeated names: last one wins (`LinkedHashMap`,
   insertion-ordered). **The filename is never read** — only the field name decides what a part is.
   The search is for the literal `name="`, which also occurs inside `filename="`; browsers emit
   `name` first, so the first match is the field name.

Consequences worth knowing: the delimiter search does not require a preceding CRLF, so content
containing the literal boundary string would split a part; text fields are decoded UTF-8 only by
`text()`, while file parts stay bytes; and the 64MB refusal surfaces to the reader as a refused
upload, not as a truncated one.

---

## The routes, and how a POST is dispatched

`GET /settings?a=` selects the tab: `run` → the run, `model` → the model, `subject` → the subject,
anything else (including absent) → prompts. `GET /prompts` is a permanent redirect to `/settings` —
cheap to keep and rude to break, because that URL shipped.

`POST /settings` is dispatched in this order, and the order is the contract:

| Condition | Action | Reply |
|---|---|---|
| `Upload.isMultipart` | `subjectPosted` | **200 with the page**, not a redirect — what a reader needs after an upload is which lines were wrong |
| `setting=model`, with `revert` | `Tuning.revert()` | 303 → `/settings?a=model` |
| `setting=model` | `Tuning.save(form)` | 303 → `/settings?a=model` |
| `setting=workers` | `Workers.save(here, (int) num(form["workers"]))` | 303 → `/settings?a=run` |
| otherwise, with `revert` | `Prompts.revert(form["agent"])` | 303 → `/settings#<agent>` |
| otherwise | `Prompts.save(form["agent"], form["prompt"])` | 303 → `/settings#<agent>` |

"With `revert`" is `form.containsKey("revert")`, and the buttons post `revert=1`; the form parser
keeps only `k=v` pairs with `k` non-empty, so a valueless `revert` would not register.

Because the last row is a fall-through, a POST that names no recognised setting and no agent writes
`prompts/unnamed.txt`, which no agent reads.

**In the four urlencoded rows an `IOException` while saving is swallowed, and the redirect is sent
anyway** — the page the browser then loads is drawn from disk, which is the honest reply: it shows
what is actually stored rather than what was typed. The multipart row is the opposite and
deliberately so: `subjectPosted` catches and *reports* the throw as `!<SimpleName>: <message>`,
because an upload that vanished silently would read as an upload that was accepted.

The subject forms that carry no file (`token`, `jdk`) still declare `enctype='multipart/form-data'`,
because the multipart branch is the only route to `subjectPosted`. The model and workers forms are
ordinary urlencoded posts.

### What `subjectPosted` says

The reply string is the contract between this handler and the page: **a leading `!` means refused**
and is rendered as `refused` with the marker removed; anything else renders as `done`.

| `setting` | Condition | Said |
|---|---|---|
| `markers` | the chosen text is blank | `!nothing was uploaded and nothing was pasted.` |
| `markers` | accepted | `<n> marker(s) queued. The old queue is kept beside it.` |
| `markers` | complaints | `!the queue was NOT replaced:` then `\n  ` and each complaint joined by `\n  ` |
| `token` | `forget` non-blank | `the credential is gone; clones are public again.` |
| `token` | `host` or `token` blank | `!a host and a token are both needed.` |
| `token` | stored | `a credential for <host> is stored, owner-only, in git's own store.` |
| `jdk` | `Subject.jdk(results)` equals the submitted value after `saveJdk` — i.e. it was in `JDKS` | `builds will run on Java <v> from the next marker.` |
| `jdk` | it was not | `!<v> is not one of the JDKs in this image.` |
| `zip` | `forget` non-blank | `the zip is gone; the markers' repository is cloned again.` |
| `zip` | no `file` part, or zero bytes | `!no file was uploaded.` |
| `zip` | length ≤ 4, or the first two bytes are not `P` and `K` | `!that is not a zip — it does not start with PK.` |
| `zip` | stored | `<n>KB stored. Nothing will be cloned while it is here.` — `<n>` is `length / 1024`, integer division |
| anything else | | `!nothing to do.` |
| any throw (`IOException` or `RuntimeException`) | | `!<SimpleName>: <message>` |

`forget` is `!Upload.text(parts, "forget").isBlank()`, read once at the top and used by both `token`
and `zip`. `setting` is likewise read once and stripped.

For `markers`, the uploaded `file` part wins when it is present and has bytes; the pasted `text` part
is the fallback. Both are decoded UTF-8, and the refusal is decided on the *result* — a file of
nothing but whitespace refuses with the same sentence as no file at all.

Whatever it says, the reply is `theSubject(results, said)` — **the whole subject page, re-read from
disk, with the sentence at the top**. Nothing about an upload is reported anywhere but there.

---

## What a rebuilder must not simplify back

- **Do not merge `patience` and `ceiling`.** Two names, two clamps, two sentences. One "timeout"
  killed eighty-six live proves.
- **Do not read settings once per process.** Per prove is what makes an edit take effect on the next
  marker without killing the pool and orphaning claims.
- **Do not make an unreadable file mean an empty setting.** For the four readers that decide
  something — the model settings, the prompt, the width, the JDK — the fallback is the environment or
  the constant, never nothing.
- **Do not put the credential in the parameter loop**, and do not let a blank field clear it.
- **Do not drop either copy of the width clamp.** One is what a person types; the other is what
  starts JVMs.
- **Do not accept a marker file line by line.** Validate the whole thing, refuse the whole thing,
  keep the old queue.
- **Do not merge a prompt override with the built-in.** Whole replacement or nothing.
- **Do not record the built-in prompt after building the model.** It is recorded first, and the
  collector swallows the missing-endpoint throw, so that reading what an agent is told never depends
  on an inference endpoint being reachable.
