# 02. The chain: fifteen agents in a fixed order

A **prove** is one process, one **marker**, one settlement. A marker is a line of the form
`repo|file|line|checker` — the analyser's claim that a defect sits at that file and line. Inside a
prove, fifteen language-model agents run in an order written in Java, in `Prove.run()`, top to
bottom.

They are five **triples**, one per stage. In each of them a **planner** decides how the stage should
be approached and writes that down before anything is made; a **doer** makes it, with the plan in
front of it; a **verifier** judges the work and may send the fault back to either one. No agent
decides who runs next.

```java
static final java.util.List<String> CHAIN = java.util.List.of(
        "reproduce-planner", "reproduce-doer", "reproduce-verifier",
        "fix-planner", "fix-doer", "fix-verifier",
        "propose-planner", "propose-doer", "propose-verifier",
        "argue-planner", "argue-doer", "argue-verifier",
        "price-planner", "price-doer", "price-verifier");
```

**That list is the single copy of the order.** It is read by the prompt editor, by the marker tabs
and by the built-in-prompt collector. Three copies of an order drift and the drift is invisible: the
tabs were once missing `argue-verifier` entirely, so an agent that can send a settlement back for
rework had no page of its own and nobody noticed.

`CHAIN` is the first fifteen of `Agents.ORDER`, which is `CHAIN` then `WATCH`
(`overwatch-planner`, `overwatch-doer`, `overwatch-verifier`, `interpreter-planner`,
`interpreter-doer`, `interpreter-verifier`) then `ASKED` (`chat`) — twenty-two in all, the order a
reader meets them in, and the order the prompts page is sorted into. The other seven agents watch a
run rather than run inside one and are not part of a prove; the two watches are triples for the same
reason the chain is, and chapters 08 and 09 have them.

The list is a reading order, not a claim that all fifteen run. The `argue` triple and the `propose`
triple are alternatives — a prove reaches **at most one** of them, and several paths (`unprovable`
from a test that never built, either `needs-review`, either `reproduced`) reach neither. The `price`
triple runs on every path. So **at most twelve of the fifteen are asked in one prove**, and a marker
whose test never built asks five: two of the reproduce triple, then the three that price it.

**A record written before the triples carries the old agent names**, and the marker page appends any
agent it does not recognise rather than dropping it, so a historical lane still renders every stage
it ran. The doers were `reproducer`, `fixer`, `pr-maker`, `verdict` and `estimator`; the verifiers
were `proof-critic`, `fix-skeptic`, `pr-curator`, `verdict-critic` and `estimator-critic`; there was
no planner. The names in this chapter are the ones that run now, and a trace naming a producer and a
critic where this says a doer and a verifier is an old trace rather than a chain doing something else.

---

## The five triples, and what each triple guards

**Why a planner at all.** A verifier's complaint used to go back to the agent that had just failed to
satisfy it, which works when the fault is in the DOING and not when it is in the APPROACH. Thirty-three
DM_DEFAULT_ENCODING markers never produced a build because every writer reasoned its way to "the
default charset is fixed at JVM start-up, so no test can vary it" — true, and the conclusion does not
follow, and telling that agent its test was wrong produced the same test in different words. That is a
planning failure, and there was nobody in the chain whose job was the plan.

Every stage is `<stage>-planner`, `<stage>-doer`, `<stage>-verifier`, in that order. The planner's
answer is prose the doer works from, headed `The plan you are working from:` wherever it is handed
over; it is never parsed for a word.

| # | Stage | Doer | The doer's tools | Verifier | The verifier's allowed words |
|---|-------|------|-------|--------|--------------------------------------|
| 1 | reproduce | `reproduce-doer` — writes ONE JUnit test that must fail because of the defect | `list_dir`, `read_file`, `write_file`, `grep`, `glob`, `run_test` | `reproduce-verifier` | `reducible` \| `necessary` \| `replan` |
| 2 | fix | `fix-doer` — patches the defect, minimally | `list_dir`, `read_file`, `edit_file`, `grep`, `glob`, `run_test` | `fix-verifier` | `sound` \| `over-fit` \| `regression-risk` |
| 3 | propose | `propose-doer` — decides whether the patch goes to a stranger's repository (`make` \| `reject`) | read-only | `propose-verifier` | `sound` \| `redo` \| `replan` |
| 4 | argue | `argue-doer` — argues what execution could not settle (`false-positive` \| `by-design` \| `unprovable`) | read-only | `argue-verifier` | `sound` \| `redo` \| `replan` |
| 5 | price | `price-doer` — prices the marker in developer minutes (`minutes: N`) | read-only | `price-verifier` | `sound` \| `redo` \| `replan` |

"Read-only" is exactly `list_dir`, `read_file`, `grep`, `glob` — the same four to every planner and
every verifier, and the same four the two writing doers get on top of their one write tool and
`run_test`. The allowed words are what the chain will read out of the reply; the agents are *asked* to
lead with them, but the reader does not require it (see *How a word is read*).

**One of the five cannot replan at all, and three more are not yet asked to.** `fix-verifier`'s three
words are about the patch and none of them is `replan`, so `rejects()` reads only those three and the
fix stage's loopback ends at the doer — the fix-planner is asked exactly once per prove, whatever the
patch does. Rows 3 to 5 go through `planned()`, which reads `replan` out of every verifier it calls,
and none of those three prompts yet names the word: the loopback is reachable there and not invited.
`reproduce-verifier` is the one whose prompt spells all three out and explains the difference between
them, which is the difference between a fault in the writing and a fault in the approach.

**A doer may write; a planner and a verifier may only read.** The reproduce-doer gets `write_file`
and NOT `edit_file` — a reproduce-doer that can edit source can make its own test pass, which is the
one thing the whole program exists to prevent. The fix-doer gets `edit_file` and NOT `write_file` —
creating a new file is not patching a defect. A verifier gets neither, because a certification that
can edit its subject certifies nothing, and a planner gets neither for the same reason one step
earlier: a plan that can edit its subject is a plan that can arrange for itself to be satisfiable.

**Both writing doers get `run_test`; no planner and no verifier does.** The rule that protects is that
a certification must not manufacture the evidence it certifies — not that a doer should work blind.
What a doer learns from its own `run_test` (phase `check`) is **feedback, not evidence**: it goes
through `Runner` directly rather than through `built()`, so it never enters the build ledger, never
appears in `whatExecutionProduced()` and never sets the RED/GREEN flags. The RED and GREEN that count
are the ones the chain runs between stages.

`grep` and `glob` go to every agent, planners and verifiers included. A model asking for a tool that
does not exist does not degrade gracefully — the runtime treats the unknown name as a hallucination and throws,
ending the prove. Two markers were lost that way: one to `grep` before this program had one, one to
`glob` after a prompt sentence was written to talk a model out of wanting it. Prompt text does not
win that argument; a twenty-line tool does.

---

## The order that executes

`REASK = 1`. Every hand-written loop below runs at most once — the verifier loopbacks, the rewrite
loops and the compile-retry loops alike. **The budget is per loop, not per prove** — a reproduce-doer
can legitimately be asked several times in one prove, once by each loop that reaches it.

**Two stages are written out here and three go through one helper.** `reproduce` and `fix` are spelt
out in `run()`, because a build runs between their doer and their verifier and nothing generic can
express that. `propose`, `argue` and `price` are three calls to `planned()`, which asks the planner,
then the doer, then the verifier, and lets the verifier reach either — described in full under *The
generic triple*. `planned()` does not read `REASK`: it carries its own bound of two turns, at most one
`replan` and at most one `redo`.

### 0. The brief

Assembled by the chain, not fetched by an agent, and re-sent in full on every call to every agent in
the prove. It is one string, concatenated in this order:

```java
brief = "Marker: " + marker
      + "\nThe checkout is your workspace; read further only if you need to.\n\n"
      + Checkers.note(checkout, marker, checkerOf(marker), fileOf(marker), lineOf(marker))
      + aTestThisBuildCannotRun(marker)          // empty unless the flagged file is a test
      + "The flagged file, " + fileOf(marker) + ":\n" + source(checkout, marker)
      + siblingTests(checkout, marker);          // at most 2, in full
```

`source()` numbers every line and puts `>> ` on the flagged one, and appends a paragraph when the
marker points past the end of the file (the analyser ran against an older revision). Chapter 04 has
the parts in full; what matters here is that **the chain builds it before the first agent is called
and every later call restates it**.

**Handed over, not fetched:** a runtime caps one agent at 25 sequential tool calls, and fetching a
file the caller already holds can spend most of them. File tools are for reading what nobody
anticipated.

### 1. `reproduce-planner` → a plan, then `reproduce-doer` → a file, or a decline

```
trace.progress: "reproduce-planner: deciding how to observe it"
brief = brief + "\n\nThe plan you are working from:\n" + plan
trace.progress: "reproduce-doer: writing a failing test"
```

**The plan is appended to the brief, not passed beside it.** Every later call in this stage restates
the brief, so the plan travels with the compile retries and the rewrites for free — a doer that loses
the plan on its second turn is a doer rewording its own first answer, which is the loop this stage had
before it had a planner.

**Each progress note fires before the agent it names**, because the note is what the table shows while
that agent is thinking. Announcing the doer before the planner had been asked put a stage's own order
backwards on the one column most people read.

The planner is asked once here, and once more only if the verifier says `replan` in stage 3 below. It
may answer that the thing cannot be done — `no test is possible, because …` is a plan, and its prompt
says so in those words, because a plan invented to have something to write costs a build and teaches
nothing. What that plan becomes is still the doer's answer to give: the decline token is read off the
doer's reply and never off the plan.

**Nothing is built until there is a test class to build.** The gate is `testClass(trace, reply)`
being non-blank; the build used to run first, so a reproduce-doer that wrote nothing cost two Maven
invocations before anyone looked: 78 of 153 builds in a 67-marker run executed no test at all.

The test class is taken from **what the reproduce-doer wrote, not from what it said**. `JsonlTrace`
watches `write_file` arguments (through `onToolInvocation`, the one thing that listener is still kept
for) and remembers the last `path` argument containing `src/test/`, `src/it/` or `src/integrationTest/`
and ending in `Test.java`; the class name is that file's basename minus `.java`. Only when nothing
was written does the chain fall back to scraping `([A-Z][A-Za-z0-9_]*Test)\b` from the reply, taking
the **first** match. That is a guess: prose names the harness it borrowed as readily as the test it
wrote. The file it wrote is not ambiguous, which is why the write record wins whenever there is one.

`src/it/` and `src/integrationTest/` count deliberately: rejecting them told the runner no test had
been named, and it reported `infra` for a file sitting on disk.

**The fallback is a name, not a file.** A reply that mentions `SomethingTest` and wrote nothing still
passes the gate; the build then finds no such class and comes back `infra`, which is the honest
answer and is what the next paragraphs act on. Only a reply naming nothing at all — no write, no
`…Test` in the prose — short-circuits to `argued()` without a build.

**Loopback A — no file.** While the reply is neither a decline nor a written test:

```
You wrote no test file. Either use write_file to create one, or answer with exactly
`no test` and a one-line reason. An empty answer is not a decision.
```

If still nothing: **no build is spent.** The marker goes to `argued()` — that is an argument to be
made, not a build to be spent — with exactly this task:

```java
argued(whatThisRunMade() + brief
     + "\nNo test was written for this marker. The reproduce-doer said:\n"
     + (reply.isBlank() ? "(nothing at all)" : reply));
```

`(nothing at all)` is literal: **the argue triple is told the difference between a reproduce-doer that
declined and one that said nothing**, because those are different facts about the marker.

### 2. RED — the build, before any verifier

**Loopback B — the compiler is a verifier too, and a free one.** Inside `reproduce()`, while the build
is `infra` (produced no test result at all — compile error, missing dependency, wrong JDK, timeout):

```
Your test did not build. The compiler said:
<summary>
Fix exactly that, write the file again, and end with the test class name.
```

`reproduce()` **returns the latest reply as well as the build**, as `record Attempt(String test,
Runner.Result build)`. Drop the reply and the caller's `test` string describes a file the reproduce-doer
has since replaced — a rewrite that renames the class is then built under the old name and handed to
the verifier as source that does not exist.

Note what `Attempt.test()` holds: **the reproduce-doer's reply text**, not the file's contents. That
string is what every later stage means by "the test" — it is what the reproduce-verifier reads, what goes
into `evidence`, and what `testClass()` is re-run over when the patched tree is built.

Still `infra` after the re-ask → `priced("unprovable", "the test never built, after " + REASK +
" re-ask(s) with the compiler's own words:\n" + summary)`. **This is one of two ways a marker becomes
`unprovable`, and this one never asks the argue triple** — the facts are established, so nothing is
argued. **Every failure carries what failed:** a settlement that says "the test never built" and
drops javac's output throws away the one piece of feedback in this program guaranteed to be correct.

**Loopback C — a green RED is not a reproduction.**

```
trace.progress: "RED passed before any patch; asking for a test that fails"
```

If the RED build ran and *passed*, the reproduce-doer is asked again with `GREEN_RED` appended to the
brief (`brief + GREEN_RED`, with no other context). This fact is certain rather than heuristic: the
reproduce-doer holds no `edit_file` and no fix-doer has run, so the tree this build ran against **is** the
revision the marker was raised against.

> Across one run, 16 of the 33 markers that reached a build had their first RED pass, and 13 of them
> settled on it — six `by-design`, seven `false-positive`, every one argued from a build that showed
> nothing. The argue triple cannot fix a test.

The `GREEN_RED` text is load-bearing and shared verbatim with the `run_test` tool, so a reproduce-doer
running its own test hears the same thing at the same moment rather than reading the bare word
`PASSED`, which means its opposite here:

```
YOUR TEST COMPILED AND PASSED against the code as it stands. Nothing you write can pass here
unless it is green on the defect: you cannot edit source and no patch has been applied, so this
is the revision the marker was raised against.

A test that passes before the fix has DOCUMENTED the defect, not observed it. `assertThrows`
for the very exception the marker names is satisfied BY the defect and would go red the moment
it was fixed — which is backwards.

Write it again so it FAILS on this code and would pass once the defect is gone: assert what the
method should RETURN or leave behind, not that it throws.

IF THE DEFECT ONLY SHOWS UNDER A JVM SETTING THIS BUILD DOES NOT USE, A TEST MAY START A JVM.
Fork one with ProcessBuilder, pass the setting, run the subject inside it and assert on what the
child prints. A whole checker family here has been written off as undemonstrable by agents who
knew the setting was fixed at start-up and did not think of starting one.

If nothing can do that — because the flagged state is unreachable from any caller, or because
the checker names a code shape whose fix changes nothing observable — answer with exactly
`no test` and one line of why. That answer is worth more than this build was.
```

The rewrite loop breaks immediately if the reproduce-doer declines or names no test class — the rewrite
is never built in that case, and the original build stands as the last word. In practice, once the
first attempt wrote a file only a decline can break it, because the written name is remembered for
the whole prove (see *The decline token*). If the build is still `infra` or still passing afterwards,
the marker goes to `argued()` with:

```java
argued(whatThisRunMade() + brief
     + "\nNO TEST COULD BE MADE TO FAIL ON THIS CODE. The reproduce-doer was asked "
     + "twice; the last build was:\n" + a.build().summary());
```

### 3. `reproduce-verifier` — asked only after the build agreed

```
trace.progress: "RED reproduced; reproduce-verifier reading the test"
input: brief + "\nThe test, which compiles and goes RED:\n" + test + "\n" + red.summary()
```

**RED runs before the verifier.** A test that does not compile cannot be over-mocked in any interesting
way, and a test that does not go red proves nothing whatever its mocks look like — so grading its
mocking first spends a model call on something no build has agreed exists.

It judges one thing: does the test observe more than the defect requires — by mocking what could
have been real, or by introspection (reflection, `setAccessible`, a private field, an assertion on a
log line or a call count)? It is told both facts are established and not to re-litigate them, and
that **if it cannot name a replacement it must answer `necessary`** — naming nothing is the same as
approving.

Its answer is read as `verdict(critique, "reducible", "necessary", "replan")`, and the three words go
three different ways. `necessary` keeps the test and the stage ends. `reducible` is a fault in the
WRITING and goes back to the doer. `replan` is a fault in the APPROACH and goes back to the planner —
a loopback that reaches past the doer, which the pair shape had no way to express. **They are
exclusive**: the word is read once and only the branch it names runs.

**Loopback D — `replan`, which reaches past the doer.** A test that does not observe the defect is
usually not a badly written test; it is a test written to a plan that was never going to observe it,
and sending that back to the writer produces the same test in different words. So the
reproduce-planner is asked again:

```
Your plan was sent back:
<critique>

Plan it a different way. Do not restate the plan that was refused.
```

The new plan goes to the reproduce-doer appended to the brief as `"\n\nThe plan you are working
from:\n" + plan`, and what it writes is built through `reproduce()` like every other write in this
stage — so a test written to the new plan that does not compile gets the compiler's words back rather
than being thrown away, and the new plan travels with those retries as `context`. Note that the brief
still carries the FIRST plan, which was appended to it in stage 1: the doer sees both, in order, with
the refused one first and the one it is to work from last.

**The replan is kept only if it is better.** `red` and `test` become the replanned attempt's only when
that build is neither `infra` nor passing; otherwise the original RED stands. The first plan did
observe the defect, and trading a working reproduction for a tidier one that proves nothing is the
wrong direction. That is the opposite of what `reducible` does below, and deliberately: a reduced
rewrite is meant to be the same test with less in it, so its failing to reproduce is a fact about this
prove worth recording as `needs-review`, while a replan is a different approach altogether and its
failing says only that the first approach was the better one.

**Loopback E — `reducible`.** The reproduce-doer is asked once more:

```
A reviewer read your test and judged it observes more than it needs to:
<critique>
Write it again. Keep only what the defect requires.
```

That text travels *with* the compile retries as `reproduce()`'s `context` argument: a reproduce-doer
being told to fix a build error mid-rewrite must still know what the reviewer asked it to change.

**The rewrite is re-built, and its failure does not fall back to the original.** If the rewrite is
`infra` → `needs-review` ("the original test reproduced; the rewrite the critic asked for would not
build"). If the rewrite *passes* → `needs-review` ("the original test reproduced; the rewrite the
critic asked for no longer does"). A rewritten test nobody re-builds is how a green proof gets
recorded for a test that stopped reproducing.

If the rewrite does go red, it **replaces** the original: `test` and `red` become the rewrite's, and
everything downstream — the evidence string, the fix-doer's brief, the certificate — is about the
reduced test and never about the one the verifier faulted.

The reproduce-verifier is **not** asked again — not about the rewrite, and not about what came back
from a replan. One judgement per test in this stage; the build is what reads the second one.

### 4. `fix-planner` → `fix-doer` → GREEN

```
trace.progress: "fix-planner: deciding where to fix it"
evidence = "\nThe failing test:\n" + test + "\nRED:\n" + red.summary()
input: brief + evidence
trace.progress: "fix-doer: patching"
input: brief + evidence + "\n\nThe plan you are working from:\n" + fixPlan
```

**Evidence is assembled once**, so a retry can never be poorer than the call it replaces.

The fix-planner names the change — which file, which construct, what it becomes — and is asked to say
what class of input it covers, so a reader can tell a fix of the defect from a fix of the test. It is
also the stage that may say the subject WANTS the bug: this runs against teaching code, where a
vulnerability can be the lesson, and a plan that says so is the one that stops a pull request nobody
would merge. **The fix-planner is asked once and never again** — see the fix-verifier's words below —
and `fixPlan` goes into that one `fix-doer` call and no other, so neither re-ask in this stage
restates it.

**There is no patch object.** The fix-doer edits the working tree through `edit_file`; `patch` is only
what it *said*. The tree is what the runner builds and what `git diff` reads back, and the GREEN
build is run over the same test class as RED (`testClass(trace, test)`), so the fix-doer cannot change
which test decides it.

**Loopback F — the patch does not build.** `patchUntilItBuilds()` gives the fix-doer the same courtesy
`reproduce()` gives the reproduce-doer, and for the same reason — a patch that does not compile is not a
rejected patch, it is an unfinished one:

```
Your patch did not build. The compiler said:
<summary>
Fix exactly that. Do not change the test.
```

**That re-ask's reply is discarded** — `agents.fix-doer().run(...)` inside `patchUntilItBuilds()` is
called for its effect on the tree, not its words, so `patch` still holds the first answer's prose.
Little is lost by that, because the verifier judges the diff and not the prose — but a rebuilder should
know that "What the fix-doer says it did" can be one round stale.

GREEN `infra` after that → `reproduced` ("the defect is real; no patch of it would build"). GREEN
ran and failed → `reproduced` ("the defect is real and no patch held"). Both keep the reproduction:
a failed patch does not retract a demonstrated defect.

### 5. `fix-verifier` — the skeptic, who certifies

```
trace.progress: "GREEN passed; fix-verifier certifying"
input: brief + evidence
     + "\nGREEN:\n" + green.summary()
     + "\nWhat the fix-doer says it did:\n" + patch
     + "\nWHAT IT ACTUALLY CHANGED (git diff, tests excluded):\n" + changed
     + "\n" + reachesTheFlaggedLine(changed)
```

**The verifier judges the diff, not the prose.** It used to be handed the fix-doer's account — "I added a
null check in `resolve()`" — and certified that. Two markers reached pr-ready with the flagged line
untouched and a sibling class edited instead, because nobody in the chain ever looked at a diff. The
diff is `git diff -U3 -- . ':(exclude)*src/test/*' ':(exclude)*src/it/*'`; the test the reproduce-doer
wrote is not part of the patch under review. A non-zero exit or an empty diff both become the literal
`(the working tree is unchanged outside the tests)` — **git failing to speak reads as "nothing was
changed", which is the direction that gets a patch questioned rather than waved through.**

`reachesTheFlaggedLine()` is arithmetic over the diff, not an opinion, and it is handed to the verifier
as a fact it may not contradict:

- a `+++ ` line switches the arithmetic on when it ends with the marker's file (with or without a
  leading `/`), and off otherwise;
- each `@@` row is matched against `^@@ -(\d+)(?:,(\d+))? ` — group 1 is the old-side start, group 2
  the count, defaulting to `1` when absent;
- the patch reaches the line when `line >= from && line < from + count`.

When it does, the verifier is told so and asked to judge whether what changed there removes the defect.
When it does not, the verifier is told **`THE PATCH DOES NOT TOUCH <file>:<line>, the line flagged`**
and that it **may not answer `sound` without saying, in one sentence, why the flagged line is no
longer defective; if it cannot say that, the answer is `over-fit`.** Missing the line is not
automatically wrong — a defect is often correctly fixed at its source rather than where the analyser
saw it.

A marker whose line field is missing or unparseable yields `0`, which matches no hunk, so the verifier
gets the "does not touch" sentence. **The absent number costs an explanation, never a free `sound`.**

**Loopback G — rejection, and it ends at the doer like every loopback in this stage.** `rejects()` is
`!"sound".equals(verdict(certificate, "sound", "over-fit", "regression-risk"))`, so anything but a
readable `sound` — including an empty reply — sends the patch back to the DOER:

```
Your previous patch was REJECTED and discarded:
<the previous patch>
The reviewer's objection:
<certificate>
Write a DIFFERENT patch answering it. Do not widen the test.
```

The previous patch is quoted because *"do not resubmit the previous one"* is unfollowable unless the
previous one is there. The replacement is re-built through `patchUntilItBuilds()`; if that build is
`infra` the marker settles `reproduced` — **a build that never ran is not a failed certification.**

**There is no `replan` here.** The three words `rejects()` reads are all about the patch, so a
fix-verifier has no way to say the plan was the fault and the fix-planner is asked exactly once per
prove. This is the one triple whose verifier can only reach the doer, and a rebuilder adding the word
must add it to `rejects()` as well as to the prompt — the prompt alone changes nothing, because the
reader only branches on what it was given to look for.

The `fix-verifier` is asked twice where it rejected once, and its second look is **narrower**: the
re-ask carries `brief + evidence + GREEN + "\nThe patch it certifies:\n" + patch` — no `git diff`, no
flagged-line arithmetic. It is the only verifier outside `planned()` that judges what its own
objection produced.

The stage ends at `needs-review` ("red then green, but the patch was not certified") when
`!green.passed() || rejects(certificate)`. Two ways in, and the message is the same for both: the
certificate was refused, or the replacement patch built and its test went red again.

### 6. The `propose` triple — the curator

```
trace.progress: "propose-planner: deciding what the case rests on"
proposal = brief + evidence + "\nGREEN:\n" + green.summary()
         + "\nThe certified patch:\n" + patch + "\nThe certification:\n" + certificate
curation = planned(agents.proposePlanner(), agents.proposeDoer(),
                   agents.proposeVerifier(), proposal, "")[1]
```

The curator gets the whole record rather than the patch alone, because it decides whether this
reaches a stranger's repository; `proposal` is the task all three of them are given. Its verifier is
asked to be **hardest on `make`**: the expensive mistake is one-sided — a wrongly proposed patch costs a
maintainer their afternoon and this project its welcome; a wrongly declined one costs nothing anybody
notices.

The propose-planner does not answer `make` or `reject` — that word is the doer's. It says what the
case RESTS on: what a maintainer would have to believe, what in the record supports it, and what a
maintainer would ask that this record cannot answer. It is also asked to look first for the strongest
reason to say no, which is not that the patch is bad but that the behaviour is intended.

Terminal states: `verified/pr-ready` only when `"make".equals(verdict(curation, "make", "reject"))`;
otherwise `verified/pr-rejected`. The `curation` read there is `planned()`'s second return value — the
work as it finally stood, after however many of its two turns the verifier spent.

### 7. The `argue` triple — only where execution settled nothing

Reached from exactly two places — no test was ever written, and no test could be made to fail — both
of them through `argued(task)`, which composes and then routes:

```
trace.progress: "argue-planner: deciding what could settle this without a test"
```

```java
String record   = task + "\n\n" + whatExecutionProduced();
String argument = planned(agents.arguePlanner(), agents.argueDoer(),
                          agents.argueVerifier(), record, "")[1];
String kind = verdict(argument, "false-positive", "by-design", "unprovable");
return priced(kind.isEmpty() ? "unprovable" : kind, argument);
```

and `task` is `whatThisRunMade() + brief + <why we are here>`.

The argue-planner reads the same `record` and answers which of the three states the evidence could
honestly reach, **naming the artefact that would pay for it — the file and the line**. That is the
half this stage was missing: an agent that has not decided in advance what would pay for
`false-positive` or `by-design` reaches for whichever exit is cheapest, and saying `unprovable`
plainly before the argument is written is what stops it.

**`whatThisRunMade()` marks this run's own output inadmissible.** The argue-doer reads the tree,
and by the time it is asked the tree contains the test this run wrote and the patch this run
applied. Thirteen settlements rested on that: `by-design` because "a test depends on this
behaviour", where the test was the one written eleven minutes earlier by the reproduce-doer, in this
prove, about this marker. Circular, and invisible in the record because the citation reads exactly
like a citation of the project's own tests. `git status --porcelain` knows: everything untracked or
modified is ours — each row's status columns are dropped (`substring(3)`) and the rest listed under
`INADMISSIBLE — THIS RUN CREATED THESE, AND THEY ARE NOT EVIDENCE ABOUT THE PROJECT:`.

**Its failure direction is toward permitting.** A non-zero git exit, or an empty porcelain list,
returns `""` and the paragraph simply does not appear — the agent is then told nothing about what is
ours. A rebuilder must not "fix" that into a blocking check: a prove whose git call failed still has
to reach a settlement, and the inadmissibility notice is a warning to a reader, not a gate.

**`whatExecutionProduced()` is computed, not described.** With an empty build ledger it opens
`WHAT THIS RUN OBSERVED: NOTHING EXECUTED FOR THIS MARKER.` and prices the three states against each
other — `false-positive` is about how the code BEHAVES and nothing watched it behave, `by-design` is
about what somebody INTENDED and needs an artefact older than this run, so the honest state is
`unprovable`. With builds it opens `WHAT THIS RUN OBSERVED, in order: ` followed by the ledger joined
with `; `, and adds that "a build that never ran is not evidence, and a red build that PASSED
observed the code behaving correctly on the inputs that test used — and nothing more than that."

The ledger itself is the **first line of each build summary**, appended by `built()` in the order the
builds ran — `Runner` writes that line as a plain verdict (`red: FAILED`, `green: PASSED`, `red: no
test class was named, so nothing ran`), so this is a list of facts and not a description of them. An
agent told nothing about what executed reaches for whichever of the three states is cheapest to
argue, and in a repository framed as deliberately vulnerable the cheapest is always `by-design`.

**The state follows the argument.** Whatever word is read out of the verdict is the settlement;
filing its answer under a state chosen by the branch that called it would record a marker argued
`by-design` as `false-positive`, and those mean opposite things — one says the code is deliberately
that way, the other says the claim is untrue. A verdict naming none of the three words settles
`unprovable`, the residual. Note what is read: the word comes out of the doer's argument, and the
verifier's own `sound`/`redo`/`replan` never becomes a disposition.

The `argue-verifier` exists because this was the one stage answerable to nobody, and it showed:
this stage carried **20 of the 77 faults found in a 28-marker read**, and six of the thirteen
wrong settlements were `by-design` reached because the framing "WebGoat is deliberately vulnerable"
licenses whichever exit is cheapest. Its question is narrow and its prompt puts it in those words:
*"DOES THE ARGUMENT REACH THE STATE IT NAMED, or only a weaker one?"* It is told that `sound` is a
correct and expected answer, and that a `redo` must **name which of the three the argument actually
reaches and what artefact was missing for the one it named** — a complaint that cannot name the
weaker state cannot be acted on, and comes back word for word.

### 8. The `price` triple — last, on every path

```java
private String priced(String disposition, String because)
record = brief + "\n\nIt settled as: " + disposition + "\n\nThe record:\n" + because
estimate = planned(agents.pricePlanner(), agents.priceDoer(),
                   agents.priceVerifier(), record, "")[1]
```

**The price triple fires on every terminal path**, including the ones that never built anything. A
marker the reproduce-doer declined still cost a person the read that decided it, so pricing only the
ones that reach a pull request would measure how often this program succeeds rather than what it
saved.

The price-planner lists the units of work a person would have gone through and **gives no number** —
the doer adds them up. It is told the two ways this stage lies: that what the pipeline spent is not
what a person would spend, six agent turns and two Maven builds being this program's cost rather than
theirs; and that a marker nobody could reproduce still cost somebody the reading that established
that, so an honest estimate has a floor.

**Loopback H — the shape, checked by a regex and not by a model.** `minutes(estimate)` is
`Pattern.compile("minutes\\s*:\\s*(\\d+)").matcher(estimate)` with `find()` — the first match
anywhere in the reply, not an anchored match on the first line. Missing → one re-ask **of the
price-doer**, outside `planned()` and never seen by the verifier: *"Your answer had no figure in it.
Begin with exactly `minutes: N`."* The verifier once passed an estimate with no figure in it at all,
which is exactly the failure a parser cannot have. **The regex runs after `planned()`, not inside
it** — a `redo` can come back without the shape the first answer had, so whatever the triple finally
returns is what gets measured. It runs once more after its own re-ask for the same reason; still
missing, and the estimate becomes `"minutes: unknown\n" + estimate` — prepended, never replacing what
was said.

`priced()` calls `settled()`, never itself — calling itself here would price the estimate of the
estimate until the stack runs out. What it returns is the whole account, and **its first line is the
state**, because `main` reads the state as `account.split("\n", 2)[0]`:

```
<disposition>

<why, in the words of whatever settled it>

--- human-equivalent ---
minutes: N
<three to six lines itemising what was charged>
```

`--- human-equivalent ---` is the literal separator. Before returning, `priced()` calls
`trace.priced(marker, minutes(estimate), estimate)` — the parsed figure and the text, separately, so
a reader of the record never has to re-run the regex.

The whole `planned()` call — planner, doer, verifier and both their loopbacks — together with the
missing-figure re-ask sits inside one `try` that catches `RuntimeException`. **The pricing step can
fail entirely and the settlement still stands**, carrying `minutes: unknown (<ExceptionSimpleName>)`
in place of a figure. It is the only stage where an unreachable planner or doer does not end the
prove, and that is deliberate: a number is worth less than a settlement.

---

## The generic triple

Three stages share one implementation:

```java
private static String[] planned(Agents.Agent planner, Agents.Agent doer, Agents.Agent verifier,
                                String task, String hint)
```

- planner sees `task + hint`; doer sees `task + "\n\nThe plan you are working from:\n" + plan`
- verifier sees `task + "\n\nThe plan:\n" + plan + "\n\nWhat was made from it:\n" + work` — **it is
  given the plan as well as the work**, which is what lets it fault one rather than the other
- the word is `verdict(judged, "sound", "redo", "replan")`; anything else — **including an empty or
  unreadable reply — ends the loop and leaves the work standing**
- **Loopback I — `replan`** — planner sees `task + hint + "\n\nYour last plan was sent back:\n" +
  judged + "\n\nPlan it a different way. Do not restate the plan that was refused."`, and the doer is
  then asked again from the new plan
- **Loopback J — `redo`** — doer sees `task + "\n\nThe plan you are working from:\n" + plan +
  "\n\nYour work was sent back:\n" + judged + "\n\nAnswer the objection. Do not simply restate what
  you did."`
- **one of each, ever**, in whichever order they come: two turns of the loop, one `replan` and one
  `redo`, and a second `replan` is read and not acted on. A stage that can replan without bound is a
  stage that argues with itself for the length of the run, and the second failure of the same kind is
  a finding for a person — which is what the supervisor is for
- **the verifier IS asked again about what its own objection produced.** That is the difference from
  the pair-shaped helper this replaced, which returned the second answer unjudged. So a triple costs
  at most two judgements, and what the second one says can still spend the other loopback; after two
  turns the loop ends whether or not it was satisfied, and the work as it stands is what the caller
  gets
- **every call inside the loop is wrapped in `try/catch`** — the verifier's, the replan's planner and
  doer, the redo's doer — and a throw breaks the loop rather than the prove. The FIRST plan and the
  FIRST work are outside it: an unreachable planner or doer at the top of a stage ends the prove,
  except in `priced()`, whose own `try` is wider
- returns `{plan, work}`, and **all three call sites take `[1]`**. The plan is written to the trace by
  the runtime that produced it and is deliberately not carried into the settlement: it is how the work
  was reached, not what was settled

| Call site | task | what the caller reads out of `[1]` |
|---|---|---|
| the `propose` triple | the whole record: brief, evidence, GREEN, the certified patch, the certification | `make` \| `reject` → `verified/pr-ready` or `verified/pr-rejected` |
| the `argue` triple | `whatThisRunMade()` + brief + why we are here + `whatExecutionProduced()` | `false-positive` \| `by-design` \| `unprovable` — and a reply naming none of the three settles `unprovable` |
| the `price` triple | brief + how it settled + the record | the `minutes: N` regex, then the itemisation |

`hint` is `""` at all three call sites. It is there so a caller can put something in front of the
planner that the doer never sees, and nothing needs that yet — a parameter with no user is worth one
sentence here rather than a rediscovery later.

---

## What is re-asked, and what is not

**Every loopback ends at a planner or a doer.** A verifier that only complains changes nothing; the
point of asking is to hand the objection back to whoever can act on it — and which of the two that is
is the whole reason there are three roles. Consequences a rebuilder must preserve:

- **No agent remembers a previous call.** Every ask goes through a fresh `Agents.<name>()`, which
  builds a new `SubAgentRuntime` over a new `ChatModel`; each re-ask therefore restates the brief,
  the evidence, the plan and the objection in full. Nothing persists a conversation with a model —
  which is also why a postponed marker comes back as a fresh attempt rather than a continuation.
- **A plan survives a re-ask only where it was put into the brief.** The reproduce stage appends the
  plan to `brief`, so every later ask in that stage carries it, and `planned()` restates the plan in
  each re-ask it makes. The fix stage does neither: `fixPlan` goes to the first `fix-doer` call and
  nowhere else, so the compile retry and the rejection re-ask carry the brief, the evidence and the
  objection but not the plan. A doer that loses the plan on its second turn rewords its own first
  answer, which is the loop the triples were built to break.
- **A verifier is re-consulted exactly where it can still change something.** Inside `planned()` it
  judges what its own objection produced, up to two judgements per stage. In the two hand-written
  stages it is asked once, except `fix-verifier`, which is asked again about the replacement patch on
  the narrower record described above — and `reproduce-verifier`, which is never asked about a rewrite
  or a replan, because the build reads those.
- **The rewrite is always re-built.** The chain runs the build, and re-runs it after any rewrite.
- **The evidence string is built once** and reused by every later call.
- **The compiler's words go back verbatim** to whoever wrote the thing that would not build.
- Doers' own `run_test` calls are invisible to the ledger: they are not traced as builds, do not
  appear in `whatExecutionProduced()`, and do not set the RED/GREEN flags.

The flags carried into the settlement are the *builds'*, not the disposition's: `redOk` is set when a
non-`infra` RED build did **not** pass, `greenOk` when a non-`infra` GREEN build **did**. A
disposition implies them and an implication is not a record — `reproduced` and `verified` both mean
red failed, and only one of them means green passed.

Both are plain assignments inside `built()`, so **the last non-`infra` build of each phase decides
the flag** and an `infra` build never touches either. A first RED that failed and a rewrite that
passed leaves `redOk` false, which is the truth about the test that was actually carried forward.

---

## The direction of silence

Two kinds of absence, and they are not the same thing:

- **An empty reply** — the model answered with tool calls and no content, or nothing readable. The
  runtime turns `null` into `""`. This is an empty judgement, not a failure, and the chain branches
  on it.
- **An unreachable agent** — the call throws. Two places catch a `RuntimeException` inside a prove:
  every call in `planned()`'s loop — the verifier's, and the planner's and doer's on a loopback — and
  the whole price block inside `priced()`. Anywhere else, including the FIRST plan and the FIRST work
  of any stage, the exception unwinds to `main`, which writes a `failed` row for the marker and exits
  1: **a prove that dies still leaves a row, because a dropped connection must not look like nothing
  having happened.**

One rule sets every direction: **an OBJECTION must be raised to bite, so an absent objector waives
and the work stands. A CERTIFICATE must be given to bite, so an absent certifier withholds and
nothing is enforced.** A plan is neither, which is why an absent planner is not caught anywhere: it
does not fail open or shut, it leaves the doer with nothing to work from.

| Agent | Role | Test in code | Empty reply | Unreachable |
|---|---|---|---|---|
| `reproduce-planner` | planner | none — its answer is prose appended to the brief | the doer writes with an empty plan section and is not told the plan is missing | prove dies, `failed` row |
| `reproduce-doer` | doer | `declined()`, then `testClass(…).isBlank()` | asked once more, then `argued()` — **an empty answer is never a decline** | prove dies, `failed` row |
| `reproduce-verifier` | objector | `verdict(…, "reducible","necessary","replan")` | **waives** — the test stands, chain proceeds to the fix triple | prove dies, `failed` row |
| `fix-planner` | planner | none | the doer patches with an empty plan section | prove dies, `failed` row |
| `fix-doer` | doer | none — the build and the diff judge it | the tree it already edited still decides; the prose is only quoted | prove dies, `failed` row |
| `fix-verifier` | certifier | `rejects()` = `!"sound".equals(verdict(…))` | **withholds** — patch sent back, then `needs-review` | prove dies, `failed` row |
| `propose-planner` | planner | none | the doer decides with an empty plan section | prove dies on the first call; **waives** on a `replan` |
| `propose-doer` | certifier | `"make".equals(verdict(…))` | **withholds** — `verified/pr-rejected` | prove dies on the first call; **waives** on a loopback |
| `propose-verifier` | objector | `verdict(…, "sound","redo","replan")` in `planned()` | **waives** — the decision stands | **waives** — the loop breaks and the work stands |
| `argue-planner` | planner | none | the doer argues with an empty plan section | prove dies on the first call; **waives** on a `replan` |
| `argue-doer` | doer | `verdict(…, "false-positive","by-design","unprovable")` | falls to `unprovable`, the residual | prove dies on the first call; **waives** on a loopback |
| `argue-verifier` | objector | `verdict(…, "sound","redo","replan")` in `planned()` | **waives** — the stated verdict stands | **waives** |
| `price-planner` | planner | none | the doer prices with an empty plan section | `minutes: unknown (<ExceptionName>)` |
| `price-doer` | doer | regex `minutes\s*:\s*(\d+)` | `minutes: unknown` prepended | `minutes: unknown (<ExceptionName>)` — **an unreachable price-doer costs a number, never a settlement** |
| `price-verifier` | objector | `verdict(…, "sound","redo","replan")` in `planned()` | **waives** — the figure stands | **waives** |

Why they differ, stated in the source: *"AN OBJECTION MUST BE RAISED TO BITE. A verifier that cannot
be reached must leave the work standing: the alternative is that a dropped connection decides
settlements, which is strictly worse than an unjudged answer. The pair-shaped helper this replaced had
the same rule and a test pinned it — the rule is the invariant, not the helper."*

Getting one backwards is silent. A `fix-verifier` whose absence waived would ship uncertified patches;
an `argue-verifier` whose absence blocked would turn a stated verdict into no verdict at all.

**How a word is read** (the mechanism the whole table depends on). `verdict(reply, allowed…)` runs
two passes, and a `null` reply reads as `""`:

1. **A declaration, not a mention.** Every line is reduced and then compared for *equality* with each
   allowed word — never `contains`. The reduction, in order:
   ```
   line.strip().toLowerCase()
       .replaceAll("^[#*_`\\s>-]+", "")     // heading marks, emphasis, quote and list bullets
       .replaceAll("[#*_`\\s.!]+$", "")     // trailing emphasis, full stop, exclamation
   // then, if the line contains a ':' that is not its last character,
   // everything up to and including the LAST ':' is dropped and the rest stripped
   ```
   So `**reject**`, `## Verdict: sound`, `` `redo` `` and a bare `necessary` all count. The **last**
   matching line wins: an agent that reasons and then concludes has its conclusion last, and one that
   opens with its answer has only the one. Note the trailing-strip set does **not** include `:`, so a
   line that is only `sound:` is not a declaration — it falls through to pass 2 and is read there.
2. **Earliest mention.** With nothing declared, `reply.toLowerCase().indexOf(word)` over each allowed
   word, lowest index wins. Substring, so a word inside a longer word counts.
3. Neither → `""`, which is not equal to anything the chain tests for.

Searching the whole text for a rejection reads every careful acquittal as a conviction: a `propose-doer`
on `PasswordResetLink:21` wrote `**reject**` on its last line and was read as a `make`, because
"makes admin reset links predictable" came first. It would have shipped a patch that breaks a graded
lesson, against the explicit judgement of both agents that exist to stop that. The same fault in the
other direction retried a fix nobody had faulted: a `fix-verifier` that led with `sound` and then
explained that the patch was "not over-fit" had its own explanation counted as its verdict.

---

## The decline token

```java
private static final String DECLINE = "no test";
```

**`no test` is the reproduce-doer's only way to say there is nothing to demonstrate, and it is named in
its prompt verbatim.** A decline is a decision; silence is not one. Treating a blank reply as a
decline let **53 empty answers out of 133 pass for judgements**, and each one reached the argue
triple as though the reproduce-doer had considered the marker and ruled on it. The token exists so there
is something to say instead of nothing.

Where it is honoured:

- **Loopback A** stops asking for a file (`!declined(reply)` guards the loop).
- **Loopback C** breaks out of the green-RED rewrite.
- A marker declined with nothing written then falls to `argued()` like any other marker with no
  test: it gets the whole argue triple and the whole price triple, and its record quotes the decline.

Detection is `reply.toLowerCase().contains("no test")` — substring, anywhere in the reply. A reply
that merely says the words in passing reads as a decline.

**A decline never routes a marker on its own.** Outside the two loop guards, every branch asks
whether a test class is named, not whether the reproduce-doer declined — so a reproduce-doer that wrote a
file and *then* said `no test` has its file built anyway. And **`testWritten` is sticky for the life
of the prove**: it is a field on the one `JsonlTrace`, set by the last qualifying `write_file` and
never cleared. Once anything has been written, `testClass()` cannot return blank again, so a later
reply that writes nothing re-builds whatever was written last rather than reading as "no test".

The prompts that mention it, and must:

- `reproduce-doer`: *"answer with exactly `no test` on its own line and one line of reason. That is a
  useful answer and it costs nothing. An empty answer is not one: it spends a build and tells the
  next reader nothing."*
- `GREEN_RED`, as the last exit when no test can be made to fail.
- `aTestThisBuildCannotRun()`, injected into the brief when the repo-relative flagged file starts
  with `src/it/` or `src/test/`. It opens `THE FLAGGED LINE IS IN THE INTEGRATION TEST TREE, NOT IN
  APPLICATION CODE` — or `UNIT` for `src/test/` — and only the `src/it/` branch adds the two facts
  the program knows and the agent was left to work out: that this project binds `src/it` to failsafe
  and excludes it from the surefire run this pipeline uses, that those classes need a WebGoat serving
  on `localhost:8080`, and therefore that *"a failure you see from it is a connection error and not
  your defect. Do not write a test that calls into it."* Both branches close with the same sentence:
  *"the honest answer is very often `no test`, and it is the expected one here. Give it in one line
  and stop."*

  **Fifty-six of eighty-six runaway generations were the reproduce-doer**, going round for half an hour
  on a marker inside an integration test — "this is an integration test class, not a regular source
  class", "the method is private, so I can't directly test it", "let me think about this differently"
  — because the task it was given has no answer and the answer it was allowed to give was one line in
  a prompt. The connection error is also how markers in that tree collected a free RED and settled
  `reproduced` on nothing.

---

## Why the order is Java rather than a prompt

**Investigation belongs to the agents; sequence belongs here, where nothing can rewrite it.**

- **There is no orchestrator.** An agent asked to follow an order it can rewrite will rewrite it.
  `Prove.run()` is the order and it reads top to bottom.
- **The build is not a tool.** No agent may invoke the deciding runner, because whether RED runs
  before the patch is not a decision, and a tool is something a model chooses to call. `Prove` runs
  it at exactly two points in the chain — `reproduce()` for RED, `patchUntilItBuilds()` for GREEN —
  re-runs it after any rewrite, and hands the result on as text. `run_test` is a third phase,
  `check`, and it decides nothing.
- **The compiler is the one verifier guaranteed to be correct**, and it is free, so its output is what
  goes back on a failed build rather than another model's opinion of it.
- **Where the facts are established, no model is called.** `settled()` composes the disposition from
  the record. Routing an established outcome through a model turns a deterministic settlement into a
  sampled one. Conversely, where the builds established nothing, the *only* thing available is an
  argument — and that argument gets a plan before it and a judgement after it, like every other stage.
- **Shape checks belong to parsers, not to judges.** Whether a reply contains `minutes: N` is a
  question a regex answers; asking a model spends a call to be told the same thing less reliably.
- **The prompts are data; the order is not.** Every runtime is built in one place, `Agents.runtime()`,
  which records the built-in text under the agent's name and then asks `Prompts.effective(name,
  builtIn)` for what to actually send — an operator's override replaces the built-in entirely, with
  no merge. Because a prove is a fresh process per marker, an edit lands on the next marker and
  disturbs nothing running, and **an override that cannot be read falls back to the built-in, never
  to an empty prompt** — an agent with no instructions still answers something. What no override can
  touch is which agent is asked next (chapter 13 has the mechanism).

The chain branches on a word read out of seven of these replies — all five verifiers, plus the
propose-doer's `make`/`reject` and the argue-doer's three states — so sampling must be tight. **No
planner's answer is branched on**: a plan is read by the next model and by nobody else, which is why
none of the five has a word list, and why an unreadable plan costs a stage its approach rather than
its routing. The source states the intent as
`private static final double CERTIFYING = 0.0;` — *"Every agent, at zero: four of the six replies are
branched on"*, a count from an earlier and shorter chain. **The
constant is declared and never referenced**: the model builder passes `Tuning.temperature()`, whose
default is also `0.0`, clamped to `[0, 2]` and re-read per prove. Treat the tuning value as
authoritative and the constant as a comment.
