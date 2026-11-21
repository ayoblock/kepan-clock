# -*- coding: utf-8 -*-
"""P1 golden 语料生成器（Python oracle）。

输出到 domain-core/src/test/resources/golden/：
- chen_1440.txt   : 逐分钟时刻换算（1440 行）
- geng_1440.txt   : 固定更点 span 下逐分钟 geng（1440 行）
- sun_times.txt   : 多地点多日期日出日落（明文）
- lunar_daily.txt : 2024-2026 逐日历算明文（诊断用）
- lunar_hash.txt  : 1900-2100 逐年逐日历算聚合 SHA-256（全量校验）
- terms.txt       : 2024-2026 各年 24 节气日序
"""
import hashlib
import math
import os
import sys
from datetime import date, datetime, timedelta, timezone

sys.path.insert(0, "./src")
import chen_core
import sun
import lunar
import lunar_data as LD

OUT = "android/domain-core/src/test/resources/golden"
os.makedirs(OUT, exist_ok=True)

TZ = timezone(timedelta(minutes=480))   # 东八，固定以保证 Kotlin 侧可复现


def f(x, nd=6):
    return "none" if x is None else f"{x:.{nd}f}"


# ---- 1. chen 逐分钟 ----
lines = []
for m in range(1440):
    lines.append(f"{m}|{chen_core.read_str(m)}|{chen_core.animal(m)}|"
                 f"{f(chen_core.needle_angle(float(m)))}|{f(chen_core.side_angle(m))}")
open(f"{OUT}/chen_1440.txt", "w").write("\n".join(lines) + "\n")

# ---- 2. 更点：北京 2026-12-21 的夜 span，逐分钟 ----
now0 = datetime(2026, 12, 21, 0, 0)
span = sun.night_span(0.0, 39.9, 116.4, 480, now=now0, dusk=37)
lines = [f"span|{f(span[0], 4)}|{f(span[1], 4)}"]
for m in range(1440):
    gp = sun.geng_point(float(m), span)
    lines.append(f"{m}|{'none' if gp is None else f'{gp[0]},{gp[1]}'}|{sun.geng_str(float(m), span)}")
open(f"{OUT}/geng_1440.txt", "w").write("\n".join(lines) + "\n")

# ---- 3. 日出日落：4 地点 × 每月 1/15 日 ----
CITIES = [(39.9, 116.4), (31.23, 121.47), (22.55, 114.1), (43.88, 125.35)]
lines = []
for d in [date(2026, m, dd) for m in range(1, 13) for dd in (1, 15)]:
    for lat, lon in CITIES:
        rise, sett = sun.sun_times_local(d, lat, lon, 480)
        lines.append(f"{d.isoformat()}|{lat}|{lon}|{f(rise, 4)}|{f(sett, 4)}")
open(f"{OUT}/sun_times.txt", "w").write("\n".join(lines) + "\n")

# ---- 4. 节气日序：2024-2026 ----
lines = []
for y in (2024, 2025, 2026):
    for name, to in lunar.terms_of_year(y):
        lines.append(f"{y}|{name}|{to}")
open(f"{OUT}/terms.txt", "w").write("\n".join(lines) + "\n")

# ---- 5. 逐日明文：2024-01-01 .. 2026-12-31 ----
lines = []
d = date(2024, 1, 1)
while d <= date(2026, 12, 31):
    y, m, dd, lp = lunar.solar_to_lunar(d)
    tn = lunar.term_on(d)
    lines.append(f"{d.isoformat()}|{lunar.lunar_str(d)}|{lunar.gz_year(d)}|"
                 f"{lunar.gz_month(d)}|{lunar.gz_day(d)}|{lunar.hou(d)}|"
                 f"{lunar.wuyao(d)}|{'-' if tn is None else tn}")
    d += timedelta(days=1)
open(f"{OUT}/lunar_daily.txt", "w").write("\n".join(lines) + "\n")

# ---- 6. 全量逐年摘要：1900-2100 ----
# 每年串接 "ordinal|y|m|d|leap|gzDay|gzYear|gzMonth" 行后取 SHA-256
def year_digest(year):
    ny = lunar._newyears()
    o = ny[year - LD.START_YEAR]
    end = ny[year - LD.START_YEAR + 1]
    h = hashlib.sha256()
    cur = o
    while cur < end:
        dd = date.fromordinal(cur)
        y, m, ld, lp = lunar.solar_to_lunar(dd)
        h.update(f"{cur}|{y}|{m}|{ld}|{int(lp)}|{lunar.gz_day(dd)}|"
                 f"{lunar.gz_year(dd)}|{lunar.gz_month(dd)}\n".encode("utf-8"))
        cur += 1
    return h.hexdigest()

lines = [f"{y}|{year_digest(y)}" for y in range(LD.START_YEAR, LD.END_YEAR + 1)]
open(f"{OUT}/lunar_hash.txt", "w").write("\n".join(lines) + "\n")

print("golden 生成完毕：", sorted(os.listdir(OUT)))
print("每年摘要行数：", len(lines))
