#!/usr/bin/env python3
"""Run the prover's `Prep prover` Code node and assert the test path/package it derives.

Regression origin (found by e2e, 2026-07-27): the first real prove wrote its test to
`src/test/java/Assignment5FsmProofTest.java` with NO package declaration, instead of
`src/test/java/org/owasp/webgoat/lessons/challenges/challenge5/Assignment5FsmProofTest.java`.

Prep split the file path on `"/src/main/java/"` — with a LEADING SLASH. That works for the parent
pipeline, whose suspector reported paths under a module (`opennlp-api/src/main/java/...`), but the
Svace ingester emits repo-relative paths, and for a single-module repo like WebGoat those START with
`src/main/java/`. The separator never matched, so module, package and package directory all came out
empty and every generated test landed in the default package.

Nothing failed loudly: the test still compiled, so this surfaced only as a wrong-looking path.

    python3 test_prep.py
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tables  # noqa: E402
tables.SUSPICIONS_TABLE = tables.BUGS_TABLE = "test0000000000"
tables.SCAN_FILES_TABLE = tables.METHOD_RUNS_TABLE = "test0000000000"
import gen_prover  # noqa: E402


def run_prep(row):
    js = """
const ROW = %s;
const $json = ROW;
const $env = { GITHUB_TOKEN: 'test-token' };
const out = (async function () {
%s
})();
out.then(r => process.stdout.write(JSON.stringify(r)));
""" % (json.dumps(row), gen_prover.PREP)
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(js)
        path = f.name
    try:
        p = subprocess.run(["node", path], capture_output=True, text=True)
        if p.returncode != 0:
            raise AssertionError("Prep threw:\n" + p.stderr.strip()[-2000:])
        return json.loads(p.stdout)
    finally:
        os.unlink(path)


FAILED = []


def check(label, got, want):
    if got == want:
        print("  ok   %s" % label)
    else:
        print("  FAIL %s\n         got  %r\n         want %r" % (label, got, want))
        FAILED.append(label)


def base(**kw):
    row = {"dedup_key": "k", "repo": "WebGoat/WebGoat", "branch": "main", "class_name": "",
           "method": "", "category": "taint", "severity": "high", "title": "t",
           "description": "d", "evidence": "Settle-by: test.", "svace_line": 44}
    row.update(kw)
    return row


def main():
    # 1) THE REGRESSION: single-module repo, path relative to the repo root.
    print("single-module repo (path starts at src/main/java/)")
    r = run_prep(base(file="src/main/java/org/owasp/webgoat/lessons/challenges/challenge5/Assignment5.java"))
    check("module is empty", r["module"], "")
    check("package resolved", r["pkg"], "org.owasp.webgoat.lessons.challenges.challenge5")
    check("test path keeps the package dirs", r["test_path"],
          "src/test/java/org/owasp/webgoat/lessons/challenges/challenge5/Assignment5FsmProofTest.java")
    check("class name", r["class_name"], "Assignment5")
    check("test class", r["test_class"], "Assignment5FsmProofTest")

    # 2) Multi-module — the shape the parent pipeline produced. Must keep working.
    print("\nmulti-module repo (module prefix before src/main/java/)")
    r = run_prep(base(file="webgoat-container/src/main/java/org/owasp/webgoat/container/Foo.java"))
    check("module extracted", r["module"], "webgoat-container")
    check("package resolved", r["pkg"], "org.owasp.webgoat.container")
    check("test path under the module", r["test_path"],
          "webgoat-container/src/test/java/org/owasp/webgoat/container/FooFsmProofTest.java")

    # 3) A class in the default package: no directories after src/main/java/.
    print("\nclass directly under src/main/java")
    r = run_prep(base(file="src/main/java/Root.java"))
    check("no package", r["pkg"], "")
    check("test path", r["test_path"], "src/test/java/RootFsmProofTest.java")

    # 4) A path with no src/main/java at all must not crash or invent a package.
    print("\npath without src/main/java")
    r = run_prep(base(file="Weird.java"))
    check("no package", r["pkg"], "")
    check("test path", r["test_path"], "src/test/java/WeirdFsmProofTest.java")

    # 5) Svace provenance has to survive into the prompt-building stages.
    print("\nmarker provenance carried through")
    r = run_prep(base(file="src/main/java/a/B.java", svace_checker="TAINTED_PTR",
                      svace_severity="Critical", marker_id="m1", svace_line=41,
                      evidence="Svace Critical marker. Settle-by: argue."))
    check("checker", r["svace_checker"], "TAINTED_PTR")
    check("severity", r["svace_severity"], "Critical")
    check("marker id", r["marker_id"], "m1")
    check("svace line", r["svace_line"], 41)
    check("settle_by parsed from evidence", r["settle_by"], "argue")
    r = run_prep(base(file="src/main/java/a/B.java", evidence="no hint here"))
    check("settle_by defaults to test", r["settle_by"], "test")

    print()
    if FAILED:
        raise SystemExit("%d check(s) FAILED: %s" % (len(FAILED), ", ".join(FAILED)))
    print("all checks passed")


if __name__ == "__main__":
    main()
