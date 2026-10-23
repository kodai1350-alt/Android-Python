package com.pythonrunner.app.python

import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class PipCommandsTest {
    private val rt = PythonRuntime(File("/f"), File("/n"), File("/c"), "v") { error("unused") as InputStream }

    @Test fun bootstrapRunsPipFromWheel() {
        assertEquals(
            rt.command("${rt.pipWheel.path}/pip", "install", "--no-index", "--no-deps", rt.pipWheel.path),
            PipCommands.bootstrap(rt),
        )
    }

    @Test fun installTargetsBotLibs() {
        assertEquals(
            rt.command(
                "-m", "pip", "install", "--upgrade", "--no-input", "--disable-pip-version-check",
                "--target", File("/b/libs").path, "discord.py",
            ),
            PipCommands.install(rt, listOf("discord.py"), File("/b/libs")),
        )
    }
}
