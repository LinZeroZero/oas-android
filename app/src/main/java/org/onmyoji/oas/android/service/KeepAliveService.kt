package org.onmyoji.oas.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.onmyoji.oas.android.MainActivity
import org.onmyoji.oas.android.R

/**
 * 最小「保活」前台服务（阶段 2 / P1-1）。
 *
 * 职责边界（用户确认过）：
 *  - 只负责 ① 把 app 进程提升到「前台服务」态，绕开 Android 14 的 cached-app freezer；
 *    ② 持有 PARTIAL_WAKE_LOCK（息屏时 CPU 不休眠）；
 *    ③ 挂一条常驻通知。
 *  - **不管** 虚拟屏 daemon 的生命周期、**不管** 建屏/销毁（下一轮再收敛）。
 *
 * 纪律（用户硬要求）：
 *  - 不自启：没有 BootReceiver / JobScheduler / AlarmManager，连 BOOT_COMPLETED 权限都不申请；
 *  - `START_NOT_STICKY`：被系统杀掉不自动重启；
 *  - 退出即收：Activity 正常退出、通知「停止」、无头 `--ei cleanup 1` 都会把它收掉。
 */
class KeepAliveService : Service() {

    companion object {
        const val ACTION_START = "org.onmyoji.oas.android.action.KEEPALIVE_START"
        const val ACTION_STOP = "org.onmyoji.oas.android.action.KEEPALIVE_STOP"

        private const val CHANNEL_ID = "oas_keepalive"
        private const val NOTIF_ID = 1001

        @Volatile
        var running = false
            private set

        /** 服务在何时起来的（elapsedRealtime），0 = 没起。 */
        @Volatile
        var startedAtElapsed = 0L
            private set

        @Volatile
        var wakeLockHeld = false
            private set

        /** 最近一次外部写入的提示（验证器/UI 用来显示进度）。 */
        @Volatile
        var note = ""
            private set

        /** 起服务；返回 null 表示成功，否则错误描述。 */
        fun start(context: Context): String? {
            val intent = Intent(context, KeepAliveService::class.java).setAction(ACTION_START)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                null
            } catch (t: Throwable) {
                "前台服务启动失败: ${t.message ?: t}"
            }
        }

        /** 停服务（先发 STOP action 让它自己收拾，再 stopService 兜底）。 */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP))
            }
            runCatching {
                context.stopService(Intent(context, KeepAliveService::class.java))
            }
        }

        fun updateNote(text: String) {
            note = text
        }

        fun statusLine(): String {
            if (!running) return "前台服务：未运行"
            val up = (SystemClock.elapsedRealtime() - startedAtElapsed) / 1000
            val wl = if (wakeLockHeld) "WakeLock=PARTIAL" else "WakeLock=未持有"
            return "前台服务：运行中 ${up}s，$wl" + if (note.isNotBlank()) "，$note" else ""
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var ticker: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 前台服务必须在 5 秒内把通知挂上，否则 ANR/崩溃
        startForegroundSafely()
        acquireWakeLock()
        running = true
        if (startedAtElapsed == 0L) startedAtElapsed = SystemClock.elapsedRealtime()
        startTicker()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        startedAtElapsed = 0L
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        releaseWakeLock()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ //

    private fun startForegroundSafely() {
        val notification = buildNotification()
        try {
            startForeground(NOTIF_ID, notification)
        } catch (t: Throwable) {
            // 通知权限被拒 / 类型未声明：这里不能崩，降级成「普通服务」并记录
            note = "startForeground 失败: ${t.message ?: t}"
        }
    }

    private fun startTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!running) return
                runCatching {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, buildNotification())
                }
                handler.postDelayed(this, 5_000)
            }
        }
        ticker = r
        handler.postDelayed(r, 5_000)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val up = if (startedAtElapsed == 0L) 0 else (SystemClock.elapsedRealtime() - startedAtElapsed) / 1000
        val wl = if (wakeLockHeld) "·CPU 保持唤醒" else ""
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("OAS 后台运行中")
            .setContentText("已运行 ${up}s$wl${if (note.isNotBlank()) " · $note" else ""}")
            .setSmallIcon(R.drawable.ic_stat_oas)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(null, "停止", stop).build(),
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台运行",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "脚本在后台运行时显示；关闭会中断自动化"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLockHeld) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oas:keepalive").apply {
                setReferenceCounted(false)
                acquire()
            }
            wakeLockHeld = true
        }.onFailure { note = "WakeLock 申请失败: ${it.message}" }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
        wakeLockHeld = false
    }
}
