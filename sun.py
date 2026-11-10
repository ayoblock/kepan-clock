# -*- coding: utf-8 -*-
"""刻盘钟·历算
日出日落（NOAA 算法，地平含 -0.833° 折射修正）、节气（太阳视黄经）、更点。
精度：日出日落约 ±1 分钟，节气约 ±5 分钟——足够换气环片、定更牌之用。
"""
import math
from datetime import date, datetime, timedelta, timezone

TERMS = ("小寒 大寒 立春 雨水 惊蛰 春分 清明 谷雨 "
         "立夏 小满 芒种 夏至 小暑 大暑 立秋 处暑 "
         "白露 秋分 寒露 霜降 立冬 小雪 大雪 冬至").split()

ZENITH = 90.833          # 太阳下缘触地平
DUSK_DEFAULT = 37        # 昏旦偏移（分钟），约二刻半

_num = "一二三四五"


def _norm360(x):
    return x - 360.0 * math.floor(x / 360.0)


def _sun_utc_minutes(d, lat, lon, rising=True):
    """NOAA 算法：给定日期与经纬，返回该事件的世界时（小时），极昼极夜返回 None。"""
    n = d.timetuple().tm_yday
    lng_hour = lon / 15.0
    t = n + ((6 - lng_hour) if rising else (18 - lng_hour)) / 24.0
    m = _norm360(0.9856 * t - 3.289)
    mr = math.radians(m)
    l = _norm360(m + 1.916 * math.sin(mr) + 0.020 * math.sin(2 * mr) + 282.634)
    lr = math.radians(l)
    ra = math.degrees(math.atan(0.91764 * math.tan(lr)))
    if ra < 0:
        ra += 360.0
    ra += (l // 90) * 90 - (ra // 90) * 90        # 象限校正
    ra /= 15.0
    sin_dec = 0.39782 * math.sin(lr)
    cos_dec = math.cos(math.asin(sin_dec))
    latr = math.radians(lat)
    cos_h = (math.cos(math.radians(ZENITH)) - sin_dec * math.sin(latr)) / (cos_dec * math.cos(latr))
    if cos_h > 1.0 or cos_h < -1.0:
        return None
    h = (360.0 - math.degrees(math.acos(cos_h))) / 15.0 if rising \
        else math.degrees(math.acos(cos_h)) / 15.0
    tt = h + ra - 0.06571 * t - 6.622
    ut = tt - lng_hour
    return ut - 24.0 * math.floor(ut / 24.0)      # 归 0–24


def sun_times_local(d, lat, lon, tz_min):
    """返回 (日出、日落) 的当日本地分钟数；极昼极夜为 (None, None)。"""
    ru = _sun_utc_minutes(d, lat, lon, rising=True)
    su = _sun_utc_minutes(d, lat, lon, rising=False)
    if ru is None or su is None:
        return None, None
    rise = ru * 60.0 + tz_min
    sett = su * 60.0 + tz_min
    # 归到当日：跨日者 ±1440
    if rise > 720: rise -= 1440.0
    if sett < 720: sett += 1440.0
    return rise, sett


def night_span(now_min, lat, lon, tz_min, now=None, dusk=DUSK_DEFAULT):
    """定昏旦：夜起于今日日落加偏移，止于（今夜所属的）次日日出减偏移。
    返回 (夜起、夜止) 绝对分钟；越过子夜的时刻由 geng_point 内部加 1440 对齐。
    极区返回 None。"""
    now = now or datetime.now()
    rise_t, set_t = sun_times_local(now.date(), lat, lon, tz_min)
    if rise_t is None or set_t is None:
        return None
    rise_tom, _ = sun_times_local(now.date() + timedelta(days=1), lat, lon, tz_min)
    if rise_tom is None:
        return None
    start = set_t + dusk
    end = rise_tom + 1440.0 - dusk
    if end <= start:
        return None
    return start, end


def geng_point(now_min, span):
    """返回 (更序 1–5, 点序 1–5) 或 None（昼／极区）。"""
    if span is None:
        return None
    start, end = span
    t = now_min
    if t < start and t + 1440.0 < end:
        t += 1440.0                                # 已过子夜，归入今夜
    if not (start <= t < end):
        return None
    night_len = end - start
    frac = (t - start) / night_len
    g = int(frac * 5)
    if g > 4: g = 4
    p = int((frac * 25) % 5)
    if p > 4: p = 4
    return g + 1, p + 1


def geng_str(now_min, span):
    gp = geng_point(now_min, span)
    if gp is None:
        return "昼" if span is not None else "更点缺"
    return f"{_num[gp[0]-1]}更{_num[gp[1]-1]}点"


def is_night(now_min, rise_t, set_t):
    """昼夜主题判定：日落到日出之间（不含昏旦偏移）。极区缺值时恒判昼。"""
    if rise_t is None or set_t is None:
        return False
    if set_t <= now_min < rise_t + 1440.0:
        return True
    if rise_t <= now_min < set_t:
        return False
    # 越子后：与昨日日落比
    return now_min < rise_t


# ------------------------------------------------------------- 节气
def _jd(dt):
    """本地 datetime（含 tzinfo 或本地时）→ 儒略日。"""
    if dt.tzinfo is None:
        import time as _t
        dt = dt.replace(tzinfo=timezone(timedelta(seconds=_t.localtime().tm_gmtoff)))
    epoch = datetime(2000, 1, 1, 12, tzinfo=timezone.utc)
    return (dt - epoch).total_seconds() / 86400.0 + 2451545.0


def solar_longitude(jd):
    """太阳视黄经（度），Meeus 低阶式，精度约 0.01°。"""
    t = (jd - 2451545.0) / 36525.0
    l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
    m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
    mr = math.radians(m % 360.0)
    c = ((1.914602 - 0.004817 * t - 0.000014 * t * t) * math.sin(mr)
         + (0.019993 - 0.000101 * t) * math.sin(2 * mr)
         + 0.000289 * math.sin(3 * mr))
    lam = l0 + c - 0.00569 - 0.00478 * math.sin(math.radians(125.04 - 1934.136 * t))
    return _norm360(lam)


def true_solar_minutes(now_min, day_of_year, lon, tz_min):
    """真太阳时（分钟）：平太阳时＋时差＋经度改正。
    时差用 SPASOFT 近似式，精度约 ±0.5 分钟；经度改正＝4×(当地经度－时区中央经线)。
    时区中央经线＝tz_min/4 度（东八区即 120°）。"""
    b = 2.0 * math.pi * (day_of_year - 81) / 364.0
    eot = 9.87 * math.sin(2 * b) - 7.53 * math.cos(b) - 1.5 * math.sin(b)
    lon_corr = 4.0 * (lon - tz_min / 4.0)
    return now_min + eot + lon_corr


def term_name(dt):
    """当前所处节气名。小寒在黄经 285°，表序与之逐位对齐。"""
    lam = solar_longitude(_jd(dt))
    idx = int(((lam - 285.0) % 360.0) // 15.0)
    return TERMS[idx]


# ------------------------------------------------------------- 自校
def _selftest():
    tz = 480  # 东八
    # 北京 2026 冬至附近：日出应晚于 7 点、日落早于 17 点半（量级校验）
    rise, sett = sun_times_local(date(2026, 12, 21), 39.9, 116.4, tz)
    assert 7.0 * 60 < rise < 7.9 * 60, f"冬至日出异常: {rise}"
    assert 16.5 * 60 < sett < 17.5 * 60, f"冬至日落异常: {sett}"
    # 夏至：日出早于 5 点、日落晚于 19 点
    rise2, sett2 = sun_times_local(date(2026, 6, 21), 39.9, 116.4, tz)
    assert 4.3 * 60 < rise2 < 5.0 * 60, f"夏至日出异常: {rise2}"
    assert 19.0 * 60 < sett2 < 19.8 * 60, f"夏至日落异常: {sett2}"
    # 昼夜判别
    assert is_night(0.0, rise, sett) is True
    assert is_night(12 * 60, rise, sett) is False
    # 节气：2026-11-10 应在立冬（11 月 7 日前后）
    t = term_name(datetime(2026, 11, 10, 12, 0))
    assert t == "立冬", f"节气异常: {t}"
    t2 = term_name(datetime(2026, 6, 21, 12, 0))
    assert t2 in ("夏至", "芒种"), f"节气异常: {t2}"
    # 更点：冬至夜取其半应得三更
    span = night_span(0.0, 39.9, 116.4, tz, now=datetime(2026, 12, 21, 0, 0))
    mid = (span[0] + span[1]) / 2.0
    g, p = geng_point(mid, span)
    assert g == 3 and p in (2, 3, 4), f"三更校验异常: {g}更{p}点"
    # 真太阳时：北京东经 116.4，较东经 120 慢约 14.4 分钟；11 月时差为正约 +16 分
    ts = true_solar_minutes(12 * 60.0, 314, 116.4, 480)
    assert abs(ts - (720 - 14.4 + 16.4)) < 1.5, f"真太阳时异常: {ts}"
    print("sun 自校通过：日出日落、节气、更点、真太阳时")


if __name__ == "__main__":
    _selftest()
