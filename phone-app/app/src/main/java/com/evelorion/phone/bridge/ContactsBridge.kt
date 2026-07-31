package com.evelorion.phone.bridge

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.util.Log
import java.security.MessageDigest

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
    private const val OFFICIAL_CERT_SHA256 =
        "127d2f23c90868b267016c66f01a4b5550e2a04c4a2cb25c6000467cf6611b4b"

    enum class AccessState {
        AVAILABLE,
        APP_NOT_INSTALLED,
        ACCESS_DENIED,
        PROVIDER_ERROR,
    }

    @Volatile
    var accessState: AccessState = AccessState.PROVIDER_ERROR
        private set

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

    /** 修改通讯录中的真实收藏状态。只有同一发行证书签名的电话 App 能调用。 */
    fun setFavorite(context: Context, contactId: Int, favorite: Boolean): Boolean {
        if (contactId <= 0 || !usesOfficialCertificates(context)) return false
        return try {
            val uri = CONTACTS_URI.buildUpon().appendPath(contactId.toString()).build()
            val changed = context.contentResolver.update(
                uri,
                ContentValues().apply { put("starred", if (favorite) 1 else 0) },
                null,
                null,
            )
            changed == 1
        } catch (e: Exception) {
            Log.w(TAG, "修改联系人收藏失败，id=$contactId", e)
            false
        }
    }

    private fun query(context: Context, uri: Uri): List<Contact> {
        if (!usesOfficialCertificates(context)) return emptyList()

        return try {
            val result = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
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
            }
            accessState = if (result == null) AccessState.PROVIDER_ERROR else AccessState.AVAILABLE
            result ?: emptyList()
        } catch (e: SecurityException) {
            accessState = AccessState.ACCESS_DENIED
            Log.w(TAG, "通讯录拒绝访问：请确认电话与通讯录使用同一正式发行证书", e)
            emptyList()
        } catch (e: Exception) {
            accessState = if (contactsAppInstalled(context)) {
                AccessState.PROVIDER_ERROR
            } else {
                AccessState.APP_NOT_INSTALLED
            }
            Log.w(TAG, "读取加密联系人失败：${e.message}", e)
            emptyList()
        }
    }

    /** 通讯录装没装。设置页用它决定要不要提示用户去装。 */
    fun contactsAppInstalled(context: Context): Boolean = runCatching {
        context.contentResolver.acquireContentProviderClient(AUTHORITY)?.also { it.close() } != null
    }.getOrDefault(false)

    private fun usesOfficialCertificates(context: Context): Boolean {
        val providerPackage = context.packageManager
            .resolveContentProvider(AUTHORITY, 0)
            ?.packageName
        if (providerPackage == null) {
            accessState = AccessState.APP_NOT_INSTALLED
            return false
        }

        val trusted = listOf(context.packageName, providerPackage).all { packageName ->
            signingCertificates(context, packageName).any { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) } == OFFICIAL_CERT_SHA256
            }
        }
        if (!trusted) {
            accessState = AccessState.ACCESS_DENIED
            Log.w(TAG, "电话或通讯录没有使用正式发行证书")
        }
        return trusted
    }

    private fun signingCertificates(context: Context, packageName: String): List<Signature> =
        try {
            val packageManager = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                ).signingInfo
                when {
                    signingInfo == null -> emptyList()
                    signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners.toList()
                    else -> signingInfo.signingCertificateHistory.toList()
                }
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES,
                ).signatures?.toList().orEmpty()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            emptyList()
        }
}
