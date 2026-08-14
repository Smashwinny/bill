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
                SensitivePhaseRules.mayLaunchTarget(phase, ownServiceAbsent = true),
            )
            assertFalse(SensitivePhaseRules.mayLaunchTarget(phase, ownServiceAbsent = false))
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

    @Test
    fun warningClassifierOnlySuggestsStrongSecurityWarnings() {
        assertEquals(
            SensitiveWarningKind.SCREEN_CAPTURE_WARNING,
            SensitiveWarningClassifier.classify(
                listOf("检测到您正在共享/录制屏幕", "为保障账户安全，请关闭上述功能")
            )
        )
        assertEquals(
            SensitiveWarningKind.ACCESSIBILITY_WARNING,
            SensitiveWarningClassifier.classify(listOf("检测到无障碍功能存在风险，请关闭"))
        )
        assertEquals(null, SensitiveWarningClassifier.classify(listOf("录屏教程", "欢迎使用")))
        assertEquals(null, SensitiveWarningClassifier.classify(listOf("辅助功能设置")))
    }

    @Test
    fun sensitiveIdentityConfirmationBindsPackageSignatureAndAndroidUser() {
        val selected = SensitiveAppIdentity("bank.pkg", "银行旧名称", "sha256-a", 10L, 7L)

        assertTrue(
            "normal version/label updates with the same signer remain the same principal",
            selected.samePrincipalAs(
                SensitiveAppIdentity("bank.pkg", "银行新名称", "sha256-a", 11L, 7L)
            ),
        )
        assertFalse(
            "a package replaced between selection and confirmation must be rejected",
            selected.samePrincipalAs(
                SensitiveAppIdentity("bank.pkg", "银行旧名称", "sha256-b", 10L, 7L)
            ),
        )
        assertFalse(
            selected.samePrincipalAs(
                SensitiveAppIdentity("bank.pkg", "银行旧名称", "sha256-a", 10L, 8L)
            ),
        )
        assertFalse(
            selected.samePrincipalAs(
                SensitiveAppIdentity("wallet.pkg", "银行旧名称", "sha256-a", 10L, 7L)
            ),
        )
    }

    @Test
    fun sensitiveFastIndexFailsClosedUntilAnAtomicVerifiedSnapshotExists() {
        val index = SensitiveFastProfileIndex()
        val bank = SensitiveAppProfile(
            SensitiveAppIdentity("bank.pkg", "银行", "sha256-a", 1L, 7L),
            SensitiveProfileOrigin.USER_SELECTED,
            1L,
        )

        assertEquals(SensitiveProfileLookupKind.NOT_READY, index.lookup("bank.pkg").kind)
        assertEquals(SensitiveProfileLookupKind.NOT_READY, index.lookup("ordinary.pkg").kind)

        index.replace(listOf(bank), blockedPackages = setOf("replaced.bank"))
        assertEquals(SensitiveProfileLookupKind.ACTIVE, index.lookup("bank.pkg").kind)
        assertEquals(bank, index.lookup("bank.pkg").profile)
        assertEquals(
            SensitiveProfileLookupKind.VERIFIED_NON_SENSITIVE,
            index.lookup("ordinary.pkg").kind,
        )
        assertEquals(SensitiveProfileLookupKind.NOT_READY, index.lookup("replaced.bank").kind)

        index.invalidate("bank.pkg")
        assertEquals(SensitiveProfileLookupKind.NOT_READY, index.lookup("bank.pkg").kind)
        index.reset()
        assertEquals(SensitiveProfileLookupKind.NOT_READY, index.lookup("ordinary.pkg").kind)
    }

    @Test
    fun sensitiveRegistryCoverageGapsAreIsolatedPerPackage() {
        val blockedBank = sensitiveProfileGuardDetector("blocked.bank")
        val ordinaryApp = sensitiveProfileGuardDetector("ordinary.app")

        assertTrue(blockedBank.startsWith("${com.hulk.pillsapp.ledger.GapDetectors.A11Y_PROFILE_GUARD}:"))
        assertFalse(
            "resolving one package must never address another package's open gap",
            blockedBank == ordinaryApp,
        )
        assertEquals(blockedBank, sensitiveProfileGuardDetector("blocked.bank"))
        assertFalse(blockedBank.contains("blocked.bank"))
    }

    @Test
    fun sensitiveGuardWorkIsBoundedAndRetriesOnlyAfterBackoff() {
        val gate = SensitiveGuardWorkGate(retryBackoffMs = 1_000L)
        var openCount = 0
        val refreshTokens = mutableListOf<Long>()

        repeat(1_000) {
            val action = gate.enterGuard("blocked.bank", nowMs = 100L, needsRefresh = true)
            if (action.openGap) openCount += 1
            action.refreshToken?.let(refreshTokens::add)
        }
        assertEquals(1, openCount)
        assertEquals(1, refreshTokens.size)
        assertFalse(gate.finishRefresh("blocked.bank", refreshTokens.single(), false, 200L))

        repeat(1_000) {
            val action = gate.enterGuard("blocked.bank", nowMs = 1_199L, needsRefresh = true)
            assertFalse(action.openGap)
            assertEquals(null, action.refreshToken)
        }
        val retry = gate.enterGuard("blocked.bank", nowMs = 1_200L, needsRefresh = true)
        assertFalse(retry.openGap)
        val retryToken = requireNotNull(retry.refreshToken)
        assertTrue(gate.finishRefresh("blocked.bank", retryToken, true, 1_201L))
        assertFalse("the same resolved interval closes only once", gate.leaveGuard("blocked.bank"))

        var activeOpenCount = 0
        repeat(1_000) {
            val action = gate.enterGuard("active.bank", nowMs = 2_000L, needsRefresh = false)
            if (action.openGap) activeOpenCount += 1
            assertEquals(null, action.refreshToken)
        }
        assertEquals(1, activeOpenCount)
        assertTrue(gate.leaveGuard("active.bank"))
        assertFalse(gate.leaveGuard("active.bank"))
    }

    @Test
    fun sensitiveGuardAllowsOnlyOneGlobalRefreshInFlight() {
        val gate = SensitiveGuardWorkGate(retryBackoffMs = 10L)
        val first = requireNotNull(
            gate.enterGuard("bank.a", 0L, needsRefresh = true).refreshToken
        )
        assertEquals(null, gate.enterGuard("bank.b", 0L, needsRefresh = true).refreshToken)
        assertFalse(gate.finishRefresh("bank.a", first, false, 1L))
        assertEquals(null, gate.enterGuard("bank.a", 10L, needsRefresh = true).refreshToken)
        assertTrue(gate.enterGuard("bank.b", 1L, needsRefresh = true).refreshToken != null)
    }

    @Test
    fun repeatedSensitiveWarningWorkIsCoalescedAndBackedOff() {
        val gate = SensitiveTimedWorkGate(backoffMs = 30_000L)
        var accepted = 0
        repeat(1_000) {
            if (gate.tryStart("bank.pkg|SCREEN_CAPTURE_WARNING", 100L)) accepted += 1
        }
        assertEquals(1, accepted)
        gate.finish("bank.pkg|SCREEN_CAPTURE_WARNING", 200L)
        repeat(1_000) {
            assertFalse(gate.tryStart("bank.pkg|SCREEN_CAPTURE_WARNING", 30_199L))
        }
        assertTrue(gate.tryStart("bank.pkg|SCREEN_CAPTURE_WARNING", 30_200L))
        gate.cancel("bank.pkg|SCREEN_CAPTURE_WARNING")
        assertTrue("queue rejection releases the in-flight claim", gate.tryStart("bank.pkg|SCREEN_CAPTURE_WARNING", 30_200L))
    }

    @Test
    fun usageTransitionsNeverDirectlyRequestRestore() {
        val tracker = SensitiveDepartureTracker("bank.pkg")
        val transient = setOf("com.android.systemui", "com.android.permissioncontroller")

        assertEquals(
            SensitiveMonitorAction.NONE,
            tracker.observe("launcher.pkg", "ledger.pkg", transient),
        )
        assertEquals(
            SensitiveMonitorAction.TARGET_ENTERED,
            tracker.observe("bank.pkg", "ledger.pkg", transient),
        )
        assertEquals(
            SensitiveMonitorAction.NONE,
            tracker.observe("com.android.systemui", "ledger.pkg", transient),
        )
        assertEquals(
            SensitiveMonitorAction.PROMPT_EXIT_CONFIRMATION,
            tracker.observe("sms.pkg", "ledger.pkg", transient),
        )
        assertEquals(
            SensitiveMonitorAction.NONE,
            tracker.observe("camera.pkg", "ledger.pkg", transient),
        )
        assertEquals(
            SensitiveMonitorAction.TARGET_ENTERED,
            tracker.observe("bank.pkg", "ledger.pkg", transient),
        )
        assertEquals(
            SensitiveMonitorAction.PROMPT_EXIT_CONFIRMATION,
            tracker.observe("launcher.pkg", "ledger.pkg", transient),
        )
    }

    @Test
    fun restartedUsageMonitorInheritsDurableEnteredAndPromptedState() {
        val transient = setOf("com.android.systemui")
        val enteredBeforeRestart = SensitiveDepartureTracker(
            targetPackage = "bank.pkg",
            targetEnteredInitially = true,
            promptedSinceLastTargetInitially = false,
        )
        assertEquals(
            "a restart after the five-second lookback must still prompt when the user leaves",
            SensitiveMonitorAction.PROMPT_EXIT_CONFIRMATION,
            enteredBeforeRestart.observe("launcher.pkg", "ledger.pkg", transient),
        )

        val alreadyPromptedBeforeRestart = SensitiveDepartureTracker(
            targetPackage = "bank.pkg",
            targetEnteredInitially = true,
            promptedSinceLastTargetInitially = true,
        )
        assertEquals(
            SensitiveMonitorAction.NONE,
            alreadyPromptedBeforeRestart.observe("launcher.pkg", "ledger.pkg", transient),
        )
        assertEquals(
            SensitiveMonitorAction.TARGET_ENTERED,
            alreadyPromptedBeforeRestart.observe("bank.pkg", "ledger.pkg", transient),
        )
        assertEquals(
            SensitiveMonitorAction.PROMPT_EXIT_CONFIRMATION,
            alreadyPromptedBeforeRestart.observe("sms.pkg", "ledger.pkg", transient),
        )
    }

    @Test
    fun usageEventsAreOrderedAndEqualTimestampTargetSuppressesFalseDeparture() {
        val ordered = SensitiveUsageTransitionOrder.order(
            listOf(
                SensitiveUsageTransition(200L, "camera.pkg"),
                SensitiveUsageTransition(100L, "bank.pkg"),
            ),
            targetPackage = "bank.pkg",
        )
        assertEquals(listOf("bank.pkg", "camera.pkg"), ordered.map { it.packageName })

        val sameTimestamp = SensitiveUsageTransitionOrder.order(
            listOf(
                SensitiveUsageTransition(300L, "camera.pkg"),
                SensitiveUsageTransition(300L, "bank.pkg"),
                SensitiveUsageTransition(300L, "com.android.systemui"),
            ),
            targetPackage = "bank.pkg",
        )
        assertEquals(listOf("bank.pkg"), sameTimestamp.map { it.packageName })
    }

    @Test
    fun staleMonitorStartPromotesOwnerGenerationWithoutLettingOldPollStopIt() {
        val promoted = requireNotNull(
            SensitiveMonitorGenerationRules.promoteForStaleStart(
                ownedSessionId = "session-a",
                currentSessionId = "session-a",
                currentStartId = 1,
                receivedStartId = 2,
            )
        )
        assertEquals(2, promoted)
        assertFalse(
            SensitiveMonitorGenerationRules.mayStop("session-a", promoted, "session-a", 1)
        )
        assertTrue(
            SensitiveMonitorGenerationRules.mayStop("session-a", promoted, "session-a", 2)
        )
        assertEquals(
            null,
            SensitiveMonitorGenerationRules.promoteForStaleStart("session-a", "session-b", 2, 3),
        )
    }
}
