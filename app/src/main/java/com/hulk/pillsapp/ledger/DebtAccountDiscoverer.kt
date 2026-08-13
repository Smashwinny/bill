package com.hulk.pillsapp.ledger

import java.util.UUID

/**
 * M3 历史/增量负债账户发现器。
 *
 * 发现结果与候选交易完全分离：这里不创建 CanonicalTransaction 或 LedgerEntry，避免把
 * “账单金额、信用额度、还款本金”误计为消费。每条 observation_revision 独立审计；解析器
 * 升级只 supersede 旧版本，不删除证据链。
 */
object DebtAccountDiscoverer {

    fun processPendingForObservation(
        db: LedgerDatabase,
        observationId: Long,
        hashMaterial: (String) -> String,
    ): Int {
        val inputs = db.debtAccountDao().pendingDiscoveryForObservation(
            observationId,
            DEBT_DISCOVERY_PARSER_VERSION,
        )
        inputs.forEach { process(db, it, hashMaterial) }
        return inputs.size
    }

    private fun process(
        db: LedgerDatabase,
        input: DebtDiscoveryInput,
        hashMaterial: (String) -> String,
    ): Boolean {
        val parsed = runCatching {
            DebtSignalParser.parseAll(
                title = input.title,
                body = input.body,
                sourceNamespace = input.packageName,
                source = input.source,
            )
        }
        val signals = parsed.getOrNull().orEmpty()
        val now = System.currentTimeMillis()
        db.runInTransaction {
            val dao = db.debtAccountDao()
            dao.supersedeEvidenceVersion(input.observationId, input.contentHash)
            dao.supersedeScanVersion(input.observationId, input.contentHash)
            if (parsed.isFailure || signals.isEmpty()) {
                dao.insertScan(
                    AccountDiscoveryScanEntity(
                        observationId = input.observationId,
                        contentHash = input.contentHash,
                        parserVersion = DEBT_DISCOVERY_PARSER_VERSION,
                        isCurrent = true,
                        result = if (parsed.isFailure) {
                            DiscoveryScanResult.FAILED
                        } else {
                            DiscoveryScanResult.NO_MATCH
                        },
                        scannedAtMs = now,
                    )
                )
                dao.retireUnreferencedCandidates(now)
                return@runInTransaction
            }

            signals.forEach { signal ->
                val identity = buildDebtAccountIdentity(
                    signal = signal,
                    userHandle = input.userHandle,
                    sourceNamespace = input.packageName,
                    hashMaterial = hashMaterial,
                )
                val proposed = DebtAccountEntity(
                    publicId = UUID.randomUUID().toString(),
                    clusterHash = identity.clusterHash,
                    identityHash = identity.identityHash,
                    product = signal.product,
                    institutionCode = signal.institutionCode,
                    institutionLabel = signal.institutionLabel,
                    displayLabel = identity.displayLabel,
                    maskedSuffix = signal.maskedSuffix,
                    userHandle = input.userHandle,
                    status = identity.status,
                    confidence = identity.confidence,
                    lastEventKind = signal.eventKind,
                    lastEvidenceStrength = signal.evidenceStrength,
                    dueDayOfMonth = signal.dueDayOfMonth,
                    firstSeenAtMs = input.postTimeMs,
                    lastSeenAtMs = input.postTimeMs,
                    createdAtMs = now,
                    updatedAtMs = now,
                )

                val clusterExisting = dao.findByClusterHash(identity.clusterHash)
                val identityExisting = identity.identityHash?.let(dao::findByIdentityHash)
                val account = when {
                    clusterExisting != null && identityExisting != null &&
                        clusterExisting.id != identityExisting.id -> {
                        // 聚类与稳定身份指向不同候选：保留两者并显式冲突，绝不自动吞并。
                        dao.updateAccount(
                            clusterExisting.copy(
                                status = DebtAccountStatus.CONFLICTED,
                                updatedAtMs = now,
                            )
                        )
                        dao.updateAccount(
                            identityExisting.copy(
                                status = DebtAccountStatus.CONFLICTED,
                                updatedAtMs = now,
                            )
                        )
                        clusterExisting.copy(
                            status = DebtAccountStatus.CONFLICTED,
                            updatedAtMs = now,
                        )
                    }
                    clusterExisting != null -> updateExisting(dao, clusterExisting, proposed, now)
                    identityExisting != null -> updateExisting(dao, identityExisting, proposed, now)
                    else -> {
                        val insertedId = dao.insertAccountIgnore(proposed)
                        if (insertedId == -1L) {
                            dao.findByClusterHash(identity.clusterHash)
                                ?: identity.identityHash?.let(dao::findByIdentityHash)
                                ?: error("负债账户唯一约束冲突后无法读取")
                        } else {
                            proposed.copy(id = insertedId)
                        }
                    }
                }

                val signalFingerprint = hashMaterial(
                    listOf(
                        "DEBT_SIGNAL",
                        identity.clusterHash,
                        signal.eventKind.name,
                        signal.amountRole.name,
                        signal.amountCents?.toString().orEmpty(),
                        signal.dueDayOfMonth?.toString().orEmpty(),
                    ).joinToString(":"),
                )
                dao.insertEvidence(
                    DebtAccountEvidenceEntity(
                        observationId = input.observationId,
                        accountId = account.id,
                        contentHash = input.contentHash,
                        parserVersion = DEBT_DISCOVERY_PARSER_VERSION,
                        signalFingerprint = signalFingerprint,
                        isCurrent = true,
                        eventKind = signal.eventKind,
                        strength = signal.evidenceStrength,
                        amountRole = signal.amountRole,
                        amountCents = signal.amountCents,
                        dueDayOfMonth = signal.dueDayOfMonth,
                        observedAtMs = input.postTimeMs,
                        createdAtMs = now,
                    )
                )
            }
            dao.insertScan(
                AccountDiscoveryScanEntity(
                    observationId = input.observationId,
                    contentHash = input.contentHash,
                    parserVersion = DEBT_DISCOVERY_PARSER_VERSION,
                    isCurrent = true,
                    result = DiscoveryScanResult.MATCHED,
                    scannedAtMs = now,
                )
            )
            dao.retireUnreferencedCandidates(now)
        }
        return signals.isNotEmpty()
    }

    private fun updateExisting(
        dao: DebtAccountDao,
        existing: DebtAccountEntity,
        proposed: DebtAccountEntity,
        now: Long,
    ): DebtAccountEntity {
        val updated = existing.copy(
            identityHash = existing.identityHash ?: proposed.identityHash,
            institutionCode = if (existing.institutionCode == "UNKNOWN") {
                proposed.institutionCode
            } else {
                existing.institutionCode
            },
            institutionLabel = if (existing.institutionCode == "UNKNOWN") {
                proposed.institutionLabel
            } else {
                existing.institutionLabel
            },
            displayLabel = if (existing.maskedSuffix == null && proposed.maskedSuffix != null) {
                proposed.displayLabel
            } else {
                existing.displayLabel
            },
            maskedSuffix = existing.maskedSuffix ?: proposed.maskedSuffix,
            status = transitionDiscoveryStatus(existing.status, proposed.status),
            confidence = maxOf(existing.confidence, proposed.confidence),
            lastEventKind = if (proposed.lastSeenAtMs >= existing.lastSeenAtMs) {
                proposed.lastEventKind
            } else {
                existing.lastEventKind
            },
            lastEvidenceStrength = if (proposed.lastSeenAtMs >= existing.lastSeenAtMs) {
                proposed.lastEvidenceStrength
            } else {
                existing.lastEvidenceStrength
            },
            dueDayOfMonth = proposed.dueDayOfMonth ?: existing.dueDayOfMonth,
            firstSeenAtMs = minOf(existing.firstSeenAtMs, proposed.firstSeenAtMs),
            lastSeenAtMs = maxOf(existing.lastSeenAtMs, proposed.lastSeenAtMs),
            updatedAtMs = now,
        )
        dao.updateAccount(updated)
        return updated
    }

    /** 返回本次处理的修订条数；重复执行在没有修订/解析器升级时返回 0。 */
    fun drain(
        db: LedgerDatabase,
        hashMaterial: (String) -> String,
        batchSize: Int = 100,
    ): Int {
        var processed = 0
        while (true) {
            val batch = db.debtAccountDao().pendingDiscovery(DEBT_DISCOVERY_PARSER_VERSION, batchSize)
            if (batch.isEmpty()) break
            batch.forEach {
                process(db, it, hashMaterial)
                processed++
            }
        }
        return processed
    }
}
