package com.pythonrunner.app.bot

import kotlin.test.Test
import kotlin.test.assertEquals

class DotEnvTest {
    @Test fun parsesTheCommonForms() {
        val parsed = DotEnv.parse("﻿A=1\r\n# c\r\nexport B = two \nC=\"x # y\"\nD='q=r'\nE=\nF=v # note\n")
        assertEquals(mapOf("A" to "1", "B" to "two", "C" to "x # y", "D" to "q=r", "E" to "", "F" to "v"), parsed.values)
        assertEquals(emptyList(), parsed.badLines)
    }

    @Test fun reportsLinesItCannotRead() {
        val parsed = DotEnv.parse("OK=1\nnot a pair\n=x\n")
        assertEquals(mapOf("OK" to "1"), parsed.values)
        assertEquals(listOf(2, 3), parsed.badLines)
    }

    @Test fun laterLinesWin() = assertEquals(mapOf("A" to "2"), DotEnv.parse("A=1\nA=2").values)

    @Test fun doubleQuotedValuesUnescape() =
        assertEquals(mapOf("A" to "a\"b\\c"), DotEnv.parse("A=\"a\\\"b\\\\c\"").values)

    @Test fun withMissingAppendsOnlyNewNames() {
        assertEquals(
            "A=1\nB=\"x y\"\nC=\"a\\\"b\"\n",
            DotEnv.withMissing("A=1", mapOf("A" to "9", "B" to "x y", "C" to "a\"b")),
        )
        assertEquals("A=1\n", DotEnv.withMissing("A=1\n", mapOf("A" to "2")))
        assertEquals("T=abc\n", DotEnv.withMissing("", mapOf("T" to "abc")))
    }

    @Test fun appendedValuesReadBackTheSame() {
        val values = mapOf("A" to "x y", "B" to "q=r#s", "C" to "it's \"quoted\" \\ here", "D" to "")
        assertEquals(values, DotEnv.parse(DotEnv.withMissing("", values)).values)
    }

    @Test fun namesListsKeysInOrder() = assertEquals(listOf("B", "A"), DotEnv.names("B=1\nA=2\n# x\n"))
}
