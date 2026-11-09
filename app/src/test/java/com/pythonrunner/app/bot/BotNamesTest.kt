package com.pythonrunner.app.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotNamesTest {
    @Test fun trimsAndAccepts() = assertEquals(NameCheck.Ok("紬"), BotNames.check("  紬 ", emptyList(), emptyList()))

    @Test fun rejectsEmpty() = assertTrue(BotNames.check("   ", emptyList(), emptyList()) is NameCheck.Invalid)

    @Test fun rejectsLongerThan40() {
        assertTrue(BotNames.check("あ".repeat(41), emptyList(), emptyList()) is NameCheck.Invalid)
        assertEquals(NameCheck.Ok("あ".repeat(40)), BotNames.check("あ".repeat(40), emptyList(), emptyList()))
    }

    @Test fun rejectsFolderUnsafeCharacters() =
        listOf("a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\nb", "a\tb", ".hidden")
            .forEach { assertTrue(BotNames.check(it, emptyList(), emptyList()) is NameCheck.Invalid, it) }

    @Test fun rejectsTakenNameIgnoringCase() =
        assertEquals(NameCheck.Invalid("同じ名前のBotがあります"), BotNames.check("mybot", listOf("MyBot"), emptyList()))

    @Test fun rejectsUnlistedFolderWithSameName() =
        assertEquals(NameCheck.Invalid("同じ名前のフォルダがあります"), BotNames.check("音楽bot", emptyList(), listOf("音楽Bot")))

    @Test fun suggestStripsExtensionAndUnsafeCharacters() {
        assertEquals("android", BotNames.suggest("android.zip", emptyList()))
        assertEquals("main", BotNames.suggest("main.py", emptyList()))
        assertEquals("a_b", BotNames.suggest("a:b", emptyList()))
    }

    @Test fun suggestAvoidsTakenNames() = assertEquals("紬 3", BotNames.suggest("紬", listOf("紬", "紬 2")))

    @Test fun suggestFallsBackWhenNothingIsLeft() = assertEquals("Bot", BotNames.suggest(".zip", emptyList()))

    @Test fun suggestKeepsWithinTheLengthLimit() {
        val name = BotNames.suggest("あ".repeat(50), listOf("あ".repeat(40)))
        assertTrue(name.length <= BotNames.MAX_LENGTH, name)
        assertTrue(BotNames.check(name, listOf("あ".repeat(40)), emptyList()) is NameCheck.Ok, name)
    }
}
