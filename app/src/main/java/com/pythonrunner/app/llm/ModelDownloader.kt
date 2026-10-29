package com.pythonrunner.app.llm

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Downloads the model files into [dir]. An interrupted download continues from its ".part" file;
 * a file only counts once its SHA-256 matched (recorded in "<name>.ok").
 */
class ModelDownloader(
    val dir: File,
    private val files: List<ModelFile> = LlmModel.FILES,
    private val baseUrl: String = LlmModel.BASE_URL,
    private val chunkBytes: Int = 1024 * 1024,
) {
    class Cancelled : IOException("cancelled")

    fun file(f: ModelFile): File = File(dir, f.name)

    private fun part(f: ModelFile) = File(dir, f.name + ".part")
    private fun ok(f: ModelFile) = File(dir, f.name + ".ok")

    private fun isReady(f: ModelFile): Boolean =
        file(f).length() == f.size && ok(f).isFile && ok(f).readText() == f.sha256

    fun isReady(): Boolean = files.all(::isReady)

    /** Blocks until every file is downloaded and verified; call it off the main thread. */
    fun download(onProgress: (done: Long, total: Long) -> Unit, isCancelled: () -> Boolean) {
        dir.mkdirs()
        val total = files.sumOf { it.size }
        var before = 0L
        for (f in files) {
            if (!isReady(f)) fetch(f, onProgress = { done -> onProgress(before + done, total) }, isCancelled = isCancelled)
            before += f.size
            onProgress(before, total)
        }
    }

    fun delete() {
        for (f in files) listOf(file(f), part(f), ok(f)).forEach { it.delete() }
    }

    private fun fetch(f: ModelFile, onProgress: (Long) -> Unit, isCancelled: () -> Boolean) {
        val part = part(f)
        val digest = MessageDigest.getInstance("SHA-256")
        var have = if (part.isFile) part.length() else 0L
        if (have > f.size) {
            part.delete()
            have = 0
        }
        // Re-hash what is already there so the final check covers the whole file.
        if (have > 0) {
            part.inputStream().use { input ->
                val buf = ByteArray(chunkBytes)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
        }

        val conn = URI(baseUrl + f.name).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            // The server ignored the range: start over.
            if (have > 0 && code != 206) {
                have = 0
                digest.reset()
            }
            FileOutputStream(part, have > 0).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(chunkBytes)
                    while (true) {
                        if (isCancelled()) throw Cancelled()
                        val n = input.readNBytes(buf, 0, buf.size)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        have += n
                        onProgress(have)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (have != f.size || actual != f.sha256) {
            part.delete()
            throw IOException("${f.name} が壊れています（サイズかハッシュが違います）")
        }
        file(f).delete()
        if (!part.renameTo(file(f))) throw IOException("Could not rename ${part.name}")
        ok(f).writeText(f.sha256)
    }
}
