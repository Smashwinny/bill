package com.hulk.pillsapp.ledger

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * 账户聚类键含机构、产品和尾号等低熵材料，不能使用普通 SHA-256 防字典枚举。
 * 使用设备 Android Keystore 内不可导出的 HMAC 密钥；诊断报告永不输出结果摘要。
 */
object AccountIdentityHasher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ledger_account_identity_hmac_v1"

    @Synchronized
    fun hash(@Suppress("UNUSED_PARAMETER") context: Context, material: String): String {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: createKey()
        return Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(material.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKey()
    }
}
