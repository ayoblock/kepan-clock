package cn.workbuddy.kepanclock.wallpaper

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import cn.workbuddy.kepanclock.domain.ClockState
import cn.workbuddy.kepanclock.render.Palette
import cn.workbuddy.kepanclock.render.Primitives
import cn.workbuddy.kepanclock.render.SceneRenderer
import cn.workbuddy.kepanclock.render.SceneState
import cn.workbuddy.kepanclock.settings.ConfigRepository
import cn.workbuddy.kepanclock.settings.KepanConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 刻盘钟壁画（P4-R2，§3.5-4）：完全独立于主界面——自带 ClockState 副本、
 * 独立 tick 驱动，读同一份 DataStore 配置（随昼夜/辰字即时热重载）。
 *
 * 壁画构图（dial.draw_wallpaper 1:1）：大盘居中（径 0.36×短边）、
 * 星宿与针、四角竖题（刻盘钟/候/日干支/年干支）、底部一行读数。
 *
 * 功耗三道闸门（DoD）：
 *  1. 可见闸门——onVisibilityChanged(false) 立即停 tick，恢复时重绘一帧；
 *  2. 空闲闸门——盘面签名（读数/更点/分钟/blend/配置）未变不重绘；
 *  3. 温控降帧——API 31+ thermalStatus 达 MODERATE 时 tick 由 6 s 降到 30 s。
 */
class KepanWallpaperService : WallpaperService() {

    companion object {
        /** 平稳档：六秒一换（桌面 TICK_STEADY_MS 同款）。 */
        const val TICK_STEADY_MS: Long = 6000

        /** 昼夜渐变档（桌面 TICK_FADE_MS 同款）。 */
        const val TICK_FADE_MS: Long = 100

        /** 温控降帧档：热状态达 MODERATE 时的 tick 周期。 */
        const val TICK_THERMAL_MS: Long = 30000
    }

    override fun onCreateEngine(): Engine = KepanEngine()

    inner class KepanEngine : Engine() {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val handler = Handler(Looper.getMainLooper())
        private var cfg = KepanConfig()
        private var st: SceneState? = null
        private var visible = false
        private var lastSig = ""
        private var scheduled = false

        // 字体表（与 ClockView 同款：kai/ming/zhuan 三态，zhuan 全盘篆化）
        private val fonts: Map<String, Primitives.Fonts> by lazy {
            val loaded = runCatching {
                Primitives.Fonts(
                    chen = Typeface.createFromAsset(applicationContext.assets, "fonts/UKai.ttf"),
                    seal = Typeface.createFromAsset(applicationContext.assets, "fonts/HuaRuiSeal.ttf"),
                    sealAlt = Typeface.createFromAsset(applicationContext.assets, "fonts/HuaRuiSeal.ttf"),
                )
            }.getOrElse {
                Primitives.Fonts(Typeface.SERIF, Typeface.SERIF)
            }
            // 明体：工程未内置明体，用系统 serif 承接（与 ClockView 同款）
            val ming = Primitives.Fonts(
                chen = Typeface.create(Typeface.SERIF, Typeface.NORMAL),
                seal = loaded.seal,
                sealAlt = loaded.sealAlt,
            )
            val zhuan = Primitives.Fonts(
                chen = loaded.seal,
                seal = loaded.seal,
                sealAlt = loaded.sealAlt,
            )
            mapOf("kai" to loaded, "ming" to ming, "zhuan" to zhuan)
        }

        private val ticker = object : Runnable {
            override fun run() {
                scheduled = false
                if (!visible) return
                compute()
                draw()
                schedule(nextInterval())
            }
        }

        init {
            scope.launch {
                ConfigRepository(applicationContext).config.collectLatest { c ->
                    cfg = c
                    schedule(0)   // 配置变更即刻重绘（热重载）
                }
            }
        }

        private fun schedule(ms: Long) {
            handler.removeCallbacks(ticker)
            scheduled = true
            handler.postDelayed(ticker, ms)
        }

        /** 下一 tick 周期：渐变档 100 ms；温控闸门触发时 30 s；否则六秒。 */
        private fun nextInterval(): Long {
            val blend = st?.blend ?: 0.0
            if (blend in 0.001..0.999) return TICK_FADE_MS
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val pm = getSystemService(PowerManager::class.java)
                val status = runCatching { pm.currentThermalStatus }
                    .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
                if (status >= PowerManager.THERMAL_STATUS_MODERATE) return TICK_THERMAL_MS
            }
            return TICK_STEADY_MS
        }

        private fun compute() {
            val now = LocalDateTime.now()
            val cs = ClockState.compute(
                date = now.toLocalDate(),
                hour = now.hour, minute = now.minute, second = now.second,
                micro = now.nano / 1000,
                lat = cfg.lat, lon = cfg.lon, tzMin = 480, dusk = cfg.duskMin,
                theme = cfg.theme,
            )
            st = SceneRenderer.sceneStateOf(cs)
        }

        private fun draw() {
            val holder = surfaceHolder
            val canvas = holder.lockCanvas() ?: return
            try {
                val s = st ?: return
                // 空闲闸门：盘面签名未变不重绘
                val frame = holder.surfaceFrame
                val sig = "${s.tMin}|${"%.2f".format(s.blend)}|${s.geng}|${s.read}|" +
                    "${cfg.theme}|${cfg.font}|${frame.width()}x${frame.height()}"
                if (sig == lastSig) return
                lastSig = sig

                val dpr = applicationContext.resources.displayMetrics.density
                val pal = Palette.lerp(Palette.day(), Palette.night(), s.blend)
                val f = fonts[cfg.font] ?: fonts["kai"]!!
                canvas.save()
                canvas.scale(dpr, dpr)
                SceneRenderer.drawWallpaper(
                    canvas, frame.width() / dpr.toDouble(),
                    frame.height() / dpr.toDouble(),
                    s, pal, f, dpr.toDouble(),
                )
                canvas.restore()
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            if (visible) {
                // 恢复可见：立即重绘一帧（停绘期间状态可能已过期）
                schedule(0)
            } else {
                // 可见闸门：立停全部绘制循环
                handler.removeCallbacks(ticker)
                scheduled = false
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            lastSig = ""          // 尺寸变化必须重绘
            schedule(0)
        }

        override fun onDestroy() {
            handler.removeCallbacksAndMessages(null)
            scope.cancel()
            super.onDestroy()
        }
    }
}
