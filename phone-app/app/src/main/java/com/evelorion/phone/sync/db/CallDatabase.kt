package com.evelorion.phone.sync.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 通话记录的本地库。
 *
 * ── 为什么不直接用系统通话记录 ──────────────────────────────
 *
 * 系统那份任何有 READ_CALL_LOG 权限的 App 都能读，而且不跟着账号走 ——
 * 换手机就没了。这个 App 的卖点是「你的数据只有你能看」，
 * 把通话记录留在一个所有 App 都能翻的地方，和这个前提是矛盾的。
 *
 * 所以自己存一份，加密，并同步到用户自己的服务器。
 * 默认电话应用会在通话结束后先写入这里，再精确删除系统 CallLog 中的副本。
 * 如果用户没有授予 WRITE_CALL_LOG，系统副本可能仍然存在，但这里始终不依赖它。
 */

@Entity(tableName = "call_records")
data class CallRecordEntity(
    /** 跨设备稳定的 id，由记录它的那台设备生成。 */
    @PrimaryKey val uuid: String,

    val number: String,
    /** 记录当时查到的名字。留一份快照 —— 联系人以后改名或删掉，历史记录也该保持原样。 */
    val name: String,
    /** incoming / outgoing / missed */
    val kind: String,
    val startedAt: Long,
    /** 通话真正结束的时间；旧记录为 0，界面会明确标成“结束时间未记录”。 */
    val endedAt: Long = 0,
    val durationSeconds: Int,

    /** 服务端版本号，推送时当 baseRev 用。0 表示服务端还没有这条。 */
    val rev: Int = 0,
    /** 有待推送的本地改动。 */
    val dirty: Boolean = true,
    /** 本机已删除，等着把墓碑推上去。 */
    val deletedLocally: Boolean = false,
)

@Entity(tableName = "call_sync_state")
data class CallSyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastSeq: Long = 0,
    val lastSyncAt: Long = 0,
    val lastError: String = "",
    /** 防止切换账号后把旧账号的本地通话记录上传到新账号。 */
    val accountId: String = "",
    /** 只存 SHA-256 指纹，不存 collection 子密钥本身。用于识别密钥版本迁移。 */
    val keyFingerprint: String = "",
    /**
     * 已经从系统通话记录导入到哪个时间点。
     * 只导入比它更新的，避免每次同步都把同一批记录重新生成一遍 uuid、
     * 在服务器上堆出无数重复。
     */
    val systemImportedUpTo: Long = 0,
)

@Dao
interface CallDao {
    @Query("SELECT * FROM call_records WHERE deletedLocally = 0 ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int = 200): List<CallRecordEntity>

    @Query("SELECT * FROM call_records WHERE uuid = :uuid")
    fun byUuid(uuid: String): CallRecordEntity?

    @Query("SELECT * FROM call_records WHERE dirty = 1 OR deletedLocally = 1 ORDER BY startedAt LIMIT :limit")
    fun pending(limit: Int): List<CallRecordEntity>

    @Query("SELECT COUNT(*) FROM call_records WHERE dirty = 1 OR deletedLocally = 1")
    fun countPending(): Int

    /** 密钥升级时保留本地明文和服务端 rev，只把所有记录重新排队加密上传。 */
    @Query("UPDATE call_records SET dirty = 1")
    fun markAllForReencrypt()

    @Query("SELECT MAX(startedAt) FROM call_records")
    fun newestStart(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(record: CallRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(records: List<CallRecordEntity>)

    @Query("DELETE FROM call_records WHERE uuid = :uuid")
    fun deleteByUuid(uuid: String)

    @Query("SELECT * FROM call_sync_state WHERE id = 1")
    fun state(): CallSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putState(state: CallSyncStateEntity)
}

@Database(entities = [CallRecordEntity::class, CallSyncStateEntity::class], version = 3, exportSchema = true)
abstract class CallDatabase : RoomDatabase() {

    abstract fun callDao(): CallDao

    companion object {
        private const val NAME = "fc_calls.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE call_records ADD COLUMN endedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE call_sync_state ADD COLUMN accountId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE call_sync_state ADD COLUMN keyFingerprint TEXT NOT NULL DEFAULT ''")
                // 升级后从头核对云端记录。能用当前密钥解开的不会重传；解不开但
                // 本机仍有明文的，会在同步引擎里以更高 rev 自动修复。
                db.execSQL("UPDATE call_sync_state SET lastSeq = 0")
                db.execSQL("UPDATE call_records SET dirty = 1")
            }
        }

        @Volatile
        private var instance: CallDatabase? = null

        /**
         * 注意这个库**目前是明文**的。
         *
         * 通讯录那边的经验：SQLCipher 的口令要么来自 Keystore，要么来自主口令，
         * 而电话 App 两样都没有 —— 它连主口令都不该知道。
         * 用 calls 子密钥来加密本地库是可行的，但那把密钥只在保险库解锁时拿得到，
         * 而来电时（锁屏、刚开机）往往拿不到，那就变成「有时候记不下通话记录」。
         *
         * 所以本地保持明文，**上传前才加密**。防的是服务器和网络，
         * 不是拿到手机 root 权限的人 —— 那个威胁模型在通讯录那边处理。
         * 这一点必须写清楚，不能让人以为这里也是加密的。
         */
        fun get(context: Context): CallDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CallDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
