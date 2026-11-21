package cn.workbuddy.kepanclock.strike

/**
 * 报时纯逻辑（无 Android 依赖，单测锁死）。逐条对齐桌面 app.py：
 *
 * - 事件序列 `_EVENTS`（app.py:266–268）：bell3 = 钟×3，drum2 = 鼓×2，drum1 = 鼓×1；
 * - 触发式（app.py:787–793）：对钟表分钟 m，u = (m+60) % 1440，p = u % 120；
 *   p==0 → bell3（时辰初，01:00/03:00/…），p==60 → drum2（时辰正，00:00/02:00/…），
 *   p%15==0 → drum1（每刻）；其余不报；
 * - 不补播（app.py:778–783）：休眠/挂起/时钟回拨只评估当前一刻，历史不追。
 *   Android 侧由 AlarmReceiver 只取当前分钟判定，天然无补播。
 */
object StrikeLogic {

    /** 声间留白（秒），对齐桌面 _STRIKE_GAP = 0.20。 */
    const val GAP_SEC: Double = 0.20

    /** 事件名 -> 单声样序列（对齐桌面 _EVENTS）。 */
    fun sequence(event: String): List<String> = when (event) {
        "bell3" -> listOf("bell", "bell", "bell")
        "drum2" -> listOf("drum", "drum")
        "drum1" -> listOf("drum")
        else -> emptyList()
    }

    /** 当前钟表分钟（0–1439）对应事件；null = 本分无报时。 */
    fun eventForMinute(minOfDay: Int): String? {
        val p = ((minOfDay + 60) % 1440).mod(120)
        return when {
            p == 0 -> "bell3"
            p == 60 -> "drum2"
            p % 15 == 0 -> "drum1"
            else -> null
        }
    }

    /** 下一个一刻（15 分）边界的 epoch 分钟：m=0→15，m=14→15，m=15→30，m=45→60。 */
    fun nextQuarterMinute(epochMin: Long): Long {
        val rem = epochMin.mod(15L)
        return epochMin + if (rem == 0L) 15L else 15L - rem
    }
}
