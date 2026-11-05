package com.nuvio.tv.core.subtitles

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local encrypted configuration for the Nan Add-ons translation service.
 * The API token is encrypted with an Android Keystore AES key; it is never written
 * to ordinary DataStore preferences or BuildConfig.
 */
class EmbeddedSubtitleTranslationSettings(context: Context) {
    data class Config(val endpoint: String, val token: String) {
        val isConfigured: Boolean
            get() = endpoint.startsWith("https://", ignoreCase = true) && token.isNotBlank()
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun read(): Config {
        val encrypted = preferences.getString(VALUE_KEY, null) ?: return Config(DEFAULT_ENDPOINT, "")
        return runCatching {
            val json = JSONObject(decrypt(encrypted))
            Config(
                endpoint = json.optString("endpoint", DEFAULT_ENDPOINT),
                token = json.optString("token", ""),
            )
        }.getOrElse { Config(DEFAULT_ENDPOINT, "") }
    }

    @Synchronized
    fun save(endpoint: String, token: String) {
        val normalized = endpoint.trim().trimEnd('/')
        require(normalized.startsWith("https://", ignoreCase = true)) {
            "O endereço precisa usar HTTPS."
        }
        require(token.isNotBlank()) { "Informe o token de acesso." }
        val payload = JSONObject()
            .put("endpoint", normalized)
            .put("token", token.trim())
            .toString()
        preferences.edit().putString(VALUE_KEY, encrypt(payload)).apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(VALUE_KEY).apply()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > GCM_IV_LENGTH_BYTES)
        val iv = bytes.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val encrypted = bytes.copyOfRange(GCM_IV_LENGTH_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://nuvio-office-superfan.onrender.com"
        private const val PREFS_NAME = "embedded_subtitle_translation_secure"
        private const val VALUE_KEY = "encrypted_config"
        private const val KEY_ALIAS = "nuvio_embedded_subtitle_translation_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
