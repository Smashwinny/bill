package com.hulk.pillsapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class BuildConfigTest {
    @Test
    fun buildTimeShouldNotBeEmpty() {
        assertNotNull(BuildConfig.BUILD_TIME)
        assertFalse(BuildConfig.BUILD_TIME.isBlank())
    }
}
