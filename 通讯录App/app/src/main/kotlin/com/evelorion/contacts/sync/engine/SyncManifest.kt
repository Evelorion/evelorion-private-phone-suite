package com.evelorion.contacts.sync.engine

import com.evelorion.contacts.sync.crypto.Crypto

/**
 * 同步清单：一份由客户端自己加密、列出「这个账号下应该有哪些记录」的目录。
 *
 * ── 它解决的问题 ──────────────────────────────────────────────
 *
 * 端到端加密保证服务器读不懂内容，也改不了内容（AEAD 标签会发现），
 * 但**挡不住服务器少给你东西**：
 *
 *   · 隐藏一次更新 —— 你本地还是旧版本，不知道有新的
 *   · 隐藏一整条记录 —— 换新手机恢复时，你根本不知道少了谁
 *   · 只回滚某一条 —— AAD 里的 rev 能挡住「拿旧密文冒充新 rev」，
 *     但挡不住「连 rev 一起退回旧的那一对」
 *
 * 这类攻击对纯密文同步来说是个真实缺口。DESIGN.md 7.4 里之前记着「没有实现」，
 * 这个文件就是把它补上。
 *
 * ── 怎么做到的 ────────────────────────────────────────────────
 *
 * 清单本身就是一条普通记录，uuid 固定，内容是 `{uuid: rev}` 的全量映射。
 * 它和其它记录一样被客户端加密并带 AEAD 标签，所以服务器：
 *
 *   · 改不了它（标签会失败）
 *   · 伪造不了它（没有密钥）
 *   · 隐藏它？客户端本地记着清单自己的 rev，藏起来立刻会被发现
 *
 * 于是「服务器说这就是全部」变成了「我自己上次写下的目录说应该有这些」，
 * 信任基础从服务器挪回了客户端。
 *
 * ── 编码 ─────────────────────────────────────────────────────
 *
 * 为了省空间用紧凑二进制而不是 JSON：
 *
 *   魔数(4) ‖ 版本(1) ‖ 条目数(4, 大端) ‖ [uuid 原始 16 字节 ‖ rev 4 字节] × N
 *
 * 每条 20 字节。1000 个联系人约 20 KB，还在服务端 64 KB 的单条上限内。
 * 超过约 3200 条时会顶到上限 —— [MAX_ENTRIES] 就是这个界，
 * 到了会明确报错而不是静默截断（截断等于把保护关掉了，最糟的失败方式）。
 */
object SyncManifest {

    /** 清单记录的固定 uuid。用固定值是为了让客户端总能定位它，藏不住。 */
    const val MANIFEST_UUID = "00000000-0000-4000-8000-000000000001"

    private const val MAGIC = 0x4653594d // "FSYM"
    private const val VERSION = 1
    private const val ENTRY_BYTES = 20

    /**
     * 单份清单能装下的最大条目数。
     * 留了余量：填充到 256 字节块 + GCM 标签之后仍要低于服务端的 65536 上限。
     */
    const val MAX_ENTRIES = 3200

    class TooManyRecords(val count: Int) : Exception(
        "联系人数量 $count 超过了单份同步清单能记录的上限 $MAX_ENTRIES，" +
            "防篡改校验会失效。需要把清单拆成多份，见 SyncManifest 的注释"
    )

    /** 清单校验发现的问题。每一条都意味着服务器给的数据不完整或被回退过。 */
    sealed class Issue {
        /** 清单里有这条，但本地和服务端都没给。 */
        data class Missing(val uuid: String, val expectedRev: Int) : Issue()

        /** 服务端给的版本比清单记录的旧，说明这条被回滚了。 */
        data class Rollback(val uuid: String, val expectedRev: Int, val actualRev: Int) : Issue()

        /** 清单自己的版本比本地记着的旧，说明整份清单被回滚了。 */
        data class ManifestRollback(val expectedRev: Int, val actualRev: Int) : Issue()

        /** 服务端根本没返回清单，但本地知道它存在过。 */
        data class ManifestMissing(val expectedRev: Int) : Issue()

        fun describe(): String = when (this) {
            is Missing -> "服务器没有返回联系人 ${uuid.take(8)}（清单里记着 rev=$expectedRev）"
            is Rollback -> "联系人 ${uuid.take(8)} 的版本被退回了（应为 $expectedRev，实际 $actualRev）"
            is ManifestRollback -> "同步清单被退回了（应为 rev=$expectedRev，实际 $actualRev）"
            is ManifestMissing -> "服务器没有返回同步清单（本地记着 rev=$expectedRev）"
        }
    }

    // ---------------------------------------------------------------- 编解码

    fun encode(entries: Map<String, Int>): String {
        if (entries.size > MAX_ENTRIES) throw TooManyRecords(entries.size)

        val out = ByteArray(9 + entries.size * ENTRY_BYTES)
        writeInt(out, 0, MAGIC)
        out[4] = VERSION.toByte()
        writeInt(out, 5, entries.size)

        var offset = 9
        // 按 uuid 排序，保证同一份内容永远编码成同样的字节 ——
        // 否则每次同步都会因为顺序不同而产生"有改动"的假象，白白多推一轮
        for ((uuid, rev) in entries.toSortedMap()) {
            Crypto.uuidToBytes(uuid).copyInto(out, offset)
            writeInt(out, offset + 16, rev)
            offset += ENTRY_BYTES
        }
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    fun decode(payload: String): Map<String, Int> {
        val bytes = try {
            android.util.Base64.decode(payload, android.util.Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("同步清单不是合法的 base64")
        }
        if (bytes.size < 9) throw IllegalArgumentException("同步清单过短")
        if (readInt(bytes, 0) != MAGIC) throw IllegalArgumentException("同步清单魔数不对")
        if (bytes[4].toInt() != VERSION) {
            throw IllegalArgumentException("同步清单版本 ${bytes[4]} 不认识，可能来自更新的 App")
        }

        val count = readInt(bytes, 5)
        if (count < 0 || 9 + count * ENTRY_BYTES != bytes.size) {
            throw IllegalArgumentException("同步清单长度和条目数对不上")
        }

        val out = HashMap<String, Int>(count)
        var offset = 9
        repeat(count) {
            val hex = Crypto.toHex(bytes.copyOfRange(offset, offset + 16))
            val uuid = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
            out[uuid] = readInt(bytes, offset + 16)
            offset += ENTRY_BYTES
        }
        return out
    }

    // ---------------------------------------------------------------- 校验

    /**
     * 拿服务端这一轮给出的东西，和清单对一遍。
     *
     * @param manifest        解出来的清单内容
     * @param manifestRev     服务端给的清单 rev
     * @param lastKnownRev    本地记着的清单 rev（0 表示还没见过）
     * @param presentRevs     本地现在实际持有的 uuid → rev
     */
    fun verify(
        manifest: Map<String, Int>,
        manifestRev: Int,
        lastKnownRev: Int,
        presentRevs: Map<String, Int>,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (manifestRev < lastKnownRev) {
            issues.add(Issue.ManifestRollback(lastKnownRev, manifestRev))
        }

        for ((uuid, expectedRev) in manifest) {
            val actual = presentRevs[uuid]
            when {
                actual == null -> issues.add(Issue.Missing(uuid, expectedRev))
                actual < expectedRev -> issues.add(Issue.Rollback(uuid, expectedRev, actual))
            }
        }
        return issues
    }

    /**
     * 服务端一条清单都没返回时的判断。
     *
     * 第一次同步（lastKnownRev == 0）时没有清单是正常的，不算问题。
     * 之前见过却突然没了，那就是被藏起来了。
     */
    fun verifyAbsence(lastKnownRev: Int): List<Issue> =
        if (lastKnownRev > 0) listOf(Issue.ManifestMissing(lastKnownRev)) else emptyList()

    // ---------------------------------------------------------------- 工具

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun readInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xff) shl 24) or
            ((buf[offset + 1].toInt() and 0xff) shl 16) or
            ((buf[offset + 2].toInt() and 0xff) shl 8) or
            (buf[offset + 3].toInt() and 0xff)
}
