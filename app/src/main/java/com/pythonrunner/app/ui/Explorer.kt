package com.pythonrunner.app.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pythonrunner.app.files.Clash
import com.pythonrunner.app.files.FileEntry
import com.pythonrunner.app.files.FileOp
import com.pythonrunner.app.files.FileOps
import com.pythonrunner.app.files.FileTree
import com.pythonrunner.app.files.FolderSync
import com.pythonrunner.app.files.Item
import com.pythonrunner.app.files.NewKind
import com.pythonrunner.app.files.Resolution
import com.pythonrunner.app.service.BotController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SortBy { NAME, KIND, DATE, SIZE }

/**
 * The bot's files, like Windows 11 Explorer: tap selects (and tapping the selected item again opens it),
 * long press is the right-click menu, the □ adds to the selection, and the toolbar has 新規・切り取り・
 * コピー・貼り付け・名前の変更・削除・元に戻す. Deleting moves to the bot's recycle bin.
 */
@Composable
fun Explorer(
    botId: String,
    rootLabel: String,
    /** The folder shown, relative to the bot ("" for the top); kept by the caller, where 取り込む puts files. */
    path: String,
    onPathChange: (String) -> Unit,
    mainFile: String?,
    refreshKey: Any,
    /** False while parts install or an import runs: nothing that changes files can start. */
    enabled: Boolean,
    onOpenFile: (String) -> Unit,
    onSetMain: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = rememberBotActions()
    val store = remember(botId) { BotController.store(context, botId) }
    val root = store.codeDir.apply { mkdirs() }
    val ops = remember { BotController.fileOps(context) }
    val busy by BotController.fileBusy.collectAsStateWithLifecycle()
    val clip by BotController.clipboard.collectAsStateWithLifecycle()
    val canChange = enabled && !busy
    var version by remember { mutableIntStateOf(0) }

    val back = remember(botId) { mutableStateListOf<String>() }
    val forward = remember(botId) { mutableStateListOf<String>() }
    var showTrash by rememberSaveable(botId) { mutableStateOf(false) }
    val selection = remember(botId) { mutableStateListOf<String>() }
    var sortBy by remember { mutableStateOf(SortBy.NAME) }
    var ascending by remember { mutableStateOf(true) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var newOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var properties by remember { mutableStateOf<List<String>?>(null) }
    var pasteClashes by remember { mutableStateOf<List<Clash>?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val cancel = remember { AtomicBoolean(false) }
    var pending by remember { mutableStateOf<BotController.PendingChanges?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val folder = if (File(root, path).isDirectory) path else ""
    val insidePart = folder.split('/').any { FileTree.isInstalledPart(it) }
    val entries = remember(refreshKey, version, folder, sortBy, ascending) {
        val list = FileTree.list(File(root, folder), root)
        val sorted = when (sortBy) {
            SortBy.NAME -> list
            SortBy.KIND -> list.sortedWith(compareBy({ !it.isDirectory }, { FileTree.kind(it.name, it.isDirectory) }, { it.name.lowercase() }))
            SortBy.DATE -> list.sortedWith(compareBy({ !it.isDirectory }, { it.modifiedMillis }))
            SortBy.SIZE -> list.sortedWith(compareBy({ !it.isDirectory }, { it.sizeBytes }))
        }
        if (ascending) sorted else sorted.reversed()
    }
    val trashCount by produceState(0, botId, version, refreshKey) {
        value = withContext(Dispatchers.IO) { BotController.trash(context, botId).entries().size }
    }
    // Items that vanished (deleted elsewhere) leave the selection.
    selection.retainAll(entries.map { it.relativePath }.toSet())

    fun go(to: String) {
        if (to == folder) return
        back += folder
        forward.clear()
        onPathChange(to)
        selection.clear()
    }

    fun isPart(p: String) = p.split('/').any { FileTree.isInstalledPart(it) }

    fun finish(result: Result<FileOp>, onOk: (FileOp) -> Unit = {}) {
        version++
        result.onSuccess(onOk).onFailure { e ->
            when (e) {
                is BotController.PendingChanges -> pending = e
                is java.util.concurrent.CancellationException -> Unit
                else -> Toast.makeText(context, e.message ?: "できませんでした", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteSelected() {
        val paths = selection.toList().filter { !isPart(it) }
        if (paths.isEmpty() || !canChange) return
        BotController.runFileOp(context, botId, paths.map { Item(botId, it) }, op = { ops.trash(botId, paths) }) { r ->
            finish(r) {
                selection.clear()
                scope.launch {
                    val answer = snackbar.showSnackbar(
                        "${paths.size} 個の項目をごみ箱に移動しました", actionLabel = "元に戻す", duration = SnackbarDuration.Short,
                    )
                    if (answer == SnackbarResult.ActionPerformed) BotController.undoLast(context, botId) { finish(it) }
                }
            }
        }
    }

    fun copyOrCut(cut: Boolean) {
        val paths = selection.toList().filter { !isPart(it) }
        if (paths.isNotEmpty()) BotController.clipboard.value = BotController.Clip(paths.map { Item(botId, it) }, cut)
    }

    fun doPaste(resolution: Resolution) {
        val c = clip ?: return
        cancel.set(false)
        progress = 0 to c.items.size
        BotController.runFileOp(
            context, botId, c.items + c.items.map { Item(botId, FileOps.join(folder, it.path.substringAfterLast('/'))) },
            op = {
                ops.paste(c.items, c.cut, botId, folder, resolution, onProgress = { d, t -> progress = d to t }, cancelled = { cancel.get() })
            },
        ) { r ->
            progress = null
            finish(r) { if (c.cut) BotController.clipboard.value = null }
        }
    }

    fun paste() {
        val c = clip ?: return
        if (!canChange) return
        scope.launch {
            val clashes = withContext(Dispatchers.IO) { runCatching { ops.clashes(c.items, botId, folder) }.getOrDefault(emptyList()) }
            if (clashes.isEmpty()) doPaste(Resolution.ReplaceAll) else pasteClashes = clashes
        }
    }

    fun create(kind: NewKind) {
        BotController.runFileOp(context, botId, listOf(Item(botId, folder)), op = { ops.create(botId, folder, kind) }) { r ->
            finish(r) { op -> renaming = (op as FileOp.Created).path; selection.clear(); selection += op.path }
        }
    }

    fun undo() {
        if (!canChange || BotController.undoHistory(botId).peek() == null) return
        BotController.undoLast(context, botId) { finish(it) }
    }

    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().height(520.dp).background(Color.White, shape).border(1.dp, Border, shape)) {
        // Toolbar, in Windows 11's order. It scrolls sideways on a narrow screen.
        Row(
            Modifier.fillMaxWidth().background(Toolbar, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Tool("＋ 新規 ▾", canChange && !showTrash && !insidePart) { newOpen = true }
                DropdownMenu(expanded = newOpen, onDismissRequest = { newOpen = false }) {
                    DropdownMenuItem(text = { Text("📁 フォルダー") }, onClick = { newOpen = false; create(NewKind.FOLDER) })
                    DropdownMenuItem(text = { Text("📄 テキスト ドキュメント") }, onClick = { newOpen = false; create(NewKind.TEXT) })
                    DropdownMenuItem(text = { Text("🐍 Python ファイル") }, onClick = { newOpen = false; create(NewKind.PYTHON) })
                }
            }
            val pickable = selection.isNotEmpty() && selection.none { isPart(it) } && !showTrash
            Tool("✂ 切り取り", pickable) { copyOrCut(true) }
            Tool("⧉ コピー", pickable) { copyOrCut(false) }
            Tool("📋 貼り付け", canChange && clip != null && !showTrash && !insidePart) { paste() }
            Tool("✏ 名前の変更", canChange && selection.size == 1 && !isPart(selection[0]) && !showTrash) { renaming = selection[0] }
            Tool("🗑 削除", canChange && pickable, color = Danger) { deleteSelected() }
            Tool("↶ 元に戻す", canChange && BotController.undoHistory(botId).peek() != null) { undo() }
            Box {
                Tool("⋯", true) { moreOpen = true }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(text = { Text("すべて選択") }, onClick = {
                        moreOpen = false; selection.clear(); selection += entries.map { it.relativePath }
                    })
                    DropdownMenuItem(text = { Text("選択を外す") }, onClick = { moreOpen = false; selection.clear() })
                    DropdownMenuItem(
                        text = { Text("プロパティ") }, enabled = selection.isNotEmpty(),
                        onClick = { moreOpen = false; properties = selection.toList() },
                    )
                }
            }
        }
        HorizontalDivider(color = Border)
        // Address bar: back, forward, up, and where we are.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(Toolbar)) {
            Tool("←", back.isNotEmpty()) { forward += folder; onPathChange(back.removeAt(back.lastIndex)); selection.clear() }
            Tool("→", forward.isNotEmpty()) { back += folder; onPathChange(forward.removeAt(forward.lastIndex)); selection.clear() }
            Tool("↑", folder.isNotEmpty() && !showTrash) { go(FileTree.parentOf(folder)) }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                if (showTrash) {
                    Text("ごみ箱", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
                } else {
                    FileTree.breadcrumbs(rootLabel, folder).forEachIndexed { i, (label, segmentPath) ->
                        if (i > 0) Text(" › ", color = Muted, fontSize = 13.sp)
                        Text(
                            label, fontSize = 13.sp, color = Link,
                            fontWeight = if (segmentPath == folder) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { go(segmentPath) }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = Border)
        Row(Modifier.weight(1f)) {
            // Left: the bot and its recycle bin.
            Column(Modifier.width(92.dp).fillMaxHeight().background(Toolbar)) {
                NavItem("📁 $rootLabel", !showTrash) { showTrash = false; selection.clear() }
                NavItem("🗑 ごみ箱 ($trashCount)", showTrash) { showTrash = true; selection.clear() }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (showTrash) {
                    TrashView(botId, refreshKey to version, enabled = canChange, onChanged = { version++ }, onPending = { pending = it })
                } else {
                    Row(Modifier.fillMaxWidth().background(HeaderBg).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Header("名前", Modifier.weight(1f), SortBy.NAME, sortBy, ascending) { by -> if (sortBy == by) ascending = !ascending else { sortBy = by; ascending = true } }
                        Header("更新日時", Modifier.width(70.dp), SortBy.DATE, sortBy, ascending) { by -> if (sortBy == by) ascending = !ascending else { sortBy = by; ascending = true } }
                        Header("サイズ", Modifier.width(48.dp), SortBy.SIZE, sortBy, ascending) { by -> if (sortBy == by) ascending = !ascending else { sortBy = by; ascending = true } }
                    }
                    // Tapping the empty space clears the selection, as in Windows.
                    Box(Modifier.weight(1f).fillMaxWidth().clickable(indication = null, interactionSource = null) { selection.clear() }) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            if (entries.isEmpty()) Text("このフォルダーは空です", color = Muted, modifier = Modifier.padding(10.dp))
                            for (entry in entries) {
                                val p = entry.relativePath
                                val part = isPart(p)
                                EntryRow(
                                    entry,
                                    selected = p in selection,
                                    isMain = p == mainFile,
                                    part = part,
                                    faded = clip?.cut == true && clip?.items?.any { it.botId == botId && it.path == p } == true,
                                    onTap = {
                                        if (selection.size == 1 && selection[0] == p) {
                                            if (entry.isDirectory) go(p) else onOpenFile(p)
                                        } else {
                                            selection.clear(); selection += p
                                        }
                                    },
                                    onToggle = { if (p in selection) selection -= p else selection += p },
                                    onLongPress = {
                                        if (p !in selection) { selection.clear(); selection += p }
                                        menuFor = p
                                    },
                                    menuOpen = menuFor == p,
                                    onMenuClose = { menuFor = null },
                                    menu = { close ->
                                        val single = selection.size == 1
                                        if (single) DropdownMenuItem(text = { Text("開く") }, onClick = {
                                            close(); if (entry.isDirectory) go(p) else onOpenFile(p)
                                        })
                                        if (!part) {
                                            DropdownMenuItem(text = { Text("切り取り") }, onClick = { close(); copyOrCut(true) })
                                            DropdownMenuItem(text = { Text("コピー") }, onClick = { close(); copyOrCut(false) })
                                            if (single) DropdownMenuItem(text = { Text("名前の変更") }, enabled = canChange, onClick = { close(); renaming = p })
                                            DropdownMenuItem(text = { Text("削除（ごみ箱へ）", color = Danger) }, enabled = canChange, onClick = { close(); deleteSelected() })
                                            if (single && !entry.isDirectory && entry.name.endsWith(".py") && p != mainFile) {
                                                HorizontalDivider()
                                                DropdownMenuItem(text = { Text("▶ これを最初に実行") }, onClick = { close(); onSetMain(p) })
                                            }
                                        }
                                        DropdownMenuItem(text = { Text("プロパティ") }, onClick = { close(); properties = selection.toList() })
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Border)
        Text(
            if (showTrash) "ごみ箱：$trashCount 個の項目" else "${entries.size} 個の項目" + if (selection.isNotEmpty()) "　${selection.size} 個の項目を選択" else "",
            fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    SnackbarHost(snackbar)

    renaming?.let { target ->
        RenameDialog(
            current = target.substringAfterLast('/'),
            siblings = File(root, FileTree.parentOf(target)).list().orEmpty().toList(),
            onRename = { newName ->
                renaming = null
                BotController.runFileOp(context, botId, listOf(Item(botId, target)), op = { ops.rename(botId, target, newName) }) { r ->
                    finish(r) { selection.clear(); selection += FileOps.join(FileTree.parentOf(target), newName) }
                }
            },
            onCancel = { renaming = null },
        )
    }
    properties?.let { paths -> PropertiesDialog(root, rootLabel, paths) { properties = null } }
    pasteClashes?.let { clashes ->
        ReplaceOrSkipDialog(
            clashes, folder.substringAfterLast('/').ifEmpty { rootLabel },
            onDone = { pasteClashes = null; doPaste(it) }, onCancel = { pasteClashes = null },
        )
    }
    progress?.let { (done, total) -> ProgressDialog(done, total) { cancel.set(true) } }
    pending?.let { p ->
        PendingDialog(
            p.paths,
            onReflect = {
                pending = null
                BotController.pendingChanges(context, p.botId)?.let { actions.review(p.botId, it) }
            },
            onCancel = { pending = null },
        )
    }
}

@Composable
private fun Tool(label: String, enabled: Boolean, color: Color = Color.Unspecified, onClick: () -> Unit) {
    TextButton(enabled = enabled, onClick = onClick) { Text(label, fontSize = 13.sp, color = if (enabled) color else Color.Unspecified) }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().background(if (selected) Selected else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
    )
}

@Composable
private fun Header(label: String, modifier: Modifier, by: SortBy, current: SortBy, ascending: Boolean, onSort: (SortBy) -> Unit) {
    val mark = if (by == current) (if (ascending) " ▲" else " ▼") else ""
    Text(
        label + mark, modifier.clickable { onSort(by) }, fontSize = 11.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold,
        textAlign = if (by == SortBy.SIZE) TextAlign.End else TextAlign.Start,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: FileEntry,
    selected: Boolean,
    isMain: Boolean,
    part: Boolean,
    faded: Boolean,
    onTap: () -> Unit,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    menuOpen: Boolean,
    onMenuClose: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    val note = when {
        entry.isDirectory && FileTree.isInstalledPart(entry.name) -> "アプリが入れた部品"
        !entry.isDirectory && entry.name == ".env" -> "🔒 トークンなど"
        !entry.isDirectory && FolderSync.isSecret(entry.name) -> "🔒 アプリの中だけ（フォルダには出ません）"
        else -> null
    }
    val textColor = if (part || faded) Muted else Color.Unspecified
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .background(if (selected) Selected else if (isMain) MainRow else Color.Transparent)
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                .padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                if (selected) "☑" else "☐", fontSize = 18.sp, color = if (selected) Link else Muted,
                modifier = Modifier.clickable(onClick = onToggle).padding(horizontal = 8.dp),
            )
            Text(FileTree.icon(entry.name, entry.isDirectory), fontSize = 16.sp, color = textColor)
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = textColor,
                    fontWeight = if (isMain) FontWeight.Bold else FontWeight.Normal,
                )
                note?.let { Text(it, color = Muted, fontSize = 10.sp, maxLines = 2) }
                if (isMain) {
                    Text(
                        "▶ 最初に実行", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                        modifier = Modifier.background(MainBadge, RoundedCornerShape(8.dp)).padding(horizontal = 5.dp),
                    )
                }
            }
            Text(DATE_FORMAT.format(Date(entry.modifiedMillis)), Modifier.width(70.dp), fontSize = 10.sp, color = Muted)
            Text(
                if (entry.isDirectory) "${entry.childCount}個" else FileTree.formatSize(entry.sizeBytes),
                Modifier.width(48.dp), fontSize = 10.sp, color = Muted, textAlign = TextAlign.End,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuClose) { menu(onMenuClose) }
    }
}

private val Border = Color(0xFFCED4DA)
private val Toolbar = Color(0xFFF1F3F5)
private val HeaderBg = Color(0xFFF8F9FA)
private val Selected = Color(0xFFDBE4FF)
private val Link = Color(0xFF1C3FAA)
private val Muted = Color(0xFF868E96)
private val MainRow = Color(0xFFEBFBEE)
private val MainBadge = Color(0xFF2F9E44)
private val Danger = Color(0xFFC92A2A)
private val DATE_FORMAT = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)
