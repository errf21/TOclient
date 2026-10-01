package com.toclient.core.backup

import com.toclient.core.model.VpnProfile
import com.toclient.core.subscription.Subscription
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupPayload(
    val formatVersion: Int = 1,
    val createdAtMillis: Long,
    val profiles: List<BackupProfile>,
    val subscriptions: List<BackupSubscription> = emptyList(),
)

@Serializable
data class BackupProfile(
    val profile: VpnProfile,
    val xrayJson: String,
)

@Serializable
data class BackupSubscription(
    val subscription: Subscription,
    val url: String,
)

@Serializable
private data class EncryptedEnvelope(
    val format: String = "to-client-backup",
    val version: Int = 1,
    val kdf: String = "PBKDF2-HMAC-SHA256",
    val iterations: Int,
    val salt: String,
    val cipher: String = "AES-256-GCM",
    val iv: String,
    val ciphertext: String,
)

class TocBackupCodec(
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
    private val random: SecureRandom = SecureRandom(),
) {
    fun encode(payload: BackupPayload, password: CharArray): ByteArray {
        validatePassword(password)
        validatePayload(payload)
        val plaintext = json.encodeToString(BackupPayload.serializer(), payload)
            .toByteArray(StandardCharsets.UTF_8)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Backup is too large" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(password, salt, ITERATIONS),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(HEADER)
        val ciphertext = cipher.doFinal(plaintext)
        plaintext.fill(0)
        val envelope = EncryptedEnvelope(
            iterations = ITERATIONS,
            salt = Base64.getEncoder().encodeToString(salt),
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
        )
        return HEADER + json.encodeToString(EncryptedEnvelope.serializer(), envelope)
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(encoded: ByteArray, password: CharArray): BackupPayload {
        validatePassword(password)
        require(encoded.size <= MAX_ENCODED_BYTES) { "Backup is too large" }
        require(encoded.size > HEADER.size && encoded.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            "Not a TO Client backup"
        }
        val envelope = json.decodeFromString(
            EncryptedEnvelope.serializer(),
            encoded.copyOfRange(HEADER.size, encoded.size).toString(StandardCharsets.UTF_8),
        )
        require(envelope.format == "to-client-backup" && envelope.version == 1)
        require(envelope.kdf == "PBKDF2-HMAC-SHA256" && envelope.cipher == "AES-256-GCM")
        require(envelope.iterations in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS)
        val salt = decodeBounded(envelope.salt, SALT_BYTES)
        val iv = decodeBounded(envelope.iv, IV_BYTES)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertext)
        require(ciphertext.size <= MAX_PLAINTEXT_BYTES + 32)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, salt, envelope.iterations),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(HEADER)
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException("Incorrect password or damaged backup", error)
        }
        return try {
            json.decodeFromString(
                BackupPayload.serializer(),
                plaintext.toString(StandardCharsets.UTF_8),
            ).also(::validatePayload)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun validatePassword(password: CharArray) {
        require(password.size in 8..1_024) { "Backup password must contain at least 8 characters" }
    }

    private fun validatePayload(payload: BackupPayload) {
        require(payload.formatVersion == 1) { "Unsupported backup version" }
        require(payload.profiles.size <= MAX_PROFILES)
        require(payload.subscriptions.size <= MAX_SUBSCRIPTIONS)
        payload.profiles.forEach {
            require(it.xrayJson.toByteArray().size <= MAX_PROFILE_BYTES)
        }
        payload.subscriptions.forEach {
            require(it.url.length <= MAX_URL_CHARS)
            require(java.net.URI(it.url).scheme == "https")
        }
    }

    private fun decodeBounded(value: String, expectedBytes: Int): ByteArray =
        Base64.getDecoder().decode(value).also {
            require(it.size == expectedBytes) { "Invalid backup cryptographic parameters" }
        }

    companion object {
        private val HEADER = "TOCLIENT-BACKUP\n".toByteArray(StandardCharsets.US_ASCII)
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val ITERATIONS = 210_000
        private const val MIN_ACCEPTED_ITERATIONS = 150_000
        private const val MAX_ACCEPTED_ITERATIONS = 1_000_000
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val MAX_PROFILES = 5_000
        private const val MAX_SUBSCRIPTIONS = 500
        private const val MAX_PROFILE_BYTES = 2 * 1024 * 1024
        private const val MAX_URL_CHARS = 8 * 1024
        private const val MAX_PLAINTEXT_BYTES = 32 * 1024 * 1024
        private const val MAX_ENCODED_BYTES = 48 * 1024 * 1024
    }
}