package cn.workbuddy.kepanclock

import cn.workbuddy.kepanclock.settings.KepanConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P3 配置纯函数 JVM 单测：钳制、白名单归一、经纬度与昏旦偏移校验。
 * 配置写回（DataStore）前的最后一道防线，非法值不得落盘。
 */
class ConfigLogicTest {

    @Test
    fun `淡度钳制到 002-045 并保留三位小数`() {
        assertEquals(0.02, KepanConfig.clampAlpha(0.0), 1e-9)
        assertEquals(0.45, KepanConfig.clampAlpha(0.9), 1e-9)
        assertEquals(0.16, KepanConfig.clampAlpha(0.16), 1e-9)
        // 桌面 round(…, 3)：超长小数收敛为三位
        assertEquals(0.123, KepanConfig.clampAlpha(0.12349), 1e-9)
        assertEquals(0.376, KepanConfig.clampAlpha(0.3755), 1e-9)
    }

    @Test
    fun `主题与剪影白名单归一`() {
        assertEquals("auto", KepanConfig.normalizeTheme("auto"))
        assertEquals("day", KepanConfig.normalizeTheme("day"))
        assertEquals("night", KepanConfig.normalizeTheme("night"))
        assertEquals("auto", KepanConfig.normalizeTheme("dark"))     // 白名单外回退
        assertEquals("zodiac", KepanConfig.normalizeWatermark("zodiac"))
        assertEquals("feitian", KepanConfig.normalizeWatermark("feitian"))
        assertEquals("zodiac", KepanConfig.normalizeWatermark("dragon"))  // 白名单外回退
    }

    @Test
    fun `经纬度与昏旦偏移校验`() {
        assertEquals(39.9, KepanConfig.parseLatitude("39.9")!!, 1e-9)
        assertNull(KepanConfig.parseLatitude("95"))     // 纬度越北界
        assertNull(KepanConfig.parseLatitude("-90.1"))
        assertNull(KepanConfig.parseLatitude("abc"))    // 非数值
        assertEquals(116.4, KepanConfig.parseLongitude("116.4")!!, 1e-9)
        assertNull(KepanConfig.parseLongitude("181"))   // 经度越东界
        assertEquals(-180.0, KepanConfig.parseLongitude(" -180 ")!!, 1e-9)
        assertEquals(37.0, KepanConfig.parseDusk("37")!!, 1e-9)
        assertNull(KepanConfig.parseDusk("121"))        // 昏旦偏移上限 120
        assertNull(KepanConfig.parseDusk("-1"))
        assertNull(KepanConfig.parseDusk(""))
        // 合法输入带空白
        assertEquals(31.2, KepanConfig.parseLatitude(" 31.2 ")!!, 1e-9)
    }

    @Test
    fun `默认配置与桌面一致`() {
        val c = KepanConfig()
        assertEquals("auto", c.theme)
        assertEquals("zodiac", c.watermark)
        assertEquals(0.16, c.wmAlpha, 1e-9)
        assertEquals("kai", c.font)
        assertEquals(39.9, c.lat, 1e-9)
        assertEquals(116.4, c.lon, 1e-9)
        assertEquals(37.0, c.duskMin, 1e-9)
        assertFalse(KepanConfig.WATERMARKS.isEmpty())
    }

    @Test
    fun `字体三选白名单归一`() {
        assertEquals("kai", KepanConfig.normalizeFont("kai"))
        assertEquals("ming", KepanConfig.normalizeFont("ming"))
        assertEquals("zhuan", KepanConfig.normalizeFont("zhuan"))
        assertEquals("kai", KepanConfig.normalizeFont("serif"))   // 白名单外回退
        assertEquals("kai", KepanConfig.normalizeFont(""))        // 空值回退
    }
}
