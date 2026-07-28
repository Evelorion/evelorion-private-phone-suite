package com.evelorion.contacts.data

import android.content.Context
import android.net.Uri
import com.evelorion.contacts.sync.localdb.EncryptedDatabases
import org.fossify.commons.extensions.contactsDB
import org.fossify.commons.extensions.groupsDB
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.contacts.Contact
import org.fossify.commons.models.contacts.Email
import org.fossify.commons.models.contacts.LocalContact
import org.fossify.commons.models.contacts.Organization

/**
 * 私密联系人存储。
 *
 * ── 这是哪一份数据 ──────────────────────────────────────────
 *
 * App 自己的本地 Room 库（可以用 SQLCipher 整库加密），**不是系统通讯录**。
 * 存在这里的联系人：
 *
 *   · 其它 App 读不到（系统通讯录里根本没有这条记录）
 *   · 只有同签名的自家 App 能通过 PrivateContactsProvider 读到
 *   · **只有这一份会被端到端加密同步到服务器**
 *
 * 系统通讯录里的联系人不参与同步 —— 它们本来就是公开的，
 * 加密同步一份公开数据没有意义，而且会和系统自己的账号同步打架。
 *
 * ── 为什么之前"待上传 0 条" ────────────────────────────────
 *
 * 因为这个库是空的。用户的联系人都在系统通讯录里，而同步引擎读的是这里。
 * 所以要么新建时就存进这里，要么把系统里的迁移过来 —— 见 [importFromSystem]。
 */
class PrivateContactStore(private val context: Context) {

    /**
     * 每次访问数据库之前确认加密层还在位。
     *
     * commons 那边随时可能把 Room 实例换掉（destroyInstance），换掉之后
     * 新建的是明文实例，而文件是加密的 —— Room 会认为数据库损坏并**删掉它**。
     * 只在 Application 启动时装一次挡不住这种情况。
     *
     * 装不上就抛异常，**不返回空列表**。空列表和「真的没有联系人」
     * 长得一模一样，上层没法区分，同步引擎更会当成「用户删光了」。
     */
    private fun db() = context.also { EncryptedDatabases.requireReady(it) }.contactsDB

    /** 全部私密联系人，转成 UI 用的模型。 */
    fun loadAll(): List<Contact> = db().getContacts().map { it.toContact() }

    /**
     * 联系人 + 它所属的分组名。
     *
     * 单独一个方法而不是塞进 loadAll：查分组要额外读一张表，
     * 而列表页每次刷新都调 loadAll —— 为了一个大多数页面用不到的字段
     * 让主列表变慢不划算。
     *
     * 分组信息在 [toContact] 里是被丢掉的（LocalContact.groups 存的是 id，
     * Contact.groups 要的是对象），这里补回来给跨应用查询用。
     */
    fun loadAllWithGroups(): List<Pair<Contact, List<String>>> {
        val titles = runCatching {
            context.groupsDB.getGroups().associate { (it.id ?: 0L) to it.title }
        }.getOrDefault(emptyMap())
        return db().getContacts().map { local ->
            local.toContact() to local.groups.mapNotNull { titles[it] }
        }
    }

    fun getById(localId: Int): Contact? =
        db().getContactWithId(localId)?.toContact()

    /** @return 新建或更新后的本地 id */
    fun save(contact: Contact): Int {
        val id = db().insertOrUpdate(contact.toLocal()).toInt()
        notifyChanged(context)
        return id
    }

    fun delete(localId: Int) {
        db().deleteContactId(localId)
        notifyChanged(context)
    }

    fun count(): Int = db().getContacts().size

    /**
     * 导入结果。
     *
     * [imported] 记的是**系统里那一份的原始对象**，不是导入后的副本 ——
     * 因为删除要用系统的 id。只记条数的话，后面想删就只能靠姓名号码
     * 重新猜一遍，那会误删。
     */
    data class ImportResult(
        /** 成功导入的（对应系统里的原件） */
        val imported: List<Contact>,
        /** 因为号码已存在而跳过的条数 */
        val skipped: Int,
    )

    /**
     * 把系统通讯录里的联系人搬进加密库。
     *
     * **这一步不删系统里的原件。** 删除不可逆，一次可能上千条 ——
     * 让用户先看到结果、确认没问题，再单独决定删不删。
     * 见 [ImportResult]。
     */
    fun importFromSystem(systemContacts: List<Contact>): ImportResult {
        val known = loadAll()
            .flatMap { c -> c.phoneNumbers.map { DuplicateFinder.normalizeNumber(it.value) } }
            .filter { it.length >= 6 }
            .toMutableSet()

        val imported = mutableListOf<Contact>()
        var skipped = 0

        systemContacts.forEach { c ->
            val numbers = c.phoneNumbers.map { DuplicateFinder.normalizeNumber(it.value) }
                .filter { it.length >= 6 }
            if (numbers.any { it in known }) {
                skipped++
                return@forEach
            }

            // id 必须清零，否则会覆盖加密库里编号相同的另一条记录
            save(c.copy(id = 0, contactId = 0, source = SOURCE))
            known.addAll(numbers)
            imported.add(c)
        }
        return ImportResult(imported, skipped)
    }

    // ------------------------------------------------------------ 模型转换

    /**
     * LocalContact ↔ Contact。
     *
     * commons 用两个模型：Contact 是「统一视图」（可能来自系统也可能来自本地），
     * LocalContact 是本地 Room 表的实体。字段几乎一一对应，只有组织信息在
     * LocalContact 里是拆开的两列。
     */
    private fun LocalContact.toContact() = Contact(
        id = id ?: 0,
        prefix = prefix,
        firstName = firstName,
        middleName = middleName,
        surname = surname,
        suffix = suffix,
        nickname = nickname,
        photoUri = photoUri,
        phoneNumbers = ArrayList(phoneNumbers),
        emails = ArrayList(emails),
        addresses = ArrayList(addresses),
        events = ArrayList(events),
        source = SOURCE,
        starred = starred,
        contactId = id ?: 0,
        thumbnailUri = "",
        photo = null,
        notes = notes,
        groups = ArrayList(),
        organization = Organization(company, jobPosition),
        websites = ArrayList(websites),
        IMs = ArrayList(IMs),
        mimetype = "",
        ringtone = ringtone,
    )

    private fun Contact.toLocal() = LocalContact(
        id = if (id == 0) null else id,
        prefix = prefix,
        firstName = firstName,
        middleName = middleName,
        surname = surname,
        suffix = suffix,
        nickname = nickname,
        photo = photo?.let { bmp ->
            java.io.ByteArrayOutputStream().use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        },
        photoUri = photoUri,
        phoneNumbers = ArrayList(phoneNumbers),
        emails = ArrayList(emails),
        events = ArrayList(events),
        starred = starred,
        addresses = ArrayList(addresses),
        notes = notes,
        groups = ArrayList(),
        company = organization.company,
        jobPosition = organization.jobPosition,
        websites = ArrayList(websites),
        IMs = ArrayList(IMs),
        ringtone = ringtone,
    )

    companion object {
        private const val PROVIDER_AUTHORITY = "com.evelorion.contacts.privateprovider"
        val CONTACTS_URI: Uri = Uri.parse("content://$PROVIDER_AUTHORITY/contacts")

        fun notifyChanged(context: Context) {
            context.contentResolver.notifyChange(CONTACTS_URI, null)
        }

        /** Contact.source 里用这个值标记「这条来自私密库」。 */
        const val SOURCE = "private"
    }
}
