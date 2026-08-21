package com.vibeplayer.app.domain.tssl

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM primitives shared by the encrypted-HLS packager and playback
 * proxy. Mirrors the Qt EncryptedHls module: each segment uses its own random
 * 256-bit key and 128-bit IV with the byte layout
 *
 *   `16-byte IV | ciphertext | 16-byte authentication tag`
 *
 * with **no** additional authenticated data. The encrypted source-name block
 * instead binds the identifier as GCM AAD. A key/IV pair must never be reused.
 */
object TsslCrypto {

    const val KEY_BYTES = 32
    const val IV_BYTES = 16
    const val TAG_BITS = 128

    private val secureRandom = SecureRandom()

    /** New CSPRNG-generated 256-bit key. */
    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { secureRandom.nextBytes(it) }

    /** New CSPRNG-generated 128-bit IV. */
    fun newIv(): ByteArray = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }

    /**
     * Encrypts [plain] and returns `IV | ciphertext | tag`. When [aad] is
     * non-null it is bound into the GCM authentication tag (used only for the
     * encrypted source name, keyed to the package identifier).
     */
    fun encrypt(key: ByteArray, iv: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        if (aad != null) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plain)
        return iv + ciphertext
    }

    /**
     * Validates the GCM tag and returns the plaintext of an `IV | ciphertext | tag`
     * block. Throws [javax.crypto.AEADBadTagException] on a truncated or tampered
     * ciphertext (no plaintext is ever exposed in that case).
     */
    fun decrypt(key: ByteArray, block: ByteArray, aad: ByteArray? = null): ByteArray {
        require(block.size > IV_BYTES) { "Truncated encrypted block" }
        val iv = block.copyOfRange(0, IV_BYTES)
        val ciphertext = block.copyOfRange(IV_BYTES, block.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /** SHA-256 hex digest (lowercase) of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun toBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun fromBase64Url(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s)

    /**
     * Package identifier: 3072 CSPRNG bytes encoded as unpadded Base64URL,
     * yielding exactly 4096 characters in `[A-Za-z0-9_-]`.
     */
    fun newIdentifier(): String = toBase64Url(ByteArray(3072).also { secureRandom.nextBytes(it) })
}
