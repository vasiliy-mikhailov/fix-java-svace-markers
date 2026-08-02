# The runner's differential harness

This module is a PORT. `java-runner/lib/edit.js`, `java-runner/lib/build.js` and the non-git parts of
`java-runner/src/server.js` were the live implementation; `src/main/java/tech/mikhailov/fsm/runner`
is what replaced them. The harness is the evidence that the replacement answers the same way — and,
where it does not, the record of every place it deliberately does not.

**The comparison now runs on every `mvn test`.** It is
`src/test/java/tech/mikhailov/fsm/runner/DifferentialHarnessTest.java`. A divergence that was not
there yesterday is a red test.

```
mvn -pl runner -am test -Dtest=DifferentialHarnessTest   # just the harness
sh harness/run.sh                                        # …and unpack + print the long report
```

---

## What is in `harness/fixtures`, and where it came from

| file | what it is |
| --- | --- |
| `cases.json.gz` | the 23 851 cases the JavaScript side generated |
| `js-results.json.gz` | the 23 851 answers **the JavaScript gave**, type-tagged |
| `tree.json.gz` | the filesystem the four fs-touching families read: 230 entries — bodies, mtimes, symlinks |
| `expected.json` | the divergence CATALOGUE the test asserts against |

**Generated 2026-07-31**, on Node v22.22.2, by the last version of `java-runner` — `lib/edit.js`
after the missing-`old_str` guard and `src/server.js` after the `<key>.tmp` containment fix, i.e. the
JavaScript exactly as it stood when the fleet last ran it. `harness/js-side.cjs`, which is now a
loader, is the file that used to generate them.

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
| `lease` | 450 | acquire/release/TTL, driven through one shared lease |
| `buildCmd` | 1 510 | the command and the environment, over ten build-marker layouts |
| `outcome` | 433 | the XML-plus-mtime gate that decides whether a fix is believed |
| `readFile` | 42 | `/fs/read_file` as a WIRE reply, through the real route |

Three inputs in the frozen files were properties of the machine that generated them, not of either
implementation, and are substituted on load (`@FIXTURES@`, `@CWD@`, `@PATH@`). `HarnessFixtures.java`
explains each one; the short version is that the fixture tree is rebuilt somewhere else, a relative
workspace resolves against the working directory, and `buildCmd` prefixes the JDK to the PATH it
INHERITS — which the case file never carried, and which Node and Java saw the same value of because
they were children of one shell.

---

## The baseline: 745 divergences, and why each one is a decision

`CASES 23851  IDENTICAL 23106  DIVERGENT 745`, in 14 kinds. **Every divergence has one of the seven
causes below, and each is a decision somebody wrote down. A divergence that does not reduce to one of
them is a regression, whatever the totals say.**

- **581 `applyEdit`** — `new_str` containing `$&`, `$$`, `` $` `` or `$'`, and ONLY where the match was
  EXACT, because that is the path the JS ran through `String.prototype.replace`, which expands them.
  `applyEdit("a", "a", "$&")` is `"a"` in JavaScript and `"$&"` here. The JS was wrong and was still
  live, so this is reported and not touched. Nothing about the MATCH differs: all 581 differ only at
  `.text`, every one had exactly one exact match, and the whitespace fallback — which concatenates in
  both languages — agrees byte for byte over the whole corpus.
- **110 `fixTarget`** — `ws = "/"`. The JS tested `startsWith(resolve(ws) + sep)`, which is `"//"` for
  a root workspace, so it refused EVERY path in one. Unreachable — `ws` is always `/cache/<12 hex>` —
  and this port's answer is the correct one.
- **27 `fixTarget`** — a NUL inside the path, which `Path.of` cannot represent at all. The internal
  `{path}` differs; the `edit_errors` LINE the caller actually reads does NOT, which is why
  `out.edit_error` is compared beside it and does not appear in this kind.
- **16 `outcome` / 2 `summarize`** — a count that does not fit in a long: `<testsuite tests="1e20">`,
  and `Tests run: 99999999999999999999`. Clamped rather than thrown, per `Build.count`. Bounded: only
  `.ran` and the `.tests` TEXT differ, never `test_executed` / `failures` / `errors` /
  `compile_error` / `source`, so no case in the corpus can clamp a verdict.
- **7 `tail`** — `tail(s, 0)`, where `s.slice(-0)` is the WHOLE string in JavaScript. The only two
  values ever passed are 6000 and 1500. Java's answer is what the name promises.
- **1 `applyEditCoerced`** — `old_str` explicitly NULL and not matched: `null.length` threw in
  `wsNorm` too, where this port answers "old_str not found". Read it together with what is IDENTICAL
  beside it — an explicit null whose word "null" DOES occur once is applied by BOTH sides, because
  `cur.split(null)` searches for those four characters. That was a live defect of the JavaScript,
  reproduced rather than invented here, and it is why the fix distinguishes ABSENCE and not type.
- **1 `applyEditCoerced`** — `new_str` absent, on the WHITESPACE path only, where the JS died in
  `undefined.trim()`. The exact path is identical and that is the one that matters:
  `cur.replace(old, undefined)` wrote the word into the file and so does this. Kept, because
  `new_str` is INSERTED where the edit aimed rather than searched for, and `undefined` is not a Java
  expression — the green build fails to compile and says so, which is the opposite of a quiet wrong
  answer.

### Four more causes went away by fixing the code, which is what a harness is for

Two of them on the LIVE JAVASCRIPT side, which is the only side a marker was ever proven on:

- **6 `applyEditCoerced`** — a `fix_edit` with NO `old_str` at all. This port interpolated the absence
  into the string `"undefined"` and handed it to `applyEdit` as a real SEARCH NEEDLE, and Java source
  says that word in comments, string literals and identifiers — one occurrence and a malformed edit
  APPLIED where nobody aimed it. Fixed here first; `lib/edit.js` then grew the same guard, in the same
  place. The JS could not misapply it but died in `wsNorm` on `undefined.length`, so the whole prove
  came back `{ok:false}` and the engine retried a marker whose real answer was "that edit was
  malformed". Both sides now refuse that ONE edit and apply the rest.
- **5 `readFile`** — THE FIRST ONE FIXED ON THE JAVASCRIPT SIDE. Three cases were a path escaping the
  repo through a SIBLING directory whose name merely STARTS with the cache key — `<key>.tmp`, the
  half-finished clone, which has a `.git/config` of its own. The JS compared strings, so
  `<base>.tmp/...` passed `startsWith(<base>)`. It now tests for the separator, as this port's
  component-wise `startsWith` always did. The other two came with the containment
  `Workspace.readFile` grew for the credential leak. The invariant that used to report VIOLATED on the
  JS side now holds on both — and the test asserts that, so the record cannot quietly rot.

Two more went away by fixing the Java: 50 `lease` cases (`ttl_s` of `"0x10"` / `"0b101"` / `[60]` —
`Number()` reads all five and the first cut of `Lease.number` answered NaN, which is never greater
than `now`, so the mutual exclusion the lease exists for was silently off) and 10 `coerce` cases (an
explicit `"repo": null` spells `"null"` in JavaScript and spelled `"undefined"` here, and `Workspace`
hashes `` `${repo}@…` `` into the cache DIRECTORY NAME).

### The invariants

Agreement is not enough for the rules that exist to stop a bad edit: two implementations that both
picked the wrong span out of two candidates would agree perfectly and the harness would print
IDENTICAL. So each side is also checked against an ORACLE written a different way — occurrences are
counted by splitting, where `Edit.java` walks the string character by character. Eight rules, each
reported with the number of cases it APPLIED to as well as the number it failed on, because "0
violations" out of 0 applicable cases is not evidence of anything. All eight hold on both sides.

---

## THE COST OF FREEZING, stated plainly

**The JavaScript is gone.** `java-runner/` was deleted on 2026-07-31, in the same change that wrote
these fixtures. What follows is what that costs, and none of it is hypothetical.

1. **A divergence in a case NOBODY GENERATED can no longer be found.** The harness now proves that
   this port agrees with the JavaScript on 23 851 inputs. It cannot say anything about the 23 852nd.
   If a reviewer asks "what does the old code do with a `new_str` containing a lone surrogate?", the
   answer is no longer "let's run it" — it is "nobody asked in July 2026, and now nobody can".

2. **The fixtures cannot be regenerated.** Not "with effort" — at all. Regenerating them means having
   the JavaScript, and the JavaScript exists only in `git log` (the last version is in the working
   tree of the commit that deleted it, and before that in `n8n-fleet/java-runner/`). Restoring it
   would give you a program that has not been deployed since the port landed, has not been maintained
   since, and whose agreement with the current Java would prove nothing about the current Java. **If
   somebody proposes "just regenerate the fixtures", the honest answer is that there is nothing to
   regenerate them FROM.** Treat these files as an archive, not a cache.

3. **The corpus is therefore permanently fixed.** New behaviour in this module gets ordinary unit
   tests (`EditTest`, `BuildTest`, `WorkspaceTest`, …). It does NOT get new differential cases, and
   pretending otherwise by adding a case with a hand-written "JS answer" would be worse than useless:
   it would put a guess in a file whose whole authority is that every value in it was MEASURED.

4. **What the freeze does still buy** — and this is why the trade was taken. Before: 23 851 cases and
   745 catalogued divergences that nothing ran, in a script whose first step was `node`, against a
   service that was about to be deleted. The evidence had a half-life of one `rm -rf`. After: the same
   23 851 cases run on every build, the 745 divergences are re-derived from the port's current
   behaviour rather than asserted in prose, and the day somebody changes `Edit.applyEdit` the build
   tells them which kind moved. That is strictly more than the harness was delivering, for a cost that
   was already sunk the moment the JavaScript was scheduled for deletion.

---

## Changing the catalogue

`expected.json` is asserted as text, so a legitimate change is reviewed as a diff:

```
mvn -pl runner -am test -Dtest=DifferentialHarnessTest -Dharness.record=true
git diff harness/fixtures/expected.json
```

Every line of that diff is a behaviour this service was proven against. Re-recording without reading
it is how a differential harness becomes a rubber stamp.
