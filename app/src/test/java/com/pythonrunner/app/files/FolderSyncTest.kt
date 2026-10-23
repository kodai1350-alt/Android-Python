package com.pythonrunner.app.files

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderSyncTest {
    private val root: File = Files.createTempDirectory("sync").toFile()
    private val private = File(root, "private")
    private val shared = File(root, "shared")
    private val sync = FolderSync(private, shared, File(root, "manifest.txt"))

    private fun write(dir: File, path: String, text: String) =
        File(dir, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun read(dir: File, path: String) = File(dir, path).readText()

    @Test fun firstExportCopiesFilesButNotSecretsOrCaches() {
        write(private, "main.py", "print(1)")
        write(private, "data/a.json", "{}")
        write(private, ".env", "TOKEN=x")
        write(private, "bot.session", "s")
        write(private, "__pycache__/main.pyc", "c")
        sync.export()
        assertEquals("print(1)", read(shared, "main.py"))
        assertEquals("{}", read(shared, "data/a.json"))
        assertFalse(File(shared, ".env").exists())
        assertFalse(File(shared, "bot.session").exists())
        assertFalse(File(shared, "__pycache__").exists())
    }

    @Test fun nothingPendingRightAfterExport() {
        write(private, "main.py", "print(1)")
        sync.export()
        assertTrue(sync.pendingImports().isEmpty)
    }

    @Test fun detectsEditedAddedAndDeletedSharedFiles() {
        write(private, "main.py", "print(1)")
        write(private, "old.py", "x")
        sync.export()
        write(shared, "main.py", "print(2)")
        write(shared, "cogs/new.py", "y")
        File(shared, "old.py").delete()
        val changes = sync.pendingImports()
        assertEquals(listOf("main.py"), changes.modified)
        assertEquals(listOf("cogs/new.py"), changes.added)
        assertEquals(listOf("old.py"), changes.removed)
    }

    @Test fun importBringsChangesIntoPrivateAndClearsThem() {
        write(private, "main.py", "print(1)")
        write(private, "old.py", "x")
        sync.export()
        write(shared, "main.py", "print(2)")
        write(shared, "cogs/new.py", "y")
        File(shared, "old.py").delete()
        sync.importChanges(sync.pendingImports())
        assertEquals("print(2)", read(private, "main.py"))
        assertEquals("y", read(private, "cogs/new.py"))
        assertFalse(File(private, "old.py").exists())
        assertTrue(sync.pendingImports().isEmpty)
    }

    @Test fun exportNeverOverwritesEditsMadeInShared() {
        write(private, "main.py", "print(1)")
        sync.export()
        write(shared, "main.py", "edited on PC")
        write(private, "main.py", "changed by app")
        sync.export()
        assertEquals("edited on PC", read(shared, "main.py"))
        assertEquals(listOf("main.py"), sync.pendingImports().modified)
    }

    @Test fun exportNeverResurrectsFilesDeletedInShared() {
        write(private, "main.py", "print(1)")
        sync.export()
        File(shared, "main.py").delete()
        sync.export()
        assertFalse(File(shared, "main.py").exists())
        assertEquals(listOf("main.py"), sync.pendingImports().removed)
    }

    @Test fun exportUpdatesSharedWhenOnlyTheAppChangedIt() {
        write(private, "data/a.json", "{}")
        sync.export()
        write(private, "data/a.json", "{\"n\":1}")
        sync.export()
        assertEquals("{\"n\":1}", read(shared, "data/a.json"))
        assertTrue(sync.pendingImports().isEmpty)
    }

    @Test fun exportRemovesSharedCopyWhenTheBotDeletedTheFile() {
        write(private, "tmp.txt", "x")
        sync.export()
        File(private, "tmp.txt").delete()
        sync.export()
        assertFalse(File(shared, "tmp.txt").exists())
        assertTrue(sync.pendingImports().isEmpty)
    }

    @Test fun filesPlantedBeforeTheFirstExportAreNeitherOverwrittenNorTrusted() {
        write(shared, "main.py", "planted")
        write(private, "main.py", "mine")
        sync.export()
        assertEquals("planted", read(shared, "main.py"))
        assertEquals(listOf("main.py"), sync.pendingImports().added)
        assertEquals("mine", read(private, "main.py"))
    }

    @Test fun secretLookingNamesAreNeverShared() {
        listOf(
            ".env", ".env.local", "bot.session", "server.pem", "private.key", "cert.p12", "cert.pfx",
            "data.db", "cache.sqlite", "cache.sqlite3", "token.txt", "Discord_TOKEN.json",
            "secrets.json", "credentials.json", "password.txt",
        ).forEach { assertTrue(FolderSync.isSecret(it), it) }
        listOf("main.py", "config.json", "data.json", "README.md", "requirements.txt")
            .forEach { assertFalse(FolderSync.isSecret(it), it) }
    }

    @Test fun exportSkipsKeysAndDatabases() {
        write(private, "main.py", "x")
        write(private, "data/users.db", "x")
        write(private, "keys/bot.key", "x")
        sync.export()
        assertTrue(File(shared, "main.py").exists())
        assertFalse(File(shared, "data/users.db").exists())
        assertFalse(File(shared, "keys/bot.key").exists())
    }

    @Test fun importSkipsFilesThatChangedAfterTheUserConfirmed() {
        write(private, "main.py", "print(1)")
        sync.export()
        write(shared, "main.py", "what the user saw")
        val confirmed = sync.pendingImports()
        write(shared, "main.py", "swapped afterwards")
        val skipped = sync.importChanges(confirmed)
        assertEquals(listOf("main.py"), skipped)
        assertEquals("print(1)", read(private, "main.py"))
        assertEquals(listOf("main.py"), sync.pendingImports().modified)
    }

    @Test fun importNeverTouchesFilesOutsideTheBotFolder() {
        val outside = write(root, "outside.txt", "keep me")
        write(private, "main.py", "x")
        sync.export()
        val forged = SyncChanges(added = listOf("../shared/../outside.txt"), modified = emptyList(), removed = listOf("../outside.txt"))
        sync.importChanges(forged)
        assertEquals("keep me", outside.readText())
    }

    @Test fun forgedManifestEntriesAreIgnored() {
        val outside = write(root, "outside.txt", "keep me")
        File(root, "manifest.txt").writeText("abc\t../outside.txt\nabc\t/etc/passwd\n")
        val changes = sync.pendingImports()
        assertTrue(changes.removed.isEmpty(), changes.toString())
        sync.importChanges(SyncChanges(emptyList(), emptyList(), listOf("../outside.txt")))
        assertEquals("keep me", outside.readText())
    }

    @Test fun unsafeNamesAreRejected() {
        listOf("../a.py", "a/../../b.py", "/abs.py", "a\nb.py", "a\tb.py", "a\rb.py", "")
            .forEach { assertFalse(FolderSync.isSafePath(it), it) }
        listOf("main.py", "cogs/music.py", "data/a b.json")
            .forEach { assertTrue(FolderSync.isSafePath(it), it) }
    }

    @Test fun secretLookingFilesInSharedAreIgnored() {
        sync.export()
        write(shared, ".env", "TOKEN=evil")
        assertTrue(sync.pendingImports().isEmpty)
    }
}
