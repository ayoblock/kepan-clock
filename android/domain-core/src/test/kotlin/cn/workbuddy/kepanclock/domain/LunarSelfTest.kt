package cn.workbuddy.kepanclock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 移植自桌面 lunar.py 的自校（春节、闰月、干支锚点、农历月日、节气日、候、连续性）。
 */
class LunarSelfTest {

    @Test
    fun `春节 11 例正反双向`() {
        val chunjie = mapOf(
            2020 to (1 to 25), 2021 to (2 to 12), 2022 to (2 to 1), 2023 to (1 to 22),
            2024 to (2 to 10), 2025 to (1 to 29), 2026 to (2 to 17), 2027 to (2 to 6),
            2028 to (1 to 26), 2029 to (2 to 13), 2030 to (2 to 3),
        )
        for ((y, md) in chunjie) {
            val got = Lunar.lunarToSolar(y, 1, 1)
            assertEquals(md, got.monthValue to got.dayOfMonth, "春节错误 $y: $got")
            val back = Lunar.solarToLunar(LocalDate.of(y, md.first, md.second))
            assertEquals(Lunar.LunarDate(y, 1, 1, false), back, "反查错误 $y: $back")
        }
    }

    @Test
    fun `闰月 5 例正反双向`() {
        val leapCases = mapOf(2020 to 4, 2023 to 2, 2025 to 6, 2028 to 5, 2033 to 11)
        for ((y, lm) in leapCases) {
            assertEquals(lm, Lunar.leapMonth(y), "闰月错误 $y")
            val solar = Lunar.lunarToSolar(y, lm, 1, true)
            val back = Lunar.solarToLunar(solar)
            assertEquals(Lunar.LunarDate(y, lm, 1, true), back, "闰月反查错误 $y")
        }
    }

    @Test
    fun `干支锚点`() {
        val anchors = mapOf(
            LocalDate.of(2026, 11, 10) to Triple("丙午", "己亥", "戊子"),
            LocalDate.of(2026, 11, 11) to Triple(null, null, "己丑"),
            LocalDate.of(2026, 11, 9) to Triple(null, null, "丁亥"),
        )
        for ((d, expect) in anchors) {
            val (gy, gm, gd) = expect
            gy?.let { assertEquals(it, Lunar.gzYear(d), "年干支 $d") }
            gm?.let { assertEquals(it, Lunar.gzMonth(d), "月干支 $d") }
            assertEquals(gd, Lunar.gzDay(d), "日干支 $d")
        }
    }

    @Test
    fun `农历月日`() {
        assertEquals("十月初二", Lunar.lunarStr(LocalDate.of(2026, 11, 10)))
    }

    @Test
    fun `节气日对照公开历表`() {
        val ts = Lunar.termsOfYear(2026)
        assertEquals(Lunar.ordinal(LocalDate.of(2026, 11, 7)), ts.first { it.name == "立冬" }.ordinal, "立冬")
        assertEquals(Lunar.ordinal(LocalDate.of(2026, 11, 22)), ts.first { it.name == "小雪" }.ordinal, "小雪")
    }

    @Test
    fun `候自校`() {
        // 立冬后三日应为「水始冰」
        assertEquals("水始冰", Lunar.hou(LocalDate.of(2026, 11, 10)))
    }

    @Test
    fun `连续 400 日推进不跳月`() {
        var d = LocalDate.of(2025, 12, 1)
        var prev = Lunar.solarToLunar(d)
        repeat(400) {
            d = d.plusDays(1)
            val cur = Lunar.solarToLunar(d)
            val sameMonth = cur.year == prev.year && cur.month == prev.month && cur.leap == prev.leap
            assertTrue(sameMonth || cur.day == 1, "月序跳变 $d: $prev → $cur")
            prev = cur
        }
    }
}
