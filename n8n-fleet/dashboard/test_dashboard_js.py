#!/usr/bin/env python3
"""Static checks on the dashboard's inline JavaScript.

Origin (2026-07-28): the dashboard rendered a completely blank page. The server was fine — /api/state
returned 282 markers and 152 artifacts — but the page was dead, because `const settled` had been
declared twice in the same scope. A duplicate `const` is a SyntaxError, so the browser discarded the
ENTIRE inline script: no render, no error visible anywhere server-side, and every HTTP check still
returning 200. Nothing in the pipeline could detect it.

The dashboard's JS lives inside a Python string, so it is never parsed by anything until a browser
tries. These checks are the cheapest possible substitute for that:

  1. the script actually parses (node --check)
  2. every getElementById target exists in the page, since a null dereference aborts the render from
     that point on and silently blanks whatever renders later
  3. tick() actually RENDERS — driven over a synthetic state under a DOM stub. Parsing is not
     rendering, and a TypeError halfway through the render blanks the page just as completely as a
     SyntaxError while still passing checks 1 and 2.

    python3 test_dashboard_js.py
"""
import json
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
FAILED = []


def check(label, cond, detail=""):
    if cond:
        print("  ok   %s" % label)
    else:
        print("  FAIL %s\n         %s" % (label, detail))
        FAILED.append(label)


def page_source():
    s = open(os.path.join(HERE, "dashboard.py")).read()
    i = s.index('PAGE = r"""') + len('PAGE = r"""')
    return s[i:s.index('"""', i)]


def main():
    page = page_source()
    blocks = re.findall(r"<script[^>]*>(.*?)</script>", page, re.S)
    check("page has an inline script", len(blocks) >= 1, "found %d" % len(blocks))

    for n, b in enumerate(blocks):
        with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
            f.write(b)
            p = f.name
        try:
            r = subprocess.run(["node", "--check", p], capture_output=True, text=True)
            err = ""
            if r.returncode != 0:
                # strip the temp path so the message points at the symptom, not a scratch file
                err = "\n         ".join(
                    ln for ln in r.stderr.splitlines()[:6] if ln.strip() and "/tmp" not in ln and not ln.startswith("    at "))
            check("script block %d parses" % n, r.returncode == 0, err)
        finally:
            os.unlink(p)

    ids = set(re.findall(r"\bid=([A-Za-z_][\w-]*)", page)) | set(re.findall(r'\bid="([^"]+)"', page))
    used = set(re.findall(r"getElementById\('([^']+)'\)", page)) | set(re.findall(r'getElementById\("([^"]+)"\)', page))
    # renderLive guards on `live` before touching either, so both are reachable only when present.
    guarded = {"live", "livecount"}
    missing = sorted(used - ids - guarded)
    check("no getElementById target is missing from the page", not missing,
          "referenced but never rendered: " + ", ".join(missing))

    if "getElementById('live')" in page:
        idx_guard = page.find("if(!document.getElementById('live')) return;")
        idx_use = page.find("getElementById('livecount')")
        check("renderLive bails before touching removed elements",
              idx_guard != -1 and idx_guard < idx_use,
              "guard at %d, first use at %d" % (idx_guard, idx_use))

    # Parsing is not rendering. Both blank-page failures so far were invisible to every static check
    # and to the server; the only thing that would have caught them is running the render. This drives
    # tick() over a synthetic state under a DOM stub and fails if it throws or leaves a panel empty.
    state = {
        "scan": {"status": "idle", "repo": "WebGoat/WebGoat"},
        "files": [], "prover_built": True,
        "activity": [{"wf": "prover", "status": "running", "started": "2026-07-28 07:00:00", "file": "", "dur": 12}],
        "suspicions": [
            {"dedup_key": "k1", "repo": "WebGoat/WebGoat", "file": "src/main/java/a/B.java", "status": "verified",
             "severity": "high", "svace_severity": "Critical", "svace_checker": "TAINTED_PTR", "svace_line": 44,
             "category": "taint", "anchor": "login", "anchor_status": "exact", "note": ""},
            {"dedup_key": "k2", "repo": "WebGoat/WebGoat", "file": "src/main/java/a/C.java", "status": "new",
             "severity": "low", "svace_severity": "Minor", "svace_checker": "FB.EI_EXPOSE_REP2", "svace_line": 7,
             "category": "mutable-exposure", "anchor": "", "anchor_status": "pending", "note": ""},
        ],
        "bugs": [
            {"suspicion_key": "k1", "state": "pr_ready", "file": "src/main/java/a/B.java", "title": "t",
             "red_verified": "1", "green_verified": "1", "verdict_kind": "true-positive",
             "verdict_text": "CONFIRMED, and fixed.", "svace_checker": "TAINTED_PTR", "svace_severity": "Critical",
             "svace_line": 44, "category": "taint", "anchor": "login", "description": "a claim",
             "marker_orphaned": False},
        ],
        "work": {"totalMarkers": 2, "settled": 1, "remaining": 1, "humanHours": 2.2, "machineHours": 0.3,
                 "fte": 7.3, "fteBasis": 1, "fteSettledOnly": 9.1, "retryHours": 0.1, "etaSec": 3600,
                 "humanMin": {"triage": 10, "assess": 20, "write_test": 45, "write_fix": 40,
                              "verify": 15, "rebut": 25}},
    }
    script = blocks[0] if blocks else ""
    harness = """
const STATE = %s;
const els = {};
const mk = () => ({ innerHTML:'', textContent:'', className:'', style:{},
                    parentElement:{querySelector:()=>mk()}, querySelector:()=>mk() });
global.document = { getElementById:(id)=>(els[id]=els[id]||mk()), querySelectorAll:()=>[], addEventListener:()=>{} };
global.window = {}; global.location = { hash:'' };
global.setInterval = ()=>{}; global.setTimeout = ()=>{};
global.fetch = async (u) => ({ json: async () =>
  u.includes('api/state') ? STATE : (u.includes('errors') ? {errors:[]} : {dialogs:[]}) });
%s
(async () => {
  try { await tick(); } catch (e) { console.log('THREW: ' + e.message); process.exit(1); }
  const need = ['stats','work','verdicts','suspicions','stage-name'];
  const empty = need.filter(k => !(els[k] && (els[k].innerHTML || els[k].textContent)));
  if (empty.length) { console.log('EMPTY: ' + empty.join(',')); process.exit(2); }
  console.log('OK');
})();
""" % (json.dumps(state), script)
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(harness)
        hp = f.name
    try:
        r = subprocess.run(["node", hp], capture_output=True, text=True)
        out = (r.stdout + r.stderr).strip().splitlines()
        check("tick() renders without throwing and fills every panel",
              r.returncode == 0, out[-1] if out else "no output")
    finally:
        os.unlink(hp)

    print()
    if FAILED:
        raise SystemExit("%d check(s) FAILED: %s" % (len(FAILED), ", ".join(FAILED)))
    print("all checks passed")


if __name__ == "__main__":
    main()
