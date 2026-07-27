#!/usr/bin/env python3
"""Run the prover's marker re-anchoring against REAL WebGoat sources, for every src/main marker.

The scanned commit is unknown, so `File:Line` is resolved against upstream HEAD and has drifted by an
unknown amount. This harness answers the question that decides whether the prover can be trusted at
all: for each of the 282 markers, does the reported line still land inside a method, and which one?

It also exercises the brace-matching itself. That code masks comments and string literals to keep the
scan in sync; a Java file with a `"}"` in a string or a `/* { */` comment breaks a naive version, and
WebGoat has both.

    python3 test_anchor.py            # uses a local cache of the fetched sources

Network: fetches each distinct file once from raw.githubusercontent.com, then caches.
"""
import csv
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
CSV_PATH = os.path.join(ROOT, "data", "svace", "webgoat-markers-356.csv")
CACHE = os.environ.get("ANCHOR_CACHE", "/tmp/fsm-webgoat-src")
REPO_RAW = "https://raw.githubusercontent.com/WebGoat/WebGoat/main/"
PREFIX = "/builds/gitlab/drit_digital_trace/owasp-webgoat/"

sys.path.insert(0, HERE)
import tables  # noqa: E402
tables.SUSPICIONS_TABLE = tables.BUGS_TABLE = "test0000000000"
tables.SCAN_FILES_TABLE = tables.METHOD_RUNS_TABLE = "test0000000000"
import gen_prover  # noqa: E402


def anchor_js():
    """Lift the mask/enclosingMethod pair out of BUILD_REPRODUCE_INPUT so the test runs the REAL code."""
    src = gen_prover.BUILD_REPRODUCE_INPUT
    start = src.index("function mask(s)")
    end = src.index("const lines = src.split")
    return src[start:end]


def fetch(rel):
    dest = os.path.join(CACHE, rel)
    if os.path.exists(dest):
        with open(dest, encoding="utf-8", errors="replace") as f:
            return f.read()
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    try:
        with urllib.request.urlopen(REPO_RAW + rel, timeout=60) as r:
            body = r.read().decode("utf-8", "replace")
    except Exception:
        body = ""          # deleted upstream — a real and interesting outcome, not a test error
    with open(dest, "w", encoding="utf-8") as f:
        f.write(body)
    return body


def main():
    rows = [r for r in csv.DictReader(open(CSV_PATH))
            if "/src/main/" in r["File"]]
    by_file = {}
    for r in rows:
        by_file.setdefault(r["File"].replace(PREFIX, ""), []).append(int(r["Line"]))

    print("fetching %d distinct files (cache: %s)" % (len(by_file), CACHE))
    sources = {}
    for i, rel in enumerate(sorted(by_file), 1):
        sources[rel] = fetch(rel)
        if i % 40 == 0:
            print("  %d/%d" % (i, len(by_file)))

    payload = [{"file": rel, "src": sources[rel], "lines": sorted(set(by_file[rel]))}
               for rel in sorted(by_file)]

    js = """
const INPUT = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));
%s
const out = [];
for (const f of INPUT) {
  const lines = f.src.split('\\n');
  for (const line of f.lines) {
    let status = 'unresolved', name = '';
    if (!f.src.trim()) status = 'no-source';
    else if (line < 1 || line > lines.length) status = 'past-eof';
    else {
      const em = enclosingMethod(f.src, line);
      if (em) { status = 'exact'; name = em.name; } else status = 'no-method';
    }
    out.push({ file: f.file, line, status, method: name,
               text: (status === 'exact' || status === 'no-method') ? lines[line-1].trim().slice(0,90) : '' });
  }
}
process.stdout.write(JSON.stringify(out));
""" % anchor_js()

    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(js)
        jsp = f.name
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(payload, f)
        jp = f.name
    try:
        p = subprocess.run(["node", jsp, jp], capture_output=True, text=True)
        if p.returncode != 0:
            raise AssertionError("anchor JS threw:\n" + p.stderr[-3000:])
        res = json.loads(p.stdout)
    finally:
        os.unlink(jsp)
        os.unlink(jp)

    counts = {}
    for r in res:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    print("\nanchor status over %d src/main markers:" % len(res))
    for k in sorted(counts, key=lambda k: -counts[k]):
        print("  %-12s %4d  (%.1f%%)" % (k, counts[k], 100.0 * counts[k] / len(res)))

    missing = sorted({r["file"] for r in res if r["status"] == "no-source"})
    if missing:
        print("\nfiles that no longer exist upstream (%d):" % len(missing))
        for m in missing[:15]:
            print("   ", m)

    print("\nsample of resolved anchors:")
    for r in [x for x in res if x["status"] == "exact"][:8]:
        print("  %s:%d -> %s()  | %s" % (r["file"].split("/")[-1], r["line"], r["method"], r["text"]))

    print("\nsample of line-not-in-a-method:")
    for r in [x for x in res if x["status"] == "no-method"][:8]:
        print("  %s:%d  | %s" % (r["file"].split("/")[-1], r["line"], r["text"]))

    failures = []
    # The scan must never crash and must classify every marker into a known bucket.
    known = {"exact", "no-method", "past-eof", "no-source", "unresolved"}
    if not set(counts) <= known:
        failures.append("unknown status values: %s" % (set(counts) - known))
    # 282 src/main markers, but several share a (file, line) — two checkers can fire on one line — and
    # anchoring is a property of the LOCATION, so the harness resolves each distinct site once.
    if len(res) != 240:
        failures.append("expected 240 distinct src/main marker sites, got %d" % len(res))
    # If brace matching were broken, almost nothing would resolve. This is the regression guard.
    exact_pct = 100.0 * counts.get("exact", 0) / len(res)
    if exact_pct < 60:
        failures.append("only %.1f%% of markers resolved to a method — brace matching is likely broken"
                        % exact_pct)
    print()
    if failures:
        raise SystemExit("FAILED: " + "; ".join(failures))
    print("ok — %.1f%% of markers anchor onto an enclosing method" % exact_pct)


if __name__ == "__main__":
    main()
