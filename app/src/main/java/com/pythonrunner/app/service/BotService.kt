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
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.bot.statusForExit
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.process.ManagedProcess
import java.io.File
import kotlin.concurrent.thread

/** Keeps the bot process alive with the screen off: foreground notification plus a partial wake lock. */
class BotService : Service() {
    @Volatile private var process: ManagedProcess? = null
    @Volatile private var stoppedByUser = false
    private var wakeLock: PowerManager.WakeLock? = null

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

    private fun startBot() {
        if (process?.isRunning == true) return
        goForeground()
        acquireWakeLock()
        stoppedByUser = false
        val store = BotController.store(this)
        val rt = BotController.runtime(this)
        store.wasRunning = true
        BotController.setStatus(BotStatus.STARTING)
        thread(name = "bot-start") {
            try {
                rt.ensureInstalled()
                val main = store.mainFile ?: error("最初に実行するファイルが選ばれていません")
                val p = ManagedProcess(
                    rt.command(File(store.botDir, main).path),
                    rt.environment(listOf(store.libsDir), store.envVars),
                    store.botDir,
                    BotController::appendLog,
                    ::onExit,
                )
                process = p
                p.start()
                BotController.setRunning(System.currentTimeMillis())
            } catch (e: Exception) {
                BotController.appendLog(LogLine(System.currentTimeMillis(), "起動できませんでした: ${e.message}", true))
                onExit(-1)
            }
        }
    }

    private fun stopBot() {
        stoppedByUser = true
        BotController.store(this).wasRunning = false
        val p = process
        if (p == null) {
            BotController.setStatus(BotStatus.STOPPED)
            stopSelf()
        } else {
            thread(name = "bot-stop") { p.stop() }
        }
    }

    private fun onExit(code: Int) {
        val status = statusForExit(code, stoppedByUser)
        BotController.appendLog(
            LogLine(System.currentTimeMillis(), "終了しました（終了コード $code）", status == BotStatus.CRASHED),
        )
        BotController.setStatus(status)
        if (status == BotStatus.CRASHED) notifyCrash(code)
        process = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PythonRunner:bot")
            .apply { acquire() }
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
