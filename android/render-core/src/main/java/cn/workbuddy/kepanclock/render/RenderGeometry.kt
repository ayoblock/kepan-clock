package cn.workbuddy.kepanclock.render

import cn.workbuddy.kepanclock.domain.ChenCore
import kotlin.math.max
import kotlin.math.min

/**
 * 盘面与面板几何（移植自桌面 dial.py 的 _layout/_popup_geom/settings_geom/
 * hit_test 与基元 _pt/_u_angle）。绘制与命中测试共此一份，避免两处走形。
 * 本文件零 Android 依赖，可 JVM 单测。
 */
object RenderGeometry {
    const val SIDEREAL_MIN = 1436.07          // 恒星日（23时56分04秒）按分钟
    const val NUM_CN = "一二三四五六七八"      // 副盘八刻序字

    // 四象：午上朱雀、酉右白虎、子下玄武、卯左苍龙（方位顺时针 0/90/180/270）
    val SIMEN = listOf(
        Triple(0.0, "朱雀", "que"),
        Triple(90.0, "白虎", "hu"),
        Triple(180.0, "玄武", "xuan"),
        Triple(270.0, "苍龙", "long"),
    )
    const val MEDALLION_MIN = 14.0            // 圆牌半径低于此值回退篆字

    // 面板设计基准（k=1）
    const val PANEL_H = 560.0
    const val NOTE_Y = 276.0
    const val SEAL_Y = 302.0
    const val NAME_Y = 374.0
    const val SIDE_Y = 482.0
    const val POPUP_GAP = 7.0

    const val POP_W = 320.0
    const val POP_H3 = 168.0

    /** 横屏右面板相对主盘的缩小比例（2026-11-23 v1.6.9：主盘主导，面板为陪衬）。 */
    private const val PANEL_K = 0.75

    val SET_WM = arrayOf(
        "zodiac" to "生肖", "feitian" to "飞天", "beast" to "神兽",
        "chen" to "辰字", "none" to "无",
    )
    val ALPHA_RANGE = 0.02 to 0.45

    /** 辰内统一时 u（自前夜23时起的分钟）→ 顺时针角位（度）。 */
    fun uAngle(u: Double): Double {
        val t = (u - 60.0) % 1440.0
        val tt = if (t < 0) t + 1440.0 else t
        return (tt - 720.0) * 0.25
    }

    /** 角位自正上（午位）顺时针计的点。 */
    fun pt(cx: Double, cy: Double, r: Double, thetaDeg: Double): Pair<Double, Double> {
        val t = Math.toRadians(thetaDeg)
        return (cx + r * Math.sin(t)) to (cy - r * Math.cos(t))
    }

    /** 盘面与面板的几何。 */
    data class Layout(
        val R: Double, val pw: Double, val k: Double,
        val cx: Double, val cy: Double,
        val px: Double, val py0: Double, val noteY: Double,
        val sealX: Double, val sealY: Double,
        val noteRect: DoubleArray,      // x, y, w, h
        val anchorX: Double, val anchorY: Double,
        // ---- 竖屏专属（横屏为 false / 0）----
        val portrait: Boolean = false,
        val row2Y: Double = 0.0,                                  // 读数行中心（盘底+50）
        val gengY: Double = 0.0,                                  // 更牌行中心（读数行+行距）
        val gengW: Double = 0.0, val gengH: Double = 0.0,         // 更牌窗尺寸（居中由绘制层排布）
        val leftCx: Double = 0.0, val infoTop: Double = 0.0,      // 左块中心 / 下区顶
        val sideCx: Double = 0.0, val sideCy: Double = 0.0, val sideR: Double = 0.0,
        val wmTop: Double = 0.0, val wmBottom: Double = 0.0,      // 水印带
        val nameY: Double = 0.0,                                  // 名例行
    )

    fun layout(w: Double, h: Double): Layout =
        if (h > w) layoutPortrait(w, h) else layoutLandscape(w, h)

    /** 竖屏：主盘上段居中 → 读数行居中 → 更牌行居中 → 左立冬天行/右小盘 → 历注+名例居中。
     *  数值锚点对齐用户示意稿（412×867）：盘底≈0.52h、读数 502、更牌 554、立冬块顶 635、
     *  天行印心 751、副盘心 (293,715) r76、历注 815、名例 843。 */
    private fun layoutPortrait(w: Double, h: Double): Layout {
        val top = 72.0
        val R = max(60.0, min(w / 2.0 - 16.0, h * 0.30 - top)).let { Math.round(it).toDouble() }
        val cx = w / 2.0
        val cy = top + R
        val k = max(0.82, min(w / 412.0, 1.35))
        // 读数行（司辰印+读数整组居中）与更牌行（窗居中），上下两行。
        // 2026-11-22 用户反馈：更牌下移——rowGap 52k→64k，读数行与更牌窗拉开间距
        val readY = cy + R + 50.0
        val rowGap = 64.0 * k
        val gengW = min(320.0 * k, w * 0.37)
        val gengH = 50.0 * k
        val gengY = readY + rowGap
        // 下区：左块（节气/对时/真太阳时/天行印） vs 右块（八刻副盘）
        val infoTop = gengY + gengH / 2.0 + 56.0 * k
        val leftCx = w * 0.27
        val sideR = min(76.0 * k, w * 0.185)
        val sideCx = w * 0.71
        val nameY = h - 24.0
        val noteY = nameY - 28.0 * k
        val sideCy = min(infoTop + 80.0 * k, noteY - sideR - 18.0 * k)
        val sealY = min(infoTop + 116.0 * k, noteY - 44.0 * k)
        return Layout(
            R = R, pw = min(w * 0.9, 480.0), k = k, cx = cx, cy = cy,
            px = 0.0, py0 = 0.0, noteY = noteY,
            sealX = leftCx, sealY = sealY,
            noteRect = doubleArrayOf(cx - w * 0.30, noteY - 12.0 * k, w * 0.60, 24.0 * k),
            anchorX = cx, anchorY = noteY + 12.0 * k,
            portrait = true,
            row2Y = readY,
            gengY = gengY,
            gengW = gengW, gengH = gengH,
            leftCx = leftCx, infoTop = infoTop,
            sideCx = sideCx, sideCy = sideCy, sideR = sideR,
            wmTop = infoTop - 25.0, wmBottom = noteY - 26.0 * k,
            nameY = nameY,
        )
    }

    /** 横屏（2026-11-23 用户反馈 v1.6.9）：主盘主导——主盘放大（≤0.45h）锚定
     *  屏幕正中（cx=w/2、cy=h/2），副盘居左余位中央，右面板按主盘比例
     *  PANEL_K=0.75 缩小为陪衬；主盘↔面板间距＝右缘边距（gR=(w-cx-R-pw)/2）。
     *  弹层/设置随 k 缩。空间不足时整体等比收缩（k 同步缩保持面板不出框）。 */
    private fun layoutLandscape(w: Double, h: Double): Layout {
        val R0 = max(60.0, min(h * 0.45, (h - 44.0) / 2.0))
        val rS0 = h * 0.18
        val k0 = max(0.62, min((h - 40.0) / 400.0, 2.2) * PANEL_K)
        val cap = w / 2.0 - 40.0
        val s = minOf(1.0, cap / (R0 + 2.0 * rS0), cap / (R0 + 300.0 * k0))
        val k = max(0.55, k0 * s)
        return landscapePlace(w, h, R0 * s, rS0 * s, 300.0 * k, k)
    }

    /** 横屏布位（layoutLandscape 与收缩路径共用）：主盘正中、副盘居左余位
     *  中央、面板居右，主盘↔面板间距与右缘边距相等。 */
    private fun landscapePlace(w: Double, h: Double, R: Double, rS: Double, pw: Double, k: Double): Layout {
        val cx = w / 2.0
        val cy = h * 0.5
        val sideCx = (cx - R) / 2.0
        val px = cx + R + max(20.0, (w - cx - R - pw) / 2.0)
        val py0 = max(12.0, (h - 400.0 * k) / 2.0)
        val ny = py0 + NOTE_Y * k
        val scx = px + pw / 2.0
        val scy = py0 + (SEAL_Y + 25.0) * k
        return Layout(
            R = R, pw = pw, k = k, cx = cx, cy = cy, px = px, py0 = py0, noteY = ny,
            sealX = scx, sealY = scy,
            noteRect = doubleArrayOf(px + 4.0 * k, ny - 12.0 * k, pw - 8.0 * k, 24.0 * k),
            anchorX = scx, anchorY = ny + 12.0 * k,
            sideCx = sideCx, sideCy = cy, sideR = rS,
        )
    }

    /** 气泡卡片位置：下 → 上 → 右 → 左 四向避让，皆不容则钳制于窗内。 */
    data class PopupBox(
        val x: Double, val y: Double, val bw: Double, val bh: Double,
        val k: Double, val side: String,
    )

    fun popupGeom(w: Double, h: Double, lay: Layout, isMonth: Boolean, monthRows: Int): PopupBox {
        val k = lay.k
        val rows = if (isMonth) monthRows else 6
        var bh = ((if (isMonth) 56.0 + 30.0 * max(5, rows) + 44.0 else POP_H3)) * k
        var bw = max(POP_W * k, lay.pw * 1.14)
        bw = min(bw, max(200.0, w - 16.0))
        bh = min(bh, max(120.0, h - 16.0))
        val ax = lay.anchorX
        val ay = lay.anchorY
        val gap = POPUP_GAP * k
        val trials = listOf(
            Triple("down", ax - bw / 2.0, ay + gap),
            Triple("up", ax - bw / 2.0, ay - gap - bh),
            Triple("right", ax + gap, ay - bh / 2.0),
            Triple("left", ax - gap - bw, ay - bh / 2.0),
        )
        var x: Double? = null
        var y: Double? = null
        var side = "down"
        for ((s, tx, ty) in trials) {
            if (tx >= 8.0 && ty >= 8.0 && tx + bw <= w - 8.0 && ty + bh <= h - 8.0) {
                x = tx
                y = ty
                side = s
                break
            }
        }
        if (x == null || y == null) {
            x = min(max(8.0, trials[0].second), max(8.0, w - 8.0 - bw))
            y = min(max(8.0, trials[0].third), max(8.0, h - 8.0 - bh))
        }
        return PopupBox(x, y, bw, bh, k, side)
    }

    /** 设置牌窗与「天行」字印的几何，绘制与命中测试共此一份。 */
    data class SettingsBox(
        val x: Double, val y: Double, val bw: Double, val bh: Double, val k: Double,
        val left: Double, val right: Double, val pad: Double,
        val sealRect: DoubleArray,            // x, y, w, h
        val chips: Map<String, DoubleArray>,  // key -> x, y, w, h
        val track: DoubleArray,               // x, y, w, h
    ) {
        val alphaRange: Pair<Double, Double> get() = ALPHA_RANGE
    }

    fun settingsGeom(w: Double, h: Double): SettingsBox {
        val lay = layout(w, h)
        val k = lay.k
        val bw = min(max(POP_W * k, lay.pw * 1.14), max(200.0, w - 16.0))
        val bh = min(254.0 * k, max(150.0, h - 16.0))
        val scx = lay.sealX
        val scy = lay.sealY
        var x = min(max(8.0, scx - bw / 2.0), max(8.0, w - 8.0 - bw))
        var y = scy - 18.0 * k - bh
        if (y < 8.0) y = max(8.0, h - 8.0 - bh)
        val pad = 12.0 * k
        val left = x + pad + 36.0 * k
        val right = x + bw - pad
        val chips = LinkedHashMap<String, DoubleArray>()

        fun chip(key: String, rx: Double, rw: Double, cy: Double) {
            chips[key] = doubleArrayOf(rx, cy - 11.0 * k, rw, 22.0 * k)
        }

        // 昼夜：三选
        var cy = y + 58.0 * k
        val cw3 = (right - left - 12.0 * k) / 3.0
        chip("theme:auto", left, cw3, cy)
        chip("theme:day", left + (cw3 + 6.0 * k), cw3, cy)
        chip("theme:night", left + 2.0 * (cw3 + 6.0 * k), cw3, cy)
        // 剪影：五选
        cy = y + 94.0 * k
        val cw5 = (right - left - 20.0 * k) / 5.0
        for ((i, entry) in SET_WM.withIndex()) {
            chip("wm:" + entry.first, left + i * (cw5 + 5.0 * k), cw5, cy)
        }
        // 淡度：滑轨（右留数值位）
        val tx1 = right - 44.0 * k
        val track = doubleArrayOf(left, y + 130.0 * k - 2.0 * k, tx1 - left, 4.0 * k)
        // 辰字：二选
        cy = y + 170.0 * k
        chip("font:kai", left, 46.0 * k, cy)
        chip("font:ming", left + 52.0 * k, 46.0 * k, cy)
        // 壁纸：二动作
        cy = y + 228.0 * k
        val half = (right - left - 8.0 * k) / 2.0
        chip("wall:live", left, half, cy)
        chip("wall:static", left + half + 8.0 * k, half, cy)
        return SettingsBox(
            x = x, y = y, bw = bw, bh = bh, k = k,
            left = left, right = right, pad = pad,
            sealRect = doubleArrayOf(scx - 25.0 * k, scy - 25.0 * k, 50.0 * k, 50.0 * k),
            chips = chips, track = track,
        )
    }

    /**
     * 天行印字号适配（P5-R1 出框根修）：基准字号 sealProbe 下实测两字宽 textWidth，
     * 收敛为「字宽 ≤ fs×0.76、字高 ≤ fs×0.52」；竖屏 h>w 时固定 h 比例字号
     * 必然横向撑破 w 比例框，故按框宽反算。
     */
    fun sealTextSize(sealProbe: Double, fs: Double, textWidth: Double): Double =
        minOf(
            sealProbe,
            if (textWidth > 0) sealProbe * fs * 0.76 / textWidth else sealProbe,
            fs * 0.52,
        )

    /**
     * 盘面试时：角位 → 读数（桌面 dial.py draw_scene 的 hover 块 1:1 移植）。
     * 指针落在主盘环带（0.60R–1.02R）内返回该角位的时刻读数，否则 null。
     */
    fun hoverRead(w: Double, h: Double, x: Double, y: Double): String? {
        val lay = layout(w, h)
        val dx = x - lay.cx
        val dy = y - lay.cy
        val dist = Math.sqrt(dx * dx + dy * dy)
        if (dist <= lay.R * 0.60 || dist >= lay.R * 1.02) return null
        var theta = Math.toDegrees(Math.atan2(dx, -dy))
        if (theta < 0) theta += 360.0
        theta %= 360.0
        val t = ((theta * 4.0 + 720.0) % 1440.0).toInt()
        return ChenCore.readStr(t)
    }

    /**
     * 点击命中：返回 "note" / "popup" / "footer" / "seal" / "settings" /
     * "set:…" / null。popupKind 为当前展开的气泡（null = 无）。
     */
    fun hitTest(
        w: Double, h: Double, x: Double, y: Double,
        popupKind: String?, monthRows: Int,
    ): String? {
        val lay = layout(w, h)
        val sg = settingsGeom(w, h)
        val sx = sg.sealRect[0]
        val sy = sg.sealRect[1]
        val sw = sg.sealRect[2]
        val sh = sg.sealRect[3]
        val inSeal = sx <= x && x <= sx + sw && sy <= y && y <= sy + sh
        if (popupKind == "settings") {
            if (sg.x <= x && x <= sg.x + sg.bw && sg.y <= y && y <= sg.y + sg.bh) {
                for ((key, r) in sg.chips) {
                    if (r[0] <= x && x <= r[0] + r[2] && r[1] <= y && y <= r[1] + r[3]) {
                        return "set:$key"
                    }
                }
                val tx = sg.track[0]
                val ty = sg.track[1]
                val tw = sg.track[2]
                val th = sg.track[3]
                if (tx <= x && x <= tx + tw && ty - 6.0 * sg.k <= y && y <= ty + th + 6.0 * sg.k) {
                    val (a0, a1) = sg.alphaRange
                    val v = a0 + (x - tx) / max(tw, 1.0) * (a1 - a0)
                    val clamped = min(max(v, a0), a1)
                    return "set:alpha:%.3f".format(clamped)
                }
                return "settings"
            }
        } else if (inSeal) {
            return "seal"
        }
        // 气泡命中须先于历注行：竖屏布局气泡在历注行上方展开，底栏「展全月/收」
        // 恰与历注命中框重叠（桌面横向布局气泡在历注下方无重叠，故桌面先判 note）。
        // 设置展开时不复用历注气泡几何（两者互斥展示，设置牌窗为独立浮卡）
        if (popupKind != null && popupKind != "settings") {
            val g = popupGeom(w, h, lay, popupKind == "month", monthRows)
            if (g.x <= x && x <= g.x + g.bw && g.y <= y && y <= g.y + g.bh) {
                if (y >= g.y + g.bh - 28.0 * g.k) return "footer"
                return "popup"
            }
        }
        val nx = lay.noteRect[0]
        val ny = lay.noteRect[1]
        val nw = lay.noteRect[2]
        val nh = lay.noteRect[3]
        if (nx <= x && x <= nx + nw && ny <= y && y <= ny + nh) {
            return "note"
        }
        return null
    }
}
