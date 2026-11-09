package com.pythonrunner.app.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.python.PipCommands
import com.pythonrunner.app.service.BotController
import kotlinx.coroutines.launch

private const val SELF_CHECK =
    "import sys, ssl, sqlite3, ctypes; print('Python', sys.version.split()[0]); print(ssl.OPENSSL_VERSION)"

/** Settings of the whole app, not of one bot: Python itself, storage access, and the phone's power settings. */
@Composable
fun AppSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = rememberUiPrefs()
    val rt = remember { BotController.runtime(context) }
    val busy by BotController.workBusy.collectAsStateWithLifecycle()
    var ready by remember { mutableStateOf(rt.isPipInstalled()) }
    var prepResult by remember { mutableStateOf<WorkResult?>(null) }
    val workLog = remember { mutableStateListOf<LogLine>() }
    var hasStorage by remember { mutableStateOf(BotController.hasSharedStorageAccess(context)) }
    var ignoring by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasStorage = BotController.hasSharedStorageAccess(context)
        ignoring = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val requestStorage = rememberStorageRequest { hasStorage = BotController.hasSharedStorageAccess(context) }

    // Runs one Python command and streams its output into the work log.
    fun runWork(command: List<String>, then: (Int) -> Unit) {
        BotController.workBusy.value = true
        // runOneShot prepends the launcher itself, so drop it from the full command.
        BotController.runOneShot(
            context, null, command.drop(1),
            onLine = { line -> scope.launch { workLog += line } },
            // On the main thread, not the screen's scope: leaving the screen mid-job must still run the next
            // step and clear the busy flag, or every pip and 取り込む button would stay disabled.
            onExit = { code ->
                ContextCompat.getMainExecutor(context).execute {
                    then(code)
                    if (code != 0) BotController.workBusy.value = false
                }
            },
        )
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 戻る") }
            Text("アプリの設定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Section("Pythonの準備") {
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
                            val versions = workLog.drop(checkFrom).filter { !it.isError }.joinToString(" / ") { it.text }
                            WorkResult.Done("準備完了：$versions")
                        } else {
                            WorkResult.Failed("準備に失敗しました（Pythonの動作確認でエラー）")
                        }
                        BotController.workBusy.value = false
                    }
                }
            }) { Text(if (ready) "もう一度準備する" else "Pythonを準備") }
            Help("python", prefs) {
                Text("ライブラリ（pip install）を使う前に1回だけ必要です。Botを動かすだけなら、なくても動きます。", fontSize = 12.sp, color = Color.DarkGray)
            }
            if (workLog.isNotEmpty()) LogView(workLog, context, height = 200.dp)
        }

        Section("ファイルの共有") {
            if (hasStorage) {
                Text("✓ ストレージへのアクセスを許可済み", color = Green, fontWeight = FontWeight.Bold)
            } else {
                Button(onClick = requestStorage) { Text("ストレージへのアクセスを許可する") }
            }
            Help("storage", prefs) {
                Text(
                    "許可すると、各Botのファイルのコピーを ドキュメント › PythonRunner › Bot名 に置きます。" +
                        "ファイルアプリやPCで見たり直したりでき、変更はアプリが自動で見つけて「✏ 変更あり」と知らせます。",
                    fontSize = 12.sp, color = Color.DarkGray,
                )
                Text(SHARED_WARNING, fontSize = 12.sp, color = Color(0xFFC92A2A))
            }
        }

        Section("端末の設定（画面オフで動かすため）") {
            if (ignoring) {
                Text("✓ 電池の最適化から除外済み", color = Green, fontWeight = FontWeight.Bold)
            } else {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text("電池の最適化を除外する") }
            }
            Help("deviceSettings", prefs) {
                Text("ASUSの「自動起動マネージャー」（モバイルマネージャー内）で Python Runner を許可してください。", fontSize = 13.sp)
            }
        }
    }
}
