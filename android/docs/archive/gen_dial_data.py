# -*- coding: utf-8 -*-
"""P2 渲染固定数据导出：STARS/GALAXY/_BEAST/_ZODIAC/_FT → Kotlin DialData.kt。

随机星空（种子 20261104）与部件表为纯数据，导出为 Kotlin 字面量，
保证 Android 侧与桌面版逐值一致（java.util.Random 与 Python 序列不同，不能现场生成）。
"""
import sys

sys.path.insert(0, "./src")
import dial  # noqa: E402  触发模块级 STARS/GALAXY 生成

OUT = "android/render-core/src/main/java/cn/workbuddy/kepanclock/render/DialData.kt"


def fnum(v):
    return repr(float(v))


def stars_kt():
    lines = ["    val STARS = listOf("]
    for phi, rf, sz, a in dial.STARS:
        lines.append(f"        Star({fnum(phi)}, {fnum(rf)}, {fnum(sz)}, {fnum(a)}),")
    lines.append("    )")
    return "\n".join(lines)


def galaxy_kt():
    lines = ["    val GALAXY = listOf("]
    for phi, rf, sz, a in dial.GALAXY:
        lines.append(f"        Star({fnum(phi)}, {fnum(rf)}, {fnum(sz)}, {fnum(a)}),")
    lines.append("    )")
    return "\n".join(lines)


def beast_kt():
    lines = ["    val BEAST: Map<String, List<PathSeg>> = mapOf("]
    for key, segs in dial._BEAST.items():
        items = []
        for seg in segs:
            op = seg[0]
            nums = ", ".join(fnum(v) for v in seg[1:])
            items.append(f'PathSeg("{op}", doubleArrayOf({nums}))')
        lines.append(f'        "{key}" to listOf(')
        for it in items:
            lines.append(f"            {it},")
        lines.append("        ),")
    lines.append("    )")
    return "\n".join(lines)


def parts_table_kt(var_name, table, doc):
    lines = [f"    /** {doc} */", f"    val {var_name}: Map<String, List<Part>> = mapOf("]
    for key, parts in table.items():
        lines.append(f'        "{key}" to listOf(')
        for p in parts:
            k = p[0]
            if k == "c":
                lines.append(f'            Part.Circle({fnum(p[1])}, {fnum(p[2])}, {fnum(p[3])}),')
            elif k == "t":
                lines.append(f'            Part.Triangle({fnum(p[1])}, {fnum(p[2])}, {fnum(p[3])}, {fnum(p[4])}, {fnum(p[5])}, {fnum(p[6])}),')
            elif k == "q":
                nums = ", ".join(fnum(v) for v in p[1:])
                lines.append(f"            Part.Quad(doubleArrayOf({nums})),")
            elif k == "a":
                lines.append(f'            Part.Arc({fnum(p[1])}, {fnum(p[2])}, {fnum(p[3])}, {fnum(p[4])}, {fnum(p[5])}, {fnum(p[6])}),')
            elif k == "l":
                nums = ", ".join(fnum(v) for v in p[1])
                lines.append(f"            Part.Poly(doubleArrayOf({nums}), {fnum(p[2])}),")
            elif k == "b":
                nums = ", ".join(fnum(v) for v in p[1])
                lines.append(f"            Part.Bez(doubleArrayOf({nums}), {fnum(p[2])}),")
            else:
                raise ValueError(k)
        lines.append("        ),")
    lines.append("    )")
    return "\n".join(lines)


header = '''package cn.workbuddy.kepanclock.render

/**
 * 盘面固定数据表（由桌面版 dial.py 逐值导出，生成脚本 docs/archive/gen_dial_data.py）。
 *
 * 星空为主星三等（桌面种子 20261104 的固定序列）与银河弱带暗星：因 java.util.Random
 * 与 Python random 序列不同，必须固化数值表，不能现场生成（方案 §1.3-3）。
 * 生肖剪影（ZODIAC）与飞天水印（FT）为归一化部件拼装表，四象（BEAST）为瓦当线描路径。
 */

/** 星点：(方位角 φ°，径比 rf，显示尺寸 px，透明度 a)。 */
data class Star(val phi: Double, val rf: Double, val sz: Double, val a: Double)

/** 线描路径段：op ∈ M/L/C，rest 为 2 或 6 个坐标。 */
data class PathSeg(val op: String, val rest: DoubleArray)

/** 剪影部件：圆 c / 三角 t / 四边 q / 粗弧 a / 折线 l / 贝塞尔粗线 b。 */
sealed class Part {
    data class Circle(val x: Double, val y: Double, val r: Double) : Part()
    data class Triangle(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x3: Double, val y3: Double) : Part()
    data class Quad(val pts: DoubleArray) : Part()
    data class Arc(val x: Double, val y: Double, val r: Double, val a0: Double, val a1: Double, val w: Double) : Part()
    data class Poly(val pts: DoubleArray, val w: Double) : Part()
    data class Bez(val q: DoubleArray, val w: Double) : Part()
}

object DialData {
'''

footer = '''}
'''

body = "\n".join([
    stars_kt(),
    "",
    galaxy_kt(),
    "",
    beast_kt(),
    "",
    parts_table_kt("ZODIAC", dial._ZODIAC, "生肖剪影部件表（键与桌面 _ZODIAC_ORDER 鼠牛虎兔龙蛇马羊猴鸡狗猪 对齐）。"),
    "",
    parts_table_kt("FT", dial._FT, "飞天水印部件表（键与桌面 _FT_ORDER 子丑寅卯辰巳午未申酉戌亥 对齐）。"),
])

open(OUT, "w").write(header + body + footer)
print("DialData.kt 写出完毕")
import subprocess
print(subprocess.run(["wc", "-l", OUT], capture_output=True, text=True).stdout)
