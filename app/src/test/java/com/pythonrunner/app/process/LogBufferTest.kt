package com.pythonrunner.app.process

import kotlin.test.Test
import kotlin.test.assertEquals

class LogBufferTest {
    @Test fun keepsOnlyLatestLines() {
        val b = LogBuffer(capacity = 3)
        (1..5).forEach { b.add(LogLine(it.toLong(), "l$it", false)) }
        assertEquals(listOf("l3", "l4", "l5"), b.snapshot().map { it.text })
    }

    @Test fun clearEmptiesIt() {
        val b = LogBuffer(capacity = 3)
        b.add(LogLine(1, "a", false))
        b.clear()
        assertEquals(emptyList(), b.snapshot())
        b.add(LogLine(2, "b", false))
        assertEquals(listOf("b"), b.snapshot().map { it.text })
    }
}
