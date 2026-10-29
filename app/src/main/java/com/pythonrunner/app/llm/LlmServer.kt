package com.pythonrunner.app.llm

import java.io.File

/** The bundled llama-server, reachable only from this phone. */
class LlmServer(nativeLibDir: File, private val models: ModelDownloader, val port: Int = 18089) {
    private val executable = File(nativeLibDir, "libllama_server.so")

    val url: String = "http://127.0.0.1:$port"

    fun command(threads: Int): List<String> = listOf(
        executable.path,
        "-m", models.file(LlmModel.MODEL).path,
        "--mmproj", models.file(LlmModel.MMPROJ).path,
        "--host", "127.0.0.1", "--port", port.toString(),
        // One slot: reading a prompt stalls every other slot anyway (spike 1), so answer one at a time.
        "-c", "8192", "-np", "1", "-t", threads.toString(),
        "--reasoning", "off", "--no-webui",
    )
}
