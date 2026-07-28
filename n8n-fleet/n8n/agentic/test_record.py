#!/usr/bin/env python3
"""Tests for `Record outcome` — the state machine that decides what a marker becomes.

This is the highest-consequence code in the pipeline and it had no test at all. Every downstream
decision keys off `state`: whether a PR is drafted, whether a verdict is written, whether the marker
is retried or retired for good. A wrong branch here does not crash — it silently retires a real defect
as `not_reproduced`, or drafts a PR from a fix that was never applied.

The distinction it exists to protect is infra-vs-verdict: a build that never compiled, a source fetch
that returned nothing, an unparseable reply are all failures OF THE PIPELINE and must be retried,
never recorded as a judgement about the code.

    python3 test_record.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tables  # noqa: E402
tables.SUSPICIONS_TABLE = tables.BUGS_TABLE = "test0000000000"
tables.SCAN_FILES_TABLE = tables.METHOD_RUNS_TABLE = "test0000000000"
import gen_prover  # noqa: E402
from nodekit import run_node  # noqa: E402

FAILED = []


def check(label, got, want):
    if got == want:
        print("  ok   %s" % label)
    else:
        print("  FAIL %s\n         got  %r\n         want %r" % (label, got, want))
        FAILED.append(label)


def record(prep=None, parse_test=None, parse_fix=None, repro=None, bri=None, pm=None):
    """Drive Record outcome. Defaults describe a clean, fully-proven marker."""
    nodes = {
        "Prep prover": {"suspicion_key": "k", "repo": "o/r", "file": "src/main/java/a/B.java",
                        "title": "t", "test_path": "src/test/java/a/BTest.java", "branch": "main",
                        "branch_ok": True, "prove_attempts": 0, **(prep or {})},
        "Parse test": {"can_prove": True, "parse_failed": False, "test_code": "x",
                       "test_sound": True, "test_score": 100, "test_realness": "real",
                       "repro_value_verdict": "real", **(parse_test or {})},
        "Parse fix": {"can_fix": True, "fix_parse_failed": False, "fix_rejected": "",
                      "fix_edits_json": "[]", "fix_root_cause": "rc", **(parse_fix or {})},
        "run_test reproduce": {"ok": True, "red_reproduced": True, "jdk": "25",
                               "red_summary": {"test_executed": True}, **(repro or {})},
        "Build reproduce input": {"src": "class B {}", "src_truncated": False, **(bri or {})},
    }
    j = {"proven": True, "green_passed": True, "skeptic_verdict": "sound", "pr_decision": "make",
         "applied_files": ["src/main/java/a/B.java"], "edit_errors": [], **(pm or {})}
    out, _ = run_node(gen_prover.RECORD, json=j, nodes=nodes)
    return out


def main():
    print("the happy path")
    r = record()
    check("fully proven and curated -> pr_ready", r["state"], "pr_ready")
    check("red recorded", r["red_verified"], True)
    check("green recorded", r["green_verified"], True)
    check("value_score carries test realness", r["value_score"], 100)

    print("\ninfra failures must be RETRIED, never recorded as a judgement")
    for label, kw in [
        ("branch unresolved", {"prep": {"branch_ok": False, "branch_error": "404"}}),
        ("source fetch empty", {"bri": {"src": "   "}}),
        ("source truncated", {"bri": {"src_truncated": True}}),
        ("reproducer reply unparseable", {"parse_test": {"parse_failed": True}}),
        ("fixer reply unparseable", {"parse_fix": {"fix_parse_failed": True}}),
        ("edits hit the source-only allowlist", {"parse_fix": {"fix_rejected": "pom.xml"}}),
        ("run_test(reproduce) errored", {"repro": {"error": "boom"}}),
        ("run_test(fix) errored", {"pm": {"error": "boom"}}),
    ]:
        check(label + " -> infra_error", record(**kw)["state"], "infra_error")

    # The one that matters most: a test that never RAN is not evidence the bug is unreal.
    r = record(repro={"red_reproduced": False, "red_summary": {"test_executed": False},
                      "red_output": "BUILD FAILURE"})
    check("test never executed -> infra_error, not not_reproduced", r["state"], "infra_error")
    check("build failure named in the reason", "BUILD FAILURE" in r["infra_reason"], True)

    print("\na green flip that proves nothing must not reach pr_ready")
    check("no edit applied at all -> needs_review",
          record(pm={"applied_files": [], "edit_errors": []})["state"], "needs_review")
    check("edit reported an error -> needs_review",
          record(pm={"edit_errors": ["old_str not found"]})["state"], "needs_review")
    r = record(parse_test={"test_sound": False, "test_realness": "never constructs B"})
    check("test does not exercise the real class -> needs_review", r["state"], "needs_review")
    check("reason names the problem on the PR body",
          "DOES NOT EXERCISE THE REAL CODE" in r["pr_body"], True)

    print("\nthe skeptic and the curator are fail-closed")
    check("skeptic silent -> needs_review, not pr_ready",
          record(pm={"skeptic_verdict": ""})["state"], "needs_review")
    check("skeptic flags over-fit -> needs_review",
          record(pm={"skeptic_verdict": "over-fit"})["state"], "needs_review")
    check("curator crashed (no decision) -> needs_review",
          record(pm={"pr_decision": ""})["state"], "needs_review")
    check("curator rejects -> pr_rejected",
          record(pm={"pr_decision": "reject"})["state"], "pr_rejected")

    print("\nnot proven")
    check("reproducer declined -> not-a-bug",
          record(parse_test={"can_prove": False})["state"], "not-a-bug")
    check("red but no green -> fix_failed",
          record(pm={"proven": False, "green_passed": False})["state"], "fix_failed")
    check("test ran and passed on unpatched code -> not_reproduced",
          record(repro={"red_reproduced": False}, pm={"proven": False, "green_passed": True})["state"],
          "not_reproduced")

    print("\nattempts are counted so a broken row stops being requeued")
    check("attempts incremented", record(prep={"prove_attempts": 2})["attempts"], 3)

    print("\nvalue_score is only claimed where a test was judged")
    check("not-a-bug scores 0", record(parse_test={"can_prove": False})["value_score"], 0)

    print()
    if FAILED:
        raise SystemExit("%d check(s) FAILED: %s" % (len(FAILED), ", ".join(FAILED)))
    print("all checks passed")


if __name__ == "__main__":
    main()
