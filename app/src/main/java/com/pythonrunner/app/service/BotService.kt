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
import com.pythonrunner.app.bot.BotFleet
import com.pythonrunner.app.bot.BotRunner
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.process.ManagedProcess
import java.io.File
import kotlin.concurrent.thread

/**
 * Keeps every running bot alive with the screen off: one foreground notification and a partial wake
 * lock while at least one bot is active. Each bot is its own process, started and stopped by id.
 */
class BotService : Service() {
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    private val fleet by lazy {
        val rt = BotController.runtime(this)
        BotFleet { id ->
            BotRunner(
                prepare = {
                    // An import the app did not live to finish is undone first, never run half-applied.
                    BotController.recoverImportNow(this, id)
                    rt.ensureInstalled()
                },
                createProcess = { onLine, onExit ->
                    val store = BotController.store(this, id)
                    val main = store.mainFile ?: error("最初に実行するファイルが選ばれていません")
                    ManagedProcess(
                        rt.command(File(store.codeDir, main).path),
                        rt.environment(listOf(store.libsDir), store.envVars),
                        store.codeDir, onLine, onExit,
                    )
                },
                listener = object : BotRunner.Listener {
                    override fun onStatus(status: BotStatus, sinceMillis: Long?) {
                        if (status == BotStatus.RUNNING && sinceMillis != null) {
                            BotController.setRunning(id, sinceMillis)
                        } else {
                            BotController.setStatus(id, status)
                        }
                    }

                    override fun onLine(line: LogLine) = BotController.appendLog(id, line)

                    override fun onFinished(status: BotStatus, exitCode: Int) = onBotFinished(id, status, exitCode)
                },
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        BotController.ensureReady(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_BOT_ID)
        when (intent?.action) {
            ACTION_STOP -> {
                if (id != null) stopBot(id)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                goForeground()
                if (id != null) startBot(id)
                stopIfIdle()
            }
            ACTION_RESTART -> {
                goForeground()
                if (id != null) restartBot(id)
                stopIfIdle()
            }
            // Restarted by the system after being killed: bring back the bots that were running.
            null -> {
                val running = BotController.registry(this).all().filter { BotController.store(this, it.id).wasRunning }
                if (running.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                goForeground()
                running.forEach { startBot(it.id) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val active = fleet.activeIds()
        if (active.isNotEmpty()) thread(name = "bot-stop") { active.forEach { fleet.stop(it) } }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startBot(id: String) {
        // A bot deleted meanwhile is not started.
        if (BotController.registry(this).get(id) == null) return
        if (!fleet.start(id)) return
        acquireWakeLock()
        BotController.store(this, id).wasRunning = true
        updateNotification()
    }

    private fun restartBot(id: String) {
        if (fleet.requestRestart(id)) {
            thread(name = "bot-restart") { fleet.stopForRestart(id) }
        } else {
            startBot(id)
        }
    }

    private fun stopBot(id: String) {
        BotController.store(this, id).wasRunning = false
        if (fleet.isActive(id)) {
            thread(name = "bot-stop") { fleet.stop(id) }
        } else {
            fleet.takeRestart(id)
            BotController.setStatus(id, BotStatus.STOPPED)
            stopIfIdle()
        }
    }

    private fun onBotFinished(id: String, status: BotStatus, exitCode: Int) {
        BotController.appendLog(
            id, LogLine(System.currentTimeMillis(), "終了しました（終了コード $exitCode）", status == BotStatus.CRASHED),
        )
        if (status == BotStatus.CRASHED) notifyCrash(id, exitCode)
        // Show what the bot wrote (data files etc.) in the shared copy too.
        thread(name = "bot-export") { runCatching { BotController.sync(this, id)?.export() } }
        if (fleet.takeRestart(id)) {
            BotController.appendLog(id, LogLine(System.currentTimeMillis(), "再起動します", false))
            startBot(id)
            return
        }
        updateNotification()
        stopIfIdle()
    }

    /** With no bot left running, the notification, the wake lock and the service go. */
    private fun stopIfIdle() {
        if (fleet.activeIds().isNotEmpty()) return
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runningNotification() =
        NotificationCompat.Builder(this, CHANNEL_BOTS)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Python Runner")
            .setContentText("${fleet.activeIds().size.coerceAtLeast(1)}個のBotが稼働中")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun goForeground() {
        createChannels()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_RUNNING, runningNotification(), type)
    }

    private fun updateNotification() {
        if (fleet.activeIds().isEmpty()) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_RUNNING, runningNotification())
    }

    private fun notifyCrash(id: String, code: Int) {
        val name = BotController.name(this, id) ?: "Bot"
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("『$name』がエラーで停止しました")
            .setContentText("終了コード $code")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(crashNotificationId(id), notification)
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
        const val ACTION_RESTART = "com.pythonrunner.app.RESTART"
        const val EXTRA_BOT_ID = "com.pythonrunner.app.BOT_ID"
        private const val CHANNEL_BOTS = "bots"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIFICATION_RUNNING = 1

        /** One crash notification per bot, so one bot's error never replaces another's. */
        fun crashNotificationId(botId: String): Int = 1000 + (botId.hashCode() and 0x7fff)
    }
}
