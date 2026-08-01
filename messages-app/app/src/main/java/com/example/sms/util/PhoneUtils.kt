package com.example.sms.util

/** 号码规范化：去掉空格/横线/括号，统一 +86 前缀 */
object PhoneUtils {

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        // 短号（如 95533、10086）与字母发件人原样保留
        if (trimmed.any { it.isLetter() }) return trimmed
        val digits = trimmed.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+86") -> digits.removePrefix("+86")
            digits.startsWith("0086") -> digits.removePrefix("0086")
            else -> digits
        }
    }

    fun sameNumber(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return true
        // 末 8 位相同视为同一号码（兼容区号差异）
        return na.length >= 8 && nb.length >= 8 && na.takeLast(8) == nb.takeLast(8)
    }

    fun format(raw: String): String {
        val n = normalize(raw)
        return if (n.length == 11 && n.all { it.isDigit() }) {
            "${n.substring(0, 3)} ${n.substring(3, 7)} ${n.substring(7)}"
        } else raw
    }

    fun isLikelyPhone(text: String): Boolean {
        val digits = text.filter { it.isDigit() }
        return digits.length in 3..20 && text.all { it.isDigit() || it in "+-() " }
    }

    /** 多收件人以 ";" 存储 */
    fun joinAddresses(list: List<String>): String = list.map { normalize(it) }.distinct().joinToString(";")

    fun splitAddresses(joined: String): List<String> =
        joined.split(";").map { it.trim() }.filter { it.isNotEmpty() }
}
