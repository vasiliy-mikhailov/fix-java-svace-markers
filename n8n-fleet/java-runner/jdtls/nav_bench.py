#!/usr/bin/env python3
"""In-container jdtls bakeoff harness: run goto-definition / implementation on the 12 nav queries,
INSIDE fjb-java-runner (has JDK21 + the opennlp clone + internet). Mirrors the JavaParser bench so
both engines are measured in the SAME environment (no external SSH asymmetry).

Usage (inside the container):  python3 nav_bench.py /tmp/navq.json <repo-clone-dir>
Self-downloads jdtls into /cache/jdtls (persistent volume) on first run.
This is a DRAFT authored offline while the host was down — expect to iterate on jdtls quirks on-host.
"""
import subprocess, json, os, sys, threading, time, glob, urllib.request, tarfile, re

JAVA = "/opt/jdk/21/bin/java"
JDTLS_DIR = "/cache/jdtls"
WS = "/cache/jdtls-ws"
JDTLS_URL = "https://www.eclipse.org/downloads/download.php?file=/jdtls/snapshots/jdt-language-server-latest.tar.gz&r=1"


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def ensure_jdtls():
    if glob.glob(JDTLS_DIR + "/plugins/org.eclipse.equinox.launcher_*.jar"):
        return
    os.makedirs(JDTLS_DIR, exist_ok=True)
    tgz = "/tmp/jdtls.tar.gz"
    log("downloading jdtls ...")
    urllib.request.urlretrieve(JDTLS_URL, tgz)
    with tarfile.open(tgz) as t:
        t.extractall(JDTLS_DIR)
    log("jdtls extracted to", JDTLS_DIR)


class LSP:
    def __init__(self, proc):
        self.p = proc
        self.id = 0
        self.resp = {}
        self.notes = []
        self.lock = threading.Lock()
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        f = self.p.stdout
        while True:
            n = None
            while True:
                line = f.readline()
                if not line:
                    return
                if line in (b"\r\n", b"\n"):
                    break
                m = re.match(rb"Content-Length:\s*(\d+)", line, re.I)
                if m:
                    n = int(m.group(1))
            if n is None:
                continue
            body = f.read(n)
            try:
                msg = json.loads(body)
            except Exception:
                continue
            if "id" in msg and ("result" in msg or "error" in msg):
                with self.lock:
                    self.resp[msg["id"]] = msg
            elif "method" in msg:
                self.notes.append(msg)
                if "id" in msg:                     # server->client request: reply so jdtls proceeds
                    self._raw({"jsonrpc": "2.0", "id": msg["id"], "result": None})

    def _raw(self, msg):
        b = json.dumps(msg).encode()
        self.p.stdin.write(("Content-Length: %d\r\n\r\n" % len(b)).encode() + b)
        self.p.stdin.flush()

    def notify(self, method, params):
        self._raw({"jsonrpc": "2.0", "method": method, "params": params})

    def request(self, method, params, timeout=120):
        self.id += 1
        rid = self.id
        self._raw({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        t0 = time.time()
        while time.time() - t0 < timeout:
            with self.lock:
                if rid in self.resp:
                    return self.resp.pop(rid)
            time.sleep(0.03)
        return {"error": "timeout"}

    def wait_ready(self, timeout=420):
        t0 = time.time()
        while time.time() - t0 < timeout:
            for m in list(self.notes):
                if m.get("method") == "language/status":
                    if "ServiceReady" in json.dumps(m.get("params", {})):
                        return time.time() - t0
            time.sleep(0.5)
        return None


def uri(path):
    return "file://" + path


def main():
    qpath = sys.argv[1] if len(sys.argv) > 1 else "/tmp/navq.json"
    repo = sys.argv[2] if len(sys.argv) > 2 else None
    if not repo:  # discover the opennlp clone under the fs cache
        cands = glob.glob("/cache/fs/*/opennlp-api")
        repo = os.path.dirname(cands[0]) if cands else None
    if not repo or not os.path.isdir(repo):
        log("repo clone not found"); sys.exit(2)
    queries = json.load(open(qpath))
    ensure_jdtls()
    os.makedirs(WS, exist_ok=True)
    launcher = glob.glob(JDTLS_DIR + "/plugins/org.eclipse.equinox.launcher_*.jar")[0]
    config = JDTLS_DIR + "/config_linux"
    args = [JAVA, "-Declipse.application=org.eclipse.jdt.ls.core.id1",
            "-Dosgi.bundles.defaultStartLevel=4", "-Declipse.product=org.eclipse.jdt.ls.core.product",
            "-Dlog.level=ALL", "-Xmx2G", "--add-modules=ALL-SYSTEM",
            "--add-opens", "java.base/java.util=ALL-UNNAMED", "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "-jar", launcher, "-configuration", config, "-data", WS]
    log("launching jdtls ...")
    proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    lsp = LSP(proc)
    lsp.request("initialize", {"processId": os.getpid(), "rootUri": uri(repo),
                               "workspaceFolders": [{"uri": uri(repo), "name": "opennlp"}],
                               "capabilities": {"textDocument": {"definition": {}, "implementation": {}}}}, timeout=120)
    lsp.notify("initialized", {})
    t0 = time.time()
    ready = lsp.wait_ready(420)
    setup = round(time.time() - t0, 1)
    log("ServiceReady after", setup, "s (ready-signal=%s)" % (ready is not None))

    print("id                         | jdtls resolved                                   | expected")
    for q in queries:
        fpath = os.path.join(repo, q["file"])
        try:
            content = open(fpath, errors="replace").read()
        except Exception as e:
            print(q["id"][:26].ljust(26), "| OPEN-ERR", str(e)[:40]); continue
        furi = uri(fpath)
        lsp.notify("textDocument/didOpen", {"textDocument": {"uri": furi, "languageId": "java", "version": 1, "text": content}})
        time.sleep(0.3)
        col = max(0, (q.get("source_line", "") or "").find(q["symbol"]))
        pos = {"line": int(q["line"]) - 1, "character": col + 1}
        t1 = time.time()
        r = lsp.request("textDocument/definition", {"textDocument": {"uri": furi}, "position": pos}, timeout=40)
        ms = int((time.time() - t1) * 1000)
        res = r.get("result")
        loc = res[0] if isinstance(res, list) and res else (res if isinstance(res, dict) else None)
        if loc:
            turi = loc.get("uri") or loc.get("targetUri") or ""
            rng = loc.get("range") or loc.get("targetSelectionRange") or {}
            ln = rng.get("start", {}).get("line", -1) + 1
            short = turi.split("/")[-1]
            ext = "jrt:" in turi or "jdk" in turi.lower() or ".jar" in turi
            got = ("EXTERNAL " if ext else "") + short + ":" + str(ln)
        else:
            got = "UNRESOLVED"
        print(q["id"][:26].ljust(26), "|", got[:48].ljust(48), "|", q["expected_def_symbol"].split("(")[0][:30], "[%dms]" % ms)
        lsp.notify("textDocument/didClose", {"textDocument": {"uri": furi}})
    proc.terminate()
    print("SETUP_SECONDS", setup)


if __name__ == "__main__":
    main()
