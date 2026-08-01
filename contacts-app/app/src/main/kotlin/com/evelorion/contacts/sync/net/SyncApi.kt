package com.evelorion.contacts.sync.net

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 同步服务端的 HTTP 客户端。
 *
 * 用手写的 OkHttp 调用而不是 Retrofit，是为了少引一层依赖 —— 这个 App 是
 * 要长期自己维护的，端点就这么十来个，多一个注解处理器不划算。
 *
 * 401 的处理集中在这里：拿刷新令牌换一次新的访问令牌，只重试一次。
 * 再失败就抛 AuthExpired，由上层提示用户重新登录，绝不静默吞掉。
 */
class SyncApi(
    private val baseUrl: String,
    private val session: SessionStore,
) {

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val TIMEOUT_SECONDS = 30L
        private val REFRESH_LOCK = Any()
    }

    class HttpFailure(val status: Int, val code: String, override val message: String) : IOException(message)
    class AuthExpired(message: String) : IOException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------ 账号

    fun getKdfParams(username: String): JSONObject =
        request("GET", "/v1/account/kdf?username=${enc(username)}", null, authenticated = false)

    /**
     * 用恢复码登录。
     *
     * 忘了主口令时走这条路。响应结构和普通登录一样，额外带
     * mustResetPassphrase —— 走到这里说明用户已经不知道口令了，
     * 让他继续用一个自己不知道的口令没有意义。
     */
    fun loginWithRecovery(
        username: String,
        recoveryAuthSecret: String,
        deviceName: String,
    ): JSONObject = request(
        "POST", "/v1/session/recovery",
        JSONObject().apply {
            put("username", username)
            put("recoveryAuthSecret", recoveryAuthSecret)
            put("deviceName", deviceName)
            put("directLogin", true)
        },
        authenticated = false,
    )

    fun register(
        username: String,
        authSecret: String,
        /** 恢复码派生的登录凭据。服务器存哈希，让恢复码也能当登录方式。 */
        recoveryAuthSecret: String,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        dekWrapPassword: ByteArray,
        dekWrapRecovery: ByteArray,
        deviceName: String,
        registrationToken: String,
    ): JSONObject = request(
        "POST", "/v1/account/register",
        JSONObject().apply {
            put("registrationToken", registrationToken)
            put("username", username)
            put("authSecret", authSecret)
            put("recoveryAuthSecret", recoveryAuthSecret)
            put("kdf", JSONObject().apply {
                put("salt", b64(salt))
                put("memoryKiB", memoryKiB)
                put("iterations", iterations)
                put("parallelism", parallelism)
            })
            put("dekWrapPassword", b64(dekWrapPassword))
            put("dekWrapRecovery", b64(dekWrapRecovery))
            put("deviceName", deviceName)
        },
        authenticated = false,
    )

    fun login(username: String, authSecret: String, deviceName: String): JSONObject = request(
        "POST", "/v1/session",
        JSONObject().apply {
            put("username", username)
            put("authSecret", authSecret)
            put("deviceName", deviceName)
        },
        authenticated = false,
    )

    fun completeMfa(mfaToken: String, code: String): JSONObject = request(
        "POST", "/v1/session/mfa/complete",
        JSONObject().apply {
            put("mfaToken", mfaToken)
            val normalized = code.trim().replace(" ", "")
            if (normalized.matches(Regex("\\d{6}"))) {
                put("totpCode", normalized)
            } else {
                put("backupCode", normalized)
            }
        },
        authenticated = false,
    )

    fun getMfaOptions(mfaToken: String): JSONObject = request(
        "POST", "/v1/session/mfa/options",
        JSONObject().put("mfaToken", mfaToken),
        authenticated = false,
    ).getJSONObject("options")

    fun completeMfa(
        mfaToken: String,
        passkeyResponse: JSONObject,
        code: String? = null,
    ): JSONObject = request(
        "POST", "/v1/session/mfa/complete",
        JSONObject().apply {
            put("mfaToken", mfaToken)
            put("passkey", passkeyResponse)
            if (!code.isNullOrBlank()) {
                val normalized = code.trim().replace(" ", "")
                if (normalized.matches(Regex("\\d{6}"))) {
                    put("totpCode", normalized)
                } else {
                    put("backupCode", normalized)
                }
            }
        },
        authenticated = false,
    )

    fun logout(): JSONObject = request("POST", "/v1/session/logout", JSONObject())

    fun getVault(): JSONObject = request("GET", "/v1/vault", null)

    fun enablePrivateKeyLogin(currentAuthSecret: String, privateKeyAuthSecret: String): JSONObject = request(
        "POST", "/v1/vault/private-key-login/enable",
        JSONObject().apply {
            put("currentAuthSecret", currentAuthSecret)
            put("privateKeyAuthSecret", privateKeyAuthSecret)
        },
    )

    fun rewrapVault(
        currentAuthSecret: String,
        newAuthSecret: String,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        dekWrapPassword: ByteArray,
        dekWrapRecovery: ByteArray,
        recoveryAuthSecret: String,
    ): JSONObject = request(
        "POST", "/v1/vault/rewrap",
        JSONObject().apply {
            put("currentAuthSecret", currentAuthSecret)
            put("newAuthSecret", newAuthSecret)
            put("kdf", JSONObject().apply {
                put("salt", b64(salt))
                put("memoryKiB", memoryKiB)
                put("iterations", iterations)
                put("parallelism", parallelism)
            })
            put("dekWrapPassword", b64(dekWrapPassword))
            put("dekWrapRecovery", b64(dekWrapRecovery))
            put("recoveryAuthSecret", recoveryAuthSecret)
        },
    )

    fun listDevices(): JSONObject = request("GET", "/v1/devices", null)

    fun revokeDevice(deviceId: String): JSONObject = request("DELETE", "/v1/devices/${enc(deviceId)}", null)

    // ------------------------------------------------------------ 同步

    fun getChanges(since: Long, limit: Int = 500): JSONObject =
        request("GET", "/v1/sync/changes?since=$since&limit=$limit", null)

    /** changes 里每项：uuid / baseRev / deleted / schemaVer / nonce / ciphertext。 */
    fun push(changes: JSONArray): JSONObject =
        request("POST", "/v1/sync/push", JSONObject().put("changes", changes))

    fun status(): JSONObject = request("GET", "/v1/sync/status", null)

    // ------------------------------------------------------------ 头像

    fun putBlob(hash: String, nonce: ByteArray, ciphertext: ByteArray): JSONObject = request(
        "PUT", "/v1/blobs/$hash",
        JSONObject().put("nonce", b64(nonce)).put("ciphertext", b64(ciphertext)),
    )

    fun getBlob(hash: String): JSONObject = request("GET", "/v1/blobs/$hash", null)

    fun missingBlobs(hashes: List<String>): JSONObject =
        request("POST", "/v1/blobs/missing", JSONObject().put("hashes", JSONArray(hashes)))

    // ------------------------------------------------------------ 内部

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        authenticated: Boolean = true,
        allowRefresh: Boolean = true,
    ): JSONObject {
        val builder = Request.Builder().url(baseUrl.trimEnd('/') + path)
        val requestBody = body?.toString()?.toRequestBody(JSON)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, requestBody ?: "{}".toRequestBody(JSON))
        }
        val requestAccessToken = if (authenticated) {
            val token = session.accessToken
                ?: throw AuthExpired("本机还没有登录同步账号")
            builder.header("Authorization", "Bearer $token")
            token
        } else null

        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }

            if (response.isSuccessful) return json

            val code = json.optString("error", "http_${response.code}")
            val message = json.optString("message", "HTTP ${response.code}")

            // 访问令牌过期是常态（15 分钟一换），静默续一次就好
            if (response.code == 401 && authenticated && allowRefresh && code != "device_revoked") {
                if (refreshTokens(requestAccessToken.orEmpty())) {
                    return request(method, path, body, authenticated, allowRefresh = false)
                }
            }
            // 401 只在**已认证的请求**上才意味着「登录过期」。
            //
            // 登录 / 注册 / 刷新这些请求本身是 unauthenticated 的，它们返回 401
            // 说明的是「用户名或口令不对」——之前这里不分青红皂白全转成
            // AuthExpired，导致口令输错时提示「登录已过期，请重新登录」，
            // 用户照着提示重新登录一遍，还是同样的错，完全找不到北。
            if (response.code == 401 && authenticated) {
                session.clearTokens()
                throw AuthExpired(message)
            }
            throw HttpFailure(response.code, code, message)
        }
    }

    /**
     * 刷新令牌是一次性的，服务端会轮换。
     * 如果服务端报 refresh_token_reuse，说明令牌可能已经泄露，设备已被吊销，
     * 这里不再重试，直接让用户重新登录。
     */
    private fun refreshTokens(failedAccessToken: String): Boolean = synchronized(REFRESH_LOCK) {
        // 另一个同步请求可能已经轮换过一次性刷新令牌。直接使用它刚保存的
        // access token，不能拿旧 refresh token 再换一次，否则服务端会按重放攻击吊销设备。
        val currentAccessToken = session.accessToken
        if (!currentAccessToken.isNullOrEmpty() && currentAccessToken != failedAccessToken) {
            return@synchronized true
        }

        val refresh = session.refreshToken ?: return@synchronized false
        try {
            val json = request(
                "POST", "/v1/session/refresh",
                JSONObject().put("refreshToken", refresh),
                authenticated = false,
                allowRefresh = false,
            )
            session.saveTokens(
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                accessExpiresAt = json.optLong("accessExpiresAt", 0),
            )
            true
        } catch (e: HttpFailure) {
            // 只有服务器明确拒绝刷新凭据，才把本机标成已退出。
            // 断网、超时、5xx 都只是本次云同步失败，本地联系人必须照常可用。
            if (e.status == 401 || e.code == "device_revoked" ||
                e.code == "refresh_token_reuse" || e.code == "invalid_refresh_token"
            ) {
                session.clearTokens()
                false
            } else {
                throw e
            }
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
