package cn.workbuddy.kepanclock.domain

import java.time.LocalDate
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * 刻盘钟·历算（移植自桌面 src/sun.py）。
 * 日出日落（NOAA 算法，地平含 -0.833° 折射修正）、节气（太阳视黄经）、更点、真太阳时。
 * 精度：日出日落约 ±1 分钟，节气约 ±5 分钟——足够换气环片、定更牌之用。
 */
object SunMath {
    val TERMS = ("小寒 大寒 立春 雨水 惊蛰 春分 清明 谷雨 "
        + "立夏 小满 芒种 夏至 小暑 大暑 立秋 处暑 "
        + "白露 秋分 寒露 霜降 立冬 小雪 大雪 冬至").split(" ")

    const val ZENITH = 90.833          // 太阳下缘触地平
    const val DUSK_DEFAULT = 37.0      // 昏旦偏移（分钟），约二刻半

    private const val NUM_CN = "一二三四五"

    private fun norm360(x: Double): Double = x - 360.0 * floor(x / 360.0)

    private fun mod(a: Double, b: Double): Double = a - b * floor(a / b)

    /** NOAA 算法：给定日期与经纬，返回该事件的世界时（小时），极昼极夜返回 null。 */
    private fun sunUtcMinutes(d: LocalDate, lat: Double, lon: Double, rising: Boolean): Double? {
        val n = d.dayOfYear
        val lngHour = lon / 15.0
        val t = n + ((6 - lngHour).takeIf { rising } ?: (18 - lngHour)) / 24.0
        val m = norm360(0.9856 * t - 3.289)
        val mr = Math.toRadians(m)
        val l = norm360(m + 1.916 * sin(mr) + 0.020 * sin(2 * mr) + 282.634)
        val lr = Math.toRadians(l)
        var ra = Math.toDegrees(atan(0.91764 * tan(lr)))
        if (ra < 0) ra += 360.0
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90   // 象限校正
        ra /= 15.0
        val sinDec = 0.39782 * sin(lr)
        val cosDec = cos(asin(sinDec))
        val latr = Math.toRadians(lat)
        val cosH = (cos(Math.toRadians(ZENITH)) - sinDec * sin(latr)) / (cosDec * cos(latr))
        if (cosH > 1.0 || cosH < -1.0) return null
        val h = if (rising) (360.0 - Math.toDegrees(acos(cosH))) / 15.0
        else Math.toDegrees(acos(cosH)) / 15.0
        val tt = h + ra - 0.06571 * t - 6.622
        val ut = tt - lngHour
        return ut - 24.0 * floor(ut / 24.0)              // 归 0–24
    }

    /** 返回 (日出、日落) 的当日本地分钟数；极昼极夜为 (null, null)。 */
    fun sunTimesLocal(d: LocalDate, lat: Double, lon: Double, tzMin: Int): Pair<Double?, Double?> {
        val ru = sunUtcMinutes(d, lat, lon, rising = true)
        val su = sunUtcMinutes(d, lat, lon, rising = false)
        if (ru == null || su == null) return null to null
        var rise = ru * 60.0 + tzMin
        var sett = su * 60.0 + tzMin
        // 归到当日：跨日者 ±1440
        if (rise > 720) rise -= 1440.0
        if (sett < 720) sett += 1440.0
        return rise to sett
    }

    /**
     * 定昏旦：夜起于今日日落加偏移，止于（今夜所属的）次日日出减偏移。
     * 返回 (夜起、夜止) 绝对分钟；越过子夜的时刻由 gengPoint 内部加 1440 对齐。
     * 极区返回 null。
     */
    fun nightSpan(
        nowMin: Double, lat: Double, lon: Double, tzMin: Int,
        date: LocalDate, dusk: Double = DUSK_DEFAULT,
    ): Pair<Double, Double>? {
        val (riseT, setT) = sunTimesLocal(date, lat, lon, tzMin)
        if (riseT == null || setT == null) return null
        val (riseTom, _) = sunTimesLocal(date.plusDays(1), lat, lon, tzMin)
        if (riseTom == null) return null
        val start = setT + dusk
        val end = riseTom + 1440.0 - dusk
        if (end <= start) return null
        return start to end
    }

    /** 返回 (更序 1–5, 点序 1–5) 或 null（昼／极区）。 */
    fun gengPoint(nowMin: Double, span: Pair<Double, Double>?): Pair<Int, Int>? {
        if (span == null) return null
        val (start, end) = span
        var t = nowMin
        if (t < start && t + 1440.0 < end) t += 1440.0    // 已过子夜，归入今夜
        if (!(t >= start && t < end)) return null
        val nightLen = end - start
        val frac = (t - start) / nightLen
        var g = (frac * 5).toInt()
        if (g > 4) g = 4
        var p = (mod(frac * 25, 5.0)).toInt()
        if (p > 4) p = 4
        return (g + 1) to (p + 1)
    }

    fun gengStr(nowMin: Double, span: Pair<Double, Double>?): String {
        val gp = gengPoint(nowMin, span) ?: return if (span != null) "昼" else "更点缺"
        return "${NUM_CN[gp.first - 1]}更${NUM_CN[gp.second - 1]}点"
    }

    /** 昼夜主题判定：日落到日出之间（不含昏旦偏移）。极区缺值时恒判昼。 */
    fun isNight(nowMin: Double, riseT: Double?, setT: Double?): Boolean {
        if (riseT == null || setT == null) return false
        if (setT <= nowMin && nowMin < riseT + 1440.0) return true
        if (riseT <= nowMin && nowMin < setT) return false
        // 越子后：与昨日日落比
        return nowMin < riseT
    }

    // ------------------------------------------------------------- 节气

    /**
     * 本地时刻 → 儒略日。offsetMinutes 为本地相对 UTC 的偏移（东八 480）。
     * 桌面版此处读系统本地时区；Android 侧由调用方显式传入以保证可复现。
     */
    fun julianDay(year: Int, month: Int, day: Int, hour: Int, minute: Int, offsetMinutes: Int): Double {
        val epochSec = LocalDate.of(year, month, day).toEpochDay() * 86400
            + hour * 3600L + minute * 60L - offsetMinutes * 60L
        return (epochSec - 946728000L) / 86400.0 + 2451545.0
    }

    /** 太阳视黄经（度），Meeus 低阶式，精度约 0.01°。 */
    fun solarLongitude(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
        val mr = Math.toRadians(mod(m, 360.0))
        val c = ((1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mr)
            + (0.019993 - 0.000101 * t) * sin(2 * mr)
            + 0.000289 * sin(3 * mr))
        val lam = l0 + c - 0.00569 - 0.00478 * sin(Math.toRadians(125.04 - 1934.136 * t))
        return norm360(lam)
    }

    /**
     * 真太阳时（分钟）：平太阳时＋时差＋经度改正。
     * 时差用 SPASOFT 近似式，精度约 ±0.5 分钟；经度改正＝4×(当地经度－时区中央经线)。
     */
    fun trueSolarMinutes(nowMin: Double, dayOfYear: Int, lon: Double, tzMin: Int): Double {
        val b = 2.0 * Math.PI * (dayOfYear - 81) / 364.0
        val eot = 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)
        val lonCorr = 4.0 * (lon - tzMin / 4.0)
        return nowMin + eot + lonCorr
    }

    /** 当前所处节气名。小寒在黄经 285°，表序与之逐位对齐。 */
    fun termName(year: Int, month: Int, day: Int, hour: Int, minute: Int, offsetMinutes: Int): String {
        val lam = solarLongitude(julianDay(year, month, day, hour, minute, offsetMinutes))
        val idx = (mod(lam - 285.0, 360.0) / 15.0).toInt()
        return TERMS[idx]
    }
}
