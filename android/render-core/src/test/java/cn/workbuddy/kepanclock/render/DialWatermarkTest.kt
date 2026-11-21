package cn.workbuddy.kepanclock.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 水印数据表回归：修复「汉字名直查拼音键表 → table[name]!! NPE 崩溃」
 * （桌面版有 _ZODIAC_KEY/_FT_KEY 两级映射，移植首版遗漏）。
 */
class DialWatermarkTest {

    private val zodiacOrder = "鼠牛虎兔龙蛇马羊猴鸡狗猪"
    private val ftOrder = "子丑寅卯辰巳午未申酉戌亥"

    @Test
    fun `key maps are complete and bijective with tables`() {
        assertEquals(12, DialData.ZODIAC_KEY.size)
        assertEquals(12, DialData.FT_KEY.size)
        for (ch in zodiacOrder) {
            val key = DialData.ZODIAC_KEY[ch.toString()]
            assertNotNull(key, "生肖映射缺 $ch")
            assertTrue(DialData.ZODIAC.containsKey(key), "生肖表缺键 $key")
        }
        for (ch in ftOrder) {
            val key = DialData.FT_KEY[ch.toString()]
            assertNotNull(key, "飞天映射缺 $ch")
            assertTrue(DialData.FT.containsKey(key), "飞天表缺键 $key")
        }
    }

    @Test
    fun `key map order aligns with chen order`() {
        zodiacOrder.forEachIndexed { i, ch ->
            assertEquals(DialData.ZODIAC.keys.toList()[i], DialData.ZODIAC_KEY[ch.toString()])
        }
        ftOrder.forEachIndexed { i, ch ->
            assertEquals(DialData.FT.keys.toList()[i], DialData.FT_KEY[ch.toString()])
        }
    }

    @Test
    fun `every mapped part set yields a bbox`() {
        for (key in DialData.ZODIAC_KEY.values) {
            val box = Primitives.bboxOf("zodiac", DialData.ZODIAC, key)
            assertTrue(box[2] > box[0])
            assertTrue(box[3] > box[1])
        }
        for (key in DialData.FT_KEY.values) {
            val box = Primitives.bboxOf("feitian", DialData.FT, key)
            assertTrue(box[2] > box[0])
            assertTrue(box[3] > box[1])
        }
    }

    @Test
    fun `raw hanzi lookup reproduces the original crash`() {
        // 防回归锚点：首版崩溃路径——拿汉字当表键必为 null
        assertThrows(NullPointerException::class.java) {
            DialData.ZODIAC["鼠"]!!
        }
        assertThrows(NullPointerException::class.java) {
            DialData.FT["子"]!!
        }
    }

    @Test
    fun `chen slot index is floor-mod aligned with desktop`() {
        // 对齐桌面 int((t_min + 60.0) // 120.0) % 12（floor 语义，负时区不越界）
        fun idxOf(tMin: Double) = Math.floorMod(Math.floor((tMin + 60.0) / 120.0).toInt(), 12)
        assertEquals(4, idxOf(480.0))   // UTC+8 → 午（马）
        assertEquals(8, idxOf(-480.0))  // UTC-8：截断版会得 -3（越界），floor 版为 8
        assertEquals(0, idxOf(-60.0))
        assertEquals(1, idxOf(60.0))
    }
}
