# WTF.md — consolidated finding registry

**Repository:** `fix-java-svace-markers` · **HEAD:** `3e728a5` · **Read as:** a senior Java developer
who has just inherited this and has to keep it alive. Bias toward deleting.

**Method.** Six readers' findings, checked at the code one at a time. Where behaviour was claimed, it
was run — a scratch harness compiled against `engine/target/classes` reproduced the coercion
divergences, the JSON-extraction failure and the allocation blow-up; `javadoc -Xdoclint:reference`
was run over two packages; `maven-surefire-plugin-3.5.6`'s own `plugin.xml` was unzipped. Nothing was
deployed, nothing was committed, no shell left this machine. The test baseline (engine 860,
orchestrator 787, runner 225, 0 skipped) was taken as supplied and not re-derived.

**Verification score: 2 findings dropped outright, 6 materially corrected, 3 with wrong locations.**
That is a low error rate for six partial readers. Treat the rest as trustworthy, and treat the eight
findings marked **RUN** as facts rather than readings.

**That score has since moved, and only by working the entries.** The four fix passes of 2026-08-06 found
`W31`'s prescribed edit wrong, `W26`/`W27`/`W28`'s line numbers drifted, `W52`'s `pause()` not a
duplicate at all, `W54(b)`'s `health()` not dead, `W5`'s "−6 in `splitCount`" backwards, and in the
Overengineering set `O8` naming the wrong constructor, `O17` already done, `O11` wrong on its merits and
`O1`'s staleness evidence already fixed by `W13`. **Nine more corrections, none of them visible from a
read.** The rule that produced all of them is the one at the top of each pass: go to the file:line and
check the claim before acting on it.

---

## STATUS — 2026-08-06 fix pass

Worked from HEAD `3e728a5`. Local reads and local `mvn` only; nothing deployed, nothing committed.
Baseline was **engine 860 / orchestrator 787 / runner 225, 0 skipped**; the tree now reports
**engine 863 / orchestrator 790 / runner 229, 0 skipped**, `BUILD SUCCESS`, Maven exit 0. The four
catalogue fixtures are byte-identical to HEAD — `git status` on both `harness/fixtures/` directories is
empty.

**THE GOVERNING CONSTRAINT OF THIS PASS.** Three Tier-1 entries are real defects whose fix moves a
catalogue fixture, and re-recording was explicitly not this occasion. They were implemented, measured,
reverted, and the measurement written down at the code so the next reader inherits the number instead
of re-deriving it. The catalogue is the record of where Java deliberately differs from the retired
JavaScript — and in all three cases the JavaScript had the same defect, so correcting the Java is
necessarily a new divergence. That is what a re-baseline is for.

| ID | Outcome | Note |
|---|---|---|
| **W1** | **REVERTED — moves a fixture** | Was already implemented in the working tree when this pass started. It moves `node-family-expected.json`: identical 1991 → 1989, divergence classes 138 → 142, `prmaker` 309/338 → 307/338. Reverted whole, tests included. |
| **W2** | **FIXED** | `HEAD /run_test` ran a full prove (measured: answered **200**, and `FakeExec` recorded `git clone` + two `mvn`); `HEAD /health` was a 404. Method hoisted, HEAD accepted beside GET. +2 tests. |
| **W3** | **FIXED** | Was already in the tree; verified catalogue-neutral and kept. One hoisted `Matcher` + `region()`/`lookingAt()`. |
| **W4** | **FIXED** | `test_path` now goes through `Workspace.stripLeadingSlashes` before the equality test, and `Edit`'s private copy is deleted (W54c). Catalogue-neutral: `fixTarget` stays 1794/1931. +1 test. |
| **W5** | **REVERTED — moves a fixture** | Defect reproduced exactly (`applyEdit("{}", "", "HACKED")` → `ok=true`, `text="HACKED{}"`). The guard moves `runner/harness/fixtures/expected.json`: `applyEdit` identical 16943 → 16389, and two invariants recorded at **0** violations go to **48** and **508**. Reverted; pinned in prose instead — see below. |
| **W6** | **FIXED** | Was already in the tree; verified catalogue-neutral and kept. Literals mask to quote + spaces + quote. |
| **W7** | **FIXED** | Measured red: six polls took **50,356,224 bytes** off an 8,392,704-byte newline-free file. Now `MAX_LINE_BYTES` (4 MiB), discard-to-newline, offset advanced past it, `readError` → `UNREADABLE`, and `complete` is false for a pass that folded nothing. +1 test. |
| **W8** | **FIXED** | Measured red: **80 records on disk, 78 callers told "written"**. `FeedbackStore.append` returns boolean; the before/after read of the shared counter is gone. +1 test. |
| **W9** | **FIXED** | `Json.num` delegates to `Values.numberOr`. Catalogue-neutral — all three engine harnesses green. +1 test covering `"1d"`, `"0x1p3"`, `"Infinity"`, `"1e400"` and both infinities. |
| **W10** | **SKIPPED — moves a fixture** | The `svace_line` half moves `node-family-expected.json` (divergence classes 138 → 140; the harness has cases literally named `verdict: prompt prep_prover.svace_line=0`). The `or`-into-`lib` half was skipped **on its own merits**: without the behaviour fix it promotes a JS-truthiness helper that `Values.java:29-35` calls a defect into the shared library, which is the opposite of the entry's intent. Finding recorded on `Verdict.or`. |
| **W13** | **FIXED, and wider than filed** | All five doclint errors fixed. `-Xdoclint:reference` added to **all three** modules — which immediately caught **five more** dangling references the entry did not know about — `Secrets.java:26`, `Suspicion.java:25`, `Suspicion.java:84` in orchestrator main, and `HarnessJavaSide.java:358`, `WorkspaceTest.java:95` in runner **test** sources, which only a clean build recompiles. All fixed. The five false paragraphs corrected rather than deleted, because three of them sit on components that W11/W14 own. |
| **W18** | **FIXED** | `git rm`'d (staged, not committed), `harness/report.txt` added to `runner/.gitignore`, `README.md:277` 745 → 833, `pipeline/.dockerignore`'s comment corrected. **The entry's location for the second README is wrong** — `pipeline/README.md` contains no `745` anywhere. `runner/harness/README.md`'s five mentions are correct as they stand: that file is explicitly historical and carries a "THE CURRENT TOTAL IS 833" note. |
| **W25** | **FIXED, and wider than filed** | The three lines commented out — plus **two more the entry missed** in `orchestrator/.env.example` (`FSM_GIT_HOST=`, `FSM_SOURCE_MODE=`), which the new test finds because it reads every `*.env.example` as specified. |

**Left as recorded defects rather than as silence.** Both reverted fixes are now written down at the
code with the number that stopped them, so a re-baseline has something to flip:

- `runner/Edit.java` — `splitCount`'s javadoc states the two-character hole plainly, and
  `EditTest.aTwoCharacterFilePlusAnEmptyOldStringPrependsSilentlyAndThatIsADefect` asserts the WRONG
  behaviour on purpose, with a comment saying so and what to do when it goes red.
- `nodes/Verdict.java` — `or`'s javadoc names the `svace_line` of 0, the one-word fix, and the exact
  catalogue cost.

**One correction to the registry's own advice.** W5 says "−6 in `splitCount` once the empty case is
unreachable". Deleting that branch does not shrink the method, it **hangs** it: `indexOf("", from)`
answers `from` for ever, so the loop never advances. Three existing assertions
(`EditTest:209-211`) pin its return values. The branch is a termination guard, not dead code.

**Line delta, +651 / −295 over 37 files.** By module: engine +164/−43, runner +122/−213 (the 193-line
`report.txt` is most of that), orchestrator +347/−34, docs and deploy +18/−5. Splitting by kind:
main sources +253/−92, tests +348/−3. The test half is the larger one on purpose — seven of the nine
entries that landed were pinned by a test that was red first.

---

## STATUS — 2026-08-06, second pass: the CODE entries

Worked from HEAD `f59c8ab`. Local reads and local `mvn` only; nothing deployed, nothing committed,
no shell left this machine. Baseline **engine 863 / orchestrator 791 / runner 229, 0 skipped**; the
tree now reports **engine 867 / orchestrator 797 / runner 229, 0 skipped**, `BUILD SUCCESS`, Maven
exit 0 (read off Maven's own exit status — the ~30 literal `BUILD FAILURE` lines in a green run are
`fsm-runner` test FIXTURE output). **The four catalogue fixtures are byte-identical to HEAD**:
`git status` on both `harness/fixtures/` directories is empty after every run.

**Scope.** Tier 2 minus the docs and deploy entries: 29 of them worked, plus the four open `W54`
sub-items. Rows are annotated in place below with FIXED / PARTLY / SKIPPED / WRONG and the reason.
`+688 / −386` over 50 files — main sources `+331 / −262`, tests `+347 / −114`.

**The two behaviour defects were red first, and the four new guard tests were proved to bite.**
`W12` reproduced exactly as filed (`{"test_score": 1e400}` threw `IllegalArgumentException: not
representable in JSON` out of a stage that composes evidence outside every try, and 1e21 printed
`1.0E21`). `W48`'s refusal is new behaviour with its own test. For `W34`, `W49`, `W50` and `W51` the
defect was PLANTED back — the multipart placeholder, `Clip`'s `max - 1`, the `setPosixFilePermissions`
call, `.version(HTTP_1_1)` and `.followRedirects` — each new test failed, and every file was restored
from a copy whose `sha256` was checked afterwards.

**What was NOT touched, and why.**

- **`W42` — the wire.** `note` is a key on every `/topic/markers` message. Dropping it changes an
  answer shape, which this pass may not do. `W45` was cut the same way: the duplicated record
  COMPONENT is gone, the emitted key stays and is sourced from `added`.
- **The catalogue fixtures.** Nothing moved one. `W12` was the risk — it changes how a non-finite and
  a ≥1e15 `test_score` render — and all three engine harnesses stayed green with the fixtures
  untouched, so it landed rather than joining W1/W5/W10.
- **Still open, and deliberately left rather than half-done:** `W47` (one `Env` beside `Http`, one
  `awaitShutdown`, and the first test either `main` has ever had — the repo's own signature defect, and
  a cross-module change that deserves its own pass), `W55` (eleven `item(...)` and the duplicated
  scripted `Llm.Http`), `W38`'s move of `SourceWindowService`'s seven names into the presenter, `W52`'s
  four `trimTrailingSlash` spellings, and `W54(e)`'s remaining constructors. Every one of these is a
  behaviour-neutral refactor whose only honest form is "with a test in front of it".

**Three corrections to the registry** are recorded on the rows themselves: `W52`'s `pause()` is not a
duplicate at all (three different interrupt messages, three different delay fields), its
`positive(Duration)` pair is `HttpRunnerClient`/`LocalRunnerClient`, and `W54(b)`'s `health()` is not a
dead pass-through — the GET handler calls it, so it was inlined rather than deleted. `W11`'s
description was also stale: the 2026-08-06 pass had already rewritten that javadoc, so the "8-line
javadoc asserting they differ" no longer existed, though the redundant component did.

**And the pass caught itself once, which is the whole argument for `W39`.** The refusal message added
for `W48` named `SuspicionDao` inside a STRING LITERAL in `domain/`. The naive comment-stripper the
backlog-port guard used to share would not have been the one to see it; the literal-aware stripper that
`W39` promoted to the single implementation failed the build on it immediately. The message was
reworded. A guard that reads less of a file than it claims to is the same defect class as a comment
that has stopped being true.

---

## STATUS — 2026-08-06, third pass: the DOCS, BUILD and DEPLOY entries

Worked in the tree the second pass left (HEAD `f59c8ab` plus its uncommitted changes). Local reads and
local `mvn` only; nothing deployed, nothing committed, no shell left this machine. The tree was
**engine 867 / orchestrator 797 / runner 229, 0 skipped** on the way in and is **engine 867 /
orchestrator 798 / runner 229, 0 skipped** on the way out — `BUILD SUCCESS`, **Maven exit 0**, read off
Maven's own exit status. (The 30 literal `BUILD FAILURE` lines in that log are `fsm-runner` test FIXTURE
output and were 30 before this pass too.) **The four catalogue fixtures are byte-identical**: `git
status` on both `harness/fixtures/` directories is empty. Nothing here touches the wire or `dedup_key`.

**Eight entries, all eight FIXED: W19, W26, W27, W28, W29, W30, W31, W59.** The one new test is the
only new test, and it was proved to bite.

**THE COUNTS WERE THE POINT, AND THE ENTRY ARGUES ITS OWN CASE.** `W19` was filed against a baseline of
1872. HEAD `f59c8ab` is 1883. The tree this pass started in was already 1893. Three different correct
answers for one README sentence, none more than a day apart — so all six numbers are **gone rather than
refreshed**, and `DeploymentTest.neitherReadmeAtTheFrontPrintsATestCount` keeps them out. Two of the six
were planted back to prove it fails, and it caught the one that mattered most: `"its 216\ntests"`, split
across a line wrap, which a line-oriented `grep` misses — five of six found, file reported clean. Both
READMEs were restored from copies whose `sha256` was checked afterwards.

**Numbers that do NOT rot were left alone**, and the distinction is the useful part: `README.md` still
prints 23,401 harness cases and 833 catalogued divergences, because those are pinned by FROZEN fixtures
and move only when somebody deliberately re-records. A test total moves on every commit; a fixture count
moves on a decision. `runner/harness/README.md`'s five mentions of 745 stay for the same reason plus
one more — that file is explicitly historical, reconciles them itself at `:67` and `:193-194`, and
numbers are its subject rather than a fact in passing. **The previous pass's reasoning for leaving it
was re-checked at the file this pass, not assumed.**

**Line delta, `+134 / −41` over 8 files** — docs and deploy `+80 / −41`, the one new test `+54 / −0`.

**AND THIS PASS CAUGHT ITSELF, exactly as the brief predicted a fix pass would.** The first draft of the
new guard's own javadoc said the six counts were wrong because "engine had grown past 860 while
orchestrator and runner had been miscounted low". Engine's README number was **901 against an actual
860 — overstated by 41**, the opposite direction. A false sentence about staleness, inside the test
written to stop false sentences about staleness. Corrected before the run that this STATUS reports, and
recorded here because the near-miss is better evidence than the fix.

**Three corrections to the registry, on the rows themselves.**

- **`W31`'s prescribed edit is wrong.** "Delete `:109-115`" would delete two facts that live nowhere
  else in the compose file: that `FSM_RUNNER_URL` is the one of the four bare variables where a blank
  value is survivable (both `HttpRunnerClient:108` and `HttpSourceReader:32` fall back to the same
  `DEFAULT_BASE_URL` — which is precisely why the paragraph below it lists only "the other three"), and
  that `DeploymentTest` pins the mode/address pair. Rewritten, not deleted.
- **`W26` and `W27` have stale line numbers** — `:88` is really `:100`, `:74-83` is really `:86-95`.
  Both drifted because **W25's fix in the FIRST pass added ~20 lines above them**. A registry written
  against one HEAD and worked against another does this; the claims themselves were exact.
- **`W28`'s supporting line numbers are one off** (`DeploymentTest`'s `DELETED` is `:102`, the assertion
  `:178`). The finding was otherwise verbatim correct.

**Nothing was deleted silently.** Every entry here removed a sentence that had stopped being true, and
in each case the reason it was written is still worth knowing, so it was rewritten rather than cut:
`W27` says where the two paths ARE set and why compose must not be made to interpolate them; `W28` says
which test its old sentence used to cite and that the test forbids the claim; `W31` carries a dated note
naming the shape it fossilised from; `W29` keeps the "a third module added later" clause and finishes
it, because that module arrived and is `runner`.

**Still open in this category:** `W35` (the comments journal is on by default and no documented route
prepares its directory — needs `DOCKER.md` edits and a `chown` line), `W36` (`stop_grace_period`, a
deploy behaviour change), `W37` (delete `orchestrator/.env.example` and retarget `theRunbookExists` —
a doc fix with a test move in it), and `W56` (the two `.cjs` unpack scripts). Each is a change with real
blast radius rather than a truth repair, and each deserves the pass it did not get here.

---

## STATUS — 2026-08-06, fourth pass: the OVERENGINEERING entries, O1-O21

Worked in the tree the third pass left (HEAD `f59c8ab` plus its uncommitted changes). Local reads and
local `mvn` only; nothing deployed, nothing committed, no shell left this machine. **Baseline measured
on the way in rather than assumed** — the brief's `863/791/229` is HEAD's, and three passes had already
landed, so the tree was **engine 867 / orchestrator 798 / runner 229, 0 skipped** in and out. `BUILD
SUCCESS`, **Maven exit 0**, read off Maven's own exit status; the 30 literal `BUILD FAILURE` lines are
`fsm-runner` test FIXTURE output and were 30 before this pass too. **The four catalogue fixtures are
byte-identical** — `git status` on both `harness/fixtures/` directories is empty after every run.
Nothing here touches the wire, an answer shape, the dashboard JSON or `dedup_key`.

**9 DONE, 2 PARTLY (O4, O6), 7 KEPT, 2 WRONG, 2 LEFT. Line delta `+244 / −433` over 30 files — net
−189** — plus `+118 / −19` in this registry itself, which is `+362 / −452` all told. Measured, not
estimated: the session-start diff was `+978 / −465`, every file this pass did not touch cancels, and the
residual is the pass. Deleted outright: `engine/Dockerfile` (−148), `engine/.dockerignore` (−14), the two
`package-info` tables (−62), `README.md`'s duplicated operations list (−42), `usecase/ProveMarkerRequest.java`
(−19), `Verdict.Attempts` (−16), `Verdict.Request`'s 12-argument constructor (−17), `Json.str(Object)`
(−18), `WorkModel.UNSETTLED` (−18), `LocalRunner.ensureCache` and its two calls (−12). The per-module
split is an ATTRIBUTION rather than a measurement — eight of the thirty files also carry earlier passes'
changes — but it lands roughly at docs and deploy −143, engine −60, orchestrator −15, runner −7.

**THE TWO HEADLINE CANDIDATES WERE BOTH REFUSED, AND BOTH ON NUMBERS TAKEN THIS PASS.**

`O2` is the one worth reading. find-sec-bugs was not argued about — it was WIRED IN and RUN.
`spotbugs-maven-plugin 4.10.3.0` + `findsecbugs-plugin 1.14.0` analyse Java 25 bytecode without
complaint (167 classes, 189 findings, **0 errors, 0 missing classes**) and report **10
`SQL_INJECTION_SPRING_JDBC`**, which looks like a clean swap until the defect goes back in. With
`"… ORDER BY … DESC LIMIT " + limit` planted at `JobRunDao.findRecent` — the exact concatenation the
745-line test's ORIGIN paragraph is about — and the reactor recompiled, **the report is identical: 189
before, 189 after, the same 10 findings at the same 10 methods, and `findRecent` in none of them.**
The taint is attributed to the private `query(String sql, Object...)` helper that every query in that
DAO funnels through, and that helper was already flagged, so any `@SuppressFBWarnings` that silences the
four blessed sites silences the defect with them. The homemade test, on the same planted tree, went red
and printed `JobRunDao.java:149 \`limit\``. Two more findings from the run: find-sec-bugs raises 2 false
positives the homemade rule resolves correctly (`CommentDao`'s ternary between two string literals), and
nothing in SpotBugs corresponds to `everyAllowanceStillSuppressesSomething`, which deletes a blessing
that has stopped applying. `orchestrator/pom.xml` and `JobRunDao.java` were restored from copies and
their `sha256` checked afterwards.

`O20` was refused the same way and honested up instead. The entry is right that the class promises a
whitelist and ships a blacklist for fully-qualified references; the javadoc now says so in two rules
with the hole named. The `−220` is only available on the ArchUnit swap, and a replacement that cannot be
shown catching what it replaces is a guard traded for a hope.

**THE ONE DELETION THAT WAS DEMONSTRATED BEFORE IT WAS MADE.** `O3` deletes a whole second image on the
claim that the fat jar already carries the engine. That claim was checked by running it: the jar was
built, `java -Dloader.main=tech.mikhailov.fsm.engine.Engine -cp orchestrator/target/fsm-orchestrator.jar
org.springframework.boot.loader.launch.PropertiesLauncher` printed `[engine] fsm-engine 0.1.0-SNAPSHOT
listening` and answered `GET /health` **200**, and `docker compose --profile engine config` then resolved
the rewritten service correctly. No image was built, and the compose file says so at the service.
**The entry's one-line prescription would have shipped a silent defect:** the pipeline image deliberately
declares no locale — that refusal is itself on the JUSTIFIED list, because an image-level `LANG` flips
`file.encoding` for every JDK 8/11 build under test — so an entrypoint that overrides the image's and
drops `LC_ALL=C.UTF-8` mojibakes exactly the stage log lines `NodeRoutes` returns in its reply. The new
entrypoint carries it, and a healthcheck, because the image's own asks the orchestrator's `/healthz`.

**AND SAYING WHICH PROBLEM `O4` IS SOLVING, because it is filed as one and is two.** The *five copies*
half is largely infeasible as prescribed — a build stage cannot be shared across three Dockerfiles in
three build contexts, and the two blocks inside `pipeline/Dockerfile` fetch different things — and O3
took the two copies that were genuinely removable, five down to three. The *unpinned Adoptium* half is a
supply-chain problem, not a tidiness one, and fixing it ADDS lines: five majors on two architectures is
ten release names and ten sha256s to bump on every Adoptium release, and a stale pin is a JDK nobody is
patching. So it is recorded at all three fetch sites with the exact pinned URL and the checksum endpoint
(both verified against the live API this pass) and the upkeep stated, rather than half-implemented in a
file this session cannot build. That is a decision someone can now take in ten minutes; today it is
taken by default.

**FOUR CORRECTIONS TO THE REGISTRY, on the rows themselves.** `O8` names the wrong constructor —
`Request.of` is a production caller of the 13-argument one, so only the 12-argument one was ceremony (and
its single caller was the test asserting what it did). `O17` is already done: the tree has five one-line
javadocs, not eleven duplicated paragraphs, so there is no `−34` to take. `O11` is wrong on its merits —
every claim in that javadoc was verified true at `ParseMarkers`, `BuildReproduceInput` and `app.js:118-123`,
and its case rests on a ratio that this registry's own Measurements section calls "not the signal".
`O1`'s staleness evidence is gone: W13 fixed the `{@link lib.Js}` in the first pass.

**AND THIS PASS FOUND TWO MORE LIES WHILE DELETING, which is the pattern the brief predicted.** The
javadoc of `TheInnerCirclesDependOnNoFrameworkTest` still named `lib.Js` among the things a domain class
must not reach; `Js` was deleted on 2026-08-05. And `VerdictTest:1192` said "the twelve-argument
constructor every existing caller uses" about a constructor with exactly one caller — that line. Both
were found by working an Overengineering entry, not by looking for them.

---

## Tier 1 — FIX NOW

Silent wrong answers about markers, all cheap. Every one of these makes the pipeline record something
untrue about a marker with nothing red anywhere, which is this project's signature defect.

### W1 · `nodes/PrMaker.java:216-224` + `nodes/FixSkeptic.java:211-214` · **WTF** · **RUN** · **REVERTED 2026-08-06 — moves `node-family-expected.json` (identical 1991→1989, classes 138→142). The reading below is confirmed; the fix needs a deliberate re-record.**

**Two of the five model-reply parsers use the naive `indexOf('{')..lastIndexOf('}')` scan that
`JsonExtract`'s own class comment calls a defect, and the failure is silent and inverted.**

`JsonExtract.java:16-19` states "Every stage that reads a model reply comes through here". Three do
(`ParseTest:117`, `ParseFix:112`, `Verdict:706`). These two do not.

Reproduced against the compiled classes. A curator reply of
`{"decision":"reject","reason":"this fork is frozen"}` followed by prose containing `{@code
FrozenForkPolicy}` makes `lastIndexOf('}')` land in the javadoc reference:

```
PrMaker.parsePrReply    → THREW JsonException: unexpected trailing content at offset 53
JsonExtract.extractJson → {decision=reject, reason=this fork is frozen}
```

`PrMaker`'s shell catch at `:287-291` then sets `decision = PrDecision.MAKE.wire()`. **A curator that
said reject produces a drafted PR**, with `pr_curated:false` and a reason blaming the endpoint.

The same shape through `FixSkeptic` (verified: `THREW … at offset 64`) hits the catch at `:282-285`,
which sets `verdict = UNKNOWN` and `answered = false` — **the row says the endpoint never answered
when it answered `sound`**, and a certified fix drops to `needs_review`. That is precisely the state
`FixSkepticTest:533-543` documents four markers of a 53-marker run reaching with no explanation.

`PrMaker.java:219-222` documents this exact failure mode and fixed only the `a >= 0` half of it. No
test in either class covers trailing prose.

**EDIT:** replace both blocks with `JsonExtract.extractJson(Llm.replyText(r), KEYS)` and branch on
null. `KEYS` is `List.of("decision","reason","pr_title")` and `List.of("verdict","reason")`.
**−14 / +4**, and it deletes the last two naive scans in the engine.

### W2 · `runner/RunnerServer.java:126` · **WTF** · **FIXED 2026-08-06.** Reproduced first: `HEAD /run_test` answered 200 and `FakeExec` recorded a clone and two Maven runs.

**`HEAD /run_test` runs a full prove, and `HEAD /health` is a 404.**

The method check is `if ("GET".equals(exchange.getRequestMethod()))`, so HEAD falls through to the
POST switch at `:145-152` and reaches `runner.runTest(body)`. An unsafe method does up to 90 minutes
of work, takes the single FIFO build slot and patches the shared workspace; the one HEAD a prober
actually sends is refused. `RunnerServerTest:344` only drives `HEAD /nope` — the 404 arm — so it
passes while the dangerous arm is unguarded.

**EDIT:** `String method = exchange.getRequestMethod(); if ("GET".equals(method) ||
"HEAD".equals(method)) {`. **+2 / −1**, plus a test that `HEAD /run_test` executes zero commands and
`HEAD /health` is 200 (**+12 test lines**). It is also what finally makes `Api.sendJson`'s HEAD arm
mean something.

### W3 · `lib/JsonExtract.java:111-121` · **WTF** · **RUN** · **FIXED.** Catalogue-neutral.

**Pass 2 is unbounded and copies the whole tail per candidate**, while `JsonExtract.java:36-39`
claims "neither the candidate scan nor the rewind may become quadratic".

`MAX_STARTS` bounds pass 3 only. Pass 2 iterates every `'{'` in the reply and calls
`t.substring(p + 1)`. The reproducer embeds a whole Java file, so brace count scales with payload.

Measured on a 677 KB reply with 48,001 braces: **16,372,243,520 bytes allocated in one call.** (One
reader reported 2 GB on a smaller input; the growth is quadratic, so the shape is the finding and the
number is worse than reported.)

**EDIT:** hoist one `Matcher` and use a region —
`Matcher m = keyRe.matcher(t); for (int p : starts) { m.region(p + 1, t.length()); if (m.lookingAt()) {…} }`.
Same answer. **Net −1 line.**

### W4 · `runner/Edit.java:240` · **WTF** · **FIXED 2026-08-06**, with W54c's deletion of `Edit`'s private copy. Catalogue-neutral — `fixTarget` stays 1794/1931.

**The fixer/test-independence guard is defeated by a leading slash.**

`String tp = (testPath == null ? "" : testPath).replace(File.separatorChar, '/');` is compared with
`rel.equals(tp)` at `:243`, but `rel` comes from `base.relativize(full)` (never leading-slashed) while
`tp` is not stripped — and `Prove.java:116` explicitly calls
`Workspace.stripLeadingSlashes(testPath)` when writing the test, so a leading-slash `test_path` is a
supported input shape. For any project whose tests are not under `src/test/` — the only case this
exact-match arm exists for, per its own javadoc — **the fixer may edit the proof test and grade its
own exam.**

**EDIT:** `String tp = Workspace.stripLeadingSlashes((testPath == null ? "" : testPath).replace(File.separatorChar, '/'));`
**1 line changed.** (See W54c: `Edit`'s private copy of `stripLeadingSlashes` should go, and this fix
is the reason `Workspace`'s is the one to keep.)

### W5 · `runner/Edit.java:180-192` · **WTF** · **REVERTED 2026-08-06 — moves `runner/harness/fixtures/expected.json`** (`applyEdit` identical 16943→16389; two invariants 0→48 and 0→508). Defect reproduced verbatim and now pinned in prose at `splitCount` and in `EditTest`. **The "−6 in `splitCount`" half is wrong: deleting that branch hangs the loop.**

**`splitCount` returns `haystack.length() - 1` for an empty needle, so a 2-character file plus an
empty `old_str` reports exactly one match and `applyEdit` prepends silently.**

`applyEdit("{}", "", "HACKED")` → `ok=true`, `text="HACKED{}"` — a patch nobody aimed, wearing the
shape of a good one, which is the outcome the `old == null` refusal at `:136` exists to prevent. The
javadoc keeps it for fidelity to JS `split()`; HEAD's own commit message is *"there is no JavaScript,
so there is no reason to behave like it"*, so the justification has expired.

**EDIT:** `if (old.isEmpty()) { return Applied.failed("old_str is empty"); }` above the `splitCount`
call at `:139`. **+2**, and **−6** in `splitCount` once the empty case is unreachable.

### W6 · `lib/TestRealness.java:157-172` · **WTF** · **FIXED.** Catalogue-neutral. `BuildReproduceInput.quoted` is `private` in `nodes` and `lib` may not depend on `nodes`, so the two-line equivalent is inlined.

**`mask()` promises offset preservation and then breaks it on the next line.**

The javadoc says comments are blanked "CHARACTER FOR CHARACTER rather than deleted" because the
`@Mock` rules at `:105-108` measure an 80-character window and "a mask that collapsed a comment to
something shorter would drag an unrelated @Mock collaborator inside the window and condemn a sound
proof". Line 171 then collapses every string literal to `""` — the identical shortening, dismissed as
"their length is not load-bearing" three lines under the paragraph that says it is.

Collapsing pulls text *closer*, so the direction is toward false positives: a test with `@Mock
DataSource ds;` followed by a long SQL constant and then the subject's declaration reads as mocking
its own subject after masking → `sound=false`, `score=0`, and `RecordOutcome.java:610` routes a real
proof to `needs_review`.

**EDIT:** mask literals to `"` + spaces + `"`. `BuildReproduceInput.java:348` already has exactly that
helper (`quoted(char, int)`) for exactly this reason. **+2.**

### W7 · `feedback/CritiqueIndex.java:473-501` · **WTF** · **FIXED 2026-08-06.** Measured red: six polls read 50,356,224 bytes off an 8,392,704-byte file.

**`BUDGET_BYTES` bounds nothing, and a file with no newline is re-read in full on every poll and
buffered whole in heap.**

The loop is `while (consumed < BUDGET_BYTES && (read = in.read(buffer)) > 0)` and `consumed` is only
incremented at a `\n` (`:487`); every non-newline byte goes into an unbounded `ByteArrayOutputStream`
at `:492`. With no newline: `consumed` stays 0, the budget never trips, `offset` never advances, and
the next poll starts from 0 again. The `return offset >= size || consumed < BUDGET_BYTES;` at `:500`
then reports `complete` for a pass that consumed nothing.

Even *with* newlines, a single very large record — and `FeedbackStore` records carry prompts, replies
and whole source files — is buffered entirely in heap. Every one of the class's three headline claims
at `:60-66` ("every line parsed once, ever", "`BUDGET_BYTES` per pass", "`complete` says which of the
two a reader is looking at") is false on that input.

Reachable: `fsm.feedback.path` is a configured path and `:438` explicitly defends against "an operator
may move one aside".

**EDIT:** cap the in-flight line at ~4 MiB; on overflow discard to the next `\n`, advance `offset`
past it, and set `readError` so `stateNow()` returns `UNREADABLE` rather than `WAITING`. Fix `:500` to
not report `complete` for a pass that consumed nothing. **+15, +1 test.**

### W8 · `comment/CommentJournal.java:184-191` · **WTF** · **FIXED 2026-08-06.** Measured red: 80 records on disk, 78 callers told "written".

**One caller's durability answer is read off a shared counter, so two concurrent comments swap
outcomes.**

`write()` does `long before = store.failures(); store.append(event); return store.failures() == before
? WRITTEN : FAILED;` — but `failures` is a single `AtomicLong` on the shared `FeedbackStore`
(`feedback/FeedbackStore.java:169`). Two overlapping `POST /api/comment` and a failure on one is
reported as `FAILED` on the other and `WRITTEN` on the one that actually lost the durable copy. The
field this serves is `stored.journal` in the 201 body — a promise to a person that their paragraph
survives a redeploy — and `CommentService.java:53-57` notes the endpoint has "no rate limit and no
authentication of its own". The javadoc at `:176-183` calls the double read "not elegant" and does not
mention that it is not thread-safe.

**EDIT:** make `FeedbackStore.append` return `boolean` (the try/catch at `:271-276` returns false,
true at the end) and delete the before/after read. **−6 / +3**, no change to the never-throws rule.

### W9 · `lib/Json.java:265-279` vs `lib/Values.java:196-204` · **WTF** · **RUN** · **FIXED 2026-08-06.** Catalogue-neutral, to my surprise — all three engine harnesses green.

**One column, two readers, and the hardened one is not the one on the arithmetic path.**

`Values.numberOr` has a `DECIMAL` guard whose javadoc explains that `Double.parseDouble` accepts
`"1d"`, `"0x1p3"` and `"NaN"`, "every one of which would turn a corrupt cell into a plausible-looking
count". `Json.num` has no guard. Measured:

| input | `Json.num` | `Values.numberOr` |
|---|---|---|
| `"1d"` | 1.0 | 0.0 |
| `"1f"` | 1.0 | 0.0 |
| `"0x1p3"` | 8.0 | 0.0 |
| `"Infinity"` | Infinity | 0.0 |
| `"1e400"` | Infinity | 0.0 |

`prove_attempts` is read through the guarded helper at `PrepProver.java:262` **and through the
unguarded one at `RecordOutcome.java:180`**. `RecordOutcome.java:468` then writes
`(long) marker.proveAttempts() + 1`, and `(long) Infinity + 1` is `Long.MIN_VALUE` (measured), so the
`attempts >= MAX_ATTEMPTS` ceiling at `Verdict.java:784/:859` never fires and the marker requeues for
ever.

**EDIT:** make `Json.num` delegate to `Values.numberOr` so all 11 call sites get the guard. **−10.**

### W10 · `nodes/Verdict.java:372` (and `FixSkeptic.java:302`, `PrMaker.java:318`) · **WTF** · **SKIPPED 2026-08-06.** The `svace_line` half moves `node-family-expected.json` (classes 138→140). The `or`-into-`lib` half is skipped on its own merits — see STATUS. Finding recorded on `Verdict.or`.

**JS truthiness survived the retirement inside the node layer, and it is swallowing a `svace_line` of
0.**

Three byte-identical private `or(Object, Object)` = `Json.truthy(v) ? v : fallback`.
`Verdict.java:372` is `Values.text(or(Json.get(j, "svace_line"), "?"))` — a `svace_line` of 0 renders
`?` into the adjudication prompt, so the model is told the marker's line is unknown.
`Values.java:29-35` states that the `x || ''` idiom was a defect *precisely* because it swallowed "a
marker on line 0".

**EDIT:** move one `or` into `lib` beside `Values.orIfAbsent` so the two spellings sit together and
the choice is visible at each site, then audit the ~20 call sites. The `svace_line` one should be
`Values.orIfAbsent`. **−18 net.**

---

## Tier 2 — Cheap truth repairs

Comments and tests that state something the code does not do. Near-zero risk, high value: this
repository's comments are its best asset and every false one spends that credit.

| ID | Location | Bucket | What it is | Edit | Δ |
|---|---|---|---|---|---|
| **W11** ✅ | `nodes/Verdict.java:310-317, :325` | WTF | **RUN** — `Row.infraText` and `Row.infraJson` are the same expression under an 8-line javadoc asserting they differ. `Json.str(c,k)` → `Json.str(v)` → `Values.text(v)`. Ran both over null/String/Long/Boolean/List/Map/NaN/Infinity: identical every time, including `["a","b"]` for the list the comment names. The `Js` it cites was deleted 2026-08-05. | Delete the `infraJson` component, both constructor arguments and the paragraph; point `:1020` and `:1044` at `infraText` — **DONE 2026-08-06.** `infraJson` deleted; `stuck` and `evidence` read `infraText`. Catalogue-neutral. NOTE: the 2026-08-06 pass had already corrected this javadoc, so the entry's "8-line javadoc asserting they differ" no longer described HEAD — the component was still there and the finding stood | −12 |
| **W12** ✅ | `lib/ExecVerdict.java:97-107` | WTF | **RUN** — `Evidence.scoreText`'s stated reason is false. It says it is "pointedly NOT `Json#str` — that helper implements `x \|\| ''`, which turns a measured score of 0 into 'not measured'". Measured: `Json.str(0L)="0"`, `Json.str(0.0)="0"`, `Json.str(88.0)="88"`, `Json.str(2.5)="2.5"`. What actually differs: at NaN/Infinity `scoreText` **throws** and `Json.str` renders the word; **and at \|d\| ≥ 1e15** `scoreText` gives `"1.0E21"` where `Json.str` gives digits. `test_score` reaches this raw off a posted body and the throw at `:793` is outside every try, so the verdict stage dies on `{"test_score":1e400}` — well-formed JSON that `ParseFix.java:192-225` already hardens against by name | Delete `scoreText`, use `Json.str`; **note the 1e15 rendering moves**, in the direction `Values.plain`'s "no exponent, ever" rule already mandates. Then the 10-key stringly-typed round trip in `Verdict.evidence()`/`stuck()` has no reason left — call `new ExecVerdict.Evidence(...)` directly — **DONE 2026-08-06, both halves.** Red first: `{"test_score": Infinity}` threw `IllegalArgumentException: not representable in JSON` out of the stage, and 1e21 printed `1.0E21` exactly as filed. `scoreText` deleted, `Json.str` used; the ten-key map round trip in `evidence()`/`stuck()` replaced by the constructor, because the map's stated reason WAS the private coercion. Catalogue-neutral. +3 tests (Infinity, NaN, the 1e15 move) and one stage test | −45 |
| **W13** ✅ | `ParseMarkers.java:56,:147`, `nodes/package-info.java:35`, `Llm.java:76,:90` | WTF | **RUN** — `javadoc -Xdoclint:reference` over the two packages gives exactly **5 `error: reference not found`**, every one pointing at something deleted on 2026-08-05 (`Js`, `Js#parseInt10`, `Llm#concat`). Nobody runs javadoc. Separately, five paragraphs now say the opposite of what the code does: `ParseTest.java:109-111`, `BuildFixInput.java:147`, `BuildReproduceInput.java:269`, `RecordOutcome.java:222`, `Verdict.java:312` all assert that `Values.text` and `Json.str` are different functions, or that an absent field renders `undefined` (`Values.text(null)` is `""`) | **DONE 2026-08-06, wider than filed.** Five links fixed; `-Xdoclint:reference` added to ALL THREE modules, which immediately caught FIVE more the entry did not know about — `Secrets.java:26`, `Suspicion.java:25`, `Suspicion.java:84`, plus `HarnessJavaSide.java:358` and `WorkspaceTest.java:95` in runner test sources, which only `mvn clean test` recompiles — all fixed. The five paragraphs were CORRECTED rather than deleted: three sit on components W11/W14 own, and deleting the prose without the component leaves the next reader with no explanation at all | +26 / −13 |
| **W14** ✅ | `nodes/Verdict.java:355-374, :386-398` | WTF | Two record components that equal the field beside them, kept alive by 14 lines of javadoc about a JS quirk. `markerIdGiven` is `!Values.text(markerId).isEmpty()` and `markerId` is `Values.text(markerId)` — trivially equal by construction. **The javadoc's own counterexample refutes it**: it argues they must be separate because "`Boolean([])` is true and `String([])` is `""`", but `Values.text(List.of())` is `"[]"`, so under this coercion the empty array is the case where they *still agree*. Same for `methodGiven`/`methodText` | Delete both booleans; `:1170` → `marker.markerId().isEmpty()`, `:1067` → `!source.methodText().isEmpty()` — **DONE 2026-08-06.** Both booleans deleted; `:1173` asks `marker.markerId().isEmpty()` and `:1070` `!source.methodText().isEmpty()`. Catalogue-neutral | −20 |
| **W15** ✅ | `domain/Marker.java:88-89` | WTF | The `settle` javadoc's second refusal ends "NOTHING checks this today, at any layer" — inside the javadoc of the method that checks it at `:105-107` and throws `AttemptsWentBackwards`. Reads as a known-unguarded hole and is not one | "Nothing checked this before this method did; nothing else does now." — **DONE 2026-08-06 — the javadoc, not the code.** The code checks it: `settle` throws `AttemptsWentBackwards` three lines below the sentence. The paragraph now names that check as the ONLY one and says what is deliberately below it — `SuspicionDao` settles with no predicate on the column — pointing at `MarkerRepositoryContract`, which already states the division | 0 |
| **W16** ✅ | `runner/Preflight.java:412` | WTF | Garbled half-finished edit: *"BOTH ARE PROBED WITH A WRITE, including the second one, BOTH kinds, and both are probed with a WRITE."* In a class whose whole value is that its lines are trustworthy | "Both kinds are probed with a WRITE, including the read-only one." — **DONE 2026-08-06.** Rewritten as "BOTH TREES ARE PROBED WITH A WRITE — including the one every other comment calls read-only", pointing at the paragraph below it that gives the `prepareFs` reason. Not shortened into a preference | −1 / +1 |
| **W17** ✅ | `batch/CsvSpool.java:130-144` | WTF | The javadoc says "Checked in CHARACTERS before it is encoded, and in BYTES as it is written"; the code encodes first (`:139`) and checks BYTES both times | Keep the check (it avoids creating and deleting a temp file for a body already known to be oversized) and rewrite the two sentences to say that — **DONE 2026-08-06.** Confirmed: the string is encoded first and BOTH checks are in bytes. Rewritten to say so, keeping the check and its reason, and naming what the old sentence cost (characters and bytes differ 4× on a UTF-8 report) | −4 |
| **W18** ✅ | `runner/harness/report.txt` | WTF | A committed, generated, stale artifact that two comments say is not committed. `git ls-files` confirms it is tracked (193 lines). It says `CASES 23401 IDENTICAL 22656 DIVERGENT 745`; the catalogue the test actually asserts (`harness/fixtures/expected.json`) says **22568 / 833 / 29 kinds**. `DifferentialHarnessTest.java:78` states in writing "It is generated, so it is NOT committed; the catalogue is", and `runner/.gitignore`'s header says the same. `README.md:277` prints "745 catalogued divergences" as fact | **DONE 2026-08-06.** `git rm`'d (staged, not committed), gitignored, `README.md:277` 745 → 833, `.dockerignore` comment corrected. **`pipeline/README.md` contains no `745`** — that location is wrong. `runner/harness/README.md`'s five mentions are correct as they stand: that file is explicitly historical and already carries a "THE CURRENT TOTAL IS 833" note | −193 / +12 |
| **W19** ✅ | `README.md:238,266,376`, `pipeline/README.md:20,27,62` | WTF | Six stale test counts in the two files a new maintainer reads first. They say 1751 across engine (901), orchestrator (634), runner (216); the supplied baseline at this HEAD is **860 / 787 / 225 = 1872**. All six locations verified EXACT | Fix the six — or better, stop printing a number that rots: say "run `mvn -B test`" — **DONE 2026-08-06, the second option.** All six numbers are gone rather than refreshed, and the entry's own arithmetic is the argument: it was filed against 1872, HEAD `f59c8ab` is 1883, and the tree this pass started in was ALREADY 1893 — three different right answers for one sentence, none of them older than a day. `mvn -B test` prints the totals; the prose now says what the command runs. **+1 guard test** (`DeploymentTest.neitherReadmeAtTheFrontPrintsATestCount`) so the number cannot come back, proved to bite by planting two of the six back. It normalises whitespace before matching **because one of the six was `"its 216\ntests"`, split by a line wrap — a line-oriented search finds five of six and calls the file clean.** `runner/harness/README.md` stays out of scope and its five mentions of 745 stay: numbers are that file's SUBJECT, it is explicitly historical, and it reconciles them itself at `:67` and `:193-194` — re-checked this pass, the previous pass's reasoning still holds | −6 counts, +54 test |
| **W20** ✅ | `TheBrowserSuiteIsNotInThisBuildTest.java:18, :96-105` | WTF | Two defects in the one class whose entire thesis is that a stale notice about an absent suite *is* the defect. `:18` says "The eight browser tests"; `SUITE` at `:48-81` lists **thirteen** and `ls ui/*Test.java` returns thirteen. And `theBrowserSuiteDidNotRunInThisBuildAndHereIsHowToRunIt` asserts `SUITE.isNotEmpty()` on a hardcoded `List.of` — an assertion that cannot fail | "eight" → "thirteen"; assert `SUITE.size()` against the file count so the print and the tree cannot drift — **DONE 2026-08-06.** "eight" → "thirteen"; the `isNotEmpty()` on a hardcoded `List.of` replaced by `containsExactlyInAnyOrderElementsOf(presentTests())` in BOTH methods, so the printed number is the tree's and an ADDED browser test fails the notice too | 1 word, +4 |
| **W21** ✅ | `nodes/BuildReproduceInput.java:11-12` | WTF | The same import twice (`tech.mikhailov.fsm.lib.Values`). Trivial — but it is the tree's only duplicate import and it survived in a repo that ships hand-written tests to scan source text for rule violations | Delete line 12; add Checkstyle `UnusedImports`/`RedundantImport` — **DONE 2026-08-06.** Deleted, and the three remaining imports sorted | −1 |
| **W22** ✅ | `nodes/BuildReproduceInput.java:297` | WTF | **RUN** — `SourceText.stripSpace` before `Values.base64ToUtf8` is a no-op, and its javadoc says it is load-bearing ("the newlines have to come out before the decode or what reaches the model is whatever the decoder made of the padding"). `Values.java:225-227` documents the opposite. Tested a 60-column wrapped payload *including padding split across a newline*: decode-with-strip and decode-without are byte-identical and both equal the original. Costs one full copy of a string up to `SRC_MAX` (300,000 chars) per source fetch. **`stripSpace` then has no callers anywhere in the repo** — verified by grep across all three modules | Drop the call, delete `SourceText.stripSpace` — **DONE 2026-08-06.** Call deleted and `SourceText.stripSpace` with it (a note left where it was). +1 test: a wrap landing INSIDE the `==` padding group, the case the old comment named as its reason, decodes identically | −12 |
| **W23** ✅ | `web/DashboardService.java:317` | WTF | `if (lower.contains("prove") \|\| lower.contains("prover"))` — any string containing `"prover"` contains `"prove"`, so the right operand is unreachable | Delete `\|\| lower.contains("prover")` — **DONE 2026-08-06.** The author meant "both spellings count" — `ABadReadMustNotBlankThePageTest` pins `markerProver` and `proveJob`, and `BatchConfig.PROVE_JOB` is the literal `prove`. The subsuming test is kept, the unreachable one deleted, and the javadoc now says why it cannot come back | −1 |
| **W24** ✅ | `web/JobsController.java:364-373` | WTF | An orphaned javadoc block: two javadoc comments sit back to back, javac attaches only the second, and the API's most surprising refusal (the GitLab-clone-URL host rule) is documented onto nothing. The method it describes, `refuse(String,String)` at `:411-422`, has no javadoc at all | Move `:364-373` down to sit immediately above `:411` — **DONE 2026-08-06.** The block moved down onto `refuse(String,String)`, with one line recording that it had been attached to nothing | 0 |
| **W25** ✅ | `deploy/.env.example:46,51,57` | WTF | **The documented quickstart ships variables the compose file explicitly forbids setting to empty.** `docker-compose.yml:116-121` moved four variables to bare `- VAR` pass-through so an unset one arrives *absent*, and names the exact consequence of not doing so: *"an empty value defeats it. `FSM_INGEST_MAX_CSV_BYTES` fails loudly that way (a DataSize cannot parse `""`)"*. `.env.example` ships three of those four as `FSM_GIT_HOST=`, `FSM_SOURCE_MODE=`, `FSM_INGEST_MAX_CSV_BYTES=`. That is `README.md:33-34` and `DOCKER.md:24-25` verbatim: `cp .env.example .env && docker compose up -d --build`. (Source-confirmed; the boot failure was reported by a reader who ran it, not re-run here.) `DeploymentTest:1055-1078` cannot catch it — it iterates `app.environment` in the compose file and `continue`s on any bare entry | **DONE 2026-08-06, wider than filed.** Commented out — plus **two more the entry missed**, `FSM_GIT_HOST=` and `FSM_SOURCE_MODE=` in `orchestrator/.env.example`, which the new test finds because it reads EVERY `*.env.example` as specified. Red first: it listed all five | +20 / −5, +59 test |
| **W26** ✅ | `deploy/.env.example:88` | WTF | Ships `FSM_PROVE_SCHEDULE=false`. compose:181 defaults it `${FSM_PROVE_SCHEDULE:-true}`; `application.yml:324` defaults true; the compose comment calls false "what you want while debugging one marker". A host that follows the runbook deploys a pipeline that never drains anything on its own | Comment it out — **DONE 2026-08-06.** Claim confirmed in full: compose `:181` is `${FSM_PROVE_SCHEDULE:-true}` and `application.yml:331` is `${FSM_PROVE_SCHEDULE:true}`. **The filed line is stale — it is `:100`, not `:88`**; W25's own fix in the previous pass added ~20 lines above it. Commented out, and the block now names which shape is the default and which is the debugging one, and says what the `=false` shipped: a healthy stack, a full backlog, a green run history and nothing ever proved | 1 line → +8 comment |
| **W27** ✅ | `deploy/.env.example:74-83` | WTF | Documents two knobs that do nothing. compose:74 and :79 hardcode `- FSM_DB_PATH=/state/fsm` and `- CACHE=/cache` with no interpolation, so a value in `.env` is discarded. Line 75 says "compose sets them correctly — change them with care", which is the sentence that makes a reader believe changing them there works. `README.md:294-295` lists both in the configuration table | Delete `:74-83` and the two README rows. **Do NOT** "fix" it by making compose `${FSM_DB_PATH:-…}` — that reintroduces W25 for the two variables where an empty value costs a whole run — **DONE 2026-08-06.** Every clause verified: compose `:74`/`:79` are hardcoded and uninterpolated, and the "change them with care" sentence is there. **The filed range is stale — it is `:86-95`, not `:74-83`** (same W25 drift as W26); the two README rows were exact. Both settings and both rows gone. **NOT a bare deletion**: the block is replaced by a comment naming where the two paths ARE set, why a value here is read by nothing, and — carrying the entry's warning to the reader who will next be tempted — why the compose file must not be made to interpolate them. The README table gained one paragraph saying it lists what an operator sets, not everything the stack reads. The volume argument was already carried by `README.md`'s "**The H2 path must be on a volume**" paragraph and by compose at both lines, so nothing was lost (named by its heading, not by a line number — this entry's own `:74-83` had already rotted into `:86-95`) | −10 / −2, +6 comment |
| **W28** | `orchestrator/playwright/run.sh:11-16` | WTF | Describes a stack that has not existed for several commits: "declares exactly three services — engine, orchestrator, runner — and DeploymentTest pins that list". It declares **two**: `fsm` (`:47`) and `engine` (`:262`). `DeploymentTest:101` puts "runner" in its DELETED list and `:175-177` asserts it never comes back — so the comment cites, as its authority, the very test that forbids what it claims. `pipeline/README.md:34-35` gets it right | Rewrite to "compose declares one running service, `fsm`, plus `engine` behind a profile; a test suite is not a service, so it is `docker run --rm`" — **DONE 2026-08-06.** Confirmed to the line: compose declares `fsm` (`:47`) and `engine` (`:262`, `profiles: ["engine"]`), and `DeploymentTest`'s `DELETED` list holds `"runner"` (**`:102`, not `:101`**) with the assertion at **`:178`, not `:175-177`**. Rewritten, and the replacement SAYS the comment used to cite that test — a stale sentence that names a real, findable test is the most believable kind there is, and deleting it silently teaches the next reader nothing | −6 / +3 |
| **W29** ✅ | `pipeline/pom.xml:3,11,14` | WTF | The aggregator's header is one module out of date: "the reactor that builds the judgement engine and the orchestrator", "NEITHER module inherits the properties below", "a third module added later has one obvious place to read the target from". `<modules>` at `:37-41` lists **three**, and the third (`runner`) pins `maven.compiler.release` itself | 3 lines — **DONE 2026-08-06.** All three locations exact, all three claims confirmed (`runner/pom.xml:36` pins `maven.compiler.release` itself, `engine/pom.xml:32` likewise, orchestrator via `java.version`). "NEITHER" → "NO module", the runner added to the header sentence, and the "a third module added later" clause kept and finished — that module ARRIVED, it is `runner`, and this header was written before it | 0 / +1 |
| **W30** ✅ | `orchestrator/README.md:42` | WTF | `mvn -B test  # engine + orchestrator` — the reactor has three modules and that command runs all three, including the 225 runner tests. The same file at `:62` gets it right ("all THREE modules' test suites") | 1 line — **DONE 2026-08-06.** Both locations exact. Now `# all three modules; no network, no container, no model`. The entry's own "225 runner tests" is left OUT of the fix on purpose — that is the W19 defect, and a count written into the line that was being repaired for being wrong would have been this pass committing the class of defect it was sent to remove | 0 |
| **W31** | `deploy/docker-compose.yml:109-115` | WTF | Two adjacent paragraphs describe incompatible mechanics for the same line. `:109-115` argues the old `=${VAR:-}` shape ("THE DEFAULT IS EMPTY… Empty resolves to blank"); `:116-121` states the variable is passed bare, under which an unset `FSM_RUNNER_URL` is **absent** and `application.yml:193` supplies the default. The first paragraph is a fossil of the shape the second replaced | Delete `:109-115` — **FIXED 2026-08-06, but the prescribed EDIT IS WRONG and was not followed.** The reading is exact: `:109-115` argues the `=${VAR:-}` shape and `:116-121` states the bare one, and `:122` is bare, so the first paragraph's opening IS a fossil. But it is not only a fossil, and deleting it loses two live facts that appear nowhere else in the file: (1) `FSM_RUNNER_URL` is the ONE of the four bare variables where a blank value is survivable — `HttpRunnerClient:108` and `HttpSourceReader:32` both fall back to the same `DEFAULT_BASE_URL`, which is exactly why `:116-121`'s "the other three bind to '' in silence" does not list it; (2) `DeploymentTest` pins the mode/address pair (`:531-538`) and pins the yaml default against the compiled one (`theRemoteRunnersAddressSaysTheSameThingInBothPlacesThatKeepIt`). Rewritten to the mechanics the line actually has, keeping both, with a dated note that the paragraph used to open "THE DEFAULT IS EMPTY" | −7 → −7 / +11 |
| **W32** ✅ | `client/SourceClient.java:14-21, :35-36, :79-81` | WTF | Three problems in one interface javadoc. (a) The 8-line HTTP request block is written twice and has drifted — the interface says `Authorization: Bearer $GIT_TOKEN`, `GithubSourceClient.java:23` says `$GITHUB_TOKEN`, which `CloneUrl.java:88` names as `LEGACY_GIT_TOKEN_ENV`; they also disagree on how to spell the retry budget. (b) `:35-36` says "`Connection: close` is not decoration" — the header has not been sent since `HttpTransport` was written, and `GithubSourceClient.java:30-31` was updated to say so while the interface's copy was not. (c) `:79-81` states "Implementations own the retry budget (3 attempts, 3s apart)" and `CheckoutSourceClient` — the default — has none: `:109-122` is one `reader.read(body)` in a try/catch. **Worse than reported: in `fsm.runner.mode=http` that reader is `HttpSourceReader`, a real network call**, so the "an in-process read has nothing transient to retry" defence covers only the `local` half. Nothing is red because `ClientContractTest` only ever instantiates `GithubSourceClient` (7 sites) | Delete the duplicated block and point at `{@link GithubSourceClient}`; fix `$GIT_TOKEN`; delete the `Connection: close` sentence; move the retry sentence onto `GithubSourceClient` and state on the interface that retry is an implementation choice — **DONE 2026-08-06, all three.** (a) The duplicated request block deleted from the interface, which points at `GithubSourceClient`; the surviving copy's `$GITHUB_TOKEN` corrected to `$GIT_TOKEN` (`Secrets.gitToken` reads it and falls back). (b) The `Connection: close` paragraph replaced by what actually happens — the stages set it, `HttpTransport` skips it, the rule survives as "do not open a second pool". (c) The retry sentence moved onto `GithubSourceClient`; the interface now says retry is an implementation choice, and `CheckoutSourceClient` carries the `http`-mode gap in writing | −14 / +2 |
| **W33** ✅ | `engine/pom.xml:77-86`, `runner/pom.xml:92-101` | WTF | **RUN** — a surefire `<configuration>` that does nothing, under a comment about something else. Unzipped `maven-surefire-plugin-3.5.6`'s own `META-INF/maven/plugin.xml`: `<trimStackTrace implementation="boolean" default-value="false">`. The one setting is a no-op, and the 3-line comment above it is about binding real sockets and asking the OS for port 0 | Keep the `<version>` pin; delete the `<configuration>` block and its comment in both poms — **DONE 2026-08-06.** Re-verified against `maven-surefire-plugin-3.5.6`'s own `plugin.xml` (`default-value="false"`). Both `<configuration>` blocks deleted, version pins kept, and a comment left in each pom so the no-op is not re-added | −12 |
| **W34** ✅ | `application.yml:88-91` | WTF | Says "one number, three enforcement points" and the container's is resolved differently from the other two. `max-file-size: ${FSM_INGEST_MAX_CSV_BYTES:33554432}` resolves only from a *placeholder*; `IngestSizeLimit` and `CsvSpool` read the *property* `fsm.ingest.max-csv-bytes`. Set it as a Spring property and the filter and spool move while the container stays at 32 MiB — `TheFilterAndTheControllerRefuseInOneDocumentTest:52` demonstrates exactly that. (In the deployment, where the env var is what moves, all three agree; this bites property-based configuration only) | `max-file-size: ${fsm.ingest.max-csv-bytes:33554432}` and the same for `max-request-size` — **DONE 2026-08-06.** Both lines now read `${fsm.ingest.max-csv-bytes:33554432}`. +1 test: `TheFilterAndTheControllerRefuseInOneDocumentTest` sets the property to 1000 and now asserts `MultipartProperties` moved with it — red before the change (32 MiB against a 1000-byte filter) | 2 lines |
| **W35** | `application.yml:427,433` + `DOCKER.md:72,96` | WTF | The human-comments journal is **on by default** and no documented deployment route prepares the directory it writes to. `FSM_COMMENTS_JOURNAL:true`, path `/data/feedback/human-comments.jsonl`. Neither variable appears in `deploy/docker-compose.yml`, `deploy/.env.example`, `README.md` or `DOCKER.md` (all four grepped). `DOCKER.md:72`'s `mkdir -p` omits `fsm/feedback`, so Docker creates the bind source as root and the unprivileged process cannot write it — the failure the compose file spends 18 lines at `:227-245` explaining. `DOCKER.md:92-98`'s `docker run` route has no writable feedback mount at all. Every operator-facing mention of the uid-10002 chown is conditioned on `FSM_FEEDBACK=true`, which is off by default | `DOCKER.md:72` add `fsm/feedback` + a `chown 10002:10002` line; `:96` add `-v "$PWD/feedback":/data/feedback`; rewrite `DOCKER.md:275` and `README.md:340` to be about the directory, not about `FSM_FEEDBACK` | +3, 2 lines |
| **W36** | `deploy/docker-compose.yml` (no `stop_grace_period`) | WTF | Makes the Dockerfile's longest argument false in the one case it is about. `Dockerfile:305-311` explains that `env` EXECs so the JVM keeps pid 1 and "receives compose's SIGTERM directly… the shutdown closes the Spring context, which closes LocalRunner, which interrupts the build thread and kills the child Maven rather than orphaning one that owns the shared workspace". Compose's default grace period is 10s, and that chain will not complete in 10s during a 90-minute prove — so `docker compose down` mid-prove SIGKILLs the JVM and orphans the Maven, the exact outcome the comment says the design prevents | `stop_grace_period: 120s` on the `fsm` service | +1 |
| **W37** | `DeploymentTest.java:739-747` + `orchestrator/.env.example` | WTF | Two `.env.example` files (137 and 111 lines) that have already drifted — the orchestrator copy is missing `FSM_DB_PATH`, `CACHE`, `FSM_INGEST_MAX_CSV_BYTES`, `FSM_INGEST_SPOOL_DIR`, `FSM_INGEST_RESET`, `FSM_RUNNER_MODE`, `FSM_PROVE_VERDICT` — and `theRunbookExists` asserts against the **orchestrator** copy while every runbook (`README.md:33`, `DOCKER.md:24`, `orchestrator/README.md:55`) tells you to copy the **deploy** one. `DeploymentTest:690`'s own javadoc records that this split already shipped a wrong pre-filled model endpoint to a stranger | Delete `orchestrator/.env.example`, retarget the test, fix `orchestrator/README.md`'s pointer | −111, 2 lines |
| **W38** ✅ | `web/DashboardPresenter.java:8` + `NoControllerAssemblesItsOwnResponseTest.java:31-35, :153-184` | WTF | `DashboardPresenter:8` claims "THE SHAPE OF EVERY READ-PATH RESPONSE, IN ONE PLACE" and ~30 read-path wire names live in two files the guard cannot see. `/api/source` names 7 of its own in `web/SourceWindowService.java:99-104, :118-125, :128-132`; `/api/feedback` names 13+10+8 in `feedback/CritiqueIndex.java`. The guard governs four hand-listed files, and `everyControllerIsOnTheListAbove` — added specifically after `LivePublisher` was missed — only scans for `@RestController`/`@Controller`. `SourceWindowService` is `@Service` and `CritiqueIndex` carries no annotation at all; **neither can ever be caught**. The test's javadoc says "there is no fourth"; there are two | Move `SourceWindowService`'s 7 names into `DashboardPresenter` (3 small methods, ~25 lines moved); for `CritiqueIndex`, narrow the claim at both sites to "the controllers' bodies" — **PARTLY DONE 2026-08-06 — the claims, not the move.** Confirmed: `SourceWindowService` is `@Service` and `CritiqueIndex` carries no annotation, so neither can ever be reached by a guard that finds subjects by annotation. Both headings now state what is actually governed — every response ASSEMBLED IN A CONTROLLER — and name the two files that are not, and why moving them is a deliberate change to the read path. The move itself is left open | 0 net, 2 javadoc edits |
| **W39** ✅ | `TheInnerCirclesDependOnNoFrameworkTest.java:215`, `web/NoControllerAssemblesItsOwnResponseTest.java:215`, `NoNewCallerReachesTheBacklogAroundItsPortTest.java:468` | WTF | Three copies of `stripComments()`, and two are the buggy one. The third tracks string literals and text blocks and its javadoc says why: "being inside a literal is what tells a `//` in a URL apart from a comment, which a line-oriented stripper cannot do". The other two are 17-line naive versions that do exactly that. It fires today: `JobsController.java:414` contains `"https://gitlab.company/grp/proj.git"` inside a `badRequest(...)` argument, and `JobsController` is one of the four files the presenter guard governs — so part of it is invisible to the check right now. No `.put("key")` is lost today (latent, not live), but a `/*` inside a literal would swallow every line to the next `*/` and the check would go quietly green | Delete both naive copies, promote the literal-aware one to a package-private `tech.mikhailov.fsm.orch.TestSource` helper, call it from all three — **DONE 2026-08-06.** The literal-aware copy is now `tech.mikhailov.fsm.orch.TestSource` (public only because one caller is in `..web`); all three guards call it and the two naive copies are gone. It bit immediately: an exception message added in this same pass named `SuspicionDao` in a STRING LITERAL, and the backlog-port guard — reading literals for the first time in that file — failed on it. Message reworded | −34 |
| **W40** ✅ | `DifferentialHarnessTest.java:147-157` | WTF | `theyHeldOnTheRecordedReferenceAsWell()` iterates `report.invariants().get("js")` with `forEach` and asserts nothing about size or applicability, so an empty map passes over zero rules. Its sibling twelve lines up does it right (`assertEquals(8, java.size())`, `assertTrue(r.applicable() > 0)`) and states the rule in a comment: *"'0 violations' out of 0 applicable cases is not evidence of anything and must not be able to read like it is."* The very next method breaks the rule it just wrote down. The two sides really can diverge — the catalogue shows "a file that was served is inside the repository" at **16** applicable on the js side and **13** on the java side | Add the two lines from its sibling — **DONE 2026-08-06.** The js side now asserts `8` rules and `applicable() > 0` per rule, exactly as its sibling does. Both hold today | +2 |
| **W41** ✅ | `comment/CommentDao.java:250-255` | WTF | `markerCount()` is dead — no caller in `src/main` or `src/test` (both grepped). Its neighbour `count()` is test-only but asserted on; `deleteAll()` is documented as test-only. This is neither; its job was taken by `countsByMarker` at `:233-242` | Delete it — **DONE 2026-08-06.** Deleted, with a note that `countsByMarker`'s keys ARE the distinct markers and that its neighbour `count()` only looks equally dead | −6 |
| **W42** ✅ | `web/LivePublisher.java:111`, `web/DashboardPresenter.java:170` | WTF | A wire field that is permanently null. `pushMarker(dedupKey, from, to, note)` has exactly one production caller, `LiveWatcher.java:167`, which passes `null`. `markerTransition` then puts `note: null` on every message of `/topic/markers` — the busiest document this service sends. The only non-null value in the repo is `LiveSocketTest.java:281`, a test asserting the parameter it alone supplies | Drop the parameter and the key, or fill it from `SuspicionDao`'s note column — **SKIPPED 2026-08-06 — THE WIRE.** `note` is a key on every `/topic/markers` message, i.e. part of an answer shape this pass may not move. The other half of the entry (fill it from `SuspicionDao`'s note column) is a behaviour change with the same blast radius. Left as filed | −4 |
| **W43** ✅ | `comment/CommentService.java:114-116` | WTF | `Written.refused(...)` hard-codes `CommentJournal.Outcome.OFF`, reporting the durable journal as switched off when nothing touched it. Nothing reads it today (`CommentPresenter.refusal` reads `journal.enabled()` directly), so it is a loaded gun: the day somebody adds `stored` to a refusal body, every 400 tells the caller their durable store is off | Pass `null` so a reader has to decide — **DONE 2026-08-06.** `null` now. Verified nothing reads it on the refusal path today: `CommentPresenter.refusal` asks the journal, and only `accepted(...)` calls `written.journal().wire()` | 1 token |
| **W44** ✅ | `web/LiveWatcher.java:214-232` | WTF | A `Map<String,Object>` used as a three-field tuple, cast back out by hand at `:162-167` with `String.valueOf` and two unchecked `(String)` casts — in the one module that types everything. It also re-spells three of `DashboardPresenter.markerTransition`'s wire names in a file the presenter guard does not govern | `private record Moved(String key, String from, String to) {}`; return `List<Moved>` — **DONE 2026-08-06.** `private record Moved(String key, String from, String to)`; `diff` returns `List<Moved>` and the loop reads it typed. The three re-spelled wire names are gone with the map — the presenter still owns the names on the wire | −6 / +1 |
| **W45** ✅ | `batch/IngestAccount.java:41,45` | WTF | `written` and `added` are the same number by construction — `IngestTasklet.java:171-175` passes `added` for both, and the record's **own javadoc at `:41` says so**: "rows inserted, which equals `added`". Costs a record slot, a `toMap` key, an `into`/`from` pair and a doc paragraph, in a record whose stated job is to be believed at 3 a.m. | Delete the component; use `added` in `sentence()`'s RESET branch — **PARTLY DONE 2026-08-06 — the component, not the key.** Confirmed `IngestTasklet` passes `added` for both. The record component, the `from` read and the `sentence()` use are gone; `toMap()` STILL EMITS `written` (from `added`) because it is on an answer this service already gives, and this pass may not move the wire. The two tests that asserted `account.written()` now assert the emitted key instead | −9 |
| **W46** ✅ | `runner/HarnessFixtures.java:52,56-57,62-64` | WTF | The `treeRoot` field, its constructor parameter and the `treeRoot()` accessor are dead — no reference outside this file across `engine/src/test` and `runner/src/test` | Delete all three — **DONE 2026-08-06.** Field, constructor parameter and accessor deleted; the substitution that actually needs the root happens in `gunzip` on the way in | −6 |
| **W47** | `engine/Engine.java:37-55` vs `runner/Runner.java:82-109` | WTF | `env()` is byte-identical between the two mains; `intEnv()` differs only in its comment; `EngineServer.java:246-253` and `Runner.java:103-109` are two copies of `awaitShutdown` that **have already drifted** (the engine throws `UncheckedIOException` on interrupt, the runner silently returns). And neither `main` is tested — grep across all three test trees finds no reference to `Engine.main`, `Runner.main` or `intEnv`, leaving untested: the CACHE-spelling refusal, the non-ASCII locale warning, and the `intEnv` refusal both files argue for at length. This is the repo's own signature defect (`LocalRunner.java:107-111` says so) | One `Env.string`/`Env.port` beside `Http`; delete both copies; pick one `awaitShutdown`; unit-test it | −30 / +25 test |
| **W48** ✅ | `domain/Judgement.java:44-53` | WTF (demoted) | `Judgement.of` accepts `proving`, which `SuspicionDao.claimNext` will never select (`WHERE status = 'new'`) and `MarkerRepository.settle` writes with no status predicate. **Corrected from the original finding:** the class javadoc refuses "a status nothing in the pipeline claims", and `proving` *is* claimed — it is `SuspicionDao`'s queue token, documented as such at `SuspicionStatus:24-27`. So there is no lie, and nothing in the engine writes `proving`. What survives is cheap defence in depth for a state that would be invisible until the next restart | In `Judgement.of`, after the null check, refuse `PROVING` with its own message. Also fix `AProveThatReachedNoAnswerMustNotSpendAnAttemptTest:118-119`, which asserts every status is accepted and comments "Verdict writes each of these" — **DONE 2026-08-06.** `Judgement.of` refuses `PROVING` with its own message and its own reason (`claimNext` selects `new`, so the row is offered by no drain and counted by no grouping). `everyStatusTheEngineCanWriteIsOneAJudgementAccepts` now skips it and says why the comment "Verdict writes each of these" was wrong; +1 test for the refusal | +4 |
| **W49** ✅ | `dao/Clip.java:67` | WTF | The surrogate-pair guard is untested everywhere — grepping the whole orchestrator test tree for "urrogate" returns one hit, about URL escaping. Correct today; if a later edit made it `max` or `max + 1` (out of bounds on a 2048-char title) nothing would say so, and this class sits on the path its own javadoc describes as "one reply from one model, and a 26-hour drain makes no progress ever again" | In `OversizedPrTitleTest`: a `pr_title` of 2047 chars plus one emoji must come back 2047 long — **DONE 2026-08-06.** +1 test: 2047 chars plus one emoji comes back 2047 long and does not end in a lone high surrogate. Proved to bite — with `max - 1` planted back as `max` it fails; file restored, sha256 checked | +6 |
| **W50** ✅ | `batch/CsvSpool.java:186` | WTF | The owner-only permission on the spool directory is untested. `:42-53` lists four properties that make a report "untrusted input"; three are pinned, and the only one that stops third-party source paths sitting world-readable in a shared temp dir is not | Assert `Files.getPosixFilePermissions(spool.dir())` is `rwx------`, guarded on `PosixFileAttributeView` being available — **DONE 2026-08-06.** +1 test, guarded on the POSIX view being available exactly as the production code is. Proved to bite — with `setPosixFilePermissions` disabled it fails; file restored, sha256 checked | +8 |
| **W51** ✅ | `client/HttpTransport.java:95, :100` | WTF | The most expensive decision in the slice has no test and its twin in the engine does. `.version(HTTP_1_1)` carries a 10-line comment recording a real outage — over cleartext the JDK default sends `Upgrade: h2c`, uvicorn hands vLLM a bodyless request, every chat completion 400s, and the only visible effect is markers settling `needs_review` with `skeptic_verdict 'unknown'`. `HttpTransport:26-28` says this class "mirrors `Outbound` deliberately… its decisions are restated here"; `OutboundTest.java:230` pins it there. **Grepping the entire `orch/client` test tree for `Upgrade`, `HTTP_1_1`, `followRedirects` or `Redirect` returns zero hits.** The decision was copied into the class every deployed model call goes through; the test was not. `followRedirects(NORMAL)` at `:100` is unpinned for the same reason | `HttpTransportTest`'s `Stub` already records every request header and exposes `headerValues(String)`. One test asserting `headerValues("Upgrade")` is empty and the body still arrived; one test with two stubs, the first answering 301 with a `Location` at the second — **DONE 2026-08-06.** +2 tests: no `Upgrade`/`HTTP2-Settings` on the wire AND the body still arrives (both halves of the outage), and a 301 with a `Location` is followed to the second stub. `Stub` gained a `redirectingTo` factory. Proved to bite — with `.version(HTTP_1_1)` and `.followRedirects(...)` removed both fail; file restored, sha256 checked | +27 |
| **W52** ✅ | `client/` package | WTF | Duplicated private helpers across one package: `cause(Throwable)` **byte-identical in four files** (`HttpRunnerClient:257`, `HttpLlmClient:205`, `GithubSourceClient:295`, `CheckoutSourceClient:153`); `pause()` in three; `positive(Duration)` in two; `NOTHING_TO_SAY` verbatim in two; and `trimTrailingSlash` written four times with **three different meanings** (`HttpRunnerClient:252` and `GithubSourceClient:291` strip one, `GithubRepoLookup:124` strips all, `HttpSourceReader:33` inline strips one) | One package-private `final class Failures` holding `cause`, `positive`, `pause` and the constant; one `static String base(String, String)` in `HttpTransport` for the URL trimming. `retrying()` stays per-class — the log prefixes genuinely differ — **PARTLY DONE 2026-08-06, and the entry is partly WRONG.** `cause` (byte-identical in FOUR files), `NOTHING_TO_SAY` (two) and `positive` (two) now live in one package-private `Failures`. **`pause()` in three is NOT a duplicate** — each names its own wait in the message it throws and reads its own delay field, and one takes an endpoint; merging them would erase which wait was interrupted. **`positive(Duration)`'s two copies are `HttpRunnerClient` and `LocalRunnerClient`**, not the pair the entry implies. The four `trimTrailingSlash` spellings with three meanings are confirmed and NOT merged: making `GithubRepoLookup`'s strip-all into a strip-one is a behaviour change that needs its own test | −60 / +33 |
| **W53** ✅ | `batch/ProveProcessor.java:68-85` | WTF | A dead public constructor with 12 lines of javadoc. Both call sites pass 10 arguments (`BatchConfig:221-223`, `TheArgumentOffChangesNothingButTheArgumentTest:221-223`); this 8-arg one is called by nothing. It is also the only thing in the codebase that builds a throwaway `new FeedbackStore(false, Path.of(DEFAULT_FILE_NAME))` | Delete `:68-85` — **DONE 2026-08-06.** Deleted; the javadoc merged onto the surviving 10-argument constructor and a note left saying why both defaulted arguments must stay arguments | −18 |
| **W54** ◑ | `runner/` seams | WTF | Five dead or near-dead seams, verified by grep. (a) `RunnerServer.start(String,int,Path,String)` at `:60-64` — zero callers; `Runner.java:43` uses the 4-arg `(host,port,LocalRunner,boolean)` overload and both test sites use the 6-arg. (**Corrected:** the mirror wiring inside it is *not* dead — `Runner.java:38` reads `MIRROR_ENV` live.) (b) `RunnerServer.DEFAULT_CACHE`, `health()` and `ensureCache()` are three pass-throughs to `LocalRunner` with one implementation and no seam; their only caller already imports `LocalRunner`. (c) `Edit.stripLeadingSlashes:250-256` duplicates `Workspace:518-524` byte-for-byte in the same package (**corrected:** the javadocs differ by one clause, the code does not); `Prove.java:116` already calls `Workspace`'s. (d) `Build.buildCmd` 5-arg at `:227` — no production caller, 16 test call sites; it is a hole that lets a caller silently omit `-s`, which `LocalRunnerTest#aConfiguredMirrorReachesEveryMavenCommand` exists to forbid. (e) `Workspace` has four constructors and production uses one; `Prove` has two and production uses one | (a) delete; (b) delete three, point `Runner` at `LocalRunner`; (c) **DONE 2026-08-06 with W4** — `Edit`'s copy deleted, both call sites on `Workspace`'s; (d) delete, add `, null` at the test sites; (e) keep one canonical + one test helper each — **(a) DONE**, with a note that the mirror wiring is live in `Runner.main`. **(b) DONE** — `DEFAULT_CACHE` and `ensureCache` deleted and `Runner` points at `LocalRunner`; `health()` was NOT dead (the GET handler calls it) and is inlined at that one site. **(c) DONE 2026-08-06 with W4.** **(d) DONE** — the 5-arg `buildCmd` deleted, ten test sites pass `null`. **(e) PARTLY** — the `(cache, token, exec, LongSupplier)` overload is gone (two 4-arg constructors differing only by the LAST ARGUMENT'S TYPE is how a caller moves the clock meaning to move the git host); the 3-arg test helper, the 4-arg production one and the canonical 5-arg stay, which is one more than the entry asks for and each has a caller | −40 |
| **W55** | engine test tree | WTF | `private static Map<String, Object> item(Object... kv)` is redefined **eleven** times, identically (9 of them in `tech.mikhailov.fsm.nodes`). Separately, `WireSafetyTest.java:350-401` duplicates `harness/Diff.java:30-83` — the same scripted `Llm.Http` decoding the same frozen fixture protocol, one file apart in the same package. If the protocol grows a step kind, one handles it and the other falls through to `Json.get(step, "reply")` and returns null: a whole family answering wrong while both tests stay green | One package-private `Items.item(...)`; drop `private` on `Diff.Stub` and delete `WireSafetyTest.Script` | −102 |
| **W56** | `engine/harness/unpack-fixtures.cjs`, `runner/harness/unpack-fixtures.cjs` | WTF | Two Node scripts in a repo whose HEAD commit is *"there is no JavaScript, so there is no reason to behave like it"* — the only `.js`/`.cjs` besides the dashboard's `app.js`. Nothing in the build runs them; only `harness/run.sh` does. `HarnessFixtures.java` already does the same unpack in Java in both modules (`buildTree()` is a line-for-line port). The root README lists prerequisites as Docker, or JDK 25 + Maven; Node is never mentioned, so `sh harness/run.sh` fails on a documented-complete workstation | Delete both; drop the `node harness/unpack-fixtures.cjs` line from both `run.sh`; expose the existing Java unpack behind `-Dharness.unpack=true` | −95 |
| **W57** | `AGreenRunMustHaveRunEveryTestClassTest.java` | WTF | The best test in the repository exists in only one of three modules. Its origin note records a real, reproduced defect: a concurrent Maven build wrote `target/test-classes` mid-scan and Surefire reported 265 tests, BUILD SUCCESS, Skipped 0, over 31 of 44 classes. It covers orchestrator's 109 classes. Engine (30 classes) and runner (14) have nothing — **and they are the two modules that ship a script starting a second Maven build into the same target** (`engine/harness/run.sh:19`, `runner/harness/run.sh:19`). That is the exact collision the guard was written after, wired into the two modules that lack it. The class reads its own module off the classloader and asserts no number, so it is module-agnostic as written | Move it to a shared test-jar, or copy it verbatim into engine and runner | ~+120 |
| **W58** | `batch/ProvenMarker.java:39-43` vs `dao/JdbcArtifactRepository.java:28-32` | WTF | The same `instanceof Bug` downcast-and-throw with nearly the same sentence, in two places. `JdbcArtifactRepository`'s is the right one — it is where a `Bug` is actually persisted; `ProvenMarker`'s makes the domain blind to 22 columns by a type the adapter casts straight back | Move the check to the repository only (see O9, which deletes `ProvenMarker` outright) | −6 / +2 |
| **W59** | `pipeline/README.md:36` | WTF | "`deploy/.env.example` documents every variable it reads" — it does not. `FSM_RUNNER_URL` is listed bare in compose and appears nowhere in `.env.example`, deliberately, which makes the sentence false about the one variable compose spends twenty lines on | "documents every variable an operator sets; `FSM_RUNNER_URL` is deliberately absent, see the compose comment" — **DONE 2026-08-06.** Location exact; confirmed `FSM_RUNNER_URL` appears in `.env.example` only inside a comment at `:142`, never as a setting. Taken slightly wider than filed because **W27 landed in the same pass and made the sentence false a second way**: `FSM_DB_PATH` and `CACHE` also left that file. The rewrite names both kinds of gap — absent so that unset means absent rather than blank, and fixed in compose beside the volume that mounts it | 1 line → +3 |

---

## Overengineering

Ranked by lines removed per unit of risk. None of these causes a defect; all of them cost reading time.

**WORKED 2026-08-06, fourth pass — outcome on every row.** 9 DONE, 1 PARTLY, 7 KEPT, 2 WRONG, 2 LEFT.
The bar applied throughout: delete it, then ask what would now go unnoticed. If the answer is "nothing"
it was ceremony; if the answer is "the thing it was written for" it was not. Two of the seven KEPTs are
the registry's own headline candidates, and both were refused on MEASUREMENTS taken this pass rather
than on taste — see O2 in particular, where find-sec-bugs was actually wired in and actually run.

| ID | Location | What it is | Edit | Δ |
|---|---|---|---|---|
| **O1** · **DONE 2026-08-06.** | `nodes/package-info.java:20-46`, `lib/package-info.java:11-45` | **Measured 55:1 and 45:1** comment-to-code. Both are HTML `<table>`s restating each class's own javadoc one abstraction level worse, and both have gone stale — `nodes:35` links `{@link tech.mikhailov.fsm.lib.Js}`, deleted 2026-08-05 (this is one of W13's five doclint errors) | Keep the two load-bearing paragraphs (the request-contract naming rule at `nodes:8-18`, "these look like the JDK equivalent and are not" at `lib:4-9`); delete both tables — **OUTCOME:** Both tables deleted; the load-bearing paragraphs kept. The stale `{@link lib.Js}` was already gone — W13 fixed it in the first pass — so the *staleness* half of this entry is now WRONG and the ratio half stands on its own. What replaced each table is one paragraph of the only thing a table could carry that `javadoc`'s own generated class list cannot: the ORDER. `nodes` now says read `RecordOutcome` and `Verdict` first and why they fail differently; `lib` says start at `Values.plain` and `Json`'s strictness. Checked before deleting: every cell was a weaker restatement of that class's own first javadoc paragraph. **−62/+14.** | −60 |
| **O2** · **KEPT — the trade was MEASURED and it does not hold.** | `NoQueryIsBuiltFromANonConstantTest.java` (745 lines, whole file) | A hand-written Java lexer, constant-folder and allowlist to enforce one rule. It tokenizes Java, resolves `+`-chains, folds same-file constants transitively, keeps a 4-entry allowlist, a self-test and a staleness check. It is the best-built homemade linter in the repo, and that is the problem: 745 lines that must themselves be maintained, and it admits at `:76-80` that it cannot see `StringBuilder` or `String.format` | find-sec-bugs' `SQL_INJECTION_SPRING_JDBC` covers exactly this (which plain SpotBugs' `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` does **not**) with real dataflow. Add `spotbugs-maven-plugin` + `findsecbugs-plugin`; turn the four `ALLOWED` entries into `@SuppressFBWarnings` with the same sentences at `JobRunDao:99-102`, `StaleExecutionReconciler:183-184`, `IngestHistory:70-72`, `SuspicionDao:120`; delete the file. **See the owner's call below — this is the one rule the repo cannot ship broken, so it is a swap, not a deletion** — **OUTCOME:** spotbugs-maven-plugin 4.10.3.0 + findsecbugs-plugin 1.14.0 were added to `orchestrator/pom.xml` and RUN, on this machine, against Java 25 bytecode. It works: 167 classes, 189 findings, 0 errors, 0 missing classes, and **10 `SQL_INJECTION_SPRING_JDBC`**. Then the ORIGINAL DEFECT was planted back — `"… DESC LIMIT " + limit` at `JobRunDao.findRecent`, the exact concatenation this entry's own ORIGIN paragraph is about — the reactor was recompiled, and SpotBugs was re-run. **The report is identical: 189 bugs before, 189 after; the same 10 SQL findings at the same 10 methods; `findRecent` is not among them.** Find-sec-bugs attributes the taint to the private `query(String sql, Object...)` helper every one of this DAO's queries funnels through, which was already flagged — so there is no `@SuppressFBWarnings` scheme that can bless the four `ALLOWED` sites without blessing the defect in the same breath. The homemade test, run against the same planted tree, FAILED THE BUILD and printed `JobRunDao.java:149 \`limit\``. Two further findings from the same run: FSB reports 2 FALSE POSITIVES the homemade rule correctly resolves (`CommentDao`'s ternary between two literals), and it has no equivalent of `everyAllowanceStillSuppressesSomething`. The pom and `JobRunDao` were restored from copies and `sha256` checked. **745 lines kept; the entry is now answered rather than open.** | −745 / +29 |
| **O3** · **DONE 2026-08-06.** | `engine/Dockerfile` (148) + `engine/.dockerignore` (14) + `deploy/docker-compose.yml:262-268` | A whole second ~1.5 GB image, from its own Dockerfile, for a service the file itself says "nothing in a run calls". Not needed: the fat jar already carries `BOOT-INF/lib/engine-0.1.0-SNAPSHOT.jar` and `PropertiesLauncher`, and `README.md:202-205` documents exactly this trick for the runner | Delete both files; in compose replace the engine service's `build:`/`image:` with `image: fsm:latest` plus a `PropertiesLauncher` entrypoint. Cost, stated honestly: the debug route then requires the pipeline image to exist, which for a profile in the same stack it always does — **OUTCOME:** `engine/Dockerfile` (148) and `engine/.dockerignore` (14) `git rm`'d; the compose `engine` service is `image: fsm:latest` with a `PropertiesLauncher` entrypoint. **The mechanism was demonstrated, not assumed:** the fat jar was built and the engine started out of it outside Docker — `java -Dloader.main=tech.mikhailov.fsm.engine.Engine -cp orchestrator/target/fsm-orchestrator.jar org.springframework.boot.loader.launch.PropertiesLauncher` printed `[engine] fsm-engine 0.1.0-SNAPSHOT listening` and answered `GET /health` **200** `{"ok":true,"service":"fsm-engine",…}`; `docker compose --profile engine config` then resolved the service correctly. **One thing the entry's one-line prescription misses and it would have been silent:** the pipeline image deliberately declares NO locale (that refusal is on the JUSTIFIED list), so its ENTRYPOINT sets `LC_ALL=C.UTF-8` for its own process — an overriding entrypoint that dropped it would mojibake the stage log lines `NodeRoutes` returns in its reply, which are the whole reason this service exists. The entrypoint carries it, plus a healthcheck, because the image's own asks the orchestrator's `/healthz` on 8085. Not verified: no image was built. Three stale references fixed with it — `DeploymentTest:657`, `pipeline/.dockerignore:8`, `playwright/Dockerfile:109`. **−162/+37.** | −162, −8/+4 |
| **O4** · **PARTLY — and the two halves are different problems.** | `pipeline/Dockerfile:57-68,70-75,181-194,212-217`; `engine/Dockerfile:32-43,45-50,106-117`; `orchestrator/playwright/Dockerfile:59-70,72-77` | The JDK+Maven provisioning block is copy-pasted **five times**, and every copy fetches `https://api.adoptium.net/v3/binary/latest/<N>/ga/…` — an unpinned moving target, no checksum, no signature. Two images meant to run the same bytes, built a week apart, get different 25.0.x | One `FROM debian:bookworm-slim AS jdk` stage, `COPY --from=jdk`; pin the Adoptium release instead of `latest` — **OUTCOME:** **The tidiness half is now WRONG as prescribed.** "One `FROM debian:bookworm-slim AS jdk` stage, `COPY --from=jdk`" cannot be done: a stage is file-local and these are three Dockerfiles in three build CONTEXTS, and the two blocks inside `pipeline/Dockerfile` fetch different things (one JDK in the build stage, five majors in the runtime stage, because the prover re-runs a build under a different major). The copies that could go went with O3 — **five down to three**. **The supply-chain half is the real finding and it is an ADDITION, not a deletion,** so it is written down at the code instead of half-done: both fetch sites in `pipeline/Dockerfile` and the one in `playwright/Dockerfile` now say plainly that the image is not reproducible, give the exact pinned URL (`/v3/binary/version/jdk-25.0.4+7/…`, verified against the live API this pass) and the checksum endpoint that goes with it, and name the reason nobody has taken it: five majors × two architectures is **ten release names and ten hashes** to bump on every Adoptium release, and a stale pin is a JDK nobody is patching. `latest` is the opposite trade, currently taken by default rather than on purpose. `JDK_VERSION` cannot carry the pin either way — it is also the `/opt/jdk/<major>` directory `Build.javaHome` resolves and `DeploymentTest` pins at 25. **+19.** | ~−60 |
| **O5** · **DONE 2026-08-06.** | `README.md:304-345` vs `DOCKER.md:259-316` | The operational surface is 3.5–4.6 lines of prose per line of configuration, and the same five arguments are restated in 6–9 files each. The concrete cut: "Things that cost us time" duplicates "Things that will bite" item for item — same H2-volume paragraph, same `QWEN_BASE_URL`, same external-network, same mirror, same uid-10002 | Delete `README.md:304-345`; replace with one line pointing at `DOCKER.md`. Keep the argument where the SETTING lives — a README is not where a knob is defined — **OUTCOME:** `README.md`'s "Things that cost us time" deleted and replaced with a pointer to `DOCKER.md`'s "Things that will bite". **Not deleted as prescribed, and the difference matters:** five of the six items were duplicates, but `infra_error` is not a verdict has no home in `DOCKER.md` and is not an operational trap at all — it is a property of the output. It is kept, under its own heading, and the pointer says what left and why. One more thing the prescription would have broken: `README.md:312` said "the volume argument is still the one below", pointing INTO the deleted section; retargeted. **−46/+29.** | −42 |
| **O6** · **PARTLY.** | `engine/Http.java:11-41`, `runner/Api.java:12-25` | **Measured 1.73:1 and 2.22:1.** ~90 lines of comment over ~50 of code, and most of it is the *origin story* of a deduplication that has already happened ("the runner's said so in its own class comment…"). A reader needs the invariant — one cap, one reader, one writer; `withBody` is a parameter because the two services genuinely differ on HEAD — about 4 lines | Cut both headers to the invariant; move the history to the commit message. (**One reader called this header stale on the grounds that `HttpTransport:265` is "a third `readCapped`" — dropped, see below**) — **OUTCOME:** The ~50-line cut is refused: on re-reading, `Http.java`'s remaining paragraphs are invariants rather than history — why it is not in `lib` (that package's I/O-free claim is what the differential harness leans on), and what is deliberately NOT shared (the two services' wire contracts). The origin story is the one part that is history, and it names a specific recurring hazard with its cost ("the engine's `Http` was package-private" → two independently drifting body caps), which is worth pre-empting in a repo where the duplicated-logic defect has shipped six times. What WAS duplication is that `Api.java` told the same origin story a second time, in full. Cut to a pointer. **−5/+4.** | ~−50 |
| **O7** · **KEPT.** | `nodes/Verdict.java:459-490` | The `Settled` sealed interface with `Arrived(Object)` / `Concluded(SuspicionStatus)` and a `wire()` on each: 32 lines to model one nullable field. Its only consumer, `nextSuspicionStatus:846-850`, immediately destructures it back into that pair | Carry `Object arrivedState` and a nullable `SuspicionStatus concludedStatus` on `Argument`. **Honest caveat:** the sealed switch at `:847-850` is exhaustive and the nullable form is not, so this trades 26 lines for a compile-time check. Owner's call — **OUTCOME:** The entry states its own caveat and the caveat decides it: the sealed switch at `:847-850` is exhaustive and the nullable pair is not, so this trades 26 lines for a compile-time check. The JUSTIFIED list two sections down defends `Verdict.java:966-1005` — exhaustive switches with no default arm — as "the one place the 'a ninth state must not fall through to its nearest neighbour' argument is enforced by the compiler rather than asserted in prose". Removing one of them to save 26 lines fails the bar for this section: it leaves the codebase worse defended. | −32 / +6 |
| **O8** · **DONE 2026-08-06, and the entry is materially WRONG about which constructor.** | `nodes/Verdict.java:166-197` | Three telescoping `Request` constructors (12→13→14 args) with 24 lines of javadoc arguing that a default must be reachable positionally. Only `ProveChain:267` and three tests construct one | Keep the full constructor; add one static factory, or have `ProveChain` pass `DEFAULT_PROMPT` explicitly. (`FixSkeptic:130-143` has the same pattern at half the size — leave it, it is one delegation not two) — **OUTCOME:** It says "only `ProveChain:267` and three tests construct one". `Request.of(body)` — the HTTP surface, production code, in the same file — uses the THIRTEEN-argument one, so the middle constructor is not ceremony and stays. The TWELVE-argument one is: its only caller in the whole reactor was `VerdictTest:1194`, the assertion that it defaults `verdictEnabled` to true. A constructor whose one caller is the test that it exists to satisfy. Deleted, with the argument for it — a new record component is silently `false` at every call site that predates it, and `false` here retires every non-reproduction unargued — rewritten onto `Request.of`, where the default that actually reaches a marker lives and where three assertions pin it. The test's own comment was a third lie: it said "the twelve-argument constructor every existing caller uses". **−17/+13.** | −32 |
| **O9** · **LEFT, deliberately rather than half-done.** | `batch/ProvenMarker.java` (whole file) | An 8-component record whose `of()` spreads one `Settlement` across 8 fields and whose `settlement()` reassembles the identical `Settlement`. Its own javadoc calls this "a round trip and not a second reading" — which is the argument for not having the type. The only thing it adds is `dedupKey()` for one log line, and `Settlement.id()` already carries that. (**Corrected:** one reader claimed the round trip launders status through `wire()`/`of()` and can return null — it cannot; `settlement()` parses back exactly the string `of()` wrote) | Make the step `<Suspicion, Settlement>` in `BatchConfig:181`; `ProveProcessor` returns `settled.settlement()`; `ProveWriter` takes `Chunk<? extends Settlement>`; `ClaimReleaseListener` retypes. Delete the file — it also deletes W58's duplicate downcast — **OUTCOME:** Reads correctly at the code and the −61 is real: `of()` spreads one `Settlement` across eight components and `settlement()` reassembles the identical value. But it is `BatchConfig`'s two type parameters, `ProveProcessor`'s return type, `ProveWriter`'s `Chunk`, `ClaimReleaseListener`'s `SkipListener` and `onSkipInWrite`, six call sites in a 450-line test, and a home for the `dedupKey` paragraph (which is about settling on the CLAIMED key and is load-bearing). Eight files of behaviour-neutral refactor across the framework's transaction boundary is the kind of change whose only honest form is its own pass with a test in front of it — the same call the second pass made on `W47` and `W55`. O21 is covered by it and is left with it. | −61 |
| **O10** · **KEPT — and the entry's own edit says so ("Nothing on day one").** | `usecase/SettlementPresenter.java` (29) + `usecase/ReleasePresenter.java` (21) | 50 lines of interface plus a record for three `log.info` calls. This is the weakest instance of a pattern that otherwise earns its keep (see JUSTIFIED): unlike `ProveMarkerPresenter`, neither is what makes its interactor testable — both `RecordProvenMarker` and `ReleaseClaim` are already pure functions of a repository's int return | Nothing on day one. If the slice is ever trimmed, fold both into `ProveMarkerPresenter` as two more methods — **OUTCOME:** One reason to add, sharper than the entry's: `orch.usecase` is an inner circle, and `TheInnerCirclesDependOnNoFrameworkTest` puts `org.slf4j` on `BANNED_ANYWHERE`. So these interfaces are not a stylistic choice about where three `log.info`s live — they are the only legal way for a use case in this slice to report anything at all. Folding them into `ProveMarkerPresenter` is a rename, not a deletion: the same methods, one fewer file, and three unrelated use cases coupled to one interface. | −50 / +6 |
| **O11** · **KEPT — the entry is WRONG on its merits.** | `lib/AnchorStatus.java:1-52` | **Measured 2.14:1** — 30 lines of comment over 14 of code for an enum with no `of(String)`, whose own javadoc says "Nothing in Java branches on this today". Used at four sites purely as `.wire()`. The real consumer is dashboard JavaScript, which this type cannot constrain | Keep the enum (four spellings in one place is worth it); cut the essay to three lines — **OUTCOME:** Checked at the code and at the consumer. Every claim in that javadoc is still true: `ParseMarkers:433` writes `PENDING` and `BuildReproduceInput:187/206/214` overwrites it with the other three, so one column really is written by two nodes that never meet; and `app.js:118-123` really does key a colour and a tooltip off exactly these four spellings with a grey, EMPTY-tooltip fallback for anything else. The case for cutting is a ratio of 2.14:1 — and this registry's own Measurements section is the thing that demolishes it: "the ratio is not the signal. The signal is whether the comment is still true." Cutting the essay to three lines deletes the record of what a fifth spelling costs and the answer to the first question a reader asks (why is there no `of(String)`). | −20 |
| **O12** · **DONE 2026-08-06.** | `lib/Json.java:217-238` | `Json.str(Object)` is `return Values.text(v);` wrapped in 22 lines of javadoc explaining an `x \|\| ''` defect that was removed. It is a rename with a history attached — **and it is what makes W11, W12 and W13 invisible today**: three files reason about "`Json.str` vs `Values.text`" as if they were different functions | Keep the two-arg form (the field read, used everywhere); delete the one-arg form in favour of `Values.text`; move the historical paragraph to `Values.text` where the rule lives — **OUTCOME:** `Json.str(Object)` deleted; its eleven call sites now call `Values.text` directly (8 in `RecordOutcome`, 1 each in `ParseTest`, `Verdict`, `WireSafetyTest`). The two-argument field read stays and is the reason the name exists. The 22-line javadoc was not moved, because `Values.text` already tells the same `x || ''` story at more length and better — which IS the finding: two public names for one function, each with its own account of the same defect, is how three separate readers came to describe "`Json.str` versus `Values.text`" as though the answer could differ between them. What replaced it says that in five lines on the surviving overload. **−18/+11.** | −14 |
| **O13** · **DONE 2026-08-06.** | `usecase/ProveMarkerRequest.java` (whole file) | A one-component record wrapping a `Marker`, justified by "the boundary is where the next thing this use case needs will arrive — a run id, a deadline". Speculative generality, in the repo that argues against it everywhere else. (**Corrected:** one reader called the null check unreachable via `Marker`'s own constructor; it is not — the record can be constructed with null directly. The overengineering call stands on its own) | `ProveMarker.prove(Marker)`; delete the file; fix 1 production and 6 test sites — **OUTCOME:** `ProveMarkerRequest` deleted; `ProveMarker.prove(Marker)` takes the marker, keeps the null check, and carries the reason the record was there. One production site (`ProveProcessor:136`) and three tests, not six. The justification was explicitly speculative ("the next thing this use case needs will arrive — a run id, a deadline") and nothing had arrived; introducing the record on the day something does is a compile error at every driver, which is the entire benefit it was supposed to buy, available when it is actually needed. **−19/+13.** | −19 |
| **O14** · **KEPT.** | `domain/ProverNote.java` (24 lines) | A final class, a private constructor and a private constant, so that `"[prover] " + text` has a type. Two call sites: `Requeue.java:38` and `InfraStreak.java:45` (**not `:122` as reported**). The stated reason — three documented recovery queries match by `LIKE '[…]%'`, so the notes must not drift — is real, and is served by one shared constant | `public static final String PREFIX = "[prover] ";` on `Requeue`; delete the file — **OUTCOME:** The prescribed edit trades one problem for another. `ProverNote` is 24 lines of which ~10 are the javadoc that has to exist wherever the prefix lives — three documented recovery queries match `LIKE '[…]%'`, so the label must lead and must not drift. Moving the constant onto `Requeue` saves about ten lines and makes `InfraStreak` depend on `Requeue` for a string, which is a lateral coupling between two unrelated domain concepts where there is currently a named one. `ProverNote.of(text)` at two call sites also reads better than `Requeue.PREFIX + text`. Deleting a named concept to save ten lines and gain an edge is not "easier to work in". | −20 |
| **O15** · **DONE 2026-08-06, in the form the entry's own alternative gives.** | `nodes/Verdict.java:284-299` | The `Attempts` record: 16 lines wrapping one double so `recorded()` and `current()` can be named. Verified: `current()` is called once (`:653`), `recorded()` once (`:854`). The stated justification ("the two reads used to sit 152 lines apart") was fixed by the `Row` parse, not by the wrapper | One `double attempts` on `Row` plus a `static double currentAttempt(double)`, or inline the ternary at `:653` — **OUTCOME:** The `Attempts` record is gone; `Verdict.Row` carries a bare `double attempts` and a `private static double currentAttempt(double)` holds the `|| 1`. The NAME is the whole value here and it is kept — inlining the ternary at `:653` would have deleted the distinction between "which attempt this IS" and "the count AS RECORDED", and confusing those two swaps a retry for a permanent retirement. What is deleted is sixteen lines giving one double two accessors, one of which returned the component. **−16/+17 in the type, −5/+3 at the two call sites; net −1 line and one fewer nested type in a file with fourteen.** | −12 |
| **O16** · **DONE 2026-08-06.** | `web/WorkModel.java:96-110` | 14 lines of javadoc over `public static final Set<SuspicionStatus> UNSETTLED = SuspicionStatus.UNSETTLED;` — a pure re-export. Referenced from one place outside the file (`CountsMustAddUpTest:93,130`) and one inside (`:312`). The javadoc's own defence — "a Set that is re-exported rather than re-spelled cannot drift" — is true of the constant it aliases | Delete the field; point the test and `:312` at `SuspicionStatus.UNSETTLED` — **OUTCOME:** `WorkModel.UNSETTLED` deleted; `WorkModel:312` reads `SuspicionStatus.UNSETTLED` (the same object — this was a pure re-export, so the change is provably behaviour-identical), and the two `{@link}`s in `CountsMustAddUpTest` plus the comment at `DashboardService:191` point at the enum. The javadoc's own defence — "a Set that is re-exported rather than re-spelled cannot drift from the vocabulary it belongs to" — is equally true of not re-exporting it, and the argument that IS load-bearing (why `proving` counts as unsettled, and what charging it would do to the ETA) moved to the one place that reads it. **−18/+10.** | −16 |
| **O17** · **WRONG as filed — already done.** | `client/HttpRunnerClient.java:116-133`, `GithubSourceClient.java:111-129`, `HttpLlmClient.java:89-97`, `SuspicionReader.java:188-191`, `ProveProcessor.java:116-134` | Fifteen accessors whose entire purpose is to be asserted, eleven carrying the same sentence ("exposed so a test can prove the knob reached this object"). All fifteen are called from tests, so none is dead — but the assertion they buy is bought twice over by `ClientContractTest` and `FsmPropertiesTest` | Keep the accessors — `TheProverIsChosenByConfigurationTest` and `TheSourceFetchIsChosenByConfigurationTest` use them to prove which bean the container picked, and no behavioural test does that cheaply. Collapse the eleven duplicate paragraphs to one-liners — **OUTCOME:** The tree has FIVE occurrences of "exposed so a test can prove the knob reached this object", not eleven, and every one is a ONE-LINE javadoc on one accessor; the repeats within a class are already `@see` pointers. There is no −34 of duplicated paragraphs to take. The five sentences stay: each is the answer to "why does this getter exist when nothing in main calls it", and deleting them is an invitation to delete the getters, which the entry itself says to keep. | −34 comment |
| **O18** · **DONE 2026-08-06, and one line wider than filed.** | `runner/Runner.java:42`, `LocalRunner.java:86`, `Preflight.java:183` | The cache directory is created three times on the way up (a fourth at `MavenSettings.java:63`). Only `Preflight`'s matters, because it is the one followed by a real write probe — `Preflight.java:167` itself explains that a bare `createDirectories` on an existing directory proves nothing | Delete `Runner.java:42` and `LocalRunner.java:86` — **OUTCOME:** `Runner.java:42` and `LocalRunner.java:86` deleted. Confirmed first that `Preflight.check` runs from LocalRunner's CONSTRUCTOR — so every construction path including the injectable test seam reaches it — and that `Preflight.cacheIsWritable` does its own `createDirectories` followed by a real write probe. That left `LocalRunner.ensureCache` with no caller in main or test in any of the three modules, so it went too, and the two javadocs that named it (`Preflight:167`, `AProverThatCannotUseItsCacheMustSaySoOnceTest:289`) were rewritten rather than repointed — `-Xdoclint:reference` is on all three modules since W13 and would have failed the build on a dangling `{@link}`, which is exactly the check working. Failure messages get better, not worse: the bare `UncheckedIOException` is replaced by Preflight's `refuse(...)`, which names the mount, the ownership and the fix. **−12/+7.** | −2 |
| **O19** · **DONE 2026-08-06.** | `orchestrator/pom.xml:42-43` | `<engine.version>` and `<runner.version>` whose only values are the literal `0.1.0-SNAPSHOT` that `${project.version}` already is, in a reactor where both modules are always built alongside. Two names that exist to be named | Inline them into the two `<dependency>` blocks — **OUTCOME:** `<engine.version>` and `<runner.version>` inlined to `${project.version}` with one line saying why a separate property could only ever be a second name for it. **`runner/pom.xml:50` has the same property and the entry does not mention it** — left alone this pass rather than swept in, because `runner` declares a zero-third-party-dependency policy that its pom argues at length and that pom deserves reading before it is edited. **−4/+5** (the explanatory line costs more than the properties saved, and is worth it). | −2 |
| **O20** · **DONE 2026-08-06 — the javadoc, not the ArchUnit swap.** | `TheInnerCirclesDependOnNoFrameworkTest.java:25-28` vs `:105-114` | The javadoc promises a whitelist and the code ships a blacklist for the case that matters. It argues "A banned list only catches the frameworks somebody thought of. This asserts what these two packages ARE allowed to see" — but `ALLOWED_IMPORTS` is applied only to lines `importsOf()` returns, i.e. lines starting with `import`. Fully-qualified references in method bodies are checked against `BANNED_ANYWHERE`, a ten-entry hand-written blacklist. So `com.google.common.collect.ImmutableList.of(…)` written out in full inside a domain class passes both checks — and the javadoc at `:45-49` names that exact case as the *reason* to read sources rather than bytecode, when bytecode's constant pool is precisely where such a reference IS visible | Either state the real guarantee (whitelist on imports, blacklist on FQNs), or replace the class with ~15 lines of ArchUnit — **OUTCOME:** The entry is exactly right about the code: `ALLOWED_IMPORTS` is applied only to lines `importsOf()` returns, and a fully-qualified reference in a method body is checked against a ten-entry hand-written blacklist, so `com.google.common.collect.ImmutableList.of(…)` written out in full inside a domain class passes both halves. The class comment now states that as two rules rather than one, names the hole, and says what ArchUnit would and would not close — because the file's other argument is also true: ArchUnit reads bytecode and cannot see an import a class does not use. The −220 is only available on the swap, and the swap is refused for the same reason as O2: it cannot be demonstrated catching what this catches. **A second stale claim was found in the same file while doing it** — the javadoc named `lib.Js` among the things `orch.domain` must not reach, and `Js` was deleted on 2026-08-05. Fixed. **−28/+34.** | −220 if replaced |
| **O21** · **LEFT with O9.** | `domain/Artifact.java` (33 lines, 19 of javadoc for `String state();`) | A single-method interface with one implementation whose dependency inversion is undone eight lines after it crosses the boundary — `ProvenMarker:39-43` casts straight back to `Bug`. Keep the interface (the domain genuinely must not see the 22 columns); it is the downcast's *placement* that is wrong | Covered by O9 + W58 — **OUTCOME:** Nothing to do on its own: the entry's own edit is "covered by O9 + W58", and O9 is left. | — |

---

## Called JUSTIFIED — and I agree

A codebase with none of these is one you have not understood. These looked wrong and are not; the
reasons are recorded at the code and I checked them. Do not "clean up" any of these.

- **`Json.java:8-46` — not using Jackson.** Load-bearing, and I watched the mechanism work.
  `JsonExtract`'s entire algorithm is "try candidate substrings, take the first that parses", which
  only discriminates because `Json.parse` rejects trailing content — `{"decision":"reject"}` plus prose
  threw `unexpected trailing content at offset 53` in my own run, which is exactly how the extractor
  knows to keep walking. A default `ObjectMapper` accepts it. (This is also why W1 is a defect and not
  a style complaint.)
- **`SourceText.java:3-34` — the hand-written whitespace set, and specifically NOT deriving it from
  `Character.isSpaceChar`.** `String.isBlank` excludes U+00A0, U+2007, U+202F and U+FEFF; a file
  GitHub returns as a lone BOM has to count as empty. Pinning the set makes a JDK upgrade a failing
  test instead of a silent change to what this pipeline calls an empty file.
- **`Values.java:110-118` — `plain()` via `BigDecimal.stripTrailingZeros().toPlainString()`.** It is
  the third segment of `dedup_key`, a marker's identity across re-ingests. `Double.toString` gives
  `1.0E21` and `3.0` where the wire carries `1` and `3`, so a change re-keys the deployed 282-row
  backlog at once and nothing goes red. The "BEFORE CHANGING THIS METHOD, READ WHAT JOINS ON IT"
  paragraph names the consequence, the blast radius and the two tests that pin it — the best comment
  in the repository.
- **`ParseFix.java:192-225` — `serialisable()`, the recursive non-finite-double scrub.** `{"old_str":
  1e400}` is well-formed JSON that parses to Infinity, `Json.stringify` refuses it deliberately, and
  this node's job is to survive a bad model reply rather than take the stage down. It is also what
  makes the *missing* equivalent on `Verdict`'s `test_score` path (W12) a real gap.
- **`MarkerState` and `SuspicionStatus` as two enums with overlapping vocabulary**, and
  `MarkerState:73-99` putting `Work` on the constructor. `infra_stuck` is a member of both and means
  something different in each; `not-a-bug` is a state that is never a status. Making the compiler
  refuse the comparison is the point. Work-as-a-mandatory-constructor-argument turns "a ninth state is
  silently absent from every `Set.of`, counted as settled, flattering the one number the project is
  judged on" into a compile error. The two identical private `derive(Work…)` helpers are unavoidable —
  `EnumSet` is invariant and the two `Work` enums are deliberately distinct types.
- **`ExecVerdict.java:121-188` and `Verdict.java:966-1005` — exhaustive switches with no default arm.**
  The one place the "a ninth state must not fall through to its nearest neighbour" argument is enforced
  by the compiler rather than asserted in prose.
- **`Csv.java:6-34` — hand-rolled and explicitly bug-compatible rather than RFC4180.** A mid-field
  quote (`a"b"c` → `abc`) is malformed by the RFC and is what real Svace exports contain. A strict
  library turns rows this pipeline has ingested for months into errors, and a column shift on the free-
  prose fifth column silently turns every File cell into a sentence and every Line into NaN.
- **`BuildReproduceInput.java:47-49` — brace matching rather than JavaParser/JDT.** Any difference in
  which method a marker anchors to moves what the model is shown; the file says plainly this is "a
  behaviour change to be made on purpose and re-baselined, not a cleanup".
- **`RecordOutcome.java:629-753` — `prText` and `infraReason` branching on the same conditions with
  deliberately opposite logic.** One is prose for a human triaging a row, one is a greppable audit
  column that `WHERE infra_reason LIKE '%never answered%'` has to find. I would add a test pinning the
  two condition sets against each other rather than merge them.
- **`Http.java:133-137` — Content-Length by hand then `sendResponseHeaders(status, -1)`.** Looks like a
  header about to be clobbered; `ExchangeImpl` special-cases `isHeadRequest()` and logs a WARNING if
  you pass a length ≥ 0. Manual header + `-1` is the only correct spelling.
- **`Http.java:57`, `Outbound.java:48`, `HttpTransport.java:57` — three separate 16 MiB constants.**
  `HttpTransport:45-56` writes the argument out properly: one caps what a server ACCEPTS FROM a caller
  and raises `BodyTooLarge` so a handler can answer 413; the other caps what a client accepts BACK FROM
  a downstream. Coupling them means raising the upload limit silently raises how much a broken model
  endpoint can push into the prover's heap. One nit: `Outbound:44`'s weaker word "mirroring" is what
  invites the wrong dedup.
- **`RunnerImageTest.java` — a test that parses the Dockerfile.** The one homemade linter to keep: no
  ArchUnit/Checkstyle/ErrorProne asserts anything about a Dockerfile, and the property is load-bearing
  and non-local (the runner's JVM must get a UTF-8 locale on the ENTRYPOINT but the IMAGE must declare
  none, or every JDK 8/11 build under test has its `file.encoding` changed). It also handles the trap
  that kills naive source-scanning tests — `commandsOnly()` strips comments so the paragraphs warning
  against `JAVA_TOOL_OPTIONS` do not fail the test forbidding it — and skips LOUDLY inside the image
  build where the file is legitimately absent.
- **`Proc.java:234-241` — `destroyTree` signals through `ProcessHandle` instead of
  `Process.destroyForcibly()`.** The `Process` form also closes the JVM's pipe ends, which on macOS
  wakes a thread blocked in `read()` so the kill *looked* like it worked while Maven kept running.
- **`Workspace.java:426-455` — the containment check runs twice, lexically then after `toRealPath()`.**
  Any repository this service clones may ship `Innocent.java -> .git/config`. The root is resolved too,
  because `/var` is a symlink to `/private/var` on the dev machines.
- **`LocalRunner.java:119-120` — a single PLATFORM thread while both servers use virtual threads.**
  This thread does nothing but wait on a child process it pins for twenty minutes at a time, which is
  the pinning case virtual threads are worst at.
- **`Workspace.java:27-31` — two clones per repository.** It is what stops the dashboard showing a
  reviewer the file with the fixer's edit already applied, when the marker view exists precisely to
  show the code that was JUDGED.
- **The three hand-rolled retry loops** (`HttpRunnerClient:195-228`, `GithubSourceClient:162-205`,
  `HttpLlmClient:162-189`). Three different questions, not one. The runner retries ONLY a connect that
  never happened, because anything past that may have started a clone plus two Maven builds in the one
  serialised workspace; the source fetch retries transport plus 429/5xx but never 401/403; the LLM
  retries a connect but explicitly not a read timeout, because the model may have spent the whole hour
  on it. Spring Retry classifies by exception type and cannot express "retry `HttpConnectTimeoutException`
  but not its supertype `HttpTimeoutException`" without a custom classifier — which is this code with a
  framework on top. All three are pinned by tests that go red when mutated.
- **`BatchConfig.java:169` binding `skip-limit` with a raw `@Value`.** `FsmProperties:239-248` states
  the rule: a knob consumed by `@Value`, `@Scheduled`, `@ConditionalOnProperty` or a guard that runs
  before the context exists cannot read a record component.
- **`BatchConfig.java:173-179` assigning one object to two interface-typed locals.**
  `StepBuilderHelper.listener(Object)` is a real overload that scans annotations, so an untyped call
  compiles, registers nothing, and leaves every infra-failed marker parked in `proving` for ever.
- **`SuspicionDao.claimNext(Suspicion)` taking a whole row as a cursor.** The queue is ordered by
  `severity DESC, dedup_key` and a cursor must be the whole sort key; without the rank half, every
  marker more severe than the cursor is re-offered on every call and the drain does not terminate.
- **`StaleExecutionReconciler.java:243-248` reaching around `JobOperator.abandon`.**
  `JobOperator.abandon` refuses anything below `STOPPING`, which is every row this class exists to
  clear, and the stop half signals a live thread that does not exist.
- **`InfraFailure` being a checked exception.** The compiler-enforced separation between "the question
  was never answered" and "the answer is bad news about the marker". Given that six defects in this
  project have had the shape "a failed call recorded as a judgement", this is the last place to trade a
  compiler check for tidiness.
- **The chunk transaction spanning a 90-minute prove with `CHUNK_SIZE = 1`.** What makes
  claim/judge/settle atomic without a compensating action, affordable only because the chunk is one and
  the step is single-flight. The `noRollback(InfraFailure.class)` is the non-obvious half.
- **`SourceReader` (45 lines of interface) with a 37-line `LocalSourceReader` that is `readFile::apply`.**
  A real seam: the source the dashboard shows must come from the checkout the prove ran in, so the
  reader and the runner client are chosen together in one place.
- **`ProveOutcome` + `RequeuedClaim` — deciding a requeue as a value then re-throwing it in the adapter.**
  Transaction semantics that cannot move: the step declares `skip`/`noRollback` on `InfraFailure`, and a
  processor returning null would make Spring Batch FILTER rather than SKIP, so no listener fires and no
  strike is counted.
- **`web/MarkerColumns.java:62-71` — copying the artifact map per row.** It is what makes the join a
  *function*, asserted without a database. The javadoc records that it used to mutate in place.
- **`web/DashboardController.java:149-172` — five endpoints answering permanently-empty documents with
  200.** The page's `jget()` turns a failed fetch into `null`, indistinguishable from "there is none" —
  which is exactly how dropping `/api/bug` made 50 proven markers render as "not proven yet".
- **`web/DashboardController.java:227-247` — binding `line` as a `String` and parsing it by hand.**
  `@RequestParam int` answers a malformed value with a 400, and `jget()` swallows a 400 into "source
  unavailable" with no reason attached. The `Double.parseDouble` first is because a line number reaches
  the page as a JSON real and comes back as `26.0`.
- **`domain/MarkerTransition.java` — a 2-constant enum with 42 lines of javadoc, most about the three
  transitions NOT in it.** The best-earning javadoc in the orchestrator: the cost it names was measured
  ("two use-case tests injected hand-written doubles through that port, neither double held a status, so
  both performed a release on an already-settled marker and reported success"), and
  `MarkerRepositoryContract` now holds both implementations to it.
- **`web/DatabaseHealth.java:41` — `SELECT COUNT(*) FROM suspicions` as a liveness probe.** `SELECT 1`
  proves the pool can hand out a connection and nothing about the schema, and the failure this codebase
  keeps meeting is a database that is open but whose schema did not apply. Tested by closing the real
  pool, not by stubbing the probe.
- **`comment/CommentKinds.java:117-137` — reading `CritiqueKind`'s constants by reflection.** The
  alternative is a second hand-copied vocabulary whose failure mode silently halves a count.
- **`comment/CommentService.java:133`, `web/LiveWatcher.java:91` — `@Autowired` on a constructor-injected
  bean.** Both classes have *two* public constructors, and two candidates with none marked fails the
  whole context at boot with "No default constructor found".
- **`usecase/MarkerRepositoryContract.java` (280 lines) run twice** by an in-memory subclass and a
  `@SpringBootTest` one against real H2 and real `schema.sql`. One contract, two subclasses supplying
  only a row and a way to read one back. The fake's javadoc records what it bought: "THIS IS THE HALF
  THAT WAS RED… the doubles this replaced returned 1 from release and park whatever state the marker
  was in". Copy the pattern the next time a port gets a second adapter.
- **`harness/WireSafetyTest.java:215-236` — the source scan refusing `put(…, SomeEnum.CONSTANT)`.** The
  one homemade linter I would not replace: ArchUnit works at type granularity and cannot see the last
  argument of a call. It is honest about its blind spot and closes it a different way — the runtime half
  drives 6,910 frozen cases plus the real 356-row CSV through ten stages, the FLOOR map fails if the
  guard ever reaches LESS of the engine, and `enumSimpleNames()` DISCOVERS the enums off `target/classes`
  rather than listing them. Its javadoc reports an actual experiment (`.wire()` deleted at all 30 call
  sites one at a time, 23 of which do not compile without it) to establish that exactly 7 sites are at
  risk. **This is why the engine harness CASES must survive any cut to the catalogues** (see below).
- **The three-way vacuity guarding across the meta-tests**, which I checked rather than assumed:
  `AGreenRunMustHaveRunEveryTestClassTest:76-79` asserts the source tree exists before comparing;
  `TheInnerCirclesDependOnNoFrameworkTest:96-100` asserts ≥2 files per package;
  `NoControllerAssemblesItsOwnResponseTest:165-168` asserts it found at least as many `@Controller`
  classes as it governs; `ThePageAndTheStateDocumentAgreeOnEveryNameTest:254-268` asserts five
  extractions total ≥30 names so one silently ceasing to match cannot hide behind the other four. Every
  one of these knows its failure mode is passing by reading nothing, and says so. That is the single
  most encouraging property of this suite.
- **`pipeline/Dockerfile:80-97` sets `ENV LANG=C.UTF-8` in the build stage and `:268-288` refuses the
  identical line in the runtime stage at 20 lines' length.** Looks like the author arguing with himself;
  it is the only correct arrangement. `sun.jnu.encoding` is what `Path` encodes filenames with, it comes
  from the process locale, and `-Dsun.jnu.encoding=UTF-8` is overwritten by the JDK — so the build stage
  needs the locale or the runner harness cannot construct its `/cache/é😀` fixture path, and the runtime
  stage must not have it as an image ENV because every third-party build under test would inherit it and
  flip `file.encoding` for JDK 8/11 targets, changing what those tests DO in a service whose job is
  answering whether a defect reproduces.
- **`orchestrator/pom.xml:177-202` declaring Playwright unconditionally** rather than inside the `ui`
  profile. The `@Tag("ui")` classes are COMPILED by every build, so a rename in `DashboardService`
  breaks the build on the developer's machine instead of in a container nobody runs before a deploy. The
  bundle is never opened unless `Playwright.create()` is called.
- **`deploy/docker-compose.yml:291-299` — every volume repeating its own key as an explicit `name:`.**
  Data-retention configuration: without it Compose derives `<project>_<key>`, so a rename of the project
  or the directory makes Compose look for a volume that does not exist, CREATE IT EMPTY, and start a
  healthy service over an empty backlog. `schema.sql` recreates the tables, so nothing errors.
- **`engine/pom.xml:155-162`, `runner/pom.xml:161-165` — an eight-line comment where an empty
  `<excludedClasses>` would be.** It records a reversed decision: excluding `Engine`/`Runner` from PIT as
  "just an entrypoint" did not spare an untestable class an unfair score, it removed an UNTESTED class
  from the denominator.
- **`orchestrator/pom.xml:339-352` — PIT `timeoutConstant` at 60000, 15× the default.** PIT counts
  `TIMED_OUT` as KILLED, so the 4000ms default would not have produced a slow run, it would have produced
  a *flattering* one. The knob is set in the direction that makes the number worse.
- **`application.yml:18-44` — 27 lines of comment on one JDBC URL.** It records a MEASURED exposure
  (`;AUTO_SERVER=TRUE` starting H2's TCP server on the IPv6 wildcard, observed on `*:50159`, reachable as
  `sa` with an empty password, read and write, in front of 282 markers and their drafted PR bodies) and
  names the class that now reads the RESOLVED url. Delete this and the next person re-adds `AUTO_SERVER`
  for the debugging convenience the comment agrees is genuine.
- **~~`engine/Dockerfile:96-97` shipping a full JDK rather than a `jre`.~~ `jcmd`, `jstack` and flight
  recorder are how a service whose requests last an hour gets diagnosed WHILE it is stuck. The reasoning
  was right and the file it was about is gone (O3, 2026-08-06): the engine profile now runs `fsm:latest`,
  which carries five full JDKs for the builds under test, so the property this defended is stronger than
  before and is nobody's decision to un-make.
- **`deploy/docker-compose.yml:24-29` + `docker-compose.override.yml.example` — refusing `external: true`
  networks in the committed file.** `network mvn-cache declared as external, but could not be found` is
  the FIRST line of `docker compose up -d`, and it arrives after a multi-minute image build that
  succeeded.

---

## Measurements that settle the owner's three suspicions

**The 2:1 comment ratio is not a repository number.** Counted across all 348 Java files:

| tree | comment | code | ratio |
|---|---|---|---|
| `engine/src/main` | 4,263 | 4,705 | 0.91 : 1 |
| `orchestrator/src/main` | 8,028 | 6,944 | 1.16 : 1 |
| `runner/src/main` | 1,659 | 1,656 | 1.00 : 1 |
| **all main** | **13,950** | **13,305** | **1.05 : 1** |
| `engine/src/test` | 3,170 | 10,321 | 0.31 : 1 |
| `orchestrator/src/test` | 9,110 | 19,501 | 0.47 : 1 |
| `runner/src/test` | 1,452 | 3,913 | 0.37 : 1 |
| **all test** | **13,732** | **33,735** | **0.41 : 1** |
| **whole repo** | **27,682** | **47,040** | **0.59 : 1** |

And it is **inversely correlated with risk.** The essays are on the code with the fewest branches:

| file | ratio |
|---|---|
| `nodes/package-info.java` | 55.0 : 1 |
| `lib/package-info.java` | 45.0 : 1 |
| `runner/Api.java` | 2.22 : 1 |
| `lib/AnchorStatus.java` | 2.14 : 1 |
| `http/Http.java` | 1.73 : 1 |
| `nodes/Verdict.java` | 1.25 : 1 |
| **`lib/Json.java`** | **0.32 : 1** |

`Json.java` — the file whose strictness three other findings depend on, and the one that would take
the whole engine down if it were wrong — is the thinnest-commented file measured. That, not the
average, is the finding. **The answer to "which is which" is: the ratio is not the signal. The signal
is whether the comment is still true**, and W11–W17 are seven places where it is not.

**The long test method names are fine, and the drift is elsewhere.** 1,669 `@Test` methods, mean name
48 chars, median 48, max 83. That reads absurd in the abstract, but the names carry the REASON
(`theAdjudicationIsSampledAtZeroBecauseVerdictKindIsACertificationAndNotProse`) and Surefire prints
`Class.method` on failure, so the failure line states the broken contract without opening the file.
153 test classes for 1,872 tests — a mean of 12 per class, only 6 with ≤1 — so the sentence-shaped
class names did **not** produce a class per assertion. The real finding here is the **14
`@DisplayName` annotations in 5 files**: that is a second convention, not a style. Delete the 14.

**No test in this suite asserts nothing.** A parse of all 1,669 test bodies looking for methods with
no assert/verify/fail call produced 8 candidates; all 8 are artefacts of brace-matching against text
blocks and escaped quotes. The real count is zero — worth stating as a measured fact given this
project's history of tests that pass while the code is dead. The two exceptions are W20 (an assertion
on a hardcoded `List.of` that cannot fail) and W40 (a `forEach` over a possibly-empty map).

---

## Owner's call — do not do these on my say-so

**1. `Verdict.java` is 1,266 lines (not 1,221) with 14 nested types.** I could not show that its size
causes a defect. Its four real defects (W11, W12, W14, W10) are all *stale comments about deleted
JavaScript*, which a smaller file would have carried just as happily. O7, O8 and O15 remove 76 lines
of type ceremony from it and are safe; splitting it is a taste question. **My advice: fix the four
lies, take the 76 lines, leave the file.**

**2. The engine golden master — the biggest number on this page, and the one I would not decide
alone.** `engine/harness/` is ~2,300 lines of Java plus 3,731 lines of committed catalogue JSON
(`input-family-expected.json` alone is 2,797) producing **ten** test methods, and by its own
documentation it no longer measures what it was built to measure: `NodeFamilyHarnessTest:22-27` says
"THIS IS NO LONGER A FIDELITY MEASUREMENT"; `DifferentialHarnessTest:55-58` says "It is a GOLDEN
MASTER… it detects change, it does not prove fidelity."

A reader measured the tax by changing one word in one prompt (`BuildFixInput.java:118`, "Source file
to fix" → "to repair"). Two tests went red: `BuildFixInputTest:80`, which names the defect in one
line, and `InputFamilyHarnessTest`, which says "the difference from the recorded reference has
changed". Re-recording moved **548 of the catalogue's 2,797 lines**, retired 61 divergence classes and
created 129. The test's own failure message orders the reviewer to "REVIEW THE DIFF — every line of it
is a behaviour this service was proven against." Nobody reviews 548 lines of 220-char-truncated string
pairs against a program that no longer exists, which is the rubber stamp `Catalogue.java:24` warns
about. Composition confirms it: of 464 classes, 354 are singletons, 118 are at `out.agent_input`
(prompt prose), and 28 differ ONLY in the `…(length)` marker because `TaggedDiff:213` truncates at 220
chars — the catalogue records that two strings differ without recording how.

**The cut, if you take it:** delete the comparison half — `Catalogue.java`, `TaggedDiff.java`,
`JsonFamilyCompare.java`, the three `*HarnessTest` classes, the answer-encoding in
`Diff`/`DiffJsonFamily`/`InputFamilyDiff` — plus the three committed catalogues and the three
`*js-results*.gz`. **≈ −4,800 lines, 6 test methods lost, zero unit-test coverage lost.**

**What must NOT be cut:** `engine/harness/fixtures/*cases*.gz`. `WireSafetyTest:252-319` drives all
6,910 of them through ten stages and is the strongest test in the engine.

**And keep the runner's harness whole.** Its catalogue is 191 lines, not 2,797; its divergences were
adjudicated cause-by-cause before the reference died; and its four invariant rules are real oracles
written a different way rather than diffs against a ghost. Its only defects are W18 (the committed
stale `report.txt`) and W40 (the vacuous loop), both of which are two-line fixes.

**3. Five source-scanning tests, ~2,000 lines of hand-written Java string-scanning.** Two of them I
would keep as they are (`WireSafetyTest`'s enum-on-the-wire scan and `RunnerImageTest`'s Dockerfile
parse — both are properties no off-the-shelf tool expresses, and both close their own blind spots).
One I would swap (O2, `NoQueryIsBuiltFromANonConstantTest`, 745 lines → find-sec-bugs). One I would
either honest up or replace with 15 lines of ArchUnit (O20). And all three copies of `stripComments`
should become one (W39). **The rule I would write down: before adding linter #5, add Semgrep or
ErrorProne to the build once and express these as rules rather than as another 745 lines of Java.**

> **ANSWERED 2026-08-06, and the swap is off.** find-sec-bugs was wired in and run rather than argued
> about. It works on this codebase — 189 findings, 10 of them `SQL_INJECTION_SPRING_JDBC` — and it does
> not see the defect: with `"… DESC LIMIT " + limit` planted back into `JobRunDao.findRecent`, its report
> is byte-for-byte what it was on the clean tree, because the taint lands on the private `query(String,
> Object...)` helper every query in that DAO goes through, which was already flagged. The homemade test
> failed the build on the same tree and named `JobRunDao.java:149 \`limit\``. The rule above survives —
> it is still right that linter #5 should be a tool and not more Java — but it is a rule about the NEXT
> one, not a reason to trade this one away. O20 was honested up rather than swapped, for the same reason:
> a replacement that cannot be shown catching what it replaces is a guard traded for a hope.

**4. `AGreenRunMustHaveRunEveryTestClassTest` in all three modules (W57)** — +120 lines of duplicated
test to close a hole that has bitten once. Cheap insurance or cargo cult, depending on how much you
trust `harness/run.sh` not to be run concurrently again. I would copy it.

---

## What I dropped, and what I added

### Dropped outright — 2

1. **"`HttpSourceReader` silently produces a source window that renders 'source unavailable' on every
   marker with nothing red."** Not reachable. `httpRunnerClient` and `httpSourceReader` are both
   `@ConditionalOnProperty(fsm.runner.mode=http)`, so they exist together; a malformed base URL throws
   out of `HttpTransport.uriOf` in the runner-client bean and **the whole application context refuses
   to start**. Nothing is silent. What survives is the plain duplication (four spellings of
   trim-trailing-slash), demoted into W52.

2. **"`Http.java`'s header has already gone stale: it claims 'both HTTP surfaces of this deployment'
   while `HttpTransport.java:265` is a third `readCapped`."** The claim is correct as written.
   `Http.java` is scoped explicitly to *serving* — reading a request, writing a JSON reply — and the two
   surfaces are the engine and runner servers. `HttpTransport` is an outbound *client* in another
   module, and the engine's own outbound client (`Outbound.java:127`) does call `Http.readCapped`.
   Notably, a different reader filed the separate cap as JUSTIFIED and was right. The header is long
   (O6) but not false.

### Materially corrected — 6

3. **`Judgement.of` "the type whose stated purpose is to make an unroutable status unrepresentable
   accepts `proving`."** The javadoc refuses "a status nothing in the pipeline claims", and `proving`
   *is* claimed — `SuspicionStatus:24-27` names it as `SuspicionDao`'s queue token. There is no lie,
   and nothing in the engine writes it. Demoted to cheap hardening (W48).
4. **`ProvenMarker` "the round trip launders the status through `wire()`/`of()`, and `of()` returns null
   for anything the two ever stop agreeing on."** It cannot: `settlement()` parses back exactly the
   string `of()` wrote via `status().wire()`. The redundancy survives (O9); the harm does not.
5. **"`Values.java:170-177`'s census is wrong."** It is not — `Values.numberOr` genuinely is used for
   both `svace_line` and `prove_attempts` (`BuildReproduceInput:184`, `PrepProver:254/256/262`). The
   real finding is that `prove_attempts` has a **second, unguarded** reader at `RecordOutcome:180`.
   Rewritten as W9.
6. **`RunnerServer.start` "is also the only place that reads `System.getenv(MIRROR_ENV)`, so that mirror
   wiring is dead too."** `Runner.java:38` reads it live. The method is dead; the wiring is not (W54a).
7. **`Edit.stripLeadingSlashes` "duplicated byte-for-byte, with the same javadoc."** The code is
   identical; the javadocs differ by one clause (`Workspace`'s adds "an absolute-looking path means
   repo-relative"). Finding stands, description corrected (W54c).
8. **`ProveMarkerRequest`'s "null check that `Marker`'s own constructor already makes unreachable."** It
   does not — the record can be constructed with a null marker directly. The overengineering call stands
   on its own without that argument (O13).

### Wrong locations — 3

- `ProverNote` is called from `InfraStreak.java:45`, **not `:122`**.
- `Verdict.java` is **1,266** lines, not 1,221.
- `engine/.dockerignore` is **14** lines, not 13. (Both it and `engine/Dockerfile` were deleted by O3
  on 2026-08-06; the count is what the deletion was measured against.)

### Added by me — 8

1. **`JsonExtract` pass 2 allocates 16.4 GB, not 2 GB.** Re-measured on a realistic 677 KB reply with
   48,001 braces: `16,372,243,520` bytes in one `extractJson` call. The growth is quadratic, so the
   number a reader quotes depends entirely on their payload; the shape is the finding, and it is worse
   than reported.
2. **`scoreText` and `Json.str` also disagree at |d| ≥ 1e15**, not only at NaN/Infinity:
   `scoreText(1e21)` = `"1.0E21"`, `Json.str(1e21)` = `"1000000000000000000000"`. Anyone applying W12
   should know the edit *moves a value* — in the direction `Values.plain`'s "no exponent, ever" rule
   already mandates, but it is not the no-op the finding implied.
3. **The `markerIdGiven` javadoc's own counterexample refutes it.** It splits the components because
   "`Boolean([])` is true and `String([])` is `\"\"`" — but `Values.text(List.of())` is `"[]"`, so under
   this coercion the empty array is the one case where the two still *agree*. The split cannot be
   justified even on its own terms (W14).
4. **`SourceText.stripSpace` has exactly one caller in the entire repository** (verified across all
   three modules), so W22's edit deletes a public `lib` method rather than orphaning it. I also verified
   the no-op empirically including the padding-split-across-a-newline case the comment names as its
   reason for existing.
5. **`SourceClient`'s broken retry contract is worse in `http` mode than reported.** `CheckoutSourceClient`
   wraps `HttpSourceReader` when `fsm.runner.mode=http`, so the no-retry path is a real network call. The
   "an in-process read has nothing transient to retry" defence covers only the `local` half (W32c).
6. **`Values.numberOr`'s guard also rejects `"1f"`** (measured 0.0) where `Json.num` accepts 1.0 — one
   more spelling in the W9 divergence than the guard's own javadoc enumerates.
7. **`engine/harness/fixtures/input-family-expected.json` has a working-tree mtime of Aug 6 07:07 while
   `git status` is clean.** The golden master is being rewritten in place by test runs and happening to
   match. Harmless today, and exactly the condition under which a re-record lands unnoticed — one more
   argument for the owner's call above.
8. **`Http.java`'s three-cap arrangement is right and the reader who called it JUSTIFIED beat the reader
   who called it stale.** Recorded here because two readers disagreed and the disagreement is the kind of
   thing that gets "resolved" by whoever edits the file next.

---

*Registry compiled from six independent reads, verified at the code at HEAD `3e728a5`. Behavioural
claims marked **RUN** were reproduced against `engine/target/classes` in this session. No deployment,
no commit, no remote access.*
