package com.pythonrunner.app.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonrunner.app.bot.duplicateNames
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.ui.DeleteBotDialog
import com.pythonrunner.app.ui.Gray
import com.pythonrunner.app.ui.Green
import com.pythonrunner.app.ui.Help
import com.pythonrunner.app.ui.Red
import com.pythonrunner.app.ui.Section
import com.pythonrunner.app.ui.rememberUiPrefs

/** One editable line of the secrets section. */
private data class SecretRow(val name: String, val value: String, val shown: Boolean = false)

/** The file to run first, secret values, the bot's name, and deleting it. */
@Composable
fun SettingsTab(botId: String, name: String, mainFile: String?, filesVersion: Int, onMainChanged: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = rememberUiPrefs()
    val store = remember(botId) { BotController.store(context, botId) }

    Section("最初に実行するファイル") {
        val pyFiles = remember(botId, filesVersion) {
            store.codeDir.walkTopDown()
                .onEnter { it == store.codeDir || it.name != "__pycache__" }
                .filter { it.isFile && it.name.endsWith(".py") }
                .map { it.relativeTo(store.codeDir).invariantSeparatorsPath }
                .sorted().toList()
        }
        var open by remember { mutableStateOf(false) }
        Text(mainFile?.let { "▶ $it" } ?: "まだ選ばれていません", color = if (mainFile != null) Green else Gray, fontWeight = FontWeight.Bold)
        Box {
            OutlinedButton(enabled = pyFiles.isNotEmpty(), onClick = { open = true }) { Text("選ぶ") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                pyFiles.forEach { path ->
                    DropdownMenuItem(text = { Text(path) }, onClick = {
                        open = false
                        store.mainFile = path
                        onMainChanged(path)
                    })
                }
            }
        }
        if (pyFiles.isEmpty()) Text("まだ .py ファイルがありません（「ファイル」タブで取り込めます）", fontSize = 12.sp, color = Gray)
    }

    SecretsSection(botId)

    Section("Botの名前") {
        var newName by remember(name) { mutableStateOf(name) }
        var error by remember(name) { mutableStateOf<String?>(null) }
        OutlinedTextField(value = newName, onValueChange = { newName = it; error = null }, singleLine = true, label = { Text("名前") })
        Text("フォルダの名前（ドキュメント › PythonRunner › ${newName.trim()}）も一緒に変わります。", fontSize = 12.sp, color = Color.DarkGray)
        error?.let { Text(it, color = Red, fontSize = 13.sp) }
        Button(enabled = newName.trim() != name, onClick = { error = BotController.renameBot(context, botId, newName) }) {
            Text("変更する")
        }
    }

    Section("Botの削除") {
        var confirm by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { confirm = true }) { Text("🗑 このBotを削除", color = Red) }
        if (confirm) {
            DeleteBotDialog(
                name = name,
                onDelete = { deleteFolder ->
                    confirm = false
                    // The screen goes back to the list by itself once the bot is gone.
                    BotController.deleteBot(context, botId, deleteFolder)
                },
                onCancel = { confirm = false },
            )
        }
        Help("delete", prefs) {
            Text("「一覧から外すだけ」なら、フォルダはファイルアプリやPCにそのまま残り、あとでホーム画面から追加し直せます。", fontSize = 12.sp, color = Color.DarkGray)
        }
    }
}

@Composable
private fun SecretsSection(botId: String) {
    val context = LocalContext.current
    val prefs = rememberUiPrefs()
    val store = remember(botId) { BotController.store(context, botId) }
    val secrets = remember(botId) {
        mutableStateListOf<SecretRow>().apply { addAll(store.envVars.map { (k, v) -> SecretRow(k, v) }) }
    }
    var savedSecrets by remember(botId) { mutableStateOf(store.envVars) }
    var justSaved by remember(botId) { mutableStateOf(false) }

    Section("秘密の値（トークンなど）※使わなくてもOK") {
        Help("secrets", prefs) {
            Text(
                "トークンをBotのコードに直接書かずに済ませるための保管場所です。" +
                    "ここに入れた値は、Botの中で os.environ[\"名前\"] で読めます。" +
                    "コードにトークンを直接書いているなら、ここは空のままで大丈夫です。",
                fontSize = 13.sp,
                color = Color.DarkGray,
            )
        }
        secrets.forEachIndexed { index, row ->
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = row.name, onValueChange = { secrets[index] = row.copy(name = it); justSaved = false },
                    label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = row.value, onValueChange = { secrets[index] = row.copy(value = it); justSaved = false },
                    label = { Text("値") }, singleLine = true,
                    visualTransformation = if (row.shown) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { secrets[index] = row.copy(shown = !row.shown) }) {
                        Text(if (row.shown) "🙈 隠す" else "👁 表示")
                    }
                    TextButton(onClick = { secrets.removeAt(index); justSaved = false }) {
                        Text("🗑 削除", color = Red)
                    }
                }
            }
        }
        val duplicates = duplicateNames(secrets.map { it.name })
        if (duplicates.isNotEmpty()) {
            Text("同じ名前が2つ以上あります：${duplicates.joinToString(", ")}（下の行の値が使われます）", color = Red, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                secrets += SecretRow(if (secrets.none { it.name.trim() == "TOKEN" }) "TOKEN" else "", "")
                justSaved = false
            }) { Text("＋追加") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = {
                store.envVars = secrets.filter { it.name.isNotBlank() }.associate { it.name.trim() to it.value }
                savedSecrets = store.envVars
                justSaved = true
            }) { Text("保存") }
        }
        val current = secrets.filter { it.name.isNotBlank() }.associate { it.name.trim() to it.value }
        when {
            current != savedSecrets -> Text("まだ保存していません（「保存」を押すと次の起動から使われます）", color = Red, fontSize = 12.sp)
            justSaved -> Text("✓ 保存しました（次の起動から使われます）", color = Green, fontSize = 12.sp)
            savedSecrets.isNotEmpty() -> Text("保存済み：${savedSecrets.keys.joinToString(", ")}", color = Gray, fontSize = 12.sp)
        }
    }
}
