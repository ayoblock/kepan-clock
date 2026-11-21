package cn.workbuddy.kepanclock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * P1 差分黄金语料比对。Python oracle 生成于桌面版原实现，逐例/逐摘要对照 Kotlin。
 * 语料在 test/resources/golden/，含：时刻逐分钟 1440 例、更点逐分钟 1440 例、
 * 日出日落 96 例、节气 72 例、逐日明文 1096 例、1900–2100 逐年 SHA-256 摘要 201 例。
 */
class GoldenTest {

    private fun res(name: String): List<String> =
        javaClass.classLoader.getResourceAsStream("golden/$name")!!
            .bufferedReader(Charsets.UTF_8).readLines()

    private fun parseD(v: String): Double? = if (v == "none") null else v.toDouble()

    @Test
    fun `时刻换算逐分钟 1440 例`() {
        val lines = res("chen_1440.txt")
        assertEquals(1440, lines.size)
        for (line in lines) {
            val (m, read, animal, needle, side) = line.split("|")
            val minutes = m.toInt()
            assertEquals(read, ChenCore.readStr(minutes), "readStr($minutes)")
            assertEquals(animal, ChenCore.animal(minutes), "animal($minutes)")
            assertEquals(parseD(needle)!!, ChenCore.needleAngle(minutes.toDouble()), 1e-9, "needle($minutes)")
            assertEquals(parseD(side)!!, ChenCore.sideAngle(minutes), 1e-9, "side($minutes)")
        }
    }

    @Test
    fun `更点逐分钟 1440 例`() {
        val lines = res("geng_1440.txt")
        val spanLine = lines.first().split("|")
        val span = parseD(spanLine[1])!! to parseD(spanLine[2])!!
        for (line in lines.drop(1)) {
            val (m, gp, gs) = line.split("|")
            val minutes = m.toInt()
            val gotPoint = SunMath.gengPoint(minutes.toDouble(), span)
            if (gp == "none") {
                assertEquals(null, gotPoint, "gengPoint($minutes)")
            } else {
                val (g, p) = gp.split(",").map { it.toInt() }
                assertEquals(g to p, gotPoint, "gengPoint($minutes)")
            }
            assertEquals(gs, SunMath.gengStr(minutes.toDouble(), span), "gengStr($minutes)")
        }
    }

    @Test
    fun `日出日落 96 例`() {
        val lines = res("sun_times.txt")
        assertEquals(96, lines.size)
        for (line in lines) {
            val (date, lat, lon, rise, sett) = line.split("|")
            val got = SunMath.sunTimesLocal(LocalDate.parse(date), lat.toDouble(), lon.toDouble(), 480)
            assertEquals(parseD(rise)!!, got.first!!, 1e-4, "rise($date,$lat,$lon)")
            assertEquals(parseD(sett)!!, got.second!!, 1e-4, "set($date,$lat,$lon)")
        }
    }

    @Test
    fun `节气日序 72 例`() {
        val lines = res("terms.txt")
        assertEquals(72, lines.size)
        for (line in lines) {
            val (year, name, ord) = line.split("|")
            val got = Lunar.termsOfYear(year.toInt()).first { it.name == name }
            assertEquals(ord.toInt(), got.ordinal, "$year $name")
        }
    }

    @Test
    fun `逐日明文 1096 例`() {
        val lines = res("lunar_daily.txt")
        assertEquals(1096, lines.size)
        for (line in lines) {
            val parts = line.split("|")
            val date = parts[0]
            val lunarLabel = parts[1]
            val gzY = parts[2]
            val gzM = parts[3]
            val gzD = parts[4]
            val hou = parts[5]
            val wy = parts[6]
            val term = parts[7]
            val d = LocalDate.parse(date)
            assertEquals(lunarLabel, Lunar.lunarStr(d), "lunarStr($date)")
            assertEquals(gzY, Lunar.gzYear(d), "gzYear($date)")
            assertEquals(gzM, Lunar.gzMonth(d), "gzMonth($date)")
            assertEquals(gzD, Lunar.gzDay(d), "gzDay($date)")
            assertEquals(hou, Lunar.hou(d), "hou($date)")
            assertEquals(wy, Lunar.wuyao(d), "wuyao($date)")
            assertEquals(if (term == "-") null else term, Lunar.termOn(d), "termOn($date)")
        }
    }

    @Test
    fun `1900 到 2100 逐年逐日 SHA-256 摘要 201 例`() {
        val lines = res("lunar_hash.txt")
        assertEquals(201, lines.size)
        for (line in lines) {
            val (year, want) = line.split("|")
            val got = Lunar.sha256Hex(Lunar.yearDigestLines(year.toInt()))
            assertEquals(want, got, "逐年摘要 $year")
        }
    }

    @Test
    fun `ClockState 聚合与桌面语义一致`() {
        // 用北京 2026-12-21 12:00 验证关键派生字段
        val st = ClockState.compute(
            LocalDate.of(2026, 12, 21), 12, 0, 0, 0,
            lat = 39.9, lon = 116.4, tzMin = 480, dusk = 37.0,
        )
        assertEquals("午正一刻", st.read)
        assertEquals("昼", st.geng)
        assertEquals("马", st.animal, "12:00 属午时")
        assertTrue(st.dateStr == "2026年12月21日")
        assertTrue(st.hhmm == "12:00")
        assertTrue(!st.night)
        // 真太阳时读法应存在且不同（经度改正约 -14.4 分钟 + 时差）
        assertTrue(st.tsRead.isNotEmpty() && st.tsRead != st.read, "tsRead=${st.tsRead}")
    }
}
