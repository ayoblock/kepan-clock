#!/usr/bin/python3
# -*- coding: utf-8 -*-
"""刻盘钟·主程序（1.5.2）
用法：
  kepan-clock                正常启动（GTK4 窗口，单实例）
  kepan-clock --day|--night  锁定昼盘／夜盘启动
  kepan-clock --wallpaper    壁画模式：全屏置底窗，Esc 退出
  kepan-clock --png OUT.png [--hhmm 15:45] [--date 2026-11-10] [--size 960x680]
              [--popup three|month|settings] [--wallpaper] [--day|--night]
                             离屏渲染一张盘面存为 PNG 后退出（自检用）
配置查找次序：~/.config/kepan-clock/config.toml → 程序目录 config.toml；
配置保存后约半秒热重载，免重启。设置牌窗（右下「天行」字印）改即存。
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


def _toml_literal(v):
    import json
    return json.dumps(v, ensure_ascii=False)


def save_cfg_value(section, key, value):
    """把单项设置写回用户级配置（~/.config 恒定，即便当前读的是程序目录
    兜底配置），逐行替换保留注释；缺文件时先以安装目录模板作底。
    原子写入，写完由文件监视触发热重载，不在写入端手动刷新。"""
    p = pathlib.Path.home() / ".config" / "kepan-clock" / "config.toml"
    p.parent.mkdir(parents=True, exist_ok=True)
    if not p.exists():
        try:
            text = (_HERE / "config.toml").read_text(encoding="utf-8")
        except OSError:
            text = ""
        p.write_text(text, encoding="utf-8")
    lines = p.read_text(encoding="utf-8").splitlines()
    out = []
    cur = None
    done = False
    for ln in lines:
        s = ln.strip()
        if s.startswith("[") and s.endswith("]"):
            if cur == section and not done:
                out.append(f"{key} = {_toml_literal(value)}")   # 段内未找到，段尾补插
                done = True
            cur = s[1:-1].strip()
        elif cur == section and not done and s.split("=")[0].strip() == key:
            out.append(f"{key} = {_toml_literal(value)}")
            done = True
            continue
        out.append(ln)
    if not done:
        if cur != section:
            out.append("")
            out.append(f"[{section}]")
        out.append(f"{key} = {_toml_literal(value)}")
    tmp = p.with_name("config.toml.tmp")
    tmp.write_text("\n".join(out) + "\n", encoding="utf-8")
    os.replace(tmp, p)


_AUTO_PATH = (pathlib.Path.home() / ".config" / "autostart" /
              "cn.workbuddy.kepan.wallpaper.desktop")


def _wall_autostart_on():
    try:
        return "X-GNOME-Autostart-enabled=false" not in \
            _AUTO_PATH.read_text(encoding="utf-8")
    except OSError:
        return False


def _set_wall_autostart(on):
    """壁画开机自启：只写/改自启项，从不删除用户目录下的文件。"""
    _AUTO_PATH.parent.mkdir(parents=True, exist_ok=True)
    body = "\n".join([
        "[Desktop Entry]",
        "Type=Application",
        "Name=刻盘钟·壁画",
        "Comment=十二时辰盘钟动态壁画（刻盘钟）",
        "Exec=kepan-clock --wallpaper",
        "Icon=cn.workbuddy.kepan",
        "X-GNOME-Autostart-enabled=" + ("true" if on else "false"),
    ]) + "\n"
    tmp = _AUTO_PATH.with_name(_AUTO_PATH.name + ".tmp")
    tmp.write_text(body, encoding="utf-8")
    os.replace(tmp, _AUTO_PATH)


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
# 1.5.2 起改为运行时串行播放单声样，不再把多声预混进一个事件音：
# 事件名 -> 单声样序列；每声待上一声播完、再留 _STRIKE_GAP 静默后起。
_EVENTS = {
    "bell3": ("bell", "bell", "bell"),
    "drum2": ("drum", "drum"),
    "drum1": ("drum",),
}
_STRIKE_GAP = 0.20         # 声间留白（秒），自上一声播完起算
_STRIKE_POLL_MS = 20       # 播放完成轮询粒度（毫秒）


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
    """备齐单声样于 ~/.config/kepan-clock/sound/，返回 {kind: path}。
    来源依次：用户自备 bell.wav/drum.wav > 随包实录 > 合成兜底。
    1.5.2 起报时由 _StrikeQueue 运行时串行播放，不再预合成事件音；
    换自备声样即时生效，无需删除旧文件或重启。缺 paplay 则不可用。"""
    if not shutil.which("paplay"):
        return None
    d = _SOUND_DIR
    d.mkdir(parents=True, exist_ok=True)
    system = _HERE / "sounds"
    out = {}
    for kind in ("bell", "drum"):
        dst = d / f"{kind}.wav"
        if not dst.exists():
            cand = system / f"{kind}.wav"
            if cand.exists():
                shutil.copyfile(cand, dst)
            else:
                _write_wav(dst, _synth_strike(kind))
        out[kind] = dst
    try:
        with open(d / "声样来源.txt", "w") as f:
            note = system / "声样来源.txt"
            if note.exists():
                f.write(note.read_text())
    except OSError:
        pass
    return out


def _play_strike(paths, kind, debug=False, diag=False):
    """起 paplay 播一记单声样，返回 Popen 供上层轮询回收；diag=True 时
    同步运行一次并打印退出码与 stderr，用于持续失败时的诊断。"""
    if not paths or kind not in paths:
        if debug:
            print(f"报时声 {kind}：声样缺失，未播", file=sys.stderr)
        return None
    try:
        if diag:
            r = subprocess.run(["paplay", str(paths[kind])], capture_output=True,
                               text=True, timeout=10)
            err = r.stderr.strip()
            print(f"诊断 paplay {kind}：退出码 {r.returncode}"
                  + (f"，stderr：{err}" if err else ""), file=sys.stderr)
            return None
        p = subprocess.Popen(["paplay", str(paths[kind])],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if debug:
            import datetime as _dt
            print(f"[{_dt.datetime.now():%H:%M:%S}] 报时：{kind}", flush=True)
        return p
    except OSError as e:
        print(f"报时声 {kind}：paplay 启动失败（{e}）", file=sys.stderr)
        return None


class _StrikeQueue:
    """报时声串行队列（1.5.2）。

    paplay 会把音频 drain 完才退出（本机实测：1.0 s 音样，进程存活
    1.11 s；随包 bell.wav 3.00 s、drum.wav 1.50 s）。故以子进程退出
    为「播放完成」事件：上一声退出后隔 _STRIKE_GAP 再起下一声，不依赖
    任何硬编码音效时长，换任意长度的自备声样都不重叠。全程走 GLib 主
    循环轮询，不阻塞界面。
    """

    def __init__(self, get_paths, rebuild, get_debug):
        self._get_paths = get_paths
        self._rebuild = rebuild
        self._get_debug = get_debug
        self._seq = []
        self._running = False
        self._fail = 0

    def fire(self, kinds):
        if not kinds or self._running:
            return False                    # 空序列或上一轮未完，不叠发
        if self._fail >= 6:
            if self._fail == 6:
                print("报时声持续失败，转入逐次诊断；请查音频输出设备。",
                      file=sys.stderr)
            self._fail += 1
            _play_strike(self._get_paths(), kinds[0], self._get_debug(),
                         diag=True)
            return False
        self._seq = list(kinds)
        self._running = True
        self._step()
        return True

    def reset_fail(self):
        self._fail = 0

    def _step(self):
        if not self._seq:
            self._running = False
            return GLib.SOURCE_REMOVE
        kind = self._seq.pop(0)
        p = _play_strike(self._get_paths(), kind, self._get_debug())
        if p is None:
            self._seq = []
            self._running = False
            return GLib.SOURCE_REMOVE
        GLib.timeout_add(_STRIKE_POLL_MS, self._wait_done, p)
        return GLib.SOURCE_REMOVE

    def _wait_done(self, p):
        if p.poll() is None:
            return GLib.SOURCE_CONTINUE     # 仍在播，继续等
        if p.returncode == 0:
            self._fail = 0
        else:
            self._fail += 1
            if self._fail in (2, 4):
                print("报时声连续失败，重建声样。", file=sys.stderr)
                self._rebuild()
        if self._seq:
            GLib.timeout_add(int(_STRIKE_GAP * 1000), self._step)
        else:
            self._running = False
        return GLib.SOURCE_REMOVE


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
    """--sound-test：按运行时节律串行播钟三下，逐声打印全链路诊断。"""
    if not shutil.which("paplay"):
        print("诊断：未找到 paplay。请安装 pulseaudio-utils。", file=sys.stderr)
        return 1
    sounds = _ensure_sounds()
    if not sounds:
        print("诊断：声样合成失败（paplay 缺失）。", file=sys.stderr)
        return 1
    print("声样就绪：")
    for k, v in sounds.items():
        try:
            with wave.open(str(v), "rb") as wf:
                dur = wf.getnframes() / wf.getframerate()
            note = f"（{dur:.2f}s）"
        except Exception:
            note = "（时长未知）"
        print(f"  {k} -> {v}{note}")
    print(f"节律：播完一声，留白 {_STRIKE_GAP:.2f}s，再起下一声；共三声。")
    bad = 0
    for i in range(3):
        t0 = time.monotonic()
        r = subprocess.run(["paplay", str(sounds["bell"])], capture_output=True,
                           text=True, timeout=15)
        dt = time.monotonic() - t0
        err = r.stderr.strip()
        print(f"第 {i + 1} 声：退出码 {r.returncode}，进程存活 {dt:.2f}s"
              + (f"，stderr：{err}" if err else ""))
        if r.returncode != 0:
            bad += 1
        if i < 2:
            time.sleep(_STRIKE_GAP)
    if bad:
        print(f"{bad} 声失败，请查音频输出设备与 paplay 报错。",
              file=sys.stderr)
        return 1
    print("三声播完——若耳中无声，请查系统音量与输出设备。")
    return 0


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
        self._strikes = _StrikeQueue(self._sound_paths,
                                     lambda: setattr(self, "_sounds", None),
                                     lambda: self._sound_debug)
        self._alpha_drag = False   # 淡度滑轨拖动中：只改内存，松手落盘
        self._toast = None         # (文字, 失效时刻)
        self._seal_note = ("款识：篆书生效中"
                           if seal_effective(cfg["fonts"]["seal"])
                           else "款识：篆体未装·暂退楷体")
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
        gc.connect("released", self._released)
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
        if self._alpha_drag and self._popup == "settings":
            self._apply_alpha_xy(x)
        st = getattr(self, "st", None)
        if st is not None:
            hit = dial.hit_test(self.da.get_width(), self.da.get_height(), st, x, y)
            pointer = (hit in ("note", "footer", "seal", "settings")
                       or (isinstance(hit, str) and hit.startswith("set:")))
            name = "pointer" if pointer else "default"
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
        if hit == "seal":
            self._popup = None if self._popup == "settings" else "settings"
        elif isinstance(hit, str) and hit.startswith("set:"):
            self._on_setting(hit[4:])
        elif hit == "settings":
            return                       # 牌窗空白处：不收起
        elif hit == "note":
            self._popup = None if self._popup else "three"
        elif hit == "footer":
            self._popup = "three" if self._popup == "month" else "month"
        elif hit == "popup":
            return
        else:
            self._popup = None
        self.da.queue_draw()

    def _released(self, gesture, n_press, x, y):
        if self._alpha_drag:
            self._alpha_drag = False
            self._save("panel", "watermark_alpha",
                       self.cfg["panel"]["watermark_alpha"])
        return False

    def _key(self, ctl, keyval, keycode, state):
        if keyval == Gdk.KEY_Escape and self._popup:
            self._popup = None
            self.da.queue_draw()
            return True
        return False

    # ------------------------------------------------------------- 设置
    def _save(self, section, key, value):
        try:
            save_cfg_value(section, key, value)
        except OSError as e:
            _log_error(f"配置写回失败（{section}.{key}）：{e}")
            self._say("配置写回失败，详见 error.log")

    def _say(self, msg):
        self._toast = (msg, time.time() + 2.6)

    def _decorate(self, st):
        """把设置牌窗所需的视图状态挂到渲染状态上。"""
        st["cfg"] = self.cfg
        st["seal_note"] = self._seal_note
        st["wall_live"] = _wall_autostart_on()
        if self._toast:
            if time.time() < self._toast[1]:
                st["toast"] = self._toast
            else:
                self._toast = None
        return st

    def _apply_alpha_xy(self, x):
        sg = dial.settings_geom(self.da.get_width(), self.da.get_height())
        tx, _ty, tw, _th = sg["track"]
        a0, a1 = sg["alpha_range"]
        v = a0 + (x - tx) / max(tw, 1.0) * (a1 - a0)
        self.cfg["panel"]["watermark_alpha"] = round(min(max(v, a0), a1), 3)
        self.da.queue_draw()

    def _on_setting(self, key):
        cfg = self.cfg
        if key.startswith("theme:"):
            # CLI --day/--night 强制时主题仍受其钳制；常规启动以配置为准
            v = key.split(":", 1)[1]
            cfg["mode"]["theme"] = v
            self._save("mode", "theme", v)
        elif key.startswith("wm:"):
            v = key.split(":", 1)[1]
            cfg["panel"]["watermark"] = v
            self._save("panel", "watermark", v)
        elif key.startswith("font:"):
            v = dial.CHEN_KAI if key == "font:kai" else dial.CHEN_MING
            cfg["fonts"]["chen"] = v
            self._save("fonts", "chen", v)
        elif key.startswith("alpha:"):
            self._alpha_drag = True
            self.cfg["panel"]["watermark_alpha"] = float(key.split(":", 1)[1])
        elif key == "wall:live":
            self._toggle_wall_live()
        elif key == "wall:static":
            self._set_static_wall()
        self.da.queue_draw()

    def _toggle_wall_live(self):
        on = not _wall_autostart_on()
        try:
            _set_wall_autostart(on)
        except OSError as e:
            _log_error(f"自启项写入失败：{e}")
            self._say("自启项写入失败，详见 error.log")
            return
        if on:
            exe = shutil.which("kepan-clock")
            cmd = ([exe, "--wallpaper"] if exe
                   else ["/usr/bin/python3", str(_HERE / "app.py"), "--wallpaper"])
            try:
                subprocess.Popen(cmd, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL)
                self._say("壁画自启：开（已启动）")
            except OSError:
                self._say("壁画自启：开")
        else:
            self._say("壁画自启：关")

    def _set_static_wall(self):
        try:
            d = pathlib.Path.home() / ".local" / "share" / "kepan-clock"
            d.mkdir(parents=True, exist_ok=True)
            out = d / "wallpaper.png"
            W, H = 1920, 1080
            try:
                mon = Gdk.Display.get_default().get_monitors().get_item(0)
                geo = mon.get_geometry()
                W, H = max(640, geo.width), max(480, geo.height)
            except Exception:
                pass
            render_png(str(out), self.cfg, size=(W, H), wallpaper=True)
            if shutil.which("gsettings") is None:
                self._say(f"PNG 已存 {out}（未找到 gsettings）")
                return
            uri = out.resolve().as_uri()
            for key in ("picture-uri", "picture-uri-dark"):
                subprocess.run(["gsettings", "set",
                                "org.gnome.desktop.background", key, uri],
                               check=False, timeout=15)
            subprocess.run(["gsettings", "set", "org.gnome.desktop.background",
                            "picture-options", "zoom"],
                           check=False, timeout=15)
            self._say("已设为桌面壁纸（静态快照）")
        except Exception as e:
            _log_error(f"设静态壁纸失败：{e}\n{traceback.format_exc()}")
            self._say("设壁纸失败，详见 error.log")

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
            self._strikes.reset_fail()
            self._sound_debug = bool(cfg.get("sound", {}).get("debug", False))
            self._seal_note = ("款识：篆书生效中"
                               if seal_effective(cfg["fonts"]["seal"])
                               else "款识：篆体未装·暂退楷体")
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
        self._decorate(st)
        st["popup"] = self._popup
        self.da.queue_draw()
        return "flip" in st or 0.0 < st["blend"] < 1.0

    def _sound_paths(self):
        """声样路径惰性准备；热重载或失败重建后由下一声自动补齐。"""
        if self._sounds is None:
            self._sounds = _ensure_sounds()
        return self._sounds

    def _fire(self, name):
        """触发一次报时：按 _EVENTS 序列串行播放，播完一声再起下一声。"""
        self._strikes.fire(_EVENTS.get(name, ()))

    def _on_draw(self, area, ctx, w, h):
        st = getattr(self, "st", None)
        if st is None:
            st = compute_state(datetime.datetime.now(), self.cfg, self.force_night)
            self._decorate(st)
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
    st["cfg"] = cfg
    st["seal_note"] = ("款识：篆书生效中" if seal_effective(cfg["fonts"]["seal"])
                       else "款识：篆体未装·暂退楷体")
    st["wall_live"] = _wall_autostart_on()
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
