#!/usr/bin/env python3
"""Mutation-test the JavaScript inside the n8n Code nodes.

The pipeline's logic lives in JS strings inside the generators, so no ordinary tool sees it. Coverage
would only say a line ran; mutation says it was CHECKED. That distinction is the whole point here —
every serious bug this pipeline has had (a state-machine branch that retired markers silently, a
verdict route that was never wired, a duplicate const that blanked the dashboard) sat on lines that
executed perfectly well while nothing asserted on what they produced.

Each node body is a Python constant, and the tests import it. So a mutant is seeded by patching that
constant in-process and re-running the tests that exercise it — no subprocess plumbing, no rewriting
of the body under test.

    python3 measure.py                 # every node that has a test
    python3 measure.py --node Record   # one node
    python3 measure.py --limit 40      # more mutants per node
"""
import argparse
import importlib
import io
import json
import os
import re
import sys
from contextlib import redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tables  # noqa: E402
for _a in ("SUSPICIONS_TABLE", "BUGS_TABLE", "SCAN_FILES_TABLE", "METHOD_RUNS_TABLE"):
    if getattr(tables, _a).startswith("REPLACE_"):
        setattr(tables, _a, "measure00000000")

MIN_LINES = 10   # per the brief: do not chase coverage on trivial glue nodes

# node name -> (generator module, constant holding its JS, tests that exercise it)
NODES = {
    "Prep prover":           ("gen_prover", "PREP",                  ["test_prep"]),
    "Build reproduce input": ("gen_prover", "BUILD_REPRODUCE_INPUT",  ["test_anchor"]),
    "Parse test":            ("gen_prover", "PARSE_TEST",             ["test_realness"]),
    "Record outcome":        ("gen_prover", "RECORD",                 ["test_record"]),
    "Verdict":               ("gen_prover", "VERDICT",                ["test_verdict"]),
    "Parse fix":             ("gen_prover", "PARSE_FIX",              ["test_parsefix"]),
    "Parse markers":         ("gen_ingest", "PARSE",                  ["test_ingest"]),
}

MUTATORS = [
    ("negate ===",    re.compile(r"(?<![!=<>])===(?!=)"), "!=="),
    ("negate !==",    re.compile(r"!==(?!=)"),            "==="),
    ("boundary >=",   re.compile(r">="),                  ">"),
    ("boundary <=",   re.compile(r"<="),                  "<"),
    ("boundary <",    re.compile(r"(?<![<>=!])<(?![<=])"), "<="),
    ("boundary >",    re.compile(r"(?<![<>=!])>(?![>=])"), ">="),
    ("&& -> ||",      re.compile(r"&&"),                  "||"),
    ("|| -> &&",      re.compile(r"\|\|"),                "&&"),
    ("true -> false", re.compile(r"\btrue\b"),            "false"),
    ("false -> true", re.compile(r"\bfalse\b"),           "true"),
]


def mutants(js, limit):
    """(label, line, mutated js) for seeded faults, spread evenly through the body."""
    lines = js.splitlines()
    found = []
    for i, line in enumerate(lines):
        t = line.strip()
        if not t or t.startswith("//") or t.startswith("*") or t.startswith("/*"):
            continue
        for label, pat, rep in MUTATORS:
            if pat.search(line):
                m = lines[:]
                m[i] = pat.sub(rep, line, count=1)
                found.append((label, i + 1, "\n".join(m)))
                break
    if len(found) <= limit:
        return found
    step = len(found) / float(limit)
    return [found[int(k * step)] for k in range(limit)]


def run_tests(test_names):
    """True if every named test module passes. Output is swallowed; only the verdict matters."""
    for t in test_names:
        try:
            mod = importlib.import_module(t)
            importlib.reload(mod)
            buf = io.StringIO()
            with redirect_stdout(buf):
                mod.main()
        except SystemExit as e:
            if e.code:
                return False
        except Exception:
            return False
    return True


def body_lines(js):
    return len([l for l in js.splitlines() if l.strip() and not l.strip().startswith("//")])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--node")
    ap.add_argument("--limit", type=int, default=25)
    args = ap.parse_args()

    targets = {k: v for k, v in NODES.items()
               if not args.node or args.node.lower() in k.lower()}

    # A mutation score is meaningless unless the suite is green to begin with.
    print("checking the suite is green before seeding faults...")
    baseline = {}
    for name, (mod_name, const, tests) in sorted(targets.items()):
        try:
            mod = importlib.import_module(mod_name)
        except SystemExit:
            raise SystemExit("cannot import %s — run sync_tables.py first" % mod_name)
        missing = [t for t in tests if not os.path.exists(os.path.join(HERE, t + ".py"))]
        if missing:
            baseline[name] = ("NO TEST", None, None)
            continue
        if not run_tests(tests):
            baseline[name] = ("SUITE RED", None, None)
            continue
        baseline[name] = ("ok", mod, const)
    print()

    print("%-24s %6s %8s %7s %8s   %s" % ("NODE", "LINES", "MUTANTS", "KILLED", "SCORE", "SURVIVORS"))
    tot_m = tot_k = 0
    for name, (status, mod, const) in sorted(baseline.items()):
        if status != "ok":
            print("%-24s %6s %8s %7s %8s   %s" % (name, "-", "-", "-", "-", status))
            continue
        js = getattr(mod, const)
        ms = mutants(js, args.limit)
        killed, survivors = 0, []
        for label, line, mjs in ms:
            setattr(mod, const, mjs)
            try:
                survived = run_tests(NODES[name][2])
            finally:
                setattr(mod, const, js)
            if survived:
                survivors.append("%s:%d" % (label, line))
            else:
                killed += 1
        score = round(100.0 * killed / len(ms)) if ms else 0
        tot_m += len(ms)
        tot_k += killed
        print("%-24s %6d %8d %7d %7d%%   %s"
              % (name, body_lines(js), len(ms), killed, score,
                 ", ".join(survivors[:2]) + (" +%d" % (len(survivors) - 2) if len(survivors) > 2 else "")))
    if tot_m:
        overall = round(100.0 * tot_k / tot_m)
        print("\nMUTATION SCORE: %d%% (%d/%d mutants killed)" % (overall, tot_k, tot_m))
        print("Target is 80%. A survivor is a line the tests execute but never check.")


if __name__ == "__main__":
    main()
