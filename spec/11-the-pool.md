# 11. The pool, the queue and claims

One prove is one process against one marker. Everything in this chapter exists to run three hundred
of them on four workers without two provers taking the same marker, without a dead attempt retiring
the marker it died on, and without one slow marker holding a quarter of the pool while the queue
waits.

Vocabulary used throughout:

- **marker** — one line, `repo|file|line|checker`. The whole line is the marker's *key*.
- **queue** — the marker file the pool reads, top to bottom. `<results>/markers.txt` in a deployment.
- **id** (also **slug**) — the directory name a marker gets: `Ping.java_34_FB.DM_DEFAULT_ENCODING`.
- **lane** — `<results>/m/<id>/`, everything one attempt at one marker produced.
- **claim** — `<results>/claims/<id>/`, an empty directory meaning "a prove owns this marker now".
- **width** — how many proves run at once.
- **disposition** — one of the seven states this program decides. Listed below, exactly.
- **settled** — a marker that has reached a disposition, anywhere in the record.
- **postponed** — set aside for taking much longer than the others; proved after the queue is done.
- **sweep** — the pass over `claims/` that a run does before it starts, to free markers stranded by
  earlier runs.

---

## The entrypoint, and every mode

`agent/entrypoint.sh` is the image's `ENTRYPOINT`; `CMD` is `["dashboard"]`, so a container started
with no arguments serves the record. The script defaults the same way on its own —
`case "${1:-dashboard}" in` — so an `ENTRYPOINT` invoked with no `CMD` at all still serves rather
than printing usage.

**It is `#!/bin/bash`, not `sh`, and that is load-bearing.** The pool waits on `wait -n` and counts
with `jobs -p`, neither of which is POSIX. A pool built without them either polls on a sleep or
spawns everything at once.

```bash
set -eu
CP="/opt/agent/agent.jar:/opt/agent/lib/*"
RESULTS="${RESULTS:-/results}"
CHECKOUTS="${CHECKOUTS:-/work/checkouts}"
```

| mode | what it runs |
|---|---|
| `prove 'repo\|file\|line\|checker'` | `checkout` of the marker's repo, then `exec java … Prove <dir> <marker> $RESULTS` — one marker, no claim, no lane; the trace and settlements land at the top of `$RESULTS`. |
| `slice <markers> [n]` (alias `parallel`) | the pool. Everything from *The slice loop* below. |
| `overwatch [seconds]` | `exec java … Overwatch "$RESULTS" "${2:-900}"` — the supervisor alone. |
| `seed [cases]` | `java … ModelTest --seed "$RESULTS/trace.jsonl" "${2:-$RESULTS/cases.jsonl}"` — not `exec`. |
| `test [cases]` | `checkout "${REPO:-https://github.com/WebGoat/WebGoat.git}"`, then `exec java … ModelTest <dir> <cases> "$RESULTS"`. |
| `serve [seconds]` | `Overwatch` in the background appending to `$RESULTS/overwatch.log`, then `exec … Dashboard "$RESULTS/settlements.jsonl" "${PORT:-8087}"`. |
| `dashboard` | `exec … Dashboard "$RESULTS/settlements.jsonl" "${PORT:-8087}"`. |
| anything else | the usage line on stderr, `exit 2`. |

The usage line, exactly:

```
usage: prove 'repo|file|line|checker' | slice <markers> [concurrency] | serve [seconds] | overwatch [seconds] | test [cases] | seed [cases] | dashboard
```

**`serve` is deliberately asymmetric: watcher in the background, dashboard in the foreground.** A
supervisor that dies must not take the record with it — its own loop already survives a failed pass,
and if the process goes the dashboard keeps serving what is there. A dashboard that dies *should* end
the container, so the restart policy brings both back. They were two containers off the same image,
which is two things to deploy, two sets of environment to keep in step, and one of them silently
missing its prompts and tuning for a deploy or two.

### The spec is copied into the results volume, on every start

Before the `case`, unconditionally for every mode:

```bash
if [ -d /opt/agent/spec ]; then
    rm -rf "$RESULTS/spec" 2>/dev/null || true
    mkdir -p "$RESULTS/spec" 2>/dev/null || true
    cp -R /opt/agent/spec/. "$RESULTS/spec/" 2>/dev/null || true
fi
```

**Every agent's file tools are rooted at the results directory, so a prompt naming `/opt/agent/spec`
would name a file none of them can read** — and would teach the model to report that its tools are
broken. The prompts say `spec/`, relative to the root the agents have.

Refreshed on every start rather than copied once, so a deploy updates it. The old copy is **removed
first**, so a chapter deleted upstream does not linger as a chapter the watcher still cites.

---

## The checkout, which is the script's job and not the agent's

**A prove needs a tree that is exactly the upstream one.** A previous prove leaves a test and a patch
behind, and inheriting them would let a marker be "reproduced" by the fix for the marker before it.

`checkout <repo>` echoes a directory path. Its rules, in order:

1. The directory is `$CHECKOUTS/$(echo "$repo" | sed 's|.*/||; s|\.git$||')` — **one checkout per
   repository name**. Four provers resetting and cleaning one tree would delete each other's test
   between the write and the build.
2. **If `$RESULTS/source.zip` exists, nothing goes to the network at all.** A source zip is for a
   subject that is not in a repository this container can reach — an export, a vendor drop, something
   behind a VPN — and while one is present it *is* the subject. It is re-extracted when the directory
   is missing or the zip is newer (`-nt`), with `jar xf` and `unzip` only as a fallback: a jar is a
   zip, the JDK is already in the image, and adding a package to avoid noticing that is a dependency
   bought with nothing. A zip holding one wrapper directory is unwrapped when the top level has
   neither `pom.xml` nor `build.gradle`, so marker paths starting at `src/` resolve against the tree.
   A tree with no `.git` is `git init`-ed and committed as `uploaded` (`user.email=a@b`,
   `user.name=fsm`), because the worktree machinery wants a repository.
3. **A credential goes in git's store, never in the URL.** If `$RESULTS/git-credentials` exists,
   `git config --global credential.helper "store --file=$RESULTS/git-credentials"`. A token pasted
   into a clone address is in the process list every prover can read and in the log this writes.
   This is reached only when there is no `source.zip` — that branch returns the directory before it,
   which is the point of "nothing goes to the network".
4. An existing clone is `git reset --hard -q` then **`git clean -xfdq`**. Otherwise
   `git clone --depth 1`.

**`-x`, not just `-fd`, and that is an incident.** `clean` skips ignored files by default and
`target/` is ignored — so a class compiled from the previous marker's *patch* survives a reset that
restored its source, and Maven decides by timestamp whether to recompile. The next marker's RED then
runs against the last marker's fix, which is a green that belongs to somebody else. The dependency
cache lives in `~/.m2`, outside the checkout, so `-x` costs a recompile and not a re-download.

---

## The id

```bash
slug() { echo "$1" | sed 's|.*/||; s|[^A-Za-z0-9._-]|_|g' | cut -c1-80; }
```

Everything through the last `/` is dropped, every character outside `[A-Za-z0-9._-]` becomes `_`, and
the result is cut to 80 characters. A directory named for the marker, so a reader finds a prove by
what it proved.

**`Supervisor.slug(String)` must produce the same string, character for character.** It is the same
rule in Java — `lastIndexOf('/')`, `replaceAll("[^A-Za-z0-9._-]", "_")`, truncate at 80 — and the
supervisor's `restart`, `reprove` and `postpone` address lanes, claims, worktrees and processes by
it. A rebuilder who lets the two drift gets a supervisor whose actions silently miss.

**Two markers must not share an id.** The shipped queue, `examples/webgoat/markers.txt`, is 356
markers and produces 356 distinct ids (checked by hand, not by a test); a queue where the
80-character cut collides would have one marker's claim and lane answer for another's.

---

## The width

`<results>/workers`: one number, the whole file.

```java
static final int DEFAULT = 4;
static final int LEAST = 1;
static final int MOST = 16;
static int of(Path results);                         // clamped; DEFAULT when the file is not
                                                     // readable, or parses as anything but an int
static void save(Path results, int) throws IOException;  // creates results/, writes clamp(w) + "\n"
static int clamp(int workers);                       // max(LEAST, min(MOST, workers))
```

`save` clamps on the way in as well as `of` clamping on the way out, so the file a person can read
already holds the number the pool will use.

**The width is re-read at the top of every iteration of the pool loop, so it can be changed while a
run is going.** It used to be the third argument and nothing else, fixed for the life of the pool, so
widening a run meant killing it — which orphans every claim in flight and throws away whatever those
markers had done.

**It is clamped in Java and again in the shell, and neither trusts the other.** Java is what a person
types at `/settings`; the shell is the side that starts JVMs, and a file edited by hand or left behind
by an older version must not be able to start ninety of them against one GPU.

The shell's copy:

```bash
asked="${3:-4}"
width() {
    w=$(cat "$RESULTS/workers" 2>/dev/null | tr -cd '0-9')
    [ -n "$w" ] || w="$asked"
    [ "$w" -ge 1 ] 2>/dev/null || w=4
    [ "$w" -le 16 ] 2>/dev/null || w=16
    echo "$w"
}
```

**`asked` is captured outside the function on purpose.** Inside it, `$3` is the *function's* third
argument, which there is never one of, so the fallback would have silently become the empty string.

**The failure directions differ and both are deliberate:**

| what is in the file | `Workers.of` | the shell's `width` |
|---|---|---|
| absent, empty, unreadable, `four` | `4` (DEFAULT) | `$asked`, else `4` |
| `  6  \n` | `6` | `6` |
| `900` | `16` | `16` |
| `0` | `1` (LEAST) | `4` |
| `-5` | `1` (LEAST) | **`5`** — `tr -cd '0-9'` deletes the sign before anything compares it |

The two are not the same function and are not meant to be; they agree on the cases a person can
produce from `/settings`, which only ever writes a clamped integer, and they are each safe on their
own for the cases a hand-edited file can produce. The shell keeps *every* digit in the file, so a
`workers` file of two lines (`4\n8`) reads as `48` and clamps to 16, where `Workers.of` fails to
parse it and falls back to 4.

**A width that cannot be read is not zero.** Falling back to the default keeps the run going at the
width it has been; returning nothing would stop the pool dead over a typo in a one-line file.

Sixteen at the top because the provers share one inference endpoint: past that they are not proving
markers faster, they are queueing on the same tokens and each answer gets slower. Lowering the number
does not stop a prove already running — the pool simply stops replacing them until it is back under.

---

## The queue

One marker per line, four fields, `|`-separated:

```
https://github.com/WebGoat/WebGoat.git|src/main/java/org/owasp/webgoat/lessons/xxe/Ping.java|34|FB.DM_DEFAULT_ENCODING
```

Blank lines are skipped by the pool, and by `Subject.complaints` before that. `complaints` refuses a
queue whose lines do not have exactly four fields, whose third field is not `\d+`, or whose first
field matches neither `https?://\S+` nor `/\S*` nor `\S+\.zip`; a file with no non-blank line at all
gets `no markers in it at all`. Every message names the line number, because a queue is hundreds of
lines and "invalid format" sends somebody reading all of them; it stops after 12 and adds
`… and possibly more; fix these first`.

**A bad line that is not caught at upload does not fail at upload — it fails eight hours later as one
marker that never ran**, which a reader cannot tell from a marker that ran and decided nothing.

`Subject.saveMarkers` refuses a file with any complaint rather than half-taking it, and **keeps the
queue it replaces** as `markers-before-<epoch millis>.txt`: results already recorded may name markers
the new queue has never heard of, and the old file is then the only explanation for them.

### The order of the queue

`markers.txt` is sorted **severity, then path, then line as a number, then checker** — Critical,
Major, Normal, Minor, then the markers with no analyser severity, which sort last.

**The file *is* the order** — of the queue, of the dashboard's table, and of what a run stopped half
way through has covered. Two runs are only comparable if they take the markers the same way, and a run
cut short should have spent its time on the worst ones.

The line sorts as a number. As text, 100 comes before 21, which reads as a mistake in a file people
scan. Unknown severity sorts last rather than first because 74 of the shipped markers are in the test
tree, where an analyser severity was never assigned, and they are the least interesting thing in the
queue.

`TheQueueIsInOneOrderTest` **asserts the file is in this order rather than sorting it at load**: a
queue that reorders itself is a queue nobody can diff. It checks the queue shipped in the repository,
`examples/webgoat/markers.txt`, not the deployed `<results>/markers.txt`. The same test asserts no
marker appears twice — a duplicate key means the second copy is skipped as already settled, and the
queue count stops matching what was proved — that the first marker is Critical, and that every line
still parses as four fields with a numeric line.

Severities come from `examples/webgoat/severities.tsv`, tab-separated, four columns:

```
AccountVerificationHelper.java	36	UNUSED_VALUE	Minor
```

— keyed on `<file name>`, `<line>`, `<checker>` with the severity fourth. The key uses the marker's
**base file name**, not its path, while the sort's second component is the full path field.

---

## The slice loop, step by step

`slice <markers> [n]`. `$2` is the queue file; `$3` is the fallback width.

**Setup**

1. `limit=$(width)`; `mkdir -p "$RESULTS/claims" "$RESULTS/m"`.
2. `reference=$(checkout "$(repo_of "$(head -1 "$2")")")` — the reference clone, from the repository
   named by the **first line** of the queue. Prints
   `reference clone ready at <dir>; worktrees are per marker`.
3. The sweep (below).

**The reference is prepared once and then read only.** Every prove used to reset and clean it on the
way past, which meant four subshells mutating the repository the others were adding worktrees from —
and a worktree taken mid-clean is a directory with no `pom.xml` in it, which this program reports as
"nothing can run the test" for a marker that was fine.

**Per line of the queue** (`while IFS= read -r marker; … done < "$2"`):

4. Empty line → skip (`[ -z "$marker" ] && continue`; a line of only spaces is not empty and would
   be taken as a marker). Otherwise `n=$((n+1))` and `id=$(slug "$marker")`. `n` counts every
   non-empty line read, including the ones skipped below, and is what `SLICE DONE` reports.
5. **Wait for a free slot before claiming, so a claim always becomes a prove:**
   ```bash
   limit=$(width)
   while [ "$(jobs -p | wc -l)" -ge "$limit" ]; do
       wait -n 2>/dev/null || sleep 2
       limit=$(width)
   done
   ```
6. `settled "$marker" && continue` — a marker with a disposition anywhere in `m/` is skipped. This is
   what makes a run resumable.
7. `Pace --postponed` says `yes` → skip. Set aside, not skipped: the pass after the queue picks it up.
8. `Pace --tries` at or over `TRIES` → print and skip:
   ```
   === <id> has not settled in <tries> attempt(s); leaving it for a person
   ```
9. `mkdir "$RESULTS/claims/$id" 2>/dev/null || continue` — **the claim**. The loser of a race gets a
   non-zero exit and moves on.
10. Fork a subshell with `&`. Inside it:
    - `out="$RESULTS/m/$id"`, `mkdir -p "$out"`, and `echo "=== [$n] $marker" | tee -a "$out/slice.log"`.
    - `tree="$CHECKOUTS/tree-$id"`, `rm -rf "$tree"`, then
      `git -C "$reference" worktree add --detach -f "$tree" HEAD`, logging to `slice.log`, with
      `cp -a "$reference/." "$tree/"` as the fallback if that fails.
    - If the tree has neither `pom.xml` nor `build.gradle`, write
      `WORKTREE FAILED for <marker> — no build file in <tree>` to `slice.log` and run nothing. **A tree
      with no build file cannot prove anything, and saying so here names the cause**; letting it
      through reports it as a marker that could not be built.
    - Otherwise `java -cp "$CP" tech.mikhailov.fsm.agent.Prove "$tree" "$marker" "$out" >> "$out/slice.log" 2>&1 || true`.
    - `git -C "$reference" worktree remove --force "$tree"`, falling back to `rm -rf "$tree"`.
    - `release "$marker" "$id"`.
11. After the loop: `wait`, then the postponed pass, then `echo "SLICE DONE ($n marker(s))"`.

**A pool, not a partition.** Striping markers across fixed workers means the one that drew the slow
half is still going while the others idle; a pool hands the next marker to whichever prover is free,
and the run finishes when the work does rather than when the unluckiest slice does.

**The queue is read once, top to bottom.** There is no second pass within a run: a claim released or a
lane archived after the loop has passed that line is picked up only by the postponed pass (for
postponed markers) or by a later `slice` invocation, whose sweep reaches back over what the previous
run left.

### A worktree per marker

Each prove gets `$CHECKOUTS/tree-<id>`, added `--detach -f` from the reference at `HEAD` and removed
afterwards. It shares the reference clone's objects, so this costs a file copy and no network, and
**throwing it away is a stronger isolation than resetting a tree, because nothing ignored survives it
either.**

The worktree path is also the **identity of the running prove**. It appears in the prover's command
line as its own argument, so a process can be found by `tree-<id>` followed by a space:

```bash
pgrep -f "tree-$held "                                    # the sweep, deciding if a claim is stale
```
```java
Shell.run(results, "pkill", "-f", "tree-" + id + " ");    // Supervisor.kill
```

The trailing space is what stops `tree-Foo_1_TAINTED_PTR` from matching
`tree-Foo_1_TAINTED_PTR.COOKIE`. **Anything that sweeps or kills must share a process namespace with
the provers**, since this is the only handle either one has on them.

The pattern is a regex, and an id contains `.` — which matches any character. It can therefore only
ever match *more* than intended, and the two readings of an over-match are "a claim looks live" (the
sweep leaves it alone) and "kill a process that was not the target" — the first is the safe
direction and the second is bounded by the `tree-` prefix, which only this pool's worktrees carry.

---

## The claim

**A claim is a `mkdir`.** It is the one filesystem operation that is atomic and tells the loser it
lost, so two provers cannot take the same marker and no lock file is left behind by a process that
died holding it. The directory is empty; its existence is the whole content.

**A claim lasts exactly as long as its prove.** Created immediately before the subshell is forked;
removed by `release` as the last thing the subshell does, on every path — settled, unsettled, killed,
crashed, no build file.

```bash
release() {
    _id=$2
    if ! settled "$1"; then
        mkdir -p "$RESULTS/dead"
        _try=$(java -cp "$CP" tech.mikhailov.fsm.agent.Pace --tries "$RESULTS" "$_id")
        mv "$RESULTS/m/$_id" "$RESULTS/dead/$_id.attempt-$((_try + 1))" 2>/dev/null || true
    fi
    rm -rf "$RESULTS/claims/$_id"
}
```

A settled marker keeps its lane in `m/`, because that is where `settled` looks. An unsettled attempt
is moved to `dead/<id>.attempt-<n>` **because `Prove` appends**: retrying on top of the old trace
makes one prove that changed its mind rather than two attempts with a line between them. Either way
the claim goes.

### The gate that repealed a gate

The pool decides twice whether to prove a marker: `settled`, then `mkdir claims/$id || continue`.

`settled` was deliberately taught to answer **no** for a marker whose prove ended in `infra` — the
state written when a prove *throws* — precisely so the pool would take it again. Three lines later the
`mkdir` skipped it anyway, because the claim from the dead attempt was still sitting there and **no
code path ever removed it**.

So the second gate silently repealed the first. Every marker whose prove threw was retired by the
mechanism that exists to stop two provers taking the same marker; the promise that "a marker already
settled anywhere is skipped" quietly became "already **attempted** anywhere is skipped"; and the fix
written for the reported symptom — markers stuck in `infra` that the pipeline would not revisit —
survived its own fix. **Both gates read correctly on their own; only their order was wrong, which is
why reading either one found nothing.**

Two other readers paid for it:

- The watcher reads `claims/` to tell a prove that is thinking from one that has died, so several
  hundred finished markers arrived in its brief with `idle=1009m  <-- QUIET, still claimed` on the
  end of their line. It spent two whole passes reporting a stalled pipeline that was not stalled, and
  its critic refuted both findings.
- The dashboard's live view read the claims directory as the list of active provers, and gave
  **thirty-four panels for a pool of four**.

Both now filter by settlement as well as by claim. What is left of the gap is the seconds between a
JVM exiting and the shell tidying up after it. `AClaimOutlivedItsProveTest` holds the rule.

---

## `settled()` and the dispositions

**What counts as settled is a disposition, not "anything that is not `proving`".**

```bash
DISPOSITIONS='"state":"(false-positive|by-design|unprovable|reproduced|needs-review|verified/pr-ready|verified/pr-rejected)"'

settled() {
    grep -rlF "\"suspicion_key\":\"$1\"" "$RESULTS"/m/*/settlements.jsonl 2>/dev/null \
        | while read -r f; do
            grep -F "\"suspicion_key\":\"$1\"" "$f" \
                | grep -qE "$DISPOSITIONS" && echo hit
          done | grep -q hit
}

settled_lane() { grep -qE "$DISPOSITIONS" "$RESULTS/m/$1/settlements.jsonl" 2>/dev/null; }
```

**These seven are the states this program actually decides.** Everything else is a prove that did not
finish, and a marker that did not finish goes back in the queue.

This used to skip a marker whose settlements file held any state but `proving`, which meant `infra` —
the state a prove writes when it **throws** — counted as an answer. **A prove killed by the tool
ceiling therefore retired its own marker, and nothing would revisit it.**

Details a rebuilder cannot infer:

- The key and the disposition must be on the **same line**. `settlements.jsonl` is one line per stage
  boundary and each line carries both `suspicion_key` and `state`.
- `settled` asks about a **marker key**, across every lane in `m/`. `settled_lane` asks about **one
  lane** and does not check the key at all — it is what the sweep uses, because a stale claim gives it
  an id and not a key.
- `proving`, `infra` and `queued` are the non-dispositions. `Pace.settled`, `Dashboard.settled` and
  `Interpreter` ask the same question in the negative form — `state` is non-blank and none of those
  three — while the pool asks it as the positive list above. Both forms agree on the seven states
  this program writes. The negative form is the one to prefer when adding a reader: a disposition
  added to `Prove` and forgotten in a positive list reads as unsettled forever, whereas a new
  not-an-answer state reads as settled once and gets noticed.
- `Overwatch.Marker.settledState()` is a fourth variant and lists **four**: not `proving`, not
  `infra`, not `FAILED`, not `queued` (and it does not test for blank, because a lane with no
  settlement line is `proving` by construction there). `FAILED` is the watcher's own word for a lane
  whose trace records a death; it is never written to `settlements.jsonl`.

**The failure direction is "not settled".** No `m/` directory, an unreadable file, a glob that matches
nothing: `settled` says no and the marker is proved. That costs a duplicate prove at worst; the
opposite failure loses a marker silently for the rest of the run.

---

## The retry bound

```bash
TRIES=3
```

**Three, because the first retry tests "was that transient", the second tests "was it transient
twice", and a marker that has failed to settle three times is failing for a reason no further attempt
will discover.** Without a bound, releasing the claim is a loop: the claim is released, the marker is
unsettled, the next pass takes it, and it dies in the same place.

The count is `Pace.tries(results, id)` — the number of directories under `dead/` matching

```
^<id>\.attempt-[0-9]+$        (Pattern.quote(id) + "\\.attempt-[0-9]+")
```

— matched with `String.matches`, so it is the whole directory name and not a prefix.
**Only that one suffix.** `dead/<id>.restart-<n>` and `dead/<id>.before-postponing` do not count.

> A supervisor restart and a postponement are somebody **choosing** to spend another go on this
> marker; a pool retry is the marker having **failed to settle on its own**. Counting them together
> let two supervisor restarts exhaust the pool's allowance for a marker the pool had only ever tried
> once, and the marker went quiet for the rest of the run with nothing saying why.

`Pace.attempts` is the other count — the current lane, if there is one, plus every archived directory
of any archive suffix — and it answers a different question: how much time this marker has cost. `Pace.tries` gates; `Pace.attempts`
and `Pace.totalMinutes` measure.

**A `dead/` directory that cannot be listed returns `Integer.MAX_VALUE`, not 0.** Reporting zero would
let a directory that cannot be listed hand a broken marker unlimited goes at the pool, which is the
one direction this must not fail in. Note the asymmetry: **no `dead/` at all — missing, or a path
that is not a directory — is honestly zero attempts and returns 0.** Only an `IOException` from
listing an existing directory is the unreadable case. `Supervisor.restarts` fails the same way, to
`LIMIT`, for the same reason — though it counts lines in `restarts.jsonl` keyed on `"id"`, not
directories in `dead/`, so `dead/<id>.restart-<n>` is the *record* of a restart and never the count
of one.

`Pace.attempts` and `Pace.totalMinutes` cannot use an exact suffix, because they want every archive
kind, so they use `mine(archived, id)`: the name must start with `<id>.` **and** what follows must
match `[a-z][a-z0-9-]*`. Prefix alone is not enough, because a checker name can be a prefix of
another: `TAINTED_PTR` matches `TAINTED_PTR.COOKIE.abandoned`, so one marker would absorb another's
history and report time it never spent. Archive suffixes are lowercase words; every checker segment
is uppercase, which is what makes the test decidable.

---

## The sweep

Before the loop, once:

```bash
swept=0
for claim in "$RESULTS"/claims/*; do
    [ -d "$claim" ] || continue
    held=$(basename "$claim")
    pgrep -f "tree-$held " >/dev/null 2>&1 && continue
    if ! settled_lane "$held"; then
        mkdir -p "$RESULTS/dead"
        tried=$(java -cp "$CP" tech.mikhailov.fsm.agent.Pace --tries "$RESULTS" "$held")
        mv "$RESULTS/m/$held" "$RESULTS/dead/$held.attempt-$((tried + 1))" 2>/dev/null || true
    fi
    rm -rf "$claim"
    swept=$((swept + 1))
done
[ "$swept" -gt 0 ] && echo "released $swept claim(s) left by earlier runs"
```

**Releasing claims when a prove ends fixes the markers *this* pass strands; it does nothing for the
ones already stranded**, because their stale claim still fails the `mkdir` and they are skipped exactly
as before. The results volume outlives the fix, so the fix has to reach back.

**A claim is stale when no prove is behind it**, and the prove is identifiable because the pool gives
each one a worktree named for the marker — the same string the supervisor kills by. Anything still
running keeps its claim, so a second pool started alongside this one is not robbed of its work.

**The one race left is a claim made microseconds ago whose JVM has not started yet, and that costs a
marker proved twice rather than a marker lost.** That is the direction to keep if a rebuilder changes
this.

The sweep archives with `settled_lane`, not `settled`: it has an id, not a key, so it asks the only
question an id can answer.

---

## The postponed pass

A marker is postponed when it is **working and simply taking much longer than the others** — past
`Pace.MUCH_LONGER` (4) times the median of what settled markers took, with a floor of
`Pace.NEVER_BEFORE` (20) minutes and nothing at all judged until `Pace.ENOUGH` (8) markers have
settled. It stops holding a quarter of the pool while three hundred wait. The measurement and the
supervisor's `postpone_prove` tool are chapter 8's; what follows is the pool's half.

Marker set aside ⇒ `<results>/postponed/<id>` exists, containing the reason and a newline. The name is
the id with every character outside `[A-Za-z0-9._-]` **deleted** (`Pace.file`), which is identical to
the id for anything the pool's `slug` produced — the shell removes the file by raw id
(`rm -f "$RESULTS/postponed/$id"`) and the two only agree because slugs contain nothing else.

After `wait`, with the pool otherwise idle:

```bash
held=$(java -cp "$CP" tech.mikhailov.fsm.agent.Pace --list-postponed "$RESULTS" | wc -l)
if [ "$held" -gt 0 ]; then
    echo "=== the queue is done; $held marker(s) were set aside for taking much longer"
    for id in $(java -cp "$CP" tech.mikhailov.fsm.agent.Pace --list-postponed "$RESULTS"); do
        marker=$(grep -F "$(echo "$id" | sed 's/_[0-9]*_[A-Z].*//')" "$2" | head -1)
        [ -z "$marker" ] && continue
        rm -f "$RESULTS/postponed/$id"
        rm -rf "$RESULTS/claims/$id"
        settled "$marker" && continue
        mkdir "$RESULTS/claims/$id" 2>/dev/null || continue
        …
```

Then, per marker: archive any existing lane, make a fresh one, log
`=== proving again after the queue: <marker>`, add a worktree, run `Prove`, remove the worktree,
`release`.

Everything specific about this pass:

- **It is sequential.** No `&`, no `wait -n`. Each postponed marker gets the pool to itself, which is
  the point: its slot now costs nothing.
- **It does not consult `TRIES`.** The gate at step 8 is the queue's; this pass is the promise that a
  postponement is not a deletion.
- **It does not check for a build file either.** The main loop's `pom.xml`/`build.gradle` guard is
  not repeated here: this pass runs `Prove` on whatever the worktree turned out to be. The only gates
  it applies are `settled` and the `mkdir` claim.
- **The postponed file is removed *before* the prove starts, so a marker postponed again during this
  pass stays postponed.** The second time it is not competing with anything and taking long is all it
  is doing. The list is captured once, so nothing re-postponed is revisited by this pass.
- **The first attempt is put aside, not written over:** an existing `m/<id>` is moved to
  `dead/<id>.before-postponing` (falling back to `rm -rf`). `Prove` appends, so without this the second
  attempt lands on top of the first in one `trace.jsonl` and the record reads as a single prove that
  changed its mind — two reproducers, two verdicts, no line between them. What comes back is a fresh
  attempt and the record says so.
- **What comes back is a fresh attempt, not a continuation.** A prove is a JVM mid-conversation with a
  model; nothing persists that. What postponing saves is the *slot*.
- **The claim is cleared and re-taken.** `rm -f postponed/$id`, then `rm -rf claims/$id`, then the
  `mkdir` claim. `Supervisor.postpone` already deletes the claim when it sets a marker aside, so this
  is for anything left over; by this point the pool is idle and there is nothing to race.
- **The id is turned back into a marker by `sed 's/_[0-9]*_[A-Z].*//'`** — which strips `_<line>_<CHECKER>…`
  and leaves the file's base name — then `grep -F … "$2" | head -1`. So the recovered marker is **the
  first line of the queue containing that base name as a substring**, which is the right line only
  when the file contributes one marker to the queue and no other path contains its name. A marker
  whose base name matches no queue line is skipped (`[ -z "$marker" ] && continue`).

  **This is lossy, and the shipped queue already contains a case where it recovers the wrong
  marker.** `Ping.java` appears twice — line 32 `FB.PATH_TRAVERSAL_IN` and line 34
  `FB.DM_DEFAULT_ENCODING` — so postponing the second one and running this pass recovers the first:

  ```
  id   Ping.java_34_FB.DM_DEFAULT_ENCODING
  sed  Ping.java
  grep …/xxe/Ping.java|32|FB.PATH_TRAVERSAL_IN        <-- the other marker
  ```

  `Prove` is then run on that key into the lane named for the postponed id, and `release` asks
  `settled` about that key too. A rebuilder must not "clean this up" into something that looks
  equivalent without noticing it: **the id is not a lossless encoding of the marker**, and this is the
  only place in the pool that treats it as one. Everywhere else — `settled`, `release`, the sweep —
  carries the key and the id side by side precisely so it does not have to invert the slug.
- **There is no `resume` tool**, and the agents are told so in those words. A postponed marker comes
  back by itself when the queue is done; `Supervisor.restart` also lifts a postponement — it calls
  `Pace.resume`, which deletes `postponed/<id>` — because "prove it again from scratch" is what a
  postponed marker needs when somebody wants it now. A separate resume would be a second name for
  that with a promise it cannot keep, since there is nothing to resume.

---

## On disk

Everything under `$RESULTS` (`/results`) unless stated.

| path | written by | read by |
|---|---|---|
| `markers.txt` | `/settings` upload (`Subject.saveMarkers`) | the pool (`slice <markers>`), the dashboard's table |
| `markers-before-<epoch millis>.txt` | `Subject.saveMarkers`, on replacement | a person |
| `workers` | `/settings` (`Workers.save`), or by hand | `width()` each iteration, `Workers.of` |
| `claims/<id>/` | the pool's `mkdir` (atomic) | the pool's `mkdir`, the sweep, the watcher's QUIET flag, the dashboard's live panels, `Supervisor.restart/reprove/postpone` |
| `m/<id>/trace.jsonl` | `Prove` (append) | `Pace`, `Overwatch`, `Interpreter`, the dashboard |
| `m/<id>/settlements.jsonl` | `Prove` (append) | **`settled` / `settled_lane`**, everything that asks what happened |
| `m/<id>/trace.jsonl.live` | `JsonlTrace.streaming` (overwritten) | the dashboard's live panel |
| `m/<id>/slice.log` | the pool's subshell | a person |
| `m/<id>/summary.txt` | `Interpreter` | the dashboard |
| `dead/<id>.attempt-<n>` | the pool: `release` and the sweep | `Pace.tries` (**only** this suffix), `Pace.attempts`, `Pace.totalMinutes` |
| `dead/<id>.restart-<n>` | `Supervisor` | `Pace.attempts`, `Pace.totalMinutes` — **not** `Pace.tries` |
| `dead/<id>.before-postponing` | the postponed pass | `Pace.attempts`, `Pace.totalMinutes` — **not** `Pace.tries` |
| `postponed/<id>` | `Pace.postpone` (content: the reason + `\n`) | `--postponed`, `--list-postponed`, the postponed pass |
| `source.zip` | `/settings` upload | `checkout` — while present, nothing is cloned |
| `git-credentials` | `Subject.saveToken`, mode `rw-------` | `checkout`, as git's credential store |
| `spec/` | the entrypoint, on every start | the agents' file tools |
| `overwatch.log` | `serve` | a person |
| `trace.jsonl`, `settlements.jsonl` (top level) | single-marker `prove` mode | the `Dashboard` argument |
| `$CHECKOUTS/<repo name>` | `checkout` | the reference for `worktree add` |
| `$CHECKOUTS/tree-<id>` | the pool, per marker | the prove; `pgrep`/`pkill` identity |

---

## Failure directions, collected

Getting one of these backwards is silent.

| situation | what happens | why that way round |
|---|---|---|
| `m/*/settlements.jsonl` unreadable or absent | `settled` says **no** → the marker is proved | a duplicate prove costs time; a lost marker costs the marker |
| `dead/` exists but will not list | `Pace.tries` returns `MAX_VALUE` → no more goes | an unlistable directory must not license unlimited retries |
| `dead/` missing, or not a directory | `Pace.tries` returns 0 | a run that has archived nothing has retried nothing |
| `restarts.jsonl` will not read | `Supervisor.restarts` returns `LIMIT` → no more restarts | same direction; an unreadable log is not a licence |
| `workers` unreadable or junk | `4` (Java: `DEFAULT`; shell: `$asked`, else 4) | zero provers is a run that does nothing, stopped by a typo |
| `workers` says 90 | clamped to 16, twice | ninety JVMs against one GPU is not a thing to allow by typo |
| claim directory will not delete | reads as still claimed; the marker is not re-proved | better one marker skipped than two provers on one marker |
| claim exists, JVM not started yet | the sweep may release it → proved twice | a marker proved twice, never a marker lost |
| worktree has no build file (main loop) | logged as `WORKTREE FAILED … no build file`, `Prove` not run | names the cause instead of reporting it as a marker that could not be built |
| worktree has no build file (postponed pass) | `Prove` runs anyway and fails inside | the guard is not repeated there; a rebuilder copying the loop should not assume it is |
| `worktree add` fails | falls back to `cp -a "$reference/." "$tree/"` | a copy still proves; nothing silently runs in the reference |
| `Prove` exits non-zero | `|| true`; the subshell still removes the tree and releases | a crashed prove must not strand its claim or its worktree |
| a prove throws | `Settlement` state `infra` → **not** a disposition → back in the queue | `infra` is a prove that did not finish, not an answer about the marker |
