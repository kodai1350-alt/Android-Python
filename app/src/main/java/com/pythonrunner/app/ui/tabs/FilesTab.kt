package com.pythonrunner.app.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.service.ImportState
import com.pythonrunner.app.ui.Explorer
import com.pythonrunner.app.ui.Gray
import com.pythonrunner.app.ui.Green
import com.pythonrunner.app.ui.Help
import com.pythonrunner.app.ui.ImportActions
import com.pythonrunner.app.ui.SHARED_WARNING
import com.pythonrunner.app.ui.folderLabel
import com.pythonrunner.app.ui.rememberStorageRequest
import com.pythonrunner.app.ui.rememberUiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The bot's files in one place, the ways to bring new ones in, and long-press delete. */
@Composable
fun FilesTab(
    botId: String,
    name: String,
    status: BotStatus,
    mainFile: String?,
    filesVersion: Int,
    importState: ImportState,
    importNote: String?,
    imports: ImportActions,
    onMainChanged: (String) -> Unit,
    onFilesChanged: () -> Unit,
    onImportStarted: () -> Unit,
    /** A file was opened (tapped again): Notepad. */
    onOpenFile: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = rememberUiPrefs()
    val store = remember(botId) { BotController.store(context, botId) }
    val workBusy by BotController.workBusy.collectAsStateWithLifecycle()
    var hasStorage by remember { mutableStateOf(BotController.hasSharedStorageAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasStorage = BotController.hasSharedStorageAccess(context) }
    val requestStorage = rememberStorageRequest { hasStorage = BotController.hasSharedStorageAccess(context) }
    val importBusy = importState is ImportState.Working || importState is ImportState.Confirm || importState is ImportState.ChooseName
    val running = status == BotStatus.RUNNING || status == BotStatus.STARTING
    var path by rememberSaveable(botId) { mutableStateOf("") }
    val canImport = !importBusy && !workBusy

    Text(
        mainFile?.let { "▶ 最初に実行: $it" } ?: "最初に実行するファイルがまだ選ばれていません",
        color = if (mainFile != null) Green else Gray,
        fontWeight = FontWeight.Bold,
    )
    if (!hasStorage) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFFFFF4E6), RoundedCornerShape(8.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Botのファイルを、スマホのファイルアプリやPCからも見えるようにしませんか？", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("許可すると ${folderLabel(name)} にファイルが並びます。", fontSize = 12.sp, color = Color.DarkGray)
            Button(onClick = requestStorage) { Text("ストレージへのアクセスを許可する") }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(enabled = canImport, onClick = { onImportStarted(); imports.pickFolder(botId, path) }, modifier = Modifier.weight(1f)) {
            Text("📁 フォルダを取り込む", fontSize = 13.sp)
        }
        OutlinedButton(enabled = canImport, onClick = { onImportStarted(); imports.pickFile(botId, path) }, modifier = Modifier.weight(1f)) {
            Text("📄 ファイルを取り込む", fontSize = 13.sp)
        }
    }
    Box {
        var replaceOpen by remember { mutableStateOf(false) }
        OutlinedButton(enabled = canImport, onClick = { replaceOpen = true }) { Text("🔄 Botをまるごと入れ替える", fontSize = 13.sp) }
        DropdownMenu(expanded = replaceOpen, onDismissRequest = { replaceOpen = false }) {
            DropdownMenuItem(text = { Text("フォルダを選ぶ") }, onClick = { replaceOpen = false; onImportStarted(); imports.replaceBot(botId, false) })
            DropdownMenuItem(text = { Text("ZIPを選ぶ") }, onClick = { replaceOpen = false; onImportStarted(); imports.replaceBot(botId, true) })
        }
    }
    if (importBusy && BotController.importingBotId() != botId) Text("ほかのBotの取り込み中です", fontSize = 12.sp, color = Gray)
    importNote?.let { Text(it, color = Green, fontSize = 13.sp) }

    Explorer(
        botId = botId,
        rootLabel = name,
        path = path,
        onPathChange = { path = it },
        mainFile = mainFile,
        refreshKey = filesVersion to status,
        enabled = !workBusy && BotController.importingBotId() != botId,
        onOpenFile = onOpenFile,
        onSetMain = { p ->
            store.mainFile = p
            onMainChanged(p)
        },
    )
    Text(
        "場所：${folderLabel(name)}（PC やファイルアプリからも同じ中身が見えます）",
        fontSize = 12.sp, color = Gray,
    )
    Help("files", prefs) {
        Text(
            "PC のエクスプローラーと同じように使えます。タップで選び、もう一度タップで開きます。長押しは右クリックと同じメニューです。" +
                "削除したものは「ごみ箱」から戻せます。PC で直したフォルダは『フォルダを取り込む』か『Botをまるごと入れ替える』で入れます。",
            fontSize = 12.sp, color = Color.DarkGray,
        )
    }
    if (hasStorage) {
        Help("sharedWarning", prefs) { Text(SHARED_WARNING, fontSize = 12.sp, color = Color(0xFFC92A2A)) }
    }
}
