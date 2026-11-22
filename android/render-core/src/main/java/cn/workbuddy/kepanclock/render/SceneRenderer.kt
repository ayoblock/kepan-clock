package cn.workbuddy.kepanclock.render

import android.graphics.Canvas
import android.graphics.Paint
import cn.workbuddy.kepanclock.domain.ChenCore
import cn.workbuddy.kepanclock.domain.Lunar
import java.time.LocalDate

/**
 * 场景渲染主入口（移植自桌面 dial.py draw_scene / draw_wallpaper）。
 * 静态层入缓存，逐秒只绘星空与晷针；昼夜渐变期（blend∈(0,1)）绕过缓存全量重绘。
 */
object SceneRenderer {

    /** 桌面 _almanac 的按日缓存（上限 6，跨日自动换新）。 */
    private val almCache = LinkedHashMap<LocalDate, Lunar.Almanac>()

    fun almanac(d: LocalDate): Lunar.Almanac? {
        almCache[d]?.let { return it }
        val v = try {
            Lunar.almanac(d)
        } catch (e: Exception) {
            // 与桌面 error.log 同职能：历注/月历数据异常必须可见，不静默吞掉
            android.util.Log.e("KepanClock", "almanac($d) failed", e)
            return null
        }
        if (almCache.size > 6) almCache.clear()
        almCache[d] = v
        return v
    }

    /** 桌面 draw_scene 主入口。pointer 为悬停测试（null = 无指针）。
     *  dpr：设备像素比。静态盘位图按 R*dpr 物理像素生成并在恒等矩阵下 1:1 贴绘，
     *  与桌面 Cairo 的设备像素渲染等价；矢量层仍在缩放后的 dp 坐标绘制。 */
    fun drawScene(
        c: Canvas, w: Double, h: Double, st: SceneState, pal: Palette,
        fonts: Primitives.Fonts, pointer: Pair<Double, Double>? = null,
        dpr: Double = 1.0,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = Palette.argb(pal["bg"])
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        val lay = RenderGeometry.layout(w, h)
        // 桌面 draw_scene 的指针块：hover（试时读数）与 note/seal 悬停点亮。
        // SceneState 为不可变值，按指针存在与否派生生效态 eff 再进入绘制。
        val eff = if (pointer != null) {
            val nx = lay.noteRect[0]
            val ny = lay.noteRect[1]
            val nw = lay.noteRect[2]
            val nh = lay.noteRect[3]
            st.copy(
                hover = RenderGeometry.hoverRead(w, h, pointer.first, pointer.second),
                noteHover = nx <= pointer.first && pointer.first <= nx + nw &&
                    ny - 4.0 * lay.k <= pointer.second && pointer.second <= ny + nh + 4.0 * lay.k,
                sealHover = Math.abs(pointer.first - lay.sealX) <= 16.0 * lay.k &&
                    Math.abs(pointer.second - lay.sealY) <= 14.0 * lay.k,
            )
        } else {
            st.copy(hover = null, noteHover = false, sealHover = false)
        }
        val R = lay.R
        val cx = lay.cx
        val cy = lay.cy
        val px = lay.px
        val py0 = lay.py0
        val pw = lay.pw
        val k = lay.k

        val blend = st.blend
        val night = blend > 0.5
        val cacheable = blend == 0.0 || blend == 1.0
        val surf = DialRenderer.getStatic(R * dpr, pal, fonts, night, cacheable)
        // 位图按物理像素生成、恒等矩阵 1:1 贴绘：位图像素与屏幕像素一一对应（桌面同款），
        // 且落点取整避免半像素偏移的双线性采样
        c.save()
        c.setMatrix(null)
        val sDp = surf.width / dpr
        val leftPx = Math.round((cx - sDp / 2.0) * dpr).toFloat()
        val topPx = Math.round((cy - sDp / 2.0) * dpr).toFloat()
        c.drawBitmap(surf, leftPx, topPx, null)
        c.restore()

        DialRenderer.drawStars(c, cx, cy, R, st.tMin, pal)
        DialRenderer.drawNeedle(c, cx, cy, R, st.tMin, pal, night)
        if (lay.portrait) {
            DialRenderer.panelPortrait(c, w, h, lay, eff, pal, fonts)
        } else {
            // 横屏三区：副盘外置左带（等间隔均布见 layoutLandscape）
            DialRenderer.panel(
                c, px, py0, pw, eff, pal, fonts, k = k,
                sealPos = lay.sealX to lay.sealY,
                sidePos = lay.sideCx to lay.sideCy, sideROver = lay.sideR,
            )
        }

        val popup = eff.popup
        if (popup != null) {
            val alm = if (popup == "settings") null else almanac(st.date ?: LocalDate.now())
            PopupRenderer.popup(c, w, h, lay, popup, st, alm, pal, fonts)
        }

        val toast = st.toast
        if (toast != null) {
            if (System.currentTimeMillis() / 1000.0 < toast.second) {
                Primitives.text(c, w * 0.5, h - 16.0, toast.first, 11.0, fonts.chen, Palette.argb(pal["gold"]))
            }
        }
    }

    /** 壁纸/快照构图：竖屏按用户示意稿（2026-11-21，四轮精调后）三段式——
     *  顶题组（顶部朱框「天行」印＋左右干支竖题沿竖中线对称篆书并列
     *  「丙午年」「己亥日」＋中央节气名篆书竖写居中）、大盘（外金环＋篆书辰字环＋
     *  刻线环＋内盘星宿瓦当＋晷针）、底部读数组（刻读/更点/干支日/竖排历注）；
     *  横屏按用户示意稿 kepan-wallpaper16（2026-11-23）：三等分带——左八刻
     *  副盘、中主盘、右信息列，各区域带内水平居中、带距相等。全部文字取包内
     *  楷体/篆书子集，缺字由系统回退承接。 */
    fun drawWallpaper(
        c: Canvas, w: Double, h: Double, st: SceneState, pal: Palette,
        fonts: Primitives.Fonts, dpr: Double = 1.0,
    ) {
        if (h > w) drawWallpaperPortrait(c, w, h, st, pal, fonts)
        else drawWallpaperLandscape(c, w, h, st, pal, fonts, dpr)
    }

    /** 竖屏示意稿构图（dpr 预缩放约定：caller 已 scale(dpr)，此处全 dp 坐标）。 */
    private fun drawWallpaperPortrait(
        c: Canvas, w: Double, h: Double, st: SceneState, pal: Palette,
        fonts: Primitives.Fonts,
    ) {
        val night = st.blend > 0.5
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = Palette.argb(pal["bg"])
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        val gold = Palette.argb(pal["gold"])
        val cinnabar = Palette.argb(pal["cinnabar"])
        val ink = Palette.argb(pal["ink"])
        val faint = Palette.argb(pal["faint"])
        val hair = Palette.argb(pal["hair"])
        val chenF = fonts.chen
        val seal = fonts.seal
        val sealAlt = fonts.sealAlt
        val pt = st.parts

        // ---- 顶题组（2026-11-22 用户拍板：天行印与节气名位置互换）----
        // 顶部天行印：衬方同壁纸底色（遮星不显色差）＋朱框圆角方＋篆书朱字；
        // 中心取原节气名竖排块几何中心（首字 0.050h、二字 0.032h、行距 1.28 → 0.091h）
        val scy = h * 0.091
        val bs = w * 0.25
        p.style = Paint.Style.FILL
        p.color = Palette.argb(pal["bg"])
        c.drawRect(
            (w * 0.5 - bs / 2).toFloat(), (scy - bs / 2).toFloat(),
            (w * 0.5 + bs / 2).toFloat(), (scy + bs / 2).toFloat(), p,
        )
        val fs = w * 0.145
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(2.5, h * 0.0035).toFloat()
        p.color = cinnabar
        c.drawRoundRect(
            (w * 0.5 - fs / 2).toFloat(), (scy - fs / 2).toFloat(),
            (w * 0.5 + fs / 2).toFloat(), (scy + fs / 2).toFloat(),
            (fs * 0.10).toFloat(), (fs * 0.10).toFloat(), p,
        )
        // 字号动态适配（出框根修）：竖屏 h>w，固定 h 比例字号必然横向撑破 w 比例框。
        // 以 Paint 实测两字宽收敛：字宽 ≤ fs×0.76、字高 ≤ fs×0.52，再与基准字号取小
        val sealProbe = h * 0.052
        val sealM = Paint()
        sealM.typeface = seal
        sealM.textSize = sealProbe.toFloat()
        val sealSize = RenderGeometry.sealTextSize(sealProbe, fs, sealM.measureText("天行").toDouble())
        // 印文用華瑞小篆（北师大说文小篆系；霞鹜 alpha 缺「行」字、全字庫仅四字
        // 子集，2026-11-21 用户拍板统一换華瑞小篆）
        Primitives.text(c, w * 0.5, scy, "天行", sealSize, sealAlt, cinnabar)
        // 左右干支竖题（篆书金字，沿竖中线对称并列，居印章与大盘之间）：
        // 右列年干支「丙午年」、左列日干支「己亥日」（右尊左辅，自上而下）
        val gz = pt?.gz
        val yz = pt?.y
        val colSize = h * 0.021
        val colX = w * 0.085
        if (!yz.isNullOrEmpty()) {
            DialRenderer.vtext(c, w * 0.5 + colX, h * 0.180, "${yz}年", colSize, seal, gold, align = "l")
        }
        if (!gz.isNullOrEmpty()) {
            DialRenderer.vtext(c, w * 0.5 - colX, h * 0.180, "${gz}日", colSize, seal, gold, align = "r")
        }
        // 中央节气名：篆书竖写居中（候名六字归底部竖排；按字数对 0.252h 垂直居中）
        val term = st.term
        val termSize = h * 0.032
        if (term.isNotEmpty()) {
            val step = termSize * 1.28
            val ty = h * 0.252 - term.length * step / 2
            DialRenderer.vtext(c, w * 0.5, ty, term, termSize, seal, gold, align = "c")
        }

        // ---- 大盘 ----
        val cx = w * 0.5
        val cy = h * 0.505
        val R = minOf(w * 0.335, h * 0.152)
        Primitives.ring(c, cx, cy, R * 0.995, gold, maxOf(1.6, R * 0.006))
        Primitives.ring(c, cx, cy, R * 0.935, hair, 1.0)

        // 辰字环（环外，朱填篆书——2026-11-21 用户拍板：表盘外子丑等改篆）
        for ((k, ch) in ChenCore.CHEN.withIndex()) {
            val (x, y) = RenderGeometry.pt(cx, cy, R * 1.115, RenderGeometry.uAngle(k * 120.0 + 60.0))
            Primitives.text(c, x, y, ch.toString(), R * 0.118, seal, cinnabar)
        }

        // 刻线环：九十六刻，金色为主
        for (i in 0 until 96) {
            val th = RenderGeometry.uAngle(i * 15.0)
            val (r0, col, lw) = when {
                i % 8 == 0 -> Triple(0.775, gold, maxOf(1.4, R * 0.005))
                i % 4 == 0 -> Triple(0.805, Palette.argb(pal["gold"], 0.7), maxOf(1.1, R * 0.0035))
                else -> Triple(0.835, Palette.argb(pal["gold"], 0.45), 1.0)
            }
            val (x1, y1) = RenderGeometry.pt(cx, cy, R * r0, th)
            val (x2, y2) = RenderGeometry.pt(cx, cy, R * 0.925, th)
            Primitives.line(c, x1, y1, x2, y2, col, lw)
        }

        // 内盘：微亮圆底承星空
        p.style = Paint.Style.FILL
        p.color = Palette.argb(Palette.mul(pal["bg"], if (night) 1.06 else 0.985))
        c.drawCircle(cx.toFloat(), cy.toFloat(), (R * 0.76).toFloat(), p)
        DialRenderer.drawStars(c, cx, cy, R * 0.73, st.tMin, pal)

        // 四正四象瓦当：神兽线描图案（2026-11-21 用户拍板：壁纸不回退篆字，
        // 不受桌面 MEDALLION_MIN 小盘回退约束；盘心「司辰」篆款保留）
        for ((deg, _, key) in RenderGeometry.SIMEN) {
            val (mx, my) = RenderGeometry.pt(cx, cy, R * 0.50, deg)
            val mr = R * 0.095
            Primitives.ring(c, mx, my, mr, hair, maxOf(1.0, R * 0.004))
            Primitives.ring(c, mx, my, mr * 0.88, faint, 0.8)
            Primitives.beast(c, mx, my, mr * 0.70, key, gold, maxOf(1.0, mr * 0.10))
        }
        Primitives.text(c, cx, cy - R * 0.155, "司辰", R * 0.052, sealAlt, cinnabar)
        DialRenderer.drawNeedle(c, cx, cy, R * 0.985, st.tMin, pal, night)

        // ---- 底部读数组（居中；2026-11-21 用户拍板：三行统一字号 0.024h、
        //      统一行距 0.044h，自 0.716h 整体下移，让位后仍避开竖排候名 0.822h）----
        val rowSize = h * 0.024
        val rowPitch = h * 0.044
        val dateLine = "${yz ?: ""}年${pt?.lum ?: ""}${gz ?: ""}日"
        Primitives.text(c, cx, h * 0.716, st.read, rowSize, chenF, gold)
        Primitives.text(c, cx, h * 0.716 + rowPitch, st.geng, rowSize, chenF, gold)
        Primitives.text(c, cx, h * 0.716 + rowPitch * 2, dateLine, rowSize, chenF, gold)
        // 竖排候名（示意稿：六字候名逐字向下，非完整历注串）：自 0.822h 起，
        // 超长自动缩字号收进 0.985h
        val hou = pt?.hou
        if (!hou.isNullOrEmpty()) {
            var size = h * 0.024
            val top = h * 0.822
            val bottom = h * 0.985
            if (top + hou.length * size * 1.30 > bottom) {
                size = (bottom - top) / (hou.length * 1.30)
            }
            // align="c"：vtext 默认 "l"（左对齐）会把整列推到中线右半字宽
            // （2026-11-23 像素实测：720px 画布偏右 17px），候名须逐字居中
            DialRenderer.vtext(c, cx, top, hou, size, chenF, gold, align = "c")
        }
    }

    /** 桌面 draw_wallpaper：横屏壁画构图（四角竖题）。 */
    private fun drawWallpaperLandscape(
        c: Canvas, w: Double, h: Double, st: SceneState, pal: Palette,
        fonts: Primitives.Fonts, dpr: Double = 1.0,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = Palette.argb(pal["bg"])
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        // ---- 三区等间隔（2026-11-23 示意稿 kepan-wallpaper16）：左八刻副盘 /
        //      中主盘 / 右信息列；复用 layoutLandscape 的等间隔均布几何，
        //      与 app 横屏主场景完全同位 ----
        val lay = RenderGeometry.layout(w, h)
        val cy = h * 0.53
        val night = st.blend > 0.5
        val gold = Palette.argb(pal["gold"])
        val cinnabar = Palette.argb(pal["cinnabar"])
        val faint = Palette.argb(pal["faint"])
        val chenF = fonts.chen
        val seal = fonts.seal
        val sealAlt = fonts.sealAlt
        val pt = st.parts

        // 左带：八刻副盘（桌面 _dial_side 同款：八刻序字＋刻针一辰一周＋盘心生肖篆字）
        DialRenderer.dialSide(c, lay.sideCx, cy, lay.sideR, st, pal, fonts)

        // 中带：主盘（静态位图＋星点＋晷针）
        val R = lay.R
        val cx = lay.cx
        val cacheable = st.blend == 0.0 || st.blend == 1.0
        val surf = DialRenderer.getStatic(R * dpr, pal, fonts, night, cacheable)
        c.save()
        c.setMatrix(null)
        val sDp = surf.width / dpr
        c.drawBitmap(
            surf, Math.round((cx - sDp / 2.0) * dpr).toFloat(),
            Math.round((cy - sDp / 2.0) * dpr).toFloat(), null,
        )
        c.restore()
        DialRenderer.drawStars(c, cx, cy, R, st.tMin, pal)
        DialRenderer.drawNeedle(c, cx, cy, R, st.tMin, pal, night)

        // 右带：信息列（自上而下：篆书时辰读数题＋朱印 → 更牌框 → 节气 →
        // 日期 → 真太阳时 → 历注 → 天行印 → 干支候名底款），逐元素水平居中
        val rx = lay.px + lay.pw / 2.0
        val ts = h * 0.058
        val ty = h * 0.185
        // 标题组：小朱印「辰」＋篆书时辰读数，整组按实测宽居中
        val chipS = ts * 0.82
        val readW = Primitives.textW(st.read, ts, seal)
        val groupW = chipS + ts * 0.35 + readW
        val gx = rx - groupW / 2.0
        Primitives.fillRoundRect(c, gx, ty - chipS / 2.0, chipS, chipS, chipS * 0.14, cinnabar)
        Primitives.text(c, gx + chipS / 2.0, ty, "辰", chipS * 0.60, sealAlt, Palette.argb(pal["bg"]))
        Primitives.text(c, gx + chipS + ts * 0.35 + readW / 2.0, ty, st.read, ts, seal, gold)
        // 更牌框（金描圆角方，更点居中）
        val bw = lay.pw                    // 全带宽：三区可视间隔与边距全等（示意稿要求）
        val bh = h * 0.115
        val bgy = h * 0.30
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(1.5, h * 0.0028).toFloat()
        p.color = gold
        c.drawRoundRect(
            (rx - bw / 2).toFloat(), bgy.toFloat(),
            (rx + bw / 2).toFloat(), (bgy + bh).toFloat(),
            (bh * 0.08).toFloat(), (bh * 0.08).toFloat(), p,
        )
        Primitives.text(c, rx, bgy + bh / 2.0, st.geng, h * 0.042, chenF, gold)
        // 节气名 / 日期 / 真太阳时 / 历注
        Primitives.text(c, rx, h * 0.505, st.term, h * 0.036, chenF, gold)
        Primitives.text(c, rx, h * 0.565, "${st.dateStr}  ${st.hhmm}", h * 0.024, chenF, faint)
        Primitives.text(c, rx, h * 0.615, "真太阳时 ${st.tsRead}", h * 0.028, chenF, gold)
        if (st.note.isNotEmpty()) {
            var nSize = h * 0.020
            val nMax = lay.pw * 0.88
            val nw = Primitives.textW(st.note, nSize, chenF)
            if (nw > nMax) nSize *= nMax / nw
            Primitives.text(c, rx, h * 0.665, st.note, nSize, chenF, faint)
        }
        // 天行印（朱框圆角方＋篆书印文，字号实测收敛不出框）
        val scy2 = h * 0.755
        val fs = minOf(lay.pw * 0.24, h * 0.125)
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(2.0, h * 0.003).toFloat()
        p.color = cinnabar
        c.drawRoundRect(
            (rx - fs / 2).toFloat(), (scy2 - fs / 2).toFloat(),
            (rx + fs / 2).toFloat(), (scy2 + fs / 2).toFloat(),
            (fs * 0.10).toFloat(), (fs * 0.10).toFloat(), p,
        )
        val probe = fs * 0.5
        val sealM = Paint()
        sealM.typeface = seal
        sealM.textSize = probe.toFloat()
        val sealSize = RenderGeometry.sealTextSize(probe, fs, sealM.measureText("天行").toDouble())
        Primitives.text(c, rx, scy2, "天行", sealSize, sealAlt, cinnabar)
        // 底款：干支年月日 · 候名（faint 小字）
        val yz = pt?.y ?: ""
        val gz = pt?.gz ?: ""
        val lum = pt?.lum ?: ""
        val hou = pt?.hou ?: ""
        Primitives.text(c, rx, h * 0.87, "${yz}年${lum}${gz}日 · ${hou}", h * 0.020, chenF, faint)
    }

    /** 由 domain ClockState 组装渲染视图状态（app 层便捷入口）。 */
    fun sceneStateOf(cs: cn.workbuddy.kepanclock.domain.ClockState): SceneState = SceneState(
        tMin = cs.tMin, read = cs.read, geng = cs.geng, term = cs.term,
        dateStr = cs.dateStr, hhmm = cs.hhmm, tsRead = cs.tsRead,
        note = cs.note, animal = cs.animal, night = cs.night, blend = cs.blend,
        date = cs.date, parts = cs.parts,
    )
}
