package com.evelorion.contacts.sync.net

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.evelorion.contacts.sync.crypto.Crypto

/**
 * 服务器地址、账号信息和令牌的存放处。
 *
 * 令牌不是普通配置：拿到刷新令牌的人可以持续从服务器下载全部密文
 * （虽然解不开，但能看到条数、大小、同步频率这些元数据，还能删你的数据）。
 * 所以这里用 EncryptedSharedPreferences，密钥在 Android Keystore 里。
 *
 * EncryptedSharedPreferences 在少数厂商 ROM 上会因为 Keystore 异常初始化失败，
 * 那种情况下降级到普通 SharedPreferences 并把这个状态暴露给设置页 ——
 * 静默降级比崩溃好，但必须让用户知道。
 */
class SessionStore(context: Context) {

    companion object {
        private const val PREFS_ENCRYPTED = "fc_sync_session"
        private const val PREFS_PLAIN = "fc_sync_session_plain"

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_ACCESS_EXP = "access_expires_at"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_KDF_SALT = "kdf_salt"
        private const val KEY_KDF_MEM = "kdf_mem"
        private const val KEY_KDF_TIME = "kdf_time"
        private const val KEY_KDF_PAR = "kdf_par"
        private const val KEY_VAULT_VERSION = "vault_version"
        private const val KEY_WRAPPED_DEK = "wrapped_dek_password"
    }

    /** 加密存储不可用时为 true，设置页应当据此提示用户。 */
    var usingFallbackStorage: Boolean = false
        private set

    private val prefs = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_ENCRYPTED,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        usingFallbackStorage = true
        context.applicationContext.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------ 服务器

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trimEnd('/')).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var accountId: String
        get() = prefs.getString(KEY_ACCOUNT_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACCOUNT_ID, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    val isConfigured: Boolean
        get() = baseUrl.isNotEmpty() && username.isNotEmpty()

    // ------------------------------------------------------------ 令牌

    val accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)?.takeIf { it.isNotEmpty() }

    val refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)?.takeIf { it.isNotEmpty() }

    val accessExpiresAt: Long
        get() = prefs.getLong(KEY_ACCESS_EXP, 0)

    fun saveTokens(accessToken: String, refreshToken: String, accessExpiresAt: Long) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_ACCESS_EXP, accessExpiresAt)
            .apply()
    }

    fun clearTokens() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_ACCESS_EXP).apply()
    }

    // ------------------------------------------------------------ KDF 参数

    /**
     * 缓存服务器的 KDF 参数，这样离线时用户输口令也能解开本地缓存。
     * 参数本身不是秘密（服务器对任何人都返回），缓存它没有额外风险。
     */
    fun saveKdf(salt: ByteArray, memoryKiB: Int, iterations: Int, parallelism: Int, vaultVersion: Int) {
        prefs.edit()
            .putString(KEY_KDF_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt(KEY_KDF_MEM, memoryKiB)
            .putInt(KEY_KDF_TIME, iterations)
            .putInt(KEY_KDF_PAR, parallelism)
            .putInt(KEY_VAULT_VERSION, vaultVersion)
            .apply()
    }

    val kdfSalt: ByteArray?
        get() = prefs.getString(KEY_KDF_SALT, null)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }

    /**
     * 服务器上那份「被口令包裹的 DEK」的本地副本（base64）。
     * 缓存它是为了断网时也能用主口令解锁 —— 光有 KDF 参数没有包裹是解不开的。
     * 它本身没有泄密风险：不知道口令就打不开，这正是零知识设计的前提。
     */
    var wrappedDekCache: String?
        get() = prefs.getString(KEY_WRAPPED_DEK, null)?.takeIf { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_WRAPPED_DEK, value.orEmpty()).apply()

    val kdfMemoryKiB: Int get() = prefs.getInt(KEY_KDF_MEM, 65536)
    val kdfIterations: Int get() = prefs.getInt(KEY_KDF_TIME, 3)
    val kdfParallelism: Int get() = prefs.getInt(KEY_KDF_PAR, 4)
    val vaultVersion: Int get() = prefs.getInt(KEY_VAULT_VERSION, 0)

    // ------------------------------------------------------------ 清理

    fun wipe() {
        prefs.edit().clear().apply()
    }

    /**
     * 服务器地址的基本校验。
     * 明确拒绝 http:// —— 内容虽然是密文，但访问令牌会明文过网，
     * 拿到令牌的人可以删掉你服务器上的全部数据。
     */
    fun validateUrl(url: String): String? {
        val trimmed = url.trim()
        return when {
            trimmed.isEmpty() -> "请填写服务器地址"
            trimmed.startsWith("http://") ->
                "必须使用 https://。http 会让访问令牌明文过网，拿到它的人可以删除你服务器上的数据"
            !trimmed.startsWith("https://") -> "地址要以 https:// 开头"
            runCatching { java.net.URL(trimmed) }.isFailure -> "地址格式不正确"
            else -> null
        }
    }

    /** 用于在设置页显示「当前设备」标识，不含任何敏感信息。 */
    fun deviceFingerprint(): String =
        Crypto.toHex(Crypto.sha256(deviceId.toByteArray(Charsets.UTF_8))).substring(0, 8)
}
