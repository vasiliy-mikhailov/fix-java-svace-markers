#!/usr/bin/env python3
"""Run the ingester's Code-node JS against the real 356-marker report and assert what it produces.

The parse step is the whole of stage 3 — everything downstream trusts its output — and it is written
in JS embedded in a Python string, where nothing type-checks it. This harness executes that exact
string under node with the n8n globals stubbed, so a bad path prefix, a dropped filter or a
dedup_key collision fails HERE instead of after a deploy + a 356-row ingest.

    python3 test_ingest.py
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CSV = os.path.normpath(os.path.join(HERE, "..", "..", "..", "data", "svace", "webgoat-markers-356.csv"))

# gen_ingest refuses to generate against placeholder table ids; the parse JS does not use them, so
# stub them out to import the module and read PARSE.
sys.path.insert(0, HERE)
import tables  # noqa: E402
tables.SUSPICIONS_TABLE = tables.BUGS_TABLE = "test0000000000"
tables.SCAN_FILES_TABLE = tables.METHOD_RUNS_TABLE = "test0000000000"
import gen_ingest  # noqa: E402


def run_parse(body):
    """Execute the PARSE code node with `body` as the webhook payload; return its emitted items."""
    js = """
const BODY = %s;
function $(name) { return { first: () => ({ json: { body: BODY } }) }; }
const console = { log: () => {} };
const items = (function () {
%s
})();
process.stdout.write(JSON.stringify(items.map(i => i.json)));
""" % (json.dumps(body), gen_ingest.PARSE)
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(js)
        path = f.name
    try:
        p = subprocess.run(["node", path], capture_output=True, text=True)
        if p.returncode != 0:
            raise AssertionError("parse threw:\n" + p.stderr.strip()[-2000:])
        return json.loads(p.stdout)
    finally:
        os.unlink(path)


FAILED = []


def check(label, cond, detail=""):
    if cond:
        print("  ok   %s" % label)
    else:
        print("  FAIL %s %s" % (label, detail))
        FAILED.append(label)


def main():
    if not os.path.exists(CSV):
        raise SystemExit("missing fixture: " + CSV)
    body = {"repo": "WebGoat/WebGoat", "branch": "main", "csv_path": CSV}

    print("default ingest (src/main only)")
    rows = run_parse(body)
    summary = json.loads(rows[0]["__summary"])

    # 356 markers: 282 under src/main, 26 under src/test, 48 under src/it.
    check("282 markers ingested", len(rows) == 282, "got %d" % len(rows))
    check("74 test/it markers skipped", summary["skipped"]["tests"] == 74,
          "got %d" % summary["skipped"]["tests"])
    check("no rows dropped as malformed", summary["skipped"]["bad_row"] == 0)
    check("every checker is mapped", not summary["unmapped_checkers"],
          str(summary["unmapped_checkers"]))
    check("all rows counted", summary["csv_rows"] == 356, "got %d" % summary["csv_rows"])

    # The CI build prefix must be gone: a path starting with / or containing /builds/ opens nowhere.
    bad = [r["file"] for r in rows if not r["file"].startswith("src/main/java/")]
    check("paths normalized to repo-relative", not bad, str(bad[:3]))

    keys = [r["dedup_key"] for r in rows]
    check("dedup_key unique", len(set(keys)) == len(keys),
          "%d dupes" % (len(keys) - len(set(keys))))
    ids = [r["marker_id"] for r in rows]
    check("marker_id unique", len(set(ids)) == len(ids))

    check("svace_line preserved", all(r["svace_line"] == r["line"] for r in rows))
    check("lines are positive ints", all(isinstance(r["line"], int) and r["line"] > 0 for r in rows))
    check("all status=new", all(r["status"] == "new" for r in rows))
    check("repo carried", all(r["repo"] == "WebGoat/WebGoat" for r in rows))
    check("no 'unmapped' category", not [r for r in rows if r["category"] == "unmapped"])
    check("every row has a meaning", all(len(r["description"]) > 20 for r in rows))
    check("anchor starts pending", all(r["anchor_status"] == "pending" for r in rows))

    # The three Critical TAINTED_PTR markers are the highest-value targets — make sure they survive.
    crit = [r for r in rows if r["svace_severity"] == "Critical"]
    check("3 Critical markers kept", len(crit) == 3, "got %d" % len(crit))
    check("Critical mapped to high", all(r["severity"] == "high" for r in crit))

    print("\ninclude_tests=true")
    allrows = run_parse({**body, "include_tests": True})
    check("356 markers when tests included", len(allrows) == 356, "got %d" % len(allrows))

    print("\nmin_severity=Major")
    major = run_parse({**body, "min_severity": "Major"})
    check("59 Critical+Major under src/main", len(major) == 59, "got %d" % len(major))

    print("\nonly_checkers filter")
    # 15 HANDLE_LEAK markers in the report; one sits in SqlInjectionLesson5Test.java under src/test,
    # which the default filter drops — so 14 is the correct count, not 15.
    leaks = run_parse({**body, "only_checkers": ["HANDLE_LEAK"]})
    check("14 HANDLE_LEAK markers under src/main", len(leaks) == 14, "got %d" % len(leaks))
    check("filtered rows keep their checker",
          all(r["svace_checker"] == "HANDLE_LEAK" for r in leaks))

    print("\ninline csv_text + a foreign CI root")
    inline = run_parse({"repo": "x/y", "csv_text":
                        'Severity,Checker,File,Line\n'
                        '"Major","HANDLE_LEAK","/builds/other/root/src/main/java/A.java","7"\n'})
    check("unknown prefix still normalizes via /src/",
          inline[0]["file"] == "src/main/java/A.java", inline[0]["file"])

    print("\nunknown checker is kept, not dropped")
    unk = run_parse({"repo": "x/y", "csv_text":
                     'Severity,Checker,File,Line\n'
                     '"Major","BRAND_NEW_CHECKER","/builds/o/r/src/main/java/A.java","7"\n'})
    check("unmapped checker ingested", len(unk) == 1 and unk[0]["category"] == "unmapped")
    check("unmapped checker still carries its name",
          "BRAND_NEW_CHECKER" in unk[0]["description"])

    print()
    if FAILED:
        raise SystemExit("%d check(s) FAILED: %s" % (len(FAILED), ", ".join(FAILED)))
    print("all checks passed")


if __name__ == "__main__":
    main()
