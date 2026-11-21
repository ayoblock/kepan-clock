package cn.workbuddy.kepanclock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 移植自桌面 sun.py 的自校（日出日落、节气、更点、真太阳时）。
 */
class SunSelfTest {
    private val tz = 480   // 东八

    @Test
    fun `北京冬至日出日落量级`() {
        val (rise, sett) = SunMath.sunTimesLocal(LocalDate.of(2026, 12, 21), 39.9, 116.4, tz)
        assertTrue(rise != null && 7.0 * 60 < rise && rise < 7.9 * 60, "冬至日出异常: $rise")
        assertTrue(sett != null && 16.5 * 60 < sett && sett < 17.5 * 60, "冬至日落异常: $sett")
    }

    @Test
    fun `北京夏至日出日落量级`() {
        val (rise, sett) = SunMath.sunTimesLocal(LocalDate.of(2026, 6, 21), 39.9, 116.4, tz)
        assertTrue(rise != null && 4.3 * 60 < rise && rise < 5.0 * 60, "夏至日出异常: $rise")
        assertTrue(sett != null && 19.0 * 60 < sett && sett < 19.8 * 60, "夏至日落异常: $sett")
    }

    @Test
    fun `昼夜判别`() {
        val (rise, sett) = SunMath.sunTimesLocal(LocalDate.of(2026, 12, 21), 39.9, 116.4, tz)
        assertTrue(SunMath.isNight(0.0, rise, sett))
        assertFalse(SunMath.isNight(12 * 60.0, rise, sett))
    }

    @Test
    fun `节气自校`() {
        // 2026-11-10 应在立冬（11 月 7 日前后）
        assertEquals("立冬", SunMath.termName(2026, 11, 10, 12, 0, tz))
        // 2026-06-21 应在夏至或芒种
        val t2 = SunMath.termName(2026, 6, 21, 12, 0, tz)
        assertTrue(t2 == "夏至" || t2 == "芒种", "节气异常: $t2")
    }

    @Test
    fun `更点三更校验`() {
        val span = SunMath.nightSpan(0.0, 39.9, 116.4, tz, LocalDate.of(2026, 12, 21), 37.0)
        val mid = (span!!.first + span.second) / 2.0
        val (g, p) = SunMath.gengPoint(mid, span)!!
        assertEquals(3, g, "三更校验异常: ${g}更${p}点")
        assertTrue(p in 2..4, "三更校验异常: ${g}更${p}点")
    }

    @Test
    fun `真太阳时量级`() {
        // 北京东经 116.4，较东经 120 慢约 14.4 分钟；11 月时差为正约 +16 分
        val ts = SunMath.trueSolarMinutes(12 * 60.0, 314, 116.4, tz)
        assertTrue(Math.abs(ts - (720 - 14.4 + 16.4)) < 1.5, "真太阳时异常: $ts")
    }
}
