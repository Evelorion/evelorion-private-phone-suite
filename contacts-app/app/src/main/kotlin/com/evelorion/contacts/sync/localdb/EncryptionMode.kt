package com.evelorion.contacts.sync.localdb

import android.content.Context
import com.evelorion.contacts.sync.VaultManager
import com.evelorion.contacts.sync.crypto.Crypto
import com.evelorion.contacts.sync.crypto.VaultCrypto
import com.evelorion.contacts.sync.db.SyncDatabase

/**
 * 本地数据库加密的三档模式，以及模式之间的切换。
 *
 * 切换模式本质上就是「换一把数据库口令」，靠 SQLCipher 的 PRAGMA rekey 完成，
 * 不需要重新导出整个库。
 *
 * 有个必须处理好的顺序问题：切换时两个数据库（联系人库、同步库）都要换，
 * 而且要在**所有连接关闭之后**做，否则 rekey 会因为文件被占用而失败或产生半新半旧的库。
 */
enum class EncryptionMode {
    /** Keystore 保管随机口令。开机即可用。 */
    KEYSTORE,

    /** Keystore + 屏幕锁验证。锁屏状态下就算 root 也开不了库。 */
    KEYSTORE_SCREEN_LOCK,

    /** 由主口令派生。Keystore 里什么都不存，是唯一能挡住 root 的模式。 */
    PASSPHRASE;

    companion object {
        private const val PREFS = "fc_localdb_mode"
        private const val KEY_MODE = "mode"

        fun current(context: Context): EncryptionMode {
            val name = prefs(context).getString(KEY_MODE, KEYSTORE.name)
            return entries.firstOrNull { it.name == name } ?: KEYSTORE
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun describe(mode: EncryptionMode): String = when (mode) {
            KEYSTORE ->
                "口令由 Android Keystore 保管，开机即可用。挡住的是拿到设备文件的攻击者，挡不住 root。"
            KEYSTORE_SCREEN_LOCK ->
                "同上，但用之前需要屏幕锁验证（5 分钟内有效）。锁屏状态下 root 也开不了库。" +
                    "代价是开机后第一次用通讯录要验证一次，在那之前来电显示查不到私密联系人。"
            PASSPHRASE ->
                "口令由主口令派生，Keystore 里什么都不存。唯一能挡住 root 的模式。" +
                    "代价是每次进程启动都要输主口令，后台同步和来电显示在解锁前都不可用。"
        }

        /**
         * 切换模式。
         *
         * @param passphrase 切到 PASSPHRASE 模式，或者从 PASSPHRASE 模式切走时必须提供主口令。
         *                   其余情况传 null。
         *
         * 这个方法会阻塞几百毫秒到几秒（Argon2 + 两个库的 rekey），必须在后台线程调用。
         * 中途失败会抛异常，数据库保持在切换前的状态 —— rekey 内部有备份还原。
         */
        fun switchTo(context: Context, target: EncryptionMode, passphrase: String?) {
            val from = current(context)
            if (from == target) return

            val oldKey = resolveKey(context, from, passphrase)
                ?: throw IllegalStateException("拿不到当前的数据库口令，无法切换")
            val newKey = generateKeyFor(context, target, passphrase)

            try {
                // 关掉所有连接再动文件。Room 的实例也要一起丢掉，
                // 否则它还持有旧口令打开的 handle。
                closeAllDatabases(context)

                DatabaseEncryptionMigrator.rekey(context, "local_contacts.db", oldKey, newKey)
                DatabaseEncryptionMigrator.rekey(context, "fc_sync.db", oldKey, newKey)

                persistKey(context, target, newKey)
                prefs(context).edit().putString(KEY_MODE, target.name).apply()

                // 用新口令把加密层重新装回 commons。不做这一步的话，
                // 下次有人调 ContactsDatabase.getInstance() 会拿到一个明文实例，
                // 而文件已经是加密的 —— 直接崩。
                if (target != PASSPHRASE) {
                    EncryptedDatabases.reinstall(context)
                }
            } finally {
                Crypto.wipe(oldKey, newKey)
            }
        }

        /**
         * PASSPHRASE 模式下，每次进程启动都要用主口令算出数据库口令。
         * 用的是和同步 DEK 同源的主密钥，但 HKDF 的 info 不同，做了域分离 ——
         * 拿到数据库口令推不出 DEK，反过来也一样。
         */
        fun keyFromPassphrase(context: Context, passphrase: String): ByteArray {
            val session = VaultManager.get(context).session
            val salt = session.kdfSalt
                ?: throw IllegalStateException("本机还没有同步账号的密钥参数，无法使用主口令模式")
            val masterKey = VaultCrypto.deriveMasterKey(
                passphrase, salt, session.kdfMemoryKiB, session.kdfIterations, session.kdfParallelism
            )
            return try {
                DatabaseKey.fromPassphraseDerivedKey(masterKey, salt)
            } finally {
                Crypto.wipe(masterKey)
            }
        }

        private fun resolveKey(context: Context, mode: EncryptionMode, passphrase: String?): ByteArray? =
            when (mode) {
                KEYSTORE, KEYSTORE_SCREEN_LOCK -> DatabaseKey.getOrCreate(context)
                PASSPHRASE -> passphrase?.let { keyFromPassphrase(context, it) }
            }

        private fun generateKeyFor(context: Context, mode: EncryptionMode, passphrase: String?): ByteArray =
            when (mode) {
                KEYSTORE, KEYSTORE_SCREEN_LOCK ->
                    Crypto.toHex(Crypto.randomBytes(32)).toByteArray(Charsets.US_ASCII)

                PASSPHRASE -> keyFromPassphrase(
                    context,
                    passphrase ?: throw IllegalArgumentException("切到主口令模式需要提供主口令"),
                )
            }

        private fun persistKey(context: Context, mode: EncryptionMode, key: ByteArray) {
            when (mode) {
                KEYSTORE -> DatabaseKey.storeExternal(context, key, requireScreenLock = false)
                KEYSTORE_SCREEN_LOCK -> DatabaseKey.storeExternal(context, key, requireScreenLock = true)
                // 主口令模式下什么都不存 —— 这正是它能挡住 root 的原因
                PASSPHRASE -> DatabaseKey.clear(context)
            }
        }

        /**
         * 关掉所有数据库连接。
         *
         * 联系人库那个实例在 commons 的静态字段里，得通过 destroyInstance 清掉；
         * 同步库是我们自己的，直接 close。
         */
        private fun closeAllDatabases(context: Context) {
            runCatching { org.fossify.commons.databases.ContactsDatabase.destroyInstance() }
            runCatching { SyncDatabase.closeInstance() }
        }
    }
}
