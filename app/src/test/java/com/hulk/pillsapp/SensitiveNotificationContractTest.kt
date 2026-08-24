package com.hulk.pillsapp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class SensitiveNotificationContractTest {
    @Test
    fun restoreNotificationCardAndActionUseTheSamePendingIntent() {
        val source = String(
            Files.readAllBytes(Paths.get("src/main/java/com/hulk/pillsapp/SensitiveAppMode.kt")),
        )
        val builder = source.substringAfter("private fun sessionNotificationBuilder(")
            .substringBefore("\n    }\n}")

        assertTrue(builder.contains(".setContentIntent(restore)"))
        assertTrue(builder.contains(".addAction(0, \"已离开，立即恢复\", restore)"))
    }
}
