package cn.workbuddy.kepanclock.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 渲染几何纯函数单测（与桌面 dial.py _layout/_popup_geom/settings_geom/
 * hit_test 逐函数对齐）。 golden 结构性断言之一（方案 P2 DoD）。
 */
class RenderGeometryTest {

    @Test
    fun `uAngle 与桌面语义一致`() {
        // 桌面语义：t = (u - 60) % 1440（Python 非负取模），角 = (t - 720) * 0.25
        // 午正 u=780 → t=720 → 0°（正上）；子夜 u=0 → t=-60→1380 → 165°*? 手算：1380-720=660*0.25=165
        assertEquals(0.0, RenderGeometry.uAngle(780.0))
        assertEquals(165.0, RenderGeometry.uAngle(0.0))
        // 辰初 u=60 → t=0 → -180°（正下）
        assertEquals(-180.0, RenderGeometry.uAngle(60.0))
    }

    @Test
    fun `pt 四正方位`() {
        // 0° 正上、90° 右、180° 正下、270° 左（顺时针自午位）；三角函数 1e-9 容差
        val (tx, ty) = RenderGeometry.pt(100.0, 100.0, 50.0, 0.0)
        assertEquals(100.0, tx, 1e-9); assertEquals(50.0, ty, 1e-9)
        val (rx, ry) = RenderGeometry.pt(100.0, 100.0, 50.0, 90.0)
        assertEquals(150.0, rx, 1e-9); assertEquals(100.0, ry, 1e-9)
        val (bx, by) = RenderGeometry.pt(100.0, 100.0, 50.0, 180.0)
        assertEquals(100.0, bx, 1e-9); assertEquals(150.0, by, 1e-9)
        val (lx, ly) = RenderGeometry.pt(100.0, 100.0, 50.0, 270.0)
        assertEquals(50.0, lx, 1e-9); assertEquals(100.0, ly, 1e-9)
    }

    @Test
    fun `layout 结构性断言`() {
        // 桌面设计基准：1024×640
        val lay = RenderGeometry.layout(1024.0, 640.0)
        // R = min((640-72)/2=284, (1024-292)/2=366) → 284
        assertEquals(284.0, lay.R)
        // pw = min(480, 1024*0.30=307.2) → 307.2；k = min(307.2/260, (640-60)/560=1.036, 2.2) → 1.036
        assertEquals(307.2, lay.pw, 1e-9)
        assertEquals((640.0 - 60.0) / 560.0, lay.k, 1e-9)
        // 盘心在面板左侧，面板起点 = 盘心 + R + 28
        assertEquals(lay.cx + lay.R + 28.0, lay.px, 1e-9)
        // 锚点即历注行下缘中心
        assertEquals(lay.px + lay.pw / 2.0, lay.anchorX, 1e-9)
        assertEquals(lay.py0 + RenderGeometry.NOTE_Y * lay.k + 12.0 * lay.k, lay.anchorY, 1e-9)
    }

    @Test
    fun `popupGeom 四向避让`() {
        val lay = RenderGeometry.layout(1024.0, 640.0)
        // 三日气泡 POP_W=320，1024 宽窗下 down 通常可行；
        // bw = max(POP_W*k, pw*1.14)——本窗 pw*1.14=350.2 > 320*k=331.4，取后者（桌面同式）
        val g3 = RenderGeometry.popupGeom(1024.0, 640.0, lay, isMonth = false, monthRows = 6)
        assertEquals("down", g3.side)
        assertEquals(maxOf(RenderGeometry.POP_W * lay.k, lay.pw * 1.14), g3.bw, 1e-9)
        assertEquals(RenderGeometry.POP_H3 * lay.k, g3.bh, 1e-9)
        // 整月历更高（6 行：56+180+44=280*k）
        val gm = RenderGeometry.popupGeom(1024.0, 640.0, lay, isMonth = true, monthRows = 6)
        assertEquals((56.0 + 30.0 * 6 + 44.0) * lay.k, gm.bh, 1e-9)
        // 极窄窗：四向皆不容 → 钳制于窗内且不越界
        val gs = RenderGeometry.popupGeom(300.0, 200.0, RenderGeometry.layout(300.0, 200.0), isMonth = true, monthRows = 6)
        assertTrue(gs.x >= 8.0 && gs.x + gs.bw <= 300.0 - 8.0 + 1e-9)
        assertTrue(gs.y >= 8.0 && gs.y + gs.bh <= 200.0 - 8.0 + 1e-9)
    }

    @Test
    fun `settingsGeom chips 键集与桌面一致`() {
        val sg = RenderGeometry.settingsGeom(1024.0, 640.0)
        val keys = sg.chips.keys
        for (k in listOf("theme:auto", "theme:day", "theme:night",
            "wm:zodiac", "wm:feitian", "wm:beast", "wm:chen", "wm:none",
            "font:kai", "font:ming", "wall:live", "wall:static")) {
            assertTrue(keys.contains(k), "缺 chip: $k")
        }
        assertEquals(12, keys.size)
        // 印位 50×50 居字印心
        assertEquals(50.0 * sg.k, sg.sealRect[2], 1e-9)
        assertEquals(50.0 * sg.k, sg.sealRect[3], 1e-9)
    }

    @Test
    fun `hitTest 全路径`() {
        val w = 1024.0
        val h = 640.0
        val lay = RenderGeometry.layout(w, h)
        // 无气泡时点印 → seal
        assertEquals("seal", RenderGeometry.hitTest(w, h, lay.sealX, lay.sealY, null, 6))
        // 点历注行 → note
        val nx = lay.noteRect[0] + 10.0
        val ny = lay.noteRect[1] + 5.0
        assertEquals("note", RenderGeometry.hitTest(w, h, nx, ny, null, 6))
        // 设置牌窗内空白（右下角，壁纸行下方、无控件区）→ settings
        val sg0 = RenderGeometry.settingsGeom(w, h)
        assertEquals(
            "settings",
            RenderGeometry.hitTest(w, h, sg0.x + sg0.bw - 4.0, sg0.y + sg0.bh - 6.0, "settings", 6),
        )
        // 设置牌窗展开时点印位：印位在牌窗外（牌窗自印上方展开），与桌面同逻辑
        // 落入三日气泡几何 → "popup"（桌面 dial.py hit_test 同值，1:1 对齐）
        assertEquals("popup", RenderGeometry.hitTest(w, h, lay.sealX, lay.sealY, "settings", 6))
        // 设置牌窗内 chip 命中 → set:theme:day
        val sg = RenderGeometry.settingsGeom(w, h)
        val dayChip = sg.chips["theme:day"]!!
        assertEquals(
            "set:theme:day",
            RenderGeometry.hitTest(w, h, dayChip[0] + dayChip[2] / 2.0, dayChip[1] + dayChip[3] / 2.0, "settings", 6),
        )
        // 滑轨命中 → set:alpha:…
        val alphaHit = RenderGeometry.hitTest(w, h, sg.track[0] + sg.track[2] * 0.5, sg.track[1] + 1.0, "settings", 6)
        assertNotNull(alphaHit)
        assertTrue(alphaHit!!.startsWith("set:alpha:"))
        // 历注气泡展开时：气泡内部 → popup，底栏 → footer
        val g = RenderGeometry.popupGeom(w, h, lay, isMonth = false, monthRows = 6)
        assertEquals("popup", RenderGeometry.hitTest(w, h, g.x + g.bw / 2.0, g.y + g.bh / 2.0, "three", 6))
        assertEquals("footer", RenderGeometry.hitTest(w, h, g.x + g.bw / 2.0, g.y + g.bh - 10.0, "three", 6))
        // 全部未命中 → null
        assertNull(RenderGeometry.hitTest(w, h, 2.0, 2.0, null, 6))
    }

    @Test
    fun `调色板插值与钳制`() {
        val day = Palette.day()
        val night = Palette.night()
        // f=0 为昼、f=1 为夜
        val mid = Palette.lerp(day, night, 0.5)
        assertEquals((day.ink[0] + night.ink[0]) / 2.0, mid.ink[0], 1e-12)
        val end = Palette.lerp(day, night, 1.0)
        assertEquals(night.ink[0], end.ink[0], 1e-12)
        // _mul 钳到 1.0
        val clamped = Palette.mul(doubleArrayOf(1.2, 0.5, 0.1), 2.0)
        assertEquals(1.0, clamped[0])
        assertEquals(1.0, clamped[1])
        // ARGB 转换抽样：黑 → 0xFF000000，白 → 0xFFFFFFFF
        assertEquals(-0x1000000, Palette.argb(doubleArrayOf(0.0, 0.0, 0.0)))
        assertEquals(-0x1, Palette.argb(doubleArrayOf(1.0, 1.0, 1.0)))
        assertEquals(0x40FFFFFF, Palette.argb(doubleArrayOf(1.0, 1.0, 1.0), 64.0 / 255.0))
        assertFalse(Palette.argb(day["bg"]) == Palette.argb(night["bg"]))
    }

    @Test
    fun `固定数据表与桌面规模一致`() {
        // 桌面：主星 40、银河 56、四象 4、生肖 12、飞天 12
        assertEquals(40, DialData.STARS.size)
        assertEquals(56, DialData.GALAXY.size)
        assertEquals(4, DialData.BEAST.size)
        assertEquals(12, DialData.ZODIAC.size)
        assertEquals(12, DialData.FT.size)
        // 星空种子过滤：无主星落入四象 26° 内径带（桌面生成规则）
        for (s in DialData.STARS) {
            val nearCardinal = RenderGeometry.SIMEN.any { entry ->
                val c = entry.first
                val d1 = (s.phi - c) % 360.0
                val d2 = (c - s.phi) % 360.0
                val dm = minOf(if (d1 < 0) d1 + 360 else d1, if (d2 < 0) d2 + 360 else d2)
                Math.abs(s.rf - 0.50) < 0.14 && dm < 26.0
            }
            assertFalse(nearCardinal, "主星误入四象保护区: $s")
        }
    }

    @Test
    fun `竖屏示意稿布局结构断言`() {
        // 手机典型竖屏 412×915
        val lay = RenderGeometry.layout(412.0, 915.0)
        assertTrue(lay.portrait)
        // 主盘上段居中：盘顶 ≥ 0；R 为整数像素（避免位图半像素采样）
        assertTrue(lay.cy - lay.R >= 0.0)
        assertEquals(Math.round(lay.R).toDouble(), lay.R, 1e-9)
        // 读数行距盘底 50，居中；更牌行在读数行下方，同样居中
        assertEquals(lay.cy + lay.R + 50.0, lay.row2Y, 1e-9)
        assertEquals(lay.row2Y + 64.0 * lay.k, lay.gengY, 1e-9)
        assertTrue(lay.gengW > 0.0 && lay.gengH > 0.0)
        assertTrue(lay.gengW <= 412.0 * 0.37)
        // 下区在更牌行之下：左块与右副盘纵向有序，天行印与副盘都在历注之上
        assertTrue(lay.infoTop > lay.gengY + lay.gengH / 2.0)
        assertTrue(lay.sideCy > lay.infoTop)
        assertTrue(lay.sealY > lay.infoTop)
        // 历注居中于名例上方，名例行最底
        assertTrue(lay.noteY > maxOf(lay.sealY + 25.0 * lay.k, lay.sideCy + lay.sideR))
        assertTrue(lay.noteY < lay.nameY)
        assertEquals(412.0 / 2.0, lay.noteRect[0] + lay.noteRect[2] / 2.0, 1e-9)
        assertEquals(lay.nameY - lay.noteY, 28.0 * lay.k, 1e-9)
        assertTrue(lay.nameY <= 915.0)
        // 命中：竖屏下历注与天行印仍可点，设置牌在印上方展开
        assertEquals("note", RenderGeometry.hitTest(412.0, 915.0, lay.anchorX, lay.noteY, null, 6))
        assertEquals("seal", RenderGeometry.hitTest(412.0, 915.0, lay.sealX, lay.sealY, null, 6))
        val sg = RenderGeometry.settingsGeom(412.0, 915.0)
        assertTrue(sg.y >= 8.0 && sg.y + sg.bh <= 915.0 - 8.0)
        // 示意稿锚点复核（412×867，容差 5dp）：读数 502 / 更牌 566 / infoTop 647 / 历注 815
        // （2026-11-22 用户反馈：更牌下移 12dp 拉开与读数行间距，rowGap 52k→64k）
        val m = RenderGeometry.layout(412.0, 867.0)
        assertEquals(502.0, m.row2Y, 5.0)
        assertEquals(566.0, m.gengY, 5.0)
        assertEquals(647.0, m.infoTop, 5.0)
        assertEquals(815.0, m.noteY, 1.0)
        // 横屏不受影响：同尺寸仍走桌面布局
        assertFalse(RenderGeometry.layout(1024.0, 640.0).portrait)
    }

    @Test
    fun `盘面试时 hover 角位换算`() {
        // 412×867 竖屏：R=round(min(190, 188.1))=188，cx=206、cy=260。
        // 环带 (0.60R, 1.02R) = (112.8, 191.76)。桌面 t=theta*4+720 为钟表分钟。
        val R = 188.0
        val cx = 206.0
        val cy = 72.0 + R
        // 正上（午位）：theta=0 → T=720 → 午正一刻（手算：u=780、k=6、p=60→正、刻一）
        assertEquals("午正一刻", RenderGeometry.hoverRead(412.0, 867.0, cx, cy - 0.8 * R))
        // 正下：theta=180 → T=1440%1440=0 → 子正一刻（u=60、k=0、p=60）
        assertEquals("子正一刻", RenderGeometry.hoverRead(412.0, 867.0, cx, cy + 0.8 * R))
        // 正右：theta=90 → T=1080（18:00）→ 酉正一刻（酉右）
        assertEquals("酉正一刻", RenderGeometry.hoverRead(412.0, 867.0, cx + 0.8 * R, cy))
        // 正左：theta=270 → T=360（06:00）→ 卯正一刻（卯左）
        assertEquals("卯正一刻", RenderGeometry.hoverRead(412.0, 867.0, cx - 0.8 * R, cy))
        // 环带外不试时：盘心内径 0.59R（内）与盘缘外 1.05R。
        // 注意不取 0.60R 精确边界：二进制浮点往返（cy-0.6R 再求距）会差 1 ulp，边界判定不稳定。
        assertEquals(null, RenderGeometry.hoverRead(412.0, 867.0, cx, cy - 0.59 * R))
        assertEquals(null, RenderGeometry.hoverRead(412.0, 867.0, cx, cy - 1.05 * R))
        // 触发条件锚点（回归注释）：竖屏气泡在历注行上方展开，底栏与历注命中框重叠，
        // 命中顺序必须先气泡后历注（P3-R1 实证：桌面顺序先 note 后 popup 会吞掉 footer）
        val layP = RenderGeometry.layout(412.0, 915.0)
        val g3 = RenderGeometry.popupGeom(412.0, 915.0, layP, false, 6)
        val fy = g3.y + g3.bh - 14.0 * g3.k   // 三日气泡底栏中心
        assertEquals(
            "footer",
            RenderGeometry.hitTest(412.0, 915.0, layP.anchorX, fy, "three", 6),
        )
        assertEquals(
            "popup",
            RenderGeometry.hitTest(412.0, 915.0, layP.anchorX, g3.y + 20.0 * g3.k, "three", 6),
        )
    }

    @Test
    fun `天行印字号按框宽收敛不出框`() {
        // P5-R1 出框根修：竖屏 720x1600，fs=0.145w≈104.4，probe=0.052h≈83.2，
        // 两字楷体实测宽约 2×probe≈166 > fs——必须反算收敛，宽度限 0.76fs≈79.4
        val probe = 0.052 * 1600
        val fs = 0.145 * 720
        val wide = probe * 2.0 // 两字宽（CJK 逐字近方，2 倍字号）
        val s = RenderGeometry.sealTextSize(probe, fs, wide)
        // 收敛后两字总宽 ≤ 0.76×fs，且字高（≈字号）≤ 0.52×fs
        assertTrue(s * 2.0 <= fs * 0.76 + 1e-9)
        assertTrue(s <= fs * 0.52 + 1e-9)
        // 极端竖屏（h>>w）也不出框；横屏（h<w）框相对更大，应保留 probe 原字号
        val sTall = RenderGeometry.sealTextSize(0.052 * 2400, 0.145 * 720, 2.0 * 0.052 * 2400)
        assertTrue(sTall * 2.0 <= 0.145 * 720 * 0.76 + 1e-9)
        val sWide = RenderGeometry.sealTextSize(20.0, 200.0, 40.0)
        assertEquals(20.0, sWide, 1e-9)
        // textWidth 非正时退回 probe（不产生 0/负字号）
        assertEquals(12.0, RenderGeometry.sealTextSize(12.0, 100.0, 0.0), 1e-9)
    }
}
