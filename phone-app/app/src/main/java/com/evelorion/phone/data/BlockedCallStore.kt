package com.evelorion.phone.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 一次「拦下的来电」。 */
data class BlockedCall(
    val number: String,
    val at: Long,
    /** 来电落在哪张卡上，例如「卡 2 · 中国联通」。取不到时为空串。 */
    val simLabel: String,
)

/** 某个号码的拦截统计。 */
data class BlockedCallStats(
    val count: Int,
    val lastAt: Long,
    val lastSimLabel: String,
) {
    val hasHistory: Boolean get() = count > 0
}

/**
 * 被拦截号码的来电记录。
 *
 * ── 为什么要单独存一份 ──────────────────────────────────────
 *
 * CallScreeningService 把来电拒掉之后，系统界面上只会留下一条「已拦截」，
 * 不告诉你它是什么时候打的、落在哪张卡上。而「这个号今天又打了几次」
 * 恰恰是用户拉黑之后最想知道的事。
 *
 * 记录和拦截名单一样只留在本机，用 Android Keystore 的 AES-GCM 加密；
 * 不写文件、不上传，最多保留 [MAX_RECORDS] 条，防止长期使用后无限增长。
 */
object BlockedCallStore {

    private const val PREFS = "blocked_call_log"
    private const val CIPHERTEXT = "ciphertext"
    private const val IV = "iv"
    private const val KEY_ALIAS = "evelorion_blocked_calls_v1"
    private const val MAX_RECORDS = 400

    /** 记一次被拦截的来电。调用方已经确认号码在黑名单里。 */
    @Synchronized
    fun record(context: Context, number: String, simLabel: String) {
        val trimmed = number.trim()
        if (trimmed.isBlank()) return
        val entries = read(context).toMutableList()
        entries.add(
            BlockedCall(
                number = trimmed,
                at = System.currentTimeMillis(),
                simLabel = simLabel.trim(),
            )
        )
        // 新的在前；超量时丢最旧的。
        val trimmedList = entries.sortedByDescending { it.at }.take(MAX_RECORDS)
        write(context, trimmedList)
    }

    /** 全部记录，最近的在最前。 */
    @Synchronized
    fun recent(context: Context, limit: Int = MAX_RECORDS): List<BlockedCall> =
        read(context).sortedByDescending { it.at }.take(limit)

    /**
     * 一次性算出所有号码的统计，键是归一化后的号码。
     *
     * 拦截列表可能有几十条，逐条调 [stats] 会把整份加密记录解密几十遍；
     * 列表页只调用这里一次。
     */
    @Synchronized
    fun statsByNormalized(context: Context): Map<String, BlockedCallStats> =
        read(context)
            .groupBy { normalize(it.number) }
            .mapValues { (_, entries) ->
                val last = entries.maxByOrNull { it.at }!!
                BlockedCallStats(entries.size, last.at, last.simLabel)
            }

    /** 某个号码打过几次、最后一次是什么时候。号码比较用 [normalize]。 */
    @Synchronized
    fun stats(context: Context, number: String): BlockedCallStats {
        val key = normalize(number)
        if (key.isBlank()) return BlockedCallStats(0, 0L, "")
        return statsByNormalized(context)[key] ?: BlockedCallStats(0, 0L, "")
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * 归一化：只留数字，并去掉 +86/86 前缀。
     *
     * 系统给的号码有时带国家码、有时带空格横线，同一个号会变成两种写法；
     * 不归一化的话「打过几次」会被拆成两份统计。
     */
    fun normalize(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        return when {
            digits.length == 13 && digits.startsWith("86") -> digits.drop(2)
            digits.length == 15 && digits.startsWith("0086") -> digits.drop(4)
            else -> digits
        }
    }

    private fun read(context: Context): List<BlockedCall> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encodedCiphertext = prefs.getString(CIPHERTEXT, null) ?: return emptyList()
        val encodedIv = prefs.getString(IV, null) ?: return emptyList()
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            val json = String(
                cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)),
                StandardCharsets.UTF_8,
            )
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        BlockedCall(
                            number = item.optString("number"),
                            at = item.optLong("at"),
                            simLabel = item.optString("simLabel"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, entries: List<BlockedCall>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("number", entry.number)
                    .put("at", entry.at)
                    .put("simLabel", entry.simLabel)
            )
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(array.toString().toByteArray(StandardCharsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}

/** 「3 分钟前」这种相对时间。超过一个月就显示日期。 */
fun relativeTimeText(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0L) return ""
    val diff = (now - at).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 30L * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> absoluteTimeText(at)
    }
}

/** 「11-07 04:52」。用来给相对时间补一个精确落点。 */
fun absoluteTimeText(at: Long): String {
    if (at <= 0L) return ""
    return runCatching {
        Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault("")
}
