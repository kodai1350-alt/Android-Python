package com.pythonrunner.app.python

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * The bundled Node.js (SPIKE): the node executable ships as lib/libnodejs.so (tools/node_bundle.py), npm
 * as an asset that is extracted once. bin/node is a link to the executable so that programs looking for
 * "node" on PATH (shutil.which, a bot's Start.py) find it.
 */
class NodeRuntime(
    private val baseDir: File,
    private val nativeLibDir: File,
    private val tmpDir: File,
    private val version: String,
    private val certFile: File,
    private val openAsset: (String) -> InputStream,
) {
    val home: File = File(baseDir, "node")
    val executable: File = File(nativeLibDir, "libnodejs.so")
    val binDir: File = File(home, "bin")
    val npmCli: File = File(home, "npm/bin/npm-cli.js")
    private val marker = File(home, ".installed")

    fun isBundled(): Boolean = executable.isFile

    @Synchronized
    fun ensureInstalled() {
        File(baseDir, "home").mkdirs()
        if (marker.isFile && marker.readText() == version && File(binDir, "node").exists()) return
        val staging = File(baseDir, "node.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        openAsset("node/npm.zip").use { unzip(it, staging) }
        val bin = File(staging, "bin").apply { mkdirs() }
        Files.createSymbolicLink(File(bin, "node").toPath(), executable.toPath())
        File(staging, marker.name).writeText(version)
        home.deleteRecursively()
        if (!staging.renameTo(home)) throw IOException("Could not move $staging to $home")
    }

    fun command(vararg args: String): List<String> = listOf(executable.path, *args)

    fun npm(vararg args: String): List<String> = command(npmCli.path, *args)

    /** Termux's build looks for certificates, temp files and HOME under Termux's own folders: point it at ours. */
    fun environment(): Map<String, String> = mapOf(
        "PATH" to binDir.path + ":" + (System.getenv("PATH") ?: "/system/bin"),
        "LD_LIBRARY_PATH" to nativeLibDir.path,
        "SSL_CERT_FILE" to certFile.path,
        "NODE_EXTRA_CA_CERTS" to certFile.path,
        "TMPDIR" to tmpDir.path,
        "HOME" to File(baseDir, "home").path,
    )

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
}
