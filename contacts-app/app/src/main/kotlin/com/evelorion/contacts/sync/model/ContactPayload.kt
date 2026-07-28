package com.evelorion.contacts.sync.model

import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.contacts.Address
import org.fossify.commons.models.contacts.Email
import org.fossify.commons.models.contacts.Event
import org.fossify.commons.models.contacts.IM
import org.fossify.commons.models.contacts.LocalContact
import com.evelorion.contacts.sync.crypto.VaultCrypto
import org.json.JSONArray
import org.json.JSONObject

/**
 * 上传到服务器的联系人明文结构（加密之前的那一层）。
 *
 * 为什么不直接序列化 commons 的 Contact / LocalContact：
 *   1. 它们的 id 是本机 Room 表的自增主键，换台设备就对不上了，不能进负载。
 *   2. 它们是外部依赖里的类，加字段我们控制不了，直接序列化会让协议跟着别人的版本漂移。
 *   3. 三方合并需要给每个列表条目一个跨设备稳定的 id（见 VaultCrypto.itemId）。
 *
 * 序列化规则（两端必须一致，改动前先跑 CryptoVectorsTest）：
 *   · 所有键一律输出，空值写成 "" 或 []，不做省略
 *   · 对象的键按字典序排列，列表按条目 id 排序
 *   · 不输出任何空白字符
 * 解析时缺失的键一律当作空值，这样以后加字段是向后兼容的。
 */
data class ContactPayload(
    val v: Int = VaultCrypto.SCHEMA_VERSION,
    val prefix: String = "",
    val first: String = "",
    val middle: String = "",
    val surname: String = "",
    val suffix: String = "",
    val nickname: String = "",
    val company: String = "",
    val jobTitle: String = "",
    val notes: String = "",
    val starred: Int = 0,
    val ringtone: String = "",
    /** 头像的 blob id，空串表示没有头像。图片本体走 /v1/blobs。 */
    val photo: String = "",
    val phones: List<PhoneItem> = emptyList(),
    val emails: List<LabeledItem> = emptyList(),
    val addresses: List<AddressItem> = emptyList(),
    val events: List<EventItem> = emptyList(),
    val websites: List<SimpleItem> = emptyList(),
    val ims: List<LabeledItem> = emptyList(),
    val groups: List<SimpleItem> = emptyList(),
) {

    data class PhoneItem(
        val id: String, val value: String, val norm: String,
        val type: Int, val label: String, val primary: Boolean,
    )

    data class LabeledItem(val id: String, val value: String, val type: Int, val label: String)

    data class SimpleItem(val id: String, val value: String)

    data class EventItem(val id: String, val value: String, val type: Int)

    data class AddressItem(
        val id: String, val value: String, val type: Int, val label: String,
        val country: String, val region: String, val city: String,
        val postcode: String, val pobox: String, val street: String, val neighborhood: String,
    )

    // ---------------------------------------------------------------- 序列化

    fun toCanonicalJson(): String = buildString {
        append('{')
        var isFirstKey = true
        fun key(name: String) {
            if (!isFirstKey) append(',')
            isFirstKey = false
            append(quote(name)).append(':')
        }
        // 键按字典序排列
        key("addresses"); append(addressesJson())
        key("company"); append(quote(company))
        key("emails"); append(labeledJson(emails))
        key("events"); append(eventsJson())
        key("first"); append(quote(first))
        key("groups"); append(simpleJson(groups))
        key("ims"); append(labeledJson(ims))
        key("jobTitle"); append(quote(jobTitle))
        key("middle"); append(quote(middle))
        key("nickname"); append(quote(nickname))
        key("notes"); append(quote(notes))
        key("phones"); append(phonesJson())
        key("photo"); append(quote(photo))
        key("prefix"); append(quote(prefix))
        key("ringtone"); append(quote(ringtone))
        key("starred"); append(starred)
        key("suffix"); append(quote(suffix))
        key("surname"); append(quote(surname))
        key("v"); append(v)
        key("websites"); append(simpleJson(websites))
        append('}')
    }

    private fun phonesJson() = phones.sortedBy { it.id }.joinToString(",", "[", "]") {
        "{" + listOf(
            "\"id\":${quote(it.id)}",
            "\"label\":${quote(it.label)}",
            "\"norm\":${quote(it.norm)}",
            "\"primary\":${it.primary}",
            "\"type\":${it.type}",
            "\"value\":${quote(it.value)}",
        ).joinToString(",") + "}"
    }

    private fun labeledJson(items: List<LabeledItem>) = items.sortedBy { it.id }.joinToString(",", "[", "]") {
        "{" + listOf(
            "\"id\":${quote(it.id)}",
            "\"label\":${quote(it.label)}",
            "\"type\":${it.type}",
            "\"value\":${quote(it.value)}",
        ).joinToString(",") + "}"
    }

    private fun simpleJson(items: List<SimpleItem>) = items.sortedBy { it.id }.joinToString(",", "[", "]") {
        "{\"id\":${quote(it.id)},\"value\":${quote(it.value)}}"
    }

    private fun eventsJson() = events.sortedBy { it.id }.joinToString(",", "[", "]") {
        "{\"id\":${quote(it.id)},\"type\":${it.type},\"value\":${quote(it.value)}}"
    }

    private fun addressesJson() = addresses.sortedBy { it.id }.joinToString(",", "[", "]") {
        "{" + listOf(
            "\"city\":${quote(it.city)}",
            "\"country\":${quote(it.country)}",
            "\"id\":${quote(it.id)}",
            "\"label\":${quote(it.label)}",
            "\"neighborhood\":${quote(it.neighborhood)}",
            "\"pobox\":${quote(it.pobox)}",
            "\"postcode\":${quote(it.postcode)}",
            "\"region\":${quote(it.region)}",
            "\"street\":${quote(it.street)}",
            "\"type\":${it.type}",
            "\"value\":${quote(it.value)}",
        ).joinToString(",") + "}"
    }

    companion object {

        /** JSONObject.quote 会正确转义控制字符和引号，不要自己手写。 */
        private fun quote(s: String): String = JSONObject.quote(s)

        fun fromJson(json: String): ContactPayload {
            val o = JSONObject(json)
            return ContactPayload(
                v = o.optInt("v", 1),
                prefix = o.optString("prefix"),
                first = o.optString("first"),
                middle = o.optString("middle"),
                surname = o.optString("surname"),
                suffix = o.optString("suffix"),
                nickname = o.optString("nickname"),
                company = o.optString("company"),
                jobTitle = o.optString("jobTitle"),
                notes = o.optString("notes"),
                starred = o.optInt("starred", 0),
                ringtone = o.optString("ringtone"),
                photo = o.optString("photo"),
                phones = o.optJSONArray("phones").mapObjects {
                    PhoneItem(
                        id = it.optString("id"), value = it.optString("value"),
                        norm = it.optString("norm"), type = it.optInt("type"),
                        label = it.optString("label"), primary = it.optBoolean("primary", false),
                    )
                },
                emails = o.optJSONArray("emails").mapObjects { it.toLabeled() },
                ims = o.optJSONArray("ims").mapObjects { it.toLabeled() },
                addresses = o.optJSONArray("addresses").mapObjects {
                    AddressItem(
                        id = it.optString("id"), value = it.optString("value"),
                        type = it.optInt("type"), label = it.optString("label"),
                        country = it.optString("country"), region = it.optString("region"),
                        city = it.optString("city"), postcode = it.optString("postcode"),
                        pobox = it.optString("pobox"), street = it.optString("street"),
                        neighborhood = it.optString("neighborhood"),
                    )
                },
                events = o.optJSONArray("events").mapObjects {
                    EventItem(it.optString("id"), it.optString("value"), it.optInt("type"))
                },
                websites = o.optJSONArray("websites").mapObjects {
                    SimpleItem(it.optString("id"), it.optString("value"))
                },
                groups = o.optJSONArray("groups").mapObjects {
                    SimpleItem(it.optString("id"), it.optString("value"))
                },
            )
        }

        private fun JSONObject.toLabeled() =
            LabeledItem(optString("id"), optString("value"), optInt("type"), optString("label"))

        private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
            if (this == null) return emptyList()
            val out = ArrayList<T>(length())
            for (i in 0 until length()) {
                optJSONObject(i)?.let { out.add(transform(it)) }
            }
            return out
        }

        // ---------------------------------------- 与 commons LocalContact 互转

        /**
         * 直接读写 LocalContact 而不是 Contact，原因有两个：
         *
         *   1. Contact 里的头像是 Bitmap。来回解码既慢又会改变原始字节，
         *      而 blob id 是对字节做 HMAC，字节变了 id 就变，会导致每次同步都重传头像。
         *      LocalContact 存的就是原始 ByteArray，正好。
         *   2. LocalContactsHelper.insertOrUpdateContact 只返回 Boolean，拿不到新插入行的
         *      自增 id，而同步必须记住这个 id 才能建立 uuid 到本地行的映射。
         *      走 contactsDB.insertOrUpdate 能拿到 Long。
         *
         * photoBlobId 由调用方先把头像加密上传后传进来，加密和网络不该塞在模型转换里。
         * groupTitles 是本机组 id 到组名的映射，组 id 是本地自增的，不能进负载。
         */
        fun fromLocalContact(
            contact: LocalContact,
            photoBlobId: String,
            groupTitles: Map<Long, String>,
        ): ContactPayload = ContactPayload(
            prefix = contact.prefix,
            first = contact.firstName,
            middle = contact.middleName,
            surname = contact.surname,
            suffix = contact.suffix,
            nickname = contact.nickname,
            company = contact.company,
            jobTitle = contact.jobPosition,
            notes = contact.notes,
            starred = contact.starred,
            ringtone = contact.ringtone.orEmpty(),
            photo = photoBlobId,
            phones = contact.phoneNumbers.map {
                val norm = normalizeNumber(it.normalizedNumber.ifEmpty { it.value })
                PhoneItem(
                    id = VaultCrypto.itemId("phones", norm),
                    value = it.value, norm = norm, type = it.type,
                    label = it.label, primary = it.isPrimary,
                )
            }.distinctBy { it.id },
            emails = contact.emails.map {
                LabeledItem(VaultCrypto.itemId("emails", it.value.lowercase()), it.value, it.type, it.label)
            }.distinctBy { it.id },
            ims = contact.IMs.map {
                LabeledItem(VaultCrypto.itemId("ims", "${it.type} ${it.value}"), it.value, it.type, it.label)
            }.distinctBy { it.id },
            addresses = contact.addresses.map {
                AddressItem(
                    id = VaultCrypto.itemId("addresses", it.value),
                    value = it.value, type = it.type, label = it.label,
                    country = it.country, region = it.region, city = it.city,
                    postcode = it.postcode, pobox = it.pobox, street = it.street,
                    neighborhood = it.neighborhood,
                )
            }.distinctBy { it.id },
            events = contact.events.map {
                EventItem(VaultCrypto.itemId("events", "${it.type} ${it.value}"), it.value, it.type)
            }.distinctBy { it.id },
            websites = contact.websites.map {
                SimpleItem(VaultCrypto.itemId("websites", it), it)
            }.distinctBy { it.id },
            groups = contact.groups.mapNotNull { groupId ->
                groupTitles[groupId]?.let { title ->
                    SimpleItem(VaultCrypto.itemId("groups", title), title)
                }
            }.distinctBy { it.id },
        )

        /**
         * 把负载写回本机。
         * localId 为 null 表示新建，让 Room 自增分配。
         * groupResolver 按组名找出（必要时创建）本机的组 id。
         */
        fun ContactPayload.toLocalContact(
            localId: Int?,
            photoBytes: ByteArray?,
            groupResolver: (String) -> Long?,
        ): LocalContact = LocalContact(
            id = localId,
            prefix = prefix,
            firstName = first,
            middleName = middle,
            surname = surname,
            suffix = suffix,
            nickname = nickname,
            photo = photoBytes,
            photoUri = "",
            phoneNumbers = ArrayList(phones.map {
                PhoneNumber(it.value, it.type, it.label, it.norm, it.primary)
            }),
            emails = ArrayList(emails.map { Email(it.value, it.type, it.label) }),
            events = ArrayList(events.map { Event(it.value, it.type) }),
            starred = starred,
            addresses = ArrayList(addresses.map {
                Address(
                    it.value, it.type, it.label, it.country, it.region, it.city,
                    it.postcode, it.pobox, it.street, it.neighborhood,
                )
            }),
            notes = notes,
            groups = ArrayList(groups.mapNotNull { groupResolver(it.value) }),
            company = company,
            jobPosition = jobTitle,
            websites = ArrayList(websites.map { it.value }),
            IMs = ArrayList(ims.map { IM(it.value, it.type, it.label) }),
            ringtone = ringtone.ifEmpty { null },
        )

        /**
         * 号码归一化。两台设备必须对同一个号码算出同一个字符串，
         * 否则 itemId 不同，合并后会变成两条重复号码。
         * 只保留数字和开头的加号，空格、括号、连字符一律去掉。
         */
        fun normalizeNumber(raw: String): String {
            val sb = StringBuilder(raw.length)
            for ((i, c) in raw.withIndex()) {
                when {
                    c.isDigit() -> sb.append(c)
                    c == '+' && i == 0 -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
