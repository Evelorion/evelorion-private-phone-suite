package com.evelorion.contacts.sync.crypto

/**
 * 恢复码：32 字节随机密钥 → 52 个 Crockford Base32 字符 + 4 个校验字符，
 * 显示成 14 组 4 字符，例如
 *
 *   810M-4GT4-8N34-EJ29-995M-RKAE-9X85-2MJK-AHAN-CNTR-B5D5-PQ2X-BSFG-S8N4
 *
 * 用 Crockford 变体是因为它去掉了 I、L、O、U —— 抄在纸上时 1/I/l 和 0/O 最容易搞混。
 * 解析时会把这些误输入自动纠正回来，末尾 4 个字符负责挡下真正的输错。
 */
object RecoveryCode {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val PAYLOAD_CHARS = 52   // ceil(256 bits / 5)
    private const val CHECK_CHARS = 4
    private const val TOTAL_CHARS = PAYLOAD_CHARS + CHECK_CHARS

    fun format(recoveryKey: ByteArray): String {
        require(recoveryKey.size == 32) { "恢复密钥必须是 32 字节" }
        val payload = base32Encode(recoveryKey)
        val check = base32Encode(Crypto.sha256(recoveryKey)).substring(0, CHECK_CHARS)
        return (payload + check).chunked(4).joinToString("-")
    }

    /**
     * 容错解析：忽略大小写、空格、连字符，并把 I/L→1、O→0、U→V 纠正回来。
     * 校验位不匹配时抛异常，而不是返回一把错误的密钥 —— 后者会让用户看到
     * 「解密失败」这种毫无头绪的报错。
     */
    fun parse(input: String): ByteArray {
        val cleaned = buildString {
            for (ch in input.uppercase()) {
                when (ch) {
                    ' ', '-', '\t', '\n' -> {}
                    'I', 'L' -> append('1')
                    'O' -> append('0')
                    'U' -> append('V')
                    else -> append(ch)
                }
            }
        }
        if (cleaned.length != TOTAL_CHARS) {
            throw RecoveryCodeException("恢复码应该是 $TOTAL_CHARS 个字符，实际收到 ${cleaned.length} 个")
        }
        val key = base32Decode(cleaned.substring(0, PAYLOAD_CHARS)).copyOf(32)
        val expected = base32Encode(Crypto.sha256(key)).substring(0, CHECK_CHARS)
        if (cleaned.substring(PAYLOAD_CHARS) != expected) {
            throw RecoveryCodeException("恢复码校验失败，请检查是否有输错的字符")
        }
        return key
    }

    /** 输入过程中实时判断能不能提交，用来控制「下一步」按钮的可用状态。 */
    fun isValid(input: String): Boolean = try {
        parse(input); true
    } catch (_: RecoveryCodeException) {
        false
    }

    private fun base32Encode(data: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    private fun base32Decode(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length * 5 / 8 + 1)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val index = ALPHABET.indexOf(ch)
            if (index < 0) throw RecoveryCodeException("恢复码含有非法字符「$ch」")
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                out.add(((buffer ushr (bits - 8)) and 0xff).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    class RecoveryCodeException(message: String) : IllegalArgumentException(message)
}
