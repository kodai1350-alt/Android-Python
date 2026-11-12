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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.pythonrunner.app.files.FileEntry
import com.pythonrunner.app.files.FileTree
import com.pythonrunner.app.files.FolderSync
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bot's files in one place, like a folder on a PC: an address bar with an up button, then name,
 * kind, date and size. Tap opens a folder (or a file's menu); a long press deletes, after asking.
 */
@Composable
fun Explorer(
    root: File,
    rootLabel: String,
    /** The folder shown, relative to [root] ("" for the top). */
    path: String,
    onPathChange: (String) -> Unit,
    mainFile: String?,
    refreshKey: Any,
    onSetMain: (String) -> Unit,
    /** Deletes a file or folder of the bot; null while deleting is not allowed. */
    onDelete: ((String) -> Unit)?,
    /** Shown when a long press cannot delete right now. */
    deleteBlockedMessage: String,
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<FileEntry?>(null) }
    // The bot may delete the folder being shown; fall back to the top.
    val folder = if (File(root, path).isDirectory) path else ""
    val insidePart = folder.split('/').any { FileTree.isInstalledPart(it) }
    val entries = remember(refreshKey, root, folder) { FileTree.list(File(root, folder), root) }
    val shape = RoundedCornerShape(8.dp)

    fun askDelete(entry: FileEntry) {
        when {
            insidePart || entry.isDirectory && FileTree.isInstalledPart(entry.name) -> Unit
            onDelete == null -> Toast.makeText(context, deleteBlockedMessage, Toast.LENGTH_SHORT).show()
            else -> confirmDelete = entry
        }
    }

    Column(Modifier.fillMaxWidth().height(480.dp).background(Color.White, shape).border(1.dp, Border, shape)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .background(Toolbar, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .padding(horizontal = 2.dp),
        ) {
            TextButton(enabled = folder.isNotEmpty(), onClick = { onPathChange(FileTree.parentOf(folder)) }) { Text("⬆ 上へ") }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                FileTree.breadcrumbs(rootLabel, folder).forEachIndexed { i, (label, segmentPath) ->
                    if (i > 0) Text(" › ", color = Muted, fontSize = 13.sp)
                    Text(
                        label, fontSize = 13.sp, color = Link,
                        fontWeight = if (segmentPath == folder) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { onPathChange(segmentPath) }.padding(vertical = 10.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = Border)
        Row(Modifier.fillMaxWidth().background(Header).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text("名前", Modifier.weight(1f), fontSize = 11.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
            Text("種類", Modifier.width(KIND_WIDTH), fontSize = 11.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
            Text("更新日時", Modifier.width(70.dp), fontSize = 11.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
            Text("サイズ", Modifier.width(50.dp), fontSize = 11.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (entries.isEmpty()) {
                Text("このフォルダは空です", color = Muted, modifier = Modifier.padding(10.dp))
            } else {
                for (entry in entries) {
                    val part = insidePart || entry.isDirectory && FileTree.isInstalledPart(entry.name)
                    EntryRow(
                        entry,
                        isMain = entry.relativePath == mainFile,
                        part = part,
                        canDelete = onDelete != null && !part,
                        onOpen = { onPathChange(entry.relativePath) },
                        onSetMain = { onSetMain(entry.relativePath) },
                        onAskDelete = { askDelete(entry) },
                    )
                }
            }
        }
        HorizontalDivider(color = Border)
        Text(
            "${entries.size}個の項目　（長押しで削除）",
            fontSize = 10.sp, color = Muted,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }

    confirmDelete?.let { entry ->
        // Counted off the main thread: a folder holding node_modules has tens of thousands of files.
        val count by produceState<Int?>(null, entry) {
            value = if (entry.isDirectory) withContext(Dispatchers.IO) { FileTree.fileCount(File(root, entry.relativePath)) } else 0
        }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = {
                Text(if (entry.isDirectory) "フォルダ『${entry.name}』を中身ごと削除しますか？" else "『${entry.name}』を削除しますか？")
            },
            text = {
                Text(
                    if (entry.isDirectory) "中の ${count ?: "…"} 個のファイルも消えます。共有フォルダからも消えます。" else "共有フォルダからも消えます。",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete?.invoke(entry.relativePath) }) {
                    Text("削除する", color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("やめる") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: FileEntry,
    isMain: Boolean,
    /** Inside or one of the folders the app fills itself: shown faded, never deleted by hand. */
    part: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onSetMain: () -> Unit,
    onAskDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val note = when {
        entry.isDirectory && FileTree.isInstalledPart(entry.name) -> "アプリが入れた部品"
        !entry.isDirectory && entry.name == ".env" -> "🔒 トークンなど"
        !entry.isDirectory && FolderSync.isSecret(entry.name) -> "🔒 アプリの中だけ（フォルダには出ません）"
        else -> null
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .background(if (isMain) MainRow else Color.Transparent)
                .combinedClickable(
                    onClick = { if (entry.isDirectory) onOpen() else menuOpen = true },
                    onLongClick = onAskDelete,
                )
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Text(FileTree.icon(entry.name, entry.isDirectory), fontSize = 16.sp)
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (part) Muted else Color.Unspecified,
                    fontWeight = if (isMain) FontWeight.Bold else FontWeight.Normal,
                )
                note?.let { Text(it, color = Muted, fontSize = 10.sp, maxLines = 2) }
                if (isMain) {
                    // One line always: wrapped, it stood one character per line and squeezed the name.
                    Text(
                        "▶ 最初に実行", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.background(MainBadge, RoundedCornerShape(8.dp)).padding(horizontal = 5.dp),
                    )
                }
            }
            Text(FileTree.kind(entry.name, entry.isDirectory), Modifier.width(KIND_WIDTH), fontSize = 10.sp, color = Muted, maxLines = 1)
            Text(DATE_FORMAT.format(Date(entry.modifiedMillis)), Modifier.width(70.dp), fontSize = 10.sp, color = Muted)
            Text(
                if (entry.isDirectory) "${entry.childCount}個" else FileTree.formatSize(entry.sizeBytes),
                Modifier.width(50.dp), fontSize = 10.sp, color = Muted, textAlign = TextAlign.End,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (isMain) {
                DropdownMenuItem(text = { Text("✓ 最初に実行するファイルです") }, enabled = false, onClick = {})
            } else if (!part && entry.name.endsWith(".py")) {
                DropdownMenuItem(text = { Text("▶ これを最初に実行") }, onClick = { onSetMain(); menuOpen = false })
            }
            DropdownMenuItem(
                text = { Text("${FileTree.formatSize(entry.sizeBytes)}・${DATE_FORMAT.format(Date(entry.modifiedMillis))}", fontSize = 12.sp) },
                enabled = false, onClick = {},
            )
            if (!part) {
                DropdownMenuItem(
                    text = { Text("🗑 削除", color = if (canDelete) Danger else Muted) },
                    onClick = { menuOpen = false; onAskDelete() },
                )
            }
            DropdownMenuItem(text = { Text("閉じる") }, onClick = { menuOpen = false })
        }
    }
}

private val KIND_WIDTH = 58.dp
private val Border = Color(0xFFCED4DA)
private val Toolbar = Color(0xFFF1F3F5)
private val Header = Color(0xFFF8F9FA)
private val Link = Color(0xFF1C3FAA)
private val Muted = Color(0xFF868E96)
private val MainRow = Color(0xFFEBFBEE)
private val MainBadge = Color(0xFF2F9E44)
private val Danger = Color(0xFFC92A2A)
private val DATE_FORMAT = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)
