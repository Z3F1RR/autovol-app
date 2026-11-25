#!/usr/bin/env bash
# Installs pdm-probe: a tiny standalone HTTP service for the train modem app (real data-transfer
# check + speed test). Does NOT touch Xray, nginx, the bot or existing ports. Safe to re-run:
# keeps the same token and port, so the address in the app stays valid.
set -Eeuo pipefail

NAME=pdm-probe
TS=$(date +%Y%m%d-%H%M%S)
BK=/root/backups/$NAME-$TS
DIR=/opt/pdm-probe
UNIT=/etc/systemd/system/pdm-probe.service
ENVF=/etc/pdm-probe.env
LOG=/var/log/zfr-changes.log
mkdir -p "$BK"

command -v python3 >/dev/null || { echo "Нет python3 — установка отменена, ничего не изменено"; exit 1; }

# ---------- backup + rollback script
for f in "$DIR" "$UNIT" "$ENVF"; do
  if [ -e "$f" ]; then cp -a "$f" "$BK/"; fi
done
WAS_ENABLED=$(systemctl is-enabled pdm-probe 2>/dev/null || true)
WAS_ACTIVE=$(systemctl is-active pdm-probe 2>/dev/null || true)
cat > "$BK/rollback.sh" <<RB
#!/usr/bin/env bash
systemctl stop pdm-probe 2>/dev/null || true
systemctl disable pdm-probe 2>/dev/null || true
rm -rf "$DIR" "$UNIT" "$ENVF"
[ -e "$BK/pdm-probe" ] && cp -a "$BK/pdm-probe" "$DIR"
[ -e "$BK/pdm-probe.service" ] && cp -a "$BK/pdm-probe.service" "$UNIT"
[ -e "$BK/pdm-probe.env" ] && cp -a "$BK/pdm-probe.env" "$ENVF"
systemctl daemon-reload
[ "$WAS_ENABLED" = "enabled" ] && systemctl enable pdm-probe 2>/dev/null
[ "$WAS_ACTIVE" = "active" ] && systemctl start pdm-probe 2>/dev/null
echo "pdm-probe: откат выполнен"
RB
chmod +x "$BK/rollback.sh"

fail() {
  echo "ОШИБКА в строке $1 — откатываю изменения"
  bash "$BK/rollback.sh" || true
  echo "$(date -Is) $NAME FAIL line $1 backup=$BK" >> "$LOG"
  exit 1
}
trap 'fail $LINENO' ERR

# ---------- token and port: keep existing ones on re-run
TOKEN=""; PORT=""
if [ -f "$ENVF" ]; then
  TOKEN=$(sed -n 's/^PDM_TOKEN=//p' "$ENVF")
  PORT=$(sed -n 's/^PDM_PORT=//p' "$ENVF")
fi
[ -n "$TOKEN" ] || TOKEN=$(head -c 18 /dev/urandom | base64 | tr '+/' 'xy' | tr -d '=')
if [ -z "$PORT" ]; then
  for p in 8093 8094 18093 28093 38093; do
    if ! ss -Hltn "sport = :$p" | grep -q .; then PORT=$p; break; fi
  done
fi
[ -n "$PORT" ] || { echo "Нет свободного порта из списка"; false; }

# ---------- files
mkdir -p "$DIR"
cat > "$DIR/pdm_probe.py" <<'PYEOF'
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
PYEOF
python3 -m py_compile "$DIR/pdm_probe.py"
rm -rf "$DIR/__pycache__"

umask 077
printf 'PDM_TOKEN=%s\nPDM_PORT=%s\n' "$TOKEN" "$PORT" > "$ENVF"
umask 022

cat > "$UNIT" <<UEOF
[Unit]
Description=PDM probe (train internet check + speed test)
After=network-online.target
Wants=network-online.target

[Service]
EnvironmentFile=$ENVF
ExecStart=/usr/bin/python3 $DIR/pdm_probe.py
DynamicUser=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
MemoryMax=96M
CPUQuota=50%
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UEOF

# ---------- firewall: only if ufw is active, only our port
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
  if ! ufw status | grep -qE "^$PORT/tcp +ALLOW"; then
    ufw allow "$PORT/tcp" comment pdm-probe >/dev/null
    echo "ufw delete allow $PORT/tcp >/dev/null 2>&1 || true" >> "$BK/rollback.sh"
  fi
fi

# ---------- start + health check
systemctl daemon-reload
systemctl enable pdm-probe >/dev/null 2>&1
systemctl restart pdm-probe
OK=""
for i in $(seq 1 10); do
  sleep 2
  if systemctl is-active -q pdm-probe && curl -fsS -m 3 "http://127.0.0.1:$PORT/$TOKEN/ping?n=hc$i" 2>/dev/null | grep -q "pdm-ok hc$i"; then
    OK=1; break
  fi
done
[ -n "$OK" ] || { echo "Сервис не ответил за 20 секунд"; false; }
# echo check: hash of an uploaded body must come back
head -c 1024 /dev/urandom > "$BK/echo.bin"
curl -fsS -m 5 --data-binary @"$BK/echo.bin" -H 'Content-Type: application/octet-stream' -o "$BK/echo.out" "http://127.0.0.1:$PORT/$TOKEN/echo"
WANT=$(sha256sum "$BK/echo.bin" | awk '{print $1}')
grep -aq "^pdm-ok $WANT" "$BK/echo.out" || { echo "Проверка echo не прошла"; false; }
rm -f "$BK/echo.bin" "$BK/echo.out"
trap - ERR

IP=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") {print $(i+1); exit}}')
URL="http://$IP:$PORT/$TOKEN"
echo "$(date -Is) $NAME OK port=$PORT backup=$BK" >> "$LOG"
echo
echo "pdm-probe работает. Xray, nginx и бот не тронуты."
echo "Адрес для приложения (Настройки → Сервер проверки):"
echo
echo "$URL"
echo
echo "Бэкап: $BK"
echo "Откат: bash $BK/rollback.sh"
