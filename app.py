#!/usr/bin/python3
# -*- coding: utf-8 -*-
"""刻盘钟·主程序（1.4.0）
用法：
  kepan-clock                正常启动（GTK4 窗口，单实例）
  kepan-clock --day|--night  锁定昼盘／夜盘启动
  kepan-clock --wallpaper    壁画模式：全屏置底窗，Esc 退出
  kepan-clock --png OUT.png [--hhmm 15:45] [--date 2026-11-10] [--size 960x680]
              [--popup three|month] [--wallpaper] [--day|--night]
                             离屏渲染一张盘面存为 PNG 后退出（自检用）
配置查找次序：~/.config/kepan-clock/config.toml → 程序目录 config.toml；
配置保存后约半秒热重载，免重启。
"""
import datetime
import math
import os
import pathlib
import shutil
import struct
import subprocess
import sys
import time
import tomllib
import traceback
import wave

import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Gdk", "4.0")
from gi.repository import Gdk, Gio, GLib, Gtk  # noqa: E402

import dial  # noqa: E402
import lunar  # noqa: E402
import sun  # noqa: E402
from chen_core import animal, read_str  # noqa: E402

_TITLE = "刻盘钟·司辰"
_HERE = pathlib.Path(__file__).resolve().parent
_CROSSFADE_S = 30.0            # 昼夜过渡各三十秒

DEFAULT_CONFIG = {
    "place": {"latitude": 39.9, "longitude": 116.4},
    "dusk": {"offset_minutes": 37},
    "mode": {"theme": "auto"},
    "fonts": {
        "chen": "AR PL UKai CN, AR PL UMing CN, Noto Serif CJK SC, serif",
        "seal": ("zihunyoulongzhuanshu_trial, 字魂游龙篆书(商用需授权), "
                 "霞鹜篆书, LXGW Seal, Aa美人篆（简繁）, AR PL UKai CN, serif"),
    },
    "sound": {"enabled": False, "debug": False},
    "panel": {"watermark": "zodiac", "watermark_alpha": 0.16},
}


def _deep_merge(base, extra):
    out = dict(base)
    for k, v in (extra or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _deep_merge(out[k], v)
        else:
            out[k] = v
    return out


def load_config():
    """返回 (配置, 所载文件路径或 None)。与默认值深度合并，缺段补默认。
    配置文件损坏（如编辑器写入半截）时跳过该文件回退默认，不致崩溃。"""
    paths = [pathlib.Path.home() / ".config" / "kepan-clock" / "config.toml",
             _HERE / "config.toml"]
    user = {}
    src = None
    for p in paths:
        if p.exists():
            try:
                with open(p, "rb") as f:
                    user = tomllib.load(f)
            except tomllib.TOMLDecodeError as e:
                print(f"警告：配置 {p} 解析失败（{e}），已回退默认值。",
                      file=sys.stderr)
                continue
            src = p
            break
    return _deep_merge(DEFAULT_CONFIG, user), src


def _tz_minutes(now):
    off = now.utcoffset()
    if off is not None:
        return off.total_seconds() / 60.0
    lt = time.localtime()
    return (lt.tm_gmtoff or 0) / 60.0


def _wrap_diff(a, b):
    d = (a - b) % 1440.0
    return min(d, 1440.0 - d)


_note_cache = {}
_parts_cache = {}


def note_of(d):
    """一行历注（含干支、候），按日缓存——候需解节气，不宜每秒重算。"""
    v = _note_cache.get(d)
    if v is None:
        try:
            v = lunar.note(d)
        except (ValueError, RuntimeError, KeyError) as e:
            print(f"警告：历注推算失败（{e}），本日略去历注。", file=sys.stderr)
            v = ""
        _note_cache.clear()
        _note_cache[d] = v
    return v


def parts_of(d):
    """日辰零件（年干支、农历、日干支、候），壁画题款用，按日缓存。"""
    v = _parts_cache.get(d)
    if v is None:
        try:
            v = lunar.parts(d)
        except (ValueError, RuntimeError, KeyError):
            v = {}
        _parts_cache.clear()
        _parts_cache[d] = v
    return v


def compute_state(now, cfg, force_night=None):
    t_min = now.hour * 60 + now.minute + now.second / 60.0 + now.microsecond / 6e7
    lat = float(cfg["place"]["latitude"])
    lon = float(cfg["place"]["longitude"])
    dusk = float(cfg["dusk"]["offset_minutes"])
    tz = _tz_minutes(now)

    rise, sett = sun.sun_times_local(now.date(), lat, lon, tz)
    span = sun.night_span(t_min, lat, lon, tz, now=now, dusk=dusk)
    night = sun.is_night(t_min, rise, sett) if force_night is None else force_night

    # 昼夜渐变：距日出或日落三十秒内线性过渡
    blend = 1.0 if night else 0.0
    if rise is not None and sett is not None:
        win_min = _CROSSFADE_S / 60.0
        for boundary, going_night in ((sett, True), (rise, False)):
            signed = ((t_min - boundary + 720.0) % 1440.0) - 720.0
            if abs(signed) < win_min:
                blend = min(1.0, max(0.0, (0.5 + signed / (2.0 * win_min)) if going_night
                                          else (0.5 - signed / (2.0 * win_min))))

    ts = sun.true_solar_minutes(t_min, now.timetuple().tm_yday, lon, tz)
    return dict(
        t_min=t_min,
        read=read_str(int(t_min)),
        geng=sun.geng_str(t_min, span),
        term=sun.term_name(now),
        date=now.date(),
        note=note_of(now.date()),
        parts=parts_of(now.date()),
        date_str=f"{now.year}年{now.month}月{now.day}日",
        hhmm=f"{now.hour:02d}:{now.minute:02d}",
        ts_read=read_str(int(ts) % 1440),
        animal=animal(int(t_min)),
        night=night,
        blend=blend,
    )


# ---------------------------------------------------------------- 报时声
_SOUND_DIR = pathlib.Path.home() / ".config" / "kepan-clock" / "sound"
_ERRLOG = pathlib.Path.home() / ".config" / "kepan-clock" / "error.log"
_RATE = 22050
_EVENTS = {  # 事件名 -> [(起点秒, 单声样), ...]
    "bell3": [(0.0, "bell"), (0.55, "bell"), (1.10, "bell")],
    "drum2": [(0.0, "drum"), (0.42, "drum")],
    "drum1": [(0.0, "drum")],
}


def _load_wav_mono(path):
    """读 16-bit WAV（单/立体声、任意采样率），转单声道 22050，返回 list[float]。"""
    with wave.open(str(path), "rb") as wf:
        ch, width, rate, n = (wf.getnchannels(), wf.getsampwidth(),
                              wf.getframerate(), wf.getnframes())
        if width != 2:
            raise ValueError("仅支持 16-bit WAV")
        raw = wf.readframes(n)
    if len(raw) < ch * n * 2:          # 文件被截断：按实有帧数解，缺帧补零
        n = len(raw) // (ch * 2)
        raw = raw[:n * ch * 2]
        if n == 0:
            raise ValueError("WAV 无有效帧")
    cnt = ch * n
    vals = struct.unpack(f"<{cnt}h", raw)
    mono = [sum(vals[i * ch:(i + 1) * ch]) / ch / 32768.0 for i in range(n)]
    if rate == _RATE:
        return mono
    # 线性插值重采样
    out_n = int(n * _RATE / rate)
    out = []
    for i in range(out_n):
        pos = i * rate / _RATE
        j = int(pos)
        if j + 1 >= n:
            out.append(mono[n - 1])
        else:
            frac = pos - j
            out.append(mono[j] * (1.0 - frac) + mono[j + 1] * frac)
    return out


def _write_wav(path, samples):
    peak = max(1e-6, max(abs(v) for v in samples))
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(_RATE)
        wf.writeframes(b"".join(
            struct.pack("<h", int(max(-1.0, min(1.0, v / peak)) * 31000))
            for v in samples))


def _synth_strike(kind):
    """合成兜底单声样（无实录且用户未自备时）。"""
    n = int(_RATE * (0.45 if kind == "bell" else 0.4))
    out = [0.0] * n
    for i in range(n):
        t = i / _RATE
        if kind == "bell":
            out[i] = (math.exp(-3.2 * t) * math.sin(2 * math.pi * 660 * t)
                      + 0.5 * math.exp(-4.5 * t) * math.sin(2 * math.pi * 1320 * t)
                      + 0.22 * math.exp(-6.0 * t) * math.sin(2 * math.pi * 1980 * t))
        else:
            f = 150.0 * math.exp(-6 * t) + 62.0
            out[i] = 0.9 * math.exp(-7.0 * t) * math.sin(2 * math.pi * f * t)
    return out


def _ensure_sounds():
    """备齐三件报时声于 ~/.config/kepan-clock/sound/。
    单声样来源依次：用户自备 bell.wav/drum.wav > 随包实录 > 合成；
    事件声（bell3/drum2/drum1）由单声样组装，源更新即重建。缺 paplay 则不可用。"""
    if not shutil.which("paplay"):
        return None
    d = _SOUND_DIR
    d.mkdir(parents=True, exist_ok=True)
    system = _HERE / "sounds"
    # 单声样就位
    src_mtime = {}
    for kind in ("bell", "drum"):
        dst = d / f"{kind}.wav"
        if dst.exists():
            src_mtime[kind] = dst.stat().st_mtime
            continue
        cand = system / f"{kind}.wav"
        if cand.exists():
            shutil.copyfile(cand, dst)
        else:
            _write_wav(dst, _synth_strike(kind))
        src_mtime[kind] = dst.stat().st_mtime
    try:
        with open(d / "声样来源.txt", "w") as f:
            note = system / "声样来源.txt"
            if note.exists():
                f.write(note.read_text())
    except OSError:
        pass
    # 组装事件声
    singles = {}
    for kind in ("bell", "drum"):
        try:
            singles[kind] = _load_wav_mono(d / f"{kind}.wav")
        except Exception as e:          # 解析失败一律兜合成，绝不外抛进主循环
            print(f"警告：{kind}.wav 无法解析（{e}），改用合成声样。", file=sys.stderr)
            singles[kind] = _synth_strike(kind)
    out = {}
    for name, strikes in _EVENTS.items():
        path = d / f"{name}.wav"
        newest = max(src_mtime[k] for _, k in strikes)
        if path.exists() and path.stat().st_mtime >= newest:
            out[name] = path
            continue
        longest = max(len(singles[k]) for _, k in strikes)
        total = int(_RATE * (max(t0 for t0, _ in strikes) + longest / _RATE)) + 64
        buf = [0.0] * total
        for t0, kind in strikes:
            seg = singles[kind]
            j0 = int(t0 * _RATE)
            for i, v in enumerate(seg):
                if j0 + i < total:
                    buf[j0 + i] += v
        # 先写临时文件再原子替换：热重载并发重读时不会碰到半截 WAV
        tmp = path.with_suffix(".tmp")
        try:
            _write_wav(tmp, buf)
            os.replace(tmp, path)
        except OSError:
            tmp.unlink(missing_ok=True)
            if not path.exists():
                raise
        out[name] = path
    return out


def _play(paths, name, debug=False, diag=False):
    """起 paplay 播一声，返回 Popen 供上层 poll 回收；diag=True 时同步
    运行一次并打印 paplay 的退出码与 stderr，用于失败诊断。"""
    if not paths or name not in paths:
        if debug:
            print(f"报时声 {name}：声样缺失，未播", file=sys.stderr)
        return None
    try:
        if diag:
            r = subprocess.run(["paplay", str(paths[name])], capture_output=True,
                               text=True, timeout=10)
            err = r.stderr.strip()
            print(f"诊断 paplay {name}：退出码 {r.returncode}"
                  + (f"，stderr：{err}" if err else ""), file=sys.stderr)
            return None
        p = subprocess.Popen(["paplay", str(paths[name])],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if debug:
            import datetime as _dt
            print(f"[{_dt.datetime.now():%H:%M:%S}] 报时：{name}", flush=True)
        return p
    except OSError as e:
        print(f"报时声 {name}：paplay 启动失败（{e}）", file=sys.stderr)
        return None


def _log_error(msg=None):
    """异常落盘到 ~/.config/kepan-clock/error.log。
    桌面启动时 stderr 进日志系统不可见，这里保底可查；超 64 KiB 清空重记。"""
    try:
        _ERRLOG.parent.mkdir(parents=True, exist_ok=True)
        if _ERRLOG.exists() and _ERRLOG.stat().st_size > 65536:
            _ERRLOG.write_text("")
        with open(_ERRLOG, "a") as f:
            f.write(f"—— {datetime.datetime.now():%Y-%m-%d %H:%M:%S} ——\n")
            if msg:
                f.write(msg + "\n")
            traceback.print_exc(file=f)
    except OSError:
        pass


def _sound_test():
    """--sound-test：立即播一声钟并打印全链路诊断。"""
    if not shutil.which("paplay"):
        print("诊断：未找到 paplay。请安装 pulseaudio-utils。", file=sys.stderr)
        return 1
    sounds = _ensure_sounds()
    if not sounds:
        print("诊断：声样合成失败（paplay 缺失）。", file=sys.stderr)
        return 1
    print("声样就绪：")
    for k, v in sounds.items():
        print(f"  {k} -> {v}")
    r = subprocess.run(["paplay", str(sounds["bell3"])], capture_output=True,
                       text=True, timeout=10)
    if r.returncode == 0:
        print("paplay 退出码 0——刚才应已闻钟声三下。"
              "若耳中无声，请查系统音量与输出设备。")
        return 0
    print(f"paplay 失败（退出码 {r.returncode}）：{r.stderr.strip()}",
          file=sys.stderr)
    return 1


# ---------------------------------------------------------------- 篆体自检
def _families_digest(ctx, families, s="司辰"):
    from gi.repository import Pango, PangoCairo
    lay = PangoCairo.create_layout(ctx)
    fd = Pango.FontDescription()
    fd.set_family(families)
    fd.set_absolute_size(80 * Pango.SCALE)
    lay.set_font_description(fd)
    lay.set_text(s, -1)
    ctx.move_to(10, 20)
    PangoCairo.show_layout(ctx, lay)
    import hashlib
    return hashlib.sha1(ctx.get_target().get_data()).hexdigest()


def seal_effective(seal_chain):
    import cairo
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, 400, 160)
    ctx = cairo.Context(surf)
    d_seal = _families_digest(ctx, seal_chain)
    ctx.set_source_rgb(0, 0, 0)
    ctx.set_operator(cairo.OPERATOR_CLEAR)
    ctx.paint()
    ctx.set_operator(cairo.OPERATOR_OVER)
    d_kai = _families_digest(ctx, "AR PL UKai CN, serif")
    return d_seal != d_kai


# ---------------------------------------------------------------- 窗口
class ClockWindow(Gtk.ApplicationWindow):
    def __init__(self, app, cfg, cfg_path, force_night=None):
        super().__init__(application=app, title=_TITLE)
        self.cfg = cfg
        self.cfg_path = cfg_path
        self.force_night = force_night
        self._pointer = None
        self._cursor = None
        self._popup = None
        self._geng = None
        self._flip_t0 = 0.0
        self._flip_old = ""
        self._last_min = None
        self._sounds = None
        self._sound_debug = bool(cfg.get("sound", {}).get("debug", False))
        self._last_play = None     # 上一发 paplay，供 poll 回收与失败计数
        self._snd_fail = 0         # 连续失败计数：≥2 重建声样，≥6 转诊断
        self.set_default_size(960, 680)
        self.set_size_request(520, 520)

        self.da = Gtk.DrawingArea()
        self.da.set_draw_func(self._on_draw)
        self.da.set_focusable(True)
        self.set_child(self.da)

        ec = Gtk.EventControllerMotion()
        ec.connect("motion", self._motion)
        ec.connect("leave", self._leave)
        self.da.add_controller(ec)

        gc = Gtk.GestureClick()
        gc.connect("pressed", self._clicked)
        self.da.add_controller(gc)

        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", self._key)
        self.da.add_controller(kc)

        if cfg_path is not None:
            mon = Gio.File.new_for_path(str(cfg_path)).monitor_file(Gio.FileMonitorFlags.NONE)
            mon.connect("changed", self._cfg_changed)
            self._monitor = mon

        self._tick()

    def _motion(self, ctl, x, y):
        self._pointer = (x, y)
        st = getattr(self, "st", None)
        if st is not None:
            hit = dial.hit_test(self.da.get_width(), self.da.get_height(), st, x, y)
            name = "pointer" if hit in ("note", "footer") else "default"
            if name != self._cursor:
                self._cursor = name
                self.da.set_cursor_from_name(name)
        self.da.queue_draw()

    def _leave(self, ctl):
        self._pointer = None
        self.da.queue_draw()

    def _clicked(self, gesture, n_press, x, y):
        self.da.grab_focus()
        st = getattr(self, "st", None)
        if st is None:
            return
        hit = dial.hit_test(self.da.get_width(), self.da.get_height(), st, x, y)
        if hit == "note":
            self._popup = None if self._popup else "three"
        elif hit == "footer":
            self._popup = "three" if self._popup == "month" else "month"
        elif hit == "popup":
            return
        else:
            self._popup = None
        self.da.queue_draw()

    def _key(self, ctl, keyval, keycode, state):
        if keyval == Gdk.KEY_Escape and self._popup:
            self._popup = None
            self.da.queue_draw()
            return True
        return False

    def _cfg_changed(self, mon, f1, f2, evt):
        if getattr(self, "_reload_pending", False):
            return
        self._reload_pending = True
        GLib.timeout_add(500, self._reload)

    def _reload(self):
        self._reload_pending = False
        try:
            cfg, _ = load_config()
            self.cfg = cfg
            self._sounds = None
            self._snd_fail = 0
            self._sound_debug = bool(cfg.get("sound", {}).get("debug", False))
            self.da.queue_draw()
        except Exception:
            _log_error("热重载失败，沿用旧配置。")
        return GLib.SOURCE_REMOVE

    def _schedule(self, ms=None):
        if ms is None:
            now = time.time()
            ms = int(1000.0 - (now - int(now)) * 1000.0) + 30
        GLib.timeout_add(ms, self._tick)
        return GLib.SOURCE_REMOVE

    def _tick(self):
        """每秒一跳。GLib 的 timeout 回调一旦抛异常，source 即被移除、
        tick 链永久断掉（钟面冻结、报时失效），故整体兜底：异常落盘
        error.log，无论如何都重挂下一跳，渲染沿用上一次状态。"""
        anim = False
        try:
            anim = self._tick_body()
        except Exception:
            _log_error("tick 异常，本跳沿用上次状态。")
        self._schedule(33 if anim else None)
        return GLib.SOURCE_REMOVE

    def _tick_body(self):
        now = datetime.datetime.now()
        st = compute_state(now, self.cfg, self.force_night)
        # 翻牌动画：更点文字变更时半秒翻牌
        if self._geng is not None and st["geng"] != self._geng:
            self._flip_t0 = time.time()
            self._flip_old = self._geng
        self._geng = st["geng"]
        frac = (time.time() - self._flip_t0) / 0.5
        if 0.0 <= frac < 1.0 and self._flip_old != st["geng"]:
            st["flip"] = (frac, self._flip_old)
        # 报时声：整分过刻线时触发（默认关）
        if self.cfg["sound"]["enabled"]:
            m = int(st["t_min"])
            if self._last_min is not None and m != self._last_min:
                if self._sounds is None:
                    self._sounds = _ensure_sounds()
                gap = (m - self._last_min) % 1440
                if gap > 2:
                    # 休眠/挂起/时钟回拨：不补播历史，只评估当前一刻
                    toks = (m,)
                    if self._sound_debug:
                        print(f"时间跳变 {gap} 分，跳过补播。", file=sys.stderr)
                else:
                    toks = range(self._last_min + 1, m + 1)
                for b in toks:
                    u = (b + 60) % 1440
                    p = u % 120
                    if p == 0:
                        self._fire("bell3")
                    elif p == 60:
                        self._fire("drum2")
                    elif p % 15 == 0:
                        self._fire("drum1")
            self._last_min = m
        self.st = st
        st["popup"] = self._popup
        self.da.queue_draw()
        return "flip" in st or 0.0 < st["blend"] < 1.0

    def _fire(self, name):
        """触发一声，带失败自愈：poll 回收上一发 paplay；连续失败先重建
        声样，仍败则转逐次诊断（同步取 paplay 的 stderr 打印给用户）。"""
        if self._last_play is not None:
            code = self._last_play.poll()   # 顺手收僵尸
            self._last_play = None
            if code is None:
                return                      # 上一声还在播，不叠发
            self._snd_fail = 0 if code == 0 else self._snd_fail + 1
        if self._snd_fail in (2, 4):
            print("报时声连续失败，重建声样。", file=sys.stderr)
            self._sounds = None
        if self._snd_fail >= 6:
            if self._snd_fail == 6:
                print("报时声持续失败，转入逐次诊断；请查音频输出设备。",
                      file=sys.stderr)
            _play(self._sounds, name, self._sound_debug, diag=True)
            self._snd_fail += 1
            return
        self._last_play = _play(self._sounds, name, self._sound_debug)

    def _on_draw(self, area, ctx, w, h):
        st = getattr(self, "st", None)
        if st is None:
            st = compute_state(datetime.datetime.now(), self.cfg, self.force_night)
            st["popup"] = self._popup
        pal = dial.lerp_pal(dial.DAY, dial.NIGHT, st["blend"])
        fonts = {"chen": self.cfg["fonts"]["chen"], "seal": self.cfg["fonts"]["seal"]}
        wm = self.cfg.get("panel", {}).get("watermark", "none")
        wa = float(self.cfg.get("panel", {}).get("watermark_alpha", 0.16))
        dial.draw_scene(ctx, w, h, st, pal, fonts, pointer=self._pointer,
                        watermark=None if wm == "none" else wm, wm_alpha=wa)


class App(Gtk.Application):
    def __init__(self, cfg, cfg_path, force_night=None):
        super().__init__(application_id="cn.workbuddy.kepan")
        self.cfg, self.cfg_path, self.force_night = cfg, cfg_path, force_night
        self.win = None

    def do_activate(self):
        # 单实例：二次启动唤起既有窗口
        if self.win is not None:
            self.win.present()
            return
        self.win = ClockWindow(self, self.cfg, self.cfg_path, self.force_night)
        self.win.present()


# ---------------------------------------------------------------- 壁画模式
class WallpaperWindow(Gtk.ApplicationWindow):
    """全屏置底窗。Wayland 无 layer-shell，取「全屏＋无饰＋沉底」近似；
    沉底只在映射时降一次层，被点活后不再与之争，按 Esc 退出。"""
    TICK_STEADY_MS = 6000      # 平稳档：六秒一换
    TICK_FADE_MS = 100         # 昼夜渐变档
    TICK_FLIP_MS = 33          # 动画档

    def __init__(self, app, cfg, force_night=None):
        super().__init__(application=app, title="刻盘钟·壁画")
        self.cfg = cfg
        self.force_night = force_night
        self.st = None
        self.set_decorated(False)
        self.fullscreen()

        self.da = Gtk.DrawingArea()
        self.da.set_draw_func(self._on_draw)
        self.da.set_cursor_from_name("none")
        self.da.set_focusable(True)
        self.set_child(self.da)

        kc = Gtk.EventControllerKey()
        kc.connect("key-pressed", self._key)
        self.da.add_controller(kc)

        self.connect("map", self._on_map)

    def _on_map(self, *a):
        surf = self.get_surface()
        if surf is not None and hasattr(surf, "lower"):
            try:
                surf.lower()
            except Exception as e:
                print(f"壁画沉底失败（{e}），仍为全屏窗。", file=sys.stderr)

    def _key(self, ctl, keyval, keycode, state):
        if keyval == Gdk.KEY_Escape:
            self.get_application().quit()
            return True
        return False

    def _schedule(self, ms):
        GLib.timeout_add(ms, self._tick)
        return GLib.SOURCE_REMOVE

    def _tick(self):
        try:
            st = compute_state(datetime.datetime.now(), self.cfg, self.force_night)
            self.st = st
            self.da.queue_draw()
            ms = self.TICK_FADE_MS if 0.0 < st["blend"] < 1.0 else self.TICK_STEADY_MS
        except Exception:
            _log_error("壁画 tick 异常，本跳沿用上次状态。")
            ms = self.TICK_STEADY_MS
        self._schedule(ms)
        return GLib.SOURCE_REMOVE

    def _on_draw(self, area, ctx, w, h):
        st = self.st or compute_state(datetime.datetime.now(), self.cfg,
                                      self.force_night)
        pal = dial.lerp_pal(dial.DAY, dial.NIGHT, st["blend"])
        fonts = {"chen": self.cfg["fonts"]["chen"], "seal": self.cfg["fonts"]["seal"]}
        dial.draw_wallpaper(ctx, w, h, st, pal, fonts)


class WallpaperApp(Gtk.Application):
    """壁画独立应用：应用号有别于时钟，可与时钟同时驻留。"""

    def __init__(self, cfg, force_night=None):
        super().__init__(application_id="cn.workbuddy.kepan.wallpaper")
        self.cfg = cfg
        self.force_night = force_night
        self.win = None

    def do_activate(self):
        if self.win is not None:
            return
        self.win = WallpaperWindow(self, self.cfg, self.force_night)
        self.win.present()
        self.win._tick()
        self.win.da.grab_focus()


def render_png(path, cfg, hhmm=None, force_night=None, pointer=None,
               popup=None, date=None, size=(960, 680), wm_alpha=None,
               wallpaper=False):
    import cairo
    now = datetime.datetime.now()
    if date:
        y, m, d = (int(v) for v in date.split("-"))
        now = now.replace(year=y, month=m, day=d)
    if hhmm:
        hh, mm = hhmm.split(":")[:2]
        now = now.replace(hour=int(hh), minute=int(mm), second=0)
    st = compute_state(now, cfg, force_night=force_night)
    st["popup"] = popup
    pal = dial.lerp_pal(dial.DAY, dial.NIGHT, st["blend"])
    fonts = {"chen": cfg["fonts"]["chen"], "seal": cfg["fonts"]["seal"]}
    if wm_alpha is None:
        wm_alpha = float(cfg.get("panel", {}).get("watermark_alpha", 0.16))
    W, H = size
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, W, H)
    c = cairo.Context(surf)
    if wallpaper:
        dial.draw_wallpaper(c, W, H, st, pal, fonts)
    else:
        wm = cfg.get("panel", {}).get("watermark", "none")
        dial.draw_scene(c, W, H, st, pal, fonts, pointer=pointer,
                        watermark=None if wm == "none" else wm, wm_alpha=wm_alpha)
    surf.write_to_png(path)
    print("PNG 已写：" + path)


def main():
    argv = sys.argv[1:]
    force_night = None
    png = None
    hhmm = None
    hover = None
    popup = None
    date = None
    size = (960, 680)
    wallpaper = False
    sound_test = False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--day":
            force_night = False
        elif a == "--night":
            force_night = True
        elif a == "--wallpaper":
            wallpaper = True
        elif a == "--png" and i + 1 < len(argv):
            i += 1
            png = argv[i]
        elif a == "--hhmm" and i + 1 < len(argv):
            i += 1
            hhmm = argv[i]
        elif a == "--date" and i + 1 < len(argv):
            i += 1
            date = argv[i]
        elif a == "--size" and i + 1 < len(argv):
            i += 1
            size = tuple(int(v) for v in argv[i].lower().split("x"))
        elif a == "--popup" and i + 1 < len(argv):
            i += 1
            popup = None if argv[i] in ("none", "off") else argv[i]
        elif a == "--hover" and i + 1 < len(argv):
            i += 1
            hover = tuple(float(v) for v in argv[i].split(","))
        elif a == "--sound-test":
            sound_test = True
        i += 1

    cfg, src = load_config()
    if sound_test:
        return _sound_test()
    if png:
        render_png(png, cfg, hhmm=hhmm, force_night=force_night, pointer=hover,
                   popup=popup, date=date, size=size, wallpaper=wallpaper)
        return
    # dock 图标配对：进程名对齐 desktop 的 StartupWMClass，窗口取主题图标
    GLib.set_prgname("kepan-clock")
    GLib.set_application_name("刻盘钟")
    Gtk.Window.set_default_icon_name("kepan-clock")
    if not seal_effective(cfg["fonts"]["seal"]):
        print("警告：篆体未生效，款识已回退楷体。请检查字体安装或 config.toml 的 [fonts].seal。",
              file=sys.stderr)
    if wallpaper:
        WallpaperApp(cfg, force_night).run()
        return
    App(cfg, src, force_night).run()


if __name__ == "__main__":
    main()
