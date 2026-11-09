package com.pythonrunner.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonrunner.app.bot.BotStatus
import com.pythonrunner.app.process.LogLine
import kotlinx.coroutines.delay

internal val Green = Color(0xFF2F9E44)
internal val Red = Color(0xFFE03131)
internal val Gray = Color(0xFFADB5BD)
internal val Yellow = Color(0xFFF59F00)
internal val Orange = Color(0xFFF08C00)

/** How often the shared folders are checked for changes while the app is shown. */
internal const val SHARED_CHECK_MILLIS = 4_000L

internal const val SHARED_WARNING =
    "⚠ 共有フォルダは、ストレージの許可を持つ他のアプリからも読めます。トークンは .py や config.json などに書かず、" +
        "設定タブの「秘密の値」（アプリ専用）に入れてください。.env・鍵・データベース、名前に token や secret を含むファイルはコピーしません。"

@Composable
internal fun rememberUiPrefs(): SharedPreferences {
    val context = LocalContext.current
    return remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
}

/**
 * An explanation the person can close; it stays closed (remembered per [key]) until "説明を見る" is pressed.
 */
@Composable
internal fun Help(key: String, prefs: SharedPreferences, content: @Composable () -> Unit) {
    val prefKey = "help.$key.closed"
    var open by remember { mutableStateOf(!prefs.getBoolean(prefKey, false)) }
    fun setOpen(value: Boolean) {
        open = value
        prefs.edit().putBoolean(prefKey, !value).apply()
    }
    if (open) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFFF1F3F5), RoundedCornerShape(8.dp)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
            TextButton(onClick = { setOpen(false) }) { Text("✕ 説明を閉じる") }
        }
    } else {
        TextButton(onClick = { setOpen(true) }) { Text("ℹ 説明を見る") }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

internal fun statusColor(status: BotStatus): Color = when (status) {
    BotStatus.RUNNING -> Green
    BotStatus.STARTING -> Yellow
    BotStatus.CRASHED -> Red
    BotStatus.STOPPED -> Gray
}

internal fun statusLabel(status: BotStatus): String = when (status) {
    BotStatus.RUNNING -> "稼働中"
    BotStatus.STARTING -> "起動中…"
    BotStatus.CRASHED -> "エラーで停止"
    BotStatus.STOPPED -> "停止中"
}

@Composable
internal fun StatusDot(status: BotStatus, size: Dp = 12.dp) {
    Box(Modifier.size(size).background(statusColor(status), CircleShape))
}

/** The dot, the state in words and, while running, how long it has been up (ticking each second). */
@Composable
internal fun StatusRow(status: BotStatus, startedAt: Long?, fontSize: Int = 14) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (startedAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(status)
        Spacer(Modifier.width(8.dp))
        val color = if (status == BotStatus.STOPPED) Color.DarkGray else statusColor(status)
        Text(statusLabel(status), fontWeight = FontWeight.Bold, color = color, fontSize = fontSize.sp)
        if (startedAt != null && status == BotStatus.RUNNING) {
            Spacer(Modifier.width(6.dp))
            Text(formatUptime(now - startedAt), color = color, fontSize = fontSize.sp)
        }
    }
}

@Composable
internal fun LogView(lines: List<LogLine>, context: Context, height: Dp = 280.dp) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(Color(0xFF111111), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        items(lines) { line -> LogText(line) }
    }
    OutlinedButton(onClick = {
        val text = lines.joinToString("\n") { it.text }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", text))
    }) { Text("コピー") }
}

@Composable
internal fun LogText(line: LogLine, maxLines: Int = Int.MAX_VALUE) {
    Text(
        line.text,
        color = if (line.isError) Color(0xFFFF8787) else Color(0xFF8CE99A),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        maxLines = maxLines,
    )
}

internal fun formatUptime(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "${h}時間${m}分" else "${m}分${s}秒"
}

/** The outcome of a one-shot job (preparing Python, pip install), shown so success or failure is unmistakable. */
internal sealed class WorkResult(val message: String) {
    class Idle(message: String) : WorkResult(message)
    class Working(message: String) : WorkResult(message)
    class Done(message: String) : WorkResult(message)
    class Failed(message: String) : WorkResult(message)
}

@Composable
internal fun WorkResultView(result: WorkResult) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (result) {
            is WorkResult.Working -> {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(result.message, fontWeight = FontWeight.Bold)
            }
            is WorkResult.Done -> Text("✓ " + result.message, color = Green, fontWeight = FontWeight.Bold)
            is WorkResult.Failed -> Column {
                Text("✗ " + result.message, color = Red, fontWeight = FontWeight.Bold)
                Text("下の「作業の出力」の「コピー」で内容を送ってください", color = Red, fontSize = 12.sp)
            }
            is WorkResult.Idle -> Text(result.message, color = Gray)
        }
    }
}

/** Opens the screen that grants access to shared storage (all files on Android 11+). */
@Composable
internal fun rememberStorageRequest(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    return {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            ask.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
}

/** The place a bot's files can be seen from the Files app or a PC. */
internal fun folderLabel(name: String) = "ドキュメント › PythonRunner › $name"
