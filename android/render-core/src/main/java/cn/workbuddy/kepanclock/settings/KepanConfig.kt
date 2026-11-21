package cn.workbuddy.kepanclock.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 应用配置与仓库（桌面 app.py load_config/save_cfg_value 的 Android 承接）。
 * 桌面 TOML 改应用内 DataStore（方案 §2.1）；键名沿用桌面语义对照：
 *   mode.theme / panel.watermark / panel.watermark_alpha / fonts.chen /
 *   place.latitude / place.longitude / dusk.offset_minutes。
 * 写回后 Flow 发射即生效（桌面的「半秒热重载」在 Android 侧等价为流发射，改即生效）。
 */
data class KepanConfig(
    val theme: String = "auto",          // auto / day / night
    val watermark: String = "zodiac",    // zodiac / feitian / beast / chen / none
    val wmAlpha: Double = 0.16,          // 0.02–0.45（与渲染钳制一致）
    // 字体（2026-11-21 用户拍板：全局切换所有文字，不止辰字）：
    // kai=楷体（内置 UKai 子集）/ ming=明体（系统 serif）/ zhuan=篆书（華瑞小篆子集）
    val font: String = "kai",
    val lat: Double = 39.9,
    val lon: Double = 116.4,
    val duskMin: Double = 37.0,          // 昏旦偏移（分钟）
    val soundEnabled: Boolean = false,   // 报时声（对齐桌面 sound.enabled，默认关）
) {
    companion object {
        val ALPHA_RANGE = 0.02..0.45
        val THEMES = arrayOf("auto", "day", "night")
        val WATERMARKS = arrayOf("zodiac", "feitian", "beast", "chen", "none")
        val FONTS = arrayOf("kai", "ming", "zhuan")

        /** 淡度钳制到 [0.02, 0.45]，三位小数（与桌面 round(…, 3) 一致）。 */
        fun clampAlpha(v: Double): Double =
            (v.coerceIn(ALPHA_RANGE.start, ALPHA_RANGE.endInclusive) * 1000).let {
                Math.round(it) / 1000.0
            }

        /** 纬度合法域 [-90, 90]，非法返回 null（交 UI 提示，不静默钳制）。 */
        fun parseLatitude(s: String): Double? =
            s.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }

        /** 经度合法域 [-180, 180]，非法返回 null。 */
        fun parseLongitude(s: String): Double? =
            s.trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 }

        /** 昏旦偏移合法域 [0, 120] 分钟（约古二刻半量级），非法返回 null。 */
        fun parseDusk(s: String): Double? =
            s.trim().toDoubleOrNull()?.takeIf { it in 0.0..120.0 }

        /** 主题值归一：白名单外的值回退 auto。 */
        fun normalizeTheme(v: String): String =
            if (v in THEMES) v else "auto"

        /** 剪影值归一：白名单外的值回退 zodiac。 */
        fun normalizeWatermark(v: String): String =
            if (v in WATERMARKS) v else "zodiac"

        /** 字体值归一：白名单外的值回退 kai。 */
        fun normalizeFont(v: String): String =
            if (v in FONTS) v else "kai"
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kepan")

class ConfigRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("mode.theme")
        val WATERMARK = stringPreferencesKey("panel.watermark")
        val WM_ALPHA = doublePreferencesKey("panel.watermark_alpha")
        val FONT = stringPreferencesKey("fonts.chen")          // "kai" / "ming" / "zhuan"
        val LAT = doublePreferencesKey("place.latitude")
        val LON = doublePreferencesKey("place.longitude")
        val DUSK = doublePreferencesKey("dusk.offset_minutes")
        val SOUND = booleanPreferencesKey("sound.enabled")
    }

    val config: Flow<KepanConfig> = context.dataStore.data.map { p ->
        KepanConfig(
            theme = KepanConfig.normalizeTheme(p[Keys.THEME] ?: "auto"),
            watermark = KepanConfig.normalizeWatermark(p[Keys.WATERMARK] ?: "zodiac"),
            wmAlpha = KepanConfig.clampAlpha(p[Keys.WM_ALPHA] ?: 0.16),
            font = KepanConfig.normalizeFont(p[Keys.FONT] ?: "kai"),
            lat = p[Keys.LAT] ?: 39.9,
            lon = p[Keys.LON] ?: 116.4,
            duskMin = p[Keys.DUSK] ?: 37.0,
            soundEnabled = p[Keys.SOUND] ?: false,
        )
    }

    suspend fun setTheme(v: String) = write(Keys.THEME, KepanConfig.normalizeTheme(v))
    suspend fun setWatermark(v: String) = write(Keys.WATERMARK, KepanConfig.normalizeWatermark(v))
    suspend fun setWmAlpha(v: Double) = write(Keys.WM_ALPHA, KepanConfig.clampAlpha(v))
    suspend fun setFont(v: String) = write(Keys.FONT, KepanConfig.normalizeFont(v))
    suspend fun setPlace(lat: Double, lon: Double) = context.dataStore.edit {
        it[Keys.LAT] = lat
        it[Keys.LON] = lon
    }
    suspend fun setDusk(v: Double) = write(Keys.DUSK, v)
    suspend fun setSoundEnabled(v: Boolean) = write(Keys.SOUND, v)

    private suspend fun write(key: Preferences.Key<*>, value: Any) {
        context.dataStore.edit {
            @Suppress("UNCHECKED_CAST")
            it[key as Preferences.Key<Any>] = value
        }
    }
}
