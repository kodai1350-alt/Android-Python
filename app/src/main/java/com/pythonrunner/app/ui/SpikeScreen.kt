package com.pythonrunner.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.bot.duplicateNames
import com.pythonrunner.app.files.SyncChanges
import java.io.File
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.python.PipCommands
import com.pythonrunner.app.python.PipInput
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.service.LlmState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Green = Color(0xFF2F9E44)
private val Red = Color(0xFFE03131)
private val Gray = Color(0xFFADB5BD)

private const val SELF_CHECK =
    "import sys, ssl, sqlite3, ctypes; print('Python', sys.version.split()[0]); print(ssl.OPENSSL_VERSION)"

/** The trial version's single screen: work through the sections top to bottom to run one bot. */
@Composable
fun SpikeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rt = remember { BotController.runtime(context) }
    var hasStorage by remember { mutableStateOf(BotController.hasSharedStorageAccess(context)) }
    // Coming back from the permission screen: re-check, and switch the bot folder if it was granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasStorage = BotController.hasSharedStorageAccess(context) }
    val store = remember(hasStorage) { BotController.store(context) }
    val uiPrefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }

    val status by BotController.status.collectAsStateWithLifecycle()
    val startedAt by BotController.startedAtMillis.collectAsStateWithLifecycle()
    val botLogs by BotController.logs.collectAsStateWithLifecycle()

    val workLog = remember { mutableStateListOf<LogLine>() }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(rt.isPipInstalled()) }
    var prepResult by remember { mutableStateOf<WorkResult?>(null) }
    var installResult by remember { mutableStateOf<WorkResult?>(null) }
    var mainFile by remember { mutableStateOf(store.mainFile) }
    var filesVersion by remember { mutableStateOf(0) }
    var packages by remember { mutableStateOf("pip install discord.py") }
    val secrets = remember {
        mutableStateListOf<SecretRow>().apply { addAll(store.envVars.map { (k, v) -> SecretRow(k, v) }) }
    }
    var savedSecrets by remember { mutableStateOf(store.envVars) }
    var justSaved by remember { mutableStateOf(false) }

    val llmState by BotController.llmState.collectAsStateWithLifecycle()
    var llmThreads by remember { mutableStateOf(store.llmThreads) }
    var confirmDeleteModel by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { BotController.refreshLlmState(context) }
    // A 1.25GB download should not stop because the screen turned off.
    val view = LocalView.current
    val downloadingModel = llmState is LlmState.Downloading
    DisposableEffect(downloadingModel) {
        view.keepScreenOn = downloadingModel
        onDispose { view.keepScreenOn = false }
    }

    // Runs one Python command and streams its output into the work log.
    fun runWork(command: List<String>, then: (Int) -> Unit = {}) {
        busy = true
        // runOneShot prepends the launcher itself, so drop it from the full command.
        BotController.runOneShot(
            context, command.drop(1),
            onLine = { line -> scope.launch { workLog += line } },
            onExit = { code -> scope.launch { then(code); if (code != 0) busy = false } },
        )
    }

    // Mirror the private bot folder to the shared copy (never overwrites edits made there).
    fun exportShared() {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { BotController.sync(context)?.export() } }
            filesVersion++
        }
    }
    LaunchedEffect(hasStorage) { if (hasStorage) exportShared() }

    var pendingImport by remember { mutableStateOf<SyncChanges?>(null) }
    // Whether accepting pendingImport also starts the bot ("起動") or only brings the changes in ("🔄 更新").
    var importThenStart by remember { mutableStateOf(true) }

    // "🔄 更新": changes made in the shared copy (a file deleted with the Files app, say) are shown and,
    // once confirmed, brought into the bot folder; otherwise the bot folder is mirrored out as before.
    fun refreshShared() {
        scope.launch {
            val changes = withContext(Dispatchers.IO) {
                runCatching { BotController.sync(context)?.pendingImports() }.getOrNull()
            }
            if (changes != null && !changes.isEmpty) {
                importThenStart = false
                pendingImport = changes
            } else {
                exportShared()
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val name = displayName(context, uri) ?: "main.py"
                context.contentResolver.openInputStream(uri)!!.use { store.importPy(name, it) }
                mainFile = store.mainFile
                exportShared()
            } catch (e: Exception) {
                workLog += LogLine(System.currentTimeMillis(), "ファイルを取り込めませんでした: ${e.message}", true)
            }
        }
    }

    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasStorage = BotController.hasSharedStorageAccess(context)
    }
    fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            askStorage.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        BotController.start(context)
    }
    fun launchBot() {
        if (Build.VERSION.SDK_INT >= 33) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            BotController.start(context)
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Python Runner（お試し版）", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Section("1. Pythonの準備") {
            WorkResultView(prepResult ?: if (ready) WorkResult.Done("準備完了") else WorkResult.Idle("まだ準備していません"))
            Button(enabled = !busy, onClick = {
                workLog.clear()
                prepResult = WorkResult.Working("準備中…（1分ほどかかることがあります）")
                runWork(PipCommands.bootstrap(rt)) { code ->
                    if (code != 0) {
                        ready = false
                        prepResult = WorkResult.Failed("準備に失敗しました（pipを入れられませんでした）")
                        return@runWork
                    }
                    val checkFrom = workLog.size
                    runWork(listOf(rt.executable.path, "-c", SELF_CHECK)) { checkCode ->
                        ready = checkCode == 0
                        prepResult = if (ready) {
                            val versions = workLog.drop(checkFrom).filter { !it.isError }
                                .joinToString(" / ") { it.text }
                            WorkResult.Done("準備完了：$versions")
                        } else {
                            WorkResult.Failed("準備に失敗しました（Pythonの動作確認でエラー）")
                        }
                        busy = false
                    }
                }
            }) { Text(if (ready) "もう一度準備する" else "Pythonを準備") }
        }

        Section("2. Botのファイル") {
            Text(
                mainFile?.let { "▶ 最初に実行: $it" } ?: "まだ選ばれていません",
                color = if (mainFile != null) Green else Gray,
                fontWeight = FontWeight.Bold,
            )
            if (!hasStorage) {
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFFFFF4E6), RoundedCornerShape(8.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Botのファイルを、スマホのファイルアプリやPCからも見えるようにしませんか？", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Help("storage", uiPrefs) {
                        Text(
                            "ストレージへのアクセスを許可すると、Botのファイルのコピーをスマホの Documents/PythonRunner/MyBot に置きます。" +
                                "ファイルアプリやPCで見たり編集したりできます。",
                            fontSize = 12.sp, color = Color.DarkGray,
                        )
                        Text(
                            "実行するのはアプリ専用の場所（他のアプリから書き換えられない）のファイルです。" +
                                "共有のコピーで変更があったときは、起動前に一覧を見せて、取り込むか確認します。" +
                                ".env などの秘密のファイルはコピーしません。",
                            fontSize = 12.sp, color = Color.DarkGray,
                        )
                        Text(SHARED_WARNING, fontSize = 12.sp, color = Color(0xFFC92A2A))
                    }
                    Button(onClick = { requestStorage() }) { Text("ストレージへのアクセスを許可する") }
                }
            }
            val pyHome = rt.home
            val places = remember(hasStorage, store, status) {
                val storageRoot = Environment.getExternalStorageDirectory()
                listOfNotNull(
                    Place(PlaceGroup.BOT, "MyBot（実行用）・${statusText(status)}", store.botDir, isBotFolder = true),
                    Place(PlaceGroup.SHARED, "MyBot（共有コピー）", BotController.sharedBotDir().takeIf { hasStorage }),
                    Place(PlaceGroup.SHARED, "内部ストレージ", storageRoot.takeIf { hasStorage }),
                    Place(PlaceGroup.SHARED, "Documents", File(storageRoot, Environment.DIRECTORY_DOCUMENTS).takeIf { hasStorage }),
                    Place(PlaceGroup.SHARED, "Download", File(storageRoot, Environment.DIRECTORY_DOWNLOADS).takeIf { hasStorage }),
                    Place(PlaceGroup.APP, "Python本体", pyHome),
                    Place(PlaceGroup.APP, "ライブラリ（pip）", store.libsDir.apply { mkdirs() }),
                )
            }
            Explorer(
                places = places,
                mainFile = mainFile,
                refreshKey = filesVersion to status,
                onSetMain = { path ->
                    store.mainFile = path
                    mainFile = path
                },
                onDelete = if (status == BotStatus.RUNNING || status == BotStatus.STARTING) null else { path ->
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) { store.deleteFile(path) }
                        mainFile = store.mainFile
                        filesVersion++
                        if (deleted) exportShared()  // the shared copy follows unless it was edited there
                    }
                },
                lockedMessage = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ここを見るには、ストレージへのアクセスの許可が必要です。", fontSize = 12.sp)
                        TextButton(onClick = { requestStorage() }) { Text("許可する") }
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { pickFile.launch(arrayOf("*/*")) }) {
                    Text("📄 .pyファイルを追加")
                }
                OutlinedButton(onClick = { refreshShared() }) { Text("🔄 更新") }
            }
            if (hasStorage) {
                Help("sharedWarning", uiPrefs) { Text(SHARED_WARNING, fontSize = 12.sp, color = Color(0xFFC92A2A)) }
            }
        }

        Section("3. ライブラリ（pip install）") {
            OutlinedTextField(
                value = packages, onValueChange = { packages = it },
                label = { Text("pip install のコマンド（貼り付けOK）") },
                placeholder = { Text("pip install discord.py aiohttp") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            val names = PipInput.packages(packages)
            Text(
                if (names.isEmpty()) "インストールするものがありません" else "インストールするもの：${names.joinToString(", ")}",
                color = if (names.isEmpty()) Gray else Color.DarkGray,
                fontSize = 13.sp,
            )
            installResult?.let { WorkResultView(it) }
            Button(enabled = !busy && ready && names.isNotEmpty(), onClick = {
                workLog.clear()
                installResult = WorkResult.Working("インストール中…")
                runWork(PipCommands.install(rt, names, store.libsDir)) { code ->
                    installResult = if (code == 0) {
                        WorkResult.Done("インストール完了：${names.joinToString(", ")}")
                    } else {
                        WorkResult.Failed("インストールに失敗しました")
                    }
                    if (code == 0) busy = false
                }
            }) { Text("インストール") }
            if (!ready) Text("先に「1. Pythonの準備」をしてください", color = Gray, fontSize = 12.sp)
        }

        if (workLog.isNotEmpty()) {
            Section("作業の出力") {
                LogView(workLog, context)
            }
        }

        Section("4. 秘密の値（トークンなど）※使わなくてもOK") {
            Help("secrets", uiPrefs) {
                Text(
                    "トークンをBotのコードに直接書かずに済ませるための保管場所です。" +
                        "ここに入れた値は、Botの中で os.environ[\"名前\"] で読めます。" +
                        "コードにトークンを直接書いているなら、ここは空のままで大丈夫です。",
                    fontSize = 13.sp,
                    color = Color.DarkGray,
                )
            }
            secrets.forEachIndexed { index, row ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = row.name, onValueChange = { secrets[index] = row.copy(name = it); justSaved = false },
                            label = { Text("名前") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = row.value, onValueChange = { secrets[index] = row.copy(value = it); justSaved = false },
                            label = { Text("値") }, singleLine = true,
                            visualTransformation = if (row.shown) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { secrets[index] = row.copy(shown = !row.shown) }) {
                            Text(if (row.shown) "🙈 隠す" else "👁 表示")
                        }
                        TextButton(onClick = { secrets.removeAt(index); justSaved = false }) {
                            Text("🗑 削除", color = Red)
                        }
                    }
                }
            }
            val duplicates = duplicateNames(secrets.map { it.name })
            if (duplicates.isNotEmpty()) {
                Text(
                    "同じ名前が2つ以上あります：${duplicates.joinToString(", ")}（下の行の値が使われます）",
                    color = Red, fontSize = 12.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    secrets += SecretRow(if (secrets.none { it.name.trim() == "TOKEN" }) "TOKEN" else "", "")
                    justSaved = false
                }) { Text("＋追加") }
                Button(onClick = {
                    store.envVars = secrets.filter { it.name.isNotBlank() }.associate { it.name.trim() to it.value }
                    savedSecrets = store.envVars
                    justSaved = true
                }) { Text("保存") }
            }
            val current = secrets.filter { it.name.isNotBlank() }.associate { it.name.trim() to it.value }
            when {
                current != savedSecrets -> Text("まだ保存していません（「保存」を押すと次の起動から使われます）", color = Red, fontSize = 12.sp)
                justSaved -> Text("✓ 保存しました（次の起動から使われます）", color = Green, fontSize = 12.sp)
                savedSecrets.isNotEmpty() -> Text("保存済み：${savedSecrets.keys.joinToString(", ")}", color = Gray, fontSize = 12.sp)
            }
        }

        Section("5. AIモデル（LLM）") {
            Help("llm", uiPrefs) {
                Text(
                    "Discordで質問に答えるBot（askbot）が使うAIモデルです。" +
                        "ダウンロードしておくと、Botを起動したときに一緒に動きます。" +
                        "Botからは環境変数 LLM_URL で使えます。",
                    fontSize = 13.sp, color = Color.DarkGray,
                )
            }
            val botRunning = status == BotStatus.RUNNING || status == BotStatus.STARTING
            when (val s = llmState) {
                LlmState.Missing -> {
                    WorkResultView(WorkResult.Idle("まだダウンロードしていません"))
                    Button(onClick = { BotController.startModelDownload(context) }) { Text("ダウンロード（約1.25GB・Wi-Fi推奨）") }
                }
                is LlmState.Downloading -> {
                    WorkResultView(WorkResult.Working("ダウンロード中 ${s.percent}%（この画面を開いたままにしてください）"))
                    OutlinedButton(onClick = { BotController.cancelModelDownload() }) { Text("中止") }
                }
                LlmState.Ready -> {
                    WorkResultView(WorkResult.Done("準備完了（Botを起動すると一緒に動きます）"))
                    OutlinedButton(enabled = !botRunning, onClick = { confirmDeleteModel = true }) { Text("削除", color = Red) }
                    if (botRunning) Text("削除するにはBotを停止してください", color = Gray, fontSize = 12.sp)
                }
                is LlmState.Failed -> {
                    WorkResultView(WorkResult.Failed(s.message))
                    Button(onClick = { BotController.startModelDownload(context) }) { Text("もう一度ダウンロード（続きから）") }
                    // A way out when resuming keeps failing: start over from nothing.
                    OutlinedButton(enabled = !botRunning, onClick = { confirmDeleteModel = true }) { Text("削除して最初から", color = Red) }
                }
            }
            Text("使うCPUの数（次の起動から）", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in listOf(2, 4, 6)) {
                    if (n == llmThreads) {
                        Button(onClick = {}) { Text("$n") }
                    } else {
                        OutlinedButton(onClick = { store.llmThreads = n; llmThreads = n }) { Text("$n") }
                    }
                }
            }
        }

        if (confirmDeleteModel) {
            AlertDialog(
                onDismissRequest = { confirmDeleteModel = false },
                title = { Text("AIモデルを削除しますか？") },
                text = { Text("約1.25GBを空けます。使うときは、もう一度ダウンロードが必要です。") },
                confirmButton = {
                    TextButton(onClick = { BotController.deleteModel(context); confirmDeleteModel = false }) {
                        Text("削除", color = Red)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDeleteModel = false }) { Text("やめる") } },
            )
        }

        Section("6. 実行") {
            StatusRow(status, startedAt)
            val running = status == BotStatus.RUNNING || status == BotStatus.STARTING
            if (running) {
                Button(
                    onClick = { BotController.stop(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                ) { Text("停止") }
            } else {
                Button(
                    enabled = ready && mainFile != null && !busy,
                    onClick = {
                        scope.launch {
                            val changes = withContext(Dispatchers.IO) {
                                runCatching { BotController.sync(context)?.pendingImports() }.getOrNull()
                            }
                            if (changes != null && !changes.isEmpty) {
                                importThenStart = true
                                pendingImport = changes
                            } else {
                                launchBot()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                ) { Text("起動") }
            }
        }

        pendingImport?.let { changes ->
            SharedChangesDialog(
                changes,
                thenStart = importThenStart,
                onImport = {
                    pendingImport = null
                    scope.launch {
                        // Files changed again after confirming are not imported: ask again instead of going on.
                        val again = withContext(Dispatchers.IO) {
                            val sync = BotController.sync(context)
                            val skipped = sync?.importChanges(changes).orEmpty()
                            store.forgetMissingMain()  // the file to run first may have been deleted there
                            if (skipped.isEmpty()) null else sync?.pendingImports()
                        }
                        mainFile = store.mainFile
                        filesVersion++
                        when {
                            again != null && !again.isEmpty -> pendingImport = again
                            importThenStart && mainFile != null -> launchBot()
                            else -> exportShared()
                        }
                    }
                },
                onSkip = {
                    pendingImport = null
                    launchBot()
                },
                onCancel = { pendingImport = null },
            )
        }

        Section("7. ログ") {
            if (botLogs.isEmpty()) {
                Text("まだログはありません", color = Gray)
            } else {
                LogView(botLogs, context)
                OutlinedButton(onClick = { BotController.clearLogs() }) { Text("🗑 ログを消す", color = Red) }
            }
        }

        Section("8. 端末の設定（画面オフで動かすため）") {
            val ignoring = remember(status) {
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            }
            if (ignoring) {
                Text("✓ 電池の最適化から除外済み", color = Green, fontWeight = FontWeight.Bold)
            } else {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text("電池の最適化を除外する") }
            }
            Help("deviceSettings", uiPrefs) {
                Text(
                    "ASUSの「自動起動マネージャー」（モバイルマネージャー内）で Python Runner を許可してください。",
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * An explanation the user can close; it stays closed (remembered per [key]) until "説明を見る" is pressed.
 */
@Composable
private fun Help(key: String, prefs: SharedPreferences, content: @Composable () -> Unit) {
    val prefKey = "help.$key.closed"
    var open by remember { mutableStateOf(!prefs.getBoolean(prefKey, false)) }
    fun setOpen(value: Boolean) {
        open = value
        prefs.edit().putBoolean(prefKey, !value).apply()
    }
    if (open) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFFF1F3F5), RoundedCornerShape(8.dp)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
            TextButton(onClick = { setOpen(false) }) { Text("✕ 説明を閉じる") }
        }
    } else {
        TextButton(onClick = { setOpen(true) }) { Text("ℹ 説明を見る") }
    }
}

private const val SHARED_WARNING =
    "⚠ 共有のコピーは、ストレージの許可を持つ他のアプリからも読めます。トークンは .py や config.json などに書かず、" +
        "「4. 秘密の値」（アプリ専用）に入れてください。.env・鍵・データベース、名前に token や secret を含むファイルはコピーしません。"

/** Asks before running anything that changed in the shared copy, where other apps can write too. */
@Composable
private fun SharedChangesDialog(
    changes: SyncChanges,
    thenStart: Boolean,
    onImport: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("共有のコピーが変わっています") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Documents/PythonRunner/MyBot で、次のファイルが変わっています。", fontSize = 13.sp)
                changes.added.forEach { Text("＋ 追加：$it", color = Green, fontSize = 13.sp) }
                changes.modified.forEach { Text("✎ 変更：$it", color = Color(0xFFF08C00), fontSize = 13.sp) }
                changes.removed.forEach { Text("－ 削除：$it（実行用からも消えます）", color = Red, fontSize = 13.sp) }
                Text(
                    "自分で変えたものなら「${if (thenStart) "反映して起動" else "反映する"}」を押すと、実行用の MyBot も同じになります。" +
                        "「${if (thenStart) "反映せずに起動" else "反映しない"}」なら、実行用はそのままです。心当たりがない変更は、" +
                        "他のアプリが書き換えた可能性があるので反映しないでください。",
                    fontSize = 12.sp, color = Color.DarkGray,
                )
            }
        },
        confirmButton = { TextButton(onClick = onImport) { Text(if (thenStart) "反映して起動" else "反映する") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text(if (thenStart) "やめる" else "反映しない") }
                if (thenStart) TextButton(onClick = onSkip) { Text("反映せずに起動") }
            }
        },
    )
}

private fun statusText(status: BotStatus): String = when (status) {
    BotStatus.RUNNING -> "稼働中"
    BotStatus.STARTING -> "起動中"
    BotStatus.CRASHED -> "エラー"
    BotStatus.STOPPED -> "停止中"
}

/** One editable line of the secrets section. */
private data class SecretRow(val name: String, val value: String, val shown: Boolean = false)

/** The outcome of a one-shot job (preparing Python, pip install), shown so success or failure is unmistakable. */
private sealed class WorkResult(val message: String) {
    class Idle(message: String) : WorkResult(message)
    class Working(message: String) : WorkResult(message)
    class Done(message: String) : WorkResult(message)
    class Failed(message: String) : WorkResult(message)
}

@Composable
private fun WorkResultView(result: WorkResult) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (result) {
            is WorkResult.Working -> {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(result.message, fontWeight = FontWeight.Bold)
            }
            is WorkResult.Done -> Text("✓ " + result.message, color = Green, fontWeight = FontWeight.Bold)
            is WorkResult.Failed -> Column {
                Text("✗ " + result.message, color = Red, fontWeight = FontWeight.Bold)
                Text("下の「作業の出力」の「コピー」で内容を送ってください", color = Red, fontSize = 12.sp)
            }
            is WorkResult.Idle -> Text(result.message, color = Gray)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun StatusRow(status: BotStatus, startedAt: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (startedAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val (color, label) = when (status) {
        BotStatus.RUNNING -> Green to "稼働中"
        BotStatus.STARTING -> Green to "起動中…"
        BotStatus.CRASHED -> Red to "エラーで停止"
        BotStatus.STOPPED -> Gray to "停止中"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.Bold, color = color)
        if (startedAt != null) {
            Spacer(Modifier.width(8.dp))
            Text("・" + formatUptime(now - startedAt), color = Color.DarkGray)
        }
    }
}

@Composable
private fun LogView(lines: List<LogLine>, context: Context) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(Color(0xFF111111), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        items(lines) { line ->
            Text(
                line.text,
                color = if (line.isError) Color(0xFFFF8787) else Color(0xFF8CE99A),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
    OutlinedButton(onClick = {
        val text = lines.joinToString("\n") { it.text }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", text))
    }) { Text("コピー") }
}

private fun formatUptime(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "${h}時間${m}分" else "${m}分${s}秒"
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
