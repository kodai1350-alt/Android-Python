package com.pythonrunner.app.ui.tabs

import android.widget.Toast
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonrunner.app.bot.DotEnv
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.ui.DeleteBotDialog
import com.pythonrunner.app.ui.Gray
import com.pythonrunner.app.ui.Green
import com.pythonrunner.app.ui.Help
import com.pythonrunner.app.ui.Red
import com.pythonrunner.app.ui.Section
import com.pythonrunner.app.ui.rememberUiPrefs

/** One editable line of the secrets section. */

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

    EnvSection(botId, filesVersion)

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
                onDelete = {
                    confirm = false
                    // The screen goes back to the list by itself once the bot is gone.
                    BotController.deleteBot(context, botId)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                },
                onCancel = { confirm = false },
            )
        }
    }
}

/**
 * The bot's .env: the names it sets, values hidden; 「表示して編集」 opens the whole file for editing.
 * It is the same file as in the bot's folder (and the shared folder), so it can be edited on a PC too.
 */
@Composable
private fun EnvSection(botId: String, filesVersion: Int) {
    val context = LocalContext.current
    val prefs = rememberUiPrefs()
    val file = remember(botId) { File(BotController.store(context, botId).codeDir, DotEnv.FILE) }
    var saved by remember(botId, filesVersion) { mutableStateOf(if (file.isFile) file.readText() else null) }
    var editing by remember(botId) { mutableStateOf<String?>(null) }
    var justSaved by remember(botId) { mutableStateOf(false) }

    Section(".env（トークンなど）") {
        val text = editing
        when {
            text != null -> {
                OutlinedTextField(
                    value = text, onValueChange = { editing = it; justSaved = false },
                    label = { Text(".env") },
                    placeholder = { Text("DISCORD_TOKEN=ここにトークン") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        BotController.saveEnv(context, botId, text)
                        saved = text
                        editing = null
                        justSaved = true
                    }) { Text("保存") }
                    OutlinedButton(onClick = { editing = null }) { Text("やめる") }
                }
            }
            saved == null -> {
                Text("まだありません", color = Gray, fontSize = 13.sp)
                OutlinedButton(onClick = { editing = ""; justSaved = false }) { Text("作る") }
            }
            else -> {
                val names = DotEnv.names(saved.orEmpty())
                if (names.isEmpty()) Text("値がまだ書かれていません", color = Gray, fontSize = 13.sp)
                names.forEach { Text("$it ●●●●", fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
                OutlinedButton(onClick = { editing = saved; justSaved = false }) { Text("表示して編集") }
            }
        }
        if (justSaved) Text("✓ 保存しました（次の起動から使われます）", color = Green, fontSize = 12.sp)
        Help("env", prefs) {
            Text(
                "Bot のフォルダの .env に書いた値は、Bot の中で os.environ[\"名前\"] で読めます。" +
                    ".env は共有フォルダにも置かれるので、PC で直しても構いません。",
                fontSize = 12.sp, color = Color.DarkGray,
            )
        }
    }
}
