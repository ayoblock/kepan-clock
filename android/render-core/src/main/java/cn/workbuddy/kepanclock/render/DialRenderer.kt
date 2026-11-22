package cn.workbuddy.kepanclock.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import cn.workbuddy.kepanclock.domain.ChenCore
import cn.workbuddy.kepanclock.domain.Lunar
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.cos

/**
 * 渲染视图状态（对应桌面 dial.py 各绘制函数的 st dict 参数），
 * 由 app 层从 domain ClockState 与交互状态组装。
 */
data class SceneState(
    val tMin: Double,
    val read: String,
    val geng: String,
    val term: String,
    val dateStr: String,
    val hhmm: String,
    val tsRead: String,
    val note: String,
    val animal: String,
    val night: Boolean,
    val blend: Double,
    val date: LocalDate?,
    val parts: Lunar.DayParts?,
    // 交互状态（P3 逐项接线，本轮渲染先留空）
    val popup: String? = null,
    val hover: String? = null,
    val flip: Pair<Double, String>? = null,
    val toast: Pair<String, Double>? = null,   // (文字, 失效时刻-秒)
    val noteHover: Boolean = false,
    val sealHover: Boolean = false,
    val sealNote: String? = null,
    val wallLive: Boolean = false,
    // 设置牌窗所需的配置镜像
    val themeCfg: String = "auto",
    val watermark: String? = "zodiac",
    val wmAlpha: Double = 0.16,
    val fontFace: String = "kai",        // kai / ming / zhuan（设置牌窗镜像）
)

/**
 * 盘面渲染内核（移植自桌面 dial.py 的 _dial_static/_draw_stars/_draw_needle/
 * _dial_side/_panel 与静态缓存）。绘制顺序、线宽、比例与桌面逐值一致。
 */
object DialRenderer {
    private const val PM = Paint.ANTI_ALIAS_FLAG

    // ---------------------------------------------------------------- 静态层

    /** 桌面 _dial_static：辰字环＋初正环＋九十六刻环＋素环＋四象瓦当＋司辰款。 */
    fun dialStatic(c: Canvas, cx: Double, cy: Double, R: Double, pal: Palette, fonts: Primitives.Fonts, night: Boolean) {
        val chenF = fonts.chen
        val seal = fonts.seal
        val ink = Palette.argb(pal["ink"])
        val faint = Palette.argb(pal["faint"])
        val hair = Palette.argb(pal["hair"])
        val gold = pal["gold"]

        // 素底晕影
        Primitives.radialBackdrop(c, cx, cy, R, pal)

        // 外缘弦纹两道
        Primitives.ring(c, cx, cy, R * 0.995, ink, maxOf(1.6, R * 0.006))
        Primitives.ring(c, cx, cy, R * 0.958, ink, maxOf(1.0, R * 0.003))

        // 分隔细线
        for (rf in doubleArrayOf(0.795, 0.710, 0.625)) {
            Primitives.ring(c, cx, cy, R * rf, hair, 1.0)
        }

        // 辰字环：一字三十度，字居辰之中，朱填
        val cinnabar = Palette.argb(pal["cinnabar"])
        for ((k, ch) in ChenCore.CHEN.withIndex()) {
            val (x, y) = RenderGeometry.pt(cx, cy, R * 0.876, RenderGeometry.uAngle(k * 120.0 + 60.0))
            Primitives.text(c, x, y, ch.toString(), R * 0.128, chenF, cinnabar)
        }

        // 初正环：微字二十四
        for (k in 0 until 12) {
            for ((half, word) in arrayOf(30.0 to "初", 90.0 to "正")) {
                val (x, y) = RenderGeometry.pt(cx, cy, R * 0.752, RenderGeometry.uAngle(k * 120.0 + half))
                Primitives.text(c, x, y, word, R * 0.034, chenF, faint)
            }
        }

        // 刻环：九十六刻线；夜盘刻线描金晕
        for (i in 0 until 96) {
            val th = RenderGeometry.uAngle(i * 15.0)
            val (r0, lw, col) = when {
                i % 8 == 0 -> Triple(0.610, maxOf(1.4, R * 0.005), ink)
                i % 4 == 0 -> Triple(0.640, maxOf(1.1, R * 0.0035), ink)
                else -> Triple(0.652, 1.0, faint)
            }
            val (x1, y1) = RenderGeometry.pt(cx, cy, R * r0, th)
            val (x2, y2) = RenderGeometry.pt(cx, cy, R * 0.710, th)
            if (night) {
                val p = Paint(PM)
                p.style = Paint.Style.STROKE
                p.color = Palette.argb(gold, 0.16)
                p.strokeWidth = (lw * 2.6).toFloat()
                c.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), p)
            }
            Primitives.line(c, x1, y1, x2, y2, col, lw)
        }

        // 四正四象：瓦当线描兽形；圆牌过小则回退篆字
        for ((deg, name, key) in RenderGeometry.SIMEN) {
            val (mx, my) = RenderGeometry.pt(cx, cy, R * 0.50, deg)
            val mr = R * 0.088
            Primitives.ring(c, mx, my, mr, hair, maxOf(1.0, R * 0.004))
            Primitives.ring(c, mx, my, mr * 0.88, faint, 0.8)
            if (mr >= RenderGeometry.MEDALLION_MIN) {
                Primitives.beast(c, mx, my, mr * 0.62, key, Palette.argb(gold), maxOf(1.0, mr * 0.09))
            } else {
                Primitives.text(c, mx, my, name.substring(0, 1), mr * 1.1, seal, Palette.argb(gold))
            }
        }

        // 盘心：晷针轴帽与「司辰」篆款（TWSeal 全字庫：霞鹜缺「司」字）
        Primitives.text(c, cx, cy - R * 0.150, "司辰", R * 0.050, fonts.sealAlt, cinnabar)
    }

    /** 桌面 _build_static：静态层离屏位图。 */
    fun buildStatic(R: Double, pal: Palette, fonts: Primitives.Fonts, night: Boolean): Bitmap {
        val S = Math.round(2 * R + 8).toInt()
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        dialStatic(c, S / 2.0, S / 2.0, R, pal, fonts, night)
        return bmp
    }

    private data class StaticKey(val R: Double, val night: Boolean, val palSig: Long, val fontsSig: Int)

    private fun fontsSig(fonts: Primitives.Fonts): Int =
        ((fonts.chen.hashCode() * 31 + fonts.seal.hashCode()) * 31 + fonts.sealAlt.hashCode())

    private fun palSig(pal: Palette): Long {
        var sig = 0L
        for (key in arrayOf("bg", "ink", "faint", "hair", "cinnabar", "gold")) {
            for (v in pal[key]) {
                sig = sig * 31 + Math.round(v * 10000.0)
            }
        }
        return sig
    }

    private val staticCache = LinkedHashMap<StaticKey, Bitmap>()

    /** 桌面 _get_static：静态层缓存（上限 6，超出清空——与桌面策略一致）。 */
    fun getStatic(R: Double, pal: Palette, fonts: Primitives.Fonts, night: Boolean, cacheable: Boolean): Bitmap {
        // 键含字体签名：辰字楷/明切换（P3 配置项）必须使旧位图缓存失效
        val key = StaticKey(Math.round(R * 10.0) / 10.0, night, palSig(pal), fontsSig(fonts))
        if (cacheable) {
            staticCache[key]?.let { return it }
        }
        val bmp = buildStatic(R, pal, fonts, night)
        if (cacheable) {
            if (staticCache.size > 6) {
                for (old in staticCache.values) old.recycle()
                staticCache.clear()
            }
            staticCache[key] = bmp
        }
        return bmp
    }

    /** 配置变更时清缓存。 */
    fun clearStaticCache() {
        for (old in staticCache.values) old.recycle()
        staticCache.clear()
    }

    // ---------------------------------------------------------------- 动态层

    /** 桌面 _draw_stars：星空随恒星日旋转（固定数值表，见 DialData）。 */
    fun drawStars(c: Canvas, cx: Double, cy: Double, R: Double, tMin: Double, pal: Palette) {
        val rot = (tMin / RenderGeometry.SIDEREAL_MIN) * 360.0 % 360.0
        val p = Paint(PM)
        p.style = Paint.Style.FILL
        p.color = Palette.argb(pal["star"])
        for (star in DialData.GALAXY) {
            val (x, y) = RenderGeometry.pt(cx, cy, R * star.rf, star.phi + rot)
            p.alpha = (star.a.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255)
            c.drawCircle(x.toFloat(), y.toFloat(), star.sz.toFloat(), p)
        }
        for (star in DialData.STARS) {
            val (x, y) = RenderGeometry.pt(cx, cy, R * star.rf, star.phi + rot)
            p.alpha = (star.a.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255)
            c.drawCircle(x.toFloat(), y.toFloat(), star.sz.toFloat(), p)
        }
    }

    /** 桌面 _needle_path。 */
    private fun needlePath(cx: Double, cy: Double, R: Double, ang: Double): Path {
        val (tx, ty) = RenderGeometry.pt(cx, cy, R * 0.665, ang)
        val (bx, by) = RenderGeometry.pt(cx, cy, -R * 0.115, ang)
        val (px, py) = RenderGeometry.pt(cx, cy, R * 0.030, ang + 90)
        val (qx, qy) = RenderGeometry.pt(cx, cy, R * 0.030, ang - 90)
        val path = Path()
        path.moveTo(tx.toFloat(), ty.toFloat())
        path.lineTo(qx.toFloat(), qy.toFloat())
        path.lineTo(bx.toFloat(), by.toFloat())
        path.lineTo(px.toFloat(), py.toFloat())
        path.close()
        return path
    }

    /** 桌面 _draw_needle：晷针（昼影偏移 / 夜晕描金）。 */
    fun drawNeedle(c: Canvas, cx: Double, cy: Double, R: Double, tMin: Double, pal: Palette, night: Boolean) {
        val ang = ChenCore.needleAngle(tMin)
        val off = R * 0.012
        val halo = Paint(PM)
        halo.style = if (night) Paint.Style.STROKE else Paint.Style.FILL
        c.save()
        c.translate(off.toFloat(), (off * 1.3).toFloat())
        if (night) {
            halo.color = Palette.argb(pal["gold"], 0.22)
            halo.strokeWidth = (R * 0.020).toFloat()
            halo.strokeJoin = Paint.Join.ROUND
            c.drawPath(needlePath(cx, cy, R, ang), halo)
        } else {
            halo.color = (0x33 shl 24)   // rgba(0,0,0,0.20)
            c.drawPath(needlePath(cx, cy, R, ang), halo)
        }
        c.restore()
        val p = Paint(PM)
        p.style = Paint.Style.FILL
        p.color = Palette.argb(pal["needle"])
        c.drawPath(needlePath(cx, cy, R, ang), p)
        p.color = Palette.argb(pal["cinnabar"])
        c.drawCircle(cx.toFloat(), cy.toFloat(), (R * 0.028).toFloat(), p)
        p.color = Palette.argb(pal["bg"])
        c.drawCircle(cx.toFloat(), cy.toFloat(), (R * 0.009).toFloat(), p)
    }

    // ---------------------------------------------------------------- 副盘

    /** 桌面 _dial_side：八刻副盘，刻针一辰一周，盘心生肖篆字。 */
    fun dialSide(c: Canvas, cx: Double, cy: Double, r: Double, st: SceneState, pal: Palette, fonts: Primitives.Fonts) {
        val chenF = fonts.chen
        val seal = fonts.seal
        val ink = Palette.argb(pal["ink"])
        val faint = Palette.argb(pal["faint"])
        val hair = Palette.argb(pal["hair"])
        val cinnabar = Palette.argb(pal["cinnabar"])
        Primitives.ring(c, cx, cy, r, ink, 1.6)
        Primitives.ring(c, cx, cy, r * 0.60, hair, 0.9)
        for (i in 0 until 8) {
            val th = i * 45.0
            val (x1, y1) = RenderGeometry.pt(cx, cy, r * 0.60, th)
            val (x2, y2) = RenderGeometry.pt(cx, cy, r * 0.97, th)
            Primitives.line(c, x1, y1, x2, y2, hair, 0.9)
            val (x3, y3) = RenderGeometry.pt(cx, cy, r * 0.79, th)
            Primitives.text(c, x3, y3, RenderGeometry.NUM_CN[i].toString(), r * 0.16, chenF, ink)
        }
        Primitives.text(c, cx, cy + r * 0.36, st.animal, r * 0.22, seal, cinnabar)
        val th = ChenCore.sideAngle(st.tMin.toInt())
        val (tx, ty) = RenderGeometry.pt(cx, cy, r * 0.88, th)
        val (bx, by) = RenderGeometry.pt(cx, cy, -r * 0.16, th)
        val p = Paint(PM)
        p.style = Paint.Style.STROKE
        p.color = Palette.argb(pal["needle"])
        p.strokeWidth = maxOf(2.0, r * 0.035).toFloat()
        c.drawLine(bx.toFloat(), by.toFloat(), tx.toFloat(), ty.toFloat(), p)
        p.style = Paint.Style.FILL
        p.color = cinnabar
        c.drawCircle(cx.toFloat(), cy.toFloat(), (r * 0.045).toFloat(), p)
    }

    // ---------------------------------------------------------------- 面板

    /** 桌面 _watermark：面板背景水印（zodiac/feitian/beast 解除定尺，chen 大篆）。 */
    private fun watermark(
        c: Canvas, cx: Double, bottom: Double, radius: Double, st: SceneState,
        pal: Palette, fonts: Primitives.Fonts, kind: String, alpha: Double,
        top: Double? = null, halfW: Double? = null,
    ) {
        // 对齐桌面 int((t_min + 60.0) // 120.0) % 12：floor 整除 + floor 取模（负时区不越界）
        val idx = Math.floorMod(Math.floor((st.tMin + 60.0) / 120.0).toInt(), 12)
        val t = top ?: (bottom - 2.0 * radius)
        val availW = 2.0 * (halfW ?: radius)
        val availH = bottom - t
        val mid = (t + bottom) / 2.0
        val layer = Bitmap.createBitmap(
            (availW.coerceAtLeast(2.0)).toInt() + 4,
            (availH.coerceAtLeast(2.0)).toInt() + 4, Bitmap.Config.ARGB_8888,
        )
        val lc = Canvas(layer)
        val lcxC = layer.width / 2.0
        val lcyC = layer.height / 2.0
        val faintCol = Palette.argb(pal["faint"])
        when (kind) {
            "zodiac", "feitian" -> {
                val table = if (kind == "zodiac") DialData.ZODIAC else DialData.FT
                val order = if (kind == "zodiac") "鼠牛虎兔龙蛇马羊猴鸡狗猪" else "子丑寅卯辰巳午未申酉戌亥"
                // 对齐桌面 _ZODIAC_KEY/_FT_KEY：汉字 → 部件表键（表键为拼音）
                val keyMap = if (kind == "zodiac") DialData.ZODIAC_KEY else DialData.FT_KEY
                val name = keyMap[order[idx].toString()]!!
                val box = Primitives.bboxOf(kind, table, name)
                val bw = box[2] - box[0]
                val bh = box[3] - box[1]
                val scale = minOf(
                    if (bw > 0.01) availW / bw else 9e9,
                    if (bh > 0.01) availH / bh else 9e9,
                )
                val ox = lcxC - (box[0] + box[2]) / 2.0 * scale
                val oy = lcyC - (box[1] + box[3]) / 2.0 * scale
                Primitives.zodiac(lc, ox, oy, scale, name, faintCol, table)
            }
            "beast" -> {
                val r2 = minOf(availW, availH) / 2.0
                val key = arrayOf("xuan", "long", "que", "hu")[
                    Math.floorMod(Math.floor(st.tMin / 180.0).toInt(), 4)]
                Primitives.ring(lc, lcxC, lcyC, r2, faintCol, maxOf(1.0, r2 * 0.02))
                Primitives.beast(lc, lcxC, lcyC, r2 * 0.68, key, faintCol, maxOf(1.5, r2 * 0.04))
            }
            else -> {   // chen：当前辰字大篆
                Primitives.text(lc, lcxC, layer.height - radius, ChenCore.CHEN[idx].toString(), radius * 1.45, fonts.seal, faintCol)
            }
        }
        val pa = Paint(PM)
        pa.alpha = (maxOf(0.02, minOf(0.45, alpha)) * 255.0).toInt().coerceIn(0, 255)
        // 桌面 push_group 原点即窗口原点；此处层左上对齐区域左上
        val layerLeft = (cx - layer.width / 2.0).toFloat()
        val layerTop = (mid - layer.height / 2.0).toFloat()
        c.drawBitmap(layer, layerLeft, layerTop, pa)
        layer.recycle()
    }

    /** 桌面 _panel：右列面板。 */
    fun panel(
        c: Canvas, x: Double, y: Double, w: Double, st: SceneState,
        pal: Palette, fonts: Primitives.Fonts, k: Double = 1.0,
        sealPos: Pair<Double, Double>? = null,
        sidePos: Pair<Double, Double>? = null,
        sideROver: Double? = null,
    ) {
        val chenF = fonts.chen
        val seal = fonts.seal
        val ink = Palette.argb(pal["ink"])
        val faint = Palette.argb(pal["faint"])
        val hairCol = pal["hair"]
        val cinnabar = Palette.argb(pal["cinnabar"])
        val gold = Palette.argb(pal["gold"])
        val cx = x + w / 2.0
        val sideR = sideROver ?: minOf(76.0 * k, w * 0.30)

        val wm = st.watermark
        if (wm != null && wm != "chen") {
            watermark(
                c, cx, y + (RenderGeometry.SIDE_Y - sideR - 4.0) * k, sideR, st, pal,
                fonts, wm, st.wmAlpha, top = y + 160.0 * k, halfW = (w / 2.0 - 6.0 * k),
            )
        } else if (wm != null) {
            watermark(c, cx, y + (RenderGeometry.NOTE_Y + 9.0) * k, sideR, st, pal, fonts, wm, st.wmAlpha)
        }

        // 顶行：司辰小印在前，大读数随后；组宽实测居中，过窄列则退回独排
        val ry = y + 40 * k
        val rw = Primitives.textW(st.read, 42 * k, chenF)
        val ssz = 30.0 * k
        val gap = 12.0 * k
        if (ssz + gap + rw <= w - 16.0 * k) {
            val gx = cx - (ssz + gap + rw) / 2.0
            Primitives.fillRoundRect(c, gx, ry - ssz / 2.0, ssz, ssz, 4.0 * k, cinnabar)
            Primitives.text(c, gx + ssz / 2.0, ry - 6.0 * k, "司", 11.0 * k, fonts.sealAlt, Palette.argb(pal["bg"]))
            Primitives.text(c, gx + ssz / 2.0, ry + 6.6 * k, "辰", 11.0 * k, fonts.sealAlt, Palette.argb(pal["bg"]))
            Primitives.text(c, gx + ssz + gap + rw / 2.0, ry, st.read, 42 * k, chenF, ink)
        } else {
            Primitives.text(c, cx, ry, st.read, 42 * k, chenF, ink)
        }

        // 更牌窗
        val bx = x + 10 * k
        val by = y + 92 * k
        val bw = w - 20 * k
        val bh = 62 * k
        Primitives.fillRoundRect(c, bx, by, bw, bh, 0.0, Palette.argb(pal["bg"]))
        Primitives.strokeRoundRect(c, bx, by, bw, bh, 0.0, ink, 1.6 * k)
        Primitives.strokeRoundRect(c, bx + 4 * k, by + 4 * k, bw - 8 * k, bh - 8 * k, 0.0, faint, 0.7 * k)

        val hover = st.hover
        if (hover != null) {
            Primitives.text(c, cx, by + bh / 2.0, "试时·$hover", 22 * k, chenF, gold)
        } else {
            val flip = st.flip
            if (flip == null) {
                Primitives.text(c, cx, by + bh / 2.0, st.geng, 28 * k, chenF, ink)
            } else {
                val (t01, old) = flip
                val ys = abs(cos(Math.PI * t01))
                val word = if (t01 < 0.5) old else st.geng
                Primitives.text(c, cx, by + bh / 2.0, word, 28 * k, chenF, ink, ys = ys)
            }
        }

        // 节气（篆）、公历对时、真太阳时
        Primitives.text(c, cx, y + 196 * k, st.term, 24 * k, seal, gold)
        Primitives.text(c, cx, y + 228 * k, st.dateStr + "  " + st.hhmm, 13 * k, chenF, faint)
        Primitives.text(c, cx, y + 252 * k, "真太阳时 " + st.tsRead, 13 * k, chenF, gold)

        // 历注：常驻一行，点按弹出气泡（下划线示意可点）
        val note = st.note
        if (note.isNotEmpty()) {
            val lit = st.noteHover || st.popup != null
            Primitives.text(c, cx, y + RenderGeometry.NOTE_Y * k, note, 11 * k, chenF, if (lit) ink else faint)
            val uy = y + (RenderGeometry.NOTE_Y + 9.0) * k
            val x0 = cx - w * 0.33
            val x1 = cx + w * 0.33
            if (lit) {
                Primitives.line(c, x0, uy, x1, uy, cinnabar, 1.0 * k)
            } else {
                val p = Paint(PM)
                p.style = Paint.Style.STROKE
                p.color = Palette.argb(hairCol, 0.95)
                p.strokeWidth = (1.0 * k).toFloat()
                var xx = x0
                while (xx < x1) {
                    c.drawLine(xx.toFloat(), uy.toFloat(), Math.min(xx + 2.0 * k, x1).toFloat(), uy.toFloat(), p)
                    xx += 4.5 * k
                }
            }
        }

        // 天行字印（设置入口）：常时朱线空印，悬停/展开时实印
        if (sealPos != null) {
            val lit = st.sealHover || st.popup == "settings"
            val scx = sealPos.first
            val scy = sealPos.second
            val sw = 50.0 * k
            val sy0 = scy - sw / 2.0
            if (lit) {
                Primitives.fillRoundRect(c, scx - sw / 2.0, sy0, sw, sw, 6.0 * k, cinnabar)
                val tcol = Palette.argb(pal["bg"])
                Primitives.text(c, scx, sy0 + sw * 0.30, "天", 17.0 * k, seal, tcol)
                Primitives.text(c, scx, sy0 + sw * 0.70, "行", 17.0 * k, seal, tcol)
            } else {
                Primitives.strokeRoundRect(c, scx - sw / 2.0, sy0, sw, sw, 6.0 * k, cinnabar, 2.0 * k)
                Primitives.text(c, scx, sy0 + sw * 0.30, "天", 17.0 * k, seal, cinnabar)
                Primitives.text(c, scx, sy0 + sw * 0.70, "行", 17.0 * k, seal, cinnabar)
            }
        }

        // 名例一行
        Primitives.text(c, cx, y + RenderGeometry.NAME_Y * k, "观天之道，执天之行，尽矣！", 11 * k, chenF, faint)

        // 八刻副盘：sidePos 非空时画在面板外（横屏左带），否则面板内嵌（桌面式）
        if (sidePos != null) {
            dialSide(c, sidePos.first, sidePos.second, sideR, st, pal, fonts)
        } else {
            dialSide(c, cx, y + RenderGeometry.SIDE_Y * k, sideR, st, pal, fonts)
        }
    }

    /**
     * 竖屏五段式面板（几何见 RenderGeometry.layoutPortrait）：
     * 主盘之上由 drawScene 绘制；此处画 读数+更牌并列行、水印带、
     * 左块（节气/对时/真太阳时/历注/天行印）、右块八刻副盘、底部名例。
     */
    fun panelPortrait(
        c: Canvas, w: Double, h: Double, lay: RenderGeometry.Layout,
        st: SceneState, pal: Palette, fonts: Primitives.Fonts,
    ) {
        val k = lay.k
        val chenF = fonts.chen
        val seal = fonts.seal
        val ink = Palette.argb(pal["ink"])
        val faint = Palette.argb(pal["faint"])
        val cinnabar = Palette.argb(pal["cinnabar"])
        val gold = Palette.argb(pal["gold"])

        // 背景水印带：铺在下区（立冬块与小表盘之下），对齐桌面面板水印语义
        val wm = st.watermark
        if (wm != null) {
            val bandH = lay.wmBottom - lay.wmTop
            watermark(
                c, w / 2.0, lay.wmBottom, minOf(lay.sideR, bandH / 2.0), st, pal,
                fonts, wm, st.wmAlpha, top = lay.wmTop, halfW = w / 2.0 - 12.0,
            )
        }

        // 第二行：司辰印+读数 整组按实测宽居中
        val ry = lay.row2Y
        val rw = Primitives.textW(st.read, 42 * k, chenF)
        val ssz = 30.0 * k
        val gap = 12.0 * k
        val gx = w / 2.0 - (ssz + gap + rw) / 2.0
        Primitives.fillRoundRect(c, gx, ry - ssz / 2.0, ssz, ssz, 4.0 * k, cinnabar)
        Primitives.text(c, gx + ssz / 2.0, ry - 6.0 * k, "司", 11.0 * k, fonts.sealAlt, Palette.argb(pal["bg"]))
        Primitives.text(c, gx + ssz / 2.0, ry + 6.6 * k, "辰", 11.0 * k, fonts.sealAlt, Palette.argb(pal["bg"]))
        Primitives.text(c, gx + ssz + gap + rw / 2.0, ry, st.read, 42 * k, chenF, ink)

        // 第三行：更牌窗（翻牌/试时）居中
        val bw = lay.gengW
        val bh = lay.gengH
        val gbx = w / 2.0 - bw / 2.0
        val gby = lay.gengY - bh / 2.0
        Primitives.fillRoundRect(c, gbx, gby, bw, bh, 0.0, Palette.argb(pal["bg"]))
        Primitives.strokeRoundRect(c, gbx, gby, bw, bh, 0.0, ink, 1.6 * k)
        Primitives.strokeRoundRect(c, gbx + 4 * k, gby + 4 * k, bw - 8 * k, bh - 8 * k, 0.0, faint, 0.7 * k)
        val gcy = gby + bh / 2.0
        val gcx = gbx + bw / 2.0
        val hover = st.hover
        if (hover != null) {
            Primitives.text(c, gcx, gcy, "试时·$hover", 22 * k, chenF, gold)
        } else {
            val flip = st.flip
            if (flip == null) {
                Primitives.text(c, gcx, gcy, st.geng, 28 * k, chenF, ink)
            } else {
                val (t01, old) = flip
                val ys = abs(cos(Math.PI * t01))
                val word = if (t01 < 0.5) old else st.geng
                Primitives.text(c, gcx, gcy, word, 28 * k, chenF, ink, ys = ys)
            }
        }

        // 左块：节气（篆金）、公历对时、真太阳时、天行字印（历注已移底部居中）
        val lx = lay.leftCx
        val ty = lay.infoTop + 8.0 * k
        Primitives.text(c, lx, ty, st.term, 24 * k, seal, gold)
        Primitives.text(c, lx, ty + 34.0 * k, st.dateStr + "  " + st.hhmm, 13 * k, chenF, faint)
        Primitives.text(c, lx, ty + 58.0 * k, "真太阳时 " + st.tsRead, 13 * k, chenF, gold)
        val lit2 = st.sealHover || st.popup == "settings"
        val scx = lay.sealX
        val scy = lay.sealY
        val sw = 50.0 * k
        val sy0 = scy - sw / 2.0
        if (lit2) {
            Primitives.fillRoundRect(c, scx - sw / 2.0, sy0, sw, sw, 6.0 * k, cinnabar)
            val tcol = Palette.argb(pal["bg"])
            Primitives.text(c, scx, sy0 + sw * 0.30, "天", 17.0 * k, seal, tcol)
            Primitives.text(c, scx, sy0 + sw * 0.70, "行", 17.0 * k, seal, tcol)
        } else {
            Primitives.strokeRoundRect(c, scx - sw / 2.0, sy0, sw, sw, 6.0 * k, cinnabar, 2.0 * k)
            Primitives.text(c, scx, sy0 + sw * 0.30, "天", 17.0 * k, seal, cinnabar)
            Primitives.text(c, scx, sy0 + sw * 0.70, "行", 17.0 * k, seal, cinnabar)
        }

        // 右块：八刻副盘
        dialSide(c, lay.sideCx, lay.sideCy, lay.sideR, st, pal, fonts)

        // 底部：历注（可点）居中于名例上方
        val note = st.note
        if (note.isNotEmpty()) {
            val lit = st.noteHover || st.popup != null
            Primitives.text(c, w / 2.0, lay.noteY, note, 11 * k, chenF, if (lit) ink else faint)
            val uy = lay.noteY + 9.0 * k
            val x0 = w / 2.0 - w * 0.30
            val x1 = w / 2.0 + w * 0.30
            if (lit) {
                Primitives.line(c, x0, uy, x1, uy, cinnabar, 1.0 * k)
            } else {
                val p = Paint(PM)
                p.style = Paint.Style.STROKE
                p.color = Palette.argb(pal["hair"], 0.95)
                p.strokeWidth = (1.0 * k).toFloat()
                var xx = x0
                while (xx < x1) {
                    c.drawLine(xx.toFloat(), uy.toFloat(), Math.min(xx + 2.0 * k, x1).toFloat(), uy.toFloat(), p)
                    xx += 4.5 * k
                }
            }
        }

        // 最底名例一行
        Primitives.text(c, w / 2.0, lay.nameY, "观天之道，执天之行，尽矣！", 11 * k, chenF, faint)
    }

    /** 桌面 _vtext：竖排题款，一字一行，行距 1.28 倍字高。 */
    fun vtext(
        c: Canvas, x: Double, y: Double, s: String, size: Double,
        tf: android.graphics.Typeface, color: Int, align: String = "l",
    ) {
        val step = size * 1.28
        for ((i, ch) in s.withIndex()) {
            Primitives.text(c, x, y + (i + 0.5) * step, ch.toString(), size, tf, color, align = align)
        }
    }
}
