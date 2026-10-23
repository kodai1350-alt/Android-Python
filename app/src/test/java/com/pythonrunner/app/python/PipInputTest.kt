package com.pythonrunner.app.python

import kotlin.test.Test
import kotlin.test.assertEquals

class PipInputTest {
    private fun parse(text: String) = PipInput.packages(text)

    @Test fun plainPipInstall() {
        assertEquals(listOf("discord.py", "aiohttp"), parse("pip install discord.py aiohttp"))
    }

    @Test fun barePackageNamesStillWork() {
        assertEquals(listOf("discord.py", "aiohttp"), parse("discord.py aiohttp"))
    }

    @Test fun commonCommandForms() {
        assertEquals(listOf("a"), parse("pip3 install a"))
        assertEquals(listOf("a"), parse("python -m pip install a"))
        assertEquals(listOf("a"), parse("python3 -m pip install a"))
        assertEquals(listOf("a"), parse("py -m pip install a"))
        assertEquals(listOf("a"), parse("!pip install a"))
        assertEquals(listOf("a"), parse("%pip install a"))
        assertEquals(listOf("a"), parse("$ pip install a"))
        assertEquals(listOf("a"), parse("sudo pip install a"))
    }

    @Test fun flagsAreDropped() {
        assertEquals(listOf("discord.py"), parse("pip install -U discord.py"))
        assertEquals(listOf("a", "b"), parse("pip install --upgrade --user a b"))
    }

    @Test fun flagsWithValuesDropTheirValue() {
        assertEquals(listOf("a"), parse("pip install -i https://example.com/simple a"))
        assertEquals(listOf("a"), parse("pip install --index-url https://example.com/simple a"))
        assertEquals(listOf("a"), parse("pip install -t /sdcard/x a"))
        assertEquals(listOf("a"), parse("pip install --index-url=https://example.com/simple a"))
    }

    @Test fun versionsExtrasAndQuotesAreKept() {
        assertEquals(listOf("requests==2.31.0"), parse("pip install requests==2.31.0"))
        assertEquals(listOf("discord.py[voice]"), parse("pip install 'discord.py[voice]'"))
        assertEquals(listOf("aiohttp>=3.9"), parse("pip install \"aiohttp>=3.9\""))
    }

    @Test fun severalCommandsAndLines() {
        assertEquals(listOf("a", "b", "c"), parse("pip install a\npip install b && pip install c"))
        assertEquals(listOf("a", "b"), parse("pip install a; pip install b"))
        assertEquals(listOf("a", "b"), parse("  \r\npip install a\r\n\r\npip install b\r\n"))
    }

    @Test fun commentsAndOtherCommandsAreIgnored() {
        assertEquals(listOf("a"), parse("# 必要なライブラリ\npip install a  # これを入れる"))
        assertEquals(emptyList(), parse("pip uninstall a"))
        assertEquals(emptyList(), parse("pip install -r requirements.txt"))
        assertEquals(emptyList(), parse("pip list"))
    }

    @Test fun duplicatesAreRemoved() {
        assertEquals(listOf("a", "b"), parse("pip install a b\npip install a"))
    }

    @Test fun emptyInputGivesNothing() {
        assertEquals(emptyList(), parse(""))
        assertEquals(emptyList(), parse("pip install"))
    }
}
