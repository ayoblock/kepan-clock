package cn.workbuddy.kepanclock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 历注 API 与边界分支覆盖（与桌面 lunar.py note/strip3/month_cells 等逐函数对齐）。
 */
class LunarApiTest {

    @Test
    fun `dayCn 全分支`() {
        assertEquals("初一", Lunar.dayCn(1))
        assertEquals("初五", Lunar.dayCn(5))
        assertEquals("初十", Lunar.dayCn(10))
        assertEquals("十一", Lunar.dayCn(11))
        assertEquals("十九", Lunar.dayCn(19))
        assertEquals("二十", Lunar.dayCn(20))
        assertEquals("廿一", Lunar.dayCn(21))
        assertEquals("廿三", Lunar.dayCn(23))
        assertEquals("廿九", Lunar.dayCn(29))
        assertEquals("三十", Lunar.dayCn(30))
    }

    @Test
    fun `曜日对位`() {
        // 2026-11-22 是周日 → 日曜
        assertEquals("日", Lunar.wuyao(LocalDate.of(2026, 11, 22)))
        assertEquals("月", Lunar.wuyao(LocalDate.of(2026, 11, 23)))
        assertEquals("土", Lunar.wuyao(LocalDate.of(2026, 11, 21)))
    }

    @Test
    fun `termOn 交节日`() {
        assertEquals("立冬", Lunar.termOn(LocalDate.of(2026, 11, 7)))
        assertNull(Lunar.termOn(LocalDate.of(2026, 11, 8)))
    }

    @Test
    fun `nextTerm 自校`() {
        // 2026-11-10 已过立冬（11-07），下一个是小雪（11-22）
        val (name, ord) = Lunar.nextTerm(LocalDate.of(2026, 11, 10))
        assertEquals("小雪", name)
        assertEquals(Lunar.ordinal(LocalDate.of(2026, 11, 22)), ord)
    }

    @Test
    fun `strip3 三日条`() {
        val d = LocalDate.of(2026, 11, 10)
        val three = Lunar.strip3(d)
        assertEquals(3, three.size)
        assertEquals(d.minusDays(1), three[0].date)
        assertEquals(d, three[1].date)
        assertEquals(d.plusDays(1), three[2].date)
        assertEquals("戊子", three[1].gz)
        assertEquals(Lunar.hou(d), three[1].hou)
    }

    @Test
    fun `monthCells 网格与朔日标注`() {
        val cells = Lunar.monthCells(LocalDate.of(2026, 11, 10))
        // 周日起首整周
        assertEquals(0, cells.size % 7)
        // 2026-11-01 是周日（无前导空位），农历九月廿三
        assertEquals("廿三", cells[0].label)
        assertEquals(LocalDate.of(2026, 11, 1), cells[0].date)
        // 十月初一在 11-09 → 朔日标注月名
        assertEquals("十月", cells[8].label)
        assertEquals("初二", cells[9].label)
        // 交节日标注：11-07 立冬
        assertEquals("立冬", cells[6].term)
        // 末尾空位补齐：11-30 后补到整周
        assertEquals(35, cells.size)
    }

    @Test
    fun `almanac 一次算齐`() {
        val d = LocalDate.of(2026, 11, 10)
        val a = Lunar.almanac(d)
        assertEquals(d, a.today)
        assertEquals(3, a.three.size)
        assertEquals("2026年11月", a.solarTitle)
        assertEquals("丙午年 十月", a.lunarTitle)
        assertEquals(listOf("日", "月", "火", "水", "木", "金", "土"), a.week)
        assertTrue(a.month.size % 7 == 0)
    }

    @Test
    fun `历表范围外抛出`() {
        assertThrows(IllegalArgumentException::class.java) {
            Lunar.solarToLunar(LocalDate.of(1899, 1, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Lunar.lunarToSolar(2110, 1, 1)
        }
    }

    @Test
    fun `ClockState 主题锁定与强制`() {
        val args = mapOf(
            "date" to LocalDate.of(2026, 12, 21),
        )
        // 常夜锁定：正午也是夜且 blend 恒 1（不叠加过渡）
        val nightLocked = ClockState.compute(
            args["date"]!!, 12, 0, 0, 0,
            lat = 39.9, lon = 116.4, tzMin = 480, dusk = 37.0, theme = "night",
        )
        assertTrue(nightLocked.night)
        assertEquals(1.0, nightLocked.blend)
        // 常昼锁定：午夜也是昼
        val dayLocked = ClockState.compute(
            args["date"]!!, 23, 30, 0, 0,
            lat = 39.9, lon = 116.4, tzMin = 480, dusk = 37.0, theme = "day",
        )
        assertTrue(!dayLocked.night)
        assertEquals(0.0, dayLocked.blend)
        // forceNight 覆盖 theme
        val forced = ClockState.compute(
            args["date"]!!, 12, 0, 0, 0,
            lat = 39.9, lon = 116.4, tzMin = 480, dusk = 37.0,
            theme = "day", forceNight = true,
        )
        assertTrue(forced.night)
        // auto 正午为昼、blend 0
        val auto = ClockState.compute(
            args["date"]!!, 12, 0, 0, 0,
            lat = 39.9, lon = 116.4, tzMin = 480, dusk = 37.0,
        )
        assertTrue(!auto.night)
        assertEquals(0.0, auto.blend)
        // 极昼地点（6 月北纬 75°）：缺值恒判昼
        val polar = ClockState.compute(
            LocalDate.of(2026, 6, 21), 12, 0, 0, 0,
            lat = 75.0, lon = 40.0, tzMin = 480, dusk = 37.0,
        )
        assertTrue(!polar.night)
        assertEquals("更点缺", polar.geng)
    }
}
