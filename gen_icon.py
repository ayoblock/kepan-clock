#!/usr/bin/python3
# -*- coding: utf-8 -*-
"""刻盘钟·应用图标生成器（v2：以真表盘为面）
直接调用 dial 的静态层绘制主盘全环——素底晕影、弦纹、辰字环、初正环、
九十六刻线、四正四象圆牌、司辰篆款——再叠晷针（指申初一刻），圆形裁切。
产出：icons/hicolor/<n>x<n>/apps/kepan-clock.png
"""
import math
import pathlib
import tomllib

import cairo

import dial  # noqa: E402

HERE = pathlib.Path(__file__).resolve().parent
ICONS = HERE / "icons" / "hicolor"
SIZES = (256, 128, 64, 48, 32, 16)
BASE = 1024           # 母版尺寸，各档由其缩放
T_MIN = 15 * 60 + 45  # 晷针指申初一刻，与旧徽记一致


def load_fonts():
    """字体链：用户配置优先，随包 config 兜底。"""
    for p in (pathlib.Path.home() / ".config" / "kepan-clock" / "config.toml",
              HERE / "config.toml"):
        if p.exists():
            cfg = tomllib.loads(p.read_bytes().decode("utf-8"))
            f = cfg.get("fonts", {})
            return {"chen": f.get("chen", "AR PL UKai CN, serif"),
                    "seal": f.get("seal", "AR PL UKai CN, serif")}
    return {"chen": "AR PL UKai CN, serif", "seal": "AR PL UKai CN, serif"}


def build_master():
    """母版：方画布居中一块完整表盘（含针），盘外透明。"""
    fonts = load_fonts()
    pal = dial.DAY
    m = BASE
    cx = cy = m / 2.0
    R = m / 2.0 - 8.0

    # 盘面静态层（_build_static 出图为 2R+8 方形）
    dial_surf = dial._build_static(R, pal, fonts, night=False)
    S = dial_surf.get_width()

    # 叠晷针（昼影、针身、朱轴帽），画在静态层画布坐标系内
    nc = cairo.Context(dial_surf)
    dial._draw_needle(nc, S / 2.0, S / 2.0, R, T_MIN, pal, night=False)
    dial_surf.flush()

    # 圆形裁切成母版，盘外透明
    icon = cairo.ImageSurface(cairo.FORMAT_ARGB32, m, m)
    c = cairo.Context(icon)
    c.new_sub_path()
    c.arc(cx, cy, m / 2.0 - 2.0, 0, 2 * math.pi)
    c.clip()
    c.set_source_surface(dial_surf, cx - S / 2.0, cy - S / 2.0)
    c.paint()
    icon.flush()
    return icon


def main():
    master = build_master()
    for sz in SIZES:
        d = ICONS / f"{sz}x{sz}" / "apps"
        d.mkdir(parents=True, exist_ok=True)
        out = cairo.ImageSurface(cairo.FORMAT_ARGB32, sz, sz)
        c = cairo.Context(out)
        c.scale(sz / BASE, sz / BASE)
        c.set_antialias(cairo.Antialias.GOOD)
        c.set_source_surface(master, 0, 0)
        c.paint()
        out.flush()
        out.write_to_png(str(d / "kepan-clock.png"))
        print(f"  {sz}x{sz} OK")


if __name__ == "__main__":
    main()
