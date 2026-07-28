package com.evelorion.contacts.sync.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DEK 的本地缓存。
 *
 * 用户不可能每次打开通讯录都输一遍主口令，所以解出来的 DEK 需要在本机留一份。
 * 直接写 SharedPreferences 等于没加密 —— 拿到设备文件系统的人可以直接读走。
 *
 * 这里用 Android Keystore 里的一把密钥再包一层：
 *   · 密钥材料由 TEE / StrongBox 持有，App 只能请求它做加解密，读不出密钥本身
 *   · 恢复出厂设置、卸载 App、换设备都会让这把密钥永久消失
 *   · requireScreenLock 打开后，还需要屏幕锁验证才能用
 *
 * 需要说清楚的边界：这挡的是「拿到设备文件」的攻击者。已经 root 且能注入本
 * App 进程的攻击者可以直接调用 Keystore 完成解密 —— 那种情况下任何纯软件方案都无解。
 */
class KeystoreVault(private val context: Context) {

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "fc_sync_dek_wrapper_v1"
        private const val PREFS = "fc_sync_vault"
        private const val KEY_WRAPPED_DEK = "wrapped_dek"
        private const val KEY_REQUIRES_AUTH = "requires_auth"
        private val AAD = "fc.local.dek.v1".toByteArray(Charsets.UTF_8)

        /** 需要屏幕锁验证时，一次验证在多少秒内有效。 */
        private const val AUTH_VALIDITY_SECONDS = 300

        private const val GCM_IV_BYTES = 12
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val hasCachedDek: Boolean
        get() = prefs.contains(KEY_WRAPPED_DEK)

    val requiresScreenLock: Boolean
        get() = prefs.getBoolean(KEY_REQUIRES_AUTH, false)

    /**
     * 把解出来的 DEK 存进本机。
     * 切换 requireScreenLock 需要换一把 Keystore 密钥，所以会先删掉旧的。
     */
    fun store(dek: ByteArray, requireScreenLock: Boolean) {
        if (requireScreenLock != requiresScreenLock || loadKey() == null) {
            deleteKeystoreEntry()
        }
        val key = getOrCreateKey(requireScreenLock)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // IV 由 Keystore 自己生成，不能由调用方指定 —— setRandomizedEncryptionRequired(true) 强制了这一点
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val body = cipher.doFinal(dek)
        val blob = cipher.iv + body

        prefs.edit()
            .putString(KEY_WRAPPED_DEK, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putBoolean(KEY_REQUIRES_AUTH, requireScreenLock)
            .apply()
    }

    /**
     * 取回 DEK。
     * 返回 null 表示本机没有可用的缓存，调用方应当让用户输主口令。
     * 抛 UserNotAuthenticatedException 表示需要先过屏幕锁，调用方拉起验证后重试。
     */
    fun load(): ByteArray? {
        val stored = prefs.getString(KEY_WRAPPED_DEK, null) ?: return null
        val key = loadKey() ?: run {
            // Keystore 里的密钥没了（用户改了锁屏方式、恢复出厂设置……），
            // 缓存的密文已经永远解不开，清掉让用户重新输口令。
            clear()
            return null
        }
        val blob = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            clear(); return null
        }
        if (blob.size <= GCM_IV_BYTES) {
            clear(); return null
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(128, blob.copyOfRange(0, GCM_IV_BYTES))
            )
            cipher.updateAAD(AAD)
            cipher.doFinal(blob.copyOfRange(GCM_IV_BYTES, blob.size))
        } catch (e: UserNotAuthenticatedException) {
            throw e
        } catch (e: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_WRAPPED_DEK).remove(KEY_REQUIRES_AUTH).apply()
        deleteKeystoreEntry()
    }

    private fun deleteKeystoreEntry() {
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun loadKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        ks.getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    private fun getOrCreateKey(requireScreenLock: Boolean): SecretKey {
        loadKey()?.let { return it }
        return generateKey(requireScreenLock, useStrongBox = true)
    }

    private fun generateKey(requireScreenLock: Boolean, useStrongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
            setRandomizedEncryptionRequired(true)
            if (requireScreenLock) {
                setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
                        KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                }
            }
            // StrongBox 是独立安全芯片，有就用。很多设备没有，失败必须能降级。
            if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setIsStrongBoxBacked(true)
            }
        }.build()

        return try {
            generator.init(spec)
            generator.generateKey()
        } catch (e: Exception) {
            // StrongBoxUnavailableException 在 API 28 才存在，直接按类型 catch 会在
            // 低版本上触发类加载问题，所以这里用名字判断后降级重试一次。
            if (useStrongBox && e::class.java.simpleName == "StrongBoxUnavailableException") {
                deleteKeystoreEntry()
                generateKey(requireScreenLock, useStrongBox = false)
            } else {
                throw e
            }
        }
    }
}
