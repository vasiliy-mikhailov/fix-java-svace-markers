# 05. Checker notes

A **marker** is one unit of work: the string `repo|file|line|checker` and nothing else. The
**checker** is the fourth field, a bare family name — `FB.DM_DEFAULT_ENCODING`, `HANDLE_LEAK`,
`DEREF_OF_NULL.RET.STAT` — with no message, no trace and no explanation of what the analyser
objected to. Every agent that touches the marker has to reconstruct the claim from that name, and
several reconstructed it wrong in ways that decided the marker outright: `JWT:180` bound
`DM_DEFAULT_ENCODING` to `Charset.defaultCharset()` because that was the only charset-looking token
in the statement; `VulnerableTaskHolder:69` spent its single RED on a semicolon payload because
nobody knew `Runtime.exec(String)` starts the first whitespace-separated token, so a shell
metacharacter chains nothing.

A **note** is that reconstruction, done once, in a file, by a reader with the source open. It is
data in `resources`, not prose in a prompt, because it is per-family and the prompts are not.

**Every checker named by the queue has a note.** The queue in this repository is
`data/svace/webgoat-markers-356.csv` (`Severity,Checker,File,Line`, 356 rows). It names 48 distinct
checkers and `agent/src/main/resources/checkers/` holds exactly 48 `.txt` files, one per name — no
checker without a note, no note without a checker. Before the notes existed, 44 families had none,
covering 308 of the 356 markers; the four families that did have one accounted for the other 48
markers.

---

## The file

```
agent/src/main/resources/checkers/<CHECKER>.txt
```

The file name is the checker family verbatim, dots included: `FB.DM_DEFAULT_ENCODING.txt`,
`HANDLE_LEAK.EXCEPTION.txt`, `DEREF_OF_NULL.RET.LIB.txt`.

**Line 1 is a regex. Everything after the first newline is the note.** Nothing else is structured;
the body is prose read by a model. The head of `FB.DM_DEFAULT_ENCODING.txt`, with line 1 verbatim:

```
getBytes\(\s*\)|new String\s*\(\s*[^,)]+\s*\)|new (FileReader|FileWriter|InputStreamReader|OutputStreamWriter|PrintWriter|Formatter|Scanner)\s*\([^,)]*\)$
A byte/char conversion with no charset argument, so it uses the JVM's default. `getBytes()`,
`new String(byte[])`, `new FileReader(...)`, `new PrintWriter(File)` and friends.
It is NOT `Charset.defaultCharset()` — ...
```

The reader is `Checkers.read`:

```java
String safe = checker.replaceAll("[^A-Za-z0-9._-]", "");
try (InputStream in = Checkers.class.getResourceAsStream("/checkers/" + safe + ".txt")) {
    if (in == null) {
        return null;
    }
    String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    int nl = all.indexOf('\n');
    return nl < 0 ? null : new String[] {all.substring(0, nl).strip(), all.substring(nl + 1).strip()};
} catch (IOException unreadable) {
    return null;
}
```

Fixed by that code, and a rebuilder cannot vary them compatibly:

- The resource is read as **UTF-8**. Notes contain `café`, `caf©Ã`, em dashes.
- The checker name is **sanitised to `[A-Za-z0-9._-]`** before it becomes a resource path.
  Disallowed characters are **deleted, not replaced**, so dots survive (`FB.DM_DEFAULT_ENCODING`
  addresses `FB.DM_DEFAULT_ENCODING.txt` unchanged) and a checker field carrying a slash or `..`
  collapses into a name that cannot leave `/checkers/` — it simply misses.
- Both halves are **stripped**.
- **A file with no newline in it is not a note.** `read` returns `null`, exactly as if the file did
  not exist. This is deliberate: the failure is *absent*, which is announced (below), rather than a
  regex-that-is-really-prose being matched against source lines.
- **Every other way of failing to read also returns `null`**: no such resource, and `IOException`
  while reading. There is no path on which a partial or unparsed file becomes a note.

---

## The two failure directions, which are opposite

A note can fail in two ways and they are not symmetric.

**A missing note is safe and is said out loud.** `Checkers.note` returns, and this exact sentence is
the whole of what the agent gets about the checker:

```
THIS PIPELINE HAS NO NOTE FOR <CHECKER>. Say in your answer which construct you took that name to
mean, so the next reader can tell a correct answer from a lucky one.
```

(Emitted as one line, prefixed by `\n\n` and followed by `\n`.)

The demand for a record is the point. The agent is going to guess either way; a guess this program
can read afterwards is worth more than one it cannot, and it is what turns "the model was wrong
about the checker" from an invisible cause into a line in the transcript.

**A wrong note is unsafe and is invisible.** At the moment it is read, a confident wrong note is
indistinguishable from a right one, and every agent on that family acts on it — the note is not one
opinion among several, it is the premise all of them share. That asymmetry is why absence is stated
rather than papered over, and why the note body is checked by a second reader rather than trusted
(below).

---

## `Checkers.note` — what the agent actually receives

```java
static String note(Path checkout, String marker, String checker, String file, int line)
```

The `marker` parameter is carried for context and not read; the three fields `note` uses are passed
separately, already split by the caller.

With a note present, the output is exactly:

```
\n\nWHAT <CHECKER> REPORTS: <the whole body, stripped>\n<the where() sentence>\n
```

The **whole** body goes in, not a summary and not the first paragraph — the tiers, the measured
numbers, the `WHAT GOES WRONG HERE` list, the second reader's corrections. The body is written to
be read by the agent that is about to write a test.

`Prove` splices it into the **brief**, the single string every agent on the marker is handed:

```java
brief = "Marker: " + marker
        + "\nThe checkout is your workspace; read further only if you need to.\n\n"
        + Checkers.note(checkout, marker, checkerOf(marker), fileOf(marker), lineOf(marker))
        + aTestThisBuildCannotRun(marker)
        + "The flagged file, " + fileOf(marker) + ":\n" + source(checkout, marker)
        + siblingTests(checkout, marker);
```

The fields come off the marker by position, splitting on `\|`, zero-based: `checkerOf` is
`parts[3]`, `fileOf` is `parts[1]` truncated at the first of `src/main/java/`, `src/test/java/`,
`src/main/`, `src/` that occurs in it, and `lineOf` is `parts[2]` parsed as an `int`, or `0` when
the field is missing or does not parse.

The dashboard reads the same files for a different audience, with its own copy of the sanitiser and
the resource lookup — the two readers are duplicated, not shared. `Dashboard.claimIs` strips the
body and shows **only the first paragraph** — up to the first `\n\n` — because the rest is an
instruction to an agent and not an explanation to a person. Three consequences for whoever writes a
note:

- **The first paragraph must stand alone as the definition of the claim.** It is the only part a
  human reader of the marker page ever sees.
- With no note, the page says `This pipeline has no note for <CHECKER>, so what it means here is
  whatever the agents below took it to mean.`
- If the resource exists but cannot be read, `claimIs` returns the empty string and the page carries
  no claim line at all. Absence-of-file is announced; unreadability is not.

---

## `where()` — whether the flagged line holds the construct

This is the one sentence in the note that is arithmetic rather than opinion, and it exists because
**the markers came off an older revision than the checkout**. The queue records no revision — its
columns are `Severity,Checker,File,Line` and nothing else — so the reported line is a hint and not
an address. Told only "line 63", an agent reasons about whatever
is at line 63 today. Told that line 63 does not match and line 62 does, it reasons about the right
statement — or says plainly that it cannot find one.

The algorithm, in full:

1. Read `checkout.resolve(file)` as lines. On `IOException` or any `RuntimeException`, **return the
   empty string** — no sentence at all.
2. Compile line 1 of the note as a `Pattern`. On `PatternSyntaxException`, **return the empty
   string**.
3. If `1 <= line <= lines.size()` and the pattern `find()`s in `lines[line-1]`, emit:
   ```
   Line <N> does contain it.
   ```
4. Otherwise scan the file from line 1 downward, collecting every line number `n` where the pattern
   matches **and** `|n - line| <= 40`, joined with `", "`. The scan breaks once the accumulated
   string holds `NEARBY = 3` commas — so **at most four line numbers**, and they are the first four
   in file order within the window, not the four closest. (The constant is named for three; the
   break is tested after the append, so the fourth number is already in.) Emit, on one line:
   ```
   LINE <N> DOES NOT CONTAIN THE CONSTRUCT THIS CHECKER REPORTS. The nearest lines that do are <list>. Judge the one the checker meant, and say in your answer which line you judged.
   ```
5. If that list is empty, emit instead, on one line:
   ```
   LINE <N> DOES NOT CONTAIN THE CONSTRUCT THIS CHECKER REPORTS. Neither does any line near it. The marker may have drifted off this file entirely — say so rather than judging whatever is at that line.
   ```

Both silent returns in steps 1 and 2 are the safe direction and both are load-bearing:

- **A file the checkout does not have costs the line check, not the note.** The body still arrives in
  full; what does not arrive is any claim about the line. The program must never say "line 3 does
  not contain the construct" when it could not look.
- **A regex that does not compile is swallowed.** That is safe at run time and dangerous at authoring
  time — the note still looks present and complete, and the sentence the agent most needs has gone
  missing without a trace. It is checked in a test instead (below).

A marker with no parseable line number arrives here as `line == 0`, fails step 3 unconditionally, and
gets the negative sentence with the window `1..40`.

---

## What a good note contains

The observability fact first, everything else after it.

**1. What the checker reports, and what it is not.** One paragraph. Name the construct, and name the
neighbouring construct it is constantly mistaken for, because that is where the reconstructions went
wrong:

- `FB.DM_DEFAULT_ENCODING` — "It is NOT `Charset.defaultCharset()` — that call is how you READ the
  setting, not how you depend on it accidentally, and a marker has already been settled on that
  confusion."
- `FB.UC_USELESS_OBJECT` — "It is a claim about one *allocation site*, not about the variable", with
  `DLS_DEAD_LOCAL_STORE` and `UC_USELESS_OBJECT_STACK` named as the neighbours.
- `DEREF_OF_NULL.RET.STAT` — on `T x = (T) v.m();` the flagged value "is NOT `m()`'s result being
  cast — it is `v`, the receiver".
- `FB.HARD_CODE_PASSWORD` — "a name-proximity heuristic, not a data-flow proof", which is why it
  fires on `.passwordParameter("password")` and on a message key.

**2. HOW YOU MAKE IT VISIBLE — the sentence the whole family turns on.** A **RED** is a test that,
in `prompts/reproduce-doer.txt`'s words, "FAILS on the current, UNPATCHED code precisely because of this
defect, and would PASS once it is fixed"; a **GREEN** is that same test passing after the fix. The
note's job is to say whether a RED is possible for this family, and by what instrument. Everything
measured is labelled as measured, with the numbers:

- `HANDLE_LEAK` tier 1: "one-entry zip, N=300, default heap: delta 300, 300, 300, 300, 300 (five
  runs)" versus "five-entry zip, N=300: delta 2, 31, 5, 17, 18". Hence: pin the fixture, assert
  `delta >= N/2`, never call `System.gc()` — "I measured one `System.gc()` take a 300-fd delta to 0".
- `HANDLE_LEAK` tier 2: 200 leaked calls → session delta 200; 200 in try-with-resources → delta 0.
- `DEREF_OF_NULL.RET.STAT`: the actual stack frame the RED must produce, quoted, plus "That frame —
  naming the flagged line AND the null variable — is what 'failed for the right reason' means for
  this family. Read it. Do not settle for 'an NPE was thrown'."

**3. When it cannot be shown, say so and say what to write instead.** A note that leaves this
unstated buys a family of manufactured REDs.

- `FB.DLS_DEAD_LOCAL_STORE`: "THIS IS ALMOST NEVER OBSERVABLE. Removing a dead store changes no
  return value, no field, no output and no timing — that is what makes it dead."
- `HANDLE_LEAK` tier 3: "the correct settlement is 'real omission, release guaranteed by the
  enclosing try-with-resources (a) / by the JVM process reaper (b), not demonstrable by exhaustion' —
  write that down instead of manufacturing a RED."
- `FB.UC_USELESS_OBJECT` variant (1): "write down that the finding is TRUE and NOT TEST-OBSERVABLE".

**4. Polarity.** The prescribed assertion must fail before the fix. Two families —
`DEREF_OF_NULL.RET` (6 markers) and `TAINTED_PTR` (3) — shipped notes whose recipe passed on
unpatched code and had to be corrected by the second reader; copied literally, such a test certifies
the defect and reports the fix as a regression. `TAINTED_PTR`'s correction opens its first item with
"POLARITY — THE PRESCRIBED TEST IS GREEN ON UNPATCHED CODE" and fixes it by asserting the SAFE
property instead, so the test fails now and passes after the fix. `DEREF_OF_NULL.RET.STAT` states
the trap directly:
`assertThrows(NullPointerException.class, ...)` "PASSES on today's buggy code and FAILS after the fix
— a regression lock on the defect, inverted."

**5. Which verdict words follow from which observation.** `HANDLE_LEAK`: "'the fd count did not move'
is not 'false positive' and not 'not a defect' for tier 3". `FB.UC_USELESS_OBJECT`: "'Harmless,
therefore false positive.' Not the same claim." `FB.DLS_DEAD_LOCAL_STORE`: "`by-design` needs
evidence that somebody CHOSE this ... 'Removing the assignment would be functionally identical' is an
argument that the fix is safe, which is an argument FOR the fix and not evidence of intent."

**6. Where the markers actually are, and where they are not.** `FB.HARD_CODE_PASSWORD` names its
trap: "There is no src/main line in this family outside IDORLogin — if you find yourself
demonstrating on DefaultUserInitializer, WebSecurityConfig, JWTRefreshEndpoint, MissingFunctionAC or
DefaultCredentialsTask, you are working on a line that carries no marker."

**7. Lesson safety, specifically and never as a blanket.** The subject is a deliberately vulnerable
teaching application, so "it is a lesson" is available as an excuse for declining every marker and as
an excuse for breaking every assignment. Notes name the assignment line and the defect line
separately: "ProfileZipSlip's assignment is the unnormalized `new File(tmpZipDirectory.toFile(),
e.getName())` at line 79 — closing the ZipFile at 75 leaves the traversal fully intact, so neither
decline the fix as 'it's a lesson' nor add normalize()/startsWith around 79."

**8. NOT LEAKS / verified, do not file.** An explicit list of shapes that match the regex and are not
defects, each with how it was checked: "`FileCopyUtils.copy(in, new FileOutputStream(f))` at
Salaries.java:47-49 ... I ran a close()-recording FileOutputStream subclass against spring-core 7.0.8;
both `copy(InputStream,OutputStream)` and `copy(byte[],OutputStream)` closed it."

---

## The DM_DEFAULT_ENCODING story

The note that mattered most, and the reason the mechanism exists at all.

Thirty-three `FB.DM_DEFAULT_ENCODING` markers had never produced a single build. Every agent that
met one reached the same answer in nearly the same words: *the default charset is fixed at JVM
start-up, therefore no test can vary it, therefore this cannot be demonstrated.*

The first clause is true. The conclusion does not follow. **A test may START a JVM.**

```java
ProcessBuilder b = new ProcessBuilder(
        System.getProperty("java.home") + "/bin/java",
        "-Dfile.encoding=ISO-8859-1",
        "-cp", System.getProperty("java.class.path"),
        SomeMainThatExercisesTheSubject.class.getName());
```

Run by hand against this checkout, `EncDec` goes RED under `-Dfile.encoding=ISO-8859-1` with
`expected: "café" but was: "caf©Ã"`, and GREEN once the charsets are explicit. Under UTF-16 a base64
decode throws `IllegalArgumentException: Illegal base64 character`.

Since Java 18 the default *is* UTF-8 on every platform (JEP 400), so the defect cannot be shown by
running the same JVM the build runs in — "That is a reason to fork, not a reason to give up".

Two things a rebuilder must carry:

- The sentence `A TEST MAY START A JVM` is asserted by a test against the note file, with the reason
  attached: "without it the family goes back to never producing a build". It is not a phrase to
  paraphrase.
- The failure mode it illustrates is a **true premise with a false conclusion**, reached
  independently by many agents in nearly identical words. Convergence is not corroboration here —
  every agent shares the same gap. A note is the only place a fact that nobody has can be added.

`FB.COMMAND_INJECTION.txt`, written in the same commit, carries the companion failure —
`VulnerableTaskHolder:69` spent its single RED on a semicolon payload, and the note opens "HOW IT
ACTUALLY BEHAVES, because a marker has already been spent getting this wrong": `Runtime.exec(String)`
"does NOT run a shell. It splits the string on whitespace with a StringTokenizer and starts the first
token", so `;`, `&&`, `|`, backticks and `$( )` "chain NOTHING". A test asserting that
`"ls; touch /tmp/pwned"` creates a file "will not fail, and it will not fail for a reason that has
nothing to do with whether the marker is real."

---

## The second reader

**A note is checked against a real checkout before it is trusted, and the check is recorded in the
note rather than replacing it.**

29 of the 48 notes carry, verbatim and as a line of its own, this sentinel:

```
CHECKED AGAINST THE CHECKOUT BY A SECOND READER WHO RE-RAN THE CLAIMS ABOVE. Where the two disagree, THIS IS THE ONE THAT HOLDS.
```

Everything below that line supersedes everything above it. The draft stays because a correction is
usually a patch and not a replacement — several corrections say in effect *keep regimes (A) and (C)
exactly as written* — and because the disagreement itself is information for the next reader.

All 29 are among the 44 notes written in one commit; the four earliest notes, written before the
second reading existed, carry none. That commit's own account is "Seventeen stood; twenty-nine carry
a correction" — the prose counts in this repository disagree with each other and with the files (the
enforcing test's javadoc says thirty-one), so treat **29 sentinel lines on disk** as the fact and the
ratio as a story. Three corrections were worth the exercise on their own:

- **`FB.HARD_CODE_PASSWORD` (17 markers)** — the entire recipe had been written against a file that
  carries no marker and was added to HEAD after the scan. All 17 markers would have burned a build on
  unrelated code. The note now names that file as the trap.
- **`HANDLE_LEAK` (15) and `HANDLE_LEAK.EXCEPTION` (5)** — both drafts assumed a HikariCP pool and
  prescribed a pool-exhaustion RED. `DatabaseConfiguration.java:28-37` declares a
  `DriverManagerDataSource`; there is no pool anywhere in `src/`, so the prescribed RED could never
  fire. The correction leads with the fact that decides every JDBC marker in the family and is
  measured: 2000 iterations, session delta 0. It also says what to do instead — "just count sessions,
  not pool timeouts" — and warns that a Hikari harness "compiles, it goes RED, and it proves nothing
  about this application".
- **`DEREF_OF_NULL.RET` (6) and `TAINTED_PTR` (3)** — inverted polarity, as above.

The second reader's first target is always the observability claim, because that is the sentence
every agent on the family acts on.

---

## What is enforced automatically

`ANoteIsCheckedBeforeItIsTrustedTest` holds the shape, not the truth. Truth is a job for a reader
with the source. Two of the five checks — `compiles` and `notUniversal` — exist because those
failures happen **silently inside `where()`**: a first line that will not compile is swallowed by
`catch (PatternSyntaxException)` and returns `""`, and a first line that matches everything reports
"Line 63 does contain it" for every line of every file. Either way the note still looks present and
the sentence the agent most needs is quietly missing or a lie. The test iterates every `.txt` under
`src/main/resources/checkers`, sorted.

| Check | What it asserts | Why |
| --- | --- | --- |
| `twoParts` | every `.txt` has a `\n` at index > 0 and a non-blank first line | `read` returns `null` otherwise — the note would be silently absent rather than wrong, "which is at least the safe failure, but it is still not a note" |
| `compiles` | line 1 of every note compiles as a `Pattern` | `where` swallows `PatternSyntaxException` and returns `""`, costing the note "its one arithmetical sentence" while the note still looks present |
| `notUniversal` | no note's regex matches any of `""`, `"    // just a comment"`, `"    }"`, `"import java.util.List;"`, `"package org.owasp.webgoat.container;"`, `"        }  // done"` | a regex that fires on a closing brace reports "Line 63 does contain it" for every marker, "including the agents whose marker has drifted off the file — which is the exact case this sentence exists to catch" |
| `saysHowToSeeIt` | the body (everything after the first `\n`), stripped, is at least 200 characters | "a note that only renames the checker leaves the agent exactly where it was" |
| `theFactThatDidIt` | `FB.DM_DEFAULT_ENCODING.txt` contains `A TEST MAY START A JVM` | without it the family goes back to never producing a build |

Two notes on `notUniversal`: it caught `UNREACHABLE_CODE` matching a blank line and a bare closing
brace when the notes were first written. And a bare `return;` is deliberately **not** in the
contentless list — "it looks contentless and is not: for UNREACHABLE_CODE and the null-return
families a control-flow statement is precisely the construct, and listing it here failed a note for
being right."

`AWholeFamilyWrittenOffTest` holds the emitted sentences end to end, against a `@TempDir` checkout
holding a five-line `src/main/java/Subject.java`: the fork-a-JVM fact and `-Dfile.encoding` and
`Line 3 does contain it.`; the ``It is NOT `Charset.defaultCharset()` `` sentence together with `DOES
NOT CONTAIN THE CONSTRUCT` when the flagged line is the `defaultCharset()` one; `The nearest lines
that do are 2` for a line flagged at 4 when the construct sits at 2; `chain NOTHING` plus either
`does NOT run a shell` or `splits the string` for the exec family; `NO NOTE FOR
SOME.CHECKER_NOBODY_WROTE_UP` plus `which construct you took that name to mean` for an unknown
checker; and, for a file the checkout does not have (`src/main/java/Gone.java`), that the body still
arrives and `DOES NOT CONTAIN` does not.

---

## Numbers of record

Against the real checkout and the real queue, at the time the notes were completed:

- 48 checker families in the queue, 48 notes, no marker without one.
- 281 of 356 flagged lines hold the construct.
- 65 have drifted and are told which nearby lines do.
- 8 are told honestly that nothing near them matches.
- 2 name a file this checkout does not have — and receive the note with no line claim at all.

The first line is checkable from this repository alone. The last four are one run of `where()` over
a WebGoat checkout that this repository does not contain; they partition the queue exactly
(281 + 65 + 8 + 2 = 356) and are a measurement, not an invariant — a rebuilder against a different
checkout will get different splits and should expect the drifted and missing-file buckets to be
non-empty, which is the whole reason the sentence exists.
