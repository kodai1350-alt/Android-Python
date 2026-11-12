package com.pythonrunner.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pythonrunner.app.files.SyncChanges
import com.pythonrunner.app.service.BotController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Asks before running anything that changed in the shared folder, where other apps can write too. */
@Composable
fun SharedChangesDialog(
    name: String,
    changes: SyncChanges,
    thenStart: Boolean,
    onImport: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("✏ 変更あり：$name") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${folderLabel(name)} で、次のファイルが変わっています。", fontSize = 13.sp)
                changes.added.forEach { Text("＋ 追加：$it", color = Green, fontSize = 13.sp) }
                changes.modified.forEach { Text("✎ 変更：$it", color = Orange, fontSize = 13.sp) }
                changes.removed.forEach { Text("－ 削除：$it（アプリの中からも消えます）", color = Red, fontSize = 13.sp) }
                Text(
                    "自分で変えたものなら「${if (thenStart) "反映して起動" else "反映する"}」を押すと、アプリの中の「$name」も同じになります。" +
                        "「${if (thenStart) "反映せずに起動" else "反映しない"}」なら、アプリの中はそのままです。心当たりがない変更は、" +
                        "他のアプリが書き換えた可能性があるので反映しないでください。",
                    fontSize = 12.sp, color = Color.DarkGray,
                )
            }
        },
        confirmButton = { TextButton(onClick = onImport) { Text(if (thenStart) "反映して起動" else "反映する") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text(if (thenStart) "やめる" else "反映しない") }
                if (thenStart) TextButton(onClick = onSkip) { Text("反映せずに起動") }
            }
        },
    )
}

/** 「このBotを削除」: the bot goes with its shared folder. */
@Composable
fun DeleteBotDialog(name: String, onDelete: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("『$name』を削除しますか？") },
        text = {
            Text(
                "動いていれば止めます。ファイル・ライブラリ・設定と、共有フォルダ（${folderLabel(name)}）も消えます。元に戻せません。",
                fontSize = 13.sp, color = Red,
            )
        },
        confirmButton = { TextButton(onClick = onDelete) { Text("削除する", color = Red) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("やめる") } },
    )
}

/** Shown once, before the 11th bot. */
@Composable
fun ManyBotsDialog(onOk: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        text = { Text("10個を超えると、スマホによっては動きが重くなることがあります") },
        confirmButton = { TextButton(onClick = onOk) { Text("追加する") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("やめる") } },
    )
}

/** Starting a bot and reviewing its shared-folder changes, from any screen. */
class BotActions(
    /** 起動: first offers changes waiting in the shared folder, then asks for notifications if needed. */
    val start: (String) -> Unit,
    /** 「✏ 変更あり」: shows the changes with 反映する／反映しない. */
    val review: (String, SyncChanges) -> Unit,
)

/** Holds the dialogs [BotActions] opens; call once per screen and use the result. */
@Composable
fun rememberBotActions(): BotActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reviewing by remember { mutableStateOf<Triple<String, SyncChanges, Boolean>?>(null) }
    var askRestart by remember { mutableStateOf<String?>(null) }
    var launchAfterPermission by remember { mutableStateOf<String?>(null) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchAfterPermission?.let { BotController.start(context, it) }
        launchAfterPermission = null
    }

    fun launch(id: String) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            BotController.start(context, id)
        } else {
            launchAfterPermission = id
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val actions = remember {
        BotActions(
            start = { id ->
                scope.launch {
                    val changes = withContext(Dispatchers.IO) {
                        runCatching { BotController.sync(context, id)?.pendingImports() }.getOrNull()
                    }
                    if (changes != null && !changes.isEmpty) reviewing = Triple(id, changes, true) else launch(id)
                }
            },
            review = { id, changes -> reviewing = Triple(id, changes, false) },
        )
    }

    reviewing?.let { (id, changes, thenStart) ->
        SharedChangesDialog(
            name = BotController.name(context, id) ?: "",
            changes = changes,
            thenStart = thenStart,
            onImport = {
                reviewing = null
                scope.launch {
                    // Files changed again after confirming are not imported: ask again instead of going on.
                    val again = withContext(Dispatchers.IO) {
                        val sync = BotController.sync(context, id)
                        val skipped = sync?.importChanges(changes).orEmpty()
                        BotController.store(context, id).forgetMissingMain()  // the file to run first may be gone
                        BotController.declinedChanges.remove(id)
                        BotController.recheck(id)
                        if (skipped.isEmpty()) null else sync?.pendingImports()
                    }
                    when {
                        again != null && !again.isEmpty -> reviewing = Triple(id, again, thenStart)
                        thenStart && BotController.store(context, id).mainFile != null -> launch(id)
                        // A running bot keeps the old files until it starts again.
                        BotController.isRunning(id) -> askRestart = id
                    }
                    withContext(Dispatchers.IO) { BotController.checkShared(context) }
                }
            },
            onSkip = {
                reviewing = null
                launch(id)
            },
            onCancel = {
                reviewing = null
                // 反映しない: not offered again until something else changes (起動 still asks).
                if (!thenStart) {
                    BotController.declinedChanges[id] = changes
                    scope.launch(Dispatchers.IO) { BotController.checkShared(context) }
                }
            },
        )
    }
    askRestart?.let { id ->
        RestartDialog(
            onRestart = { askRestart = null; BotController.restart(context, id) },
            onLater = { askRestart = null },
        )
    }
    return actions
}
