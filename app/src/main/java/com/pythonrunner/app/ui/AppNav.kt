package com.pythonrunner.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pythonrunner.app.service.BotController
import com.pythonrunner.app.service.ImportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Which screen is shown; saved as a string so it survives turning the phone. */
sealed interface Screen {
    data object Home : Screen
    data class Detail(val botId: String) : Screen
    data object Settings : Screen

    fun save(): String = when (this) {
        Home -> "home"
        Settings -> "settings"
        is Detail -> "bot:$botId"
    }

    companion object {
        fun restore(text: String): Screen = when {
            text == "settings" -> Settings
            text.startsWith("bot:") -> Detail(text.removePrefix("bot:"))
            else -> Home
        }
    }
}

/**
 * The app: home, a bot's detail, app settings. Also owns what every screen shares: the file pickers,
 * the import's progress and questions, and the automatic check of the shared folders.
 */
@Composable
fun AppNav() {
    val context = LocalContext.current
    var saved by rememberSaveable { mutableStateOf("home") }
    val screen = Screen.restore(saved)
    fun go(to: Screen) {
        saved = to.save()
    }
    BackHandler(enabled = screen != Screen.Home) { go(Screen.Home) }

    // The pickers: which bot the picked file goes into (null = a new bot).
    var pickTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) BotController.importFile(context, uri, pickTarget)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) BotController.importFolder(context, uri, pickTarget)
    }
    val imports = remember {
        ImportActions(
            pickFile = { id -> pickTarget = id; pickFile.launch(arrayOf("*/*")) },
            pickFolder = { id -> pickTarget = id; pickFolder.launch(null) },
        )
    }

    val importState by BotController.importState.collectAsStateWithLifecycle()
    var lastDone by remember { mutableStateOf<ImportState.Done?>(null) }
    LaunchedEffect(importState) {
        val done = importState as? ImportState.Done ?: return@LaunchedEffect
        lastDone = done
        if (done.isNewBot) go(Screen.Detail(done.botId))
        BotController.finishImport()
    }

    // While the app is shown, notice changes made in the shared folders (a file edited on a PC, a folder
    // added) without any button: a cheap fingerprint every few seconds, the full comparison only when it
    // moved. Bringing changes in still needs 反映する.
    var hasStorage by remember { mutableStateOf(BotController.hasSharedStorageAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasStorage = BotController.hasSharedStorageAccess(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(hasStorage, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            withContext(Dispatchers.IO) { runCatching { BotController.exportAll(context) } }
            while (true) {
                withContext(Dispatchers.IO) { runCatching { BotController.checkShared(context) } }
                delay(SHARED_CHECK_MILLIS)
            }
        }
    }

    Column {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ImportStateView(
                importState,
                onConfirm = { BotController.confirmImport(context) },
                onCancel = { BotController.cancelImport(context) },
                onClose = { BotController.finishImport() },
                onName = { BotController.confirmName(context, it) },
            )
        }
        when (screen) {
            Screen.Home -> HomeScreen(imports, onOpen = { go(Screen.Detail(it)) }, onSettings = { go(Screen.Settings) })
            Screen.Settings -> AppSettingsScreen(onBack = { go(Screen.Home) })
            is Screen.Detail -> BotDetailScreen(
                botId = screen.botId,
                imports = imports,
                importResult = lastDone?.takeIf { it.botId == screen.botId },
                onImportResultSeen = { lastDone = null },
                onBack = { go(Screen.Home) },
            )
        }
    }
}
