package ru.bodro.music.data.account

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Пароли учёток. Шифруются AES-256-GCM (Tink), ключ обёрнут мастер-ключом Android Keystore.
 * Файлы исключены из бэкапа (res/xml/backup_rules.xml). В логи и Room пароль не попадает.
 */
interface CredentialStore {
    suspend fun save(accountId: Long, password: String)

    suspend fun load(accountId: Long): String?

    suspend fun delete(accountId: Long)
}

@Singleton
class TinkCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : CredentialStore {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override suspend fun save(accountId: Long, password: String) = withContext(Dispatchers.IO) {
        val encrypted = aead.encrypt(password.toByteArray(Charsets.UTF_8), associatedData(accountId))
        prefs.edit().putString(key(accountId), Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    override suspend fun load(accountId: Long): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key(accountId), null) ?: return@withContext null
        try {
            String(aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), associatedData(accountId)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Ключ Keystore потерян (например, восстановление на другом устройстве) — нужен повторный вход.
            null
        }
    }

    override suspend fun delete(accountId: Long) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key(accountId)).apply()
    }

    private fun key(accountId: Long) = "password_$accountId"

    private fun associatedData(accountId: Long) = "bodro-account-$accountId".toByteArray()

    private companion object {
        const val PREFS = "bodro_secrets"
        const val KEYSET_PREFS = "bodro_keyset"
        const val KEYSET_NAME = "bodro_keyset"
        const val MASTER_KEY_URI = "android-keystore://bodro_master_key"
    }
}
