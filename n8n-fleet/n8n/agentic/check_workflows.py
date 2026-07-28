#!/usr/bin/env python3
"""Pre-deploy validation for the generated n8n workflows.

`node --check` only catches SYNTAX. The failures that actually bit us were runtime ReferenceErrors in
Code nodes — `method is not defined` (class auditor) and `_toolCache is not defined` (deleted by an
edit that spanned its declaration). Under onError=continueRegularOutput n8n forwards the node's INPUT
on such a throw, so the failure looks like "no findings" rather than an error. Each Code node is run
through `node --check` (syntax), then eslint's `no-undef` (scope) and `no-use-before-define` (the
temporal-dead-zone throws that `no-undef` cannot see — a `const` read above its own declaration cost
78% of one run). n8n's injected globals are declared. Verified against the real regressions.

Usage: python3 check_workflows.py [workflow_*.json ...]
"""
import glob
import json
import re
import os
import subprocess
import sys
import tempfile

# n8n injects these into Code nodes, alongside the standard JS globals.
N8N_GLOBALS = ["$json", "$input", "$env", "$", "$node", "$workflow", "$execution", "$prevNode",
               "$items", "$item", "$runIndex", "$mode", "$now", "$today", "console", "Buffer",
               "require", "process", "setTimeout", "URL", "URLSearchParams", "TextEncoder",
               "TextDecoder", "structuredClone", "globalThis"]

ESLINT_CONFIG = """export default [{
  languageOptions: {
    ecmaVersion: 2022,
    sourceType: "script",
    globals: { %s }
  },
  rules: { "no-undef": "error", "no-use-before-define": ["warn", {"variables": true, "functions": false}] }
}];
""" % ", ".join('"%s":"readonly"' % g for g in N8N_GLOBALS)


def code_nodes(wf):
    for n in wf.get("nodes", []):
        js = (n.get("parameters") or {}).get("jsCode")
        if js:
            yield n["name"], js


def check(path, workdir):
    """node --check for syntax + eslint no-undef for the ReferenceErrors that fail SILENTLY in n8n."""
    wf = json.load(open(path))
    problems, warnings = [], []
    for name, js in code_nodes(wf):
        body = os.path.join(workdir, "node.js")
        # the node body runs inside an async wrapper, so top-level await is legal
        open(body, "w").write("(async () => {\n" + js + "\n})();\n")
        r = subprocess.run(["node", "--check", body], capture_output=True, text=True)
        if r.returncode:
            problems.append((name, "SYNTAX: " + (r.stderr.strip().splitlines() or ["?"])[0]))
            continue
        r = subprocess.run(["npx", "--yes", "--quiet", "eslint", "--no-color", body],
                           capture_output=True, text=True, cwd=workdir)
        for ln in (r.stdout or "").splitlines():
            ln = ln.strip()
            m = re.match(r"^(\d+):(\d+)\s+(error|warning)\s+(.*)$", ln)
            if not m:
                continue
            if m.group(3) == "error":
                problems.append((name, m.group(4)))
            else:
                # `no-use-before-define` also fires on the legitimate pattern of a helper function
                # referencing outer state declared later, so it advises rather than blocks — but a
                # DIRECT read before the declaration is a real TemporalDeadZone throw, so show them.
                warnings.append((name, "line %s: %s" % (m.group(1), m.group(4))))
    return problems, warnings


# The three live stages. The suspector, orchestrator and dedup generators were deleted when Svace
# markers replaced LLM detection: they were never re-run after the fsm rename, so they sat permanently
# STALE here and every validation run reported "3 problem(s)". A check that is always red is a check
# nobody reads, and it would have hidden a real staleness failure in the workflows that do ship.
GEN_FOR = {"workflow_ingest.json": "gen_ingest.py", "workflow_prover.json": "gen_prover.py",
           "workflow_setup.json": "gen_setup.py"}


def stale(path):
    """A generator that FAILED leaves the old JSON on disk, and validating that prints a false green.
    (Hit exactly that: gen_suspector.py died with a NameError and this script reported 0 problems.)"""
    gen = GEN_FOR.get(os.path.basename(path))
    if not gen:
        return None
    gpath = os.path.join(os.path.dirname(path), gen)
    if not os.path.exists(gpath) or not os.path.exists(path):
        return None
    if os.path.getmtime(gpath) > os.path.getmtime(path):
        return "STALE: %s is newer than this JSON — the generator did not run (or it failed). Re-run it." % gen
    return None


def main():
    paths = sys.argv[1:] or sorted(glob.glob("workflow_*.json"))
    paths = [os.path.abspath(p) for p in paths]
    bad = 0
    with tempfile.TemporaryDirectory() as wd:
        open(os.path.join(wd, "eslint.config.mjs"), "w").write(ESLINT_CONFIG)
        for p in paths:
            st = stale(p)
            if st:
                bad += 1
                print("\u2717 " + os.path.basename(p) + "\n    " + st)
                continue
            probs, warns = check(p, wd)
            if probs:
                bad += len(probs)
                print("\u2717 " + os.path.basename(p))
                for node, msg in probs:
                    print("    [%s] %s" % (node, msg))
            else:
                print("\u2713 " + os.path.basename(p))
            for node, msg in warns:
                print("    \u26a0 [%s] %s" % (node, msg))
    print("\n%d problem(s)" % bad)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
