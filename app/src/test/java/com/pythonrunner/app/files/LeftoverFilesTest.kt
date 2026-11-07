package com.pythonrunner.app.files

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeftoverFilesTest {
    private val filesDir: File = Files.createTempDirectory("files").toFile()

    @Test fun removesTheAiModelFolder() {
        val models = File(filesDir, "llm-models").apply { mkdirs() }
        File(models, "model.gguf").writeText("x")
        File(models, "mmproj.gguf.part").writeText("x")
        val bot = File(filesDir, "bots/spike/bot.py").apply { parentFile.mkdirs(); writeText("print(1)") }

        assertTrue(LeftoverFiles.removeAiModels(filesDir))
        assertFalse(models.exists())
        assertTrue(bot.exists(), "only the AI model folder goes")
    }

    @Test fun nothingToRemoveIsFine() {
        assertFalse(LeftoverFiles.removeAiModels(filesDir))
    }
}
