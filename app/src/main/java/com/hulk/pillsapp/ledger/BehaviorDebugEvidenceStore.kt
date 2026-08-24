package com.hulk.pillsapp.ledger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

object BehaviorDebugEvidenceStore {
    private const val PREFS = "behavior_debug_evidence"
    private const val KEY_ENABLED = "enabled"
    private const val DIRECTORY = "behavior_debug_evidence"
    private const val SUFFIX = ".jpg.aesgcm"
    private val retentionMs = TimeUnit.DAYS.toMillis(7)

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        cleanup(context)
    }

    fun save(context: Context, publicId: String, bitmap: Bitmap): Boolean {
        if (!isEnabled(context)) return false
        val scaled = scaleForEvidence(bitmap)
        val plain = ByteArrayOutputStream().use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 72, output))
            output.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        val encrypted = DbCrypto.encryptLocalArtifact(plain)
        plain.fill(0)
        val directory = directory(context)
        val target = File(directory, safeName(publicId) + SUFFIX)
        val pending = File(directory, target.name + ".pending")
        pending.outputStream().use { stream ->
            stream.write(encrypted)
            stream.fd.sync()
        }
        val committed = pending.renameTo(target)
        if (!committed) pending.delete()
        cleanup(context)
        return committed
    }

    fun load(context: Context, publicId: String): Bitmap? = runCatching {
        val file = File(directory(context), safeName(publicId) + SUFFIX)
        if (!file.isFile) return null
        val plain = DbCrypto.decryptLocalArtifact(file.readBytes())
        try {
            BitmapFactory.decodeByteArray(plain, 0, plain.size)
        } finally {
            plain.fill(0)
        }
    }.getOrNull()

    fun cleanup(context: Context, nowMs: Long = System.currentTimeMillis()) {
        directory(context).listFiles().orEmpty().forEach { file ->
            if (file.name.endsWith(".pending") || nowMs - file.lastModified() > retentionMs) {
                file.delete()
            }
        }
    }

    private fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    private fun safeName(publicId: String): String =
        publicId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    private fun scaleForEvidence(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= 720) return bitmap
        val height = (bitmap.height.toLong() * 720L / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, 720, height, true)
    }
}
