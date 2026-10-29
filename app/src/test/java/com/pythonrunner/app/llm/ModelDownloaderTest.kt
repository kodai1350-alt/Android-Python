package com.pythonrunner.app.llm

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelDownloaderTest {
    private val dir: File = Files.createTempDirectory("models").toFile()
    private val big = ByteArray(3000) { (it % 251).toByte() }
    private val small = ByteArray(500) { (it % 13).toByte() }
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        for ((name, body) in listOf("big.gguf" to big, "small.gguf" to small)) {
            createContext("/$name") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                ranges += range
                val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                if (from >= body.size) {
                    // Like Hugging Face: a range starting at the end is not satisfiable.
                    ex.sendResponseHeaders(416, -1)
                    ex.close()
                    return@createContext
                }
                val part = body.copyOfRange(from, body.size)
                ex.sendResponseHeaders(if (range != null) 206 else 200, part.size.toLong())
                ex.responseBody.use { it.write(part) }
            }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}/"

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val files = listOf(ModelFile("big.gguf", 3000, sha(big)), ModelFile("small.gguf", 500, sha(small)))

    @AfterTest fun stop() = server.stop(0)

    @Test fun downloadsAndVerifies() {
        val d = ModelDownloader(dir, files, base)
        var last = 0L to 0L
        d.download(onProgress = { done, total -> last = done to total }, isCancelled = { false })
        assertTrue(d.isReady())
        assertContentEquals(big, d.file(files[0]).readBytes())
        assertContentEquals(small, d.file(files[1]).readBytes())
        assertEquals(3500L to 3500L, last)
    }

    @Test fun resumesFromPart() {
        File(dir, "big.gguf.part").writeBytes(big.copyOfRange(0, 1000))
        val d = ModelDownloader(dir, files, base)
        d.download(onProgress = { _, _ -> }, isCancelled = { false })
        assertEquals("bytes=1000-", ranges.first())
        assertTrue(d.isReady())
        assertContentEquals(big, d.file(files[0]).readBytes())
    }

    @Test fun completePartIsVerifiedWithoutRequest() {
        // The app was killed after the last byte was written but before the rename.
        File(dir, "big.gguf.part").writeBytes(big)
        val d = ModelDownloader(dir, files, base)
        d.download(onProgress = { _, _ -> }, isCancelled = { false })
        assertTrue(d.isReady())
        assertFalse("bytes=3000-" in ranges)
    }

    @Test fun rejectsHashMismatch() {
        val bad = listOf(files[0].copy(sha256 = "0".repeat(64)))
        val d = ModelDownloader(dir, bad, base)
        assertFailsWith<IOException> { d.download(onProgress = { _, _ -> }, isCancelled = { false }) }
        assertFalse(File(dir, "big.gguf.part").exists())
        assertFalse(d.isReady())
    }

    @Test fun cancelKeepsPart() {
        val d = ModelDownloader(dir, files, base, chunkBytes = 1000)
        var calls = 0
        assertFailsWith<ModelDownloader.Cancelled> {
            d.download(onProgress = { _, _ -> }, isCancelled = { ++calls > 1 })
        }
        assertTrue(File(dir, "big.gguf.part").exists())
        assertFalse(d.isReady())
    }

    @Test fun notReadyWhenFileSizeWrong() {
        val d = ModelDownloader(dir, files, base)
        d.download(onProgress = { _, _ -> }, isCancelled = { false })
        d.file(files[1]).writeBytes(small.copyOfRange(0, 10))
        assertFalse(d.isReady())
    }
}
