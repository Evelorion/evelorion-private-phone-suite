package com.evelorion.contacts.data

import android.os.Build
import java.nio.charset.Charset

/**
 * 汉字 → 拼音首字母。
 *
 * ── 为什么不直接引一个拼音库 ────────────────────────────────
 *
 * 常见的拼音库（pinyin4j 之类）会带一张几百 KB 的全量字表。
 * 通讯录只需要**首字母**用来分组，不需要完整拼音，所以这里用两条路：
 *
 *   Android 10+ → 系统自带的 ICU Transliterator，最准，还能处理生僻字
 *   Android 10- → GB2312 区位码边界表，几十行代码，覆盖常用字
 *
 * ── GB2312 那条路的原理 ─────────────────────────────────────
 *
 * GB2312 里 3755 个一级汉字**本身就是按拼音排的**。所以只要知道每个
 * 字母段的起始区位码，二分一下就知道某个字属于哪个字母。
 * 这是个几十年的老办法，缺点是二级汉字（生僻字）不在这个序列里，
 * 会落到「#」。
 *
 * ── 多音字 ──────────────────────────────────────────────────
 *
 * 姓氏里的多音字必须单独处理。「单」在姓氏里念 shàn 不是 dān，
 * 「曾」念 zēng 不是 céng —— 按通用读音排的话，这些姓会被排到错的字母下，
 * 用户翻不到人。下面 [SURNAME_OVERRIDES] 收的就是这类。
 */
object Pinyin {

    /**
     * 姓氏多音字。key 是汉字，value 是**姓氏读音**的首字母。
     *
     * 只收姓氏 —— 名字里的多音字不影响分组（分组只看第一个字）。
     */
    private val SURNAME_OVERRIDES = mapOf(
        '单' to 'S', // shàn，不是 dān
        '曾' to 'Z', // zēng，不是 céng
        '解' to 'X', // xiè，不是 jiě
        '查' to 'Z', // zhā，不是 chá
        '区' to 'O', // ōu，不是 qū
        '仇' to 'Q', // qiú，不是 chóu
        '朴' to 'P', // piáo，不是 pǔ
        '缪' to 'M', // miào，不是 móu
        '重' to 'C', // chóng，不是 zhòng
        '乐' to 'Y', // yuè，不是 lè
        '尉' to 'Y', // yù（尉迟），不是 wèi
        '折' to 'S', // shé，不是 zhé
        '员' to 'Y', // yùn，不是 yuán
        '种' to 'C', // chóng，不是 zhǒng
        '任' to 'R', // rén，不是 rèn
        '燕' to 'Y', // yān，不是 yàn
        '华' to 'H', // huà，不是 huá
        '过' to 'G',
        '相' to 'X',
        '牟' to 'M',
    )

    /** GB2312 每个字母段的起始区位码。和 [LETTERS] 一一对应。 */
    private val GB_BOUNDARIES = intArrayOf(
        1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787, 3106,
        3212, 3472, 3635, 3722, 3730, 3858, 4027, 4086, 4390, 4558,
        4684, 4925, 5249, 5590,
    )

    /** 注意没有 I、U、V —— 拼音里没有以这三个字母开头的音节。 */
    private const val LETTERS = "ABCDEFGHJKLMNOPQRSTWXYZ"

    private val gbk: Charset? = runCatching { Charset.forName("GBK") }.getOrNull()

    /**
     * ICU 的转写器。构造有点贵（几十毫秒），所以只做一次。
     *
     * "Han-Latin/Names" 这个变体是专门给人名用的，对姓氏多音字的处理
     * 比通用的 "Han-Latin" 好，但也不是全对 —— 所以 [SURNAME_OVERRIDES]
     * 仍然优先。
     */
    private val transliterator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                android.icu.text.Transliterator.getInstance("Han-Latin/Names; Latin-ASCII")
            }.getOrNull()
        } else {
            null
        }
    }

    /**
     * 取姓名的分组字母。
     *
     * @return 'A'..'Z' 或 '#'（数字、符号、无法识别的字）
     */
    fun initialOf(name: String?): Char {
        val c = name?.trim()?.firstOrNull() ?: return '#'

        if (c in 'A'..'Z') return c
        if (c in 'a'..'z') return c.uppercaseChar()
        if (!isHan(c)) return '#'

        SURNAME_OVERRIDES[c]?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            transliterator?.let { t ->
                val latin = t.transliterate(c.toString()).trim()
                val first = latin.firstOrNull()?.uppercaseChar()
                if (first != null && first in 'A'..'Z') return first
            }
        }

        return gbInitial(c)
    }

    private fun isHan(c: Char) = c.code in 0x4E00..0x9FFF

    /** GB2312 区位码二分。落在表外（二级汉字、生僻字）返回 '#'。 */
    private fun gbInitial(c: Char): Char {
        val charset = gbk ?: return '#'
        val bytes = runCatching { c.toString().toByteArray(charset) }.getOrNull() ?: return '#'
        if (bytes.size != 2) return '#'

        val b1 = bytes[0].toInt() and 0xFF
        val b2 = bytes[1].toInt() and 0xFF
        // 区位码 = (第一字节 - 160) * 100 + (第二字节 - 160)
        val code = (b1 - 160) * 100 + (b2 - 160)
        if (code < GB_BOUNDARIES.first() || code > 5900) return '#'

        // 找最后一个 <= code 的边界
        var lo = 0
        var hi = GB_BOUNDARIES.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (GB_BOUNDARIES[mid] <= code) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (found >= 0) LETTERS[found] else '#'
    }
}
