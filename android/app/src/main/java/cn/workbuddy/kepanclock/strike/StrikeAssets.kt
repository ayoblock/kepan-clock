package cn.workbuddy.kepanclock.strike

import android.content.Context

/**
 * 随包实录声样装载（对齐桌面 `_ensure_sounds` 三级来源的「随包实录」级）：
 * assets/sounds/{bell,drum}.wav（22050 Hz 单声道 16-bit PCM，与桌面
 * assets/sounds 同源同文件）。解析失败返回 null，调用方回退合成兜底。
 */
object StrikeAssets {

    /** 解码结果：PCM 16-bit 单声道 + 采样率。 */
    data class Wav(val pcm: ShortArray, val rate: Int)

    /** 读取并解析 assets 下 16-bit PCM WAV（仅支持单声道，RIFF/`fmt `/`data`）。 */
    fun load(context: Context, path: String): Wav? = runCatching {
        context.assets.open(path).use { input ->
            val bytes = input.readBytes()
            var pos = 12   // 跳过 RIFF 头（12 字节）
            var rate = 0
            var bits = 0
            var channels = 0
            var data: ByteArray? = null
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = readLeInt(bytes, pos + 4)
                when (id) {
                    "fmt " -> {
                        channels = readLeShort(bytes, pos + 10)
                        rate = readLeInt(bytes, pos + 12)
                        bits = readLeShort(bytes, pos + 22)
                    }
                    "data" -> {
                        val n = minOf(size, bytes.size - pos - 8)
                        data = bytes.copyOfRange(pos + 8, pos + 8 + n)
                    }
                }
                pos += 8 + size + (size % 2)
            }
            val raw = data ?: return@runCatching null
            if (bits != 16 || channels != 1 || rate <= 0) return@runCatching null
            val n = raw.size / 2
            val pcm = ShortArray(n)
            for (i in 0 until n) {
                pcm[i] = ((raw[2 * i].toInt() and 0xFF) or (raw[2 * i + 1].toInt() shl 8)).toShort()
            }
            Wav(pcm, rate)
        }
    }.getOrNull()

    private fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readLeShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
