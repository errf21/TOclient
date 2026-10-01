package com.toclient.android.persistence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreSecretStore(
    context: Context,
) {
    private val directory = File(context.noBackupFilesDir, "profile-secrets").apply { mkdirs() }
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    fun write(reference: String, plaintext: ByteArray) {
        require(reference.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        val encoded = buildString {
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            append('.')
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }.toByteArray(StandardCharsets.US_ASCII)

        val target = AtomicFile(file(reference))
        val output = target.startWrite()
        try {
            output.write(encoded)
            output.fd.sync()
            target.finishWrite(output)
        } catch (error: Throwable) {
            target.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun read(reference: String): ByteArray {
        val parts = AtomicFile(file(reference)).readFully()
            .toString(StandardCharsets.US_ASCII)
            .split('.', limit = 2)
        require(parts.size == 2) { "Corrupt encrypted profile" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
    }

    @Synchronized
    fun delete(reference: String) {
        file(reference).delete()
    }

    private fun file(reference: String): File {
        require(reference.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        return File(directory, "$reference.enc")
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "to-client-profile-secrets-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}