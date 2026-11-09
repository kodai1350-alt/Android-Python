package com.pythonrunner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.ui.SpikeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BotController.ensureReady(this)
        setContent {
            MaterialTheme {
                Surface {
                    val bots by BotController.bots.collectAsStateWithLifecycle()
                    val first = bots.firstOrNull()
                    if (first != null) SpikeScreen(first.id) else Text("最初のBotを追加しましょう", Modifier.padding(16.dp))
                }
            }
        }
    }
}
