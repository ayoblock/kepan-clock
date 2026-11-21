package cn.workbuddy.kepanclock.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import cn.workbuddy.kepanclock.domain.ClockState
import cn.workbuddy.kepanclock.render.Palette
import cn.workbuddy.kepanclock.render.Primitives
import cn.workbuddy.kepanclock.render.RenderGeometry
import cn.workbuddy.kepanclock.render.SceneRenderer
import cn.workbuddy.kepanclock.render.SceneState
import cn.workbuddy.kepanclock.settings.ConfigRepository
import cn.workbuddy.kepanclock.settings.KepanConfig
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 盘面原生自定义 View（§9-3 硬约束：严禁放进 AndroidView）。
 * 与设置页 ComposeView 在 Activity 布局中平级。
 * P3 交互接线：
 *  - 单击 → RenderGeometry.hitTest 分发：seal（天行印，切设置页回调）/
 *    note（历注气泡）/ footer（展全月↔收三日）/ popup（保持）/ null（收气泡）。
 *  - 长按盘面环带 → 试时读数；按住移动实时更新；松手保留；下次单击清除。
 *  - 配置：collectLatest DataStore 流，改即生效（热重载）。
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val repository: ConfigRepository? = null,
) : View(context, attrs) {

    /** 与宿主 Activity 的通信：天行印开合设置页、点击盘面（收设置页）。 */
    interface Listener {
        fun onSealToggle()
        fun onClockTap()
    }

    var listener: Listener? = null

    private var cfg = KepanConfig()

    // 观测点时区固定东八区（与桌面默认一致）；经纬度与昏旦偏移来自配置
    private val tzMin = 480

    private var state: SceneState? = null

    // 交互状态
    private var popupKind: String? = null
    private var trialPointer: Pair<Double, Double>? = null   // 试时：长按/拖动中的指针

    // 字体表（2026-11-21 用户拍板：切换全局生效——chen 位驱动全部正文，
    // zhuan 项把楷体位换成華瑞小篆，整盘文字随之篆化）
    private val fonts: Map<String, Primitives.Fonts> by lazy {
        val fallback = {
            Primitives.Fonts(
                chen = Typeface.create(Typeface.SERIF, Typeface.NORMAL),
                seal = Typeface.create(Typeface.SERIF, Typeface.NORMAL),
            )
        }
        val loaded = runCatching {
            Primitives.Fonts(
                chen = Typeface.createFromAsset(context.assets, "fonts/UKai.ttf"),
                seal = Typeface.createFromAsset(context.assets, "fonts/HuaRuiSeal.ttf"),
                sealAlt = Typeface.createFromAsset(context.assets, "fonts/HuaRuiSeal.ttf"),
            )
        }.getOrElse { fallback() }
        // 明体：工程未内置明体，用系统 serif 承接（验证边界：字形随设备字库）
        val ming = Primitives.Fonts(
            chen = Typeface.create(Typeface.SERIF, Typeface.NORMAL),
            seal = loaded.seal,
            sealAlt = loaded.sealAlt,
        )
        // 篆书：正文位与款识位全换華瑞小篆
        val zhuan = Primitives.Fonts(
            chen = loaded.seal,
            seal = loaded.seal,
            sealAlt = loaded.sealAlt,
        )
        mapOf("kai" to loaded, "ming" to ming, "zhuan" to zhuan)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            recompute()
            invalidate()
            handler.postDelayed(this, 1000L)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val gesture = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val (x, y) = toDp(e)
                handleTap(x, y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // 试时模式：长按激活，读数保留至下次单击
                trialPointer = toDp(e)
                invalidate()
            }
        },
    )

    private fun toDp(e: MotionEvent): Pair<Double, Double> {
        val d = resources.displayMetrics.density.toDouble()
        return (e.x / d) to (e.y / d)
    }

    @Suppress("ReturnCount")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (trialPointer != null) {
                // 试时按住移动：实时更新角位读数
                trialPointer = toDp(event)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 试时松手保留读数；下一次单击在 handleTap 中清除
            }
        }
        // 盘面接管完整触摸流（实证：GestureDetector.onTouchEvent(DOWN) 不承诺 true，
        // 若透传 super（false）则本 View 不锁定触摸目标，UP/MOVE 永不再派发至此）
        gesture.onTouchEvent(event)
        return true
    }

    private fun handleTap(x: Double, y: Double) {
        val st = state
        val w = width / resources.displayMetrics.density.toDouble()
        val h = height / resources.displayMetrics.density.toDouble()
        if (st == null) return
        if (trialPointer != null) {
            // 试时激活中：单击先收试时，不当作页面点击
            trialPointer = null
            invalidate()
            return
        }
        val monthRows = cn.workbuddy.kepanclock.render.PopupRenderer.monthRows(
            st.date?.let { SceneRenderer.almanac(it) },
        )
        val hit = RenderGeometry.hitTest(w, h, x, y, popupKind, monthRows)
        when (hit) {
            "seal" -> listener?.onSealToggle()
            "note" -> {
                popupKind = if (popupKind == null) "three" else null
                listener?.onClockTap()          // 桌面语义：盘面点击收设置页（未开则空操作）
            }
            "footer" -> {
                popupKind = if (popupKind == "month") "three" else "month"
                listener?.onClockTap()
            }
            "popup" -> listener?.onClockTap()   // 气泡内空白：气泡保持，收设置页
            // "settings" / "set:*" 仅在 canvas 设置牌窗展开时命中；本版设置走
            // Compose 牌窗（§9-3 拍板），popupKind 恒不为 "settings"，路径留置
            "settings" -> {}
            else -> {
                popupKind = null
                listener?.onClockTap()
            }
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        repository?.let { repo ->
            scope.launch {
                repo.config.collectLatest { c ->
                    cfg = c
                    recompute()
                    invalidate()
                }
            }
        }
        ticker.run()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        scope.cancel()
        super.onDetachedFromWindow()
    }

    /** 生效昼夜（设置牌窗跟随用）：blend≥0.5 视为夜盘态。 */
    val effectiveNight: Boolean
        get() = (state?.blend ?: 0.0) >= 0.5

    /** 当前渲染态与字体（静态快照导出用）。 */
    val renderSnapshot: Pair<SceneState, Primitives.Fonts>?
        get() {
            val st = state ?: return null
            return st to (fonts[cfg.font] ?: fonts["kai"]!!)
        }

    private fun recompute() {
        val now = LocalDateTime.now()
        val cs = ClockState.compute(
            date = now.toLocalDate(),
            hour = now.hour, minute = now.minute, second = now.second,
            micro = now.nano / 1000,
            lat = cfg.lat, lon = cfg.lon, tzMin = tzMin, dusk = cfg.duskMin,
            theme = cfg.theme,
        )
        state = SceneRenderer.sceneStateOf(cs).copy(
            popup = popupKind,
            watermark = cfg.watermark,
            wmAlpha = cfg.wmAlpha,
            fontFace = cfg.font,
            themeCfg = cfg.theme,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val st = state ?: return
        val pal = Palette.lerp(Palette.day(), Palette.night(), st.blend)
        val dpr = resources.displayMetrics.density
        val f = fonts[cfg.font] ?: fonts["kai"]!!
        // 桌面版几何按设计像素；Android 侧矢量层以密度缩放，静态盘位图按 dpr 物理像素 1:1 贴绘
        canvas.save()
        canvas.scale(dpr, dpr)
        SceneRenderer.drawScene(
            canvas,
            width / dpr.toDouble(),
            height / dpr.toDouble(),
            st, pal, f,
            pointer = trialPointer,
            dpr = dpr.toDouble(),
        )
        canvas.restore()
    }
}
