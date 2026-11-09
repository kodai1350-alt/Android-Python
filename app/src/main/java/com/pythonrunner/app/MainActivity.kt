package com.pythonrunner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.ui.AppNav

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BotController.ensureReady(this)
        // The AI is set aside for now: free the model a test build may have downloaded.
        BotController.removeLeftoverFiles(this)
        setContent {
            MaterialTheme {
                Surface {
                    AppNav()
                }
            }
        }
    }
}
