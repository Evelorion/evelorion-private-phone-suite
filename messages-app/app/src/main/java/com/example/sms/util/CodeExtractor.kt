package com.example.sms.util

/** 从短信正文里抽取验证码 / 取件码，供搜索页「从信息中提取」卡片和通知快捷复制使用 */
object CodeExtractor {

    data class Extracted(
        val code: String,
        val label: String,
        val context: String,
    )

    private val pickupPatterns = listOf(
        Regex("""取件码[^0-9A-Za-z]{0,6}([0-9A-Za-z\-]{3,12})"""),
        Regex("""提货码[^0-9A-Za-z]{0,6}([0-9A-Za-z\-]{3,12})"""),
        Regex("""凭[^0-9A-Za-z]{0,4}([0-9\-]{4,12})[^0-9]{0,4}取件"""),
    )

    private val verifyPatterns = listOf(
        Regex("""验证码[^0-9A-Za-z]{0,6}([0-9A-Za-z]{4,8})"""),
        Regex("""动态码[^0-9A-Za-z]{0,6}([0-9A-Za-z]{4,8})"""),
        Regex("""校验码[^0-9A-Za-z]{0,6}([0-9A-Za-z]{4,8})"""),
        Regex("""([0-9]{4,8})\s*(?:是您的|为您的|为你的)"""),
        Regex("""(?:code|Code|CODE)[^0-9A-Za-z]{0,4}([0-9A-Za-z]{4,8})"""),
    )

    fun extract(body: String): Extracted? {
        for (p in pickupPatterns) {
            p.find(body)?.let { m ->
                return Extracted(m.groupValues[1], "取件码", contextAround(body))
            }
        }
        for (p in verifyPatterns) {
            p.find(body)?.let { m ->
                return Extracted(m.groupValues[1], "验证码", contextAround(body))
            }
        }
        return null
    }

    /** 取一句最能说明用途/期限的上下文 */
    private fun contextAround(body: String): String {
        val parts = body.split("，", "。", ",", ".", "；", ";").map { it.trim() }.filter { it.isNotEmpty() }
        val hint = parts.firstOrNull { s ->
            listOf("前", "内", "小时", "分钟", "有效", "柜", "号", "营业", "今日").any { s.contains(it) }
        }
        return hint ?: parts.firstOrNull().orEmpty()
    }
}
