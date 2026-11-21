package cn.workbuddy.kepanclock.domain

import java.security.MessageDigest
import java.time.LocalDate

/**
 * 农历、干支、二十四节气与七十二候（移植自桌面 src/lunar.py）。
 *
 * 数据源：农历月序为内置 LunarData 表（桌面版自行推算）；节气按太阳视黄经每 15° 求根，
 * 用 [SunMath.solarLongitude]（Meeus 低阶式，约 0.01°）；干支纪日以 2026-11-10 = 戊子日为锚。
 *
 * 日界：农历日以子正（公历 0 时）为界，与刻盘钟日界一致。
 */
object Lunar {
    const val GAN = "甲乙丙丁戊己庚辛壬癸"
    const val ZHI = "子丑寅卯辰巳午未申酉戌亥"
    const val ANIMAL = "鼠牛虎兔龙蛇马羊猴鸡狗猪"
    val MONTH_CN = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    // 七政曜日：日曜（周日）起，月火水木金土依次。星期以七曜名之，为古历旧称。
    const val WUYAO = "日月火水木金土"
    private const val DIGIT = "一二三四五六七八九十"

    // 二十四气，自立春起（黄经 315° 起，每 15° 一气）
    val TERMS24 = arrayOf(
        "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
        "立夏", "小满", "芒种", "夏至", "小暑", "大暑",
        "立秋", "处暑", "白露", "秋分", "寒露", "霜降",
        "立冬", "小雪", "大雪", "冬至", "小寒", "大寒",
    )

    // 各气的近似公历月日，仅作求根初值
    private val GUESS = arrayOf(
        2 to 4, 2 to 19, 3 to 6, 3 to 21, 4 to 5, 4 to 20,
        5 to 6, 5 to 21, 6 to 6, 6 to 21, 7 to 7, 7 to 23,
        8 to 8, 8 to 23, 9 to 8, 9 to 23, 10 to 8, 10 to 23,
        11 to 7, 11 to 22, 12 to 7, 12 to 22, 1 to 6, 1 to 20,
    )

    // 七十二候，每气三候
    val HOU = arrayOf(
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

    // 十二「节」，用于定月建（寅月起于立春）
    private val JIE_IDX = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22)
    private val JIE_ZHI = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0, 1) // 寅卯辰巳午未申酉戌亥子丑

    // Python date(1,1,1).toordinal() = 1；epochDay(0001-01-01) = -719162
    private const val ORDINAL_EPOCH_OFFSET = 719163L

    /** 公历 LocalDate ↔ Python date.toordinal() 日序互转。 */
    fun ordinal(d: LocalDate): Int = (d.toEpochDay() + ORDINAL_EPOCH_OFFSET).toInt()

    fun fromOrdinal(o: Int): LocalDate = LocalDate.ofEpochDay(o - ORDINAL_EPOCH_OFFSET)

    /** 农历日四元组。 */
    data class LunarDate(val year: Int, val month: Int, val day: Int, val leap: Boolean)

    /** 节气条目：(名, 日序)。 */
    data class TermEntry(val name: String, val ordinal: Int)

    private val newYearsCache by lazy {
        var o = ordinal(LocalDate.of(1900, 1, 31))
        val arr = IntArray(LunarData.END_YEAR - LunarData.START_YEAR + 2)
        for (y in LunarData.START_YEAR..LunarData.END_YEAR) {
            arr[y - LunarData.START_YEAR] = o
            o += yearDays(y)
        }
        arr[LunarData.END_YEAR - LunarData.START_YEAR + 1] = o
        arr
    }

    private val termCache = HashMap<Int, List<TermEntry>>()

    // ---------------------------------------------------------------- 月序表

    fun leapMonth(year: Int): Int = LunarData.info(year) and 0xF

    fun monthDays(year: Int, month: Int, leap: Boolean = false): Int {
        val info = LunarData.info(year)
        return if (leap) {
            if ((info shr 16) and 1 == 1) 30 else 29
        } else {
            if ((info shr (3 + month)) and 1 == 1) 30 else 29
        }
    }

    fun yearDays(year: Int): Int {
        var total = 0
        for (m in 1..12) total += monthDays(year, m)
        val lm = leapMonth(year)
        if (lm != 0) total += monthDays(year, lm, true)
        return total
    }

    /** 正月初一的日序，覆盖 1900–2101（末位为终表边界）。 */
    private fun newYears(): IntArray = newYearsCache

    // ---------------------------------------------------------------- 转换

    /** 公历 date → 农历 (年, 月, 日, 是否闰月)。 */
    fun solarToLunar(d: LocalDate): LunarDate {
        val o = ordinal(d)
        val ny = newYears()
        require(o >= ny[0] && o < ny[ny.size - 1]) { "超出历表范围：$d" }
        var lo = 0
        var hi = ny.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (ny[mid] <= o) lo = mid else hi = mid - 1
        }
        val year = LunarData.START_YEAR + lo
        var off = o - ny[lo]
        val leap = leapMonth(year)
        for (m in 1..12) {
            var n = monthDays(year, m)
            if (off < n) return LunarDate(year, m, off + 1, false)
            off -= n
            if (leap == m) {
                n = monthDays(year, m, true)
                if (off < n) return LunarDate(year, m, off + 1, true)
                off -= n
            }
        }
        error("月序表异常")
    }

    fun lunarToSolar(year: Int, month: Int, day: Int, leap: Boolean = false): LocalDate {
        require(year in LunarData.START_YEAR..LunarData.END_YEAR) {
            "超出历表范围：$year"
        }
        var o = newYears()[year - LunarData.START_YEAR]
        val lp = leapMonth(year)
        for (m in 1..12) {
            if (m == month && !leap) return fromOrdinal(o + day - 1)
            o += monthDays(year, m)
            if (lp == m) {
                if (m == month && leap) return fromOrdinal(o + day - 1)
                o += monthDays(year, m, true)
            }
        }
        throw IllegalArgumentException("无此月")
    }

    fun dayCn(d: Int): String = when {
        d == 10 -> "初十"
        d < 10 -> "初" + DIGIT[d - 1]
        d == 20 -> "二十"
        d < 20 -> "十" + DIGIT[d - 11]
        d == 30 -> "三十"
        else -> "廿" + DIGIT[d - 21]
    }

    fun lunarStr(d: LocalDate): String {
        val (y, m, dd, lp) = solarToLunar(d)
        return (if (lp) "闰" else "") + MONTH_CN[m - 1] + "月" + dayCn(dd)
    }

    // ---------------------------------------------------------------- 节气

    private fun ordFromJd(jd: Double): Int = (Math.floor(jd + 0.5)).toInt() - 1721425

    private fun solveTerm(target: Double, jd0: Double): Int {
        var jd = jd0
        for (i in 0 until 40) {
            val lam = SunMath.solarLongitude(jd)
            val diff = mod2((target - lam + 180.0), 360.0) - 180.0
            jd += diff / 0.9856473
            if (Math.abs(diff) < 1e-7) break
        }
        return ordFromJd(jd + 8.0 / 24.0)
    }

    private fun mod2(a: Double, b: Double): Double {
        val r = a % b
        return if (r < 0) r + b else r
    }

    /** 以该年立春起、至次年大寒止的二十四个节气：[(名, 日序)]。 */
    fun termsOfYear(year: Int): List<TermEntry> = synchronized(termCache) {
        termCache.getOrPut(year) {
            val out = ArrayList<TermEntry>(24)
            for (i in 0 until 24) {
                val (mm, dd) = GUESS[i]
                val yy = if (i < 22) year else year + 1
                val jd0 = ordinal(LocalDate.of(yy, mm, dd)) + 1721425 - 0.5
                out.add(TermEntry(TERMS24[i], solveTerm(315.0 + 15.0 * i, jd0)))
            }
            out
        }
    }

    /** 返回 (节气名, 交节日序, 在该年序列中的序号)。 */
    fun currentTerm(d: LocalDate): Triple<String, Int, Int> {
        val o = ordinal(d)
        var best: Int? = null
        var bestName: String = ""
        var bestIdx = -1
        for (y in intArrayOf(d.year - 1, d.year, d.year + 1)) {
            for ((i, t) in termsOfYear(y).withIndex()) {
                if (t.ordinal <= o && (best == null || t.ordinal > best)) {
                    best = t.ordinal
                    bestName = t.name
                    bestIdx = i
                }
            }
        }
        check(best != null) { "节气求解失败" }
        return Triple(bestName, best!!, bestIdx)
    }

    /** 当日是否交节；是则返回节气名。 */
    fun termOn(d: LocalDate): String? {
        val o = ordinal(d)
        for (y in intArrayOf(d.year - 1, d.year)) {
            for (t in termsOfYear(y)) {
                if (t.ordinal == o) return t.name
            }
        }
        return null
    }

    /** 当前候名。 */
    fun hou(d: LocalDate): String {
        val (_, to, idx) = currentTerm(d)
        var k = (ordinal(d) - to) / 5
        k = if (k < 0) 0 else if (k > 2) 2 else k
        return HOU[idx * 3 + k]
    }

    /** 下一节气：(名, 日序)。 */
    fun nextTerm(d: LocalDate): Pair<String, Int> {
        val o = ordinal(d)
        val cands = ArrayList<Pair<Int, String>>()
        for (y in intArrayOf(d.year - 1, d.year, d.year + 1)) {
            for (t in termsOfYear(y)) {
                if (t.ordinal > o) cands.add(t.ordinal to t.name)
            }
        }
        cands.sortBy { it.first }
        return cands[0].second to cands[0].first
    }

    // ---------------------------------------------------------------- 干支

    fun gzDay(d: LocalDate): String {
        val n = Math.floorMod(ordinal(d) + 14, 60)
        return GAN[n % 10].toString() + ZHI[n % 12]
    }

    fun lichunOrd(year: Int): Int = termsOfYear(year)[0].ordinal

    /** 年干支，以立春为界。 */
    fun gzYear(d: LocalDate): String {
        var y = d.year
        if (ordinal(d) < lichunOrd(y)) y -= 1
        val n = Math.floorMod(y - 1984, 60)
        return GAN[n % 10].toString() + ZHI[n % 12]
    }

    /** 月干支，以节为界；月建自寅月起。 */
    fun gzMonth(d: LocalDate): String {
        val o = ordinal(d)
        var bestTo = -1
        var bestY = -1
        var bestK = -1
        for (y in intArrayOf(d.year - 1, d.year, d.year + 1)) {
            val ts = termsOfYear(y)
            for ((k, ti) in JIE_IDX.withIndex()) {
                val to = ts[ti].ordinal
                if (to <= o && to > bestTo) {
                    bestTo = to
                    bestY = y
                    bestK = k
                }
            }
        }
        check(bestY != -1) { "月建求解失败" }
        var yg = bestY
        if (o < lichunOrd(bestY)) yg = bestY - 1
        val yearGan = Math.floorMod(Math.floorMod(yg - 1984, 60), 10)
        val yinGan = Math.floorMod(yearGan * 2 + 2, 10)
        val zhi = JIE_ZHI[bestK]
        val offset = Math.floorMod(zhi - 2, 12)
        return GAN[Math.floorMod(yinGan + offset, 10)].toString() + ZHI[zhi]
    }

    /** 当日所属农历年的生肖（以立春为界）。 */
    fun animal(d: LocalDate): String {
        var y = d.year
        if (ordinal(d) < lichunOrd(y)) y -= 1
        return ANIMAL[Math.floorMod(y - 1984, 12)].toString()
    }

    // ---------------------------------------------------------------- 历注

    /** 一行历注：年干支 + 农历月日 + 日干支 + 候。 */
    fun note(d: LocalDate): String = "${gzYear(d)}年 ${lunarStr(d)} · ${gzDay(d)}日 · ${hou(d)}"

    /** 三日条：(日期, 农历月日, 日干支, 候, 曜日)。 */
    data class StripEntry(val date: LocalDate, val lunarLabel: String, val gz: String, val hou: String, val wuyao: String)

    fun strip3(d: LocalDate): List<StripEntry> =
        (-1..1).map { off ->
            val cur = d.plusDays(off.toLong())
            StripEntry(cur, lunarStr(cur), gzDay(cur), hou(cur), wuyao(cur))
        }

    /** 七政曜日：日曜为周日，月火水木金土依次。 */
    fun wuyao(d: LocalDate): String = WUYAO[Math.floorMod(d.dayOfWeek.value, 7)].toString()

    /** 日历格：(日期或 null, 农历标注, 节气名或 null)。 */
    data class MonthCell(val date: LocalDate?, val label: String, val term: String?)

    /**
     * d 所在公历月的日历格（周日起首）。
     * 农历标注：朔日取月名（十月 / 闰四月），余取日名（初二 / 廿三）。
     */
    fun monthCells(d: LocalDate): List<MonthCell> {
        val first = d.withDayOfMonth(1)
        val lead = Math.floorMod(first.dayOfWeek.value, 7)          // 周日=0
        val n = first.lengthOfMonth()
        val cells = ArrayList<MonthCell>(lead + n)
        repeat(lead) { cells.add(MonthCell(null, "", null)) }
        for (i in 0 until n) {
            val cur = first.plusDays(i.toLong())
            val (y, m, dd, lp) = solarToLunar(cur)
            val lab = if (dd == 1) {
                (if (lp) "闰" else "") + MONTH_CN[m - 1] + "月"
            } else {
                dayCn(dd)
            }
            cells.add(MonthCell(cur, lab, termOn(cur)))
        }
        while (cells.size % 7 != 0) cells.add(MonthCell(null, "", null))
        return cells
    }

    /** 气泡两态所需全部数据，一次算好供渲染复用。 */
    data class Almanac(
        val today: LocalDate,
        val three: List<StripEntry>,
        val month: List<MonthCell>,
        val solarTitle: String,
        val lunarTitle: String,
        val week: List<String>,
    )

    fun almanac(d: LocalDate): Almanac {
        val (y, m, _, lp) = solarToLunar(d)
        return Almanac(
            today = d,
            three = strip3(d),
            month = monthCells(d),
            solarTitle = "${d.year}年${d.monthValue}月",
            lunarTitle = "${gzYear(d)}年 ${(if (lp) "闰" else "") + MONTH_CN[m - 1]}月",
            week = WUYAO.map { it.toString() },
        )
    }

    /** 日辰零件：年干支、农历月日、日干支、候。壁画题款与历注拼句用。 */
    data class DayParts(val y: String, val lum: String, val gz: String, val hou: String)

    fun parts(d: LocalDate): DayParts = DayParts(gzYear(d), lunarStr(d), gzDay(d), hou(d))

    // ---------------------------------------------------------------- 全量校验辅助

    /**
     * 指定年份逐日历算行串（与 golden 生成脚本逐字节一致，供 SHA-256 摘要比对）：
     * 每行 `ordinal|年|月|日|闰|int|日干支|年干支|月干支\n`。
     */
    fun yearDigestLines(year: Int): String {
        val ny = newYears()
        val start = ny[year - LunarData.START_YEAR]
        val end = ny[year - LunarData.START_YEAR + 1]
        val sb = StringBuilder()
        for (o in start until end) {
            val d = fromOrdinal(o)
            val (y, m, dd, lp) = solarToLunar(d)
            sb.append(o).append('|').append(y).append('|').append(m).append('|').append(dd)
                .append('|').append(if (lp) 1 else 0)
                .append('|').append(gzDay(d)).append('|').append(gzYear(d)).append('|')
                .append(gzMonth(d)).append('\n')
        }
        return sb.toString()
    }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
