package com.evelorion.contacts.data

import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.contacts.Contact
import org.fossify.commons.models.contacts.Email
import org.fossify.commons.models.contacts.Organization
import java.io.BufferedReader

/**
 * vCard 3.0 的读写。
 *
 * ── 为什么自己写而不引 ez-vcard ─────────────────────────────
 *
 * ez-vcard 大约 700 KB，支持 vCard 2.1/3.0/4.0 的全部特性 ——
 * 而通讯录导入导出真正会用到的只有姓名、电话、邮箱、公司这几行。
 * 自己写一百多行，包小得多，行为也完全可控。
 *
 * 代价：不支持照片（BASE64 内联）、不支持 vCard 4.0 的部分语法。
 * 遇到不认识的行直接跳过，不会因此整个文件读不了。
 *
 * ── 折行（line folding）────────────────────────────────────
 *
 * vCard 规定单行超过 75 字节要折行，续行以空格或制表符开头。
 * 不处理折行的话，长地址、长备注会被截断成两条无效行 ——
 * 这是解析 vCard 最常见的出错原因。
 */
object VCard {

    // ------------------------------------------------------------------ 导出

    fun export(contacts: List<Contact>): String = buildString {
        contacts.forEach { c ->
            append("BEGIN:VCARD\r\n")
            append("VERSION:3.0\r\n")

            // N 是结构化姓名：姓;名;中间名;前缀;后缀
            append("N:${esc(c.surname)};${esc(c.firstName)};${esc(c.middleName)};${esc(c.prefix)};${esc(c.suffix)}\r\n")
            append("FN:${esc(c.getNameToDisplay())}\r\n")

            c.phoneNumbers.forEach { p ->
                append(fold("TEL;TYPE=${typeName(p.type)}:${esc(p.value)}"))
            }
            c.emails.forEach { e ->
                append(fold("EMAIL;TYPE=${emailTypeName(e.type)}:${esc(e.value)}"))
            }
            if (c.organization.company.isNotBlank()) {
                append(fold("ORG:${esc(c.organization.company)}"))
            }
            if (c.organization.jobPosition.isNotBlank()) {
                append(fold("TITLE:${esc(c.organization.jobPosition)}"))
            }
            if (c.notes.isNotBlank()) {
                append(fold("NOTE:${esc(c.notes)}"))
            }
            append("END:VCARD\r\n")
        }
    }

    /**
     * 折行到 75 字节。
     *
     * 注意是**字节**不是字符 —— 中文一个字 3 字节，按字符折会超标。
     * 而且不能在一个多字节字符中间切开，否则续行拼回来是乱码。
     */
    private fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return "$line\r\n"

        val out = StringBuilder()
        var chunk = StringBuilder()
        var chunkBytes = 0
        var first = true

        line.forEach { ch ->
            val w = ch.toString().toByteArray(Charsets.UTF_8).size
            val limit = if (first) 75 else 74 // 续行开头要占一个空格
            if (chunkBytes + w > limit) {
                out.append(if (first) chunk else " $chunk").append("\r\n")
                first = false
                chunk = StringBuilder()
                chunkBytes = 0
            }
            chunk.append(ch)
            chunkBytes += w
        }
        if (chunk.isNotEmpty()) out.append(if (first) chunk else " $chunk").append("\r\n")
        return out.toString()
    }

    /** vCard 里 \ , ; 换行都要转义。 */
    private fun esc(s: String) = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")

    private fun unesc(s: String) = s
        .replace("\\n", "\n").replace("\\N", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")

    private fun typeName(type: Int) = when (type) {
        android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "HOME"
        android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "WORK"
        else -> "CELL"
    }

    private fun emailTypeName(type: Int) = when (type) {
        android.provider.ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "WORK"
        else -> "HOME"
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 解析。遇到不认识的属性直接跳过 —— 宁可少读几个字段，
     * 也不要因为一行看不懂就整个文件导不进来。
     */
    fun parse(reader: BufferedReader): List<Parsed> {
        val out = mutableListOf<Parsed>()
        var current: Parsed? = null
        var pending: String? = null

        fun flushPending() {
            val line = pending ?: return
            pending = null
            val c = current ?: return
            handleLine(c, line)
        }

        reader.forEachLine { raw ->
            // 续行：以空格或制表符开头，接到上一行末尾
            if (raw.startsWith(" ") || raw.startsWith("\t")) {
                pending = (pending ?: "") + raw.substring(1)
                return@forEachLine
            }
            flushPending()

            val line = raw.trim()
            when {
                line.equals("BEGIN:VCARD", true) -> current = Parsed()
                line.equals("END:VCARD", true) -> {
                    current?.takeIf { it.isUsable() }?.let { out.add(it) }
                    current = null
                }
                else -> pending = line
            }
        }
        flushPending()
        return out
    }

    private fun handleLine(c: Parsed, line: String) {
        val colon = line.indexOf(':')
        if (colon <= 0) return
        val head = line.substring(0, colon).uppercase()
        val value = line.substring(colon + 1)
        val prop = head.substringBefore(';')

        when (prop) {
            "N" -> {
                // 姓;名;中间名;前缀;后缀 —— split 要保留空段，所以 limit 给 -1
                val parts = value.split(';')
                c.surname = unesc(parts.getOrNull(0).orEmpty())
                c.firstName = unesc(parts.getOrNull(1).orEmpty())
                c.middleName = unesc(parts.getOrNull(2).orEmpty())
            }
            "FN" -> c.formattedName = unesc(value)
            "TEL" -> if (value.isNotBlank()) c.phones.add(unesc(value) to typeOf(head))
            "EMAIL" -> if (value.isNotBlank()) c.emails.add(unesc(value))
            "ORG" -> c.company = unesc(value.substringBefore(';'))
            "TITLE" -> c.jobPosition = unesc(value)
            "NOTE" -> c.notes = unesc(value)
        }
    }

    private fun typeOf(head: String): Int {
        val t = head.uppercase()
        return when {
            t.contains("WORK") -> android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK
            t.contains("HOME") -> android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_HOME
            else -> android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        }
    }

    /** 解析中间态。转成 commons 的 Contact 由 [toContact] 做。 */
    class Parsed {
        var firstName = ""
        var middleName = ""
        var surname = ""
        var formattedName = ""
        var company = ""
        var jobPosition = ""
        var notes = ""
        val phones = mutableListOf<Pair<String, Int>>()
        val emails = mutableListOf<String>()

        /** 连名字和号码都没有的条目没有意义，丢掉。 */
        fun isUsable() = displayName().isNotBlank() || phones.isNotEmpty()

        fun displayName(): String {
            val fromParts = listOf(surname, firstName, middleName)
                .filter { it.isNotBlank() }.joinToString("")
            return fromParts.ifBlank { formattedName }
        }

        fun toContact(): Contact = Contact(
            id = 0,
            prefix = "",
            firstName = if (firstName.isNotBlank() || surname.isNotBlank()) firstName else displayName(),
            middleName = middleName,
            surname = surname,
            suffix = "",
            nickname = "",
            photoUri = "",
            phoneNumbers = ArrayList(phones.map { (v, t) -> PhoneNumber(v, t, "", v, false) }),
            emails = ArrayList(emails.map { Email(it, 1, "") }),
            addresses = ArrayList(),
            events = ArrayList(),
            source = "",
            starred = 0,
            contactId = 0,
            thumbnailUri = "",
            photo = null,
            notes = notes,
            groups = ArrayList(),
            organization = Organization(company, jobPosition),
            websites = ArrayList(),
            IMs = ArrayList(),
            mimetype = "",
            ringtone = null,
        )
    }
}
