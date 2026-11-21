package cn.workbuddy.kepanclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cn.workbuddy.kepanclock.strike.StrikeAudio
import cn.workbuddy.kepanclock.strike.StrikeLogic

/**
 * 报时逻辑回归（对齐桌面 app.py:787–793 触发式与 _EVENTS 序列）。
 * 手算锚点：u=(m+60)%1440, p=u%120。
 *   m=60（丑初 01:00）→ p=0 → bell3；m=0（子正 00:00）→ p=60 → drum2；
 *   m=15（一刻）→ p=75 → drum1；m=63 → p=3 → null。
 */
class StrikeLogicTest {

    @Test
    fun `事件序列对齐桌面 _EVENTS`() {
        assertEquals(listOf("bell", "bell", "bell"), StrikeLogic.sequence("bell3"))
        assertEquals(listOf("drum", "drum"), StrikeLogic.sequence("drum2"))
        assertEquals(listOf("drum"), StrikeLogic.sequence("drum1"))
        assertTrue(StrikeLogic.sequence("unknown").isEmpty())
    }

    @Test
    fun `时辰初报钟三声`() {
        // 01:00/03:00/…：m ≡ 60 (mod 120)
        assertEquals("bell3", StrikeLogic.eventForMinute(60))
        assertEquals("bell3", StrikeLogic.eventForMinute(180))
        assertEquals("bell3", StrikeLogic.eventForMinute(23 * 60)) // 23:00 子初
    }

    @Test
    fun `时辰正报鼓两声`() {
        // 00:00/02:00/…：m ≡ 0 (mod 120)
        assertEquals("drum2", StrikeLogic.eventForMinute(0))
        assertEquals("drum2", StrikeLogic.eventForMinute(120))
        assertEquals("drum2", StrikeLogic.eventForMinute(12 * 60)) // 12:00 午正
    }

    @Test
    fun `每刻报鼓一声`() {
        assertEquals("drum1", StrikeLogic.eventForMinute(15))
        assertEquals("drum1", StrikeLogic.eventForMinute(30))
        assertEquals("drum1", StrikeLogic.eventForMinute(45))
        assertEquals("drum1", StrikeLogic.eventForMinute(75)) // 时辰内一刻
    }

    @Test
    fun `非刻线分钟不报`() {
        assertNull(StrikeLogic.eventForMinute(7))
        assertNull(StrikeLogic.eventForMinute(63))
        assertNull(StrikeLogic.eventForMinute(1439))
    }

    @Test
    fun `下一刻边界`() {
        assertEquals(15L, StrikeLogic.nextQuarterMinute(0))
        assertEquals(15L, StrikeLogic.nextQuarterMinute(14))
        assertEquals(30L, StrikeLogic.nextQuarterMinute(15))
        assertEquals(60L, StrikeLogic.nextQuarterMinute(45))
        assertEquals(60L, StrikeLogic.nextQuarterMinute(46))
    }

    @Test
    fun `声样合成形状对齐桌面 _synth_strike`() {
        val bell = StrikeAudio.synth("bell")
        val drum = StrikeAudio.synth("drum")
        // 时长：bell 0.45 s、drum 0.4 s（22050 Hz）
        assertEquals((22050 * 0.45).toInt(), bell.size)
        assertEquals((22050 * 0.4).toInt(), drum.size)
        // 峰值归一 ×31000：恰有一处顶到幅值上限附近，且不削波
        assertTrue(bell.maxOf { kotlin.math.abs(it.toInt()) } in 30000..31000)
        assertTrue(drum.maxOf { kotlin.math.abs(it.toInt()) } in 30000..31000)
        // 非静音（首样本应有能量）
        assertTrue(kotlin.math.abs(bell[10].toInt()) > 1000)
    }

    @Test
    fun `drum手机端音高适配锁定`() {
        // 2026-11-21 用户拍板：桌面 150→62 Hz 手机扬声器不可闻，
        // 适配为 320→130 Hz 扫频 + 2.4 倍频泛音。归一化在比值中可约去，
        // 用样本 10/5 的公式比值比对 PCM 比值锁定公式（防意外回归桌面值）。
        fun formula(i: Int): Double {
            val t = i / 22050.0
            val f = 320.0 * kotlin.math.exp(-6 * t) + 130.0
            return 0.9 * kotlin.math.exp(-7.0 * t) * kotlin.math.sin(2 * Math.PI * f * t) +
                0.30 * kotlin.math.exp(-9.0 * t) * kotlin.math.sin(2 * Math.PI * 2.4 * f * t)
        }
        val drum = StrikeAudio.synth("drum")
        val pcmRatio = drum[10].toDouble() / drum[5].toDouble()
        assertEquals(formula(10) / formula(5), pcmRatio, 1e-3) // Short 量化 ±1
    }

    @Test
    fun `串行时长估算含留白`() {
        // 实录声样时长：bell 3.0 s、drum 1.5 s（对齐桌面随包 bell.wav/drum.wav）
        // bell3 = 3×3.0 + 2×0.20 = 9.40 s
        assertEquals(9.40, StrikeAudio.sequenceDurationSec("bell3"), 1e-9)
        // drum2 = 2×1.5 + 1×0.20 = 3.20 s；drum1 = 1.5 s
        assertEquals(3.20, StrikeAudio.sequenceDurationSec("drum2"), 1e-9)
        assertEquals(1.50, StrikeAudio.sequenceDurationSec("drum1"), 1e-9)
    }
}
