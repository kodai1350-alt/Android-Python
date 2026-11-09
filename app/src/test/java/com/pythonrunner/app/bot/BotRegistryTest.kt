package com.pythonrunner.app.bot

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BotRegistryTest {
    private val root: File = Files.createTempDirectory("registry").toFile()
    private val file = File(root, "bots/registry.txt")
    private val registry = BotRegistry(file)

    @Test fun keepsTheOrderBotsWereAdded() {
        listOf("A", "B", "C").forEach { registry.add(it) }
        assertEquals(listOf("A", "B", "C"), registry.all().map { it.name })
    }

    @Test fun survivesReloadFromDisk() {
        val a = registry.add("紬")
        val b = registry.add("音楽Bot")
        assertEquals(listOf(a, b), BotRegistry(file).all())
    }

    @Test fun renameKeepsIdAndPosition() {
        val a = registry.add("A")
        registry.add("B")
        registry.rename(a.id, "Z")
        assertEquals(listOf(BotEntry(a.id, "Z")), registry.all().take(1))
        assertEquals("Z", BotRegistry(file).get(a.id)?.name)
    }

    @Test fun removeDropsOnlyThatBot() {
        val a = registry.add("A")
        val b = registry.add("B")
        registry.remove(a.id)
        assertEquals(listOf(b), registry.all())
        assertNull(registry.get(a.id))
    }

    @Test fun addWithFixedIdIsKept() = assertEquals("mybot", registry.add("MyBot", "mybot").id)

    @Test fun newIdsAreDistinctAndNeverTheMigratedId() {
        val ids = (1..200).map { BotRegistry.newId() }.toSet()
        assertEquals(200, ids.size)
        ids.forEach { assertNotEquals("mybot", it); assertEquals(12, it.length) }
    }

    @Test fun aLeftoverTmpFileDoesNotBreakReading() {
        val a = registry.add("A")
        File(file.path + ".tmp").writeText("garbage\n\u0000")
        assertEquals(listOf(a), BotRegistry(file).all())
    }

    @Test fun namesWithTabsOrNewlinesAreRefused() {
        assertFailsWith<IllegalArgumentException> { registry.add("a\tb") }
        assertFailsWith<IllegalArgumentException> { registry.add("a\nb") }
        val a = registry.add("A")
        assertFailsWith<IllegalArgumentException> { registry.rename(a.id, "x\ny") }
    }
}
