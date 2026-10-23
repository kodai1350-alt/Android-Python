package com.pythonrunner.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.python.PipCommands
import com.pythonrunner.app.service.BotController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Green = Color(0xFF2F9E44)
private val Red = Color(0xFFE03131)
private val Gray = Color(0xFFADB5BD)

private const val SELF_CHECK =
    "import sys, ssl, sqlite3, ctypes; print(sys.version); print(ssl.OPENSSL_VERSION)"

/** The trial version's single screen: work through the sections top to bottom to run one bot. */
@Composable
fun SpikeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rt = remember { BotController.runtime(context) }
    val store = remember { BotController.store(context) }

    val status by BotController.status.collectAsStateWithLifecycle()
    val startedAt by BotController.startedAtMillis.collectAsStateWithLifecycle()
    val botLogs by BotController.logs.collectAsStateWithLifecycle()

    val workLog = remember { mutableStateListOf<LogLine>() }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(rt.isPipInstalled()) }
    var mainFile by remember { mutableStateOf(store.mainFile) }
    var packages by remember { mutableStateOf("discord.py") }
    val secrets = remember {
        mutableStateListOf<Pair<String, String>>().apply { addAll(store.envVars.toList()) }
    }
    var secretsSaved by remember { mutableStateOf(false) }

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

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val name = displayName(context, uri) ?: "main.py"
                context.contentResolver.openInputStream(uri)!!.use { store.importPy(name, it) }
                mainFile = store.mainFile
            } catch (e: Exception) {
                workLog += LogLine(System.currentTimeMillis(), "ファイルを取り込めませんでした: ${e.message}", true)
            }
        }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        BotController.start(context)
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Python Runner（お試し版）", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Section("1. Pythonの準備") {
            if (ready) Text("✓ 準備完了", color = Green, fontWeight = FontWeight.Bold)
            Button(enabled = !busy, onClick = {
                workLog.clear()
                runWork(PipCommands.bootstrap(rt)) { code ->
                    if (code == 0) {
                        runWork(listOf(rt.executable.path, "-c", SELF_CHECK)) { checkCode ->
                            ready = checkCode == 0
                            busy = false
                        }
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
            OutlinedButton(enabled = !busy, onClick = { pickFile.launch(arrayOf("*/*")) }) {
                Text("📄 .pyファイルを選ぶ")
            }
        }

        Section("3. ライブラリ") {
            OutlinedTextField(
                value = packages, onValueChange = { packages = it },
                label = { Text("パッケージ名（スペース区切り）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = !busy && ready && packages.isNotBlank(), onClick = {
                workLog.clear()
                val names = packages.trim().split(Regex("\\s+"))
                runWork(PipCommands.install(rt, names, store.libsDir)) { code ->
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

        Section("4. 秘密の値（トークンなど）") {
            secrets.forEachIndexed { index, (name, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name, onValueChange = { secrets[index] = it to value; secretsSaved = false },
                        label = { Text("名前") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = value, onValueChange = { secrets[index] = name to it; secretsSaved = false },
                        label = { Text("値") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { secrets += "TOKEN" to ""; secretsSaved = false }) { Text("＋追加") }
                Button(onClick = {
                    store.envVars = secrets.filter { it.first.isNotBlank() }.toMap()
                    secretsSaved = true
                }) { Text("保存") }
            }
            if (secretsSaved) Text("✓ 保存しました（次の起動から使われます）", color = Green, fontSize = 12.sp)
        }

        Section("5. 実行") {
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
                        if (Build.VERSION.SDK_INT >= 33) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            BotController.start(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                ) { Text("起動") }
            }
        }

        Section("6. ログ") {
            if (botLogs.isEmpty()) Text("まだログはありません", color = Gray) else LogView(botLogs, context)
        }

        Section("7. 端末の設定（画面オフで動かすため）") {
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
            Text(
                "ASUSの「自動起動マネージャー」（モバイルマネージャー内）で Python Runner を許可してください。",
                fontSize = 13.sp,
            )
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
