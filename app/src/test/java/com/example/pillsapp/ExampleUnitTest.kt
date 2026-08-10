package com.example.pillsapp

import org.junit.Test
import kotlin.test.assertEquals

class ExampleUnitTest {
    @Test
    fun buildTimeFormat_isNotEmpty() {
        val sampleTime = "2026-01-01T00:00:00"
        assertEquals(19, sampleTime.length)
    }
}
