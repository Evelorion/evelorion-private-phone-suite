package com.evelorion.contacts.sync.model

/**
 * 三方合并。和服务端 test/merge.ts 是同一套规则，改一边必须改另一边。
 *
 * 为什么不按时间戳裁决：
 *   手机时钟不可靠（时区、手动改时间、NTP 抖动），而且「谁的时间戳大谁赢」是整条
 *   覆盖 —— 另一台设备刚加的号码会直接消失。三方合并用「本机上次同步成功时的那份
 *   快照」当共同祖先，能准确区分「这一侧改过」和「这一侧只是没变」。
 *
 *   base   = sync_records.base_payload，本机上次同步成功时的快照
 *   local  = 本机当前状态
 *   remote = 服务端退回来的版本
 *
 * 规则（与 git 的三方合并同构）：
 *   某一侧相对 base 没变  → 采用另一侧
 *   两侧都变且结果一样    → 采用该值
 *   两侧都变且不一样      → 确定性裁决，并记进 conflicts 供 UI 提示
 *
 * 两个必须成立的性质，SyncEngineTest 里有对应用例：
 *   幂等     merge(x, x, x) == x
 *   对称     merge(base, l, r) == merge(base, r, l)
 * 否则两台设备会互相推来推去停不下来。
 */
object Merger {

    data class Result(val merged: ContactPayload, val conflicts: List<String>)

    fun merge(base: ContactPayload?, local: ContactPayload, remote: ContactPayload): Result {
        val b = base ?: ContactPayload()
        val conflicts = mutableListOf<String>()

        fun <T> pick(field: String, vb: T, vl: T, vr: T): T = when {
            vl == vr -> vl
            vl == vb -> vr          // 本机没动，采用远端
            vr == vb -> vl          // 远端没动，采用本机
            else -> {
                conflicts.add(field)
                resolveScalar(vl, vr)
            }
        }

        val merged = ContactPayload(
            v = maxOf(local.v, remote.v),
            prefix = pick("prefix", b.prefix, local.prefix, remote.prefix),
            first = pick("first", b.first, local.first, remote.first),
            middle = pick("middle", b.middle, local.middle, remote.middle),
            surname = pick("surname", b.surname, local.surname, remote.surname),
            suffix = pick("suffix", b.suffix, local.suffix, remote.suffix),
            nickname = pick("nickname", b.nickname, local.nickname, remote.nickname),
            company = pick("company", b.company, local.company, remote.company),
            jobTitle = pick("jobTitle", b.jobTitle, local.jobTitle, remote.jobTitle),
            notes = pick("notes", b.notes, local.notes, remote.notes),
            starred = pick("starred", b.starred, local.starred, remote.starred),
            ringtone = pick("ringtone", b.ringtone, local.ringtone, remote.ringtone),
            photo = pick("photo", b.photo, local.photo, remote.photo),
            phones = mergeList("phones", b.phones, local.phones, remote.phones, { it.id }, conflicts, ::mergePhone),
            emails = mergeList("emails", b.emails, local.emails, remote.emails, { it.id }, conflicts, ::mergeLabeled),
            ims = mergeList("ims", b.ims, local.ims, remote.ims, { it.id }, conflicts, ::mergeLabeled),
            addresses = mergeList("addresses", b.addresses, local.addresses, remote.addresses, { it.id }, conflicts, ::mergeAddress),
            events = mergeList("events", b.events, local.events, remote.events, { it.id }, conflicts) { _, l, _ -> l },
            websites = mergeList("websites", b.websites, local.websites, remote.websites, { it.id }, conflicts) { _, l, _ -> l },
            groups = mergeList("groups", b.groups, local.groups, remote.groups, { it.id }, conflicts) { _, l, _ -> l },
        )
        return Result(merged, conflicts.distinct())
    }

    /**
     * 两侧都改过同一个标量时的确定性裁决。
     * 两台设备各自算都必须得到同一个结果，否则会互相覆盖来回推。
     * 先偏向非空值（用户填了东西通常比清空更有意图），再按字符串序取大的。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> resolveScalar(local: T, remote: T): T {
        val emptyLocal = local == null || local == "" || local == 0
        val emptyRemote = remote == null || remote == "" || remote == 0
        if (emptyLocal && !emptyRemote) return remote
        if (emptyRemote && !emptyLocal) return local
        return if (local.toString() >= remote.toString()) local else remote
    }

    /**
     * 列表按 id 做集合级三方。
     * 存在性判定：本机相对 base 有变化就听本机的（增或删），否则听远端的。
     * 这条规则保证「本机删掉的条目不会被远端没动过的旧副本复活」。
     */
    private fun <T : Any> mergeList(
        listName: String,
        base: List<T>,
        local: List<T>,
        remote: List<T>,
        idOf: (T) -> String,
        conflicts: MutableList<String>,
        mergeItem: (T?, T, T) -> T,
    ): List<T> {
        val mb = base.associateBy(idOf)
        val ml = local.associateBy(idOf)
        val mr = remote.associateBy(idOf)
        val ids = LinkedHashSet<String>().apply {
            addAll(mb.keys); addAll(ml.keys); addAll(mr.keys)
        }

        val kept = ArrayList<T>(ids.size)
        for (id in ids) {
            val inBase = mb.containsKey(id)
            val inLocal = ml.containsKey(id)
            val inRemote = mr.containsKey(id)
            val present = if (inLocal != inBase) inLocal else inRemote
            if (!present) continue

            val ib = mb[id]
            val il = ml[id]
            val ir = mr[id]
            val chosen = when {
                il != null && ir != null -> when {
                    il == ir -> il
                    il == ib -> ir
                    ir == ib -> il
                    else -> {
                        conflicts.add("$listName.$id")
                        mergeItem(ib, il, ir)
                    }
                }
                else -> il ?: ir!!
            }
            kept.add(chosen)
        }
        return kept.sortedBy(idOf)
    }

    // 同一条目（同一个号码）的附属字段两边都改了，逐字段再做一次三方。
    // id 一定相同，不参与合并。

    private fun mergePhone(
        b: ContactPayload.PhoneItem?, l: ContactPayload.PhoneItem, r: ContactPayload.PhoneItem,
    ) = ContactPayload.PhoneItem(
        id = l.id,
        value = field(b?.value, l.value, r.value),
        norm = field(b?.norm, l.norm, r.norm),
        type = field(b?.type, l.type, r.type),
        label = field(b?.label, l.label, r.label),
        primary = field(b?.primary, l.primary, r.primary),
    )

    private fun mergeLabeled(
        b: ContactPayload.LabeledItem?, l: ContactPayload.LabeledItem, r: ContactPayload.LabeledItem,
    ) = ContactPayload.LabeledItem(
        id = l.id,
        value = field(b?.value, l.value, r.value),
        type = field(b?.type, l.type, r.type),
        label = field(b?.label, l.label, r.label),
    )

    private fun mergeAddress(
        b: ContactPayload.AddressItem?, l: ContactPayload.AddressItem, r: ContactPayload.AddressItem,
    ) = ContactPayload.AddressItem(
        id = l.id,
        value = field(b?.value, l.value, r.value),
        type = field(b?.type, l.type, r.type),
        label = field(b?.label, l.label, r.label),
        country = field(b?.country, l.country, r.country),
        region = field(b?.region, l.region, r.region),
        city = field(b?.city, l.city, r.city),
        postcode = field(b?.postcode, l.postcode, r.postcode),
        pobox = field(b?.pobox, l.pobox, r.pobox),
        street = field(b?.street, l.street, r.street),
        neighborhood = field(b?.neighborhood, l.neighborhood, r.neighborhood),
    )

    private fun <T> field(vb: T?, vl: T, vr: T): T = when {
        vl == vr -> vl
        vl == vb -> vr
        vr == vb -> vl
        else -> resolveScalar(vl, vr)
    }
}
