# 05. Checker notes

A **marker** is one unit of work: the string `repo|file|line|checker` and nothing else. The
**checker** is the fourth field, a bare family name — `FB.DM_DEFAULT_ENCODING`, `HANDLE_LEAK`,
`DEREF_OF_NULL.RET.STAT` — with no message, no trace and no explanation of what the analyser
objected to. Every agent that touches the marker has to reconstruct the claim from that name, and
several reconstructed it wrong in ways that decided the marker outright: one bound
`DM_DEFAULT_ENCODING` to `Charset.defaultCharset()` because that was the only charset-looking token
in the statement; one spent its single RED on a semicolon payload, not knowing that
`Runtime.exec(String)` starts the first whitespace-separated token, so a shell metacharacter chains
nothing.

A **note** is that reconstruction, done once, in a file. Its subject is the **checker**: what that
name means, what it is constantly confused with, and what a demonstration of it would have to
assert. Its subject is never the repository the harness is currently pointed at. It is data in
`resources`, not prose in a prompt, because it is per-family and the prompts are not.

**Every checker named by the queue has a note.** The queue in this repository is
`data/svace/webgoat-markers-356.csv` (`Severity,Checker,File,Line`, 356 rows). It names 48 distinct
checkers and `agent/src/main/resources/checkers/` holds exactly 48 `.txt` files, one per name — no
checker without a note, no note without a checker. The queue is the corpus this harness happens to
be aimed at; the notes are part of the harness. That distinction is the whole of the next section
and it is the one a rebuilder gets wrong.

---

## The line: a note is about the checker, never about the subject

`Checkers` pastes the note verbatim into the task of all fifteen agents that judge a marker of that
family. Whatever the note knows, they were told rather than found.

The notes did not start out knowing anything about the subject and grew into it, run after run, as
each pass wrote down what it had learned. At the point this was caught they carried the subject's
class names, its architecture vocabulary, its build quirks, and — for forty of the forty-eight — the
specific `File.java:LINE` sites in its tree. Across the directory that came to **457,650
characters** of subject knowledge. On one `TAINTED_PTR` marker the note alone was **10,727 of the
planner's 13,549-character task**: 79% of what the planner was given before it had read a line of
code.

Two things were wrong with that, and the second is worse than the first.

**They carried the answer.** *"All three TAINTED_PTR markers land on lesson code and all three are
by-design."* *"The honest pipeline outcome is `can_prove:false`."* Those markers were never judged.
The verdict was handed over in the prompt — in the `by-design` direction the owner had already
rejected twice — before any agent had opened the file. A pipeline whose purpose is to settle a
marker by execution cannot ship the settlement in the brief.

**They claimed authority the reader could not check.** The notes spoke in an unattributed first
person (*"I ran"*, *"Both verified"*, *"I measured"*), and one line asserted precedence over the
agent's own eyes: *"Where the two disagree, THIS IS THE ONE THAT HOLDS."* An agent cannot identify
that author, cannot re-run that measurement, and cannot weigh it against the source in front of it.
It can only defer.

And every sentence of it was false the moment the harness was pointed at a second repository, which
is the ordinary case and not the exceptional one.

### What may not appear in a note

`TheHarnessDoesNotKnowItsSubjectTest.theNotesAreAboutCheckers` compiles each of these against the
prose of every note — everything after the first newline; line 1 is the construct regex and may
legitimately contain anything. Each is a regex and a reason, and the reason is why it is not a style
preference:

- `(?i)webgoat` — names the repository under test, so the note is false the moment the harness is
  pointed somewhere else.
- `org\.owasp` — names the subject's packages.
- `[A-Za-z_$][\w$]*\.java:\d+` — cites a specific site in the subject's tree. The marker under
  judgement is the agent's to read, and a note that has already read it has done the work the
  pipeline exists to do.
- `\bI (?:ran|wrote|measured|sent|re-ran|verified|read|got)\b` — speaks in a first person the
  reading agent cannot identify or check, and arrives with more authority than the evidence
  deserves.
- `(?i)second reader|THIS IS THE ONE THAT HOLDS` — claims precedence over what the agent sees in
  the code itself.
- `(?i)\blessons?\b|@AssignmentHints|AssignmentEndpoint|lessonCompleted|WebWolf` — is the subject's
  vocabulary for its own architecture, which the harness has no business knowing.
- `(?i)(deliberately|intentionally) (vulnerable|insecure|broken)|vulnerable on purpose|purpose-built to be`
  — assumes the repository under test is a deliberately vulnerable teaching application: the
  subject's defining property with its name filed off. Pointed at ordinary code, the sentence rebuts
  an argument nobody would make.
- `(?i)this checkout|this repo\b|in this tree` — points at the tree the harness happens to be aimed
  at right now.
- `(?i)a marker has already been|markers? (of this family|in this family)|has been run by hand|never produced a single build`
  — reports the history of one run over one queue: a measurement the reading agent cannot check,
  false the moment the harness is pointed elsewhere.

**Two things are deliberately NOT banned**, and a rebuilder tightening this list will break notes
that are right:

- `assignment` — a dead-store note has to talk about assignment statements.
- `by-design` and `unprovable` — the pipeline's own state words. Explaining how to REACH a state is
  exactly a note's job; handing one over for a named site is what is forbidden, and naming sites is
  what the third rule is for.

Both exclusions are the same shape as the bare `return;` left out of the contentless-line list in
`notUniversal` (below): a pattern that looks like contamination and is not, because for some family
it is precisely the subject matter. Every tightening of either list should be checked against the
notes that would start failing.

### Why it is a list of regexes and not a principle

The first sweep searched for the subject's **name** — `webgoat`, `org.owasp`, `File.java:NN` — and
four notes came back clean. Two of those four were then used as the model for rewriting the other
forty-four. They were not clean; they had merely stopped saying the name. That is how *"a marker has
already been spent getting this wrong"* spread through the directory as an approved idiom: it names
no file and no package, and it is still the history of one run over one queue, asserted to an agent
that has no way to check it.

The last four rules in the list above exist because of that sweep. Each of them catches text that
passes a search for the subject's name. A rebuilder who replaces the list with "don't mention the
subject" will rebuild exactly the four notes that were declared clean.

---

## The size expectation

`TheHarnessDoesNotKnowItsSubjectTest.theNotesAreNotThePrompt` fails any note over **8,000
characters**. A checker explains itself in a page; a note past that length has started accumulating
findings again, which is how the last one reached 79% of the planner's task.

The bound is a smoke alarm, not a target. What the ceiling is really measuring is whether the note
has gone back to being a record of what previous runs concluded, because that is the only thing that
makes a note grow without bound — the checker's semantics do not.

| | Before | Now |
|---|---|---|
| Notes | 48 | 48 |
| Total across the directory | 457,650 | 215,462 |
| Mean | 9,534 | 4,489 |
| Largest | 20,353 | 7,033 |

A note that shrinks below **200 characters of prose** fails a different check: it no longer says
what its checker means, which is worse than having no note at all, because the agent believes it.
The working range is roughly 900 to 7,000 characters, and the long ones are long because the family
genuinely splits — `FB.EI_EXPOSE_REP2` has to separate the value case from the collaborator case and
give a discriminator, `HANDLE_LEAK` has three tiers with different instruments — not because they
have more history to report.

---

## The first paragraph is the claim line

**This is the constraint most likely to be missed, because nothing in the note's own file says it.**

Two readers read these files, with their own copies of the sanitiser and the resource lookup — the
readers are duplicated, not shared. `Checkers` reads the whole body for an agent.
`ApiMarker.claimNote` reads the same file for a person, and takes **only the first paragraph** — the
prose up to the first `\n\n`:

```java
String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
int nl = all.indexOf('\n');
String note = nl < 0 ? "" : all.substring(nl + 1).strip();
int para = note.indexOf("\n\n");
return para < 0 ? note : note.substring(0, para).strip();
```

That paragraph is the claim line on the marker page. It is the only part of the note a human reader
of a marker ever sees, and it sits directly above the flagged source as the answer to *what does
this checker say is wrong here*. Consequences for whoever writes a note:

- **The first paragraph must stand alone as the definition of the construct.** It cannot open with a
  tautology. "`FB.URF_UNREAD_FIELD` reports an unread field" restates the name, and the name is
  already on the page an inch above it — a reader who could decode the name did not need the note,
  and a reader who could not is no better off. It has to say what makes a field unread, what the
  checker counts as a read, and what it is being confused with.
- **It must not open with the instrument or the verdict.** `HOW YOU MAKE IT VISIBLE` is the second
  paragraph, always. A claim line that opens on the test recipe describes the demonstration to
  somebody who has not yet been told what is being demonstrated.
- The regex on line 1 is dropped before the paragraph is taken — it is machinery for `Checkers` and
  reads as noise on a screen.
- **With no note, `claimNote` returns `null`**, and the sentence a reader sees is written in
  `ClaimCard.tsx`, not on the server: *"nothing is bundled for `<CHECKER>`, so what it claims is
  only what its name says — the flagged line below is the whole of the evidence on this page."* The
  server sends the fact; the screen writes the prose. The earlier version returned that sentence
  from the server, and a reader could not tell "this checker has no note" from "the note says that
  sentence".
- An unreadable resource also returns `null`, and is therefore indistinguishable on the page from an
  absent one.

Good first paragraphs from the current set, both of which define and then immediately name the
neighbour:

> A command built from data that reaches a process launcher.
> — `FB.COMMAND_INJECTION`

> A value assigned to a local variable that is never read afterwards.
> — `FB.DLS_DEAD_LOCAL_STORE`

> A byte/char conversion with no charset argument, so it uses the JVM's default. `getBytes()`,
> `new String(byte[])`, `new FileReader(...)`, `new PrintWriter(File)` and friends. It is NOT
> `Charset.defaultCharset()` — that call is how you READ the setting, not how you depend on it
> accidentally.
> — `FB.DM_DEFAULT_ENCODING`

---

## The file

```
agent/src/main/resources/checkers/<CHECKER>.txt
```

The file name is the checker family verbatim, dots included: `FB.DM_DEFAULT_ENCODING.txt`,
`HANDLE_LEAK.EXCEPTION.txt`, `DEREF_OF_NULL.RET.LIB.txt`.

**Line 1 is a regex. Everything after the first newline is the note.** Nothing else is structured;
the body is prose read by a model. The head of `FB.COMMAND_INJECTION.txt`, with line 1 verbatim:

```
Runtime\.getRuntime\(\)\.exec|ProcessBuilder|\.exec\s*\(
A command built from data that reaches a process launcher.

HOW IT ACTUALLY BEHAVES. `Runtime.exec(String)` does NOT run a shell. It splits the string on
whitespace with a StringTokenizer and starts the first token as a program, passing the rest as
argv. ...
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
opinion among several, it is the premise all of them share. That asymmetry is the reason absence is
stated rather than papered over, and it is also why a note carrying the subject is so much more
damaging than a note that is merely thin: fifteen agents inherit it, and none of them has standing
to doubt it.

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

The **whole** body goes in, not a summary and not the first paragraph — the tiers, the
discriminators, the `WHAT GOES WRONG HERE` list. The body is written to be read by the agent that is
about to write a test; the first paragraph is written to be read by a person. Both audiences are
served by the same file and neither is served by a summary of the other's part.

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

Note the order: the note arrives **before** the flagged source. That is the arithmetic behind the
size ceiling — every character of note is a character the agent reads before it has seen the code it
is judging.

---

## `where()` — whether the flagged line holds the construct

This is the one sentence in the note that is arithmetic rather than opinion, and it is the only
place the harness is allowed to say anything about the subject at all — because it computes it,
per marker, against the checkout in front of it, rather than remembering it.

It exists because **the markers came off an older revision than the checkout**. The queue records no
revision — its columns are `Severity,Checker,File,Line` and nothing else — so the reported line is a
hint and not an address. Told only "line 63", an agent reasons about whatever is at line 63 today.
Told that line 63 does not match and line 62 does, it reasons about the right statement — or says
plainly that it cannot find one.

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

The regex on line 1 therefore has a second job beyond addressing this sentence: it is the note's
executable half, the one statement of "what the construct looks like" that a machine can act on. It
must describe the construct tightly enough to be wrong about a line that does not hold it — which is
what the `notUniversal` check below enforces.

---

## What a good note contains

Everything here is a property of the checker, the language, or the shape of a demonstration. None of
it is a property of a repository.

**1. What the checker reports, and the sibling it is confused with.** One paragraph — the claim
line. Name the construct, then name the neighbouring construct or checker that gets mistaken for it,
because that is where the reconstructions went wrong.

- `FB.DM_DEFAULT_ENCODING` — "It is NOT `Charset.defaultCharset()` — that call is how you READ the
  setting, not how you depend on it accidentally."
- `FB.EI_EXPOSE_REP2` — "It is the INCOMING side; its neighbour `EI_EXPOSE_REP` is the OUTGOING
  side, where a getter returns the field itself. The same field usually carries both, and they are
  separate markers with separate fixes."
- `DEREF_OF_NULL.RET.STAT` — on a line shaped `T x = (T) v.m();` the flagged value "is NOT `m()`'s
  result being cast — it is `v`, the receiver".
- `HANDLE_LEAK` — "The construct this is constantly mistaken for is a handle the CALLEE closes: a
  copy helper taking `new FileOutputStream(f)` as an argument matches the regex and is no leak if
  the helper closes in a finally."

**2. Language, JVM and library facts the agent may not have.** This is the highest-value thing a
note can carry and the reason the mechanism exists: a fact that is true of Java, checkable by
anybody, and absent from every agent that met the family.

`FB.COMMAND_INJECTION` is the cleanest instance. `Runtime.exec(String)`

> does NOT run a shell. It splits the string on whitespace with a StringTokenizer and starts the
> first token as a program, passing the rest as argv. So `;`, `&&`, `|`, backticks and `$( )` chain
> NOTHING — they arrive as literal arguments to the first program.

and therefore

> `Runtime.exec(String[])` and `ProcessBuilder` do not tokenise at all, so each element is one
> argument and even argument injection needs the caller to have concatenated.

Nothing in that paragraph is about any repository, and it decides every marker in the family.

**3. HOW YOU MAKE IT VISIBLE — the sentence the whole family turns on.** A **RED** is a test that,
in `prompts/reproduce-doer.txt`'s words, "FAILS on the current, UNPATCHED code precisely because of
this defect, and would PASS once it is fixed"; a **GREEN** is that same test passing after the fix.
The note's job is to say whether a RED is possible for this family, and by what instrument. 33 of
the 48 notes carry a `HOW YOU MAKE IT VISIBLE` heading; `FB.COMMAND_INJECTION` calls its equivalent
`HOW IT ACTUALLY BEHAVES`, because for that family the instrument follows from the behaviour and the
behaviour is the thing nobody knew.

The instrument is described generically — a seam, a shape, a bean to sample — never a call site.
`HANDLE_LEAK` tier 1 says to loop the leaky shape N times "through the smallest seam that reaches
it", sampling `getOpenFileDescriptorCount()`, and then gives the trap that makes the measurement
worthless: these types self-close through a `java.lang.ref.Cleaner` once unreachable, so "keep the
body small, assert `delta >= N/2` rather than equality, never call `System.gc()` or a helper that
might — one collection takes a full delta to zero".

**4. When it cannot be shown, say so and say what to write instead.** A note that leaves this
unstated buys a family of manufactured REDs. `FB.DLS_DEAD_LOCAL_STORE` is the model:

> THIS IS ALMOST NEVER OBSERVABLE. Removing a dead store changes no return value, no field, no
> output and no timing — that is what makes it dead. So a test that "demonstrates" it is nearly
> always asserting something else, and `unprovable` is usually the honest state.

`HANDLE_LEAK` tier 3 does the same in the other direction, and supplies the words: "the correct
settlement is 'real omission, release guaranteed by the enclosing try-with-resources (a) or by the
process reaper (b), not demonstrable by exhaustion' — write that instead of manufacturing a RED."

**5. Polarity.** The prescribed assertion must fail before the fix. Getting this backwards produces
a test that certifies the defect and reports the fix as a regression, and it is easy to get
backwards, because the natural sentence to write is the one describing what the bug does.

- `DEREF_OF_NULL.RET.STAT`, on `assertThrows(NullPointerException.class, …)` as the RED: "It PASSES
  on today's defective code and FAILS after the fix — a regression lock on the defect, inverted. It
  also fails to pin the line: any other null argument satisfies it just as well." What to write
  instead: "ASSERT THE GRACEFUL OUTCOME, NOT THE EXCEPTION."
- `TAINTED_PTR`: "A test that sends a payload and asserts the injection SUCCEEDS is green before any
  fix and stays green forever. Assert the safe property — only the requested row comes back, a wrong
  password does not authenticate, the resolved path stays inside the intended directory — so it
  fails on the current code and passes once the value is bound or validated."
- `FB.EI_EXPOSE_REP2`: "Asserting the marker back at itself: `assertThat(t.getItems()).isSameAs(items)`
  asserts the aliasing exists, which nobody disputes, and goes GREEN only by breaking `==`."

**6. Which verdict words follow from which observation.** The pipeline's state words are harness
vocabulary and a note may reason about them freely; what it may not do is reach one for a named
site.

- `HANDLE_LEAK`: "'the descriptor count did not move' is not 'false positive' and not 'not a defect'
  for tier 3 — it is 'not demonstrable by exhaustion, release guaranteed elsewhere'."
- `FB.DLS_DEAD_LOCAL_STORE`: "`by-design` needs evidence that somebody CHOSE this: a comment, a
  suppression entry, an annotation. 'Removing the assignment would be functionally identical' is an
  argument that the fix is safe, which is an argument FOR the fix and not evidence of intent."

**7. What intent actually requires — stated as a rule, never as a fact about the subject.** This
replaces what used to be a paragraph about the subject being a teaching application. The
transferable version is stronger and works on any repository: the surroundings are not evidence in
either direction, and intent needs an artefact.

`DEREF_OF_NULL.RET.STAT`:

> The name, package or directory of the surrounding code decides nothing in either direction: code
> that reads as demonstration or sample material does not thereby intend its crashes … while code
> that reads as critical is not thereby free of ordinary defects. Intent needs something checked in
> that DEPENDS on the dereference behaving as it does — a test asserting the throw, documentation
> instructing callers to expect it, a comment or suppression naming the choice.

`TAINTED_PTR` states the symmetry outright: "'the code around it is understood to be careless, so a
defect is a defect' and 'the code around it is understood to want this, so it is intended' are the
same failure."

**8. Shapes that match the regex and are not defects.** The regex is deliberately loose enough to
find a drifted line, so it over-matches, and the note owes the agent the list of over-matches.
`HANDLE_LEAK`'s `NOT LEAKS` paragraph: purely in-memory streams hold no OS handle; a handle acquired
and immediately returned to the caller is ownership transfer, and closing it at the acquisition site
would break every caller; an acquisition already inside try-with-resources is a flat false positive
— check that first. Each is a shape, not a site.

**9. WHAT GOES WRONG HERE — the reasoning traps specific to this checker.** 41 of the 48 notes carry
a section under this heading, and it is the part that most reliably changes an outcome. These are
the wrong turns a competent agent takes on *this* family: the assertion that looks like a
demonstration and is not, the fix that turns the test green for the wrong reason, the confusion
between two markers on one field. `FB.EI_EXPOSE_REP2` lists seven, including one that only shows up
after the fix: "Where both defects sit on one field a sloppy test passes for the wrong reason and
stays RED after the constructor is correctly fixed — which then gets misread as 'the fix didn't
work'."

**10. Where the family splits, and a discriminator for telling which half you are in.** Where a
checker covers two situations with different answers, saying so is not enough — the note has to give
a test the agent can run. `FB.EI_EXPOSE_REP2` splits value objects from framework collaborators and
then supplies:

> DISCRIMINATOR, when you are unsure which half you are in: try to write the defensive copy. If
> `new ArrayList<>(x)` / `x.clone()` / `new Date(x.getTime())` compiles and the program still
> behaves, it is (A). If the copy needs a copy constructor you would have to invent for a framework
> type, or would freeze something the code needs live, it is (B).

A split with no discriminator is worse than no split: it tells the agent there are two answers and
leaves it to pick one.

---

## Keeping the fact and dropping the site

The single manoeuvre a rebuilder needs. Most of what the contaminated notes knew was worth knowing;
what made it unusable was that it was expressed as a fact about one tree. The same knowledge
restated as a fact about the world survives the harness being pointed elsewhere, and is *more*
useful, because it now tells the agent what to check rather than what to believe.

The `HANDLE_LEAK` note is the worked example. The old text asserted that the subject declared a
`DriverManagerDataSource` at a named file and line, that there was no pool anywhere in `src/`, and
that a measured run of 2000 iterations moved the session count by 0. An agent could do nothing with
that except believe it. What the note says now:

> FIRST ESTABLISH WHAT THE HANDLE COSTS. For JDBC that means finding the DataSource implementation
> actually constructed. A connection pool on the dependency tree is not a pool in the path — an
> application that declares its own DataSource commonly suppresses the framework's pooled default,
> and then every `getConnection()` opens a fresh physical session. Do not build a harness around a
> pool the application does not use: it compiles, it goes RED, and it proves nothing about this
> application.

Same discovery. It has become an instruction to look, a reason why looking matters, and a named
failure mode for not looking — and it is true of every Java application rather than one. The
measurement that produced it belongs in the record of the run that produced it. It does not belong
in a file that fifteen agents read as a premise.

Apply the same conversion to a verdict and it disappears, correctly: "all three markers of this
family are by-design" converts to nothing, because there was never a general fact underneath it.
That is the test. If a sentence cannot be restated without the subject, it was the answer, not
context for finding it.

---

## The fact that unlocked a family

The note that mattered most, and the reason the mechanism exists at all.

A whole `FB.DM_DEFAULT_ENCODING` family had never produced a single build. Every agent that met one
reached the same answer in nearly the same words: *the default charset is fixed at JVM start-up,
therefore no test can vary it, therefore this cannot be demonstrated.*

The first clause is true. The conclusion does not follow. **A test may START a JVM.**

```java
ProcessBuilder b = new ProcessBuilder(
        System.getProperty("java.home") + "/bin/java",
        "-Dfile.encoding=ISO-8859-1",
        "-cp", System.getProperty("java.class.path"),
        SomeMainThatExercisesTheSubject.class.getName());
```

Assert on what the child prints: under ISO-8859-1 a round trip of `"café"` comes back `"caf©Ã"`;
under UTF-16 a base64 decode throws `IllegalArgumentException: Illegal base64 character`. Both go
GREEN once the charset is explicit. Since Java 18 the default *is* UTF-8 on every platform (JEP
400), so the defect cannot be shown by running the same JVM the build runs in — "That is a reason to
fork, not a reason to give up."

Two things a rebuilder must carry:

- The sentence `A TEST MAY START A JVM` is asserted by a test against the note file, with the reason
  attached: "without it the family goes back to never producing a build". It is not a phrase to
  paraphrase.
- The failure mode it illustrates is a **true premise with a false conclusion**, reached
  independently by many agents in nearly identical words. Convergence is not corroboration here —
  every agent shares the same gap. A note is the only place a fact that nobody has can be added.

**This chapter may tell that story; the note may not.** The note carries the fact, the fork recipe
and the JEP 400 consequence — all of them true of Java. What it does not carry is the count of
markers, the name of the class that was run by hand, or the sentence saying a family had never
produced a build. That is the ninth forbidden rule, and this section is where a careless rewrite
would put it back.

---

## What replaced the second reader

Earlier, notes were checked against a real checkout by a second reader whose corrections were
appended to the note under a sentinel line reading *"CHECKED AGAINST THE CHECKOUT BY A SECOND READER
WHO RE-RAN THE CLAIMS ABOVE. Where the two disagree, THIS IS THE ONE THAT HOLDS."* Twenty-nine of
the forty-eight carried it.

**The reading was worth doing. Recording it inside the note was the mistake**, and it is now
forbidden by the fifth rule above — not because the corrections were wrong, but because of what the
sentinel claimed. It told an agent to prefer an unidentifiable author's earlier reading of a
different checkout over the source in front of it. That is the exact instruction a pipeline built to
settle markers by execution must never give.

Three corrections were worth the exercise on their own, and all three survive — as checker facts
with the sites removed:

| What the reader found | What the note says now |
|---|---|
| A whole family's recipe was written against a file carrying no marker, added to HEAD after the scan | the general form: the flagged line is the marker, a GREEN attached to a reachable sibling class is not a settlement — and `where()` computes the drift per marker instead of remembering it |
| Two drafts assumed a connection pool that the application did not use, so the prescribed RED could never fire | the `FIRST ESTABLISH WHAT THE HANDLE COSTS` paragraph quoted above |
| Two families prescribed a test that was green on unpatched code | the polarity paragraphs in `TAINTED_PTR` and `DEREF_OF_NULL.RET.STAT` |

Truth is still not machine-checkable, and no test in this repository asserts that a note is right.
What the tests hold is the shape, the size, and the boundary. A note is still worth reading against
a real checkout before it is trusted — the reading is now expected to change the note's *claims*,
not to be appended to it as an authority. If a re-reading finds the note wrong, fix the note.

---

## What is enforced automatically

Two test classes, with overlapping shape checks. `ANoteIsCheckedBeforeItIsTrustedTest` holds the
mechanics; `TheHarnessDoesNotKnowItsSubjectTest` holds the boundary. Both iterate every `.txt` under
`src/main/resources/checkers`, sorted.

Two of the mechanical checks — `compiles` and `notUniversal` — exist because those failures happen
**silently inside `where()`**: a first line that will not compile is swallowed by
`catch (PatternSyntaxException)` and returns `""`, and a first line that matches everything reports
"Line 63 does contain it" for every line of every file. Either way the note still looks present and
the sentence the agent most needs is quietly missing or a lie.

| Class | Check | What it asserts | Why |
| --- | --- | --- | --- |
| `ANoteIsChecked…` | `twoParts` | every `.txt` has a `\n` at index > 0 and a non-blank first line | `read` returns `null` otherwise — the note would be silently absent rather than wrong, "which is at least the safe failure, but it is still not a note" |
| `ANoteIsChecked…` | `compiles` | line 1 of every note compiles as a `Pattern` | `where` swallows `PatternSyntaxException` and returns `""`, costing the note "its one arithmetical sentence" while the note still looks present |
| `ANoteIsChecked…` | `notUniversal` | no note's regex matches any of `""`, `"    // just a comment"`, `"    }"`, `"import java.util.List;"`, `"package org.owasp.webgoat.container;"`, `"        }  // done"` | a regex that fires on a closing brace reports "Line 63 does contain it" for every marker, "including the agents whose marker has drifted off the file — which is the exact case this sentence exists to catch" |
| `ANoteIsChecked…` | `saysHowToSeeIt` | the body, stripped, is at least 200 characters | "a note that only renames the checker leaves the agent exactly where it was" |
| `ANoteIsChecked…` | `theFactThatDidIt` | `FB.DM_DEFAULT_ENCODING.txt` contains `A TEST MAY START A JVM` | without it the family goes back to never producing a build |
| `TheHarnessDoesNot…` | `theNotesAreAboutCheckers` | none of the nine forbidden patterns appears in any note's prose | the note is pasted verbatim into the prompt of every agent that judges a marker of that checker, "so anything it knows about the subject is something the agents were told instead of finding" |
| `TheHarnessDoesNot…` | `theNotesAreNotThePrompt` | no note exceeds 8,000 characters | "a checker explains itself in a page; a note past this length has started accumulating findings again, which is how the last one reached 79% of the planner's task" |
| `TheHarnessDoesNot…` | `theShapeSurvived` | regex on line 1, compiling, with ≥ 200 characters of prose after it | restates the three mechanical checks inside the contamination test, so a rewrite pass that strips a note to nothing fails in the same class that told it to strip |

`theShapeSurvived` is a deliberate duplicate of `twoParts` + `compiles` + `saysHowToSeeIt`. The
duplication is the point: the two classes pull in opposite directions — one says *cut*, the other
says *a note that says nothing is worse than none, because the agent believes it* — and a rebuilder
who removes the duplicate loses the counterweight at the moment it is needed.

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

**Checkable from this repository alone**, and therefore true of the harness:

- 48 checker families in the queue, 48 notes, no marker without one.
- 215,462 characters across the directory; mean 4,489; largest 7,033 against a ceiling of 8,000.
  Before the rewrite: 457,650, mean 9,534, largest 20,353.
- 33 of 48 carry a `HOW YOU MAKE IT VISIBLE` heading and one more its `HOW IT ACTUALLY BEHAVES`
  equivalent; 41 of 48 carry a `WHAT GOES WRONG HERE` section.
- The smallest note is `FB.DLS_DEAD_LOCAL_STORE.txt` at 991 characters, and it is one of the two
  quoted here as a model. Short is not thin: it defines the construct, names the two cheap wrong
  answers, and stops.
- 0 notes name the subject, its packages, its architecture vocabulary, or any `File.java:LINE`.

**A property of one checkout, not of the harness.** One run of `where()` over a WebGoat checkout
this repository does not contain, at the time the notes were completed:

- 281 of 356 flagged lines hold the construct.
- 65 have drifted and are told which nearby lines do.
- 8 are told honestly that nothing near them matches.
- 2 name a file that checkout does not have — and receive the note with no line claim at all.

They partition the queue exactly (281 + 65 + 8 + 2 = 356) and are a measurement, not an invariant. A
rebuilder against a different checkout will get different splits and should expect the drifted and
missing-file buckets to be non-empty, which is the whole reason the sentence exists.

**This section is where a number like that is allowed to live** — labelled as one run, over one
checkout, in a document a person reads once. The same number inside a note is the ninth forbidden
pattern, because a note is not read once by a person; it is read as a premise by fifteen agents who
cannot check it and have no standing to doubt it. That is the whole distinction this chapter turns
on, and it is not about which facts are true. It is about which reader can check them.
