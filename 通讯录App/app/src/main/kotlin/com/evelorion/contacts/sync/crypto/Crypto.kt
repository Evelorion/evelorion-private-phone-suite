package com.evelorion.contacts.sync.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 底层密码学原语。
 *
 * 这个文件里的每一个常量和每一步顺序都必须和服务端 test/client.ts 完全一致，
 * 差一个字节两端就解不开对方的数据。改动前先跑 CryptoVectorsTest。
 */
object Crypto {

    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val KEY_BYTES = 32

    /** 密文按 256 字节对齐，抹掉「这个联系人字段多」这种能被服务器观察到的元数据。 */
    const val PAD_BLOCK = 256

    private val secureRandom = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    // ---------------------------------------------------------------- HKDF

    /**
     * RFC 5869 的 HKDF-SHA256。Android 没有内置实现，这里用 HMAC 手写 extract + expand。
     * 只需要 <= 32 字节输出，所以 expand 只跑一轮。
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        require(length in 1..32) { "这里的 HKDF 只支持最多 32 字节输出" }

        val extractMac = Mac.getInstance("HmacSHA256")
        // RFC 5869: salt 为空时用全零的 hashLen 字节
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        extractMac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)

        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        expandMac.update(info.toByteArray(Charsets.UTF_8))
        expandMac.update(0x01)
        val okm = expandMac.doFinal()

        prk.fill(0)
        return okm.copyOf(length)
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    // ---------------------------------------------------------- AES-256-GCM

    /**
     * 返回 nonce ‖ ciphertext ‖ tag。nonce 每次随机生成，
     * 而且每条记录用的是各自派生出来的子密钥，所以不存在 nonce 复用的风险。
     */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "密钥必须是 32 字节" }
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plaintext)
        return nonce + body
    }

    /** 输入必须是 seal 的输出格式。认证失败会抛 AEADBadTagException，调用方不要吞掉。 */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "密钥必须是 32 字节" }
        require(sealed.size >= NONCE_BYTES + TAG_BYTES) { "密文过短" }
        val nonce = sealed.copyOfRange(0, NONCE_BYTES)
        val body = sealed.copyOfRange(NONCE_BYTES, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(body)
    }

    // -------------------------------------------------------------- 填充

    /** ISO/IEC 7816-4：先补一个 0x80，再补 0x00 到 PAD_BLOCK 的整数倍。 */
    fun pad(data: ByteArray): ByteArray {
        val total = (data.size / PAD_BLOCK + 1) * PAD_BLOCK
        val out = ByteArray(total)
        data.copyInto(out)
        out[data.size] = 0x80.toByte()
        return out
    }

    fun unpad(data: ByteArray): ByteArray {
        for (i in data.indices.reversed()) {
            when (data[i]) {
                0x80.toByte() -> return data.copyOfRange(0, i)
                0x00.toByte() -> continue
                else -> throw IllegalArgumentException("填充格式错误")
            }
        }
        throw IllegalArgumentException("填充格式错误")
    }

    // -------------------------------------------------------------- 工具

    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append("0123456789abcdef"[v ushr 4])
            out.append("0123456789abcdef"[v and 0x0f])
        }
        return out.toString()
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "十六进制字符串长度必须是偶数" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    /** 比较不能提前返回，否则会漏出前缀匹配长度。 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** 用完的密钥立刻抹掉。JVM 不保证真的清干净，但能显著缩短它留在堆上的时间。 */
    fun wipe(vararg arrays: ByteArray?) {
        for (a in arrays) a?.fill(0)
    }

    /** "11111111-2222-…" → 16 字节。AAD 和记录密钥派生都要用。 */
    fun uuidToBytes(uuid: String): ByteArray = fromHex(uuid.replace("-", ""))
}
