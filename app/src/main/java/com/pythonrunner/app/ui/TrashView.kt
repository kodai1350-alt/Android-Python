package com.pythonrunner.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonrunner.app.files.Clash
import com.pythonrunner.app.files.FileNames
import com.pythonrunner.app.files.FileTree
import com.pythonrunner.app.files.Item
import com.pythonrunner.app.files.Resolution
import com.pythonrunner.app.files.TrashEntry
import com.pythonrunner.app.service.BotController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bot's recycle bin: 元に戻す to the original place, 完全に削除, ごみ箱を空にする (both asked first). */
@Composable
fun ColumnScope.TrashView(
    botId: String,
    refreshKey: Any,
    enabled: Boolean,
    onChanged: () -> Unit,
    onPending: (BotController.PendingChanges) -> Unit,
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val entries by produceState(emptyList<TrashEntry>(), botId, refreshKey, version) {
        value = withContext(Dispatchers.IO) { BotController.trash(context, botId).entries() }
    }
    val selected = remember(botId) { mutableStateListOf<String>() }
    selected.retainAll(entries.map { it.id }.toSet())
    var confirmDelete by remember { mutableStateOf<List<TrashEntry>?>(null) }
    var restoreClashes by remember { mutableStateOf<Pair<List<TrashEntry>, List<Clash>>?>(null) }
    val codeDir = remember(botId) { BotController.store(context, botId).codeDir }
    val chosen = entries.filter { it.id in selected }

    fun restore(list: List<TrashEntry>, resolution: Resolution) {
        val ops = BotController.fileOps(context)
        BotController.runFileOp(
            context, botId, list.map { Item(botId, it.originalPath) },
            op = { ops.restore(botId, list, resolution) },
        ) { r ->
            version++
            onChanged()
            r.onFailure { e ->
                if (e is BotController.PendingChanges) onPending(e)
                else Toast.makeText(context, e.message ?: "元に戻せませんでした", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun askRestore(list: List<TrashEntry>) {
        // A place taken again since: Windows asks replace or skip.
        val clashes = list.mapNotNull { e ->
            val parent = File(codeDir, e.originalPath.substringBeforeLast('/', ""))
            val existing = parent.listFiles().orEmpty().firstOrNull { FileNames.sameName(it.name, e.originalPath.substringAfterLast('/')) }
            existing?.let {
                val parentPath = e.originalPath.substringBeforeLast('/', "")
                Clash(if (parentPath.isEmpty()) it.name else "$parentPath/${it.name}", FileTree.sizeOf(it), it.lastModified(), e.size, e.deletedAt)
            }
        }
        if (clashes.isEmpty()) restore(list, Resolution.ReplaceAll) else restoreClashes = list to clashes
    }

    Row(Modifier.fillMaxWidth().background(Color(0xFFF8F9FA)).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = enabled && chosen.isNotEmpty(), onClick = { askRestore(chosen) }) { Text("↶ 元に戻す", fontSize = 13.sp) }
        TextButton(enabled = enabled && chosen.isNotEmpty(), onClick = { confirmDelete = chosen }) { Text("完全に削除", fontSize = 13.sp, color = Danger2) }
        TextButton(enabled = enabled && entries.isNotEmpty(), onClick = { confirmDelete = entries }) { Text("ごみ箱を空にする", fontSize = 13.sp, color = Danger2) }
    }
    HorizontalDivider()
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (entries.isEmpty()) Text("ごみ箱は空です", color = Color(0xFF868E96), modifier = Modifier.padding(10.dp))
        for (entry in entries) {
            val isSelected = entry.id in selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(if (isSelected) Color(0xFFDBE4FF) else Color.Transparent)
                    .clickable { if (isSelected) selected -= entry.id else selected += entry.id }
                    .padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(if (isSelected) "☑" else "☐", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        FileTree.icon(entry.originalPath, entry.isDirectory) + " " + entry.originalPath.substringAfterLast('/'),
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val place = entry.originalPath.substringBeforeLast('/', "").ifEmpty { "いちばん上" }
                    Text("元の場所：$place　${WHEN.format(Date(entry.deletedAt))}　${FileTree.formatSize(entry.size)}", fontSize = 10.sp, color = Color(0xFF868E96))
                }
            }
        }
    }

    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (list.size == entries.size) "ごみ箱を空にする" else "完全に削除") },
            text = { Text("${list.size} 個の項目を完全に削除しますか？元に戻せません") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    val all = list.size == entries.size
                    BotController.deleteFromTrash(context, botId, if (all) null else list) { version++; onChanged() }
                }) { Text("削除する", color = Danger2) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("キャンセル") } },
        )
    }
    restoreClashes?.let { (list, clashes) ->
        ReplaceOrSkipDialog(
            clashes, BotController.name(context, botId) ?: "Bot",
            onDone = { restoreClashes = null; restore(list, it) }, onCancel = { restoreClashes = null },
        )
    }
}

private val Danger2 = Color(0xFFC92A2A)
private val WHEN = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)
