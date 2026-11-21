package cn.workbuddy.kepanclock.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 绘制基元（移植自桌面 dial.py 的 _ring/_line/_text/_text_w/_round_rect/_card/
 * _beast/_zodiac/_bbox_of/_bez_pts）。文本用 Android Paint（Pango 对应实现）。
 */
object Primitives {

    /** 字体集：辰字（楷）、篆款字体族与篆书主字体（華瑞小篆子集——2026-11-21
     *  用户拍板：霞鹜缺「行/司」、全字庫仅四字子集，统一换華瑞小篆，北师大
     *  说文小篆系、非商用引用；sealAlt 与 seal 同源保留兼容位）。桌面为字体族
     *  字符串，Android 为 Typeface。 */
    data class Fonts(
        val chen: android.graphics.Typeface,
        val seal: android.graphics.Typeface,
        val sealAlt: android.graphics.Typeface = seal,
    )

    private fun paint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------------------------------------------------------------- 基元

    fun ring(c: Canvas, cx: Double, cy: Double, r: Double, color: Int, lw: Double) {
        val p = paint()
        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = lw.toFloat()
        c.drawCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), p)
    }

    fun line(c: Canvas, x1: Double, y1: Double, x2: Double, y2: Double, color: Int, lw: Double) {
        val p = paint()
        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = lw.toFloat()
        c.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), p)
    }

    /**
     * 文本绘制。align: "c" 居中 / "l" 左对齐 / "r" 右对齐；
     * ys 为纵向缩放（翻牌动画），仅支持居中对齐（与桌面全部调用点一致）。
     */
    fun text(
        c: Canvas, x: Double, y: Double, s: String, size: Double,
        tf: android.graphics.Typeface, color: Int,
        align: String = "c", ys: Double = 1.0,
    ) {
        val p = paint()
        p.typeface = tf
        p.textSize = size.toFloat()
        p.color = color
        if (ys != 1.0) {
            val lw = p.measureText(s)
            c.save()
            c.translate(x.toFloat(), y.toFloat())
            c.scale(1.0f, maxOf(0.02, ys).toFloat())
            p.textAlign = Paint.Align.CENTER
            c.drawText(s, 0.0f, (lh(p) / 2.0f - p.descent()), p)
            c.restore()
            return
        }
        val baseline = (y - lh(p) / 2.0 - p.ascent()).toFloat()
        when (align) {
            "l" -> {
                p.textAlign = Paint.Align.LEFT
                c.drawText(s, x.toFloat(), baseline, p)
            }
            "r" -> {
                p.textAlign = Paint.Align.RIGHT
                c.drawText(s, x.toFloat(), baseline, p)
            }
            else -> {
                p.textAlign = Paint.Align.CENTER
                c.drawText(s, x.toFloat(), baseline, p)
            }
        }
    }

    /** 桌面 _text_w：实测文本像素宽（Pango 布局宽 → Paint 实测宽）。 */
    fun textW(s: String, size: Double, tf: android.graphics.Typeface): Double {
        val p = paint()
        p.typeface = tf
        p.textSize = size.toFloat()
        return p.measureText(s).toDouble()
    }

    /** 布局行高（对应 Pango get_pixel_size 的 lh，用于垂直居中换算）。 */
    fun lh(p: Paint): Float {
        val fm = p.fontMetrics
        return fm.descent - fm.ascent
    }

    fun fillRoundRect(c: Canvas, x: Double, y: Double, w: Double, h: Double, r: Double, color: Int) {
        val p = paint()
        p.style = Paint.Style.FILL
        p.color = color
        val rr = maxOf(0.0, min(r, min(w / 2.0, h / 2.0)))
        c.drawRoundRect(RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()), rr.toFloat(), rr.toFloat(), p)
    }

    fun strokeRoundRect(c: Canvas, x: Double, y: Double, w: Double, h: Double, r: Double, color: Int, lw: Double) {
        val p = paint()
        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = lw.toFloat()
        val rr = maxOf(0.0, min(r, min(w / 2.0, h / 2.0)))
        c.drawRoundRect(RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()), rr.toFloat(), rr.toFloat(), p)
    }

    /** 桌面 _card：素纸卡片——淡影 + 纸底 + 外粗内细双边。 */
    fun card(c: Canvas, x: Double, y: Double, w: Double, h: Double, pal: Palette, k: Double) {
        val shadow = paint()
        shadow.style = Paint.Style.FILL
        shadow.color = (0x21 shl 24)  // rgba(0,0,0,0.13)
        val rr = maxOf(0.0, min(6.0 * k, min(w / 2.0, h / 2.0)))
        c.drawRoundRect(
            RectF((x + 1.0 * k).toFloat(), (y + 3.0 * k).toFloat(), (x + 1.0 * k + w).toFloat(), (y + 3.0 * k + h).toFloat()),
            rr.toFloat(), rr.toFloat(), shadow,
        )
        fillRoundRect(c, x, y, w, h, 6.0 * k, Palette.argb(pal["bg"]))
        strokeRoundRect(c, x, y, w, h, 6.0 * k, Palette.argb(pal["ink"]), 1.5 * k)
        strokeRoundRect(c, x + 3.5 * k, y + 3.5 * k, w - 7 * k, h - 7 * k, 4.0 * k, Palette.argb(pal["faint"]), 0.7 * k)
    }

    /** 桌面 _bez_pts：三次贝塞尔采样。 */
    fun bezPts(q: DoubleArray, n: Int = 18): List<DoubleArray> {
        val x0 = q[0]; val y0 = q[1]
        val x1 = q[2]; val y1 = q[3]
        val x2 = q[4]; val y2 = q[5]
        val x3 = q[6]; val y3 = q[7]
        val out = ArrayList<DoubleArray>(n + 1)
        for (i in 0..n) {
            val t = i.toDouble() / n
            val u = 1.0 - t
            out.add(doubleArrayOf(
                u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3,
            ))
        }
        return out
    }

    /** 桌面 _arc_pts：弧线采样点（+x 起、顺时针，y 向下为正）。 */
    fun arcPts(cx: Double, cy: Double, r: Double, a0: Double, a1: Double, n: Int = 12): List<DoubleArray> {
        var aa0 = a0 % 360.0
        var aa1 = a1 % 360.0
        if (aa0 < 0) aa0 += 360.0
        if (aa1 < 0) aa1 += 360.0
        if (aa1 <= aa0) aa1 += 360.0
        val out = ArrayList<DoubleArray>(n + 1)
        for (i in 0..n) {
            val a = Math.toRadians(aa0 + (aa1 - aa0) * i / n)
            out.add(doubleArrayOf(cx + r * Math.cos(a), cy + r * Math.sin(a)))
        }
        return out
    }

    // ---------------------------------------------------------------- 部件包围盒

    private val bboxCache = HashMap<Pair<String, String>, DoubleArray>()

    /** 部件表归一化包围盒 (x0,y0,x1,y1)，粗线按半宽外扩（含贝塞尔采样）。 */
    fun bboxOf(tableKey: String, table: Map<String, List<Part>>, name: String): DoubleArray {
        val key = tableKey to name
        bboxCache[key]?.let { return it }
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (p in table[name]!!) {
            when (p) {
                is Part.Circle -> {
                    xs.add(p.x - p.r); xs.add(p.x + p.r)
                    ys.add(p.y - p.r); ys.add(p.y + p.r)
                }
                is Part.Triangle -> {
                    xs.add(p.x1); xs.add(p.x2); xs.add(p.x3)
                    ys.add(p.y1); ys.add(p.y2); ys.add(p.y3)
                }
                is Part.Quad -> {
                    var i = 0
                    while (i < p.pts.size) { xs.add(p.pts[i]); i += 2 }
                    i = 1
                    while (i < p.pts.size) { ys.add(p.pts[i]); i += 2 }
                }
                is Part.Arc -> {
                    val hw = p.w / 2.0
                    for (q in arcPts(p.x, p.y, p.r, p.a0, p.a1)) {
                        xs.add(q[0]); ys.add(q[1])
                    }
                    xs.add(p.x - p.r - hw); xs.add(p.x + p.r + hw)
                    ys.add(p.y - p.r - hw); ys.add(p.y + p.r + hw)
                }
                is Part.Poly -> {
                    val hw = p.w / 2.0
                    var i = 0
                    while (i < p.pts.size) { xs.add(p.pts[i]); i += 2 }
                    i = 1
                    while (i < p.pts.size) { ys.add(p.pts[i]); i += 2 }
                    xs.add(xs.min() - hw); xs.add(xs.max() + hw)
                    ys.add(ys.min() - hw); ys.add(ys.max() + hw)
                }
                is Part.Bez -> {
                    val hw = p.w / 2.0
                    for (q in bezPts(p.q)) {
                        xs.add(q[0] - hw); xs.add(q[0] + hw)
                        ys.add(q[1] - hw); ys.add(q[1] + hw)
                    }
                }
            }
        }
        val box = doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
        if (bboxCache.size > 40) bboxCache.clear()
        bboxCache[key] = box
        return box
    }

    // ---------------------------------------------------------------- 线描与剪影

    /** 桌面 _beast：四象瓦当线描（路径 DSL M/L/C，单位坐标 [-1,1]）。 */
    fun beast(c: Canvas, cx: Double, cy: Double, mr: Double, name: String, color: Int, lw: Double) {
        val p = paint()
        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = lw.toFloat()
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val path = Path()
        var started = false
        for (seg in DialData.BEAST[name]!!) {
            val r = seg.rest
            when (seg.op) {
                "M" -> { path.moveTo((cx + r[0] * mr).toFloat(), (cy + r[1] * mr).toFloat()); started = true }
                "L" -> { path.lineTo((cx + r[0] * mr).toFloat(), (cy + r[1] * mr).toFloat()); started = true }
                else -> {
                    path.cubicTo(
                        (cx + r[0] * mr).toFloat(), (cy + r[1] * mr).toFloat(),
                        (cx + r[2] * mr).toFloat(), (cy + r[3] * mr).toFloat(),
                        (cx + r[4] * mr).toFloat(), (cy + r[5] * mr).toFloat(),
                    )
                    started = true
                }
            }
        }
        if (started) c.drawPath(path, p)
    }

    /** 桌面 _zodiac：剪影部件拼装。tableKey 用于包围盒缓存键。 */
    fun zodiac(
        c: Canvas, cx: Double, cy: Double, mr: Double, name: String,
        color: Int, table: Map<String, List<Part>>,
    ) {
        val p = paint()
        p.color = color
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        for (part in table[name]!!) {
            when (part) {
                is Part.Circle -> {
                    p.style = Paint.Style.FILL
                    c.drawCircle((cx + part.x * mr).toFloat(), (cy + part.y * mr).toFloat(), (part.r * mr).toFloat(), p)
                }
                is Part.Triangle -> {
                    p.style = Paint.Style.FILL
                    val path = Path()
                    path.moveTo((cx + part.x1 * mr).toFloat(), (cy + part.y1 * mr).toFloat())
                    path.lineTo((cx + part.x2 * mr).toFloat(), (cy + part.y2 * mr).toFloat())
                    path.lineTo((cx + part.x3 * mr).toFloat(), (cy + part.y3 * mr).toFloat())
                    path.close()
                    c.drawPath(path, p)
                }
                is Part.Quad -> {
                    p.style = Paint.Style.FILL
                    val path = Path()
                    path.moveTo((cx + part.pts[0] * mr).toFloat(), (cy + part.pts[1] * mr).toFloat())
                    var i = 2
                    while (i < part.pts.size) {
                        path.lineTo((cx + part.pts[i] * mr).toFloat(), (cy + part.pts[i + 1] * mr).toFloat())
                        i += 2
                    }
                    path.close()
                    c.drawPath(path, p)
                }
                is Part.Arc -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = (part.w * mr).toFloat()
                    // cairo 与 Android drawArc 同为 +x 起顺时针（y 向下为正），单位度
                    c.drawArc(
                        RectF((cx + part.x * mr - part.r * mr).toFloat(), (cy + part.y * mr - part.r * mr).toFloat(),
                            (cx + part.x * mr + part.r * mr).toFloat(), (cy + part.y * mr + part.r * mr).toFloat()),
                        part.a0.toFloat(),
                        (part.a1 - part.a0).toFloat(),
                        false, p,
                    )
                }
                is Part.Poly -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = (part.w * mr).toFloat()
                    val path = Path()
                    path.moveTo((cx + part.pts[0] * mr).toFloat(), (cy + part.pts[1] * mr).toFloat())
                    var i = 2
                    while (i < part.pts.size) {
                        path.lineTo((cx + part.pts[i] * mr).toFloat(), (cy + part.pts[i + 1] * mr).toFloat())
                        i += 2
                    }
                    c.drawPath(path, p)
                }
                is Part.Bez -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = (part.w * mr).toFloat()
                    val pts = bezPts(part.q)
                    val path = Path()
                    path.moveTo((cx + pts[0][0] * mr).toFloat(), (cy + pts[0][1] * mr).toFloat())
                    for (q in pts.drop(1)) {
                        path.lineTo((cx + q[0] * mr).toFloat(), (cy + q[1] * mr).toFloat())
                    }
                    c.drawPath(path, p)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 渐变

    /** 素底晕影（纸色不匀之意，外缘微沉）。 */
    fun radialBackdrop(c: Canvas, cx: Double, cy: Double, R: Double, pal: Palette) {
        val c0 = Palette.argb(pal["bg"])
        val c1 = Palette.argb(Palette.mul(pal["bg"], 0.945))
        val p = paint()
        p.style = Paint.Style.FILL
        p.shader = if (android.os.Build.VERSION.SDK_INT >= 31) {
            // API 31+：双心构造（内径 0.15R），与桌面 Cairo 径向渐变逐值一致；
            // 双心版仅 LongColor 构造（int 版不存在）
            RadialGradient(
                cx.toFloat(), cy.toFloat(), (R * 0.15).toFloat(),
                cx.toFloat(), cy.toFloat(), (R * 1.01).toFloat(),
                longArrayOf(
                    android.graphics.Color.valueOf(c0).pack(),
                    android.graphics.Color.valueOf(c1).pack(),
                ),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
        } else {
            // minSdk 26 兼容：单心 int 色版——首站点位移 r0/1.01R，r0 内钳首色实填 bg，
            // r0→1.01R 由 bg 渐入 bg×0.945（渲染等效）
            RadialGradient(
                cx.toFloat(), cy.toFloat(), (R * 1.01).toFloat(),
                intArrayOf(c0, c1),
                floatArrayOf(((R * 0.15) / (R * 1.01)).toFloat(), 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx.toFloat(), cy.toFloat(), (R * 1.01).toFloat(), p)
        p.shader = null
    }

    /** 供卡片的线性渐变场景使用（预留）。 */
    fun linearGradient(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray): LinearGradient =
        LinearGradient(x0, y0, x1, y1, colors, null, Shader.TileMode.CLAMP)
}
