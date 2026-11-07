# フォルダ・ZIPの取り込みと自動反映 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 「📥 取り込む」1つで .py・ZIP・その他のファイル・フォルダをBotフォルダに取り込めるようにし、共有コピーの変更は「🔄 更新」を押さなくても自動で気づいて確認を出す。

**Architecture:**
- 取り込みは3段階に分ける。
  1. 何でも一時フォルダ（staging）に置く。
  2. 計画（上書きされるもの・大きさ）を作る。
  3. 退避（backup）付きでBotフォルダへ移す。
- 1〜3 はAndroidに依存しない `files/BotImporter.kt` に置き、JVMの単体テストで確かめる。
- Androidに依存する部分は `BotController`（アプリ全体のコルーチンで動かす。画面を離れても続く）と `ui/ImportUi.kt` に置く。依存する部分とは、SAFのフォルダ読み出し、状態の管理、ダイアログ。
- 自動反映は、画面が出ている間だけ4秒ごとに `FolderSync.fingerprint()` を比べる。変わったときだけ `pendingImports()` を計算する。

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `java.util.zip.ZipFile`, Storage Access Framework (`ActivityResultContracts.OpenDocument` / `OpenDocumentTree`, `DocumentsContract`), JUnit4 + kotlin.test

**Spec:** `docs/superpowers/specs/2026-11-07-folder-import-and-auto-sync-design.md`

## Global Constraints

- minSdk 26（Android 8）。CI の `lintDebug`（NewApi）を通すこと。API 26 にない呼び出しは使わない（`readNBytes` などは不可）。
- 取り込みの大きさに上限を付けない。代わりに空き容量を確かめる。
- 取り込まないフォルダ名：`__pycache__`、`.git`、`venv`、`.venv`（どの階層でも）。
- 「最初に実行」の自動選択の順：`main.py` → `bot.py` → `app.py` → 一番上の階層の `.py` が1つだけならそれ。決まらなければ null。すでに決まっていて、そのファイルがあるなら変えない。
- 2回目以降は「同じ名前のファイルだけ上書き」。Botフォルダにだけあるファイルは消さない。
- 秘密のファイル（`FolderSync.isSecret`）は、Botフォルダには入れるが、共有コピーには出さない。
- 共有コピーから実行用へ入れるときは、必ず「反映する」の確認を通す。自動チェックの間隔は4秒で、画面が出ている間（ON_RESUME〜ON_PAUSE）だけ。
- 画面の文言（仕様で決まったもの）：
  - 「📥 取り込む」「ファイルを選ぶ」「フォルダを選ぶ」
  - 「上書きして取り込む」
  - 「今すぐBotを再起動しますか？（再起動するまで新しい中身は使われません）」「再起動する」「あとで」
  - 「取り込み中 {n}%」
- 「🔄 更新」ボタンはなくす。

## Review Focus

1. **Windowsで作ったZIP**：日本語のファイル名がShift_JIS（CP932）で入っていても、文字化けや失敗なしで取り込める（Task 1）。
2. **取り込むものが何もない**：ZIPやフォルダに、取り込めるファイルが1つもない（空、不要フォルダだけ）。そのときは「取り込めるファイルがありませんでした」と出し、Botフォルダは変えない（Task 1, Task 2）。
3. **ファイルとフォルダの名前がぶつかる**：Botフォルダの `data/` フォルダと、取り込む側の `data` ファイルなど。途中で失敗しても、Botフォルダは取り込み前の状態に戻る（Task 3）。
4. **移している途中でアプリが終了させられる**：次にアプリを開いたとき、退避しておいたものから取り込み前の状態に戻す（Task 3）。
5. **取り込んだ直後の自動チェック**：取り込んだファイルについて「共有のコピーが変わっています」が出ない（Task 4）。

---

### Task 1: ZIPを一時フォルダに展開する（安全に）

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/files/BotImporter.kt`
- Test: `app/src/test/java/com/pythonrunner/app/files/BotImporterTest.kt`

**Interfaces:**
- Produces:
  - `object BotImporter`
  - `fun isSkipped(relativePath: String): Boolean`：パスのどこかに `__pycache__`/`.git`/`venv`/`.venv` があれば true。
  - `fun zipSize(zip: File): Long`：展開後の合計バイト（`ZipFile` の各 entry の `size`、不明なら `compressedSize`）。
  - `fun stageZip(zip: File, staging: File, onProgress: (Int) -> Unit = {}): Int`：
    - 展開して、取り込んだファイル数を返す。
    - UTF-8 で読めない名前は CP932（`Charset.forName("MS932")`）で読み直す。
    - Botの外に出る名前（`..`、絶対パス）と `isSkipped` のものは捨てる。
    - 展開後、一番上の階層がフォルダ1つだけなら、その中身を一番上に上げる。
    - `onProgress` は 0〜100。

- [ ] **Step 1: 失敗するテストを書く**（テスト内で `ZipOutputStream` を使ってZIPを作る小さな関数を用意する）
  - `stagesFilesAndFolders`：`main.py`、`cogs/a.py`、`config.json` → staging に同じ形で入り、戻り値は 3。
  - `stripsSingleTopFolder`：`repo-main/main.py`、`repo-main/cogs/a.py` → staging に `main.py`、`cogs/a.py`。
  - `keepsTopFolderWhenThereAreSeveral`：`a/x.py`、`b/y.py` → そのまま。
  - `skipsJunkFolders`：`__pycache__/m.pyc`、`.git/HEAD`、`venv/bin/python`、`sub/.venv/x` は入らない。`isSkipped("cogs/__pycache__/a.pyc") == true`、`isSkipped("cogs/a.py") == false`。
  - `rejectsZipSlip`：`../evil.py`、`/abs.py` は staging の外にも中にも作られない。
  - `readsShiftJisNames`：`ZipOutputStream(out, Charset.forName("MS932"))` で `日本語.py` を入れたZIP → staging に `日本語.py`。
  - `emptyZipStagesNothing`：フォルダの entry だけ → 戻り値 0。
  - `zipSizeSumsUncompressedSizes`
  - `progressEndsAt100`
- [ ] **Step 2: 失敗を確かめる** — Run: `./gradlew testDebugUnitTest --tests "com.pythonrunner.app.files.BotImporterTest"` / Expected: FAIL（`BotImporter` がない）
- [ ] **Step 3: 実装する**（`ZipFile(zip, charset)` を使う。UTF-8 で開いて `ZipException`/`IllegalArgumentException` なら MS932 で開き直す）
- [ ] **Step 4: 通ることを確かめる** — 同じコマンドで PASS
- [ ] **Step 5: Commit** — `git commit -m "feat: stage ZIP imports safely (zip slip, junk folders, Shift_JIS names)"`

---

### Task 2: ファイル1つ・フォルダの取り込み準備と、取り込み計画

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/files/BotImporter.kt`
- Test: `app/src/test/java/com/pythonrunner/app/files/BotImporterTest.kt`

**Interfaces:**
- Consumes: `isSkipped`（Task 1）
- Produces:
  - `fun stageFile(name: String, input: InputStream, staging: File)`：
    - 名前の最後の部分だけを使って、staging の一番上に置く。
    - `..` や空の名前は `IllegalArgumentException`。
  - `fun stageTree(children: List<Pair<String, () -> InputStream>>, staging: File, onProgress: (Int) -> Unit = {}): Int`：
    - （相対パス, 開き方）の一覧を staging に書く。
    - `isSkipped` と危ないパスは捨てる。
    - SAFのフォルダ読み出しは Task 6 がこの形に変える。
  - `data class ImportPlan(val files: List<String>, val overwrites: List<String>, val bytes: Long)`：
    - `files`：取り込む全ファイルの相対パス（`/` 区切り、並べ替え済み）。
    - `overwrites`：Botフォルダにすでにあり、中身が違うもの。中身が同じものは含めない。
  - `fun plan(staging: File, botDir: File): ImportPlan`
  - `fun missingBytes(needed: Long, available: Long, margin: Long = 50L * 1024 * 1024): Long`：足りなければ不足バイト数、足りれば 0。

- [ ] **Step 1: 失敗するテストを書く**
  - `stageFilePutsItAtTheTop`：`stageFile("bot.py", …)` → `staging/bot.py`。`stageFile("../x.py", …)` は例外。
  - `stageTreeSkipsJunkAndUnsafePaths`
  - `planListsOnlyChangedExistingFilesAsOverwrites`：
    - Botフォルダに `main.py`（中身A）、`data.json`（中身X）、`keep.db`（中身Z）。
    - staging に `main.py`（中身B）、`data.json`（中身X）、`new.py`。
    - → `overwrites == ["main.py"]`、`files == ["data.json", "main.py", "new.py"]`。
  - `planCountsBytes`
  - `missingBytesKeepsAMargin`：`missingBytes(100, 50L*1024*1024 + 99) == 1`、`missingBytes(100, 50L*1024*1024 + 100) == 0`。
- [ ] **Step 2: 失敗を確かめる** — Expected: FAIL
- [ ] **Step 3: 実装する**
- [ ] **Step 4: 通ることを確かめる** — PASS
- [ ] **Step 5: Commit** — `git commit -m "feat: stage single files and folders; plan imports with their overwrites"`

---

### Task 3: 退避付きでBotフォルダへ移す・途中終了からの復旧・最初に実行・requirements

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/files/BotImporter.kt`
- Test: `app/src/test/java/com/pythonrunner/app/files/BotImporterTest.kt`

**Interfaces:**
- Consumes: `ImportPlan`（Task 2）
- Produces:
  - `fun apply(plan: ImportPlan, staging: File, botDir: File, backup: File, move: (File, File) -> Unit = ::defaultMove)`：
    1. 上書きされる既存ファイルを `backup` に移す（同じ相対パス）。
    2. staging のファイルを `botDir` に移す。
    3. 成功したら backup と staging を消す。
    - 途中で例外が出たら、`botDir` に移した新しいファイルを消し、backup から戻して、例外を投げ直す。
    - `defaultMove`：同じ場所の中なら `renameTo`、だめならコピーして元を消す。
    - 既存の「フォルダ」と同じ名前のファイルは、移す前に失敗として扱う（`IOException`）。
  - `fun recover(botDir: File, backup: File, staging: File): Boolean`：
    - 前回の apply が途中で終わった印（`backup/.importing` に、移すファイル一覧を1行ずつ書いたもの）があれば戻す。
    - 戻し方は apply の失敗時と同じで、戻したら true。
    - staging は無条件で消す。
    - `apply` は最初にこの印を書き、最後に消す。
  - `fun pickMain(botDir: File, current: String?): String?`（Global Constraints の順）
  - `fun requirements(staging: File): String?`：一番上の階層の `requirements.txt` の中身。なければ null。

- [ ] **Step 1: 失敗するテストを書く**
  - `applyOverwritesAndKeepsOtherFiles`：上書き後、`keep.db` は残り、`main.py` は新しい中身。backup と staging は空。
  - `failureMidwayRestoresTheBotFolder`：
    - `move` を、2つ目のファイルで `IOException` を投げる偽物にする。
    - → `assertFailsWith<IOException>`。Botフォルダの全ファイルが取り込み前と同じで、`new.py` はない。
  - `fileOverAFolderOfTheSameNameFailsCleanly`：Botフォルダに `data/x.json`、staging に `data`（ファイル） → 例外。Botフォルダは元のまま。
  - `recoverRestoresAnInterruptedApply`：
    - apply の途中で止まった状態を手で作る。backup に `main.py`（古い中身）と `.importing`（`main.py` と `new.py`）、Botフォルダに新しい `main.py` と `new.py`。
    - → `recover` が true。`main.py` は古い中身に戻り、`new.py` は消える。
  - `recoverWithNothingToDoReturnsFalse`
  - `pickMainOrder`：
    - `bot.py` と `app.py` → `bot.py`
    - `main.py` があれば `main.py`
    - `run.py` だけ → `run.py`
    - `a.py` と `b.py` → null
    - `current = "x.py"` があればそれ
    - `current` が消えていれば選び直す
  - `requirementsReadsTopLevelOnly`
- [ ] **Step 2: 失敗を確かめる** — FAIL
- [ ] **Step 3: 実装する**
- [ ] **Step 4: 通ることを確かめる** — PASS
- [ ] **Step 5: Commit** — `git commit -m "feat: apply imports with backup and rollback; recover interrupted imports"`

---

### Task 4: 共有コピーへ強く書く・変化の目印

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/files/FolderSync.kt`
- Test: `app/src/test/java/com/pythonrunner/app/files/FolderSyncTest.kt`

**Interfaces:**
- Produces:
  - `fun exportForced(paths: Collection<String>)`：
    - 指定のファイル（Botフォルダ側）を、共有コピーに今の中身で上書きし、目録もその中身にする。
    - 秘密のファイルと危ないパスは飛ばす。
    - 共有コピーにまだ反映していない変更があっても上書きする（仕様 §3）。
  - `fun fingerprint(): String`：共有コピーの（パス, 大きさ, 更新時刻）の並びから作る短い文字列。中身は読まない。

- [ ] **Step 1: 失敗するテストを書く**
  - `forcedExportOverwritesUnimportedSharedEdits`：
    - export 済みの `main.py` を共有側で書き換える（`pendingImports().modified == ["main.py"]` になる）。
    - そのあと実行用の `main.py` を新しい中身にして `exportForced(listOf("main.py"))`。
    - → 共有側も新しい中身で、`pendingImports().isEmpty`。
  - `forcedExportSkipsSecrets`：`.env` は共有に出ない。
  - `fingerprintChangesWhenASharedFileChanges`：
    - 書き換え前後で違う値になる。
    - 何もしなければ同じ値。
    - 書き換えでは `setLastModified` を進めて、時刻の分解能に頼らない。
- [ ] **Step 2: 失敗を確かめる** — FAIL
- [ ] **Step 3: 実装する**
- [ ] **Step 4: 通ることを確かめる** — PASS
- [ ] **Step 5: Commit** — `git commit -m "feat: forced export for imported files; cheap fingerprint of the shared copy"`

---

### Task 5: Botの再起動

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/service/BotService.kt`, `app/src/main/java/com/pythonrunner/app/service/BotController.kt`

**Interfaces:**
- Produces:
  - `BotService.ACTION_RESTART = "com.pythonrunner.app.RESTART"`
  - `BotController.restart(context: Context)`

- [ ] **Step 1: 実装する**
  - `ACTION_RESTART` を受けたら、`restartRequested = true` にして `runner.stop()` を別スレッドで呼ぶ。
  - `onBotFinished` で `restartRequested` なら、それを false に戻して `runner.start()` する。サービスとウェイクロックは止めない。
  - Botが動いていないときの `ACTION_RESTART` は、`ACTION_START` と同じ。
  - Service は単体テストできないので、Task 9 の実機確認に含める。
- [ ] **Step 2:** `./gradlew testDebugUnitTest assembleDebug lintDebug` が通ること。
- [ ] **Step 3: Commit** — `git commit -m "feat: restart the bot in one step"`

---

### Task 6: 取り込みの流れ（アプリ全体で動く）

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/service/BotController.kt`
- Create: `app/src/main/java/com/pythonrunner/app/files/SafTree.kt`

**Interfaces:**
- Consumes: Task 1〜4 のすべて
- Produces:
  - `sealed interface ImportState`：
    - `Idle`
    - `Working(percent: Int)`
    - `Confirm(plan: ImportPlan)`
    - `Done(count: Int, requirements: String?, botWasRunning: Boolean)`
    - `Failed(message: String)`
  - `BotController.importState: StateFlow<ImportState>`
  - `BotController.importFile(context: Context, uri: Uri)`
  - `BotController.importFolder(context: Context, treeUri: Uri)`
  - `BotController.confirmImport(context: Context)`
  - `BotController.cancelImport(context: Context)`
  - `BotController.finishImport()`：`Done`/`Failed` を `Idle` に戻す。
  - `SafTree.list(context: Context, treeUri: Uri): List<Pair<String, () -> InputStream>>`：`DocumentsContract.buildChildDocumentsUriUsingTree` で下の階層までたどる。
  - `BotController.recoverImport(context: Context)`：アプリ起動時に `BotImporter.recover` を呼ぶ。

- [ ] **Step 1: 実装する**
  - 置き場所：staging は `filesDir/import-staging`、backup は `filesDir/import-backup`。
  - 流れは、ひとつの `CoroutineScope(Dispatchers.IO + SupervisorJob())` で動かす。
    1. ファイルの扱い方を決める。
       - `.zip` は `cacheDir/import.zip` に写して `zipSize` で空き容量を確かめ、`stageZip`。
       - そのほかは `stageFile`。
       - フォルダは `SafTree.list` → `stageTree`。
    2. 0件なら `Failed("取り込めるファイルがありませんでした")`。
    3. `plan` を作る。
    4. `missingBytes(plan.bytes, filesDir.usableSpace) > 0` なら `Failed("空き容量が足りません（あと {n}MB 必要です）")`。
    5. `overwrites` が空なら、そのまま適用する。空でなければ `Confirm(plan)` で止まり、`confirmImport` を待つ。
    6. 適用：`apply` → `sync?.exportForced(plan.files)` → `store.mainFile = pickMain(botDir, store.mainFile)` → `Done(plan.files.size, requirements(staging を apply 前に読んだもの), status が RUNNING か)`。
  - `cancelImport` は staging を消して `Idle`。
  - 取り込み中に、もう一度取り込みを始めることはできない（`Working`/`Confirm` の間は何もしない）。
  - 例外は `Failed("取り込めませんでした: {message}")` にする。
- [ ] **Step 2:** `./gradlew testDebugUnitTest assembleDebug lintDebug` が通ること。
- [ ] **Step 3: Commit** — `git commit -m "feat: import flow that keeps going when the screen goes away"`

---

### Task 7: 取り込みの画面（ボタン1つ・確認・再起動）

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/ui/ImportUi.kt`
- Modify: `app/src/main/java/com/pythonrunner/app/ui/SpikeScreen.kt`

**Interfaces:**
- Consumes: Task 5・6
- Produces:
  - `@Composable fun ImportButton(enabled: Boolean, onPickFile: () -> Unit, onPickFolder: () -> Unit)`：「📥 取り込む」を押すと、メニューで「ファイルを選ぶ」「フォルダを選ぶ」を出す。
  - `@Composable fun ImportStateView(state: ImportState, onConfirm: () -> Unit, onCancel: () -> Unit, onDone: (ImportState.Done) -> Unit)`

- [ ] **Step 1: 実装する**
  - 「📄 .pyファイルを追加」を `ImportButton` に置き換える。
    - ファイルは `OpenDocument`（`*/*`）、フォルダは `OpenDocumentTree` で選ぶ。
  - `Working` のとき：「取り込み中 {n}%」と進み具合のバーを出す。
  - `Confirm` のとき：AlertDialog を出す。
    - 題名：「上書きされるファイルがあります」
    - 本文：`overwrites` の一覧（多いときはスクロール）
    - ボタン：「上書きして取り込む」、「やめる」
  - `Done` のとき：
    1. 「{count}個のファイルを取り込みました」と出す。
    2. `requirements` があれば、「3. ライブラリ」の入力欄に入れる。入力欄が空でなければ置き換える前に確かめない（作り手の判断：ユーザーは今入れたものを使いたいので置き換える）。
    3. `mainFile` と `filesVersion` を更新する。
    4. `botWasRunning` なら、AlertDialog を出す。
       - 本文：「今すぐBotを再起動しますか？（再起動するまで新しい中身は使われません）」
       - ボタン：「再起動する」（`BotController.restart`）、「あとで」
    5. 最後に `finishImport()` を呼ぶ。
  - `Failed` のとき：赤字でメッセージを出し、「閉じる」で `finishImport()` を呼ぶ。
  - 画面の説明（Help）に次の文を足す：「Googleドライブのフォルダは、ドライブでZIPとしてダウンロードしてから『ファイルを選ぶ』で取り込めます。」
  - アプリ起動時（`LaunchedEffect(Unit)`）に `BotController.recoverImport(context)` を呼ぶ。
- [ ] **Step 2:** `./gradlew testDebugUnitTest assembleDebug lintDebug` が通ること。
- [ ] **Step 3: Commit** — `git commit -m "feat: one import button, overwrite confirmation and restart prompt"`

---

### Task 8: 共有コピーの自動チェック（「🔄 更新」をなくす）

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/ui/SpikeScreen.kt`, `app/src/main/java/com/pythonrunner/app/service/BotController.kt`

**Interfaces:**
- Consumes: `FolderSync.fingerprint()`（Task 4）、`BotController.restart`（Task 5）
- Produces: `BotController.declinedChanges: SyncChanges?`（「反映しない」を選んだ変更。プロセスの間だけ覚える）

- [ ] **Step 1: 実装する**
  - 「🔄 更新」ボタンを消す。
  - 画面が出ている間（`LifecycleResumeEffect` などで ON_RESUME〜ON_PAUSE）だけ、4秒ごとに次をする。
    1. IO で `fingerprint()` を取る。
    2. 前回と違うとき、または初回だけ `pendingImports()` を計算する。
    3. 結果が空でなく、`declinedChanges` と同じでなく、ダイアログや取り込みが出ていなければ、`SharedChangesDialog`（`thenStart = false`）を出す。
  - 「反映しない」は `declinedChanges = changes` にする。
  - 「起動」のときの確認は今のまま、`declinedChanges` に関係なく必ず出す。
  - 「反映する」のあと、Botが動いていれば、Task 7 と同じ再起動の確認を出す。
  - 画面が出たとき（ON_RESUME）は、`exportShared()` も1回呼ぶ（アプリ → 共有コピー）。
- [ ] **Step 2:** `./gradlew testDebugUnitTest assembleDebug lintDebug` が通ること。
- [ ] **Step 3: Commit** — `git commit -m "feat: notice shared-copy changes automatically; remove the Update button"`

---

### Task 9: 説明書き・試験版・実機確認

**Files:**
- Modify: `README.md`（「2. Botのファイル」の説明。「🔄 更新」の記述を自動チェックの説明に置き換える）

- [ ] **Step 1:** README を書き直す。取り込み（ファイル・ZIP・フォルダ）、上書きの確認、Googleドライブの使い方、自動で気づくこと、再起動の確認を書く。
- [ ] **Step 2:** `./gradlew testDebugUnitTest assembleDebug lintDebug` が通ること。push して CI が通ること。
- [ ] **Step 3:** 試験版（例 `v0.5.0-test2`）を出し、署名証明書の SHA-256 `a4559f5ba1885373dc77ea81037e152329bf0afac9a36c26e7d4ac5fe200ae9b` を確かめる。
- [ ] **Step 4:** ユーザーに実機で確かめてもらう。
  - Googleドライブから落としたZIP、フォルダを選ぶ、2回目の取り込みで上書きの確認が出る、動作中の取り込みで再起動の確認が出る。
  - ファイルアプリで共有コピーのファイルを消すと、数秒で一覧が出る。
  - Googleドライブがフォルダ選びの画面に出るかどうか（仕様 §7）も聞く。
