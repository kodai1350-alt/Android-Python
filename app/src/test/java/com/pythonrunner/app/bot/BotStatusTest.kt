package com.pythonrunner.app.bot

import kotlin.test.Test
import kotlin.test.assertEquals

class BotStatusTest {
    @Test fun nonZeroExitIsCrashed() { assertEquals(BotStatus.CRASHED, statusForExit(1, stoppedByUser = false)) }
    @Test fun userStopIsStopped() { assertEquals(BotStatus.STOPPED, statusForExit(143, stoppedByUser = true)) }
    @Test fun cleanExitIsStopped() { assertEquals(BotStatus.STOPPED, statusForExit(0, stoppedByUser = false)) }
}
