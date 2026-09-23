package xin.dponnood.remoteservice.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ServiceCredentials(
    val username: String,
    val password: String,
)

sealed class CredentialStoreException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {
    class InvalidKey(cause: Throwable) : CredentialStoreException("Credential key is unavailable", cause)
    class CorruptValue(cause: Throwable) : CredentialStoreException("Credential value is corrupt", cause)
}

interface CredentialStore {
    fun put(serviceId: String, routeKey: String, credentials: ServiceCredentials)
    fun get(serviceId: String, routeKey: String): ServiceCredentials?
    fun delete(serviceId: String, routeKey: String)
    fun clear()
}

/**
 * Keystore-backed credential storage. SharedPreferences contains only encrypted
 * blobs and a hashed lookup key; plaintext credentials never leave this class.
 */
class KeystoreCredentialStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : CredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun put(serviceId: String, routeKey: String, credentials: ServiceCredentials) {
        require(serviceId.isNotBlank()) { "serviceId must not be blank" }
        require(routeKey.isNotBlank()) { "routeKey must not be blank" }
        // Encode each field before joining so a colon, newline, or NUL in a
        // username/password cannot corrupt the record framing.
        val payload = Base64.encodeToString(credentials.username.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP) +
            SEPARATOR +
            Base64.encodeToString(credentials.password.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val encrypted = encrypt(payload.toByteArray(StandardCharsets.UTF_8))
        synchronized(lock) {
            preferences.edit().putString(storageKey(serviceId, routeKey), encrypted).apply()
        }
    }

    override fun get(serviceId: String, routeKey: String): ServiceCredentials? {
        val encoded = synchronized(lock) { preferences.getString(storageKey(serviceId, routeKey), null) }
            ?: return null
        val plaintext = try {
            decrypt(encoded).toString(StandardCharsets.UTF_8)
        } catch (error: CredentialStoreException) {
            throw error
        } catch (error: Exception) {
            throw CredentialStoreException.CorruptValue(error)
        }
        val splitAt = plaintext.indexOf(SEPARATOR)
        if (splitAt < 0) throw CredentialStoreException.CorruptValue(IllegalArgumentException("missing separator"))
        return try {
            ServiceCredentials(
                Base64.decode(plaintext.substring(0, splitAt), Base64.DEFAULT).toString(StandardCharsets.UTF_8),
                Base64.decode(plaintext.substring(splitAt + SEPARATOR.length), Base64.DEFAULT).toString(StandardCharsets.UTF_8),
            )
        } catch (error: Exception) {
            throw CredentialStoreException.CorruptValue(error)
        }
    }

    override fun delete(serviceId: String, routeKey: String) {
        synchronized(lock) {
            preferences.edit().remove(storageKey(serviceId, routeKey)).apply()
        }
    }

    override fun clear() {
        synchronized(lock) { preferences.edit().clear().apply() }
    }

    private fun storageKey(serviceId: String, routeKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((serviceId + "\u0000" + routeKey).toByteArray(StandardCharsets.UTF_8))
        return "credential_" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun encrypt(value: ByteArray): String {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(value)
            return VERSION + SEPARATOR +
                Base64.encodeToString(iv, Base64.NO_WRAP) + SEPARATOR +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (error: Exception) {
            throw CredentialStoreException.InvalidKey(error)
        }
    }

    private fun decrypt(encoded: String): ByteArray {
        try {
            val fields = encoded.split(SEPARATOR)
            require(fields.size == 3 && fields[0] == VERSION) { "unsupported credential blob" }
            val iv = Base64.decode(fields[1], Base64.DEFAULT)
            val ciphertext = Base64.decode(fields[2], Base64.DEFAULT)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            return cipher.doFinal(ciphertext)
        } catch (error: CredentialStoreException) {
            throw error
        } catch (error: Exception) {
            throw CredentialStoreException.CorruptValue(error)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        } catch (error: Exception) {
            throw CredentialStoreException.InvalidKey(error)
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "remote_service_credentials_v1"
        private const val PREFERENCES_NAME = "remote_service_secure_store"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val VERSION = "v1"
        private const val SEPARATOR = ":"
    }
}
