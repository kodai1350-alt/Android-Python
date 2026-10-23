package com.pythonrunner.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test fun buildConfigHasPythonVersion() {
        assertEquals("3.14.9", BuildConfig.PYTHON_VERSION)
    }
}
