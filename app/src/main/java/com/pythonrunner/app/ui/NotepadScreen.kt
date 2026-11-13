package com.pythonrunner.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonrunner.app.files.FileTree
import com.pythonrunner.app.files.Opened
import com.pythonrunner.app.files.TextFiles
import com.pythonrunner.app.service.BotController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Notepad, like Windows': the file's text to read and edit, 「*」 before the name while unsaved, line and
 * column, charset and line breaks at the bottom, and 「〇〇への変更内容を保存しますか？」 on close.
 */
@Composable
fun NotepadScreen(botId: String, path: String, running: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = rememberBotActions()
    val name = path.substringAfterLast('/')
    var opened by remember(path) { mutableStateOf<Opened?>(null) }
    var pending by remember(path) { mutableStateOf<List<String>?>(null) }
    var value by remember(path) { mutableStateOf(TextFieldValue("")) }
    var saved by remember(path) { mutableStateOf("") }
    var askSave by remember { mutableStateOf(false) }
    var justSaved by remember { mutableStateOf(false) }
    // A save that needs an answer first: the file changed since it was opened, or the charset cannot hold the text.
    var askOverwrite by remember { mutableStateOf<(() -> Unit)?>(null) }
    var askUtf8 by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(path) {
        val file = File(BotController.store(context, botId).codeDir, path)
        // A file changed on a PC and not brought in yet would be overwritten by a save: bring it in first.
        val unreflected = withContext(Dispatchers.IO) {
            runCatching { BotController.sync(context, botId)?.pendingUnder(listOf(path)) }.getOrNull().orEmpty()
        }
        if (unreflected.isNotEmpty()) {
            pending = unreflected
            return@LaunchedEffect
        }
        val o = withContext(Dispatchers.IO) { runCatching { TextFiles.open(file) }.getOrNull() }
        opened = o
        if (o is Opened.Text) {
            value = TextFieldValue(o.text)
            saved = o.text
        }
    }
    val text = opened as? Opened.Text
    val changed = text != null && value.text != saved

    fun save(force: Boolean = false, charset: String? = null, then: () -> Unit = {}) {
        val like = (text ?: return).let { if (charset != null) it.copy(charset = charset) else it }
        val now = value.text
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { BotController.saveText(context, botId, path, now, like, force) } }
            result.onSuccess { modified ->
                // The next save compares against this save, not the original opening.
                opened = like.copy(text = now, modified = modified)
                saved = now
                justSaved = true
                then()
            }.onFailure { e ->
                when (e) {
                    is TextFiles.ChangedSinceOpened -> askOverwrite = { save(force = true, charset = charset, then = then) }
                    is TextFiles.CannotEncode -> askUtf8 = { save(force = force, charset = "UTF-8", then = then) }
                    is BotController.PendingChanges -> pending = e.paths
                    else -> Toast.makeText(context, "保存できませんでした", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun close() {
        if (changed) askSave = true else onClose()
    }
    BackHandler { close() }

    Column(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                (if (changed) "*" else "") + name, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Button(enabled = changed, onClick = { save() }) { Text("保存") }
            TextButton(onClick = { close() }) { Text("閉じる") }
        }
        when (val o = opened) {
            null -> if (pending == null) Text("開いています…", color = Color.Gray, fontSize = 13.sp)
            is Opened.NotText -> Text(
                "このファイルは文字のファイルではないので、開けません（${o.kind}・${FileTree.formatSize(o.size)}）",
                fontSize = 14.sp, modifier = Modifier.padding(8.dp),
            )
            is Opened.TooBig -> Text(
                "大きすぎて開けません（1MB まで。このファイルは ${FileTree.formatSize(o.size)}）",
                fontSize = 14.sp, modifier = Modifier.padding(8.dp),
            )
            is Opened.Text -> {
                if (justSaved && running && !changed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF4E6)).padding(horizontal = 8.dp),
                    ) {
                        Text("Bot を再起動すると、この変更が使われます", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { justSaved = false; BotController.restart(context, botId) }) { Text("今すぐ再起動") }
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; justSaved = false },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                val (line, column) = TextFiles.position(value.text, value.selection.start)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Text("行 $line, 列 $column", fontSize = 11.sp, color = Color.DarkGray)
                    Text(o.charset + if (o.bom) "（BOM付き）" else "", fontSize = 11.sp, color = Color.DarkGray)
                    Text(if (o.crlf) "CRLF" else "LF", fontSize = 11.sp, color = Color.DarkGray)
                }
            }
        }
    }

    if (askSave) {
        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text("メモ帳") },
            text = { Text("$name への変更内容を保存しますか？") },
            confirmButton = { TextButton(onClick = { askSave = false; save { onClose() } }) { Text("保存する") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { askSave = false; onClose() }) { Text("保存しない") }
                    OutlinedButton(onClick = { askSave = false }) { Text("キャンセル") }
                }
            },
        )
    }
    askOverwrite?.let { go ->
        AlertDialog(
            onDismissRequest = { askOverwrite = null },
            title = { Text("メモ帳") },
            text = { Text("$name は、開いたあとに変更されています（PC や Bot が書き換えた可能性があります）。上書きしますか？") },
            confirmButton = { TextButton(onClick = { askOverwrite = null; go() }) { Text("上書きする") } },
            dismissButton = { TextButton(onClick = { askOverwrite = null }) { Text("やめる") } },
        )
    }
    askUtf8?.let { go ->
        AlertDialog(
            onDismissRequest = { askUtf8 = null },
            title = { Text("メモ帳") },
            text = { Text("${text?.charset} では保存できない文字（絵文字など）があります。UTF-8 で保存しますか？") },
            confirmButton = { TextButton(onClick = { askUtf8 = null; go() }) { Text("UTF-8 で保存") } },
            dismissButton = { TextButton(onClick = { askUtf8 = null }) { Text("やめる") } },
        )
    }
    pending?.let { paths ->
        PendingDialog(
            paths,
            onReflect = {
                pending = null
                onClose()
                BotController.pendingChanges(context, botId)?.let { actions.review(botId, it) }
            },
            onCancel = { pending = null; onClose() },
        )
    }
}
