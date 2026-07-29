package com.evelorion.contacts.sync.localdb

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.fossify.commons.databases.ContactsDatabase
import java.lang.reflect.Field

/**
 * 把 SQLCipher 装进本机的两个数据库。
 *
 * ── 为什么要用反射 ────────────────────────────────────────────────
 *
 * 私密联系人存在 commons 的 `local_contacts.db` 里，而 `ContactsDatabase`
 * 是 `org.fossify:commons` 这个 Maven 依赖里的类。它的 `getInstance()`
 * 内部直接 `Room.databaseBuilder(...).build()`，没有留任何注入 openHelperFactory
 * 的口子，我们也没法在外面改它。
 *
 * 但它把实例存在 companion object 的一个静态字段里，而且是「为 null 才创建」。
 * 所以只要在**任何人第一次调用 getInstance() 之前**，把我们自己用 SQLCipher
 * 工厂建好的实例塞进那个字段，之后全 App 拿到的就都是加密版本 ——
 * commons 里的 LocalContactsHelper、ContactsHelper 全都会走加密库，一行都不用改。
 *
 * 这条路的好处是不用动构建结构。缺点也很明确，见 [install] 的注释。
 * 更稳的做法是把 commons 拉成 composite build 后打个小补丁，
 * 见 docs/LOCAL_DB_ENCRYPTION.md 的「方案 B」。
 *
 * ── 时机 ──────────────────────────────────────────────────────
 *
 * 必须在 `Application.attachBaseContext()` 里调用 [install]。
 * 不能放 `onCreate()`：ContentProvider 的 onCreate 比 Application.onCreate 早，
 * 而我们的 MyContactsContentProvider 会读数据库。
 */
object EncryptedDatabases {

    private const val TAG = "EncryptedDatabases"
    private const val CONTACTS_DB = "local_contacts.db"

    @Volatile
    private var installed = false

    @Volatile
    var lastError: String = ""
        private set

    /** 本次进程里 commons 的库到底有没有跑在加密模式上。设置页应当显示这个。 */
    @Volatile
    var contactsDbEncrypted = false
        private set

    /**
     * 本次装载实际生效的那把口令，只在内存里。
     *
     * 必须留一份：PASSPHRASE 模式下 Keystore 里没有口令，
     * 同步库要建加密工厂时没别的地方拿。
     * 进程结束就没了，这正是 PASSPHRASE 模式能挡住 root 的原因之一。
     */
    @Volatile
    private var activePassphrase: ByteArray? = null

    /**
     * 我们自己塞进 commons 的那个实例。
     *
     * 留这个引用是为了做**同一性**判断，而不只是判非空。
     * commons 里有代码会调 destroyInstance() 把静态字段清成 null，
     * 之后任何一次 getInstance() 都会新建一个**明文** Room 实例 ——
     * 而磁盘上的文件是 SQLCipher 加密的。
     *
     * 明文实例打开加密文件时，SQLite 报的是「file is not a database」，
     * Room 默认的 DatabaseErrorHandler 会把这当成数据库损坏，
     * **直接删掉文件**再建一个空库。整个过程不抛异常、不留提示，
     * 结果就是通讯录一瞬间变成空的 —— 这正是那次数据事故的起点。
     *
     * 只判非空的话，字段里躺着那个明文实例时我们会以为一切正常。
     */
    @Volatile
    private var ourInstance: ContactsDatabase? = null

    /** 这把口令是不是已经被证明能打开这个库。见 [requireReady]。 */
    @Volatile
    private var verified = false

    /**
     * 幂等，可以重复调用。
     *
     * MainActivity 里有一处 `ContactsDatabase.destroyInstance()`，它会把静态字段
     * 清成 null，下次 getInstance() 就会重新建一个**明文**实例。
     * 所以那一行后面必须再调一次这个方法，见 INTEGRATION.md。
     *
     * 失败时不抛异常，而是退回明文并把原因记进 [lastError]。
     * 理由：加密失败就让 App 打不开通讯录，比不加密更糟。
     * 但这个状态必须让用户看到，不能静默降级。
     */
    @Synchronized
    fun install(context: Context) {
        if (installed && holdsOurInstance()) return

        try {
            System.loadLibrary("sqlcipher")
        } catch (e: Throwable) {
            lastError = "SQLCipher 原生库加载失败：${e.message}"
            Log.e(TAG, lastError, e)
            return
        }

        val passphrase = try {
            DatabaseKey.getOrCreate(context)
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            // 开了屏幕锁保护但还没验证。这不是错误，调用方应当拉起验证后重试。
            lastError = "需要先通过屏幕锁验证才能打开通讯录"
            Log.i(TAG, lastError)
            return
        } catch (e: Exception) {
            lastError = "无法取得数据库口令：${e.message}"
            Log.e(TAG, lastError, e)
            return
        }

        try {
            // 第一次启用时，把已有的明文库原地转成加密库
            if (!DatabaseKey.isEnabled(context)) {
                DatabaseEncryptionMigrator.encryptInPlace(context, CONTACTS_DB, passphrase)
                DatabaseKey.markEnabled(context)
            }

            val factory = SupportOpenHelperFactory(passphrase)
            val db = buildContactsDatabase(context, factory)
            injectIntoCommons(db)

            // 这里**故意不去真的打开数据库**。
            //
            // 曾经在这里加过一次「装完立刻查一下，确认口令是对的」，
            // 结果是把一次磁盘 I/O 和 Room 的建库/迁移全都搬到了
            // Application.attachBaseContext 上 —— App 启动路径最早、
            // 最脆弱的一段，出问题的表现就是点图标直接闪退，
            // 而且连主界面都进不去，用户什么信息也拿不到。
            //
            // 验证本身是对的，只是不该在这个时机做：改到 [requireReady]
            // 里，第一次真正访问数据库时验一次，那时已经在后台线程上，
            // 失败也有地方显示。
            ourInstance = db
            verified = false
            activePassphrase = passphrase
            installed = true
            contactsDbEncrypted = true
            lastError = ""
            Log.i(TAG, "本机联系人数据库已切换到加密模式")
        } catch (e: Exception) {
            // 带上文件名。「file is not a database」这种消息本身完全看不出
            // 是哪个库出的问题，而这个 App 有两个库（联系人库和同步库），
            // 排查方向完全不同。
            lastError = "数据库加密启用失败（$CONTACTS_DB）：${e.message}"
            contactsDbEncrypted = false
            installed = false
            ourInstance = null
            verified = false
            activePassphrase?.fill(0)
            activePassphrase = null
            runCatching { ContactsDatabase.destroyInstance() }
            Log.e(TAG, lastError, e)
        }
    }

    /**
     * 强制用当前的口令重新装一次。
     * 切换加密模式之后必须调 —— installed 标记还是 true，
     * 但持有的实例是用旧口令打开的，直接用会解不开新加密的文件。
     */
    @Synchronized
    fun reinstall(context: Context) {
        installed = false
        contactsDbEncrypted = false
        activePassphrase = null
        ourInstance = null
        verified = false
        install(context)
    }

    /**
     * 把内存里的口令抹掉并断开加密层。
     * PASSPHRASE 模式下用户主动锁定时调用。
     */
    @Synchronized
    fun lock() {
        activePassphrase?.fill(0)
        activePassphrase = null
        installed = false
        contactsDbEncrypted = false
        ourInstance = null
        verified = false
    }

    /**
     * PASSPHRASE 模式专用：用主口令派生出的口令装上加密层。
     * 和 install 的区别是口令不来自 Keystore，而是调用方现算的。
     */
    @Synchronized
    fun installWithKey(context: Context, passphrase: ByteArray) {
        if (!runCatching { System.loadLibrary("sqlcipher") }.isSuccess) {
            lastError = "SQLCipher 原生库加载失败"
            return
        }
        try {
            val db = buildContactsDatabase(context, SupportOpenHelperFactory(passphrase))
            injectIntoCommons(db)
            ourInstance = db
            verified = false
            activePassphrase = passphrase
            installed = true
            contactsDbEncrypted = true
            lastError = ""
        } catch (e: Exception) {
            lastError = "用主口令打开数据库失败：${e.message}"
            contactsDbEncrypted = false
            Log.e(TAG, lastError, e)
        }
    }

    /**
     * 给我们自己的同步库用。这个库的 builder 在我们手里，不需要反射。
     *
     * 用的是本次装载时实际生效的那把口令，而不是重新去 Keystore 取 ——
     * PASSPHRASE 模式下 Keystore 里根本没有口令，取不到。
     */
    /**
     * 让同步库（fc_sync.db）的磁盘状态和当前口令对齐。
     *
     * ── 这是一个真实事故的修补 ────────────────────────────────
     *
     * [install] 只把 `local_contacts.db` 就地加密过，**从来没管过 fc_sync.db**。
     * 后者是靠 [openHelperFactory] 在建库时直接以加密方式创建的 ——
     * 只要它曾经在「加密层还没装上」的时候被创建过一次（比如首次安装、
     * 或者某次 Keystore 抽风导致 install 失败），磁盘上就留下一个明文文件。
     * 之后每次拿加密工厂去开它，SQLCipher 都会报「file is not a database」。
     *
     * 而这个异常是在后台线程里抛的，表现就是「点开同步页面直接闪退」，
     * 崩溃点和真正的原因中间隔着十几层 Room 和协程的栈。
     *
     * encryptInPlace 自己会判断文件是不是已经加密：已经加密就跳过，
     * 是明文就原地转换（有临时文件兜底，失败不动原文件）。
     * 所以无条件调用是安全的。
     *
     * @return 是否真的做了对齐（false 表示现在根本没有口令可用）
     */
    fun reconcileSyncDatabase(context: Context, dbName: String): Boolean {
        val passphrase = activePassphrase ?: return false
        return runCatching {
            DatabaseEncryptionMigrator.encryptInPlace(context, dbName, passphrase)
            true
        }.onFailure { Log.e(TAG, "对齐 $dbName 的加密状态失败", it) }.getOrDefault(false)
    }

    fun openHelperFactory(context: Context): SupportSQLiteOpenHelper.Factory? {
        if (!installed || !contactsDbEncrypted) return null
        val passphrase = activePassphrase ?: return null
        return try {
            SupportOpenHelperFactory(passphrase)
        } catch (e: Exception) {
            Log.e(TAG, "同步库的加密工厂创建失败，退回明文", e)
            null
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 复刻 commons 里 getInstance() 的 builder 配置。
     *
     * 这里必须和 commons 保持一致，否则 Room 会因为 schema 版本或迁移路径对不上而崩。
     * 升级 commons 版本时**要回来核对这段**——这是反射方案最主要的维护成本。
     * 当前对齐的是 commons 6.1.6（ContactsDatabase version = 3）。
     */
    private fun buildContactsDatabase(
        context: Context,
        factory: SupportSQLiteOpenHelper.Factory,
    ): ContactsDatabase = Room.databaseBuilder(
        context.applicationContext,
        ContactsDatabase::class.java,
        CONTACTS_DB,
    )
        .openHelperFactory(factory)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN photo_uri TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN ringtone TEXT DEFAULT ''")
        }
    }

    /**
     * 把实例塞进 commons 的静态字段。
     *
     * Kotlin 把 companion object 里的私有属性编译成外部类上的静态字段，
     * 但这属于实现细节，不同 Kotlin 版本可能变。所以两个类都找一遍，
     * 按「类型能装下 ContactsDatabase」来认字段，而不是按名字硬编码 ——
     * 名字会被 R8 改掉，类型不会。
     */
    private fun injectIntoCommons(database: ContactsDatabase) {
        val field = findInstanceField()
            ?: throw IllegalStateException(
                "在 ContactsDatabase 里找不到存实例的静态字段，" +
                    "可能是 commons 版本变了。请改用 composite build 方案（见文档）"
            )
        field.isAccessible = true
        field.set(companionInstanceOrNull(), database)
    }

    private fun holdsOurInstance(): Boolean = try {
        val mine = ourInstance
        val field = findInstanceField()
        if (mine == null || field == null) {
            false
        } else {
            field.isAccessible = true
            // 必须是同一个对象。字段里换成了别人建的明文实例时，
            // 判非空会返回 true，然后我们就一路把加密库当明文库用下去了。
            field.get(companionInstanceOrNull()) === mine
        }
    } catch (e: Exception) {
        false
    }

    /**
     * 每次碰数据库之前调一次。
     *
     * commons 随时可能把静态字段清掉或换成明文实例，
     * 光在 Application 启动时装一次是不够的。这个方法很便宜 ——
     * 正常情况下就是一次字段读取加一次引用比较。
     *
     * @throws IllegalStateException 加密层装不上。**必须让它抛出去**：
     *   吞掉异常会让调用方读到一个空列表，而空列表会被同步引擎
     *   理解成「用户把联系人全删了」。
     */
    @Synchronized
    fun requireReady(context: Context) {
        if (installed && holdsOurInstance() && verified) return

        if (!installed || !holdsOurInstance()) install(context)
        if (!installed || !holdsOurInstance()) {
            throw IllegalStateException(lastError.ifEmpty { "本机联系人数据库的加密层没能装上" })
        }

        if (!verified) {
            // 真的开一次库。Room 是懒打开的，不碰它的话「口令不对」
            // 要等到第一次业务查询才暴露，而那时异常已经跑到别处去了。
            //
            // 只验一次：verified 之后这个方法就退化成三次字段读取。
            if (!tryOpen()) {
                val file = context.getDatabasePath(CONTACTS_DB)
                if (file.exists() &&
                    runCatching { DatabaseEncryptionMigrator.isEncrypted(file) }.getOrDefault(true)
                ) {
                    closeInjectedInstance()
                    contactsDbEncrypted = false
                    throw IllegalStateException(
                        "本机加密联系人库无法用当前口令打开；已停止操作，数据文件没有被清空"
                    )
                }

                // 开不了最常见的原因是**记录的状态和磁盘上的事实对不上**：
                // prefs 说「已加密」，但文件其实是明文（上一次崩溃后被 Room 的
                // 默认损坏处理器删掉重建过），或者反过来。
                //
                // 这种情况必须自己纠正。放任不管的话，我们装不上加密层，
                // commons 就会自己建一个**明文**实例去开这个文件 ——
                // 而 Room 遇到打不开的文件会认定「损坏」，**直接删掉重建**。
                // 那是一次静默的数据清零，正是这次事故的起点。
                closeInjectedInstance()
                reconcileOnDisk(context)
                if (!tryOpen()) {
                    // 文件确实是加密的，但这把口令打不开它 —— 说明当初加密用的
                    // 那把钥匙已经不在了（换过锁屏方式、恢复过出厂设置、
                    // 或者 Keystore 条目被清掉过）。
                    //
                    // 这种情况没有任何技术手段能救：密钥就是没了。
                    // 但至少要说人话，并且告诉用户出路在哪 ——
                    // 甩一句「file is not a database」等于什么都没说。
                    if (file.exists() && runCatching { DatabaseEncryptionMigrator.isEncrypted(file) }.getOrDefault(false)) {
                        lastError = "本机联系人库是加密的，但解开它的钥匙已经不在这台手机上了" +
                            "（通常是换了锁屏方式或恢复过出厂设置）。本机这份数据打不开了，" +
                            "云端的那份不受影响 —— 可以在同步设置里重新登录，把联系人从云端取回来。"
                    }
                    installed = false
                    ourInstance = null
                    contactsDbEncrypted = false
                    verified = false
                    throw IllegalStateException(lastError.ifEmpty { "本机联系人数据库打不开" })
                }
            }
            verified = true
        }
    }

    private fun closeInjectedInstance() {
        runCatching { ourInstance?.close() }
        runCatching { ContactsDatabase.destroyInstance() }
        ourInstance = null
        installed = false
        verified = false
    }

    /** 试着真的读一下。失败只记录原因，不抛 —— 调用方要据此决定要不要纠正。 */
    private fun tryOpen(): Boolean {
        val db = ourInstance ?: return false
        return try {
            db.openHelper.readableDatabase
                .query("SELECT count(*) FROM sqlite_master")
                .use { it.moveToFirst() }
            true
        } catch (e: Exception) {
            lastError = "本机联系人数据库打不开（$CONTACTS_DB）：${e.message}"
            Log.w(TAG, lastError, e)
            false
        }
    }

    /**
     * 让磁盘上的实际状态和我们记录的状态重新对齐，然后用新状态重装一次。
     *
     * encryptInPlace 自己会判断文件是不是已经加密：已经加密就直接跳过，
     * 是明文就原地转成加密（转换有临时文件兜底，失败不动原文件）。
     * 所以无条件调用它是安全的，而且正好能修好「prefs 说加密、文件是明文」这种错位。
     */
    private fun reconcileOnDisk(context: Context) {
        runCatching {
            val passphrase = DatabaseKey.getOrCreate(context)
            DatabaseEncryptionMigrator.encryptInPlace(context, CONTACTS_DB, passphrase)
            DatabaseKey.markEnabled(context)

            val db = buildContactsDatabase(context, SupportOpenHelperFactory(passphrase))
            injectIntoCommons(db)
            ourInstance = db
            activePassphrase = passphrase
            installed = true
            contactsDbEncrypted = true
            Log.i(TAG, "已修正本机数据库的加密状态错位")
        }.onFailure {
            lastError = "修正数据库加密状态失败：${it.message}"
            Log.e(TAG, lastError, it)
        }
    }

    private fun findInstanceField(): Field? {
        val candidates = buildList {
            addAll(ContactsDatabase::class.java.declaredFields.toList())
            ContactsDatabase::class.java.declaredClasses
                .firstOrNull { it.simpleName == "Companion" }
                ?.let { addAll(it.declaredFields.toList()) }
        }
        return candidates.firstOrNull { ContactsDatabase::class.java.isAssignableFrom(it.type) }
    }

    private fun companionInstanceOrNull(): Any? = try {
        val companionClass = ContactsDatabase::class.java.declaredClasses
            .firstOrNull { it.simpleName == "Companion" }
        val field = findInstanceField()
        // 静态字段 set 的第一个参数会被忽略；实例字段才需要 Companion 对象
        if (field != null && java.lang.reflect.Modifier.isStatic(field.modifiers)) {
            null
        } else {
            ContactsDatabase::class.java.getDeclaredField("Companion")
                .apply { isAccessible = true }
                .get(null)
                ?: companionClass?.getDeclaredConstructor()?.apply { isAccessible = true }?.newInstance()
        }
    } catch (e: Exception) {
        null
    }
}
