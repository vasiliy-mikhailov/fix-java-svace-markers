# 03. The build is the arbiter

Everything else in a prove is somebody's opinion. A **marker** — one line of `repo|file|line|checker`
from a static analyser — gets past this program only by having a test that genuinely failed before a
patch and genuinely passed after it. Prose does not substitute, a plan does not substitute, and an
agent that says the defect is real does not substitute.

Vocabulary used throughout this chapter:

- **prove** — one process, one marker, from the brief to the settled disposition (`Prove`).
- **phase** — which of the two decisive builds is running. `red` is the build before any patch;
  `green` is the build after one. The word is a plain string, carried into every summary. A third
  value, `check`, belongs to the producers' own tool and decides nothing.
- **infra** — a build that produced no test result at all. Not a failing test; not anything.
- **runner** — the object that turns "run this test class" into a build and a `Result`.
- **disposition** — the state a marker settles as (`reproduced`, `verified/pr-ready`, `unprovable`, …).

---

## Three outcomes, not two

**A build reports one of three things, and a boolean cannot hold them.**

```java
/**
 * @param infra  the build produced no test result at all — a compile error, a missing dependency,
 *               the wrong JDK, a timeout. NEVER evidence, in either phase.
 * @param passed meaningful only when infra is false
 */
record Result(boolean infra, boolean passed, String summary) {}

Result run(String phase, String test);
```

**`passed` may only be read when `infra` is false.** Nothing downstream may branch on `passed`
without first asking `infra`.

**Why the third outcome exists.** In the RED phase a failing test *is* the goal. Anything that
decides by exit code alone therefore reads a compile error as a successful reproduction: `javac`
failing and a JUnit assertion failing both exit non-zero, and the phase wants the second one. Collapse
them and a marker nobody reproduced is recorded as reproduced. That is the single distinction the
whole disposition rests on, and it is decided by one line of output-sniffing per build tool.

**A blank test class name is infra, never an empty pass.**

```
red: no test class was named, so nothing ran
```

**The failure direction is infra.** Every way a runner can be unsure — no output, an exception
starting the process, a timeout, a report directory it cannot read — resolves to `infra(true)`.
An infra result costs the marker a chance to be proved; a wrongly-confident `passed` puts a claim
in the record that nobody made.

---

## Nobody chooses to run the build

**No judge holds the runner, both producers do, and only the builds `Prove` runs decide anything.**
`Prove` runs the deciding builds itself, at exactly two points in the order — RED in `reproduce`,
GREEN in `patchUntilItBuilds`, each with its own re-ask loop around it — and hands the result on as
text. A tool is something a model chooses to call, and whether RED runs before the patch is not a
choice.

The judges are excluded because **a certification that can run the build can manufacture the evidence
it certifies.** The producers are included for the opposite reason: a reproduce-doer that can run what it
wrote finds its own compile error in seconds instead of spending a round trip through the chain to be
told, and it still cannot make its own test pass, because it holds no `edit_file` and so cannot
change the subject.

**Only builds run by `Prove.built` count.** `run_test` calls the runner directly: its result is not
written to the trace as a `built` event, is not added to the run's build ledger (`builds`), and can
never set the red/green flags. (It is not invisible — like every tool it is wrapped so the trace
records a `tool` event with its arguments and result in full. What it is not is a *fact* in the sense
`built` is.)

`run_test` is offered to the reproduce-doer (alongside `list_dir`, `read_file`, `write_file`) and to the
fix-doer (alongside `list_dir`, `read_file`, `edit_file`); `grep` and `glob` go to every agent in the
program, judges included, so those two are in both sets as well — a model asking for a tool that does
not exist does not degrade, it throws and the prove ends, and two markers were lost that way. It runs
under phase `check`, and it translates the runner's answer before the model sees it:

```java
return (r.infra() ? "DID NOT RUN" : r.passed()
        ? "PASSED — WHICH IS A FAILURE HERE." + Prove.GREEN_RED
        : "FAILED") + "\n" + r.summary();
```

The word `PASSED` means its opposite in the RED phase and it used to reach the agent bare. A
reproduce-doer reading `PASSED` after running the test it just wrote reads success; what happened is that
its test is green on the defect. **Told at the moment it happens, the agent can still fix it — a
round trip later only the verdict agent hears, and the verdict agent cannot rewrite a test.**

**Every process that builds `Agents` without a checkout passes a runner that refuses.** `Overwatch`
has no checkout, and its agents' file tools are read-only over the results directory. It is
constructed with a runner that always returns infra:

```java
Runner nothingToBuild = (phase, test) -> new Runner.Result(true, false,
        "the supervisor does not build; it reads what the provers built");
```

A supervisor that can run tests can manufacture the evidence it supervises. `Chat` passes the same
lambda with the same sentence; `Dashboard`, which constructs every agent once purely to collect its
built-in prompt, passes `"the dashboard does not build"`. `Agents` takes a `Runner` and there is no
way to give it none, so the refusal has to be spelled out; it takes the shape every other refusal
here takes, `infra(true)` with a sentence saying why.

---

## Choosing the build tool

**Which build tool is an implementation detail and the call sites do not know it.** They ask the
runner to run a test; `Runner.of` decides from the checkout:

```java
static Runner of(Path checkout) {
    if (Files.exists(checkout.resolve("pom.xml")))            return new Maven(checkout);
    if (Files.exists(checkout.resolve("build.gradle"))
            || Files.exists(checkout.resolve("build.gradle.kts"))) return new Gradle(checkout);
    throw new IllegalStateException(
            "no pom.xml and no build.gradle in " + checkout + " — nothing can run the test");
}
```

Order matters: `pom.xml` wins over a Gradle script in the same directory.

**A tree with no build file is refused by name, loudly, rather than guessed at.** A worktree that
failed to materialise has no build file, and defaulting to Maven turns every marker in the
repository into "the build produced no test result" — written off with a message that blames the
project. The test that pins this behaviour records forty perfectly good markers lost that way. The
exception carries the checkout path and the words `nothing can run the test`.

`Runner.of` is called first thing in `Prove.main`, inside the try that catches `RuntimeException`, so
the throw lands as a `failed` trace row and a settlement whose state is `infra`. `infra` is **not**
one of the seven dispositions the pool counts as settled, so the marker goes back in the queue for
another attempt rather than being retired — up to the pool's limit of three attempts, after which it
is left for a person.

The slice loop makes a *similar* check before it starts a prove at all — but not the same one, and
the difference is a trap. It tests only `pom.xml` and `build.gradle`; it does not know about
`build.gradle.kts`.

```sh
if [ ! -f "$tree/pom.xml" ] && [ ! -f "$tree/build.gradle" ]; then
    echo "WORKTREE FAILED for $marker — no build file in $tree" >> "$out/slice.log"
else
    java -cp "$CP" tech.mikhailov.fsm.agent.Prove "$tree" "$marker" "$out" \
        >> "$out/slice.log" 2>&1 || true
fi
```

A broken worktree is therefore named in `slice.log` instead of being reported as an unbuildable
marker, which is what the gate is for. **But the gate is strictly narrower than `Runner.of`, so a
Kotlin-DSL Gradle project — `build.gradle.kts` and no `pom.xml` — is written off here as a failed
worktree and never reaches the runner that would have built it.** `Runner.of`'s third acceptance is
unreachable through the pool's main loop; it is reached from `entrypoint.sh prove`, which runs
`Prove` directly with no gate, and from the post-queue pass over postponed markers, which makes no
build-file check at all. The same two-name test decides in `checkout()` whether an uploaded zip needs
unwrapping, with the same blind spot.

The two implementations differ in more than their command line: **they report "no test executed" in
completely different words, and that difference is what an implementation is for.**

---

## Maven

```java
Shell.runWith(checkout, Subject.javaHome(RESULTS), "mvn", "-B", "test",
        "-Dtest=" + test, "-Dsurefire.failIfNoSpecifiedTests=false");
```

- `-B` for batch output.
- **Not `-q`.** Quiet suppresses the compiler error, leaving a build that failed and a summary that
  says only that lombok called a deprecated method — the one piece of feedback in this program
  guaranteed to be correct, thrown away.
- `-Dsurefire.failIfNoSpecifiedTests=false` so a name that matches nothing is a build that ran no
  test rather than a build that errored, which keeps the "did a test execute" question answerable in
  one place.

**The infra test is Surefire's own line, not the exit code.**

```java
if (out.timedOut())                        { /* infra: did not finish in time */ }
if (!out.text().contains("Tests run:"))    { /* infra: no test executed */ }
return new Result(false, out.exit() == 0, …);
```

Surefire prints `Tests run:` exactly when a test executed. Without that line the build stopped
earlier, whatever it exited with. With it, the exit code is meaningful: `0` is a pass, non-zero is a
failing test.

The timeout is asked about **first**, so a build that was killed at the bound says it ran out of time
rather than that no test executed. Both are infra; they are different facts and the record keeps
them apart.

---

## Gradle

```java
String wrapper = Files.exists(checkout.resolve("gradlew")) ? "./gradlew" : "gradle";
Shell.runWith(checkout, Subject.javaHome(RESULTS), wrapper, "test", "--tests", test, "--console=plain");
```

The project's own wrapper is preferred; the image's `gradle` is the fallback.

**Gradle has no `Tests run:` line, so execution is read from the test report.** `build/test-results/test/*.xml`
exists only once the test task has actually run:

```java
Path results = checkout.resolve("build/test-results/test");
long before = stamp(results);     // newest lastModified in the directory, or 0
… run the build …
if (stamp(results) <= before) { /* infra: no test executed */ }
```

`stamp` returns `0L` on any exception — a missing directory, an unreadable one. **A report that
cannot be read is a test that did not run**, which is the safe direction: it costs a marker rather
than inventing a result. The comparison is `<=`, so a stale report from an earlier build in the same
worktree cannot be mistaken for this build's. `timedOut` is checked before the stamp, exactly as in
Maven.

Reading the exit code instead would call a compile error a failing test, which in the RED phase would
be recorded as a successful reproduction.

---

## The summary is a record, not a description

Both implementations produce the same five shapes. **The first line of every summary is already a
verdict**, so the ledger downstream is a list of facts rather than a summary of them:

```
<phase>: no test class was named, so nothing ran
<phase>: the build did not finish in time\n<tail of output>
<phase>: no test executed\n<tail of output>
<phase>: PASSED\n<tail of output>
<phase>: FAILED\n<tail of output>
```

`<phase>` is whatever string the caller passed — `red`, `green`, or `check` from `run_test`. It is
carried so that a reader of a settlement can tell which build they are looking at.

`Prove` keeps the first line of each of its own builds, in order, and hands the list to the agents
that argue about a marker nothing demonstrated:

> WHAT THIS RUN OBSERVED, in order: red: FAILED; green: PASSED. A build that never ran is not
> evidence, and a red build that PASSED observed the code behaving correctly on the inputs that test
> used — and nothing more than that.

When no build ran at all the same method says so explicitly, and names the honest state:

```
WHAT THIS RUN OBSERVED: NOTHING EXECUTED FOR THIS MARKER. No test was written, so no build ran.
`false-positive` is a claim about how this code BEHAVES and nothing here watched it behave;
`by-design` is a claim about what somebody INTENDED and needs an artefact older than this run.
Absent either, the honest state is `unprovable`.
```

**An agent told nothing about what executed reaches for whichever state is cheapest to argue, and in
a repository framed as deliberately vulnerable the cheapest is always `by-design`.**

Every build is also written to the trace, as the one event there that is a fact:

```json
{"at":"1754899200000","marker":"…","kind":"built","phase":"red","infra":"false","passed":"false","summary":"red: FAILED\n…"}
```

Every value is a string, including `at` (epoch milliseconds), `infra` and `passed`. `at`, `marker`
and `kind` lead every row in the trace whatever its kind.

Which disposition each build outcome settles as is chapter 02's subject; this chapter is only about
how the runner decides which of the three outcomes it produced.

---

## How RED and GREEN are run, and by whom

**The order belongs to `Prove` and nothing can rewrite it.** Investigation belongs to the agents;
sequence does not.

Every loop below is bounded by one shared constant — `private static final int REASK = 1`, "one
re-ask per producer, quoting whoever objected. Two loops, one budget, stated once." Every "once" in
this section is that number.

1. **Nothing is built until a file exists.** The reproduce-doer is asked for a test; if the trace has not
   seen a test file written, and the reply did not decline — `declined` is a case-insensitive
   substring test for `no test`, the token named verbatim in the reproduce-doer's own prompt — it is
   asked once more, plainly, with the alternative named:

   ```
   You wrote no test file. Either use write_file to create one, or answer with exactly `no test`
   and a one-line reason. An empty answer is not a decision.
   ```

   If there is still no file, **no build runs at all**: the marker goes straight to the verdict agent
   with `WHAT THIS RUN OBSERVED: NOTHING EXECUTED FOR THIS MARKER`, and the reproduce-doer's reply, or
   `(nothing at all)`, quoted under it. The build used to run first, so a reproduce-doer that wrote
   nothing cost two Maven invocations before anyone looked — **78 of 153 builds in a 67-marker run
   executed no test at all.**
2. **RED runs before the critic.** A test that does not compile cannot be over-mocked in any
   interesting way, and a test that does not go red proves nothing whatever its mocks look like.
   Grading it first spends a model call on something no build has agreed exists.
3. **The compiler is a critic too, and a free one.** When RED is infra, the build summary goes back
   to the reproduce-doer verbatim with `Fix exactly that, write the file again, and end with the test
   class name.` **Only infra is re-asked** — a test that ran and FAILED is the goal here, not a
   fault.
4. **A green RED is re-asked, once, of the reproduce-doer.** See below.
5. **GREEN runs after the fix-doer**, over the same test class, through the same infra loop: a patch
   that does not compile is not a rejected patch, it is an unfinished one, and the fix-doer is handed
   the compiler's words with `Fix exactly that. Do not change the test.`
6. **Any rewrite is re-built.** A rewritten test nobody re-builds is how a green proof gets recorded
   for a test that stopped reproducing.

**Which test class is built comes from what the reproduce-doer WROTE, not from what it said.** Its prose
names the harness it borrowed as readily as the test it wrote, so a class name scraped from the reply
picks whichever came last; the file it wrote is not ambiguous.

The trace watches the `write_file` tool call and remembers the last path that satisfies **both**
conditions:

```
path contains "src/test/" or "src/it/" or "src/integrationTest/"
path ends with "Test.java"
```

The class handed to the runner is that filename minus `.java`, and it is remembered for the whole
prove. **All three test roots count, not just `src/test`** — a project puts integration tests under
`src/it`, and a marker raised in one of those is answered by a test written beside it, which an
`src/test`-only rule rejected: the runner was told no test had been named and reported infra for a
file that was sitting on disk.

The reply is only a fallback, via `([A-Z][A-Za-z0-9_]*Test)\b` (first match), and a wrong guess there
produces "no test executed" rather than a guess at a verdict. A test file named anything but
`…Test.java` is therefore invisible to this program however good it is.

### What the flags mean

```java
if (!r.infra()) {
    if (phase.equals("red")) redOk  = !r.passed();
    else                     greenOk =  r.passed();
}
```

- **RED counts when the test FAILED. GREEN counts when it passed. An infra build changes neither
  flag.**
- The flags are assigned, not accumulated: the last non-infra build of each phase is the one that
  stands.
- The `else` branch is unguarded: **anything that is not the string `red` sets `greenOk`.** Only
  `red` and `green` ever reach `built`, and `check` must never be routed through it.
- They travel into the settlement as `red` and `green`, next to the state, because **a disposition
  implies them and an implication is not a record**: `reproduced` and `verified` both mean red
  failed, and only one of them means green passed.

### The green RED

**A test that passes before the fix has documented the defect, not observed it.** This fact is
certain rather than heuristic: the reproduce-doer holds no `edit_file` and no fix-doer has run, so the tree
a first RED ran against IS the revision the marker was raised against.

**Across one run, 16 of the 33 markers that reached a build had their first RED pass, and 13 of them
settled on it** — six `by-design`, seven `false-positive`, every one argued from a build that showed
nothing. The chain routed the fact to the verdict agent, which cannot rewrite a test, and never told
the reproduce-doer, which can. One of them had already worked it out and shipped anyway: *"this test
won't actually fail on most platforms because the default charset is typically UTF-8"*, followed by
*"But actually, let me just submit the test."*

So a passing RED is handed straight back to the reproduce-doer, once, with `Prove.GREEN_RED`: it names
the fact and why the fact is certain, names `assertThrows` as the exact shape that causes it, asks
for an assertion on what the method should RETURN, permits a JVM to be forked with `ProcessBuilder`
when the defect only shows under a setting this build does not use, and names the exit — `no test`
plus one line of why. It contains no congratulation; a re-ask that opens by agreeing gets the same
test back.

If the second attempt is still infra or still passes — or if the reproduce-doer declines it, or writes no
file, in which case the first passing build stands — the marker goes to the verdict agent as:

```
NO TEST COULD BE MADE TO FAIL ON THIS CODE. The reproduce-doer was asked twice; the last build was:
<the summary>
```

Note the asymmetry with the RED-infra loop above it. A RED that never built settles `unprovable`
without the verdict agent being asked at all, quoting the compiler; a RED that built and passed is
*argued*, because something did execute and what it observed has to be accounted for.

### The arbiter's blind spot

A build cannot tell a defect from a broken environment. `src/it` is bound to failsafe and excluded
from the Surefire run this pipeline uses, so those classes never execute here; and they need a
WebGoat on `localhost:8080`, so when one IS run its failure is a connection error rather than a
defect — **which is also how markers in that tree have been collecting a free RED and settling
`reproduced` on nothing.** This is not fixed in the runner. It is stated in the brief, before the
reproduce-doer starts, for any marker whose file begins `src/it/` or `src/test/`, together with the
instruction that `no test` is the expected answer there. Fifty-six of eighty-six runaway generations
were the reproduce-doer on exactly this: half an hour of reasoning towards a test that cannot exist.

---

## JDK selection, and what it is for

**This is not about compiling.** javac 25 targets 8 through 25 — `--release 8` produces class file
major version 52. What a subject written before 2018 needs is a JVM to RUN its tests on, because
**Surefire forks a JVM from `JAVA_HOME`** and finds 25 there whatever the bytecode says: strong
encapsulation, removed APIs, and bytecode libraries that cannot read class file 69.

Every one of those arrives as *the build produced no test result*, which this program is right to
refuse as evidence and which costs the marker regardless.

```java
/** THE JDKS IN THE IMAGE, newest first. */
static final List<String> JDKS = List.of("25", "21", "17", "11", "8");

static String jdk(Path results);       // contents of <results>/jdk, or "25"
static String javaHome(Path results);  // "" for 25, else "/opt/java/" + chosen
static void  saveJdk(Path results, String chosen) throws IOException;
```

- The choice is one file, `<results>/jdk`, holding the version and a newline. It is stripped before
  it is matched against `JDKS`.
- **A value not in `JDKS` is refused on write and ignored on read.** `saveJdk("14")` writes nothing;
  a file containing `eight` reads back as `25`. Pointing `JAVA_HOME` at a directory that does not
  exist makes every build fail in a way that reads like the project rather than like the setting.
- **25 means blank, not a path.** The base image's JDK sits where the base image put it; leaving
  `JAVA_HOME` alone is how a build gets it, and hard-coding somebody else's layout is how it stops
  working on the next base image.
- The other four are copied into the image from the official Temurin images as `/opt/java/8`,
  `/opt/java/11`, `/opt/java/17`, `/opt/java/21`. About a gigabyte, which is the price of being able
  to prove a marker in a project written before 2018.

**The setting is run-wide and is read per build.** `Maven` and `Gradle` each hold

```java
private static final Path RESULTS = Path.of(System.getenv().getOrDefault("RESULTS", "/results"));
```

— **the run's results root, not the per-marker lane directory `Prove` was given.** The pool passes
`$RESULTS/m/<id>` to `Prove` as its results argument, so a runner that resolved the JDK against
*that* would look for the setting in a directory nothing ever writes it to, and every build would
silently run on 25.

The environment variable is read once at class load; `Subject.javaHome(RESULTS)` is called inside
`run`, so the *file* is re-read on every build and a change takes effect on the next one without
restarting anything. The dashboard exposes it as a select over `Subject.JDKS` and answers
`builds will run on Java <n> from the next marker.`, or `!<n> is not one of the JDKs in this image.`
when `saveJdk` refused it.

### Both `JAVA_HOME` and the PATH

```java
builder.environment().put("JAVA_HOME", javaHome);
builder.environment().merge("PATH", javaHome + "/bin", (was, bin) -> bin + ":" + was);
```

**Set both or neither.** `JAVA_HOME` alone leaves `java` on the PATH resolving to the image's own, so
a build compiles under one JDK and tests under another — which fails in a way that reads like the
project rather than like the setting. The chosen JDK's `bin` goes *first* on the PATH.

**Blank must not blank it.** A blank `javaHome` leaves the environment exactly as it was; it must not
set `JAVA_HOME=""`.

---

## Timeouts

**One bound, on the build, in `Shell`:**

```java
/** Long enough for a cold dependency cache on a first clone. */
private static final long TIMEOUT_MINUTES = 30;
```

It is not configurable. Unlike the model bounds below it is a constant, not a `Tuning` setting, so
changing it needs a build.

The process is started with `redirectErrorStream(true)` — stderr merged into stdout, because the
compiler's words arrive on stderr and they are the feedback the loops depend on — its output is read
to EOF, and then `waitFor(30, MINUTES)`. On expiry the child is `destroyForcibly()`d and the output
is:

```java
new Output(-1, tail(text), true)   // timedOut = true
```

which both runners turn into infra: `"<phase>: the build did not finish in time\n" + out.text()`.

Note the order: the bound is applied to the wait that follows the read. A child that never closes
its output stream is not bounded here (verified from the code, not from an incident).

**Anything thrown while starting or running the process is infra too**, by a different route:

```java
catch (Exception e) { return new Output(-1, e.getClass().getSimpleName() + ": " + e.getMessage(), false); }
```

`timedOut` is false, but the text contains no `Tests run:` and no test report was written, so Maven
and Gradle both report "no test executed". **Every path out of `Shell` that is not a completed build
lands on infra.**

**Only the tail is kept.**

```java
/** Enough tail to see the failure; the head is the tool announcing itself. */
private static final int TAIL = 4_000;
static String tail(String output) {
    return output.length() <= TAIL ? output : "…" + output.substring(output.length() - TAIL);
}
```

The head is the tool announcing itself; the failure is at the end. A truncated summary is prefixed
with `…` so a reader can tell it was cut.

### Other clocks, which are not this one

Do not conflate these with the build timeout; each measures a different failure and the record must
not report them as the same one.

| Bound | Value | What it measures |
|---|---|---|
| `Shell.TIMEOUT_MINUTES` | 30 minutes | a build that has not finished |
| `Prove.PATIENCE` / `Tuning.patience()` | 4 minutes (1–120) | silence: a model call with no token |
| `Prove.CEILING` / `Tuning.ceiling()` | 4 hours (1–1440) | a generation that streams steadily and never stops |
| `Pace.MUCH_LONGER` × median, `Pace.NEVER_BEFORE`, `Pace.ENOUGH` | 4× the run's median, never before 20 minutes, and nothing is an outlier until 8 markers have settled | a marker holding a slot while the queue waits — postponed, not killed |
| `Overwatch.QUIET` | 20 minutes | a claimed prove that has logged no event |

---

## What has to be true around the build for it to be an arbiter at all

The runner only decides anything if the tree it runs in is the subject's. That is the entrypoint's
job, not an agent's, and two rules of it belong here because a build that violates them produces a
confident wrong answer:

- **A worktree per marker**, added from a reference clone that is prepared once and thereafter read
  only, and removed afterwards. Four provers resetting and cleaning one tree delete each other's
  test between the write and the build; a worktree taken mid-clean is a directory with no `pom.xml`
  in it, which this program then reports as "nothing can run the test" for a marker that was fine.
- **`git clean -xfd`, not `-fd`, when a tree is reused.** `clean` skips ignored files by default and
  `target/` is ignored — so a class compiled from the previous marker's *patch* survives a reset that
  restored its source, Maven decides by timestamp whether to recompile, and the next marker's RED
  runs against the last marker's fix. That is a green that belongs to somebody else. The dependency
  cache lives in `~/.m2`, outside the checkout, so this costs a recompile and not a re-download.

And one rule about what a build proves: **what this run made is not evidence about the project.** By
the time the verdict agent reads the tree it contains the test this run wrote and the patch this run
applied. Thirteen settlements rested on that — `by-design` because "a test depends on this
behaviour", where the test was the one written eleven minutes earlier by the reproduce-doer, in this
prove, about this marker.
