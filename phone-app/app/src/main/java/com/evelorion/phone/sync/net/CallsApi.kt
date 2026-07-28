package com.evelorion.phone.sync.net

import com.evelorion.phone.bridge.VaultBridge
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 通话记录的同步接口。
 *
 * ── 为什么不直接抄通讯录那个 SyncApi ────────────────────────
 *
 * 形状不一样。通讯录持有刷新令牌，401 时自己换一张新的访问令牌。
 * 电话 App **没有刷新令牌** —— 桥只给短期访问令牌，这是刻意的：
 * 它不该能长期独占账号访问权。
 *
 * 所以这里 401 的处理是「回头再向通讯录要一张新的」，
 * 而那要求用户的保险库当时是解锁的。语义完全不同，硬套会写出
 * 一个永远刷新失败的死循环。
 *
 * ── collection ─────────────────────────────────────────────
 *
 * 所有请求都带 collection=calls。不带的话服务端默认走 contacts，
 * 通话记录就会和联系人混在同一个流里 —— 而两者用的是不同的密钥，
 * 结果是两边都拿到自己解不开的密文。
 */
class CallsApi(
    private val baseUrl: String,
    /** 令牌过期时回头要一张新的。返回空串表示要不到（保险库锁着）。 */
    private val tokenProvider: () -> String,
) {

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val TIMEOUT_SECONDS = 30L
        const val COLLECTION = "calls"
    }

    class HttpFailure(val status: Int, val code: String, override val message: String) : IOException(message)
    class AuthExpired(message: String) : IOException(message)

    private var token: String = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun getChanges(since: Long, limit: Int = 500): JSONObject =
        request("GET", "/v1/sync/changes?since=$since&limit=$limit&collection=$COLLECTION", null)

    fun push(changes: JSONArray): JSONObject = request(
        "POST", "/v1/sync/push",
        JSONObject().put("collection", COLLECTION).put("changes", changes),
    )

    fun status(): JSONObject = request("GET", "/v1/sync/status", null)

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        allowRetry: Boolean = true,
    ): JSONObject {
        if (token.isBlank()) token = tokenProvider()
        if (token.isBlank()) throw AuthExpired("拿不到同步凭据，请先在通讯录里解锁保险库")

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .addHeader("Authorization", "Bearer $token")
            .apply {
                when (method) {
                    "GET" -> get()
                    "POST" -> post((body ?: JSONObject()).toString().toRequestBody(JSON))
                    else -> throw IllegalArgumentException("不支持的方法 $method")
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()

            // 401 只可能是令牌过期。重新向通讯录要一张，**只重试一次** ——
            // 不限次数的话，保险库锁着时会变成一个安静的死循环。
            if (response.code == 401 && allowRetry) {
                token = tokenProvider()
                if (token.isBlank()) throw AuthExpired("同步凭据已过期，请在通讯录里解锁一次")
                return request(method, path, body, allowRetry = false)
            }

            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                throw HttpFailure(
                    response.code,
                    json?.optString("code").orEmpty(),
                    json?.optString("message").orEmpty().ifEmpty { "HTTP ${response.code}" },
                )
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}
