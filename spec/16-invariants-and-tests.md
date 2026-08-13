# 16. What the tests hold

**Every test class in this repository is named after a failure that actually happened, and its class
javadoc is the incident report.** Not "DashboardTest". `ABlankLineIsALineTest`,
`AClaimOutlivedItsProveTest`, `SilenceIsNotADecisionTest`. The name is the invariant; the javadoc is
the reason anybody would think to write it down; the `@DisplayName` on each method is the rule in one
sentence; the assertion message is what goes wrong when the rule is broken.

A rebuilder who reproduces the code and drops the javadocs has thrown away the more valuable half.
Every one of these bugs was found by deploying to a server and reading a trace back afterwards. The
tests are cheap — each is a pure function answering in a millisecond — and that cheapness is the whole
argument for pinning them: the discovery cost hours of GPU time and a run of markers.

Vocabulary used below. A **marker** is one line `repo|file|line|checker`. A **prove** is one JVM
proving one marker. A **claim** is `results/claims/<id>`, by which the pool marks a marker as taken.
A **disposition** is a terminal state (`false-positive`, `by-design`, `unprovable`, `reproduced`,
`needs-review`, `verified/pr-ready`, `verified/pr-rejected`); `infra` and `proving` are not
dispositions. A **lane** is one marker's whole journey. A **digest** is a record assembled by
counting and quoting rather than by asking a model. **RED** is the build phase where the test is
expected to fail; **GREEN** is the phase after a patch, where it is expected to pass.

---

## Where they live and how they run

```
agent/src/test/java/tech/mikhailov/fsm/agent/*.java   34 JUnit 5 classes
agent/src/test/settled_test.sh                        the pool's skip rule, in shell
agent/src/test/width_test.sh                          the pool's width rule, in shell
```

JUnit runs in the ordinary build (`junit-jupiter` 5.11.4, surefire 3.5.6). The two shell scripts are
standalone: nothing in `pom.xml`, `Dockerfile` or `entrypoint.sh` invokes them, and they are run by
hand. They exist because the rules they hold are implemented **in shell**, in `entrypoint.sh`, and a
Java test of the Java copy would hold the wrong artefact.

**Surefire must export `PROMPTS` and `TUNING` into the test JVM**, or three tests pass by not
running:

```xml
<environmentVariables>
  <PROMPTS>${project.build.directory}/test-prompts</PROMPTS>
  <TUNING>${project.build.directory}/test-model</TUNING>
</environmentVariables>
```

`Prompts.WHERE` and `Tuning.WHERE` are `static final Path` read from those variables at class load,
defaulting to `/results/prompts` and `/results/model`. The three tests in question are
`APromptIsDataTest.replaces`, `.blankIsNotAnInstruction` and `.noEscape`: without the override they
guard themselves with `Assumptions.assumeTrue(writable())` and skip on every machine except the
container. `TUNING` is not a skip guard but a redirect — `WhatTheModelIsAskedForTest` writes and
reverts real settings files with no assumption around it, so without the override it writes to
`/results/model`.

Two mechanical patterns recur and a rebuilder should keep both:

- **Reflection reaches package-private statics and privates** (`Prove.verdict`, `Prove.rejects`,
  `Prove.declined`, `Prove.minutes`, `Prove.source`, `Prove.fileOf`, `Prove.aTestThisBuildCannotRun`,
  `Prove.whatThisRunMade`, `Prove.whatExecutionProduced`, `Prove.reachesTheFlaggedLine`,
  `Prove.reviewed`, `Dashboard.code`, `Dashboard.diff`, `Dashboard.flagged`, `Dashboard.slug`,
  `Overwatch.split`, `Interpreter.write`). Several classes build a `Prove` through its private
  constructor `(Path, String, Agents, Runner, Trace)` with `null` agents, runner and trace, precisely
  so a method that touches none of them can be called without a model endpoint.
- **A `quiet()` `Trace`** — an anonymous implementation of all eight callbacks (`asked`, `thought`,
  `tool`, `built`, `settled`, `failed`, `progress`, `priced`) that keeps nothing — is redeclared in
  each class that needs one. `TheReasoningIsNotThrownAwayTest` instead uses a `Kept` trace that
  records `thought(agent, text)` so the test can ask what was written down.

---

## The meta-rule: a check that passes on both the right and the wrong answer is not a check

This is stated in the codebase itself, in `ACredentialIsNotPartOfTheRecordTest`:

> AND THE SEARCH REALLY REACHED IT. Without this the test passes just as happily when grep matched
> nothing at all, which would make it a check that holds whether or not the guard exists — the exact
> shape of check that let two earlier bugs through.

The cases where a check here was itself wrong, or was nearly written wrong, and the correction:

| Check | How it was hollow | The fix |
|---|---|---|
| `grepRedacted` | asserted only that the key was **absent** from the grep result — true when grep found nothing | also assert `said.contains("(hidden)")`: the line was found *and* redacted |
| `slowButAlive` | the fake model spoke in breaths shorter than one poll, so the future completed before the check ever ran — "which is how the first version of this test passed against the bug" | 160 breaths of 20 ms against a 200 ms silence bound, i.e. three seconds of steady speech |
| the prompt tests | `assumeTrue(writable())` skipped them everywhere but the container: "three tests that pass by not running" | the surefire `PROMPTS`/`TUNING` environment variables above |
| `theNameItArrivesUnder` | reproducing the SSE field-reading inside the test would "give a test that passes with `onEvent` deleted" | events are fed through the **real** `Overheard` decorator by standing in for the `HttpClient` underneath it |
| `notUniversal` | a bare `return;` was listed as a contentless line — "listing it here failed a note for being right", because for `UNREACHABLE_CODE` and the null-return families a control-flow statement *is* the construct | `return;` deliberately excluded from the list, with the reason written above it |
| `wiredToTheReadingSet` | reads `Agents.java` as text and slices at `"Agent chat("`; a rename would silently match nothing | `assertTrue(at > 0, "the chat agent has been renamed; this guard now checks nothing")`. It then allows `Tools.asking(` **or** `Tools.reading(` by name and forbids `Tools.supervising(` — naming the permitted sets explicitly is what made the guard fire when the wiring moved from one read-only set to the other |
| `OneOrderNotThreeTest.pairs` | deriving each critic's name from its producer's would test a convention this code does not have (`fixer`/`fix-critic`, `reproducer`/`proof-critic`) | the five pairs are written out literally |
| `ABlankLineIsALineTest` | its assertions were written against the escaped form of `flagged()` — "the reason that move was noticed rather than shipped" when escaping moved into `code()` | assertions kept against raw source, with the note explaining why they read that way |
| `quietStillFlagged` | the fix for phantom QUIET rows could have been bought by never reporting QUIET | a second test asserts a genuinely stalled prove is still flagged: "the fix must not buy its quiet by blinding the watcher to the one case it is for" |

Three accepted versions of the hazard survive, and a rebuilder should know they are not oversights
to be "fixed" into something stronger without reading why:

- `TheQueueIsInOneOrderTest` — all four of its tests open with `assumeTrue(Files.exists(QUEUE))`
  against `../examples/webgoat/markers.txt`, so they are silently skipped unless the suite runs from
  the `agent/` module.
- `TellingAFactFromAnOpinionTest.mavenCallsACompileErrorInfraRatherThanAFailure` — asserts a
  `noTestsIn` helper **defined in the test file itself**, not `Maven`. It documents the rule
  (`Tests run:` is present exactly when a test executed) rather than holding the implementation;
  the other four methods in that class do call production code.
- `AskingTheWatcherSomethingTest.oneAtATime` — its `@DisplayName` says "a second question while one
  is in flight is refused, not queued", and the body waits for the in-flight flag to clear and then
  asserts only that `Chat.ask` returns `""` for an idle chat. The refusal path is not asserted.

---

## The inventory

### Reading what an agent actually said

| Class | What must be true | The failure behind it |
|---|---|---|
| `ADeclarationNotAMentionTest` | `Prove.verdict(reply, allowed…)` returns the **last declared** verdict; a heading, colon, backticks and `**` are decoration; with nothing declared it falls back to the earliest mention | the pr-maker on `PasswordResetLink:21` declared `**reject**` on its last line; reading the earliest mention took "make" out of *"makes admin reset links predictable"* and settled the marker pr-ready — a patch that breaks a graded lesson, against the explicit judgement of both agents that exist to stop that |
| `RoutingAWordTest` | a judge's own explanation must not overrule its verdict; `rejects("")` and `rejects(unreadable)` are **true**; a word nobody allowed yields `""` | the skeptic led with `sound` and later wrote "over-fit" while explaining what the patch was *not*; a substring search read a careful acquittal as a conviction and the chain retried a fix nobody had faulted |
| `SilenceIsNotADecisionTest` | `Prove.declined(reply)` is false for `null`, `""` and whitespace; true for the token `no test`, plain or decorated; false for prose that merely reasons towards no defect | in a 67-marker run the reproducer returned nothing 53 times out of 133, and every one was passed to the verdict agent as *"the reproducer declined to write a test, saying:"* followed by nothing. Four markers settled on that |
| `TellingAFactFromAnOpinionTest` | a Maven build with no `Tests run:` in its output is **infra**, not a failure (Gradle prints no such line and reads `build/test-results/test/*.xml` timestamps instead); a blank or null test name is infra and never a pass; `Runner.of` returns `Maven` for a tree with `pom.xml`, `Gradle` for one with `build.gradle`, and otherwise throws `IllegalStateException` whose message contains `nothing can run the test`; `Prove.minutes` reads `minutes: 25` as `"25"` and returns blank for prose with no figure | in RED a failing test is the goal, so anything reading the exit code alone records a compile error as a successful reproduction. Guessing Maven for a worktree that failed to materialise turned forty good markers into "nothing can run the test" |

### The brief an agent is handed

| Class | What must be true | The failure behind it |
|---|---|---|
| `ALineNumberIsACheckableClaimTest` | `Prove.source` numbers every line, marks the flagged one `>> N  `, and states drift as a fact: `THE MARKER POINTS AT LINE 67 AND THIS FILE HAS 64`, plus an instruction to record which line was judged. In-range markers say nothing about drift. Unreadable → `(could not be read…` | the markers came off an older analyser revision. `EncDec:67` points six lines past the end of a 64-line file; `TokenTest:47` lands on a blank line. Given unnumbered source the reproducer decided for itself what the marker must have meant, with nothing in the record saying so |
| `AWholeFamilyWrittenOffTest` | `Checkers.note(checkout, marker, checker, file, line)` carries `A TEST MAY START A JVM`, `-Dfile.encoding`, the sentence saying the construct is **not** `Charset.defaultCharset()` (asserted as ``It is NOT `Charset.defaultCharset()` ``), and a per-line verdict — `Line 3 does contain it.` when the flagged line matches the construct regex, otherwise `LINE <n> DOES NOT CONTAIN THE CONSTRUCT THIS CHECKER REPORTS.` followed by `The nearest lines that do are <ns>` (matches within ±40 lines, at most 3 listed) or by *the marker may have drifted off this file entirely* when there are none. For `FB.COMMAND_INJECTION` it says `Runtime.exec(String)` `does NOT run a shell` / `splits the string` and can `chain NOTHING`. An unknown checker returns only `THIS PIPELINE HAS NO NOTE FOR <checker>. Say in your answer which construct you took that name to mean, so the next reader can tell a correct answer from a lucky one.` A missing file costs the line check, not the note | thirty-three markers never produced a build. Every agent meeting `FB.DM_DEFAULT_ENCODING` reasoned: the default charset is fixed at JVM start-up, therefore no test can vary it, therefore this cannot be demonstrated. The first clause is true. Run by hand, `EncDec` goes RED under `-Dfile.encoding=ISO-8859-1` with `expected: "café" but was: "caf©Ã"`. Separately, `DM_DEFAULT_ENCODING` was bound to `Charset.defaultCharset()` (how you *read* the setting, not how you depend on it) and `COMMAND_INJECTION` was tested with a semicolon payload, when `Runtime.exec(String)` splits on whitespace and chains **nothing** |
| `ANoteIsCheckedBeforeItIsTrustedTest` | a note is a first line and a body, which is the shape `Checkers.read` reads. For every `src/main/resources/checkers/*.txt`: the first line **compiles as a regex**; it is not blank; the file contains a newline at an index `> 0`; the first line matches **none** of the contentless lines `""`, `"    // just a comment"`, `"    }"`, `"import java.util.List;"`, `"package org.owasp.webgoat.container;"`, `"        }  // done"` — a bare `return;` is deliberately absent from that list; and everything after the first newline is ≥ 200 characters once stripped. `FB.DM_DEFAULT_ENCODING.txt` still contains `A TEST MAY START A JVM` | both failures are silent inside `Checkers.where`: an uncompilable first line is swallowed by `catch (PatternSyntaxException)` and returns `""`; a first line matching everything reports "Line 63 does contain it" for every line of every file. Either way the note still looks present and the one sentence the agent most needs is missing or a lie |
| `ATestThisBuildCannotRunTest` | `Prove.aTestThisBuildCannotRun(marker)`. For `src/it/…`: `INTEGRATION TEST TREE`, `localhost:8080`, `Do not write a test that calls into it.`, and `` `no test` `` named as `expected one here`. For `src/test/…`: `UNIT TEST TREE` and `` `no test` ``, **without** the localhost reasons — surefire really does run that tree, and claiming otherwise teaches the agent to disbelieve the rest. For `src/main/…`: the empty string | fifty-six of eighty-six runaway generations were the reproducer on a defect inside an integration test, reasoning for half an hour and arriving nowhere: *"Wait, but this is an integration test class (src/it/java), not a regular source class" … "The uploadTrickHtml method is private, so I can't directly test it" … "Let me think about this differently."* The exit existed already — in a prompt the model had long since left behind |
| `ARunMayNotCiteItselfTest` | `Prove.whatThisRunMade` runs `git status`; this run's new test and this run's patch are listed under `INADMISSIBLE` with the consequence spelled out (`you do not have an argument`); a clean tree and a non-repository both say **nothing** | thirteen settlements in a 67-marker run cited the pipeline's own work: `by-design` because "an existing test depends on this", where the test had been written eleven minutes earlier by the reproducer, in that prove, about that marker. A citation of our test reads exactly like a citation of theirs |
| `AGreenRedIsNotAReproductionTest` | `Prove.GREEN_RED` contains `PASSED against the code as it stands`, `you cannot edit source and no patch has been applied`, `assertThrows`, `Write it again so it FAILS`, `what the method should RETURN` and `` `no test` ``, and **no** word of praise (`well done`, `good`, `correct`, `success`, compared lower-case). The test asserts the composed string `"PASSED — WHICH IS A FAILURE HERE." + Prove.GREEN_RED` rather than calling the tool; `Tools.runTest` builds exactly that, its three heads being `DID NOT RUN` (infra), `PASSED — WHICH IS A FAILURE HERE.` + `GREEN_RED` (passed) and `FAILED`, each followed by `"\n" + summary` | across one run, 16 of the 33 markers that reached a build had their first RED pass and 13 settled on it — six `by-design`, seven `false-positive`, every one argued from a build that showed nothing. One reproducer had worked it out and shipped anyway: *"this test won't actually fail on most platforms because the default charset is typically UTF-8"* … *"But actually, let me just submit the test."* |
| `WhetherThePatchReachesTheLineTest` | `Prove.reachesTheFlaggedLine(diff)` decides by arithmetic on `@@ -a,b +c,d @@` headers under the matching `---`/`+++` file: spanning reaches (line 40 inside `-37,8`), stopping short does not (`-12,4`), the right lines in the wrong file do not, an empty patch reaches nothing, and a **second** file's hunks are not read against the first file's name. Not reaching is signalled by the return value starting `THE PATCH DOES NOT TOUCH`; the text then asks for a sentence (`may not answer \`sound\` without saying`) rather than forbidding the verdict, and names `over-fit` as the answer when that sentence cannot be written | the fix-critic used to be handed the fixer's own account of its patch. Markers 02 and 19 reached `pr-ready` with the flagged line untouched: the fixer edited a neighbouring class, described the edit accurately, and the critic answered `sound` about a sentence rather than a diff |
| `TheVerdictAnswersToSomebodyTest` | `whatExecutionProduced` reads only `Prove`'s `builds` ledger. Empty: it names `NOTHING EXECUTED`, `` `unprovable` ``, and the two stronger states with what each costs (`BEHAVES`, `INTENDED`) — three priced options rather than three words. Non-empty: the ledger in order joined by `; `, no `NOTHING EXECUTED`, and what a passing red showed (`a red build that PASSED observed the code behaving correctly`). `reviewed(critic, producer, task, answer, preface)` keeps the producer's answer when the critic **throws** | the verdict was the one producer with no critic between it and the record. It carried 20 of the 77 faults found across 28 markers, and six of the thirteen wrong settlements were `by-design` reached because a repository framed as deliberately vulnerable licenses whichever exit is cheapest to argue. Structurally it could not have a critic: `argued()` took a finished argument, so there was no task left to re-ask with |

### The record

| Class | What must be true | The failure behind it |
|---|---|---|
| `ReadingWhatWasWrittenTest` | `Json.field` reads quoted strings, **unquoted booleans**, unquoted numbers, `\n`/`\t` escapes and escaped quotes; an absent field is `""`; a truncated line costs one field and does not throw; it round-trips `Settlement.escape` | `Settlement` writes booleans unquoted. Scanning for the next quote skipped the value and found the *following* key's quote, so `red_verified` read as empty for every marker that had gone red — the semaphore never lit while the data behind it was correct all along |
| `TheRecordSurvivesAnEmptyAnswerTest` | a `null` reply is recorded as `""`; `failed(...)` writes both `cause` and a `stack` containing `at `; every event carries a numeric `at`; a settlement carries `red_verified` and `green_verified` as written | an agent that answers with tool calls and no content returns `null`. Nineteen proves died on that, recorded as `NullPointerException` with no message, for markers whose model was working perfectly |
| `NamingWhatWasWrittenTest` | `Prove.fileOf` makes an analyser's build path repo-relative (`/builds/gitlab/team/owasp-webgoat/src/main/java/org/owasp/webgoat/X.java` → `src/main/java/org/owasp/webgoat/X.java`), leaves an already-relative path alone, and treats `src/it/java` as a source root like `src/main/java`. `Dashboard.slug` reproduces the shell's name exactly — everything after the **last slash** of the marker line, every character outside `A-Za-z0-9._-` replaced with `_`, cut to 80 — so `…\|src/main/java/a/b/Servers.java\|54\|TAINTED_PTR` is `Servers.java_54_TAINTED_PTR` | an analyser reports the path it compiled — wherever CI checked out — and resolving that against a checkout escaped it, making every marker in the report `infra`. Treating only `src/main` as a source root lost 74 markers. A slug the dashboard cannot reproduce reads as a marker nobody is working on, which is how "proving" lied about twenty-five rows |

### The model call

`TheReasoningIsNotThrownAwayTest` is one class and holds several separate rules:

- **Reasoning is recorded against the agent that thought it.** vLLM runs Qwen with a reasoning
  parser, so the server splits reasoning out of the content into a field of its own. The client did
  not ask for that field — which is why every recorded reply in the old traces opens with a blank
  line, the gap where the reasoning had been cut away. It was generated on every call and thrown away
  on every call.
- **There are two sources and both must be read.** The finished `AiMessage`'s `thinking`, and — when
  the server streams thinking but sets none on the message — what `Overheard` collected off the wire.
  Holding only the first records nothing, "and the difference is invisible until somebody opens a
  trace looking for a reasoning that is not there".
- **The field vLLM sends is `reasoning`, not the `reasoning_content` the client knows.** Chunks are
  reassembled across `delta` objects with escapes read (`{"choices":[{"delta":{"reasoning":"…"}}]}`),
  a chunk carrying only `content` leaves nothing behind, and `drain()` empties the buffer so the next
  call does not inherit it. Every SSE event reaches the client untouched — "one dropped here is a
  token the answer never sees".
- **A model that thinks nothing records nothing**, rather than an empty fold on every turn; blank or
  whitespace-only thinking is nothing.
- **The two bounds are different quantities.** Silence on the wire (`patience`) and an answer that
  will not finish (`ceiling`) fail with different messages: the first names the agent, the second
  says `still generating`. A model speaking steadily is never killed by the silence bound.
- **A runaway leaves its reasoning behind before it is killed**, marked `as far as it got`. Eight of
  ten deaths in one run recorded the exception and not a word of what the model had been generating
  for thirty minutes; "it looped" was a guess.
- **An endpoint that errors reports its own cause** (`context length exceeded`), not a wrapper's.

`WhatTheModelIsAskedForTest` holds the settings behind those bounds; see the table further down.

> The incident that separated them: a single "timeout" measured elapsed time and read like silence.
> It reported a healthy endpoint as dead **eighty-six times**, and with the token cap removed it
> killed ten live calls in the first five markers of a full run — five requests sharing one GPU
> generate about twenty-four tokens a second each, and twelve minutes of that is seventeen thousand
> tokens.

### The pool, the claim and the pace

| Class | What must be true | The failure behind it |
|---|---|---|
| `AClaimOutlivedItsProveTest` | **a claim lasts exactly as long as its prove.** The class reimplements the pool's shell `release()` in Java, so the rule can be asserted without starting a container — a rebuilder must keep the shell and this mirror in step. A prove that ended in `infra` gives the marker back: the claim is deleted and the attempt is moved to `dead/<id>.attempt-N` (N = `Pace.tries` + 1), out of `m/`. A settled marker keeps `m/<id>/settlements.jsonl` and still loses its claim. `Pace.tries` counts only `attempt-N`; `Pace.attempts` also counts `restart-N` and `before-postponing`. An unreadable `dead/` yields 0 tries. A settled marker is never reported QUIET however long its claim lingers, and a genuinely stalled one still is | the pool decides twice. `settled` was deliberately taught to answer NO for `infra`, because a prove that threw has settled nothing — and three lines later `mkdir claims/$id \|\| continue` skipped it, because the claim from the dead attempt was still there and no code path removed it. **The second gate silently repealed the first.** The README's promise that "a marker already settled anywhere is skipped" quietly became "already ATTEMPTED is skipped". Both gates read correctly alone; only their order was wrong, which is why reading either one found nothing. The watcher paid too: several hundred finished markers arrived in its brief as `QUIET, still claimed (idle=1009m)`, it spent two whole passes reporting a stall that was not happening, and its critic refuted both findings |
| `MuchLongerThanTheOthersTest` | "too long" is a **comparison**: `Pace.typical` is the median of settled lanes and is `0` until `ENOUGH` of them exist; nothing is an outlier below `NEVER_BEFORE` minutes; the outlier text carries both numbers, says `not necessarily stuck`, reports total minutes (`85 minutes on this marker`) `across N attempts`, and warns that `restarting it again would reset that count`. `Pace.totalMinutes` sums the live lane and every `dead/<id>.*` archive; archived attempts count towards a marker's total but **not** towards `Pace.typical`. A marker id is matched exactly, never as a prefix. `postpone`/`resume`/`postponed`/`allPostponed` round-trip, and `allPostponed` is the list the pool reads once the queue is otherwise done | a fixed cap is either strangling ordinary work or letting a stuck prove run all day the moment the model, endpoint or subject changes. A mean would be dragged far enough by one four-hour prove to make itself ordinary. Folding container-restart archives into the median took it from 8 minutes to 744 and made an outlier impossible. `restart_prove` moves the trace to `dead/` and the next attempt starts a new one, so a marker could burn the same time over and over and read as young every single time. `TAINTED_PTR` is a prefix of `TAINTED_PTR.COOKIE`, so prefix matching made one marker report five hours it never spent |
| `HowWideThePoolRunsTest` | `Workers.of(dir)` returns `DEFAULT` when the `workers` file is absent, empty or non-numeric — **never zero** — and otherwise clamps to `[LEAST, MOST]` (900 → `MOST`, 0 and −5 → `LEAST`); whitespace around the number is not junk (`"  6  \n"` → 6) | the width used to be a process argument fixed for the life of a run, so changing it meant killing the pool, which orphans every claim in flight. As a file the pool re-reads, it changes as the next marker starts |
| `settled_test.sh` | the pool's `settled()` is: `grep -rlF "\"suspicion_key\":\"<key>\""` over `m/*/settlements.jsonl`, then within each hit require a line for that key that also matches `"state":"(false-positive\|by-design\|unprovable\|reproduced\|needs-review\|verified/pr-ready\|verified/pr-rejected)"`. `by-design` is settled; **`infra` is not**; a record whose only state is `infra`, including one sitting in an archived-looking directory under `m/` (the glob still reaches it), does not answer for the marker | the same claim/disposition failure above, held at the layer that implements it |
| `width_test.sh` | the shell's `width()` strips the file through `tr -cd '0-9'`, so anything with no digit in it becomes the **asked default** (`$1`, 4 in the harness). Then a value below 1 becomes a hard-coded `4` and a value above 16 becomes `16`. Cases: no file → the asked default; `8` → 8; `99` → 16; `0` → 4; `x` → 4; `" 6 "` → 6 | the width is read by shell, in `entrypoint.sh`, at the top of every pool iteration — a Java test of `Workers` would hold the wrong artefact |

Note a real divergence between the two width implementations: for a value below 1, **the shell falls
back to 4 and Java clamps to `LEAST` (1)**. `Workers.save` clamps on write, so a file written through
the dashboard never carries 0; the divergence shows only for a hand-edited file.

### The supervisor and the watchers

| Class | What must be true | The failure behind it |
|---|---|---|
| `CuttingTheTreeTest` | `restart` releases the claim, deletes `m/<id>` entirely, keeps the evidence at `dead/<id>.restart-N`, writes the agent's own words to `restarts.jsonl`, and lifts any postponement. `Supervisor.LIMIT` restarts are allowed and the next is `REFUSED` with what to do instead (`finding to report`), changing nothing on disk. An unreadable `restarts.jsonl` **refuses**. A marker nobody is proving, or a blank key, refuses | a supervisor that can restart without bound is a loop that looks like progress — kill, re-prove, find the same anomaly, kill again — while every individual decision reads as reasonable. Nothing of a restarted prove may remain under `m/`, because the pool asks "has this marker settled" by grepping every `m/*/settlements.jsonl`, so a record left there answers on the dead prove's behalf and the restart becomes a no-op that reports success |
| `ADeadProveDoesNotReadAsAWorkingOneTest` | `Overwatch.digest` says `FAILED` and carries the cause but **not** the stack; a settled marker reads as settled; a claimed marker gone quiet is `QUIET, still claimed` and one a minute into a turn is not; replies and tool calls are counted per agent (`reproducer=2/`, `!1` for an empty answer, `reproducer=0/0t140`); an agent that has only used tools still appears; a marker with no test says `NO TEST`; a run that has not started yields a **blank** digest | a prove that died left no settlement, so its digest row said `proving` and read as a marker merely thinking hard — the exact case the supervisor exists for was the one case invisible to it. The cause travels with the fact because "no token in four minutes" and "still generating after thirty" are different failures and only one is the endpoint's. There is no token cap, so an agent that called tools 140 times and answered nothing is the only remaining shape of a loop |
| `AFindingIsNotAParagraphTest` | `Overwatch.split` bounds a finding by a `## Finding` heading, not a blank line: examples and explanation stay attached to their claim, the preamble is not a finding, a report with no headings is **one** finding rather than none, and a heading with nothing under it does not become a judgement | the watcher's first real report named four markers whose tests pass on unfixed code and traced the cause to a prompt — a genuine finding. Splitting on blank lines turned it into four claims, none of which contained the claim, and the critic correctly refuted a list of file names for asserting nothing |
| `ALaneIsTheUnitNobodySeesTest` | `Interpreter.lane` calls an infra build `did not run at all` and never `FAILED`; a green RED is reported as `the test PASSED`; an empty reply is shown as `nothing at all`; stages appear in order and the lane ends `WHERE IT ENDED: <state>` with the claim spelled out. `write` splits on the **last** `SHORT:` label, never ships the label, and falls back to the first sentence when the critic forgets it. An unsettled lane is not summarised, and a lane already summarised is not summarised again | every agent in a prove is handed its own stage, so none sees the lane; the table used to show the verdict agent's first sentence, which is an argument addressed to the next agent. An interpreter told "FAILED" about a build that never ran will write that a test failed. The first summary this pair ever wrote delivered the `SHORT:` block twice, and splitting on the *first* label opened the account by repeating the table's own sentence. 356 markers times two model calls is not a thing to repeat every pass |
| `AskingTheWatcherSomethingTest` | `Tools.reading(root, trace, agent)` is exactly `[glob, grep, list_dir, read_file]` — four, not two, because the private `only(...)` adds `grep` and `glob` to whatever it is asked for — and holds none of `restart_prove`, `postpone_prove`, `write_file`, `edit_file`, `run_build`. `Agents.chat(` must name `Tools.asking(` or `Tools.reading(` and must **not** name `Tools.supervising(`; the chat prompt is in `Agents.ORDER` and says `YOU CANNOT CHANGE ANYTHING`. A question is on disk **before** the model is called; a failure to answer becomes a turn and clears the flag; a blank question is not a turn; a conversation with an unanswered question says so (`Chat.unanswered`); newlines, quotes and backslashes round-trip; an unreadable `Chat.where(results)` is an empty conversation rather than an error page | `restart_prove` and `postpone_prove` belong to `overwatch-critic` alone, whose **silence refuses to act** — an unreachable critic can neither authorise a kill nor suppress a warning. An agent that answers questions and holds those tools fails the other way: somebody types "what is happening with LessonMenuService" and a model that reads it as a request kills the prove they were asking about. The container is redeployed several times a day and an answer takes minutes, so "the dashboard died mid-reply" is a normal state |
| `TheQuestionsItWasReconstructingTest` | `Tools.asking(results, trace, "chat")` — the set `Agents.chat` is actually given — is exactly `[glob, grep, list_dir, list_markers, marker_record, read_file]`: `reading` plus two read-only registry tools, and still none of `restart_prove`, `postpone_prove`, `write_file`, `edit_file`, `run_test`. `Registry.list(results, state, checker, limit)` opens with the **exact** whole-queue total as its first line (`301 marker(s) in the queue`) before anything that can be capped, says `291 more not shown` when it caps, and says `counts above are complete`; totals stay whole under a state or checker filter while the rows are filtered; every marker is counted including the ones with no lane (`queued=2`), and `infra` is counted as itself rather than folded into a disposition. `Registry.one(results, wanted)` resolves a full `repo\|file\|line\|checker` key or an unambiguous fragment, refuses an ambiguous one with `` `EI_EXPOSE_REP2` matches several `` and the candidates rather than guessing, returns the disposition, the checker, the settlement's `because`, the lane's `summary.txt` and the **name** `trace.jsonl` rather than the trace itself, and says `queued` / `no record` for a marker that has never run. A missing or unreadable `markers.txt` says `empty or unreadable` | asked how many markers were in the queue, an agent holding only file tools took the honest route — grep across every `settlements.jsonl` — and answered *"at least 60 markers (the grep output was suppressed after showing 60 matches, so the actual count is higher)"*. Truthful, careful about its own limits, and not the number: the queue held 356. Counting three hundred files with a tool that returns matching LINES is the wrong instrument and no prompt makes it the right one — **but a new tool that quietly stopped at sixty rows would be the same bug behind a better name**, which is why the counts are exact, always precede the rows, and a capped listing says how many it left out. Counting only the directories under `m/` is how 82 got reported as the size of a 356-marker queue |

**"One question at a time" is process-wide**, because there is one dashboard. That is right in
production and it makes `AskingTheWatcherSomethingTest` order-dependent: a test that asks without
waiting leaves the flag set, and the next one is refused and sees an empty conversation. A
`@BeforeEach` therefore polls `Chat.answering()` up to 200 times at 25 ms and asserts the flag is
clear before the test body runs. Without it the failure surfaces as *"expected 2 turns but was 0"*,
which reads like the recording is broken rather than like the test before it has not finished.

### Settings as data

| Class | What must be true | The failure behind it |
|---|---|---|
| `APromptIsDataTest` | with no override an agent gets the code's prompt; an override **replaces** it entirely; a blank or unreadable override is not an override; `Prompts.same` ignores trailing whitespace and line endings; an agent name cannot escape the prompts directory — it lands as a direct child under a flattened name | sixteen of the faults found in one run were "the prompt says nothing about this" — each a paragraph somebody could have written in a minute and could not, because it was a Java text block behind a build, an image and a redeploy. The fallback direction is the point: an agent given no instructions does not fail, it answers something, and a run of those is indistinguishable from a run that went badly |
| `WhatTheModelIsAskedForTest` | `patience_minutes` and `ceiling_minutes` are separate settings with separate names; every value is clamped **on the way out**; junk leaves the value where it was; a blank model or base URL falls back to the environment; saving one setting keeps the others; an unreadable settings file leaves the pipeline exactly as it was; `api_key` never appears among the settings, nor does anything key-shaped | the same eighty-six killed proves. A file edited by hand or left behind by an older version must not be able to put the pipeline somewhere the code does not expect |
| `WhichJdkTheBuildRunsOnTest` | the default is `25` and `javaHome` is `""`, meaning **leave the environment alone**; a chosen JDK becomes `/opt/java/<n>`; a JDK not in the image is refused rather than written; junk leaves builds on 25; choosing one sets `JAVA_HOME` **and** prepends `<home>/bin` to `PATH`; blank must not blank `JAVA_HOME` | this is not about compiling — javac 25 targets 8 through 25, and `--release 8` produces class file major version 52. A subject written before 2018 needs a JVM to *run* its tests on, because Surefire forks one from `JAVA_HOME` and finds 25 there whatever the bytecode says. Every such failure arrives as "the build produced no test result", which this program is right to refuse as evidence and which costs the marker regardless. Setting `JAVA_HOME` alone leaves `java` on the `PATH` resolving to the image's own, so a build compiles under one JDK and tests under another |
| `AQueueIsCheckedBeforeItReplacesOneTest` | the whole marker file is validated **before** any of it replaces the running queue; the previous queue is kept as `markers-before-*`; every complaint names its line number; complaints stop at a dozen with `possibly more`; an empty file is refused; a token is written to git's credential store as `oauth2:` for GitLab and `x-access-token:` for GitHub, never into a URL; a blank token writes nothing | a bad line in a queue does not fail at upload, it fails eight hours later — and it fails as one marker that never ran, which a reader cannot tell from a marker that ran and decided nothing. "invalid format" over three hundred lines sends somebody reading all of them. A run with no markers looks exactly like a run that has finished |
| `OneOrderNotThreeTest` | there is **one** agent order: `ORDER = CHAIN + WATCH + ASKED` in that order, `ORDER.size() == Agents.builtIn(...).size()`, no name appears twice, and `builtIn` returns its keys in `ORDER`'s sequence and not the hash's. `CHAIN` is ten names read as five producer/critic pairs — `reproducer`/`proof-critic`, `fixer`/`fix-critic`, `pr-maker`/`pr-critic`, `verdict`/`verdict-critic`, `estimator`/`estimator-critic` — in the order `Prove` calls them. `WATCH` is `overwatch`, `overwatch-critic`, `interpreter`, `interpreter-critic`; `ASKED` is `chat` alone. `verdict-critic` is in `CHAIN`; no `WATCH` name is, and no `ASKED` name is in either | the marker tabs held their own list and it was missing `verdict-critic` — so the agent that can send a settlement back for rework had no page, and its answers could be read only by scrolling the whole trace. Nothing failed; a tab was simply never there. An agent missing from the order still appears, at the end, sorted, which is the drift this replaced rather than a fix for it |
| `TheQueueIsInOneOrderTest` | `examples/webgoat/markers.txt` is sorted by severity rank (Critical, Major, Normal, Minor, then unknown **last**), then path, then line **as a number**, then checker; the first marker is Critical; no marker appears twice; every line parses as `repo\|file\|line\|checker` with a numeric line. Severity is not in `markers.txt` — it is joined from `examples/webgoat/severities.tsv`, tab-separated, ≥ 4 fields, keyed `<basename>\|<line>\|<checker>` with the severity word in field 4. The sort key the test builds is `"<rank> <path> <line as %09d> <checker>"`, and a missing severity ranks 4 | two runs are only comparable if they take the markers in the same order, and the file *is* the order — of the queue, of the table, and of what a run stopped half way through has covered. Sorting the line as text puts 100 before 21. 74 of these markers are in the test tree where no analyser severity was assigned, and they are the least interesting thing in the queue. The file is asserted to be in order rather than sorted at load, because a queue that reorders itself is a queue nobody can diff |

### The dashboard's rendering

| Class | What must be true | The failure behind it |
|---|---|---|
| `ACredentialIsNotPartOfTheRecordTest` | `read_file` on `model` or `git-credentials` returns a string starting `REFUSED`, under every spelling of the path (`./model`, `/results/model`, `../results/model`, `./git-credentials`); `grep` redacts the shapes themselves and marks the line `(hidden)`; the rest of the record still reads; a file whose *name* merely contains `model` still reads. The guard lives in `Tools`, so it holds for every agent given the tool, not only the chat one | the watchers are rooted where the secrets are: they read `/results` because that is where the record is, and the model settings and git credential store live in the same directory. It stayed theoretical while nothing asked. **A chat box makes it a question somebody can type** — "what is in the model settings" is one line, and the answer would put the API key into `chat.jsonl` and onto a page, the same key the settings form masks behind a button. A mask a second route walks around is not a mask. So it is refused at the tool, for every agent: a prompt is a request, this has to be a fact. A guard matching `model` as a substring would refuse every marker on a class called `Model` — a fence that has eaten the job it was protecting |
| `CodeIsEscapedOnceTest` | `Dashboard.code(String)` and `Dashboard.diff(String)` escape their input (`<` → `&lt;`, `"` → `&quot;`); colouring is **one pass over one alternation** so a keyword inside a string stays inside the string and a quote inside a comment does not open one — the spans are `<span class=k>` keyword, `<span class=s>` string, `<span class=c>` comment; a diff is coloured by line kind (`dr` removed, `da` added, `dh` hunk header, context plain); empty or whitespace-only renders empty | `code()` concatenated its argument straight into a `<pre>`, and one caller handed it `git diff` — so a patch touching a line containing `<` wrote markup into the page. Four separate replacements over the same text is how `// the "public" API` comes out with half a comment and a stray keyword |
| `ABlankLineIsALineTest` | `Dashboard.flagged` numbers lines **including blank ones**, marks the flagged line `>> N  `, says `THIS FILE HAS …` for a line past the end, and returns an empty block when the checkout is missing | `read()` drops blank lines, which is correct for a JSONL file where a blank line is nothing and wrong for a Java file where a blank line is line 79 and every number after it moves. Reusing it put `public ResponseEntity getProfilePicture` at line 82 of `ProfileUploadBase` — four lines below the truth — so the block disagreed with the argument beside it, which read as marker drift and nearly became a finding about the analyser. The agent had been right the whole time. **An off-by-blank-lines code block is worse than no code block: it is evidence, it is wrong, and it looks exactly like the drift this pipeline is supposed to detect** |

---

## The numbers the tests pin

| Constant | Value | Held by |
|---|---|---|
| `Workers.DEFAULT` / `LEAST` / `MOST` | 4 / 1 / 16 | `HowWideThePoolRunsTest`, `width_test.sh` |
| shell `width()` floor for a sub-1 value | 4 | `width_test.sh` |
| `Supervisor.LIMIT` | 2 restarts, the next refused | `CuttingTheTreeTest.limit` |
| `entrypoint.sh` `TRIES` | 3 attempts, then left for a person | `AClaimOutlivedItsProveTest.bounded` |
| `Pace.ENOUGH` | 8 settled lanes before there is a median | `MuchLongerThanTheOthersTest.tooEarly` |
| `Pace.MUCH_LONGER` | 4× the median | `MuchLongerThanTheOthersTest.outlier` |
| `Pace.NEVER_BEFORE` | 20 minutes, whatever the median | `MuchLongerThanTheOthersTest.floor` |
| `Tuning.TEMPERATURE` | 0.0, clamped to `[0, 2]` | `WhatTheModelIsAskedForTest` |
| `Tuning.MAX_TOKENS` | 0 (no cap), clamped to `[0, 200000]`; negative clamps to 0 | `WhatTheModelIsAskedForTest` |
| `patience_minutes` | default 4, clamped to `[1, 120]` (the test pins the lower bound) | `WhatTheModelIsAskedForTest` |
| `ceiling_minutes` | default 240, clamped to `[1, 1440]` | `WhatTheModelIsAskedForTest` |
| slug length | cut at 80 characters | `NamingWhatWasWrittenTest` |
| checker-note body | at least 200 characters of prose after stripping | `ANoteIsCheckedBeforeItIsTrustedTest` |
| complaint cap on a marker upload | at most 13 lines, last one `possibly more` | `AQueueIsCheckedBeforeItReplacesOneTest` |
| default JDK | `25`, `JAVA_HOME` untouched | `WhichJdkTheBuildRunsOnTest` |

---

## Failure direction, asserted

Getting one of these backwards is silent. Each row is held by a named test.

| When this is absent or unreachable | It must resolve to | Held by |
|---|---|---|
| prompt override file (missing, blank, unreadable) | the built-in prompt, never an empty prompt | `APromptIsDataTest` |
| model settings file (unreadable) | the coded defaults and the environment | `WhatTheModelIsAskedForTest.unreadable` |
| `workers` file (absent, empty, junk) | `DEFAULT` (4), never 0 | `HowWideThePoolRunsTest.theDefault`, `.junk` |
| `restarts.jsonl` (unreadable) | **REFUSED** — the count may never read as zero | `CuttingTheTreeTest.unreadable` |
| `dead/` (not a directory) | 0 tries — "no archive is no attempts" | `AClaimOutlivedItsProveTest.unreadable` |
| a critic that throws | the producer's answer **stands**; an objection must be raised to bite | `TheVerdictAnswersToSomebodyTest.silenceWaives` |
| a judge's own blank or unreadable reply | **rejects** — silence certifies nothing | `RoutingAWordTest.silenceCertifiesNothing` |
| `git status` failing / not a repository | an empty inadmissible list, never an invented one | `ARunMayNotCiteItselfTest.notARepository` |
| checkout missing under the dashboard | an empty code block, not a broken page | `ABlankLineIsALineTest.noTree` |
| the flagged source file missing | `Checkers.where` returns `""` — the checker note still arrives, minus the line check | `AWholeFamilyWrittenOffTest.noFile` |
| no note for a checker | `THIS PIPELINE HAS NO NOTE FOR <checker>`, said out loud, plus a request to record the reading | `AWholeFamilyWrittenOffTest.unknown` |
| a note resource that is absent, unreadable, or has no newline | `Checkers.read` returns null — silently absent, "at least the safe failure" | `ANoteIsCheckedBeforeItIsTrustedTest.twoParts` |
| a construct regex that will not compile | `Checkers.where` returns `""` — the sentence is missing, never invented | `ANoteIsCheckedBeforeItIsTrustedTest.compiles` |
| the chat log unreadable | an empty conversation; the page survives | `AskingTheWatcherSomethingTest.unreadable` |
| the chat answering thread dying | a turn saying it could not answer; the flag clears | `AskingTheWatcherSomethingTest.failureIsAnAnswer` |
| a build tree with no build file | an `IllegalStateException` naming it, never a guess of Maven | `TellingAFactFromAnOpinionTest` |
| a blank test name | `infra`, never a pass | `TellingAFactFromAnOpinionTest` |
| a Maven build with no `Tests run:` in its output | `infra`, never a failure | `TellingAFactFromAnOpinionTest` |
| a Gradle build whose `build/test-results/test` reports did not get newer | `infra`, never a failure — Gradle prints no `Tests run:` line, so "did a test execute" is the report timestamp | (`Gradle`; not covered by a JUnit class) |
| a chosen JDK not present in the image | fall back to 25, do not write the setting | `WhichJdkTheBuildRunsOnTest.notInTheImage` |
| a blank JDK choice | leave `JAVA_HOME` exactly as it was | `WhichJdkTheBuildRunsOnTest.untouched` |
| a blank token | write no credential entry at all | `AQueueIsCheckedBeforeItReplacesOneTest.blankToken` |
| a marker file with any bad line | replace nothing; the running queue survives | `AQueueIsCheckedBeforeItReplacesOneTest.refused` |
| a malformed JSONL line | one empty field, never a thrown parser | `ReadingWhatWasWrittenTest` |
| a `null` model reply | an empty `reply` in the trace, never an NPE | `TheRecordSurvivesAnEmptyAnswerTest` |
| a run that has not started | a **blank** digest, not a report about nothing | `ADeadProveDoesNotReadAsAWorkingOneTest.nothingYet` |
| a lane with no ending | not summarised at all | `ALaneIsTheUnitNobodySeesTest.stillRunning` |
| a critic that omits the `SHORT:` label | the first sentence becomes the short form; both halves survive | `ALaneIsTheUnitNobodySeesTest.noLabel` |
| `markers.txt` missing or unreadable | `empty or unreadable`, **never** `0 markers` — a confident wrong answer about the size of the run | `TheQuestionsItWasReconstructingTest.noQueue` |
| a marker name matching several markers | refused with the candidates, never resolved to a guess | `TheQuestionsItWasReconstructingTest.ambiguous` |
| a listing longer than its `limit` | the exact totals first, then the capped rows, then `<n> more not shown` — a silent stop is read as the whole set | `TheQuestionsItWasReconstructingTest.countIsExact` |

One caveat a rebuilder should know about: the `@DisplayName` on `AClaimOutlivedItsProveTest.unreadable`
reads *"an unreadable archive stops the retries rather than licensing them"*, while the assertion is
`assertEquals(0, Pace.tries(results, "m4"))` — zero tries means the `TRIES` gate does not fire and the
marker stays eligible. **Build to the assertion, which is what the pool reads.**

---

## What the tests deliberately do not hold

- **Whether a checker note is true.** `ANoteIsCheckedBeforeItIsTrustedTest` checks that a note is
  structurally sound and not a rubber stamp, and says so explicitly: thirty-one notes were corrected
  by a second reader working against a real checkout, one of them a whole family whose recipe pointed
  at a file carrying no marker at all. "That is a job for a reader with the source, not for an
  assertion."
- **Anything that needs a model endpoint.** Every class here is reachable without one — by reflection
  into pure functions, by a fake `StreamingChatModel`, or by reading a source file as text.
  `AskingTheWatcherSomethingTest` goes further and *depends* on there being none: "no endpoint is
  configured here, so answering fails immediately — which is the point", and that is how it observes
  that the question was written down first and that the failure became a turn.
- **The two shell rules, from Java.** `settled_test.sh` and `width_test.sh` exercise the shell
  implementations because those are the ones that run.

**The suite's working directory must be the `agent/` module.** Three classes read files by relative
path rather than through a `@TempDir`, in four places: `ANoteIsCheckedBeforeItIsTrustedTest`
(`src/main/resources/checkers`), `AskingTheWatcherSomethingTest.wiredToTheReadingSet`
(`src/main/java/tech/mikhailov/fsm/agent/Agents.java`), `AskingTheWatcherSomethingTest.editable`
(`target/chat-test-*.jsonl`), and `TheQueueIsInOneOrderTest` (`../examples/webgoat/markers.txt`,
`../examples/webgoat/severities.tsv`). Only the last of these skips politely when the path is
missing; the others fail.
