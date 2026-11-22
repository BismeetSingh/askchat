package com.askchat.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the 64-digit backup key encrypted with a hardware-backed Android Keystore key.
 * The raw key never touches disk in plaintext.
 */
class BackupKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("backup_key", Context.MODE_PRIVATE)

    val hasKey: Boolean get() = prefs.contains(PREF_BLOB)

    fun save(rootKey: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val blob = cipher.iv + cipher.doFinal(rootKey)
        prefs.edit().putString(PREF_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun load(): ByteArray? {
        val blob = prefs.getString(PREF_BLOB, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, blob, 0, 12))
            cipher.doFinal(blob, 12, blob.size - 12)
        }.getOrNull()
    }

    fun clear() = prefs.edit().remove(PREF_BLOB).apply()

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "whatsapp_backup_key_wrapper"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val PREF_BLOB = "wrapped_key"
    }
}
