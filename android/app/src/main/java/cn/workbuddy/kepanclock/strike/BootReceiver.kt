package cn.workbuddy.kepanclock.strike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.workbuddy.kepanclock.settings.ConfigRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** 开机重布报时闹钟（对齐桌面：配置开着才布）。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = runBlocking {
            runCatching { ConfigRepository(context).config.first().soundEnabled }
                .getOrDefault(false)
        }
        if (enabled) StrikeScheduler.scheduleNext(context)
    }
}
