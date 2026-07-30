package com.evelorion.contacts.sync.localdb

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * A second, encrypted local copy of the contacts database.
 *
 * SQLCipher encrypts the source database, so copying the checkpointed database
 * file does not create a plaintext export. The copy exists only to recover from
 * SQLite/Room replacing an unreadable database with a new empty one.
 */
object ContactDatabaseSafety {

    private const val TAG = "ContactDbSafety"
    private const val DATABASE_NAME = "local_contacts.db"
    private const val SNAPSHOT_NAME = "local_contacts.safety"
    private const val FAILED_NAME = "local_contacts.failed"
    private const val PREFS = "contact_database_safety"
    private const val KEY_COUNT = "snapshot_count"

    @Synchronized
    fun restoreIfNeeded(context: Context, passphrase: ByteArray): Boolean {
        val expectedCount = prefs(context).getInt(KEY_COUNT, 0)
        if (expectedCount <= 0) return false

        val atomic = AtomicFile(File(context.filesDir, SNAPSHOT_NAME))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return false

        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        val currentCount = databaseFile.takeIf(File::exists)?.let {
            countContacts(it, passphrase)
        }
        if (currentCount != null && currentCount > 0) return false

        databaseFile.parentFile?.mkdirs()
        val candidate = File(databaseFile.parentFile, "$DATABASE_NAME.restoring")
        candidate.delete()
        atomic.openRead().use { input ->
            FileOutputStream(candidate).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }

        val candidateCount = countContacts(candidate, passphrase)
        if (candidateCount == null || candidateCount < expectedCount) {
            candidate.delete()
            Log.e(TAG, "本地安全快照校验失败，拒绝覆盖联系人主库")
            return false
        }

        if (databaseFile.exists()) {
            val failed = File(context.filesDir, FAILED_NAME)
            runCatching { databaseFile.copyTo(failed, overwrite = true) }
        }
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
        if (databaseFile.exists() && !databaseFile.delete()) {
            candidate.delete()
            throw IllegalStateException("无法移走异常的联系人主库")
        }
        if (!candidate.renameTo(databaseFile)) {
            candidate.delete()
            throw IllegalStateException("无法恢复联系人本地安全快照")
        }

        Log.w(TAG, "联系人主库异常，已从本机加密安全快照恢复 $candidateCount 条")
        return true
    }

    @Synchronized
    fun snapshotAfterMutation(context: Context) {
        val database = EncryptedDatabases.databaseForSafetySnapshot()
        val count = database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM contacts")
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val atomic = AtomicFile(File(context.filesDir, SNAPSHOT_NAME))
        if (count == 0) {
            atomic.delete()
            prefs(context).edit().putInt(KEY_COUNT, 0).commit()
            return
        }

        runCatching {
            database.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)")
                .use { it.moveToFirst() }
            val source = context.getDatabasePath(DATABASE_NAME)
            require(source.exists() && source.length() > 0L) { "联系人数据库文件不存在" }

            val output = atomic.startWrite()
            try {
                source.inputStream().use { it.copyTo(output) }
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (e: Exception) {
                atomic.failWrite(output)
                throw e
            }
            check(prefs(context).edit().putInt(KEY_COUNT, count).commit()) {
                "无法保存联系人安全快照元数据"
            }
        }.onFailure {
            Log.e(TAG, "更新联系人本地安全快照失败", it)
        }
    }

    fun ensureSnapshot(context: Context) {
        if (prefs(context).getInt(KEY_COUNT, 0) <= 0) {
            snapshotAfterMutation(context)
        }
    }

    private fun countContacts(file: File, passphrase: ByteArray): Int? {
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openOrCreateDatabase(
                file.absolutePath,
                String(passphrase, Charsets.US_ASCII),
                null,
                null,
            )
            database.rawQuery("SELECT COUNT(*) FROM contacts", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "联系人数据库副本无法校验：${file.name}", e)
            null
        } finally {
            runCatching { database?.close() }
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
