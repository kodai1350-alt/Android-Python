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
        URI(url.get()).toURL().openStream().use { input ->
            part.outputStream().use { input.copyTo(it) }
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
