package com.pythonrunner.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.files.SharedState
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.service.BotRunState
import com.pythonrunner.app.service.ImportState
import com.pythonrunner.app.ui.tabs.FilesTab
import com.pythonrunner.app.ui.tabs.LibraryTab
import com.pythonrunner.app.ui.tabs.LogTab
import com.pythonrunner.app.ui.tabs.SettingsTab

private val TABS = listOf("ログ", "ファイル", "ライブラリ", "設定")

/** One bot: its state and buttons on top, then ログ / ファイル / ライブラリ / 設定. */
@Composable
fun BotDetailScreen(
    botId: String,
    imports: ImportActions,
    importResult: ImportState.Done?,
    onImportResultSeen: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val actions = rememberBotActions()
    val bots by BotController.bots.collectAsStateWithLifecycle()
    val bot = bots.firstOrNull { it.id == botId }
    // The bot was deleted (here or meanwhile): nothing left to show.
    LaunchedEffect(bot == null) { if (bot == null) onBack() }
    if (bot == null) return

    val runStates by BotController.runStates.collectAsStateWithLifecycle()
    val run = runStates[botId] ?: BotRunState()
    val sharedStates by BotController.sharedStates.collectAsStateWithLifecycle()
    val importState by BotController.importState.collectAsStateWithLifecycle()
    var tab by rememberSaveable(botId) { mutableStateOf(if (run.logs.isEmpty()) 1 else 0) }
    var mainFile by remember(botId) { mutableStateOf(BotController.store(context, botId).mainFile) }
    var filesVersion by remember(botId) { mutableStateOf(0) }
    var packages by rememberSaveable(botId) { mutableStateOf("pip install discord.py") }
    var importNote by remember(botId) { mutableStateOf<String?>(null) }
    var askRestart by remember(botId) { mutableStateOf(false) }
    // A file open in Notepad (relative path), shown instead of the tabs.
    var editing by rememberSaveable(botId) { mutableStateOf<String?>(null) }
    // Files changed by the files screen under a running bot: ask once whether to restart it.
    val changedWhileRunning by BotController.filesChangedWhileRunning.collectAsStateWithLifecycle()
    var askedRestartThisVisit by remember(botId) { mutableStateOf(false) }
    LaunchedEffect(botId in changedWhileRunning) {
        if (botId in changedWhileRunning) {
            BotController.filesChangedWhileRunning.value = BotController.filesChangedWhileRunning.value - botId
            if (!askedRestartThisVisit) {
                askedRestartThisVisit = true
                askRestart = true
            }
        }
    }
    val importingHere = BotController.importingBotId() == botId

    LaunchedEffect(importResult) {
        val done = importResult ?: return@LaunchedEffect
        // The person just imported these: use their requirements.txt over what the box held.
        done.requirements?.let { packages = it }
        mainFile = BotController.store(context, botId).mainFile
        filesVersion++
        importNote = "✓ ${done.count}個のファイルを取り込みました" +
            if (done.requirements != null) "（requirements.txt が見つかりました：「ライブラリ」タブでまとめて入れられます）" else ""
        askRestart = done.botWasRunning
        onImportResultSeen()
    }
    // Files may change under the screen (反映する, the bot itself): refresh what the tabs show.
    LaunchedEffect(sharedStates[botId], run.status) {
        mainFile = BotController.store(context, botId).mainFile
        filesVersion++
    }

    editing?.let { path ->
        Column(Modifier.padding(horizontal = 12.dp)) {
            NotepadScreen(
                botId, path,
                running = run.status == BotStatus.RUNNING || run.status == BotStatus.STARTING,
                onClose = { editing = null; filesVersion++ },
            )
        }
        return
    }

    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 一覧") }
            Text(
                bot.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { StatusRow(run.status, run.startedAtMillis) }
            val running = run.status == BotStatus.RUNNING || run.status == BotStatus.STARTING
            if (running) {
                OutlinedButton(onClick = { BotController.restart(context, botId) }) { Text("再起動") }
                Button(
                    onClick = { BotController.stop(context, botId) },
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    modifier = Modifier.padding(start = 6.dp),
                ) { Text("停止") }
            } else {
                Button(
                    // Never start on a folder an import is still moving files into.
                    enabled = mainFile != null && !importingHere,
                    onClick = { actions.start(botId) },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                ) { Text("起動") }
            }
        }
        if (mainFile == null) Text("最初に実行するファイルを「ファイル」タブか「設定」タブで選んでください", fontSize = 12.sp, color = Gray)
        when (val shared = sharedStates[botId]) {
            is SharedState.Changed -> TextButton(onClick = { actions.review(botId, shared.changes) }) {
                Text("✏ 変更あり（タップして確かめる）", color = Orange, fontWeight = FontWeight.Bold)
            }
            SharedState.Missing -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF5F5), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
            ) {
                Text("📁 フォルダが見つかりません", color = Red, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { BotController.recreateShared(context, botId) }) { Text("フォルダを作り直す") }
            }
            else -> Unit
        }

        PrimaryTabRow(selectedTabIndex = tab) {
            TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                0 -> LogTab(botId, run.logs)
                1 -> FilesTab(
                    botId = botId,
                    name = bot.name,
                    status = run.status,
                    mainFile = mainFile,
                    filesVersion = filesVersion,
                    importState = importState,
                    importNote = importNote,
                    imports = imports,
                    onMainChanged = { mainFile = it },
                    onFilesChanged = { mainFile = BotController.store(context, botId).mainFile; filesVersion++ },
                    onImportStarted = { importNote = null },
                    onOpenFile = { editing = it },
                )
                2 -> LibraryTab(botId, packages, onPackagesChange = { packages = it })
                3 -> SettingsTab(
                    botId = botId,
                    name = bot.name,
                    mainFile = mainFile,
                    filesVersion = filesVersion,
                    onMainChanged = { mainFile = it },
                    onOpenFile = { editing = it },
                )
            }
        }
    }

    if (askRestart) {
        RestartDialog(
            onRestart = { askRestart = false; BotController.restart(context, botId) },
            onLater = { askRestart = false },
        )
    }
}
