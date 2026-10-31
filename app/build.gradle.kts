import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.SourceDirectories
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pythonrunner.app"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.pythonrunner.app"
        minSdk = 26
        targetSdk = 36
        // Release CI derives these from the tag and run number; the Python marker depends on versionCode.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0-dev"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "PYTHON_VERSION", "\"${property("pythonVersion")}\"")
    }

    // Release signing comes from CI secrets; without KEYSTORE_FILE the release build stays unsigned.
    System.getenv("KEYSTORE_FILE")?.let { keystore ->
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

// ---------------------------------------------------------------------------
// Python: bundle the official CPython Android build, pip, CA certificates and
// the pylauncher executable into the APK.
// ---------------------------------------------------------------------------

val pythonVersion = property("pythonVersion") as String
val pyMinorVersion = pythonVersion.split(".").take(2).joinToString(".")
val pythonSha256 = property("pythonSha256") as String
val pythonDir = layout.buildDirectory.dir("python")
val pythonPrefix = layout.buildDirectory.dir("python/extracted/prefix")

abstract class DownloadFile : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input @get:Optional abstract val sha256: Property<String>
    @get:OutputFile abstract val dest: RegularFileProperty

    @TaskAction
    fun run() {
        val file = dest.get().asFile
        file.parentFile.mkdirs()
        val part = File(file.path + ".part")
        // CI downloads these on every run: a network blip must not fail the build.
        for (attempt in 1..3) {
            try {
                val connection = URI(url.get()).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                }
                connection.getInputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                break
            } catch (e: java.io.IOException) {
                if (attempt == 3) throw e
                logger.warn("Download of ${url.get()} failed (${e.message}); trying again")
                Thread.sleep(5_000L * attempt)
            }
        }
        sha256.orNull?.let { expected ->
            val actual = MessageDigest.getInstance("SHA-256").digest(part.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                part.delete()
                throw GradleException("SHA-256 mismatch for ${url.get()}: got $actual")
            }
        }
        part.copyTo(file, overwrite = true)
        part.delete()
    }
}

abstract class BuildLauncher : DefaultTask() {
    @get:InputFile abstract val source: RegularFileProperty
    @get:InputDirectory abstract val prefix: DirectoryProperty
    @get:Input abstract val clang: Property<String>
    @get:Input abstract val pyMinor: Property<String>
    @get:OutputFile abstract val output: RegularFileProperty
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val prefixDir = prefix.get().asFile
        execOps.exec {
            commandLine(
                clang.get(), "-fPIE", "-pie", "-O2",
                "-I${prefixDir}/include/python${pyMinor.get()}",
                source.get().asFile.path,
                "-L${prefixDir}/lib", "-lpython${pyMinor.get()}",
                "-o", output.get().asFile.path,
            )
        }
    }
}

/**
 * Builds llama-server for Android 8 arm64 (static, stripped). Skips the build when the output
 * already carries this version's stamp, so a CI cache of build/llama avoids a 10-minute rebuild.
 */
abstract class BuildLlamaServer : DefaultTask() {
    @get:InputDirectory abstract val source: DirectoryProperty
    @get:Input abstract val cmake: Property<String>
    @get:Input abstract val ndkDir: Property<String>
    @get:Input abstract val stripTool: Property<String>
    @get:Input abstract val version: Property<String>
    @get:Internal abstract val buildDir: DirectoryProperty
    @get:OutputFile abstract val output: RegularFileProperty
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val out = output.get().asFile
        val stamp = File(out.path + ".version")
        if (out.isFile && stamp.isFile && stamp.readText() == version.get()) return
        val cmakeExe = File(cmake.get())
        val ninja = File(cmakeExe.parentFile, if (cmakeExe.name.endsWith(".exe")) "ninja.exe" else "ninja")
        val work = buildDir.get().asFile
        execOps.exec {
            commandLine(
                cmakeExe.path, "-S", source.get().asFile.path, "-B", work.path, "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=${ninja.path}",
                "-DCMAKE_TOOLCHAIN_FILE=${ndkDir.get()}/build/cmake/android.toolchain.cmake",
                "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
                "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF",
                "-DGGML_NATIVE=OFF", "-DGGML_CPU_ARM_ARCH=armv8-a", "-DGGML_OPENMP=OFF",
                "-DLLAMA_OPENSSL=OFF", "-DLLAMA_BUILD_TESTS=OFF", "-DLLAMA_BUILD_EXAMPLES=OFF",
                "-DLLAMA_BUILD_UI=OFF", "-DLLAMA_USE_PREBUILT_UI=OFF", "-DLLAMA_BUILD_APP=OFF",
            )
        }
        execOps.exec { commandLine(cmakeExe.path, "--build", work.path, "--target", "llama-server", "-j") }
        out.parentFile.mkdirs()
        execOps.exec { commandLine(stripTool.get(), "-o", out.path, File(work, "bin/llama-server").path) }
        stamp.writeText(version.get())
    }
}

abstract class GenerateTask : Sync() {
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
}

val downloadPython = tasks.register<DownloadFile>("downloadPython") {
    url.set("https://www.python.org/ftp/python/$pythonVersion/python-$pythonVersion-aarch64-linux-android.tar.gz")
    sha256.set(pythonSha256)
    dest.set(pythonDir.map { it.file("python-$pythonVersion-aarch64-linux-android.tar.gz") })
}

val downloadCacert = tasks.register<DownloadFile>("downloadCacert") {
    url.set("https://curl.se/ca/cacert.pem")
    dest.set(pythonDir.map { it.file("cacert.pem") })
}

val extractPython = tasks.register<Sync>("extractPython") {
    from(downloadPython.map { tarTree(it.dest.get().asFile) })
    include("prefix/**")
    into(pythonDir.map { it.dir("extracted") })
}

val buildPyLauncher = tasks.register<BuildLauncher>("buildPyLauncher") {
    dependsOn(extractPython)
    source.set(layout.projectDirectory.file("src/main/c/pylauncher.c"))
    prefix.set(pythonPrefix)
    pyMinor.set(pyMinorVersion)
    val os = System.getProperty("os.name").lowercase()
    val host = when {
        os.startsWith("win") -> "windows-x86_64"
        os.contains("mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val suffix = if (os.startsWith("win")) ".cmd" else ""
    clang.set(androidComponents.sdkComponents.ndkDirectory.map {
        it.file("toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android26-clang$suffix").asFile.path
    })
    output.set(pythonDir.map { it.file("launcher/libpylauncher.so") })
}

val zipStdlib = tasks.register<Zip>("zipStdlib") {
    dependsOn(extractPython)
    from(pythonPrefix.map { it.dir("lib/python$pyMinorVersion") }) {
        into("lib/python$pyMinorVersion")
        exclude(
            "test/**", "**/__pycache__/**", "idlelib/**", "tkinter/**", "turtledemo/**",
            "lib-dynload/*_test*", "lib-dynload/xxlimited*",
        )
    }
    destinationDirectory.set(pythonDir)
    archiveFileName.set("stdlib.zip")
}

// Stub wheels for packages that cannot be built on the phone (see PythonRuntime.BUNDLED_WHEELS).
val buildStubWheels = tasks.register<Zip>("buildStubWheels") {
    from(layout.projectDirectory.dir("src/main/python/stubs/audioop_lts"))
    destinationDirectory.set(pythonDir.map { it.dir("wheels") })
    archiveFileName.set("audioop_lts-0.2.2-py3-none-any.whl")
}

// ---------------------------------------------------------------------------
// LLM: llama-server built from source, bundled as an executable in jniLibs.
// ---------------------------------------------------------------------------

val llamaCppVersion = property("llamaCppVersion") as String
val llamaCppSha256 = property("llamaCppSha256") as String
val cmakeVersion = property("cmakeVersion") as String
val llamaDir = layout.buildDirectory.dir("llama")

val downloadLlamaSource = tasks.register<DownloadFile>("downloadLlamaSource") {
    url.set("https://github.com/ggml-org/llama.cpp/archive/refs/tags/$llamaCppVersion.tar.gz")
    sha256.set(llamaCppSha256)
    dest.set(llamaDir.map { it.file("llama.cpp-$llamaCppVersion.tar.gz") })
}

val extractLlamaSource = tasks.register<Sync>("extractLlamaSource") {
    from(downloadLlamaSource.map { tarTree(it.dest.get().asFile) })
    into(llamaDir.map { it.dir("src") })
}

val buildLlamaServer = tasks.register<BuildLlamaServer>("buildLlamaServer") {
    dependsOn(extractLlamaSource)
    source.set(llamaDir.map { it.dir("src/llama.cpp-$llamaCppVersion") })
    val os = System.getProperty("os.name").lowercase()
    val host = when {
        os.startsWith("win") -> "windows-x86_64"
        os.contains("mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val exe = if (os.startsWith("win")) ".exe" else ""
    cmake.set(androidComponents.sdkComponents.sdkDirectory.map { it.file("cmake/$cmakeVersion/bin/cmake$exe").asFile.path })
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.path })
    stripTool.set(androidComponents.sdkComponents.ndkDirectory.map {
        it.file("toolchains/llvm/prebuilt/$host/bin/llvm-strip$exe").asFile.path
    })
    version.set(llamaCppVersion)
    buildDir.set(llamaDir.map { it.dir("cmake") })
    output.set(llamaDir.map { it.file("libllama_server.so") })
}

fun generateTask(
    variant: ApplicationVariant, directories: SourceDirectories,
    configure: GenerateTask.() -> Unit,
) {
    val taskName = "generate" + listOf(variant.name, "Python", directories.name)
        .joinToString("") { it.replaceFirstChar(Char::uppercase) }
    directories.addGeneratedSourceDirectory(
        tasks.register<GenerateTask>(taskName) {
            into(outputDir)
            configure()
        },
        GenerateTask::outputDir,
    )
}

androidComponents.onVariants { variant ->
    generateTask(variant, variant.sources.jniLibs!!) {
        into("arm64-v8a") {
            from(pythonPrefix.map { it.dir("lib") }) {
                include("libpython$pyMinorVersion.so", "lib*_python.so")
            }
            from(buildPyLauncher)
            from(buildLlamaServer)
        }
        dependsOn(extractPython)
    }
    generateTask(variant, variant.sources.assets!!) {
        into("python") {
            from(zipStdlib)
            from(downloadCacert)
            into("wheels") { from(buildStubWheels) }
        }
        dependsOn(extractPython)
    }
}
