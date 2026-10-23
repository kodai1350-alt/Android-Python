package com.pythonrunner.app.python

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class PythonRuntimeTest {
    private val root: File = Files.createTempDirectory("prt").toFile()
    private val baseDir = File(root, "files").apply { mkdirs() }
    private val nativeLibDir = File(root, "native").apply { mkdirs() }
    private val tmpDir = File(root, "cache").apply { mkdirs() }

    private fun stdlibZip(vararg entries: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("# $name".toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val assets = mapOf(
        "python/stdlib.zip" to stdlibZip("lib/python3.14/os.py", "lib/python3.14/json/__init__.py"),
        "python/cacert.pem" to "CERT".toByteArray(),
        "python/wheels/audioop_lts-0.2.2-py3-none-any.whl" to "STUB".toByteArray(),
    )

    private fun openAsset(name: String): InputStream =
        ByteArrayInputStream(assets[name] ?: error("no asset $name"))

    private fun runtime(
        version: String = "3.14.9-1",
        open: (String) -> InputStream = ::openAsset,
    ) = PythonRuntime(baseDir, nativeLibDir, tmpDir, version, open)

    @Test fun extractsStdlibAndWritesMarker() {
        val rt = runtime()
        rt.ensureInstalled()
        assertTrue(File(rt.home, "lib/python3.14/os.py").exists())
        assertTrue(File(rt.home, "lib/python3.14/json/__init__.py").exists())
        assertEquals("3.14.9-1", File(rt.home, ".installed").readText())
        assertEquals("CERT", File(rt.home, "cacert.pem").readText())
        assertEquals("STUB", File(rt.wheelsDir, "audioop_lts-0.2.2-py3-none-any.whl").readText())
        assertTrue(rt.isInstalled())
    }

    @Test fun reExtractsWhenMarkerMissing() {
        val rt = runtime()
        val broken = File(rt.home, "lib/python3.14/broken.py")
        broken.parentFile!!.mkdirs()
        broken.writeText("half-written")
        assertFalse(rt.isInstalled())
        rt.ensureInstalled()
        assertFalse(broken.exists())
        assertTrue(File(rt.home, "lib/python3.14/os.py").exists())
    }

    @Test fun skipsExtractionWhenVersionMatches() {
        runtime().ensureInstalled()
        runtime(open = { fail("asset $it opened again") }).ensureInstalled()
    }

    @Test fun reExtractsWhenVersionDiffers() {
        val rt = runtime()
        rt.ensureInstalled()
        File(rt.home, ".installed").writeText("3.14.8-1")
        rt.ensureInstalled()
        assertEquals("3.14.9-1", File(rt.home, ".installed").readText())
    }

    @Test fun pipIsReadyOnlyForCurrentInstall() {
        val rt = runtime()
        rt.ensureInstalled()
        assertFalse(rt.isPipInstalled())
        File(rt.home, "lib/python3.14/site-packages/pip").mkdirs()
        assertTrue(rt.isPipInstalled())
        // After an app update the old home (with pip) is still there until re-extraction.
        assertFalse(runtime(version = "3.14.9-2").isPipInstalled())
    }

    @Test fun environmentHasRequiredVariables() {
        val rt = runtime()
        val env = rt.environment(listOf(File("/x/libs")), mapOf("TOKEN" to "abc", "PYTHONHOME" to "evil"))
        assertEquals(rt.home.path, env["PYTHONHOME"])
        assertEquals(nativeLibDir.path, env["LD_LIBRARY_PATH"])
        assertEquals("1", env["PYTHONUNBUFFERED"])
        assertEquals(File(rt.home, "cacert.pem").path, env["SSL_CERT_FILE"])
        assertEquals(File("/x/libs").path, env["PYTHONPATH"])
        assertEquals(tmpDir.path, env["TMPDIR"])
        assertEquals(File(baseDir, "home").path, env["HOME"])
        listOf("AIOHTTP", "YARL", "MULTIDICT", "FROZENLIST", "PROPCACHE").forEach {
            assertEquals("1", env["${it}_NO_EXTENSIONS"], it)
        }
        assertEquals("abc", env["TOKEN"])
    }

    @Test fun pythonPathJoinsWithColon() {
        val env = runtime().environment(listOf(File("/a"), File("/b")), emptyMap())
        assertEquals(File("/a").path + ":" + File("/b").path, env["PYTHONPATH"])
    }

    @Test fun commandStartsWithLauncher() {
        val rt = runtime()
        assertEquals(
            listOf(File(nativeLibDir, "libpylauncher.so").path, "-c", "print(1)"),
            rt.command("-c", "print(1)"),
        )
    }
}
