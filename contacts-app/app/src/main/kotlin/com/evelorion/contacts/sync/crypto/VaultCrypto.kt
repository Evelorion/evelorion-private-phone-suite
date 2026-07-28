package com.evelorion.contacts.sync.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.text.Normalizer

/**
 * 密钥体系。整棵树长这样：
 *
 *   主口令 ──Argon2id(salt)──► MK（主密钥，只在内存里存在）
 *                               ├─HKDF"fc.kek.v1"──► KEK ──包裹──► DEK
 *                               └─HKDF"fc.auth.v1"─► authSecret（送服务器认证）
 *
 *   恢复码 ──HKDF"fc.rkek.v1"─► RKEK ──包裹──► 同一把 DEK
 *
 *   DEK ──HKDF"fc.rec.v1"(salt=联系人 uuid)──► 每条联系人各自的记录密钥
 *       ──HKDF"fc.idx.v1"─────────────────► 盲索引密钥（只留本机）
 *       ──HKDF"fc.blob.v1"────────────────► 头像密钥
 *
 * 关键性质：
 *   · 服务器只拿到 authSecret 和被包裹的 DEK，两者都推不出 MK，也解不开任何联系人。
 *   · 改口令只需要重新包裹 DEK，一条密文都不用重传。
 *   · 恢复码是 DEK 的第二条独立路径，忘了口令也能拿回数据。
 */
object VaultCrypto {

    const val KDF_MEMORY_KIB = 65_536      // 64 MiB
    const val KDF_ITERATIONS = 3
    const val KDF_PARALLELISM = 4
    const val SCHEMA_VERSION = 1

    private const val INFO_KEK = "fc.kek.v1"
    private const val INFO_AUTH = "fc.auth.v1"

    /**
     * 恢复码派生认证凭据用的域分离标签。
     *
     * 必须和 INFO_AUTH 不同 —— 一样的话拿到其中一个就能推出另一个，
     * 恢复码和口令的独立性就没了。
     *
     * 这个字符串在四份实现里必须逐字一致：
     *   server/test/client.ts、server/web/lib/crypto.js、这里。
     */
    private const val INFO_AUTH_RECOVERY = "fc.auth.recovery.v1"
    private const val INFO_RECOVERY = "fc.rkek.v1"
    private const val INFO_RECORD = "fc.rec.v1"
    private const val INFO_INDEX = "fc.idx.v1"
    private const val INFO_BLOB_ID = "fc.blobid.v1"
    private const val INFO_BLOB_KEY = "fc.blob.v1"
    private const val AAD_DEK_PASSWORD = "fc.dek.pw.v1"
    private const val AAD_DEK_RECOVERY = "fc.dek.rc.v1"

    private val argon2 by lazy { Argon2Kt() }

    /**
     * 口令先做 NFKC 归一化。中文输入法打出的全角字符、不同来源的组合字符，
     * 不归一化的话同一个口令在两台设备上会派生出不同的密钥。
     */
    fun deriveMasterKey(
        passphrase: String,
        salt: ByteArray,
        memoryKiB: Int = KDF_MEMORY_KIB,
        iterations: Int = KDF_ITERATIONS,
        parallelism: Int = KDF_PARALLELISM,
    ): ByteArray {
        require(salt.size >= 16) { "盐至少要 16 字节" }
        val normalized = Normalizer.normalize(passphrase, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        val result = argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = normalized,
            salt = salt,
            tCostInIterations = iterations,
            mCostInKibibyte = memoryKiB,
            parallelism = parallelism,
            hashLengthInBytes = Crypto.KEY_BYTES,
        )
        normalized.fill(0)
        return result.rawHashAsByteArray()
    }

    fun deriveKek(masterKey: ByteArray, salt: ByteArray): ByteArray =
        Crypto.hkdf(masterKey, salt, INFO_KEK)

    /** 送到服务器的认证值。它和 KEK 在 HKDF 的 info 上做了域分离，服务器拿到它也解不开 DEK。 */
    fun deriveAuthSecret(masterKey: ByteArray, salt: ByteArray): String =
        Crypto.toHex(Crypto.hkdf(masterKey, salt, INFO_AUTH))

    /**
     * 恢复码派生的认证凭据。
     *
     * 有了它，恢复码本身就能当登录凭据 —— 否则「忘记主口令」是死局：
     * 恢复码能解开 DEK，但登录这一关需要口令派生的 authSecret。
     *
     * 输入是恢复码解析出的原始密钥，不是恢复码字符串。
     */
    fun deriveRecoveryAuthSecret(recoveryKey: ByteArray, salt: ByteArray): String =
        Crypto.toHex(Crypto.hkdf(recoveryKey, salt, INFO_AUTH_RECOVERY))

    fun deriveRecoveryKek(recoveryKey: ByteArray, salt: ByteArray): ByteArray =
        Crypto.hkdf(recoveryKey, salt, INFO_RECOVERY)

    /**
     * 按 collection 派生的子密钥。
     *
     * 电话 App 只拿得到 collection = "calls" 的这一把，拿不到 DEK 本身。
     * 所以它就算被攻破，攻击者也解不开通讯录 —— 反过来也一样。
     * 通讯录通过一个 signature 权限保护的 ContentProvider 把这把子密钥交出去。
     *
     * 它和记录密钥、盲索引密钥都做了 info 域分离，互相推不出来。
     */
    fun deriveCollectionKey(dek: ByteArray, salt: ByteArray, collection: String): ByteArray =
        Crypto.hkdf(dek, salt, "fc.collection.$collection.v1")

    fun wrapDek(kek: ByteArray, dek: ByteArray, forRecovery: Boolean): ByteArray =
        Crypto.seal(kek, dek, aadFor(forRecovery))

    fun unwrapDek(kek: ByteArray, wrapped: ByteArray, forRecovery: Boolean): ByteArray =
        Crypto.open(kek, wrapped, aadFor(forRecovery))

    private fun aadFor(forRecovery: Boolean) =
        (if (forRecovery) AAD_DEK_RECOVERY else AAD_DEK_PASSWORD).toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------ 记录加密

    fun deriveRecordKey(dek: ByteArray, uuid: String): ByteArray =
        Crypto.hkdf(dek, Crypto.uuidToBytes(uuid), INFO_RECORD)

    /**
     * AAD 绑定 uuid + rev + schema 版本。
     * 这样即使服务器是恶意的，它也没法把旧版本的密文冒充成新版本推给你（回滚攻击），
     * 也没法把 A 联系人的密文塞到 B 的位置上。
     */
    fun recordAad(uuid: String, rev: Int, schemaVersion: Int = SCHEMA_VERSION): ByteArray {
        val out = ByteArray(21)
        Crypto.uuidToBytes(uuid).copyInto(out, 0)
        out[16] = (rev ushr 24).toByte()
        out[17] = (rev ushr 16).toByte()
        out[18] = (rev ushr 8).toByte()
        out[19] = rev.toByte()
        out[20] = schemaVersion.toByte()
        return out
    }

    /** 返回 nonce ‖ ciphertext‖tag，调用方分开做 base64 后发给服务端。 */
    fun encryptRecord(dek: ByteArray, uuid: String, rev: Int, json: String): ByteArray {
        val key = deriveRecordKey(dek, uuid)
        try {
            return Crypto.seal(key, Crypto.pad(json.toByteArray(Charsets.UTF_8)), recordAad(uuid, rev))
        } finally {
            Crypto.wipe(key)
        }
    }

    fun decryptRecord(dek: ByteArray, uuid: String, rev: Int, sealed: ByteArray): String {
        val key = deriveRecordKey(dek, uuid)
        try {
            return Crypto.unpad(Crypto.open(key, sealed, recordAad(uuid, rev))).toString(Charsets.UTF_8)
        } finally {
            Crypto.wipe(key)
        }
    }

    // ------------------------------------------------------------ 盲索引

    fun deriveIndexKey(dek: ByteArray, salt: ByteArray): ByteArray =
        Crypto.hkdf(dek, salt, INFO_INDEX)

    /**
     * 号码的盲索引，只存在本机数据库里，不上传。
     * 作用是让电话 App 能按来电号码查到联系人，而不需要把号码明文存成可搜索的列。
     * 输入必须是归一化后的号码，否则 +8613800138000 和 13800138000 会索引到不同的值。
     */
    fun blindIndex(indexKey: ByteArray, normalizedNumber: String): String =
        Crypto.toHex(Crypto.hmacSha256(indexKey, normalizedNumber.toByteArray(Charsets.UTF_8))).substring(0, 32)

    // ------------------------------------------------------------ 头像 blob

    /**
     * 头像的内容 id 用 DEK 派生的密钥做 HMAC，不是明文 SHA-256。
     * 用明文哈希的话，服务器看到两个账号有同一个 hash 就知道它们存了同一张图。
     */
    fun blobId(dek: ByteArray, plaintext: ByteArray): String {
        val idKey = Crypto.hkdf(dek, ByteArray(0), INFO_BLOB_ID)
        try {
            return Crypto.toHex(Crypto.hmacSha256(idKey, plaintext))
        } finally {
            Crypto.wipe(idKey)
        }
    }

    fun sealBlob(dek: ByteArray, plaintext: ByteArray): Pair<String, ByteArray> {
        val hash = blobId(dek, plaintext)
        val key = Crypto.hkdf(dek, Crypto.fromHex(hash), INFO_BLOB_KEY)
        try {
            return hash to Crypto.seal(key, plaintext, Crypto.fromHex(hash))
        } finally {
            Crypto.wipe(key)
        }
    }

    fun openBlob(dek: ByteArray, hash: String, sealed: ByteArray): ByteArray {
        val key = Crypto.hkdf(dek, Crypto.fromHex(hash), INFO_BLOB_KEY)
        try {
            return Crypto.open(key, sealed, Crypto.fromHex(hash))
        } finally {
            Crypto.wipe(key)
        }
    }

    // ------------------------------------------------------------ 条目 id

    /**
     * 列表条目（号码、邮箱、地址……）的 id 由内容确定性推导，不随机生成。
     *
     * 这么做有两个原因：
     *   1. commons 的 LocalContact 表是外部依赖里的 Room 实体，加不了「每条号码的 uuid」这种列。
     *   2. 两台设备各自录入同一个号码时会算出同一个 id，三方合并时自动去重。
     *
     * identity 只包含「决定这条是不是同一条」的部分，label 和 type 改了 id 不变。
     *
     * 分隔符是 NUL（0x00）不是空格。空格会造成歧义：
     * itemId("phones", "a b") 和 itemId("phones a", "b") 会算出同一个值。
     * NUL 在合法的 UTF-8 文本里不可能出现，所以不会撞。
     */
    fun itemId(list: String, identity: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(list.toByteArray(Charsets.UTF_8))
        digest.update(0x00)
        digest.update(identity.toByteArray(Charsets.UTF_8))
        return Crypto.toHex(digest.digest()).substring(0, 32)
    }

    // ------------------------------------------------------------ 建库

    class NewVault(
        val salt: ByteArray,
        val dek: ByteArray,
        val recoveryKey: ByteArray,
        val recoveryCode: String,
        val authSecret: String,
        /** 恢复码派生的登录凭据。发给服务器存哈希，让恢复码也能登录。 */
        val recoveryAuthSecret: String,
        val dekWrapPassword: ByteArray,
        val dekWrapRecovery: ByteArray,
    )

    /** 首次启用同步时调用。恢复码只在这一刻出现，之后任何地方都拿不回来。 */
    fun createVault(passphrase: String): NewVault {
        val salt = Crypto.randomBytes(16)
        val masterKey = deriveMasterKey(passphrase, salt)
        val dek = Crypto.randomBytes(Crypto.KEY_BYTES)
        val recoveryKey = Crypto.randomBytes(Crypto.KEY_BYTES)
        val kek = deriveKek(masterKey, salt)
        val rkek = deriveRecoveryKek(recoveryKey, salt)
        try {
            return NewVault(
                salt = salt,
                dek = dek,
                recoveryKey = recoveryKey,
                recoveryCode = RecoveryCode.format(recoveryKey),
                authSecret = deriveAuthSecret(masterKey, salt),
                recoveryAuthSecret = deriveRecoveryAuthSecret(recoveryKey, salt),
                dekWrapPassword = wrapDek(kek, dek, forRecovery = false),
                dekWrapRecovery = wrapDek(rkek, dek, forRecovery = true),
            )
        } finally {
            Crypto.wipe(masterKey, kek, rkek)
        }
    }
}
