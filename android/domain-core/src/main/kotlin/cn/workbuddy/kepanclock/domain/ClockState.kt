package cn.workbuddy.kepanclock.domain

import java.time.LocalDate

/**
 * 时钟状态聚合（移植自桌面 src/app.py compute_state 的历算部分）。
 * 桌面版的 cfg 读取与 CLI 覆盖由 app 层承担；此处只做纯计算，
 * theme 与 forceNight 作为参数传入（theme: auto/day/night）。
 */
data class ClockState(
    val tMin: Double,        // 当日分钟数（含秒小数）
    val read: String,        // 如「午正一刻」
    val geng: String,        // 如「三更两点」/「昼」
    val term: String,        // 当前节气名
    val date: LocalDate,     // 公历日
    val note: String,        // 一行历注（推算失败为空串）
    val parts: Lunar.DayParts?, // 日辰零件（推算失败为 null）
    val dateStr: String,     // 如「2026年11月21日」
    val hhmm: String,        // 如「14:30」
    val tsRead: String,      // 真太阳时的时刻读法
    val animal: String,      // 当前辰生肖
    val night: Boolean,      // 是否夜主题
    val blend: Double,       // 昼夜过渡系数 0–1
) {
    companion object {
        const val CROSSFADE_S = 30.0   // 昼夜过渡各三十秒

        private fun modFloor(a: Double, b: Double): Double = a - b * Math.floor(a / b)

        /**
         * @param forceNight CLI 式强制（null = 不强制，按 theme 与日出日落判定）
         */
        fun compute(
            date: LocalDate,
            hour: Int,
            minute: Int,
            second: Int,
            micro: Int,
            lat: Double,
            lon: Double,
            tzMin: Int,
            dusk: Double,
            theme: String = "auto",
            forceNight: Boolean? = null,
        ): ClockState {
            val tMin = hour * 60 + minute + second / 60.0 + micro / 6e7

            val (rise, sett) = SunMath.sunTimesLocal(date, lat, lon, tzMin)
            val span = SunMath.nightSpan(tMin, lat, lon, tzMin, date, dusk)

            // 昼夜优先级：forceNight > theme 锁定 > 日出日落。
            val locked = theme == "day" || theme == "night"
            val night = forceNight
                ?: when (theme) {
                    "day" -> false
                    "night" -> true
                    else -> SunMath.isNight(tMin, rise, sett)   // auto 或未知值
                }

            // 昼夜渐变：仅 auto 时，距日出或日落三十秒内线性过渡；锁定主题不叠加。
            var blend = if (night) 1.0 else 0.0
            if (!locked && rise != null && sett != null) {
                val winMin = CROSSFADE_S / 60.0
                for ((boundary, goingNight) in listOf(sett to true, rise to false)) {
                    // 与 Python ((t - b + 720) % 1440) - 720 同义（结果落 [-720, 720)）
                    val signed = modFloor(tMin - boundary + 720.0, 1440.0) - 720.0
                    if (Math.abs(signed) < winMin) {
                        blend = if (goingNight) {
                            0.5 + signed / (2.0 * winMin)
                        } else {
                            0.5 - signed / (2.0 * winMin)
                        }.coerceIn(0.0, 1.0)
                    }
                }
            }

            val ts = SunMath.trueSolarMinutes(tMin, date.dayOfYear, lon, tzMin)
            return ClockState(
                tMin = tMin,
                read = ChenCore.readStr(tMin.toInt()),
                geng = SunMath.gengStr(tMin, span),
                term = SunMath.termName(date.year, date.monthValue, date.dayOfMonth, hour, minute, tzMin),
                date = date,
                note = runCatching { Lunar.note(date) }.getOrDefault(""),
                parts = runCatching { Lunar.parts(date) }.getOrNull(),
                dateStr = "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                hhmm = "%02d:%02d".format(hour, minute),
                tsRead = ChenCore.readStr(Math.floorMod(ts.toInt(), 1440)),
                animal = ChenCore.animal(tMin.toInt()),
                night = night,
                blend = blend,
            )
        }
    }
}
