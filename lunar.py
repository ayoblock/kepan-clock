"""农历、干支、二十四节气与七十二候。

数据源：
- 农历月序：内置 lunar_data 表（本程序自行推算，见该文件头注）。
- 节气：按太阳视黄经每 15° 求根，用 sun.solar_longitude（Meeus 低阶式，约 0.01°，
  折合时刻误差约十五分钟）；日内边界可能差一日，但年份与气序不会错。
- 干支纪日：以 2026-11-10 = 戊子日为锚，序 = (日序 + 14) mod 60。
- 七十二候名依《逸周书·时训解》系统，个别用字待校（如「鹖鴠」「雉始雊」）。

日界：农历日以子正（公历 0 时）为界，与刻盘钟日界一致。
"""
import calendar
import datetime as dt
import math

import lunar_data as _LD
import sun as _sun

GAN = "甲乙丙丁戊己庚辛壬癸"
ZHI = "子丑寅卯辰巳午未申酉戌亥"
ANIMAL = "鼠牛虎兔龙蛇马羊猴鸡狗猪"
MONTH_CN = ("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
# 七政曜日：日曜（周日）起，月火水木金土依次。星期以七曜名之，为古历旧称。
WUYAO = "日月火水木金土"
_DIGIT = "一二三四五六七八九十"

# 二十四气，自立春起（黄经 315° 起，每 15° 一气）
TERMS24 = ("立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
           "立夏", "小满", "芒种", "夏至", "小暑", "大暑",
           "立秋", "处暑", "白露", "秋分", "寒露", "霜降",
           "立冬", "小雪", "大雪", "冬至", "小寒", "大寒")

# 各气的近似公历月日，仅作求根初值
_GUESS = ((2, 4), (2, 19), (3, 6), (3, 21), (4, 5), (4, 20),
          (5, 6), (5, 21), (6, 6), (6, 21), (7, 7), (7, 23),
          (8, 8), (8, 23), (9, 8), (9, 23), (10, 8), (10, 23),
          (11, 7), (11, 22), (12, 7), (12, 22), (1, 6), (1, 20))

# 七十二候，每气三候
HOU = (
    "东风解冻", "蛰虫始振", "鱼陟负冰",
    "獭祭鱼", "候雁北", "草木萌动",
    "桃始华", "仓庚鸣", "鹰化为鸠",
    "玄鸟至", "雷乃发声", "始电",
    "桐始华", "田鼠化为鴽", "虹始见",
    "萍始生", "鸣鸠拂其羽", "戴胜降于桑",
    "蝼蝈鸣", "蚯蚓出", "王瓜生",
    "苦菜秀", "靡草死", "麦秋至",
    "螳螂生", "鵙始鸣", "反舌无声",
    "鹿角解", "蜩始鸣", "半夏生",
    "温风至", "蟋蟀居壁", "鹰始挚",
    "腐草为萤", "土润溽暑", "大雨时行",
    "凉风至", "白露降", "寒蝉鸣",
    "鹰乃祭鸟", "天地始肃", "禾乃登",
    "鸿雁来", "玄鸟归", "群鸟养羞",
    "雷始收声", "蛰虫坯户", "水始涸",
    "鸿雁来宾", "雀入大水为蛤", "菊有黄华",
    "豺乃祭兽", "草木黄落", "蛰虫咸俯",
    "水始冰", "地始冻", "雉入大水为蜃",
    "虹藏不见", "天气上升地气下降", "闭塞而成冬",
    "鹖鴠不鸣", "虎始交", "荔挺出",
    "蚯蚓结", "麋角解", "水泉动",
    "雁北乡", "鹊始巢", "雉始雊",
    "鸡始乳", "征鸟厉疾", "水泽腹坚",
)

# 十二「节」，用于定月建（寅月起于立春）
JIE_IDX = (0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22)
JIE_ZHI = (2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0, 1)   # 寅卯辰巳午未申酉戌亥子丑

_EPOCH_ORD = dt.date(1899, 12, 31).toordinal()


# ---------------------------------------------------------------- 月序表
def _info(year):
    if not (_LD.START_YEAR <= year <= _LD.END_YEAR):
        raise ValueError(f"超出历表范围 {_LD.START_YEAR}-{_LD.END_YEAR}：{year}")
    return _LD._TABLE[year - _LD.START_YEAR]


def leap_month(year):
    return _info(year) & 0xF


def month_days(year, month, leap=False):
    info = _info(year)
    if leap:
        return 30 if (info >> 16) & 1 else 29
    return 30 if (info >> (3 + month)) & 1 else 29


def year_days(year):
    total = sum(month_days(year, m) for m in range(1, 13))
    lm = leap_month(year)
    if lm:
        total += month_days(year, lm, True)
    return total


_NY = None


def _newyears():
    """正月初一的日序，覆盖 1900–2101。"""
    global _NY
    if _NY is None:
        arr, o = [], dt.date(1900, 1, 31).toordinal()
        for y in range(_LD.START_YEAR, _LD.END_YEAR + 1):
            arr.append(o)
            o += year_days(y)
        arr.append(o)
        _NY = arr
    return _NY


# ---------------------------------------------------------------- 转换
def solar_to_lunar(d):
    """公历 date → (农历年, 月, 日, 是否闰月)。"""
    o = d.toordinal()
    ny = _newyears()
    if o < ny[0] or o >= ny[-1]:
        raise ValueError(f"超出历表范围：{d.isoformat()}")
    lo, hi = 0, len(ny) - 2
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if ny[mid] <= o:
            lo = mid
        else:
            hi = mid - 1
    year = _LD.START_YEAR + lo
    off = o - ny[lo]
    leap = leap_month(year)
    for m in range(1, 13):
        n = month_days(year, m)
        if off < n:
            return year, m, off + 1, False
        off -= n
        if leap == m:
            n = month_days(year, m, True)
            if off < n:
                return year, m, off + 1, True
            off -= n
    raise AssertionError("月序表异常")


def lunar_to_solar(year, month, day, leap=False):
    o = _newyears()[year - _LD.START_YEAR]
    lp = leap_month(year)
    for m in range(1, 13):
        if m == month and not leap:
            return dt.date.fromordinal(o + day - 1)
        o += month_days(year, m)
        if lp == m:
            if m == month and leap:
                return dt.date.fromordinal(o + day - 1)
            o += month_days(year, m, True)
    raise ValueError("无此月")


def day_cn(d):
    if d == 10:
        return "初十"
    if d < 10:
        return "初" + _DIGIT[d - 1]
    if d == 20:
        return "二十"
    if d < 20:
        return "十" + _DIGIT[d - 11]
    if d == 30:
        return "三十"
    return "廿" + _DIGIT[d - 21]


def lunar_str(d):
    y, m, dd, lp = solar_to_lunar(d)
    return ("闰" if lp else "") + MONTH_CN[m - 1] + "月" + day_cn(dd)


# ---------------------------------------------------------------- 节气
def _ord_from_jd(jd):
    return int(math.floor(jd + 0.5)) - 1721425


def _solve_term(target, jd0):
    jd = jd0
    for _ in range(40):
        lam = _sun.solar_longitude(jd)
        diff = (target - lam + 180.0) % 360.0 - 180.0
        jd += diff / 0.9856473
        if abs(diff) < 1e-7:
            break
    return _ord_from_jd(jd + 8.0 / 24.0)


_TERM_CACHE = {}


def terms_of_year(year):
    """以该年立春起、至次年大寒止的二十四个节气：[(名, 日序)]。"""
    if year in _TERM_CACHE:
        return _TERM_CACHE[year]
    out = []
    for i, (name, (mm, dd)) in enumerate(zip(TERMS24, _GUESS)):
        yy = year if i < 22 else year + 1
        jd0 = dt.date(yy, mm, dd).toordinal() + 1721425 - 0.5
        out.append((name, _solve_term(315.0 + 15.0 * i, jd0)))
    _TERM_CACHE[year] = out
    return out


def current_term(d):
    """返回 (节气名, 交节日序, 在该年序列中的序号)。"""
    o = d.toordinal()
    best = None
    for y in (d.year - 1, d.year, d.year + 1):
        for i, (name, to) in enumerate(terms_of_year(y)):
            if to <= o and (best is None or to > best[1]):
                best = (name, to, i, y)
    if best is None:
        raise RuntimeError("节气求解失败")
    return best[0], best[1], best[2]


def term_on(d):
    """当日是否交节；是则返回节气名。"""
    o = d.toordinal()
    for y in (d.year - 1, d.year):
        for name, to in terms_of_year(y):
            if to == o:
                return name
    return None


def hou(d):
    """当前候名。"""
    name, to, idx = current_term(d)
    k = (d.toordinal() - to) // 5
    k = 0 if k < 0 else (2 if k > 2 else k)
    return HOU[idx * 3 + k]


def next_term(d):
    """下一节气：(名, 日序)。"""
    o = d.toordinal()
    cands = []
    for y in (d.year - 1, d.year, d.year + 1):
        for name, to in terms_of_year(y):
            if to > o:
                cands.append((to, name))
    cands.sort()
    return cands[0][1], cands[0][0]


# ---------------------------------------------------------------- 干支
def gz_day(d):
    n = (d.toordinal() + 14) % 60
    return GAN[n % 10] + ZHI[n % 12]


def lichun_ord(year):
    return terms_of_year(year)[0][1]


def gz_year(d):
    """年干支，以立春为界。"""
    y = d.year
    if d.toordinal() < lichun_ord(y):
        y -= 1
    n = (y - 1984) % 60
    return GAN[n % 10] + ZHI[n % 12]


def gz_month(d):
    """月干支，以节为界；月建自寅月起。"""
    o = d.toordinal()
    best = None
    for y in (d.year - 1, d.year, d.year + 1):
        ts = terms_of_year(y)
        for k, ti in enumerate(JIE_IDX):
            to = ts[ti][1]
            if to <= o and (best is None or to > best[0]):
                best = (to, y, k)
    if best is None:
        raise RuntimeError("月建求解失败")
    _, y, k = best
    yg = y
    if o < lichun_ord(y):
        yg = y - 1
    year_gan = ((yg - 1984) % 60) % 10
    yin_gan = (year_gan * 2 + 2) % 10
    zhi = JIE_ZHI[k]
    offset = (zhi - 2) % 12
    return GAN[(yin_gan + offset) % 10] + ZHI[zhi]


def animal(d):
    """当日所属农历年的生肖（以立春为界）。"""
    y = d.year
    if d.toordinal() < lichun_ord(y):
        y -= 1
    return ANIMAL[(y - 1984) % 12]


# ---------------------------------------------------------------- 历注
def note(d):
    """一行历注：年干支 + 农历月日 + 日干支 + 候。"""
    return f"{gz_year(d)}年 {lunar_str(d)} · {gz_day(d)}日 · {hou(d)}"


def strip3(d):
    """以 d 为中心的三日条：[(date, 农历月日, 日干支, 候, 曜日), ...]。"""
    out = []
    for off in (-1, 0, 1):
        cur = d + dt.timedelta(days=off)
        out.append((cur, lunar_str(cur), gz_day(cur), hou(cur), wuyao(cur)))
    return out


def wuyao(d):
    """七政曜日：日曜为周日，月火水木金土依次。"""
    return WUYAO[(d.weekday() + 1) % 7]


def month_cells(d):
    """d 所在公历月的日历格（周日起首）：[(date 或 None, 农历标注, 节气名或 None)]。

    农历标注：朔日取月名（十月 / 闰四月），余取日名（初二 / 廿三）。
    """
    first = d.replace(day=1)
    lead = (first.weekday() + 1) % 7          # 周日=0
    n = calendar.monthrange(d.year, d.month)[1]
    cells = [(None, "", None)] * lead
    for i in range(n):
        cur = first + dt.timedelta(days=i)
        _, m, dd, lp = solar_to_lunar(cur)
        lab = ("闰" if lp else "") + MONTH_CN[m - 1] + "月" if dd == 1 else day_cn(dd)
        cells.append((cur, lab, term_on(cur)))
    while len(cells) % 7:
        cells.append((None, "", None))
    return cells


def almanac(d):
    """气泡两态所需全部数据，一次算好供渲染复用。"""
    y, m, dd, lp = solar_to_lunar(d)
    return {
        "today": d,
        "three": strip3(d),
        "month": month_cells(d),
        "solar_title": f"{d.year}年{d.month}月",
        "lunar_title": f"{gz_year(d)}年 {('闰' if lp else '') + MONTH_CN[m - 1]}月",
        "week": tuple(WUYAO),
    }


def parts(d):
    """日辰零件：年干支、农历月日、日干支、候。壁画题款与历注拼句用。"""
    return dict(y=gz_year(d), lum=lunar_str(d), gz=gz_day(d), hou=hou(d))


# ---------------------------------------------------------------- 自校
def _selftest():
    D = dt.date
    # 春节（正月初一）样本
    chunjie = {
        2020: (1, 25), 2021: (2, 12), 2022: (2, 1), 2023: (1, 22), 2024: (2, 10),
        2025: (1, 29), 2026: (2, 17), 2027: (2, 6), 2028: (1, 26), 2029: (2, 13),
        2030: (2, 3),
    }
    for y, (m, dd) in chunjie.items():
        got = lunar_to_solar(y, 1, 1)
        assert (got.month, got.day) == (m, dd), f"春节错误 {y}: {got} 应 {m}-{dd}"
        back = solar_to_lunar(D(y, m, dd))
        assert back == (y, 1, 1, False), f"反查错误 {y}: {back}"

    # 闰月样本
    leap_cases = {2020: 4, 2023: 2, 2025: 6, 2028: 5, 2033: 11}
    for y, lm in leap_cases.items():
        assert leap_month(y) == lm, f"闰月错误 {y}: {leap_month(y)} 应 {lm}"
        ly, mm, dd, lp = solar_to_lunar(lunar_to_solar(y, lm, 1, True))
        assert (mm, dd, lp) == (lm, 1, True), f"闰月反查错误 {y}"

    # 干支锚点（多来源一致的公历日期）
    anchors = {
        D(2026, 11, 10): ("丙午", "己亥", "戊子"),
        D(2026, 11, 11): (None, None, "己丑"),
        D(2026, 11, 9): (None, None, "丁亥"),
    }
    for d, (gy, gm, gd) in anchors.items():
        if gy:
            assert gz_year(d) == gy, f"年干支 {d}: {gz_year(d)}"
        if gm:
            assert gz_month(d) == gm, f"月干支 {d}: {gz_month(d)}"
        assert gz_day(d) == gd, f"日干支 {d}: {gz_day(d)}"

    # 农历月日
    assert lunar_str(D(2026, 11, 10)) == "十月初二", lunar_str(D(2026, 11, 10))

    # 节气日（公开历表：2026 立冬 11-07、小雪 11-22）
    ts = dict(terms_of_year(2026))
    assert ts["立冬"] == D(2026, 11, 7).toordinal(), ts["立冬"]
    assert ts["小雪"] == D(2026, 11, 22).toordinal(), ts["小雪"]

    # 候：立冬后三日应为「水始冰」
    assert hou(D(2026, 11, 10)) == "水始冰", hou(D(2026, 11, 10))

    # 连续日推进不跳月
    d = D(2025, 12, 1)
    prev = solar_to_lunar(d)
    for _ in range(400):
        d += dt.timedelta(days=1)
        cur = solar_to_lunar(d)
        assert (cur[0], cur[1], cur[3]) == (prev[0], prev[1], prev[3]) or cur[2] == 1, \
            f"月序跳变 {d}: {prev} → {cur}"
        prev = cur

    print("lunar 自校通过：春节 11 例、闰月 5 例、干支锚点 3 例、节气 2 例、候 1 例、连续 400 日")


if __name__ == "__main__":
    _selftest()
    today = dt.date.today()
    print("今日历注：", note(today))
    print("  年干支", gz_year(today), "月建", gz_month(today), "日", gz_day(today),
          "节气", current_term(today)[0], "候", hou(today))
    nt, no = next_term(today)
    print("  下一节气", nt, dt.date.fromordinal(no).isoformat())
