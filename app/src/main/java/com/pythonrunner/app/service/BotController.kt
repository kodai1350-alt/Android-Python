package com.pythonrunner.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.pythonrunner.app.BuildConfig
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.bot.KeyValue
import com.pythonrunner.app.bot.SpikeBotStore
import com.pythonrunner.app.process.LogBuffer
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.process.ManagedProcess
import com.pythonrunner.app.python.PythonRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Shared state between [BotService] and the UI. */
object BotController {
    private val _status = MutableStateFlow(BotStatus.STOPPED)
    private val _startedAtMillis = MutableStateFlow<Long?>(null)
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val status: StateFlow<BotStatus> = _status
    val startedAtMillis: StateFlow<Long?> = _startedAtMillis
    val logs: StateFlow<List<LogLine>> = _logs

    private val buffer = LogBuffer()
    private val logsDirty = AtomicBoolean(false)

    init {
        // Publish log snapshots at most four times a second.
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                if (logsDirty.getAndSet(false)) _logs.value = buffer.snapshot()
                delay(250)
            }
        }
    }

    @Volatile private var runtime: PythonRuntime? = null

    fun runtime(context: Context): PythonRuntime = runtime ?: synchronized(this) {
        runtime ?: context.applicationContext.let { app ->
            PythonRuntime(
                baseDir = app.filesDir,
                nativeLibDir = File(app.applicationInfo.nativeLibraryDir),
                tmpDir = app.cacheDir,
                version = "${BuildConfig.PYTHON_VERSION}-${BuildConfig.VERSION_CODE}",
                openAsset = app.assets::open,
            )
        }.also { runtime = it }
    }

    fun store(context: Context): SpikeBotStore {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("spike", Context.MODE_PRIVATE)
        return SpikeBotStore(app.filesDir, object : KeyValue {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String?) {
                prefs.edit().putString(key, value).apply()
            }
        })
    }

    fun start(context: Context) {
        val intent = Intent(context, BotService::class.java).setAction(BotService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, BotService::class.java).setAction(BotService.ACTION_STOP))
    }

    /** Runs Python once without the service (pip, self-check). */
    fun runOneShot(context: Context, args: List<String>, onLine: (LogLine) -> Unit, onExit: (Int) -> Unit) {
        val rt = runtime(context)
        val store = store(context)
        thread(name = "python-oneshot") {
            try {
                rt.ensureInstalled()
                store.botDir.mkdirs()
                ManagedProcess(
                    rt.command(*args.toTypedArray()),
                    rt.environment(listOf(store.libsDir), store.envVars),
                    store.botDir, onLine, onExit,
                ).start()
            } catch (e: Exception) {
                onLine(LogLine(System.currentTimeMillis(), "実行できませんでした: ${e.message}", true))
                onExit(-1)
            }
        }
    }

    internal fun setStatus(status: BotStatus) {
        _status.value = status
        if (status != BotStatus.RUNNING) _startedAtMillis.value = null
    }

    internal fun setRunning(sinceMillis: Long) {
        _startedAtMillis.value = sinceMillis
        _status.value = BotStatus.RUNNING
    }

    internal fun appendLog(line: LogLine) {
        buffer.add(line)
        logsDirty.set(true)
    }
}
