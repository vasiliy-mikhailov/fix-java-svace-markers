# Proving static-analysis markers

A marker claims a defect at a file and a line. A prove either demonstrates it or argues it away.

```
mvn -q -DskipTests package -f agent

QWEN_BASE_URL=… QWEN_API_KEY=… QWEN_MODEL=… \
  docker run -p 8085:8085 -v results:/results fsm-agent prove 'repo|file|line|checker'
```

`entrypoint.sh` takes `prove`, `slice <markers> [concurrency]`, `serve [seconds]`,
`overwatch [seconds]`, `test [cases]`, `seed [cases]` and `dashboard`.

**[`RUNNING.md`](RUNNING.md) is how to run it** — the image, the modes, the environment, the volumes,
and the one thing to read before exposing the port.

**[`spec/`](spec/) is the specification**, by chapter — written so that this system could be rebuilt
from it alone, which means it carries the *reasons*: most rules here exist because something went
wrong once, and a rebuilder who does not know which failure a rule prevents will simplify it back
into the bug. This README is the tour; the spec is the contract. It is copied into the results volume
at container start, so the supervisor and the chat agent can read it with their own file tools.

`serve` is what the deployed container runs: the supervisor in the background and the dashboard in
the foreground. The asymmetry is deliberate — a watcher that dies must not take the record with it,
and a dashboard that dies should end the container so the restart policy brings both back.

## The chain

Fifteen agents. Five stages, three roles each, called in a fixed order by `Prove.prove()` — the
order is Java, not a paragraph an agent can rewrite.

```
reproduce-planner → reproduce-doer → RED ──→ reproduce-verifier ─┐  (redo → doer · replan → planner)
                                                                 ↓
fix-planner       → fix-doer       → GREEN → fix-verifier ───────┤  (over-fit | regression-risk)
                                                                 ↓
propose-planner   → propose-doer   → propose-verifier               (redo → propose-doer, once)
argue-planner     → argue-doer     → argue-verifier                 (only where execution settled
                                                                     nothing)
price-planner     → price-doer     → price-verifier                 (prices every exit)
```

**The planner exists because a complaint used to have one address.** A verifier's objection went back
to the agent that had just failed to satisfy it, which works when the fault is in the doing and not
when it is in the approach: a doer told "this test does not observe the defect" rewrites the same
test, because rewriting is the only move it has. Thirty-three `DM_DEFAULT_ENCODING` markers produced
no build that way. So the verifier has a third word — `replan` reaches past the doer to the planner —
and one of each is allowed per stage, ever.

**A planner reads and never writes.** A plan that can edit its subject is a plan that can arrange to
be satisfiable, which is the same reason no judge here holds the runner.

**The build is the arbiter.** A test that fails before the patch and passes after it is the whole
standard of proof. `Runner` has three outcomes, not two: a build that produced no test result is
never evidence, because in the RED phase a failing test is the goal and a compile error would
otherwise read as success.

**Nothing is built until a file exists**, and a blank reply is not a decline. A reproduce-doer that
explained a marker at length and wrote nothing used to cost two builds before anyone looked; its
silence then reached the argue-doer as a considered refusal. The decline is a token now (`no
test`), it is named in the prompt, and the file is checked before Maven runs.

**The critic judges the diff, not the account of it.** `fix-verifier` gets `git diff` beside the
fix-doer's prose, and a computed sentence saying whether a hunk spans the flagged line. That does not
forbid `sound` — a defect is often correctly fixed at its source rather than where the analyser saw
it — it makes `sound` cost a sentence saying why.

**A run may not cite itself.** By the time the argue-doer reads the tree, the tree holds this
run's test and this run's patch. `git status` is the line between ours and theirs, and everything on
our side is handed over as inadmissible.

**The source is numbered.** These markers came off an older revision and some have drifted —
`EncDec:67` points six lines past a 64-line file. Numbered, the drift is a fact in the brief instead
of a guess an agent makes silently.

**Producers may run their own tests; judges may not.** The rule is that a certification must not
manufacture the evidence it certifies — not that a producer should work blind. The RED and GREEN that
*decide* a marker are still the ones `Prove` runs between stages.

**No tool ceiling.** `SubAgentRuntime` hardcodes twenty-five sequential tool calls as a literal, and
twenty-five is not many for an agent reading a class, its callers and its tests before writing
anything — hitting it throws and ends the prove, losing a marker to a budget rather than to anything
about the marker. The Dockerfile patches the literal where it can be seen, with a `grep` that fails
the build if upstream changes the line. What stops a tool loop now is the supervisor, which counts
calls per agent.

**Two write, the rest plan or judge**, and the split decides the tools. The reproduce-doer gets `write_file` but not
`edit_file`, so it can never make its own test pass; the fix-doer gets `edit_file` but not `write_file`,
because a new file is not a patch. Everyone gets `grep` and `glob`: a model asking for a tool that
does not exist does not degrade, it throws and the prove ends.

**A red that passes is not a reproduction, and the reproduce-doer is the one told.** A first RED build
runs against the revision the marker was raised against — the reproduce-doer holds no `edit_file` and no
fix-doer has run — so a test that is green there has documented the defect rather than observed it. In
one run 16 of the 33 markers that reached a build had their first RED pass, and 13 settled on it.
The chain re-asks the reproduce-doer once; `run_test` says the same thing at the moment it happens,
because the bare word `PASSED` reads as success and means its opposite here.

**The checker's claim is stated, not guessed.** A marker arrives as `file|line|checker` and every
agent reconstructs the claim from a bare name — several reconstructed it wrong in ways that decided
the marker. `src/main/resources/checkers/<CHECKER>.txt` carries the construct as a regex and the note;
Java reports whether the flagged line actually contains it and, when it does not, which nearby lines
do. A checker with no note says so.

The note that mattered most: 33 `DM_DEFAULT_ENCODING` markers had never produced a build, because
every agent reasoned that the default charset is fixed at JVM start-up and concluded no test could
vary it. The first clause is true and the conclusion does not follow — **a test may start a JVM**.

**Silence has a direction — and only one kind of silence.** An *empty* critique waives everywhere:
`verdict()` reads no word, so the producer's answer stands. A critic that THROWS is different, and
which way it falls depends on who catches it. `argue-verifier`, `propose-verifier` and `price-verifier`
run inside helpers that catch and return the producer's answer unchanged, so an unreachable one
waives. Everything else — both producers, `reproduce-verifier`, `fix-verifier`, `propose-doer` — is unguarded, so
a throw ends the prove as `infra` and the marker is re-queued rather than settled.

This paragraph used to claim that an unreachable `fix-verifier` or `propose-verifier` *blocks the pull
request*. `propose-verifier` goes through `reviewed()`, which catches and returns the `propose-doer`'s decision
unreviewed, so `verified/pr-ready` can be reached with no second signature on it. Nothing acts on
that — `propose-doer` decides and nothing pushes — so the cost is a row in the record that looks reviewed
and was not. The stronger guarantee the sentence described does not exist; stating it was worse than
not having it, because a reader relies on it.

**Dispositions are computed where the builds settled them.** The verdict agent is asked only where
they settled nothing — a declined proof, or a test that passed before any patch.

## The supervisor

Two more agents, in their own process, whose subject is the fifteen.

```
overwatch → finding → overwatch-critic → holds | refuted   (and may restart_prove)
```

Every agent in a prove is handed one marker and cannot know its answer is the fortieth identical
one. A pattern is invisible from inside a prove — a critic that has said `sound` in one word thirty
times, a checker family that always settles the same way, a reproduce-doer whose tests keep passing
before any patch. Each of those was found by a person reading a finished run, which is the expensive
way and the late way.

**The digest is counted, not summarised.** Builds, answers, empty replies, the length of each judge's
last word, whether a test was written, minutes since anything happened. Asking a model to summarise
the traces would be asking the thing under watch to summarise the evidence about itself. The watcher
reads whichever traces it wants with its own tools; the digest only says where to look.

**Two levers, for two different failures.** `restart_prove` is for a prove that is BROKEN — it died
of something a fresh attempt would not hit. `postpone_prove` is for one that is WORKING and simply
taking much longer than the others: restarting that changes nothing, and leaving it costs a quarter
of the pool while the queue waits. Postponing frees the slot and the pool proves the marker again once
everything else is done — from scratch, with the first attempt kept aside in `dead/` rather than
written over.

**There is no resume**, because there is nothing to resume: a prove is a JVM mid-conversation with a
model and nothing persists that. Proving a postponed marker sooner is `restart_prove`, which is the
same act under its only honest name and which lifts the postponement as part of doing it.

**The clock belongs to the marker, not the attempt.** `restart_prove` moves the trace to `dead/` and
the next attempt starts a new one — so a marker could burn seventy-nine minutes, be restarted, burn
seventy-nine more, and read as seventy-nine every time. The one tool the supervisor reached for was
resetting the measurement that would have told it to reach for the other one, and a marker could
never look slow however long it actually took. `Pace` sums every attempt, `dead/` included, and the
digest says how many there were.

**"Much longer" is a comparison, not a number somebody picked.** Half an hour is only long because
most markers finish in five, and a fixed cap either strangles ordinary work or lets a stuck prove run
all day the moment the model or the subject changes. `Pace` takes the median of what has actually
settled in this run. The floor matters more than the multiple: nothing is an outlier until there are
eight settlements to have a median of, and nothing is an outlier under twenty minutes however quick
the rest has been. Median rather than mean, because one four-hour prove would drag a mean far enough
to make itself look ordinary — which is the case it exists to catch.

What comes back later is a fresh attempt, not a continuation. A prove is a JVM mid-conversation with
a model and nothing persists that, so what is saved is not the work but the SLOT, which is what was
actually being wasted.

**Only the critic may act, and a restart is a process restart.** An agent is a synchronous call —
there is nothing behind it to kill. A prove is a JVM, so `restart_prove` kills it, keeps its trace
aside, deletes its results and releases its claim; the pool takes the marker again in a clean
worktree. Two restarts per marker, ever, counted in a file rather than asked for in a prompt.

**Silence is fail-safe in both directions here.** A finding the critic never judges still reaches the
record marked `unjudged`, so an unreachable critic cannot suppress a warning; a restart it never
orders does not happen, so an unreachable critic cannot kill anything either.

## Asking it something

`/chat` (the ✉ beside the gear) puts a question to the same agent, over the same digest, with the
same read-only tools. It reports patterns on its own schedule; this answers the question actually in
front of somebody — usually narrower than a pattern, and often just "what is this marker doing".

**The dashboard answers, not the watcher.** The watcher is a loop in another process: it wakes,
reads the run, reports, sleeps fifteen minutes. Posting a question into that loop would give the
reply a quarter-hour floor, and a conversation with a quarter-hour floor is not one. So the answer is
built here, from the same prompt over the same `digest()`, sharing nothing but the results directory
— which is what both of them are looking at anyway. The watcher does not know this exists and cannot
miss a pass because of it.

**It reads and does nothing else.** `restart_prove` and `postpone_prove` belong to `overwatch-critic`
and to nothing else, because that agent's silence refuses to act. An agent that answers questions and
holds those tools fails the other way round: "what's happening with `LessonMenuService`?" is a
question, and it must not be able to end as a killed prove because a model read it as a request. It
says what it would do; the buttons are on the marker's own page. The tool map is the fence and a test
asserts it — the prompt only agrees with it.

**Enter sends; shift-enter is a new line.** Almost every message is one line, so reaching for the
button after each is the whole friction; a multi-line question is the rare case and keeps the
modifier.

**Two files under `results/` are not part of the record.** The watchers are rooted there because that
is where the record is, and `results/model` and `results/git-credentials` live in the same directory
— so any agent with `read_file` has always been able to open an API key and a repository token.
Nothing asked, so it stayed theoretical. A chat makes it a question somebody can type in one line,
and the answer would land in `chat.jsonl` and on a page. Both now refuse to open, and the shapes they
hold are redacted from every tool result — `grep` reaches a file without naming it and returns the
matching line, so refusing by name alone is not enough. At the tool layer, for every agent, because a
prompt is a request and this has to be a fact.

The conversation is `results/chat.jsonl`, and the question is written down **before** the answer is
attempted, so a dashboard that dies mid-reply leaves a record of what was asked. That is a normal
state rather than an edge case — the container is redeployed several times a day and an answer takes
minutes — so the page tells "restarted while writing" apart from "still thinking" rather than waiting
forever on the first.

## The lane-level watch

A second pair in the supervisor's process, one level below the one that watches the run.

```
interpreter → draft → interpreter-critic → the sentence the table shows
```

`why` used to hold the argue-doer's first sentence, which is an argument addressed to the next
agent rather than an account addressed to a reader: *"false-positive — the claim does not hold in
this code"* names the word and says nothing about whether anything was executed or how it was
reached.

A LANE is one marker's whole journey — every build, every agent, every loop back. No agent inside it
ever sees that, because each is handed its own stage, and the settlement records only where it
ended. The digest is assembled by counting and quoting, never by asking a model to summarise a
trace: a build that produced no test result reads as *"did not run at all — nothing was learned"*,
and an empty reply as *"nothing at all — it answered with silence"*, because rendering either as
blank makes a stage that failed look like a stage that never ran.

**Two lengths, because they are two jobs.** One sentence decides whether to open a row out of 356
and goes in the table; two to four sentences answer what happened and go at the top of that marker's
summary tab, above the test and the agents' own words. Truncating the second into the first gives a
table of sentences that all begin the same way and stop before the part that tells them apart.

**The critic writes the text that ships.** The draft appears nowhere. A summary is the one thing on
the page a reader takes at face value, so the version shown is the one read against the record by
something that was not trying to write it. Its silence WITHHOLDS: with no answer the table falls
back to the verdict's own words, which are at least demonstrably somebody's.

Only settled lanes, eight per pass. A lane is not a story until it has an ending.

## The prompts

Every prompt is a Java text block AND a file. Absent, the text block is used and nothing changes;
present, `PROMPTS/<agent>.txt` replaces it **entirely** — there is no merge, because a prompt half
from the code and half from a box is a prompt nobody can read in one place.

`/settings` shows all fourteen, editable, in the order the pipeline calls them, with the code's version one click away and a button to put
it back. An edit takes effect on the **next marker a prover starts**, not on the next deploy, because
a prove is a fresh process per marker and reads the override when it builds its agents.

Sixteen of the faults found in one audit were "the prompt says nothing about this" — each a
paragraph somebody could have written in a minute and could not, because it was behind a build, an
image and a redeploy.

Per marker, the `prompts` tab shows what each agent was **actually told when that marker was proved**
— recoverable from the trace, since `asked` carries the whole prompt and the task follows a
separator. Where one has changed since, the marker says so and offers to prove it again: a settlement
is only as good as the prompt that produced it, and one reached under instructions nobody is using
any more is worth knowing about.

## The model

`/settings` → **the model**: which model, which endpoint, temperature, token cap, and the two bounds.
Read per prove rather than per process, so a change takes effect on the next marker and disturbs
nothing running — these were environment variables and constants, and changing one meant recreating
a container or building an image, both of which kill the pool.

**The two bounds are separate settings with separate names**, because confusing them killed
eighty-six live proves: one is how long the wire may carry *nothing*, the other is how long an
answer may go on *arriving*. A single "timeout" that measures the second while reading like the
first reports a healthy endpoint as dead.

Temperature is 0 because these agents certify — a judge that answers differently on the same
evidence twice is not a judge, and every loopback replays a decision. The token cap is 0, meaning
none: a cap is not a smaller number but a different behaviour, and the last one bounded a stall by
truncating the reasoning that caused it.

**The API key is not on that page and cannot be set from it.** Everything there is a parameter; a
credential is not one, and a page that shows a key leaks it to whoever is looking at the screen.

## The subject

`/settings` → **the subject**: which markers, and how to reach the tree they are about. Both were
fixed at deploy — the queue was a file in the image and the tree came from a public clone — so
pointing this at another project meant editing the repository and building an image.

**The markers** are uploaded or pasted, and every line is checked before any of it replaces the
queue: a bad line does not fail at upload, it fails eight hours later as one marker that never ran,
which a reader cannot tell from a marker that ran and decided nothing. Complaints name their line
numbers and stop at a dozen. The old queue is kept beside the new one, because results already
recorded may name markers the new queue has never heard of.

**A credential** for a private GitHub or GitLab repository goes into git's own store, owner-only —
never into the clone URL, where a token is in the process list every prover can read and in the log
the pool writes.

**A source zip** is for a tree that is not in a repository this container can reach. While one is
present it IS the subject and nothing goes to the network. Extracted with `jar`, which the JDK
already ships, rather than adding `unzip` to the image; a zip holding a single directory is
unwrapped so marker paths resolve against the project.

## The order of the queue

`markers.txt` is sorted **severity, then path, then line, then checker** — Critical, Major, Normal,
Minor, then the 74 with no analyser severity, which are all in the test tree.

The pool reads the file top to bottom and the dashboard lists it in the same order, so the file IS
the order: of the queue, of the table, and of what a run stopped half way through has covered. Two
runs are only comparable if they take the markers the same way, and a run cut short should have
spent its time on the worst ones.

The line sorts as a number. As text, 100 comes before 21, which reads as a mistake in a file people
scan. A test asserts the file is in this order rather than sorting it at load — a queue that
reorders itself is a queue nobody can diff.

## Running many

`slice <markers> [n]` proves every marker, at most `n` at a time — and `n` is re-read from
`results/workers` at the top of each iteration, so `/settings` can widen or narrow a run while it is
going. It was an argument fixed for the life of the pool, and changing it meant killing the pool,
which orphans every claim in flight. Between 1 and 16, clamped in Java and again in the shell:
the first is what a person types, the second is what starts JVMs, and neither trusts the other. A pool, not a partition: whichever
prover is free takes the next marker, so the run ends when the work does rather than when the
unluckiest slice does. The claim is a `mkdir` — atomic, and it tells the loser it lost. Each prove
gets its own `git worktree`, thrown away afterwards.

It resumes: a marker already settled anywhere is skipped.

**A claim lasts exactly as long as its prove.** It used to outlive it, and that quietly repealed the
rule above. `settled` was taught to answer NO for a marker whose prove ended in `infra` — the state
written when a prove *throws* — precisely so the pool would take it again; three lines later
`mkdir claims/$id || continue` skipped it anyway, because the dead attempt's claim was still there
and nothing ever removed it. Every marker whose prove threw was retired by the gate that exists to
stop two provers taking the same marker, and the promise above was really "already **attempted**
anywhere is skipped". Both gates read correctly alone; only their order was wrong.

So a prove that ends without a disposition now hands its marker back: the record goes to
`dead/<marker>.attempt-N` — because `Prove` appends, and a retry landing on the old trace reads as
one prove that changed its mind — and the claim is released. Three such attempts and the marker is
left for a person, which is the bound that stops "release it, fail in the same place, take it again".
A pass also sweeps claims left by earlier runs, keeping any whose worktree still has a process behind
it; without that, the markers already stranded stay stranded, since the fix only reaches the ones
stranded after it.

## The record

| file | what it is |
|---|---|
| `results/m/<marker>/trace.jsonl` | every prompt, reply, tool call and build, stamped, in full |
| `results/m/<marker>/settlements.jsonl` | one line per stage boundary; the last is the disposition |
| `results/feedback.jsonl` | a person's judgement of one answer, carrying its prompt and reply |
| `results/cases.jsonl` | model-test cases |

`Trace` is injected once and handed to every agent, so nothing prints or appends on its own. Tool
payloads are recorded at the executor, before the library's own truncation.

## The dashboard

`dashboard` serves the record. Server-sent events push when the counts move; a marker page appends
the new events rather than reloading, so nothing you have open closes.

`/overwatch` is the supervisor, with the same tabs: its findings, each agent's answers and
reasoning, and its whole record. Nothing on those pages is trimmed or capped — a page that quietly
shows part of a record reads as the record.

The table is meant to be read without opening anything: severity, the checker, the state, and the
argument that reached it — folded to its first real sentence, whole inside the fold. Severity is
joined from `examples/webgoat/severities.tsv`, which the marker key does not carry; the 74 `src/it`
and `src/test` markers show "—" rather than a guess.

Per marker: a tab for each agent showing its **final** answer with superseded attempts folded
beneath, the test it wrote, a semaphore for whether RED reproduced and GREEN held, what the machine
spent and what a person would have. Feedback is a text box on any answer.

## Watching it think

A prove's live view is a `live` tab on that prove's own page, next to the trace of what it has
already said, and the word `proving` in the table links straight to it — it is the one state in that
column that is a question rather than a conclusion. It shows the answer that agent is producing
**right now**, updating every two seconds.

The front page has none. The supervisor's stream was there and earned its space for about a day:
the findings banner says what it has **concluded**, and a paragraph of it thinking out loud is a
slower way to learn less.

Everything else in the record is written when a call ENDS, which is right for a record and useless
for watching — a reasoning turn can run for minutes, and for those minutes the trace shows the agent
doing nothing. `Trace.streaming` is the one method that fires mid-call and the one whose argument is
replaced rather than appended: it goes to `trace.jsonl.live` beside the trace, overwritten, throttled
to once every 700ms. It is a view and never evidence, and no settlement may be read out of it.

Polled, not pushed: the event stream fires when the counts move, and an agent reasoning for four
minutes moves no counts. Only the panel container is replaced, so nothing else you have open closes.
A panel appears only while its marker is still claimed — the file outlives the prove, and a panel
still showing it would be a live view that is quietly a museum.

## Model tests

`test [cases]` replays an agent against an input it has seen and asserts **what the chain does with
the reply** — `loopback:yes|no`, `number:N±T`, `verdict:<word>` — not the wording, which is where two
runs at temperature 0 legitimately differ. `seed` turns a trace into cases; they are only as good as
the run they came from, so seed from one you have read.

## What it does not do

- **Clone or choose a JDK** beyond the checkout the entrypoint makes.
- **Cap output tokens.** A cap is a number chosen from last week's run and wrong the first time a
  marker legitimately needs more. Two time bounds stand instead — silence, and speech that gets
  nowhere — and a generation that runs away is a pattern, which is the supervisor's subject.
- **Push a pull request.** `propose-doer` decides; nothing acts on it.

## Status

`examples/webgoat/markers.txt` is the queue: **356 markers**, 48 checker families, 241 in lesson code.
Of those, **282 are `src/main`** — exactly the set an earlier system proved, recorded in
`examples/webgoat/results-282.csv`. The other 74 are `src/it` and `src/test`, which that system
excluded and which have no prior result to compare against.

2,144 lines of Java across 14 files, and 50 tests across 10 — one case per defect that reached a
deployed server.

A 14-marker sanity run after the four checks above, against the same 14 before them:

| | before | after |
|---|---|---|
| builds | 30 | 17 |
| builds that ran no test | 12 | 0 |
| reproduce-doer answers | 22 | 20 |
| of them empty | 12 | 7 |

The empty replies did not go away — a model whose last turn is a tool call returns no text, and that
is not a fault. What changed is that an empty reply no longer buys a build or passes for a
judgement: the five markers where nothing was written reached the argue-doer directly, at a cost
of zero builds each.
