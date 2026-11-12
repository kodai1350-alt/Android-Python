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
        assertEquals("TOKEN=x", read(shared, ".env"))
        assertFalse(File(shared, "bot.session").exists())
        assertFalse(File(shared, "__pycache__").exists())
    }

    @Test fun envSyncsButOtherSecretsStayPrivate() {
        write(private, ".env", "A=1")
        write(private, ".env.local", "B=2")
        write(private, "data/secure.key", "k")
        sync.export()
        assertEquals("A=1", read(shared, ".env"))
        assertFalse(File(shared, ".env.local").exists())
        assertFalse(File(shared, "data/secure.key").exists())
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
            ".env.local", "bot.session", "server.pem", "private.key", "cert.p12", "cert.pfx",
            "data.db", "cache.sqlite", "cache.sqlite3", "token.txt", "Discord_TOKEN.json",
            "secrets.json", "credentials.json", "password.txt",
        ).forEach { assertTrue(FolderSync.isSecret(it), it) }
        // .env is shared on purpose: the user keeps the token with the bot's folder.
        listOf("main.py", "config.json", "data.json", "README.md", "requirements.txt", ".env")
            .forEach { assertFalse(FolderSync.isSecret(it), it) }
    }

    @Test fun envExamplesHoldNoValuesAndAreShared() =
        listOf(".env.example", ".env.sample", ".env.template", ".ENV.EXAMPLE")
            .forEach { assertFalse(FolderSync.isSecret(it), it) }

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
        write(shared, "bot.session", "evil")
        assertTrue(sync.pendingImports().isEmpty)
    }

    @Test fun forcedExportOverwritesUnimportedSharedEdits() {
        write(private, "main.py", "v1")
        sync.export()
        write(shared, "main.py", "edited in shared")
        assertEquals(listOf("main.py"), sync.pendingImports().modified)
        write(private, "main.py", "imported")
        sync.exportForced(listOf("main.py"))
        assertEquals("imported", read(shared, "main.py"))
        assertTrue(sync.pendingImports().isEmpty)
    }

    @Test fun forcedExportSkipsSecrets() {
        write(private, "bot.session", "s")
        write(private, ".env", "TOKEN=x")
        write(private, "a.py", "a")
        sync.exportForced(listOf("bot.session", ".env", "a.py", "../escape.py"))
        assertFalse(File(shared, "bot.session").exists())
        assertEquals("TOKEN=x", read(shared, ".env"))
        assertEquals("a", read(shared, "a.py"))
    }

    @Test fun exportRunningAlongsideAForcedExportLosesNothing() {
        // The screen's export on resume and an import's forced export can run at the same time.
        repeat(300) { write(private, "f$it.py", "x$it") }
        sync.export()
        val exporter = Thread { repeat(30) { FolderSync(private, shared, File(root, "manifest.txt")).export() } }
        exporter.start()
        repeat(30) {
            write(private, "new$it.py", "n$it")
            FolderSync(private, shared, File(root, "manifest.txt")).exportForced(listOf("new$it.py"))
        }
        exporter.join()
        assertTrue(sync.pendingImports().isEmpty, sync.pendingImports().toString())
    }

    @Test fun aRemovedSharedFolderIsNotAPendingDeletion() {
        write(private, "main.py", "x")
        write(private, "src/a.py", "a")
        sync.export()
        assertTrue(shared.deleteRecursively())
        assertTrue(sync.isSharedMissing)
        assertTrue(sync.pendingImports().isEmpty)
        assertEquals(SharedState.Missing, sync.state(null))
    }

    @Test fun exportDoesNotQuietlyRecreateARemovedFolder() {
        write(private, "main.py", "x")
        sync.export()
        shared.deleteRecursively()
        sync.export()
        assertFalse(shared.exists())
        assertTrue(sync.isSharedMissing)
    }

    @Test fun recreateWritesTheFolderAgain() {
        write(private, "main.py", "x")
        sync.export()
        shared.deleteRecursively()
        sync.recreate()
        assertEquals("x", read(shared, "main.py"))
        assertFalse(sync.isSharedMissing)
        assertEquals(SharedState.InSync, sync.state(null))
    }

    @Test fun forcedExportWaitsForTheFolderToBeRecreated() {
        write(private, "main.py", "x")
        write(private, "a.py", "a")
        sync.export()
        shared.deleteRecursively()
        write(private, "a.py", "imported")
        sync.exportForced(listOf("a.py"))
        assertFalse(shared.exists())
        sync.recreate()
        assertEquals("imported", read(shared, "a.py"))
        assertEquals("x", read(shared, "main.py"))
    }

    @Test fun forgetKeepsSharedFilesWhileTheBotsFilesAreDeleted() {
        // Deleting a bot ("フォルダは残す") must never read its vanishing files as "the bot deleted these".
        write(private, "main.py", "x")
        write(private, "src/a.py", "a")
        sync.export()
        sync.forget()
        private.deleteRecursively()
        sync.export()
        assertEquals("x", read(shared, "main.py"))
        assertEquals("a", read(shared, "src/a.py"))
    }

    @Test fun gitAndVirtualEnvFoldersAreNeverSyncedOrOffered() {
        // A bot folder cloned on a PC: adopting it must not show every git object as "added".
        write(private, "bot.py", "b")
        write(shared, "bot.py", "b")
        listOf(".git/objects/ab/cd", "venv/lib/x.py", ".venv/y.py", "__MACOSX/._bot.py", ".DS_Store", "src/.DS_Store")
            .forEach { write(shared, it, "junk") }
        sync.markSynced(listOf("bot.py"))
        assertTrue(sync.pendingImports().isEmpty, sync.pendingImports().toString())
        write(private, ".git/HEAD", "ref")
        // Parts the app installs on the phone stay on the phone.
        write(private, "player/node_modules/discord.js/index.js", "js")
        write(private, "bot/.venv/bin/python", "py")
        sync.export()
        assertFalse(File(shared, "player/node_modules").exists())
        assertEquals(SharedState.InSync, sync.state(null))
    }

    @Test fun aNewBotsFirstExportStillCreatesTheFolder() {
        write(private, "main.py", "x")
        assertFalse(sync.isSharedMissing)
        sync.export()
        assertEquals("x", read(shared, "main.py"))
    }

    @Test fun markSyncedSeedsMatchingFilesOnly() {
        write(private, "a.py", "same")
        write(shared, "a.py", "same")
        write(private, "b.py", "mine")
        write(shared, "b.py", "theirs")
        write(private, ".env", "T=1")
        write(shared, ".env", "T=1")
        sync.markSynced(listOf("a.py", "b.py", ".env", "../x"))
        val changes = sync.pendingImports()
        assertEquals(listOf("b.py"), changes.added)
        assertTrue(changes.modified.isEmpty() && changes.removed.isEmpty(), changes.toString())
    }

    @Test fun changedStateCarriesTheChangesUntilDeclined() {
        write(private, "main.py", "v1")
        sync.export()
        write(shared, "main.py", "v2")
        val changes = sync.pendingImports()
        assertEquals(SharedState.Changed(changes), sync.state(null))
        assertEquals(SharedState.InSync, sync.state(changes))
    }

    @Test fun fingerprintChangesWhenASharedFileChanges() {
        write(private, "main.py", "v1")
        sync.export()
        val first = sync.fingerprint()
        assertEquals(first, sync.fingerprint())
        write(shared, "main.py", "v2").setLastModified(System.currentTimeMillis() + 60_000)
        assertTrue(first != sync.fingerprint())
    }

    @Test fun importChangesHandsOverWhatItReplacesOrDeletes() {
        write(private, "a.py", "old")
        write(private, "b.py", "b")
        sync.export()
        write(shared, "a.py", "new")
        File(shared, "b.py").delete()
        val got = mutableListOf<Pair<String, String>>()
        sync.importChanges(sync.pendingImports()) { f, p -> got += p to f.readText() }
        assertEquals(listOf("a.py" to "old", "b.py" to "b"), got.sortedBy { it.first })
        assertEquals("new", read(private, "a.py"))
        assertFalse(File(private, "b.py").exists())
    }
}
