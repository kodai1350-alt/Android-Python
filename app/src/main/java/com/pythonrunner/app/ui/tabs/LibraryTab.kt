package com.pythonrunner.app.ui.tabs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.python.PipCommands
import com.pythonrunner.app.python.PipInput
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.ui.Gray
import com.pythonrunner.app.ui.LogView
import com.pythonrunner.app.ui.Section
import com.pythonrunner.app.ui.WorkResult
import com.pythonrunner.app.ui.WorkResultView
import kotlinx.coroutines.launch

/** pip install into this bot's own packages; one pip job runs at a time in the whole app. */
@Composable
fun LibraryTab(botId: String, packages: String, onPackagesChange: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rt = remember { BotController.runtime(context) }
    val busy by BotController.workBusy.collectAsStateWithLifecycle()
    val ready = remember(busy) { rt.isPipInstalled() }
    var installResult by remember(botId) { mutableStateOf<WorkResult?>(null) }
    val workLog = remember(botId) { mutableStateListOf<LogLine>() }
    val store = remember(botId) { BotController.store(context, botId) }

    Section("ライブラリ（pip install）") {
        OutlinedTextField(
            value = packages, onValueChange = onPackagesChange,
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
            BotController.workBusy.value = true
            val command = PipCommands.install(rt, names, store.libsDir)
            // runOneShot prepends the launcher itself, so drop it from the full command.
            BotController.runOneShot(
                context, botId, command.drop(1),
                onLine = { line -> scope.launch { workLog += line } },
                onExit = { code ->
                    scope.launch {
                        installResult = if (code == 0) {
                            WorkResult.Done("インストール完了：${names.joinToString(", ")}")
                        } else {
                            WorkResult.Failed("インストールに失敗しました")
                        }
                        BotController.workBusy.value = false
                    }
                },
            )
        }) { Text("インストール") }
        when {
            !ready -> Text("先に「⚙ 設定」の「Pythonの準備」をしてください", color = Gray, fontSize = 12.sp)
            busy && installResult !is WorkResult.Working -> Text("ほかの作業（pipなど）の途中です", color = Gray, fontSize = 12.sp)
        }
    }
    if (workLog.isNotEmpty()) {
        Section("作業の出力") { LogView(workLog, context) }
    }
}
