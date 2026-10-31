package com.pythonrunner.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonrunner.app.files.FileEntry
import com.pythonrunner.app.files.FileTree
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PlaceGroup(val title: String) {
    BOT("Bot"),
    SHARED("このスマホ（共有）"),
    APP("アプリ専用"),
}

/** A starting point in the left navigation, like "This PC" entries in a PC file explorer. */
data class Place(
    val group: PlaceGroup,
    val label: String,
    /** null when the place cannot be opened yet (storage permission missing). */
    val root: File?,
    val isBotFolder: Boolean = false,
)

/**
 * A PC-style file explorer: left navigation grouped by Bot / shared phone storage / app-private,
 * an address bar with up button, the full path, and name/date/size columns.
 */
@Composable
fun Explorer(
    places: List<Place>,
    mainFile: String?,
    refreshKey: Any,
    onSetMain: (String) -> Unit,
    /** Deletes a file of the bot folder; null while deleting is not allowed (the bot is running). */
    onDelete: ((String) -> Unit)?,
    lockedMessage: @Composable () -> Unit,
) {
    var navOpen by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf<FileEntry?>(null) }
    var placeIndex by remember { mutableStateOf(0) }
    var path by remember { mutableStateOf("") }
    val place = places.getOrElse(placeIndex) { places.first() }
    val root = place.root
    // The bot may delete the folder being shown; fall back to the place's top.
    val folder = if (root != null && File(root, path).isDirectory) path else ""
    val entries = remember(refreshKey, place, folder) {
        root?.let { FileTree.list(File(it, folder), it) }.orEmpty()
    }
    val shape = RoundedCornerShape(8.dp)

    Column(Modifier.fillMaxWidth().height(480.dp).background(Color.White, shape).border(1.dp, Border, shape)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .background(Toolbar, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .padding(horizontal = 2.dp),
        ) {
            TextButton(onClick = { navOpen = !navOpen }) { Text("☰") }
            TextButton(enabled = folder.isNotEmpty(), onClick = { path = FileTree.parentOf(folder) }) { Text("⬆") }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text(place.group.title + " › ", fontSize = 13.sp, color = Muted)
                FileTree.breadcrumbs(place.label, folder).forEachIndexed { i, (label, segmentPath) ->
                    if (i > 0) Text(" › ", color = Muted, fontSize = 13.sp)
                    Text(
                        label, fontSize = 13.sp, color = Link,
                        fontWeight = if (segmentPath == folder) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { path = segmentPath }.padding(vertical = 10.dp),
                    )
                }
            }
        }
        if (root != null) {
            Text(
                "場所: " + File(root, folder).path,
                fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(Toolbar).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        HorizontalDivider(color = Border)

        Row(Modifier.weight(1f)) {
            if (navOpen) {
                NavPane(places, placeIndex, Modifier.width(128.dp).fillMaxHeight()) { index ->
                    placeIndex = index
                    path = ""
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().background(Header).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text("名前", Modifier.weight(1f), fontSize = 11.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                    // With the navigation open the name needs the room more than the kind column does.
                    if (!navOpen) Text("種類", Modifier.width(KIND_WIDTH), fontSize = 11.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                    if (!navOpen) Text("更新日時", Modifier.width(70.dp), fontSize = 11.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                    Text("サイズ", Modifier.width(50.dp), fontSize = 11.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    when {
                        root == null -> Box(Modifier.padding(10.dp)) { lockedMessage() }
                        entries.isEmpty() -> Text("このフォルダは空です", color = Muted, modifier = Modifier.padding(10.dp))
                        else -> for (entry in entries) {
                            EntryRow(
                                entry,
                                showDate = !navOpen,
                                isMain = place.isBotFolder && entry.relativePath == mainFile,
                                canBeMain = place.isBotFolder,
                                deletable = place.isBotFolder,
                                canDelete = onDelete != null,
                                onOpen = { path = entry.relativePath },
                                onSetMain = { onSetMain(entry.relativePath) },
                                onDelete = { confirmDelete = entry },
                            )
                        }
                    }
                }
                HorizontalDivider(color = Border)
                confirmDelete?.let { entry ->
                    AlertDialog(
                        onDismissRequest = { confirmDelete = null },
                        title = { Text("「${entry.name}」を削除しますか？") },
                        text = { Text("共有のコピーからも消えます。元に戻せません。") },
                        confirmButton = {
                            TextButton(onClick = { confirmDelete = null; onDelete?.invoke(entry.relativePath) }) {
                                Text("削除", color = Danger)
                            }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("やめる") } },
                    )
                }
                Text(
                    "${entries.size}個の項目",
                    fontSize = 10.sp, color = Muted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun NavPane(places: List<Place>, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    Column(modifier.background(Toolbar).verticalScroll(rememberScrollState())) {
        var lastGroup: PlaceGroup? = null
        places.forEachIndexed { index, place ->
            if (place.group != lastGroup) {
                lastGroup = place.group
                Text(
                    place.group.title, fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
                )
            }
            Text(
                place.label,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                color = if (place.root == null) Muted else Color.Unspecified,
                modifier = Modifier.fillMaxWidth()
                    .background(if (index == selected) Selected else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun EntryRow(
    entry: FileEntry,
    showDate: Boolean,
    isMain: Boolean,
    canBeMain: Boolean,
    deletable: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onSetMain: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .background(if (isMain) MainRow else Color.Transparent)
                .clickable { if (entry.isDirectory) onOpen() else menuOpen = true }
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Text(FileTree.icon(entry.name, entry.isDirectory), fontSize = 16.sp)
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isMain) FontWeight.Bold else FontWeight.Normal,
                )
                if (isMain) {
                    // One line always: wrapped, it stood one character per line and squeezed the name.
                    Text(
                        "▶ 最初に実行", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.background(MainBadge, RoundedCornerShape(8.dp)).padding(horizontal = 5.dp),
                    )
                }
            }
            if (showDate) {
                Text(FileTree.kind(entry.name, entry.isDirectory), Modifier.width(KIND_WIDTH), fontSize = 10.sp, color = Muted, maxLines = 1)
            }
            if (showDate) {
                Text(DATE_FORMAT.format(Date(entry.modifiedMillis)), Modifier.width(70.dp), fontSize = 10.sp, color = Muted)
            }
            Text(
                if (entry.isDirectory) "${entry.childCount}個" else FileTree.formatSize(entry.sizeBytes),
                Modifier.width(50.dp), fontSize = 10.sp, color = Muted, textAlign = TextAlign.End,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (isMain) {
                DropdownMenuItem(text = { Text("✓ 最初に実行するファイルです") }, enabled = false, onClick = {})
            } else if (canBeMain && entry.name.endsWith(".py")) {
                DropdownMenuItem(text = { Text("▶ これを最初に実行") }, onClick = { onSetMain(); menuOpen = false })
            }
            DropdownMenuItem(
                text = { Text("${FileTree.formatSize(entry.sizeBytes)}・${DATE_FORMAT.format(Date(entry.modifiedMillis))}", fontSize = 12.sp) },
                enabled = false, onClick = {},
            )
            if (deletable) {
                if (canDelete) {
                    DropdownMenuItem(text = { Text("🗑 削除", color = Danger) }, onClick = { menuOpen = false; onDelete() })
                } else {
                    DropdownMenuItem(text = { Text("削除するにはBotを停止してください", fontSize = 12.sp) }, enabled = false, onClick = {})
                }
            }
            DropdownMenuItem(text = { Text("閉じる") }, onClick = { menuOpen = false })
        }
    }
}

private val KIND_WIDTH = 58.dp
private val Border = Color(0xFFCED4DA)
private val Toolbar = Color(0xFFF1F3F5)
private val Header = Color(0xFFF8F9FA)
private val Selected = Color(0xFFDBE4FF)
private val Link = Color(0xFF1C3FAA)
private val Muted = Color(0xFF868E96)
private val MainRow = Color(0xFFEBFBEE)
private val MainBadge = Color(0xFF2F9E44)
private val Danger = Color(0xFFC92A2A)
private val DATE_FORMAT = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)
