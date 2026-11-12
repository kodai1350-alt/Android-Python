package com.pythonrunner.app.python

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class InstalledPartsTest {
    private val root: File = Files.createTempDirectory("parts").toFile()

    private fun file(path: String, text: String = "x") =
        File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun readsPythonLibsFromDistInfo() {
        file("libs/discord_py-2.6.4.dist-info/METADATA", "Metadata-Version: 2.1\nName: discord.py\nVersion: 2.6.4\n\nbody")
        file("libs/aiohttp-3.13.2.dist-info/METADATA", "Name: aiohttp\r\nVersion: 3.13.2\r\n")
        file("libs/aiohttp/__init__.py")
        assertEquals(
            listOf(Lib("aiohttp", "3.13.2"), Lib("discord.py", "2.6.4")),
            InstalledParts.pythonLibs(File(root, "libs")),
        )
    }

    @Test fun readsNodeDepsAndVersions() {
        file("player/package.json", """{"name":"p","dependencies":{"discord.js":"^14.28.1","@discordjs/voice":"^0.19.2"},"devDependencies":{"x":"1"}}""")
        file("player/node_modules/discord.js/package.json", """{
  "name": "discord.js",
  "version": "14.28.1",
  "dependencies": { "a": "1.0.0" }
}""")
        assertEquals(
            listOf(Lib("@discordjs/voice", "?"), Lib("discord.js", "14.28.1")),
            InstalledParts.nodeLibs(File(root, "player")),
        )
    }

    @Test fun stateIsOkMissingOrStale() {
        val code = File(root, "code")
        val req = file("code/bot/requirements.txt", "discord.py")
        file("code/player/package.json", "{}")
        file("code/player/node_modules/x/package.json", "{}")
        req.setLastModified(1_000_000)
        File(code, "player/package.json").setLastModified(1_000_000)
        val later = 2_000_000L

        file("code/bot/.venv/bin/python")
        val ok = InstalledParts.parts(code, mapOf("bot/requirements.txt" to later, "player" to later))
        assertEquals(listOf(PartState.OK, PartState.OK), ok.map { it.state })
        assertEquals(listOf("bot", "player"), ok.map { it.label })

        assertEquals(PartState.MISSING, InstalledParts.parts(code, mapOf("player" to later)).first().state)

        req.setLastModified(3_000_000)
        assertEquals(PartState.STALE, InstalledParts.parts(code, mapOf("bot/requirements.txt" to later)).first().state)

        File(code, "bot/.venv").deleteRecursively()
        assertEquals(PartState.MISSING, InstalledParts.parts(code, mapOf("bot/requirements.txt" to 4_000_000)).first().state)
    }

    @Test fun theTopFolderIsNamedInWords() {
        file("code/requirements.txt")
        assertEquals("いちばん上", InstalledParts.parts(File(root, "code"), emptyMap()).single().label)
    }

    @Test fun findIgnoresSpelling() {
        val libs = listOf(Lib("discord.py", "2.6.4"), Lib("aiohttp", "3.13.2"))
        assertEquals(Lib("discord.py", "2.6.4"), InstalledParts.find(libs, "Discord_Py"))
        assertEquals(null, InstalledParts.find(libs, "requests"))
    }
}
