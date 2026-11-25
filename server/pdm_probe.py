#!/usr/bin/env python3
"""PDM probe: proves that data really flows both ways between the train and this server.

All paths start with a secret token, everything else gets 404.
  POST /<token>/echo   body ≤ 64 KB  -> "pdm-ok <sha256 of body>\n" + 2 KB random bytes
  GET  /<token>/ping?n=<nonce>        -> "pdm-ok <nonce>\n"  (for a browser check)
  GET  /<token>/down?mb=N  (N ≤ 32)   -> N MB of incompressible data (speed test)
  POST /<token>/up         (≤ 64 MB)  -> "pdm-up <bytes received>" (speed test)
Python standard library only. Settings come from the environment: PDM_TOKEN, PDM_PORT.
"""
import hashlib
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

TOKEN = os.environ["PDM_TOKEN"]
PORT = int(os.environ.get("PDM_PORT", "8093"))
CHUNK = os.urandom(64 * 1024)
MB = 1024 * 1024


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # keep-alive: the phone reuses one connection between checks
    server_version = "pdm"
    sys_version = ""
    timeout = 30

    def log_message(self, *args):
        pass

    def reply(self, code, body=b"", ctype="text/plain; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def route(self):
        u = urlparse(self.path)
        parts = u.path.strip("/").split("/")
        if len(parts) != 2 or parts[0] != TOKEN:
            return None, {}
        return parts[1], {k: v[0] for k, v in parse_qs(u.query).items()}

    def length(self, limit):
        n = int(self.headers.get("Content-Length") or 0)
        if n < 0 or n > limit:
            raise ValueError("body too large")
        return n

    def do_GET(self):
        name, q = self.route()
        if name == "ping":
            nonce = re.sub(r"[^A-Za-z0-9_-]", "", q.get("n", ""))[:64]
            self.reply(200, ("pdm-ok %s\n" % nonce).encode())
        elif name == "down":
            mb = max(1, min(32, int(q.get("mb", "8") or 8)))
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(mb * MB))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            for _ in range(mb * MB // len(CHUNK)):
                self.wfile.write(CHUNK)
        else:
            self.reply(404)

    def do_POST(self):
        try:
            self.post()
        except ValueError:
            self.close_connection = True
            self.reply(413)

    def post(self):
        name, _ = self.route()
        if name == "echo":
            body = self.rfile.read(self.length(64 * 1024))
            self.reply(200, ("pdm-ok %s\n" % hashlib.sha256(body).hexdigest()).encode() + os.urandom(2048),
                       "application/octet-stream")
        elif name == "up":
            left = self.length(64 * MB)
            got = 0
            while left > 0:
                b = self.rfile.read(min(left, 256 * 1024))
                if not b:
                    break
                got += len(b)
                left -= len(b)
            self.reply(200, ("pdm-up %d\n" % got).encode())
        else:
            # unknown path: drain a small body so keep-alive stays in sync, then 404
            self.rfile.read(self.length(64 * 1024))
            self.reply(404)


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.daemon_threads = True
    srv.serve_forever()
