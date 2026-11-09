package com.pythonrunner.app.ui.tabs

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.ui.Gray
import com.pythonrunner.app.ui.LogView
import com.pythonrunner.app.ui.Red

/** Everything the bot printed since the app started, newest at the bottom. */
@Composable
fun LogTab(botId: String, lines: List<LogLine>) {
    val context = LocalContext.current
    if (lines.isEmpty()) {
        Text("まだログはありません（起動すると、ここにBotの出力が出ます）", color = Gray)
    } else {
        LogView(lines, context, height = 420.dp)
        OutlinedButton(onClick = { BotController.clearLogs(botId) }) { Text("🗑 ログを消す", color = Red) }
    }
}
