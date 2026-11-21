package cn.workbuddy.kepanclock.strike

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 报时调度（AlarmManager，唯一触发源，§3.5-1）：
 * `setExactAndAllowWhileIdle` 对齐下一刻（15 分）边界；Android 12+ 需
 * SCHEDULE_EXACT_ALARM，无权限（canScheduleExactAlarms()==false）时
 * 降级 `setAndAllowWhileIdle`（系统可能合并到 ~15 分钟窗口，产品文档写明）。
 */
object StrikeScheduler {

    /** 到点触发报时（只评估当前一刻，不补播）。 */
    const val ACTION_FIRE = "cn.workbuddy.kepanclock.strike.FIRE"

    /** 试钟探针：强制播一声（开启报时后 40 s 自证「闹钟→前台服务」链路）。 */
    const val ACTION_PROBE = "cn.workbuddy.kepanclock.strike.PROBE"

    private const val REQ_FIRE = 1
    private const val REQ_PROBE = 2

    fun firePending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQ_FIRE,
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun probePending(context: Context, event: String): PendingIntent = PendingIntent.getBroadcast(
        context, REQ_PROBE,
        Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_PROBE).putExtra(AlarmReceiver.EXTRA_EVENT, event),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 对齐下一刻边界精确布钟。 */
    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val atMs = StrikeLogic.nextQuarterMinute(System.currentTimeMillis() / 60000) * 60000
        setExactCompat(am, context, atMs, firePending(context))
    }

    /** 一次性试钟（now+delayMs），验证「闹钟→后台起前台服务」链路。 */
    fun scheduleProbe(context: Context, delayMs: Long, event: String = "drum1") {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        setExactCompat(am, context, System.currentTimeMillis() + delayMs, probePending(context, event))
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePending(context))
        am.cancel(probePending(context, ""))
    }

    private fun setExactCompat(am: AlarmManager, context: Context, atMs: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            // 降级：非精确闹钟（§3.5-1 约束，产品文档写明「不保证分钟级准点」）
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }
}
