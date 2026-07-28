package com.evelorion.contacts.sync.localdb

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import com.evelorion.contacts.sync.crypto.Crypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地数据库的口令。
 *
 * 这是一串随机生成的 32 字节，转成 64 个十六进制字符后交给 SQLCipher 当 passphrase。
 * 用户看不到它，也不需要记它。
 *
 * 它自己被 Android Keystore 里的一把密钥包着：
 *   · 密钥材料在 TEE / StrongBox 里，App 只能请求它做加解密，读不出密钥本身
 *   · 把手机里的文件整个拷走的人，拿到的只有一个 GCM 密文和一个加密的数据库
 *   · 恢复出厂设置、卸载 App 会让这把密钥永久消失，数据库也就永久打不开了
 *
 * 两种模式，由 requireScreenLock 决定：
 *
 *   false（默认）  开机就能用。挡住的是「拿到设备文件」的攻击者。
 *                 通讯录正常显示，电话 App 的来电显示也正常。
 *
 *   true          Keystore 密钥要求近 5 分钟内验证过屏幕锁。
 *                 安全性更高，代价是没验证之前整个通讯录打不开 ——
 *                 包括来电时电话 App 查不到名字。
 *
 * 必须说清楚的边界：这两种模式挡的都是「离线拿到文件」。
 * 设备被 root 且攻击者能注入本 App 进程时，他可以直接让 App 自己解密 ——
 * 因为 App 本来就有权限这么做。想挡住那种情况，只能让密钥来自用户脑子里的口令
 * （见 fromPassphrase），代价是 App 在解锁前完全不可用。
 */
object DatabaseKey {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "fc_localdb_wrapper_v1"
    private const val PREFS = "fc_localdb"
    private const val KEY_WRAPPED = "wrapped_passphrase"
    private const val KEY_REQUIRES_AUTH = "requires_auth"
    private const val KEY_ENCRYPTED = "db_encrypted"
    private val AAD = "fc.localdb.v1".toByteArray(Charsets.UTF_8)
    private const val AUTH_VALIDITY_SECONDS = 300
    private const val GCM_IV_BYTES = 12

    /** 数据库还没加密过时为 false，此时 EncryptedDatabases 会触发一次性迁移。 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENCRYPTED, false)

    fun requiresScreenLock(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUIRES_AUTH, false)

    fun markEnabled(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENCRYPTED, true).apply()
    }

    /**
     * 取出数据库口令，没有就生成一把。
     *
     * 返回的是十六进制字符串的字节形式 —— SQLCipher 的 SupportOpenHelperFactory
     * 收 ByteArray 当 passphrase，而迁移时的 ATTACH 语句里要把同一个值写成 SQL 字符串。
     * 统一用 hex 字符串能保证两条路径喂给 SQLCipher 的是同一个东西，
     * 不会一边走 KDF 一边走裸密钥。
     *
     * 抛 UserNotAuthenticatedException 表示开了屏幕锁保护且还没验证，
     * 调用方应当拉起验证再重试，不要吞掉。
     */
    fun getOrCreate(context: Context, requireScreenLock: Boolean = requiresScreenLock(context)): ByteArray {
        load(context)?.let { return it }

        // 新生成一把。注意这里必须在数据库第一次被打开之前完成，
        // 否则会出现「用新口令去开已经用旧口令加密过的库」。
        val passphrase = Crypto.toHex(Crypto.randomBytes(32)).toByteArray(Charsets.US_ASCII)
        store(context, passphrase, requireScreenLock)
        return passphrase
    }

    /** Keystore 暂时用不了（不是密钥丢了）。上层必须原样抛出去，不能当成「没有密钥」。 */
    class KeystoreUnavailable(cause: Throwable?) :
        Exception("Android Keystore 暂时不可用，稍后重试：${cause?.message}", cause)

    private fun load(context: Context): ByteArray? {
        val stored = prefs(context).getString(KEY_WRAPPED, null)?.takeIf { it.isNotEmpty() } ?: return null

        // ── 这里的区分是性命攸关的 ────────────────────────────────
        //
        // 「密钥确实没了」和「这一刻取不到密钥」必须分开处理：
        //
        //   确实没了  → 数据库永远打不开了，清掉标记，上层重建
        //   暂时取不到 → 抛异常，什么都别动
        //
        // 以前两种情况都走「清掉标记」。于是一次瞬时的 Keystore 异常就会
        // 让 App 生成一把新口令、把已加密的库当成新库、最终读出一个空表 ——
        // 而空表会被同步引擎理解成「用户删光了联系人」。
        // 一次读不到密钥，赔上全部数据。
        val key = try {
            loadKeystoreKey()
        } catch (e: UserNotAuthenticatedException) {
            throw e
        } catch (e: Exception) {
            throw KeystoreUnavailable(e)
        }

        if (key == null) {
            if (!keystoreEntryDefinitelyGone()) {
                // 取不到但别名还在（或者连别名都问不出来）——— 属于「暂时不可用」
                throw KeystoreUnavailable(null)
            }
            // 别名确实不在了：改了锁屏方式、恢复出厂设置……
            // 数据库已经永远打不开，清掉标记把这个事实反映出来。
            prefs(context).edit().clear().apply()
            return null
        }
        val blob = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (blob.size <= GCM_IV_BYTES) return null

        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, GCM_IV_BYTES)))
                updateAAD(AAD)
                doFinal(blob.copyOfRange(GCM_IV_BYTES, blob.size))
            }
        } catch (e: UserNotAuthenticatedException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun store(context: Context, passphrase: ByteArray, requireScreenLock: Boolean) {
        if (requireScreenLock != requiresScreenLock(context)) deleteKeystoreEntry()
        val key = loadKeystoreKey() ?: generateKeystoreKey(requireScreenLock, useStrongBox = true)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val blob = cipher.iv + cipher.doFinal(passphrase)

        prefs(context).edit()
            .putString(KEY_WRAPPED, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putBoolean(KEY_REQUIRES_AUTH, requireScreenLock)
            .apply()
    }

    /**
     * 换成「要求屏幕锁验证」或换回来。
     * 只是把同一个数据库口令重新包一次，数据库本身不用重新加密。
     */
    fun setRequireScreenLock(context: Context, requireScreenLock: Boolean) {
        val passphrase = load(context) ?: return
        store(context, passphrase, requireScreenLock)
    }

    /**
     * 让数据库口令改为由主口令派生。
     *
     * 这是安全性最高的一档：口令只在用户脑子里，Keystore 里什么都没有，
     * root 过的设备上攻击者也拿不到 —— 除非他能等到用户下次输入。
     *
     * 代价很实在：解锁之前整个通讯录打不开，来电显示查不到名字，
     * 后台同步也跑不了。所以这不是默认选项。
     *
     * 用的是和同步保险库同一把 MK，但 HKDF 的 info 不同，做了域分离：
     * 拿到数据库口令推不出 DEK，反过来也一样。
     */
    fun fromPassphraseDerivedKey(masterKey: ByteArray, salt: ByteArray): ByteArray =
        Crypto.toHex(Crypto.hkdf(masterKey, salt, "fc.localdb.key.v1")).toByteArray(Charsets.US_ASCII)

    /**
     * 存一把外部生成的口令。切换加密模式时用（见 EncryptionMode.switchTo）。
     *
     * 只在数据库已经用这把口令 rekey 成功之后才能调用 ——
     * 顺序反了会导致存的口令和实际能开库的口令对不上，库就打不开了。
     */
    fun storeExternal(context: Context, passphrase: ByteArray, requireScreenLock: Boolean) {
        store(context, passphrase, requireScreenLock)
        markEnabled(context)
    }

    /** 关闭本地加密时用。注意：调用它之前必须先把数据库解密回明文，否则数据就打不开了。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        deleteKeystoreEntry()
    }

    // ------------------------------------------------------------ Keystore

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 取密钥。**故意不吞异常** —— 调用方要靠异常区分「没有」和「取不到」。
     * 返回 null 只表示 Keystore 明确回答了「这个别名没有密钥」。
     */
    private fun loadKeystoreKey(): SecretKey? =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey

    /**
     * 别名是不是真的不在了。
     *
     * 问不出来（Keystore 本身抛异常）时返回 false —— 也就是「不确定」按
     * 「还在」处理。判断错的代价不对等：误判成「没了」会清掉口令、
     * 让已加密的数据库再也打不开；误判成「还在」只是这次打不开，下次重试。
     */
    private fun keystoreEntryDefinitelyGone(): Boolean = try {
        !KeyStore.getInstance(KEYSTORE).apply { load(null) }.containsAlias(KEY_ALIAS)
    } catch (e: Exception) {
        false
    }

    private fun deleteKeystoreEntry() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun generateKeystoreKey(requireScreenLock: Boolean, useStrongBox: Boolean): SecretKey {
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
            if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setIsStrongBoxBacked(true)
            }
        }.build()

        return try {
            generator.init(spec)
            generator.generateKey()
        } catch (e: Exception) {
            // StrongBoxUnavailableException 在 API 28 才存在，按类型 catch 会在低版本
            // 触发类加载问题，所以用名字判断后降级重试一次。
            if (useStrongBox && e::class.java.simpleName == "StrongBoxUnavailableException") {
                deleteKeystoreEntry()
                generateKeystoreKey(requireScreenLock, useStrongBox = false)
            } else {
                throw e
            }
        }
    }
}
