package com.pythonrunner.app.llm

import java.io.File
import java.security.SecureRandom

/** The bundled llama-server, reachable only from this phone. */
class LlmServer(nativeLibDir: File, private val models: ModelDownloader, val port: Int = 18089) {
    private val executable = File(nativeLibDir, "libllama_server.so")

    val url: String = "http://127.0.0.1:$port"

    /** [apiKey] keeps other apps on the phone from using the server; only the bot is given it. */
    fun command(threads: Int, apiKey: String): List<String> = listOf(
        executable.path,
        "-m", models.file(LlmModel.MODEL).path,
        "--mmproj", models.file(LlmModel.MMPROJ).path,
        "--host", "127.0.0.1", "--port", port.toString(),
        // One slot: reading a prompt stalls every other slot anyway (spike 1), so answer one at a time.
        "-c", "8192", "-np", "1", "-t", threads.toString(),
        "--reasoning", "off", "--no-webui",
        // A camera photo would otherwise take the model's 4096-token maximum: tens of minutes or
        // out of memory. 512 tokens is about 730x730 pixels.
        "--image-max-tokens", "512",
        "--api-key", apiKey,
    )

    companion object {
        fun newApiKey(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
