package com.hulk.pillsapp.ledger

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ObservationOutboxBatchTest {
    @Test
    fun selectsOnlyOldestBoundedPendingFiles() {
        val directory = Files.createTempDirectory("observation-outbox-test").toFile()
        try {
            val newest = directory.resolve("c.observation").apply { writeText("c"); setLastModified(30) }
            val oldest = directory.resolve("a.observation").apply { writeText("a"); setLastModified(10) }
            val middle = directory.resolve("b.observation").apply { writeText("b"); setLastModified(20) }
            directory.resolve("ignored.tmp").writeText("x")

            val selected = selectOldestPendingFiles(
                files = listOf(newest, oldest, middle),
                suffix = ".observation",
                limit = 2,
            )

            assertEquals(listOf(oldest, middle), selected)
        } finally {
            directory.deleteRecursively()
        }
    }
}
