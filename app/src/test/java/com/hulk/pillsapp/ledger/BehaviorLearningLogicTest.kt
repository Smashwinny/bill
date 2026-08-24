package com.hulk.pillsapp.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorLearningLogicTest {
    @Test
    fun ambiguousGapClosesOnlyAfterEveryConflictIsResolved() {
        assertFalse(shouldCloseAmbiguousRepeatGap(2))
        assertFalse(shouldCloseAmbiguousRepeatGap(1))
        assertTrue(shouldCloseAmbiguousRepeatGap(0))
    }

    @Test
    fun onlyFinalSuccessCreatesTerminalAndExtractsUniqueAmount() {
        assertNull(BehaviorTextClassifier.terminal(listOf("订单处理中，待支付 10.00元")))
        assertNull(BehaviorTextClassifier.terminal(listOf("支付失败 ￥10.00")))
        assertNull(BehaviorTextClassifier.terminal(listOf("商户支付成功率 99.9%")))

        val payment = BehaviorTextClassifier.terminal(listOf("支付成功", "￥10.00"))
        assertEquals(BehaviorKind.PAYMENT, payment?.kind)
        assertEquals(1_000L, payment?.amountCents)

        val refund = BehaviorTextClassifier.terminal(listOf("退款到账 8.88元"))
        assertEquals(BehaviorKind.REFUND, refund?.kind)
        assertEquals(888L, refund?.amountCents)
    }

    @Test
    fun ambiguousAmountsNeverEnterAutomaticPath() {
        val terminal = BehaviorTextClassifier.terminal(
            listOf("支付成功，订单金额 10.00元，优惠后 ￥8.00")
        )!!
        assertEquals(2, terminal.distinctAmountCount)
        assertNull(terminal.amountCents)
    }

    @Test
    fun fifthConsecutiveHumanConfirmationEnablesAutoAndCorrectionDisablesIt() {
        var template = BehaviorTemplateEntity(
            templateKey = "template",
            packageName = "com.example.pay",
            kind = BehaviorKind.PAYMENT,
            routeSignature = "route",
            appVersionCode = 1,
            positiveCount = 0,
            negativeCount = 0,
            consecutivePositiveCount = 0,
            autoEnabled = false,
            createdAtMs = 1,
            updatedAtMs = 1,
        )
        repeat(4) { template = BehaviorLearningPolicy.afterPositive(template, it + 2L) }
        assertFalse(template.autoEnabled)
        template = BehaviorLearningPolicy.afterPositive(template, 10)
        assertTrue(template.autoEnabled)
        template = BehaviorLearningPolicy.afterNegative(template, 11)
        assertFalse(template.autoEnabled)
        assertEquals(0, template.consecutivePositiveCount)
    }

    @Test
    fun autoRequiresStrongCurrentSignalNotOnlyLearnedTemplate() {
        val template = BehaviorTemplateEntity(
            templateKey = "template",
            packageName = "com.example.pay",
            kind = BehaviorKind.PAYMENT,
            routeSignature = "route",
            appVersionCode = 1,
            positiveCount = 5,
            negativeCount = 0,
            consecutivePositiveCount = 5,
            autoEnabled = true,
            createdAtMs = 1,
            updatedAtMs = 1,
        )
        val base = BehaviorSignal(
            occurrenceId = "occurrence",
            clipId = "clip",
            packageName = "com.example.pay",
            kind = BehaviorKind.PAYMENT,
            amountCents = 100,
            occurredAtMs = 10,
            templateKey = "template",
            confidence = 95,
            consumedIntent = true,
            routeSignature = "route",
            appVersionCode = 1,
            ambiguousRepeat = false,
            featureSummary = "safe",
        )
        assertTrue(BehaviorLearningPolicy.mayAutoRecord(template, base, true))
        assertFalse(BehaviorLearningPolicy.mayAutoRecord(template, base.copy(amountCents = null), true))
        assertFalse(BehaviorLearningPolicy.mayAutoRecord(template, base.copy(consumedIntent = false), true))
        assertFalse(BehaviorLearningPolicy.mayAutoRecord(template.copy(negativeCount = 1), base, true))
        assertFalse(BehaviorLearningPolicy.mayAutoRecord(template, base, false))
        assertFalse(BehaviorLearningPolicy.mayAutoRecord(template, base.copy(ambiguousRepeat = true), true))
    }

    @Test
    fun templateIgnoresNoisyContentEventCountButKeepsWindowIdentity() {
        val terminal = BehaviorTextClassifier.terminal(listOf("支付成功 ￥1.00"))!!
        val base = listOf(
            BehaviorWindowFeature(1, "com.example.pay", "INTENT_CLICK", "intent-class", true),
            BehaviorWindowFeature(2, "com.example.pay", "CONTENT", "terminal-class", false),
        )
        val noisy = listOf(
            BehaviorWindowFeature(1, "com.example.pay", "INTENT_CLICK", "intent-class", true),
            BehaviorWindowFeature(1, "com.example.pay", "CONTENT", "noise", false),
            BehaviorWindowFeature(2, "com.example.pay", "CONTENT", "terminal-class", false),
        )
        val emission = BehaviorEpisodeEmission(
            occurrenceId = "occurrence-one",
            clipId = "one",
            consumedIntent = true,
            routeSignature = "route",
            appVersionCode = 1,
        )
        val one = BehaviorTextClassifier.buildSignal("one", "com.example.pay", 2, terminal, base, emission)
        val two = BehaviorTextClassifier.buildSignal("two", "com.example.pay", 2, terminal, noisy, emission.copy(clipId = "two"))
        assertEquals(one.templateKey, two.templateKey)
        assertFalse(one.clipId == two.clipId)
    }

    @Test
    fun episodeConsumesOneTerminalButTwoIndependentSameAmountClicksStaySeparate() {
        var next = 0
        val tracker = BehaviorEpisodeTracker { "clip-${++next}" }
        tracker.onContext("com.example.pay", 7, 100)
        tracker.onIntent("com.example.pay", 100, "route", 1)
        val first = tracker.onTerminal("com.example.pay", 7, "PAYMENT|100", 200, 1)
        val duplicateRefresh = tracker.onTerminal("com.example.pay", 7, "PAYMENT|100", 2_000, 1)
        tracker.onIntent("com.example.pay", 2_100, "route", 1)
        val second = tracker.onTerminal("com.example.pay", 7, "PAYMENT|100", 2_200, 1)

        assertTrue(first?.consumedIntent == true)
        assertTrue(duplicateRefresh.ambiguousRepeat)
        assertEquals(first?.clipId, duplicateRefresh.clipId)
        assertFalse(first?.occurrenceId == duplicateRefresh.occurrenceId)
        assertTrue(second?.consumedIntent == true)
        assertFalse(first?.clipId == second?.clipId)
    }

    @Test
    fun failedPersistenceRestoresConsumedEpisodeForRetry() {
        val tracker = BehaviorEpisodeTracker { "durable-clip" }
        tracker.onIntent("com.example.pay", 100, "route", 1)
        val first = tracker.onTerminal("com.example.pay", 9, "PAYMENT|100", 200, 1)
        tracker.onPersistenceFailed("com.example.pay", 9, "PAYMENT|100", 201, first)
        val retried = tracker.onTerminal("com.example.pay", 9, "PAYMENT|100", 300, 1)
        assertTrue(retried.consumedIntent)
        assertEquals(first.clipId, retried.clipId)
    }
}
