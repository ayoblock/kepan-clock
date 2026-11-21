package cn.workbuddy.kepanclock.strike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalTime

/**
 * 报时触发接收器（§3.5-1）：AlarmManager 到点 → 评估当前一刻 →
 * startForegroundService 播报。前台服务被系统拒绝时降级为通知
 * （§3.5 降级路径：后台自动报时不可保证）。
 *
 * 触发判定：只取当前分钟，无历史补播（桌面 app.py:778–793 同款语义）。
 * 播完/判空后一律重布下一刻，维持唯一触发源。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_EVENT = "event"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val probeEvent = intent.getStringExtra(EXTRA_EVENT)
        val event = probeEvent ?: run {
            val t = LocalTime.now()
            StrikeLogic.eventForMinute(t.hour * 60 + t.minute)
        }

        if (event != null) {
            try {
                context.startForegroundService(StrikeService.intent(context, event))
            } catch (e: Exception) {
                // Android 12+ 后台启动 FGS 被拒（ForegroundServiceStartNotAllowedException 等）
                // → 降级：只发通知（§3.5 降级路径）
                notifyFallback(context, event, e)
            }
        }

        if (intent.action == StrikeScheduler.ACTION_FIRE) {
            StrikeScheduler.scheduleNext(context)
        }
    }

    private fun notifyFallback(context: Context, event: String, cause: Exception) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        if (nm.getNotificationChannel("strike-fallback") == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    "strike-fallback", "报时（通知降级）",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "前台服务启动受限时的报时通知" },
            )
        }
        val label = when (event) {
            "bell3" -> "三声钟"
            "drum2" -> "两声鼓"
            else -> "一声鼓"
        }
        val notif = android.app.Notification.Builder(context, "strike-fallback")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("刻盘钟 · 报时")
            .setContentText("$label（后台播放受限，降级为通知）")
            .setAutoCancel(true)
            .build()
        nm.notify(42, notif)
    }
}
