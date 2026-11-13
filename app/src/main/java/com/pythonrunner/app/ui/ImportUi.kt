package com.pythonrunner.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import com.pythonrunner.app.files.ImportPlan
import com.pythonrunner.app.files.Resolution
import com.pythonrunner.app.service.ImportState

/** The one way in: 📥 取り込む, then a file (.py, ZIP, anything) or a whole folder. */
@Composable
fun ImportButton(enabled: Boolean, onPickFile: () -> Unit, onPickFolder: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { open = true }) { Text("📥 取り込む") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("ファイルを選ぶ（.py・ZIP など）") },
                onClick = { open = false; onPickFile() },
            )
            DropdownMenuItem(text = { Text("フォルダを選ぶ") }, onClick = { open = false; onPickFolder() })
        }
    }
}

/** Progress, the overwrite question and failures of an import. Done is handled by the screen. */
@Composable
fun ImportStateView(
    state: ImportState,
    onConfirm: (resolution: Resolution, removeExtras: Boolean) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onName: (String) -> Unit = {},
) {
    when (state) {
        is ImportState.Working -> Column(Modifier.fillMaxWidth()) {
            Text("取り込み中 ${state.percent}%", fontSize = 13.sp)
            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        is ImportState.Confirm -> if (state.wholeBot) {
            ImportCheckDialog(state.plan, { removeExtras -> onConfirm(Resolution.ReplaceAll, removeExtras) }, onCancel)
        } else {
            ReplaceOrSkipDialog(state.clashes, state.folderLabel, onDone = { onConfirm(it, false) }, onCancel = onCancel)
        }
        is ImportState.Failed -> Column {
            Text(state.message, color = Color(0xFFE03131), fontSize = 13.sp)
            TextButton(onClick = onClose) { Text("閉じる") }
        }
        is ImportState.ChooseName -> ChooseNameDialog(state, onConfirm = onName, onCancel = onCancel)
        ImportState.Idle, is ImportState.Done -> Unit
    }
}

/**
 * What an import will do before it does it: what is replaced, how much is added, and the bot's files the
 * new ones lack (kept unless 「消す」 is chosen).
 */
@Composable
private fun ImportCheckDialog(plan: ImportPlan, onConfirm: (removeExtras: Boolean) -> Unit, onCancel: () -> Unit) {
    var removeExtras by remember(plan) { mutableStateOf(false) }
    val added = plan.files.size - plan.overwrites.size
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("取り込む前に確かめてください") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (plan.overwrites.isNotEmpty()) {
                    Text("置き換わるもの：${plan.overwrites.size} 個（古い方はごみ箱へ）", fontSize = 13.sp)
                }
                if (added > 0) Text("増えるもの：${added} 個", fontSize = 13.sp)
                if (plan.extras.isNotEmpty()) {
                    var all by remember(plan) { mutableStateOf(false) }
                    Text("今のBotにあって、新しいフォルダにないファイル（${plan.extras.size} 個）", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("スマホで登録した音や、Botが作ったデータなどです。", fontSize = 12.sp, color = Color.DarkGray)
                    (if (all) plan.extras else plan.extras.take(5)).forEach { Text("・$it", fontSize = 13.sp) }
                    if (!all && plan.extras.size > 5) {
                        TextButton(onClick = { all = true }) { Text("…ほか ${plan.extras.size - 5} 個を見る", fontSize = 13.sp) }
                    }
                    Text("このファイルをどうしますか？", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    listOf(
                        Triple(false, "残す（おすすめ）", "新しいファイルを入れて、このファイルもそのまま使います。"),
                        Triple(true, "削除する", "新しいフォルダと同じ中身にします。このファイルはごみ箱に入ります（ファイル画面のごみ箱から戻せます）。"),
                    ).forEach { (value, label, note) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().selectable(selected = removeExtras == value, onClick = { removeExtras = value }),
                        ) {
                            RadioButton(selected = removeExtras == value, onClick = { removeExtras = value })
                            Column {
                                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (value) Color(0xFFC92A2A) else Color.Unspecified)
                                Text(note, fontSize = 12.sp, color = Color.DarkGray)
                            }
                        }
                    }
                }
                Text(
                    "アプリが入れた部品（node_modules・.venv）と、アプリの中だけのファイル（secure.key など）は、いつも残ります。",
                    fontSize = 12.sp, color = Color.DarkGray,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(removeExtras) }) {
                if (removeExtras) Text("取り込んで ${plan.extras.size} 個を削除", color = Color(0xFFC92A2A)) else Text("取り込む")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("やめる") } },
    )
}

/** The new bot's name, prefilled from the picked file or folder; the folder in PythonRunner gets it too. */
@Composable
fun ChooseNameDialog(state: ImportState.ChooseName, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember(state) { mutableStateOf(state.suggested) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Botの名前") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("名前") })
                Text("ドキュメント › PythonRunner › ${name.trim()} に置かれます", fontSize = 12.sp, color = Color.DarkGray)
                state.error?.let { Text(it, color = Color(0xFFE03131), fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("追加する") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("やめる") } },
    )
}

/** Files changed under a running bot: it keeps the old code until it starts again. */
@Composable
fun RestartDialog(onRestart: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        text = { Text("今すぐBotを再起動しますか？（再起動するまで新しい中身は使われません）") },
        confirmButton = { TextButton(onClick = onRestart) { Text("再起動する") } },
        dismissButton = { TextButton(onClick = onLater) { Text("あとで") } },
    )
}
