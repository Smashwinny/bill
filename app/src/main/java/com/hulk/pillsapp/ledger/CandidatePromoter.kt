package com.hulk.pillsapp.ledger

/**
 * 观察 → 候选交易的异步推进器（V1.1 §5 阶段划分）。
 *
 * 实时层规则：
 * - 有强标识：同摘要只保留一笔交易，其余观察作为证据并入（V1 §5.2）；
 * - 无强标识：每条观察独立成候选，绝不按金额/时间/商户合并（V1 §5.1）；
 * - 任何解析异常：保持 PENDING_PARSE/PARSE_FAILED，可重试，永不静默丢弃。
 */
object CandidatePromoter {

    fun process(db: LedgerDatabase, observationId: Long) {
        val observationDao = db.observationDao()
        val observation = observationDao.findById(observationId) ?: return
        if (observation.parseState != ParseState.PENDING_PARSE) return

        try {
            val signal = FinancialSignalParser.parse(observation.title, observation.body)
            if (signal == null) {
                observationDao.updateParseState(observation.id, ParseState.IGNORED_NON_FINANCIAL)
                return
            }
            val strongHash = signal.strongId?.let {
                strongIdHash(
                    source = observation.source,
                    sourceNamespace = observation.packageName,
                    userHandle = observation.userHandle,
                    idKind = "LABELED_OR_BARE_ID",
                    strongId = it,
                )
            }
            val tx = CanonicalTransactionEntity(
                strongIdHash = strongHash,
                type = when (signal.direction) {
                    SignalDirection.EXPENSE -> TxType.PAYMENT
                    SignalDirection.INCOME -> TxType.INCOME
                    SignalDirection.REFUND -> TxType.REFUND
                    SignalDirection.UNKNOWN -> TxType.UNKNOWN
                },
                status = TxStatus.DETECTED,
                amountCents = signal.amountCents,
                merchantHint = null,
                occurredAtMs = observation.postTimeMs,
                backfilledFrom = null,
                createdAtMs = System.currentTimeMillis(),
            )
            db.canonicalDao().createCandidateWithEvidence(
                tx = tx,
                observationId = observation.id,
                matchReason = if (strongHash != null) "STRONG_ID" else "WEAK_OBSERVATION_ONLY",
                nowMs = System.currentTimeMillis(),
            )
            observationDao.updateParseState(observation.id, ParseState.PARSED)
        } catch (_: Throwable) {
            observationDao.updateParseState(observation.id, ParseState.PARSE_FAILED)
        }
    }
}
