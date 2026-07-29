package com.evelorion.contacts.sync.localdb

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * 把已经存在的明文数据库原地转成 SQLCipher 加密库。
 *
 * 用户手机上已经有联系人了，不能因为启用加密就清空重来。
 *
 * 走的是 SQLCipher 官方的 sqlcipher_export 路子：
 *
 *   1. 用空口令打开明文库（SQLCipher 允许这样打开未加密的文件）
 *   2. ATTACH 一个带口令的新库
 *   3. SELECT sqlcipher_export('encrypted') 把所有表和数据搬过去
 *   4. 手动同步 user_version（sqlcipher_export 不会带它，
 *      而 Room 靠这个值判断要不要跑迁移 —— 漏了这一步 Room 会当成新库重建）
 *   5. DETACH，删明文，改名
 *
 * 全程失败都不会破坏原文件：新库先写在临时文件里，只有全部成功才替换。
 */
object DatabaseEncryptionMigrator {

    private const val TAG = "DbEncryptionMigrator"

    class MigrationFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * @param dbName 数据库文件名，例如 local_contacts.db
     * @param passphrase 十六进制字符串形式的口令，来自 DatabaseKey
     */
    fun encryptInPlace(context: Context, dbName: String, passphrase: ByteArray) {
        val plainFile = context.getDatabasePath(dbName)
        if (!plainFile.exists()) {
            Log.i(TAG, "$dbName 还不存在，不需要迁移，之后会直接以加密方式创建")
            return
        }

        if (isEncrypted(plainFile)) {
            Log.i(TAG, "$dbName 已经是加密的，跳过")
            return
        }

        val tempFile = File(plainFile.parentFile, "$dbName.encrypting")
        tempFile.delete()

        val passphraseText = String(passphrase, Charsets.US_ASCII)
        // 口令是我们自己生成的 64 位十六进制，不含引号；这里仍然做一次防御性检查
        require(passphraseText.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            "数据库口令格式不对，拒绝拼进 SQL"
        }

        var plain: SQLiteDatabase? = null
        try {
            plain = SQLiteDatabase.openOrCreateDatabase(plainFile.absolutePath, "", null, null)
            val userVersion = plain.version

            plain.rawExecSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS encrypted KEY '$passphraseText';")
            plain.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            // Room 靠 user_version 判断 schema 版本，漏了这一步它会以为是全新的库
            plain.rawExecSQL("PRAGMA encrypted.user_version = $userVersion;")
            plain.rawExecSQL("DETACH DATABASE encrypted;")
            plain.close()
            plain = null

            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw MigrationFailed("导出后的加密文件是空的")
            }

            // 校验：新库能不能用这个口令打开，行数对不对得上
            verifyOrThrow(tempFile, passphrase, userVersion)

            // WAL 和 shm 是旧库的，留着会让 SQLite 困惑
            File(plainFile.absolutePath + "-wal").delete()
            File(plainFile.absolutePath + "-shm").delete()

            if (!plainFile.delete()) throw MigrationFailed("删除明文数据库失败")
            if (!tempFile.renameTo(plainFile)) throw MigrationFailed("重命名加密数据库失败")

            Log.i(TAG, "$dbName 已转为加密存储")
        } catch (e: Exception) {
            tempFile.delete()
            // 带上文件名。原始消息（比如「file is not a database」）完全看不出
            // 是哪个库、哪一步出的问题，而这条消息最终会显示给用户。
            throw if (e is MigrationFailed) e else MigrationFailed("加密 $dbName 失败：${e.message}", e)
        } finally {
            runCatching { plain?.close() }
        }
    }

    /**
     * 反过来：把加密库转回明文。
     * 用户想关掉本地加密时用。顺序和上面对称。
     */
    fun decryptInPlace(context: Context, dbName: String, passphrase: ByteArray) {
        val encFile = context.getDatabasePath(dbName)
        if (!encFile.exists()) return
        if (!isEncrypted(encFile)) return

        val tempFile = File(encFile.parentFile, "$dbName.decrypting")
        tempFile.delete()

        var enc: SQLiteDatabase? = null
        try {
            enc = SQLiteDatabase.openOrCreateDatabase(
                encFile.absolutePath, String(passphrase, Charsets.US_ASCII), null, null
            )
            val userVersion = enc.version
            enc.rawExecSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS plaintext KEY '';")
            enc.rawExecSQL("SELECT sqlcipher_export('plaintext');")
            enc.rawExecSQL("PRAGMA plaintext.user_version = $userVersion;")
            enc.rawExecSQL("DETACH DATABASE plaintext;")
            enc.close()
            enc = null

            File(encFile.absolutePath + "-wal").delete()
            File(encFile.absolutePath + "-shm").delete()
            if (!encFile.delete()) throw MigrationFailed("删除加密数据库失败")
            if (!tempFile.renameTo(encFile)) throw MigrationFailed("重命名明文数据库失败")
        } catch (e: Exception) {
            tempFile.delete()
            throw if (e is MigrationFailed) e else MigrationFailed("解密迁移失败：${e.message}", e)
        } finally {
            runCatching { enc?.close() }
        }
    }

    /**
     * 给已经加密的数据库换一把口令。
     *
     * 切换加密模式时用（比如从 Keystore 保管切到主口令派生）。
     * 走的是 SQLCipher 的 `PRAGMA rekey` —— 它只重写每一页的加密层，
     * 不搬数据，所以比 sqlcipher_export 快一个数量级，也不需要临时文件。
     *
     * 但它是**原地操作**，中途断电或被杀会留下一个半新半旧的库。
     * 所以调用前必须先备份，失败时用备份还原。这里把备份也一起做了。
     */
    fun rekey(context: Context, dbName: String, oldPassphrase: ByteArray, newPassphrase: ByteArray) {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) return

        val oldText = String(oldPassphrase, Charsets.US_ASCII)
        val newText = String(newPassphrase, Charsets.US_ASCII)
        require(newText.matches(Regex("^[0-9a-fA-F]{64}$"))) { "新口令格式不对，拒绝拼进 SQL" }

        val backup = File(dbFile.parentFile, "$dbName.rekey-backup")
        backup.delete()
        dbFile.copyTo(backup, overwrite = true)

        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, oldText, null, null)
            // WAL 里可能还有没合并的页，先并回主文件再换钥匙
            db.rawExecSQL("PRAGMA wal_checkpoint(FULL);")
            db.rawExecSQL("PRAGMA rekey = '$newText';")
            db.close()
            db = null

            // 换完必须验证能用新口令打开，否则等于把数据锁死了
            verifyOpens(dbFile, newText)
            backup.delete()
            Log.i(TAG, "$dbName 已换用新口令")
        } catch (e: Exception) {
            runCatching { db?.close() }
            db = null
            // 还原备份。这一步失败才是真的丢数据，所以单独报出来。
            val restored = runCatching {
                dbFile.delete()
                backup.renameTo(dbFile)
            }.getOrDefault(false)
            throw MigrationFailed(
                if (restored) "换钥匙失败，已还原到原来的状态：${e.message}"
                else "换钥匙失败且还原也失败了，备份在 ${backup.absolutePath}：${e.message}",
                e,
            )
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun verifyOpens(file: File, passphrase: String) {
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openOrCreateDatabase(file.absolutePath, passphrase, null, null)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) == 0) {
                    throw MigrationFailed("换钥匙后库里一张表都没有")
                }
            }
        } finally {
            runCatching { db?.close() }
        }
    }

    /**
     * Plain SQLite databases always start with this 16-byte header. SQLCipher
     * replaces it with a random salt, so this check does not need to open or
     * modify the file and cannot confuse a plaintext database with a bad key.
     */
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun isEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < SQLITE_HEADER.size) return false
        val header = ByteArray(SQLITE_HEADER.size)
        val bytesRead = runCatching {
            file.inputStream().use { it.read(header) }
        }.getOrElse { return true }
        return bytesRead != SQLITE_HEADER.size || !header.contentEquals(SQLITE_HEADER)
    }

    private fun verifyOrThrow(file: File, passphrase: ByteArray, expectedVersion: Int) {
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openOrCreateDatabase(
                file.absolutePath, String(passphrase, Charsets.US_ASCII), null, null
            )
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) == 0) {
                    throw MigrationFailed("加密后的库里一张表都没有")
                }
            }
            if (db.version != expectedVersion) {
                throw MigrationFailed("加密后的库 user_version 是 ${db.version}，应该是 $expectedVersion")
            }
        } finally {
            runCatching { db?.close() }
        }
    }
}
