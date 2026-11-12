package com.pythonrunner.app.ui.tabs

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.pythonrunner.app.ui.ImportButton
import com.pythonrunner.app.ui.Place
import com.pythonrunner.app.ui.PlaceGroup
import com.pythonrunner.app.ui.SHARED_WARNING
import com.pythonrunner.app.ui.folderLabel
import com.pythonrunner.app.ui.rememberStorageRequest
import com.pythonrunner.app.ui.rememberUiPrefs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The bot's files where they are seen from the Files app, the way in (📥 取り込む), and the explorer. */
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

    Text("📍 ${folderLabel(name)}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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

    ImportButton(
        enabled = !importBusy && !workBusy,
        onPickFile = { onImportStarted(); imports.pickFile(botId, "") },
        onPickFolder = { onImportStarted(); imports.pickFolder(botId, "") },
    )
    if (importBusy && BotController.importingBotId() != botId) Text("ほかのBotの取り込み中です", fontSize = 12.sp, color = Gray)
    importNote?.let { Text(it, color = Green, fontSize = 13.sp) }
    Help("import", prefs) {
        Text(
            "「📥 取り込む」で、.py・ZIP・そのほかのファイル、またはフォルダごと取り込めます。" +
                "同じ名前のファイルは新しいものに置き換わり、ほかのファイルは残ります。" +
                "Googleドライブのフォルダは、ドライブでZIPとしてダウンロードしてから「ファイルを選ぶ」で取り込めます。",
            fontSize = 12.sp, color = Color.DarkGray,
        )
    }

    val places = remember(botId, name, hasStorage, status) {
        val storageRoot = Environment.getExternalStorageDirectory()
        listOfNotNull(
            Place(PlaceGroup.BOT, name, store.codeDir.apply { mkdirs() }, isBotFolder = true),
            Place(PlaceGroup.SHARED, "$name（共有フォルダ）", BotController.sharedDir(context, botId)?.takeIf { hasStorage }),
            Place(PlaceGroup.SHARED, "Documents", File(storageRoot, Environment.DIRECTORY_DOCUMENTS).takeIf { hasStorage }),
            Place(PlaceGroup.SHARED, "Download", File(storageRoot, Environment.DIRECTORY_DOWNLOADS).takeIf { hasStorage }),
            Place(PlaceGroup.SHARED, "内部ストレージ", storageRoot.takeIf { hasStorage }),
            Place(PlaceGroup.APP, "ライブラリ（pip）", store.libsDir.apply { mkdirs() }),
            Place(PlaceGroup.APP, "Python本体", BotController.runtime(context).home),
        )
    }
    Explorer(
        places = places,
        mainFile = mainFile,
        refreshKey = filesVersion to status,
        onSetMain = { path ->
            store.mainFile = path
            onMainChanged(path)
        },
        onDelete = if (running || BotController.importingBotId() == botId) null else { path ->
            scope.launch {
                val deleted = withContext(Dispatchers.IO) { store.deleteFile(path) }
                onFilesChanged()
                // The shared copy follows unless it was edited there.
                if (deleted) withContext(Dispatchers.IO) { runCatching { BotController.sync(context, botId)?.export() } }
            }
        },
        lockedMessage = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ここを見るには、ストレージへのアクセスの許可が必要です。", fontSize = 12.sp)
                TextButton(onClick = requestStorage) { Text("許可する") }
            }
        },
    )
    Help("files", prefs) {
        Text(
            "実際に動くのはアプリの中のファイル（他のアプリから書き換えられない）です。${folderLabel(name)} はそのコピーで、" +
                "そこで変えたものは「✏ 変更あり」と表示され、確かめてから反映します。" +
                "🔒 の付いたファイル（.env など）はアプリの中だけにあり、フォルダには出ません。",
            fontSize = 12.sp, color = Color.DarkGray,
        )
    }
    if (hasStorage) {
        Help("sharedWarning", prefs) { Text(SHARED_WARNING, fontSize = 12.sp, color = Color(0xFFC92A2A)) }
    }
}
