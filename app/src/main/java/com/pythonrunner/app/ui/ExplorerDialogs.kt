package com.pythonrunner.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.pythonrunner.app.files.FileNames
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonrunner.app.files.Clash
import com.pythonrunner.app.files.FileTree
import com.pythonrunner.app.files.Keep
import com.pythonrunner.app.files.Resolution
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Windows' 「ファイルの置換またはスキップ」: replace them all, skip them all, or decide per file (keep the new
 * one, the one already there, or both — the new one then gets 「name (2)」).
 */
@Composable
fun ReplaceOrSkipDialog(clashes: List<Clash>, folderName: String, onDone: (Resolution) -> Unit, onCancel: () -> Unit) {
    var perFile by remember(clashes) { mutableStateOf(false) }
    if (!perFile) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("ファイルの置換またはスキップ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("「$folderName」には同じ名前のファイルが ${clashes.size} 個あります", fontSize = 14.sp)
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    Choice("✓ ファイルを置き換える", "古い方はごみ箱に入ります") { onDone(Resolution.ReplaceAll) }
                    HorizontalDivider()
                    Choice("↷ ファイルをスキップする", "今あるファイルをそのまま使います") { onDone(Resolution.SkipAll) }
                    HorizontalDivider()
                    Choice("☰ ファイルごとに決める", null) { perFile = true }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
        )
        return
    }
    // Per file: which to keep. Both ticked keeps both; at least one must be ticked for each file.
    val incoming = remember(clashes) { mutableStateMapOf<String, Boolean>().apply { clashes.forEach { put(it.path, false) } } }
    val existing = remember(clashes) { mutableStateMapOf<String, Boolean>().apply { clashes.forEach { put(it.path, false) } } }
    val ready = clashes.all { incoming[it.path] == true || existing[it.path] == true }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("どのファイルを残しますか？") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("両方にチェックを付けると、新しい方の名前に番号が付きます。", fontSize = 12.sp, color = Color.DarkGray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = clashes.all { incoming[it.path] == true }, onCheckedChange = { v -> clashes.forEach { incoming[it.path] = v } })
                    Text("取り込むファイル すべて", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Checkbox(checked = clashes.all { existing[it.path] == true }, onCheckedChange = { v -> clashes.forEach { existing[it.path] = v } })
                    Text("今あるファイル すべて", fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                for (clash in clashes) {
                    HorizontalDivider()
                    Text(clash.path, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = incoming[clash.path] == true, onCheckedChange = { incoming[clash.path] = it })
                        Text(describe(clash.incomingSize, clash.incomingModified), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Checkbox(checked = existing[clash.path] == true, onCheckedChange = { existing[clash.path] = it })
                        Text(describe(clash.existingSize, clash.existingModified), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                onDone(
                    Resolution.PerFile(
                        clashes.associate { c ->
                            c.path to when {
                                incoming[c.path] == true && existing[c.path] == true -> Keep.BOTH
                                incoming[c.path] == true -> Keep.INCOMING
                                else -> Keep.EXISTING
                            }
                        },
                    ),
                )
            }) { Text("続行") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
    )
}

@Composable
private fun Choice(title: String, note: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, fontSize = 15.sp)
        note?.let { Text(it, fontSize = 12.sp, color = Color.DarkGray) }
    }
}

private fun describe(size: Long, modified: Long): String =
    "${FileTree.formatSize(size)}\n${DATE_TIME.format(Date(modified))}"

private val DATE_TIME = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)

/** 名前の変更: the name without its extension is selected first, as in Windows; a changed extension is asked about. */
@Composable
fun RenameDialog(current: String, siblings: List<String>, onRename: (String) -> Unit, onCancel: () -> Unit) {
    val baseEnd = current.lastIndexOf('.').takeIf { it > 0 } ?: current.length
    var value by remember(current) { mutableStateOf(TextFieldValue(current, TextRange(0, baseEnd))) }
    var askExtension by remember { mutableStateOf(false) }
    val name = value.text.trim()
    val problem = if (name == current) null else FileNames.problem(name, siblings, renaming = current)
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("名前の変更") },
        text = {
            Column {
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                problem?.let { Text(it, color = Color(0xFFC92A2A), fontSize = 12.sp) }
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(enabled = problem == null && name.isNotEmpty(), onClick = {
                when {
                    name == current -> onCancel()
                    FileNames.extensionChanged(current, name) -> askExtension = true
                    else -> onRename(name)
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
    )
    if (askExtension) {
        AlertDialog(
            onDismissRequest = { askExtension = false },
            title = { Text("名前の変更") },
            text = { Text("拡張子を変更すると、ファイルが使えなくなる可能性があります。変更しますか？") },
            confirmButton = { TextButton(onClick = { askExtension = false; onRename(name) }) { Text("はい") } },
            dismissButton = { TextButton(onClick = { askExtension = false }) { Text("いいえ") } },
        )
    }
}

/** プロパティ: name, kind, place, size and counts (counted off the main thread), dates. */
@Composable
fun PropertiesDialog(root: File, rootLabel: String, paths: List<String>, onClose: () -> Unit) {
    val info by produceState<Props?>(null, paths) {
        value = withContext(Dispatchers.IO) {
            var files = 0
            var folders = 0
            fun count(f: File) {
                if (Files.isSymbolicLink(f.toPath())) { files++; return }
                if (FileTree.isRealDirectory(f)) { folders++; f.listFiles().orEmpty().forEach { count(it) } } else files++
            }
            val targets = paths.map { File(root, it) }
            targets.forEach { t -> if (FileTree.isRealDirectory(t)) t.listFiles().orEmpty().forEach { count(it) } }
            val created = if (targets.size == 1) runCatching {
                Files.readAttributes(targets[0].toPath(), BasicFileAttributes::class.java).creationTime().toMillis()
            }.getOrNull() else null
            Props(targets.sumOf { FileTree.sizeOf(it) }, files, folders, created)
        }
    }
    val single = paths.singleOrNull()
    val file = single?.let { File(root, it) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(single?.substringAfterLast('/')?.let { "$it のプロパティ" } ?: "${paths.size} 個の項目のプロパティ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (file != null) PropLine("種類", FileTree.kind(file.name, FileTree.isRealDirectory(file)))
                val parent = (single ?: paths.first()).substringBeforeLast('/', "")
                PropLine("場所", if (parent.isEmpty()) rootLabel else "$rootLabel › ${parent.replace("/", " › ")}")
                PropLine("サイズ", info?.let { FileTree.formatSize(it.size) } ?: "数えています…")
                if (file == null || FileTree.isRealDirectory(file)) {
                    PropLine("内容", info?.let { "ファイル ${it.files} 個、フォルダー ${it.folders} 個" } ?: "数えています…")
                }
                info?.created?.let { PropLine("作成日時", FULL_DATE.format(Date(it))) }
                if (file != null) PropLine("更新日時", FULL_DATE.format(Date(file.lastModified())))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("閉じる") } },
    )
}

private class Props(val size: Long, val files: Int, val folders: Int, val created: Long?)

@Composable
private fun PropLine(label: String, value: String) {
    Row {
        Text(label, fontSize = 13.sp, color = Color.DarkGray, modifier = Modifier.width(72.dp))
        Text(value, fontSize = 13.sp)
    }
}

/** A paste or restore that takes a while: how far it is, and キャンセル (what was done is put back). */
@Composable
fun ProgressDialog(done: Int, total: Int, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("コピーしています") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$total 個中 $done 個", fontSize = 14.sp)
                LinearProgressIndicator(progress = { if (total == 0) 0f else done / total.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
    )
}

/** The files touched have shared-folder changes not brought in yet: bring them in first, or leave it. */
@Composable
fun PendingDialog(paths: List<String>, onReflect: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("先に反映してください") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("この中に、PC などで変えて、まだ反映していないファイルがあります。", fontSize = 14.sp)
                paths.take(10).forEach { Text("・$it", fontSize = 13.sp) }
                if (paths.size > 10) Text("…ほか ${paths.size - 10} 個", fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onReflect) { Text("先に反映する") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
    )
}

private val FULL_DATE = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)
