#!/usr/bin/env python3
"""fjb java-runner: run a JUnit test red (bug present) -> apply fix -> green (bug gone).

POST /run_test
{
  "repo": "apache/opennlp", "branch": "main", "jdk": "17",
  "module": "opennlp-api",                 # optional: maven -pl module (-am)
  "build": "maven|gradle",                 # optional: auto-detected from the module/root
  "test_class": "LinkedSpanEqualsTest",    # simple class name for -Dtest
  "test_path": "opennlp-api/src/test/java/opennlp/tools/entitylinker/LinkedSpanEqualsTest.java",
  "test_code": "package ...; public class ... {}",
  "fix_files": [ { "path": "opennlp-api/.../LinkedSpan.java", "content": "<full fixed file>" } ]
}
-> { ok, red_reproduced, green_passed, red_summary, green_summary, red_output, green_output, error }

The workspace is a per-repo cached clone, reset to pristine on every job. Builds are serialized
(the prover calls this one suspicion at a time). Maven resolves through the Nexus mirror.
"""
import os, json, subprocess, hashlib, threading, traceback, time, glob as _glob
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CACHE = "/cache"
TOKEN = os.environ.get("GITHUB_TOKEN", "")
LOCK = threading.Lock()
os.makedirs(CACHE, exist_ok=True)


def _start_nav():
    """Launch the JavaParser code-navigation server (goto_definition / find_implementations) alongside."""
    try:
        subprocess.Popen(["/opt/jdk/21/bin/java", "-cp", "/opt/nav/classes:/opt/nav/lib/*", "NavServer"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print("nav server launched on :8099", flush=True)
    except Exception as e:
        print("nav start failed:", e, flush=True)


_start_nav()


def do_nav(op, body):
    """Proxy a nav query to NavServer, mapping repo -> the read-only fs cache dir it should resolve in."""
    base = prepare_fs(body["repo"], body.get("branch", "main"))
    if not base:
        return {"error": "clone failed"}
    # forward the disambiguators too — without arity/params/qualifier the nav server cannot separate
    # overloads, and answers "ambiguous" however precisely it was asked.
    payload = {"repoDir": base, "file": body.get("file", ""), "line": int(body.get("line") or 0),
               "symbol": body.get("symbol", ""), "type": body.get("type", ""), "method": body.get("method", ""),
               "qualifier": body.get("qualifier", ""), "params": body.get("params", "")}
    if body.get("arity") is not None:
        payload["arity"] = int(body["arity"])
    import urllib.request
    req = urllib.request.Request("http://127.0.0.1:8099/" + op, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=3600))   # match the caller's hour


def run(cmd, cwd=None, env=None, timeout=1200):
    try:
        p = subprocess.run(cmd, cwd=cwd, env=env, timeout=timeout,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        return p.returncode, p.stdout
    except subprocess.TimeoutExpired as e:
        return 124, (e.output or "") + "\n[TIMEOUT]"


def tail(s, n=6000):
    s = s or ""
    return s if len(s) <= n else "...(truncated)...\n" + s[-n:]


def summarize(out):
    """Parse the LAST JUnit 'Tests run: X, Failures: Y, Errors: Z' line + build state."""
    import re
    out = out or ""
    line, runs, fails, errs = None, 0, 0, 0
    for mm in re.finditer(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+)(?:, Skipped: \d+)?", out):
        line = mm.group(0); runs, fails, errs = int(mm.group(1)), int(mm.group(2)), int(mm.group(3))
    build = "BUILD SUCCESS" if "BUILD SUCCESS" in out else ("BUILD FAILURE" if "BUILD FAILURE" in out else "?")
    compile_err = "COMPILATION ERROR" in out or "compilation failure" in out.lower()
    return {"tests": line, "ran": runs, "failures": fails, "errors": errs,
            "test_executed": line is not None, "build": build, "compile_error": compile_err}


def summarize_xml(ws, since_ts):
    """Aggregate JUnit XML written by this run. Maven -> target/surefire-reports, Gradle ->
    build/test-results. mtime gates out stale results from an earlier build in the cached workspace."""
    import xml.etree.ElementTree as ET
    pats = ["**/target/surefire-reports/TEST-*.xml", "**/target/failsafe-reports/TEST-*.xml",
            "**/build/test-results/**/TEST-*.xml"]
    tests = fails = errs = skipped = files = 0
    for pat in pats:
        for fp in _glob.glob(os.path.join(ws, pat), recursive=True):
            try:
                if os.path.getmtime(fp) < since_ts - 2:
                    continue
                root = ET.parse(fp).getroot()
                suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
                for su in suites:
                    tests += int(su.get("tests") or 0)
                    fails += int(su.get("failures") or 0)
                    errs += int(su.get("errors") or 0)
                    skipped += int(su.get("skipped") or 0)
                files += 1
            except Exception:
                continue
    return {"tests": tests, "failures": fails, "errors": errs, "skipped": skipped, "files": files}


def outcome(out, ws, since_ts):
    """Console parse + XML truth. XML wins whenever this run produced any, so Gradle works too."""
    s = summarize(out)
    x = summarize_xml(ws, since_ts)
    if x["files"] > 0:
        s["ran"], s["failures"], s["errors"] = x["tests"], x["failures"], x["errors"]
        s["test_executed"] = x["tests"] > 0
        s["tests"] = "junit-xml: tests=%d failures=%d errors=%d" % (x["tests"], x["failures"], x["errors"])
        s["source"] = "junit-xml"
    else:
        s["source"] = "console"
    return s


def prepare_ws(repo, branch):
    key = hashlib.sha1(f"{repo}@{branch}".encode()).hexdigest()[:12]
    base = f"{CACHE}/{key}"
    auth = f"{TOKEN}@" if TOKEN else ""
    url = f"https://{auth}github.com/{repo}.git"
    if not os.path.isdir(os.path.join(base, ".git")):
        rc, out = run(["git", "clone", "--depth", "1", "--branch", branch, url, base], timeout=900)
        if rc != 0:
            return None, f"clone failed:\n{tail(out,1500)}"
    else:
        run(["git", "-C", base, "reset", "--hard"])
        run(["git", "-C", base, "clean", "-fd"])   # drop the previous test file / fix, keep target+.m2
    return base, ""


def build_cmd(ws, jdk, module, build, test_class):
    env = dict(os.environ)
    env["JAVA_HOME"] = f"/opt/jdk/{jdk}"
    env["PATH"] = f"/opt/jdk/{jdk}/bin:" + env.get("PATH", "")
    gradle_markers = ("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradlew")
    is_gradle = build == "gradle" or (
        build != "maven"
        and not os.path.exists(os.path.join(ws, "pom.xml"))
        and any(os.path.exists(os.path.join(ws, f)) for f in gradle_markers))
    if is_gradle:
        # no -x list: `test` does not depend on checkstyle/spotless/pmd (they hang off `check`), and an
        # unknown --exclude-task name aborts task-graph resolution outright. no -q either, so the
        # console fallback still has something to parse when no XML is produced.
        task = (":" + module.strip("/").replace("/", ":") + ":test") if module else "test"
        cmd = ["./gradlew", task, "--tests", "*" + test_class]
    else:
        # prove-phase: skip every style / license / static-analysis gate; just compile & run the test
        skips = ["-Dcheckstyle.skip=true", "-Dspotless.check.skip=true", "-Dspotless.apply.skip=true",
                 "-Dlicense.skip=true", "-Drat.skip=true", "-Dapache-rat.skip=true", "-Dpmd.skip=true",
                 "-Dcpd.skip=true", "-Dspotbugs.skip=true", "-Dfindbugs.skip=true", "-Denforcer.skip=true",
                 "-Danimal.sniffer.skip=true", "-Dmaven.javadoc.skip=true", "-Dgpg.skip=true",
                 "-Dforbiddenapis.skip=true", "-Dmaven.source.skip=true"]
        # no -q: with -q Maven hides the surefire "Tests run: .. Failures: 0" summary on SUCCESS
        cmd = ["mvn", "-B", "-DfailIfNoTests=false",
               "-Dsurefire.failIfNoSpecifiedTests=false", "-Dtest=" + test_class] + skips
        if module:
            cmd += ["-pl", module, "-am"]
        cmd += ["test"]
    return cmd, env


def do_run_test(body):
    repo = body["repo"]; branch = body.get("branch", "main"); jdk = str(body.get("jdk", "17"))
    module = body.get("module") or None
    build = body.get("build", "auto")
    test_class = body["test_class"]; test_path = body["test_path"]; test_code = body["test_code"]
    fix_files = body.get("fix_files", [])
    fix_edits = body.get("fix_edits", [])   # [{path, old_str, new_str}] — lightweight search/replace
    if jdk not in ("8", "11", "17", "21", "25"):
        return {"ok": False, "error": f"unsupported jdk {jdk}"}

    ws, err = prepare_ws(repo, branch)
    if not ws:
        return {"ok": False, "error": err}

    # 1) write the test
    tp = os.path.join(ws, test_path)
    os.makedirs(os.path.dirname(tp), exist_ok=True)
    with open(tp, "w") as f:
        f.write(test_code)

    def do_build(jdk_):
        cmd, env = build_cmd(ws, jdk_, module, build, test_class)
        rc, out = run(cmd, cwd=ws, env=env)
        # auto-detect the JDK the module enforces and retry once with it. The mismatch is announced
        # several ways: an enforcer message, OR javac rejecting a --release the running JDK is too old
        # for ("release version 25 not supported" — WebGoat needs 25), OR an invalid target/source.
        m = (re.search(r'Java (\d+) or higher is required', out)
             or re.search(r'release version (\d+) not supported', out)
             or re.search(r'invalid (?:target|source) release:?\s*(\d+)', out))
        if m and m.group(1) != jdk_ and m.group(1) in ("8", "11", "17", "21", "25"):
            jdk_ = m.group(1)
            cmd, env = build_cmd(ws, jdk_, module, build, test_class)
            rc, out = run(cmd, cwd=ws, env=env)
        return rc, out, jdk_
    import re  # noqa

    # 2) RED run (bug present) -> the test must actually RUN and FAIL (a build/style error is not a repro)
    red_started = time.time()
    red_rc, red_out, jdk = do_build(jdk)
    red_sum = outcome(red_out, ws, red_started)
    red_reproduced = red_sum["test_executed"] and (red_sum["failures"] + red_sum["errors"] > 0)

    # 3) apply the fix — either full-file overwrite (fix_files) or search/replace (fix_edits)
    applied = []
    edit_errors = []
    for ff in fix_files:
        p, ferr = _fix_target(ws, ff.get("path", ""), test_path)
        if ferr:
            edit_errors.append(f"{ff.get('path','')}: {ferr}"); continue
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write(ff["content"])
        applied.append(ff["path"])
    for ed in fix_edits:
        p, ferr = _fix_target(ws, ed.get("path", ""), test_path)
        if ferr:
            edit_errors.append(f"{ed['path']}: {ferr}"); continue
        if not os.path.exists(p):
            edit_errors.append(f"{ed['path']}: file not found"); continue
        with open(p) as f:
            cur = f.read()
        old, new = ed["old_str"], ed["new_str"]
        cnt = cur.count(old)
        if cnt == 0:
            edit_errors.append(f"{ed['path']}: old_str not found"); continue
        if cnt > 1:
            edit_errors.append(f"{ed['path']}: old_str matches {cnt}x (not unique)"); continue
        with open(p, "w") as f:
            f.write(cur.replace(old, new))
        applied.append(ed["path"])

    # 4) GREEN run (bug fixed) -> the test must RUN and PASS (reuse the JDK resolved above)
    green_started = time.time()
    green_rc, green_out, jdk = do_build(jdk)
    green_sum = outcome(green_out, ws, green_started)
    green_passed = (green_sum["test_executed"]
                    and (green_sum["failures"] + green_sum["errors"] == 0)
                    and not green_sum["compile_error"]
                    and (green_rc == 0 or green_sum.get("source") == "junit-xml"))

    return {
        "ok": True,
        "red_reproduced": red_reproduced,      # test failed before the fix (bug is real)
        "green_passed": green_passed,          # test passed after the fix (fix works)
        "proven": bool(red_reproduced and green_passed),
        "jdk": jdk,                            # the JDK actually used (auto-detected if the request was wrong)
        "applied_files": applied, "edit_errors": edit_errors,
        "red_summary": red_sum, "green_summary": green_sum,
        "red_output": tail(red_out), "green_output": tail(green_out),
    }


# ---- filesystem tools for the agents: read-only cached clone + atomic ops -----------------
FSCACHE = "/cache/fs"
FS_LOCK = threading.Lock()


_FS_KEYLOCKS = {}
_FS_FAILED = {}      # key -> ts of last failed clone (negative cache, 60s)


def _fs_keylock(key):
    """One lock per repo@branch: two different repos must not serialize behind each other."""
    with FS_LOCK:
        lk = _FS_KEYLOCKS.get(key)
        if lk is None:
            lk = _FS_KEYLOCKS[key] = threading.Lock()
        return lk


def prepare_fs(repo, branch):
    """Read-only shallow clone of the repo, cached and never mutated (separate from build workspaces).

    Concurrency: 5+ agents call this at once. The old fast path tested for `.git`, which exists
    part-way through a clone, so a second caller could read a half-populated tree and be told the code
    does not exist. Clone into a temp dir and rename it into place; the fast path gates on a marker
    written only after that rename, so readers see either nothing or a complete checkout.
    """
    import shutil
    key = hashlib.sha1(f"{repo}@{branch}".encode()).hexdigest()[:12]
    base = f"{FSCACHE}/{key}"
    done = os.path.join(base, ".fjb_clone_complete")
    if os.path.exists(done):
        return base
    with _fs_keylock(key):
        if os.path.exists(done):
            return base
        os.makedirs(FSCACHE, exist_ok=True)
        # Legacy/warm cache: a checkout git can resolve HEAD in is complete by definition, so adopt it
        # and just write the marker. Without this, deploying the marker gate re-clones every repo.
        if os.path.isdir(os.path.join(base, ".git")) and run(["git", "-C", base, "rev-parse", "HEAD"], timeout=60)[0] == 0:
            with open(done, "w") as f:
                f.write(branch)
            return base
        if _FS_FAILED.get(key, 0) > time.time() - 60:
            return None          # negative cache: N queued threads must not each pay a failed clone
        tmp = base + ".tmp"
        shutil.rmtree(tmp, ignore_errors=True)
        auth = f"{TOKEN}@" if TOKEN else ""
        rc, out = run(["git", "clone", "--depth", "1", "--branch", branch,
                       f"https://{auth}github.com/{repo}.git", tmp], timeout=600)
        if rc != 0:
            shutil.rmtree(tmp, ignore_errors=True)
            _FS_FAILED[key] = time.time()
            return None
        # Swap in by RENAME, never by deleting first: other agents are reading this tree right now, and
        # rmtree-then-clone would leave it missing for the whole clone (minutes), not microseconds.
        old = None
        if os.path.isdir(base):
            old = base + ".old.%d" % int(time.time())
            try:
                os.rename(base, old)
            except OSError:
                old = None
                shutil.rmtree(base, ignore_errors=True)
        try:
            os.replace(tmp, base)                    # atomic: readers never observe a partial tree
        except OSError:
            if old and not os.path.isdir(base):      # put the previous tree back rather than leave none
                try:
                    os.rename(old, base)
                    old = None
                except OSError:
                    pass
            shutil.rmtree(tmp, ignore_errors=True)
            _FS_FAILED[key] = time.time()
            return base if os.path.isdir(os.path.join(base, ".git")) else None
        if old:
            shutil.rmtree(old, ignore_errors=True)
        with open(done, "w") as f:
            f.write(branch)
        return base


def _safe(base, path):
    p = os.path.normpath(os.path.join(base, (path or "").lstrip("/")))
    return p if p.startswith(base) else None


def _fix_target(ws, path, test_path):
    """Resolve a fix path inside ws; refuse workspace escapes AND any edit under a test tree or the
    proof test itself. Server-side invariant so the fixer/test independence never relies on the caller."""
    p = _safe(ws, path or "")
    if not p:
        return None, "path escapes workspace"
    rel = os.path.relpath(p, ws).replace("\\", "/")
    low = rel.lower()
    tp = (test_path or "").replace("\\", "/")
    if "/src/test/" in low or low.startswith("src/test/") or (tp and rel == tp):
        return None, "refuses to edit a test file (fixer may not touch the test)"
    return p, None


def fs_read_file(b):
    base = prepare_fs(b["repo"], b.get("branch", "main"))
    if not base:
        return {"error": "clone failed"}
    p = _safe(base, b.get("path", ""))
    if not p or not os.path.isfile(p):
        return {"error": "file not found: " + b.get("path", "")}
    with open(p, errors="replace") as f:
        content = f.read()
    return {"path": b.get("path"), "content": content[:80000],
            "truncated": len(content) > 80000}


FJB_INTERNAL = (".git", ".fjb_clone_complete")


def fs_list_directory(b):
    base = prepare_fs(b["repo"], b.get("branch", "main"))
    if not base:
        return {"error": "clone failed"}
    p = _safe(base, b.get("path", ""))
    if not p or not os.path.isdir(p):
        return {"error": "dir not found: " + b.get("path", "")}
    entries = []
    for e in sorted(os.listdir(p)):
        if e in FJB_INTERNAL:      # our own cache marker is not part of the upstream checkout
            continue
        entries.append({"name": e, "type": "dir" if os.path.isdir(os.path.join(p, e)) else "file"})
    return {"path": b.get("path", ""), "entries": entries}


def fs_search_files(b):
    base = prepare_fs(b["repo"], b.get("branch", "main"))
    if not base:
        return {"error": "clone failed"}
    pat = (b.get("glob") or b.get("pattern") or "").lstrip("/")
    if not pat:
        return {"error": "glob required"}
    ms = _glob.glob(os.path.join(base, pat), recursive=True)
    files = sorted(os.path.relpath(m, base) for m in ms if os.path.isfile(m))
    return {"glob": pat, "files": files[:300], "count": len(files)}


def fs_grep_search(b):
    base = prepare_fs(b["repo"], b.get("branch", "main"))
    if not base:
        return {"error": "clone failed"}
    pat = b.get("pattern") or b.get("regex")
    if not pat:
        return {"error": "pattern required"}
    # context lines matter a lot here: a bare matching line shows a signature but hides the BODY, and
    # the model then re-greps variations of the same pattern trying to see more. Default to a few
    # lines of trailing context so one grep answers "what does this method actually do".
    try:
        ctx = max(0, min(60, int(b.get("context", 6))))
    except Exception:
        ctx = 6
    cmd = ["grep", "-rnI", "-E", "--exclude-dir=.git"]
    if ctx:
        cmd += ["-A", str(ctx), "-B", "1"]
    cmd.append(pat)
    inc = (b.get("path_glob") or b.get("include") or "").strip()
    target = "."
    if inc:
        p = _safe(base, inc)
        if p and os.path.exists(p):
            target = os.path.relpath(p, base)          # a real file/dir path -> search it directly
        else:
            cmd += ["--include", inc.split("/")[-1]]    # a glob (e.g. **/Span.java) -> filter by basename
    cmd.append(target)
    rc, out = run(cmd, cwd=base, timeout=60)
    lines = [ln[2:] if ln.startswith("./") else ln for ln in out.splitlines()][:400]
    # cap by CHARACTERS too, not just line count: with context lines a broad pattern can return ~80k
    # chars, and several of those in one agent context push the model past its window (the failure
    # then surfaces only as an opaque request error). Truncate loudly instead.
    total, kept = 0, []
    for ln in lines:
        if total + len(ln) > 24000:
            kept.append("... (truncated: narrow the pattern or lower `context`)")
            break
        kept.append(ln)
        total += len(ln)
    return {"pattern": pat, "context": ctx, "matches": kept,
            "count": len(kept), "truncated": len(kept) < len(lines)}


FS_OPS = {"read_file": fs_read_file, "list_directory": fs_list_directory,
          "search_files": fs_search_files, "grep_search": fs_grep_search}


# ---- live sink: the suspector streams each in-flight method's ReAct transcript here so the ------
# ---- dashboard shows analysis AS IT HAPPENS, and forensics survive an n8n crash (separate ------
# ---- container + /cache/live disk backing). Keyed by method_key = repo|file|method. -------------
LIVE = {}                       # method_key -> entry
# ---- single-instance lease -------------------------------------------------------------------
# One holder per name, TTL-expiring so a crashed holder self-recovers. The runner is a single process,
# so this in-memory dict is the authoritative lock — no compare-and-swap race, unlike a Data Table row.
# The scheduled prover acquires 'prover' before it works a suspicion; overlapping fires get acquired=false
# and exit, so at most one prove drain runs at a time (the build LOCK already serializes the workspace,
# but this stops n8n executions from piling up and starving the suspector).
LEASE_LOCK = threading.Lock()
_LEASES = {}                    # name -> expiry epoch

def lease_acquire(body):
    name = str(body.get("name") or "default")
    ttl = float(body.get("ttl_s") or 3600)
    now = time.time()
    with LEASE_LOCK:
        exp = _LEASES.get(name, 0)
        if exp > now:
            return {"acquired": False, "expires_in": round(exp - now, 1)}
        _LEASES[name] = now + ttl
        return {"acquired": True, "ttl_s": ttl}

def lease_release(body):
    with LEASE_LOCK:
        _LEASES.pop(str(body.get("name") or "default"), None)
    return {"ok": True}


LIVE_LOCK = threading.Lock()    # NOT the build LOCK — a /live post must never block behind a build
LIVE_MAX = 60                   # cap: all 'analyzing' + most-recent finished, up to this many
LIVE_DIR = "/cache/live"
os.makedirs(LIVE_DIR, exist_ok=True)
_LIVE_SEQ = [0]


def _live_path(key):
    return os.path.join(LIVE_DIR, hashlib.sha1(key.encode()).hexdigest()[:16] + ".json")


def _live_prune_locked():
    finished = sorted((e for e in LIVE.values() if e["status"] != "analyzing"), key=lambda e: e["seq"])
    n_analyzing = sum(1 for e in LIVE.values() if e["status"] == "analyzing")
    while n_analyzing + len(finished) > LIVE_MAX and finished:
        old = finished.pop(0)
        LIVE.pop(old["method_key"], None)
        try:
            os.remove(_live_path(old["method_key"]))
        except Exception:
            pass


def live_put(body):
    key = (body.get("method_key") or "").strip()
    if not key:
        return {"ok": False, "error": "no method_key"}
    with LIVE_LOCK:
        _LIVE_SEQ[0] += 1
        LIVE[key] = {
            "method_key": key,
            "repo": body.get("repo") or "", "file": body.get("file") or "",
            "class_name": body.get("class_name") or "", "method": body.get("method") or "",
            "dialog": (body.get("dialog") or "")[-200000:],
            "status": body.get("status") or "analyzing",
            "tool_calls": body.get("tool_calls") or 0, "findings": body.get("findings") or 0,
            # the findings themselves: a method is done long before its batch persists, and without
            # these the dashboard can show a count but never the actual rows
            "candidates": (body.get("candidates") or [])[:50],
            "seq": _LIVE_SEQ[0], "ts": time.time(),
        }
        _live_prune_locked()
        try:
            with open(_live_path(key), "w") as f:
                json.dump(LIVE[key], f)
        except Exception:
            pass
    return {"ok": True}


def live_list():
    out = []
    with LIVE_LOCK:
        for e in LIVE.values():
            d = e.get("dialog") or ""
            o = {k: v for k, v in e.items() if k != "dialog"}
            o["chars"] = len(d)
            o["tail"] = d[-800:]
            o["candidates"] = e.get("candidates") or []   # findings visible before the batch persists
            out.append(o)
    out.sort(key=lambda e: (0 if e["status"] == "analyzing" else 1, -e["seq"]))
    return out


def live_clear():
    """Drop every live transcript (memory + disk) — called by the orchestrator at the start of a scan
    so a re-run never shows dialogs from a previous run."""
    n = 0
    with LIVE_LOCK:
        n = len(LIVE)
        LIVE.clear()
        try:
            for fn in os.listdir(LIVE_DIR):
                if fn.endswith(".json"):
                    try:
                        os.remove(os.path.join(LIVE_DIR, fn))
                    except Exception:
                        pass
        except Exception:
            pass
    return {"ok": True, "cleared": n}


def live_get(key):
    with LIVE_LOCK:
        e = LIVE.get(key)
        return dict(e) if e else {"dialog": "(no live transcript)", "status": "gone"}


def _live_load():
    """Reload recent transcripts after a runner restart; anything still 'analyzing' is stale."""
    try:
        names = os.listdir(LIVE_DIR)
    except Exception:
        return
    for fn in names:
        if not fn.endswith(".json"):
            continue
        try:
            with open(os.path.join(LIVE_DIR, fn)) as f:
                e = json.load(f)
            if e.get("status") == "analyzing":
                e["status"] = "stale"
            LIVE[e["method_key"]] = e
            _LIVE_SEQ[0] = max(_LIVE_SEQ[0], e.get("seq", 0))
        except Exception:
            pass
    with LIVE_LOCK:
        _live_prune_locked()


_live_load()


class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/health":
            jdks = [d for d in ("8", "11", "17", "21", "25") if os.path.isdir(f"/opt/jdk/{d}")]
            return self._send(200, {"ok": True, "jdks": jdks})
        if self.path.split("?", 1)[0] == "/live/dialogs":
            return self._send(200, {"dialogs": live_list()})
        if self.path.split("?", 1)[0] == "/live/dialog":
            from urllib.parse import urlparse, parse_qs
            key = (parse_qs(urlparse(self.path).query).get("key") or [""])[0]
            return self._send(200, live_get(key))
        self._send(404, {"error": "not found"})

    def do_POST(self):
        op = self.path.strip("/").split("/")[-1]        # /run_test or /fs/<op>
        n = int(self.headers.get("Content-Length", 0))
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception as e:
            return self._send(400, {"ok": False, "error": f"bad json: {e}"})
        if self.path == "/lease":                        # single-instance guard (no build LOCK — instant)
            return self._send(200, lease_acquire(body))
        if self.path == "/lease/release":
            return self._send(200, lease_release(body))
        if self.path == "/run_test":
            with LOCK:
                try:
                    res = do_run_test(body)
                except Exception:
                    res = {"ok": False, "error": traceback.format_exc()[-1500:]}
            return self._send(200, res)
        if op in FS_OPS:                                 # filesystem tools (read-only, no build lock)
            try:
                return self._send(200, FS_OPS[op](body))
            except Exception:
                return self._send(200, {"error": traceback.format_exc()[-800:]})
        if self.path == "/live/dialog":                  # suspector streams in-flight ReAct transcripts
            try:
                return self._send(200, live_put(body))   # no build LOCK — must never block behind a build
            except Exception:
                return self._send(200, {"ok": False, "error": traceback.format_exc()[-500:]})
        if self.path == "/live/clear":                   # orchestrator wipes stale transcripts on re-run
            try:
                return self._send(200, live_clear())
            except Exception:
                return self._send(200, {"ok": False, "error": traceback.format_exc()[-500:]})
        if self.path in ("/nav/definition", "/nav/implementations"):   # semantic go-to-definition / impls
            try:
                return self._send(200, do_nav(self.path.split("/")[-1], body))
            except Exception:
                return self._send(200, {"error": traceback.format_exc()[-500:]})
        self._send(404, {"error": "not found"})

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8090"))
    print(f"java-runner listening on :{port}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()
