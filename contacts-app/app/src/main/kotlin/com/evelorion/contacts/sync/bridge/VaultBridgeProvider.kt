package com.evelorion.contacts.sync.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.evelorion.contacts.helpers.config
import com.evelorion.contacts.helpers.PrivacyGuard
import com.evelorion.contacts.sync.VaultManager
import com.evelorion.contacts.sync.crypto.Crypto
import com.evelorion.contacts.sync.crypto.VaultCrypto

/**
 * 把同步凭据交给自家的其它 App（目前只有电话 App）。
 *
 * ── 为什么需要它 ──────────────────────────────────────────────
 *
 * 通话记录也要端到端加密同步到同一台服务器。但密钥体系在通讯录这边：
 * 主口令、DEK、恢复码都归它管。电话 App 不该再搞一套独立的账号和恢复码 ——
 * 那意味着用户要记两个口令、抄两份恢复码。
 *
 * ── 交出去的是什么 ────────────────────────────────────────────
 *
 * **不是 DEK 本身**，而是 `HKDF(DEK, "fc.collection.calls.v2")` 派生出的子密钥。
 *
 * 这个区分很要紧：电话 App 拿这把子密钥能加解密通话记录，但推不回 DEK，
 * 所以它解不开任何一条联系人。万一电话 App 出了漏洞被攻破，
 * 攻击者拿到的是通话记录，不是整个通讯录。
 *
 * 访问令牌给的是**短期的那个**（15 分钟），刷新令牌不给。
 * 电话 App 过期了再来要一次即可。这样它也没法长期独占账号访问权。
 *
 * ── 谁能读 ───────────────────────────────────────────────────
 *
 * 独立的 signature 级权限 `com.evelorion.permission.VAULT_BRIDGE`，
 * 再加 PrivacyGuard 的签名指纹校验。和私密联系人那条路是一样的门槛。
 */
class VaultBridgeProvider : ContentProvider() {

    companion object {
        private const val TAG = "VaultBridge"

        const val AUTHORITY = "com.evelorion.contacts.vaultbridge"

        /** content://com.evelorion.contacts.vaultbridge/session */
        const val PATH_SESSION = "session"

        const val COL_STATUS = "status"
        const val COL_BASE_URL = "base_url"
        const val COL_ACCESS_TOKEN = "access_token"
        const val COL_ACCESS_EXPIRES_AT = "access_expires_at"
        const val COL_COLLECTION_KEY = "collection_key"
        const val COL_ACCOUNT_ID = "account_id"

        /** 一切正常，可以同步。 */
        const val STATUS_OK = "ok"

        /** 通讯录还没配置同步账号。 */
        const val STATUS_NOT_CONFIGURED = "not_configured"

        /** 配置了但保险库锁着，要用户先去通讯录里解锁。 */
        const val STATUS_LOCKED = "locked"

        private val COLUMNS = arrayOf(
            COL_STATUS, COL_BASE_URL, COL_ACCESS_TOKEN,
            COL_ACCESS_EXPIRES_AT, COL_COLLECTION_KEY, COL_ACCOUNT_ID,
        )
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = context ?: return statusOnly(STATUS_NOT_CONFIGURED)

        if (!PrivacyGuard.isCallerAllowed(context, callingPackage, context.config.privacyProtectionEnabled)) {
            Log.w(TAG, "拒绝了 ${callingPackage ?: "未知调用方"} 索取同步凭据")
            return statusOnly(STATUS_NOT_CONFIGURED)
        }

        if (uri.lastPathSegment != PATH_SESSION) return statusOnly(STATUS_NOT_CONFIGURED)

        // 调用方要哪个 collection 的子密钥。目前只放行 calls。
        val collection = selectionArgs?.getOrNull(0) ?: "calls"
        if (collection != "calls") {
            Log.w(TAG, "拒绝：不允许索取 collection=$collection 的密钥")
            return statusOnly(STATUS_NOT_CONFIGURED)
        }

        val vault = VaultManager.get(context)
        if (!vault.isConfigured) return statusOnly(STATUS_NOT_CONFIGURED)

        val dek = vault.dek() ?: return statusOnly(STATUS_LOCKED)
        val accessToken = vault.session.accessToken ?: return statusOnly(STATUS_LOCKED)

        val collectionKey = VaultCrypto.deriveCollectionKeyV2(dek, collection)
        return try {
            MatrixCursor(COLUMNS).apply {
                newRow()
                    .add(COL_STATUS, STATUS_OK)
                    .add(COL_BASE_URL, vault.session.baseUrl)
                    .add(COL_ACCESS_TOKEN, accessToken)
                    .add(COL_ACCESS_EXPIRES_AT, vault.session.accessExpiresAt)
                    .add(COL_COLLECTION_KEY, Crypto.toHex(collectionKey))
                    .add(COL_ACCOUNT_ID, vault.session.accountId)
            }
        } finally {
            Crypto.wipe(collectionKey)
        }
    }

    private fun statusOnly(status: String) = MatrixCursor(COLUMNS).apply {
        newRow().add(COL_STATUS, status)
    }

    // 只读。写操作一律拒绝。
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.$AUTHORITY.session"
}
