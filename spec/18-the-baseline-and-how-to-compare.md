# 19. The baseline, and how to compare a rerun against it

A run of this pipeline costs hours of GPU and produces 356 settlements, and the only way to know
whether a change to the prompts, the chain or the model made it **better** is to run it again and
compare. That comparison is worth nothing if nobody recorded what the first run was.

So one run is kept, whole, with a manifest that says what produced it.

## Where it is

```
/results/baseline-2026-08-15-qwen36/          on the fsm-results volume
/results/baseline-2026-08-15-qwen36.tar.gz    55 MB, the same thing
~/baseline-2026-08-15-qwen36.tar.gz           on mh, outside the volume
```

Three copies on purpose. The volume survives a redeploy and does not survive being deleted, and the
credential files live on it — so the tarball outside it is the one that survives an accident with
`docker volume rm`, and a copy off the host is the one that survives the host.

`MANIFEST.json` sits at the top of the archive and states the subject, the model and its settings,
the counts, and — the part a reader needs most — **which commits produced which part of the run**.

## READ THIS BEFORE COMPARING ANYTHING AGAINST IT

**The baseline was produced while the checker notes told the agents what to conclude.**

`Checkers.java` pastes `checkers/<CHECKER>.txt` into the task of all fifteen agents that judge a
marker, and at the time of this run those files had accumulated the corpus's own class names, its
`File.java:LINE` sites and — for several families — the settlement itself:

> "None of the 17 markers in this family can be made to fail. Do not build a test for any of them;
> write the settlement and cite the evidence." — `FB.HARD_CODE_PASSWORD`

> "THIS MARKER IS REFUTED ... do not spend an attempt trying to make it RED." — `DEREF_AFTER_NULL`

For every marker of an affected checker the recorded settlement is a transcription of the note, not a
judgement of the code, and no number computed over those rows measures the pipeline. `argued/settled`
is the worst affected: a note instructing an agent not to build a test produces an `argued`
settlement by construction, so the ratio partly counts the notes rather than the chain.

The notes were rewritten to checker semantics only in `3f9218f` (457,650 characters to 215,626), and
`TheHarnessDoesNotKnowItsSubjectTest` holds the line. **This archive is kept as a record of what the
pipeline did, not as a standard to beat.** The first run on decontaminated notes becomes the baseline
worth comparing against; until then, comparing a rerun against these numbers measures a change in the
notes and reports it as a change in the pipeline. See [05. Checker notes](05-checker-notes.md).

## The number to compare

**`argued / settled`. It was 219 of 343.**

A settlement is `argued` when no RED build ran: the marker was closed by a paragraph about one commit
rather than by executing anything. `demonstrated` is the other half — a test that failed on the code
as it stood and passed once patched, which is knowledge that survives somebody editing the file next
week.

Both used to increment one counter, so a page reading "343 settled" reported 219 markers closed on
prose as though they had been shown. `/api/index` sends all three now.

**A good rerun moves `argued` down and leaves `demonstrated` alone.** A rerun where BOTH fall has not
improved anything — it has stopped settling markers, which is a regression wearing the same numbers.

Watch `by-design` (62) and `false-positive` (70) in `countsByState`. Those are the two dispositions
that close a marker on an argument, and `by-design` is the cheapest exit in a subject like WebGoat,
which is vulnerable on purpose.

## What this run is a BEFORE picture of

It is not homogeneous, and the manifest says so rather than implying a single build:

| change | in this baseline? |
|---|---|
| thinking budget (`thinking_token_budget`) | yes — this is an AFTER picture for it |
| `grep` matching path-shaped globs | no — grep excluded the whole tree for any glob with a `/` |
| `propose`/`argue`/`price` planners being called | no — they had zero calls |
| the `replan` word in those three verifiers | no |
| the stakes preamble | no |

So a rerun measures four changes at once. If the result is ambiguous, the A/B directories are the
finer instrument: `ab-before/`, `ab-run2/` and `ab-run3/` hold the same three markers proved three
times, and runs 2 and 3 differ **only** in the preamble wording.

## How to compare

```bash
curl -s -u admin:… https://fix-java-svace-markers.mikhailov.tech/api/index > after.json
```

Then, against `baseline-2026-08-15-qwen36/index.json`:

- `run.demonstrated`, `run.argued`, `run.settled` — the headline
- `run.countsByState` — where the movement is
- `markers[]` keyed on `markers[].key` — per-marker before/after, which is the only way to see a
  marker that moved from `by-design` to `verified/pr-ready` rather than a total that happened to hold

`humanMinutes` (8527 here) and `traceEvents` (15910) are the cost side. A rerun that halves `argued`
and triples the tokens is a trade somebody should get to see rather than discover.

## Starting a rerun from scratch

Archive what is there, keep `markers.txt` and `prompts/`, clear the rest:

```bash
docker exec fsm sh -c 'A=/results/archive-$(date +%F); mkdir -p $A
  for d in m dead claims; do mv /results/$d $A/$d; mkdir -p /results/$d; done
  for f in settlements.jsonl trace.jsonl overwatch*.jsonl restarts.jsonl; do mv /results/$f $A/ 2>/dev/null; done
  : > /results/settlements.jsonl; : > /results/trace.jsonl'
```

`markers.txt` is the queue and must survive; `prompts/` holds any overrides in force, and deleting it
silently reverts every edited prompt to the code's own.
