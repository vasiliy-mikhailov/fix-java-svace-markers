# The runner's differential harness

**What the fixtures are.** `harness/fixtures` is a RECORDING. Before this module existed, the same job —
apply an edit, decide what a build did, resolve a path inside a workspace — was done by a different
implementation. Cases were generated against it, its answer to every one of them was captured, and
those answers were frozen here as data: **23 401 of them today.**
`src/main/java/tech/mikhailov/fsm/runner` is what replaced it. The harness is the evidence that the
replacement answers the same way — and, where it does not, the record of every place it deliberately
does not.

**The comparison runs on every `mvn test`.** It is
`src/test/java/tech/mikhailov/fsm/runner/DifferentialHarnessTest.java`. A divergence that was not
there yesterday is a red test.

```
mvn -pl runner -am test -Dtest=DifferentialHarnessTest   # just the harness
sh harness/run.sh                                        # …and unpack + print the long report
```

---

## What is in `harness/fixtures`

| file | what it is |
| --- | --- |
| `cases.json.gz` | the 23 401 generated cases |
| `js-results.json.gz` | the 23 401 answers **the reference implementation gave**, type-tagged |
| `tree.json.gz` | the filesystem the four fs-touching families read: 230 entries — bodies, mtimes, symlinks |
| `expected.json` | the divergence CATALOGUE the test asserts against |

**Recorded 2026-07-31**, on Node v22.22.2, against the reference as it stood on its last day in service —
after its missing-`old_str` guard and after its `<key>.tmp` containment fix. The filenames are the
archive's own and are left alone: `js-results` is what the recording was called when it was made.

### What the corpus covers

| family | cases | what it compares |
| --- | ---: | --- |
| `wsNorm` | 458 | the whitespace normaliser every other whitespace rule delegates to |
| `applyEdit` | 17 524 | the edit application, exact path and whitespace fallback |
| `applyEditCoerced` | 11 | the CALL SITE's reads of a `fix_edit` — absent vs null vs empty `old_str` |
| `fixTarget` | 1 931 | path resolution and workspace containment, over a real tree with real symlinks |
| `coerce` | 28 | `` `${x}` ``, `String(x \|\| '')` and `!!x` over 28 hostile values |
| `summarize` | 543 | surefire/maven output parsing |
| `requiredJdk` | 758 | the JDK a build says it needs |
| `keyFor` / `keyForCoerced` | 123 | the cache key, and the coercions the request is read through |
| `tail` | 40 | log truncation |
| `buildCmd` | 1 510 | the command and the environment, over ten build-marker layouts |
| `outcome` | 433 | the XML-plus-mtime gate that decides whether a fix is believed |
| `readFile` | 42 | `/fs/read_file` as a WIRE reply, through the real route |

Three inputs in the frozen files were properties of the machine that recorded them, not of either
implementation, and are substituted on load (`@FIXTURES@`, `@CWD@`, `@PATH@`). `HarnessFixtures.java`
explains each one; the short version is that the fixture tree is rebuilt somewhere else, a relative
workspace resolves against the working directory, and `buildCmd` prefixes the JDK to the PATH it
INHERITS — which the case file never carried, and which both sides saw the same value of because they
were children of one shell.

---

## The baseline: 745 divergences, and why each one is a decision

`CASES 23401  IDENTICAL 22656  DIVERGENT 745`, in 14 kinds. **Every divergence has one of the seven
causes below, and each is a decision somebody wrote down. A divergence that does not reduce to one of
them is a regression, whatever the totals say.**

> **23 401, where 23 851 were originally recorded.** A 450-case `lease` family went with the lease
> routes: mutual exclusion here is structural rather than advisory (Spring Batch single-flight plus one
> FIFO build thread), so there is no code left for those cases to be answered by. They were 450/450
> IDENTICAL, so nothing was hidden by dropping them — **DIVERGENT is still 745**, which is the number
> that matters. `DifferentialHarnessTest` hardcodes 23 401 precisely so a corpus that quietly shrinks
> cannot re-record its way to green.

- **581 `applyEdit`** — `new_str` containing `$&`, `$$`, `` $` `` or `$'`, and ONLY where the match was
  EXACT, because that is the path the reference ran through a regex-replace primitive, which expands
  them. `applyEdit("a", "a", "$&")` is `"a"` there and `"$&"` here. The reference was wrong and was
  still live when it was recorded, so this is reported and not adopted. Nothing about the MATCH differs:
  all 581 differ only at `.text`, every one had exactly one exact match, and the whitespace fallback —
  which concatenates on both sides — agrees byte for byte over the whole corpus.
- **110 `fixTarget`** — `ws = "/"`. The reference tested `startsWith(resolve(ws) + sep)`, which is `"//"`
  for a root workspace, so it refused EVERY path in one. Unreachable — `ws` is always `/cache/<12 hex>` —
  and this implementation's answer is the correct one.
- **27 `fixTarget`** — a NUL inside the path, which `Path.of` cannot represent at all. The internal
  `{path}` differs; the `edit_errors` LINE the caller actually reads does NOT, which is why
  `out.edit_error` is compared beside it and does not appear in this kind.
- **16 `outcome` / 2 `summarize`** — a count that does not fit in a long: `<testsuite tests="1e20">`,
  and `Tests run: 99999999999999999999`. Clamped rather than thrown, per `Build.count`. Bounded: only
  `.ran` and the `.tests` TEXT differ, never `test_executed` / `failures` / `errors` /
  `compile_error` / `source`, so no case in the corpus can clamp a verdict.
- **7 `tail`** — `tail(s, 0)`, which returned the WHOLE string on the reference side. The only two
  values ever passed are 6000 and 1500. This implementation's answer is what the name promises.
- **1 `applyEditCoerced`** — `old_str` explicitly NULL and not matched: the reference threw on it inside
  `wsNorm`, where this implementation answers "old_str not found". Read it together with what is
  IDENTICAL beside it — an explicit null whose word "null" DOES occur once is applied by BOTH sides,
  because the search needle is the four characters `null`. That was a live defect of the reference,
  reproduced rather than invented here, and it is why the fix distinguishes ABSENCE and not type.
- **1 `applyEditCoerced`** — `new_str` absent, on the WHITESPACE path only, where the reference died in
  a `.trim()` on the missing value. The exact path is identical and that is the one that matters: both
  sides write the literal word into the file. Kept, because `new_str` is INSERTED where the edit aimed
  rather than searched for, so the green build fails to compile and says so — which is the opposite of a
  quiet wrong answer.

### Four more causes went away by fixing the code, which is what a harness is for

Two of them on the reference side, while it was still the implementation every marker was proven on:

- **6 `applyEditCoerced`** — a `fix_edit` with NO `old_str` at all. This implementation interpolated the
  absence into the string `"undefined"` and handed it to `applyEdit` as a real SEARCH NEEDLE, and Java
  source says that word in comments, string literals and identifiers — one occurrence and a malformed
  edit APPLIED where nobody aimed it. Fixed here first; the reference then grew the same guard in the
  same place. It could not misapply the edit but died on the missing value, so the whole prove came back
  `{ok:false}` and the marker was retried when its real answer was "that edit was malformed". Both sides
  now refuse that ONE edit and apply the rest.
- **5 `readFile`** — THE FIRST ONE FIXED ON THE REFERENCE SIDE. Three cases were a path escaping the
  repo through a SIBLING directory whose name merely STARTS with the cache key — `<key>.tmp`, the
  half-finished clone, which has a `.git/config` of its own. The reference compared strings, so
  `<base>.tmp/...` passed `startsWith(<base>)`. It was changed to test for the separator, as this
  implementation's component-wise `startsWith` always did. The other two came with the containment
  `Workspace.readFile` grew for the credential leak. The invariant that used to report VIOLATED on the
  reference side now holds on both — and the test asserts that, so the record cannot quietly rot.

Two more went away by fixing this side: 50 `lease` cases (a `ttl_s` of `"0x10"` / `"0b101"` / `[60]`,
all five of which the reference read as numbers and the first cut here answered NaN for — and NaN is
never greater than `now`, so the mutual exclusion the lease existed for was silently off) and 10
`coerce` cases (an explicit `"repo": null` spells `"null"` on the reference side and spelled
`"undefined"` here, and `Workspace` hashes `` `${repo}@…` `` into the cache DIRECTORY NAME).

### The invariants

Agreement is not enough for the rules that exist to stop a bad edit: two implementations that both
picked the wrong span out of two candidates would agree perfectly and the harness would print
IDENTICAL. So each side is also checked against an ORACLE written a different way — occurrences are
counted by splitting, where `Edit.java` walks the string character by character. Eight rules, each
reported with the number of cases it APPLIED to as well as the number it failed on, because "0
violations" out of 0 applicable cases is not evidence of anything. All eight hold on both sides.

---

## THE COST OF FREEZING, stated plainly

**The reference implementation is gone.** It was deleted in the same change that wrote these fixtures.
What follows is what that costs, and none of it is hypothetical.

1. **A divergence in a case NOBODY GENERATED can no longer be found.** The harness proves agreement on
   the inputs in this corpus. It cannot say anything about the next one. If a reviewer asks "what did
   the reference do with a `new_str` containing a lone surrogate?", the answer is no longer "let's run
   it" — it is "nobody asked in July 2026, and now nobody can".

2. **The fixtures cannot be regenerated.** Not "with effort" — at all. Regenerating them means having
   the reference implementation, and it exists only in `git log`. Restoring it would give you a program
   that has not been deployed or maintained since, and whose agreement with the current code would prove
   nothing about the current code. **If somebody proposes "just regenerate the fixtures", the honest
   answer is that there is nothing to regenerate them FROM.** Treat these files as an archive, not a
   cache.

3. **The corpus is therefore permanently fixed.** New behaviour in this module gets ordinary unit
   tests (`EditTest`, `BuildTest`, `WorkspaceTest`, …). It does NOT get new differential cases, and
   pretending otherwise by adding a case with a hand-written "reference answer" would be worse than
   useless: it would put a guess in a file whose whole authority is that every value in it was MEASURED.

4. **What the freeze does still buy** — and this is why the trade was taken. The alternative was a shell
   script nothing invoked, whose evidence had a half-life of one `rm -rf`. As it stands, all 23 401 cases
   run on every build, the 745 divergences are re-derived from this code's current behaviour rather than
   asserted in prose, and the day somebody changes `Edit.applyEdit` the build tells them which kind
   moved.

---

## Changing the catalogue

`expected.json` is asserted as text, so a legitimate change is reviewed as a diff:

```
mvn -pl runner -am test -Dtest=DifferentialHarnessTest -Dharness.record=true
git diff harness/fixtures/expected.json
```

Every line of that diff is a behaviour this module was proven against. Re-recording without reading
it is how a differential harness becomes a rubber stamp.
