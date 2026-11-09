package com.pythonrunner.app.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BotFleetTest {
    private class FakeRunner : FleetRunner {
        var starts = 0
        var stops = 0
        override var isActive = false
        override fun start(): Boolean {
            if (isActive) return false
            starts++
            isActive = true
            return true
        }
        override fun stop() {
            stops++
            isActive = false
        }
    }

    private val created = mutableMapOf<String, MutableList<FakeRunner>>()
    private val fleet = BotFleet { id -> FakeRunner().also { created.getOrPut(id) { mutableListOf() } += it } }
    private fun runner(id: String) = created.getValue(id).last()

    @Test fun botsStartAndStopIndependently() {
        assertTrue(fleet.start("a"))
        assertTrue(fleet.start("b"))
        assertEquals(listOf("a", "b"), fleet.activeIds())
        fleet.stop("a")
        assertEquals(listOf("b"), fleet.activeIds())
        assertEquals(0, runner("b").stops)
        assertTrue(fleet.isActive("b"))
        assertFalse(fleet.isActive("a"))
    }

    @Test fun restartIsRememberedPerBot() {
        fleet.start("a")
        fleet.start("b")
        assertTrue(fleet.requestRestart("a"))
        fleet.stopForRestart("a")
        assertEquals(1, runner("a").stops)
        assertFalse(fleet.takeRestart("b"))
        assertTrue(fleet.takeRestart("a"))
        assertFalse(fleet.takeRestart("a"))
    }

    @Test fun restartOfAStoppedBotSaysStartIt() {
        assertFalse(fleet.requestRestart("a"))
        assertFalse(fleet.takeRestart("a"))
    }

    @Test fun stopCancelsAPendingRestart() {
        fleet.start("a")
        assertTrue(fleet.requestRestart("a"))
        fleet.stop("a")
        assertFalse(fleet.takeRestart("a"))
    }

    @Test fun runnersAreCreatedOncePerBot() {
        fleet.start("a")
        fleet.stop("a")
        fleet.start("a")
        assertEquals(1, created.getValue("a").size)
        assertEquals(2, runner("a").starts)
    }

    @Test fun forgetDropsTheRunner() {
        fleet.start("a")
        val first = runner("a")
        fleet.stop("a")
        fleet.forget("a")
        fleet.start("a")
        assertNotSame(first, runner("a"))
        assertSame(runner("a"), created.getValue("a")[1])
    }

    @Test fun stoppingAnUnknownBotDoesNothing() {
        fleet.stop("nobody")
        assertTrue(fleet.activeIds().isEmpty())
        assertTrue(created.isEmpty())
    }
}
