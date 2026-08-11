package com.hulk.pillsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentProbeSessionTest {
    @Test
    fun sessionCanStartAndEndAndRecordNotificationPresence() {
        val config = ProbeSessionConfig(
            sessionId = "session-1",
            channelName = "微信",
            packageName = "com.tencent.mm",
            scenario = ProbeScenario.FOREGROUND,
            action = ProbeAction.SUCCESS_PAYMENT,
            startedAtMs = 1000L,
        )
        val runtime = ProbeSessionRuntime(config)

        runtime.appendEvent(
            NotificationEvent(
                notificationKey = "key-1",
                packageName = "com.tencent.mm",
                postedAtMs = 1500L,
                receivedAtMs = 1500L,
                title = "支付成功",
                body = "¥12.30 商户A",
                contentHash = sha256Hex("x"),
            ),
            reusedNotificationKey = false,
        )

        val result = runtime.finish(2000L)
        assertEquals(1, result.observations.size)
        assertEquals(config, result.config)
        assertFalse(result.observations[0].reusedNotificationKey)
        assertTrue(result.receivedAnyNotification())
    }

    @Test
    fun sameNotificationKeyCanBeMarkedAsReused() {
        val config = ProbeSessionConfig(
            sessionId = "session-2",
            channelName = "支付宝",
            packageName = "com.eg.android.Alipay",
            scenario = ProbeScenario.BACKGROUND,
            action = ProbeAction.FULL_REFUND,
            startedAtMs = 1000L,
        )
        val runtime = ProbeSessionRuntime(config)

        runtime.appendEvent(
            NotificationEvent(
                notificationKey = "dup",
                packageName = "com.eg.android.Alipay",
                postedAtMs = 1100L,
                receivedAtMs = 1100L,
                title = "退款成功",
                body = "订单号: A001",
                contentHash = sha256Hex("refund"),
            ),
            reusedNotificationKey = false,
        )
        runtime.appendEvent(
            NotificationEvent(
                notificationKey = "dup",
                packageName = "com.eg.android.Alipay",
                postedAtMs = 1200L,
                receivedAtMs = 1200L,
                title = "退款成功",
                body = "订单号: A001",
                contentHash = sha256Hex("refund"),
            ),
            reusedNotificationKey = true,
        )

        val result = runtime.finish(1300L)
        assertEquals(2, result.observations.size)
        assertTrue(result.observations[1].reusedNotificationKey)
        assertFalse(result.observations[0].reusedNotificationKey)
        assertEquals(1, result.observations.count { it.reusedNotificationKey })
    }

    @Test
    fun fieldPresenceProbeAndNoSensitiveOutputInReport() {
        val session = ProbeSessionResult(
            config = ProbeSessionConfig(
                sessionId = "session-3",
                channelName = "银行A",
                packageName = "com.example.bank",
                scenario = ProbeScenario.LOCK_SCREEN,
                action = ProbeAction.PARTIAL_REFUND,
                startedAtMs = 1000L,
            ),
            endedAtMs = 2000L,
            observations = listOf(
                ProbeSessionObservation(
                    notificationKeyHash = "khash1",
                    postedAtMs = 1500L,
                    receivedAtMs = 1500L,
                    reusedNotificationKey = false,
                    fieldPresence = ProbeFieldPresence(
                        hasAmount = true,
                        hasMerchantHint = true,
                        hasOrderHint = true,
                        hasExplicitSuccessOrRefund = true,
                    ),
                )
            ),
        )

        val fields = analyzeProbeFields(
            title = "你的订单订单号ORD-2026-0001已支付成功",
            body = "付款成功，金额120元，商户：安全测试店"
        )
        assertTrue(fields.hasAmount)
        assertTrue(fields.hasMerchantHint)
        assertTrue(fields.hasOrderHint)
        assertTrue(fields.hasExplicitSuccessOrRefund)

        val report = buildProbeReportText(
            ProbeReportBundle(
                sessions = listOf(session),
                appVersionName = "1.0.4-t04",
                appVersionCode = 4,
            )
        )
        assertTrue(report.contains("金额/商户/订单/语义"))
        assertTrue(report.contains("key=khash1"))
        assertFalse(report.contains("ORD-2026-0001"))
        assertFalse(report.contains("120元"))
        assertFalse(report.contains("安全测试店"))
    }

    @Test
    fun noNotificationSessionIsRecordedInCoverage() {
        val runtime = ProbeSessionRuntime(
            ProbeSessionConfig(
                sessionId = "session-4",
                channelName = "银行B",
                packageName = "com.example.bank",
                scenario = ProbeScenario.BACKGROUND,
                action = ProbeAction.SUCCESS_PAYMENT,
                startedAtMs = 1000L,
            )
        )
        val result = runtime.finish(5000L)
        assertFalse(result.receivedAnyNotification())
        assertEquals("未收到通知", result.coverageText())
        assertEquals("0/0", result.amountCoverage())
        assertEquals("0/0", result.merchantCoverage())
    }

    @Test
    fun t04RepositoryStartAndEndCollectsEvents() {
        ProbeSessionRepository.clearCompletedSessions()
        val started = ProbeSessionRepository.startSession(
            channelName = "自选银行",
            packageName = "com.example.bank",
            scenario = ProbeScenario.FOREGROUND,
            action = ProbeAction.SUCCESS_PAYMENT,
            startedAtMs = 1000L,
        )
        assertTrue(started)
        assertNotNull(ProbeSessionRepository.activeSessionConfig.value)

        ProbeSessionRepository.onNotificationEvent(
            NotificationEvent(
                notificationKey = "k1",
                packageName = "com.example.bank",
                postedAtMs = 2000L,
                receivedAtMs = 2000L,
                title = "支付成功",
                body = "实付 ¥99.00 商户A",
                contentHash = sha256Hex("pay"),
            ),
            keyReused = false,
        )

        val ended = ProbeSessionRepository.endSession(3000L)
        assertNotNull(ended)
        assertTrue(ended!!.observations.isNotEmpty())
        assertEquals(1, ProbeSessionRepository.completedSessions.value.size)
        assertNull(ProbeSessionRepository.activeSessionConfig.value)

        ProbeSessionRepository.clearCompletedSessions()
    }
}
