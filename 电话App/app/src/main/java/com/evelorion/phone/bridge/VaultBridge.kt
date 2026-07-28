package com.evelorion.phone.bridge

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * 向通讯录索取同步凭据。
 *
 * ── 拿到的是什么 ────────────────────────────────────────────
 *
 *   baseUrl        服务器地址（用户在通讯录里填的，这边不再问一遍）
 *   accessToken    **短期**访问令牌（15 分钟），刷新令牌拿不到
 *   collectionKey  HKDF(DEK, "fc.collection.calls.v1") 派生的子密钥
 *
 * 关键在最后一条：给的**不是 DEK**。用它能加解密通话记录，但推不回 DEK，
 * 所以电话 App 解不开任何一条联系人。万一这个 App 被攻破，
 * 泄露的是通话记录，不是整个通讯录。
 *
 * 令牌短期也是同样的思路：电话 App 没法长期独占账号访问权，
 * 过期了就得再问一次，而那时候用户的保险库必须是解锁状态。
 */
object VaultBridge {

    private const val TAG = "VaultBridge"
    private const val AUTHORITY = "com.evelorion.contacts.vaultbridge"

    /** 通讯录还没配置同步账号。 */
    const val STATUS_NOT_CONFIGURED = "not_configured"

    /** 配置了但保险库锁着 —— 要用户先去通讯录里解锁，这边干等没用。 */
    const val STATUS_LOCKED = "locked"

    const val STATUS_OK = "ok"

    data class Session(
        val status: String,
        val baseUrl: String = "",
        val accessToken: String = "",
        val accessExpiresAt: Long = 0,
        /** 十六进制的 calls 子密钥。 */
        val collectionKeyHex: String = "",
        val accountId: String = "",
    ) {
        val usable: Boolean get() = status == STATUS_OK &&
            baseUrl.isNotBlank() && accessToken.isNotBlank() && collectionKeyHex.isNotBlank()

        /** 人话版的状态，直接显示给用户。 */
        val message: String
            get() = when (status) {
                STATUS_OK -> "已连接到通讯录的同步账号"
                STATUS_LOCKED -> "通讯录的保险库锁着，请先在通讯录里解锁一次"
                else -> "通讯录还没有配置同步账号"
            }
    }

    /** **跨进程查询，必须在后台线程调用。** */
    fun session(context: Context): Session = try {
        context.contentResolver.query(
            Uri.parse("content://$AUTHORITY/session"),
            null, null, arrayOf("calls"), null,
        )?.use { c ->
            if (!c.moveToFirst()) Session(STATUS_NOT_CONFIGURED)
            else Session(
                status = c.getString(c.getColumnIndexOrThrow("status")).orEmpty(),
                baseUrl = c.str("base_url"),
                accessToken = c.str("access_token"),
                accessExpiresAt = runCatching { c.getLong(c.getColumnIndexOrThrow("access_expires_at")) }.getOrDefault(0L),
                collectionKeyHex = c.str("collection_key"),
                accountId = c.str("account_id"),
            )
        } ?: Session(STATUS_NOT_CONFIGURED)
    } catch (e: Exception) {
        // 通讯录没装、权限没给、provider 没起来都走这里。
        // 这不是错误状态，是「还没打通」，界面照常显示提示。
        Log.i(TAG, "取同步凭据失败（通讯录可能未安装）：${e.message}")
        Session(STATUS_NOT_CONFIGURED)
    }

    private fun android.database.Cursor.str(name: String): String =
        runCatching { getString(getColumnIndexOrThrow(name)).orEmpty() }.getOrDefault("")
}
