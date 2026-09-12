package com.landosol.toolbox.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidKeystoreEncryptedStore(
    context: Context,
    private val storageName: String,
) {
    private val preferences = context.applicationContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val keyAlias = "com.landosol.toolbox.credentials.${storageName.hashCode().toUInt()}"

    suspend fun save(key: String, plaintext: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(plaintext)
            val payload = listOf(
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                Base64.encodeToString(encrypted, Base64.NO_WRAP),
            ).joinToString(SEPARATOR)
            check(preferences.edit().putString(key, payload).commit()) { "Encrypted storage failed" }
        }
    }

    suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val payload = preferences.getString(key, null) ?: return@withLock null
            val parts = payload.split(SEPARATOR, limit = 2)
            check(parts.size == 2) { "Invalid encrypted payload" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
        }
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(preferences.edit().remove(key).commit()) { "Encrypted value deletion failed" }
        }
    }

    suspend fun clearAllForTest() = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(preferences.edit().clear().commit()) { "Encrypted storage cleanup failed" }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
