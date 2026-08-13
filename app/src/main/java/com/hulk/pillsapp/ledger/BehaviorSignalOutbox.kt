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
 * 无障碍回调的极短持久待办。内容只有已经脱敏的 [BehaviorSignal]，没有页面原文或截图。
 * 文件先 fsync 再同目录原子改名；数据库提交成功后删除，崩溃重放由 occurrence receipt 保证幂等。
 */
class BehaviorSignalOutbox(private val directory: File) {
    private companion object {
        const val MAGIC = 0x42485631 // BHV1
        const val FORMAT_VERSION = 2
        const val SUFFIX = ".behavior"
    }

    data class Pending(val file: File, val signal: BehaviorSignal)

    @Synchronized
    fun stage(signal: BehaviorSignal): File {
        check(directory.exists() || directory.mkdirs()) { "behavior outbox unavailable" }
        val target = File(directory, sha256Hex(signal.occurrenceId) + SUFFIX)
        if (target.exists()) return target
        val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            val serialized = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(BufferedOutputStream(bytes)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeUTF(signal.occurrenceId)
                    output.writeUTF(signal.clipId)
                    output.writeUTF(signal.packageName)
                    output.writeUTF(signal.kind.name)
                    output.writeBoolean(signal.amountCents != null)
                    signal.amountCents?.let(output::writeLong)
                    output.writeLong(signal.occurredAtMs)
                    output.writeUTF(signal.templateKey)
                    output.writeInt(signal.confidence)
                    output.writeBoolean(signal.consumedIntent)
                    output.writeUTF(signal.routeSignature)
                    output.writeLong(signal.appVersionCode)
                    output.writeBoolean(signal.ambiguousRepeat)
                    output.writeUTF(signal.featureSummary)
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
                error("behavior outbox atomic rename failed")
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
            if (!file.delete()) error("behavior outbox completion failed")
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

    private fun read(file: File): BehaviorSignal =
        DataInputStream(
            BufferedInputStream(
                ByteArrayInputStream(DbCrypto.decryptLocalArtifact(FileInputStream(file).use { it.readBytes() }))
            )
        ).use { input ->
            check(input.readInt() == MAGIC) { "invalid behavior outbox magic" }
            check(input.readInt() == FORMAT_VERSION) { "unsupported behavior outbox version" }
            val occurrenceId = input.readUTF()
            val clipId = input.readUTF()
            val packageName = input.readUTF()
            val kind = BehaviorKind.valueOf(input.readUTF())
            val amount = if (input.readBoolean()) input.readLong() else null
            val occurredAtMs = input.readLong()
            val templateKey = input.readUTF()
            val confidence = input.readInt()
            val consumedIntent = input.readBoolean()
            val routeSignature = input.readUTF()
            val appVersionCode = input.readLong()
            val ambiguousRepeat = input.readBoolean()
            val featureSummary = input.readUTF()
            check(input.read() == -1) { "trailing behavior outbox bytes" }
            BehaviorSignal(
                occurrenceId = occurrenceId,
                clipId = clipId,
                packageName = packageName,
                kind = kind,
                amountCents = amount,
                occurredAtMs = occurredAtMs,
                templateKey = templateKey,
                confidence = confidence,
                consumedIntent = consumedIntent,
                routeSignature = routeSignature,
                appVersionCode = appVersionCode,
                ambiguousRepeat = ambiguousRepeat,
                featureSummary = featureSummary,
            )
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
