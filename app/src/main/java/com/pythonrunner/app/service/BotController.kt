package com.pythonrunner.app.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.pythonrunner.app.BuildConfig
import com.pythonrunner.app.bot.BotEntry
import com.pythonrunner.app.bot.BotNames
import com.pythonrunner.app.bot.BotRegistry
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.bot.BotStore
import com.pythonrunner.app.bot.DotEnv
import com.pythonrunner.app.bot.EnvMigration
import com.pythonrunner.app.bot.KeyValue
import com.pythonrunner.app.bot.NameCheck
import com.pythonrunner.app.bot.SpikeMigration
import com.pythonrunner.app.files.BotImporter
import com.pythonrunner.app.files.BotSpace
import com.pythonrunner.app.files.Clash
import com.pythonrunner.app.files.FileOp
import com.pythonrunner.app.files.FileOps
import com.pythonrunner.app.files.Item
import com.pythonrunner.app.files.Move
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.pythonrunner.app.files.TrashEntry
import com.pythonrunner.app.files.UndoHistory
import com.pythonrunner.app.files.FileNames
import com.pythonrunner.app.files.Resolution
import com.pythonrunner.app.files.Trash
import com.pythonrunner.app.files.FolderSync
import com.pythonrunner.app.files.ImportPlan
import com.pythonrunner.app.files.LeftoverFiles
import com.pythonrunner.app.files.SafTree
import com.pythonrunner.app.files.SharedFolders
import com.pythonrunner.app.files.SharedState
import com.pythonrunner.app.files.SyncChanges
import com.pythonrunner.app.process.LogBuffer
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.process.ManagedProcess
import com.pythonrunner.app.python.BotParts
import com.pythonrunner.app.python.NodeRuntime
import com.pythonrunner.app.python.PipCommands
import com.pythonrunner.app.python.PythonRuntime
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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
    /**
     * The import needs an answer first. [wholeBot]: 「Botをまるごと入れ替える」 (same names are replaced, the
     * files the new ones lack are asked about); otherwise the taken names ([clashes]) get 「ファイルの置換または
     * スキップ」 for the folder [folderLabel].
     */
    data class Confirm(
        val plan: ImportPlan,
        val wholeBot: Boolean = false,
        val clashes: List<Clash> = emptyList(),
        val folderLabel: String = "",
    ) : ImportState
    /** A new bot's files are staged: waiting for its name (追加する). */
    data class ChooseName(val suggested: String, val error: String? = null) : ImportState
    data class Done(
        val botId: String,
        val count: Int,
        val requirements: String?,
        val botWasRunning: Boolean,
        val isNewBot: Boolean,
    ) : ImportState
    data class Failed(val message: String) : ImportState
}

/** One bot's run state as the screens show it. */
data class BotRunState(
    val status: BotStatus = BotStatus.STOPPED,
    val startedAtMillis: Long? = null,
    val logs: List<LogLine> = emptyList(),
)

/** Shared state between [BotService] and the UI, for every bot by id. */
object BotController {
    // Imports and deletions run here, not in the screen, so turning or leaving the screen does not stop them.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ---- The bot list ----

    private val _bots = MutableStateFlow<List<BotEntry>>(emptyList())
    /** Every bot, in the order added. */
    val bots: StateFlow<List<BotEntry>> = _bots

    @Volatile private var registry: BotRegistry? = null
    @Volatile private var ready = false

    private fun botsRoot(context: Context) = File(context.applicationContext.filesDir, "bots")

    fun registry(context: Context): BotRegistry = registry ?: synchronized(this) {
        registry ?: BotRegistry(File(botsRoot(context), "registry.txt")).also { registry = it }
    }

    private fun prefs(context: Context, name: String): KeyValue {
        val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        return object : KeyValue {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String?) {
                prefs.edit().putString(key, value).apply()
            }
        }
    }

    /**
     * Once per process, before any screen or bot: moves the trial version's bot into the list and undoes
     * imports the app did not live to finish.
     */
    @Synchronized
    fun ensureReady(context: Context) {
        if (ready) return
        val app = context.applicationContext
        appContext = app
        runCatching { SpikeMigration.run(app.filesDir, registry(app), prefs(app, "spike"), prefs(app, "bots")) }
        for (bot in registry(app).all()) {
            val s = store(app, bot.id)
            runCatching { BotImporter.recover(s.codeDir, s.backupDir, s.stagingDir) }
            // The old 秘密の値 move into the bot's .env; a failure is retried at the next start.
            runCatching {
                val shared = if (hasSharedStorageAccess(app)) sharedDir(app, bot.id) else null
                if (EnvMigration.migrate(s, shared)) sync(app, bot.id)?.exportForced(listOf(DotEnv.FILE))
            }
        }
        newStaging(app).deleteRecursively()
        refreshBots(app)
        ready = true
    }

    private fun refreshBots(context: Context) {
        _bots.value = registry(context).all()
    }

    /** The bot's private files and settings. Bot code always runs from here, which other apps cannot modify. */
    fun store(context: Context, id: String): BotStore = BotStore(id, botsRoot(context), prefs(context, "bots"))

    fun name(context: Context, id: String): String? = registry(context).get(id)?.name

    /**
     * Notepad's 保存: writes [path] as [like] was read and returns the file's new time. Refuses with
     * [PendingChanges] when the shared copy was changed and not brought in, and with
     * TextFiles.ChangedSinceOpened when the file changed after it was opened (unless [force]); the shared
     * copy follows only where nobody edited it.
     */
    fun saveText(
        context: Context,
        botId: String,
        path: String,
        text: String,
        like: com.pythonrunner.app.files.Opened.Text,
        force: Boolean = false,
    ): Long {
        val app = context.applicationContext
        val sync = sync(app, botId)
        val pending = runCatching { sync?.pendingUnder(listOf(path)) }.getOrNull().orEmpty()
        if (pending.isNotEmpty()) throw PendingChanges(botId, pending)
        val file = File(store(app, botId).codeDir, path)
        com.pythonrunner.app.files.TextFiles.save(file, text, like, force)
        runCatching { sync?.export() }
        recheck(botId)
        return file.lastModified()
    }

    /**
     * 作る in the settings tab: an empty .env. Refuses with [PendingChanges] when the shared folder has a
     * .env not brought in yet (its token must not be wiped), and never replaces an existing one.
     */
    fun saveEnv(context: Context, id: String, text: String) {
        val app = context.applicationContext
        val sync = sync(app, id)
        val pending = runCatching { sync?.pendingUnder(listOf(DotEnv.FILE)) }.getOrNull().orEmpty()
        if (pending.isNotEmpty()) throw PendingChanges(id, pending)
        val file = File(store(app, id).codeDir, DotEnv.FILE)
        if (file.exists()) return
        EnvMigration.write(file, text)
        runCatching { sync?.export() }
    }

    /**
     * The values of the bot's .env, for its environment. [onNote] gets a line for the log for each line
     * that could not be read and each name left out (never a value).
     */
    fun botEnv(context: Context, id: String, onNote: (String) -> Unit): Map<String, String> {
        val file = File(store(context, id).codeDir, DotEnv.FILE)
        if (!file.isFile) return emptyMap()
        val parsed = DotEnv.parse(file.readText())
        parsed.badLines.forEach { onNote(".env の ${it} 行目を読めませんでした") }
        val (kept, dropped) = DotEnv.forProcess(parsed.values)
        if (dropped.isNotEmpty()) onNote(".env の ${dropped.joinToString(", ")} は、安全のため使いません")
        return kept
    }

    // ---- Shared folders ----

    fun sharedBotsRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PythonRunner")

    /** Documents/PythonRunner/<name>, visible to the Files app and a PC over USB. */
    fun sharedDir(context: Context, id: String): File? = name(context, id)?.let { File(sharedBotsRoot(), it) }

    /** The bot's shared copy; null without storage access or for an unknown bot. */
    fun sync(context: Context, id: String): FolderSync? {
        val app = context.applicationContext
        if (!hasSharedStorageAccess(app)) return null
        val dir = sharedDir(app, id) ?: return null
        val s = store(app, id)
        return FolderSync(s.codeDir, dir, s.manifestFile)
    }

    fun hasSharedStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Shared-copy changes the person chose not to bring in, per bot; not shown again while unchanged. */
    val declinedChanges: MutableMap<String, SyncChanges> = ConcurrentHashMap()

    private val _sharedStates = MutableStateFlow<Map<String, SharedState>>(emptyMap())
    val sharedStates: StateFlow<Map<String, SharedState>> = _sharedStates
    private val _unlistedFolders = MutableStateFlow<List<String>>(emptyList())
    /** Folders in PythonRunner that belong to no bot (新しいフォルダが見つかりました). */
    val unlistedFolders: StateFlow<List<String>> = _unlistedFolders
    private val fingerprints = ConcurrentHashMap<String, String>()

    /** Mirrors every bot's files to its shared copy (never over edits made there). */
    fun exportAll(context: Context) {
        val app = context.applicationContext
        for (bot in registry(app).all()) {
            if (bot.id == importingBotId()) continue
            runCatching { sync(app, bot.id)?.export() }
        }
    }

    /**
     * One pass of the automatic check: each bot's folder is compared in full only when its cheap
     * fingerprint moved (or the person declined or brought in changes since). Call it off the main thread.
     */
    // Its own lock: a pass hashes files, and must not hold up status updates that lock the controller.
    private val checkLock = Any()

    fun checkShared(context: Context) = synchronized(checkLock) { checkSharedLocked(context) }

    private fun checkSharedLocked(context: Context) {
        val app = context.applicationContext
        if (!hasSharedStorageAccess(app)) {
            _sharedStates.value = emptyMap()
            _unlistedFolders.value = emptyList()
            return
        }
        val bots = registry(app).all()
        val states = HashMap(_sharedStates.value)
        states.keys.retainAll(bots.map { it.id }.toSet())
        for (bot in bots) {
            if (bot.id == importingBotId()) continue
            val sync = sync(app, bot.id) ?: continue
            val fingerprint = runCatching { sync.fingerprint() }.getOrNull() ?: continue
            val key = fingerprint + "|" + declinedChanges[bot.id].hashCode()
            if (fingerprints[bot.id] == key && bot.id in states) continue
            fingerprints[bot.id] = key
            states[bot.id] = runCatching { sync.state(declinedChanges[bot.id]) }.getOrDefault(SharedState.InSync)
        }
        _sharedStates.value = states
        _unlistedFolders.value = runCatching { SharedFolders.unlisted(sharedBotsRoot(), bots.map { it.name }) }
            .getOrDefault(emptyList())
    }

    /** Forgets the last check of [id], so the next pass compares it in full (after 反映する, say). */
    fun recheck(id: String) {
        fingerprints.remove(id)
    }

    /** 「フォルダを作り直す」: writes the bot's shared folder again. */
    fun recreateShared(context: Context, id: String) {
        scope.launch {
            runCatching { sync(context, id)?.recreate() }
            recheck(id)
            checkShared(context)
        }
    }

    // ---- Running bots ----

    private val _runStates = MutableStateFlow<Map<String, BotRunState>>(emptyMap())
    val runStates: StateFlow<Map<String, BotRunState>> = _runStates
    private val buffers = ConcurrentHashMap<String, LogBuffer>()
    private val statuses = ConcurrentHashMap<String, Pair<BotStatus, Long?>>()
    private val logsDirty = AtomicBoolean(false)

    init {
        // Publish log snapshots at most four times a second.
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                if (logsDirty.getAndSet(false)) publish()
                delay(250)
            }
        }
    }

    @Synchronized
    private fun publish() {
        val ids = statuses.keys + buffers.keys
        _runStates.value = ids.associateWith { id ->
            val (status, since) = statuses[id] ?: (BotStatus.STOPPED to null)
            BotRunState(status, since, buffers[id]?.snapshot().orEmpty())
        }
    }

    fun runState(id: String): BotRunState = _runStates.value[id] ?: BotRunState()

    internal fun setStatus(id: String, status: BotStatus) {
        statuses[id] = status to null
        publish()
    }

    internal fun setRunning(id: String, sinceMillis: Long) {
        statuses[id] = BotStatus.RUNNING to sinceMillis
        publish()
    }

    internal fun appendLog(id: String, line: LogLine) {
        buffers.getOrPut(id) { LogBuffer() }.add(line)
        logsDirty.set(true)
    }

    /** Empties the bot's log (the bot keeps running). */
    fun clearLogs(id: String) {
        buffers[id]?.clear()
        publish()
    }

    fun isRunning(id: String): Boolean = runState(id).status.let { it == BotStatus.RUNNING || it == BotStatus.STARTING }

    fun start(context: Context, id: String) {
        if (refuseWhileInstalling(id)) return
        send(context, BotService.ACTION_START, id, foreground = true)
    }

    /** Stops the bot and starts it again, so files changed while it ran are used. */
    fun restart(context: Context, id: String) {
        if (refuseWhileInstalling(id)) return
        send(context, BotService.ACTION_RESTART, id, foreground = true)
    }

    private fun refuseWhileInstalling(id: String): Boolean {
        if (id !in installing) return false
        appendLog(id, LogLine(System.currentTimeMillis(), "部品を入れている間は起動できません。終わってから起動してください", true))
        return true
    }

    fun stop(context: Context, id: String) = send(context, BotService.ACTION_STOP, id, foreground = false)

    private fun send(context: Context, action: String, id: String, foreground: Boolean) {
        val intent = Intent(context, BotService::class.java).setAction(action).putExtra(BotService.EXTRA_BOT_ID, id)
        if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
    }

    /** True while a one-shot job (preparing Python, pip) runs; only one runs at a time in the whole app. */
    val workBusy = MutableStateFlow(false)

    /**
     * Runs Python once without the service (pip, self-check), in bot [botId]'s folder with its packages,
     * or in the app's home folder when null.
     */
    fun runOneShot(context: Context, botId: String?, args: List<String>, onLine: (LogLine) -> Unit, onExit: (Int) -> Unit) {
        val app = context.applicationContext
        val rt = runtime(app)
        val s = botId?.let { store(app, it) }
        thread(name = "python-oneshot") {
            try {
                rt.ensureInstalled()
                val dir = s?.codeDir ?: File(app.filesDir, "home")
                dir.mkdirs()
                ManagedProcess(
                    rt.command(*args.toTypedArray()),
                    // No user secrets here: pip may run arbitrary build backends from PyPI.
                    rt.environment(listOfNotNull(s?.libsDir), emptyMap()),
                    dir, onLine, onExit,
                ).start()
            } catch (e: Exception) {
                onLine(LogLine(System.currentTimeMillis(), "実行できませんでした: ${e.message}", true))
                onExit(-1)
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

    @Volatile private var node: NodeRuntime? = null

    /** The bundled Node.js (SPIKE). */
    fun node(context: Context): NodeRuntime = node ?: synchronized(this) {
        node ?: context.applicationContext.let { app ->
            NodeRuntime(
                baseDir = app.filesDir,
                nativeLibDir = File(app.applicationInfo.nativeLibraryDir),
                tmpDir = app.cacheDir,
                version = "node-${BuildConfig.VERSION_CODE}",
                certFile = File(runtime(app).home, "cacert.pem"),
                openAsset = app.assets::open,
            )
        }.also { node = it }
    }

    /**
     * SPIKE: checks, step by step, that the bundled Node.js runs here, reaches the internet, installs
     * packages with npm, and loads Discord's voice encryption (DAVE). Every line goes to [onLine].
     */
    fun runNodeCheck(context: Context, onLine: (LogLine) -> Unit, onExit: (Int) -> Unit) {
        val app = context.applicationContext
        thread(name = "node-check") {
            fun say(text: String, error: Boolean = false) = onLine(LogLine(System.currentTimeMillis(), text, error))
            var failures = 0
            try {
                val rt = runtime(app)
                rt.ensureInstalled()
                val nd = node(app)
                if (!nd.isBundled()) {
                    say("Node.js がこのアプリに入っていません", true)
                    onExit(1)
                    return@thread
                }
                nd.ensureInstalled()
                val work = File(app.filesDir, "node-check").apply { mkdirs() }
                File(work, "package.json").writeText("{\"name\":\"node-check\",\"private\":true}")
                val env = rt.environment(emptyList(), emptyMap()) + nd.environment()
                fun step(title: String, command: List<String>, dir: File = work) {
                    say("▶ $title")
                    val done = java.util.concurrent.CountDownLatch(1)
                    var code = -1
                    ManagedProcess(command, env, dir, onLine) { code = it; done.countDown() }.start()
                    done.await()
                    if (code == 0) say("✓ $title") else { failures++; say("✗ $title（終了コード $code）", true) }
                }
                step("Node.js のバージョン", nd.command("--version"))
                step(
                    "Node.js の中身",
                    nd.command("-p", "[process.platform, process.arch, 'openssl ' + process.versions.openssl, 'icu ' + process.versions.icu].join(' / ')"),
                )
                step(
                    "インターネット（https）",
                    nd.command(
                        "-e",
                        "fetch('https://discord.com/api/v10/gateway').then(r => r.json())" +
                            ".then(j => console.log('https OK', j.url)).catch(e => { console.error('https NG', e); process.exit(1) })",
                    ),
                )
                step(
                    "npm install（@snazzah/davey @discordjs/voice）",
                    nd.npm("install", "--ignore-scripts", "--no-audit", "--no-fund", "@snazzah/davey", "@discordjs/voice"),
                )
                step(
                    "通話の暗号化（DAVE）の部品を読み込む",
                    nd.command(
                        "--input-type=module", "-e",
                        "const d = await import('@snazzah/davey'); console.log('DAVE OK', 'protocol', d.DAVE_PROTOCOL_VERSION, Object.keys(d).length, 'exports')",
                    ),
                )
                step(
                    "@discordjs/voice の部品の一覧",
                    nd.command("-e", "console.log(require('@discordjs/voice').generateDependencyReport())"),
                )
                step(
                    "Python から node が見つかるか",
                    rt.command("-c", "import shutil, subprocess; p = shutil.which('node'); print('which node:', p); print(subprocess.run([p, '-v'], capture_output=True, text=True).stdout.strip())"),
                )
                say(if (failures == 0) "すべて成功しました" else "失敗：$failures 個", failures > 0)
                onExit(if (failures == 0) 0 else 1)
            } catch (e: Exception) {
                say("試験を続けられませんでした: ${e.message ?: e.javaClass.simpleName}", true)
                onExit(-1)
            }
        }
    }

    /**
     * 部品を入れる: the libraries of every requirements.txt into the bot's own packages, with a
     * .venv/bin/python beside it, then npm for every Node.js project (see [BotParts]). Each finished part
     * is recorded for the library tab. A running bot is stopped first (npm ci replaces node_modules
     * wholesale) and started again at the end, whatever the outcome.
     */
    fun runBotParts(context: Context, botId: String, onLine: (LogLine) -> Unit, onExit: (Int) -> Unit) {
        val app = context.applicationContext
        installing += botId
        thread(name = "bot-parts") {
            fun say(text: String, error: Boolean = false) = onLine(LogLine(System.currentTimeMillis(), text, error))
            val wasRunning = isRunning(botId)
            var code = -1
            try {
                if (wasRunning) {
                    say("Botを止めます")
                    stop(app, botId)
                    val until = System.currentTimeMillis() + 10_000
                    while (isRunning(botId) && System.currentTimeMillis() < until) Thread.sleep(200)
                }
                // npm ci would replace node_modules under a bot that is still running.
                if (isRunning(botId)) {
                    say("Botが止まらなかったので、部品は入れていません", true)
                    code = 1
                } else {
                    runParts(app, botId, ::say) { code = it }
                }
            } finally {
                installing -= botId
                if (wasRunning) {
                    say("Botを起動し直します")
                    // From a background thread Android 12+ may refuse a foreground start: say so, never crash.
                    runCatching { start(app, botId) }.onFailure { say("Botを起動できませんでした。「起動」を押してください", true) }
                }
                // Last, so delete and 取り込む stay disabled until the bot is back.
                onExit(code)
            }
        }
    }

    /** Bots whose parts are being installed: they are not started meanwhile. */
    private val installing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun runParts(app: Context, botId: String, say: (String, Boolean) -> Unit, onExit: (Int) -> Unit) {
        val onLine: (LogLine) -> Unit = { say(it.text, it.isError) }
        run {
            try {
                val rt = runtime(app)
                rt.ensureInstalled()
                val s = store(app, botId)
                val code = s.codeDir
                val nd = node(app)
                val requirements = BotParts.requirementFiles(code)
                val projects = BotParts.nodeProjects(code)
                if (requirements.isEmpty() && projects.isEmpty()) {
                    say("requirements.txt も package.json も見つかりませんでした", true)
                    onExit(1)
                    return
                }
                // Not the bot's secret values: pip and npm need none of them.
                val env = rt.environment(listOf(s.libsDir), emptyMap()) + if (nd.isBundled()) nd.environment() else emptyMap()
                fun record(key: String) {
                    s.partsInstalled = s.partsInstalled + (key to System.currentTimeMillis())
                }
                fun step(title: String, command: List<String>, dir: File): Boolean {
                    say("▶ $title", false)
                    val done = java.util.concurrent.CountDownLatch(1)
                    var exit = -1
                    ManagedProcess(command, env, dir, onLine) { exit = it; done.countDown() }.start()
                    done.await()
                    if (exit == 0) say("✓ $title", false) else say("✗ $title（終了コード $exit）", true)
                    return exit == 0
                }
                fun where(f: File) = f.relativeTo(code).invariantSeparatorsPath.ifEmpty { "（いちばん上）" }

                if (requirements.isNotEmpty() && !rt.isPipInstalled() &&
                    !step("pip を準備する", PipCommands.bootstrap(rt), code)
                ) {
                    onExit(1)
                    return
                }
                for (req in requirements) {
                    val dir = req.parentFile!!
                    if (!step("Python のライブラリ（${where(req)}）", PipCommands.install(rt, listOf("-r", req.path), s.libsDir), dir)) {
                        onExit(1)
                        return
                    }
                    BotParts.linkVenvPython(dir, rt.executable)
                    say("✓ ${where(BotParts.venvPython(dir))} を用意しました", false)
                    record(req.relativeTo(code).invariantSeparatorsPath)
                }
                if (projects.isNotEmpty()) {
                    if (!nd.isBundled()) {
                        say("Node.js がこのアプリに入っていません", true)
                        onExit(1)
                        return
                    }
                    nd.ensureInstalled()
                    for (project in projects) {
                        val args = BotParts.npmArgs(project)
                        if (!step("Node.js の部品（${where(project)}、npm ${args.first()}）", nd.npm(*args.toTypedArray()), project)) {
                            onExit(1)
                            return
                        }
                        record(project.relativeTo(code).invariantSeparatorsPath.ifEmpty { "." })
                    }
                }
                say("部品をすべて入れました", false)
                onExit(0)
            } catch (e: Exception) {
                say("続けられませんでした: ${e.message ?: e.javaClass.simpleName}", true)
                onExit(-1)
            }
        }
    }

    // ---- Imports: into a bot, as a new bot, or adopting a found folder ----

    private sealed interface Target {
        data class Existing(val id: String) : Target
        data object NewBot : Target
        data class Adopt(val folder: String) : Target
    }

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState
    @Volatile private var target: Target? = null
    @Volatile private var importRequirements: String? = null

    /** An import is under way somewhere in the app; only one runs at a time. */
    val importBusy: Boolean
        get() = _importState.value.let { it is ImportState.Working || it is ImportState.Confirm || it is ImportState.ChooseName }

    /** The bot whose folder an import is changing right now, if any. */
    fun importingBotId(): String? = if (importBusy) (target as? Target.Existing)?.id else null

    private fun newStaging(context: Context) = File(botsRoot(context), "new-staging")

    private fun stagingFor(context: Context, t: Target?): File =
        if (t is Target.Existing) store(context, t.id).stagingDir else newStaging(context)

    /**
     * A picked file into bot [botId] (inside its folder [into]), or a new bot when null: a ZIP is
     * extracted, anything else added as it is. Files of the same name are replaced, others kept.
     */
    fun importFile(context: Context, uri: Uri, botId: String?, into: String = "") =
        startImport(context, uri, botId?.let { Target.Existing(it) } ?: Target.NewBot) { app, staging ->
            val prefix = if (botId != null) into else ""
            importPrefix = prefix
            stageFileFrom(app, uri, staging, prefix)
        }

    /**
     * A picked folder: into bot [botId] it goes under its own name inside [into] (the folder open on the
     * files screen), checked against the folder of that name already there. For a new bot (null) its
     * contents become the bot.
     */
    fun importFolder(context: Context, treeUri: Uri, botId: String?, into: String = "") =
        startImport(context, treeUri, botId?.let { Target.Existing(it) } ?: Target.NewBot) { app, staging ->
            val name = folderName(app, treeUri)
            pickedName = name ?: "Bot"
            val prefix = if (botId == null) {
                ""
            } else {
                val safe = name?.let { BotImporter.safePath(it) }?.takeIf { '/' !in it }
                    ?: throw IOException("このフォルダの名前は使えません")
                if (into.isEmpty()) safe else "$into/$safe"
            }
            importPrefix = prefix
            // A new bot from a ZIP unpacked on the phone: its one inner folder is the bot.
            stageFolderFrom(app, treeUri, staging, prefix, liftSingleTop = botId == null)
        }

    /**
     * 「Botをまるごと入れ替える」: the picked folder's (or ZIP's) contents become bot [botId]'s files.
     * Its files that the new ones lack are listed in the check and kept unless the person removes them.
     */
    fun replaceBot(context: Context, uri: Uri, botId: String, zip: Boolean) =
        startImport(context, uri, Target.Existing(botId)) { app, staging ->
            importScope = ""
            importWholeBot = true
            if (zip) stageFileFrom(app, uri, staging, "") else stageFolderFrom(app, uri, staging, "", liftSingleTop = true)
        }

    private fun folderName(app: Context, treeUri: Uri): String? = runCatching {
        SafTree.displayName(app, DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)))
    }.getOrNull()

    private fun stageFileFrom(app: Context, uri: Uri, staging: File, prefix: String): Int {
        val name = SafTree.displayName(app, uri) ?: "file"
        val open = { app.contentResolver.openInputStream(uri) ?: error("ファイルを開けませんでした") }
        pickedName = name
        return if (name.lowercase().endsWith(".zip")) {
            requireSpace(SafTree.size(app, uri), app.cacheDir)
            val zip = File(app.cacheDir, "import.zip")
            try {
                open().use { input -> zip.outputStream().use { input.copyTo(it) } }
                requireSpace(BotImporter.zipSize(zip), app.filesDir)
                // Android 14+ refuses a whole ZIP over one ".." name; BotImporter drops such names itself.
                if (Build.VERSION.SDK_INT >= 34) dalvik.system.ZipPathValidator.clearCallback()
                BotImporter.stageZip(zip, staging, prefix) { _importState.value = ImportState.Working(it) }
            } finally {
                zip.delete()
            }
        } else {
            requireSpace(SafTree.size(app, uri), app.filesDir)
            open().use { BotImporter.stageFile(name, it, staging, prefix) }
            1
        }
    }

    private fun stageFolderFrom(app: Context, treeUri: Uri, staging: File, prefix: String, liftSingleTop: Boolean = false): Int {
        val files = SafTree.list(app, treeUri).filter { !BotImporter.isSkipped(it.path) }
        requireSpace(files.sumOf { it.size }, app.filesDir)
        return BotImporter.stageTree(files.map { it.path to it.open }, staging, prefix, liftSingleTop) {
            _importState.value = ImportState.Working(it)
        }
    }

    /** Where the files being imported go inside the bot, and which part of it the check compares (null: none). */
    @Volatile private var importPrefix: String = ""
    @Volatile private var importScope: String? = null
    @Volatile private var importWholeBot: Boolean = false

    /** 「追加する」 on a folder found in PythonRunner: it becomes the new bot's shared folder as it is. */
    fun adoptFolder(context: Context, folderName: String) {
        if (importBusy) return
        val app = context.applicationContext
        val folder = File(sharedBotsRoot(), folderName)
        target = Target.Adopt(folderName)
        _importState.value = ImportState.Working(0)
        scope.launch {
            runImport(app) {
                val staging = newStaging(app).apply { deleteRecursively() }
                val files = SharedFolders.adoptableFiles(folder)
                requireSpace(files.sumOf { File(folder, it).length() }, app.filesDir)
                val count = BotImporter.stageTree(files.map { path -> path to { File(folder, path).inputStream() } }, staging) {
                    _importState.value = ImportState.Working(it)
                }
                importRequirements = BotImporter.requirements(staging)
                // An empty folder still becomes a bot; its files can be added later.
                if (count == 0) staging.mkdirs()
                _importState.value = ImportState.ChooseName(folderName)
            }
        }
    }

    @Volatile private var pickedName: String = "Bot"

    private fun startImport(context: Context, picked: Uri, t: Target, stage: (Context, File) -> Int) {
        if (importBusy) return
        target = t
        importPrefix = ""
        importScope = null
        importWholeBot = false
        _importState.value = ImportState.Working(0)
        val app = context.applicationContext
        // The picker's grant ends with the activity; hold it so leaving the app does not stop the import.
        val held = runCatching {
            app.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        scope.launch {
            runImport(app) {
                val staging = stagingFor(app, t).apply { deleteRecursively() }
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
                importRequirements = BotImporter.requirements(staging, importPrefix)
                when (t) {
                    is Target.Existing -> {
                        val codeDir = store(app, t.id).codeDir
                        val plan = BotImporter.plan(staging, codeDir, importScope)
                        when {
                            plan.overwrites.isEmpty() && plan.extras.isEmpty() -> applyImport(app, t.id, plan, emptyList())
                            importWholeBot -> _importState.value = ImportState.Confirm(plan, wholeBot = true)
                            else -> _importState.value = ImportState.Confirm(
                                plan,
                                clashes = clashesOf(plan, staging, codeDir),
                                folderLabel = importPrefix.substringBeforeLast('/', "").substringAfterLast('/')
                                    .ifEmpty { name(app, t.id) ?: "" },
                            )
                        }
                    }
                    else -> {
                        val names = registry(app).all().map { it.name }
                        val folders = if (hasSharedStorageAccess(app)) SharedFolders.unlisted(sharedBotsRoot(), names) else emptyList()
                        _importState.value = ImportState.ChooseName(BotNames.suggest(pickedName, names + folders))
                    }
                }
            }
        }
    }

    private inline fun runImport(app: Context, block: () -> Unit) {
        try {
            block()
        } catch (e: NotEnoughSpace) {
            stagingFor(app, target).deleteRecursively()
            _importState.value = ImportState.Failed(e.message.orEmpty())
        } catch (e: Exception) {
            stagingFor(app, target).deleteRecursively()
            _importState.value = ImportState.Failed("取り込めませんでした: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** For 「ファイルの置換またはスキップ」: each taken name, with the bot's file and the incoming one side by side. */
    private fun clashesOf(plan: ImportPlan, staging: File, codeDir: File): List<Clash> = plan.overwrites.map { path ->
        val incoming = File(staging, path)
        val parent = File(codeDir, path.substringBeforeLast('/', ""))
        val existing = parent.listFiles().orEmpty().firstOrNull { FileNames.sameName(it.name, incoming.name) } ?: File(codeDir, path)
        Clash(path, existing.length(), existing.lastModified(), incoming.length(), incoming.lastModified())
    }

    /**
     * 取り込む after the check: [resolution] answers the taken names (a whole-bot replace always replaces),
     * [removeExtras] deletes the bot's files the new ones lack. Whatever is replaced or deleted goes to the bin.
     */
    fun confirmImport(context: Context, resolution: Resolution, removeExtras: Boolean) {
        val state = _importState.value as? ImportState.Confirm ?: return
        val id = (target as? Target.Existing)?.id ?: return
        _importState.value = ImportState.Working(100)
        val app = context.applicationContext
        scope.launch {
            runImport(app) {
                val s = store(app, id)
                val plan = if (state.wholeBot) state.plan else BotImporter.resolve(state.plan, s.stagingDir, s.codeDir, resolution)
                applyImport(app, id, plan, if (removeExtras) plan.extras else emptyList())
            }
        }
    }

    /** The bot's recycle bin. */
    fun trash(context: Context, id: String): Trash = Trash(store(context, id).trashDir)

    private fun applyImport(app: Context, id: String, plan: ImportPlan, remove: List<String>) {
        val s = store(app, id)
        val wasRunning = isRunning(id)
        // Removing files under a running bot could take its data away while it uses it: stop it first
        // (the screen then offers to start it again, as after any import into a running bot).
        if (remove.isNotEmpty() && wasRunning) {
            stop(app, id)
            val until = System.currentTimeMillis() + 10_000
            while (isRunning(id) && System.currentTimeMillis() < until) Thread.sleep(200)
            if (isRunning(id)) {
                s.stagingDir.deleteRecursively()
                _importState.value = ImportState.Failed("Botが止まらなかったので、取り込みませんでした")
                return
            }
        }
        s.codeDir.mkdirs()
        val bin = trash(app, id)
        BotImporter.applyPlan(plan, s.stagingDir, s.codeDir, s.backupDir, remove = remove, onReplaced = { f, p -> bin.put(f, p) })
        // The shared copy follows at once, so the automatic check does not offer its old files back.
        runCatching {
            val sync = sync(app, id)
            sync?.exportForced(plan.files)
            // Removed files leave the shared folder too, unless they were edited there.
            if (remove.isNotEmpty()) sync?.export()
        }
        recheck(id)
        s.mainFile = BotImporter.pickMain(s.codeDir, s.mainFile)
        _importState.value = ImportState.Done(id, plan.files.size, importRequirements, wasRunning, isNewBot = false)
    }

    /** 「追加する」 with the name typed for a new or adopted bot. */
    fun confirmName(context: Context, raw: String) {
        if (_importState.value !is ImportState.ChooseName) return
        val app = context.applicationContext
        val t = target
        val adopting = (t as? Target.Adopt)?.folder
        val names = registry(app).all().map { it.name }
        val unlisted = if (hasSharedStorageAccess(app)) {
            SharedFolders.unlisted(sharedBotsRoot(), names).filter { !it.equals(adopting, ignoreCase = false) }
        } else {
            emptyList()
        }
        val name = when (val check = BotNames.check(raw, names, unlisted)) {
            is NameCheck.Invalid -> {
                val hint = if (adopting == null && unlisted.any { it.equals(raw.trim(), ignoreCase = true) }) {
                    "（このフォルダはホーム画面の『新しいフォルダが見つかりました』から追加できます）"
                } else {
                    ""
                }
                _importState.value = ImportState.ChooseName(raw, check.message + hint)
                return
            }
            is NameCheck.Ok -> check.name
        }
        _importState.value = ImportState.Working(100)
        scope.launch {
            if (adopting != null && adopting != name && !SharedFolders.rename(sharedBotsRoot(), adopting, name)) {
                _importState.value = ImportState.ChooseName(raw, "フォルダの名前を変えられませんでした")
                return@launch
            }
            val entry = registry(app).add(name)
            val s = store(app, entry.id)
            try {
                val staging = newStaging(app)
                s.codeDir.mkdirs()
                val plan = BotImporter.plan(staging, s.codeDir)
                BotImporter.applyPlan(plan, staging, s.codeDir, s.backupDir)
                runCatching {
                    val sync = sync(app, entry.id)
                    if (adopting != null) sync?.markSynced(plan.files) else sync?.exportForced(plan.files)
                }
                s.mainFile = BotImporter.pickMain(s.codeDir, null)
                refreshBots(app)
                _importState.value = ImportState.Done(entry.id, plan.files.size, importRequirements, false, isNewBot = true)
            } catch (e: Exception) {
                // Never leave a half-made bot in the list.
                registry(app).remove(entry.id)
                s.deleteAll()
                newStaging(app).deleteRecursively()
                refreshBots(app)
                _importState.value = ImportState.Failed("追加できませんでした: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** やめる: nothing in any bot folder or shared folder changes. */
    fun cancelImport(context: Context) {
        val state = _importState.value
        if (state !is ImportState.Confirm && state !is ImportState.ChooseName) return
        val app = context.applicationContext
        val staging = stagingFor(app, target)
        _importState.value = ImportState.Idle
        scope.launch { staging.deleteRecursively() }
    }

    /** The screen has shown the result. */
    fun finishImport() {
        val state = _importState.value
        if (state is ImportState.Done || state is ImportState.Failed) _importState.value = ImportState.Idle
    }

    /** Before a bot starts: an import it did not live to finish is undone, so it never runs half-applied. */
    fun recoverImportNow(context: Context, id: String) {
        if (importingBotId() == id) return
        val s = store(context.applicationContext, id)
        runCatching { BotImporter.recover(s.codeDir, s.backupDir, s.stagingDir) }
    }

    private class NotEnoughSpace(message: String) : Exception(message)

    @Volatile private var appContext: Context? = null

    private fun requireSpace(needed: Long, dir: File) {
        val missing = BotImporter.missingBytes(needed, dir.usableSpace)
        if (missing > 0) {
            val mb = (missing + 1024 * 1024 - 1) / (1024 * 1024)
            // Like Windows: say how much emptying the recycle bins would free.
            val inBins = appContext?.let { a -> registry(a).all().sumOf { runCatching { trash(a, it.id).size() }.getOrDefault(0) } } ?: 0
            val hint = if (inBins >= 1024 * 1024) "（ごみ箱を空にすると ${inBins / (1024 * 1024)}MB 空きます）" else ""
            throw NotEnoughSpace("空き容量が足りません（あと ${mb}MB 必要です）$hint")
        }
    }

    // ---- The files screen: operations, clipboard, recycle bin, undo ----

    /** What 切り取り or コピー put on the app-wide clipboard. */
    data class Clip(val items: List<Item>, val cut: Boolean)

    /** Paths with shared-folder changes not brought in yet: the operation waits for 反映する. */
    class PendingChanges(val botId: String, val paths: List<String>) : Exception("未反映の変更があります")

    val clipboard = MutableStateFlow<Clip?>(null)
    private val _fileBusy = MutableStateFlow(false)
    /** A file operation is running (its buttons wait). */
    val fileBusy: StateFlow<Boolean> = _fileBusy
    /** Bots whose files changed while they ran: the screen offers a restart. */
    val filesChangedWhileRunning = MutableStateFlow<Set<String>>(emptySet())
    private val histories = ConcurrentHashMap<String, UndoHistory>()

    fun fileOps(context: Context): FileOps {
        val app = context.applicationContext
        return FileOps { id -> store(app, id).let { BotSpace(it.codeDir, Trash(it.trashDir)) } }
    }

    fun undoHistory(botId: String): UndoHistory = histories.getOrPut(botId) { UndoHistory() }

    /** The shared-folder changes of [botId] not brought in yet, for 「先に反映する」. */
    fun pendingChanges(context: Context, botId: String): SyncChanges? =
        runCatching { sync(context, botId)?.pendingImports() }.getOrNull()?.takeIf { !it.isEmpty }

    /**
     * Runs a file operation off the main thread. First, if any [touched] item has shared-folder changes not
     * brought in yet, it fails with [PendingChanges] and changes nothing. Afterwards: the op goes on
     * [botId]'s undo list ([record]), 最初に実行 and the parts records follow moves, the shared copies
     * follow, and a running bot is marked for the restart question. [onDone] runs on the main thread.
     */
    fun runFileOp(
        context: Context,
        botId: String,
        touched: List<Item>,
        record: Boolean = true,
        op: () -> FileOp,
        onDone: (Result<FileOp>) -> Unit,
    ) {
        val app = context.applicationContext
        _fileBusy.value = true
        scope.launch {
            // One file operation at a time: an undo never races the operation it reverses.
            val result = fileMutex.withLock {
                runCatching {
                    touched.groupBy { it.botId }.forEach { (bot, items) ->
                        val pending = sync(app, bot)?.pendingUnder(items.map { it.path }).orEmpty()
                        if (pending.isNotEmpty()) throw PendingChanges(bot, pending)
                    }
                    op().also { done -> afterFileOp(app, botId, done, record) }
                }
            }
            _fileBusy.value = fileMutex.isLocked
            ContextCompat.getMainExecutor(app).execute { onDone(result) }
        }
    }

    /** 元に戻す: reverses the newest op of [botId]. */
    fun undoLast(context: Context, botId: String, onDone: (Result<FileOp>) -> Unit) {
        undoHistory(botId).peek()?.let { undo(context, botId, it, onDone) }
    }

    /**
     * Reverses exactly [op] (the snackbar's 元に戻す names the delete it reports); it leaves the list only
     * once undone. For a move, 最初に実行 and the parts records follow the files back.
     */
    fun undo(context: Context, botId: String, op: FileOp, onDone: (Result<FileOp>) -> Unit) {
        val history = undoHistory(botId)
        val ops = fileOps(context)
        runFileOp(context, botId, itemsOf(op), record = false, op = {
            if (!history.contains(op)) throw IllegalStateException("もう元に戻してあります")
            ops.undo(op)
            history.remove(op)
            if (op is FileOp.Moved) op.copy(moves = op.moves.map { Move(it.to, it.from) }.reversed()) else op
        }, onDone = onDone)
    }

    private val fileMutex = Mutex()

    /** 完全に削除 / ごみ箱を空にする ([entries] null): cannot be undone. */
    fun deleteFromTrash(context: Context, botId: String, entries: List<TrashEntry>?, onDone: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val bin = trash(app, botId)
            runCatching { if (entries == null) bin.empty() else entries.forEach { bin.delete(it) } }
            ContextCompat.getMainExecutor(app).execute { onDone() }
        }
    }

    private fun itemsOf(op: FileOp): List<Item> = when (op) {
        is FileOp.Trashed -> op.entries.map { Item(op.botId, it.originalPath) }
        is FileOp.Moved -> op.moves.flatMap { listOf(it.from, it.to) }
        is FileOp.Copied -> op.created.map { Item(op.botId, it) }
        is FileOp.Created -> listOf(Item(op.botId, op.path))
        is FileOp.Restored -> op.paths.map { Item(op.botId, it) }
    }

    private fun afterFileOp(app: Context, botId: String, op: FileOp, record: Boolean) {
        if (record) undoHistory(botId).push(op)
        val bots = (itemsOf(op).map { it.botId } + botId).toSet()
        if (op is FileOp.Moved) {
            val ops = fileOps(app)
            for (bot in bots) {
                val s = store(app, bot)
                s.mainFile?.let { main ->
                    ops.remap(Item(bot, main), op.moves)?.let { s.mainFile = if (it.botId == bot) it.path else null }
                }
                s.partsInstalled = s.partsInstalled.mapNotNull { (key, at) ->
                    if (key == ".") return@mapNotNull key to at
                    val moved = ops.remap(Item(bot, key), op.moves) ?: return@mapNotNull key to at
                    if (moved.botId == bot) moved.path to at else null
                }.toMap()
            }
        }
        for (bot in bots) {
            store(app, bot).forgetMissingMain()
            runCatching { sync(app, bot)?.export() }
            recheck(bot)
            if (isRunning(bot)) filesChangedWhileRunning.value = filesChangedWhileRunning.value + bot
        }
    }

    // ---- Renaming and deleting ----

    /** Renames bot [id] and its shared folder; returns why it could not, or null when done. */
    fun renameBot(context: Context, id: String, raw: String): String? {
        val app = context.applicationContext
        val current = name(app, id) ?: return "Botが見つかりません"
        val others = registry(app).all().filter { it.id != id }.map { it.name }
        val access = hasSharedStorageAccess(app)
        val unlisted = if (access) SharedFolders.unlisted(sharedBotsRoot(), others + current) else emptyList()
        val name = when (val check = BotNames.check(raw, others, unlisted)) {
            is NameCheck.Invalid -> return check.message
            is NameCheck.Ok -> check.name
        }
        if (name == current) return null
        // Without access the folder cannot follow the name; it would turn up later as someone else's.
        if (!access && store(app, id).manifestFile.length() > 0) return "ストレージへのアクセスを許可してから変えてください"
        if (access && File(sharedBotsRoot(), current).isDirectory && !SharedFolders.rename(sharedBotsRoot(), current, name)) {
            return "フォルダの名前を変えられませんでした"
        }
        registry(app).rename(id, name)
        refreshBots(app)
        recheck(id)
        return null
    }

    /**
     * Deletes bot [id]: stops it, then removes its private files, packages and secrets, its shared folder
     * when [deleteFolder], and its place in the list.
     */
    /**
     * Deletes bot [id] with its shared folder. Without storage access the folder cannot be removed:
     * returns the message saying where it was left, else null.
     */
    fun deleteBot(context: Context, id: String): String? {
        val app = context.applicationContext
        val folder = sharedDir(app, id)
        val access = hasSharedStorageAccess(app)
        val left = if (!access && folder != null) "ドキュメント › PythonRunner › ${folder.name} は残りました（ストレージの許可がないため）" else null
        val sync = sync(app, id)
        val store = store(app, id)
        // Out of the list first: from here on no export, check, start or import can reach the bot.
        registry(app).remove(id)
        refreshBots(app)
        if (isRunning(id)) stop(app, id)
        scope.launch {
            val until = System.currentTimeMillis() + 10_000
            while (isRunning(id) && System.currentTimeMillis() < until) delay(200)
            // What was synced goes before the files, so their vanishing never deletes shared copies.
            if (sync != null) sync.forget() else store.manifestFile.delete()
            store.deleteAll()
            if (folder != null && access) runCatching { folder.deleteRecursively() }
            declinedChanges.remove(id)
            fingerprints.remove(id)
            buffers.remove(id)
            statuses.remove(id)
            publish()
            app.getSystemService(NotificationManager::class.java).cancel(BotService.crashNotificationId(id))
            checkShared(app)
        }
        return left
    }

    /** Frees the space of the AI model a test build downloaded (the AI is set aside for now). */
    fun removeLeftoverFiles(context: Context) {
        thread(name = "leftover-cleanup") {
            runCatching { LeftoverFiles.removeAiModels(context.applicationContext.filesDir) }
        }
    }
}
