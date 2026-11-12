package com.pythonrunner.app.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.python.InstalledParts
import com.pythonrunner.app.python.Lib
import com.pythonrunner.app.python.Part
import com.pythonrunner.app.python.PartState
import com.pythonrunner.app.python.PipCommands
import com.pythonrunner.app.python.PipInput
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.ui.Gray
import com.pythonrunner.app.ui.Green
import com.pythonrunner.app.ui.LogView
import com.pythonrunner.app.ui.Section
import com.pythonrunner.app.ui.WorkResult
import com.pythonrunner.app.ui.WorkResultView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the bot has installed, the button that (re)installs its parts, and pip install for anything else. */
@Composable
fun LibraryTab(botId: String, packages: String, onPackagesChange: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rt = remember { BotController.runtime(context) }
    val busy by BotController.workBusy.collectAsStateWithLifecycle()
    val ready = remember(busy) { rt.isPipInstalled() }
    val store = remember(botId) { BotController.store(context, botId) }
    var refresh by remember(botId) { mutableIntStateOf(0) }
    // Read from the files, off the main thread; again after every install.
    val installed by produceState<Installed?>(null, botId, busy, refresh) {
        value = withContext(Dispatchers.IO) {
            val parts = InstalledParts.parts(store.codeDir, store.partsInstalled)
            Installed(
                parts,
                InstalledParts.pythonLibs(store.libsDir),
                parts.filter { it.isNode }.associate { part ->
                    part.key to InstalledParts.nodeLibs(if (part.key == ".") store.codeDir else File(store.codeDir, part.key))
                },
            )
        }
    }

    installed?.takeIf { it.parts.isNotEmpty() }?.let { now ->
        PartsSection(botId, now, busy) { refresh++ }
    }

    var installResult by remember(botId) { mutableStateOf<WorkResult?>(null) }
    val workLog = remember(botId) { mutableStateListOf<LogLine>() }
    Section("ほかのライブラリを足す（pip install）") {
        OutlinedTextField(
            value = packages, onValueChange = onPackagesChange,
            label = { Text("pip install のコマンド（貼り付けOK）") },
            placeholder = { Text("pip install requests") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        val names = PipInput.packages(packages)
        if (names.isEmpty()) {
            Text("インストールするものがありません", color = Gray, fontSize = 13.sp)
        } else {
            val libs = installed?.pythonLibs.orEmpty()
            names.forEach { name ->
                // A requirement may carry a version (discord.py>=2.6): look the bare name up.
                val bare = name.split('=', '<', '>', '!', '~', '[', ';', ' ').first()
                val lib = InstalledParts.find(libs, bare)
                Text(
                    if (lib != null) "${lib.name} ${lib.version} は入っています（押すと最新にします）" else "$bare はまだ入っていません",
                    color = if (lib != null) Green else Color.DarkGray, fontSize = 13.sp,
                )
            }
        }
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
                    // Cleared here, not on the screen's scope: leaving the tab mid-install must not
                    // leave every pip and 取り込む button disabled.
                    BotController.workBusy.value = false
                    scope.launch {
                        refresh++
                        installResult = if (code == 0) {
                            WorkResult.Done("インストール完了：${names.joinToString(", ")}")
                        } else {
                            WorkResult.Failed("インストールに失敗しました")
                        }
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

private class Installed(val parts: List<Part>, val pythonLibs: List<Lib>, val nodeLibs: Map<String, List<Lib>>)

@Composable
private fun PartsSection(botId: String, installed: Installed, busy: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val botParts = installed.parts
    val partsLog = remember { mutableStateListOf<LogLine>() }
    var result by remember { mutableStateOf<WorkResult?>(null) }
    val allOk = botParts.all { it.state == PartState.OK }
    Section("入っているもの") {
        val python = botParts.filter { !it.isNode }
        if (python.isNotEmpty()) {
            Text("Python のライブラリ", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            python.forEach { PartLine("${it.label}/requirements.txt".removePrefix("${InstalledParts.TOP}/"), it) }
            LibList(installed.pythonLibs)
        }
        botParts.filter { it.isNode }.forEach { part ->
            Text("Node.js の部品（${part.label}）", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            PartLine(null, part)
            LibList(installed.nodeLibs[part.key].orEmpty())
        }
        result?.let { WorkResultView(it) }
        Button(enabled = !busy, onClick = {
            partsLog.clear()
            result = WorkResult.Working("部品を入れています…（数分かかります）")
            BotController.workBusy.value = true
            BotController.runBotParts(
                context, botId,
                onLine = { line -> ContextCompat.getMainExecutor(context).execute { partsLog += line } },
                onExit = { code ->
                    ContextCompat.getMainExecutor(context).execute {
                        BotController.workBusy.value = false
                        result = if (code == 0) WorkResult.Done("部品を入れました") else WorkResult.Failed("部品を入れられませんでした")
                        onDone()
                    }
                },
            )
        }) { Text(if (allOk) "部品を入れ直す" else "部品を入れる") }
        Text(
            "ファイルを入れ替えたときや、動かないときに押します。Bot は自動で止めて、終わったら起動し直します。",
            fontSize = 12.sp, color = Color.DarkGray,
        )
        if (partsLog.isNotEmpty()) LogView(partsLog, context, height = 320.dp)
    }
}

/** One part's state: ✓ with the day it was installed, ✗ not installed, ! changed since. */
@Composable
private fun PartLine(title: String?, part: Part) {
    val (text, color) = when (part.state) {
        PartState.OK -> "✓ ${DAY.format(Date(part.installedAt ?: 0))} 入れました" to Green
        PartState.MISSING -> "✗ まだ入っていません" to Color(0xFFC92A2A)
        PartState.STALE -> "! 入れ直してください（ファイルが変わりました）" to Color(0xFFE8590C)
    }
    Text(if (title != null) "$title　$text" else text, color = color, fontSize = 13.sp)
}

/** The first three libraries, then 「…ほか n 個」, which shows them all when tapped. */
@Composable
private fun LibList(libs: List<Lib>) {
    if (libs.isEmpty()) return
    var all by remember(libs) { mutableStateOf(false) }
    val shown = if (all) libs else libs.take(3)
    Column(Modifier.clickable(enabled = libs.size > 3) { all = !all }) {
        Text(
            shown.joinToString("・") { "${it.name} ${it.version}" } + if (!all && libs.size > 3) " …ほか ${libs.size - 3} 個" else "",
            fontSize = 12.sp, color = Color.DarkGray,
        )
    }
}

private val DAY = SimpleDateFormat("M/d", Locale.JAPAN)
