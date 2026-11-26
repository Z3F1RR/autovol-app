#!/bin/sh
# Installs or updates pdm-agent on the Keenetic router (Entware on a USB drive).
# Safe to re-run: keeps the settings, token and collected statistics; rolls back if the new
# version does not answer within 20 seconds.
set -e

DIR=/opt/pdm
INIT=/opt/etc/init.d/S99pdm-agent
TS=$(date +%Y%m%d-%H%M%S)
BK=$DIR/backup-$TS
PORT=8099

[ -x /opt/bin/python3 ] || { echo "Нет python3. Сначала: opkg update && opkg install python3 ca-certificates"; exit 1; }
mkdir -p "$DIR/data" "$BK"

# ---------- backup
[ -f "$DIR/pdm_agent.py" ] && cp -a "$DIR/pdm_agent.py" "$BK/"
[ -f "$INIT" ] && cp -a "$INIT" "$BK/"
cat > "$BK/rollback.sh" <<RB
#!/bin/sh
$INIT stop 2>/dev/null
[ -f "$BK/pdm_agent.py" ] && cp -a "$BK/pdm_agent.py" "$DIR/pdm_agent.py"
[ -f "$BK/S99pdm-agent" ] && cp -a "$BK/S99pdm-agent" "$INIT"
[ -f "$BK/S99pdm-agent" ] && $INIT start
echo "pdm-agent: откат выполнен"
RB
chmod +x "$BK/rollback.sh"

# ---------- agent
cat > "$DIR/pdm_agent.py.new" <<'PYEOF'
#!/opt/bin/python3
"""PDM agent: runs on the train's Keenetic router (Entware) around the clock.

Does what used to depend on the phone being in the car with the app alive:
  * polls both Huawei HiLink modems and the router (RCI on localhost:79, no login needed there);
  * counts traffic per day per SIM (modem counters) and per device (router's traffic monitor);
  * keeps the data-package balance of each SIM, adds the package on the payment day;
  * picks the main channel (better signal, then more data left), wakes an idle SIM before the
    operator blocks it, reconnects/reboots a modem that lost the network;
  * checks the internet from the router itself (no phone VPN in the way);
  * serves JSON for the phone app (/api/..., token protected) and a status page for passengers (/).

Python standard library only. Settings: /opt/pdm/config.json. Data: /opt/pdm/data/.
"""
import hashlib
import json
import os
import re
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1"
BASE = os.environ.get("PDM_HOME", "/opt/pdm")
DATA = os.path.join(BASE, "data")
CONFIG = os.path.join(BASE, "config.json")
RCI = os.environ.get("PDM_RCI", "http://127.0.0.1:79")

DEFAULTS = {
    "port": 8099,
    "token": "",
    "interval": 10,
    "modems": [{"name": "МегаФон", "ip": "192.168.8.1"}, {"name": "МТС", "ip": "192.168.9.1"}],
    "server": "",
    "auto_main": True,
    "heal": True,
    "auto_reg": True,
}

lock = threading.RLock()
log_lines = []


def log(msg):
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + msg
    print(line, flush=True)
    with lock:
        log_lines.append(line)
        del log_lines[:-200]


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def save_json(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    os.replace(tmp, path)


def config():
    c = dict(DEFAULTS)
    c.update(load_json(CONFIG, {}))
    return c


def update_config(changes):
    with lock:
        c = load_json(CONFIG, {})
        c.update(changes)
        save_json(CONFIG, c)


# ------------------------------------------------------------------ HTTP helpers

def _ctx():
    for ca in ("/opt/etc/ssl/certs/ca-certificates.crt", "/etc/ssl/certs/ca-certificates.crt"):
        if os.path.exists(ca):
            return ssl.create_default_context(cafile=ca)
    return ssl.create_default_context()


SSL = _ctx()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


OPENER = urllib.request.build_opener(NoRedirect, urllib.request.HTTPSHandler(context=SSL))


def http(url, data=None, headers=None, timeout=5, method=None, limit=None):
    """(status, body bytes, headers). HTTP errors return their status instead of raising."""
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    req.add_header("User-Agent", "PdmAgent")
    try:
        with OPENER.open(req, timeout=timeout) as r:
            return r.status, (r.read(limit) if limit else r.read()), r.headers
    except urllib.error.HTTPError as e:
        try:
            body = e.read()
        except Exception:
            body = b""
        return e.code, body, e.headers


# ------------------------------------------------------------------ Keenetic RCI

def rci_get(path, timeout=8):
    code, body, _ = http(RCI + path, timeout=timeout)
    if code != 200:
        raise RuntimeError("RCI %s: HTTP %s" % (path, code))
    return json.loads(body.decode("utf-8") or "null")


def rci_cli(*commands):
    """Runs CLI commands one by one; returns the first error message or None."""
    err = None
    for c in commands:
        body = json.dumps([{"parse": c}]).encode()
        code, out, _ = http(RCI + "/rci/", data=body, headers={"Content-Type": "application/json"}, timeout=15)
        if code != 200:
            err = err or "%s: HTTP %s" % (c, code)
            continue
        m = re.search(r'"status":\s*"error"[^}]*?"message":\s*"([^"]*)"', out.decode("utf-8", "replace"))
        if m:
            err = err or m.group(1)
    return err


def truthy(v):
    if isinstance(v, bool):
        return v
    return str(v).lower() in ("yes", "true", "up", "1")


def subnet(ip):
    return ip.rsplit(".", 1)[0] if "." in ip else ""


# ------------------------------------------------------------------ Huawei HiLink

def tag(xml, name):
    m = re.search("<%s>([^<]*)</%s>" % (name, name), xml)
    return m.group(1) if m else None


def num(s):
    if s is None:
        return None
    m = re.search(r"-?\d+", s)
    return int(m.group(0)) if m else None


NET = {"0": "нет сети", "1": "2G", "2": "2G", "3": "2G", "4": "3G", "5": "3G", "6": "3G", "7": "3G",
       "9": "3G+", "17": "3G+", "18": "3G+", "19": "4G", "41": "3G", "44": "3G+", "45": "3G+", "46": "3G+",
       "61": "3G", "62": "3G+", "63": "3G+", "64": "3G+", "65": "3G+", "101": "4G", "1011": "4G+"}


class HiLink:
    def __init__(self, ip):
        self.ip = ip

    def _get(self, path, cookie=None):
        h = {"Cookie": cookie} if cookie else {}
        code, body, _ = http("http://%s%s" % (self.ip, path), headers=h, timeout=4)
        return body.decode("utf-8", "replace")

    def token(self):
        x = self._get("/api/webserver/SesTokInfo")
        return tag(x, "SesInfo"), tag(x, "TokInfo")

    def _post(self, path, inner):
        cookie, tok = self.token()
        h = {"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"}
        if cookie:
            h["Cookie"] = cookie
        if tok:
            h["__RequestVerificationToken"] = tok
        body = ('<?xml version="1.0" encoding="UTF-8"?><request>%s</request>' % inner).encode()
        code, out, _ = http("http://%s%s" % (self.ip, path), data=body, headers=h, timeout=6)
        x = out.decode("utf-8", "replace")
        if "<error>" in x:
            raise RuntimeError("модем: ошибка %s" % tag(x, "code"))
        return x

    def read(self):
        cookie, _ = self.token()

        def g(p):
            try:
                x = self._get(p, cookie)
                return "" if "<error>" in x else x
            except Exception:
                return ""
        sig, st = g("/api/device/signal"), g("/api/monitoring/status")
        plmn, tr = g("/api/net/current-plmn"), g("/api/monitoring/traffic-statistics")
        nt = tag(st, "CurrentNetworkTypeEx") or tag(st, "CurrentNetworkType") or ""
        dl, ul = num(tag(tr, "TotalDownload")), num(tag(tr, "TotalUpload"))
        return {
            "ok": True,
            "operator": tag(plmn, "ShortName") or tag(plmn, "FullName") or "",
            "net": NET.get(nt, nt or "?"),
            "connected": tag(st, "ConnectionStatus") == "901",
            "rsrp": num(tag(sig, "rsrp")), "sinr": num(tag(sig, "sinr")),
            "band": tag(sig, "band") or "", "cell": tag(sig, "cell_id") or "",
            "dl_mbps": (num(tag(tr, "CurrentDownloadRate")) or 0) * 8 / 1e6,
            "total": (dl + (ul or 0)) if dl is not None else None,
        }

    def reconnect(self):
        self._post("/api/dialup/mobile-dataswitch", "<dataswitch>0</dataswitch>")
        time.sleep(2)
        self._post("/api/dialup/mobile-dataswitch", "<dataswitch>1</dataswitch>")

    def reboot(self):
        self._post("/api/device/control", "<Control>1</Control>")


# ------------------------------------------------------------------ state

class State:
    def __init__(self):
        self.modems = [None, None]
        self.hist = [[], []]          # (t, rsrp, sinr), 3 hours
        self.channels = []
        self.clients = []
        self.unknown_limit = None
        self.net = None               # FULL / WHITELIST / SLOW / NONE
        self.net_since = time.time()
        self.latency = None
        self.why = ""
        self.recent = []
        self.events = load_json(os.path.join(DATA, "events.json"), [])
        self.router_error = None
        self.updated = 0

    def event(self, text):
        log(text)
        with lock:
            self.events.append({"t": int(time.time()), "text": text})
            del self.events[:-200]
            save_json(os.path.join(DATA, "events.json"), self.events)


S = State()


# ------------------------------------------------------------------ traffic statistics and packages

class Traffic:
    """Same model as the phone app: bytes per day per SIM and per device, package balance per SIM."""

    def __init__(self):
        self.path = os.path.join(DATA, "traffic.json")
        self.qpath = os.path.join(DATA, "quota.json")
        self.r = load_json(self.path, {})
        for k in ("days", "names", "lastD"):
            self.r.setdefault(k, {})
        self.r.setdefault("lastM", [-1, -1])
        self.q = load_json(self.qpath, {})
        self.dirty = False
        self.saved = 0

    @staticmethod
    def delta(cur, last):
        if last is None or last < 0:
            return 0
        d = cur - last if cur >= last else cur
        return d if 0 <= d <= 2_000_000_000 else 0

    def today(self):
        k = date.today().isoformat()
        return self.r["days"].setdefault(k, {"m": [0, 0], "d": {}})

    def add_modem(self, i, counter):
        with lock:
            d = self.delta(counter, self.r["lastM"][i])
            self.r["lastM"][i] = counter
            if d:
                self.today()["m"][i] += d
                q = self.q.get(str(i))
                if q:
                    q["used"] = q.get("used", 0) + d
                    q["used_all"] = q.get("used_all", 0) + d
            self.dirty = True

    def add_devices(self, clients):
        with lock:
            t = self.today()["d"]
            for c in clients:
                cur = c["down"] + c["up"]
                if cur <= 0:
                    continue
                d = self.delta(cur, self.r["lastD"].get(c["mac"], -1))
                self.r["lastD"][c["mac"]] = cur
                self.r["names"][c["mac"]] = c["name"]
                if d:
                    t[c["mac"]] = t.get(c["mac"], 0) + d
            self.dirty = True

    @staticmethod
    def last_renewal(day):
        today = date.today()

        def on(y, m):
            import calendar
            return date(y, m, min(day, calendar.monthrange(y, m)[1]))
        d = on(today.year, today.month)
        if d > today:
            y, m = (today.year, today.month - 1) if today.month > 1 else (today.year - 1, 12)
            d = on(y, m)
        return datetime(d.year, d.month, d.day).timestamp()

    def renew(self):
        with lock:
            for i, q in self.q.items():
                day = q.get("day", 0)
                if not 1 <= day <= 31:
                    continue
                at = self.last_renewal(day)
                if q.get("renew", 0) < at:
                    left = q["left"] - q.get("used", 0)
                    pack = q.get("pack", 100) * 1_000_000_000
                    q["left"] = (max(0, left) + pack) if q.get("roll") else pack
                    q["used"] = 0
                    q["renew"] = at
                    self.dirty = True
                    S.event("Пакет SIM %s пополнен до %.0f ГБ" % (int(i) + 1, q["left"] / 1e9))

    def set_quota(self, i, left_gb, pack, day, roll):
        now = time.time()
        with lock:
            self.q[str(i)] = {"left": int(left_gb * 1e9), "used": 0, "used_all": 0, "pack": int(pack),
                              "day": int(day), "roll": bool(roll), "set": now, "renew": now}
            self.dirty = True
        self.flush(force=True)

    def quotas(self):
        out = {}
        with lock:
            for i, q in self.q.items():
                out[i] = {"left": q["left"] - q.get("used", 0), "pack": q.get("pack", 100), "day": q.get("day", 0),
                          "roll": q.get("roll", False), "set": q.get("set", 0), "used_all": q.get("used_all", 0)}
        return out

    def flush(self, force=False):
        now = time.time()
        with lock:
            if not force and (not self.dirty or now - self.saved < 60):
                return
            oldest = (date.today() - timedelta(days=70)).isoformat()
            for k in [k for k in self.r["days"] if k < oldest]:
                del self.r["days"][k]
            save_json(self.path, self.r)
            save_json(self.qpath, self.q)
            self.dirty = False
            self.saved = now

    def view(self):
        """Last 30 days, month total, forecast, per-SIM and per-device numbers (same as the app shows)."""
        with lock:
            days = self.r["days"]
            today = date.today()
            rows = []
            for n in range(29, -1, -1):
                k = (today - timedelta(days=n)).isoformat()
                d = days.get(k, {"m": [0, 0], "d": {}})
                rows.append({"date": k, "m": list(d["m"]), "d": dict(d["d"])})
            month = today.isoformat()[:7]
            mrows = [v for k, v in days.items() if k.startswith(month)]
            by_modem = [sum(r["m"][0] for r in mrows), sum(r["m"][1] for r in mrows)]
            full = [r for r in rows[:-1][-7:] if sum(r["m"])]
            now = datetime.now()
            frac = (now.hour * 60 + now.minute) / 1440.0

            def avg(i):
                if full:
                    return sum(r["m"][i] for r in full) // len(full)
                t = rows[-1]["m"][i]
                return int(t / frac) if frac > 0.1 else t
            per = [avg(0), avg(1)]
            import calendar
            dim = calendar.monthrange(today.year, today.month)[1]
            left_days = dim - today.day
            forecast = sum(by_modem) + int(sum(per) * (1 - frac)) + sum(per) * left_days
            dev = {}
            for r in mrows:
                for mac, b in r["d"].items():
                    dev[mac] = dev.get(mac, 0) + b
            names = self.r["names"]
            devices = [{"mac": m, "name": names.get(m, m.upper()), "month": b, "today": rows[-1]["d"].get(m, 0)}
                       for m, b in sorted(dev.items(), key=lambda x: -x[1])]
            return {"days": [{"date": r["date"], "m": r["m"]} for r in rows], "month": sum(by_modem),
                    "by_modem": by_modem, "forecast": forecast, "per_day": per, "days_left": left_days,
                    "devices": devices}


T = None


# ------------------------------------------------------------------ polling

def poll_modems(cfg):
    now = time.time()
    for i, m in enumerate(cfg["modems"][:2]):
        try:
            st = HiLink(m["ip"]).read()
        except Exception as e:
            st = {"ok": False, "error": type(e).__name__}
        S.modems[i] = st
        if st.get("ok") and st.get("total") is not None:
            T.add_modem(i, st["total"])
        if st.get("ok") and st.get("connected"):
            S.hist[i].append((now, st.get("rsrp"), st.get("sinr")))
        S.hist[i] = [p for p in S.hist[i] if now - p[0] < 3 * 3600]


limits_cache = {"t": 0, "hosts": {}, "unknown": None}
prev_router = {"t": 0, "ch": {}, "cl": {}}
reg_tried = {}


def read_limits():
    if time.time() - limits_cache["t"] < 60:
        return limits_cache["hosts"], limits_cache["unknown"]
    try:
        code, body, _ = http(RCI + "/rci/show/running-config", timeout=10)
        cfg_text = body.decode("utf-8", "replace")
    except Exception:
        cfg_text = ""

    def lim(d, u):
        return {"down": int(d), "up": int(u or d)}
    hosts = {m.group(1).lower(): lim(m.group(2), m.group(3)) for m in
             re.finditer(r"ip traffic-shape host ([0-9a-fA-F:]{17}) rate (\d+)(?: asymmetric (\d+))?", cfg_text)}
    m = re.search(r"ip traffic-shape unknown-host rate (\d+)(?: asymmetric (\d+))?", cfg_text)
    unknown = lim(m.group(1), m.group(2)) if m else None
    limits_cache.update(t=time.time(), hosts=hosts, unknown=unknown)
    return hosts, unknown


def summary(attr):
    try:
        j = rci_get("/rci/show/ip/hotspot/summary?attribute=%s&detail=0&count=500" % attr)
        return {h["mac"].lower(): int(h.get(attr, 0)) for h in j.get("host", []) if h.get("mac")}
    except Exception:
        return {}


def host_name(c):
    n = re.sub(r"[^0-9A-Za-z_-]", "", c["name"])[:20]
    tail = c["mac"].replace(":", "")[-4:]
    return "%s-%s" % (n, tail) if n else "pdm-" + tail


def poll_router(cfg):
    now = time.time()
    dt = now - prev_router["t"] if prev_router["t"] else None

    def rate(cur, old):
        if dt and old is not None and cur >= old and 1 <= dt <= 600:
            return (cur - old) * 8 / 1e6 / dt
        return None

    ifs = rci_get("/rci/show/interface")
    channels = []
    for name, o in (ifs or {}).items():
        if not isinstance(o, dict):
            continue
        addr = o.get("address", "")
        idx = next((i for i, m in enumerate(cfg["modems"][:2]) if addr and subnet(m["ip"]) == subnet(addr)), -1)
        if idx < 0 and "usb" not in (o.get("type", "") + name).lower():
            continue
        iface = o.get("id", name)
        try:
            st = rci_get("/rci/show/interface/stat?name=" + iface)
        except Exception:
            st = {}
        rx, tx = int(st.get("rxbytes", 0) or 0), int(st.get("txbytes", 0) or 0)
        old = prev_router["ch"].get(iface)
        try:
            prio = int(o.get("priority"))
        except (TypeError, ValueError):
            prio = None
        channels.append({"iface": iface, "modem": idx,
                         "connected": truthy(o.get("connected")) or (o.get("state") == "up" and o.get("link") == "up"),
                         "main": truthy(o.get("defaultgw")), "priority": prio, "rx": rx, "tx": tx,
                         "down_mbps": rate(rx, old and old[0]), "up_mbps": rate(tx, old and old[1])})
    channels.sort(key=lambda c: c["modem"] if c["modem"] >= 0 else 99)

    hosts, unknown = read_limits()
    hs = (rci_get("/rci/show/ip/hotspot") or {}).get("host", [])
    srx, stx = summary("rxbytes"), summary("txbytes")
    raws = []
    for h in hs:
        mac = (h.get("mac") or "").lower()
        if not mac:
            continue
        rx, tx = int(h.get("rxbytes", 0) or 0), int(h.get("txbytes", 0) or 0)
        if rx + tx == 0:
            rx, tx = srx.get(mac, 0), stx.get(mac, 0)
        raws.append((h, mac, rx, tx))
    rx_down = sum(r[2] for r in raws) > sum(r[3] for r in raws)
    clients, register = [], []
    for h, mac, rx, tx in raws:
        active, registered = truthy(h.get("active")), truthy(h.get("registered"))
        down, up = (rx, tx) if rx_down else (tx, rx)
        if not active and down + up == 0:
            continue
        old = prev_router["cl"].get(mac)
        c = {"mac": mac, "name": h.get("name") or h.get("hostname") or mac.upper(), "ip": h.get("ip", ""),
             "active": active, "registered": registered, "down": down, "up": up,
             "down_mbps": rate(down, old and old[0]) if active else None,
             "up_mbps": rate(up, old and old[1]) if active else None,
             "limit": hosts.get(mac) or (None if registered else unknown)}
        clients.append(c)
        if active and not registered:
            register.append(c)
    clients.sort(key=lambda c: (not c["active"], -(c["down_mbps"] or 0), -c["down"]))

    # one attempt per device per 10 minutes: if the router refuses, don't hammer it every poll
    register = [c for c in register if now - reg_tried.get(c["mac"], 0) > 600]
    if cfg["auto_reg"] and register:
        cmds = []
        for c in register[:5]:
            reg_tried[c["mac"]] = now
            cmds.append("known host %s %s" % (host_name(c), c["mac"]))
            if unknown:
                cmds.append("ip traffic-shape host %s rate %d%s" % (
                    c["mac"], unknown["down"], " asymmetric %d" % unknown["up"] if unknown["up"] != unknown["down"] else ""))
        rci_cli(*cmds)
        rci_cli("system configuration save")
        limits_cache["t"] = 0
        S.event("Зарегистрированы устройства: " + ", ".join(c["name"] for c in register[:5]))

    prev_router.update(t=now, ch={c["iface"]: (c["rx"], c["tx"]) for c in channels},
                       cl={c["mac"]: (c["down"], c["up"]) for c in clients})
    S.channels, S.clients, S.unknown_limit = channels, clients, unknown
    T.add_devices(clients)


# ------------------------------------------------------------------ internet check from the router

FULL_URLS = ["https://www.google.com/generate_204", "https://cp.cloudflare.com/generate_204"]
WL_URLS = ["https://ya.ru/", "https://vk.com/"]
SLOW_MS = 1500
bulk = {"t": 0, "err": None}


def probe(url, expect204):
    t = time.time()
    try:
        code, _, _ = http(url, timeout=5, method="GET" if expect204 else "HEAD")
        ok = code == 204 if expect204 else 100 <= code <= 599
        return (int((time.time() - t) * 1000), None) if ok else (None, "HTTP %s" % code)
    except Exception as e:
        return None, type(e).__name__


def echo(base):
    body = os.urandom(1024)
    head = ("pdm-ok %s\n" % hashlib.sha256(body).hexdigest()).encode()
    t = time.time()
    try:
        code, out, _ = http(base + "/echo", data=body, headers={"Content-Type": "application/octet-stream"}, timeout=6)
        if code == 200 and out.startswith(head) and len(out) >= len(head) + 2048:
            return int((time.time() - t) * 1000), None
        return None, "HTTP %s" % code
    except Exception as e:
        return None, type(e).__name__


def bulk64(base):
    """Frozen-path guard: 64 KB must come through whole within 8 s."""
    t = time.time()
    try:
        req = urllib.request.Request(base + "/down?mb=1", headers={"User-Agent": "PdmAgent"})
        got = 0
        with OPENER.open(req, timeout=4) as r:
            while got < 65536 and time.time() - t < 8:
                b = r.read(16384)
                if not b:
                    break
                got += len(b)
        return None if got >= 65536 else "загрузка 64 КБ встала на %d КБ" % (got // 1024)
    except Exception as e:
        return "загрузка 64 КБ: " + type(e).__name__


def check_net(cfg):
    server = cfg.get("server", "").rstrip("/")
    errs, raw, ms = [], "NONE", None
    if server:
        ms, e = echo(server)
        if ms is not None:
            raw = "FULL"
        else:
            errs.append("сервер: %s" % e)
    if raw != "FULL":
        for u in FULL_URLS:
            ms, e = probe(u, True)
            if ms is not None:
                raw = "FULL"
                break
            errs.append("%s: %s" % (u.split("/")[2], e))
    if raw != "FULL":
        for u in WL_URLS:
            ms, e = probe(u, False)
            if ms is not None:
                raw = "WHITELIST"
                break
            errs.append("%s: %s" % (u.split("/")[2], e))
    now = time.time()
    if raw == "FULL" and server and now - bulk["t"] >= 60:
        bulk.update(t=now, err=bulk64(server))
    if raw != "FULL":
        bulk["err"] = None
    if raw == "FULL" and bulk["err"]:
        raw, errs = "SLOW", [bulk["err"]]

    S.recent = (S.recent + [(raw, ms)])[-4:]
    last2 = S.recent[-2:]

    def fast(p):
        return p[1] is not None and p[1] < SLOW_MS
    if len(last2) < 2:
        cand = "SLOW" if raw == "FULL" and not fast((raw, ms)) else raw
    elif all(p[0] == "NONE" for p in last2):
        cand = "NONE"
    elif all(p[0] == "FULL" and fast(p) for p in last2):
        cand = "FULL"
    elif all(p[0] == "WHITELIST" and fast(p) for p in last2):
        cand = "WHITELIST"
    else:
        cand = "SLOW"
    if cand != S.net:
        if S.net is not None:
            S.event({"FULL": "Интернет есть", "WHITELIST": "Только белые списки", "SLOW": "Плохая связь",
                     "NONE": "Нет интернета"}[cand])
        S.net, S.net_since = cand, now
    S.latency = ms if ms is not None else S.latency
    S.why = "\n".join(errs) if cand in ("NONE", "SLOW") else ""


# ------------------------------------------------------------------ automation

auto = {"better": 0, "switch": 0, "main_at": load_json(os.path.join(DATA, "main_at.json"), {})}
heal_st = [{"bad": 0, "step": 0, "at": 0}, {"bad": 0, "step": 0, "at": 0}]


def score(i, now):
    pts = [p for p in S.hist[i] if now - p[0] < 180]
    sinr = [p[2] for p in pts if p[2] is not None]
    if not sinr:
        return None
    rsrp = [p[1] for p in pts if p[1] is not None]
    r = sum(rsrp) / len(rsrp) if rsrp else -110
    return sum(sinr) / len(sinr) + max(-6, min(6, (r + 105) / 4))


def make_main(iface):
    top = max((c["priority"] for c in S.channels if c["priority"] is not None), default=None)
    if top is None:
        raise RuntimeError("роутер не сообщает приоритеты")
    err = rci_cli("interface %s ip global %d" % (iface, top + 1), "system configuration save")
    if err:
        raise RuntimeError(err)


def auto_main(cfg):
    now = time.time()
    main = next((c for c in S.channels if c["main"] and c["modem"] in (0, 1)), None)
    if not main:
        return
    mi = main["modem"]
    auto["main_at"][str(mi)] = now
    oi = 1 - mi
    auto["main_at"].setdefault(str(oi), now)
    if now - auto.get("saved", 0) > 300:
        save_json(os.path.join(DATA, "main_at.json"), auto["main_at"])
        auto["saved"] = now
    if not cfg["auto_main"]:
        auto["better"] = 0
        return
    other = next((c for c in S.channels if c["modem"] == oi), None)
    mo = S.modems[oi] or {}
    if not other or not other["connected"] or not mo.get("ok") or not mo.get("connected"):
        auto["better"] = 0
        return
    sm, so = score(mi, now), score(oi, now)
    if so is None:
        return
    sm = -100 if sm is None else sm
    q = T.quotas()
    qm, qo = q.get(str(mi)), q.get(str(oi))
    frac = lambda x: x["left"] / (x["pack"] * 1e9) if x["pack"] else 1
    reason = None
    if so - sm >= 6:
        reason = "сигнал лучше"
    elif qm and qo and qm["left"] < 1e9 < qo["left"]:
        reason = "на основной SIM кончается пакет"
    elif abs(so - sm) < 3 and qm and qo and frac(qo) - frac(qm) > 0.2:
        reason = "там больше осталось трафика"
    elif now - auto["main_at"][str(oi)] > 36 * 3600 and so > sm - 6:
        reason = "SIM давно не работала, оператор может её заблокировать"
    if not reason:
        auto["better"] = 0
        return
    auto["better"] = auto["better"] or now
    if now - auto["better"] < 180 or now - auto["switch"] < 1200:
        return
    make_main(other["iface"])
    auto.update(switch=now, better=0)
    S.event("Основной канал: %s (%s)" % (cfg["modems"][oi]["name"], reason))


def heal(cfg):
    if not cfg["heal"]:
        return
    now = time.time()
    for i, m in enumerate(S.modems[:2]):
        if not m or not m.get("ok"):
            continue
        h = heal_st[i]
        if m.get("connected"):
            h.update(bad=0, step=0)
            continue
        h["bad"] = h["bad"] or now
        bad = now - h["bad"]
        name = cfg["modems"][i]["name"]
        try:
            if h["step"] == 0 and bad >= 90:
                h.update(step=1, at=now)
                HiLink(cfg["modems"][i]["ip"]).reconnect()
                S.event("%s без сети — переподключил" % name)
            elif h["step"] == 1 and bad >= 300:
                h.update(step=2, at=now)
                HiLink(cfg["modems"][i]["ip"]).reboot()
                S.event("%s без сети — перезагрузил" % name)
            elif h["step"] == 2 and now - h["at"] >= 1800:
                h.update(step=0, bad=now)
        except Exception as e:
            log("heal %s: %s" % (name, e))


# ------------------------------------------------------------------ loops

def guarded(name, fn, *a):
    try:
        fn(*a)
    except Exception as e:
        log("%s: %s: %s" % (name, type(e).__name__, e))
        if name == "router":
            S.router_error = str(e)
        return False
    if name == "router":
        S.router_error = None
    return True


def main_loop():
    while True:
        cfg = config()
        t = time.time()
        guarded("modems", poll_modems, cfg)
        if guarded("router", poll_router, cfg):
            guarded("auto_main", auto_main, cfg)
        guarded("heal", heal, cfg)
        T.renew()
        T.flush()
        S.updated = time.time()
        time.sleep(max(2, cfg["interval"] - (time.time() - t)))


def net_loop():
    while True:
        cfg = config()
        guarded("net", check_net, cfg)
        last2 = S.recent[-2:]
        time.sleep(3 if len(last2) == 2 and last2[0][0] != last2[1][0] else cfg["interval"])


# ------------------------------------------------------------------ HTTP: API for the app, page for passengers

def state_json():
    cfg = config()
    return {
        "agent": VERSION, "time": int(time.time()), "updated": int(S.updated),
        "net": S.net, "net_since": int(S.net_since), "latency": S.latency, "why": S.why,
        "modems": S.modems, "channels": S.channels, "clients": S.clients, "unknown_limit": S.unknown_limit,
        "router_error": S.router_error, "quotas": T.quotas(), "stats": T.view(),
        "events": S.events[-50:], "settings": {k: cfg[k] for k in ("auto_main", "heal", "auto_reg")},
    }


PAGE = """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="30">
<title>Интернет в вагоне</title><style>
body{margin:0;font-family:system-ui,sans-serif;background:#0a0b0d;color:#f3f4f6;display:flex;min-height:100vh;align-items:center;justify-content:center}
.c{max-width:420px;padding:28px}.s{font-size:34px;font-weight:600;margin:8px 0;color:%(color)s}.d{color:#9ca3af;line-height:1.5}
</style></head><body><div class="c"><div class="d">Интернет в вагоне</div><div class="s">%(title)s</div>
<div class="d">%(text)s</div></div></body></html>"""

PAGE_TEXT = {
    "FULL": ("#34d399", "Работает", "Если что-то не грузится, попробуйте выключить и включить Wi-Fi."),
    "WHITELIST": ("#fbbf24", "Только российские сайты",
                  "Оператор сейчас ограничивает доступ: работают Яндекс, ВКонтакте, Госуслуги и другие разрешённые сервисы."),
    "SLOW": ("#fbbf24", "Связь слабая", "Поезд едет по участку с плохим покрытием. Страницы могут открываться медленно."),
    "NONE": ("#f87171", "Временно нет", "На этом участке пути нет мобильной связи. Интернет вернётся сам, как только появится сеть."),
    None: ("#9ca3af", "Проверяю…", ""),
}


class Handler(BaseHTTPRequestHandler):
    server_version = "pdm-agent"
    sys_version = ""

    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def authed(self):
        tok = config().get("token", "")
        return not tok or self.headers.get("X-Token") == tok

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/health":
            return self.send(200, {"ok": True, "agent": VERSION})
        if p == "/api/state":
            if not self.authed():
                return self.send(403, {"error": "token"})
            return self.send(200, state_json())
        if p == "/api/log":
            if not self.authed():
                return self.send(403, {"error": "token"})
            with lock:
                return self.send(200, {"log": list(log_lines)})
        color, title, text = PAGE_TEXT.get(S.net, PAGE_TEXT[None])
        return self.send(200, PAGE % {"color": color, "title": title, "text": text}, "text/html; charset=utf-8")

    def do_POST(self):
        if not self.authed():
            return self.send(403, {"error": "token"})
        n = int(self.headers.get("Content-Length") or 0)
        if n > 65536:
            return self.send(413, {"error": "too large"})
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return self.send(400, {"error": "json"})
        p = self.path.split("?")[0]
        try:
            if p == "/api/quota":
                T.set_quota(int(body["i"]), float(body["left_gb"]), int(body.get("pack", 100)),
                            int(body.get("day", 0)), bool(body.get("roll", False)))
            elif p == "/api/settings":
                update_config({k: bool(v) for k, v in body.items() if k in ("auto_main", "heal", "auto_reg")})
            elif p == "/api/config":
                allowed = {k: body[k] for k in ("server", "modems", "interval") if k in body}
                update_config(allowed)
            else:
                return self.send(404, {"error": "not found"})
        except (KeyError, ValueError, TypeError) as e:
            return self.send(400, {"error": str(e)})
        return self.send(200, {"ok": True})


def main():
    global T
    os.makedirs(DATA, exist_ok=True)
    T = Traffic()
    cfg = config()
    log("pdm-agent %s, port %s" % (VERSION, cfg["port"]))
    threading.Thread(target=main_loop, daemon=True).start()
    threading.Thread(target=net_loop, daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", int(cfg["port"])), Handler)
    srv.daemon_threads = True
    try:
        srv.serve_forever()
    finally:
        T.flush(force=True)


if __name__ == "__main__":
    sys.exit(main())
PYEOF
/opt/bin/python3 -m py_compile "$DIR/pdm_agent.py.new"
rm -rf "$DIR/__pycache__"
mv "$DIR/pdm_agent.py.new" "$DIR/pdm_agent.py"
chmod +x "$DIR/pdm_agent.py"

# ---------- config: token is created once and kept
if [ ! -f "$DIR/config.json" ]; then
  TOKEN=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
  printf '{"port": %s, "token": "%s"}\n' "$PORT" "$TOKEN" > "$DIR/config.json"
  chmod 600 "$DIR/config.json"
fi

# ---------- service (own pid file: never touches other python processes)
cat > "$INIT" <<'INITEOF'
#!/bin/sh
PID=/opt/var/run/pdm-agent.pid
LOG=/opt/var/log/pdm-agent.log
start() {
  if [ -f $PID ] && kill -0 "$(cat $PID)" 2>/dev/null; then echo "pdm-agent уже работает"; return 0; fi
  mkdir -p /opt/var/run /opt/var/log
  [ -f $LOG ] && [ "$(wc -c < $LOG)" -gt 1000000 ] && mv -f $LOG $LOG.1
  nohup /opt/bin/python3 /opt/pdm/pdm_agent.py >> $LOG 2>&1 &
  echo $! > $PID
}
stop() {
  [ -f $PID ] && kill "$(cat $PID)" 2>/dev/null
  rm -f $PID
  sleep 1
}
case "$1" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) [ -f $PID ] && kill -0 "$(cat $PID)" 2>/dev/null && echo "работает" || echo "остановлен" ;;
  *) echo "start|stop|restart|status" ;;
esac
INITEOF
chmod +x "$INIT"

# ---------- start + health check
$INIT restart
OK=""
i=0
while [ $i -lt 10 ]; do
  sleep 2
  i=$((i + 1))
  if /opt/bin/python3 -c "import urllib.request,sys; sys.exit(0 if b'\"ok\"' in urllib.request.urlopen('http://127.0.0.1:$PORT/health', timeout=3).read() else 1)" 2>/dev/null; then
    OK=1; break
  fi
done
if [ -z "$OK" ]; then
  echo "pdm-agent не ответил за 20 секунд, откатываю. Последние строки журнала:"
  tail -n 15 /opt/var/log/pdm-agent.log 2>/dev/null
  sh "$BK/rollback.sh"
  exit 1
fi

TOKEN=$(/opt/bin/python3 -c "import json; print(json.load(open('$DIR/config.json')).get('token',''))")
ls -1dt $DIR/backup-* 2>/dev/null | tail -n +6 | xargs rm -rf 2>/dev/null || true
echo
echo "pdm-agent работает."
echo "Страница для пассажиров: http://192.168.1.1:$PORT/"
echo "Ключ для приложения (Настройки → Агент на роутере):"
echo
echo "$TOKEN"
echo
echo "Откат: sh $BK/rollback.sh"
