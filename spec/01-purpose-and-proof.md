# 01. Purpose, and the standard of proof

A static analyser has produced a list of claims about a Java repository. Each claim names a file and
a line and asserts a defect there. This program decides them one at a time, and the thing it is
built around is that **an argument is not evidence**: wherever a build could settle a claim, a build
settles it, and no model is asked. Where nothing could be executed, an agent argues — and the record
says plainly that nothing executed, so a reader can tell the two apart afterwards.

Everything below is a contract. Where a rule looks over-elaborate, the paragraph under it names the
run in which the simpler version was wrong.

---

## A marker

A MARKER is one analyser claim, and it is a single line of text:

```
repo|file|line|checker
```

Four fields, separated by the ASCII pipe `|`, no spaces around the separators, no quoting, no
escaping. One marker per line in the queue file. The whole line is the marker's identity — its
`suspicion_key` in the record — so it must round-trip byte for byte from the queue to the settlement.

```
https://github.com/WebGoat/WebGoat.git|src/main/java/org/owasp/webgoat/lessons/sqlinjection/introduction/SqlInjectionLesson5b.java|41|TAINTED_PTR
```

| field | index | what it is | how it is read |
|---|---|---|---|
| repo | 0 | clone URL of the subject | first field; `repo_of()` in the entrypoint is `cut -d'\|' -f1` |
| file | 1 | path to the flagged file | see the source-root rule below |
| line | 2 | the flagged line, 1-based | parsed as an integer; **any unparseable or missing value is 0**, and 0 matches no diff hunk and no source line |
| checker | 3 | the checker family name, e.g. `TAINTED_PTR`, `FB.DM_DEFAULT_ENCODING` | `Prove` takes field 3 and strips it; `Settlement` takes everything after the LAST `\|` |

**The file field is truncated to a source root before it is resolved against a checkout.** An
analyser reports the path it compiled, which is wherever CI checked the project out
(`/builds/gitlab/some-group/owasp-webgoat/src/main/java/...`). Resolving that against a worktree
escapes the worktree entirely and every marker in the report becomes an infrastructure failure. The
rule is: take these roots in order, and the first one that occurs anywhere in the path wins — the
path is cut to start at it.

```
src/main/java/   src/test/java/   src/main/   src/
```

If none of them occurs, the field is used as-is.

**The settlement records the raw field, not the truncated one.** `Settlement` writes `file` as
field 1 verbatim. Only the code that opens the file on disk truncates.

**A marker's on-disk identity is a slug, not the key.** The pool derives a directory name from the
marker with

```sh
slug() { echo "$1" | sed 's|.*/||; s|[^A-Za-z0-9._-]|_|g' | cut -c1-80; }
```

— everything up to the last `/` is dropped, every character outside `[A-Za-z0-9._-]` becomes `_`,
and the result is cut to 80 characters. The example above becomes
`SqlInjectionLesson5b.java_41_TAINTED_PTR`, and its record lives in
`results/m/SqlInjectionLesson5b.java_41_TAINTED_PTR/`.

**The checker name is a bare token, and a bare token is not a claim.** Every agent that is handed
only `DM_DEFAULT_ENCODING` reconstructs what the checker meant, and several reconstructed it wrong
in ways that decided the marker — one bound `DM_DEFAULT_ENCODING` to `Charset.defaultCharset()`,
which is how you READ the setting rather than how you depend on it. So the claim is stated rather
than guessed: `src/main/resources/checkers/<CHECKER>.txt` carries the construct as a regex and a
note, Java reports whether the flagged line actually contains it and, when it does not, which nearby
lines do, and a checker with no note says exactly that rather than saying nothing.

---

## Proving one

To PROVE a marker is to make the build contradict itself, in this order and no other:

1. **RED** — a test written against the subject as it stands, which the build runs and which
   FAILS.
2. **GREEN** — the same test, after a patch, which the build runs and which PASSES.

A test that fails before the patch and passes after it is the whole standard of proof. Nothing else
substitutes: not a plan, not a re-delegation, not confident prose, not the fixer's own account of
what it changed.

**No agent may invoke the deciding build.** Whether RED runs before the patch is not a decision, so
it is not a tool. The orchestration class runs the runner at exactly two points and hands the result
on as text. Producers do get a `run_test` tool for their own benefit — its description says so, and
says "the run that decides the marker is made elsewhere" — but its phase is `check` and nothing
branches on it.

**The order is code, not a paragraph an agent can rewrite.** An agent asked to follow an order it
can rewrite will rewrite it.

**RED runs before its critic reads it.** A test that does not compile cannot be over-mocked in any
interesting way, and a test that does not go red proves nothing whatever its mocks look like, so
grading the mocking first spends a model call on something no build has agreed exists.

**The compiler is a critic too, and a free one.** When RED fails to build, the compiler's own output
goes back to the test's author verbatim. That is the one piece of feedback in this program
guaranteed to be correct.

**Which test to run is a fact, not an inference.** The class name comes from the last `write_file`
call whose path lands under a test source root, never from the reply's prose: a reply that explains
itself names the harness it borrowed as readily as the test it wrote, so a class name scraped out of
the text picks whichever came last. The path qualifies when it contains one of

```
src/test/    src/it/    src/integrationTest/
```

and ends with `Test.java`; the class name is the basename minus `.java`. **All three roots count** —
accepting only `src/test/` rejected a test written beside the integration tests, so the runner was
told no test had been named and reported infra for a file sitting on disk.

Only if nothing qualifies does the reply itself get read, with `([A-Z][A-Za-z0-9_]*Test)\b`, so that
a decline still names something and the build reports "no test executed" rather than the program
guessing.

**The name is remembered for the whole prove, not per reply.** The trace holds the last qualifying
path it has seen and nothing clears it, which has two consequences a rebuilder who resets it per
call will lose: once any test file has been written, the "no file written" check can never fire
again, and the GREEN build is guaranteed to run the same class the RED build ran.

The tool split keeps this stable across the phases: the reproducer holds `write_file` and not
`edit_file`, the fixer holds `edit_file` and not `write_file` — so the reproducer can never make its
own test pass, and the fixer can never change which test is about to certify it.

**A rewritten test is re-built.** A rewritten test nobody re-builds is how a green proof gets
recorded for a test that stopped reproducing.

---

## Three outcomes, not two

The runner's result is a record, and the reason it is not a boolean is the whole disposition system:

```java
record Result(boolean infra, boolean passed, String summary) {}
```

- `infra` — the build produced no test result at all: a compile error, a missing dependency, the
  wrong JDK, a timeout, no test class named. **Never evidence, in either phase.**
- `passed` — meaningful only when `infra` is false.

**"The test failed" and "the build never ran" are different facts, and collapsing them is how a
marker nobody reproduced gets recorded as reproduced.** In the RED phase a failing test IS the goal,
so a compile error reads as success to anything that checks only the exit code.

The infra test is therefore never the exit code. It is per build tool:

| tool | selected when | "a test executed" means | command |
|---|---|---|---|
| Maven | `pom.xml` exists in the checkout — **tested first** | the output contains the literal `Tests run:` | `mvn -B test -Dtest=<class> -Dsurefire.failIfNoSpecifiedTests=false` |
| Gradle | no `pom.xml`, and `build.gradle` or `build.gradle.kts` exists | the newest mtime under `build/test-results/test` increased over the mtime taken before the build | `./gradlew` (or `gradle`, when there is no `gradlew`) `test --tests <class> --console=plain` |

Maven is **not** run with `-q`: quiet suppresses the compiler error, leaving a build that failed and
a summary saying only that lombok called a deprecated method.

**A tree with neither build file is refused by name.** Choosing the runner throws

```
no pom.xml and no build.gradle in <path> — nothing can run the test
```

rather than defaulting to Maven. Guessing Maven reports every marker in a worktree that failed to
materialise as an ordinary infra build that ran no test, which names nothing a reader can act on.
The refusal happens before any agent is asked, so the prove dies as `infra`, its `verdict_text` is
`IllegalStateException: ` followed by that sentence, and the marker goes back in the queue.

**A blank test class name is infra, not an empty pass.**

### The summary's first line is already a verdict

Every summary begins with the phase and a plain statement, so the ledger a later agent reads is a
list of facts rather than a description of them:

```
<phase>: PASSED
<phase>: FAILED
<phase>: no test executed
<phase>: no test class was named, so nothing ran
<phase>: the build did not finish in time
```

`<phase>` is `red`, `green`, or `check` (a producer's own `run_test`). The rest of the build output
follows after a newline — except for `no test class was named, so nothing ran`, which is the whole
summary, because no build was started and there is nothing to append.

### What the flags mean

Two booleans are carried from the runner into the settlement, and they are **what the runner
reported, never what the disposition implies**:

- `red_verified` — set when a build in phase `red` was not infra and did not pass.
- `green_verified` — set when a build in phase `green` was not infra and passed.

**An infra build touches neither flag.** The assignment is guarded by "not infra", so a compile
error on a rewrite cannot un-set a `red_verified` a real build earned. Both are plain assignments,
so where a phase builds more than once the LAST non-infra build of that phase decides.

`reproduced` and `verified` both mean red failed, and only one of them means green passed. An
implication is not a record.

---

## A RED that passes has demonstrated nothing

The first RED build runs against the revision the marker was raised against. This is certain rather
than heuristic: **the reproducer holds no `edit_file` and no fixer has run**, so the tree that build
ran against IS the unmodified subject. A test that is green there has DOCUMENTED the defect, not
observed it — `assertThrows` for the very exception the marker names is satisfied BY the defect and
would go red the moment it was fixed, which is backwards.

In one run, 16 of the 33 markers that reached a build had their first RED pass, and 13 of them
settled on it — six `by-design`, seven `false-positive`, every one argued from a build that showed
nothing. The chain routed that fact to the verdict agent, which cannot rewrite a test, and never
told the reproducer, which can. One reproducer had already worked it out and shipped anyway:
*"this test won't actually fail on most platforms because the default charset is typically UTF-8"*,
followed by *"But actually, let me just submit the test."*

So the fact goes to the only agent that can act on it, once, and at the moment it happens. The text
is a single constant shared between the chain's re-ask and the producers' own `run_test` tool,
because the bare word `PASSED` reads as success and means its opposite here. `run_test` returns one
of `DID NOT RUN`, `FAILED`, or `PASSED — WHICH IS A FAILURE HERE.` immediately followed by this
text, and then a newline and the build summary:

```
YOUR TEST COMPILED AND PASSED against the code as it stands. Nothing you write can pass here
unless it is green on the defect: you cannot edit source and no patch has been applied, so this
is the revision the marker was raised against.

A test that passes before the fix has DOCUMENTED the defect, not observed it. `assertThrows` for
the very exception the marker names is satisfied BY the defect and would go red the moment it was
fixed — which is backwards.

Write it again so it FAILS on this code and would pass once the defect is gone: assert what the
method should RETURN or leave behind, not that it throws.

IF THE DEFECT ONLY SHOWS UNDER A JVM SETTING THIS BUILD DOES NOT USE, A TEST MAY START A JVM.
Fork one with ProcessBuilder, pass the setting, run the subject inside it and assert on what the
child prints. A whole checker family here has been written off as undemonstrable by agents who
knew the setting was fixed at start-up and did not think of starting one.

If nothing can do that — because the flagged state is unreachable from any caller, or because the
checker names a code shape whose fix changes nothing observable — answer with exactly `no test`
and one line of why. That answer is worth more than this build was.
```

It is reproduced above with its paragraphs wrapped for reading. In the source each paragraph is one
unbroken line, blank lines separate the paragraphs, and the whole string begins with two newlines so
it appends cleanly to the end of a brief.

Three properties of that text are load-bearing and a rebuilder must keep all three:

- **It names the fact and why the fact is certain**, otherwise it is one more thing to be argued
  with.
- **It gives an instruction to write something different**, not only to stop writing that.
- **It offers the exit `no test`**, so an untestable marker is declined rather than papered over.
  Without the exit this asks for a test that cannot exist.

**It must not open by agreeing.** A re-ask that congratulates gets the same test back; a test
asserts that the words *well done*, *good*, *correct* and *success* do not appear in it.

The paragraph about starting a JVM is there because of the single most expensive wrong answer in
this repository's history: **33 `DM_DEFAULT_ENCODING` markers never produced a build**, because every
agent that met one reasoned that the default charset is fixed at JVM start-up and concluded that no
test could vary it. The first clause is true and the conclusion does not follow — a test may start a
JVM. Run by hand against the checkout, `EncDec` goes RED under `-Dfile.encoding=ISO-8859-1` with
`expected: "café" but was: "caf©Ã"`, and GREEN once the charsets are explicit.

**One re-ask, and then it stops.** If the rewrite declines, writes no file, still passes, or no
longer builds, the marker leaves the execution path and is argued instead — never recorded as
reproduced. Note the asymmetry with the first attempt: a FIRST test that will not build settles
`unprovable` on the spot, while a REWRITE that will not build goes to the verdict agent under
*"NO TEST COULD BE MADE TO FAIL ON THIS CODE"*, because by then the run has a passing RED to explain
and that is an argument, not a build failure.

---

## Silence is not a decision

**A blank reply is not a decline.** Treating one as a decline let 53 empty answers out of 133 pass
for judgements, each reaching the verdict agent as though the reproducer had considered the marker
and ruled on it. A model whose last turn is a tool call returns no text, and that is not a
judgement.

The decline is therefore a token, named in the prompt verbatim:

```
no test
```

**The token is matched as a case-insensitive substring of the whole reply**, not as a line of its
own — `no test could demonstrate this` declines, and so does any sentence that happens to contain
the two words. It is checked in two places: before the missing-file re-ask below (a reply that
declined is not asked again for a file) and on the rewrite after a passing RED.

**Nothing is built until a file exists.** The build used to run first, so a reproducer that wrote
nothing cost two Maven invocations before anyone looked — 78 of 153 builds in a 67-marker run
executed no test at all. A written file is a fact this program can check, and it checks it before
the runner is called. A reproducer that wrote nothing is asked once, plainly:

> You wrote no test file. Either use write_file to create one, or answer with exactly `no test` and
> a one-line reason. An empty answer is not a decision.

---

## The seven dispositions

A DISPOSITION is what a marker became. There are exactly seven, spelled exactly like this:

```
false-positive
by-design
unprovable
reproduced
needs-review
verified/pr-ready
verified/pr-rejected
```

The two `verified/…` values contain a slash and are single tokens; they are not a state plus a
qualifier, and nothing may split them.

| disposition | decided by | reached when |
|---|---|---|
| `verified/pr-ready` | execution, then an agent for the suffix | RED failed, GREEN passed, the fix-critic certified `sound`, and the pr-maker's word is `make` |
| `verified/pr-rejected` | execution, then an agent for the suffix | as above, and the pr-maker's word is anything other than `make` |
| `reproduced` | execution alone | RED failed and no patch held: the patch would not build, or GREEN did not pass, or the replacement patch would not build |
| `needs-review` | execution alone | RED failed but the record is inconsistent: the proof-critic's requested rewrite would not build, or no longer reproduces; or, after the fix-critic objected and the fixer was re-asked once, GREEN did not pass or the patch was still not certified |
| `unprovable` | execution **or** an agent | execution: the FIRST test never built, after its re-ask with the compiler's own words — a later rewrite that will not build is argued instead. Agent: the verdict agent's word, and the fallback when its argument names none of the three |
| `false-positive` | an agent | the verdict agent's word |
| `by-design` | an agent | the verdict agent's word |

**Dispositions are computed where the builds settled them, and no model is called for those.** Where
the facts are established there is nothing to argue, and a sampled reply would make a deterministic
outcome vary run to run. The verdict agent is asked in exactly two situations, both of which mean
execution settled nothing:

- **no test was written** — after the plain re-ask above; or
- **no test could be made to fail** — the last RED build was infra or passed.

**The state follows the argument, not the branch that asked for it.** Filing the verdict agent's
answer under a state chosen by the calling branch records a marker argued `by-design` as
`false-positive`, and those mean opposite things to a reader: one says the code is deliberately that
way, the other says the claim is untrue.

**The three argued states are not peers, and the verdict agent is told so in its own prompt.** The
built-in text distinguishes them like this, and a rebuilder should keep the distinctions rather than
the wording:

```
`false-positive` — the claim does not hold in this code. Say why the checker is wrong.
`by-design`      — the claim holds, and the code is deliberately that way. Say what makes it
                   deliberate, and cite something OLDER THAN THIS RUN: the lesson text, the
                   assignment, a comment, a committed test, a caller that relies on it.
`unprovable`     — the claim may hold, but no test could demonstrate it either way.
```

The `by-design` clause carries the inadmissibility rule inside the prompt as well as in the brief —
*"A test or a patch produced by this prove is not evidence about the project — it is evidence about
us"* — because `by-design` is the state a run's own artefacts get cited for. The prompt closes on
what the three cost a reader: *"A tooling failure must not read as an exoneration, and a deliberate
vulnerability must not read as a bug."*

Every prompt in this program is data with the code's text as the default, so the words above are the
built-in and can be replaced per agent; see chapter 13.

### What execution produced is computed, not described

Before the verdict agent argues, it is told what actually ran, assembled from the ledger of build
first-lines rather than from any agent's account. An agent told nothing about this reaches for
whichever state is cheapest to argue, and in a repository framed as deliberately vulnerable the
cheapest is always `by-design`. Two forms, verbatim:

```
WHAT THIS RUN OBSERVED: NOTHING EXECUTED FOR THIS MARKER. No test was written, so no build ran.
`false-positive` is a claim about how this code BEHAVES and nothing here watched it behave;
`by-design` is a claim about what somebody INTENDED and needs an artefact older than this run.
Absent either, the honest state is `unprovable`.
```

```
WHAT THIS RUN OBSERVED, in order: <build first-line>; <build first-line>; … . A build that never
ran is not evidence, and a red build that PASSED observed the code behaving correctly on the
inputs that test used — and nothing more than that.
```

### A run may not cite itself

By the time the verdict agent reads the tree, the tree holds this run's test and this run's patch.
Thirteen settlements rested on that: `by-design` because *"a test depends on this behaviour"*, where
the test was the one written eleven minutes earlier by the reproducer, in this prove, about this
marker. Circular, and invisible in the record because the citation reads exactly like a citation of
the project's own tests.

`git status --porcelain` is the line between ours and theirs: everything untracked or modified is
ours. Every such path is listed to the verdict agent under the heading `INADMISSIBLE — THIS RUN
CREATED THESE, AND THEY ARE NOT EVIDENCE ABOUT THE PROJECT`, with the instruction to cite only what
was there before the run started, and: *"If your argument needs one of the files above, you do not
have an argument."*

---

## How a word becomes a state

Judges answer in closed vocabularies, and the routing of that word is code, not prompt:

| stage | allowed words |
|---|---|
| proof-critic | `reducible`, `necessary` |
| fix-critic | `sound`, `over-fit`, `regression-risk` |
| pr-maker | `make`, `reject` |
| verdict | `false-positive`, `by-design`, `unprovable` |
| every critic that only re-asks | `sound`, `redo` |

**The verdict is the word that is DECLARED, not any word that appears.** Reading the reply for a
substring turns every careful acquittal into a conviction: a skeptic that led with `sound` and then
wrote *"The fix is not over-fit"* while explaining what the patch was NOT had a careful acquittal
read as a rejection, and the chain retried a fix nobody had faulted.

The rule, in order:

1. **Scan lines for one that IS one of the allowed words.** Each line is stripped and lowercased,
   then trimmed at both ends:

   ```java
   line.replaceAll("^[#*_`\\s>-]+", "").replaceAll("[#*_`\\s.!]+$", "")
   ```

   and if a colon remains anywhere except as the final character, everything up to and including the
   last colon is dropped. So `**reject**`, `## Verdict: sound` and a bare `necessary` all count.
   **The LAST such line wins** — an agent that reasons and then concludes has its conclusion last,
   and one that opens with its answer has only the one.
2. **If no line declares**, fall back to the EARLIEST occurrence of any allowed word anywhere in the
   reply, which is right for a judge that opens with its answer and never emphasises it.
3. **If the reply contains none of them, the result is empty.**

Step 1 exists because step 2 alone read a pr-maker that wrote `**reject**` as a `make`: the phrase
*"makes admin reset links predictable"* came earlier in the text. It would have shipped a patch that
breaks the lesson, against the explicit judgement of both agents that exist to stop that.

### The direction of silence

Every absent answer must fail in a stated direction, and getting one backwards is silent. Two kinds
of absence exist and they are not the same:

- **An empty reply.** An agent whose last turn is a tool call returns no content; that is normalised
  to the empty string and handed on. It is a judgement — "it had nothing to say" — and every stage
  that reads it treats it as one. It must never end a prove.
- **A throw.** Unless a caller catches it, an agent call that throws ends the prove, which is
  recorded as `infra` and hands the marker back to the queue. Exactly two callers catch: the shared
  re-ask helper catches a throw from the CRITIC (never from the producer it then re-asks), and the
  pricing step wraps the estimator whole. Every other call is unguarded — both producers, the
  proof-critic, the fix-critic and the pr-maker — so a throw there re-queues the marker rather than
  settling it on an answer nobody gave.

Given that, the directions:

- **An objection must be RAISED to bite**, so an absent objector waives and the work stands. An
  empty or unreadable critique is not an objection: it is not `reducible` for the proof-critic and
  not `redo` for the three that go through the shared re-ask helper (verdict-critic, pr-critic,
  estimator-critic), and the producer's answer is passed on exactly as it was.
- **Waiving an empty answer is not the same as surviving a throw**, and the two must not be
  conflated. Only the three helper-mediated critics waive when the CALL fails; a proof-critic or
  fix-critic that throws ends the prove as `infra`. Both are safe directions — the marker is either
  unreviewed-but-standing or back in the queue — and neither settles it on silence.
- **A certificate must be GIVEN to bite**, so an absent certifier withholds. The fix-critic
  certifies: anything that is not exactly `sound` — an empty reply, prose the parser cannot read, a
  hedge — is a rejection, and a patch reaches a pull request only on a certificate somebody actually
  gave.
- **The pr-maker's word defaults closed**: anything that is not `make` is `verified/pr-rejected`.
- **The verdict agent's word defaults to the residual**: an argument naming none of the three
  settles `unprovable`, which leaves the marker open for a person rather than exonerating it.
- **The estimator defaults to a missing number, never to a missing settlement**: an unreachable
  estimator costs the figure and nothing else.

---

## `infra` is not a disposition

`infra` is the state written when a prove **throws** — a dropped connection, a tool ceiling, an
endpoint that stopped answering, a bug. It is written so that a dead prove does not look like
nothing having happened:

```json
{"suspicion_key":"…","state":"infra","verdict_kind":"infra","verdict_text":"IllegalStateException: …", …}
```

**It is not a decision about the marker, and nothing may treat it as one.** The failure direction is
absolute and one-way: **a marker whose prove ended in `infra` goes back in the queue.**

The pool's definition of settled is a grep for the seven, and nothing else:

```sh
DISPOSITIONS='"state":"(false-positive|by-design|unprovable|reproduced|needs-review|verified/pr-ready|verified/pr-rejected)"'
```

This used to be "any state that is not `proving`", which meant `infra` counted as an answer: a prove
killed by the tool ceiling retired its own marker, and nothing revisited it.

**Everything that is not one of the seven means a prove that did not finish.** Four such states
exist in the record or the display, and none of them is a disposition:

| state | written by | means |
|---|---|---|
| `proving` | every progress note, one line per stage boundary | a prove is running and has reached this stage |
| `infra` | the failure path, when the prove throws | the prove died; hand the marker back |
| `queued` | nothing — the dashboard synthesises it for a marker in the queue with no settlement | never started |
| `interrupted` | nothing — the dashboard substitutes it for a `proving` row whose slug has no claim directory | a prove once started and nothing is behind it now |

The dashboard also carries a `not-a-bug` state: it has a stylesheet class, and it is excluded along
with `queued` from "this marker reached a RED build". **Nothing in this program ever writes it.** It
is vocabulary from outside this pipeline, kept so that an imported row does not render as an error.

The other half of that rule lives in the pool and is stated here because it is the same invariant
read from the other side: **a claim lasts exactly as long as its prove.** A claim that outlived its
prove silently repealed the rule above — `settled` was taught to answer NO for a marker that ended in
`infra`, precisely so the pool would take it again, and three lines later `mkdir claims/$id ||
continue` skipped it anyway because the dead attempt's claim was still there and nothing removed it.
Every marker whose prove threw was retired by the gate that exists to stop two provers taking the
same marker. Both gates read correctly alone; only their order was wrong.

---

## What a settlement looks like

Two files per marker, under `results/m/<slug>/`:

| file | what it is |
|---|---|
| `trace.jsonl` | every prompt, reply, thought, tool call and build, stamped, in full |
| `settlements.jsonl` | one line per stage boundary; the last line for a marker is its current state |

`Prove` itself only knows its third argument: it writes `<results-dir>/trace.jsonl` and
`<results-dir>/settlements.jsonl`. The pool is what passes `results/m/<slug>` as that argument, and
that is what puts one prove's two files in one directory named for its marker.

**A line per stage, not per prove.** A prove takes tens of minutes, and a record written only at the
end leaves a reader with nothing to look at for all of it. Readers group by `suspicion_key` and keep
the last line.

**Appended, never rewritten, and not one file per marker.** A marker legitimately proves more than
once, and a file named after the marker silently keeps only the last attempt.

Each line is a flat JSON object with these keys, in this order:

```json
{"suspicion_key":"repo|file|line|checker","repo":"…","file":"…","svace_checker":"…",
 "title":"<checker> at <basename of file>","state":"…","verdict_kind":"…","verdict_text":"…",
 "red_verified":false,"green_verified":false,
 "test_path":"","test_code":"","fix_diff":"","infra_reason":""}
```

- `red_verified` and `green_verified` are JSON booleans, unquoted. Every other value is a quoted
  string.
- `state` and `verdict_kind` always carry the same value.
- `title` is built from the RAW `file` field: `checker + " at " + file.substring(file.lastIndexOf('/') + 1)`.
- **`test_path`, `test_code`, `fix_diff` and `infra_reason` are always empty**, including on `infra`
  rows — the cause of a failure is in `verdict_text`. These fields come from machinery this program
  does not have, and leaving them blank says so where inventing values would put numbers on a
  dashboard that nothing computed.
- The column names are a pre-existing dashboard's, deliberately: emit that shape and a viewer needs
  no mapping layer, and every filter written against it keeps working.

The JSON is hand-rolled, escaping `"` `\` `\n` `\r` `\t` and any other character below `0x20` as
`\u%04x` — four lower-case hex digits. Nothing above `0x20` is escaped, so the record is UTF-8 text.
It is hand-rolled although a serialiser is on the classpath, for one reason: **this is the last thing
written before the process exits**, and a library that throws on an unexpected value would lose the
whole prove — hours of model calls and two Maven builds — rather than the one field it could not
render.

**A journal that cannot be written must not end a prove that is otherwise fine, and must never
replace the failure it was called to record.** Both writers catch and print to stderr.

### The account

The disposition and its argument travel as one string, and the state is its first line:

```
<disposition>

<why, in the words of whatever settled it>

--- human-equivalent ---
minutes: N
<three to six lines itemising what was charged>
```

The estimator runs on **every** path, including a marker the reproducer declined: a declined marker
still cost a person the read that decided it, and pricing only the ones that reach a pull request
would measure how often this program succeeds rather than what it saved.

**The figure is validated by a regex, not by a critic** — `minutes\s*:\s*(\d+)` — because whether the
reply contains a number is a question a parser answers reliably and a model answers less reliably;
the estimator's own critic once passed an estimate with no figure in it. The order is: ask, re-ask
once if the regex finds no figure, then the estimator-critic loop, then check again — because a
re-ask can lose the shape the first answer had. If the figure is still missing, the line
`minutes: unknown` is PREPENDED to whatever the estimator said rather than replacing it, so the
account always opens with a figure and the reasoning is not thrown away. **An unreachable estimator
costs a number, never a settlement**: the whole pricing step is wrapped, and a failure yields
`minutes: unknown (<ExceptionSimpleName>)`.

### Build events in the trace

The builds are the only entries in the trace that are facts rather than opinions, and a reader who
cannot tell which of the two decided a settlement cannot audit it:

```json
{"at":"<epoch millis>","marker":"…","kind":"built","phase":"red|green|check",
 "infra":"true|false","passed":"true|false","summary":"…"}
{"at":"…","marker":"…","kind":"settled","state":"…","because":"…","red":"true|false","green":"true|false"}
{"at":"…","marker":"…","kind":"failed","cause":"…","stack":"…"}
```

In `trace.jsonl` every value is a string, booleans included; in `settlements.jsonl` `red_verified`
and `green_verified` are real booleans. The `failed` event is the only place the stack survives — the
settlement row it writes carries the one-line cause and nothing more.

---

## The invocation

```
Prove <checkout> <repo|file|line|checker> [results-dir]
```

`results-dir` defaults to `results`. The checkout is a tree the caller has already prepared; this
program never clones. Exit code 2 for a usage error, 1 when the prove threw — with the `infra` row
already written before the exit — and 0 otherwise.

The failure path catches `RuntimeException` around the whole prove and, in this order, writes the
trace's `failed` event and the `infra` settlement row, prints the stack to stderr, and exits 1.
**The row is written before anything can fail again**: a prove that dies while reporting that it
died must still have left the marker in a state the pool will take back.
