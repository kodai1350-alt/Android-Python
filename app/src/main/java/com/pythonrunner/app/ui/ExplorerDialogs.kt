package com.pythonrunner.app.ui

import androidx.compose.foundation.clickable
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
