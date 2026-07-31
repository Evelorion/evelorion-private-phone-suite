package com.evelorion.phone.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.PhoneNumberUtils
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BlockedNumber(
    val number: String,
    val label: String,
    val addedAt: Long,
)

/**
 * 用户自己的号码拦截名单。
 *
 * 名单只保存在本机，并用 Android Keystore 的 AES-GCM 密钥加密。这里不接在线
 * 查号服务，避免把每一通来电号码交给第三方。
 */
object BlockedNumberStore {
    private const val PREFS = "blocked_numbers"
    private const val CIPHERTEXT = "ciphertext"
    private const val IV = "iv"
    private const val ENABLED = "enabled"
    private const val KEY_ALIAS = "evelorion_blocked_numbers_v1"

    @Synchronized
    fun all(context: Context): List<BlockedNumber> =
        read(context).sortedByDescending { it.addedAt }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }

    @Synchronized
    fun add(context: Context, number: String, label: String = ""): Boolean {
        val normalized = normalize(number)
        if (normalized.isBlank()) return false
        val entries = read(context).toMutableList()
        val existing = entries.indexOfFirst { normalize(it.number) == normalized }
        val entry = BlockedNumber(
            number = number.trim(),
            label = label.trim(),
            addedAt = System.currentTimeMillis(),
        )
        if (existing >= 0) entries[existing] = entry else entries += entry
        write(context, entries)
        return true
    }

    @Synchronized
    fun remove(context: Context, number: String): Boolean {
        val normalized = normalize(number)
        val entries = read(context)
        val updated = entries.filterNot { normalize(it.number) == normalized }
        if (updated.size == entries.size) return false
        write(context, updated)
        return true
    }

    @Synchronized
    fun isBlocked(context: Context, number: String): Boolean {
        val normalized = normalize(number)
        return normalized.isNotBlank() && read(context).any { normalize(it.number) == normalized }
    }

    private fun normalize(raw: String): String {
        val normalized = PhoneNumberUtils.normalizeNumber(raw)
        val digits = normalized.filter(Char::isDigit)
        return if (digits.length == 13 && digits.startsWith("86")) digits.drop(2) else digits
    }

    private fun read(context: Context): List<BlockedNumber> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encodedCiphertext = prefs.getString(CIPHERTEXT, null) ?: return emptyList()
        val encodedIv = prefs.getString(IV, null) ?: return emptyList()
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            val json = String(
                cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)),
                StandardCharsets.UTF_8,
            )
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        BlockedNumber(
                            number = item.getString("number"),
                            label = item.optString("label"),
                            addedAt = item.optLong("addedAt"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, entries: List<BlockedNumber>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("number", entry.number)
                    .put("label", entry.label)
                    .put("addedAt", entry.addedAt)
            )
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(array.toString().toByteArray(StandardCharsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKey()
        }
    }
}
