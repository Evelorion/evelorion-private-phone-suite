package com.evelorion.phone.bridge

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * 向通讯录 App 要加密联系人。
 *
 * ── 为什么不自己存一份 ──────────────────────────────────────
 *
 * 联系人的密钥体系在通讯录那边，DEK 也只在它的内存里。电话 App 自己存
 * 意味着要么再来一套主口令（用户记两个口令、抄两份恢复码），
 * 要么把 DEK 交出来（那样电话 App 一旦被攻破，整个通讯录跟着丢）。
 *
 * 所以这边一条联系人都不存，全部现问现用。
 *
 * ── 拿不到的时候 ────────────────────────────────────────────
 *
 * 通讯录没装、没登录、或者保险库锁着，这里一律返回空列表而不是抛异常。
 * 电话 App 少了来电显示仍然能打电话；为此崩掉才是荒唐的。
 */
object ContactsBridge {

    private const val TAG = "ContactsBridge"

    /** 和通讯录 manifest 里声明的 authority 逐字一致。写错的表现是静默查不到。 */
    private const val AUTHORITY = "com.evelorion.contacts.privateprovider"
    val CONTACTS_URI: Uri = Uri.parse("content://$AUTHORITY/contacts")

    data class Contact(
        val id: Int,
        val name: String,
        val number: String,
        val starred: Boolean,
        /** 所属分组名。常用页的「家人」那一块靠它。 */
        val groups: List<String> = emptyList(),
    )

    /** 和通讯录 provider 里的分隔符必须一致。 */
    private const val GROUP_SEPARATOR = "\u0001"

    /** 全部加密联系人。**耗时操作，必须在后台线程调用。** */
    fun loadAll(context: Context): List<Contact> = query(
        context, CONTACTS_URI
    )

    /**
     * 按号码查是谁。来电显示用。
     *
     * 归一化和盲索引计算都在通讯录那边做 —— 算索引需要 DEK，
     * 而 DEK 不该离开通讯录进程。
     */
    fun lookup(context: Context, number: String): Contact? {
        if (number.isBlank()) return null
        return query(
            context, Uri.parse("content://$AUTHORITY/lookup/" + Uri.encode(number))
        ).firstOrNull()
    }

    private fun query(context: Context, uri: Uri): List<Contact> = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val out = ArrayList<Contact>(cursor.count)
            val idIdx = cursor.getColumnIndex("id")
            val nameIdx = cursor.getColumnIndex("name")
            val numberIdx = cursor.getColumnIndex("number")
            val starredIdx = cursor.getColumnIndex("starred")
            val groupsIdx = cursor.getColumnIndex("groups")
            while (cursor.moveToNext()) {
                out.add(
                    Contact(
                        id = if (idIdx >= 0) cursor.getInt(idIdx) else 0,
                        name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else "",
                        number = if (numberIdx >= 0) cursor.getString(numberIdx).orEmpty() else "",
                        starred = starredIdx >= 0 && cursor.getInt(starredIdx) == 1,
                        // 老版本的通讯录没有这一列，getColumnIndex 返回 -1。
                        // 这时当成"没有分组"，而不是崩掉 —— 两个 App 的版本
                        // 不可能永远同步更新。
                        groups = if (groupsIdx >= 0) {
                            cursor.getString(groupsIdx).orEmpty()
                                .split(GROUP_SEPARATOR).filter { it.isNotBlank() }
                        } else emptyList(),
                    )
                )
            }
            out
        } ?: emptyList()
    } catch (e: Exception) {
        // 通讯录没装、权限没给、provider 没起来，都会走到这里。
        // 记一笔就够了 —— 少了来电显示不该让电话 App 停摆。
        Log.i(TAG, "读取加密联系人失败（通讯录可能未安装或未解锁）：${e.message}")
        emptyList()
    }

    /** 通讯录装没装。设置页用它决定要不要提示用户去装。 */
    fun contactsAppInstalled(context: Context): Boolean =
        context.contentResolver.acquireContentProviderClient(AUTHORITY)?.also { it.close() } != null
}
