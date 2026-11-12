package com.pythonrunner.app.bot

import kotlin.test.Test
import kotlin.test.assertEquals

class DotEnvTest {
    @Test fun parsesTheCommonForms() {
        val parsed = DotEnv.parse("\uFEFFA=1\r\n# c\r\nexport B = two \nC=\"x # y\"\nD='q=r'\nE=\nF=v # note\n")
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

    @Test fun namesThatChangeHowProgramsLoadAreDropped() {
        val (kept, dropped) = DotEnv.forProcess(
            mapOf(
                "DISCORD_TOKEN" to "t", "LD_PRELOAD" to "/sdcard/x.so", "ld_library_path" to "/x",
                "NODE_OPTIONS" to "--require /sdcard/x.js", "PYTHONSTARTUP" to "/sdcard/x.py",
                "PYTHONPATH" to "/x", "PYTHONHOME" to "/x", "PATH" to "/sdcard", "PYTHONIOENCODING" to "utf-8",
            ),
        )
        assertEquals(mapOf("DISCORD_TOKEN" to "t", "PYTHONIOENCODING" to "utf-8"), kept)
        assertEquals(
            listOf("LD_PRELOAD", "NODE_OPTIONS", "PATH", "PYTHONHOME", "PYTHONPATH", "PYTHONSTARTUP", "ld_library_path"),
            dropped,
        )
    }

    @Test fun runtimeTlsAndProxySettingsAreDroppedButHarmlessPythonOnesKept() {
        val names = listOf(
            "PYTHONINSPECT", "PYTHONWARNINGS", "NODE_TLS_REJECT_UNAUTHORIZED", "OPENSSL_CONF", "BASH_ENV", "ENV",
            "BROWSER", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "HTTPS_PROXY", "http_proxy", "ALL_PROXY",
            "PYTHONIOENCODING", "PYTHONUTF8", "PYTHONUNBUFFERED", "PYTHONDONTWRITEBYTECODE", "BOT_PREFIX",
        )
        val (kept, dropped) = DotEnv.forProcess(names.associateWith { "x" })
        assertEquals(setOf("PYTHONIOENCODING", "PYTHONUTF8", "PYTHONUNBUFFERED", "PYTHONDONTWRITEBYTECODE", "BOT_PREFIX"), kept.keys)
        assertEquals(12, dropped.size)
    }
}
