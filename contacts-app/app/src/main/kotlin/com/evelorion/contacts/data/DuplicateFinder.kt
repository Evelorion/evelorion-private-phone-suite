package com.evelorion.contacts.data

import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.contacts.Contact
import org.fossify.commons.models.contacts.Email

/**
 * 找重复联系人，并把一组合并成一条。
 *
 * ── 怎么判定「重复」────────────────────────────────────────
 *
 * 两条规则，命中任一条就算一组：
 *
 *   1. 归一化后的号码相同 —— 最可靠。「138 0013 8000」和「+8613800138000」
 *      是同一个人，去掉非数字再比末 8 位就能对上。
 *   2. 姓名完全相同且都有联系方式 —— 号码不同但同名，很可能是同一个人
 *      存了两次（一次从 SIM 卡导入，一次手动加）。
 *
 * **不按姓名模糊匹配**（编辑距离之类）。「张伟」和「张玮」是两个人，
 * 模糊匹配会把他们合掉 —— 合并是不可逆的，宁可漏判不可误判。
 *
 * ── 合并时保留谁 ────────────────────────────────────────────
 *
 * 保留字段最全的那一条当主记录（[score] 算的就是这个），其余记录的
 * 号码和邮箱去重后并进来，然后删掉多余的。
 * 保留 id 最小的那条会更"稳定"，但可能是信息最少的那条 —— 用户
 * 更在意的是别丢字段。
 */
object DuplicateFinder {

    data class Group(
        val contacts: List<Contact>,
        /** 命中的是哪条规则，UI 上要告诉用户为什么判成重复。 */
        val reason: Reason,
    )

    enum class Reason { SAME_NUMBER, SAME_NAME }

    /**
     * 号码归一化：去掉所有非数字，再取末 8 位。
     *
     * 取末 8 位是为了跨越国家码和长途前缀的差异 ——
     * 「+86 138 0013 8000」「013800138000」「13800138000」末 8 位都是
     * 「00138000」。8 位是个折中：太短会误判（不同号码尾号撞车），
     * 太长又跨不过前缀差异。
     */
    fun normalizeNumber(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        return if (digits.length > 8) digits.takeLast(8) else digits
    }

    fun find(contacts: List<Contact>): List<Group> {
        val groups = mutableListOf<Group>()
        val used = mutableSetOf<Int>()

        // ── 规则 1：号码相同 ──
        val byNumber = mutableMapOf<String, MutableList<Contact>>()
        contacts.forEach { c ->
            c.phoneNumbers.map { normalizeNumber(it.value) }
                .filter { it.length >= 6 } // 太短的号码（分机、短号）不参与判重
                .distinct()
                .forEach { key -> byNumber.getOrPut(key) { mutableListOf() }.add(c) }
        }
        byNumber.values.forEach { list ->
            val distinct = list.distinctBy { it.id }
            if (distinct.size > 1 && distinct.none { it.id in used }) {
                distinct.forEach { used.add(it.id) }
                groups.add(Group(distinct, Reason.SAME_NUMBER))
            }
        }

        // ── 规则 2：姓名相同 ──
        contacts.filter { it.id !in used }
            .groupBy { it.getNameToDisplay().trim() }
            .forEach { (name, list) ->
                if (name.isNotBlank() && list.size > 1) {
                    list.forEach { used.add(it.id) }
                    groups.add(Group(list, Reason.SAME_NAME))
                }
            }

        return groups
    }

    /** 字段完整度。数字越大信息越全。 */
    private fun score(c: Contact): Int =
        c.phoneNumbers.size * 3 +
            c.emails.size * 2 +
            c.addresses.size * 2 +
            c.events.size +
            (if (c.organization.company.isNotBlank()) 2 else 0) +
            (if (c.notes.isNotBlank()) 1 else 0) +
            (if (c.photoUri.isNotBlank()) 3 else 0)

    /**
     * 把一组合并成一条。
     *
     * @return first = 合并后的主记录（要更新），second = 要删掉的其余记录
     */
    fun merge(group: Group): Pair<Contact, List<Contact>> {
        val primary = group.contacts.maxByOrNull { score(it) } ?: group.contacts.first()
        val others = group.contacts.filter { it.id != primary.id }

        // 号码按归一化后的值去重 —— 「138 0013 8000」和「13800138000」
        // 是同一个号，合并后不该出现两条
        val seenNumbers = primary.phoneNumbers.map { normalizeNumber(it.value) }.toMutableSet()
        val mergedPhones = ArrayList<PhoneNumber>(primary.phoneNumbers)
        others.forEach { o ->
            o.phoneNumbers.forEach { p ->
                val key = normalizeNumber(p.value)
                if (seenNumbers.add(key)) mergedPhones.add(p)
            }
        }

        val seenEmails = primary.emails.map { it.value.lowercase() }.toMutableSet()
        val mergedEmails = ArrayList<Email>(primary.emails)
        others.forEach { o ->
            o.emails.forEach { e ->
                if (seenEmails.add(e.value.lowercase())) mergedEmails.add(e)
            }
        }

        primary.phoneNumbers = mergedPhones
        primary.emails = mergedEmails

        // 主记录缺的单值字段，从其它记录里补
        if (primary.organization.company.isBlank()) {
            others.firstOrNull { it.organization.company.isNotBlank() }?.let {
                primary.organization = it.organization
            }
        }
        if (primary.notes.isBlank()) {
            others.firstOrNull { it.notes.isNotBlank() }?.let { primary.notes = it.notes }
        }
        // 任一条被收藏过，合并后就是收藏的
        if (group.contacts.any { it.starred == 1 }) primary.starred = 1

        return primary to others
    }
}
