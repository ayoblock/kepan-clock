# -*- coding: utf-8 -*-
"""刻盘钟·盘面渲染（1.1.0）
主盘：辰字环（楷）＋初正环＋九十六刻环＋素环（星空三等、银河弱带、四象瓦当
线描）＋晷针（昼影夜晕）。副盘：八刻盘，刻针一辰一周，盘心生肖篆字。
面板：读数、更牌窗（翻牌动画、试时显示）、节气、对时、真太阳时、朱印、名例。
性能：静态层（环、字、刻线、四象、晕影）入 ImageSurface 缓存，逐秒只绘
星空与晷针；昼夜渐变期间（blend∈(0,1)）绕过缓存全量重绘。
"""
import math
import random

import cairo
import gi
gi.require_version("Pango", "1.0")
gi.require_version("PangoCairo", "1.0")
from gi.repository import Pango, PangoCairo  # noqa: E402

from chen_core import CHEN, needle_angle, side_angle, read_str  # noqa: E402
import lunar  # noqa: E402

SIDEREAL_MIN = 1436.07          # 恒星日（23时56分04秒）按分钟
NUM = "一二三四五六七八"

# ---------------------------------------------------------------- 配色
DAY = dict(
    bg=(0.969, 0.957, 0.918), ink=(0.165, 0.161, 0.149),
    faint=(0.44, 0.43, 0.40), hair=(0.66, 0.65, 0.61),
    cinnabar=(0.651, 0.227, 0.169), gold=(0.52, 0.40, 0.20),
    star=(0.60, 0.585, 0.55), needle=(0.165, 0.161, 0.149),
)
NIGHT = dict(
    bg=(0.104, 0.100, 0.088), ink=(0.85, 0.79, 0.64),
    faint=(0.50, 0.46, 0.37), hair=(0.30, 0.28, 0.23),
    cinnabar=(0.80, 0.32, 0.24), gold=(0.79, 0.66, 0.42),
    star=(0.52, 0.47, 0.36), needle=(0.85, 0.72, 0.45),
)


def lerp_pal(a, b, f):
    """两套配色线性插值，f∈[0,1]，f=0 为 a（昼）。"""
    out = {}
    for k in a:
        out[k] = tuple(a[k][i] + (b[k][i] - a[k][i]) * f for i in range(3))
    return out


# 四象：午上朱雀、酉右白虎、子下玄武、卯左苍龙（方位顺时针 0/90/180/270）
SIMEN = ((0, "朱雀", "que"), (90, "白虎", "hu"), (180, "玄武", "xuan"), (270, "苍龙", "long"))
MEDALLION_MIN = 14.0            # 圆牌半径低于此值回退篆字

# 星空：主星三等（固定种子，每次启动同形）
_rng = random.Random(20261104)
STARS = []
while len(STARS) < 40:
    phi = _rng.uniform(0.0, 360.0)
    r = _rng.uniform(0.400, 0.585)
    near_cardinal = any(abs(r - 0.50) < 0.14 and min((phi - c) % 360, (c - phi) % 360) < 26
                        for c, _, _ in SIMEN)
    if near_cardinal:
        continue
    mag = _rng.choice((0, 1, 1, 2))
    STARS.append((phi, r, (1.9, 1.3, 0.8)[mag], (0.85, 0.6, 0.35)[mag] * 0.7))
# 银河弱带：暗星沿一条斜贯素环的带聚集
GALAXY = []
for _ in range(56):
    phi = 65.0 + _rng.gauss(0.0, 26.0)
    if _rng.random() < 0.5:
        phi += 180.0
    GALAXY.append((phi % 360.0, _rng.uniform(0.405, 0.585),
                   _rng.uniform(0.4, 0.9), _rng.uniform(0.10, 0.26)))


# ---------------------------------------------------------------- 基元
def _pt(cx, cy, r, theta_deg):
    """角位自正上（午位）顺时针计。"""
    t = math.radians(theta_deg)
    return cx + r * math.sin(t), cy - r * math.cos(t)


def _mul(c, f):
    return tuple(min(1.0, v * f) for v in c)


def _text(ctx, x, y, s, size, families, color, align="c", ys=1.0):
    lay = PangoCairo.create_layout(ctx)
    fd = Pango.FontDescription()
    fd.set_family(families)
    fd.set_absolute_size(size * Pango.SCALE)
    lay.set_font_description(fd)
    lay.set_text(s, -1)
    lw, lh = lay.get_pixel_size()
    ox = x - lw / 2.0 if align == "c" else (x - lw if align == "r" else x)
    if ys != 1.0:
        ctx.save()
        ctx.translate(x, y)
        ctx.scale(1.0, max(0.02, ys))
        ctx.move_to(-lw / 2.0, -lh / 2.0)
        ctx.set_source_rgb(*color)
        PangoCairo.show_layout(ctx, lay)
        ctx.restore()
        return
    ctx.move_to(ox, y - lh / 2.0)
    ctx.set_source_rgb(*color)
    PangoCairo.show_layout(ctx, lay)


def _ring(ctx, cx, cy, r, color, lw=1.2):
    ctx.set_source_rgb(*color)
    ctx.set_line_width(lw)
    ctx.new_sub_path()
    ctx.arc(cx, cy, r, 0, 2 * math.pi)
    ctx.stroke()


def _line(ctx, x1, y1, x2, y2, color, lw=1.0):
    ctx.set_source_rgb(*color)
    ctx.set_line_width(lw)
    ctx.move_to(x1, y1)
    ctx.line_to(x2, y2)
    ctx.stroke()


def _round_rect(ctx, x, y, w, h, r):
    r = max(0.0, min(r, w / 2.0, h / 2.0))
    ctx.new_sub_path()
    ctx.arc(x + w - r, y + r, r, -math.pi / 2, 0)
    ctx.arc(x + w - r, y + h - r, r, 0, math.pi / 2)
    ctx.arc(x + r, y + h - r, r, math.pi / 2, math.pi)
    ctx.arc(x + r, y + r, r, math.pi, 1.5 * math.pi)
    ctx.close_path()


def _card(ctx, x, y, w, h, pal, k):
    """素纸卡片：淡影 + 纸底 + 外粗内细双边。"""
    ctx.set_source_rgba(0.0, 0.0, 0.0, 0.13)
    _round_rect(ctx, x + 1.0 * k, y + 3.0 * k, w, h, 6.0 * k)
    ctx.fill()
    ctx.set_source_rgb(*pal["bg"])
    _round_rect(ctx, x, y, w, h, 6.0 * k)
    ctx.fill()
    ctx.set_source_rgb(*pal["ink"])
    ctx.set_line_width(1.5 * k)
    _round_rect(ctx, x, y, w, h, 6.0 * k)
    ctx.stroke()
    ctx.set_source_rgb(*pal["faint"])
    ctx.set_line_width(0.7 * k)
    _round_rect(ctx, x + 3.5 * k, y + 3.5 * k, w - 7 * k, h - 7 * k, 4.0 * k)
    ctx.stroke()


def _u_angle(u):
    """辰内统一时 u（自前夜23时起的分钟）→ 顺时针角位（度）。"""
    t = (u - 60.0) % 1440.0
    return (t - 720.0) * 0.25


# ---------------------------------------------------------------- 四象线描（瓦当意）
# 路径 DSL：("M",x,y) ("L",x,y) ("C",x1,y1,x2,y2,x,y)，单位坐标 [-1,1]
_BEAST = {
    'long': [
        ('M', -0.62, 0.38),
        ('C', -0.55, 0.05, -0.3, -0.15, -0.02, -0.18),
        ('C', 0.22, -0.2, 0.38, -0.32, 0.4, -0.48),
        ('C', 0.41, -0.6, 0.52, -0.62, 0.58, -0.52),
        ('M', 0.34, -0.55),
        ('L', 0.4, -0.74),
        ('L', 0.48, -0.56),
        ('M', -0.05, -0.14),
        ('C', 0.02, 0.02, 0.0, 0.16, -0.1, 0.28),
        ('M', -0.62, 0.38),
        ('C', -0.74, 0.44, -0.8, 0.32, -0.72, 0.2),
    ],
    'hu': [
        ('M', -0.66, 0.26),
        ('C', -0.58, -0.3, -0.15, -0.52, 0.22, -0.44),
        ('C', 0.45, -0.38, 0.58, -0.24, 0.6, -0.08),
        ('L', 0.76, 0.0),
        ('L', 0.58, 0.1),
        ('C', 0.48, 0.14, 0.4, 0.1, 0.36, 0.04),
        ('M', -0.66, 0.26),
        ('C', -0.8, 0.34, -0.78, 0.48, -0.66, 0.5),
        ('M', -0.28, 0.02),
        ('L', -0.28, 0.42),
        ('M', 0.18, 0.02),
        ('L', 0.18, 0.4),
        ('M', 0.4, 0.06),
        ('L', 0.38, 0.36),
    ],
    'que': [
        ('M', -0.55, 0.5),
        ('C', -0.2, 0.3, 0.0, 0.0, 0.05, -0.28),
        ('C', 0.08, -0.44, 0.2, -0.55, 0.34, -0.52),
        ('C', 0.46, -0.49, 0.5, -0.38, 0.44, -0.3),
        ('M', 0.0, -0.1),
        ('C', 0.18, -0.02, 0.38, 0.0, 0.55, 0.04),
        ('M', -0.55, 0.5),
        ('C', -0.1, 0.6, 0.3, 0.66, 0.6, 0.8),
        ('M', -0.45, 0.38),
        ('C', -0.05, 0.48, 0.22, 0.54, 0.45, 0.66),
        ('M', -0.35, 0.26),
        ('C', -0.05, 0.34, 0.12, 0.38, 0.28, 0.48),
    ],
    'xuan': [
        ('M', 0.0, -0.34),
        ('C', 0.3, -0.32, 0.48, -0.1, 0.46, 0.12),
        ('C', 0.44, 0.36, 0.22, 0.52, -0.02, 0.5),
        ('C', -0.28, 0.48, -0.46, 0.28, -0.44, 0.04),
        ('C', -0.42, -0.2, -0.26, -0.36, 0.0, -0.34),
        ('M', 0.0, -0.32),
        ('L', -0.02, 0.48),
        ('M', -0.42, 0.08),
        ('L', 0.44, 0.1),
        ('M', -0.62, -0.56),
        ('C', 0.05, -0.8, 0.62, -0.55, 0.6, -0.1),
        ('C', 0.58, 0.2, 0.2, 0.3, 0.02, 0.16),
        ('M', -0.62, -0.56),
        ('L', -0.52, -0.44),
    ],
}


def _beast(ctx, cx, cy, mr, name, color, lw):
    ctx.set_source_rgb(*color)
    ctx.set_line_width(lw)
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    ctx.set_line_join(cairo.LINE_JOIN_ROUND)
    for seg in _BEAST[name]:
        op = seg[0]
        if op == "M":
            ctx.move_to(cx + seg[1] * mr, cy + seg[2] * mr)
        elif op == "L":
            ctx.line_to(cx + seg[1] * mr, cy + seg[2] * mr)
        else:
            x1, y1, x2, y2, x3, y3 = seg[1:]
            ctx.curve_to(cx + x1 * mr, cy + y1 * mr,
                         cx + x2 * mr, cy + y2 * mr,
                         cx + x3 * mr, cy + y3 * mr)
    ctx.stroke()


# ---------------------------------------------------------------- 静态层
def _dial_static(c, cx, cy, R, pal, fonts, night):
    chen_f, seal = fonts["chen"], fonts["seal"]
    ink, faint, hair = pal["ink"], pal["faint"], pal["hair"]

    # 素底晕影（纸色不匀之意，外缘微沉）
    g = cairo.RadialGradient(cx, cy, R * 0.15, cx, cy, R * 1.01)
    g.add_color_stop_rgba(0.0, *pal["bg"], 1.0)
    g.add_color_stop_rgba(1.0, *_mul(pal["bg"], 0.945), 1.0)
    c.set_source(g)
    c.arc(cx, cy, R * 1.01, 0, 2 * math.pi)
    c.fill()

    # 外缘弦纹两道
    _ring(c, cx, cy, R * 0.995, ink, max(1.6, R * 0.006))
    _ring(c, cx, cy, R * 0.958, ink, max(1.0, R * 0.003))

    # 分隔细线
    for rf in (0.795, 0.710, 0.625):
        _ring(c, cx, cy, R * rf, hair, 1.0)

    # 辰字环：一字三十度，字居辰之中，朱填
    for k, ch in enumerate(CHEN):
        x, y = _pt(cx, cy, R * 0.876, _u_angle(k * 120 + 60))
        _text(c, x, y, ch, R * 0.128, chen_f, pal["cinnabar"])

    # 初正环：微字二十四
    for k in range(12):
        for half, word in ((30, "初"), (90, "正")):
            x, y = _pt(cx, cy, R * 0.752, _u_angle(k * 120 + half))
            _text(c, x, y, word, R * 0.034, chen_f, faint)

    # 刻环：九十六刻线；夜盘刻线描金晕
    for i in range(96):
        th = _u_angle(i * 15)
        if i % 8 == 0:
            r0, lw_, col = 0.610, max(1.4, R * 0.005), ink
        elif i % 4 == 0:
            r0, lw_, col = 0.640, max(1.1, R * 0.0035), ink
        else:
            r0, lw_, col = 0.652, 1.0, faint
        x1, y1 = _pt(cx, cy, R * r0, th)
        x2, y2 = _pt(cx, cy, R * 0.710, th)
        if night:
            c.set_source_rgba(*pal["gold"], 0.16)
            c.set_line_width(lw_ * 2.6)
            c.move_to(x1, y1)
            c.line_to(x2, y2)
            c.stroke()
        _line(c, x1, y1, x2, y2, col, lw_)

    # 四正四象：瓦当线描兽形；圆牌过小则回退篆字
    for deg, name, key in SIMEN:
        mx, my = _pt(cx, cy, R * 0.50, deg)
        mr = R * 0.088
        _ring(c, mx, my, mr, hair, max(1.0, R * 0.004))
        _ring(c, mx, my, mr * 0.88, faint, 0.8)
        if mr >= MEDALLION_MIN:
            _beast(c, mx, my, mr * 0.62, key, pal["gold"], max(1.0, mr * 0.09))
        else:
            _text(c, mx, my, name[0], mr * 1.1, seal, pal["gold"])

    # 盘心：晷针轴帽与「司辰」篆款
    _text(c, cx, cy - R * 0.150, "司辰", R * 0.050, seal, pal["cinnabar"])


def _build_static(R, pal, fonts, night):
    S = int(2 * R + 8)
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, S, S)
    c = cairo.Context(surf)
    _dial_static(c, S / 2.0, S / 2.0, R, pal, fonts, night)
    surf.flush()
    return surf


_static_cache = {}


def _get_static(R, pal, fonts, night, cacheable):
    key = (round(R, 1), night, fonts["chen"], fonts["seal"],
           tuple(round(v, 4) for k in ("bg", "ink", "faint", "hair", "cinnabar", "gold")
                 for v in pal[k]))
    if cacheable:
        surf = _static_cache.get(key)
        if surf is not None:
            return surf
    surf = _build_static(R, pal, fonts, night)
    if cacheable:
        if len(_static_cache) > 6:
            _static_cache.clear()
        _static_cache[key] = surf
    return surf


# ---------------------------------------------------------------- 动态层
def _draw_stars(ctx, cx, cy, R, t_min, pal):
    rot = (t_min / SIDEREAL_MIN) * 360.0 % 360.0
    for phi, rf, sz, a in GALAXY:
        x, y = _pt(cx, cy, R * rf, phi + rot)
        ctx.set_source_rgba(*pal["star"], a)
        ctx.arc(x, y, sz, 0, 2 * math.pi)
        ctx.fill()
    for phi, rf, sz, a in STARS:
        x, y = _pt(cx, cy, R * rf, phi + rot)
        ctx.set_source_rgba(*pal["star"], a)
        ctx.arc(x, y, sz, 0, 2 * math.pi)
        ctx.fill()


def _needle_path(ctx, cx, cy, R, ang):
    tx, ty = _pt(cx, cy, R * 0.665, ang)
    bx, by = _pt(cx, cy, -R * 0.115, ang)
    px, py = _pt(cx, cy, R * 0.030, ang + 90)
    qx, qy = _pt(cx, cy, R * 0.030, ang - 90)
    ctx.move_to(tx, ty)
    ctx.line_to(qx, qy)
    ctx.line_to(bx, by)
    ctx.line_to(px, py)
    ctx.close_path()


def _draw_needle(ctx, cx, cy, R, t_min, pal, night):
    ang = needle_angle(t_min)
    off = R * 0.012
    ctx.save()
    ctx.translate(off, off * 1.3)
    if night:
        # 夜晕：针身宽描金
        ctx.set_source_rgba(*pal["gold"], 0.22)
        ctx.set_line_width(R * 0.020)
        ctx.set_line_join(cairo.LINE_JOIN_ROUND)
        _needle_path(ctx, cx, cy, R, ang)
        ctx.stroke()
    else:
        # 昼影：偏移半透明
        ctx.set_source_rgba(0.0, 0.0, 0.0, 0.20)
        _needle_path(ctx, cx, cy, R, ang)
        ctx.fill()
    ctx.restore()
    ctx.set_source_rgb(*pal["needle"])
    _needle_path(ctx, cx, cy, R, ang)
    ctx.fill()
    ctx.set_source_rgb(*pal["cinnabar"])
    ctx.new_sub_path()
    ctx.arc(cx, cy, R * 0.028, 0, 2 * math.pi)
    ctx.fill()
    ctx.set_source_rgb(*pal["bg"])
    ctx.new_sub_path()
    ctx.arc(cx, cy, R * 0.009, 0, 2 * math.pi)
    ctx.fill()


# ---------------------------------------------------------------- 副盘
def _dial_side(ctx, cx, cy, r, st, pal, fonts):
    chen_f, seal = fonts["chen"], fonts["seal"]
    ink, faint, hair = pal["ink"], pal["faint"], pal["hair"]
    _ring(ctx, cx, cy, r, ink, 1.6)
    _ring(ctx, cx, cy, r * 0.60, hair, 0.9)
    for i in range(8):
        th = i * 45.0
        x1, y1 = _pt(cx, cy, r * 0.60, th)
        x2, y2 = _pt(cx, cy, r * 0.97, th)
        _line(ctx, x1, y1, x2, y2, hair, 0.9)
        x3, y3 = _pt(cx, cy, r * 0.79, th)
        _text(ctx, x3, y3, NUM[i], r * 0.16, chen_f, ink)
    _text(ctx, cx, cy + r * 0.36, st["animal"], r * 0.22, seal, pal["cinnabar"])
    th = side_angle(st["t_min"])
    tx, ty = _pt(cx, cy, r * 0.88, th)
    bx, by = _pt(cx, cy, -r * 0.16, th)
    ctx.set_source_rgb(*pal["needle"])
    ctx.set_line_width(max(2.0, r * 0.035))
    ctx.move_to(bx, by)
    ctx.line_to(tx, ty)
    ctx.stroke()
    ctx.set_source_rgb(*pal["cinnabar"])
    ctx.new_sub_path()
    ctx.arc(cx, cy, r * 0.045, 0, 2 * math.pi)
    ctx.fill()


# ---------------------------------------------------------------- 面板
_PANEL_H = 566.0   # 面板设计基准高（k=1 时），缩放后列高为 _PANEL_H*k
_NOTE_Y = 276.0    # 历注行中线（可点，弹出气泡）
_SEAL_Y = 302.0    # 朱印上缘
_NAME_Y = 374.0    # 名例首行
_SIDE_Y = 482.0    # 八刻副盘心
_POPUP_GAP = 7.0   # 气泡与锚点的间隙（设计基准）


_ZODIAC = {}
_ZODIAC["shu"] = [
    ("c", -0.12, 0.15, 0.27), ("c", 0.18, -0.02, 0.17),
    ("t", 0.30, -0.14, 0.56, 0.02, 0.30, 0.12),
    ("c", 0.14, -0.26, 0.11),
    ("a", -0.38, 0.44, 0.16, 270, 180, 0.07),
    ("q", -0.30, 0.36, -0.32, 0.54, -0.22, 0.54, -0.20, 0.36),
    ("q", 0.10, 0.30, 0.10, 0.50, 0.18, 0.50, 0.18, 0.30),
]

_ZODIAC["niu"] = [
    ("c", -0.15, 0.10, 0.30), ("c", 0.30, -0.12, 0.20),
    ("q", 0.44, -0.16, 0.58, -0.10, 0.58, 0.02, 0.44, 0.04),
    ("a", 0.36, -0.44, 0.14, 0, 90, 0.09),
    ("a", 0.24, -0.44, 0.14, 90, 180, 0.09),
    ("c", 0.16, -0.24, 0.06),
    ("q", -0.32, 0.34, -0.34, 0.52, -0.24, 0.52, -0.22, 0.34),
    ("q", 0.16, 0.30, 0.14, 0.52, 0.24, 0.52, 0.26, 0.30),
]

_ZODIAC["hu"] = [
    ("c", -0.10, 0.12, 0.30), ("c", 0.30, -0.14, 0.22),
    ("t", 0.18, -0.30, 0.22, -0.46, 0.30, -0.32),
    ("t", 0.34, -0.34, 0.42, -0.46, 0.44, -0.30),
    ("q", 0.44, -0.10, 0.58, -0.04, 0.56, 0.06, 0.44, 0.06),
    ("a", -0.38, 0.02, 0.18, 90, 180, 0.09),
    ("q", -0.34, 0.36, -0.36, 0.52, -0.26, 0.52, -0.24, 0.36),
    ("q", 0.02, 0.28, 0.00, 0.46, 0.10, 0.46, 0.12, 0.28),
]

_ZODIAC["tu"] = [
    ("c", -0.10, 0.18, 0.30), ("c", 0.18, -0.10, 0.18),
    ("q", 0.20, -0.22, 0.26, -0.52, 0.36, -0.50, 0.34, -0.20),
    ("q", 0.36, -0.20, 0.44, -0.48, 0.54, -0.42, 0.48, -0.16),
    ("c", -0.40, 0.30, 0.08),
    ("q", -0.26, 0.38, -0.28, 0.54, -0.18, 0.54, -0.16, 0.38),
]

_ZODIAC["long"] = [
    ("l", (-0.55, 0.40, -0.42, 0.22, -0.36, 0.00, -0.24, -0.12, -0.04, -0.12, 0.08, -0.26, 0.12, -0.44), 0.15),
    ("c", 0.14, -0.50, 0.13),
    ("t", 0.08, -0.56, 0.02, -0.74, 0.22, -0.58),
    ("l", (-0.30, 0.08, -0.36, 0.26), 0.07),
    ("l", (0.00, -0.04, 0.06, 0.14), 0.07),
]

_ZODIAC["she"] = [
    ("l", (-0.48, 0.34, -0.34, 0.44, -0.18, 0.40, -0.10, 0.28, -0.14, 0.16, -0.04, 0.08, 0.10, 0.10, 0.20, 0.20, 0.34, 0.18), 0.11),
    ("c", 0.40, 0.16, 0.09),
    ("l", (0.48, 0.16, 0.60, 0.13), 0.045),
]

_ZODIAC["ma"] = [
    ("c", -0.22, 0.12, 0.24), ("c", 0.10, 0.00, 0.18),
    ("q", 0.16, -0.10, 0.24, -0.36, 0.34, -0.32, 0.28, -0.06),
    ("c", 0.34, -0.42, 0.11),
    ("t", 0.40, -0.50, 0.54, -0.44, 0.40, -0.36),
    ("t", 0.22, -0.16, 0.20, -0.30, 0.28, -0.20),
    ("t", 0.28, -0.22, 0.28, -0.36, 0.34, -0.26),
    ("a", -0.44, 0.00, 0.14, 90, 180, 0.08),
    ("q", -0.10, 0.22, -0.16, 0.52, -0.06, 0.52, 0.00, 0.22),
    ("q", 0.14, 0.16, 0.14, 0.50, 0.24, 0.50, 0.26, 0.16),
    ("q", -0.32, 0.28, -0.38, 0.54, -0.28, 0.54, -0.22, 0.28),
    ("q", 0.28, 0.14, 0.28, 0.44, 0.38, 0.44, 0.40, 0.14),
]

_ZODIAC["yang"] = [
    ("c", -0.12, 0.12, 0.28), ("c", 0.22, -0.12, 0.19),
    ("a", 0.16, -0.44, 0.20, 90, 270, 0.12),
    ("a", 0.16, -0.44, 0.10, 270, 0, 0.09),
    ("q", -0.30, 0.34, -0.32, 0.52, -0.22, 0.52, -0.20, 0.34),
    ("q", 0.16, 0.32, 0.14, 0.52, 0.24, 0.52, 0.26, 0.32),
]

_ZODIAC["hou"] = [
    ("c", 0.10, -0.20, 0.20), ("c", -0.08, 0.10, 0.24),
    ("l", (0.06, 0.00, 0.26, 0.14, 0.32, 0.34), 0.10),
    ("l", (-0.30, 0.14, -0.50, 0.22, -0.54, 0.04, -0.44, -0.06), 0.07),
    ("q", -0.22, 0.28, -0.24, 0.52, -0.14, 0.52, -0.12, 0.28),
    ("q", 0.02, 0.30, 0.02, 0.52, 0.12, 0.52, 0.12, 0.30),
]

_ZODIAC["ji"] = [
    ("c", -0.06, 0.14, 0.24), ("c", 0.20, -0.12, 0.15),
    ("t", 0.14, -0.26, 0.16, -0.38, 0.20, -0.28),
    ("t", 0.20, -0.28, 0.24, -0.40, 0.28, -0.28),
    ("t", 0.28, -0.28, 0.32, -0.36, 0.34, -0.24),
    ("t", 0.36, -0.20, 0.50, -0.16, 0.36, -0.12),
    ("a", -0.10, 0.06, 0.14, 180, 270, 0.10),
    ("a", -0.10, 0.06, 0.20, 180, 270, 0.10),
    ("a", -0.10, 0.06, 0.26, 180, 270, 0.09),
    ("q", -0.18, 0.34, -0.20, 0.54, -0.12, 0.54, -0.10, 0.34),
    ("q", 0.06, 0.34, 0.06, 0.54, 0.14, 0.54, 0.16, 0.34),
]

_ZODIAC["gou"] = [
    ("c", -0.12, 0.12, 0.26), ("c", 0.26, -0.10, 0.17),
    ("q", 0.40, -0.14, 0.54, -0.10, 0.54, 0.00, 0.40, 0.04),
    ("q", 0.14, -0.26, 0.10, -0.06, 0.20, -0.04, 0.26, -0.24),
    ("a", -0.38, 0.02, 0.14, 90, 0, 0.09),
    ("q", -0.30, 0.32, -0.32, 0.52, -0.22, 0.52, -0.20, 0.32),
    ("q", 0.00, 0.26, -0.02, 0.50, 0.08, 0.50, 0.10, 0.26),
]

_ZODIAC["zhu"] = [
    ("c", -0.12, 0.14, 0.31), ("c", 0.24, -0.04, 0.21),
    ("c", 0.48, 0.02, 0.10),
    ("a", -0.46, 0.16, 0.07, 90, 270, 0.05),
    ("t", 0.14, -0.24, 0.20, -0.40, 0.28, -0.22),
    ("t", 0.30, -0.22, 0.34, -0.36, 0.40, -0.20),
    ("q", -0.32, 0.34, -0.34, 0.52, -0.24, 0.52, -0.22, 0.34),
    ("q", 0.10, 0.28, 0.08, 0.52, 0.18, 0.52, 0.20, 0.28),
]

_ZODIAC_ORDER = "鼠牛虎兔龙蛇马羊猴鸡狗猪"   # 与十二辰序对齐
_ZODIAC_KEY = dict(zip(_ZODIAC_ORDER, _ZODIAC))   # 汉字 → 部件表键


def _zodiac(ctx, cx, cy, mr, name, color):
    """生肖剪影（部件拼装：圆 / 三角 / 四边 / 粗弧 / 折线）。"""
    ctx.set_source_rgb(*color)
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    ctx.set_line_join(cairo.LINE_JOIN_ROUND)
    for p in _ZODIAC[name]:
        k = p[0]
        if k == "c":
            ctx.new_sub_path()
            ctx.arc(cx + p[1] * mr, cy + p[2] * mr, p[3] * mr, 0, 2 * math.pi)
            ctx.fill()
        elif k == "t":
            ctx.move_to(cx + p[1] * mr, cy + p[2] * mr)
            ctx.line_to(cx + p[3] * mr, cy + p[4] * mr)
            ctx.line_to(cx + p[5] * mr, cy + p[6] * mr)
            ctx.close_path()
            ctx.fill()
        elif k == "q":
            pts = p[1:]
            ctx.move_to(cx + pts[0] * mr, cy + pts[1] * mr)
            for i in range(2, len(pts), 2):
                ctx.line_to(cx + pts[i] * mr, cy + pts[i + 1] * mr)
            ctx.close_path()
            ctx.fill()
        elif k == "a":
            ctx.set_line_width(p[6] * mr)
            ctx.new_sub_path()
            ctx.arc(cx + p[1] * mr, cy + p[2] * mr, p[3] * mr,
                    math.radians(p[4]), math.radians(p[5]))
            ctx.stroke()
        elif k == "l":
            pts, w = p[1], p[2]
            ctx.set_line_width(w * mr)
            ctx.move_to(cx + pts[0] * mr, cy + pts[1] * mr)
            for i in range(2, len(pts), 2):
                ctx.line_to(cx + pts[i] * mr, cy + pts[i + 1] * mr)
            ctx.stroke()


def _arc_pts(cx, cy, r, a0, a1, n=12):
    """弧线采样点（cairo 角：+x 起、顺时针，y 向下为正）。"""
    a0, a1 = a0 % 360.0, a1 % 360.0
    if a1 <= a0:
        a1 += 360.0
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n)))
            for i in range(n + 1)]


_BBOX_CACHE = {}


def _zodiac_bbox(name):
    """生肖剪影的归一化包围盒 (x0, y0, x1, y1)，粗线按半宽外扩。"""
    v = _BBOX_CACHE.get(name)
    if v is not None:
        return v
    xs, ys = [], []
    for p in _ZODIAC[name]:
        t = p[0]
        if t == "c":
            xs += [p[1] - p[3], p[1] + p[3]]
            ys += [p[2] - p[3], p[2] + p[3]]
        elif t == "t":
            xs += [p[1], p[3], p[5]]
            ys += [p[2], p[4], p[6]]
        elif t == "q":
            xs += [p[i] for i in range(1, len(p), 2)]
            ys += [p[i] for i in range(2, len(p), 2)]
        elif t == "a":
            hw = p[6] / 2.0
            pts = _arc_pts(p[1], p[2], p[3], p[4], p[5])
            xs += [q[0] for q in pts] + [p[1] - p[3] - hw, p[1] + p[3] + hw]
            ys += [q[1] for q in pts] + [p[2] - p[3] - hw, p[2] + p[3] + hw]
        elif t == "l":
            pts, hw = p[1], p[2] / 2.0
            xs += [q for q in pts[0::2]]
            ys += [q for q in pts[1::2]]
            xs = [min(xs) - hw, max(xs) + hw]
            ys = [min(ys) - hw, max(ys) + hw]
    box = (min(xs), min(ys), max(xs), max(ys))
    if len(_BBOX_CACHE) > 24:
        _BBOX_CACHE.clear()
    _BBOX_CACHE[name] = box
    return box


def _watermark(ctx, cx, bottom, radius, st, pal, fonts, kind, alpha=0.16):
    """面板背景水印：幅面与小表盘同径，下缘踩住历注下线。
    zodiac 当前时辰生肖剪影（随辰换兽）/ beast 四神兽 / chen 辰字大篆。"""
    idx = int((st["t_min"] + 60.0) // 120.0) % 12
    ctx.push_group()
    if kind == "zodiac":
        name = _ZODIAC_KEY[_ZODIAC_ORDER[idx]]
        x0, y0, x1, y1 = _zodiac_bbox(name)
        span = max(x1 - x0, y1 - y0)
        scale = (2.0 * radius) / span if span > 0.01 else radius
        ox = cx - (x0 + x1) / 2.0 * scale
        oy = bottom - y1 * scale
        _zodiac(ctx, ox, oy, scale, name, pal["faint"])
    elif kind == "beast":
        cy = bottom - radius
        key = ("xuan", "long", "que", "hu")[int(st["t_min"] // 180.0) % 4]
        _ring(ctx, cx, cy, radius, pal["faint"], max(1.0, radius * 0.02))
        _beast(ctx, cx, cy, radius * 0.68, key, pal["faint"], max(1.5, radius * 0.04))
    else:  # chen：当前辰字大篆
        _text(ctx, cx, bottom - radius, CHEN[idx], radius * 1.45,
              fonts["seal"], pal["faint"])
    ctx.pop_group_to_source()
    ctx.paint_with_alpha(max(0.02, min(0.45, alpha)))


def _panel(ctx, x, y, w, st, pal, fonts, k=1.0, watermark=None, wm_alpha=0.16):
    """右列自上而下：读数、更牌窗（翻牌/试时）、节气、对时、真太阳时、
    历注（可点）、朱印、名例、八刻副盘。全列居中锚定，随 k 等比缩放。"""
    chen_f, seal = fonts["chen"], fonts["seal"]
    ink, faint = pal["ink"], pal["faint"]
    cx = x + w / 2.0
    side_r = min(76.0 * k, w * 0.30)      # 八刻副盘半径，水印同径

    if watermark:
        _watermark(ctx, cx, y + (_NOTE_Y + 9.0) * k, side_r, st, pal, fonts,
                   watermark, wm_alpha)

    _text(ctx, cx, y + 40 * k, st["read"], 42 * k, chen_f, ink)

    # 更牌窗
    bx, by, bw, bh = x + 10 * k, y + 92 * k, w - 20 * k, 62 * k
    ctx.set_source_rgb(*pal["bg"])
    ctx.rectangle(bx, by, bw, bh)
    ctx.fill()
    ctx.set_source_rgb(*pal["ink"])
    ctx.set_line_width(1.6 * k)
    ctx.rectangle(bx, by, bw, bh)
    ctx.stroke()
    ctx.set_source_rgb(*pal["faint"])
    ctx.set_line_width(0.7 * k)
    ctx.rectangle(bx + 4 * k, by + 4 * k, bw - 8 * k, bh - 8 * k)
    ctx.stroke()

    if st.get("hover"):
        _text(ctx, cx, by + bh / 2.0, "试时·" + st["hover"], 22 * k, chen_f, pal["gold"])
    else:
        flip = st.get("flip")
        if flip is None:
            _text(ctx, cx, by + bh / 2.0, st["geng"], 28 * k, chen_f, ink)
        else:
            t01, old = flip
            ys = abs(math.cos(math.pi * t01))
            word = old if t01 < 0.5 else st["geng"]
            _text(ctx, cx, by + bh / 2.0, word, 28 * k, chen_f, ink, ys=ys)

    # 节气（篆）、公历对时、真太阳时
    _text(ctx, cx, y + 196 * k, st["term"], 24 * k, seal, pal["gold"])
    _text(ctx, cx, y + 228 * k, st["date_str"] + "  " + st["hhmm"], 13 * k, chen_f, faint)
    _text(ctx, cx, y + 252 * k, "真太阳时 " + st["ts_read"], 13 * k, chen_f, pal["gold"])

    # 历注：常驻一行，点按弹出气泡（下划线示意可点）
    note = st.get("note") or ""
    if note:
        lit = bool(st.get("note_hover")) or bool(st.get("popup"))
        _text(ctx, cx, y + _NOTE_Y * k, note, 11 * k, chen_f, ink if lit else faint)
        uy = y + (_NOTE_Y + 9.0) * k
        x0, x1 = cx - w * 0.33, cx + w * 0.33
        if lit:
            _line(ctx, x0, uy, x1, uy, pal["cinnabar"], 1.0 * k)
        else:
            ctx.set_source_rgba(*pal["hair"], 0.95)
            ctx.set_line_width(1.0 * k)
            x = x0
            while x < x1:
                ctx.move_to(x, uy)
                ctx.line_to(min(x + 2.0 * k, x1), uy)
                x += 4.5 * k
            ctx.stroke()

    # 朱印
    ss = 50 * k
    sx, sy = cx - ss / 2.0, y + _SEAL_Y * k
    ctx.set_source_rgb(*pal["cinnabar"])
    ctx.rectangle(sx, sy, ss, ss)
    ctx.fill()
    _text(ctx, cx, sy + ss * 0.30, "司", 19 * k, seal, pal["bg"])
    _text(ctx, cx, sy + ss * 0.71, "辰", 19 * k, seal, pal["bg"])

    # 名例一行
    _text(ctx, cx, y + _NAME_Y * k, "观天之道，执天之行，尽矣！", 11 * k, chen_f, faint)

    # 八刻副盘
    r = side_r
    _dial_side(ctx, cx, y + _SIDE_Y * k, r, st, pal, fonts)


# ---------------------------------------------------------------- 历注气泡
_ALM_CACHE = {}
_POP_W = 320.0            # 气泡基准宽（k=1）
_POP_H3 = 168.0           # 三日条基准高
_POP_HM = 56.0 + 30.0 * 6 + 44.0   # 整月历基准高（六行）


def _almanac(d):
    """按日缓存气泡数据；跨日自动换新。"""
    v = _ALM_CACHE.get(d)
    if v is not None:
        return v
    try:
        v = lunar.almanac(d)
    except (ValueError, RuntimeError, KeyError):
        return None
    if len(_ALM_CACHE) > 6:
        _ALM_CACHE.clear()
    _ALM_CACHE[d] = v
    return v


def _layout(w, h):
    """盘面与面板的几何，绘制与命中测试共此一份，避免两处走形。"""
    R = max(60.0, min((h - 72.0) / 2.0, (w - 292.0) / 2.0))
    pw = max(200.0, min(480.0, w * 0.30))
    k = max(0.85, min(pw / 260.0, (h - 60.0) / _PANEL_H, 2.2))
    content_w = 2.0 * R + 28.0 + pw
    x_left = max(0.0, (w - content_w) / 2.0)
    cx, cy = x_left + R, h / 2.0
    px = cx + R + 28.0
    py0 = max(30.0, (h - _PANEL_H * k) / 2.0)
    ny = py0 + _NOTE_Y * k
    return dict(R=R, pw=pw, k=k, cx=cx, cy=cy, px=px, py0=py0, note_y=ny,
                note_rect=(px + 4.0 * k, ny - 12.0 * k, pw - 8.0 * k, 24.0 * k),
                anchor=(px + pw / 2.0, ny + 12.0 * k))


def _popup_geom(w, h, lay, kind, alm=None):
    """卡片位置：下 → 上 → 右 → 左 四向避让，皆不容则钳制于窗内。"""
    k, pw = lay["k"], lay["pw"]
    rows = max(5, len(alm["month"]) // 7) if alm else 6
    bh = (_POP_H3 if kind != "month" else 56.0 + 30.0 * rows + 44.0) * k
    bw = max(_POP_W * k, pw * 1.14)
    bw = min(bw, max(200.0, w - 16.0))
    bh = min(bh, max(120.0, h - 16.0))
    ax, ay = lay["anchor"]
    gap = _POPUP_GAP * k
    trials = (("down", ax - bw / 2.0, ay + gap),
              ("up", ax - bw / 2.0, ay - gap - bh),
              ("right", ax + gap, ay - bh / 2.0),
              ("left", ax - gap - bw, ay - bh / 2.0))
    x = y = None
    side = "down"
    for s, tx, ty in trials:
        if tx >= 8.0 and ty >= 8.0 and tx + bw <= w - 8.0 and ty + bh <= h - 8.0:
            x, y, side = tx, ty, s
            break
    if x is None:
        x = min(max(8.0, trials[0][1]), max(8.0, w - 8.0 - bw))
        y = min(max(8.0, trials[0][2]), max(8.0, h - 8.0 - bh))
    return dict(x=x, y=y, bw=bw, bh=bh, k=k, side=side)


def _leader(ctx, ax, ay, g, pal):
    """锚点到卡片最近边的引线，兼示「由此展开」。"""
    x, y, bw, bh = g["x"], g["y"], g["bw"], g["bh"]
    if g["side"] == "up":
        ex, ey = ax, y + bh
    elif g["side"] == "right":
        ex, ey = x, ay
    elif g["side"] == "left":
        ex, ey = x + bw, ay
    else:
        ex, ey = ax, y
    _line(ctx, ax, ay, ex, ey, pal["ink"], 1.0 * g["k"])
    ctx.set_source_rgb(*pal["ink"])
    ctx.new_sub_path()
    ctx.arc(ax, ay, 1.7 * g["k"], 0, 2 * math.pi)
    ctx.fill()


def _popup_three(ctx, g, alm, pal, fonts):
    """一级：以今日为中，昨／今／明三日条。"""
    x, y, bw, bh, k = g["x"], g["y"], g["bw"], g["bh"], g["k"]
    chen_f, seal = fonts["chen"], fonts["seal"]
    pad = 11.0 * k
    _text(ctx, x + pad, y + 15.0 * k, "历 注", 10.5 * k, seal, pal["gold"], align="l")
    _text(ctx, x + bw - pad, y + 15.0 * k, "按此展全月", 8.0 * k, chen_f,
          pal["faint"], align="r")
    _line(ctx, x + pad, y + 30.0 * k, x + bw - pad, y + 30.0 * k, pal["hair"], 0.8 * k)

    cw = (bw - 2.0 * pad) / 3.0
    for i, (d, lum, gz, hh, wy) in enumerate(alm["three"]):
        left = x + pad + cw * i
        ccx = left + cw / 2.0
        today = (d == alm["today"])
        if i:
            _line(ctx, left, y + 40.0 * k, left, y + 126.0 * k, pal["hair"], 0.6 * k)
        if today:
            ctx.set_source_rgba(*pal["cinnabar"], 0.07)
            _round_rect(ctx, left + 3.0 * k, y + 35.0 * k, cw - 6.0 * k, 96.0 * k, 3.0 * k)
            ctx.fill()
            ctx.set_source_rgba(*pal["cinnabar"], 0.55)
            ctx.set_line_width(0.9 * k)
            _round_rect(ctx, left + 3.0 * k, y + 35.0 * k, cw - 6.0 * k, 96.0 * k, 3.0 * k)
            ctx.stroke()
        _text(ctx, ccx, y + 50.0 * k, wy + "曜", 8.0 * k, chen_f, pal["faint"])
        _text(ctx, ccx, y + 74.0 * k, lum, 15.0 * k, chen_f,
              pal["cinnabar"] if today else pal["ink"])
        _text(ctx, ccx, y + 96.0 * k, gz + "日", 10.0 * k, chen_f, pal["faint"])
        _text(ctx, ccx, y + 116.0 * k, hh, 8.5 * k, chen_f, pal["gold"])

    _line(ctx, x + pad, y + bh - 28.0 * k, x + bw - pad, y + bh - 28.0 * k,
          pal["hair"], 0.8 * k)
    _text(ctx, x + bw / 2.0, y + bh - 14.0 * k, "展全月 ▾", 8.5 * k, chen_f, pal["faint"])


def _popup_month(ctx, g, alm, pal, fonts):
    """二级：整月历（公历月为格，朔日与节气朱标，今日朱框）。"""
    x, y, bw, bh, k = g["x"], g["y"], g["bw"], g["bh"], g["k"]
    chen_f, seal = fonts["chen"], fonts["seal"]
    pad = 11.0 * k
    _text(ctx, x + pad, y + 15.0 * k, alm["solar_title"], 10.5 * k, chen_f,
          pal["ink"], align="l")
    _text(ctx, x + bw - pad, y + 15.0 * k, alm["lunar_title"], 9.0 * k, seal,
          pal["gold"], align="r")
    _line(ctx, x + pad, y + 30.0 * k, x + bw - pad, y + 30.0 * k, pal["hair"], 0.8 * k)

    cw = (bw - 2.0 * pad) / 7.0
    cells = alm["month"]
    rows = max(5, len(cells) // 7)
    gtop = y + 56.0 * k
    gbot = gtop + rows * 30.0 * k
    for i, wd in enumerate(alm["week"]):
        _text(ctx, x + pad + cw * (i + 0.5), y + 43.0 * k, wd, 8.5 * k,
              chen_f, pal["faint"])
    ctx.set_source_rgba(*pal["hair"], 0.55)
    ctx.set_line_width(0.6 * k)
    for i in range(8):
        vx = x + pad + cw * i
        ctx.move_to(vx, gtop)
        ctx.line_to(vx, gbot)
    for r in range(1, rows):
        hy = gtop + r * 30.0 * k
        ctx.move_to(x + pad, hy)
        ctx.line_to(x + bw - pad, hy)
    ctx.stroke()

    for idx, (d, lab, term) in enumerate(cells):
        if d is None:
            continue
        r, c = idx // 7, idx % 7
        ccx = x + pad + cw * (c + 0.5)
        top = gtop + r * 30.0 * k
        if d == alm["today"]:
            ctx.set_source_rgba(*pal["cinnabar"], 0.11)
            _round_rect(ctx, x + pad + cw * c + 1.5 * k, top + 0.5 * k,
                        cw - 3.0 * k, 29.0 * k, 3.0 * k)
            ctx.fill()
        _text(ctx, ccx, top + 11.0 * k, str(d.day), 9.5 * k, chen_f, pal["ink"])
        label = term or lab
        col = pal["cinnabar"] if (term or lab.endswith("月")) else pal["faint"]
        _text(ctx, ccx, top + 23.0 * k, label, 7.0 * k, chen_f, col)

    _line(ctx, x + pad, y + bh - 36.0 * k, x + bw - pad, y + bh - 36.0 * k,
          pal["hair"], 0.8 * k)
    _text(ctx, x + bw / 2.0, y + bh - 18.0 * k, "收 ▲", 8.5 * k, chen_f, pal["faint"])


def _popup(ctx, w, h, lay, kind, st, pal, fonts):
    alm = _almanac(st["date"])
    if alm is None:
        return
    g = _popup_geom(w, h, lay, kind, alm)
    _leader(ctx, lay["anchor"][0], lay["anchor"][1], g, pal)
    _card(ctx, g["x"], g["y"], g["bw"], g["bh"], pal, g["k"])
    if kind == "month":
        _popup_month(ctx, g, alm, pal, fonts)
    else:
        _popup_three(ctx, g, alm, pal, fonts)


def hit_test(w, h, st, x, y):
    """点击命中：返回 "note"（历注行）/ "popup"（气泡内）/ "footer"（气泡底栏）/ None。"""
    lay = _layout(w, h)
    nx, ny, nw, nh = lay["note_rect"]
    if nx <= x <= nx + nw and ny <= y <= ny + nh:
        return "note"
    kind = st.get("popup")
    if kind:
        alm = _almanac(st["date"])
        g = _popup_geom(w, h, lay, kind, alm)
        if g["x"] <= x <= g["x"] + g["bw"] and g["y"] <= y <= g["y"] + g["bh"]:
            if y >= g["y"] + g["bh"] - 28.0 * g["k"]:
                return "footer"
            return "popup"
    return None


# ---------------------------------------------------------------- 壁画构图
def _vtext(ctx, x, y, s, size, families, color, align="l"):
    """竖排题款：一字一行，行距 1.28 倍字高。"""
    step = size * 1.28
    for i, ch in enumerate(s):
        _text(ctx, x, y + (i + 0.5) * step, ch, size, families, color, align=align)


def draw_wallpaper(ctx, w, h, st, pal, fonts):
    """壁画构图：大盘居中（径 0.72×短边），四角竖题，底部一行读数。"""
    ctx.set_source_rgb(*pal["bg"])
    ctx.rectangle(0, 0, w, h)
    ctx.fill()

    R = 0.36 * min(w, h)
    cx, cy = w * 0.5, h * 0.48
    blend = st.get("blend", 0.0)
    night = blend > 0.5
    cacheable = blend in (0.0, 1.0)
    surf = _get_static(R, pal, fonts, night, cacheable)
    S = surf.get_width()
    ctx.save()
    ctx.translate(cx - S / 2.0, cy - S / 2.0)
    ctx.set_source_surface(surf)
    ctx.paint()
    ctx.restore()
    _draw_stars(ctx, cx, cy, R, st["t_min"], pal)
    _draw_needle(ctx, cx, cy, R, st["t_min"], pal, night)

    ink, faint, gold = pal["ink"], pal["faint"], pal["gold"]
    chen_f, seal = fonts["chen"], fonts["seal"]
    p = st.get("parts") or {}
    size = max(16.0, R * 0.085)
    mx, my = max(28.0, R * 0.16), max(28.0, R * 0.14)

    def block_bottom(s):
        return h - my - len(s) * size * 1.28 + size * 0.5

    # 四角竖题：左上款识、右上映候、左下日干支、右下年干支
    if p.get("hou"):
        _vtext(ctx, w - mx, my, p["hou"], size, seal, faint, align="r")
    _vtext(ctx, mx, my, "刻盘钟", size, seal, faint)
    if p.get("gz"):
        _vtext(ctx, mx, block_bottom(p["gz"]), p["gz"], size, chen_f, ink)
    if p.get("y"):
        _vtext(ctx, w - mx, block_bottom(p["y"] + "年"), p["y"] + "年",
               size, chen_f, ink)

    # 底部一行：时辰读数 · 更点 · 历注
    line = f"{st['read']} 　·　 {st['geng']} 　·　 {p.get('y', '')}年" \
           f"{p.get('lum', '')}{p.get('gz', '')}日"
    _text(ctx, w * 0.5, h - my - size * 0.4, line, size * 0.72, chen_f, ink)


def draw_scene(ctx, w, h, st, pal, fonts, pointer=None, watermark=None,
               wm_alpha=0.16):
    ctx.set_source_rgb(*pal["bg"])
    ctx.rectangle(0, 0, w, h)
    ctx.fill()

    lay = _layout(w, h)
    R, cx, cy = lay["R"], lay["cx"], lay["cy"]
    px, py0, pw, k = lay["px"], lay["py0"], lay["pw"], lay["k"]

    blend = st.get("blend", 0.0)
    night = blend > 0.5
    cacheable = blend in (0.0, 1.0)
    surf = _get_static(R, pal, fonts, night, cacheable)
    S = surf.get_width()
    ctx.save()
    ctx.translate(cx - S / 2.0, cy - S / 2.0)
    ctx.set_source_surface(surf)
    ctx.paint()
    ctx.restore()

    # 盘面试时：悬停角位 → 读数
    st.pop("hover", None)
    st.pop("note_hover", None)
    if pointer is not None:
        dx, dy = pointer[0] - cx, pointer[1] - cy
        dist = math.hypot(dx, dy)
        if R * 0.60 < dist < R * 1.02:
            theta = math.degrees(math.atan2(dx, -dy)) % 360.0
            t = (theta * 4.0 + 720.0) % 1440.0
            st["hover"] = read_str(int(t))
        nx, ny, nw, nh = lay["note_rect"]
        if nx <= pointer[0] <= nx + nw and ny - 4 * k <= pointer[1] <= ny + nh + 4 * k:
            st["note_hover"] = True

    _draw_stars(ctx, cx, cy, R, st["t_min"], pal)
    _draw_needle(ctx, cx, cy, R, st["t_min"], pal, night)
    _panel(ctx, px, py0, pw, st, pal, fonts, k=k, watermark=watermark,
           wm_alpha=wm_alpha)

    if st.get("popup"):
        _popup(ctx, w, h, lay, st["popup"], st, pal, fonts)

