package com.hulk.pillsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceListEditorTest {
    private val ours = "com.hulk.pillsapp/com.hulk.pillsapp.PaymentBehaviorAccessibilityService"
    private val pick = "com.wulitu.xiaoben/com.example.PickService"
    private val screenshot = "com.miui.screenshot/com.example.ScreenshotService"

    @Test
    fun removeOnlyOwnComponentAndPreserveOrder() {
        val original = "$pick:$ours:$screenshot"
        val updated = AccessibilityServiceListEditor.remove(original, ours)

        assertEquals("$pick:$screenshot", updated)
        assertFalse(AccessibilityServiceListEditor.contains(updated, ours))
        assertTrue(AccessibilityServiceListEditor.contains(updated, pick))
        assertTrue(AccessibilityServiceListEditor.contains(updated, screenshot))
        assertEquals(listOf(pick, screenshot), AccessibilityServiceListEditor.without(updated, ours))
    }

    @Test
    fun restoreIsIdempotentAndDoesNotDuplicateOtherServices() {
        val paused = "$pick:$screenshot"
        val restored = AccessibilityServiceListEditor.add(paused, ours)

        assertEquals("$pick:$screenshot:$ours", restored)
        assertEquals(restored, AccessibilityServiceListEditor.add(restored, ours))
    }

    @Test
    fun malformedEmptyEntriesAreNormalizedWithoutInventingServices() {
        assertEquals(ours, AccessibilityServiceListEditor.add("::$ours::", ours))
        assertEquals("", AccessibilityServiceListEditor.remove(null, ours))
    }

    @Test
    fun shorthandAndFullyQualifiedComponentsHaveTheSameIdentity() {
        val shorthand = "com.miui.screenshot/.accessibility.ScreenshotAccessibilityService"
        val full = "com.miui.screenshot/com.miui.screenshot.accessibility.ScreenshotAccessibilityService"
        val raw = "$pick:$shorthand:$ours"

        assertTrue(AccessibilityServiceListEditor.contains(raw, full))
        assertEquals("$pick:$shorthand:$ours", AccessibilityServiceListEditor.add(raw, full))
        assertEquals(
            listOf(
                pick,
                "com.miui.screenshot/com.miui.screenshot.accessibility.ScreenshotAccessibilityService",
            ),
            AccessibilityServiceListEditor.without(raw, ours),
        )
        assertEquals("$pick:$shorthand", AccessibilityServiceListEditor.remove(raw, ours))
    }

    @Test
    fun bankLaunchRequiresPausedPhaseAndOwnServiceAbsent() {
        SensitiveLaunchPhase.entries.forEach { phase ->
            assertEquals(
                phase == SensitiveLaunchPhase.PAUSED,
                SensitivePhaseRules.mayLaunchBank(phase, ownServiceAbsent = true),
            )
            assertFalse(SensitivePhaseRules.mayLaunchBank(phase, ownServiceAbsent = false))
        }
    }

    @Test
    fun recoveryConfirmationRequiresSameAttemptAndEnabledReadback() {
        val current = "attempt-2"
        assertTrue(
            SensitiveRecoveryRules.mayConfirm(
                SensitiveLaunchPhase.RECOVERING,
                current,
                current,
                ownServiceEnabled = true,
            )
        )
        assertFalse(
            "old callback must not confirm a newer retry",
            SensitiveRecoveryRules.mayConfirm(
                SensitiveLaunchPhase.RECOVERING,
                current,
                "attempt-1",
                ownServiceEnabled = true,
            )
        )
        assertFalse(
            "a delayed callback after add failure must not clear the session",
            SensitiveRecoveryRules.mayConfirm(
                SensitiveLaunchPhase.RECOVERING,
                current,
                current,
                ownServiceEnabled = false,
            )
        )
    }

    @Test
    fun connectedServiceKeepsCollectingWhenOnlySessionCleanupIsPending() {
        assertFalse(SensitiveConnectionConfirmation.REJECTED.allowsCollection())
        assertTrue(SensitiveConnectionConfirmation.CONNECTED.allowsCollection())
        assertTrue(SensitiveConnectionConfirmation.CONNECTED_SESSION_PENDING.allowsCollection())
    }
}
