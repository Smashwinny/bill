package com.hulk.pillsapp

import android.content.ComponentName
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveAppModeInstrumentedTest {
    @Test
    fun pauseAndRestoreRoundTripPreservesEveryOtherAccessibilityService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("WRITE_SECURE_SETTINGS must be granted by ADB", SensitiveAppMode.hasControlPermission(context))
        val component = ComponentName(
            context,
            PaymentBehaviorAccessibilityService::class.java,
        ).flattenToString()
        val before = enabledComponents(context)
        assumeTrue("behavior accessibility service must start enabled", component in before)
        var activeSessionId: String? = null
        val targetPackage = context.packageName
        val alreadyRegistered = SensitiveAppRegistry.activeProfile(context, targetPackage) != null
        assertTrue(
            PackageIdentityResolver.resolve(context, targetPackage)?.let {
                SensitiveAppRegistry.add(context, it, SensitiveProfileOrigin.USER_SELECTED)
            } == true
        )

        try {
            assertEquals(
                SensitivePauseResult.PAUSED,
                SensitiveAppMode.pauseForPackage(context, targetPackage),
            )
            val paused = enabledComponents(context)
            assertFalse(component in paused)
            assertEquals(before - component, paused)
            assertTrue(SensitiveAppMode.isActive(context))
            assertFalse("pause must invalidate the pre-pause heartbeat", com.hulk.pillsapp.ledger.LedgerKernel.isA11yHeartbeatFresh())
            val session = requireNotNull(SensitiveAppMode.currentSession(context))
            activeSessionId = session.id
            assertEquals(SensitiveLaunchPhase.PAUSED, session.phase)

            // 旧通知/旧 Worker 的 generation 必须是无副作用空操作。
            assertTrue(SensitiveAppMode.restore(context, expectedSessionId = "stale-session"))
            assertFalse(component in enabledComponents(context))
            assertTrue(SensitiveAppMode.isActive(context))

            // 截止时间只能提醒，不能在敏感 App 仍可能前台时擅自恢复。
            SensitiveAppMode.handleRestoreDeadline(context, session.id)
            assertFalse(component in enabledComponents(context))
            assertTrue(SensitiveAppMode.isActive(context))

            assertTrue(SensitiveAppMode.markTargetLaunched(context, session.id))
            assertFalse("the same session cannot launch the target twice", SensitiveAppMode.markTargetLaunched(context, session.id))
            assertEquals(
                SensitiveLaunchPhase.LAUNCHED,
                SensitiveAppMode.currentSession(context)?.phase,
            )
        } finally {
            assertTrue(SensitiveAppMode.restore(context))
            activeSessionId?.let {
                assertFalse("RECOVERING must never pass the launch CAS", SensitiveAppMode.markTargetLaunched(context, it))
            }
            if (!alreadyRegistered) SensitiveAppRegistry.remove(context, targetPackage)
        }

        awaitCondition("accessibility service must reconnect and confirm recovery") {
            component in enabledComponents(context) && !SensitiveAppMode.isActive(context)
        }
        assertEquals(before, enabledComponents(context))
        assertFalse(SensitiveAppMode.isActive(context))
        assertTrue("real reconnect must write a fresh heartbeat", com.hulk.pillsapp.ledger.LedgerKernel.isA11yHeartbeatFresh())
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(message, condition())
    }

    private fun enabledComponents(context: android.content.Context): Set<String> =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').filter(String::isNotBlank).toSet()
}
