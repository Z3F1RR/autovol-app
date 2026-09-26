#!/data/data/com.termux/files/usr/bin/python3
# autovol — громкость звонка/уведомлений по уровню окружающего шума.
# Root-демон для Android (KernelSU). Замер микрофоном через Termux:API, уровень через ffmpeg.
import os, sys, re, time, json, signal, select, ctypes, subprocess, traceback

BASE = os.environ.get("AUTOVOL_BASE", "/data/adb/autovol")
CONF = BASE + "/autovol.conf"
LOG = BASE + "/autovol.log"
PIDF = BASE + "/autovol.pid"
STATEF = BASE + "/state.json"
HISTF = BASE + "/history.json"
CALIB_LOCK = BASE + "/calib.lock"
TPREFIX = "/data/data/com.termux/files/usr"
THOME = "/data/data/com.termux/files/home"
REC = TPREFIX + "/tmp/autovol_rec.m4a"
SYS = "/system/bin/"

DEFAULTS = [
    ("ENABLED", "1", "1 = работает, 0 = пауза"),
    ("LEVELS", "-120:0 -46:25 -39:50 -32:75 -26:100",
     "порог_дБ:процент_громкости по возрастанию; 0% = минимум (1 деление, не беззвучно)"),
    ("MIN_VOL", "1", "минимальная громкость в тишине, делений (1 = самый тихий звук, не беззвучно)"),
    ("STREAMS", "2 5", "потоки: 2 = звонок, 5 = уведомления"),
    ("FAST", "90", "интервал (сек), пока обстановка меняется"),
    ("SLOW", "150", "интервал (сек), когда всё стабильно"),
    ("IDLE", "300", "интервал (сек) в паузе: вибро/без звука/BT/ручная громкость"),
    ("STABLE_MIN", "15", "через сколько минут без изменений перейти на SLOW"),
    ("MARGIN", "3", "запас в дБ против дёрганья на 1-й ступени; на верхних ступенях уменьшается до 0.5 («резинка»)"),
    ("ELEV_INT", "45", "интервал (сек) на максимальной ступени; на промежуточных — между FAST и этим"),
    ("OVERRIDE_MIN", "30", "на сколько минут не трогать громкость после ручного изменения"),
    ("REC_SEC", "2", "длительность замера, сек"),
    ("SILENT_DB", "-85", "ниже этого = микрофон заглушён системой, замер игнорируется"),
    ("LOW_BAT", "15", "при заряде ниже (и не на зарядке) — пауза"),
    ("PAUSE_ON_DND", "1", "пауза в режиме Не беспокоить"),
    ("PAUSE_ON_EXT_AUDIO", "1", "пауза при BT/проводных наушниках/USB-аудио/авто"),
    ("PAUSE_ON_MEDIA", "1", "не мерить, пока телефон сам что-то воспроизводит"),
    ("CONFIRM_SEC", "12", "повышение подтверждается повторным замером через N сек (отсекает щелчки/звук уведомления)"),
    ("AUTO_CAL", "1", "1 = пороги LEVELS вычисляются сами из истории замеров (проценты берутся из LEVELS)"),
    ("CAL_DAYS", "7", "сколько дней истории учитывать"),
    ("CAL_MIN_SAMPLES", "300", "сколько замеров нужно, чтобы включилась автокалибровка (~8-10 ч)"),
    ("CAL_FLOOR_PCT", "25", "какой процентиль истории считать тишиной"),
    ("CAL_START", "8", "на сколько дБ выше тишины начинается первая ступень"),
    ("CAL_MIN_SPAN", "24", "максимум громкости не ближе этого числа дБ к тишине"),
    ("CAL_MAX_SPAN", "40", "максимум громкости не дальше этого числа дБ от тишины"),
    ("PAUSE_PROCS", "com.google.android.projection.gearhead:projection",
     "процессы, при которых пауза (через пробел); по умолчанию Android Auto"),
]
MODE_NAMES = {0: "без звука", 1: "вибро", 2: "обычный"}


# ---------------- утилиты ----------------
def log(msg):
    line = time.strftime("%m-%d %H:%M:%S ") + msg
    try:
        if os.path.exists(LOG) and os.path.getsize(LOG) > 256 * 1024:
            os.replace(LOG, LOG + ".1")
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass


def _sys_env():
    e = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "LD_LIBRARY_PATH")}
    e["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
    return e


def _termux_env():
    e = {"HOME": THOME, "PREFIX": TPREFIX, "TMPDIR": TPREFIX + "/tmp", "LANG": "C.UTF-8",
         "PATH": TPREFIX + "/bin:/system/bin", "ANDROID_DATA": "/data", "ANDROID_ROOT": "/system"}
    for lib in ("libtermux-exec-ld-preload.so", "libtermux-exec.so"):
        if os.path.exists(TPREFIX + "/lib/" + lib):
            e["LD_PRELOAD"] = TPREFIX + "/lib/" + lib
            break
    return e


SYS_ENV = _sys_env()
TENV = _termux_env()


def run(args, timeout=20, env=None, **kw):
    try:
        p = subprocess.run(args, capture_output=True, text=True, errors="replace",
                           timeout=timeout, env=env or SYS_ENV, **kw)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except Exception as e:
        return -1, repr(e)


def termux_uid():
    return os.stat(THOME).st_uid


def termux_run(args):
    """Запуск команды внутри приложения Termux (RUN_COMMAND): так Termux:API работает
    в своём обычном SELinux-контексте. Требует allow-external-apps = true."""
    cmd = [SYS + "am", "start-foreground-service", "--user", "0",
           "-n", "com.termux/com.termux.app.RunCommandService", "-a", "com.termux.RUN_COMMAND",
           "--es", "com.termux.RUN_COMMAND_PATH", args[0],
           "--ez", "com.termux.RUN_COMMAND_BACKGROUND", "true"]
    if len(args) > 1:
        cmd += ["--esa", "com.termux.RUN_COMMAND_ARGUMENTS", ",".join(args[1:])]
    rc, out = run(cmd, timeout=15)
    if rc != 0 or "Error" in out or "Exception" in out:
        return False, out.strip()[-200:]
    return True, ""


def load_conf():
    cfg = {k: v for k, v, _ in DEFAULTS}
    if not os.path.exists(CONF):
        write_default_conf()
    try:
        with open(CONF, encoding="utf-8") as f:
            for ln in f:
                ln = ln.split("#", 1)[0].strip()
                if "=" in ln:
                    k, v = ln.split("=", 1)
                    cfg[k.strip()] = v.strip().strip("\"'")
    except OSError:
        pass
    missing = [(k, v, c) for k, v, c in DEFAULTS if not re.search(r"^\s*%s\s*=" % k, _conf_text(), re.M)]
    if missing:
        try:
            with open(CONF, "a", encoding="utf-8") as f:
                for k, v, c in missing:
                    f.write("\n# %s\n%s=%s\n" % (c, k, v))
        except OSError:
            pass
    return cfg


def _conf_text():
    try:
        with open(CONF, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def write_default_conf():
    try:
        with open(CONF, "w", encoding="utf-8") as f:
            f.write("# autovol — после правки: autovol now (или подождать до следующего цикла)\n")
            for k, v, c in DEFAULTS:
                f.write("\n# %s\n%s=%s\n" % (c, k, v))
    except OSError:
        pass


def ci(cfg, k):
    try:
        return int(float(cfg[k]))
    except (KeyError, ValueError):
        return int(dict((a, b) for a, b, _ in DEFAULTS)[k])


def parse_levels(s):
    try:
        lv = sorted((float(a), max(0, min(100, int(b)))) for a, b in (t.split(":") for t in s.split()))
        if lv:
            return lv
    except ValueError:
        log("ошибка в LEVELS, использую значения по умолчанию")
    return parse_levels(DEFAULTS[1][1])


def step_of(db, lv):
    i = 0
    for n, (thr, _) in enumerate(lv):
        if db >= thr:
            i = n
    return i


def target_vol(pct, lo, hi, mn=1):
    """0% = MIN_VOL, 100% = максимум, между ними — пропорционально."""
    mn = min(hi, max(lo, 1, mn))
    return min(hi, mn + round((hi - mn) * pct / 100.0))


def load_hist():
    try:
        with open(HISTF) as f:
            return [tuple(x) for x in json.load(f)]
    except (OSError, ValueError):
        return []


def save_hist(h):
    try:
        with open(HISTF + ".tmp", "w") as f:
            json.dump(h, f, separators=(",", ":"))
        os.replace(HISTF + ".tmp", HISTF)
    except OSError:
        pass


def pctl(xs, p):
    return xs[min(len(xs) - 1, max(0, int(len(xs) * p / 100.0)))]


def auto_levels(hist, lv, cfg):
    """Пороги из истории: тишина = процентиль CAL_FLOOR_PCT, максимум = 97-й процентиль,
    но с ограничением расстояния от тишины [CAL_MIN_SPAN..CAL_MAX_SPAN]."""
    if cfg.get("AUTO_CAL") != "1" or len(hist) < ci(cfg, "CAL_MIN_SAMPLES") or len(lv) < 2:
        return lv, None
    xs = sorted(d for _, d in hist)
    floor = pctl(xs, ci(cfg, "CAL_FLOOR_PCT"))
    top = pctl(xs, 97)
    span = min(max(top - floor, ci(cfg, "CAL_MIN_SPAN")), ci(cfg, "CAL_MAX_SPAN"))
    start = ci(cfg, "CAL_START")
    n = len(lv) - 1
    out = [(-120.0, lv[0][1])]
    for k in range(1, n + 1):
        th = floor + start + (span - start) * (k - 1) / max(1, n - 1)
        out.append((round(th, 1), lv[k][1]))
    return out, (round(floor, 1), round(floor + span, 1), len(hist))


def fmt_levels(lv):
    return " ".join("%g:%d" % (a, b) for a, b in lv)


# ---------------- запросы к системе ----------------
def sget(ns, key):
    rc, out = run([SYS + "settings", "get", ns, key])
    try:
        return int(out.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return None


VOL_RE = re.compile(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]")


def get_vol(stream):
    rc, out = run([SYS + "cmd", "media_session", "volume", "--stream", str(stream), "--get"])
    m = VOL_RE.search(out)
    return (int(m[1]), int(m[2]), int(m[3])) if m else None


def set_vol(stream, v):
    run([SYS + "cmd", "media_session", "volume", "--stream", str(stream), "--set", str(v)])


def in_call():
    rc, out = run([SYS + "dumpsys", "telephony.registry"])
    if re.search(r"mCallState=[12]\b", out):
        return "сотовый вызов"
    rc, out = run([SYS + "dumpsys", "media.audio_policy"], timeout=10)
    m = re.search(r"Phone state:\s*([A-Z_0-9]+)", out)
    if m and m[1] not in ("0", "AUDIO_MODE_NORMAL", "NORMAL"):
        return "аудиорежим " + m[1]
    return None


def _stream_devices(txt, name):
    m = re.search(r"-\s*%s:(.*?)(?=-\s*STREAM_|\Z)" % name, txt, re.S)
    if not m:
        return None
    d = re.search(r"^\s*Devices:\s*(.+)$", m[1], re.M)
    if not d:
        return None
    return [re.sub(r"\W.*$", "", x) for x in re.split(r"[,\s]+", d[1].strip()) if x]


def external_audio():
    rc, txt = run([SYS + "dumpsys", "audio"], timeout=10)
    for st in ("STREAM_RING", "STREAM_MUSIC"):
        devs = _stream_devices(txt, st)
        for d in devs or []:
            if d and not (d.startswith("speaker") or d == "earpiece"):
                return d
    return None


def media_playing():
    rc, out = run([SYS + "dumpsys", "media_session"], timeout=10)
    return bool(re.search(r"PlaybackState \{state=(?:PLAYING\()?3\b", out))


def players_active():
    """Любой активный плеер в системе (игры, браузер, голосовые, звуки уведомлений)."""
    rc, out = run([SYS + "dumpsys", "audio"], timeout=10)
    for ln in out.splitlines():
        t = ln.strip()
        if t.startswith("AudioPlaybackConfiguration") and "state:started" in t:
            m = re.search(r"usage=(\w+)", t)
            return m[1] if m else "плеер"
    return None


def playing():
    if media_playing():
        return "медиа"
    return players_active()


def procs_set():
    rc, out = run([SYS + "ps", "-A", "-o", "NAME"])
    return set(x.strip() for x in out.splitlines())


def battery():
    rc, out = run([SYS + "dumpsys", "battery"])
    lv = re.search(r"^\s*level:\s*(\d+)", out, re.M)
    stt = re.search(r"^\s*status:\s*(\d+)", out, re.M)
    return (int(lv[1]) if lv else None), (stt is not None and stt[1] in ("2", "5"))


# ---------------- замер ----------------
def _size(p):
    try:
        return os.path.getsize(p)
    except OSError:
        return -1


def measure(rec_sec):
    """Возвращает (дБFS или None, текст ошибки)."""
    if "com.termux" not in procs_set():
        run([SYS + "am", "start-foreground-service", "-n", "com.termux/.app.TermuxService"])
        time.sleep(3)
    try:
        os.remove(REC)
    except OSError:
        pass
    tool = TPREFIX + "/bin/termux-microphone-record"
    ok, err = termux_run([tool, "-f", REC, "-l", str(rec_sec), "-e", "aac"])
    if not ok:
        return None, "am/RUN_COMMAND: " + err
    t0 = time.time()
    while not os.path.exists(REC) and time.time() - t0 < 8:
        time.sleep(0.3)
    if not os.path.exists(REC):
        termux_run([tool, "-q"])
        return None, "запись не началась (allow-external-apps? Termux:API?)"
    time.sleep(rec_sec + 1.2)
    last = -2
    for _ in range(10):
        s = _size(REC)
        if s > 0 and s == last:
            break
        last = s
        time.sleep(0.5)
    else:
        termux_run([tool, "-q"])
        time.sleep(1)
    if _size(REC) <= 0:
        return None, "файл записи пуст"
    rc, out = run([TPREFIX + "/bin/ffmpeg", "-hide_banner", "-nostats", "-ss", "0.3", "-i", REC,
                   "-af", "volumedetect", "-f", "null", "-"], timeout=20, env=TENV)
    m = re.search(r"mean_volume:\s*(-?inf|-?[\d.]+) dB", out)
    if not m:
        return None, "ffmpeg: " + out.strip()[-160:]
    return (-120.0 if "inf" in m[1] else float(m[1])), ""


# ---------------- таймер с пробуждением ----------------
class WakeTimer:
    """Спит по CLOCK_BOOTTIME_ALARM: время идёт и в глубоком сне, по сроку будит телефон.
    EPOLLWAKEUP держит устройство бодрствующим, пока не отработает цикл."""
    EPOLLWAKEUP = 1 << 29

    class TS(ctypes.Structure):
        _fields_ = [("s", ctypes.c_long), ("ns", ctypes.c_long)]

    class ITS(ctypes.Structure):
        pass

    ITS._fields_ = [("interval", TS), ("value", TS)]

    def __init__(self):
        self.fd, self.kind = -1, "обычный sleep"
        for name in ("libc.so", "libc.so.6", None):
            try:
                self.libc = ctypes.CDLL(name, use_errno=True)
                self.libc.timerfd_create
                break
            except (OSError, AttributeError):
                continue
        self.libc.timerfd_settime.argtypes = [ctypes.c_int, ctypes.c_int,
                                              ctypes.POINTER(self.ITS), ctypes.POINTER(self.ITS)]
        for clk, nm in ((9, "BOOTTIME_ALARM (будит телефон)"), (7, "BOOTTIME (без пробуждения)")):
            fd = self.libc.timerfd_create(clk, 0o2000000)
            if fd >= 0:
                self.fd, self.kind = fd, nm
                break
        self.ep = select.epoll()
        if self.fd >= 0:
            try:
                self.ep.register(self.fd, select.EPOLLIN | self.EPOLLWAKEUP)
            except OSError:
                self.ep.register(self.fd, select.EPOLLIN)
        self.rp, wp = os.pipe()
        os.set_blocking(self.rp, False)
        os.set_blocking(wp, False)
        signal.set_wakeup_fd(wp)
        signal.signal(signal.SIGUSR1, lambda *a: None)
        self.ep.register(self.rp, select.EPOLLIN)

    def _arm(self, sec):
        its = self.ITS()
        its.value.s, its.value.ns = int(sec), int((sec - int(sec)) * 1e9)
        self.libc.timerfd_settime(self.fd, 0, ctypes.byref(its), None)

    def sleep(self, sec):
        """True — если разбудили вручную (autovol now)."""
        kicked = False
        if self.fd >= 0:
            self._arm(sec)
        for fd, _ in self.ep.poll(sec + 30 if self.fd >= 0 else sec):
            if fd == self.fd:
                try:
                    os.read(self.fd, 8)
                except OSError:
                    pass
            elif fd == self.rp:
                try:
                    kicked = bool(os.read(self.rp, 64))
                except OSError:
                    pass
        if self.fd >= 0:
            self._arm(0)
        return kicked


def wake_lock(on):
    try:
        with open("/sys/power/wake_lock" if on else "/sys/power/wake_unlock", "w") as f:
            f.write("autovol 60000000000" if on else "autovol")
    except OSError:
        pass


# ---------------- логика ----------------
class State:
    def __init__(self):
        self.step = None          # текущая ступень (None = нужно синхронизироваться)
        self.last_set = {}        # поток -> громкость, которую выставили мы
        self.pending = []         # замеры-кандидаты на понижение
        self.last_change = time.time()
        self.override_until = 0.0
        self.last_db = None
        self.hist = load_hist()
        self.cal = None

    def add_hist(self, db, cfg):
        now = time.time()
        self.hist.append((int(now), round(db, 1)))
        cut = now - ci(cfg, "CAL_DAYS") * 86400
        if self.hist and self.hist[0][0] < cut:
            self.hist = [x for x in self.hist if x[0] >= cut]
        save_hist(self.hist)

    def resync(self):
        self.step, self.last_set, self.pending = None, {}, []


def cycle(st, cfg):
    now = time.time()
    FAST, SLOW, IDLE = ci(cfg, "FAST"), ci(cfg, "SLOW"), ci(cfg, "IDLE")

    def pause(reason):
        st.resync()
        return IDLE, reason

    if cfg.get("ENABLED") != "1":
        return pause("выключено в конфиге")
    if os.path.exists(CALIB_LOCK) and now - os.path.getmtime(CALIB_LOCK) < 180:
        return 60, "идёт калибровка"
    mode = sget("global", "mode_ringer")
    if mode != 2:
        return pause("режим звонка: %s" % MODE_NAMES.get(mode, mode))
    if cfg.get("PAUSE_ON_DND") == "1" and (sget("global", "zen_mode") or 0) != 0:
        return pause("режим Не беспокоить")
    c = in_call()
    if c:
        return FAST, "пропуск: " + c
    procs = procs_set()
    for p in cfg.get("PAUSE_PROCS", "").split():
        if p in procs:
            return pause("пауза: запущен " + p)
    if cfg.get("PAUSE_ON_EXT_AUDIO") == "1":
        ext = external_audio()
        if ext:
            return pause("пауза: звук идёт на " + ext)
    lvl, charging = battery()
    if lvl is not None and lvl <= ci(cfg, "LOW_BAT") and not charging:
        return pause("пауза: заряд %d%%" % lvl)

    streams = [int(x) for x in cfg.get("STREAMS", "2 5").split()]
    cur = {s: get_vol(s) for s in streams}
    if any(v is None for v in cur.values()):
        return IDLE, "ошибка: не удалось прочитать громкость (cmd media_session)"
    if now < st.override_until:
        return IDLE, "ручная громкость, жду до %s" % time.strftime("%H:%M", time.localtime(st.override_until))
    if st.override_until:
        st.override_until = 0
        st.resync()
    if st.last_set and any(cur[s][0] != st.last_set.get(s, cur[s][0]) for s in streams):
        st.resync()
        st.override_until = now + ci(cfg, "OVERRIDE_MIN") * 60
        return IDLE, "громкость изменена вручную — пауза %d мин" % ci(cfg, "OVERRIDE_MIN")
    chk_play = cfg.get("PAUSE_ON_MEDIA") == "1"

    def sample():
        """Один чистый замер: (дБ, None) или (None, причина пропуска)."""
        if chk_play:
            p = playing()
            if p:
                return None, "пропуск: телефон воспроизводит звук (%s)" % p
        db, err = measure(ci(cfg, "REC_SEC"))
        if db is None:
            return None, "ошибка замера: " + err
        if db <= ci(cfg, "SILENT_DB"):
            return None, "%.1f дБ — микрофон заглушён системой/занят, пропуск" % db
        if chk_play:
            p = playing()
            if p:
                return None, "%.1f дБ отброшен: во время замера играл звук (%s)" % (db, p)
        return db, None

    db, why = sample()
    if db is None:
        return FAST, why
    lv, st.cal = auto_levels(st.hist, parse_levels(cfg.get("LEVELS", "")), cfg)
    up = step_of(db, lv)
    if up > (st.step if st.step is not None else 0):
        # подтверждение: одиночный всплеск (звук уведомления, стук, шорох) не поднимает громкость
        time.sleep(ci(cfg, "CONFIRM_SEC"))
        db2, why = sample()
        if db2 is None:
            return FAST, "повышение не подтверждено (%s)" % why
        st.add_hist(db, cfg)
        if step_of(db2, lv) < up:
            log("всплеск %.1f дБ не подтвердился (повтор %.1f дБ)" % (db, db2))
        db = min(db, db2)
    st.add_hist(db, cfg)
    st.last_db = db
    up = step_of(db, lv)
    n = len(lv) - 1
    extra = ""
    if st.step is None or up > st.step:
        new = up
    else:
        k = st.step
        # «резинка»: чем выше ступень, тем меньше запас — громкость легче уходит вниз
        m = max(0.5, float(cfg.get("MARGIN", 3)) * (1 - (k - 1) / max(1, n - 1))) if k > 0 else 0
        dn = step_of(db + m, lv)
        if dn < k:
            # быстрый повтор вместо ожидания нескольких циклов: затишье подтверждено — сразу вниз
            time.sleep(ci(cfg, "CONFIRM_SEC"))
            db2, why = sample()
            if db2 is None:
                new, extra = k, " [понижение отложено: %s]" % why
            else:
                st.add_hist(db2, cfg)
                dn2 = step_of(db2 + m, lv)
                if dn2 < k:
                    new = max(dn, dn2)
                else:
                    new, extra = k, " [затишье не подтвердилось: %.1f дБ]" % db2
        else:
            new = k
    pct = lv[new][1]

    # повторная проверка прямо перед изменением (слайдер/вызов могли измениться за время замера)
    if sget("global", "mode_ringer") != 2 or in_call():
        st.resync()
        return FAST, "режим изменился во время замера — пропуск"
    changed = []
    for s in streams:
        v, lo, hi = cur[s]
        t = target_vol(pct, lo, hi, ci(cfg, "MIN_VOL"))
        if v != t:
            set_vol(s, t)
            changed.append("%d:%d→%d" % (s, v, t))
    for s in streams:
        r = get_vol(s)
        st.last_set[s] = r[0] if r else target_vol(pct, cur[s][1], cur[s][2], ci(cfg, "MIN_VOL"))
    if changed:
        st.last_change = now
        if sget("global", "mode_ringer") != 2:
            log("ВНИМАНИЕ: после изменения громкости сменился режим звонка")
    st.step = new
    if new > 0:
        # на повышенной громкости проверяем чаще — чтобы быстрее вернуть её вниз
        wait = int(round(FAST - (FAST - ci(cfg, "ELEV_INT")) * new / max(1, n)))
    else:
        wait = FAST if now - st.last_change < ci(cfg, "STABLE_MIN") * 60 else SLOW
    note = "%.1f дБ → ступень %d (%d%%)%s%s%s" % (
        db, new, pct, "" if st.cal else " [ручные пороги]", (" изменено " + " ".join(changed)) if changed else "",
        extra)
    return wait, note


def daemon():
    os.makedirs(BASE, exist_ok=True)
    with open(PIDF, "w") as f:
        f.write(str(os.getpid()))
    stop = []
    signal.signal(signal.SIGTERM, lambda *a: stop.append(1))
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    timer = WakeTimer()
    st = State()
    log("старт, pid %d, таймер: %s" % (os.getpid(), timer.kind))
    last_note = None
    while not stop:
        wake_lock(True)
        try:
            wait, note = cycle(st, load_conf())
        except Exception:
            wait, note = 120, "исключение: " + traceback.format_exc().strip().splitlines()[-1]
            log(traceback.format_exc())
        if note != last_note or " дБ" in note:
            log(note)
        last_note = note
        try:
            with open(STATEF, "w") as f:
                json.dump({"time": time.strftime("%H:%M:%S"), "note": note, "next_sec": wait,
                           "step": st.step, "last_db": st.last_db, "last_set": st.last_set,
                           "override_until": st.override_until,
                           "samples": len(st.hist), "auto_cal": st.cal}, f, ensure_ascii=False)
        except OSError:
            pass
        wake_lock(False)
        if stop:
            break
        timer.sleep(wait)
    log("остановлен")
    try:
        os.remove(PIDF)
    except OSError:
        pass


# ---------------- команды ----------------
def calib(n=6):
    cfg = load_conf()
    lv = parse_levels(cfg.get("LEVELS", ""))
    open(CALIB_LOCK, "w").close()
    try:
        print("Замеры каждые ~5 с. Текущие пороги:", cfg.get("LEVELS"))
        for i in range(n):
            db, err = measure(ci(cfg, "REC_SEC"))
            if db is None:
                print("%d: ошибка — %s" % (i + 1, err))
            else:
                s = step_of(db, lv)
                print("%d: %.1f дБ → ступень %d (%d%%)" % (i + 1, db, s, lv[s][1]))
            time.sleep(1.5)
    finally:
        os.remove(CALIB_LOCK)


def check():
    ok = lambda b: "OK " if b else "!! "
    print(ok(os.geteuid() == 0) + "root")
    for p in ("python3", "ffmpeg", "termux-microphone-record"):
        print(ok(os.access(TPREFIX + "/bin/" + p, os.X_OK)) + p)
    try:
        props = open(THOME + "/.termux/termux.properties").read()
    except OSError:
        props = ""
    print(ok(re.search(r"^\s*allow-external-apps\s*=\s*true", props, re.M) is not None) + "allow-external-apps = true")
    rc, out = run([SYS + "pm", "path", "com.termux.api"])
    print(ok("package:" in out) + "приложение Termux:API")
    m = sget("global", "mode_ringer")
    print(ok(m is not None) + "режим звонка: %s" % MODE_NAMES.get(m, m))
    print("   Не беспокоить: %s" % sget("global", "zen_mode"))
    for s in (2, 5):
        v = get_vol(s)
        print(ok(v is not None) + "громкость потока %d: %s" % (s, "%d (диапазон %d..%d)" % v if v else "не читается"))
    print("   вызов: %s" % (in_call() or "нет"))
    print("   внешний звук: %s" % (external_audio() or "нет (динамик)"))
    print("   играет медиа (MediaSession): %s" % media_playing())
    print("   активный плеер (dumpsys audio): %s" % (players_active() or "нет"))
    print("   батарея: %s%%, зарядка: %s" % battery())
    t = WakeTimer()
    print(ok("ALARM" in t.kind) + "таймер: " + t.kind)
    print("   конфиг: " + CONF)


def status():
    cfg = load_conf()
    h = load_hist()
    lv, cal = auto_levels(h, parse_levels(cfg.get("LEVELS", "")), cfg)
    if cal:
        print("автокалибровка: тишина %.1f дБ, максимум от %.1f дБ, замеров %d" % cal)
    else:
        print("автокалибровка: копится история %d/%s замеров, пока ручные пороги" % (len(h), cfg.get("CAL_MIN_SAMPLES")))
    print("пороги сейчас:", fmt_levels(lv))
    pid = None
    try:
        pid = int(open(PIDF).read())
        os.kill(pid, 0)
        print("работает, pid", pid)
    except (OSError, ValueError):
        print("НЕ запущен")
    try:
        print(json.dumps(json.load(open(STATEF)), ensure_ascii=False, indent=1))
    except (OSError, ValueError):
        pass


def setmin(arg):
    cfg = load_conf()
    v = get_vol(2)
    hi = v[2] if v else 16
    cur = ci(cfg, "MIN_VOL")
    new = cur + int(arg) if arg[:1] in "+-" else int(arg)
    new = max(1, min(hi, new))
    txt = _conf_text()
    if re.search(r"^\s*MIN_VOL\s*=", txt, re.M):
        txt = re.sub(r"^\s*MIN_VOL\s*=.*$", "MIN_VOL=%d" % new, txt, flags=re.M)
    else:
        txt += "\nMIN_VOL=%d\n" % new
    with open(CONF, "w", encoding="utf-8") as f:
        f.write(txt)
    print("autovol: мин. громкость %d из %d%s" % (new, hi, " (предел)" if new == cur else ", применится через ~15 с"))


def short():
    """Одна строка состояния — для меню и всплывающих уведомлений."""
    cfg = load_conf()
    try:
        os.kill(int(open(PIDF).read()), 0)
    except (OSError, ValueError):
        print("autovol НЕ запущен")
        return
    if cfg.get("ENABLED") != "1":
        print("autovol: ПАУЗА (громкость не меняется)")
        return
    try:
        sj = json.load(open(STATEF))
    except (OSError, ValueError):
        print("autovol: работает, данных пока нет")
        return
    v = get_vol(2)
    ring = " · звонок %d/%d" % (v[0], v[2]) if v else ""
    cal = "мин %s · " % cfg.get("MIN_VOL", "1")
    cal += "калибровка готова" if sj.get("auto_cal") else "калибровка %s/%s" % (sj.get("samples", 0), cfg.get("CAL_MIN_SAMPLES"))
    print("autovol %s: %s%s · %s" % (sj.get("time", ""), sj.get("note", ""), ring, cal))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "daemon":
        daemon()
    elif cmd == "calib":
        calib(int(sys.argv[2]) if len(sys.argv) > 2 else 6)
    elif cmd == "check":
        check()
    elif cmd == "status":
        status()
    elif cmd == "setmin":
        setmin(sys.argv[2] if len(sys.argv) > 2 else "+0")
    elif cmd == "short":
        short()
    elif cmd == "resetcal":
        save_hist([])
        print("история замеров очищена; перезапустите: autovol restart")
    else:
        print("usage: autovol.py daemon|calib [N]|check|status")
