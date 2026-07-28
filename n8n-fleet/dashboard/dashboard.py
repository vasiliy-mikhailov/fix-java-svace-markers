#!/usr/bin/env python3
"""fsm live dashboard: reads the n8n sqlite DB (read-only) + Data Tables and serves a
live view of the agentic pipeline — scan progress (per-file, elapsed, ETA), suspicions,
and (once the Prover exists) proven bugs."""
import os, json, re, sqlite3, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_TREE_CACHE = {}   # repo -> list of src/main/java .java paths (fetched once from GitHub)


def gh_repo_files(repo):
    """Live repo-wide src/main/java file list from GitHub (cached) — n8n only flushes the
    orchestrator's tree data at completion, so a running multi-day scan can't show it otherwise."""
    if not repo:
        return []
    if repo in _TREE_CACHE:
        return _TREE_CACHE[repo]
    tok = os.environ.get("GITHUB_TOKEN", "")
    hdr = {"User-Agent": "fsm", "Accept": "application/vnd.github+json"}
    if tok:
        hdr["Authorization"] = "Bearer " + tok
    try:
        def gj(url):
            return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=hdr), timeout=15))
        br = gj(f"https://api.github.com/repos/{repo}").get("default_branch", "main")
        tree = gj(f"https://api.github.com/repos/{repo}/git/trees/{br}?recursive=1")
        files = [t["path"] for t in tree.get("tree", [])
                 if t.get("type") == "blob" and t["path"].endswith(".java")
                 and ("/src/main/java/" in t["path"] or t["path"].startswith("src/main/java/"))]
        _TREE_CACHE[repo] = files
        return files
    except Exception:
        return []

DB = os.environ.get("N8N_DB", "/n8n-data/database.sqlite")
SUS_WF = "fsmsuspector0001"
ORCH_WF = "fsmorchestr0001"
PROVER_WF = "fsmprover00001"
FILE_RE = re.compile(r'[A-Za-z0-9_.-]+/src/main/java/[A-Za-z0-9_./$-]+\.java')


def conn():
    return sqlite3.connect(f"file:{DB}?mode=ro", uri=True, timeout=5)


def table_id(c, name):
    r = c.execute("select id from data_table where name=?", (name,)).fetchone()
    return r[0] if r else None


def read_table(c, name):
    tid = table_id(c, name)
    if not tid:
        return []
    phys = f"data_table_user_{tid}"
    cols = [d[1] for d in c.execute(f"PRAGMA table_info({phys})")]
    return [dict(zip(cols, r)) for r in c.execute(f"select * from {phys} order by id desc")]


def query_table(c, name, col, val):
    tid = table_id(c, name)
    if not tid:
        return []
    phys = f"data_table_user_{tid}"
    cols = [d[1] for d in c.execute(f"PRAGMA table_info({phys})")]
    return [dict(zip(cols, r)) for r in c.execute(f"select * from {phys} where {col}=?", (val,))]


def exec_file(c, eid):
    try:
        raw = c.execute("select data from execution_data where executionId=?", (eid,)).fetchone()[0]
        m = FILE_RE.search(raw)
        return m.group(0) if m else None
    except Exception:
        return None


def secs(c, a, b):
    """seconds between two DB timestamps (both UTC), via sqlite julianday to dodge TZ skew."""
    if not a or not b:
        return None
    r = c.execute("select (julianday(?)-julianday(?))*86400", (b, a)).fetchone()[0]
    return round(r) if r is not None else None


def scan_conf(c, oid):
    """repo-wide file count, the pathFilter, and how many files it matches — so the dashboard
    can show repo-total vs matched vs cap vs done instead of a misleading single 'total'."""
    out = {"cap": None, "pathFilter": "", "repo_files": 0, "matched": 0}
    try:
        raw = c.execute("select data from execution_data where executionId=?", (oid,)).fetchone()[0]
        m = re.search(r'"maxFiles":\s*"?(\d+)"?', raw)
        out["cap"] = int(m.group(1)) if m else None
        pf = re.search(r'"pathFilter":\s*"([^"]*)"', raw)
        pfval = pf.group(1) if pf else ""
        if pfval.isdigit():          # n8n flattened it to a reference index — dereference
            try:
                arr = json.loads(raw)
                pfval = arr[int(pfval)] if int(pfval) < len(arr) and isinstance(arr[int(pfval)], str) else ""
            except Exception:
                pfval = ""
        out["pathFilter"] = "" if pfval.isdigit() else pfval
        files = set(re.findall(r'[A-Za-z0-9_.-]+/src/main/java/[A-Za-z0-9_./$-]+\.java', raw))
        out["repo_files"] = len(files)
        out["matched"] = len([f for f in files if (not out["pathFilter"] or out["pathFilter"] in f)])
    except Exception:
        pass
    return out


def exec_repo(c, eid):
    """owner/name from a suspector exec's file-fetch URL (api.github.com/repos/<owner/name>/contents)."""
    try:
        raw = c.execute("select data from execution_data where executionId=?", (eid,)).fetchone()[0]
        m = re.search(r'repos/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/contents', raw)
        return m.group(1) if m else None
    except Exception:
        return None


def state():
    c = conn()
    try:
        now = c.execute("select datetime('now')").fetchone()[0]
        orch = c.execute(
            "select id,status,startedAt,stoppedAt from execution_entity where workflowId=? order by startedAt desc limit 1",
            (ORCH_WF,)).fetchone()
        scan = {"status": "idle", "repo": None, "startedAt": None, "elapsed": None, "eta": None,
                "done": 0, "running": 0, "queued": 0, "total": 0, "planning": False,
                "total_methods": 0, "methods_done": 0, "current_file": None, "current_elapsed": None, "pathFilter": ""}
        worklist = read_table(c, "scan_files")     # the pre-enumerated plan (file + method count + status)
        if worklist:                               # keep only the latest scan's rows (rows accrue across scans)
            maxrun = max((w.get("run_id") or "") for w in worklist)
            worklist = [w for w in worklist if (w.get("run_id") or "") == maxrun]
        files = []
        if orch:
            oid, ost, ostart, ostop = orch
            scan["status"] = ost
            scan["startedAt"] = ostart
            scan["elapsed"] = secs(c, ostart, ostop if ost != "running" else now)
            # suspector executions since scan start -> file -> {status, dur}; find the file scanning now
            exec_by_file, running_file = {}, None
            for eid, st, s0, s1 in c.execute(
                "select id,status,startedAt,stoppedAt from execution_entity where workflowId=? and startedAt>=? order by startedAt",
                    (SUS_WF, ostart)):
                fp = exec_file(c, eid)
                if fp:
                    exec_by_file[fp] = secs(c, s0, s1 if st != "running" else now)
                    if st == "running":
                        running_file = fp
                        scan["current_file"] = fp.split("/")[-1]
                        scan["current_elapsed"] = secs(c, s0, now)
            # suspicion counts per file basename
            sus_ct = {}
            stid = table_id(c, "suspicions")
            if stid:
                for (fp,) in c.execute(f"select file from data_table_user_{stid}"):
                    k = fp or ""            # full path: basenames collide across packages
                    sus_ct[k] = sus_ct.get(k, 0) + 1
            if worklist:
                scan["repo"] = worklist[0].get("repo")
            done_durs, methods_done, total_methods = [], 0, 0
            for w in worklist:
                path = (w.get("file") or ""); nm = path.split("/")[-1]
                meth = int(w.get("methods") or 0); total_methods += meth
                st = w.get("status") or "queued"
                if st != "done" and path == running_file:
                    st = "running"
                d = exec_by_file.get(path)
                if w.get("status") == "done":
                    methods_done += meth
                    if d:
                        done_durs.append(d)
                files.append({"file": nm, "path": path, "status": st, "dur": d,
                              "methods": meth, "suspicions": sus_ct.get(path, 0)})
            scan["total"] = len(worklist)
            scan["done"] = sum(1 for w in worklist if (w.get("status") == "done"))
            scan["running"] = 1 if running_file else 0
            scan["queued"] = max(0, scan["total"] - scan["done"] - scan["running"])
            scan["total_methods"] = total_methods
            scan["methods_done"] = methods_done
            scan["planning"] = (ost == "running" and not worklist)   # phase 1: worklist not written yet
            if ost == "running" and done_durs and methods_done:
                avg = sum(done_durs) / methods_done
                scan["eta"] = round(avg * (total_methods - methods_done))
            files.sort(key=lambda x: (0 if x["status"] == "running" else 1 if x["status"] == "done" else 2))
            if not scan["repo"]:
                for eid in [e for e in c.execute(
                    "select id from execution_entity where workflowId=? and startedAt>=? order by id desc limit 3",
                        (SUS_WF, ostart))]:
                    r = exec_repo(c, eid[0])
                    if r:
                        scan["repo"] = r
                        break

        suspicions = read_table(c, "suspicions")
        if orch and not scan["repo"] and suspicions:
            scan["repo"] = suspicions[0].get("repo")
        bugs = read_table(c, "bugs")
        # Carry the WHOLE marker onto its artifact. A verdict is only reviewable next to the marker it
        # answers — Severity, Checker, File and Line are the four columns of the Svace report, and
        # without them the verdicts table says "unprovable" about a file with no indication of what was
        # claimed, how bad Svace thought it was, or where. `bugs` only stores svace_checker, so the rest
        # is joined from `suspicions` here rather than duplicated into a second table that could drift.
        by_key = {s.get("dedup_key"): s for s in suspicions if s.get("dedup_key")}
        marker_cols = ("svace_severity", "svace_checker", "svace_line", "marker_id", "category",
                       "severity", "line", "class_name", "method", "anchor", "anchor_status",
                       "description", "evidence", "status", "prove_attempts", "note")
        for b in bugs:
            m = by_key.get(b.get("suspicion_key")) or {}
            for col in marker_cols:
                # never let the join clobber a value the artifact itself owns (e.g. bugs.svace_checker)
                if not str(b.get(col) or "").strip():
                    b[col] = m.get(col)
            b["marker_orphaned"] = not m      # the suspicion was re-ingested out from under this row
        activity = []
        run_since = orch[2] if orch else None   # scope activity to the CURRENT orchestrator run (not old runs)
        aq = ("select id,workflowId,status,startedAt,stoppedAt from execution_entity where workflowId in (?,?,?)"
              + (" and startedAt>=?" if run_since else "") + " order by startedAt desc limit 20")
        for eid, wf, st, s0, s1 in c.execute(aq, (ORCH_WF, SUS_WF, PROVER_WF) + ((run_since,) if run_since else ())):
            name = {ORCH_WF: "orchestrator", SUS_WF: "suspector", PROVER_WF: "prover"}.get(wf, wf)
            activity.append({"wf": name, "status": st, "started": s0,
                             "file": (exec_file(c, eid) or "").split("/")[-1] if wf == SUS_WF else "",
                             "dur": secs(c, s0, s1)})
        return {"scan": scan, "files": files, "suspicions": suspicions, "bugs": bugs, "activity": activity,
                "prover_built": bool(table_id(c, "bugs")) and _wf_exists(c, PROVER_WF)}
    finally:
        c.close()


def _wf_exists(c, wid):
    return c.execute("select 1 from workflow_entity where id=?", (wid,)).fetchone() is not None


WF_NAMES = {ORCH_WF: "orchestrator", SUS_WF: "suspector", PROVER_WF: "prover"}


def _deref(arr, v):
    """n8n serializes run-data as a flat array where a decimal-digit string indexes into it."""
    if isinstance(v, str) and v.isdigit():
        i = int(v)
        if 0 <= i < len(arr):
            return arr[i]
    return v


def parse_exec_error(data_str):
    """(failed node, message) from an execution_data.data blob; both optional (interrupt-crashes have neither)."""
    node = msg = None
    try:
        arr = json.loads(data_str)
        rd = _deref(arr, arr[0].get("resultData"))
        if isinstance(rd, dict):
            node = _deref(arr, rd.get("lastNodeExecuted"))
            if isinstance(node, dict):
                node = _deref(arr, node.get("name"))
            err = _deref(arr, rd.get("error"))
            if isinstance(err, dict):
                name = _deref(arr, err.get("name"))
                http = _deref(arr, err.get("httpCode"))
                m = _deref(arr, err.get("message"))
                parts = [p for p in (name, ("HTTP %s" % http if http else None), m) if p]
                msg = " ".join(str(p) for p in parts).strip() or None
    except Exception:
        pass
    if not msg:                       # raw-string fallback for odd shapes
        mm = re.search(r'"message"\s*:\s*"((?:[^"\\]|\\.)*)"', data_str or "")
        if mm:
            try:
                msg = json.loads('"%s"' % mm.group(1))
            except Exception:
                pass
    return (node if isinstance(node, str) else None), msg


def errors(limit=25):
    """Recent pipeline failures (orchestrator+suspector+prover) from the n8n execution tables."""
    c = conn()
    try:
        out = []
        orow = c.execute("select startedAt from execution_entity where workflowId=? order by startedAt desc limit 1",
                         (ORCH_WF,)).fetchone()
        since = orow[0] if orow else "0000"     # only the CURRENT run's failures, not stale ones from old runs
        q = ("select e.id,e.workflowId,e.status,e.startedAt,ed.data "
             "from execution_entity e join execution_data ed on ed.executionId=e.id "
             "where e.workflowId in (?,?,?) and e.status in ('error','crashed','canceled') and e.startedAt>=? "
             "order by e.startedAt desc limit ?")
        for eid, wfid, status, started, data in c.execute(q, (ORCH_WF, SUS_WF, PROVER_WF, since, limit)):
            node, msg = parse_exec_error(data)
            if not msg:
                msg = "crashed / interrupted (no error recorded)" if status == "crashed" else status
            fp = exec_file(c, eid) if wfid == SUS_WF else None
            out.append({"id": eid, "wf": WF_NAMES.get(wfid, wfid), "status": status,
                        "started": started, "node": node or "",
                        "file": (fp or "").split("/")[-1], "message": msg or ""})
        return out
    finally:
        c.close()


PAGE = r"""<!doctype html><html><head><meta charset=utf-8><title>fix-java-svace-markers · live</title>
<meta name=viewport content="width=device-width,initial-scale=1">
<style>
:root{--bg:#0d1117;--panel:#161b22;--line:#30363d;--fg:#e6edf3;--dim:#8b949e;--hi:#58a6ff;
--hi2:#3fb950;--red:#f85149;--amber:#d29922;--pill:#21262d}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);
font:13px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace}
header{padding:14px 20px;border-bottom:1px solid var(--line);display:flex;gap:20px;align-items:baseline;flex-wrap:wrap}
h1{font-size:15px;margin:0;color:var(--hi)}.muted{color:var(--dim)}
.wrap{padding:16px 20px;display:grid;gap:16px;grid-template-columns:1fr 1fr}
.full{grid-column:1/-1}
.card{background:var(--panel);border:1px solid var(--line);border-radius:8px;overflow:hidden}
.card h2{font-size:12px;margin:0;padding:9px 12px;border-bottom:1px solid var(--line);
color:var(--dim);text-transform:uppercase;letter-spacing:.06em;display:flex;justify-content:space-between}
.scroll{max-height:340px;overflow:auto}
table{width:100%;border-collapse:collapse}td,th{padding:6px 12px;text-align:left;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--dim);font-weight:600;font-size:11px;position:sticky;top:0;background:var(--panel)}tr:last-child td{border-bottom:0}
.pill{display:inline-block;padding:1px 8px;border-radius:20px;background:var(--pill);font-size:11px}
.sev-high{color:var(--red)}.sev-medium{color:var(--amber)}.sev-low{color:var(--dim)}
.st-new{color:var(--hi)}.st-verified,.st-green,.st-success,.st-done{color:var(--hi2)}.st-rejected{color:var(--dim)}.st-red,.st-error{color:var(--red)}.st-running,.st-scanning{color:var(--amber)}.st-queued{color:var(--dim)}
.bar{height:6px;background:var(--pill);border-radius:4px;overflow:hidden;margin:0 12px 12px}
.bar>i{display:block;height:100%;background:var(--hi2);transition:width .5s}
.stat{display:inline-flex;flex-direction:column}.stat b{font-size:20px}.stat span{font-size:11px;color:var(--dim)}
.stats{display:flex;gap:22px;padding:12px;flex-wrap:wrap}
.dot{width:8px;height:8px;border-radius:50%;display:inline-block;margin-right:6px}
.d-running,.d-scanning{background:var(--amber)}.d-success,.d-done{background:var(--hi2)}.d-error{background:var(--red)}.d-idle,.d-queued{background:var(--dim)}
.empty{padding:20px;color:var(--dim)}.tiny{font-size:11px;color:var(--dim)}
.soon{border:1px dashed var(--line);color:var(--dim);border-radius:6px;padding:14px;text-align:center;margin:10px}
code{color:var(--hi)}.clock{font-variant-numeric:tabular-nums}
.clickrow{cursor:pointer}.clickrow:hover td{background:#1c2230}
.link{color:var(--hi);cursor:pointer}.link:hover{text-decoration:underline}
.modal-bg{position:fixed;inset:0;background:rgba(0,0,0,.65);display:none;z-index:100;justify-content:center;padding:36px 18px;overflow:auto}
.modal-bg.on{display:flex}
.modal{background:var(--panel);border:1px solid var(--line);border-radius:10px;max-width:960px;width:100%;max-height:88vh;display:flex;flex-direction:column;box-shadow:0 12px 40px rgba(0,0,0,.5);height:fit-content}
.modal h3{margin:0;padding:12px 16px;border-bottom:1px solid var(--line);font-size:13px;display:flex;justify-content:space-between;align-items:center;gap:12px}
.modal .x{cursor:pointer;color:var(--dim);font-size:16px}.modal .x:hover{color:var(--fg)}
.mtabs{display:flex;gap:4px;padding:10px 12px 0}
.tab{padding:6px 12px;cursor:pointer;border:1px solid transparent;border-bottom:none;border-radius:6px 6px 0 0;color:var(--dim);font-size:12px}
.tab.on{background:var(--bg);color:var(--fg);border-color:var(--line)}
.mbody{padding:12px 16px;overflow:auto;border-top:1px solid var(--line)}
pre.dlg{white-space:pre-wrap;word-break:break-word;font-size:12px;line-height:1.55;margin:0;color:var(--fg)}
pre.dlg b{color:var(--hi2)}
.tbranch{border-left:2px solid var(--line);margin:3px 0;padding-left:8px}
.tbranch>summary{cursor:pointer;list-style:none;font-family:ui-monospace,monospace;font-size:11.5px;
  color:var(--dim);line-height:1.8;user-select:none}
.tbranch>summary::-webkit-details-marker{display:none}
.tbranch>summary::before{content:'▸';margin-right:5px;opacity:.6;display:inline-block;transition:transform .12s}
.tbranch[open]>summary::before{transform:rotate(90deg)}
.tbranch>summary:hover{color:var(--hi)}
.tbranch.tk-dig{border-left-color:#7aa2f7}
.tbranch.tk-prompt{border-left-color:var(--pill)}
.tbranch.tk-verdict{border-left-color:var(--green,#9ece6a)}
.tbody{padding:4px 0 4px 10px}
.d-section{color:#7aa2f7;font-size:11px;font-weight:600;letter-spacing:.04em;margin:7px 0 3px}
.tnode{font-size:11.5px;font-family:ui-monospace,monospace;color:var(--dim);line-height:1.7;
  border-left:1px solid var(--line);margin-left:6px}
.tdep{display:inline-block;min-width:20px;font-size:9.5px;opacity:.75;margin-right:5px}
.tdep-1{color:#7aa2f7} .tdep-2{color:#bb9af7} .tdep-3{color:#e0af68}
.tglyph{margin-right:5px;opacity:.5}
.tname{color:var(--hi)}
.targs{margin-left:7px;opacity:.6}
.tstep-flat{border-left-color:var(--pill)!important}
.tstep{border:1px solid var(--line);border-left:3px solid #7aa2f7;border-radius:6px;
  padding:8px 11px;margin:6px 0;background:#0d1117}
.tstep-head{display:flex;align-items:baseline;gap:7px;flex-wrap:wrap}
.tbadge{font-size:9.5px;text-transform:uppercase;letter-spacing:.04em;padding:1px 6px;border-radius:3px;
  background:var(--pill);color:var(--dim)}
.tbadge.tbad{background:var(--red);color:#2b0906}
.tq{font-size:12px;margin:3px 0;color:var(--hi)}
.tq b{color:var(--dim);font-weight:600;margin-right:5px}
.timpl{border-top:1px solid var(--line);margin-top:6px;padding-top:6px}
.tchain{font-size:11px;color:var(--dim);margin:4px 0;font-family:ui-monospace,monospace;opacity:.8}
.tarrow{opacity:.5;margin:0 2px}
.tcontract{font-size:12.5px;line-height:1.55;margin-top:4px}
.trisk{font-size:12px;margin-top:4px;color:var(--amber)}
.trisk b{color:var(--dim);font-weight:600;margin-right:5px}
.vsummary{font-size:12.5px;line-height:1.55;margin:4px 0 6px}
.vsummary b,.vrisk b{color:var(--dim);font-weight:600;margin-right:6px;font-size:11px;text-transform:uppercase}
.vrisk{font-size:12.5px;line-height:1.55;margin-bottom:8px;color:var(--amber)}
.vcard{border:1px solid var(--line);border-left:3px solid var(--pill);border-radius:6px;
  padding:9px 12px;margin:7px 0;background:#0d1117}
.vcard:has(.vsev-high){border-left-color:var(--red)}
.vcard:has(.vsev-medium){border-left-color:var(--amber)}
.vtop{display:flex;align-items:baseline;gap:8px;flex-wrap:wrap}
.vtitle{font-weight:600;color:var(--hi)}
.vsev{font-size:10px;text-transform:uppercase;letter-spacing:.05em;padding:1px 6px;border-radius:3px;
  background:var(--pill);color:var(--dim)}
.vsev-high{background:var(--red);color:#2b0906}
.vsev-medium{background:var(--amber);color:#2b2106}
.vcat{font-size:11px;color:var(--dim);font-family:ui-monospace,monospace}
.vloc{font-size:11px;color:var(--dim);margin:3px 0 5px}
.vdesc{font-size:12.5px;line-height:1.55;margin-bottom:5px}
.vev{font-size:11.5px;font-family:ui-monospace,monospace;background:#0a0e15;border-radius:4px;
  padding:6px 9px;white-space:pre-wrap;word-break:break-word}
.vev b{color:var(--dim);font-weight:600;font-family:inherit;margin-right:6px}
.vempty{color:var(--dim);font-size:12px;padding:8px 2px}
.d-diag{color:var(--dim);font-size:11px;font-family:ui-monospace,monospace;opacity:.7;margin:2px 0}
.st-in-flight{color:#7aa2f7}
.dedupnote{color:var(--dim);font-size:11px;margin-top:2px}
.dimrow{opacity:.45}
.verline{padding:6px 12px;border-bottom:1px solid var(--line);color:var(--dim);font-size:11px}
.verline b{color:var(--hi);font-weight:600}
.jcode{background:#0a0e15;border:1px solid var(--line);border-radius:6px;padding:9px 12px;margin:5px 0;
  font:12px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre;overflow-x:auto;color:#c9d1d9}
.j-kw{color:#ff7b72}.j-str{color:#a5d6ff}.j-com{color:#8b949e;font-style:italic}.j-ann{color:#d2a8ff}.j-num{color:#79c0ff}.j-type{color:#7ee787}
.d-head{color:var(--hi);font-weight:600;margin:12px 0 4px;font-size:12px}
.d-tool{color:var(--amber);font:12px/1.5 ui-monospace,Menlo,monospace;margin:10px 0 2px;white-space:pre-wrap;word-break:break-word}
.d-verdict{color:var(--hi2);font-weight:600;margin:14px 0 4px;letter-spacing:.04em}
.d-prose{white-space:pre-wrap;color:var(--fg);font-size:12px;line-height:1.5}
.d-meta{color:var(--dim);font:11px/1.5 ui-monospace,Menlo,monospace;margin:2px 0 6px;white-space:pre-wrap;word-break:break-word}
.d-note{color:var(--dim);font-size:11px;font-style:italic;margin:2px 0 6px;padding-left:8px;border-left:2px solid var(--line)}
.pr-title{font-size:15px;font-weight:600;color:var(--fg);margin:2px 0 6px;line-height:1.35}
.pr-meta{margin-bottom:12px;font-size:11px;color:var(--dim)}
.pill-state{display:inline-block;padding:2px 9px;border-radius:20px;font-size:11px;background:var(--hi2);color:#04260f;font-weight:600;margin-right:6px}
.pr-body{white-space:pre-wrap;word-break:break-word;color:var(--fg);background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:10px 12px;margin:0 0 16px;font-size:12px;line-height:1.6}
.diff-file{border:1px solid var(--line);border-radius:6px;overflow:hidden;margin-bottom:10px}
.diff-path{background:var(--pill);padding:6px 12px;font-size:12px;color:var(--fg);border-bottom:1px solid var(--line)}
.diff-body{overflow-x:auto;font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace}
.diff-line{white-space:pre;padding:0 12px;display:block}
.diff-del{background:rgba(248,81,73,.14);color:#f85149}
.diff-add{background:rgba(63,185,80,.14);color:#3fb950}
.diff-hdr{color:var(--dim);font-size:11px;margin:14px 0 6px;text-transform:uppercase;letter-spacing:.05em}
</style></head><body>
<header>
  <h1>fix-java-svace-markers · live</h1>
  <div id=scanmeta class=muted>connecting…</div>
  <div class=clock id=timers style=color:var(--dim)></div>
  <div class=muted style=margin-left:auto id=ts></div>
</header>
<div class=wrap>
  <div class="card full">
    <h2><span>marker progress</span><span id=scanrepo class=tiny></span></h2>
    <div class=stats id=stats></div>
    <div class=bar><i id=progbar style=width:0%></i></div>
    <div class=tiny style="padding:0 12px 10px" id=current></div>
  </div>

  <!-- Verdicts are a first-class output, not a footnote: a marker that yields no PR must still yield
       an argued rebuttal, and this is where a reviewer reads it. -->
  <div class="card full">
    <h2><span>verdicts — every settled marker, whatever the outcome</span><span id=verdictcount class=tiny></span></h2>
    <div class=scroll id=verdicts></div>
  </div>

  <div class="card full">
    <h2><span>markers</span><span id=suscount class=tiny></span></h2>
    <div class=scroll id=suspicions></div>
  </div>

  <div class="card">
    <h2><span>bugs · proven red→green</span><span class=tiny id=bugcount></span></h2>
    <div id=bugs></div>
  </div>

  <div class="card">
    <h2>activity</h2>
    <div class=scroll id=activity></div>
  </div>

  <div class="card full">
    <h2><span>errors · orchestrator + suspector + prover</span><span id=errcount class=tiny></span></h2>
    <div class=scroll id=errors></div>
  </div>
</div>
<div class=modal-bg id=modalbg onclick="if(event.target===this)closeModal()">
  <div class=modal>
    <h3><span id=mtitle></span><span class=x onclick=closeModal()>✕ close</span></h3>
    <div class=mtabs id=mtabs></div>
    <div class=mbody id=mbody></div>
  </div>
</div>
<script>
let DASH = {files:[],suspicions:[],bugs:[]};
async function jget(u){try{return await (await fetch(u,{cache:'no-store'})).json();}catch(e){return null;}}
function stopLiveTimer(){if(window.__LIVET){clearInterval(window.__LIVET);window.__LIVET=null;}}
function closeModal(){stopLiveTimer();document.getElementById('modalbg').classList.remove('on');}
function openModal(title){stopLiveTimer();document.getElementById('mtitle').innerHTML=title;document.getElementById('mtabs').innerHTML='';document.getElementById('mbody').innerHTML='<div class=empty>loading…</div>';document.getElementById('modalbg').classList.add('on');}
document.addEventListener('keydown',e=>{if(e.key==='Escape')closeModal();});
function setTabs(tabs,active){const tb=document.getElementById('mtabs');tb.innerHTML='';tabs.forEach((t,i)=>{const el=document.createElement('span');el.className='tab'+(i===active?' on':'');el.textContent=t.name;el.onclick=()=>setTabs(tabs,i);tb.appendChild(el);});document.getElementById('mbody').innerHTML=tabs[active].render();}
async function showFile(f){ if(!f)return; openModal('methods · <code>'+esc(short(f))+'</code>');
  const rows=await jget('api/methods?file='+encodeURIComponent(f))||[];
  document.getElementById('mtabs').innerHTML='';
  if(!rows.length){document.getElementById('mbody').innerHTML=empty('no methods recorded for this file yet');return;}
  window.__M=rows;
  document.getElementById('mbody').innerHTML=tbl(['method','findings','tool calls','status','dialog'],
    rows.map(r=>['<code>'+esc(r.method)+'</code>',(r.findings||0)?'<b>'+r.findings+'</b>':'0',(r.tool_calls||0),esc(r.status||''),'<span class=link>view →</span>']),
    i=>"showDialog(window.__M["+i+"].method_key, window.__M["+i+"].method)"); }
async function showDialog(key,method){ openModal('suspector dialog · <code>'+esc(method||'')+'</code>');
  const d=await jget('api/dialog?key='+encodeURIComponent(key)); document.getElementById('mtabs').innerHTML='';
  document.getElementById('mbody').innerHTML=fmtDialog((d&&d.dialog)||''); }
async function showInvestigation(su){ if(!su)return;
  const mkey=su.method_key||(su.repo+'|'+su.file+'|'+su.method);   // producing run (class audits / callee findings differ)
  openModal('<code>'+esc(short(su.file))+' :: '+esc(su.method)+'</code> — '+esc((su.title||'').slice(0,70)));
  const [dlg,bug]=await Promise.all([jget('api/dialog?key='+encodeURIComponent(mkey)),jget('api/bug?key='+encodeURIComponent(su.dedup_key||su.suspicion_key||''))]);
  const tabs=[{name:'Suspector',render:()=>verline(stageVer(bug,'suspector',su.version),bug)+fmtDialog((dlg&&dlg.dialog)||'')}];
  if(bug&&bug.suspicion_key){
    tabs.push({name:'Reproducer '+(String(bug.red_verified)==='1'?'●':'○'),render:()=>renderTest(bug)});
    tabs.push({name:'Fixer '+(String(bug.green_verified)==='1'?'●':'○'),render:()=>renderFix(bug)});
    tabs.push({name:'PR maker '+(bug.state==='pr_ready'?'●':(bug.state==='pr_rejected'?'⛔':'○')),render:()=>renderPR(bug)});
  } else { tabs.push({name:'Reproducer / Fixer / PR',render:()=>empty('not proven yet — run the prover to generate a test, fix + PR decision')}); }
  setTabs(tabs,0); }
function showBug(b){ if(!b)return; const parts=(b.suspicion_key||'').split('|'); showInvestigation({repo:b.repo,file:b.file,method:parts[2]||'',dedup_key:b.suspicion_key,title:b.title}); }
const esc=s=>(s==null?'':String(s)).replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
const short=p=>p?p.split('/').pop():'';
// folders before class: drop the noisy src/main/java segment, dim the folders, highlight the class
function pkg(p){ if(!p) return ''; const clean=p.replace('/src/main/java/','/').replace(/^src\/main\/java\//,''); const j=clean.lastIndexOf('/');
  return j<0 ? '<code>'+esc(clean)+'</code>'
    : '<span class=tiny>'+esc(clean.slice(0,j+1))+'</span><code>'+esc(clean.slice(j+1))+'</code>'; }
const fmtTime=s=>{if(!s)return '';const d=new Date(s.replace(' ','T')+'Z');return d.toLocaleTimeString();};
function dur(s){if(s==null)return '';s=Math.round(s);
  const d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60),ss=s%60;
  const p=n=>n.toString().padStart(2,'0');
  if(d)return d+'d'+p(h)+'h';
  if(h)return h+'h'+p(m)+'m';
  if(m)return m+'m'+p(ss)+'s';
  return ss+'s';}
async function tick(){
  let s; try{ s=await (await fetch('api/state',{cache:'no-store'})).json(); }catch(e){ return; }
  DASH=s;
  document.getElementById('ts').textContent='updated '+new Date().toLocaleTimeString();
  const sc=s.scan;
  document.getElementById('scanmeta').innerHTML='<span class="dot d-'+(sc.status||'idle')+'"></span>'+esc(sc.status||'idle')
    +(sc.repo?'  ·  <code>'+esc(sc.repo)+'</code>':'');
  const scope=(sc.total?sc.total+' files · '+(sc.total_methods||0)+' methods':(sc.planning?'planning…':''))
    +(sc.pathFilter?" · filter '"+esc(sc.pathFilter)+"'":'');
  document.getElementById('scanrepo').innerHTML=(sc.repo?'<code>'+esc(sc.repo)+'</code> · ':'')+'<span class=tiny>'+scope+'</span>'+(sc.startedAt?' · '+fmtTime(sc.startedAt):'');
  document.getElementById('timers').innerHTML =
    (sc.elapsed!=null?'⏱ elapsed <b class=clock>'+dur(sc.elapsed)+'</b>':'') +
    (sc.status==='running'&&sc.eta!=null?'   ·   ⏳ ~<b class=clock>'+dur(sc.eta)+'</b> left':'');
  // Markers, not a file scan: the backlog is the Svace report, so progress is "how many markers are
  // still status=new" rather than files walked. There is no suspector and no dedup stage any more.
  const all=s.suspicions;
  const byStatus=(st)=>all.filter(x=>x.status===st).length;
  const pending=byStatus('new')+byStatus('infra_stuck');
  const settled=all.length-byStatus('new');
  document.getElementById('stats').innerHTML=
    stat(all.length,'markers')
    +stat(byStatus('new'),'queued')
    +stat(byStatus('verified'),'proven')
    +stat(byStatus('false_positive'),'refuted')
    +stat(byStatus('by_design'),'by design')
    +stat(byStatus('unprovable'),'unprovable')
    +stat(byStatus('rejected'),'not reproduced')
    +stat(byStatus('infra_stuck'),'infra stuck')
    +stat(s.bugs.filter(b=>String(b.red_verified)==='1'&&String(b.green_verified)==='1').length,'red→green')
    +stat(s.bugs.filter(b=>b.state==='infra_error').length,'infra retries');
  document.getElementById('progbar').style.width=(all.length?Math.round(settled/all.length*100):0)+'%';
  document.getElementById('current').innerHTML = pending
    ? ('▶ '+pending+' marker(s) still to settle — the prover takes them one at a time under the runner lease')
    : (all.length?'✓ every marker settled':'no markers ingested yet — POST /webhook/ingest');
  document.getElementById('scanrepo').innerHTML=(all.length?'<code>'+esc(all[0].repo||'')+'</code>':'');

  // Verdicts — every column of the Svace report row, then what we concluded about it.
  // Severity/Checker/File/Line ARE the report; a verdict shown without them asks the reviewer to
  // judge an answer without the question. The claim column is the checker's meaning, which is what
  // the verdict is actually arguing against.
  const verdicts=s.bugs.filter(b=>(b.verdict_text||'').trim());
  const settled=all.filter(x=>x.status!=='new').length;
  document.getElementById('verdictcount').textContent=
    verdicts.length+' of '+settled+' settled markers · '+all.length+' total';
  // every kind gets a colour; an unmapped one must stay visible rather than blend into the amber default
  const kindColour={'true-positive':'var(--hi2)','true-positive-unfixed':'var(--amber)',
    'false-positive':'var(--hi)','by-design':'var(--hi2)','unprovable':'var(--amber)',
    'needs-review':'var(--red)','undetermined':'var(--red)'};
  document.getElementById('verdicts').innerHTML=verdicts.length? tbl(
    ['severity','checker','file','line','category','anchor','claim','kind','verdict'],
    verdicts.map(v=>[
      '<span class=sev-'+esc(v.severity||'')+'>'+esc(v.svace_severity||v.severity||'?')+'</span>',
      '<span class=tiny>'+esc(v.svace_checker||'')+'</span>',
      pkg(v.file)+(v.marker_orphaned?'<div class=tiny style="color:var(--red)">marker row gone — re-ingested since</div>':''),
      '<span class=tiny>'+num(v.svace_line!=null?v.svace_line:v.line)+'</span>',
      '<span class=tiny>'+esc(v.category||'')+'</span>',
      '<span class=tiny>'+esc(v.anchor?v.anchor+'()':(v.anchor_status||''))+'</span>',
      '<div class=tiny style="max-width:30em">'+esc(v.description||'')+'</div>',
      '<span class=pill-state style="background:'+(kindColour[v.verdict_kind]||'var(--amber)')+';color:#101418">'
        +esc(v.verdict_kind||'?')+'</span>',
      '<div style="white-space:pre-wrap;min-width:26em">'+esc(v.verdict_text)+'</div>']))
    : empty('no verdicts yet — every settled marker gets one, whatever the outcome');

  // markers table
  document.getElementById('suscount').textContent=all.length+' rows';
  document.getElementById('suspicions').innerHTML=all.length? tbl(
    ['sev','checker','category','file:line','anchor','status'],
    all.map(x=>['<span class=sev-'+esc(x.severity)+'>'+esc(x.svace_severity||x.severity)+'</span>',
      '<span class=tiny>'+esc(x.svace_checker||'')+'</span>', esc(x.category),
      pkg(x.file)+'<span class=tiny>:'+num(x.svace_line!=null?x.svace_line:x.line)+'</span>',
      '<span class=tiny>'+esc(x.anchor?x.anchor+'()':(x.anchor_status||''))+'</span>',
      '<span class=st-'+esc(x.status)+'>'+esc(x.status)+'</span>'
      +((x.note||'').trim()?'<div class=dedupnote>'+esc(String(x.note).slice(0,180))+'</div>':'')]),
      i=>"showInvestigation(DASH.suspicions["+i+"])") : empty('no markers ingested yet');
  DASH.__all=all;

  // bugs
  document.getElementById('bugcount').textContent=s.bugs.length+' rows';
  document.getElementById('bugs').innerHTML = s.prover_built ? (s.bugs.length? tbl(
    ['file','title','red','green','state'],
    s.bugs.map(x=>['<span class=tiny>'+esc(short(x.file))+'</span>',esc(x.title),
      redgreen(x.red_verified),redgreen(x.green_verified),esc(x.state)]),
      i=>"showBug(DASH.bugs["+i+"])") : empty('no proven bugs yet'))
    : '<div class=soon>Prover not built yet — lights up when the suspicion→test→red→fix→green loop is wired.</div>';

  // activity
  document.getElementById('activity').innerHTML=tbl(['','flow','file','status','took'],
    s.activity.map(a=>['<span class="dot d-'+esc(a.status)+'"></span>'+fmtTime(a.started),esc(a.wf),
      '<span class=tiny>'+esc(a.file)+'</span>','<span class=st-'+esc(a.status)+'>'+esc(a.status)+'</span>',dur(a.dur)]));
}
function stat(n,l){return '<span class=stat><b>'+n+'</b><span>'+l+'</span></span>';}
// n8n Data Table `number` columns come back as REAL, so a line number arrives as 26.0 and a source
// location reads "Foo.java:26.0". Render whole numbers as whole numbers.
function num(x){const n=Number(x);return (x===null||x===undefined||x===''||isNaN(n))?'?':String(Math.round(n));}
function redgreen(v){return v?'<span class=st-green>●</span>':'<span class=st-red>○</span>';}
// lightweight single-pass Java highlighter (esc first, then one alternation so keywords inside
// comments/strings are never separately matched -> no broken nesting)
function hlJava(code){
  return esc(String(code==null?'':code)).replace(
    /(\/\*[\s\S]*?\*\/|\/\/[^\n]*)|("(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')|(@\w+)|\b(public|private|protected|static|final|abstract|class|interface|enum|record|extends|implements|new|return|if|else|for|while|switch|case|do|break|continue|throw|throws|try|catch|finally|void|int|long|double|float|boolean|char|byte|short|this|super|null|true|false|instanceof|package|import|synchronized|volatile|transient|native|default)\b|\b([A-Z][A-Za-z0-9_]*)\b|\b(\d[\d._]*[LlFfDd]?)\b/g,
    (m,com,str,ann,kw,typ,num)=> com?'<span class=j-com>'+com+'</span>' : str?'<span class=j-str>'+str+'</span>'
      : ann?'<span class=j-ann>'+ann+'</span>' : kw?'<span class=j-kw>'+kw+'</span>'
      : typ?'<span class=j-type>'+typ+'</span>' : '<span class=j-num>'+num+'</span>');
}
// `· dN name(args)` lines are call-TREE nodes emitted by the sub-investigators. Render them nested by
// depth with a connector, so the exploration reads as the tree it actually is.
function treeNode(depth, kind, label, detail){
  const pad = 10 + depth*20;
  return '<div class="tnode" style="padding-left:'+pad+'px">'
    + '<span class="tdep tdep-'+Math.min(depth,3)+'">d'+depth+'</span>'
    + '<span class=tglyph>'+(kind==='ret'?'↩':'↳')+'</span>'
    + '<code class=tname>'+esc(label)+'</code>'
    + (detail?'<span class=targs>'+esc(detail)+'</span>':'') + '</div>';
}
// a candidate may be a signature string OR an object from the resolver's fallback path
function candLabel(c){
  if(c==null) return '';
  if(typeof c==='string') return c;
  return c.signature || c.qualifiedSignature || c.name ||
         ((c.file?short(c.file):'')+(c.line?(':'+c.line):'')) || JSON.stringify(c).slice(0,120);
}
function renderStepInto(o){
  const d = Number(o.call_depth||o.depth||1);
  const pad = 10 + (d-1)*20;
  let h='<div class="tstep" style="margin-left:'+pad+'px">';
  h+='<div class=tstep-head><span class="tdep tdep-'+Math.min(d,3)+'">depth '+d+'</span>'
    +'<code>'+esc(o.stepped_into||'')+'</code>'
    +(o.lines?'<span class=tiny> '+esc(o.file||'')+':'+esc(o.lines)+'</span>':'')
    +'<span class="tbadge">sub-agent</span>'
    +((o.defects_reported||0)>0?'<span class="tbadge tbad">'+o.defects_reported+' defect(s)</span>':'')+'</div>';
  if(o.question) h+='<div class=tq><b>why</b> '+esc(o.question)+'</div>';
  if(Array.isArray(o.chain)&&o.chain.length>1)
    h+='<div class=tchain>'+o.chain.map(x=>esc(String(x).split('(')[0])).join(' <span class=tarrow>↓</span> ')+'</div>';
  if(o.contract) h+='<div class=tcontract>'+esc(o.contract)+'</div>';
  if(o.caller_risks&&!/^none/i.test(o.caller_risks)) h+='<div class=trisk><b>risk to caller</b> '+esc(o.caller_risks)+'</div>';
  return h+'</div>';
}
function fmtToolResult(s){
  let o=null;
  try{ o=JSON.parse(s.trim()); }catch(e){ o=repairJson(s.trim()); }   // transcripts truncate: repair it
  if(!o||typeof o!=='object') return '<div class=d-meta>'+esc(s.slice(0,3000))+'</div>';
  if(o && o.stepped_into) return renderStepInto(o);
  if(o && o.call_depth && !o.stepped_into && !Array.isArray(o.reports)){
    const d=Number(o.call_depth), pad=10+(d-1)*20;
    const why = o.refused ? 'refused — retrieval request'
              : o.ambiguous ? 'ambiguous — needs a precise symbol'
              : o.resolved_to ? 'no sub-agent — ' + o.resolved_to
              : (o.found===false ? 'unresolved' : (o.note ? 'no sub-agent needed' : ''));
    let h='<div class="tstep tstep-flat" style="margin-left:'+pad+'px"><div class=tstep-head>'
      +'<span class="tdep tdep-'+Math.min(d,3)+'">depth '+d+'</span>'
      +'<code>'+esc(o.symbol||o.dug_into||'')+'</code>'
      +(why?'<span class="tbadge">'+esc(why)+'</span>':'')+'</div>';
    if(o.note) h+='<div class=tcontract>'+esc(o.note)+'</div>';
    if(Array.isArray(o.candidates)&&o.candidates.length)
      h+='<div class=tchain>'+o.candidates.map(c=>esc(candLabel(c))).join('<br>')+'</div>';
    if(o.source) h+='<pre class="dlg jcode">'+hlJava(o.source)+'</pre>';
    return h+'</div>';
  }
  if(o && o.dug_into && Array.isArray(o.reports)){          // polymorphic call -> the overrides that run
    const d=Number(o.call_depth||o.depth||1), pad=10+(d-1)*20;
    let h='<div class="tstep" style="margin-left:'+pad+'px"><div class=tstep-head>'
      +'<span class="tdep tdep-'+Math.min(d,3)+'">d'+d+'</span><code>'+esc(o.dug_into)+'</code>'
      +'<span class="tbadge">polymorphic · '+esc(o.implementations_found||0)+' impl(s)</span></div>';
    if(o.note) h+='<div class=tchain>'+esc(o.note)+'</div>';
    o.reports.forEach(r=>{ if(!r) return;
      h+='<div class=timpl><code>'+esc(r.signature||r.file||'?')+'</code>'
        +(r.contract?'<div class=tcontract>'+esc(r.contract)+'</div>':'')
        +((r.defects_found||0)>0?'<span class="tbadge tbad">'+r.defects_found+' defect(s)</span>':'')+'</div>'; });
    return h+'</div>';
  }
  let h='';
  const codeF = o.source || o.content || o.method_src;   // the actual Java a read/goto returned
  if(codeF) h+='<pre class="dlg jcode">'+hlJava(codeF)+'</pre>';
  if(o.note) h+='<div class=d-note>'+esc(o.note)+'</div>';
  if(Array.isArray(o.matches)) h+='<pre class="dlg jcode">'+o.matches.map(x=>hlJava(x)).join('\n')+'</pre>';
  if(o.resolved_to) h+='<div class=d-note>'+esc(o.resolved_to)+'</div>';
  const skip={source:1,content:1,method_src:1,skeleton:1,note:1,matches:1,resolved_to:1};
  const meta={}; for(const k in o) if(!skip[k]) meta[k]=o[k];
  if(Object.keys(meta).length) h+='<div class=d-meta>'+esc(JSON.stringify(meta))+'</div>';
  return h || '<div class=d-meta>'+esc(s.slice(0,600))+'</div>';
}
// The VERDICT is a JSON object, not prose. Render each suspicion as a card: severity, where it
// lives, what breaks, and the concrete evidence.
function sevPill(v){ const x=(v||'').toLowerCase();
  return '<span class="vsev vsev-'+esc(x||'none')+'">'+esc(x||'?')+'</span>'; }
// the model often ends a long verdict one brace short; repair it rather than showing raw JSON
function repairJson(t){
  const a=t.indexOf('{'); if(a<0) return null;
  const body=t.slice(a), b=body.lastIndexOf('}');
  if(b>0){ try{ return JSON.parse(body.slice(0,b+1)); }catch(e){} }
  let curly=0,square=0,inStr=false,esc2=false,out='';
  for(const ch of body){
    out+=ch;
    if(esc2){esc2=false;continue;}
    if(ch==='\\'){esc2=true;continue;}
    if(ch==='"'){inStr=!inStr;continue;}
    if(inStr) continue;
    if(ch==='{')curly++; else if(ch==='}')curly--;
    else if(ch==='[')square++; else if(ch===']')square--;
  }
  if(inStr) out+='"';
  while(square-->0) out+=']';
  while(curly-->0) out+='}';
  try{ return JSON.parse(out); }catch(e){ return null; }
}
function renderVerdict(raw){
  const t=String(raw||'').trim();
  if(!t) return '<div class=vempty>(no verdict text — the model returned nothing)</div>';
  const o=repairJson(t);
  const list = o && (Array.isArray(o.defects) ? o.defects
                   : (Array.isArray(o.suspicions) ? o.suspicions : null));
  if(!list) return '<div class=d-prose>'+esc(t)+'</div>';
  let head='';
  if(o.summary) head+='<div class=vsummary><b>summary</b> '+esc(o.summary)+'</div>';
  if(o.caller_risks && !/^none/i.test(o.caller_risks))
    head+='<div class=vrisk><b>risk to caller</b> '+esc(o.caller_risks)+'</div>';
  if(!list.length) return head+'<div class=vempty>no defects reported for this method</div>';
  return head+list.map(x=>{
    const loc=[x.file?short(x.file):'', x.line?('line '+x.line):''].filter(Boolean).join(' · ');
    let h='<div class=vcard>';
    h+='<div class=vtop>'+sevPill(x.severity)+(x.category?'<span class=vcat>'+esc(x.category)+'</span>':'')
      +'<span class=vtitle>'+esc(x.title||'(untitled)')+'</span></div>';
    if(x.file||x.method||x.line) h+='<div class=vloc>'+(x.file?'<span class=tiny>'+esc(x.file)+'</span>':'')
      +(x.method?' <code>'+esc(x.method)+'()</code>':'')+(x.line?' <span class=tiny>:'+esc(x.line)+'</span>':'')+'</div>';
    if(x.description) h+='<div class=vdesc>'+esc(x.description)+'</div>';
    if(x.evidence) h+='<div class=vev><b>evidence</b> '+hlJava(x.evidence)+'</div>';
    return h+'</div>';
  }).join('');
}
// The transcript is a CALL TREE serialised as depth-tagged lines. Parse it back into a tree so each
// dig is a collapsible node holding the context it inherited, its own calls, and its verdict.
function parseTree(text){
  const root={depth:0,label:null,children:[],lines:[]}, stack=[root];
  for(const ln of String(text).split('\n')){
    const m=ln.match(/^·\s*d(\d+)\s+(.*)$/);
    if(m){
      const depth=Number(m[1]);
      while(stack.length>1 && stack[stack.length-1].depth>=depth) stack.pop();
      const node={depth,label:m[2],children:[],lines:[]};
      stack[stack.length-1].children.push(node);
      stack.push(node);
    } else {
      stack[stack.length-1].lines.push(ln);
    }
  }
  return root;
}
function nodeKind(label){
  if(/^dig_for_bugs/.test(label)) return 'dig';
  if(/^PROMPT/.test(label)) return 'prompt';
  if(/^VERDICT/.test(label)) return 'verdict';
  if(/^verdict:/.test(label)) return 'attempt';
  return 'call';
}
let __tkeyN={};
// stable, attribute-safe node id: the label is raw JSON and would break out of the attribute
function tkeyHash(str){ let h=5381; for(let i=0;i<str.length;i++) h=((h*33)^str.charCodeAt(i))>>>0; return h.toString(36); }
function renderTree(node){
  let h='';
  for(const c of node.children){
    const kind=nodeKind(c.label||'');
    const name=(c.label||'').split(/[\s({]/)[0];
    const rest=(c.label||'').slice(name.length).trim();
    const body=(c.lines.join('\n').trim()?fmtLines(c.lines.join('\n')):'')+renderTree(c);
    const badge='<span class="tdep tdep-'+Math.min(c.depth,3)+'">d'+c.depth+'</span>';
    if(!body){
      h+='<div class="tnode" style="padding-left:'+(10+c.depth*16)+'px">'+badge
        +'<span class=tglyph>'+(kind==='attempt'?'↩':'↳')+'</span><code class=tname>'+esc(name)+'</code>'
        +(rest?'<span class=targs>'+esc(rest.slice(0,160))+'</span>':'')+'</div>';
      continue;
    }
    // a node with content becomes an EXPANDING node; digs stay open one level so the tree is visible
    const open=(kind==='dig'||kind==='verdict')?' open':'';
    const kbase=c.depth+'-'+tkeyHash((c.label||'').slice(0,160));
    __tkeyN[kbase]=(__tkeyN[kbase]||0)+1;
    const tkey=kbase+'-'+__tkeyN[kbase];
    h+='<details class="tbranch tk-'+kind+'"'+open+' data-tkey="'+esc(tkey)+'"'
      +' style="margin-left:'+(c.depth*16)+'px">'
      +'<summary>'+badge+'<span class=tglyph>'+(kind==='attempt'?'↩':'↳')+'</span>'
      +'<code class=tname>'+esc(name)+'</code>'
      +(rest?'<span class=targs>'+esc(rest.slice(0,200))+'</span>':'')+'</summary>'
      +'<div class=tbody>'+body+'</div></details>';
  }
  return h;
}
function fmtLines(text){
  let out='', code=null;   // code != null -> inside a ```java fence
  for(const ln of String(text).split('\n')){
    if(code!==null){
      if(ln.trim()==='```'){ out+='<pre class="dlg jcode">'+hlJava(code)+'</pre>'; code=null; }
      else code += (code?'\n':'')+ln;
      continue;
    }
    if(ln.trim().startsWith('```')){ code=''; continue; }
    if(!ln.trim()) continue;
    if(ln.startsWith('▸')) out+='<div class=d-head>'+esc(ln)+'</div>';
    else if(ln.startsWith('>>>')) out+='<div class=d-tool>'+esc(ln.replace(/^>>>\s*/,'▸ '))+'</div>';
    else if(ln.startsWith('<<<')) out+=fmtToolResult(ln.slice(3));
    else if(/^\[(verdict|audit|investigation error|the model answered)/.test(ln.trim()))
      out+='<div class=d-diag>'+esc(ln.trim())+'</div>';
    else if(/^===\s*(THE INITIAL TASK|THE INVESTIGATION SO FAR|YOUR TASK)/.test(ln.trim()))
      out+='<div class=d-section>'+esc(ln.replace(/=/g,'').trim())+'</div>';
    else if(ln.startsWith('===')) out+='<div class=d-verdict>'+esc(ln.replace(/=/g,'').trim()||'VERDICT')+'</div>';
    else out+='<div class=d-prose>'+esc(ln)+'</div>';
  }
  if(code) out+='<pre class="dlg jcode">'+hlJava(code)+'</pre>';
  return out;
}
function fmtBody(text){
  __tkeyN={};
  const root=parseTree(text);
  return (root.lines.join('\n').trim()?fmtLines(root.lines.join('\n')):'')+renderTree(root);
}
function fmtDialog(text){
  if(!text) return empty('(no transcript)');
  const s=String(text);
  const MARK='=== VERDICT ===';
  const i=s.lastIndexOf(MARK);
  if(i<0) return fmtBody(s);
  return fmtBody(s.slice(0,i))+'<div class=d-verdict>VERDICT</div>'+renderVerdict(s.slice(i+MARK.length));
}
function difflines(s,cls,sign){return String(s==null?'':s).split('\n').map(l=>'<span class="diff-line '+cls+'">'+sign+esc(l)+'</span>').join('');}
function difffile(path,edits){ let b=''; edits.forEach(e=>{b+=difflines(e.old_str,'diff-del','- ')+difflines(e.new_str,'diff-add','+ ');});
  return '<div class=diff-file><div class=diff-path>'+esc(path)+'</div><div class=diff-body>'+b+'</div></div>'; }
// --- pipeline/stage versions -------------------------------------------------------------------
// Every artifact carries the versions that produced it (bugs.versions JSON, suspicions.version), so a
// finding from an older suspector is never read as if the current one produced it.
function bugVersions(bug){ try{ const v=JSON.parse((bug&&bug.versions)||'{}'); return (typeof v==='string')?JSON.parse(v):v; }catch(e){ return {}; } }
function stageVer(bug,stage,fallback){ const v=bugVersions(bug); return v[stage]||fallback||''; }
function verline(stageV,bug){ const p=bugVersions(bug).pipeline||'';
  if(!stageV&&!p) return '';
  return '<div class=verline>'+(stageV?'<b>'+esc(stageV)+'</b>':'')+(p?'<span> · pipeline '+esc(p)+'</span>':'')+'</div>'; }

function renderFix(bug){   // the FIX itself (diff); the PR decision lives in its own tab
  let edits=[]; try{edits=JSON.parse(bug.fix_diff||'[]');}catch(e){}
  let h=verline(stageVer(bug,'fixer'),bug);
  h+='<div class=pr-meta>fails-before-fix '+redgreen(bug.red_verified)+' · passes-after-fix '+redgreen(bug.green_verified)
    +(bug.jdk?' · jdk '+esc(bug.jdk):'')+'</div>';
  h+='<div class=diff-hdr>Files changed</div>';
  if(Array.isArray(edits)&&edits.length){
    const byPath={}; edits.forEach(e=>{(byPath[e.path]=byPath[e.path]||[]).push(e);});
    for(const p in byPath) h+=difffile(p,byPath[p]);
  } else { h+='<pre class=dlg>'+esc(bug.fix_diff||'(no fix)')+'</pre>'; }
  return h;
}
function renderPR(bug){   // the PR-maker stage: make / reject (repo-specific) + the drafted PR
  const st=bug.state||'';
  let badge, note;
  if(st==='pr_ready'){ badge='<span class=pill-state>PR READY</span>'; note=' — worth opening upstream'; }
  else if(st==='pr_rejected'){ badge='<span class="pill-state" style="background:var(--red);color:#2b0906">PR REJECTED</span>'; note=' — proven bug, but the PR maker decided it is not PR-worthy for this repo'; }
  else if(st==='needs_review'){ badge='<span class="pill-state" style="background:var(--amber);color:#2b2106">NEEDS REVIEW</span>'; note=' — the fix skeptic flagged the fix, so the PR maker was skipped'; }
  else if(st==='infra_error'){ badge='<span class="pill-state" style="background:var(--amber);color:#2b2106">INFRA ERROR</span>'; note=' — the pipeline failed to run this one; NOT a judgement about the code (the suspicion stays queued for retry)'; }
  else { badge='<span class="pill-state" style="background:var(--pill);color:var(--dim)">'+esc(st||'—')+'</span>'; note=' — not PR-ready'; }
  let h=verline(stageVer(bug,'pr_maker'),bug);
  h+='<div class=pr-meta>'+badge+esc(note)+'</div>';
  if(bug.infra_reason) h+='<div class=d-note>infra: '+esc(bug.infra_reason)+'</div>';
  h+='<div class=pr-title>'+esc(bug.pr_title||'(no title)')+'</div>';
  if(bug.pr_body) h+='<div class=pr-body>'+esc(bug.pr_body)+'</div>';
  return h;
}
function renderTest(bug){
  let h=verline(stageVer(bug,'reproducer'),bug);
  h+='<div class=pr-meta>fails-before-fix '+redgreen(bug.red_verified)+(bug.jdk?' · jdk '+esc(bug.jdk):'')+'</div>';
  h+='<div class=diff-file><div class=diff-path>'+esc(bug.test_path||'(test)')+'</div>'
    +'<pre class="dlg jcode" style="margin:0;padding:10px 12px">'+hlJava(bug.test_code||'(no test)')+'</pre></div>';
  return h;
}
function empty(t){return '<div class=empty>'+t+'</div>';}
function tbl(head,rows,rowclick){if(!rows.length)return empty('—');
  return '<table><thead><tr>'+head.map(h=>'<th>'+h+'</th>').join('')+'</tr></thead><tbody>'+
    rows.map((r,i)=>'<tr'+(rowclick?' class=clickrow onclick="'+rowclick(i)+'"':'')+'>'+r.map(c=>'<td>'+c+'</td>').join('')+'</tr>').join('')+'</tbody></table>';}
function livecls(st){return st==='analyzing'?'running':(st==='error'||st==='stale'||st==='gone')?'error':'done';}
async function renderLive(){
  // The live-dialog panel belonged to the LLM suspector, which the Svace ingester replaced — nothing
  // streams ReAct transcripts any more, so the card is gone from the page. Bail before touching it:
  // getElementById returns null here and the TypeError would kill this 3s interval every tick.
  if(!document.getElementById('live')) return;
  const d=await jget('api/live'); const rows=(d&&d.dialogs)||[]; window.__LIVE=rows;
  const na=rows.filter(r=>r.status==='analyzing').length;
  document.getElementById('livecount').textContent=rows.length?(na+' analyzing · '+rows.length+' recent'):'idle';
  document.getElementById('live').innerHTML=rows.length? tbl(['method','file','status','tools','transcript'],
    rows.map(r=>['<code>'+esc(r.method||'')+'</code>','<span class=tiny>'+esc(short(r.file||''))+'</span>',
      '<span class="dot d-'+livecls(r.status)+'"></span><span class=st-'+livecls(r.status)+'>'+esc(r.status||'')+'</span>',
      (r.tool_calls||0),'<span class=link>watch →</span>']),
    i=>"showLive("+i+")")
    : empty('no methods analyzing right now');
}
function showLive(i){ const r=window.__LIVE[i]; if(!r)return; const key=r.method_key;
  openModal('live · <code>'+esc(r.method||'')+'</code>'); document.getElementById('mtabs').innerHTML='';
  let __lastHtml='';
  async function refresh(){ const d=await jget('api/live?key='+encodeURIComponent(key));
    const t=(d&&d.dialog)||'(streaming…)'; const st=(d&&d.status)||'';
    const body=document.getElementById('mbody');
    const html='<div class=tiny>status <span class=st-'+livecls(st)+'>'+esc(st)+'</span> · '+((d&&d.tool_calls)||0)+' tool calls · auto-refreshing every 2s</div>'+fmtDialog(t);
    if(html!==__lastHtml){
      // a click expands a node; replacing innerHTML wholesale collapsed it again on the next tick
      const open=new Set([...body.querySelectorAll('details[data-tkey]')].filter(x=>x.open).map(x=>x.dataset.tkey));
      const top=body.scrollTop;
      body.innerHTML=html;
      __lastHtml=html;
      body.querySelectorAll('details[data-tkey]').forEach(x=>{ if(open.has(x.dataset.tkey)) x.open=true; });
      body.scrollTop=top;
    }
    if(st!=='analyzing')stopLiveTimer(); }
  stopLiveTimer(); refresh(); window.__LIVET=setInterval(refresh,2000);
}
async function renderErrors(){
  const rows=await jget('api/errors')||[]; window.__ERR=rows;
  document.getElementById('errcount').textContent=rows.length?rows.length+' recent':'none';
  document.getElementById('errors').innerHTML=rows.length? tbl(['','flow','node','file','when','error'],
    rows.map(e=>['<span class="dot d-error"></span>',esc(e.wf),'<span class=tiny>'+esc(e.node||'')+'</span>',
      '<span class=tiny>'+esc(e.file||'')+'</span>',fmtTime(e.started),
      '<span class=st-error>'+esc((e.message||'').slice(0,140))+'</span>']),
    i=>"showError("+i+")")
    : empty('no recent errors');
}
function showError(i){ const e=window.__ERR[i]; if(!e)return;
  openModal('error · <code>'+esc(e.wf)+'</code>'+(e.node?' · '+esc(e.node):'')); document.getElementById('mtabs').innerHTML='';
  document.getElementById('mbody').innerHTML='<div class=tiny>'+esc(e.wf)+' · exec '+esc(e.id)+' · <span class=st-error>'+esc(e.status)+'</span> · '+fmtTime(e.started)+(e.file?' · <code>'+esc(e.file)+'</code>':'')+'</div><pre class=dlg>'+esc(e.message||'')+'</pre>';
}
tick(); setInterval(tick,3000);
renderLive(); setInterval(renderLive,3000);
renderErrors(); setInterval(renderErrors,6000);
</script></body></html>"""


class H(BaseHTTPRequestHandler):
    def _send(self, code, body, ct):
        b = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        from urllib.parse import urlparse, parse_qs
        u = urlparse(self.path)
        p = u.path.rstrip("/")
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if p in ("", "/dash", "/dashboard"):
            return self._send(200, PAGE, "text/html; charset=utf-8")
        try:
            if p.endswith("/api/state"):
                return self._send(200, json.dumps(state()), "application/json")
            if p.endswith("/api/live"):            # in-flight ReAct transcripts, proxied from java-runner
                from urllib.parse import quote
                key = q.get("key", "")
                url = ("http://fsm-java-runner:8090/live/dialog?key=" + quote(key)) if key \
                    else "http://fsm-java-runner:8090/live/dialogs"
                try:
                    req = urllib.request.Request(url, headers={"User-Agent": "fsm"})
                    data = json.load(urllib.request.urlopen(req, timeout=3))
                except Exception:                  # a down runner degrades gracefully, never 500s the poller
                    data = {"dialog": "(runner unreachable)"} if key else {"dialogs": []}
                return self._send(200, json.dumps(data), "application/json")
            if p.endswith("/api/methods"):        # methods of one file (no dialog — light)
                c = conn()
                try:
                    rows = query_table(c, "method_runs", "file", q.get("file", ""))
                finally:
                    c.close()
                rows = [{k: v for k, v in r.items() if k != "dialog"} for r in rows]
                rows.sort(key=lambda r: r.get("method", ""))
                return self._send(200, json.dumps(rows), "application/json")
            if p.endswith("/api/dialog"):         # full ReAct transcript of one method
                c = conn()
                try:
                    rows = query_table(c, "method_runs", "method_key", q.get("key", ""))
                finally:
                    c.close()
                return self._send(200, json.dumps(rows[0] if rows else {"dialog": "(no transcript recorded)"}), "application/json")
            if p.endswith("/api/bug"):            # a proven/attempted bug's reproducer+fixer artifacts
                c = conn()
                try:
                    rows = query_table(c, "bugs", "suspicion_key", q.get("key", ""))
                finally:
                    c.close()
                return self._send(200, json.dumps(rows[0] if rows else {}), "application/json")
            if p.endswith("/api/errors"):          # recent pipeline execution failures (for debugging)
                return self._send(200, json.dumps(errors()), "application/json")
        except Exception as e:
            return self._send(500, json.dumps({"error": str(e)}), "application/json")
        self._send(404, "not found", "text/plain")

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8091"))
    print(f"dashboard on :{port}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()
