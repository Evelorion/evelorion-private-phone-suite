package com.example.sms.data.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.sms.util.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class SystemContact(
    val id: String,
    val name: String,
    val phone: String,
    val photoUri: String? = null,
)

/** 读取系统联系人：新建短信页的建议联系人、以及把号码显示成姓名 */
class ContactsRepository(private val context: Context) {

    companion object {
        private const val PRIVATE_CONTACTS_AUTHORITY = "com.evelorion.contacts.privateprovider"
        private val PRIVATE_CONTACTS_URI = Uri.parse(
            "content://$PRIVATE_CONTACTS_AUTHORITY/contacts"
        )
        private const val OFFICIAL_CERT_SHA256 =
            "127d2f23c90868b267016c66f01a4b5550e2a04c4a2cb25c6000467cf6611b4b"
    }

    private val cache = mutableMapOf<String, String>()

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED

    suspend fun loadAll(): List<SystemContact> = withContext(Dispatchers.IO) {
        val out = mutableListOf<SystemContact>()
        val seen = mutableSetOf<String>()

        // 私密联系人由通讯录 App 的 signature Provider 提供。调用前同时校验
        // 自己和 Provider 的正式证书，避免重打包版本把号码交给冒充的 Provider。
        loadPrivateContacts().forEach { contact ->
            val key = PhoneUtils.normalize(contact.phone)
            if (key.isNotEmpty() && seen.add(key)) out += contact
        }

        // 系统联系人是兼容路径；用户未授予 READ_CONTACTS 时，私密联系人仍可用。
        if (!hasPermission()) return@withContext out
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(2) ?: continue
                    val key = PhoneUtils.normalize(number)
                    if (key.isEmpty() || !seen.add(key)) continue
                    out += SystemContact(
                        id = c.getString(0) ?: key,
                        name = c.getString(1) ?: number,
                        phone = number,
                        photoUri = c.getString(3),
                    )
                }
            }
        }
        out
    }

    /** 号码 -> 姓名，查不到返回 null */
    suspend fun lookupName(address: String): String? = withContext(Dispatchers.IO) {
        val key = PhoneUtils.normalize(address)
        if (key.isEmpty()) return@withContext null
        cache[key]?.let { return@withContext it.ifEmpty { null } }

        lookupPrivateName(address)?.let { name ->
            cache[key] = name
            return@withContext name
        }

        if (!hasPermission()) return@withContext null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address),
        )
        val name = runCatching {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        cache[key] = name.orEmpty()
        name
    }

    fun clearCache() = cache.clear()

    private fun loadPrivateContacts(): List<SystemContact> {
        if (!canUsePrivateContactsProvider()) return emptyList()
        return runCatching {
            context.contentResolver.query(
                PRIVATE_CONTACTS_URI,
                arrayOf("id", "name", "number"),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex("id")
                val nameIndex = cursor.getColumnIndex("name")
                val numberIndex = cursor.getColumnIndex("number")
                buildList {
                    while (cursor.moveToNext()) {
                        val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                        if (number.isBlank()) continue
                        add(
                            SystemContact(
                                id = if (idIndex >= 0) cursor.getString(idIndex).orEmpty() else number,
                                name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else number,
                                phone = number,
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun lookupPrivateName(address: String): String? {
        if (!canUsePrivateContactsProvider()) return null
        val uri = Uri.parse(
            "content://$PRIVATE_CONTACTS_AUTHORITY/lookup/${Uri.encode(address)}"
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf("name"),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        }.getOrNull()
    }

    private fun canUsePrivateContactsProvider(): Boolean {
        if (!packageUsesOfficialCertificate(context.packageName)) return false
        val providerPackage = context.packageManager
            .resolveContentProvider(PRIVATE_CONTACTS_AUTHORITY, 0)
            ?.packageName
            ?: return false
        if (providerPackage != "com.evelorion.contacts" &&
            providerPackage != "com.evelorion.contacts.debug"
        ) return false
        return packageUsesOfficialCertificate(providerPackage)
    }

    private fun packageUsesOfficialCertificate(packageName: String): Boolean =
        signingCertificates(packageName).any { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) } == OFFICIAL_CERT_SHA256
        }

    private fun signingCertificates(packageName: String): List<Signature> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = context.packageManager.getPackageInfo(
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
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNATURES,
            ).signatures?.toList().orEmpty()
        }
    } catch (_: PackageManager.NameNotFoundException) {
        emptyList()
    }
}
