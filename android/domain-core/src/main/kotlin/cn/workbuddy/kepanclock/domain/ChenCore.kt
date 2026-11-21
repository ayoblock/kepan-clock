package cn.workbuddy.kepanclock.domain

/**
 * 刻盘钟·时刻换算核心（P0 最小集，移植自桌面 src/chen_core.py）。
 * 盘刻制：一日九十六刻，一辰八刻，初正各四刻，一刻十五分钟。
 * 日界取子正（零点），子时自前一日二十三时起。
 */
object ChenCore {
    const val CHEN = "子丑寅卯辰巳午未申酉戌亥"
    private const val KE_NUM = "一二三四"
    private const val SHENGXIAO = "鼠牛虎兔龙蛇马羊猴鸡狗猪"

    /** 传入当日分钟数（0–1440），返回 (辰序 k, 辰内分钟 p)。 */
    fun chenIndex(minutes: Int): Pair<Int, Int> {
        val u = Math.floorMod(minutes + 60, 1440)
        return u / 120 to u % 120
    }

    /** 返回 (辰字, 初/正, 刻序 1–4)。 */
    fun readChen(minutes: Int): Triple<String, String, Int> {
        val (k, p) = chenIndex(minutes)
        return if (p < 60) {
            Triple(CHEN[k].toString(), "初", p / 15 + 1)
        } else {
            Triple(CHEN[k].toString(), "正", (p - 60) / 15 + 1)
        }
    }

    /** 如 720 → 「午正一刻」。 */
    fun readStr(minutes: Int): String {
        val (ch, part, ke) = readChen(minutes)
        return "$ch$part${KE_NUM[ke - 1]}刻"
    }

    /** 当前辰的生肖字。 */
    fun animal(minutes: Int): String {
        val (k, _) = chenIndex(minutes)
        return SHENGXIAO[k].toString()
    }

    /** 晷针角位：自午位（正上）顺时针计的度数。 */
    fun needleAngle(minutes: Double): Double = (minutes - 720.0) * 0.25

    /** 副盘刻针角位：一辰（两小时）一周，辰初在正上。 */
    fun sideAngle(minutes: Int): Double = chenIndex(minutes).second / 120.0 * 360.0
}
