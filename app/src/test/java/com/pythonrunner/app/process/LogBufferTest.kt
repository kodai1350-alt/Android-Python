package com.pythonrunner.app.process

import kotlin.test.Test
import kotlin.test.assertEquals

class LogBufferTest {
    @Test fun keepsOnlyLatestLines() {
        val b = LogBuffer(capacity = 3)
        (1..5).forEach { b.add(LogLine(it.toLong(), "l$it", false)) }
        assertEquals(listOf("l3", "l4", "l5"), b.snapshot().map { it.text })
    }
}
