# 質問Bot（askbot）とスマホLLM Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Python Runner に llama-server とLFM3-VL-2Bを載せ、Discordで@メンションすると写真・Wikipedia・過去の会話を踏まえて答えるBot（askbot）を作る。

**Architecture:** 全体は2つの部分に分かれる。
- **アプリ側（Kotlin）**:
  - llama.cpp をソースからNDKで組み立てた `libllama_server.so` を同梱する。
  - モデルをアプリ専用の場所にダウンロードする。
  - Botを起動すると、同じフォアグラウンドサービスで llama-server も動かす。
  - Botには `LLM_URL` を環境変数で渡す。
- **Bot側（Python・1ファイル）**: `bots/askbot/askbot.py`。ユーザーが「📄 .pyファイルを追加」で1つ取り込むだけで使えるよう、1ファイルにまとめる。
  - LLMは llama-server のOpenAI互換APIで呼ぶ。
  - 会話はSQLite（FTS5 trigram）に保存する。
  - 1人ずつ順番に処理する。

**Tech Stack:**
- Kotlin / Compose / JUnit4 + kotlin.test
- llama.cpp b11972（CMake + NDK 29）
- Python 3.14（スマホ）/ 3.12以上（CI）
- discord.py 2.7.1、aiohttp（discord.py に含まれる）、sqlite3（標準）、pytest

**Spec:** `docs/superpowers/specs/2026-10-29-llm-discord-bot-design.md`

## Global Constraints

- **llama.cpp**:
  - ソース: `https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11972.tar.gz`
  - SHA-256: `3b0b6c05a420d649038b6bfd96ba3efcdcfcb8c5af0cccf7f2b661e05f7cb05e`
- **組み立てのCMake設定**:
  - `-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release`
  - `-DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8-a -DGGML_OPENMP=OFF`
  - `-DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF -DLLAMA_BUILD_APP=OFF`
  - target は `llama-server`。strip して `libllama_server.so` という名前で jniLibs/arm64-v8a に入れる。
  - CMake は SDK の `cmake;3.31.5`（ninja 同梱）を使う。
- **モデル**:
  - 取得元: `https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/resolve/main/<名前>`
  - `LFM3-VL-2B-QAD-Q4_0.gguf`: 1049615648 バイト、SHA-256 `621c455376cb96a20891697deb9975c6fb47dd5ea98ed3ad1f3b7e8f24f0b1e6`
  - `mmproj-LFM3-VL-2B-Q8_0.gguf`: 203031872 バイト、SHA-256 `c5c5b58d544a18da919172d2d0368090ea1a4a351b6feb7f8a326a264a5918ab`
  - 保存先: `filesDir/llm-models/`
- **llama-server の起動**:
  - `--host 127.0.0.1 --port 18089 -c 8192 -np 1 --reasoning off --no-webui -t <スレッド数>`
  - スレッド数の初期値は4。選べる値は 2 / 4 / 6。
- **Botに渡す環境変数**: `LLM_URL=http://127.0.0.1:18089`。モデルが準備できていないときは渡さない。
- **生成の設定**:
  - サンプリング: `temperature 0.1, top_k 50, top_p 1.0, min_p 0.0, repeat_penalty 1.1`
  - `MAX_ANSWER_TOKENS = 1500`。暴走防止の上限で、順番待ちを理由に切り上げることはしない（ユーザー回答）。
- **全部無料**。
  - 外部への通信は次の4つだけ: Discord、`ja.wikipedia.org` のAPI、Hugging Face（モデル）、GitHub（ソース）。
  - Wikipediaへの通信には User-Agent `PythonRunner-askbot/1.0 (https://github.com/kodai1350-alt/Android-Python)` を付ける。
- **Discord**:
  - 1通の上限は2000文字。メッセージの書き換えは1.5秒以上あけてまとめる。
  - 返事は質問への「返信」として送る。
  - URLは `<url>` で囲み、埋め込みプレビューを出さない。
- **Botのコード**:
  - `bots/askbot/askbot.py` の1ファイルにする。
  - Python 3.12〜3.14 で動くこと。3.13以降にしかない構文は使わない。
- **保存先**:
  - 会話のDB: `os.path.join(os.environ.get("HOME") or ".", "askbot-history.db")`（アプリ専用の場所）。
  - Botのトークン: 環境変数 `TOKEN`（アプリの「秘密の値」）。
- UIの文言は日本語にする。コミットの末尾に `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` を付ける。

## Review Focus

1. **スマホで撮った大きな写真**（4000×3000、数MB）:
   - 落ちたり固まったりせず、答えるか、分かる言葉で断る。
   - 8MBを超える画像は読まずに断る。
   - サーバーの「文脈長を超えた」エラーは、日本語のメッセージにする。
   - テスト: Task 9 `test_stream_answer_context_error`、Task 10 `test_too_large_image_rejected`
2. **llama-server が起動中や再起動中に質問が来る**:
   - 「AIモデルを起動中…」と出して最大120秒待ち、つながったら答える。
   - テスト: Task 9 `test_stream_answer_waits_for_server`
3. **改行のない長い答え**（2000文字を超える1行、コードブロック）:
   - 途中で文字が消えずに複数のメッセージに分かれる。
   - テスト: Task 5 `test_split_message_without_newlines`
4. **同じ人が続けてメンションする**:
   - 2つ目は受け付けず「前の質問が終わるまで待ってね」と返す。
   - 他の人の順番は崩れない。
   - テスト: Task 6 `test_same_user_rejected_while_waiting`
5. **1〜2文字の言葉で探す**（「名前」「店」）:
   - trigram で見つからなくても、LIKE で見つかる。
   - テスト: Task 8 `test_search_short_keyword`

---

## File Structure

```
gradle.properties                         … llamaCppVersion, llamaCppSha256, cmakeVersion を追加
app/build.gradle.kts                      … BuildLlamaServer タスクと jniLibs への追加
app/src/main/java/com/pythonrunner/app/
  llm/LlmModel.kt                         … ModelFile と2つのファイルの定義
  llm/ModelDownloader.kt                  … 続きから再開できるダウンロードとハッシュ確認
  llm/LlmServer.kt                        … llama-server のコマンドとURL
  llm/Supervisor.kt                       … 落ちたら再起動するプロセスの見張り
  bot/SpikeBotStore.kt                    … llmThreads を追加
  service/BotController.kt                … llmState、ダウンロード開始・中止・削除
  service/BotService.kt                   … Botと一緒に llama-server を起動・停止、LLM_URL
  ui/SpikeScreen.kt                       … 「5. AIモデル（LLM）」の欄、以降の番号を繰り下げ
app/src/test/java/com/pythonrunner/app/llm/
  ModelDownloaderTest.kt, LlmServerTest.kt, SupervisorTest.kt
bots/askbot/askbot.py                     … Bot本体（1ファイル）
bots/askbot/test_askbot.py                … pytest
.github/workflows/build.yml               … cmake の導入、llamaのキャッシュ、pytest
.github/workflows/release.yml             … cmake の導入、askbot.py をリリースに添付
README.md                                 … 質問Botの使い方
docs/superpowers/notes/2026-10-29-askbot-device-check.md … 実機確認の記録
```

---

### Task 1: llama-server をソースから組み立てて同梱する

**Files:**
- Modify: `gradle.properties`, `app/build.gradle.kts`, `.github/workflows/build.yml`, `.github/workflows/release.yml`

**Interfaces:**
- Produces:
  - Gradle タスク `buildLlamaServer`。出力は `build/llama/libllama_server.so`。
  - APK の `lib/arm64-v8a/libllama_server.so`。

- [ ] **Step 1: gradle.properties に固定値を追加**
  - `llamaCppVersion=b11972`
  - `llamaCppSha256=3b0b6c05…cb05e`（全桁は Global Constraints）
  - `cmakeVersion=3.31.5`

- [ ] **Step 2: 手元に cmake を入れる**
  - Run: `"$LOCALAPPDATA/Android/Sdk/cmdline-tools/latest/bin/sdkmanager.bat" "cmake;3.31.5"`
  - Expected: `$LOCALAPPDATA/Android/Sdk/cmake/3.31.5/bin/cmake.exe` と `ninja.exe` がある。

- [ ] **Step 3: `app/build.gradle.kts` にタスクを追加する**
  - `downloadLlamaSource`: 既存の `DownloadFile` を使い、SHA-256 を確認する。
  - `extractLlamaSource`: `Sync` で tarTree を展開する。
  - `BuildLlamaServer`: `abstract class BuildLlamaServer : DefaultTask()`。入力は次のとおり。
    - `source`（展開先）、`cmake`（SDKの `cmake/<cmakeVersion>/bin/cmake[.exe]`）
    - `ndkDir`、`stripTool`（`llvm-strip[.exe]`）、`version`
  - 出力は `output: RegularFileProperty`。
  - 処理の流れ:
    1. CMake を Ninja で configure する（Global Constraints の設定、`-DCMAKE_MAKE_PROGRAM=<cmakeのbin>/ninja`）。
    2. `--build --target llama-server -j` で組み立てる。
    3. `llvm-strip` で `output` に書き出す。
    4. 出力と同じ場所に `<output>.version` を書く。
  - CIのキャッシュから戻したときに組み立て直さないよう、処理の最初に確認する。`output` があって `.version` の中身が `version` と同じなら、何もせずに終わる。
  - `androidComponents.onVariants` の jniLibs の `into("arm64-v8a")` に `from(buildLlamaServer)` を追加する。

- [ ] **Step 4: 組み立てて中身を確かめる**
  - Run: `./gradlew assembleDebug`
  - その後 `llvm-readelf -d app/build/llama/libllama_server.so` と `llvm-readelf --notes …` を見る。
  - Expected:
    - NEEDED は `libm.so libdl.so libc.so` だけ。
    - android.ident の先頭バイトが `1a`（API 26）。
    - `unzip -l app/build/outputs/apk/debug/app-debug.apk` に `lib/arm64-v8a/libllama_server.so` がある。

- [ ] **Step 5: CI を直す**
  - `build.yml` と `release.yml` の `sdkmanager` に `"cmake;3.31.5"` を足す。
  - 両方に `actions/cache@v4` を足す。対象は `path: app/build/llama`、`key: llama-${{ hashFiles('gradle.properties') }}`。
  - `release.yml` の `gh release create` に、`case "$GITHUB_REF_NAME" in *-*) pre="--prerelease" ;; esac` を付ける。ハイフン付きのタグ（例: `v0.3.0-test1`）を試験版として出し、「最新版」を変えないため。スパイクのブランチと同じ内容。
- [ ] **Step 6: Commit**（`feat: build llama-server from source and bundle it`）

---

### Task 2: モデルのダウンロード（続きから再開・ハッシュ確認）

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/llm/LlmModel.kt`, `app/src/main/java/com/pythonrunner/app/llm/ModelDownloader.kt`
- Test: `app/src/test/java/com/pythonrunner/app/llm/ModelDownloaderTest.kt`

**Interfaces:**
- Produces:
  - `data class ModelFile(val name: String, val size: Long, val sha256: String)`
  - `object LlmModel`:
    - `const val BASE_URL = "https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/resolve/main/"`
    - `val MODEL: ModelFile`、`val MMPROJ: ModelFile`（Global Constraints の値）
    - `val FILES = listOf(MODEL, MMPROJ)`
  - `class ModelDownloader(val dir: File, private val files: List<ModelFile> = LlmModel.FILES, private val baseUrl: String = LlmModel.BASE_URL)`:
    - `fun isReady(): Boolean`
    - `fun file(f: ModelFile): File`
    - `fun download(onProgress: (done: Long, total: Long) -> Unit, isCancelled: () -> Boolean)`
    - `fun delete()`
    - `class Cancelled : IOException()`

- [ ] **Step 1: 失敗するテストを書く**
  - JDK の `com.sun.net.httpserver.HttpServer` で、小さなファイル2つ（例: 3000バイトと500バイト）を配る。
  - `Range: bytes=N-` に 206 で応え、受け取った Range を記録する。
  - テスト:
    - `downloadsAndVerifies`: `download` の後、`isReady()` が true。ファイルの中身が一致し、`onProgress` の最後が `(3500, 3500)`。
    - `resumesFromPart`: 先に `<name>.part` に先頭1000バイトを置いておく。サーバーが受け取った Range が `bytes=1000-` で、最終的に `isReady()` が true。
    - `rejectsHashMismatch`: `ModelFile` の sha256 を違う値にする。`IOException` になり、`.part` が消え、`isReady()` が false。
    - `cancelKeepsPart`: `isCancelled` が途中で true を返す。`Cancelled` が投げられ、`.part` は残る。
    - `notReadyWhenFileSizeWrong`: 完了済みの印があっても、ファイルの長さが違えば `isReady()` が false。
- [ ] **Step 2: テストを実行して失敗を確認**
  - Run: `./gradlew testDebugUnitTest --tests '*ModelDownloaderTest*'`
  - Expected: コンパイルエラー（クラスがない）
- [ ] **Step 3: 実装する**
  - `HttpURLConnection` で `Range` を送る。206 以外が返ったら最初から書き直す。
  - 再開するときは、既存の `.part` を読んでハッシュを積み直してから続ける。
  - 1MBごとに `onProgress` と `isCancelled` を呼ぶ。
  - 完了したら `.part` を本来の名前に変え、`<name>.ok` に sha256 を書く。
  - `isReady()` は、全ファイルについて長さと `.ok` の中身が一致することを確かめる。
- [ ] **Step 4: テストを実行して成功を確認**（同じコマンドで PASS）
- [ ] **Step 5: Commit**（`feat: resumable, verified model download`）

---

### Task 3: llama-server のコマンドと、落ちたら再起動する見張り

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/llm/LlmServer.kt`, `app/src/main/java/com/pythonrunner/app/llm/Supervisor.kt`
- Modify: `app/src/main/java/com/pythonrunner/app/bot/SpikeBotStore.kt`
- Test: `app/src/test/java/com/pythonrunner/app/llm/LlmServerTest.kt`, `app/src/test/java/com/pythonrunner/app/llm/SupervisorTest.kt`, `app/src/test/java/com/pythonrunner/app/bot/SpikeBotStoreTest.kt`

**Interfaces:**
- Consumes: `ModelDownloader.file(ModelFile)`, `LlmModel.MODEL/MMPROJ`（Task 2）、既存の `ManagedProcess`
- Produces:
  - `class LlmServer(nativeLibDir: File, private val models: ModelDownloader, val port: Int = 18089)`:
    - `val url: String = "http://127.0.0.1:$port"`
    - `fun command(threads: Int): List<String>`
  - `class Supervisor(private val create: (onLine: (LogLine) -> Unit, onExit: (Int) -> Unit) -> ManagedProcess, private val log: (LogLine) -> Unit, private val delaysMillis: List<Long> = listOf(1_000, 5_000, 30_000), private val sleep: (Long) -> Unit = Thread::sleep)`:
    - `fun start()`
    - `fun stop()`
    - `val restarts: Int`
  - `SpikeBotStore.llmThreads: Int`（キーは `llmThreads`、初期値は4）

- [ ] **Step 1: 失敗するテストを書く**
  - `LlmServerTest.commandHasPinnedFlags`: `command(4)` が次の並びと完全に一致する。
    `[<nativeLibDir>/libllama_server.so, -m, <dir>/LFM3-VL-2B-QAD-Q4_0.gguf, --mmproj, <dir>/mmproj-LFM3-VL-2B-Q8_0.gguf, --host, 127.0.0.1, --port, 18089, -c, 8192, -np, 1, -t, 4, --reasoning, off, --no-webui]`
  - `SupervisorTest`:
    - 既存の `testutil/Shell.kt` の `shell("exit 1")` で、すぐ終わるプロセスを `ManagedProcess` として渡す。`shell()` は Windows では cmd を使うので、どちらでも動く。`sleep` は偽物を注入して記録する。
    - `restartsAfterUnexpectedExit`: すぐ終わるプロセスで、`sleep` に `[1000, 5000]` が渡され、`restarts` が2以上になる。
    - 遅延リストを使い切ったら、最後の値で繰り返す。
    - `stopPreventsRestart`: `stop()` の後は `create` が呼ばれない。
    - `logsTailOnCrash`: 落ちたとき、最後の30行までを `[AIモデル] ` 付きで `log` に出す。普段の行は出さない。
  - `SpikeBotStoreTest.llmThreadsDefaultsToFour`: 初期値が4。代入して読み直すと、その値になる。
- [ ] **Step 2: 失敗を確認**
  - Run: `./gradlew testDebugUnitTest --tests '*LlmServerTest*' --tests '*SupervisorTest*' --tests '*SpikeBotStoreTest*'`
- [ ] **Step 3: 実装する**
  - `Supervisor` は `BotRunner` と同じく、1つのロックと世代番号で start / stop / exit を順番に扱う。
  - 再起動の待ちは別スレッドで行う。
  - 正常に30分以上動いたら、遅延リストの位置を最初に戻す。
- [ ] **Step 4: 成功を確認**（同じコマンドで PASS）
- [ ] **Step 5: Commit**（`feat: llama-server command and restarting supervisor`）

---

### Task 4: アプリに組み込む（サービス・状態・画面）

**Files:**
- Modify: `app/src/main/java/com/pythonrunner/app/service/BotController.kt`, `app/src/main/java/com/pythonrunner/app/service/BotService.kt`, `app/src/main/java/com/pythonrunner/app/ui/SpikeScreen.kt`

**Interfaces:**
- Consumes: Task 2 と Task 3 のクラス、`SpikeBotStore.llmThreads`
- Produces:
  - `sealed interface LlmState`:
    - `data object Missing`
    - `data class Downloading(val percent: Int)`
    - `data object Ready`
    - `data class Failed(val message: String)`
  - `BotController` に追加するもの:
    - `val llmState: StateFlow<LlmState>`
    - `fun models(context): ModelDownloader`（`filesDir/llm-models`）
    - `fun llmServer(context): LlmServer`
    - `fun startModelDownload(context)`、`fun cancelModelDownload()`、`fun deleteModel(context)`
    - `fun refreshLlmState(context)`

- [ ] **Step 1: BotController**
  - ダウンロードは `thread(name = "model-download")` で行う。
  - 進み具合を `Downloading(percent)` に流す。
  - 終わったら次のどれかにする: `Ready`、中止なら `Missing`、失敗なら `Failed("ダウンロードに失敗しました: …")`。
  - 二重に開始しない。
- [ ] **Step 2: BotService**
  - `startBot()` で `runner.start()` が true のとき、`models.isReady()` なら `Supervisor` を作って `start()` する。
  - 作るプロセスは `ManagedProcess(llmServer.command(store.llmThreads), emptyMap(), modelsDir, …)`。
  - Botの環境変数:
    - モデルが準備できていれば `rt.environment(listOf(store.libsDir), store.envVars + ("LLM_URL" to llmServer.url))`。
    - できていなければ、今までどおりにする。
  - Supervisor は次の3か所で `stop()` する（別スレッドで）: `stopBot()`、`onBotFinished()`（runner が止まったとき）、`onDestroy()`。
- [ ] **Step 3: 画面**
  - 「4. 秘密の値」の後に `Section("5. AIモデル（LLM）")` を入れる。以降の番号は「6. 実行」「7. ログ」「8. 端末の設定」に繰り下げる。
  - 欄の中身:
    - 状態の表示: `WorkResultView` で「まだダウンロードしていません」／「ダウンロード中 ○%」／「準備完了（Botを起動すると一緒に動きます）」／失敗の文言。
    - ボタン:
      - 「ダウンロード（約1.25GB・Wi-Fi推奨）」
      - ダウンロード中は「中止」
      - 準備完了なら「削除」（確認ダイアログを出す）
    - 「使うCPUの数: 2 / 4 / 6」の選択。保存し、次の起動から使う。
    - `Help("llm", uiPrefs)` の説明文:
      「Discordで質問に答えるBot（askbot）が使うAIモデルです。ダウンロードしておくと、Botを起動したときに一緒に動きます。Botからは環境変数 LLM_URL で使えます。」
  - ダウンロード中は `view.keepScreenOn = true` にする（スパイクと同じ `DisposableEffect`）。
  - `ON_RESUME` で `refreshLlmState` を呼ぶ。
- [ ] **Step 4: 組み立てとテスト**
  - Run: `./gradlew testDebugUnitTest assembleDebug`
  - Expected: BUILD SUCCESSFUL。既存のテストもすべて PASS。
- [ ] **Step 5: Commit**（`feat: download the model and run llama-server with the bot`）

---

### Task 5: Bot — 返事の分割と書き換え

**Files:**
- Create: `bots/askbot/askbot.py`, `bots/askbot/test_askbot.py`

**Interfaces:**
- Produces:
  - `DISCORD_LIMIT = 2000`
  - `EDIT_INTERVAL = 1.5`
  - `def split_message(text: str, limit: int = DISCORD_LIMIT) -> list[str]`
  - `def progress_text(kind: str, percent: int) -> str`
    - kind ごとの文言:
      - `"wiki"`: `📖 Wikipediaを読んでいます… {p}%`
      - `"photo"`: `🖼 写真を見ています… {p}%`
      - `"history"`: `🔎 前の会話を読んでいます… {p}%`
      - `"question"`: `💭 質問を読んでいます… {p}%`
    - percent は0〜100に収める。
  - `class ReplyWriter(first, send_more, *, interval=EDIT_INTERVAL, clock=time.monotonic)`:
    - `first` は `async edit(content=...)` を持つもの。
    - `send_more(text) -> awaitable` は、同じ型の新しいメッセージを返す。
    - メソッド: `async status(text)`、`async append(piece)`、`async finish(footer="")`

- [ ] **Step 1: 失敗するテストを書く**
  - `test_split_message_prefers_newlines`: 1990文字の行と50文字の行は、改行の位置で2つに分かれる。
  - `test_split_message_without_newlines`: 改行のない4500文字は、3つ（2000/2000/500）に分かれる。つなげると元に戻る。
  - `test_progress_text_clamps`: `progress_text("wiki", 130)` が `📖 Wikipediaを読んでいます… 100%`。
  - `test_reply_writer_throttles`: 偽の時計を使う。0.1秒ごとに `append` を10回しても、`edit` は1回だけ。`finish()` で全文が反映される。
  - `test_reply_writer_overflows_to_new_message`: 2500文字を `append` して `finish` すると、`send_more` が1回呼ばれる。1通目と2通目をつなげると全文になる。
  - `finish(footer)` の footer は最後のメッセージに付く。
- [ ] **Step 2: 失敗を確認**
  - Run: `python -m pytest bots/askbot -q`
  - Expected: ImportError / 失敗
- [ ] **Step 3: 実装する**
  - `split_message` は、上限内で最後の `\n` を優先して切る。なければ上限で切る。
  - `ReplyWriter` は、間隔内の更新を貯めておき、次の呼び出しか `finish` で反映する。
  - 上限を超えた分は `send_more` で新しいメッセージに移す。
- [ ] **Step 4: 成功を確認**（同じコマンドで PASS）
- [ ] **Step 5: Commit**（`feat(askbot): split and throttled reply writer`）

---

### Task 6: Bot — 順番待ち

**Files:** Modify `bots/askbot/askbot.py`, `bots/askbot/test_askbot.py`

**Interfaces:**
- Produces: `class AskQueue`:
  - `def submit(self, user_id: int, job: Callable[[], Awaitable[None]], on_position: Callable[[int], Awaitable[None]]) -> int | None`
    - 戻り値は待ちの位置（0は今すぐ実行）。同じ人の質問が既にあれば None。
  - `async def run_forever(self) -> None`
  - `def busy_users(self) -> set[int]`

- [ ] **Step 1: 失敗するテストを書く**（`asyncio.run` で回す）
  - `test_jobs_run_one_at_a_time_in_order`: A, B, C の順に実行される。同時に2つ動くことはない。
  - `test_positions_update`: B と C は 1 と 2 で受け付けられる。A が終わると、C の `on_position` が 1 で呼ばれる。
  - `test_same_user_rejected_while_waiting`: 同じ user_id の2つ目は None になる。最初の質問が終わった後なら受け付ける。
  - `test_failed_job_does_not_stop_queue`: 例外を出したジョブの後も、次のジョブが動く。
- [ ] **Step 2: 失敗を確認** → **Step 3: 実装**（`asyncio.Queue` と、利用中の人の集合で作る） → **Step 4: PASS を確認**
- [ ] **Step 5: Commit**（`feat(askbot): one-at-a-time queue with positions`）

---

### Task 7: Bot — 質問の見分け・日付・キーワード

**Files:** Modify `bots/askbot/askbot.py`, `bots/askbot/test_askbot.py`

**Interfaces:**
- Produces:
  - `JST = timezone(timedelta(hours=9))`
  - `@dataclass class Plan: mode: str; keywords: list[str]; since: datetime | None; until: datetime | None`
    - mode は `"photo" | "history" | "wiki" | "chat"`。
  - `def extract_keywords(text: str) -> list[str]`
  - `def parse_date_range(text: str, now: datetime) -> tuple[datetime | None, datetime | None]`
  - `def plan_question(text: str, has_image: bool, now: datetime) -> Plan`
- 決めごと（設計書10章をこう決めた。LLMは使わない）:
  - **photo**: 画像があれば必ず photo。
  - **history**: 次の言葉か日付表現があれば history。
    - `話した 話してた 言ってた 会話 だっけ 前に この前 さっきの`
  - **wiki**: それ以外で、キーワードがあれば wiki。
  - **chat**: どれにも当たらなければ chat。
  - **キーワードの取り出し**: カタカナ2文字以上、漢字2文字以上、英数字2文字以上の連続を取り出す。
    - 次の言葉は除く: 日付の言葉（今日 昨日 先週 今週 先月 今月 去年 何日 何月）、`写真 画像 名前 何 前`。
    - 重複は除き、出てきた順にする。
  - **日付**（JST、範囲は [since, until)）:
    - `今日` / `昨日` / `一昨日`
    - `先週`（前の週の月曜〜次の月曜）、`今週`
    - `先月`、`今月`
    - `M月D日`: 未来になるなら前年にする。
    - `M月`: その月全体。

- [ ] **Step 1: 失敗するテストを書く**（`now = datetime(2026, 10, 29, 12, 0, tzinfo=JST)`、木曜日）
  - `test_plan_history_with_date`:
    - 入力 `"10月20日に話してたゲームの名前なんだっけ？"`
    - 期待: mode `history`、keywords `["ゲーム"]`、since `2026-10-20 00:00 JST`、until `2026-10-21 00:00 JST`
  - `test_plan_last_week`:
    - 入力 `"先週ラーメン屋の話したけど、どこだっけ？"`
    - 期待: `history`、keywords `["ラーメン"]`、since `2026-10-19`、until `2026-10-26`
  - `test_plan_wiki`:
    - 入力 `"量子コンピュータって何？"`
    - 期待: `wiki`、keywords `["量子", "コンピュータ"]`、日付なし
  - `test_plan_photo`: 画像ありの `"これ何？"` → `photo`
  - `test_plan_chat`: `"こんにちは"` → `chat`
  - `test_future_date_means_last_year`: `"12月3日"` → since `2025-12-03`
  - `test_yesterday`: `"昨日"` → `2026-10-28` から `2026-10-29`
- [ ] **Step 2: 失敗を確認** → **Step 3: 実装** → **Step 4: PASS を確認**
- [ ] **Step 5: Commit**（`feat(askbot): question routing, keywords and dates`）

---

### Task 8: Bot — 会話の保存と検索（SQLite FTS5 trigram）

**Files:** Modify `bots/askbot/askbot.py`, `bots/askbot/test_askbot.py`

**Interfaces:**
- Produces:
  - `@dataclass class Stored: message_id: int; guild_id: int; channel_id: int; author: str; content: str; created_at: datetime`
  - `def jump_url(s: Stored) -> str`（`https://discord.com/channels/{guild}/{channel}/{message}`）
  - `class HistoryStore(path: str)`:
    - `register(guild_id, channel_id)`
    - `unregister(guild_id, channel_id)`（そのチャンネルの保存分を全部消す）
    - `registered(guild_id) -> set[int]`
    - `is_registered(channel_id) -> bool`
    - `upsert(s: Stored)`
    - `delete(message_id)`
    - `latest(channel_id) -> datetime | None`
    - `search(guild_id: int, channel_ids: set[int], keywords: list[str], since: datetime | None, until: datetime | None, limit: int = 20) -> list[Stored]`
- DBの作り:
  - テーブル `messages`（message_id PRIMARY KEY …）と `channels(guild_id, channel_id)`。
  - `messages_fts USING fts5(content, tokenize='trigram', content='messages', content_rowid='message_id')`。トリガーで同期する。

- [ ] **Step 1: 失敗するテストを書く**（`tmp_path` のDB）
  - `test_search_by_keyword`: 「ラーメン屋に行った」がキーワード `["ラーメン"]` で見つかる。
  - `test_search_short_keyword`: 「名前は太郎」が `["名前"]`（2文字）で見つかる。
  - `test_search_filters_channels_and_guild`: 指定した `channel_ids` と guild 以外のメッセージは出ない。
  - `test_search_date_range`: since と until の外は出ない。
  - `test_search_no_keywords_returns_latest_in_range`: キーワードが空なら、範囲内の新しい順に出る。
  - `test_edit_updates_search`: `upsert` で内容を変えると、古い言葉では出ず、新しい言葉で出る。
  - `test_delete_and_unregister_remove_rows`: 削除と登録解除で消える。
  - `test_jump_url`: URLの形が正しい。
- [ ] **Step 2: 失敗を確認** → **Step 3: 実装**
  - 3文字以上のキーワードは `messages_fts MATCH` を使う。言葉は `"…"` で囲み、`OR` でつなぐ。
  - 2文字以下は `content LIKE ?` を使う。
  - 並び順: 当たったキーワードの数が多い順、次に新しい順。
- [ ] **Step 4: PASS を確認**
- [ ] **Step 5: Commit**（`feat(askbot): searchable message history`）

---

### Task 9: Bot — LLMへの問い合わせとWikipedia

**Files:** Modify `bots/askbot/askbot.py`, `bots/askbot/test_askbot.py`

**Interfaces:**
- Produces:
  - `SYSTEM_PROMPT: str`（日本語・固定。llama-server の先頭キャッシュを効かせるため、最初のメッセージに置く）
    - 中身: 日本語で分かりやすく答える。資料があれば資料だけに基づき、資料にないことは「資料には書かれていません」と言う。分からないことを作らない。
  - `class LlmError(Exception)`（メッセージはユーザーに見せる日本語）
  - `def parse_sse(line: str) -> dict | None`
  - `def progress_percent(p: dict) -> int`
    - `round(100 * (processed - cache) / max(1, total - cache))`
  - `def image_part(data: bytes, content_type: str) -> dict`
    - `{"type": "image_url", "image_url": {"url": "data:<type>;base64,…"}}`
  - `def build_messages(question: str, *, reply_text: str | None = None, images: list[dict] = (), wiki: tuple[str, str, str] | None = None, history: list[Stored] | None = None) -> list[dict]`
  - `async def stream_answer(session, base_url: str, messages: list[dict], on_progress, on_text, *, wait_seconds: float = 120) -> None`
  - `WIKI_MAX_CHARS = 3000`
  - `def pick_passages(text: str, keywords: list[str], max_chars: int = WIKI_MAX_CHARS) -> str`
  - `async def wiki_lookup(session, keywords: list[str]) -> tuple[str, str, str] | None`
    - 戻り値は (タイトル, URL, 抜き出した本文)。
    - `action=query&list=search&srlimit=1` で記事を選び、`prop=extracts&explaintext=1` で本文を取る。
- 決めごと:
  - 送る内容:
    - `{"messages":…, "stream": true, "return_progress": true, "max_tokens": 1500, "temperature": 0.1, "top_k": 50, "top_p": 1.0, "min_p": 0.0, "repeat_penalty": 1.1}`
  - SSEの扱い:
    - `prompt_progress` があれば `on_progress(percent)` を呼ぶ。
    - `choices[0].delta.content` があれば `on_text(piece)` を呼ぶ。
  - 接続できないとき: 2秒おきに `/health` を確かめ、`wait_seconds` まで待つ。`on_progress(-1)` で「起動中」を知らせる。それでもだめなら `LlmError("AIモデルにつながりません…")`。
  - HTTP 400 で本文に `context` を含むとき: `LlmError("写真や資料が大きすぎて読み切れませんでした。写真を減らすか、質問を短くしてください。")`
  - WIKI_MAX_CHARS は細かさ優先で3000にした。約2400トークンで、AQUOSで読むのに約5分（推測）。冒頭の段落は必ず入れる。キーワードが当たった段落を、元の順番のまま上限まで足す。

- [ ] **Step 1: 失敗するテストを書く**
  - `test_parse_sse`:
    - `"data: {\"choices\":[{\"delta\":{\"content\":\"あ\"}}]}"` は dict になる。
    - `"data: [DONE]"` と空行は None になる。
  - `test_progress_percent`: `{"total": 1000, "cache": 200, "processed": 600}` は 50。
  - `test_build_messages_with_wiki`:
    - 先頭は system で SYSTEM_PROMPT。
    - 資料の本文とタイトルが user の内容に入る。
    - 質問が最後にある。
  - `test_build_messages_with_reply_and_image`: 返信先の文章が入り、画像部分が content のリストに入る。
  - `test_pick_passages_keeps_lead_and_hits`: 冒頭と「江戸」を含む段落は残る。関係ない段落は、上限を超えると落ちる。
  - `stream_answer` は aiohttp のテストサーバー（`aiohttp.web`、ポート0）で確かめる。
    - `test_stream_answer_streams`: progress 50 → 100 と、本文「こんにちは」が順に届く。
    - `test_stream_answer_waits_for_server`: 最初の3秒は接続を拒否し、後から起動するサーバーにつながる。`wait_seconds=10`。
    - `test_stream_answer_context_error`: 400 と `{"error":{"message":"…exceeds the available context size…"}}` で、写真の文言の `LlmError` になる。
- [ ] **Step 2: 失敗を確認** → **Step 3: 実装** → **Step 4: PASS を確認**
- [ ] **Step 5: Commit**（`feat(askbot): llama-server streaming and Wikipedia grounding`）

---

### Task 10: Bot — Discordとつなぐ

**Files:** Modify `bots/askbot/askbot.py`, `bots/askbot/test_askbot.py`

**Interfaces:**
- Consumes: Task 5〜9 の全部
- Produces:
  - `MAX_IMAGES = 2`、`MAX_IMAGE_BYTES = 8 * 1024 * 1024`、`BACKFILL_DAYS = 365`
  - `def readable_channels(guild, member, registered: set[int]) -> set[int]`
    - 登録済みのうち、`view_channel` と `read_message_history` の両方があるもの。
  - `async def collect_images(messages) -> tuple[list[dict], str | None]`
    - 戻り値は (画像部分, 断る理由)。
  - `def sources_footer(plan: Plan, wiki, rows: list[Stored]) -> str`
  - `def main() -> None`

- [ ] **Step 1: 失敗するテストを書く**（Discordのオブジェクトは小さな偽物で代用する）
  - `test_readable_channels_respects_permissions`: 閲覧権限のないチャンネルは結果に入らない。
  - `test_too_large_image_rejected`: 9MBの添付は読まず、理由「写真が大きすぎます（8MBまで）」が返る。
  - `test_only_two_images`: 3枚あっても2枚まで読み、理由に「最初の2枚だけ見ます」が入る。
  - `test_sources_footer`:
    - wiki は `📚 参考: <https://ja.wikipedia.org/wiki/…>`。
    - history は最大5件の `🔗 <jump_url>`。
- [ ] **Step 2: 失敗を確認** → **Step 3: 実装（`main()` とイベント）**
  - インテント: `message_content`、`guilds`、`messages`、`members` は不要（`message.author` が Member）。
  - `on_message`:
    - Botのメッセージは無視する。
    - 登録チャンネルのメッセージは `upsert` する。
    - Botへのメンションなら、質問として扱う:
      1. メンション部分を除いた本文と、返信先（`message.reference` → `fetch_message`）の本文と画像を集める。
      2. `plan_question` で質問の種類を決める。
      3. `message.reply("受け付けました")` を送る。
      4. `AskQueue.submit` に入れる。None なら「前の質問が終わるまで待ってね」と書き換える。
      5. 位置が1以上なら「順番待ち N番目です」にする。
    - 中身が空のメンションには、使い方の短い案内を返す。
  - ジョブ:
    - 種類ごとに資料を用意する:
      - history: `readable_channels` と `search` を使う。0件なら「見つかりませんでした」と答える。
      - wiki: `wiki_lookup` を使う。見つからなければ資料なしで答え、「Wikipediaに記事が見つかりませんでした」と添える。
    - `stream_answer` の進み具合と本文を `ReplyWriter` に渡す。
    - `finish(sources_footer(...))` で締める。
    - `LlmError` はその文言で書き換える。
  - `on_raw_message_edit`: 登録チャンネルなら `upsert` する。
  - `on_raw_message_delete`: `delete` する。
  - スラッシュコマンド（`app_commands`、`manage_channels` 権限が必要）:
    - `/記録する`:
      1. `register` する。
      2. 「記録を始めました。過去365日分を読み込みます」と返す。
      3. 裏で `channel.history(after=now-365日 か latest(), oldest_first=True)` を `upsert` する。
      4. 終わったら件数をチャンネルに送る。
    - `/記録やめる`: `unregister` して「記録をやめて、保存していた分を消しました」と返す。
  - 起動時: `tree.sync()`。`LLM_URL` がないときは、質問に「AIモデルが準備できていません（アプリの「5. AIモデル」でダウンロードしてから、Botを起動し直してください）」と返す。
  - トークンは `os.environ["TOKEN"]` から読む。ない場合は日本語のエラーを出して終了する。
  - `if __name__ == "__main__": main()`
- [ ] **Step 4: PASS を確認**、`python -c "import ast,sys; ast.parse(open('bots/askbot/askbot.py',encoding='utf-8').read())"` も通る
- [ ] **Step 5: Commit**（`feat(askbot): Discord mentions, replies, recording commands`）

---

### Task 11: CI・リリース・README

**Files:** Modify `.github/workflows/build.yml`, `.github/workflows/release.yml`, `README.md`

- [ ] **Step 1: build.yml に pytest のステップを足す**
  ```
  python3 -m venv .venv && .venv/bin/pip install "discord.py==2.7.1" pytest && .venv/bin/python -m pytest bots/askbot -q
  ```
- [ ] **Step 2: release.yml で添付する**: `gh release create … "$apk" bots/askbot/askbot.py $pre …`
- [ ] **Step 3: README に「質問Bot（askbot）の使い方」を書く**
  1. Discord Developer Portal でBotを作る。**Message Content Intent をオン**にする。
     - 招待URLのスコープ: `bot`、`applications.commands`
     - 権限: チャンネルを見る、メッセージを送る、メッセージ履歴を読む
  2. アプリの「3. ライブラリ」で `pip install discord.py` を実行する。
  3. リリースの `askbot.py` を「📄 .pyファイルを追加」で取り込み、「最初に実行」にする。
  4. 「4. 秘密の値」に `TOKEN` を入れて保存する。
  5. 「5. AIモデル」でダウンロードする（Wi-Fi・約1.25GB）。
  6. 起動する。
  7. 探したいチャンネルで `/記録する` を実行する。
  8. 使い方の例（写真への返信、知らない分野、日付で探す）。
  - 今のBotとは入れ替えになることも書く（同時には1つ）。
- [ ] **Step 4: 手元でも全部確かめる**
  - Run: `./gradlew testDebugUnitTest assembleDebug` と `python -m pytest bots/askbot -q`
  - Expected: どちらも成功
- [ ] **Step 5: Commit と push。CIの成功を確かめる**（`ci: test askbot and attach it to releases`）

---

### Task 12: 試験版を出して、実機で確かめる

**Files:** Create `docs/superpowers/notes/2026-10-29-askbot-device-check.md`

- [ ] **Step 1: リリースする**
  - タグ `v0.3.0` を付けて push する。
  - Releases に `PythonRunner-v0.3.0.apk` と `askbot.py` が並ぶことを確かめる。
  - 署名の証明書 SHA-256 が `a4559f5b…ae9b` で、versionCode が増えていることを確かめる。
- [ ] **Step 2: ユーザーに試してもらう**（AQUOS sense6。手元にあれば ZenFone 4 でも）
  - 確かめること:
    - モデルのダウンロードと、途中からの再開
    - 起動すると「AIモデル」が一緒に動くこと
    - メンションすると数秒で「受け付けました」が出ること
    - 写真への返信での質問
    - 知らない分野の質問（Wikipediaのリンクが付く）
    - `/記録する` の後、日付を指定して探せること
    - 2人で同時に聞いたときの順番待ちの表示
    - 画面を消して30分後も答えること
    - 各質問の書き始めまでの秒数
- [ ] **Step 3: 結果をメモに記録して commit する**
  - 遅すぎる・日本語が不自然・メモリ不足などがあれば、設計書9章の差し替え案や、11章（端末の切り替え）に沿って、次の計画で扱う。
