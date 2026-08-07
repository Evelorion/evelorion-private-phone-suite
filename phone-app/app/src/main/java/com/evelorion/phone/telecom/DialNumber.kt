package com.evelorion.phone.telecom

/** 拨号输入统一规则：允许国际号码，同时把中国手机号转换成不带 +86/86 的 11 位号码。 */
object DialNumber {

    private const val MAX_LENGTH = 32
    private val chinaMobile = Regex("1[3-9]\\d{9}")

    /**
     * 给拨号框使用。过滤空格、括号、横线等粘贴格式，只保留数字、开头的 +、* 和 #。
     * 中文全角数字、＊、＃也会转成电话系统认识的 ASCII 字符。
     */
    fun sanitizeInput(raw: String): String {
        val compact = buildString(raw.length.coerceAtMost(MAX_LENGTH)) {
            raw.forEach { char ->
                if (length >= MAX_LENGTH) return@forEach
                when {
                    char.isDigit() -> append(char.digitToInt())
                    char == '+' && isEmpty() -> append(char)
                    char == '*' || char == '＊' -> append('*')
                    char == '#' || char == '＃' -> append('#')
                }
            }
        }
        return stripChinaMobileCountryCode(compact)
    }

    /** 所有拨号入口最终都经过这里，联系人里保存为 +86 的手机号也不会带区号拨出。 */
    fun forDialing(raw: String): String = stripChinaMobileCountryCode(sanitizeInput(raw))

    private fun stripChinaMobileCountryCode(number: String): String {
        val national = when {
            number.startsWith("+86") -> number.drop(3)
            number.startsWith("0086") -> number.drop(4)
            number.startsWith("86") -> number.drop(2)
            else -> return number
        }
        return if (chinaMobile.matches(national)) national else number
    }
}
