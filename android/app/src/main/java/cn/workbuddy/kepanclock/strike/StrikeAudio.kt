package cn.workbuddy.kepanclock.strike

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * 报时声样合成（纯 Kotlin，无 Android 依赖，单测锁死）。
 *
 * **实录声样（2026-11-21 用户拍板，根因修复）**：桌面版实际播放的是
 * `_ensure_sounds` 的「随包实录」bell.wav（3.00 s，CC0）与 drum.wav
 * （1.50 s，CC BY-SA 3.0，Clngre）——合成音仅为桌面无包内声样时的兜底。
 * Android 端此前直接走合成路径，导致「钟声变电子音」。现随包同款实录
 * WAV（assets/sounds/），StrikeAssets 解码 → AudioTrack 播放；本文件的
 * 合成仅保留为声样缺失时的兜底（对齐桌面三级来源次序）。
 *
 * 合成（兜底路径）：bell 逐值对齐桌面 `_synth_strike`（app.py:285–299）
 * ——0.45 s 三分音衰减叠加（660/1320/1980 Hz）；drum 为手机端音高适配
 * （320→130 Hz 扫频 + 2.4 倍频泛音 0.30，桌面 150→62 Hz 手机扬声器
 * 物理不可闻）。峰值归一后 ×31000（对齐 `_write_wav`）。
 */
object StrikeAudio {

    const val RATE: Int = 22050

    /** 随包实录声样的时长（秒）：与桌面 assets/sounds 同源同规格。 */
    const val BELL_REAL_SEC: Double = 3.00
    const val DRUM_REAL_SEC: Double = 1.50

    /** 合成单声样（峰值归一）。 */
    fun synth(kind: String): ShortArray {
        val n = (RATE * (if (kind == "bell") 0.45 else 0.4)).toInt()
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            out[i] = if (kind == "bell") {
                exp(-3.2 * t) * sin(2 * PI * 660 * t) +
                    0.5 * exp(-4.5 * t) * sin(2 * PI * 1320 * t) +
                    0.22 * exp(-6.0 * t) * sin(2 * PI * 1980 * t)
            } else {
                // 手机端音高适配：320→130 Hz 扫频 + 2.4 倍频泛音（桌面为 150→62 Hz）
                val f = 320.0 * exp(-6 * t) + 130.0
                0.9 * exp(-7.0 * t) * sin(2 * PI * f * t) +
                    0.30 * exp(-9.0 * t) * sin(2 * PI * 2.4 * f * t)
            }
        }
        val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-6)
        return ShortArray(n) { i -> (out[i] / peak * 31000.0).toInt().toShort() }
    }

    /** 串行播放序列的 PCM 总长度估算（秒）：Σ单声样时长 + 声间留白×(n-1)。
     *  时长按随包实录声样计（bell 3.0 s / drum 1.5 s，对齐桌面实际听感）。 */
    fun sequenceDurationSec(event: String): Double {
        val seq = StrikeLogic.sequence(event)
        if (seq.isEmpty()) return 0.0
        val per = if (seq[0] == "bell") BELL_REAL_SEC else DRUM_REAL_SEC
        return seq.size * per + StrikeLogic.GAP_SEC * (seq.size - 1)
    }
}
