# Python Runner フェーズ1（お試し版）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 公式CPythonを同梱したAndroidアプリで、discord.pyのBotを1つ、画面オフのまま動かせることを実機（ZenFone 4 / Android 8）で確かめる「お試し版」を作り、GitHub ReleasesからAPKで配布する。

**Architecture:** python.org のAndroid版CPython（aarch64）をビルド時に取り込む。`libpython3.14.so` などは jniLibs に、標準ライブラリは zip にまとめて assets に入れる。小さなC製ランチャー `libpylauncher.so`（中身は `Py_BytesMain` を呼ぶ実行ファイル）を nativeLibraryDir に置き、Kotlin の `ProcessBuilder` からBotごとに別プロセスで起動する。フォアグラウンドサービスとウェイクロックで、画面オフでも動かし続ける。

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Android Gradle Plugin, NDK clang（ランチャーのビルド用）, CPython 3.14.9 Android, pip（wheelを同梱）, JUnit4 + kotlin.test, GitHub Actions

**Spec:** `docs/superpowers/specs/2026-10-23-android-python-runner-design.md`

### この計画の範囲
設計書の「進め方」に従い、この計画は**フェーズ1（お試し版）だけ**を扱う。画面一式（ホームC、詳細4タブ、追加画面、共有ストレージ、複数Bot、自動再起動、暗号化など）は**フェーズ2の計画**で扱う。フェーズ2の計画は、Task 8 の実機確認の結果をもとに書く。お試し版でも、`PythonRuntime`、`ManagedProcess`、`LogBuffer`、`BotService`、CI はそのままフェーズ2の土台になるように作る。

## Global Constraints

- minSdk 26（Android 8.0）、ABIは `arm64-v8a` のみ（`abiFilters += "arm64-v8a"`）。
- targetSdk / compileSdk は、実装時点の最新安定版。
- Python は公式CPythonのAndroid版 **3.14.9**: `https://www.python.org/ftp/python/3.14.9/python-3.14.9-aarch64-linux-android.tar.gz`
- applicationId: `com.pythonrunner.app`、アプリ名: `Python Runner`（仮）。
- UIの文言はすべて日本語。
- Google Playでは配布しない。GitHub Releases にAPKを置く。
- 秘密の値（トークン）は入力欄で伏せ字にする。Kotlin側のログやLogcatに出さない。
- Botは**別OSプロセス**で動かす。アプリのデータ領域にある実行ファイルを `exec` してはいけない。実行するのは nativeLibraryDir の `libpylauncher.so` だけにする（`packaging.jniLibs.useLegacyPackaging = true`）。
- discord.py の依存パッケージを純Python版で入れるため、次の環境変数を常に設定する: `AIOHTTP_NO_EXTENSIONS=1`、`YARL_NO_EXTENSIONS=1`、`MULTIDICT_NO_EXTENSIONS=1`、`FROZENLIST_NO_EXTENSIONS=1`、`PROPCACHE_NO_EXTENSIONS=1`
- 依存ライブラリやツールのバージョンは `gradle/libs.versions.toml` と `gradle.properties` に集約する。
- コミットメッセージの末尾には `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` を付ける。

## Review Focus

1. **大量・高速なログ出力**（例: printを無限ループ）: アプリが固まったりメモリがあふれたりせず、最新の2000行だけを保持する。→ Task 4 `LogBufferTest.keepsOnlyLatestLines`
2. **起動直後にエラーで終わるBot**（SyntaxError、トークン間違い）: 状態が「エラーで停止」になり、stderr の行が赤（`isError=true`）で残る。→ Task 4 `ManagedProcessTest.reportsNonZeroExitAndStderr`、`BotStatusTest.nonZeroExitIsCrashed`
3. **停止を無視するプロセス**（SIGTERMを握りつぶす）: 停止ボタンから5秒後に強制終了され、必ず止まる。→ Task 4 `ManagedProcessTest.forceKillsAfterGrace`
4. **初回の展開が途中で中断**（展開中にアプリが落ちる）: 次回起動時に展開をやり直し、壊れた標準ライブラリのまま動かない。→ Task 3 `PythonRuntimeTest.reExtractsWhenMarkerMissing`
5. **ユーザーの停止とクラッシュの区別**: 停止ボタンで止めたときは「停止中」と表示し、「エラー」とは表示しない。→ Task 4 `BotStatusTest.userStopIsStopped`

---

## File Structure

```
.gitignore
README.md                              … 日本語のインストール手順と署名鍵の注意
settings.gradle.kts
build.gradle.kts
gradle.properties                      … pythonVersion, pythonSha256, pipVersion, pipSha256
gradle/libs.versions.toml
gradle/wrapper/*                        gradlew, gradlew.bat
app/build.gradle.kts
app/python.gradle.kts                  … Python取り込み用のGradleタスク
app/src/main/AndroidManifest.xml
app/src/main/c/pylauncher.c            … Py_BytesMain を呼ぶだけのランチャー
app/src/main/java/com/pythonrunner/app/
  MainActivity.kt
  python/PythonRuntime.kt              … 展開・パス・コマンド・環境変数
  python/PipCommands.kt                … pipのコマンド組み立て
  process/LogLine.kt                   … LogLine, LogBuffer
  process/ManagedProcess.kt            … 起動・行読み取り・停止
  bot/BotStatus.kt                     … BotStatus, statusForExit()
  bot/SpikeBotStore.kt                 … お試し版の1Bot分の保存
  service/BotService.kt                … フォアグラウンドサービス＋ウェイクロック
  service/BotController.kt             … UIとサービスをつなぐシングルトン（StateFlow）
  ui/SpikeScreen.kt                    … お試し版の画面
app/src/test/java/com/pythonrunner/app/
  python/PythonRuntimeTest.kt
  python/PipCommandsTest.kt
  process/LogBufferTest.kt
  process/ManagedProcessTest.kt
  bot/BotStatusTest.kt
  bot/SpikeBotStoreTest.kt
  testutil/Shell.kt                    … ホストOSのシェルでコマンドを作るヘルパー
.github/workflows/build.yml            … push時: テスト＋debug APK
.github/workflows/release.yml          … v* タグ時: 署名済みAPKをReleasesへ
tools/make-keystore.ps1                … 署名鍵を作り、Secrets用のbase64を出力
```

---

### Task 1: 開発環境とプロジェクトの骨組み

このPCには JDK と Android SDK が無い。ローカルでテストとビルドを回せるようにしてから、空のComposeアプリを作る。

**Files:**
- Create: `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/pythonrunner/app/MainActivity.kt`, `app/src/test/java/com/pythonrunner/app/SmokeTest.kt`

**Interfaces:**
- Produces: `./gradlew testDebugUnitTest assembleDebug` が通るプロジェクト。パッケージ `com.pythonrunner.app`。

- [ ] **Step 1: JDK 17 を入れる**
  Run: `winget install --id Microsoft.OpenJDK.17 -e --accept-package-agreements --accept-source-agreements`
  Expected: `java -version` が `17.` を表示する（新しいシェルで確認する）。

- [ ] **Step 2: Android SDK を入れる**
  1. `https://developer.android.com/studio#command-tools` にある、Windows用の最新 command-line tools の zip を落とす。
  2. `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\` に展開する。
  3. `ANDROID_HOME` をユーザー環境変数に設定する。
  4. `sdkmanager --licenses` を実行する。
  5. `sdkmanager "platform-tools" "platforms;android-<最新>" "build-tools;<最新>" "ndk;<最新安定>"` を実行する。

  Expected: `%ANDROID_HOME%\ndk\<版>\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd` が存在する。

- [ ] **Step 3: Gradle wrapper を作る**
  Gradle の最新安定版の bin zip を scratchpad に落とし、プロジェクト直下で `gradle wrapper` を実行する。
  Expected: `gradlew.bat --version` が動く。

- [ ] **Step 4: 失敗するテストを書く**

```kotlin
// app/src/test/java/com/pythonrunner/app/SmokeTest.kt
class SmokeTest {
    @Test fun buildConfigHasPythonVersion() {
        assertEquals("3.14.9", BuildConfig.PYTHON_VERSION)
    }
}
```

- [ ] **Step 5: テストが失敗することを確認する**
  Run: `./gradlew testDebugUnitTest`
  Expected: FAIL（ビルドファイルがまだ無い、または `PYTHON_VERSION` が未定義）

- [ ] **Step 6: 骨組みを作る**
  - `gradle.properties`: `pythonVersion=3.14.9`
  - `app/build.gradle.kts`:
    - `namespace`/`applicationId` = `com.pythonrunner.app`、`minSdk = 26`
    - `ndk { abiFilters += "arm64-v8a" }`
    - `buildFeatures { compose = true; buildConfig = true }`
    - `buildConfigField("String", "PYTHON_VERSION", "\"${property("pythonVersion")}\"")`
    - `packaging { jniLibs { useLegacyPackaging = true } }`
  - 依存: Compose BOM、Material 3、activity-compose、lifecycle-runtime-compose、kotlinx-coroutines-android。テスト用に junit と kotlin-test。
  - `MainActivity` は「Python Runner」と表示するだけ。

  `.gitignore` に入れるもの: `.gradle/`、`build/`、`local.properties`、`.superpowers/`、`*.jks`、`keystore.b64.txt`

- [ ] **Step 7: テストとビルドが通ることを確認する**
  Run: `./gradlew testDebugUnitTest assembleDebug`
  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: git を初期化してコミットする**

```bash
git init -b main
git add -A
git commit -m "chore: Android project skeleton"
```

---

### Task 2: CPython・pip・証明書・ランチャーをAPKに取り込む

**Files:**
- Create: `app/python.gradle.kts`, `app/src/main/c/pylauncher.c`
- Modify: `app/build.gradle.kts`（`apply(from = "python.gradle.kts")`、sourceSets）、`gradle.properties`

**Interfaces:**
- Produces（APKの中身。Task 3 が前提にする）:
  - `lib/arm64-v8a/libpython3.14.so`、`lib/arm64-v8a/lib*_python.so`（OpenSSLなど）、`lib/arm64-v8a/libpylauncher.so`
  - `assets/python/stdlib.zip`（中のパスは `lib/python3.14/...`。`test/` と `__pycache__/` は除く）
  - `assets/python/cacert.pem`
  - `assets/python/pip.whl`

- [ ] **Step 1: `gradle.properties` に値を追加する**
  - `pythonSha256=`
  - `pipVersion=<PyPIの最新安定版>`
  - `pipSha256=`

  pip の値は PyPI の JSON（`https://pypi.org/pypi/pip/<ver>/json`）にある `py3-none-any` wheel の `url` と `sha256` を使う。`pythonSha256` は、tar.gz を一度落として `Get-FileHash -Algorithm SHA256` で求めた値を書く。

- [ ] **Step 2: `pylauncher.c` を書く**

```c
#include <Python.h>
int main(int argc, char **argv) { return Py_BytesMain(argc, argv); }
```

- [ ] **Step 3: `python.gradle.kts` にタスクを作る**
  すべて `preBuild` の前に実行させる。

  | タスク | すること |
  |---|---|
  | `downloadPython` | tar.gz を `build/python/` に落とし、`pythonSha256` と照合する（不一致なら失敗） |
  | `extractPython` | `tarTree` で `build/python/extracted/` に展開する |
  | `preparePythonJniLibs` | `prefix/lib/libpython*.so` と `prefix/lib/lib*_python.so` を `build/generated/pyJniLibs/arm64-v8a/` にコピーする |
  | `buildPyLauncher` | NDKの `aarch64-linux-android26-clang` で `pylauncher.c` をビルドし、`build/generated/pyJniLibs/arm64-v8a/libpylauncher.so` に出力する |
  | `preparePythonAssets` | `prefix/lib/python3.14/**` を `lib/python3.14/` 配下として zip にまとめ、`build/generated/pyAssets/python/stdlib.zip` に出力する。`https://curl.se/ca/cacert.pem` を `cacert.pem` として落とす。pip の wheel を落として `pipSha256` と照合し、`pip.whl` として置く |

  `buildPyLauncher` の補足:
  - 引数: `-fPIE -pie -I<prefix>/include/python3.14 -L<prefix>/lib -lpython3.14 -o <出力先>`
  - NDKの場所は `android.ndkDirectory` から取る。ホストのprebuiltディレクトリ（`windows-x86_64` / `linux-x86_64`）はOSで切り替える。Windowsでは `.cmd` を付ける。

  最後に、`android.sourceSets["main"]` の `jniLibs.srcDir` に `build/generated/pyJniLibs` を、`assets.srcDir` に `build/generated/pyAssets` を追加する。

- [ ] **Step 4: APKの中身を確認する**
  Run: `./gradlew assembleDebug`、続けて `jar tf app/build/outputs/apk/debug/app-debug.apk | findstr "libpython3.14.so libpylauncher.so _python.so python/stdlib.zip python/cacert.pem python/pip.whl"`
  Expected: 5つとも表示される。`lib*_python.so` も1つ以上ある。

- [ ] **Step 5: コミットする**

```bash
git add -A
git commit -m "build: bundle CPython 3.14.9, pip, CA certs and launcher"
```

---

### Task 3: PythonRuntime（展開・コマンド・環境変数）と pip コマンド

**Files:**
- Create: `app/src/main/java/com/pythonrunner/app/python/PythonRuntime.kt`, `.../python/PipCommands.kt`
- Test: `app/src/test/java/com/pythonrunner/app/python/PythonRuntimeTest.kt`, `.../python/PipCommandsTest.kt`

**Interfaces:**
- Consumes: Task 2 の assets 名（`python/stdlib.zip`、`python/cacert.pem`、`python/pip.whl`）と `BuildConfig.PYTHON_VERSION`
- Produces:

```kotlin
class PythonRuntime(
    private val baseDir: File,          // 本番: context.filesDir
    private val nativeLibDir: File,     // 本番: applicationInfo.nativeLibraryDir
    private val tmpDir: File,           // 本番: context.cacheDir
    private val version: String,        // 本番: "${BuildConfig.PYTHON_VERSION}-${BuildConfig.VERSION_CODE}"
    private val openAsset: (String) -> InputStream,
) {
    val home: File            // baseDir/python
    val executable: File      // nativeLibDir/libpylauncher.so
    val pipWheel: File        // home/pip.whl
    fun isInstalled(): Boolean          // home/.installed の中身 == version
    fun ensureInstalled()               // 未展開・版違いなら展開し直す
    fun command(vararg args: String): List<String>   // [executable.path, *args]
    fun environment(pythonPath: List<File>, userEnv: Map<String, String>): Map<String, String>
}

object PipCommands {
    fun bootstrap(rt: PythonRuntime): List<String>   // pip本体を home の site-packages に入れる
    fun install(rt: PythonRuntime, packages: List<String>, target: File): List<String>
}
```

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
// PythonRuntimeTest.kt — openAsset は小さな zip と文字列を返すフェイクを使う
@Test fun extractsStdlibAndWritesMarker()
//  stdlib.zip に "lib/python3.14/os.py" を入れたフェイクで ensureInstalled() を実行する
//  → assertTrue(File(rt.home, "lib/python3.14/os.py").exists())
//  → assertEquals("3.14.9-1", File(rt.home, ".installed").readText())
//  → File(rt.home, "cacert.pem") と rt.pipWheel が存在する

@Test fun reExtractsWhenMarkerMissing()
//  home/lib/python3.14/broken.py だけがあり .installed が無い状態で ensureInstalled() を実行する
//  → broken.py は消え、os.py がある

@Test fun skipsExtractionWhenVersionMatches()
//  1回目の ensureInstalled() の後に openAsset が呼ばれたら fail() するフェイクに替え、2回目を呼ぶ
//  → 例外が出ない

@Test fun reExtractsWhenVersionDiffers()
//  .installed = "3.14.8-1" → ensureInstalled() 後は "3.14.9-1"

@Test fun environmentHasRequiredVariables()
//  val env = rt.environment(listOf(File("/x/libs")), mapOf("TOKEN" to "abc", "PYTHONHOME" to "evil"))
//  assertEquals(rt.home.path, env["PYTHONHOME"])            // userEnv で上書きできない
//  assertEquals(nativeLibDir.path, env["LD_LIBRARY_PATH"])  // 同上
//  assertEquals("1", env["PYTHONUNBUFFERED"])
//  assertEquals(File(rt.home, "cacert.pem").path, env["SSL_CERT_FILE"])
//  assertEquals(File("/x/libs").path, env["PYTHONPATH"])   // 複数あれば ":" 区切り
//  assertEquals(tmpDir.path, env["TMPDIR"])
//  assertEquals(File(baseDir, "home").path, env["HOME"])
//  listOf("AIOHTTP","YARL","MULTIDICT","FROZENLIST","PROPCACHE").forEach { assertEquals("1", env["${it}_NO_EXTENSIONS"]) }
//  assertEquals("abc", env["TOKEN"])

@Test fun commandStartsWithLauncher()
//  assertEquals(listOf(File(nativeLibDir, "libpylauncher.so").path, "-c", "print(1)"), rt.command("-c", "print(1)"))

// PipCommandsTest.kt
@Test fun bootstrapRunsPipFromWheel()
//  assertEquals(rt.command("${rt.pipWheel.path}/pip", "install", "--no-index", "--no-deps", rt.pipWheel.path), PipCommands.bootstrap(rt))
@Test fun installTargetsBotLibs()
//  assertEquals(rt.command("-m", "pip", "install", "--upgrade", "--no-input", "--disable-pip-version-check", "--target", File("/b/libs").path, "discord.py"),
//               PipCommands.install(rt, listOf("discord.py"), File("/b/libs")))
```

- [ ] **Step 2: テストが失敗することを確認する**
  Run: `./gradlew testDebugUnitTest --tests "*PythonRuntimeTest" --tests "*PipCommandsTest"`
  Expected: FAIL（クラスが無い）

- [ ] **Step 3: `PythonRuntime` と `PipCommands` を実装する**
  展開は、`home` の隣の一時ディレクトリ（`python.tmp`）に `ZipInputStream` で展開する。その後 `cacert.pem` と `pip.whl` をコピーし、`.installed` を最後に書く。最後に古い `home` を消して rename する。こうすれば、途中で落ちてもマーカーが無いので、次回に必ずやり直せる。

- [ ] **Step 4: テストが通ることを確認する**
  Run: 同上
  Expected: PASS

- [ ] **Step 5: コミットする**

```bash
git add -A
git commit -m "feat: PythonRuntime extraction, environment and pip commands"
```

---

### Task 4: ManagedProcess、LogBuffer、BotStatus

**Files:**
- Create: `.../process/LogLine.kt`, `.../process/ManagedProcess.kt`, `.../bot/BotStatus.kt`
- Test: `.../process/LogBufferTest.kt`, `.../process/ManagedProcessTest.kt`, `.../bot/BotStatusTest.kt`, `.../testutil/Shell.kt`

**Interfaces:**
- Produces:

```kotlin
data class LogLine(val timeMillis: Long, val text: String, val isError: Boolean)

class LogBuffer(private val capacity: Int = 2000) {
    fun add(line: LogLine)
    fun snapshot(): List<LogLine>        // 古い順
}

class ManagedProcess(
    private val command: List<String>,
    private val env: Map<String, String>,   // 親の環境変数に上書きで足す
    private val workDir: File,
    private val onLine: (LogLine) -> Unit,  // stdout は isError=false、stderr は true
    private val onExit: (exitCode: Int) -> Unit,
) {
    val isRunning: Boolean
    fun start()                               // 起動に失敗したら IOException
    fun stop(graceMillis: Long = 5000)        // destroy() し、grace を過ぎても生きていれば destroyForcibly()
}

enum class BotStatus { STOPPED, STARTING, RUNNING, CRASHED }
fun statusForExit(exitCode: Int, stoppedByUser: Boolean): BotStatus
//  stoppedByUser なら STOPPED。exitCode==0 なら STOPPED。それ以外は CRASHED
```

- `testutil/Shell.kt`: `fun shell(script: String): List<String>`。Windows なら `cmd /c`、それ以外は `sh -c`。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
// LogBufferTest
@Test fun keepsOnlyLatestLines() {
    val b = LogBuffer(capacity = 3)
    (1..5).forEach { b.add(LogLine(it.toLong(), "l$it", false)) }
    assertEquals(listOf("l3", "l4", "l5"), b.snapshot().map { it.text })
}

// ManagedProcessTest（終了は CountDownLatch で最大10秒待つ）
@Test fun capturesStdoutLinesInOrder()
//  shell("echo a&& echo b")（Windows）/ shell("echo a; echo b") → 行が ["a","b"]、すべて isError=false、exitCode 0
@Test fun reportsNonZeroExitAndStderr()
//  shell("echo boom 1>&2 && exit 3") → "boom" が isError=true で届き、exitCode 3
@Test fun passesEnvironmentAndWorkDir()
//  env=mapOf("PR_TEST" to "ok")、workDir=一時ディレクトリ
//  → POSIXは `echo $PR_TEST; pwd`、Windowsは `echo %PR_TEST%& cd`
//  → 出力に "ok" と workDir のパスが含まれる
@Test fun forceKillsAfterGrace()
//  assumeFalse(isWindows)
//  shell("trap '' TERM; sleep 30") → stop(graceMillis = 500) は 3 秒以内に戻り、isRunning == false
@Test fun stopIsSafeWhenNotRunning()
//  start 前に stop() を呼んでも例外が出ない

// BotStatusTest
@Test fun nonZeroExitIsCrashed()  { assertEquals(BotStatus.CRASHED, statusForExit(1, stoppedByUser = false)) }
@Test fun userStopIsStopped()     { assertEquals(BotStatus.STOPPED, statusForExit(143, stoppedByUser = true)) }
@Test fun cleanExitIsStopped()    { assertEquals(BotStatus.STOPPED, statusForExit(0, stoppedByUser = false)) }
```

- [ ] **Step 2: テストが失敗することを確認する**
  Run: `./gradlew testDebugUnitTest --tests "*LogBufferTest" --tests "*ManagedProcessTest" --tests "*BotStatusTest"`
  Expected: FAIL

- [ ] **Step 3: 実装する**
  - `ManagedProcess`: `ProcessBuilder(command).directory(workDir)`。`environment().putAll(env)` を使い、stderr はマージしない。stdout と stderr はそれぞれ UTF-8 の `bufferedReader` で読み、専用スレッドで1行ずつ `onLine` に渡す。`waitFor()` するスレッドが、両方の読み取りスレッドの終了を待ってから `onExit` を呼ぶ。
  - `LogBuffer`: `ArrayDeque` を `synchronized` で守る。

- [ ] **Step 4: テストが通ることを確認する**
  Run: 同上
  Expected: PASS

- [ ] **Step 5: コミットする**

```bash
git add -A
git commit -m "feat: ManagedProcess, LogBuffer and BotStatus"
```

---

### Task 5: SpikeBotStore、BotService（フォアグラウンドサービス）、BotController

`SpikeBotStore` はユニットテストで確かめる。サービスは Android 実機でしか動きを確かめられないので、ビルドが通ることと Task 8 の実機確認で見る。

**Files:**
- Create: `.../bot/SpikeBotStore.kt`, `.../service/BotService.kt`, `.../service/BotController.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `.../bot/SpikeBotStoreTest.kt`

**Interfaces:**
- Consumes: `PythonRuntime`、`ManagedProcess`、`LogBuffer`、`LogLine`、`BotStatus`、`statusForExit`
- Produces:

```kotlin
class SpikeBotStore(private val baseDir: File, private val prefs: KeyValue) {
    val botDir: File            // baseDir/bots/spike
    val libsDir: File           // baseDir/bots/spike-libs
    var mainFile: String?       // botDir からの相対パス
    var envVars: Map<String, String>
    var wasRunning: Boolean
    fun importPy(fileName: String, input: InputStream)   // botDir に保存し、mainFile にする
}
interface KeyValue { fun get(key: String): String?; fun put(key: String, value: String?) }
// 本番は SharedPreferences のラッパー、テストは HashMap 実装

object BotController {
    val status: StateFlow<BotStatus>
    val startedAtMillis: StateFlow<Long?>
    val logs: StateFlow<List<LogLine>>        // LogBuffer の snapshot。行が増えるたびに更新する（間引きは最大 4回/秒）
    fun start(context: Context)               // BotService に ACTION_START を送る
    fun stop(context: Context)                // BotService に ACTION_STOP を送る
    fun runOneShot(context: Context, args: List<String>, onLine: (LogLine) -> Unit, onExit: (Int) -> Unit)
    //  pip や動作確認用。サービスを使わず ManagedProcess で一度だけ実行する
}
```

- [ ] **Step 1: SpikeBotStore の失敗するテストを書く**

```kotlin
@Test fun importPySavesFileAndSetsMain()
//  importPy("bot.py", "print(1)".byteInputStream())
//  → File(botDir, "bot.py").readText() == "print(1)"、mainFile == "bot.py"
@Test fun envVarsRoundTrip()
//  envVars = mapOf("TOKEN" to "a=b;c", "X" to "")
//  → 同じ KeyValue で作り直した store から同じ Map が返る（値に = や ; を含んでも壊れない）
@Test fun importPyRejectsPathTraversal()
//  importPy("../evil.py", …) → IllegalArgumentException。botDir の外にファイルができない
```

  Run: `./gradlew testDebugUnitTest --tests "*SpikeBotStoreTest"`
  Expected: FAIL

- [ ] **Step 2: SpikeBotStore を実装し、テストを通す**
  `envVars` の保存には `org.json` を使わない（Androidのスタブなので、JVMのユニットテストでは動かない）。`kotlinx.serialization` を使うか、手書きのエスケープで保存する。
  Run: 同上
  Expected: PASS

- [ ] **Step 3: Manifest に追加する**
  - 権限: `INTERNET`、`WAKE_LOCK`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  - `<service android:name=".service.BotService" android:foregroundServiceType="specialUse" android:exported="false">`
  - `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="ユーザーのPythonスクリプトを常時実行する"/>`

- [ ] **Step 4: `BotService` と `BotController` を実装する**
  - `ACTION_START`:
    1. 通知チャンネル `bots` を作る。
    2. 常駐通知「Python Runner：Botが稼働中」を出し、`startForeground` する（API 29+ は `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`）。
    3. `PARTIAL_WAKE_LOCK` を取る（タグ `PythonRunner:bot`）。
    4. `runtime.ensureInstalled()` を実行する。
    5. `SpikeBotStore` から Bot のディレクトリ、起動ファイル、環境変数を読み、`ManagedProcess(runtime.command(mainFile), runtime.environment(listOf(libsDir), userEnv), botDir, …)` を起動する。
    6. ステータスを `RUNNING` にする。
  - `onExit`: `statusForExit(code, stoppedByUser)` を反映し、ウェイクロックを放してから `stopForeground` と `stopSelf` をする。CRASHED のときは、通知チャンネル `alerts` に「Botがエラーで停止しました（終了コード N）」を出す。
  - `ACTION_STOP`: `stoppedByUser = true` にして `process.stop()` を呼ぶ。
  - `onStartCommand` は `START_STICKY` を返す。`intent == null`（システムによる再起動）のときは、`SpikeBotStore.wasRunning` が true なら起動し直す。`wasRunning` は、ACTION_START で true、ユーザー停止で false にする。

- [ ] **Step 5: ビルドが通ることを確認する**
  Run: `./gradlew assembleDebug testDebugUnitTest`
  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: コミットする**

```bash
git add -A
git commit -m "feat: SpikeBotStore and foreground BotService with wake lock"
```

---

### Task 6: お試し版の画面

1画面の縦スクロールで、上から順に操作すればBotが動く構成にする。文言は日本語にする。

**Files:**
- Create: `.../ui/SpikeScreen.kt`
- Modify: `MainActivity.kt`

**Interfaces:**
- Consumes: `BotController`（status、startedAtMillis、logs、start、stop、runOneShot）、`SpikeBotStore`、`PythonRuntime`、`PipCommands`

- [ ] **Step 1: `SpikeScreen` を作る**
  上から順に、次のセクションを並べる。

  | セクション | 中身 |
  |---|---|
  | 1. Pythonの準備 | 「Pythonを準備」ボタン。`ensureInstalled()` の後に `PipCommands.bootstrap` を実行する。続けて動作確認として `-c "import sys, ssl, sqlite3, ctypes; print(sys.version); print(ssl.OPENSSL_VERSION)"` を実行し、出力を表示する。完了したら「✓ 準備完了」と出す |
  | 2. Botのファイル | 「.pyファイルを選ぶ」ボタン（`ActivityResultContracts.OpenDocument`、MIMEは `*/*`）。選んだファイル名を「▶ 最初に実行: bot.py」と表示する |
  | 3. ライブラリ | パッケージ名の入力欄（初期値 `discord.py`）と「インストール」ボタン。`PipCommands.install` の出力をそのまま流す |
  | 4. 秘密の値 | 名前と値の入力行（値は `PasswordVisualTransformation`）、「＋追加」ボタン、保存ボタン |
  | 5. 実行 | 状態（点の色＋「稼働中 / 停止中 / エラーで停止」）、稼働時間、「起動」または「停止」ボタン |
  | 6. ログ | 黒背景・等幅フォントで表示する。stderr の行は赤。新しい行で自動スクロールし、「コピー」ボタンを付ける |
  | 7. 端末の設定 | 「電池の最適化を除外する」ボタン（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。「ASUSの自動起動マネージャーで Python Runner を許可してください」という案内文 |

  - Android 13 以上では、起動ボタンの初回に通知の権限を求める。
  - pip と動作確認の実行中は、ほかのボタンを無効にする。

- [ ] **Step 2: ビルドとテストが通ることを確認する**
  Run: `./gradlew assembleDebug testDebugUnitTest`
  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: コミットする**

```bash
git add -A
git commit -m "feat: spike screen to run one bot"
```

---

### Task 7: GitHub Actions、リリース署名、README

ユーザーが作ったGitHubリポジトリのURLが必要になる。まだ無ければここで依頼する。

**Files:**
- Create: `.github/workflows/build.yml`, `.github/workflows/release.yml`, `tools/make-keystore.ps1`, `README.md`
- Modify: `app/build.gradle.kts`（release の signingConfig）

**Interfaces:**
- Produces:
  - `v*` タグを push すると、Releases に `PythonRunner-<tag>.apk` が付く。
  - Secrets の名前は `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` の4つ。

- [ ] **Step 1: 署名設定を書く**
  `signingConfigs.release` は、環境変数 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` から読む。
  `KEYSTORE_FILE` が無いとき（ローカル）は、release に署名設定を付けない。こうすれば `assembleDebug` は影響を受けない。

- [ ] **Step 2: `tools/make-keystore.ps1` を書く**
  1. `keytool -genkeypair -v -keystore pythonrunner.jks -alias pythonrunner -keyalg RSA -keysize 4096 -validity 36500` を、ユーザーが入力したパスワードで実行する。
  2. `keystore.b64.txt` に base64 を出力する。
  3. 最後に「この .jks とパスワードは必ずバックアップしてください。なくすとアップデートできなくなります」と表示する。

  `.jks` と `keystore.b64.txt` は .gitignore 済み（Task 1）。

- [ ] **Step 3: `build.yml` を書く**
  - トリガー: `push` と `pull_request`、runner は `ubuntu-latest`
  - 手順:
    1. `actions/checkout`
    2. `actions/setup-java`（temurin 17）
    3. `android-actions/setup-android`
    4. `sdkmanager "ndk;<Task 1 と同じ版>"`
    5. `./gradlew testDebugUnitTest assembleDebug`
    6. `actions/upload-artifact` で debug APK を上げる

- [ ] **Step 4: `release.yml` を書く**
  - トリガー: `push: tags: ["v*"]`
  - 手順:
    1. build.yml と同じ準備をする。
    2. `KEYSTORE_BASE64` を `$RUNNER_TEMP/release.jks` にデコードする。
    3. `./gradlew assembleRelease` を実行する（環境変数は Secrets から渡す）。
    4. APK を `PythonRunner-${GITHUB_REF_NAME}.apk` にリネームする。
    5. `gh release create "$GITHUB_REF_NAME" <apk> --title "$GITHUB_REF_NAME" --notes "…"` で Releases に置く。権限は `permissions: contents: write`。

- [ ] **Step 5: `README.md` を日本語で書く**
  内容:
  1. このアプリについて
  2. インストール手順
     - Releases の .apk をスマホのブラウザで落とす。
     - 「提供元不明のアプリ」を許可する（Android 8 では、ブラウザごとに許可する）。
     - インストールする。
  3. お試し版の使い方（Task 6 の画面の順番どおり）
  4. 画面オフで動かすための設定（電池の最適化、ASUSの自動起動マネージャー）
  5. 開発者向け: 署名鍵の作り方と Secrets の登録手順、バックアップの注意

- [ ] **Step 6: コミットして GitHub に push し、CI が通ることを確認する**
  1. `git add -A && git commit -m "ci: build and signed release workflows, Japanese README"`
  2. `git remote add origin <ユーザーのURL>`
  3. `git push -u origin main`

  Expected: Actions の build が緑になる。

- [ ] **Step 7: ユーザーに作業してもらう**
  ユーザーが `tools/make-keystore.ps1` を実行し、4つの Secrets をGitHubのWebで登録する。署名鍵とパスワードは、ユーザー本人が扱う。

- [ ] **Step 8: リリースする**
  Run: `git tag v0.1.0 && git push origin v0.1.0`
  Expected: Releases に `PythonRunner-v0.1.0.apk` が出る。

---

### Task 8: 実機での確認（ユーザーが ZenFone 4 で行う）

コードは書かない。結果を `docs/superpowers/notes/2026-xx-xx-spike-results.md` に記録する。このファイルがフェーズ2の計画の材料になる。

- [ ] **Step 1:** Releases から v0.1.0 をインストールし、「Pythonを準備」を押す。
  Expected: Python 3.14.9 と OpenSSL のバージョンが表示される。
- [ ] **Step 2:** `discord.py` をインストールする。
  Expected: エラーなく完了する。失敗した場合は、ログ全文をコピーして記録する（どの依存がビルドに失敗したか）。
- [ ] **Step 3:** 簡単なテスト用Botの .py を選び、`TOKEN` を秘密の値に入れて起動する。
  Expected: Discord上でBotがオンラインになり、コマンドに反応する。
- [ ] **Step 4:** 電池の最適化を除外し、ASUSの自動起動マネージャーで許可する。そのうえで画面を消し、1時間、続けて24時間放置する。
  Expected: Botが反応し続ける。止まった場合は、何時間後かと、通知・ログの内容を記録する。
- [ ] **Step 5:** 停止ボタンを押す。
  Expected: 「停止中」になり、エラー通知は出ない。
- [ ] **Step 6:** 結果を記録してコミットし、フェーズ2の計画づくりに進む。
