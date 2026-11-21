package cn.workbuddy.kepanclock.strike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.HandlerThread
import android.os.IBinder
import cn.workbuddy.kepanclock.render.Palette
import cn.workbuddy.kepanclock.settings.ConfigRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 报时播放（§3.5-2）：短暂前台服务——仅在音频播放期间转前台，
 * 维护串行队列（上一声播完、留 200 ms 再起下一声，不叠发、不预混）；
 * 播完即退前台、stopSelf，**不做永久常驻**。
 *
 * 触发链：AlarmReceiver → startForegroundService(FIRE/PROBE)。
 * 桌面对齐：_StrikeQueue「以单声样播完为事件」→ Android 侧 AudioTrack
 * write() 阻塞写完即视为播完（不依赖硬编码时长）。
 */
class StrikeService : Service() {

    companion object {
        private const val CHANNEL_ID = "strike_fg"
        private const val NOTIF_ID = 41

        const val EXTRA_EVENT = "event"

        fun intent(context: Context, event: String): Intent =
            Intent(context, StrikeService::class.java).putExtra(EXTRA_EVENT, event)
    }

    private val playing = AtomicBoolean(false)
    private var worker: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val event = intent?.getStringExtra(EXTRA_EVENT)
        val seq = event?.let { StrikeLogic.sequence(it) }.orEmpty()
        startAsForeground(event ?: "报时")

        if (seq.isEmpty() || !playing.compareAndSet(false, true)) {
            // 空事件或上一轮未完：不叠发（桌面 _StrikeQueue.fire 同款语义）
            finish()
            return Service.START_NOT_STICKY
        }

        worker = HandlerThread("strike-play").apply { start() }
        android.os.Handler(worker!!.looper).post {
            try {
                for ((i, kind) in seq.withIndex()) {
                    playOne(kind)
                    if (i < seq.size - 1) Thread.sleep((StrikeLogic.GAP_SEC * 1000).toLong())
                }
            } catch (_: InterruptedException) {
                // 服务被杀/中断：直接退出
            } finally {
                playing.set(false)
                finish()
            }
        }
        return Service.START_NOT_STICKY
    }

    /**
     * 单声样：优先随包实录 WAV（assets/sounds/，桌面同源——2026-11-21 用户
     * 反馈「电子音」根因修复：合成音仅桌面兜底，桌面实际播实录声样）；
     * 声样缺失时回退合成。AudioTrack MODE_STREAM，写完 PCM 后**轮询
     * playbackHeadPosition 到帧尾才 stop**（1.4.0 真机实证教训：缓冲恰容
     * 整段 PCM 时 write() 瞬回，提前 stop 会截断未播完的音频）。
     */
    private fun playOne(kind: String) {
        val wav = StrikeAssets.load(this, "sounds/$kind.wav")
        val pcm = wav?.pcm ?: StrikeAudio.synth(kind)
        val rate = wav?.rate ?: StrikeAudio.RATE
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.play()
            track.write(pcm, 0, pcm.size)
            // 等待全部帧实际渲染完（write 只保证拷入缓冲，不代表播完）
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline &&
                track.playbackHeadPosition < pcm.size
            ) {
                Thread.sleep(20)
            }
        } finally {
            track.pause()
            track.flush()
            track.stop()
            track.release()
        }
    }

    private fun startAsForeground(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 旧渠道strike 曾以 DEFAULT+系统预设铃声创建（渠道属性一经创建不可变，
        // 每次报时先响系统铃声盖过声样——2026-11-21 用户反馈根因），删除重建静默渠道
        if (nm.getNotificationChannel("strike") != null) {
            nm.deleteNotificationChannel("strike")
        }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "报时", NotificationManager.IMPORTANCE_NONE).apply {
                    description = "刻盘钟报时播放期间的前台标记（静默）"
                    setSound(null, null)
                },
            )
        }
        // accent 色随盘面生效昼夜主题（朱色两态取自 Palette；auto 态实算 blend）
        val pal = runCatching {
            val cfg = runBlocking { ConfigRepository(this@StrikeService).config.first() }
            val now = java.time.LocalDateTime.now()
            val cs = cn.workbuddy.kepanclock.domain.ClockState.compute(
                date = now.toLocalDate(), hour = now.hour, minute = now.minute,
                second = now.second, micro = 0,
                lat = cfg.lat, lon = cfg.lon, tzMin = 480, dusk = cfg.duskMin,
                theme = cfg.theme,
            )
            if (cs.blend >= 0.5) Palette.night() else Palette.day()
        }.getOrDefault(Palette.day())
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("刻盘钟 · 报时")
            .setContentText(text)
            .setColor(Palette.argb(pal["cinnabar"]))
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        worker?.quitSafely()
        worker = null
        super.onDestroy()
    }
}
