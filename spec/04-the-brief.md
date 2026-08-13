# 04. What a prove hands an agent

A **marker** is one static-analysis finding, carried through the whole system as a single
pipe-delimited string: `repo|file|line|checker`. A **prove** is one process, for one marker, that
either demonstrates the defect with a build or argues it away. The **brief** is the block of text a
prove assembles before it calls anybody, and it is the trunk of every prompt in that prove.

**The brief is constructed exactly once, before the first agent call, and no agent prompt in the
prove contains less than the whole of it.** The reproducer gets `brief`; the proof-critic gets
`brief + the test + the RED summary`; the fixer gets `brief + evidence`; the estimator gets
`brief + how it settled`. There is no second construction and no per-agent variant. A rebuilder who
lets each stage assemble its own context will find stages disagreeing about which line was flagged.

Every prompt also *begins* with the brief, with exactly one exception: on the two paths that reach
the verdict agent, the inadmissibility block is prepended ahead of it (see below). Nothing else is
ever inserted before it.

**The flagged source is handed over, not fetched.** `SubAgentRuntime` caps one agent at 25
sequential tool calls, as a literal in the library (`.maxSequentialToolsInvocations(25)`), and an
agent that spends its budget re-reading a file the caller already holds has none left for the class
it actually needed. Worse, hitting the ceiling is a thrown `RuntimeException` that ends the prove —
a marker lost to a budget rather than to anything about the marker. File tools exist for what nobody
anticipated. (The image lifts the cap — the Dockerfile clones the deepagents source, `sed`s the
literal to `Integer.MAX_VALUE`, and a following `grep -q` on the patched file fails the build if
upstream ever makes it a parameter, so the workaround breaks loudly rather than silently reverting —
but the brief still carries the material, because a
long prompt is cheaper than a round trip and because the agent should not have to discover the
marker's own file.)

---

## The shape of the brief

```
Marker: <the marker string, verbatim>
The checkout is your workspace; read further only if you need to.

<checker note>              — Checkers.note(checkout, marker, checker, file, line)
<test-tree warning>         — aTestThisBuildCannotRun(marker); usually absent
The flagged file, <file>:
<numbered source>           — source(checkout, marker)
<sibling tests>             — siblingTests(checkout, marker); may be absent
```

In source:

```java
brief = "Marker: " + marker
        + "\nThe checkout is your workspace; read further only if you need to.\n\n"
        + Checkers.note(checkout, marker, checkerOf(marker), fileOf(marker), lineOf(marker))
        + aTestThisBuildCannotRun(marker)
        + "The flagged file, " + fileOf(marker) + ":\n" + source(checkout, marker)
        + siblingTests(checkout, marker);
```

The order is: what was claimed, what the claim means, whether this build can even act on it, the
evidence for the claim, and how this project writes a test. Each part after the first is allowed to
be empty, and each has a defined failure direction (see the table at the end).

**Each part carries its own separators and the brief does not normalise them.** The checker note and
the test-tree warning each open with `\n\n` and close with `\n`; each sibling section opens with
`\n\n`. Concatenated onto a header that already ends `\n\n`, that produces runs of blank lines. A
rebuilder aiming for byte-identical prompts should concatenate verbatim rather than tidy up.

The marker line is **verbatim**, including the analyser's own absolute path
(`/builds/gitlab/some-group/owasp-webgoat/src/main/java/...`). The repo-relative path appears
separately, on the `The flagged file,` line, so an agent can see both what was reported and what
exists here.

### Reading the four fields

Splitting is on `\|`, and every accessor tolerates a short or malformed marker rather than throwing.

| field | index | accessor | absent / unparseable |
|---|---|---|---|
| repo | 0 | — (not used in the brief beyond the verbatim line) | — |
| file | 1 | `fileOf` — **not** stripped | `""` |
| line | 2 | `lineOf` — stripped, then `Integer.parseInt` | `0` |
| checker | 3 | `checkerOf` — stripped | `""` |

**`fileOf` makes the analyser's path repo-relative by finding a source root inside it, not by
resolving it.** The roots are tried in this order and the result is the substring from the first one
found:

```
src/main/java/    src/test/java/    src/main/    src/
```

If none appears, the path is returned as given. The failure this prevents: an analyser reports the
path it compiled, which is wherever CI checked the project out. Resolving `/builds/gitlab/...`
against a checkout escapes the checkout entirely, and every marker in the report becomes an infra
failure.

Note the consequence for `src/it/java/...`: only the last root matches, so the returned path begins
`src/it/` — which is exactly what the test-tree warning below keys on.

**`lineOf` returns 0 for a marker that names no line or a non-numeric one.** Zero is chosen because
it matches no diff hunk and no source line, so a missing line number degrades into "nothing is
confirmed" rather than into a claim about line 1.

---

## Part 1 — the checker note

A marker names a checker and nothing else, so every agent reconstructs the claim from a bare name,
and several reconstructed it wrong in ways that decided the marker:

- `JWT:180` bound `DM_DEFAULT_ENCODING` to `Charset.defaultCharset()`, because that was the only
  charset-looking token in the statement. That call is how you *read* the setting, not how you
  depend on it accidentally, and a marker was settled on the confusion.
- `VulnerableTaskHolder:69` spent its single RED on a semicolon payload, because nobody knew
  `Runtime.exec(String)` splits on whitespace and starts the first token — a shell metacharacter
  chains nothing.

(Chapter 05 is the note corpus itself — what a note must contain and how it is written. What follows
is only the contract `Prove` depends on.)

### Where notes live

```
classpath:/checkers/<checker>.txt        loaded with Checkers.class.getResourceAsStream
agent/src/main/resources/checkers/*.txt  where they live on disk
```

The name is sanitised with `replaceAll("[^A-Za-z0-9._-]", "")` **for the lookup only**; the note's
own heading and the absence sentence both print the raw checker name from the marker. The file has
two parts:

```
<line 1>   a Java regular expression: the CONSTRUCT this checker reports
<line 2+>  the note, stripped — what it reports, what it is NOT, and how to make it visible
```

A file with no newline in it is treated as **absent**, not as a note with an empty body.

### What the note produces

Note present:

```

WHAT <checker> REPORTS: <the body, stripped>
<the line sentence>

```

Note absent:

```

THIS PIPELINE HAS NO NOTE FOR <checker>. Say in your answer which construct you took that name to
mean, so the next reader can tell a correct answer from a lucky one.

```

**Absence is stated out loud, never silent.** A worse brief than a note, and a much better one than a
confident wrong note — which is indistinguishable from a right one at the point it is read. A guess
this program can read afterwards is worth more than one it cannot.

### The line sentence, which is arithmetic and not an opinion

The construct regex is compiled and matched against the flagged line of the flagged file:

- match → `Line <n> does contain it.`
- no match → `LINE <n> DOES NOT CONTAIN THE CONSTRUCT THIS CHECKER REPORTS. ` followed by either
  - `The nearest lines that do are <n>, <n>, …. Judge the one the checker meant, and say in your answer which line you judged.`
  - or, when nothing nearby matches, `Neither does any line near it. The marker may have drifted off this file entirely — say so rather than judging whatever is at that line.`

Nearby means **within 40 lines of the flagged one, in either direction**. The list stops once it
holds three commas — at most four line numbers — under a constant named `NEARBY = 3`. `40` is a bare
literal in the comparison `Math.abs(n - line) <= 40`; only the comma count is named.

**The list is in ascending line order, not in order of distance.** The scan walks the file from line
1 and takes the first up-to-four matches that fall inside the window, so for a flagged line at 500 it
names the earliest matches at or after 460 and may never reach 499 even though 499 is nearer. The
sentence says "nearest" and means "earliest inside the window" — a difference a rebuilder will not
notice from the prose.

**A marker with no line number takes the same branch.** `lineOf` gave `0`, `0 >= 1` is false, so the
agent is told `LINE 0 DOES NOT CONTAIN THE CONSTRUCT…` and the window `|n - 0| <= 40` lists whichever
of lines 1–40 match. That is the intended degradation: nothing is confirmed, and the reason is on the
page.

### Failure directions inside the note

Both of these fail **silently to nothing**, and both are guarded by tests rather than by prose:

- **Flagged file unreadable** → the line sentence is `""` (`IOException` *and* `RuntimeException` are
  both caught, so an unresolvable path degrades the same way). The note body still arrives. The rule
  is that it must not claim the line is wrong when it could not look.
- **Construct regex does not compile** → `PatternSyntaxException` is caught and the line sentence is
  `""`. The note still looks present, and the one sentence the agent most needs is quietly missing.

A third failure has no exception at all and is the worst of the three: **a regex that matches
everything reports `Line 63 does contain it` for every line of every file**, so the check becomes a
rubber stamp that is confidently wrong.

`ANoteIsCheckedBeforeItIsTrustedTest` therefore holds the note corpus to four rules, because none of
the failures above can be caught by reading the prose:

1. every note's first line compiles as a regex;
2. no note's regex matches contentless lines — the fixture is exactly
   `""`, `    // just a comment`, `    }`, `import java.util.List;`,
   `package org.owasp.webgoat.container;`, `        }  // done` — because a regex that fires on a
   closing brace tells every agent its flagged line is the right one, *including* the agents whose
   marker has drifted;
3. every note's body (everything after the first newline, stripped) is at least 200 characters,
   because a note that only renames the checker leaves the agent exactly where it was;
4. every note is a first line plus a body: it contains a newline at index > 0, and the first line is
   not blank.

A bare `return;` is deliberately **not** in the contentless list: for `UNREACHABLE_CODE` and the
null-return families a control-flow statement is precisely the construct, and listing it there
failed a note for being right.

**What no test holds is whether a note is TRUE.** Thirty-one notes were corrected by a second reader
working against a real checkout — one of them a whole family whose recipe pointed at a file carrying
no marker at all. That is a job for a reader with the source, not for an assertion, and a rebuilder
who adds notes without that pass has a corpus that compiles and lies.

### The sentence that unlocked thirty-three markers

Thirty-three `FB.DM_DEFAULT_ENCODING` markers never produced a single build. Every agent that met
one reached the same conclusion in nearly the same words: the default charset is fixed at JVM
start-up, therefore no test can vary it, therefore this cannot be demonstrated. **The first clause is
true and the conclusion does not follow: a test may START a JVM.** The note carries the recipe —

```java
ProcessBuilder b = new ProcessBuilder(
        System.getProperty("java.home") + "/bin/java",
        "-Dfile.encoding=ISO-8859-1",
        "-cp", System.getProperty("java.class.path"),
        SomeMainThatExercisesTheSubject.class.getName());
```

— and the observed result: run by hand against this checkout, `EncDec` goes RED under
`-Dfile.encoding=ISO-8859-1` with `expected: "café" but was: "caf©Ã"`; under UTF-16 a base64 decode
throws `IllegalArgumentException: Illegal base64 character`; both go GREEN once the charset is
explicit. Since Java 18 the default *is* UTF-8 whatever the platform (JEP 400), so the defect cannot
be shown in the JVM the build already runs in — which is a reason to fork rather than a reason to
give up.

`ANoteIsCheckedBeforeItIsTrustedTest.theFactThatDidIt` asserts that the literal string
`A TEST MAY START A JVM` is still present in `checkers/FB.DM_DEFAULT_ENCODING.txt`. Without it the
family goes back to never producing a build.

---

## Part 2 — a test this build cannot run

**Present only when the flagged file's repo-relative path starts with `src/it/` or `src/test/`.
Application code gets nothing at all** — a note on every marker is a note nobody reads by the
fortieth.

Integration tree (`src/it/`):

```

THE FLAGGED LINE IS IN THE INTEGRATION TEST TREE, NOT IN APPLICATION CODE. This project binds
src/it to failsafe and excludes it from the surefire run this pipeline uses, and those classes need
a WebGoat serving on localhost:8080. Nothing here can execute that class, so a failure you see from
it is a connection error and not your defect. Do not write a test that calls into it. A defect in
test code has no caller in the application and usually no observable behaviour to assert on: the
honest answer is very often `no test`, and it is the expected one here. Give it in one line and
stop. Reasoning at length towards a test that cannot exist has cost this pipeline more than every
marker in this tree is worth.

```

Unit tree (`src/test/`): the same text with `UNIT` in place of `INTEGRATION` and **without** the
failsafe/localhost sentences — the head sentence is just
`THE FLAGGED LINE IS IN THE UNIT TEST TREE, NOT IN APPLICATION CODE.` and the paragraph resumes at
`A defect in test code has no caller…`. Surefire does run that tree; claiming it cannot would be
false, and a false sentence in the brief teaches the agent to disbelieve the rest of it. A test
asserts the unit variant does **not** contain `localhost:8080`.

The test on this (`ATestThisBuildCannotRunTest`) pins four strings for the integration case:
`INTEGRATION TEST TREE`, `localhost:8080`, `Do not write a test that calls into it.`, and both
`` `no test` `` and `expected one here` together — because the exit had to be named as the
**expected** answer and not merely permitted. It was already permitted, in a prompt, and cost thirty
minutes a marker.

Two incidents are folded into this paragraph:

- **Fifty-six of eighty-six runaway generations were the reproducer on this one kind of marker.** Its
  captured reasoning: *"Wait, but this is an integration test class (src/it/java), not a regular
  source class"* … *"The uploadTrickHtml method is private, so I can't directly test it"* … *"Let me
  think about this differently."* Round and round for half an hour, because the task has no answer
  and the only answer it was allowed to give was one sentence in a prompt it had long since left
  behind. `no test` was already *permitted*; the fix was to name it as the **expected** answer, in
  the brief, beside the reason.
- **A marker in `src/it` collects a free RED and settles `reproduced` on nothing.** Those classes
  need a WebGoat on localhost:8080, so when one is run its failure is `Connection refused` — a red
  build that proves the harness is absent, not that the defect is real.

Both facts are the program's to state. They are things it knows and the agent was left to work out.

---

## Part 3 — the flagged file, numbered

Every line of the flagged file is emitted with its number, and the flagged line is pointed at:

```
   39  }
>> 40      return s.getBytes();
   41  }
```

The exact shape per line is `">> "` or `"   "`, then the 1-based number, then **two spaces**, then
the line, then `\n`. The number is **not** padded, so the gutter widens as the file passes 9, 99 and
999 lines. `ALineNumberIsACheckableClaimTest` pins both `">> 40  line 40"` and `"   41  line 41"`.

**A marker with no line number points at nothing.** `flagged` is `0`, which equals no `i + 1`, so
every line gets `"   "` and no drift warning fires (`0 > lines.size()` is false). The listing is
still correct source; it simply makes no claim.

**Unnumbered source makes the marker's line an assertion nobody in the chain can check.** These
markers came off an analyser run against an older revision and some have drifted: `EncDec:67` points
past the end of a 64-line file; `TokenTest:47` lands on a blank line. Handed unnumbered
source, the reproducer decided for itself what the marker must have meant and wrote a test for that,
with nothing in the record saying it had substituted its own judgement.

### Drift past the end of the file

When and only when `flagged > lines.size()`, the listing is followed by:

```

THE MARKER POINTS AT LINE <flagged> AND THIS FILE HAS <size>. The analyser ran against an older
revision of it. Find what it meant — the same construct will usually still be here, moved — and say
in your answer which line you actually judged. If nothing here matches the checker, say so: a marker
about code that no longer exists is not a defect to prove.
```

It is appended after the last numbered line, separated by one `\n`, and it ends without a trailing
newline.

**A marker inside the file says nothing about drift, including on the last line.** Warning about a
real line would train the agents to ignore the warning; a test writes a 64-line file, flags line 64,
and asserts the string `THE MARKER POINTS AT LINE` is absent while `">> 64  line 64"` is present.
Drift *within* the file is caught by the checker note's line sentence instead, which is the check
that can see it.

### Unreadable

The whole numbered block is replaced by:

```
(could not be read: <IOException message> — use read_file)
```

Not fatal, and deliberately not silent: the agent holds `read_file` and can go and get it.

---

## Part 4 — sibling tests

The directory is derived, not searched:

```
checkout / fileOf(marker).replace("src/main/java", "src/test/java")  →  .getParent()
```

If the parent is `null` or not a directory, this part is empty. Otherwise **the first two entries
whose full path string ends in `Test.java`** are appended in full, each under:

```

An existing test beside it, <FileName.java> — this is how this project stands a subject up:
<the entire file>
```

Order comes from `Files.list`, which is unspecified — "the first two the filesystem lists", not the
two most relevant. Two, because the brief pays for them by length.

**Why in full:** they are what a reproducer reads to learn how this project stands a subject up —
the harness, the datasource, the annotations — and it reads them every time, one tool call each,
against a budget that used to be 25 calls total.

Note that for a marker already inside a test tree the `replace` is a no-op, so the siblings are the
flagged file's own neighbours.

**One unreadable sibling is skipped, not fatal.** Its `IOException` is swallowed per file. Note the
order of operations: `.limit(2)` is applied to the filtered stream *before* anything is read, so an
unreadable sibling **consumes one of the two slots** — a directory with four `Test.java` files can
still yield one section, or none. That is the accepted cost of not failing a brief over a file
nobody asked for.

An `IOException` from listing the directory yields an empty part. A brief without siblings is still
a brief.

---

## Inadmissibility — what this run made

This block is **not part of the brief**. It is prepended to the brief on exactly the two paths that
reach the verdict agent, where an argument rather than a build is going to settle the marker:

```java
argued(whatThisRunMade() + brief + "\nNo test was written for this marker. The reproducer said:\n" + …);
argued(whatThisRunMade() + brief + "\nNO TEST COULD BE MADE TO FAIL ON THIS CODE. The reproducer was asked twice; the last build was:\n" + …);
```

Those are the only two call sites of `argued`, and `argued` itself appends
`"\n\n" + whatExecutionProduced()` to whatever it is given — so the full task the verdict agent sees
is `whatThisRunMade() + brief + <why we are here> + "\n\n" + whatExecutionProduced()`.

The inadmissibility block goes **first**, ahead of everything else, because it governs how the rest
is read.

### The failure it exists for

The verdict agent reads the tree, and by the time it is asked the tree contains the test this run
wrote and the patch this run applied. **Thirteen settlements in a 67-marker run rested on that:
`by-design`, because "a test depends on this behaviour", where the test was the one written eleven
minutes earlier by the reproducer, in this prove, about this marker.** Circular — and invisible in
the record, because a citation of our test reads exactly like a citation of theirs.

### How the line is drawn

`git status --porcelain`, run in the checkout. Everything it lists — untracked or modified — is
ours; everything else was here before we were. Each row is taken from index 3 onward and stripped,
which is the path in porcelain v1's `XY <path>`; rows of three characters or fewer yield `""` and
blank results are dropped. There is no special handling of rename rows or of paths porcelain quotes:
whatever follows index 3 is the entry, verbatim. The list is read by a model, not parsed, so
legibility is the whole requirement.

The notice, when the list is non-empty:

```

INADMISSIBLE — THIS RUN CREATED THESE, AND THEY ARE NOT EVIDENCE ABOUT THE PROJECT:
  <path>
  <path>
A test written to demonstrate this marker cannot also show that the behaviour is intended, and a
patch written for this marker cannot show that the code was already correct. Cite only what was here
before this run started — the lesson text, an assignment, a comment, a committed test, a caller. If
your argument needs one of the files above, you do not have an argument.
```

Paths only; no contents. The consequence is spelled out, so the judge knows what to do instead of
noting the caveat and citing it anyway.

### The three failure directions, all toward silence

- **A clean tree produces nothing.** Before the reproducer runs there is nothing of ours, and saying
  so at length would train the judge to skim past the notice on the runs that need it.
- **A modified file is ours as surely as a new one.** The fixer edits in place, so a committed file
  the patch touched becomes inadmissible — even though the same file, untouched, would have been
  admissible evidence about the project.
- **`git status` failing produces nothing.** Somewhere that is not a repository says nothing rather
  than guessing: inventing an inadmissible list out of a failed command would block arguments that
  were fine. This is the one direction where the fail-safe is *permissive*, and it is deliberate.

---

## What gets appended after the brief

The brief is the prefix; the prove appends what each stage needs. In order of the chain (the loop
itself is chapter 02):

| appended to `brief` | when | contains |
|---|---|---|
| the empty-answer re-ask | reproducer wrote no file | `use write_file`, or answer with exactly `` `no test` `` and a one-line reason; "An empty answer is not a decision." |
| `Prove.GREEN_RED` | first RED compiled and PASSED | why a passing RED documents the defect instead of observing it, the fork-a-JVM escape, and the `no test` exit. Across one run, 16 of the 33 markers that reached a build had their first RED pass and 13 settled on it — six `by-design`, seven `false-positive`, every one argued from a build that showed nothing |
| the compiler's own words | RED or GREEN would not build | `build.summary()` verbatim, with "Fix exactly that" (plus "write the file again, and end with the test class name" for the reproducer, "Do not change the test." for the fixer) |
| the critique | proof-critic answered `reducible` | the reviewer's objection, carried through subsequent build retries |
| `evidence` | from the fixer onward | `"\nThe failing test:\n" + test + "\nRED:\n" + red.summary()` — assembled once so a retry is never poorer than the call it replaces |
| the diff and the reach sentence | fix-critic, **first call only** | `git diff -U3 -- . :(exclude)*src/test/* :(exclude)*src/it/*`, plus a computed sentence on whether a hunk spans the flagged line. The re-certification after a rejected patch carries neither |
| the settlement | estimator | `"\n\nIt settled as: " + disposition + "\n\nThe record:\n" + because` |

The one constant across all of them: **no stage ever receives less than the brief**, and no stage
receives a *different* brief.

---

## Failure-direction summary

| condition | result | why this direction |
|---|---|---|
| no note for the checker | absence stated; agent asked to name the construct it assumed | a wrong note is unreadable as wrong; a stated guess is auditable |
| note file has no newline, or is unreadable | treated as **absent**, not as an empty note | the safe failure: absence is announced, a body-less note would not be |
| note's regex will not compile | line sentence omitted, note body kept | swallowed exception; a test guards the corpus instead |
| note's regex matches everything | `Line <n> does contain it.` for every marker | **the one failure with no safe direction** — nothing at runtime can see it, which is why rule 2 of the corpus test exists |
| flagged file unreadable, in `Checkers.where` | line sentence omitted | must not claim the line is wrong when it could not look |
| flagged file unreadable, in `source` | `(could not be read: … — use read_file)` | the agent can still fetch it |
| marker's line missing or non-numeric | `0` | matches no line and no hunk; no `>>` pointer, no drift warning, and the note reports `LINE 0 DOES NOT CONTAIN…` |
| analyser path has no known source root | path used as given | better than resolving outside the checkout |
| flagged line inside the file | no drift warning | a warning on every marker is a warning nobody reads |
| sibling directory missing or unlistable | no sibling section | the brief is still complete |
| one sibling unreadable | that one skipped, and it still spent one of the two slots | not worth failing a brief over |
| flagged file in application code | no test-tree warning | same |
| `git status` fails / not a repository | no inadmissibility notice | inventing one from a failed command would block sound arguments |
| working tree clean | no inadmissibility notice | so the notice still lands on the runs that need it |
