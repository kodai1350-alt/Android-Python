package com.pythonrunner.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pythonrunner.app.MainActivity
import com.pythonrunner.app.bot.BotRunner
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.process.ManagedProcess
import java.io.File
import kotlin.concurrent.thread

/** Keeps the bot process alive with the screen off: foreground notification plus a partial wake lock. */
class BotService : Service() {
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    private val runner by lazy {
        val store = BotController.store(this)
        val rt = BotController.runtime(this)
        BotRunner(
            prepare = { rt.ensureInstalled() },
            createProcess = { onLine, onExit ->
                val main = store.mainFile ?: error("最初に実行するファイルが選ばれていません")
                ManagedProcess(
                    rt.command(File(store.botDir, main).path),
                    rt.environment(listOf(store.libsDir), store.envVars),
                    store.botDir, onLine, onExit,
                )
            },
            listener = object : BotRunner.Listener {
                override fun onStatus(status: BotStatus, sinceMillis: Long?) {
                    if (status == BotStatus.RUNNING && sinceMillis != null) {
                        BotController.setRunning(sinceMillis)
                    } else {
                        BotController.setStatus(status)
                    }
                }

                override fun onLine(line: LogLine) = BotController.appendLog(line)

                override fun onFinished(status: BotStatus, exitCode: Int) = onBotFinished(status, exitCode)
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBot()
                return START_NOT_STICKY
            }
            ACTION_START -> startBot()
            // Restarted by the system after being killed.
            null -> if (BotController.store(this).wasRunning) {
                startBot()
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (runner.isActive) thread(name = "bot-stop") { runner.stop() }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startBot() {
        // Always go foreground: startForegroundService() requires it even when already running.
        goForeground()
        if (!runner.start()) return
        acquireWakeLock()
        BotController.store(this).wasRunning = true
    }

    private fun stopBot() {
        BotController.store(this).wasRunning = false
        if (runner.isActive) {
            thread(name = "bot-stop") { runner.stop() }
        } else {
            BotController.setStatus(BotStatus.STOPPED)
            stopSelf()
        }
    }

    private fun onBotFinished(status: BotStatus, exitCode: Int) {
        BotController.appendLog(
            LogLine(System.currentTimeMillis(), "終了しました（終了コード $exitCode）", status == BotStatus.CRASHED),
        )
        if (status == BotStatus.CRASHED) notifyCrash(exitCode)
        // A new start may already be under way; leave its wake lock and service alone.
        if (runner.isActive) return
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground() {
        createChannels()
        val notification = NotificationCompat.Builder(this, CHANNEL_BOTS)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Python Runner")
            .setContentText("Botが稼働中")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_RUNNING, notification, type)
    }

    private fun notifyCrash(code: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Botがエラーで停止しました")
            .setContentText("終了コード $code")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_CRASH, notification)
    }

    @Synchronized
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PythonRunner:bot")
            .apply { acquire() }
    }

    @Synchronized
    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_BOTS, "稼働中のBot", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Botのエラー", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_START = "com.pythonrunner.app.START"
        const val ACTION_STOP = "com.pythonrunner.app.STOP"
        private const val CHANNEL_BOTS = "bots"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIFICATION_RUNNING = 1
        private const val NOTIFICATION_CRASH = 2
    }
}
