package com.hulk.pillsapp.ledger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class StartupRecoveryContractTest {
    @Test
    fun kernelInitDoesNotDecryptObservationOutboxOnCallerThread() {
        val source = String(
            Files.readAllBytes(Paths.get("src/main/java/com/hulk/pillsapp/ledger/LedgerKernel.kt")),
        )
        val initBody = source.substringAfter("fun init(context: Context)")
            .substringBefore("private val sqlitePlaintextHeader")

        assertFalse(initBody.contains("observationOutbox).pending()"))
        assertTrue(source.contains("fun drainObservationOutbox()"))
        assertTrue(source.contains("observationRecoveryExecutor.submit(::drainObservationOutboxBatch)"))
    }

    @Test
    fun keystoreEntryIsCachedForBatchOutboxRecovery() {
        val source = String(
            Files.readAllBytes(Paths.get("src/main/java/com/hulk/pillsapp/ledger/DbCrypto.kt")),
        )

        assertTrue(source.contains("cachedKeystoreKey"))
        assertTrue(source.contains("loadOrCreateKeystoreKey"))
    }
}
