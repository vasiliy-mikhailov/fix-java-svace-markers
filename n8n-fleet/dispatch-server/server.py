#!/usr/bin/env python3
"""Tiny HTTP wrapper around dispatch.sh, so n8n can trigger a fleet top-up with a
plain HTTP Request node (no shell/runner needed inside n8n). GET / runs one
dispatch tick and returns its output."""
import http.server
import subprocess


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            out = subprocess.run(["sh", "/data/dispatch.sh"], capture_output=True, text=True, timeout=180)
            body = out.stdout + out.stderr
        except Exception as e:  # noqa: BLE001
            body = f"dispatch error: {e}\n"
        data = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):  # quiet
        pass


if __name__ == "__main__":
    http.server.HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
