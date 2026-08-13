package com.hulk.pillsapp.ledger

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 数据库口令管理（V1 §8：密钥使用 Android Keystore）。
 *
 * 生成 32 字节随机口令，Base64 后作为 SQLCipher passphrase；
 * 口令本体用 Keystore 内 AES/GCM 主密钥包裹后存 SharedPreferences。
 * Keystore 主密钥不出 TEE/Keymaster，明文口令不落盘。
 */
object DbCrypto {
    private const val KEY_ALIAS = "ledger_db_passphrase_key"
    private const val PREFS_NAME = "ledger_kernel_prefs"
    private const val PREF_WRAPPED = "db_passphrase_wrapped"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    fun getOrCreatePassphrase(context: Context): ByteArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val wrapped = prefs.getString(PREF_WRAPPED, null)
        if (wrapped != null) {
            return unwrap(Base64.decode(wrapped, Base64.NO_WRAP))
        }
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded = Base64.encodeToString(passphrase, Base64.NO_WRAP)
        // 必须同步落盘：进程若在 apply() 异步写盘前崩溃，包裹口令丢失会导致下次启动
        // 重新生成口令而永远打不开既有加密库。
        prefs.edit()
            .putString(PREF_WRAPPED, Base64.encodeToString(wrap(encoded.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .commit()
        return encoded.toByteArray(Charsets.UTF_8)
    }

    /** M5 崩溃恢复待办使用同一 Keystore 主密钥；文件只包含随机 IV + 密文。 */
    fun encryptLocalArtifact(plain: ByteArray): ByteArray = wrap(plain)

    fun decryptLocalArtifact(encrypted: ByteArray): ByteArray = unwrap(encrypted)

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encrypted = cipher.doFinal(plain)
        return cipher.iv + encrypted
    }

    private fun unwrap(wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystoreKey(),
            GCMParameterSpec(GCM_TAG_BITS, wrapped, 0, GCM_IV_LENGTH),
        )
        return cipher.doFinal(wrapped, GCM_IV_LENGTH, wrapped.size - GCM_IV_LENGTH)
    }
}
