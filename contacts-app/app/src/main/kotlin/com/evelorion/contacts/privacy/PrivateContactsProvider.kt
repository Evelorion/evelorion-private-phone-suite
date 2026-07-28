package com.evelorion.contacts.privacy

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.evelorion.contacts.data.PrivateContactStore
import com.evelorion.contacts.helpers.PrivacyGuard
import com.evelorion.contacts.helpers.config
import com.evelorion.contacts.sync.VaultManager
import com.evelorion.contacts.sync.crypto.Crypto
import com.evelorion.contacts.sync.crypto.VaultCrypto
import com.evelorion.contacts.sync.db.SyncDatabase
import com.evelorion.contacts.sync.model.ContactPayload

/**
 * 加密联系人的对外出口。给自家的电话 App 用。
 *
 * ── 两条路径 ─────────────────────────────────────────────────
 *
 *   /contacts        列出全部加密联系人（电话 App 的联系人页、拨号匹配用）
 *   /lookup/<号码>   按号码查是谁（来电显示用）
 *
 * ── 为什么查号要在这一侧做 ───────────────────────────────────
 *
 * 号码在数据库里没有明文列，能查是靠**盲索引**：
 * `HMAC(HKDF(DEK, salt), 归一化号码)`。算它需要 DEK，而 DEK 只在通讯录这边。
 *
 * 所以电话 App 只能把号码原样递过来，由这边算索引、查表、把名字递回去。
 * 这不算泄露：那个号码本来就是电话 App 从来电里拿到的，它早就知道。
 *
 * 反过来如果把索引密钥交给电话 App，它就能离线枚举任意号码是否在通讯录里 ——
 * 权限扩大了，收益却只是省一次跨进程调用。
 *
 * ── 谁能读 ───────────────────────────────────────────────────
 *
 * signature 级权限 + PrivacyGuard 的签名指纹校验，双重门槛。
 * 不满足时返回**空 Cursor 而不是抛异常** —— 抛异常会让调用方闪退，
 * 反而等于告诉对方「这里确实有东西」。
 */
class PrivateContactsProvider : ContentProvider() {

    companion object {
        private const val TAG = "PrivateContacts"

        const val AUTHORITY = "com.evelorion.contacts.privateprovider"
        const val PATH_CONTACTS = "contacts"
        const val PATH_LOOKUP = "lookup"

        const val COL_ID = "id"
        const val COL_NAME = "name"
        const val COL_NUMBER = "number"
        const val COL_STARRED = "starred"

        /** 所属分组名，用 \u0001 分隔。选这个分隔符是因为分组名里不可能出现它。 */
        const val COL_GROUPS = "groups"

        const val GROUP_SEPARATOR = "\u0001"

        private val COLUMNS = arrayOf(COL_ID, COL_NAME, COL_NUMBER, COL_STARRED, COL_GROUPS)
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = context ?: return empty()

        if (!PrivacyGuard.isCallerAllowed(context, callingPackage, context.config.privacyProtectionEnabled)) {
            Log.w(TAG, "拒绝了 ${callingPackage ?: "未知调用方"} 读取加密联系人")
            return empty()
        }

        val segments = uri.pathSegments
        return when (segments.firstOrNull()) {
            PATH_CONTACTS -> listAll(context)
            PATH_LOOKUP -> lookupByNumber(context, segments.getOrNull(1).orEmpty())
            else -> empty()
        }
    }

    /** 全部加密联系人。号码只给第一个 —— 列表和拨号匹配用不到更多。 */
    private fun listAll(context: android.content.Context): Cursor = runCatching {
        val cursor = MatrixCursor(COLUMNS)
        PrivateContactStore(context).loadAllWithGroups().forEach { (c, groups) ->
            cursor.newRow()
                .add(COL_ID, c.id)
                .add(COL_NAME, c.getNameToDisplay())
                .add(COL_NUMBER, c.phoneNumbers.firstOrNull()?.value.orEmpty())
                .add(COL_STARRED, if (c.starred == 1) 1 else 0)
                .add(COL_GROUPS, groups.joinToString(GROUP_SEPARATOR))
        }
        cursor
    }.onFailure { Log.w(TAG, "读取加密联系人失败", it) }.getOrDefault(empty())

    /**
     * 按号码查人。
     *
     * 保险库锁着（用户还没解锁）时查不了 —— 算盲索引需要 DEK。
     * 这时返回空，来电界面就显示号码本身，而不是报错。
     */
    private fun lookupByNumber(context: android.content.Context, rawNumber: String): Cursor {
        if (rawNumber.isBlank()) return empty()

        val vault = VaultManager.get(context)
        val dek = vault.dek() ?: return empty()
        val salt = vault.session.kdfSalt ?: return empty()

        val normalized = ContactPayload.normalizeNumber(rawNumber)
        if (normalized.isEmpty()) return empty()

        val indexKey = VaultCrypto.deriveIndexKey(dek, salt)
        return try {
            val dao = SyncDatabase.get(context).syncDao()
            val hits = dao.lookupIndex(VaultCrypto.blindIndex(indexKey, normalized))
            if (hits.isEmpty()) return empty()

            val store = PrivateContactStore(context)
            val cursor = MatrixCursor(COLUMNS)
            hits.mapNotNull { runCatching { store.getById(it.localId) }.getOrNull() }
                .distinctBy { it.id }
                .forEach { c ->
                    cursor.newRow()
                        .add(COL_ID, c.id)
                        .add(COL_NAME, c.getNameToDisplay())
                        .add(COL_NUMBER, rawNumber)
                        .add(COL_STARRED, if (c.starred == 1) 1 else 0)
                        .add(COL_GROUPS, "")
                }
            cursor
        } catch (e: Exception) {
            Log.w(TAG, "按号码查联系人失败", e)
            empty()
        } finally {
            // 索引密钥用完立刻抹掉。它留在内存里等于给了「离线枚举任意号码」的能力
            Crypto.wipe(indexKey)
        }
    }

    private fun empty() = MatrixCursor(COLUMNS)

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.dir/vnd.com.evelorion.contacts.contact"

    // 只读。写操作一律拒绝 —— 不要因为「反正没人调」就返回成功，
    // 返回 1 会让调用方以为写进去了。
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
}
