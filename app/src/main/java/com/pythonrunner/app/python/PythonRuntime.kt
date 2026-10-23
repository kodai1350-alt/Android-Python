package com.pythonrunner.app.python

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The bundled CPython: extracts the standard library from the APK assets and
 * builds the command line and environment for running the launcher.
 */
class PythonRuntime(
    private val baseDir: File,
    private val nativeLibDir: File,
    private val tmpDir: File,
    private val version: String,
    private val openAsset: (String) -> InputStream,
) {
    val home: File = File(baseDir, "python")
    val executable: File = File(nativeLibDir, "libpylauncher.so")
    /** Local wheels pip may use via --find-links (stubs for packages that cannot be built on Android). */
    val wheelsDir: File = File(home, "wheels")

    private val marker = File(home, ".installed")

    fun isInstalled(): Boolean = marker.isFile && marker.readText() == version

    /** True once pip has been bootstrapped into the current extraction (re-extraction removes it). */
    fun isPipInstalled(): Boolean =
        isInstalled() && File(home, "lib").listFiles()?.any { File(it, "site-packages/pip").isDirectory } == true

    /** Extracts into a temporary directory and writes the marker last, so an interrupted run is redone. */
    @Synchronized
    fun ensureInstalled() {
        if (isInstalled()) return
        val staging = File(baseDir, "python.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        openAsset("python/stdlib.zip").use { unzip(it, staging) }
        openAsset("python/cacert.pem").use { input -> File(staging, "cacert.pem").outputStream().use { input.copyTo(it) } }
        val stagingWheels = File(staging, wheelsDir.name).apply { mkdirs() }
        for (name in BUNDLED_WHEELS) {
            openAsset("python/wheels/$name").use { input -> File(stagingWheels, name).outputStream().use { input.copyTo(it) } }
        }
        File(staging, marker.name).writeText(version)
        home.deleteRecursively()
        if (!staging.renameTo(home)) throw IOException("Could not move $staging to $home")
    }

    fun command(vararg args: String): List<String> = listOf(executable.path, *args)

    fun environment(pythonPath: List<File>, userEnv: Map<String, String>): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env.putAll(userEnv)
        env["PYTHONHOME"] = home.path
        env["LD_LIBRARY_PATH"] = nativeLibDir.path
        env["PYTHONUNBUFFERED"] = "1"
        env["SSL_CERT_FILE"] = File(home, "cacert.pem").path
        env["PYTHONPATH"] = pythonPath.joinToString(":") { it.path }
        env["TMPDIR"] = tmpDir.path
        env["HOME"] = File(baseDir, "home").path
        for (name in NO_EXTENSIONS) env["${name}_NO_EXTENSIONS"] = "1"
        return env
    }

    private fun unzip(input: InputStream, dest: File) {
        val root = dest.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(root)) throw IOException("Bad zip entry ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    internal companion object {
        /**
         * discord.py requires audioop-lts on Python 3.13+, which has no Android wheel. Its only use is
         * voice, which is out of scope, so a stub stands in for it.
         */
        val BUNDLED_WHEELS = listOf("audioop_lts-0.2.2-py3-none-any.whl")

        // discord.py's dependencies fall back to pure Python when these are set.
        val NO_EXTENSIONS = listOf("AIOHTTP", "YARL", "MULTIDICT", "FROZENLIST", "PROPCACHE")
    }
}
