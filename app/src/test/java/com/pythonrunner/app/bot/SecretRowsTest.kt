package com.pythonrunner.app.bot

import kotlin.test.Test
import kotlin.test.assertEquals

class SecretRowsTest {
    @Test fun findsDuplicateNames() {
        assertEquals(setOf("TOKEN"), duplicateNames(listOf("TOKEN", "API_KEY", "TOKEN")))
    }

    @Test fun ignoresBlankAndSurroundingSpaces() {
        assertEquals(setOf("TOKEN"), duplicateNames(listOf("", " ", "TOKEN", " TOKEN ")))
    }

    @Test fun noDuplicates() {
        assertEquals(emptySet(), duplicateNames(listOf("TOKEN", "API_KEY")))
    }
}
