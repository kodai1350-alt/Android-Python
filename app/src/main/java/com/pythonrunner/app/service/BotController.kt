package com.pythonrunner.app.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.pythonrunner.app.BuildConfig
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.bot.KeyValue
import com.pythonrunner.app.bot.SpikeBotStore
import com.pythonrunner.app.files.BotImporter
import com.pythonrunner.app.files.FolderSync
import com.pythonrunner.app.files.ImportPlan
import com.pythonrunner.app.files.LeftoverFiles
import com.pythonrunner.app.files.SafTree
import com.pythonrunner.app.files.SyncChanges
import com.pythonrunner.app.process.LogBuffer
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.process.ManagedProcess
import com.pythonrunner.app.python.PythonRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where an import stands; the screen shows it and answers its questions. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Working(val percent: Int) : ImportState
    /** Some existing files would be replaced: waiting for 上書きして取り込む. */
    data class Confirm(val plan: ImportPlan) : ImportState
    data class Done(val count: Int, val requirements: String?, val botWasRunning: Boolean) : ImportState
    data class Failed(val message: String) : ImportState
}

/** Shared state between [BotService] and the UI. */
object BotController {
    // Imports run here, not in the screen, so turning or leaving the screen does not stop them.
    private val importScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState
    @Volatile private var importRequirements: String? = null

    /** Shared-copy changes the user chose not to bring in; not offered again by the automatic check. */
    @Volatile var declinedChanges: SyncChanges? = null

    private fun stagingDir(context: Context) = File(context.applicationContext.filesDir, "import-staging")
    private fun backupDir(context: Context) = File(context.applicationContext.filesDir, "import-backup")

    /** A picked file: a ZIP is extracted, anything else (.py, config.json…) is added as it is. */
    fun importFile(context: Context, uri: Uri) = startImport(context, uri) { app, staging ->
        val name = SafTree.displayName(app, uri) ?: "file"
        val open = { app.contentResolver.openInputStream(uri) ?: error("ファイルを開けませんでした") }
        if (name.lowercase().endsWith(".zip")) {
            requireSpace(SafTree.size(app, uri), app.cacheDir)
            val zip = File(app.cacheDir, "import.zip")
            try {
                open().use { input -> zip.outputStream().use { input.copyTo(it) } }
                requireSpace(BotImporter.zipSize(zip), app.filesDir)
                // Android 14+ refuses a whole ZIP over one ".." name; BotImporter drops such names itself.
                if (Build.VERSION.SDK_INT >= 34) dalvik.system.ZipPathValidator.clearCallback()
                BotImporter.stageZip(zip, staging) { _importState.value = ImportState.Working(it) }
            } finally {
                zip.delete()
            }
        } else {
            requireSpace(SafTree.size(app, uri), app.filesDir)
            open().use { BotImporter.stageFile(name, it, staging) }
            1
        }
    }

    /** A picked folder, with everything in it. */
    fun importFolder(context: Context, treeUri: Uri) = startImport(context, treeUri) { app, staging ->
        val files = SafTree.list(app, treeUri).filter { !BotImporter.isSkipped(it.path) }
        requireSpace(files.sumOf { it.size }, app.filesDir)
        BotImporter.stageTree(files.map { it.path to it.open }, staging) { _importState.value = ImportState.Working(it) }
    }

    /** 上書きして取り込む. */
    fun confirmImport(context: Context) {
        val plan = (_importState.value as? ImportState.Confirm)?.plan ?: return
        _importState.value = ImportState.Working(100)
        val app = context.applicationContext
        importScope.launch { runImport(app) { applyImport(app, plan) } }
    }

    /** やめる on the overwrite question: nothing in the bot folder changes. */
    fun cancelImport(context: Context) {
        if (_importState.value !is ImportState.Confirm) return
        stagingDir(context).deleteRecursively()
        _importState.value = ImportState.Idle
    }

    /** The screen has shown the result. */
    fun finishImport() {
        val state = _importState.value
        if (state is ImportState.Done || state is ImportState.Failed) _importState.value = ImportState.Idle
    }

    /** Before the bot starts: same as [recoverImport], but done now, so it never runs on a half import. */
    fun recoverImportNow(context: Context) {
        if (_importState.value != ImportState.Idle) return
        val app = context.applicationContext
        runCatching { BotImporter.recover(store(app).botDir, backupDir(app), stagingDir(app)) }
    }

    /** At app start: undo an import the app did not live to finish. */
    fun recoverImport(context: Context) {
        if (_importState.value != ImportState.Idle) return
        val app = context.applicationContext
        importScope.launch {
            runCatching { BotImporter.recover(store(app).botDir, backupDir(app), stagingDir(app)) }
        }
    }

    private fun startImport(context: Context, picked: Uri, stage: (Context, File) -> Int) {
        val state = _importState.value
        if (state is ImportState.Working || state is ImportState.Confirm) return
        _importState.value = ImportState.Working(0)
        val app = context.applicationContext
        // The picker's grant ends with the activity; hold it so leaving the app does not stop the import.
        val held = runCatching {
            app.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        importScope.launch {
            runImport(app) {
                val staging = stagingDir(app).apply { deleteRecursively() }
                val count = try {
                    stage(app, staging)
                } finally {
                    if (held) {
                        runCatching {
                            app.contentResolver.releasePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                }
                if (count == 0) {
                    staging.deleteRecursively()
                    _importState.value = ImportState.Failed("取り込めるファイルがありませんでした")
                    return@runImport
                }
                val plan = BotImporter.plan(staging, store(app).botDir)
                importRequirements = BotImporter.requirements(staging)
                if (plan.overwrites.isEmpty()) applyImport(app, plan) else _importState.value = ImportState.Confirm(plan)
            }
        }
    }

    private inline fun runImport(app: Context, block: () -> Unit) {
        try {
            block()
        } catch (e: NotEnoughSpace) {
            stagingDir(app).deleteRecursively()
            _importState.value = ImportState.Failed(e.message.orEmpty())
        } catch (e: Exception) {
            stagingDir(app).deleteRecursively()
            _importState.value = ImportState.Failed("取り込めませんでした: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun applyImport(app: Context, plan: ImportPlan) {
        val store = store(app)
        store.botDir.mkdirs()
        BotImporter.applyPlan(plan, stagingDir(app), store.botDir, backupDir(app))
        // The shared copy follows at once, so the automatic check does not offer its old files back.
        runCatching { sync(app)?.exportForced(plan.files) }
        store.mainFile = BotImporter.pickMain(store.botDir, store.mainFile)
        val running = status.value == BotStatus.RUNNING || status.value == BotStatus.STARTING
        _importState.value = ImportState.Done(plan.files.size, importRequirements, running)
    }

    private class NotEnoughSpace(message: String) : Exception(message)

    private fun requireSpace(needed: Long, dir: File) {
        val missing = BotImporter.missingBytes(needed, dir.usableSpace)
        if (missing > 0) {
            val mb = (missing + 1024 * 1024 - 1) / (1024 * 1024)
            throw NotEnoughSpace("空き容量が足りません（あと ${mb}MB 必要です）")
        }
    }

    /** Frees the space of the AI model a test build downloaded (the AI is set aside for now). */
    fun removeLeftoverFiles(context: Context) {
        thread(name = "leftover-cleanup") {
            runCatching { LeftoverFiles.removeAiModels(context.applicationContext.filesDir) }
        }
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

    /** Stops the bot and starts it again, so files changed while it ran are used. */
    fun restart(context: Context) {
        val intent = Intent(context, BotService::class.java).setAction(BotService.ACTION_RESTART)
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

    /** Empties the log shown in "6. ログ" (the bot keeps running). */
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
