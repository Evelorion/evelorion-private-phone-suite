package com.evelorion.contacts.data

import android.content.Context
import android.util.Log
import com.evelorion.contacts.sync.db.SyncDatabase
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.models.contacts.Contact
import org.fossify.commons.models.contacts.Group

/**
 * 数据层。
 *
 * ── 为什么包一层 ────────────────────────────────────────────
 *
 * UI 一律通过这里拿数据，**不直接碰 commons**。理由有两个：
 *
 * 1. commons 的 Contact 模型字段很多、命名也不是给 UI 用的
 *    （getNameToDisplay / organization.company / phoneNumbers[].normalizedNumber…），
 *    让每个 Activity 各自去理解它，改数据源时要改十几处。
 * 2. 以后如果要把数据源换成自己的加密库（私密联系人就是这么存的），
 *    只用改这个文件。
 *
 * commons 在这个工程里的定位就是**一个读写系统通讯录的库**，
 * 它的 Activity、View、主题一概不用 —— 那正是上一版 UI 做不出来的原因。
 */
class ContactRepository(private val context: Context) {

    private val helper by lazy { ContactsHelper(context) }

    /**
     * 拉全部联系人。**这是个耗时操作**，必须在后台线程调用。
     *
     * commons 的 getContacts 是回调式的（它内部自己开线程），
     * 所以这里也保持回调式，不假装成同步的。
     */
    private val privateStore by lazy { PrivateContactStore(context) }

    /**
     * 拉全部联系人。
     *
     * **只读这个 App 自己的加密库。** 系统通讯录（别的 App 存的、
     * SIM 卡导入的）完全不碰 —— 这个 App 管的就是它自己那份加密数据，
     * 混进系统通讯录只会让「这条到底加不加密、会不会同步」变得含糊。
     *
     * 想把系统里的搬进来，用 设置 → 从系统通讯录导入（一次性操作）。
     */
    fun loadAll(onResult: (List<UiContact>) -> Unit) = loadAll { list, _ -> onResult(list) }

    /**
     * 拉全部联系人，并把「读失败」和「真的一条都没有」分开。
     *
     * 以前这里是 `runCatching { ... }.getOrElse { emptyList() }` ——
     * 数据库打不开时界面显示成「还没有联系人」，和真的空库一模一样。
     * 用户看到的是「我的联系人全没了」，而 App 一声不吭。
     * 这种静默降级在别的地方顶多是显示不全，在通讯录上会让人以为数据没了，
     * 甚至手动去做更糟的补救（重装、重新登录）。
     *
     * @param onResult 第二个参数非空表示读取失败，界面应当显示这条错误而不是空状态
     */
    fun loadAll(onResult: (List<UiContact>, String?) -> Unit) {
        val result = runCatching { privateStore.loadAll() }
        val error = result.exceptionOrNull()
        if (error != null) {
            Log.e(TAG, "读取本机联系人库失败", error)
            onResult(emptyList(), "打不开本机联系人库：${error.message ?: error::class.java.simpleName}")
            return
        }
        onResult(result.getOrDefault(emptyList()).map { it.toUi() }.sortedWith(NAME_ORDER), null)
    }

    /**
     * 查重/合并专用：直接给 commons 的 Contact，不转 UI 模型。
     * 合并要写回原字段，转一圈 UiContact 会丢掉地址、备注这些没在列表里显示的内容。
     */
    fun loadRawForMerge(): List<Contact> = runCatching { privateStore.loadAll() }
        .onFailure { Log.w(TAG, "查重读取失败", it) }.getOrDefault(emptyList())

    /** 联系人总数。同步状态页用它显示「有多少条会被同步」。 */
    fun privateCount(): Int = runCatching { privateStore.count() }.getOrDefault(0)

    /** 系统通讯录里有多少条。导入页用它显示「可导入 N 位」。 */
    fun loadSystemContacts(onResult: (List<Contact>) -> Unit) = helper.getContacts { onResult(it) }

    /** 把系统通讯录搬进加密库。不删原件。 */
    fun importFromSystem(onDone: (PrivateContactStore.ImportResult) -> Unit) {
        helper.getContacts { system ->
            onDone(
                runCatching { privateStore.importFromSystem(system) }
                    .getOrDefault(PrivateContactStore.ImportResult(emptyList(), 0))
            )
        }
    }

    /**
     * 从**系统通讯录**删除指定的联系人。
     *
     * 只删传进来的这些 —— 不是「清空系统通讯录」。传进来的应该是
     * 上一步 importFromSystem 明确报告导入成功的那些，
     * 这样不会碰到用户没导入的联系人。
     *
     * @return 实际删掉的条数
     */
    fun deleteFromSystem(contacts: List<Contact>, onDone: (Int) -> Unit) {
        if (contacts.isEmpty()) {
            onDone(0)
            return
        }
        // deleteContacts 一次删一批，比逐条 deleteContact 快很多，
        // 而且是一个事务 —— 中途失败不会留下删了一半的状态
        val ok = runCatching { helper.deleteContacts(ArrayList(contacts)) }.getOrDefault(false)
        onDone(if (ok) contacts.size else 0)
    }

    /**
     * getStoredGroupsSync 顾名思义是同步的，**必须在后台线程调用**。
     *
     * 这里必须吞掉异常：调用它的都是 `Executor.execute {}`，
     * 而 execute 里抛出的异常没人接，会直接走到默认处理器 —— 整个进程崩掉。
     * 分组读不出来只是收藏页少一块内容，不该让 App 打不开。
     */
    fun loadGroupsSync(): List<UiGroup> = runCatching {
        helper.getStoredGroupsSync().map { UiGroup(it.id ?: 0L, it.title, it.contactsCount) }
    }.onFailure { Log.w(TAG, "读取分组失败", it) }.getOrDefault(emptyList())

    fun contactById(id: Int): Contact? = runCatching { privateStore.getById(id) }.getOrNull()

    /**
     * 删除一位联系人。
     *
     * 这里必须**同时**给同步层打上删除标记。同步引擎不再靠「扫描时找不到」
     * 来推断删除（那样一次数据库故障就会把服务器上的数据全删光），
     * 所以这个入口是删除意图唯一的权威来源 —— 漏掉这一行，
     * 联系人在本机没了但服务器上还在，下次同步又会被拉回来。
     */
    fun deleteContact(contact: Contact, onDone: (Boolean) -> Unit) {
        val ok = runCatching { privateStore.delete(contact.id) }.isSuccess
        if (ok) markDeletedForSync(contact.id)
        onDone(ok)
    }

    /** 告诉同步层「这条是用户主动删的」，下次同步把墓碑推上去。 */
    fun markDeletedForSync(localId: Int) {
        if (localId == 0) return
        runCatching { SyncDatabase.get(context).syncDao().markDeletedByLocalId(localId) }
            .onFailure { Log.w(TAG, "标记删除失败，localId=$localId", it) }
    }

    /** 保存。一律写进加密库 —— 这个 App 只有这一个数据源。 */
    fun save(contact: Contact): Boolean =
        runCatching { privateStore.save(contact.copy(source = PrivateContactStore.SOURCE)) }.isSuccess

    /**
     * 收藏 / 取消收藏。
     *
     * commons 的 toggleFavorites 是 private 的，够不着，所以直接改 starred
     * 再走 updateContact。第二个参数是「联系人来源类型」，
     * 0 表示保持原样（不是布尔 false —— 它的签名是 Int）。
     */
    fun toggleFavorite(contact: Contact, favorite: Boolean): Boolean {
        contact.starred = if (favorite) 1 else 0
        return runCatching { privateStore.save(contact) }.isSuccess
    }

    // ------------------------------------------------------------------ 模型

    /**
     * UI 用的联系人。只保留界面真正要显示的字段。
     *
     * 保留 [raw] 是为了详情页和编辑页还能拿到完整对象 ——
     * 把所有字段都摊平到这里的话，这个类会有三十多个属性。
     */
    data class UiContact(
        val id: Int,
        val name: String,
        val initial: String,
        val primaryNumber: String,
        val subtitle: String,
        val photoUri: String,
        val isFavorite: Boolean,
        val sortLetter: String,
        val raw: Contact,
    )

    data class UiGroup(val id: Long, val name: String, val count: Int)

    companion object {
        private const val TAG = "ContactRepository"

        /**
         * 中文排序器。
         *
         * 直接用 String 的自然顺序排中文，比的是 Unicode 码点 ——
         * 那是按部首笔画排的，和拼音毫无关系，「王」会排在「张」前面
         * （码点更小），但拼音里 W 应该在 Z 前面…… 巧合对了这一个，
         * 大部分情况是错的。
         *
         * Collator 的中文规则就是按拼音，和分组字母天然一致。
         */
        private val CHINESE_COLLATOR: java.text.Collator =
            java.text.Collator.getInstance(java.util.Locale.CHINA)

        /**
         * 排序规则：先按分组字母，同字母内按拼音。
         * 「#」组（数字、符号、生僻字）永远排最后。
         */
        private val NAME_ORDER = Comparator<UiContact> { a, b ->
            val aHash = a.sortLetter == "#"
            val bHash = b.sortLetter == "#"
            when {
                aHash != bHash -> if (aHash) 1 else -1
                a.sortLetter != b.sortLetter -> a.sortLetter.compareTo(b.sortLetter)
                else -> CHINESE_COLLATOR.compare(a.name, b.name)
            }
        }

        /** 分组字母。中文走拼音首字母，见 [Pinyin]。 */
        fun sortLetterOf(name: String): String = Pinyin.initialOf(name).toString()
    }

    private fun Contact.toUi(): UiContact {
        val display = getNameToDisplay()
        val number = phoneNumbers.firstOrNull { it.isPrimary }?.value
            ?: phoneNumbers.firstOrNull()?.value.orEmpty()
        val company = organization.company
        // 副标题是「公司 · 号码」，缺哪个就只显示另一个，两个都没有就空着
        val subtitle = listOf(company, number).filter { it.isNotBlank() }.joinToString(" · ")
        return UiContact(
            id = id,
            name = display,
            initial = com.evelorion.contacts.ui.widget.AvatarDrawable.initialOf(display),
            primaryNumber = number,
            subtitle = subtitle,
            photoUri = photoUri,
            isFavorite = starred == 1,
            sortLetter = sortLetterOf(display),
            raw = this,
        )
    }
}
