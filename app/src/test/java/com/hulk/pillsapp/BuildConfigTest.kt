package com.hulk.pillsapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigTest {
    @Test
    fun buildTimeShouldNotBeEmpty() {
        assertNotNull(BuildConfig.BUILD_TIME)
        assertFalse(BuildConfig.BUILD_TIME.isBlank())
    }

    @Test
    fun versionNameShouldNotBeEmpty() {
        assertNotNull(BuildConfig.VERSION_NAME)
        assertTrue(BuildConfig.VERSION_NAME.isNotBlank())
    }

    @Test
    fun appIdShouldBeConfigured() {
        assertTrue(BuildConfig.APPLICATION_ID.isNotBlank())
    }
}
