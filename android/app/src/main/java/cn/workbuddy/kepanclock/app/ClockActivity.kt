package cn.workbuddy.kepanclock.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import cn.workbuddy.kepanclock.render.RenderGeometry
import cn.workbuddy.kepanclock.settings.ConfigRepository
import cn.workbuddy.kepanclock.settings.KepanConfig
import cn.workbuddy.kepanclock.settings.SettingsSheet
import cn.workbuddy.kepanclock.strike.StrikeScheduler
import kotlin.math.min
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 主界面容器（§9-3 拍板结构）：
 * FrameLayout 内两个**平级**子 View——
 *   1) ClockView：原生自定义 View，占满全屏（盘面，严禁放进 AndroidView）
 *   2) ComposeView：仅承载设置牌窗，弹出框式浮卡（placeSettingsSheet 定位），不承载盘面
 * 「天行」字印开合设置页；点盘面其他区域收起（桌面点击盘外收气泡的同款语义）。
 */
class ClockActivity : ComponentActivity() {

    private lateinit var repo: ConfigRepository
    private lateinit var clockView: ClockView
    private lateinit var settingsView: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository(this)
        // 报时若处于开启态：启动即重布下一刻闹钟（对齐「唯一触发源」语义）
        lifecycleScope.launch {
            if (runCatching { repo.config.first().soundEnabled }.getOrDefault(false)) {
                StrikeScheduler.scheduleNext(this@ClockActivity)
            }
        }

        settingsView = ComposeView(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            visibility = View.GONE
        }

        clockView = ClockView(this, repository = repo)
        clockView.listener = object : ClockView.Listener {
            override fun onSealToggle() {
                placeSettingsSheet()    // 展开前按当前几何定位（弹出框式浮卡）
                settingsView.visibility =
                    if (settingsView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }

            override fun onClockTap() {
                settingsView.visibility = View.GONE
            }
        }

        FrameLayout(this).apply {
            addView(
                clockView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(settingsView)
        }.also { setContentView(it) }

        // 配置流 → 设置页（Compose 状态）与写回回调；状态栏随生效昼夜主题
        val cfgState = androidx.compose.runtime.mutableStateOf(KepanConfig())
        lifecycleScope.launch {
            repo.config.collectLatest { c ->
                cfgState.value = c
                applySystemBars(
                    when (c.theme) {
                        "day" -> false
                        "night" -> true
                        else -> clockView.effectiveNight
                    },
                )
            }
        }
        settingsView.setContent {
            val cfg = cfgState.value
            // 设置牌窗跟随盘面生效昼夜（blend≥0.5 视夜）；cfg 变更即重组刷新。
            // 高度上限随方向（横屏 0.86h / 竖屏 0.62h），超出内滚——不占满屏幕
            val conf = androidx.compose.ui.platform.LocalConfiguration.current
            val landscape = conf.screenWidthDp > conf.screenHeightDp
            val maxH = ((if (landscape) conf.screenHeightDp * 0.86 else conf.screenHeightDp * 0.62)).dp
            SettingsSheet(
                cfg = cfg,
                night = clockView.effectiveNight,
                modifier = androidx.compose.ui.Modifier.heightIn(max = maxH),
                onTheme = { v -> lifecycleScope.launch { repo.setTheme(v) } },
                onWatermark = { v -> lifecycleScope.launch { repo.setWatermark(v) } },
                onAlpha = { v -> lifecycleScope.launch { repo.setWmAlpha(v) } },
                onFont = { v -> lifecycleScope.launch { repo.setFont(v) } },
                onSound = { v -> lifecycleScope.launch { repo.setSoundEnabled(v) }; onSoundToggle(v) },
                onLiveWallpaper = { launchLiveWallpaperPicker() },
                onSnapshot = {
                    clockView.renderSnapshot?.let { (st, f) ->
                        val msg = WallpaperSnapshot.export(this, st, f)
                        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                onPlace = { la, lo, du ->
                    lifecycleScope.launch {
                        repo.setPlace(la, lo)
                        repo.setDusk(du)
                    }
                },
            )
        }
    }

    /**
     * 设置浮卡定位（2026-11-23 v1.6.9：与历注弹出框同款的浮卡，不再满宽底板）。
     * 横屏：覆于右面板位（面板中心横向居中、垂直居中＝屏心＝面板中心），
     * 主盘保持可见；竖屏：底部居中悬浮（底距 20dp）。
     * 宽度横屏 min(300dp, w−24) / 竖屏 min(320dp, w−24)；高度自内容，
     * 上限在 Compose 侧 heightIn（横屏 0.86h / 竖屏 0.62h），超出内滚。
     */
    private fun placeSettingsSheet() {
        val dm = clockView.resources.displayMetrics
        val density = dm.density.toDouble()
        val wDp = clockView.width / density
        val hDp = clockView.height / density
        if (wDp <= 0.0 || hDp <= 0.0) return
        val landscape = wDp > hDp
        val cardW = min(if (landscape) 300.0 else 320.0, wDp - 24.0)
        val lp = settingsView.layoutParams as FrameLayout.LayoutParams
        lp.width = (cardW * density).toInt()
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        if (landscape) {
            val lay = RenderGeometry.layout(wDp, hDp)
            val pcx = lay.px + lay.pw / 2.0
            lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            lp.leftMargin =
                ((pcx - cardW / 2.0).coerceIn(8.0, wDp - cardW - 8.0) * density).toInt()
            lp.topMargin = 0
            lp.bottomMargin = 0
        } else {
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = (20.0 * density).toInt()
            lp.leftMargin = 0
            lp.topMargin = 0
        }
        settingsView.layoutParams = lp
    }

    /** 状态栏（通知栏）随盘面昼夜主题：底色取 Palette.bg，图标浅/深反转。 */
    private fun applySystemBars(night: Boolean) {
        val pal = if (night) cn.workbuddy.kepanclock.render.Palette.night()
        else cn.workbuddy.kepanclock.render.Palette.day()
        window.statusBarColor = cn.workbuddy.kepanclock.render.Palette.argb(pal["bg"])
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !night
    }

    /** 跳系统动态壁纸选择器并预选刻盘钟壁画。 */
    private fun launchLiveWallpaperPicker() {
        runCatching {
            startActivity(
                android.content.Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(
                        android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        android.content.ComponentName(this, cn.workbuddy.kepanclock.wallpaper.KepanWallpaperService::class.java),
                    ),
            )
        }
    }

    /**
     * 报时开关：开 → 布下一刻闹钟 + 40 s 试钟探针（一声鼓，自证
     * 「闹钟→后台起前台服务」链路）；关 → 撤全部闹钟。
     */
    private fun onSoundToggle(on: Boolean) {
        if (on) {
            ensureNotificationPermission()
            StrikeScheduler.scheduleNext(this)
            StrikeScheduler.scheduleProbe(this, 40_000)
        } else {
            StrikeScheduler.cancelAll(this)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }
}
