package com.pythonrunner.app.python

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class BotPartsTest {
    private fun newCode(): File = Files.createTempDirectory("botparts").toFile().resolve("code").apply { mkdirs() }

    private fun file(root: File, path: String, text: String = "x") =
        File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun findsNodeProjectsAndRequirementsInSubfolders() {
        val code = newCode()
        file(code, "Start.py")
        file(code, "player/package.json", "{}")
        file(code, "player/package-lock.json", "{}")
        file(code, "bot/requirements.txt", "discord.py")
        file(code, "requirements.txt", "aiohttp")

        assertEquals(listOf("player"), BotParts.nodeProjects(code).map { it.relativeTo(code).invariantSeparatorsPath })
        assertEquals(
            listOf("bot/requirements.txt", "requirements.txt"),
            BotParts.requirementFiles(code).map { it.relativeTo(code).invariantSeparatorsPath },
        )
    }

    @Test
    fun neverLooksInsideInstalledPartsOrVirtualEnvs() {
        val code = newCode()
        file(code, "player/package.json", "{}")
        file(code, "player/node_modules/discord.js/package.json", "{}")
        file(code, "bot/.venv/lib/requirements.txt")
        file(code, ".git/requirements.txt")

        assertEquals(listOf("player"), BotParts.nodeProjects(code).map { it.relativeTo(code).invariantSeparatorsPath })
        assertEquals(emptyList<File>(), BotParts.requirementFiles(code))
    }

    @Test
    fun usesTheLockfileWhenThereIsOne() {
        val code = newCode()
        file(code, "a/package.json", "{}")
        file(code, "a/package-lock.json", "{}")
        file(code, "b/package.json", "{}")

        assertEquals("ci", BotParts.npmArgs(File(code, "a")).first())
        assertEquals("install", BotParts.npmArgs(File(code, "b")).first())
        // Termux's node points install scripts at Termux's shell, which the app does not have.
        assertEquals(true, "--ignore-scripts" in BotParts.npmArgs(File(code, "a")))
    }

    @Test
    fun theVenvPythonSitsWhereStartScriptsLookForIt() {
        val code = newCode()
        assertEquals(File(code, "bot/.venv/bin/python"), BotParts.venvPython(File(code, "bot")))
    }
}
