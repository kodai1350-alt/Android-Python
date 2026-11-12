# Files Screen, .env and Library Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Folders can be deleted with a long press, an imported folder lands where it belongs (or replaces the whole bot), tokens live in the bot's `.env`, bot deletion always removes the folder, and the library tab shows what is installed.

**Architecture:** Pure logic goes in small JVM-tested objects (`DotEnv`, `EnvMigration`, `BotImporter` extensions, `InstalledParts`). `BotController` wires them, and the Compose screens (`FilesTab`/`Explorer`, `LibraryTab`, `SettingsTab`, `BotDialogs`, `ImportUi`) show them. The private-copy-plus-shared-folder mechanism (FolderSync) stays as it is.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), kotlin.test/JUnit on the JVM (no Android classes in unit tests, no JSON library: `org.json` is not usable in JVM tests).

**Spec:** `docs/superpowers/specs/2026-11-12-files-env-libraries-design.md`

## Global Constraints

- Branch `spike/node`; do not merge to `main`.
- Never read or commit the user's real `.env` files; tests write their own temp `.env`.
- Only a file named exactly `.env` syncs to the shared folder. `.env.*` variants, `*.key`, `*.session`, `*.db`, `*.sqlite*`, `*.pem`, `*.p12`, `*.pfx`, and names containing secret/token/credential/password stay app-only.
- `.env` values never appear in logs or on screen unless the user presses 表示して編集.
- The app's own variables (`PYTHONHOME`, `LD_LIBRARY_PATH`, `PYTHONPATH`, `PATH`, `SSL_CERT_FILE`, `TMPDIR`, `HOME`, `NODE_EXTRA_CA_CERTS`, `*_NO_EXTENSIONS`) win over `.env` values of the same name. This is today's order: user env first, then the app's own.
- pip and npm never get `.env` values (as in the spike).
- `node_modules` and `.venv` are never deleted by a long press and are never listed as extras.
- UI copy is Japanese, worded as in the spec. Tests: `./gradlew -PnodeBundlePython=python testDebugUnitTest` (all must pass; 162 before this plan).

## Review Focus

1. `.env` with CRLF line endings, a BOM, `export KEY=…`, quoted values containing `#` or `=`, and blank values → parsed like python-dotenv (no `\r` in values, BOM ignored, `#` inside quotes kept). Tests in Task 1.
2. Migration running twice, or after the user already edited `.env` → never duplicates or overwrites a key, and keeps `envVars` until `.env` is written. Tests in Task 2.
3. Replace-bot whose staged tree lacks `data/secure.key` and `data/sounds.dat` → `secure.key` is never listed or removed. `sounds.dat` is listed as an extra and kept unless 消す is chosen. Tests in Task 4.
4. Folder import into a folder whose name already exists as a *file* (or vice versa) → refused with the existing 「同じ名前のフォルダがあるため取り込めません」 kind of message, nothing half-applied. Test in Task 4.
5. Long-pressing a folder while the bot runs, an import runs, or parts are being installed → no delete, the message 「削除するには Bot を止めてください」. Covered by Task 6's `canDelete` wiring and checked by hand on the phone.

---

### Task 1: `.env` parsing and the sync rule

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/bot/DotEnv.kt`
- Modify: `app/src/main/java/com/pythonrunner/app/files/FolderSync.kt` (`isSecret`)
- Modify: `app/src/main/java/com/pythonrunner/app/files/SharedFolders.kt` (`adoptableFiles` keeps `.env` via `isSecret`)
- Test: `app/src/test/java/com/pythonrunner/app/bot/DotEnvTest.kt`, `.../files/FolderSyncTest.kt`, `.../files/SharedFoldersTest.kt`

**Interfaces:**
- Produces: `object DotEnv { const val FILE = ".env"; fun parse(text: String): Parsed; fun withMissing(text: String, add: Map<String, String>): String; fun names(text: String): List<String> }` and `data class Parsed(val values: Map<String, String>, val badLines: List<Int>)` (1-based line numbers).
- Produces: `FolderSync.isSecret(".env") == false`; everything else unchanged.

- [ ] **Step 1: Write the failing tests** in `DotEnvTest`:
  - `parsesTheCommonForms`: `"﻿A=1\r\n# c\r\nexport B = two \nC=\"x # y\"\nD='q=r'\nE=\nF=v # note\n"` gives `A→"1"`, `B→"two"`, `C→"x # y"`, `D→"q=r"`, `E→""`, `F→"v"`, and `badLines` is empty.
  - `reportsLinesItCannotRead`: `"OK=1\nnot a pair\n=x\n"` gives `values == mapOf("OK" to "1")` and `badLines == listOf(2, 3)`.
  - `laterLinesWin`: `"A=1\nA=2"` gives `A→"2"`.
  - `withMissingAppendsOnlyNewNames`: `withMissing("A=1\n", mapOf("A" to "9", "B" to "x y", "C" to "a\"b"))` equals `"A=1\nB=\"x y\"\nC=\"a\\\"b\"\n"`. Values that need no quoting stay bare. A missing final newline is added before appending.
  - `namesListsKeysInOrder`: `names("B=1\nA=2\n# x\n")` equals `listOf("B", "A")`.
  - In `FolderSyncTest`, add `envSyncsButOtherSecretsStayPrivate`: write `.env`, `.env.local`, `data/secure.key` privately → `export()` → only `.env` exists in shared.
  - In `SharedFoldersTest`, add `adoptingBringsTheEnvFile`: a folder with `.env` and `x.session` → `adoptableFiles` contains `.env`, not `x.session`.
- [ ] **Step 2: Run them** with `./gradlew -PnodeBundlePython=python testDebugUnitTest --tests '*DotEnvTest' --tests '*FolderSyncTest' --tests '*SharedFoldersTest'`. Expected: FAIL (DotEnv unresolved; `.env` not exported).
- [ ] **Step 3: Implement `DotEnv`.** Rules: strip a leading BOM. Split on `\n` and drop a trailing `\r`. Skip blank lines and lines starting with `#`. Drop a leading `export `. The key is the trimmed text before the first `=` and must match `[A-Za-z_][A-Za-z0-9_.-]*`, otherwise the line is bad. For the value: if trimmed and starting with `"` or `'`, take the text up to the matching closing quote and unescape `\"` and `\\` in double quotes. Otherwise cut at ` #` and trim. `withMissing` quotes a value with `"…"` (escaping `\` and `"`) when it contains whitespace, `#`, `"`, `'` or `=`.
- [ ] **Step 4: Change `FolderSync.isSecret`** so the exact name `.env` returns false; keep `.env.*` and the rest. Add the comment from the spec ("the user chose to share .env").
- [ ] **Step 5: Run the tests** (same command). Expected: PASS, including the existing `FolderSyncTest` cases.
- [ ] **Step 6: Commit** `feat: .env parser; .env syncs to the shared folder like other files`.

### Task 2: Secret values become `.env` (migration and start-up)

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/bot/EnvMigration.kt`
- Modify: `app/src/main/java/com/pythonrunner/app/service/BotController.kt` (`ensureReady` runs the migration per bot; new `fun botEnv(context: Context, id: String, onBadLine: (Int) -> Unit): Map<String, String>`)
- Modify: `app/src/main/java/com/pythonrunner/app/service/BotService.kt` (use `botEnv` instead of `store.envVars`; a bad line logs 「.env の n 行目を読めませんでした」, no value)
- Test: `app/src/test/java/com/pythonrunner/app/bot/EnvMigrationTest.kt`

**Interfaces:**
- Consumes: `DotEnv.parse`, `DotEnv.withMissing`, `BotStore.envVars`, `BotStore.codeDir`.
- Produces: `object EnvMigration { fun migrate(store: BotStore, sharedDir: File?): Boolean }`. It returns true when it changed `code/.env`. The caller then calls `sync(context, id)?.exportForced(listOf(".env"))` so the merged file wins over the shared copy and is recorded as synced.

- [ ] **Step 1: Write the failing tests** in `EnvMigrationTest` (temp dirs, `BotStore` with a map-backed `KeyValue` as in `BotStoreTest`):
  - `secretsBecomeEnvFile`: `envVars = {DISCORD_TOKEN: "abc"}`, no `.env` anywhere → `migrate` true, `code/.env` parses to `{DISCORD_TOKEN: "abc"}`, `envVars` empty.
  - `sharedEnvIsTakenFirst`: shared `.env` has `A=1`, private none, `envVars = {B: "2"}` → private `.env` parses to `{A: "1", B: "2"}`.
  - `privateValueWinsAndSharedOnlyNamesAreAdded`: private `A=1`, shared `A=9\nC=3` → private parses to `{A: "1", C: "3"}`.
  - `secondRunChangesNothing`: run twice → the second returns false and the file text is identical.
  - `envVarsKeptWhenWritingFails`: make `codeDir` a regular file so writing fails → `migrate` throws, `envVars` unchanged.
- [ ] **Step 2: Run** `--tests '*EnvMigrationTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `migrate`.** Read private text (or `""`). If the private file is missing and the shared one exists, start from the shared text. Otherwise add the names only the shared one has (`withMissing`). Then add `envVars` (`withMissing`). Write only when the text changed: tmp file, then rename. Clear `envVars` only after the write, and also when it was already empty of new names.
- [ ] **Step 4: Run the test**. Expected: PASS.
- [ ] **Step 5: Wire it.**
  - `ensureReady` calls `migrate` for every registry entry (catching per bot, so one failure does not stop the others), then `exportForced(listOf(".env"))` when it returns true.
  - `botEnv` parses `code/.env`.
  - `BotService.createProcess` passes `BotController.botEnv(this, id) { n -> BotController.appendLog(id, LogLine(now, ".env の ${n} 行目を読めませんでした", true)) }` where `store.envVars` was.
- [ ] **Step 6: Run all unit tests**. Expected: PASS.
- [ ] **Step 7: Commit** `feat: secret values move into the bot's .env, which the app reads at start`.

### Task 3: Deleting a bot always deletes its folder

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/service/BotController.kt` (`deleteBot(context: Context, id: String)`, no `deleteFolder` parameter. Without storage access it returns the folder label that was left behind: `fun deleteBot(context: Context, id: String): String?`)
- Modify: `app/src/main/java/com/pythonrunner/app/ui/BotDialogs.kt` (`DeleteBotDialog(name: String, onDelete: () -> Unit, onCancel: () -> Unit)`)
- Modify: `app/src/main/java/com/pythonrunner/app/ui/tabs/SettingsTab.kt` (call site; remove the 「一覧から外すだけ」 help text)

**Interfaces:**
- Produces: `BotController.deleteBot(context, id): String?`, where non-null is 「<folderLabel> は残りました（ストレージの許可がないため）」.

- [ ] **Step 1: Change `deleteBot`** to always `folder.deleteRecursively()` when access is granted, after `sync.forget()` and `store.deleteAll()` as now. Keep the existing order (registry first, stop, forget, delete).
- [ ] **Step 2: Rewrite `DeleteBotDialog`.** No radio buttons. Title 「『<name>』を削除しますか？」. Body 「動いていれば止めます。ファイル・ライブラリ・設定と、共有フォルダ（<folderLabel(name)>）も消えます。元に戻せません。」 in red. Buttons 「やめる」 and 「削除する」 (red).
- [ ] **Step 3: Update `SettingsTab`.** Delete the 「一覧から外すだけ」 help. If `deleteBot` returns a message, show it as a toast before leaving.
- [ ] **Step 4: Build and run all unit tests**. Expected: PASS (`forgetKeepsSharedFilesWhileTheBotsFilesAreDeleted` still passes).
- [ ] **Step 5: Commit** `feat: deleting a bot always deletes its shared folder too`.

### Task 4: Importer — destination folder, extras, and removals

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/files/BotImporter.kt`
- Test: `app/src/test/java/com/pythonrunner/app/files/BotImporterTest.kt`

**Interfaces:**
- Produces:
  - `data class ImportPlan(val files: List<String>, val overwrites: List<String>, val bytes: Long, val extras: List<String> = emptyList())`. `extras` are existing files under the scope that the import does not bring, sorted.
  - `fun plan(staging: File, botDir: File, scope: String? = null): ImportPlan`. `scope == null` means no extras. `""` means the whole bot. `"player"` means only under `player/`.
  - Extras never include `isSkipped` paths (`node_modules`, `.venv`, …), nor app-only names (`FolderSync.isSecret(name)`, which `.env` no longer is).
  - `fun applyPlan(plan, staging, botDir, backup, remove: List<String> = emptyList(), move = ::defaultMove, cleanup = …)`. `remove` paths (a subset of `plan.extras`) are moved into the backup as `E` lines without a replacement, so a rollback puts them back and a commit drops them.
  - `fun stageTree(children, staging, onProgress, prefix: String = "")` and `fun stageZip(zip, staging, onProgress, prefix: String = "")` and `fun stageFile(name, input, staging, prefix: String = "")`. Each places files under `prefix/` inside staging. `prefix` is a safe relative path, `""` for the top.
  - `fun requirements(staging: File, prefix: String = ""): String?` reads `<prefix>/requirements.txt`.

- [ ] **Step 1: Write the failing tests** in `BotImporterTest`:
  - `folderLandsUnderItsNameInTheOpenFolder`: `stageTree(listOf("src/a.js" to …, "package.json" to …), staging, prefix = "player")`, then `plan(staging, bot, scope = "player")` has `files == ["player/package.json", "player/src/a.js"]`.
  - `extrasAreTheOldFilesTheNewFolderLacks`: bot has `player/old.js`, `player/node_modules/x/i.js`, `bot/x.py`. The import of `player` has `package.json`. Then `extras == ["player/old.js"]`.
  - `replacingTheWholeBotKeepsKeysAndPartsOutOfExtras`: bot has `data/secure.key`, `data/sounds.dat`, `bot/.venv/bin/python`, `.env`, `Start.py`. The import has `Start.py`, `.env`. With `scope = ""`, `extras == ["data/sounds.dat"]`.
  - `removedExtrasGoAndComeBackOnRollback`: `applyPlan(…, remove = ["player/old.js"])` deletes it. A second run whose `move` throws on the last file restores `player/old.js` and leaves nothing new.
  - `zipAndFileGoIntoThePrefix`: `stageFile("a.py", …, prefix = "bot")` puts the file at `bot/a.py`. `stageZip` with a single top folder and `prefix = "bot"` puts its files at `bot/<inner>`.
  - `folderOverAFileOfTheSameNameIsRefused`: bot has a file `player`; importing into prefix `player` makes `applyPlan` throw `IOException` and the bot is unchanged.
- [ ] **Step 2: Run** `--tests '*BotImporterTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** `extras` walks `File(botDir, scope)` with the same skip rule as `FolderSync.files`. Removals use the existing marker format (`E\t<path>`); `restoreFromMarker` already moves `files/<path>` back.
- [ ] **Step 4: Run the test** (and the whole `BotImporterTest`). Expected: PASS.
- [ ] **Step 5: Commit** `feat: importer can place a folder under its own name, list extras and remove them reversibly`.

### Task 5: Import modes in the controller and the confirm dialog

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/service/BotController.kt`
- Modify: `app/src/main/java/com/pythonrunner/app/ui/AppNav.kt`, `.../ui/HomeScreen.kt` (`ImportActions`), `.../ui/ImportUi.kt` (Confirm dialog)

**Interfaces:**
- Consumes: Task 4's `prefix`, `scope`, `extras`, `remove`.
- Produces:
  - `class ImportActions(val pickFile: (botId: String?, into: String) -> Unit, val pickFolder: (botId: String?, into: String) -> Unit, val replaceBot: (botId: String, zip: Boolean) -> Unit)`.
  - `BotController.importFile(context, uri, botId: String?, into: String = "")`: a ZIP or file goes under `into`.
  - `BotController.importFolder(context, treeUri, botId: String?, into: String = "")`: for an existing bot, the folder goes to `into/<folder name>` with `scope = that path`. For a new bot (`botId == null`), the contents go at the top as today.
  - `BotController.replaceBot(context, uri, botId: String, zip: Boolean)`: contents at the top, `scope = ""`.
  - `ImportState.Confirm(plan: ImportPlan)` shows when `overwrites` or `extras` is non-empty.
  - `BotController.confirmImport(context, removeExtras: Boolean)` passes `remove = if (removeExtras) plan.extras else emptyList()`. After applying, `exportForced(plan.files)` runs; for removed paths, `export()` drops the shared copies that were not edited there. `forgetMissingMain()` runs.
  - `Done.requirements` reads `requirements(staging, prefix)`.
- The folder name for `into/<name>` comes from `SafTree.displayName` and is checked with `BotImporter.safePath`. An unsafe name fails with 「このフォルダの名前は使えません」.

- [ ] **Step 1: Implement the three entry points** on top of `startImport`, passing `prefix`/`scope` into the stage lambda and `BotImporter.plan`.
- [ ] **Step 2: Rewrite the Confirm dialog in `ImportUi`.**
  - Title 「取り込む前に確かめてください」.
  - Section 「置き換わるもの」 lists overwrites. 「増えるもの」 shows a count.
  - When `extras` is non-empty, add 「新しいフォルダにないもの（n 個）」 with the list and two radio rows, 「残す」 (selected first) and 「消す」.
  - A note: 「アプリが入れた部品（node_modules・.venv）と、アプリの中だけのファイル（secure.key など）は、いつも残ります。」
  - Buttons 「取り込む」 / 「やめる」. `onConfirm: (removeExtras: Boolean) -> Unit`.
- [ ] **Step 3: Wire `AppNav`.** Keep `pickTarget` and add `pickInto: String` (rememberSaveable) and `pickMode` (`"file"`, `"folder"`, `"replace-folder"`, `"replace-zip"`). The `OpenDocumentTree` launcher calls `importFolder` or `replaceBot` by mode. The `OpenDocument` launcher calls `importFile` or `replaceBot(zip = true)`. `HomeScreen` call sites pass `""` for `into`.
- [ ] **Step 4: Build and run all unit tests**. Expected: PASS.
- [ ] **Step 5: Commit** `feat: import into the open folder, or replace the whole bot with a check of what the new folder lacks`.

### Task 6: One-place file screen with long-press delete

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/ui/Explorer.kt`, `.../ui/tabs/FilesTab.kt`
- Modify: `app/src/main/java/com/pythonrunner/app/files/FileTree.kt` (`fun fileCount(dir: File): Int`, counting files at any depth; `fun isInstalledPart(name: String): Boolean = name == "node_modules" || name == ".venv"`)
- Test: `app/src/test/java/com/pythonrunner/app/files/FileTreeTest.kt`

**Interfaces:**
- Consumes: Task 5's `ImportActions`.
- Produces: `Explorer(root: File, rootLabel: String, path: String, onPathChange: (String) -> Unit, mainFile: String?, refreshKey: Any, onSetMain: (String) -> Unit, onDelete: ((String) -> Unit)?, deleteBlockedMessage: String)`. `Place`, `PlaceGroup` and `NavPane` are removed.

- [ ] **Step 1: Write the failing tests** in `FileTreeTest`: `fileCountCountsNestedFiles` (`src/a`, `src/b/c` → 2 for `src`), and `installedPartsAreRecognised` (`node_modules` and `.venv` true, `venv` false).
- [ ] **Step 2: Run** `--tests '*FileTreeTest'`. Expected: FAIL. Implement both. Run again: PASS.
- [ ] **Step 3: Rewrite `Explorer` as the single-place view from 見本 1.**
  - Breadcrumb 「<Bot名> › player」 with 「⬆ 上へ」.
  - Rows as now. Each row has `combinedClickable(onClick = …, onLongClick = { confirmDelete = entry })`.
  - Installed parts are drawn in `Muted` with the subtitle 「アプリが入れた部品」, and a long press does nothing.
  - `.env` gets 🔒 and 「トークンなど」. Other app-only files keep 「🔒 アプリの中だけ」.
  - The delete dialog for a folder: 「フォルダ『<name>』を中身ごと削除しますか？」 and 「中の <fileCount> 個のファイルも消えます。共有フォルダからも消えます。」. For a file: 「『<name>』を削除しますか？」 and 「共有フォルダからも消えます。」. Buttons 「やめる」 / 「削除する」 (red).
  - When `onDelete == null`, a long press shows `deleteBlockedMessage` as a toast instead.
  - The tap menu keeps 「これを最初に実行」 and gains 「🗑 削除」 for files.
- [ ] **Step 4: Rewrite `FilesTab`.**
  - Keep `path` state (rememberSaveable keyed by botId).
  - Buttons 「フォルダを取り込む」 and 「ファイルを取り込む」 call `imports.pickFolder(botId, path)` and `imports.pickFile(botId, path)`. 「Botをまるごと入れ替える」 opens a menu: 「フォルダを選ぶ」 calls `replaceBot(botId, false)`, 「ZIPを選ぶ」 calls `replaceBot(botId, true)`.
  - The footer reads 「場所：ドキュメント › PythonRunner › <Bot名>（PC やファイルアプリからも同じ中身が見えます）」.
  - `onDelete` is null while running, importing (`importingBotId() == botId`) or `workBusy`. `deleteBlockedMessage = "削除するには Bot を止めてください"`.
  - Delete calls `store.deleteFile` then `sync.export()`, as now.
  - Remove the Documents/Download/内部ストレージ/ライブラリ/Python本体 places and the old help text about 2 places. Replace it with one help line: 「ここに見えるファイルが Bot です。PC で直したフォルダは『フォルダを取り込む』か『Botをまるごと入れ替える』で入れます。」
- [ ] **Step 5: Build, run all unit tests, and install the debug APK on the phone** (`./gradlew -PnodeBundlePython=python assembleDebug`). Check by hand that a long press on a folder shows the count, deletes it, and that the shared folder loses it too.
- [ ] **Step 6: Commit** `feat: the files tab shows the bot in one place; long press deletes files and folders`.

### Task 7: Parts status, automatic stop/restart, and the library tab

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/python/InstalledParts.kt`
- Modify: `app/src/main/java/com/pythonrunner/app/bot/BotStore.kt` (`var partsInstalled: Map<String, Long>`, stored under `bot.$id.parts` with `encodeVars` and decimal millis; add the key to `keys(id)`)
- Modify: `app/src/main/java/com/pythonrunner/app/service/BotController.kt` (`runBotParts`)
- Modify: `app/src/main/java/com/pythonrunner/app/ui/tabs/LibraryTab.kt`
- Test: `app/src/test/java/com/pythonrunner/app/python/InstalledPartsTest.kt`, `.../bot/BotStoreTest.kt`

**Interfaces:**
- Consumes: `BotParts.requirementFiles`, `BotParts.nodeProjects`, `BotParts.venvPython`.
- Produces:
  - `data class Lib(val name: String, val version: String)`.
  - `enum class PartState { OK, MISSING, STALE }`.
  - `data class Part(val key: String, val label: String, val state: PartState, val installedAt: Long?)`. `key` is the relative path of `requirements.txt` or of the package folder; `label` is the folder ("いちばん上" for the top).
  - `object InstalledParts`:
    - `fun pythonLibs(libsDir: File): List<Lib>` reads `*.dist-info/METADATA` `Name:`/`Version:`, sorted by lowercased name.
    - `fun nodeLibs(project: File): List<Lib>` takes the `"dependencies"` keys of `package.json` and each `node_modules/<name>/package.json` `"version"`, using regex, not JSON; a missing module gets version `"?"`.
    - `fun parts(codeDir: File, installed: Map<String, Long>): List<Part>`.
    - `fun find(libs: List<Lib>, name: String): Lib?` compares PEP 503 normalized names (`[-_.]+` → `-`, lowercase).

- [ ] **Step 1: Write the failing tests** in `InstalledPartsTest`:
  - `readsPythonLibsFromDistInfo`: two `METADATA` files → `[Lib("aiohttp","3.13.2"), Lib("discord.py","2.6.4")]`.
  - `readsNodeDepsAndVersions`: `package.json` `{"name":"p","dependencies":{"discord.js":"^14.28.1","@discordjs/voice":"^0.19.2"}}` plus a `node_modules/discord.js/package.json` with `"version": "14.28.1"` → `[Lib("@discordjs/voice","?"), Lib("discord.js","14.28.1")]`.
  - `stateIsOkMissingOrStale`:
    - `bot/requirements.txt` with the `.venv/bin/python` file present and `installed["bot/requirements.txt"]` later than the file's `lastModified` → OK.
    - The same without `.venv` → MISSING.
    - With `requirements.txt` touched after the installed time → STALE.
    - No record → MISSING.
    - `player` with `node_modules` → OK.
  - `findIgnoresSpelling`: `find(libs, "Discord_Py")` returns the discord.py entry.
  - In `BotStoreTest`: `partsInstalledRoundTripsAndIsDeletedWithTheBot`.
- [ ] **Step 2: Run** `--tests '*InstalledPartsTest' --tests '*BotStoreTest'`. Expected: FAIL. Implement. Run again: PASS.
- [ ] **Step 3: Change `runBotParts(context, botId, onLine, onExit)`.**
  - If the bot is running or starting, log 「Botを止めます」, call `stop(context, botId)` and wait until it is stopped (up to 10 s).
  - After each successful step, record `partsInstalled[key] = System.currentTimeMillis()`.
  - At the end, if it was running before, log 「Botを起動し直します」 and call `start(context, botId)`. That happens even after a failure: the bot goes back to how it was.
  - Remove the 「先にBotを止めてください」 refusal.
- [ ] **Step 4: Rewrite `LibraryTab` as 見本 2.** Card 「入っているもの」 on top, shown only when `InstalledParts.parts` is non-empty. Compute off the main thread (`produceState` + `Dispatchers.IO`, keyed by botId, `busy`, and a refresh counter).
  - Python block 「Python のライブラリ」: one line per `requirements.txt` part, with its state and date. Under them, `pythonLibs` as 「name version・…」: the first 3, then 「…ほか n 個」, which expands on tap.
  - Node block: one header per package folder 「Node.js の部品（player）」 with state and date, then that project's `nodeLibs` in the same 3 + 「ほか n 個」 form.
  - State text:
    - OK: 「✓ 11/12 入れました」 in green (`M/d`).
    - MISSING: 「✗ まだ入っていません」 in red.
    - STALE: 「! 入れ直してください（ファイルが変わりました）」 in orange.
  - Button: 「部品を入れる」 when any part is not OK, else 「部品を入れ直す」. The note under it: 「ファイルを入れ替えたときや、動かないときに押します。Bot は自動で止めて、終わったら起動し直します。」
  - Below, 「ほかのライブラリを足す」 is today's pip box. Under the input, for each parsed package `find` gives 「<name> <version> は入っています（押すと最新にします）」 or 「<name> はまだ入っていません」.
- [ ] **Step 5: Build, run all unit tests, and check on the phone** with the 入退室音 bot: the states show ✓ after installing, the bot restarts by itself.
- [ ] **Step 6: Commit** `feat: library tab shows installed parts and versions; installing parts stops and restarts the bot itself`.

### Task 8: `.env` section in the settings tab, README

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/ui/tabs/SettingsTab.kt` (replace `SecretsSection` with `EnvSection`)
- Delete: `app/src/main/java/com/pythonrunner/app/bot/SecretRows.kt` and `app/src/test/java/com/pythonrunner/app/bot/SecretRowsTest.kt`, if nothing else uses them (check with grep).
- Modify: `README.md` (the 秘密の値, 削除 and ファイル sections)

**Interfaces:**
- Consumes: `DotEnv.names`, `BotController.store(...).codeDir`, `BotController.sync(...)`.
- Produces: `BotController.saveEnv(context: Context, id: String, text: String)`. It writes `code/.env` (tmp + rename) and runs `sync?.exportForced(listOf(".env"))`.

- [ ] **Step 1: Write `EnvSection`.** Section 「.env（トークンなど）」.
  - Without `.env`: 「まだありません」 and 「作る」, which opens the editor with an empty text.
  - With `.env`: each name from `DotEnv.names` as 「DISCORD_TOKEN ●●●●」, and the button 「表示して編集」, which shows a multi-line `OutlinedTextField` (monospace, 8 lines) with 「保存」 / 「やめる」.
  - After saving: 「✓ 保存しました（次の起動から使われます）」.
  - Help text: 「Bot のフォルダの .env に書いた値は、Bot の中で os.environ["名前"] で読めます。.env は共有フォルダにも置かれるので、PC で直しても構いません。」
- [ ] **Step 2: Remove `SecretsSection`**, and `SecretRows` if unused. Search for `envVars` in `ui/`; there should be none left.
- [ ] **Step 3: Update `README.md`.**
  - Tokens go in `.env` (設定タブ or the file itself).
  - Bot deletion removes the folder.
  - The files tab: long press, フォルダを取り込む into the open folder, Botをまるごと入れ替える.
  - The library tab's 入っているもの.
  - The note that `.env` is visible to apps with storage permission.
- [ ] **Step 4: Build and run all unit tests**. Expected: PASS.
- [ ] **Step 5: Commit** `feat: settings tab edits the bot's .env; README follows the new screens`.
