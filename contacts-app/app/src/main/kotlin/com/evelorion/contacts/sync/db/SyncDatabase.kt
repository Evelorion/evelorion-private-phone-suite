package com.evelorion.contacts.sync.db

import android.content.Context
import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.evelorion.contacts.sync.localdb.EncryptedDatabases

/**
 * 同步层自己的数据库，和 commons 的 contacts.db 分开。
 *
 * 为什么必须分开：commons 的 LocalContact 是外部依赖里的 Room 实体，
 * 我们既不能给它加列，也不该在它的 schema 上做迁移 —— 那样每次 commons
 * 升版本都可能撞车。同步需要的所有额外状态都放这里，通过 localId 关联。
 *
 * 这个库里存的是明文（base_payload 是完整的联系人快照）。
 * 它和 commons 的联系人库是同一个保密等级，靠 App 私有目录 + 关掉 allowBackup 保护，
 * 不额外加密。真要做本地静态加密，得整个换成 SQLCipher，见设计文档「已知缺口」。
 */

@Entity(tableName = "sync_records")
data class SyncRecordEntity(
    /** 跨设备稳定的联系人 id，由创建它的那台设备生成。 */
    @PrimaryKey val uuid: String,

    /** commons contacts 表的自增主键。0 表示本机当前没有这条（远端新建还没落地，或已删除）。 */
    val localId: Int,

    /** 服务端版本号。推送时当 baseRev 用；0 表示服务端还没有这条。 */
    val rev: Int,

    /** 上次同步成功时的完整快照，三方合并的共同祖先。 */
    val basePayload: String,

    /** basePayload 的哈希，用来快速判断本机有没有改过，不用每次都做字符串比较。 */
    val baseHash: String,

    /** 本机已删除，等着把墓碑推上去。 */
    val deletedLocally: Boolean = false,

    /** 有待推送的本地改动。 */
    val dirty: Boolean = false,

    /** 上次合并时两边都改过的字段，用逗号分隔，供 UI 提示用户确认。 */
    val conflictFields: String = "",

    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    /** 已经处理到的账号级序列号，下次拉取从这里往后要。 */
    val lastSeq: Long = 0,
    val lastSyncAt: Long = 0,
    val lastError: String = "",
    /** 服务端当前的 seq，用来在 UI 上显示「还差多少条没同步」。 */
    val serverSeq: Long = 0,

    /**
     * 同步清单自己的版本号。0 表示还没写过清单。
     * 服务端返回的清单 rev 比这个小，说明整份清单被回滚了。
     */
    val manifestRev: Int = 0,

    /**
     * 上一轮清单校验发现的问题，人话形式，用换行分隔。
     * 非空意味着服务器给的数据不完整 —— UI 上必须显眼地报出来，
     * 这不是「同步慢了点」，是「你的服务器可能不老实」。
     */
    val manifestIssues: String = "",
)

/**
 * 号码 → 联系人的盲索引。只存在本机，不上传。
 * 电话 App 来电时拿号码算出同样的索引值，就能查到是谁，
 * 而不需要在数据库里放一列可搜索的明文号码。
 */
@Entity(tableName = "blind_index", primaryKeys = ["idx", "localId"])
data class BlindIndexEntity(
    val idx: String,
    val localId: Int,
    val uuid: String,
)

/** 已经上传过的头像，避免每次同步都重传几百 KB。 */
@Entity(tableName = "blob_state")
data class BlobStateEntity(
    @PrimaryKey val hash: String,
    val uploaded: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface SyncDao {

    @Query("SELECT * FROM sync_records WHERE uuid = :uuid")
    fun getByUuid(uuid: String): SyncRecordEntity?

    @Query("SELECT * FROM sync_records WHERE localId = :localId LIMIT 1")
    fun getByLocalId(localId: Int): SyncRecordEntity?

    @Query("SELECT * FROM sync_records")
    fun getAll(): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_records WHERE dirty = 1 OR deletedLocally = 1 ORDER BY updatedAt LIMIT :limit")
    fun getPending(limit: Int): List<SyncRecordEntity>

    @Query("SELECT COUNT(*) FROM sync_records WHERE dirty = 1 OR deletedLocally = 1")
    fun countPending(): Int

    @Query("SELECT * FROM sync_records WHERE conflictFields != ''")
    fun getConflicted(): List<SyncRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(record: SyncRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(records: List<SyncRecordEntity>)

    @Query("DELETE FROM sync_records WHERE uuid = :uuid")
    fun deleteByUuid(uuid: String)

    @Query("UPDATE sync_records SET dirty = 1, updatedAt = :now WHERE localId = :localId")
    fun markDirtyByLocalId(localId: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE sync_records SET deletedLocally = 1, dirty = 1, localId = 0, updatedAt = :now WHERE localId = :localId")
    fun markDeletedByLocalId(localId: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE sync_records SET conflictFields = '' WHERE uuid = :uuid")
    fun clearConflict(uuid: String)

    // -------- 同步状态 --------

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun getState(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putState(state: SyncStateEntity)

    // -------- 盲索引 --------

    @Query("SELECT * FROM blind_index WHERE idx = :idx")
    fun lookupIndex(idx: String): List<BlindIndexEntity>

    @Query("DELETE FROM blind_index WHERE localId = :localId")
    fun clearIndexFor(localId: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putIndex(entries: List<BlindIndexEntity>)

    @Query("DELETE FROM blind_index")
    fun clearAllIndex()

    // -------- 头像 --------

    @Query("SELECT * FROM blob_state WHERE hash = :hash")
    fun getBlob(hash: String): BlobStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putBlob(blob: BlobStateEntity)

    @Query("SELECT hash FROM blob_state WHERE uploaded = 0")
    fun getUnuploadedBlobs(): List<String>

    // -------- 关闭同步时的清理 --------

    @Query("DELETE FROM sync_records")
    fun wipeRecords()

    @Query("DELETE FROM blob_state")
    fun wipeBlobs()

    @Query("DELETE FROM sync_state")
    fun wipeState()
}

@Database(
    entities = [
        SyncRecordEntity::class,
        SyncStateEntity::class,
        BlindIndexEntity::class,
        BlobStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SyncDatabase : RoomDatabase() {

    abstract fun syncDao(): SyncDao

    companion object {
        private const val TAG = "SyncDatabase"

        @Volatile
        private var instance: SyncDatabase? = null

        private const val NAME = "fc_sync.db"

        fun get(context: Context): SyncDatabase = instance ?: synchronized(this) {
            instance ?: open(context).also { instance = it }
        }

        /**
         * 打开同步库，并处理「文件的加密状态和当前口令对不上」的情况。
         *
         * ── 为什么需要这一段 ──────────────────────────────────
         *
         * 这个库是靠 openHelperFactory 在建库时直接以加密方式创建的，
         * 没有像联系人库那样的「就地加密」迁移。于是只要它曾经在
         * 加密层没装上的时候被创建过一次（首次安装、Keystore 抽风、
         * 早期版本……），磁盘上就是一个明文文件，而之后每次都拿加密
         * 工厂去开 —— SQLCipher 报「file is not a database」。
         *
         * 这个异常过去是在后台线程里直接抛出去的，表现是「点开同步页闪退」。
         * 现在先试着把文件对齐，再重开；实在开不了才报错，而且**不删文件** ——
         * 这里面存着 base_payload，是本机联系人在上次同步时的完整快照，
         * 数据库出问题时全靠它把联系人恢复出来。删掉等于把救生圈扔了。
         */
        private fun open(context: Context): SyncDatabase {
            EncryptedDatabases.requireReady(context)
            val factory = requireNotNull(EncryptedDatabases.openHelperFactory(context)) {
                "本机数据库加密层没有就绪，拒绝用明文方式打开同步库"
            }
            val first = build(context, factory)
            if (probe(first)) return first
            runCatching { first.close() }

            // 多半是明文文件配加密工厂。对齐之后再来一次。
            if (EncryptedDatabases.reconcileSyncDatabase(context, NAME)) {
                val second = build(context, factory)
                if (probe(second)) {
                    Log.i(TAG, "同步库的加密状态已对齐")
                    return second
                }
                runCatching { second.close() }
            }

            // 还是不行。可能是本机没有口令（加密层没装上）而文件是加密的，
            // 也可能是口令换过。两种情况都**不能删库**，只能如实报错，
            // 让上层显示出来 —— 静默重建会丢掉所有 base_payload 快照。
            throw IllegalStateException(
                "同步库打不开：${EncryptedDatabases.lastError.ifEmpty { "文件的加密状态和当前口令不一致" }}"
            )
        }

        private fun build(context: Context, factory: SupportSQLiteOpenHelper.Factory): SyncDatabase =
            Room.databaseBuilder(context.applicationContext, SyncDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .build()

        /** Room 是懒打开的，必须真的碰一下才知道口令对不对。 */
        private fun probe(db: SyncDatabase): Boolean = try {
            db.openHelper.readableDatabase.query("SELECT count(*) FROM sqlite_master").use { it.moveToFirst() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "同步库打开失败", e)
            false
        }

        /**
         * 关掉连接但保留文件。
         * 切换本地加密模式时必须先调它 —— rekey 是原地操作，
         * 有连接开着的话会失败或者产生半新半旧的库。
         * 下次 get() 会用新口令重新打开。
         */
        fun closeInstance() {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
            }
        }

        /** 用户关掉同步时，把本地所有同步痕迹清干净。 */
        fun destroy(context: Context) {
            synchronized(this) {
                instance?.close()
                instance = null
                context.applicationContext.deleteDatabase("fc_sync.db")
            }
        }
    }
}
