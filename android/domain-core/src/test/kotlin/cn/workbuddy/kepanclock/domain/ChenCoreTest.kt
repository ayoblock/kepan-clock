package cn.workbuddy.kepanclock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * 移植自桌面 chen_core.py 的 11 例自校，逐例对齐。
 * 回归触发条件：辰界跨 23:00 子时、初正分界、刻序边界——这些是 Python→Kotlin 最容易走样的点。
 */
class ChenCoreTest {

    @ParameterizedTest
    @CsvSource(
        "720, 午正一刻",
        "1110, 酉正三刻",
        "1410, 子初三刻",
        "0, 子正一刻",
        "1380, 子初一刻",
        "1439, 子初四刻",
        "60, 丑初一刻",
        "307, 卯初一刻",
        "315, 卯初二刻",
        "434, 辰初一刻",
        "695, 午初三刻",
    )
    fun `readStr 与桌面自校逐例一致`(minutes: Int, expected: String) {
        assertEquals(expected, ChenCore.readStr(minutes), "readStr($minutes)")
    }

    @Test
    fun `晷针角位四正方位`() {
        assertEquals(-90.0, ChenCore.needleAngle(360.0), "卯正居左")
        assertEquals(90.0, ChenCore.needleAngle(1080.0), "酉正居右")
        assertEquals(0.0, ChenCore.needleAngle(720.0), "午正居上")
        assertEquals(180.0, kotlin.math.abs(ChenCore.needleAngle(0.0)), "子正居下")
    }

    @Test
    fun `生肖对位`() {
        assertEquals("鼠", ChenCore.animal(1380))
        assertEquals("羊", ChenCore.animal(780))
    }

    @Test
    fun `副盘刻针角位`() {
        // u=(m+60)%1440, p=u%120；m=1380 → u=0,p=0；m=120 → u=180,p=60
        assertEquals(0.0, ChenCore.sideAngle(1380), "子初在正上")
        assertEquals(180.0, ChenCore.sideAngle(120), "辰半周")
    }
}
