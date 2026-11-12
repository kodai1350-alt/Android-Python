# PC-like Files Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The files screen works like Windows 11 Explorer: tap to select and tap again to open, long press as right-click, a toolbar, cut/copy/paste, rename, new, properties, a per-bot recycle bin, undo, and a Notepad for text files.

**Architecture:** File operations live in JVM-tested classes under `files/`: `FileNames`, `Trash`, `FileOps` with its `FileOp` records and `UndoHistory`, and `TextFiles`. `BotController` owns one `Trash` and one `UndoHistory` per bot, plus the app-wide clipboard, and runs every operation off the main thread. The Compose screens (`Explorer`, the new `ExplorerDialogs`, `TrashView` and `NotepadScreen`) only show state and call the controller. The importer and `FolderSync` hand replaced or removed files to the trash through callbacks.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), kotlin.test on the JVM. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-13-pc-like-explorer-design.md` (Japanese; section numbers below refer to it). It builds on `docs/superpowers/specs/2026-11-12-files-env-libraries-design.md`.

## Global Constraints

- Branch `spike/node`. All 194+ existing unit tests must keep passing (`./gradlew -PnodeBundlePython=python testDebugUnitTest`), and `lintDebug` must pass, because CI runs it. Never write a literal BOM or other invisible character in source; use `﻿`.
- UI wording follows Windows 11 exactly where the spec quotes it: 「ファイルの置換またはスキップ」「ファイルを置き換える」「ファイルをスキップする」「ファイルごとに決める」「新しいフォルダー」「新しいテキスト ドキュメント.txt」「〇〇 - コピー」「拡張子を変更すると、ファイルが使えなくなる可能性があります。変更しますか？」「対象のフォルダーは、元のフォルダーのサブフォルダーです」「〇〇への変更内容を保存しますか？」「保存する／保存しない／キャンセル」「完全に削除しますか？元に戻せません」.
- Invalid name characters are `/ \ : * ? " < > |`, plus empty, `.` and `..`. Names that differ only in letter case clash (spec 8.1-5).
- `node_modules` and `.venv` are never copied. Their own menu offers only 開く and プロパティ. Moving, renaming or deleting a folder that contains them is allowed (spec 8.1-3).
- No operation follows a symbolic link. A link is moved, deleted or trashed as itself, and skipped when copying.
- Every path that removes or overwrites a bot file goes through that bot's trash: delete, paste-replace, import-replace, whole-bot replace (overwrite and 削除), and 反映する. Notepad saves do not (spec 8.1-2, 5.5).
- Undo keeps up to 20 operations per bot, in memory until the process ends. A cut from bot A pasted into B is recorded on B (spec 8.1-6).
- Notepad opens text files up to 1 MB (text = no 0 byte in the first 8 KB, and valid UTF-8 or Shift_JIS). Wrapping is on. A save keeps the file's charset and line ending, writing to a temp file and then renaming.
- Every operation that changes files is disabled while parts are installing (`workBusy`) or an import is running. A running bot does not block operations; changed files lead to the existing 「今すぐBotを再起動しますか？」 prompt (spec 7).
- Before an operation touches paths that have unreflected shared-folder changes, the app asks 「先に反映する／キャンセル」 (spec 8.1-1).

## Review Focus

1. Cut in bot A, paste into bot B, then 元に戻す in B: the files are back in A at their original paths, with nothing left in B. Test in Task 4.
2. Pasting a folder into itself or into one of its subfolders is refused with 「対象のフォルダーは、元のフォルダーのサブフォルダーです」, and nothing changes. Test in Task 3.
3. Opening a Shift_JIS file with CRLF line endings, editing it and saving it writes Shift_JIS bytes with CRLF line endings. Test in Task 5.
4. Trashing or copying a folder that contains a link to a folder outside the bot: the outside files stay untouched, and the copy leaves out the link. Test in Task 3.
5. Undo after the file was changed again since the operation: it stops with 「元に戻せませんでした（ファイルが変わっています）」 and touches nothing else. Test in Task 4.

---

### Task 1: Names (`FileNames`)

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/files/FileNames.kt`
- Test: `app/src/test/java/com/pythonrunner/app/files/FileNamesTest.kt`

**Interfaces:**
- Produces: `object FileNames`:
  - `fun problem(name: String, siblings: Collection<String>, renaming: String? = null): String?` returns null when the name is fine, otherwise a Japanese reason.
  - `fun sameName(a: String, b: String): Boolean` compares ignoring case.
  - `fun unique(wanted: String, taken: Collection<String>): String` gives 「x (2).py」 and so on.
  - `fun copyName(name: String, taken: Collection<String>): String` gives 「x - コピー.py」, then 「x - コピー (2).py」.
  - `fun extensionChanged(old: String, new: String): Boolean`.
  - `const val NEW_FOLDER = "新しいフォルダー"`, `const val NEW_TEXT = "新しいテキスト ドキュメント.txt"`, `const val NEW_PYTHON = "新しい Python ファイル.py"`.

- [ ] **Step 1: Write the failing tests:**
  - `badNamesAreRefusedWithAReason`: each of `"a/b"`, `"a:b"`, `""`, `"."`, `".."`, `"a?b"` gives a non-null result. `problem("main.py", listOf("Main.py"))` is non-null, because the names differ only by case.
  - `renamingOnlyTheCaseOfItselfIsFine`: `problem("A.py", listOf("a.py", "b.py"), renaming = "a.py")` is null.
  - `uniqueAndCopyNamesFollowWindows`:
    - `unique("新しいフォルダー", listOf("新しいフォルダー"))` is `"新しいフォルダー (2)"`.
    - `copyName("main.py", listOf("main.py"))` is `"main - コピー.py"`.
    - `copyName("main.py", listOf("main.py", "main - コピー.py"))` is `"main - コピー (2).py"`.
    - `copyName("src", listOf("src"))` is `"src - コピー"`.
  - `extensionChangeIsNoticed`: `extensionChanged("a.py", "a.txt")` is true, `extensionChanged("a.py", "b.py")` is false, `extensionChanged("src", "lib")` is false.
- [ ] **Step 2: Run** `--tests '*FileNamesTest'`. Expected: FAIL (unresolved).
- [ ] **Step 3: Implement `FileNames`.** Reasons: 「名前に次の文字は使えません: / \ : * ? " < > |」, 「名前を入れてください」, 「同じ名前のものがあります」. The extension is the text after the last `.`, ignored when the name starts with `.` and has no other dot.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: Windows-style name rules for the files screen`.

### Task 2: Recycle bin (`Trash`)

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/files/Trash.kt`
- Modify: `app/src/main/java/com/pythonrunner/app/bot/BotStore.kt` (`val trashDir = File(dir, "trash")`; `deleteAll` already removes `dir`)
- Test: `app/src/test/java/com/pythonrunner/app/files/TrashTest.kt`

**Interfaces:**
- Produces:
  - `data class TrashEntry(val id: String, val originalPath: String, val deletedAt: Long, val isDirectory: Boolean, val size: Long, val item: File)`.
  - `class Trash(val root: File)` with these members:
    - `fun put(source: File, originalPath: String, now: Long = System.currentTimeMillis()): TrashEntry` moves `source` (a file, folder or link) into `root/<id>/item`. It writes `root/<id>/info`. `id` is `"<now>-<n>"`, unique.
    - `fun entries(): List<TrashEntry>` returns the newest first. An entry without `info` or `item` is skipped.
    - `fun take(entry: TrashEntry, target: File)` moves the item to `target`, creating parents, and removes the entry. It throws `IOException` if `target` exists.
    - `fun delete(entry: TrashEntry)` and `fun empty()` remove entries without following links (`FileTree.deleteTree`).
    - `fun size(): Long`.

- [ ] **Step 1: Write the failing tests:**
  - `putAndTakeBringAFileBack`: put `code/a.py`; then `entries().single().originalPath == "a.py"` and `code/a.py` no longer exists. `take(entry, File(code, "a.py"))` restores the content, and `entries()` is empty.
  - `foldersKeepTheirContents`: put `code/src` (holding `x/y.txt`); take restores `src/x/y.txt`.
  - `newestFirstAndSurvivesARestart`: put a file at now=1, put another at now=2, then `Trash(root).entries().map { it.originalPath }` lists the second one first.
  - `takeRefusesToOverwrite`: target exists, so `take` throws `IOException` and the entry stays.
  - `emptyNeverFollowsLinks`: a trashed folder holds a link to `outside/` (skip if the link cannot be created). `empty()` leaves `outside/keep.txt` in place.
  - `brokenEntriesAreSkipped`: `root/x/` without `info` is not listed.
- [ ] **Step 2: Run** `--tests '*TrashTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `info` holds `key=value` lines: `path`, `deleted`, `dir`, `size`. Write it whole to a temp file and rename it before moving the item.
  - Size is measured with `FileTree.fileCount`'s non-following walk, summing lengths.
  - Move with `renameTo`; on failure, copy without following links and then `deleteTree`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: per-bot recycle bin`.

### Task 3: File operations (`FileOps`, `FileOp`)

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/files/FileOps.kt`
- Test: `app/src/test/java/com/pythonrunner/app/files/FileOpsTest.kt`

**Interfaces:**
- Consumes: Task 1 `FileNames`, Task 2 `Trash`/`TrashEntry`, `FileTree.isInstalledPart`, `FileTree.deleteTree`.
- Produces:
  - `data class BotSpace(val codeDir: File, val trash: Trash)`.
  - `data class Item(val botId: String, val path: String)`.
  - `enum class Keep { INCOMING, EXISTING, BOTH }`.
  - `sealed interface Resolution { data object ReplaceAll : Resolution; data object SkipAll : Resolution; data class PerFile(val keep: Map<String, Keep>) : Resolution }`, keyed by the destination relative path.
  - `data class Clash(val path: String, val existingSize: Long, val existingModified: Long, val incomingSize: Long, val incomingModified: Long)`, where `path` is relative to the destination bot.
  - `sealed interface FileOp` with these members:
    - `Trashed(botId: String, entries: List<TrashEntry>)`
    - `Moved(moves: List<Move>, replaced: Map<String, List<TrashEntry>>)`, with `data class Move(val from: Item, val to: Item)`. `replaced` is keyed by botId.
    - `Copied(botId: String, created: List<String>, replaced: List<TrashEntry>)`
    - `Created(botId: String, path: String)`
    - `Restored(botId: String, paths: List<String>, entries: List<TrashEntry>, replaced: List<TrashEntry>)`
  - `class FileOps(private val space: (String) -> BotSpace)` with these members:
    - `fun trash(botId: String, paths: List<String>): FileOp.Trashed`.
    - `fun rename(botId: String, path: String, newName: String): FileOp.Moved`. Throws `IllegalArgumentException(problem)` when `FileNames.problem` is non-null.
    - `fun create(botId: String, dir: String, kind: NewKind): FileOp.Created`, with `enum class NewKind { FOLDER, TEXT, PYTHON }`. Uses the unique name.
    - `fun clashes(sources: List<Item>, toBot: String, toDir: String): List<Clash>`. Folder onto folder is merged: only files clash, at any depth, case-insensitive. Same-folder copies never clash (they get copy names).
    - `fun paste(sources: List<Item>, cut: Boolean, toBot: String, toDir: String, resolution: Resolution, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): FileOp`. A cut gives `Moved`, a copy gives `Copied`. Replaced existing files go to the destination trash. `Keep.BOTH` puts the incoming file at `FileNames.unique`. Copies skip `node_modules`, `.venv` and links. When a folder is pasted into itself or a subfolder, it throws `IllegalArgumentException("対象のフォルダーは、元のフォルダーのサブフォルダーです")` before changing anything. On an exception or cancel, it undoes what it already did (Task 4's inverse) and rethrows (`CancellationException` for cancel).
    - `fun restore(botId: String, entries: List<TrashEntry>, resolution: Resolution): FileOp.Restored`, back to `originalPath`.
    - `fun remap(path: String, moves: List<FileOp.Move>, botId: String): String?` gives where a path of `botId` went (for 最初に実行 and parts records), or null if it did not move.

- [ ] **Step 1: Write the failing tests** (temp bots `a` and `b`, each with its own code dir and trash):
  - `trashMovesIntoTheBin`: `trash("a", listOf("src", "x.py"))` → both gone from code, and `space("a").trash.entries().size == 2`.
  - `renameRefusesBadAndClashingNames`: `rename("a", "x.py", "y?.py")` and `rename("a", "x.py", "Y.py")` (when `y.py` exists) throw. `rename("a", "x.py", "X.py")` works.
  - `createUsesWindowsNames`: `create("a", "", NewKind.FOLDER)` twice gives paths `新しいフォルダー` and `新しいフォルダー (2)`.
  - `copyIntoTheSameFolderMakesACopyName`: `paste(listOf(Item("a", "main.py")), cut = false, "a", "", ReplaceAll)` creates `main - コピー.py`.
  - `clashesAreFoundInsideMergedFoldersIgnoringCase`: `a/player/{src/A.js, p.json}` pasted into `b/` which has `player/src/a.js`. Then `clashes(...).map { it.path } == listOf("player/src/a.js")`.
  - `replaceSkipAndBoth`:
    - ReplaceAll puts the old `b/player/src/a.js` in b's trash and copies the new one in.
    - SkipAll keeps the old one.
    - `PerFile(mapOf("player/src/a.js" to Keep.BOTH))` leaves `a.js` and `A (2).js` (the incoming name made unique).
  - `copiesLeaveOutInstalledPartsAndLinks`: copying `player` (holding `node_modules/x/i.js` and a link `linked → outside/`; skip the link part if links cannot be created) creates no `node_modules` and no `linked` in the destination, and `outside/` is unchanged.
  - `cutAcrossBotsMoves`: cut `a/player` and paste it into `b/` → `a/player` is gone and `b/player` exists. The op is `Moved` with `from = Item("a", "player")`, `to = Item("b", "player")`.
  - `pastingAFolderIntoItselfIsRefused`: paste `a/src` into `a/src/lib` → throws with the exact message, and the tree is unchanged.
  - `aFailedPasteLeavesNothingHalfDone`: a source file deleted between `clashes` and `paste` → `paste` throws, and the destination has no new files.
  - `remapFollowsMovesAndRenames`: after `rename("a", "bot", "core")`, `remap("bot/main.py", op.moves, "a") == "core/main.py"`, and `remap("other.py", …)` is null.
- [ ] **Step 2: Run** `--tests '*FileOpsTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `FileOps`.**
  - Moves within one bot use `renameTo`; across bots, copy then `deleteTree` (same filesystem, so usually `renameTo` works too).
  - Clash detection walks the incoming tree with `java.nio.file.Files` without following links.
  - Use `FileNames.sameName` for every lookup in the destination.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: Windows-style file operations with clashes, parts left out and no link following`.

### Task 4: Undo (`UndoHistory`, `FileOps.undo`)

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/files/FileOps.kt`
- Create: `app/src/main/java/com/pythonrunner/app/files/UndoHistory.kt`
- Test: `app/src/test/java/com/pythonrunner/app/files/UndoTest.kt`

**Interfaces:**
- Produces:
  - `class UndoHistory(private val max: Int = 20) { fun push(op: FileOp); fun pop(): FileOp?; fun peek(): FileOp?; val size: Int }`. The oldest entry drops after `max`.
  - `fun FileOps.undo(op: FileOp)`. Throws `IllegalStateException("元に戻せませんでした（ファイルが変わっています）")` when something the op created or moved is no longer where and how it left it (check existence and, for files, length and lastModified recorded in the op). The check happens before any change.
  - The undo for each op:
    - `Trashed` → `take` back. A clash at the original place → `IllegalStateException` with the same message.
    - `Moved` → move back and restore `replaced`.
    - `Copied` → trash `created` and restore `replaced`.
    - `Created` → trash the path.
    - `Restored` → put them back in the trash.
  - Every op records, for each path it leaves behind, `Stamp(path, size, modified)`. Add `val stamps: List<Stamp>` to `FileOp`, with `data class Stamp(val botId: String, val path: String, val size: Long, val modified: Long)`.

- [ ] **Step 1: Write the failing tests:**
  - `historyKeepsTheLast20`: push 21 ops; `size == 20`, and the first one pushed is gone.
  - `undoOfEachOpPutsThingsBack`: one test per op kind. Do the op, then `undo` it, and the tree equals the starting tree (compare a sorted list of `path=content`).
  - `undoAcrossBotsReturnsTheFilesToTheirBot`: cut `a/player`, paste into `b/`, undo → `a/player` is back with the same content and `b/player` is absent.
  - `undoAfterTheFileChangedStops`: copy `x.py`, append to the copy, then `undo` throws the exact message, and the copy still exists unchanged.
  - `undoOfAReplacingPasteBringsTheOldFileBack`: ReplaceAll over `b/player/src/a.js`, then undo → the old content is back and the bin no longer holds it.
- [ ] **Step 2: Run** `--tests '*UndoTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the stamps in every op from Task 3 and `undo`, and `UndoHistory`.
- [ ] **Step 4: Run the whole suite.** Expected: PASS (Task 3's tests still pass).
- [ ] **Step 5: Commit** `feat: undo for file operations, across bots, refusing when files changed since`.

### Task 5: Text files for Notepad (`TextFiles`)

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/files/TextFiles.kt`
- Test: `app/src/test/java/com/pythonrunner/app/files/TextFilesTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface Opened { data class Text(val text: String, val charset: String, val crlf: Boolean, val modified: Long) : Opened; data class NotText(val kind: String, val size: Long) : Opened; data class TooBig(val size: Long) : Opened }`. `charset` is `"UTF-8"` or `"Shift_JIS"`.
  - `object TextFiles`:
    - `const val MAX_BYTES = 1_048_576`.
    - `fun open(file: File): Opened`. A UTF-8 BOM is accepted and remembered, by adding `val bom: Boolean = false` to `Text`. `text` always has `\n` line breaks.
    - `fun save(file: File, text: String, like: Opened.Text)` writes `like.charset` with CRLF when `like.crlf`, the BOM when `like.bom`, to a temp file and then renames it.
    - `fun position(text: String, cursor: Int): Pair<Int, Int>` gives the 1-based line and column.

- [ ] **Step 1: Write the failing tests:**
  - `utf8LfRoundTrips`: `"a\nb"` saved and reopened is the same, and the bytes have no `\r`.
  - `shiftJisCrlfIsKept`: write `"日本語\r\nx"` in MS932 bytes. `open` → `Text(text = "日本語\nx", charset = "Shift_JIS", crlf = true)`. Save `"日本語\nxy"` → the file bytes equal `"日本語\r\nxy"` encoded in MS932.
  - `bomIsKept`: a UTF-8 file with a BOM reopens with `bom == true`, and the saved bytes start with `EF BB BF`.
  - `binaryAndBigFilesAreNotOpened`: bytes with a 0 → `NotText`; 1_048_577 bytes of `a` → `TooBig`.
  - `positionCountsLinesAndColumns`: `position("ab\ncd", 4) == 2 to 2`.
- [ ] **Step 2: Run** `--tests '*TextFilesTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Try a strict UTF-8 decode (`CodingErrorAction.REPORT`), then MS932 (`BotImporter.shiftJis()`). If both fail → `NotText`.
  - CRLF when the first line break in the file is `\r\n`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: read and write text files keeping their charset and line breaks`.

### Task 6: Imports and 反映する go through the trash; import clashes use the Windows choices

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/files/BotImporter.kt` (`applyPlan` gains `onReplaced: (File, String) -> Unit = { _, _ -> }`, called at commit for every original moved aside, before `cleanup`; new `fun resolve(plan: ImportPlan, staging: File, botDir: File, resolution: Resolution): ImportPlan`; `plan` lists overwrites case-insensitively and counts same-content files as overwrites)
- Modify: `app/src/main/java/com/pythonrunner/app/files/FolderSync.kt` (`importChanges(changes, beforeChange: (File, String) -> Unit = { _, _ -> })`, called with the private file before it is overwritten or deleted)
- Modify: `app/src/main/java/com/pythonrunner/app/service/BotController.kt`, `.../ui/ImportUi.kt`, `.../ui/BotDialogs.kt`
- Test: `BotImporterTest.kt`, `FolderSyncTest.kt`

**Interfaces:**
- Consumes: Task 2 `Trash.put`, Task 3 `Resolution`/`Keep`.
- Produces:
  - `ImportState.Confirm(plan: ImportPlan, wholeBot: Boolean)`.
  - `BotController.confirmImport(context, resolution: Resolution, removeExtras: Boolean)`.
  - The conflict dialog composable `ReplaceOrSkipDialog(clashes: List<Clash>, folderName: String, onDone: (Resolution) -> Unit, onCancel: () -> Unit)` in the new `ui/ExplorerDialogs.kt`. Task 8 reuses it for pastes.

- [ ] **Step 1: Write the failing tests:**
  - `replacedOriginalsAreHandedOverAtCommit`: `applyPlan(..., onReplaced = { f, p -> got += p to f.readText() })` over an existing `a.py` → `got == listOf("a.py" to "old")`. Nothing is handed over when the move fails (rollback).
  - `resolveSkipAndBoth`: `resolve(plan, …, PerFile(mapOf("a.py" to Keep.EXISTING)))` drops `a.py` from `files`. `Keep.BOTH` renames the staged `a.py` to `a (2).py` and lists that instead.
  - `overwritesIgnoreCaseAndIdenticalContent`: the bot has `Main.py` = "x". Importing `main.py` = "x" lists `main.py` in `overwrites`.
  - In `FolderSyncTest`, `importChangesHandsOverWhatItReplacesOrDeletes`: a shared edit of `a.py` and a shared delete of `b.py` → `beforeChange` is called for `a.py` (old content) and `b.py`.
- [ ] **Step 2: Run** `--tests '*BotImporterTest' --tests '*FolderSyncTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the importer and sync changes. A case-only clash under ReplaceAll moves the existing differently-cased file aside like an overwrite.
- [ ] **Step 4: Wire the controller.**
  - `applyImport` passes `onReplaced = { f, p -> store.trash.put(f, p) }` (through a `Trash(store.trashDir)`).
  - The 反映する call in `BotDialogs` passes `beforeChange` the same way.
  - `Confirm` for a whole-bot replace shows the existing keep/delete screen; its 削除する goes through `remove`, which now also ends in `onReplaced`, so it lands in the trash.
  - `Confirm` for a folder or file import shows `ReplaceOrSkipDialog` (the 3 choices; 「ファイルごとに決める」 lists each clash with both sizes and dates, and two checkboxes for keeping the incoming and the existing file). Its result is passed to `confirmImport`, which calls `resolve` before `applyPlan`.
- [ ] **Step 5: Run all tests and `lintDebug`.** Expected: PASS.
- [ ] **Step 6: Commit** `feat: imports and 反映する keep what they replace in the trash; imports ask replace or skip like Windows`.

### Task 7: Shared folder follows moves; pending-edits guard

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/files/FolderSync.kt`
- Test: `FolderSyncTest.kt`

**Interfaces:**
- Produces:
  - `fun pendingUnder(paths: Collection<String>): List<String>` lists the shared-side changes (added, modified, removed) at or under any of `paths`, sorted.
  - `export()` additionally removes shared-side folders left empty, but only when every file the manifest knew under them is gone and the folder holds nothing else.

- [ ] **Step 1: Write the failing tests:**
  - `aRenamedFolderMovesInTheSharedCopy`: export `bot/x.py`, then rename the private `bot` to `core` and export → shared has `core/x.py`, and the `bot` folder no longer exists.
  - `aFolderWithAnUnknownFileIsKept`: the shared `bot/` also holds `notes.txt`, which the manifest never knew → after the rename and export, `bot/notes.txt` remains.
  - `pendingUnderFindsEditsInsideAFolder`: an edit of shared `bot/x.py` → `pendingUnder(listOf("bot")) == listOf("bot/x.py")`, and `pendingUnder(listOf("player"))` is empty.
- [ ] **Step 2: Run** `--tests '*FolderSyncTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: shared folder follows moves and drops emptied folders; pending-edit lookup`.

### Task 8: Controller wiring and the Explorer screen

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/service/BotController.kt`
- Rewrite: `app/src/main/java/com/pythonrunner/app/ui/Explorer.kt`
- Create: `app/src/main/java/com/pythonrunner/app/ui/ExplorerDialogs.kt` (rename, new, properties, progress, and the shared `ReplaceOrSkipDialog` from Task 6), `app/src/main/java/com/pythonrunner/app/ui/TrashView.kt`
- Modify: `app/src/main/java/com/pythonrunner/app/ui/tabs/FilesTab.kt`

**Interfaces:**
- Consumes: Tasks 1–7.
- Produces, in `BotController`:
  - `fun trash(context, botId): Trash`
  - `fun fileOps(context): FileOps`
  - `fun undoHistory(botId): UndoHistory`
  - `data class Clip(val items: List<Item>, val cut: Boolean)` and `val clipboard: MutableStateFlow<Clip?>`
  - `val fileBusy: StateFlow<Boolean>`
  - `fun runFileOp(context, botId: String, touched: List<Item>, op: () -> FileOp, onDone: (Result<FileOp>) -> Unit)`. It runs on `Dispatchers.IO`. Before the op it checks `pendingUnder` for every touched bot: if there are pending changes, it calls `onDone(Result.failure(PendingChanges(list)))` and changes nothing. After the op it pushes to the undo history of the op's destination bot. It remaps `mainFile` and `partsInstalled` keys with `FileOps.remap` for `Moved` ops. It exports every touched bot's sync, and calls `recheck`. If the bot is running, it sets `filesChangedWhileRunning[botId] = true` (a `MutableStateFlow<Set<String>>`), which FilesTab turns into the existing restart question, asked once per visit.
  - `class PendingChanges(val paths: List<String>) : Exception()`.
  - `requireSpace` adds 「（ごみ箱を空にすると n MB 空きます）」 to its message when the trashes of all bots hold at least 1 MB (spec 5.4).
- Explorer UI (spec sections 3–5), rewritten as `Explorer(botId: String, rootLabel: String, mainFile: String?, refreshKey: Any, enabled: Boolean, onOpenFile: (String) -> Unit, onSetMain: (String) -> Unit, onAskRestart: () -> Unit)`. It owns path, back/forward history, selection, sort and the snackbar.
  - Selection:
    - A tap on an unselected row selects only it.
    - A tap on the selected single row opens it: a folder goes into it, a file calls `onOpenFile`.
    - A tap on the □ toggles that row.
    - A tap on empty space clears the selection.
  - Long press selects the row if it is unselected, then shows the menu from spec 4.3, with the parts rules.
  - The toolbar is as in spec 4.1, with overflow into `⋯`. Its items are disabled when `!enabled` or `fileBusy`, or when there is no selection or no clipboard.
  - The status bar reads 「n 個の項目　m 個の項目を選択」.
  - Sorting: tap a column header to sort by it, and tap again to reverse.
  - After a delete, a snackbar reads 「n 個の項目をごみ箱に移動しました」 with 「元に戻す」, for about 4 s.
  - A `PendingChanges` failure shows a dialog: 「この中に、PC などで変えて、まだ反映していないファイルがあります」, the list, 「先に反映する」 (opens the existing review dialog via `rememberBotActions`) and 「キャンセル」.
  - Paste: call `clashes`. If there are any, show `ReplaceOrSkipDialog`, then `runFileOp { paste(...) }` with a progress dialog (「n 個中 m 個」 and 「キャンセル」).
  - 名前の変更: an inline dialog with the base name preselected. On confirm, show the extension warning when `extensionChanged`.
  - 新規: a menu with フォルダー, テキスト ドキュメント and Python ファイル; create, then open the rename dialog on the new item.
  - プロパティ: a dialog with the fields from spec 5.6, counted with `produceState` on IO.
  - The left column holds 「<Bot名>」 and 「ごみ箱 (n)」. Choosing ごみ箱 shows `TrashView`, which lists name, original place, date and size, with 元に戻す (restore with the clash dialog), 完全に削除 and ごみ箱を空にする (both confirmed with 「完全に削除しますか？元に戻せません」).
  - Rows on the clipboard as a cut are drawn faded until pasted. A paste empties the clipboard after a cut, but not after a copy.
  - Remove the old long-press-to-delete and the 「削除するには Bot を止めてください」 rule.
- FilesTab: keep the three import buttons above the Explorer (spec 4.1). Pass `enabled = !workBusy && importingBotId() != botId`.

- [ ] **Step 1: Implement the controller members** above.
- [ ] **Step 2: Build and run all unit tests and `lintDebug`.** Expected: PASS.
- [ ] **Step 3: Rewrite `Explorer` and add `ExplorerDialogs` and `TrashView`; update `FilesTab`.**
- [ ] **Step 4: Build `assembleDebug`, run all unit tests and `lintDebug`.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: PC-like files screen with selection, right-click menu, toolbar, trash and undo`.

### Task 9: Notepad

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/ui/NotepadScreen.kt`
- Modify: `app/src/main/java/com/pythonrunner/app/ui/BotDetailScreen.kt` (`var editing by rememberSaveable { mutableStateOf<String?>(null) }`; when it is set, show the Notepad instead of the tabs; `BackHandler` routes back to the Notepad's close)
- Modify: `app/src/main/java/com/pythonrunner/app/ui/tabs/SettingsTab.kt` (`.env` 「表示して編集」 sets `editing = ".env"`, or `作る` writes an empty `.env` first; remove the inline editor)
- Modify: `app/src/main/java/com/pythonrunner/app/service/BotController.kt` (`fun saveText(context, botId, path, text, like: Opened.Text)` saves on IO, exports the sync and marks `filesChangedWhileRunning`)

**Interfaces:**
- Consumes: Task 5 `TextFiles`, Task 8 `filesChangedWhileRunning`, `PendingChanges`.
- Produces: `@Composable fun NotepadScreen(botId: String, path: String, running: Boolean, onClose: () -> Unit)`.

- [ ] **Step 1: Implement `NotepadScreen`.**
  - Open with `TextFiles.open` on IO.
    - `NotText` shows 「このファイルは文字のファイルではないので、開けません」 with the kind and size.
    - `TooBig` shows 「大きすぎて開けません（1MB まで）」.
    - Before opening, run the pending-edits check for that path (as in Task 8).
  - The title bar shows `*` before the name when changed, plus 保存 and 閉じる.
  - The text field is monospace, fills the screen and wraps.
  - The status bar shows 「行 L, 列 C」, the charset, and 「CRLF」 or 「LF」.
  - Closing with changes shows 「〇〇への変更内容を保存しますか？」 with 保存する, 保存しない and キャンセル.
  - After a save, when `running`: 「Bot を再起動すると、この変更が使われます」 with 「今すぐ再起動」, which calls `BotController.restart`.
  - A failed save keeps the text and shows 「保存できませんでした」.
- [ ] **Step 2: Wire `BotDetailScreen` and `SettingsTab`.** Explorer's `onOpenFile` sets `editing`.
- [ ] **Step 3: Build `assembleDebug`, run all unit tests and `lintDebug`.** Expected: PASS.
- [ ] **Step 4: Commit** `feat: Notepad for text files, also used for .env`.

### Task 10: README

**Files:**
- Modify: `README.md` (the ファイル section)

- [ ] **Step 1: Rewrite the ファイル tab description.** It covers:
  - Tap to select, tap again to open; long press = 右クリック; the □ for several.
  - The toolbar.
  - ごみ箱 (戻せる, 空にするまで残る).
  - 元に戻す (20 回まで、アプリを閉じるまで).
  - The replace/skip choices.
  - Notepad (1MB まで, 文字コードと改行はそのまま).
  - node_modules・.venv はコピーされない.
  - The pending-changes rule.
- [ ] **Step 2: Commit** `docs: README for the PC-like files screen`.
