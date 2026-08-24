package com.hulk.pillsapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class BehaviorDebugEvidenceContractTest {
    @Test
    fun evidenceIsOptionalEncryptedAndExpiring() {
        val source = String(
            Files.readAllBytes(Paths.get("src/main/java/com/hulk/pillsapp/ledger/BehaviorDebugEvidenceStore.kt")),
        )
        val config = String(
            Files.readAllBytes(Paths.get("src/main/res/xml/behavior_accessibility_service.xml")),
        )

        assertTrue(config.contains("android:canTakeScreenshot=\"true\""))
        assertTrue(source.contains("getBoolean(KEY_ENABLED, false)"))
        assertTrue(source.contains("DbCrypto.encryptLocalArtifact"))
        assertTrue(source.contains("TimeUnit.DAYS.toMillis(7)"))
        assertFalse(source.contains("MediaStore"))
    }
}
