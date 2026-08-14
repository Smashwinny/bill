package com.hulk.pillsapp.ledger

import com.hulk.pillsapp.sha256Hex
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * 通知/SMS 系统回调的加密持久待办。回调先 fsync 文件，再尝试数据库短事务；数据库已提交
 * 但文件尚未删除时，重放会以 source/key/hash/receivedAt 判定已提交或过期，不增加重复计数。
 */
class ObservationOutbox(private val directory: File) {
    private companion object {
        const val MAGIC = 0x4f425331 // OBS1
        const val FORMAT_VERSION = 1
        const val SUFFIX = ".observation"
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024
    }

    data class Pending(val file: File, val observation: RawObservationEntity)

    @Synchronized
    fun stage(observation: RawObservationEntity): File {
        check(observation.id == 0L) { "outbox only accepts unpersisted observations" }
        check(directory.exists() || directory.mkdirs()) { "observation outbox unavailable" }
        val identity = listOf(
            observation.source.name,
            observation.sourceKey,
            observation.contentHash,
        ).joinToString("\u0000")
        val target = File(directory, sha256Hex(identity) + SUFFIX)
        if (target.exists()) return target
        val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            val serialized = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(BufferedOutputStream(bytes)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeText(observation.source.name)
                    output.writeText(observation.sourceKey)
                    output.writeInt(observation.userHandle)
                    output.writeText(observation.packageName)
                    output.writeLong(observation.postTimeMs)
                    output.writeLong(observation.receivedAtMs)
                    output.writeText(observation.title)
                    output.writeText(observation.body)
                    output.writeText(observation.contentHash)
                    output.writeText(observation.capturePath.name)
                    output.writeText(observation.parseState.name)
                    output.writeLong(observation.duplicateCount)
                    output.writeLong(observation.createdAtMs)
                    output.flush()
                }
                bytes.toByteArray()
            }
            val encrypted = DbCrypto.encryptLocalArtifact(serialized)
            FileOutputStream(temporary).use { stream ->
                stream.write(encrypted)
                stream.flush()
                stream.fd.sync()
            }
            if (!temporary.renameTo(target) && !target.exists()) {
                error("observation outbox atomic rename failed")
            }
            syncDirectory()
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    @Synchronized
    fun complete(file: File) {
        if (file.exists()) {
            check(file.delete()) { "observation outbox completion failed" }
            syncDirectory()
        }
    }

    @Synchronized
    fun pending(): List<Pending> {
        if (!directory.exists()) return emptyList()
        return directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            .orEmpty()
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .map { Pending(it, read(it)) }
    }

    @Synchronized
    fun hasPending(): Boolean = directory.exists() &&
        directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            .orEmpty()
            .isNotEmpty()

    private fun read(file: File): RawObservationEntity =
        DataInputStream(
            BufferedInputStream(
                ByteArrayInputStream(DbCrypto.decryptLocalArtifact(FileInputStream(file).use { it.readBytes() }))
            )
        ).use { input ->
            check(input.readInt() == MAGIC) { "invalid observation outbox magic" }
            check(input.readInt() == FORMAT_VERSION) { "unsupported observation outbox version" }
            val observation = RawObservationEntity(
                source = ObservationSource.valueOf(input.readText()),
                sourceKey = input.readText(),
                userHandle = input.readInt(),
                packageName = input.readText(),
                postTimeMs = input.readLong(),
                receivedAtMs = input.readLong(),
                title = input.readText(),
                body = input.readText(),
                contentHash = input.readText(),
                capturePath = CapturePath.valueOf(input.readText()),
                parseState = ParseState.valueOf(input.readText()),
                duplicateCount = input.readLong(),
                createdAtMs = input.readLong(),
            )
            check(input.read() == -1) { "trailing observation outbox bytes" }
            observation
        }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_TEXT_BYTES) { "observation outbox field too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        check(size in 0..MAX_TEXT_BYTES) { "invalid observation outbox field size" }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun syncDirectory() {
        val directoryFd = android.system.Os.open(
            directory.absolutePath,
            android.system.OsConstants.O_RDONLY,
            0,
        )
        try {
            android.system.Os.fsync(directoryFd)
        } finally {
            android.system.Os.close(directoryFd)
        }
    }
}
