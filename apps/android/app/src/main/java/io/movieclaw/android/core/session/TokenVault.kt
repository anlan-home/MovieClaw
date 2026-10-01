package io.movieclaw.android.core.session

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

private val Context.vaultStore by preferencesDataStore(name = "mc_vault")

/**
 * 令牌库:Android Keystore(AES-256-GCM)加密后落 DataStore,键 `origin#username`。
 * 对齐 iOS Keychain 语义:token 不进日志、不进备份(allowBackup=false)。
 * Keystore 不可用的设备(个别模拟器/ROM)降级为明文前缀存储,保证可用性。
 */
@Singleton
class TokenVault @Inject constructor(@ApplicationContext private val context: Context) {

    private val mem = ConcurrentHashMap<String, String>()

    @Volatile
    var activeOrigin: String? = null
        private set

    @Volatile
    private var activeUsername: String? = null

    @Volatile
    private var activeTokenValue: String? = null

    fun activate(origin: String, username: String, token: String) {
        activeOrigin = origin
        activeUsername = username
        activeTokenValue = token
    }

    fun activeToken(): String? = activeTokenValue

    suspend fun warmUp() {
        val entries = context.vaultStore.data.first().asMap()
        entries.forEach { (key, value) ->
            if (value is String) decrypt(value)?.let { mem[key.name] = it }
        }
    }

    suspend fun token(origin: String, username: String): String? {
        val key = vaultKey(origin, username)
        mem[key]?.let { return it }
        val blob = context.vaultStore.data.first()[stringPreferencesKey(key)] ?: return null
        return decrypt(blob).also { if (it != null) mem[key] = it }
    }

    suspend fun saveToken(origin: String, username: String, token: String) {
        val key = vaultKey(origin, username)
        mem[key] = token
        context.vaultStore.edit { it[stringPreferencesKey(key)] = encrypt(token) }
    }

    suspend fun deleteToken(origin: String, username: String) {
        val key = vaultKey(origin, username)
        mem.remove(key)
        context.vaultStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    suspend fun deactivateActive() {
        val origin = activeOrigin
        val username = activeUsername
        if (origin != null && username != null) deleteToken(origin, username)
        activeOrigin = null
        activeUsername = null
        activeTokenValue = null
    }

    private fun vaultKey(origin: String, username: String) = "$origin#$username"

    private fun secretKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        }
    }.getOrNull()

    private fun encrypt(plain: String): String {
        val key = secretKey()
            ?: return PLAIN_PREFIX + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val cipherText = cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? = runCatching {
        if (blob.startsWith(PLAIN_PREFIX)) {
            return String(Base64.decode(blob.removePrefix(PLAIN_PREFIX), Base64.NO_WRAP))
        }
        val all = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(cipher.doFinal(all.copyOfRange(12, all.size)))
    }.getOrNull()

    private companion object {
        const val KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mc_token_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val PLAIN_PREFIX = "raw:"
    }
}
