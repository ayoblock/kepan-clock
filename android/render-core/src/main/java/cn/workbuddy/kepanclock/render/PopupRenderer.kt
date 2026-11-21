package cn.workbuddy.kepanclock.render

import android.graphics.Canvas
import android.graphics.Paint
import cn.workbuddy.kepanclock.domain.Lunar
import kotlin.math.max
import kotlin.math.min

/**
 * 历注气泡与设置牌窗（移植自桌面 dial.py 的 _leader/_popup_three/_popup_month/
 * _popup_settings/_chip）。
 */
object PopupRenderer {
    private const val PM = Paint.ANTI_ALIAS_FLAG

    /** 桌面 _leader：锚点到卡片最近边的引线。 */
    fun leader(c: Canvas, ax: Double, ay: Double, g: RenderGeometry.PopupBox, pal: Palette) {
        val x = g.x
        val y = g.y
        val bw = g.bw
        val bh = g.bh
        val (ex, ey) = when (g.side) {
            "up" -> ax to (y + bh)
            "right" -> x to ay
            "left" -> (x + bw) to ay
            else -> ax to y
        }
        Primitives.line(c, ax, ay, ex, ey, Palette.argb(pal["ink"]), 1.0 * g.k)
        val p = Paint(PM)
        p.style = Paint.Style.FILL
        p.color = Palette.argb(pal["ink"])
        c.drawCircle(ax.toFloat(), ay.toFloat(), (1.7 * g.k).toFloat(), p)
    }

    /** 桌面 _popup_three：一级——昨／今／明三日条。 */
    fun popupThree(c: Canvas, g: RenderGeometry.PopupBox, alm: Lunar.Almanac, pal: Palette, fonts: Primitives.Fonts) {
        val x = g.x
        val y = g.y
        val bw = g.bw
        val bh = g.bh
        val k = g.k
        val chenF = fonts.chen
        val seal = fonts.seal
        val pad = 11.0 * k
        Primitives.text(c, x + pad, y + 15.0 * k, "历 注", 10.5 * k, seal, Palette.argb(pal["gold"]), align = "l")
        Primitives.text(c, x + bw - pad, y + 15.0 * k, "按此展全月", 8.0 * k, chenF, Palette.argb(pal["faint"]), align = "r")
        Primitives.line(c, x + pad, y + 30.0 * k, x + bw - pad, y + 30.0 * k, Palette.argb(pal["hair"]), 0.8 * k)

        val cw = (bw - 2.0 * pad) / 3.0
        for ((i, e) in alm.three.withIndex()) {
            val left = x + pad + cw * i
            val ccx = left + cw / 2.0
            val today = e.date == alm.today
            if (i != 0) {
                Primitives.line(c, left, y + 40.0 * k, left, y + 126.0 * k, Palette.argb(pal["hair"]), 0.6 * k)
            }
            if (today) {
                Primitives.fillRoundRect(c, left + 3.0 * k, y + 35.0 * k, cw - 6.0 * k, 96.0 * k, 3.0 * k, Palette.argb(pal["cinnabar"], 0.07))
                Primitives.strokeRoundRect(c, left + 3.0 * k, y + 35.0 * k, cw - 6.0 * k, 96.0 * k, 3.0 * k, Palette.argb(pal["cinnabar"], 0.55), 0.9 * k)
            }
            Primitives.text(c, ccx, y + 50.0 * k, e.wuyao + "曜", 8.0 * k, chenF, Palette.argb(pal["faint"]))
            Primitives.text(c, ccx, y + 74.0 * k, e.lunarLabel, 15.0 * k, chenF, if (today) Palette.argb(pal["cinnabar"]) else Palette.argb(pal["ink"]))
            Primitives.text(c, ccx, y + 96.0 * k, e.gz + "日", 10.0 * k, chenF, Palette.argb(pal["faint"]))
            Primitives.text(c, ccx, y + 116.0 * k, e.hou, 8.5 * k, chenF, Palette.argb(pal["gold"]))
        }

        Primitives.line(c, x + pad, y + bh - 28.0 * k, x + bw - pad, y + bh - 28.0 * k, Palette.argb(pal["hair"]), 0.8 * k)
        Primitives.text(c, x + bw / 2.0, y + bh - 14.0 * k, "展全月 ▾", 8.5 * k, chenF, Palette.argb(pal["faint"]))
    }

    /** 桌面 _popup_month：二级——整月历（公历月为格，朔日与节气朱标，今日朱框）。 */
    fun popupMonth(c: Canvas, g: RenderGeometry.PopupBox, alm: Lunar.Almanac, pal: Palette, fonts: Primitives.Fonts) {
        val x = g.x
        val y = g.y
        val bw = g.bw
        val bh = g.bh
        val k = g.k
        val chenF = fonts.chen
        val seal = fonts.seal
        val pad = 11.0 * k
        Primitives.text(c, x + pad, y + 15.0 * k, alm.solarTitle, 10.5 * k, chenF, Palette.argb(pal["ink"]), align = "l")
        Primitives.text(c, x + bw - pad, y + 15.0 * k, alm.lunarTitle, 9.0 * k, seal, Palette.argb(pal["gold"]), align = "r")
        Primitives.line(c, x + pad, y + 30.0 * k, x + bw - pad, y + 30.0 * k, Palette.argb(pal["hair"]), 0.8 * k)

        val cw = (bw - 2.0 * pad) / 7.0
        val cells = alm.month
        val rows = max(5, cells.size / 7)
        val gtop = y + 56.0 * k
        val gbot = gtop + rows * 30.0 * k
        for ((i, wd) in alm.week.withIndex()) {
            Primitives.text(c, x + pad + cw * (i + 0.5), y + 43.0 * k, wd, 8.5 * k, chenF, Palette.argb(pal["faint"]))
        }
        val p = Paint(PM)
        p.style = Paint.Style.STROKE
        p.color = Palette.argb(pal["hair"], 0.55)
        p.strokeWidth = (0.6 * k).toFloat()
        val path = android.graphics.Path()
        for (i in 0..7) {
            val vx = x + pad + cw * i
            path.moveTo(vx.toFloat(), gtop.toFloat())
            path.lineTo(vx.toFloat(), gbot.toFloat())
        }
        for (r in 1 until rows) {
            val hy = gtop + r * 30.0 * k
            path.moveTo((x + pad).toFloat(), hy.toFloat())
            path.lineTo((x + bw - pad).toFloat(), hy.toFloat())
        }
        c.drawPath(path, p)

        for ((idx, cell) in cells.withIndex()) {
            val d = cell.date ?: continue
            val r = idx / 7
            val col = idx % 7
            val ccx = x + pad + cw * (col + 0.5)
            val top = gtop + r * 30.0 * k
            if (d == alm.today) {
                Primitives.fillRoundRect(c, x + pad + cw * col + 1.5 * k, top + 0.5 * k, cw - 3.0 * k, 29.0 * k, 3.0 * k, Palette.argb(pal["cinnabar"], 0.11))
            }
            Primitives.text(c, ccx, top + 11.0 * k, d.dayOfMonth.toString(), 9.5 * k, chenF, Palette.argb(pal["ink"]))
            val label = cell.term ?: cell.label
            val color = if (cell.term != null || cell.label.endsWith("月")) Palette.argb(pal["cinnabar"]) else Palette.argb(pal["faint"])
            Primitives.text(c, ccx, top + 23.0 * k, label, 7.0 * k, chenF, color)
        }

        Primitives.line(c, x + pad, y + bh - 36.0 * k, x + bw - pad, y + bh - 36.0 * k, Palette.argb(pal["hair"]), 0.8 * k)
        Primitives.text(c, x + bw / 2.0, y + bh - 18.0 * k, "收 ▲", 8.5 * k, chenF, Palette.argb(pal["faint"]))
    }

    /** 桌面 _chip：设置项圆角 chip。 */
    private fun chip(c: Canvas, r: DoubleArray, label: String, sel: Boolean, pal: Palette, fonts: Primitives.Fonts, k: Double) {
        val rx = r[0]
        val ry = r[1]
        val rw = r[2]
        val rh = r[3]
        if (sel) {
            Primitives.strokeRoundRect(c, rx, ry, rw, rh, 4.0 * k, Palette.argb(pal["cinnabar"]), 1.3 * k)
            Primitives.text(c, rx + rw / 2.0, ry + rh / 2.0, label, 10.0 * k, fonts.chen, Palette.argb(pal["cinnabar"]))
        } else {
            Primitives.strokeRoundRect(c, rx, ry, rw, rh, 4.0 * k, Palette.argb(pal["hair"]), 0.7 * k)
            Primitives.text(c, rx + rw / 2.0, ry + rh / 2.0, label, 10.0 * k, fonts.chen, Palette.argb(pal["faint"]))
        }
    }

    /** 桌面 _popup_settings。 */
    fun popupSettings(c: Canvas, g: RenderGeometry.SettingsBox, st: SceneState, pal: Palette, fonts: Primitives.Fonts) {
        val k = g.k
        val x = g.x
        val y = g.y
        val bw = g.bw
        val bh = g.bh
        val chenF = fonts.chen
        val seal = fonts.seal
        val pad = g.pad
        Primitives.text(c, x + pad, y + 18.0 * k, "设 置", 10.5 * k, seal, Palette.argb(pal["gold"]), align = "l")
        Primitives.text(c, x + bw - pad, y + 18.0 * k, "改即存·半秒生效", 8.0 * k, chenF, Palette.argb(pal["faint"]), align = "r")
        Primitives.line(c, x + pad, y + 32.0 * k, x + bw - pad, y + 32.0 * k, Palette.argb(pal["hair"]), 0.8 * k)
        for ((cy, label) in arrayOf(58.0 to "昼夜", 94.0 to "剪影", 130.0 to "淡度", 170.0 to "辰字", 228.0 to "壁纸")) {
            Primitives.text(c, x + pad, y + cy * k, label, 11.0 * k, chenF, Palette.argb(pal["faint"]), align = "l")
        }

        val theme = st.themeCfg
        chip(c, g.chips["theme:auto"]!!, "随昼夜", theme == "auto", pal, fonts, k)
        chip(c, g.chips["theme:day"]!!, "常昼", theme == "day", pal, fonts, k)
        chip(c, g.chips["theme:night"]!!, "常夜", theme == "night", pal, fonts, k)

        val wm = st.watermark ?: "zodiac"
        for ((key, lbl) in RenderGeometry.SET_WM) {
            chip(c, g.chips["wm:$key"]!!, lbl, wm == key, pal, fonts, k)
        }

        // 淡度滑轨：底轨 + 已选段 + 圆钮 + 数值
        val a = st.wmAlpha
        val (a0, a1) = g.alphaRange
        val tx = g.track[0]
        val ty = g.track[1]
        val tw = g.track[2]
        val th = g.track[3]
        val kn = tx + (min(max(a, a0), a1) - a0) / (a1 - a0) * tw
        Primitives.fillRoundRect(c, tx, ty, tw, th, th / 2.0, Palette.argb(pal["hair"]))
        Primitives.fillRoundRect(c, tx, ty, max(th, kn - tx), th, th / 2.0, Palette.argb(pal["cinnabar"]))
        val p = Paint(PM)
        p.style = Paint.Style.FILL
        p.color = Palette.argb(pal["cinnabar"])
        c.drawCircle(kn.toFloat(), (ty + th / 2.0).toFloat(), (5.5 * k).toFloat(), p)
        Primitives.text(c, x + bw - pad, ty + th / 2.0, "%.2f".format(a), 9.5 * k, chenF, Palette.argb(pal["ink"]), align = "r")

        chip(c, g.chips["font:kai"]!!, "楷体", st.fontFace == "kai", pal, fonts, k)
        chip(c, g.chips["font:ming"]!!, "明体", st.fontFace == "ming", pal, fonts, k)
        val note = st.sealNote
        if (note != null) {
            Primitives.text(c, x + bw - pad, y + 190.0 * k, note, 9.0 * k, chenF, Palette.argb(pal["faint"]), align = "r")
        }

        chip(c, g.chips["wall:live"]!!, "壁画自启", st.wallLive, pal, fonts, k)
        chip(c, g.chips["wall:static"]!!, "静态壁纸", false, pal, fonts, k)
        // bh 参数保留（与桌面签名对齐）：窗高参与布局但不直接引用
        check(bh > 0)
    }

    /** 桌面 _popup 入口：按 kind 分派。alm 由调用方备好（null 时历注类不弹）。 */
    fun popup(
        c: Canvas, w: Double, h: Double, lay: RenderGeometry.Layout, kind: String,
        st: SceneState, alm: Lunar.Almanac?, pal: Palette, fonts: Primitives.Fonts,
    ) {
        if (kind == "settings") {
            val g = RenderGeometry.settingsGeom(w, h)
            val scx = lay.sealX
            val scy = lay.sealY
            Primitives.line(c, scx, scy - 28.0 * g.k, scx, g.y + g.bh, Palette.argb(pal["ink"]), 1.0 * g.k)
            Primitives.card(c, g.x, g.y, g.bw, g.bh, pal, g.k)
            popupSettings(c, g, st, pal, fonts)
            return
        }
        if (alm == null) return
        val g = RenderGeometry.popupGeom(w, h, lay, kind == "month", alm.month.size / 7)
        leader(c, lay.anchorX, lay.anchorY, g, pal)
        Primitives.card(c, g.x, g.y, g.bw, g.bh, pal, g.k)
        if (kind == "month") {
            popupMonth(c, g, alm, pal, fonts)
        } else {
            popupThree(c, g, alm, pal, fonts)
        }
    }

    /** 桌面 hit_test 的 almanac 月行数换算（命中测试用）。 */
    fun monthRows(alm: Lunar.Almanac?): Int =
        if (alm == null) 6 else max(5, alm.month.size / 7)
}
