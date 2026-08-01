package com.evelorion.phone.sync.crypto

/**
 * 记录加密。
 *
 * ── 这是通讯录那份 VaultCrypto 的**裁剪版** ────────────────
 *
 * 只保留「用一把给定的密钥加解密一条记录」所需的部分。
 * 被**刻意删掉**的是：
 *
 *   deriveMasterKey / deriveKek / deriveAuthSecret   ← 从主口令派生密钥
 *   wrapDek / unwrapDek / createVault                ← 包裹和创建 DEK
 *   deriveRecoveryKek / RecoveryCode                 ← 恢复码那一整条路
 *
 * 删掉不是因为编译不过，是因为**电话 App 不该有这些能力**。
 * 它拿到的只是 HKDF(DEK, "fc.collection.calls.v2") 派生出的子密钥，
 * 从设计上就推不回 DEK。如果这个文件里留着建库和解包的代码，
 * 哪天有人「顺手」在电话 App 里加个登录框，边界就没了 ——
 * 而那时候没有任何编译错误会提醒他。
 *
 * ── 必须和通讯录逐字一致的部分 ──────────────────────────────
 *
 * HKDF 的 info 标签、AAD 的拼法、padding 方式。这三样只要有一个字不同，
 * 两边加解密的就不是同一套东西，表现是「同步成功但解不开」。
 * 改这个文件之前，先去看通讯录那份。
 */
object VaultCrypto {

    const val SCHEMA_VERSION = 1

    private const val INFO_RECORD = "fc.rec.v1"
    private const val INFO_COLLECTION_PREFIX = "fc.collection."

    /**
     * collection 子密钥。
     *
     * 电话 App 自己算不出它（需要 DEK），这里留着这个函数只是为了
     * 说明它是怎么来的，以及在测试里能复现。实际运行时是通讯录算好交过来的。
     */
    fun deriveCollectionKey(dek: ByteArray, salt: ByteArray, collection: String): ByteArray =
        Crypto.hkdf(dek, salt, "$INFO_COLLECTION_PREFIX$collection.v1")

    /** 每条记录一把独立的键，用 uuid 当 salt。 */
    fun deriveRecordKey(key: ByteArray, uuid: String): ByteArray =
        Crypto.hkdf(key, uuid.toByteArray(Charsets.UTF_8), INFO_RECORD)

    /**
     * AAD 绑定 uuid ‖ rev ‖ schemaVersion。
     *
     * 绑 rev 的意义：服务器没法把一条旧版本的密文冒充成新版本
     * （回滚攻击），因为 AAD 对不上，解密会直接失败。
     * 代价是**还原备份时 rev 必须和当初一致** —— 改了 rev 再写回去，
     * 数据还在，但谁也打不开。
     */
    fun recordAad(uuid: String, rev: Int, schemaVersion: Int = SCHEMA_VERSION): ByteArray {
        val uuidBytes = uuid.replace("-", "").chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        return uuidBytes + byteArrayOf(
            (rev ushr 24).toByte(), (rev ushr 16).toByte(),
            (rev ushr 8).toByte(), rev.toByte(),
            schemaVersion.toByte(),
        )
    }

    fun encryptRecord(key: ByteArray, uuid: String, rev: Int, json: String): ByteArray {
        val recordKey = deriveRecordKey(key, uuid)
        try {
            return Crypto.seal(recordKey, Crypto.pad(json.toByteArray(Charsets.UTF_8)), recordAad(uuid, rev))
        } finally {
            Crypto.wipe(recordKey)
        }
    }

    fun decryptRecord(key: ByteArray, uuid: String, rev: Int, sealed: ByteArray): String {
        val recordKey = deriveRecordKey(key, uuid)
        try {
            return Crypto.unpad(Crypto.open(recordKey, sealed, recordAad(uuid, rev))).toString(Charsets.UTF_8)
        } finally {
            Crypto.wipe(recordKey)
        }
    }
}
