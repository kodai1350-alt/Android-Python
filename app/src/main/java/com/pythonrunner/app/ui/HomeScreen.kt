package com.pythonrunner.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pythonrunner.app.bot.BotEntry
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.files.SharedState
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.service.BotRunState
import com.pythonrunner.app.service.ImportState

/** How a new bot comes in: a file or a folder picked with Android's pickers, for bot id or a new bot (null). */
/**
 * The file pickers. [pickFile] and [pickFolder] import into bot botId (inside its folder into; a new bot
 * when botId is null); [replaceBot] makes a picked folder or ZIP the whole bot.
 */
class ImportActions(
    val pickFile: (botId: String?, into: String) -> Unit,
    val pickFolder: (botId: String?, into: String) -> Unit,
    val replaceBot: (botId: String, zip: Boolean) -> Unit,
)

/** Every bot at a glance: how many run, which stopped with an error, and where their files are. */
@Composable
fun HomeScreen(imports: ImportActions, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val prefs = rememberUiPrefs()
    val actions = rememberBotActions()
    val bots by BotController.bots.collectAsStateWithLifecycle()
    val runStates by BotController.runStates.collectAsStateWithLifecycle()
    val sharedStates by BotController.sharedStates.collectAsStateWithLifecycle()
    val unlisted by BotController.unlistedFolders.collectAsStateWithLifecycle()
    val importState by BotController.importState.collectAsStateWithLifecycle()
    val importBusy = importState is ImportState.Working || importState is ImportState.Confirm || importState is ImportState.ChooseName
    // Runs the add once the "more than 10" note was answered (or right away).
    var pendingAdd by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun add(action: () -> Unit) {
        if (bots.size >= 10 && !prefs.getBoolean(MANY_BOTS_SHOWN, false)) {
            pendingAdd = action
        } else {
            action()
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Python Runner", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("⚙ 設定", fontSize = 16.sp) }
        }

        val states = bots.map { runStates[it.id] ?: BotRunState() }
        Summary(
            running = states.count { it.status == BotStatus.RUNNING || it.status == BotStatus.STARTING },
            errors = states.count { it.status == BotStatus.CRASHED },
            stopped = states.count { it.status == BotStatus.STOPPED },
            total = bots.size,
        )

        if (bots.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFFF1F3F5), RoundedCornerShape(12.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("最初のBotを追加しましょう", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("下の「＋ Botを追加」で、Botの .py・ZIP・フォルダを選びます。", fontSize = 13.sp, color = Color.DarkGray)
            }
        }

        bots.forEach { bot ->
            BotCard(
                bot = bot,
                state = runStates[bot.id] ?: BotRunState(),
                shared = sharedStates[bot.id],
                importing = BotController.importingBotId() == bot.id,
                onOpen = { onOpen(bot.id) },
                onStart = { actions.start(bot.id) },
                onStop = { BotController.stop(context, bot.id) },
                onReview = { changes -> actions.review(bot.id, changes) },
                onRecreate = { BotController.recreateShared(context, bot.id) },
            )
        }

        AddBotButton(
            busy = importBusy,
            onPickFile = { add { imports.pickFile(null, "") } },
            onPickFolder = { add { imports.pickFolder(null, "") } },
        )

        unlisted.forEach { folder ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(Color(0xFFE7F5FF), RoundedCornerShape(8.dp)).padding(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("新しいフォルダが見つかりました：$folder", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("📁 ${folderLabel(folder)}", fontSize = 12.sp, color = Color.DarkGray)
                    if (importBusy) Text("ほかのBotの取り込み中です", fontSize = 12.sp, color = Gray)
                }
                OutlinedButton(enabled = !importBusy, onClick = { add { BotController.adoptFolder(context, folder) } }) { Text("追加する") }
            }
        }
    }

    pendingAdd?.let { action ->
        ManyBotsDialog(
            onOk = {
                prefs.edit().putBoolean(MANY_BOTS_SHOWN, true).apply()
                pendingAdd = null
                action()
            },
            onCancel = { pendingAdd = null },
        )
    }
}

private const val MANY_BOTS_SHOWN = "manyBotsShown"

@Composable
private fun Summary(running: Int, errors: Int, stopped: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFF1F3F5), RoundedCornerShape(12.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Count("稼働中", "$running", Green, suffix = " / $total")
        Count("エラー", "$errors", if (errors > 0) Red else Color.DarkGray)
        Count("停止中", "$stopped", Color.DarkGray)
    }
}

@Composable
private fun Count(label: String, value: String, color: Color, suffix: String = "") {
    Column {
        Text(label, fontSize = 12.sp, color = Color.DarkGray)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
            if (suffix.isNotEmpty()) Text(suffix, fontSize = 14.sp, color = Color.DarkGray, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun BotCard(
    bot: BotEntry,
    state: BotRunState,
    shared: SharedState?,
    importing: Boolean,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReview: (com.pythonrunner.app.files.SyncChanges) -> Unit,
    onRecreate: () -> Unit,
) {
    val context = LocalContext.current
    val crashed = state.status == BotStatus.CRASHED
    val shape = RoundedCornerShape(12.dp)
    val mainFile = remember(bot.id, state.status, importing) { BotController.store(context, bot.id).mainFile }
    Column(
        Modifier.fillMaxWidth()
            .border(if (crashed) 2.dp else 1.dp, if (crashed) Red else Color(0xFFDEE2E6), shape)
            .background(Color.White, shape)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bot.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusRow(state.status, state.startedAtMillis, fontSize = 13)
            }
            val running = state.status == BotStatus.RUNNING || state.status == BotStatus.STARTING
            if (running) {
                Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("停止") }
            } else {
                Button(
                    enabled = mainFile != null && !importing,
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                ) { Text("起動") }
            }
        }
        if (mainFile == null && state.status == BotStatus.STOPPED) {
            Text("最初に実行するファイルを選んでください（タップして開く）", fontSize = 12.sp, color = Gray)
        }
        val recent = state.logs.takeLast(2)
        if (recent.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(Color(0xFF111111), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                recent.forEach { LogText(it, maxLines = 2) }
            }
        }
        when (shared) {
            is SharedState.Changed -> TextButton(onClick = { onReview(shared.changes) }) {
                Text("✏ 変更あり（タップして確かめる）", color = Orange, fontWeight = FontWeight.Bold)
            }
            SharedState.Missing -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📁 フォルダが見つかりません", color = Red, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onRecreate) { Text("フォルダを作り直す") }
            }
            else -> Unit
        }
        Text("📁 ${folderLabel(bot.name)}", fontSize = 12.sp, color = Color.DarkGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddBotButton(busy: Boolean, onPickFile: () -> Unit, onPickFolder: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Box {
            Button(enabled = !busy, onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text("＋ Botを追加", fontSize = 16.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("ファイルを選ぶ（.py・ZIP など）") }, onClick = { open = false; onPickFile() })
                DropdownMenuItem(text = { Text("フォルダを選ぶ") }, onClick = { open = false; onPickFolder() })
            }
        }
        if (busy) {
            Spacer(Modifier.width(4.dp))
            Text("ほかのBotの取り込み中です", fontSize = 12.sp, color = Gray)
        }
    }
}
