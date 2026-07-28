#!/usr/bin/env python3
"""Static checks on the dashboard's inline JavaScript.

Origin (2026-07-28): the dashboard rendered a completely blank page. The server was fine — /api/state
returned 282 markers and 152 artifacts — but the page was dead, because `const settled` had been
declared twice in the same scope. A duplicate `const` is a SyntaxError, so the browser discarded the
ENTIRE inline script: no render, no error visible anywhere server-side, and every HTTP check still
returning 200. Nothing in the pipeline could detect it.

The dashboard's JS lives inside a Python string, so it is never parsed by anything until a browser
tries. These two checks are the cheapest possible substitute for that:

  1. the script actually parses (node --check)
  2. every getElementById target exists in the page, since a null dereference aborts the render from
     that point on and silently blanks whatever renders later

    python3 test_dashboard_js.py
"""
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

    print()
    if FAILED:
        raise SystemExit("%d check(s) FAILED: %s" % (len(FAILED), ", ".join(FAILED)))
    print("all checks passed")


if __name__ == "__main__":
    main()
