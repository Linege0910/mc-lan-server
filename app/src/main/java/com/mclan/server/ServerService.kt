package com.mclan.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * 前台服务：让服务端在锁屏后继续运行，并在通知栏提供停止按钮。
 */
class ServerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: Server.JAVA
        when (intent?.action) {
            ACTION_STOP -> {
                if (AppPrefs.rootRunServer()) {
                    val s = Server.byType(intent?.getStringExtra(EXTRA_TYPE) ?: Server.JAVA)
                    Thread({ ServerProbe.killDetached(s) }, "mc-kill").start()
                } else {
                    ServerProcess.current?.stop()
                }
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_COMMAND -> {
                val cmd = intent.getStringExtra(EXTRA_COMMAND)
                if (!cmd.isNullOrBlank()) ServerProcess.current?.send(cmd)
                return START_STICKY
            }
            else -> {
                // 启动过程会调用 su（枚举进程、检查 root），绝不能在主线程做，
                // 否则就是「应用无响应 → 闪退」
                Thread({ startServer(type) }, "mc-start").start()
            }
        }
        return START_STICKY
    }

    private fun startServer(type: String) {
        val server = Server.byType(type)
        // 启动前先把「是否已在运行」的缓存清掉，读一次真实状态
        ServerProbe.invalidate()
        // 已经有一个（可能是上次被杀后残留的）服务端在跑，就别再起一个抢端口
        val runningState = ServerProbe.state(server)
        if (runningState.running) {
            server.log.append("[控制台] 检测到服务端已在运行（pid " + runningState.pid + "），本次启动已取消")
            startAsForeground(server, if (runningState.attached) "已在运行" else "后台运行中")
            return
        }
        ensureChannel()
        startAsForeground(server, "正在启动…")
        acquireWakeLock()
        val proc = ServerProcess(server) { state -> updateNotification(server, state) }
        ServerProcess.current = proc
        if (!proc.start()) {
            updateNotification(server, "启动失败，请检查 JDK 设置")
        }
    }

    private fun startAsForeground(server: Server, text: String) {
        startForeground(NOTIF_ID, buildNotification(server, text))
    }

    private fun updateNotification(server: Server, state: String) {
        val text = when (state) {
            "starting" -> "正在启动…"
            "stopped" -> "已停止"
            else -> state
        }
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(server, text))
        } catch (t: Throwable) {
        }
        if (state == "stopped") {
            releaseWakeLock()
        }
    }

    private fun buildNotification(server: Server, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val addr = ProcessUtils.lanAddress() ?: "局域网"
        return builder
            .setContentTitle("Minecraft 服务器运行中")
            .setContentText(addr + ":" + server.port() + " · " + text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "服务器运行状态", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    /**
     * 同时拿 CPU 唤醒锁与 Wi-Fi 高性能锁：熄屏后 CPU 不休眠、Wi-Fi 不掉速。
     * 注意 WakeLock 不能对抗 Doze，还需要「忽略电池优化」白名单（见 KeepAlive）。
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MCLanServer:server").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (t: Throwable) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MCLanServer:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
        }
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
        }
        wifiLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "mc_server"
        const val NOTIF_ID = 1001
        const val EXTRA_TYPE = "type"
        const val ACTION_STOP = "com.mclan.server.STOP"
        const val ACTION_COMMAND = "com.mclan.server.COMMAND"
        const val EXTRA_COMMAND = "command"

        fun start(ctx: Context, type: String) {
            val i = Intent(ctx, ServerService::class.java).putExtra(EXTRA_TYPE, type)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).setAction(ACTION_STOP))
        }

        fun command(ctx: Context, cmd: String) {
            ctx.startService(
                Intent(ctx, ServerService::class.java).setAction(ACTION_COMMAND).putExtra(EXTRA_COMMAND, cmd)
            )
        }
    }
}
