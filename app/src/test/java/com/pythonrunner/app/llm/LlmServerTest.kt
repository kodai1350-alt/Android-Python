package com.pythonrunner.app.llm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LlmServerTest {
    @Test fun commandHasPinnedFlags() {
        val lib = File("/data/app/lib")
        val models = ModelDownloader(File("/data/models"))
        val server = LlmServer(lib, models)
        assertEquals(
            listOf(
                File(lib, "libllama_server.so").path,
                "-m", File("/data/models", "LFM3-VL-2B-QAD-Q4_0.gguf").path,
                "--mmproj", File("/data/models", "mmproj-LFM3-VL-2B-Q8_0.gguf").path,
                "--host", "127.0.0.1", "--port", "18089",
                "-c", "8192", "-np", "1", "-t", "4",
                "--reasoning", "off", "--no-webui",
                "--api-key", "k123",
            ),
            server.command(4, "k123"),
        )
        assertEquals("http://127.0.0.1:18089", server.url)
    }

    @Test fun newApiKeyIsRandomHex() {
        val a = LlmServer.newApiKey()
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, LlmServer.newApiKey())
    }
}
