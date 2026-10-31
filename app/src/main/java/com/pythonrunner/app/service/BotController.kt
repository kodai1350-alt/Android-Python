package com.pythonrunner.app.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.pythonrunner.app.BuildConfig
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.bot.KeyValue
import com.pythonrunner.app.bot.SpikeBotStore
import com.pythonrunner.app.files.FolderSync
import com.pythonrunner.app.llm.LlmServer
import com.pythonrunner.app.llm.ModelDownloader
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

/** Whether the LLM model files are on the phone. */
sealed interface LlmState {
    data object Missing : LlmState
    data class Downloading(val percent: Int) : LlmState
    data object Ready : LlmState
    data class Failed(val message: String) : LlmState
}

/** Shared state between [BotService] and the UI. */
object BotController {
    private val _llmState = MutableStateFlow<LlmState>(LlmState.Missing)
    val llmState: StateFlow<LlmState> = _llmState
    private val downloading = AtomicBoolean(false)
    @Volatile private var downloadCancelled = false

    fun models(context: Context): ModelDownloader =
        ModelDownloader(File(context.applicationContext.filesDir, "llm-models"))

    fun llmServer(context: Context): LlmServer =
        LlmServer(File(context.applicationContext.applicationInfo.nativeLibraryDir), models(context))

    fun refreshLlmState(context: Context) {
        if (downloading.get()) return
        _llmState.value = if (models(context).isReady()) LlmState.Ready else LlmState.Missing
    }

    fun startModelDownload(context: Context) {
        if (!downloading.compareAndSet(false, true)) return
        downloadCancelled = false
        val models = models(context)
        _llmState.value = LlmState.Downloading(0)
        thread(name = "model-download") {
            try {
                models.download(
                    onProgress = { done, total ->
                        val percent = (done * 100 / total).toInt()
                        if ((_llmState.value as? LlmState.Downloading)?.percent != percent) {
                            _llmState.value = LlmState.Downloading(percent)
                        }
                    },
                    isCancelled = { downloadCancelled },
                )
                _llmState.value = LlmState.Ready
            } catch (e: ModelDownloader.Cancelled) {
                _llmState.value = LlmState.Missing
            } catch (e: Throwable) {
                // Errors too (e.g. a method missing on an old Android): show them instead of crashing the app.
                _llmState.value = LlmState.Failed("ダウンロードに失敗しました: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                downloading.set(false)
            }
        }
    }

    fun cancelModelDownload() {
        downloadCancelled = true
    }

    fun deleteModel(context: Context) {
        if (downloading.get()) return
        models(context).delete()
        _llmState.value = LlmState.Missing
    }

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

    /** The bot always runs from app-private storage, which other apps cannot modify. */
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

    /** The shared copy for the Files app and a PC; null without storage access. */
    fun sync(context: Context): FolderSync? {
        val app = context.applicationContext
        if (!hasSharedStorageAccess(app)) return null
        return FolderSync(store(app).botDir, sharedBotDir(), File(app.filesDir, "bots/spike-shared.manifest"))
    }

    /** Documents/PythonRunner/<bot>, visible to the Files app and a PC over USB. */
    fun sharedBotDir(): File = File(sharedBotsRoot(), BOT_FOLDER_NAME)

    fun sharedBotsRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PythonRunner")

    fun hasSharedStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private const val BOT_FOLDER_NAME = "MyBot"

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
                    // No user secrets here: pip may run arbitrary build backends from PyPI.
                    rt.environment(listOf(store.libsDir), emptyMap()),
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

    /** Empties the log shown in "7. ログ" (the bot keeps running). */
    fun clearLogs() {
        buffer.clear()
        logsDirty.set(false)
        _logs.value = emptyList()
    }

    internal fun appendLog(line: LogLine) {
        buffer.add(line)
        logsDirty.set(true)
    }
}
