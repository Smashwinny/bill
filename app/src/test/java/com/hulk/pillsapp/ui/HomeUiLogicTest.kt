package com.hulk.pillsapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiLogicTest {
    @Test
    fun missingPermissionsAreSetupRequired() {
        val summary = deriveHomeHealth(
            healthyInput().copy(notificationPermission = false),
        )

        assertEquals(HomeHealthLevel.SETUP_REQUIRED, summary.level)
        assertTrue(summary.issues.contains("通知读取权限未开启"))
    }

    @Test
    fun enabledButDisconnectedIsAttentionNotHealthy() {
        val summary = deriveHomeHealth(
            healthyInput().copy(notificationConnected = false),
        )

        assertEquals(HomeHealthLevel.ATTENTION, summary.level)
        assertTrue(summary.issues.any { it.contains("未连接") })
    }

    @Test
    fun staleHeartbeatAndBacklogRemainVisible() {
        val summary = deriveHomeHealth(
            healthyInput().copy(
                accessibilityHeartbeatFresh = false,
                pendingParseCount = 3,
                openGapCount = 2,
            ),
        )

        assertEquals(HomeHealthLevel.ATTENTION, summary.level)
        assertEquals(3, summary.issues.size)
    }

    @Test
    fun allRuntimeSignalsMustBeHealthy() {
        val summary = deriveHomeHealth(healthyInput())

        assertEquals(HomeHealthLevel.HEALTHY, summary.level)
        assertTrue(summary.issues.isEmpty())
    }

    private fun healthyInput() = HomeHealthInput(
        notificationPermission = true,
        notificationConnected = true,
        accessibilityPermission = true,
        accessibilityConnected = true,
        accessibilityHeartbeatFresh = true,
        pendingParseCount = 0,
        openGapCount = 0,
    )
}

