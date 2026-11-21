package cn.workbuddy.kepanclock.render

/**
 * 昼夜调色板（移植自桌面 dial.py DAY/NIGHT/lerp_pal/_mul）。
 * 分量与桌面逐值一致（0–1 浮点），渲染时转 ARGB int。
 */
data class Palette(
    val bg: DoubleArray,
    val ink: DoubleArray,
    val faint: DoubleArray,
    val hair: DoubleArray,
    val cinnabar: DoubleArray,
    val gold: DoubleArray,
    val star: DoubleArray,
    val needle: DoubleArray,
) {
    operator fun get(key: String): DoubleArray = when (key) {
        "bg" -> bg
        "ink" -> ink
        "faint" -> faint
        "hair" -> hair
        "cinnabar" -> cinnabar
        "gold" -> gold
        "star" -> star
        "needle" -> needle
        else -> throw IllegalArgumentException(key)
    }

    companion object {
        private val DAY = mapOf(
            "bg" to doubleArrayOf(0.969, 0.957, 0.918), "ink" to doubleArrayOf(0.165, 0.161, 0.149),
            "faint" to doubleArrayOf(0.44, 0.43, 0.40), "hair" to doubleArrayOf(0.66, 0.65, 0.61),
            "cinnabar" to doubleArrayOf(0.651, 0.227, 0.169), "gold" to doubleArrayOf(0.52, 0.40, 0.20),
            "star" to doubleArrayOf(0.60, 0.585, 0.55), "needle" to doubleArrayOf(0.165, 0.161, 0.149),
        )

        private val NIGHT = mapOf(
            "bg" to doubleArrayOf(0.104, 0.100, 0.088), "ink" to doubleArrayOf(0.85, 0.79, 0.64),
            "faint" to doubleArrayOf(0.50, 0.46, 0.37), "hair" to doubleArrayOf(0.30, 0.28, 0.23),
            "cinnabar" to doubleArrayOf(0.80, 0.32, 0.24), "gold" to doubleArrayOf(0.79, 0.66, 0.42),
            "star" to doubleArrayOf(0.52, 0.47, 0.36), "needle" to doubleArrayOf(0.85, 0.72, 0.45),
        )

        fun day(): Palette = fromMap(DAY)
        fun night(): Palette = fromMap(NIGHT)

        /** 两套配色线性插值，f∈[0,1]，f=0 为昼。 */
        fun lerp(a: Palette, b: Palette, f: Double): Palette {
            fun mix(x: DoubleArray, y: DoubleArray) =
                DoubleArray(3) { i -> x[i] + (y[i] - x[i]) * f }
            return Palette(
                bg = mix(a.bg, b.bg), ink = mix(a.ink, b.ink),
                faint = mix(a.faint, b.faint), hair = mix(a.hair, b.hair),
                cinnabar = mix(a.cinnabar, b.cinnabar), gold = mix(a.gold, b.gold),
                star = mix(a.star, b.star), needle = mix(a.needle, b.needle),
            )
        }

        /** 桌面 _mul：分量乘系数并钳到 1.0。 */
        fun mul(c: DoubleArray, factor: Double): DoubleArray =
            DoubleArray(3) { i -> minOf(1.0, c[i] * factor) }

        /** 0–1 浮点 RGB → ARGB int。 */
        fun argb(c: DoubleArray, alpha: Double = 1.0): Int {
            fun ch(v: Double): Int = Math.round(v.coerceIn(0.0, 1.0) * 255.0).toInt()
            return (Math.round(alpha.coerceIn(0.0, 1.0) * 255.0).toInt() shl 24) or
                (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
        }

        private fun fromMap(m: Map<String, DoubleArray>) = Palette(
            bg = m["bg"]!!, ink = m["ink"]!!, faint = m["faint"]!!, hair = m["hair"]!!,
            cinnabar = m["cinnabar"]!!, gold = m["gold"]!!, star = m["star"]!!, needle = m["needle"]!!,
        )
    }
}
