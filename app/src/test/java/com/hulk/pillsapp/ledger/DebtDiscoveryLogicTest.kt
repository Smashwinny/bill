package com.hulk.pillsapp.ledger

import com.hulk.pillsapp.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtDiscoveryLogicTest {
    private val testHash: (String) -> String = ::sha256Hex

    @Test
    fun repaymentDayReminderIsBillNoticeNotRepayment() {
        val signal = DebtSignalParser.parse(
            title = "花呗账单提醒",
            body = "本期应还 100.00 元，还款日为每月10日",
            sourceNamespace = "com.eg.android.AlipayGphone",
            source = ObservationSource.NOTIFICATION,
        )
        assertEquals(DebtEventKind.BILL_NOTICE, signal?.eventKind)
        assertEquals(DebtAmountRole.CURRENT_DUE, signal?.amountRole)
        assertEquals(10, signal?.dueDayOfMonth)
    }

    @Test
    fun explicitRepaymentSuccessIsRepaymentSignal() {
        val signal = DebtSignalParser.parse(
            title = "花呗还款成功",
            body = "已成功还款 100.00 元",
            sourceNamespace = "com.eg.android.AlipayGphone",
            source = ObservationSource.NOTIFICATION,
        )
        assertEquals(DebtEventKind.REPAYMENT, signal?.eventKind)
        assertEquals(DebtAmountRole.REPAYMENT_AMOUNT, signal?.amountRole)
    }

    @Test
    fun creditLimitMarketingDoesNotCreateDebtCandidate() {
        val signal = DebtSignalParser.parse(
            title = "花呗额度提升",
            body = "最高可用额度 20000 元，立即领取",
            sourceNamespace = "com.eg.android.AlipayGphone",
            source = ObservationSource.NOTIFICATION,
        )
        assertNull(signal)
    }

    @Test
    fun genericMaskedDigitsWithoutAccountLabelAreNotCardTail() {
        val signal = DebtSignalParser.parse(
            title = "信用卡账单",
            body = "联系电话••1234，本期应还100.00元",
            sourceNamespace = "unknown",
            source = ObservationSource.SMS,
        )
        assertNull(signal?.maskedSuffix)
        // 关联不明的号码只能留下无尾号的疑似线索，不得升级 IDENTIFIED。
        val identity = buildDebtAccountIdentity(signal!!, 0, "unknown", hashMaterial = testHash)
        assertEquals(DebtAccountStatus.SUSPECTED, identity.status)
        assertNull(identity.identityHash)
    }

    @Test
    fun adjacentLabeledMaskedDigitsCanBeCardTailHint() {
        val signal = DebtSignalParser.parse(
            title = "信用卡账单",
            body = "招商银行信用卡••1234，本期应还100.00元",
            sourceNamespace = "95555",
            source = ObservationSource.SMS,
        )
        assertEquals("1234", signal?.maskedSuffix)
    }

    @Test
    fun yearAfterCreditCardLabelIsNeverTreatedAsTail() {
        val signal = DebtSignalParser.parse(
            title = "信用卡账单",
            body = "信用卡2026年8月账单，本期应还100.00元",
            sourceNamespace = "95555",
            source = ObservationSource.SMS,
        )
        assertNull(signal?.maskedSuffix)
    }

    @Test
    fun contactPhoneTailIsNeverTreatedAsCardTail() {
        val signal = DebtSignalParser.parse(
            title = "信用卡账单",
            body = "联系电话尾号1234，本期应还100.00元",
            sourceNamespace = "95555",
            source = ObservationSource.SMS,
        )
        assertNull(signal?.maskedSuffix)
    }

    @Test
    fun bankBillWithoutCreditCardWordBecomesGenericLiabilityHint() {
        val signal = DebtSignalParser.parse(
            title = "招商银行账单提醒",
            body = "本期应还100.00元，最低还款10.00元",
            sourceNamespace = "95555",
            source = ObservationSource.SMS,
        )
        assertEquals(DebtProduct.GENERIC_LIABILITY, signal?.product)
        assertEquals(DebtEventKind.BILL_NOTICE, signal?.eventKind)
        assertNull(signal?.maskedSuffix)
    }

    @Test
    fun debitPurchaseWithoutLiabilityLanguageIsNotDebt() {
        val signal = DebtSignalParser.parse(
            title = "招商银行交易提醒",
            body = "您的账户消费10.00元，余额1000.00元",
            sourceNamespace = "95555",
            source = ObservationSource.SMS,
        )
        assertNull(signal)
    }

    @Test
    fun additionalConsumerLoanBrandCanBeDiscovered() {
        val signal = DebtSignalParser.parse(
            title = "度小满账单",
            body = "本期应还100.00元",
            sourceNamespace = "com.example.finance",
            source = ObservationSource.NOTIFICATION,
        )
        assertEquals(DebtProduct.CONSUMER_LOAN, signal?.product)
        assertEquals("DUXIAOMAN", signal?.institutionCode)
    }

    @Test
    fun debitRepaymentSourceDoesNotOverrideCreditCardTail() {
        val signal = DebtSignalParser.parse(
            title = "信用卡还款成功",
            body = "已从借记卡•••1234还款至信用卡•••5678",
            sourceNamespace = "95555",
            source = ObservationSource.SMS,
        )
        assertEquals("5678", signal?.maskedSuffix)
    }

    @Test
    fun twoExplicitCreditCardTailsAreAmbiguous() {
        val signal = DebtSignalParser.parse(
            title = "信用卡账单",
            body = "信用卡尾号1234和信用卡尾号5678共有本期应还100.00元",
            sourceNamespace = "95555",
            source = ObservationSource.SMS,
        )
        assertNull(signal?.maskedSuffix)
    }

    @Test
    fun unknownNotificationCannotCreateGenericLiabilityFromGenericWords() {
        val signal = DebtSignalParser.parse(
            title = "账单提醒",
            body = "本期应还100.00元，最低还款10.00元",
            sourceNamespace = "com.example.chat",
            source = ObservationSource.NOTIFICATION,
        )
        assertNull(signal)
    }

    @Test
    fun oneObservationCanExposeMultipleNamedDebtProducts() {
        val signals = DebtSignalParser.parseAll(
            title = "还款提醒",
            body = "花呗本期应还100.00元，借呗本期应还200.00元",
            sourceNamespace = "com.eg.android.AlipayGphone",
            source = ObservationSource.NOTIFICATION,
        )
        assertEquals(setOf(DebtProduct.HUABEI, DebtProduct.JIEBEI), signals.map { it.product }.toSet())
    }

    @Test
    fun tailOnlyClustersButNeverConfirmsIdentity() {
        val first = DebtSignalParser.parse(
            "信用卡消费",
            "招商银行信用卡尾号1234消费10.00元",
            "95555",
            ObservationSource.SMS,
        )!!
        val second = DebtSignalParser.parse(
            "信用卡消费",
            "招商银行信用卡尾号5678消费10.00元",
            "95555",
            ObservationSource.SMS,
        )!!
        val a = buildDebtAccountIdentity(first, 0, "95555", hashMaterial = testHash)
        val b = buildDebtAccountIdentity(second, 0, "95555", hashMaterial = testHash)
        assertEquals(DebtAccountStatus.SUSPECTED, a.status)
        assertNull(a.identityHash)
        assertNotEquals(a.clusterHash, b.clusterHash)
    }

    @Test
    fun sameTailFromDifferentSourcesDoesNotAutoMerge() {
        val signal = DebtSignalParser.parse(
            "信用卡消费",
            "招商银行信用卡尾号1234消费10.00元",
            "95555",
            ObservationSource.SMS,
        )!!
        val smsCluster = buildDebtAccountIdentity(signal, 0, "95555", hashMaterial = testHash)
        val appCluster = buildDebtAccountIdentity(
            signal,
            0,
            "com.example.bank",
            hashMaterial = testHash,
        )
        assertNotEquals(smsCluster.clusterHash, appCluster.clusterHash)
    }

    @Test
    fun accountSwitchCannotMergeWithoutStableAccountId() {
        val signal = DebtSignalParser.parse(
            "花呗账单",
            "本期应还100.00元",
            "com.eg.android.AlipayGphone",
            ObservationSource.NOTIFICATION,
        )!!
        val appCluster = buildDebtAccountIdentity(
            signal,
            0,
            "com.eg.android.AlipayGphone",
            hashMaterial = testHash,
        )
        val smsCluster = buildDebtAccountIdentity(signal, 0, "106-service", hashMaterial = testHash)
        assertEquals(DebtAccountStatus.SUSPECTED, appCluster.status)
        assertNotEquals(appCluster.clusterHash, smsCluster.clusterHash)
    }

    @Test
    fun unverifiedBillImportCannotEstablishBaseline() {
        val signal = DebtSignalParser.parse(
            "花呗官方账单",
            "账单账户 ABCDEF123456，全部待还 8000.00 元",
            "selected-document",
            ObservationSource.BILL_IMPORT,
        )!!
        val identity = buildDebtAccountIdentity(signal, 0, "selected-document", hashMaterial = testHash)
        assertEquals(DebtEvidenceStrength.IMPORTED_UNVERIFIED, signal.evidenceStrength)
        assertEquals(DebtAccountStatus.SUSPECTED, identity.status)
    }

    @Test
    fun excludedAndConflictedStatesAreNotOverwrittenByNewSignals() {
        assertEquals(
            DebtAccountStatus.EXCLUDED,
            transitionDiscoveryStatus(DebtAccountStatus.EXCLUDED, DebtAccountStatus.IDENTIFIED),
        )
        assertEquals(
            DebtAccountStatus.CONFLICTED,
            transitionDiscoveryStatus(DebtAccountStatus.CONFLICTED, DebtAccountStatus.BASELINED),
        )
        assertEquals(
            DebtAccountStatus.IDENTIFIED,
            transitionDiscoveryStatus(DebtAccountStatus.SUSPECTED, DebtAccountStatus.IDENTIFIED),
        )
    }

    @Test
    fun hashesContainNoPlainTailOrStableAccountId() {
        val signal = DebtSignalParser.parse(
            "信用卡账单",
            "招商银行信用卡尾号1234，账户号 ABCDEF123456，本期应还100.00元",
            "95555",
            ObservationSource.SMS,
        )!!
        val identity = buildDebtAccountIdentity(signal, 0, "95555", hashMaterial = testHash)
        assertTrue(identity.clusterHash.length == 64)
        assertNull(identity.identityHash)
        assertTrue("1234" !in identity.clusterHash)
        assertTrue("ABCDEF123456" !in identity.clusterHash)
    }
}
