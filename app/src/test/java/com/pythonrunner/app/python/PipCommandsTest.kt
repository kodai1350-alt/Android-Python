package com.pythonrunner.app.python

import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class PipCommandsTest {
    private val rt = PythonRuntime(File("/f"), File("/n"), File("/c"), "v") { error("unused") as InputStream }

    @Test fun bootstrapUsesEnsurepip() {
        // ensurepip installs the wheel bundled with the stdlib under its real filename.
        assertEquals(rt.command("-m", "ensurepip", "--upgrade"), PipCommands.bootstrap(rt))
    }

    @Test fun installTargetsBotLibs() {
        assertEquals(
            rt.command(
                "-m", "pip", "install", "--upgrade", "--no-input", "--disable-pip-version-check",
                "--find-links", rt.wheelsDir.path, "--only-binary", "audioop-lts",
                "--target", File("/b/libs").path, "discord.py",
            ),
            PipCommands.install(rt, listOf("discord.py"), File("/b/libs")),
        )
    }
}
