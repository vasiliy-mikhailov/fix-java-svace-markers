#!/usr/bin/env python3
"""
Fresh, simple per-repo Java bug finder.

Reuses the find -> prove -> fix *logic* of the original pipeline, but as a lean,
stdlib-only script (no OpenHands). One container == one repo.

  REPO forms:
    owner/repo    -> shallow-cloned from GitHub
    synth:<name>  -> local synthetic repo at $WORK/data/repos/<name>

  Per Java source file: FIND a suspected bug, then PROVE (a failing JUnit test)
  and FIX (the one-line change) for the strongest findings. Everything is written
  to $WORK/results_n8n/<owner>__<repo>.json for downstream (human) verification.

  Env:
    QWEN_BASE_URL   OpenAI-compatible base, e.g. http://vllm-proxy:8000/v1
    QWEN_API_KEY    bearer token
    QWEN_MODEL      served model name
    MAX_FILES       cap on files scanned per repo (default 40)
    PROVE_TOP       how many top findings to prove+fix (default 8)
    WORK            mounted work dir (default /work)
"""
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.request

BASE = os.environ["QWEN_BASE_URL"].rstrip("/")
KEY = os.environ["QWEN_API_KEY"]
MODEL = os.environ.get("QWEN_MODEL", "qwen")
WORK = os.environ.get("WORK", "/work")
MAX_FILES = int(os.environ.get("MAX_FILES", "40"))
PROVE_TOP = int(os.environ.get("PROVE_TOP", "8"))
SKIP_DIRS = {".git", "target", "build", "out", "bin", "node_modules", ".idea", ".gradle", "test", "tests"}


def log(*a):
    print(f"[find_bugs {time.strftime('%H:%M:%S')}]", *a, flush=True)


MAX_TOKENS = int(os.environ.get("MAX_TOKENS", "8000"))       # total budget (thinking + answer)
THINK_BUDGET = int(os.environ.get("THINK_BUDGET", "1024"))   # cap thinking so the model still emits an answer


def llm(prompt, retries=1_000_000):
    """One chat completion. Retry forever on transient errors (stoic), fast-fail on 4xx.
    Qwen is a reasoning model: bound its thinking with thinking_budget so it stops
    thinking and emits the final answer in `content` (fall back to reasoning_content)."""
    body = json.dumps({
        "model": MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.2,
        "max_tokens": MAX_TOKENS,
        # extra_body: enable_thinking + thinking_budget bound the reasoning
        "enable_thinking": True,
        "thinking_budget": THINK_BUDGET,
    }).encode()
    attempt = 0
    while True:
        attempt += 1
        try:
            req = urllib.request.Request(
                f"{BASE}/chat/completions", body,
                {"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=900) as r:
                msg = json.load(r)["choices"][0]["message"]
                return msg.get("content") or msg.get("reasoning_content") or ""
        except urllib.error.HTTPError as e:
            if e.code in (400, 401, 403, 404, 405, 422):
                raise
            if attempt > retries:
                raise
        except Exception:  # noqa: BLE001 - transient (conn/timeout/5xx): retry
            if attempt > retries:
                raise
        time.sleep(min(20, 2 ** min(attempt, 5)))


def extract_json(text):
    """Pull the first JSON object out of a model reply."""
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if not m:
        return None
    try:
        return json.loads(m.group(0))
    except Exception:  # noqa: BLE001
        return None


def resolve_repo(repo):
    if repo.startswith("synth:"):
        path = os.path.join(WORK, "data", "repos", repo[len("synth:"):])
        if not os.path.isdir(path):
            raise SystemExit(f"synthetic repo not found: {path}")
        return path, repo[len("synth:"):]
    dest = tempfile.mkdtemp(prefix="repo-")
    log(f"cloning {repo}")
    subprocess.run(["git", "clone", "--depth", "1", f"https://github.com/{repo}.git", dest],
                   check=True, capture_output=True)
    return dest, repo.replace("/", "__")


def source_files(root):
    out = []
    for path in glob.glob(f"{root}/**/*.java", recursive=True):
        norm = path.replace("\\", "/")
        if any(f"/{d}/" in norm for d in SKIP_DIRS):
            continue
        if "/src/main/java/" not in norm and "/src/" in norm:
            continue
        out.append(path)
    out.sort()
    return out


FIND_PROMPT = """You are a careful Java bug finder. Analyze this ONE file for a genuine, user-reachable bug: a correctness error, data corruption, resource/credential leak, or API-contract violation. Ignore style, naming, and hypothetical misuse.

Reply with ONLY a JSON object:
{{"has_bug": true|false, "line": <int>, "severity": "high|medium|low", "category": "correctness|data-corruption|resource-leak|contract-violation|npe", "bug": "<one sentence>", "why": "<how it fails, concrete input->wrong output>"}}
If there is no real bug, reply {{"has_bug": false}}.

FILE: {path}
```java
{src}
```"""

PROVE_PROMPT = """A suspected bug was found in {path}:
{bug}
why: {why}

Do two things and reply with ONLY JSON:
{{"test": "<a self-contained JUnit5 test method that FAILS on the current code and PASSES once fixed; include the exact assertion>", "fix": "<the minimal code change, as a unified-diff-style before/after or a one-sentence precise description with the corrected line>"}}"""


def find_in_file(path, rel):
    src = open(path, encoding="utf-8", errors="replace").read()
    if len(src) > 12000:
        src = src[:12000]
    out = llm(FIND_PROMPT.format(path=rel, src=src))
    j = extract_json(out)
    if not j or not j.get("has_bug"):
        return None
    j["file"] = rel
    return j


def prove_and_fix(f):
    out = llm(PROVE_PROMPT.format(path=f["file"], bug=f.get("bug", ""), why=f.get("why", "")))
    j = extract_json(out) or {}
    f["proposed_test"] = j.get("test", "")
    f["proposed_fix"] = j.get("fix", "")
    return f


def main():
    if len(sys.argv) < 2:
        raise SystemExit("usage: find_bugs.py <owner/repo | synth:name>")
    repo = sys.argv[1]
    t0 = time.time()
    root, tag = resolve_repo(repo)
    files = source_files(root)
    log(f"{repo}: {len(files)} source files (scanning up to {MAX_FILES})")

    findings = []
    for i, path in enumerate(files[:MAX_FILES], 1):
        rel = os.path.relpath(path, root)
        try:
            f = find_in_file(path, rel)
        except Exception as e:  # noqa: BLE001
            log(f"  [{i}] {rel}: error {e}")
            continue
        if f:
            log(f"  [{i}] {rel}: {f.get('severity')} {f.get('category')} - {f.get('bug','')[:70]}")
            findings.append(f)

    # PROVE + FIX the strongest findings
    order = {"high": 0, "medium": 1, "low": 2}
    findings.sort(key=lambda x: order.get(x.get("severity"), 3))
    for f in findings[:PROVE_TOP]:
        try:
            prove_and_fix(f)
        except Exception as e:  # noqa: BLE001
            log(f"  prove/fix error: {e}")

    os.makedirs(os.path.join(WORK, "results_n8n"), exist_ok=True)
    out_path = os.path.join(WORK, "results_n8n", f"{tag}.json")
    json.dump({
        "repo": repo,
        "scanned_files": min(len(files), MAX_FILES),
        "total_files": len(files),
        "findings": findings,
        "elapsed_s": round(time.time() - t0, 1),
    }, open(out_path, "w"), ensure_ascii=False, indent=2)
    log(f"{repo}: {len(findings)} findings -> {out_path} ({round(time.time()-t0)}s)")


if __name__ == "__main__":
    main()
