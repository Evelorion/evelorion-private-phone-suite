package com.evelorion.contacts.sync

import android.content.Context
import android.util.Base64
import com.evelorion.contacts.sync.crypto.Crypto
import com.evelorion.contacts.sync.crypto.KeystoreVault
import com.evelorion.contacts.sync.crypto.RecoveryCode
import com.evelorion.contacts.sync.crypto.VaultCrypto
import com.evelorion.contacts.sync.net.SessionStore
import com.evelorion.contacts.sync.net.SyncApi
import org.json.JSONObject

/**
 * 保险库的开关。整个 App 里只有这里持有解密后的 DEK。
 *
 * 状态机：
 *   未配置        没填服务器 / 没登录            → 引导用户去 SyncSetupActivity
 *   已配置、锁定  有账号但内存里没有 DEK          → 需要主口令，或从 Keystore 取缓存
 *   已解锁        DEK 在内存里，可以同步和读写   → 正常工作
 *
 * DEK 只放在内存和 Keystore 里，永远不写进普通文件。lock() 会把内存里的字节抹掉。
 */
class VaultManager private constructor(private val context: Context) {

    class MfaRequired(
        val methods: List<String>,
        val requireAll: Boolean,
    ) : Exception("需要两步验证码")

    companion object {
        @Volatile
        private var instance: VaultManager? = null

        fun get(context: Context): VaultManager = instance ?: synchronized(this) {
            instance ?: VaultManager(context.applicationContext).also { instance = it }
        }
    }

    val session = SessionStore(context)
    private val keystore = KeystoreVault(context)

    @Volatile
    private var dekInMemory: ByteArray? = null

    val isConfigured: Boolean get() = session.isConfigured
    val isUnlocked: Boolean get() = dekInMemory != null

    /** 拿 DEK。没解锁时返回 null，调用方必须处理，不要 !! 。 */
    fun dek(): ByteArray? = dekInMemory

    fun api(): SyncApi = SyncApi(session.baseUrl, session)

    // ---------------------------------------------------------------- 首次配置

    class SetupResult(val recoveryCode: String)

    /**
     * 在服务器上新建账号。
     * 恢复码只在返回值里出现这一次，之后任何地方都拿不回来 ——
     * 因为服务器上只存着「用恢复码才能解开的 DEK 包裹」，恢复码本身谁都没有。
     */
    fun register(
        baseUrl: String,
        username: String,
        passphrase: String,
        registrationToken: String,
        deviceName: String,
        cacheOnDevice: Boolean,
        requireScreenLock: Boolean,
    ): SetupResult {
        session.validateUrl(baseUrl)?.let { throw IllegalArgumentException(it) }
        session.baseUrl = baseUrl

        val vault = VaultCrypto.createVault(passphrase)
        val response = SyncApi(baseUrl, session).register(
            username = username,
            authSecret = vault.authSecret,
            recoveryAuthSecret = vault.recoveryAuthSecret,
            salt = vault.salt,
            memoryKiB = VaultCrypto.KDF_MEMORY_KIB,
            iterations = VaultCrypto.KDF_ITERATIONS,
            parallelism = VaultCrypto.KDF_PARALLELISM,
            dekWrapPassword = vault.dekWrapPassword,
            dekWrapRecovery = vault.dekWrapRecovery,
            deviceName = deviceName,
            registrationToken = registrationToken,
        )

        persistSession(username, response)
        setDek(vault.dek, cacheOnDevice, requireScreenLock)
        Crypto.wipe(vault.recoveryKey)
        return SetupResult(vault.recoveryCode)
    }

    /**
     * 在已有账号上登录这台新设备。
     * 服务器返回被口令包裹的 DEK，本机用口令派生出 KEK 解开它 ——
     * 全过程服务器没有拿到任何能解密的东西。
     */
    /**
     * 用恢复码从云端恢复。
     *
     * ── 什么时候用 ──────────────────────────────────────────
     *
     * 忘了主口令的时候。普通登录需要口令派生的 authSecret，过不去；
     * 恢复码走的是另一条凭据（HKDF 的 info 标签不同，两者互相推不出）。
     *
     * ── 为什么恢复完必须换口令 ──────────────────────────────
     *
     * 走到这条路说明用户已经不知道口令了。恢复之后如果不换，
     * 他仍然登录不了 —— 下次还得再用一次恢复码，而恢复码是有限的。
     * 调用方拿到 mustResetPassphrase = true 后必须引导他设新口令。
     *
     * @return true 表示服务器要求立刻重设主口令
     */
    fun loginWithRecoveryCode(
        baseUrl: String,
        username: String,
        recoveryCode: String,
        deviceName: String,
        cacheOnDevice: Boolean,
        requireScreenLock: Boolean,
        mfaCode: String? = null,
    ): Boolean {
        session.validateUrl(baseUrl)?.let { throw IllegalArgumentException(it) }
        session.baseUrl = baseUrl

        val recoveryKey = RecoveryCode.parse(recoveryCode)
        val api = SyncApi(baseUrl, session)

        // 先拿盐才能派生凭据。这个端点不需要认证，
        // 而且对不存在的用户名也返回一个确定性假盐（防账号枚举）
        val kdf = api.getKdfParams(username)
        val salt = Base64.decode(kdf.getString("salt"), Base64.NO_WRAP)

        try {
            val firstResponse = api.loginWithRecovery(
                username = username,
                recoveryAuthSecret = VaultCrypto.deriveRecoveryAuthSecret(recoveryKey, salt),
                deviceName = deviceName,
            )
            val response = completeMfaIfNeeded(api, firstResponse, mfaCode)
            persistSession(username, response)

            // 解开 DEK 用的是另一条路径：恢复码派生的 RKEK
            val rkek = VaultCrypto.deriveRecoveryKek(recoveryKey, salt)
            try {
                val dek = VaultCrypto.unwrapDek(
                    rkek,
                    Base64.decode(response.getString("dekWrapRecovery"), Base64.NO_WRAP),
                    forRecovery = true,
                )
                setDek(dek, cacheOnDevice, requireScreenLock)
            } finally {
                Crypto.wipe(rkek)
            }

            return response.optBoolean("mustResetPassphrase", true)
        } finally {
            Crypto.wipe(recoveryKey)
        }
    }

    fun login(
        baseUrl: String,
        username: String,
        passphrase: String,
        deviceName: String,
        cacheOnDevice: Boolean,
        requireScreenLock: Boolean,
        mfaCode: String? = null,
    ) {
        session.validateUrl(baseUrl)?.let { throw IllegalArgumentException(it) }
        session.baseUrl = baseUrl

        val api = SyncApi(baseUrl, session)
        val kdf = api.getKdfParams(username)
        val salt = Base64.decode(kdf.getString("salt"), Base64.NO_WRAP)
        val masterKey = VaultCrypto.deriveMasterKey(
            passphrase, salt,
            kdf.optInt("memoryKiB", VaultCrypto.KDF_MEMORY_KIB),
            kdf.optInt("iterations", VaultCrypto.KDF_ITERATIONS),
            kdf.optInt("parallelism", VaultCrypto.KDF_PARALLELISM),
        )
        try {
            val firstResponse = api.login(
                username,
                VaultCrypto.deriveAuthSecret(masterKey, salt),
                deviceName,
            )
            val response = completeMfaIfNeeded(api, firstResponse, mfaCode)
            persistSession(username, response)

            val kek = VaultCrypto.deriveKek(masterKey, salt)
            try {
                val dek = VaultCrypto.unwrapDek(
                    kek,
                    Base64.decode(response.getString("dekWrapPassword"), Base64.NO_WRAP),
                    forRecovery = false,
                )
                setDek(dek, cacheOnDevice, requireScreenLock)
            } finally {
                Crypto.wipe(kek)
            }
        } finally {
            Crypto.wipe(masterKey)
        }
    }

    // ---------------------------------------------------------------- 解锁

    /**
     * 用主口令解锁。会先向服务器要一次最新的包裹 ——
     * 因为另一台设备可能刚改过口令，本机缓存的 KDF 参数已经过期了。
     * 服务器不可达时退回到缓存的参数，让离线也能解锁。
     */
    fun unlockWithPassphrase(passphrase: String, cacheOnDevice: Boolean, requireScreenLock: Boolean) {
        val vaultInfo = runCatching { api().getVault() }.getOrNull()
        val salt: ByteArray
        val memory: Int
        val iterations: Int
        val parallelism: Int
        val wrapped: ByteArray

        if (vaultInfo != null) {
            val kdf = vaultInfo.getJSONObject("kdf")
            salt = Base64.decode(kdf.getString("salt"), Base64.NO_WRAP)
            memory = kdf.optInt("memoryKiB", VaultCrypto.KDF_MEMORY_KIB)
            iterations = kdf.optInt("iterations", VaultCrypto.KDF_ITERATIONS)
            parallelism = kdf.optInt("parallelism", VaultCrypto.KDF_PARALLELISM)
            wrapped = Base64.decode(vaultInfo.getString("dekWrapPassword"), Base64.NO_WRAP)
            session.saveKdf(salt, memory, iterations, parallelism, vaultInfo.optInt("vaultVersion", 1))
            session.wrappedDekCache = vaultInfo.getString("dekWrapPassword")
        } else {
            salt = session.kdfSalt ?: throw IllegalStateException("本机没有缓存的密钥参数，需要联网解锁一次")
            memory = session.kdfMemoryKiB
            iterations = session.kdfIterations
            parallelism = session.kdfParallelism
            wrapped = Base64.decode(
                session.wrappedDekCache ?: throw IllegalStateException("本机没有缓存的密钥包裹，需要联网解锁一次"),
                Base64.NO_WRAP,
            )
        }

        val masterKey = VaultCrypto.deriveMasterKey(passphrase, salt, memory, iterations, parallelism)
        val kek = VaultCrypto.deriveKek(masterKey, salt)
        try {
            val dek = VaultCrypto.unwrapDek(kek, wrapped, forRecovery = false)
            setDek(dek, cacheOnDevice, requireScreenLock)
        } catch (e: javax.crypto.AEADBadTagException) {
            // 认证标签对不上只有一个原因：口令不对。不要把它说成「解密失败」。
            throw IllegalArgumentException("主口令不正确")
        } finally {
            Crypto.wipe(masterKey, kek)
        }
    }

    /** 忘了口令时的第二条路。恢复码解出的是同一把 DEK。 */
    fun unlockWithRecoveryCode(code: String, cacheOnDevice: Boolean, requireScreenLock: Boolean) {
        val recoveryKey = RecoveryCode.parse(code)
        val vaultInfo = api().getVault()
        val kdf = vaultInfo.getJSONObject("kdf")
        val salt = Base64.decode(kdf.getString("salt"), Base64.NO_WRAP)
        val rkek = VaultCrypto.deriveRecoveryKek(recoveryKey, salt)
        try {
            val dek = VaultCrypto.unwrapDek(
                rkek,
                Base64.decode(vaultInfo.getString("dekWrapRecovery"), Base64.NO_WRAP),
                forRecovery = true,
            )
            setDek(dek, cacheOnDevice, requireScreenLock)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("恢复码不属于这个账号")
        } finally {
            Crypto.wipe(recoveryKey, rkek)
        }
    }

    /**
     * 用本机 Keystore 里缓存的 DEK 静默解锁。
     * 返回 false 表示没有缓存或缓存已失效，需要走口令。
     * 抛 UserNotAuthenticatedException 表示要先过屏幕锁，调用方拉起验证后重试。
     */
    fun unlockFromCache(): Boolean {
        val cached = keystore.load() ?: return false
        dekInMemory = cached
        return true
    }

    val hasDeviceCache: Boolean get() = keystore.hasCachedDek
    val requiresScreenLock: Boolean get() = keystore.requiresScreenLock

    // ---------------------------------------------------------------- 改口令

    /**
     * 改主口令。只重新包裹 DEK，服务器上的联系人密文一条都不用重传 ——
     * 这是把「加密密钥」和「口令密钥」分成两层的主要好处。
     *
     * 恢复码保持不变（用同一把恢复密钥重新包一次），所以用户不用重新抄一遍。
     */
    fun changePassphrase(currentPassphrase: String, newPassphrase: String, recoveryCode: String) {
        val dek = dekInMemory ?: throw IllegalStateException("请先解锁")
        val recoveryKey = RecoveryCode.parse(recoveryCode)

        val oldSalt = session.kdfSalt ?: throw IllegalStateException("缺少 KDF 参数")
        val oldMasterKey = VaultCrypto.deriveMasterKey(
            currentPassphrase, oldSalt, session.kdfMemoryKiB, session.kdfIterations, session.kdfParallelism,
        )

        // 换口令顺便换盐，避免新旧口令共用同一个盐
        val newSalt = Crypto.randomBytes(16)
        val newMasterKey = VaultCrypto.deriveMasterKey(newPassphrase, newSalt)
        val newKek = VaultCrypto.deriveKek(newMasterKey, newSalt)
        val newRkek = VaultCrypto.deriveRecoveryKek(recoveryKey, newSalt)

        try {
            api().rewrapVault(
                currentAuthSecret = VaultCrypto.deriveAuthSecret(oldMasterKey, oldSalt),
                newAuthSecret = VaultCrypto.deriveAuthSecret(newMasterKey, newSalt),
                salt = newSalt,
                memoryKiB = VaultCrypto.KDF_MEMORY_KIB,
                iterations = VaultCrypto.KDF_ITERATIONS,
                parallelism = VaultCrypto.KDF_PARALLELISM,
                dekWrapPassword = VaultCrypto.wrapDek(newKek, dek, forRecovery = false),
                dekWrapRecovery = VaultCrypto.wrapDek(newRkek, dek, forRecovery = true),
            )
            session.saveKdf(
                newSalt, VaultCrypto.KDF_MEMORY_KIB, VaultCrypto.KDF_ITERATIONS,
                VaultCrypto.KDF_PARALLELISM, session.vaultVersion + 1,
            )
            session.wrappedDekCache = Base64.encodeToString(
                VaultCrypto.wrapDek(newKek, dek, forRecovery = false), Base64.NO_WRAP,
            )
        } finally {
            Crypto.wipe(oldMasterKey, newMasterKey, newKek, newRkek, recoveryKey)
        }
    }

    // ---------------------------------------------------------------- 锁定与注销

    /** 把 DEK 从内存里抹掉。Keystore 里的缓存不动，下次还能静默解锁。 */
    fun lock() {
        Crypto.wipe(dekInMemory)
        dekInMemory = null
    }

    /** 彻底断开这台设备：撤销服务端令牌、清掉本地全部同步痕迹。本机联系人不删。 */
    fun signOut(revokeOnServer: Boolean = true) {
        if (revokeOnServer) {
            runCatching { api().logout() }
        }
        lock()
        keystore.clear()
        session.wipe()
    }

    // ---------------------------------------------------------------- 内部

    private fun completeMfaIfNeeded(
        api: SyncApi,
        response: JSONObject,
        mfaCode: String?,
    ): JSONObject {
        if (!response.optBoolean("mfaRequired", false)) return response

        val methodsJson = response.optJSONArray("methods")
        val methods = if (methodsJson == null) {
            emptyList()
        } else {
            (0 until methodsJson.length()).mapNotNull { index ->
                methodsJson.optString(index).takeIf { it.isNotBlank() }
            }
        }
        if (mfaCode.isNullOrBlank()) {
            throw MfaRequired(methods, response.optBoolean("requireAll", false))
        }

        val token = response.optString("mfaToken")
        if (token.isBlank()) throw IllegalStateException("服务器未返回两步验证令牌")
        return api.completeMfa(token, mfaCode)
    }

    private fun persistSession(username: String, response: JSONObject) {
        session.username = username
        session.accountId = response.optString("accountId")
        session.deviceId = response.optString("deviceId")
        session.saveTokens(
            accessToken = response.getString("accessToken"),
            refreshToken = response.getString("refreshToken"),
            accessExpiresAt = response.optLong("accessExpiresAt", 0),
        )
        response.optJSONObject("kdf")?.let { kdf ->
            session.saveKdf(
                Base64.decode(kdf.getString("salt"), Base64.NO_WRAP),
                kdf.optInt("memoryKiB", VaultCrypto.KDF_MEMORY_KIB),
                kdf.optInt("iterations", VaultCrypto.KDF_ITERATIONS),
                kdf.optInt("parallelism", VaultCrypto.KDF_PARALLELISM),
                response.optInt("vaultVersion", 1),
            )
        }
        if (response.has("dekWrapPassword")) {
            session.wrappedDekCache = response.getString("dekWrapPassword")
        }
    }

    private fun setDek(dek: ByteArray, cacheOnDevice: Boolean, requireScreenLock: Boolean) {
        dekInMemory = dek
        if (cacheOnDevice) {
            keystore.store(dek, requireScreenLock)
        } else {
            keystore.clear()
        }
    }
}
