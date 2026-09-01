package com.hulk.manualledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudSyncProvisioningTest {
    @Test
    fun configureOnlyWhenExplicitArgumentsAreProvided() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("provision_cloud") != "true") return

        val endpoint = requireNotNull(arguments.getString("sync_endpoint"))
        val token = requireNotNull(arguments.getString("sync_token"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        ManualSyncSettings.configure(context, endpoint, token)

        val status = ManualSyncSettings.status(context)
        assertTrue(status.configured)
        assertEquals(endpoint, status.endpoint)
    }
}
