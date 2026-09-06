package com.brutus.pepper

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffre le jeton d'appairage avec une clé de l'AndroidKeyStore, qui ne quitte jamais
 * le matériel. Une tablette d'accueil est physiquement accessible au public : le jeton
 * ne doit pas être lisible en clair dans les préférences.
 */
class EncryptedBrainSettingsStore(context: Context) : BrainSettingsStore {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    override fun load(): BrainSettings {
        val encodedCiphertext = preferences.getString(CIPHERTEXT, null) ?: return BrainSettings()
        val encodedIv = preferences.getString(IV, null) ?: return BrainSettings()
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP))
            )
            val plain = cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
            gson.fromJson(String(plain, Charsets.UTF_8), BrainSettings::class.java)
        }.getOrNull() ?: BrainSettings()
    }

    override fun save(settings: BrainSettings) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(gson.toJson(settings).toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFS = "brain_secure"
        private const val CIPHERTEXT = "ciphertext"
        private const val IV = "iv"
        private const val KEY_ALIAS = "brutus_pepper_brain"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
