# -*- coding: utf-8 -*-
"""刻盘钟·换算核心
时刻 → 辰 / 初正 / 刻 的纯函数，无界面依赖。
盘刻制：一日九十六刻，一辰八刻，初四刻正四刻，一刻十五分钟。
日界取子正（零点），子时自前一日二十三时起。
"""

CHEN = "子丑寅卯辰巳午未申酉戌亥"
NUM = "零一二三四五六七八九"
KE_NUM = "一二三四"          # 每初、每正各四刻
SHENGXIAO = "鼠牛虎兔龙蛇马羊猴鸡狗猪"  # 与 CHEN 逐位对位（子鼠……亥猪）


def chen_index(minutes):
    """传入当日分钟数（0–1440），返回 (辰序 k, 辰内分钟 p)。"""
    u = (minutes + 60) % 1440          # 把 23:00 折到 0 点
    return u // 120, u % 120


def read_chen(minutes):
    """返回 (辰字, 初/正, 刻序 1–4)。"""
    k, p = chen_index(minutes)
    if p < 60:
        part, ke = "初", p // 15 + 1
    else:
        part, ke = "正", (p - 60) // 15 + 1
    return CHEN[k], part, ke


def read_str(minutes):
    """如 720 → 「午正一刻」。"""
    ch, part, ke = read_chen(minutes)
    return f"{ch}{part}{KE_NUM[ke - 1]}刻"


def animal(minutes):
    """当前辰的生肖字。"""
    k, _ = chen_index(minutes)
    return SHENGXIAO[k]


def needle_angle(minutes):
    """晷针角位：自午位（正上）顺时针计的度数。子正居下，卯正在左，酉正在右。"""
    return (minutes - 720.0) * 0.25


def side_angle(minutes):
    """副盘刻针角位：一辰（两小时）一周，辰初在正上。"""
    _, p = chen_index(minutes)
    return p / 120.0 * 360.0


# ---------------------------------------------------------------- 自校
def _selftest():
    cases = [
        (12 * 60, "午正一刻"),
        (18 * 60 + 30, "酉正三刻"),
        (23 * 60 + 30, "子初三刻"),
        (0, "子正一刻"),
        (23 * 60, "子初一刻"),
        (23 * 60 + 59, "子初四刻"),
        (1 * 60, "丑初一刻"),
        (5 * 60 + 7, "卯初一刻"),
        (5 * 60 + 15, "卯初二刻"),
        (7 * 60 + 14, "辰初一刻"),
        (11 * 60 + 35, "午初三刻"),
    ]
    for m, want in cases:
        got = read_str(m)
        assert got == want, f"read_str({m}) = {got}，应为 {want}"
    # 辰方位自校：卯正在左（角位 -90）、酉正在右（+90）、午上（0）、子下（±180）
    assert needle_angle(6 * 60) == -90.0
    assert needle_angle(18 * 60) == 90.0
    assert needle_angle(12 * 60) == 0.0
    assert abs(needle_angle(0)) == 180.0
    # 生肖对位
    assert animal(23 * 60) == "鼠" and animal(13 * 60) == "羊"
    print("chen_core 自校通过：{} 例".format(len(cases)))


if __name__ == "__main__":
    _selftest()
