package com.evelorion.phone.sync.engine

import android.content.Context
import android.provider.CallLog as SystemCallLog
import android.util.Base64
import android.util.Log
import com.evelorion.phone.bridge.VaultBridge
import com.evelorion.phone.sync.crypto.Crypto
import com.evelorion.phone.sync.crypto.VaultCrypto
import com.evelorion.phone.sync.db.CallDatabase
import com.evelorion.phone.sync.db.CallRecordEntity
import com.evelorion.phone.sync.db.CallRecordDeduplicator
import com.evelorion.phone.sync.db.CallSyncStateEntity
import com.evelorion.phone.sync.net.CallsApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 通话记录的端到端加密同步。
 *
 * 一次 sync() 做三件事：
 *   1. 把系统通话记录里**新增的**那些收进本地库
 *   2. 拉远端变更
 *   3. 推本地变更
 *
 * ── 关于「绝不从扫描推断删除」 ──────────────────────────────
 *
 * 通讯录那边出过一次真实事故：本地库有一瞬间读出空表，同步引擎把它
 * 理解成「用户删光了」，一次性推了墓碑，所有设备上的数据同时消失。
 *
 * 这里从设计上就没有那条路：本地库里少了什么**不会**产生墓碑。
 * 删除只有一个来源 —— 用户在界面上明确删除，那时才打 deletedLocally 标记。
 * 扫描只负责「新增」，不负责「消失」。
 */
class CallSyncEngine(private val context: Context) {

    companion object {
        private const val TAG = "CallSync"
        private const val PUSH_BATCH = 100
        private const val SCHEMA_VERSION = 1
        private const val MAX_PULL_PAGES = 100
    }

    class Report(
        val imported: Int = 0,
        val pulled: Int = 0,
        val pushed: Int = 0,
        val error: String = "",
    ) {
        val ok: Boolean get() = error.isEmpty()
    }

    private val database by lazy { CallDatabase.get(context) }
    private val dao by lazy { database.callDao() }

    private enum class RemoteResult { APPLIED, SKIPPED, REPAIR_QUEUED, UNRECOVERABLE }

    private data class PullReport(
        val applied: Int = 0,
        val repairsQueued: Int = 0,
        val unrecoverable: Int = 0,
    )

    fun sync(): Report {
        val session = VaultBridge.session(context)
        if (!session.usable) return fail(session.message)

        val key = runCatching { Crypto.fromHex(session.collectionKeyHex) }.getOrNull()
            ?: return fail("同步子密钥格式不对")

        val api = CallsApi(session.baseUrl) {
            // 令牌过期时回头再要一张。保险库这时可能已经锁了，那就要不到。
            VaultBridge.session(context).takeIf { it.usable }?.accessToken.orEmpty()
        }

        return try {
            val bindingError = bindSyncIdentity(session.accountId, key)
            if (bindingError != null) return fail(bindingError)

            CallRecordDeduplicator.clean(dao)
            val imported = importFromSystem()
            val state = dao.state() ?: CallSyncStateEntity()
            val pull = pull(api, key, state.lastSeq)
            CallRecordDeduplicator.clean(dao)
            val pushed = push(api, key)

            val finalError = if (pull.unrecoverable > 0) {
                "云端有 ${pull.unrecoverable} 条记录使用旧密钥，本机没有对应明文，已保留云端密文"
            } else ""

            dao.putState(
                (dao.state() ?: CallSyncStateEntity()).copy(
                    lastSyncAt = System.currentTimeMillis(),
                    lastError = finalError,
                )
            )
            Report(imported = imported, pulled = pull.applied, pushed = pushed, error = finalError)
        } catch (e: CallsApi.AuthExpired) {
            fail(e.message ?: "同步凭据已失效")
        } catch (e: CallsApi.HttpFailure) {
            fail("服务器返回错误：${e.message}")
        } catch (e: IOException) {
            fail("网络不可用：${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "通话记录同步失败", e)
            fail("同步失败：${e.message}")
        } finally {
            Crypto.wipe(key)
        }
    }

    private fun fail(message: String): Report {
        runCatching {
            dao.putState((dao.state() ?: CallSyncStateEntity()).copy(lastError = message))
        }
        return Report(error = message)
    }

    /**
     * 把本地同步库绑定到账号和 collection 密钥指纹。
     *
     * 同一账号密钥升级：保留全部本地明文，回到 seq=0 核对并重新加密。
     * 切换账号：拒绝自动上传，避免把旧账号的私人通话记录泄露到新账号。
     */
    private fun bindSyncIdentity(accountId: String, key: ByteArray): String? {
        if (accountId.isBlank()) return "同步账号缺少账户标识，请先在通讯录重新登录"
        val fingerprint = Crypto.toHex(Crypto.sha256(key))
        val state = dao.state() ?: CallSyncStateEntity()

        if (state.accountId.isNotBlank() && state.accountId != accountId) {
            return "检测到通讯录切换了账号。为防止把旧账号通话记录上传到新账号，已暂停同步"
        }

        if (state.accountId.isBlank() || state.keyFingerprint != fingerprint) {
            database.runInTransaction {
                dao.markAllForReencrypt()
                dao.putState(
                    (dao.state() ?: CallSyncStateEntity()).copy(
                        accountId = accountId,
                        keyFingerprint = fingerprint,
                        lastSeq = 0,
                        lastError = "正在迁移通话记录加密密钥",
                    )
                )
            }
        }
        return null
    }

    // ------------------------------------------------------------ 1. 收编系统记录

    /**
     * 把系统通话记录里比上次更新的那些收进来。
     *
     * 只看 startedAt 严格大于水位线的 —— 用「有没有相同号码+时间」去判重
     * 在同一秒内打两个电话时会误判，而水位线不会。
     */
    private fun importFromSystem(): Int {
        val state = dao.state() ?: CallSyncStateEntity()
        val since = state.systemImportedUpTo
        var newest = since
        var count = 0
        val existing = dao.recent(10_000)
            .asSequence()
            .mapTo(HashSet(), CallRecordDeduplicator::identity)

        runCatching {
            context.contentResolver.query(
                SystemCallLog.Calls.CONTENT_URI,
                arrayOf(
                    SystemCallLog.Calls.NUMBER, SystemCallLog.Calls.TYPE,
                    SystemCallLog.Calls.DATE, SystemCallLog.Calls.DURATION,
                    SystemCallLog.Calls.CACHED_NAME,
                ),
                "${SystemCallLog.Calls.DATE} > ?", arrayOf(since.toString()),
                "${SystemCallLog.Calls.DATE} DESC LIMIT 500",
            )?.use { c ->
                val batch = ArrayList<CallRecordEntity>()
                while (c.moveToNext()) {
                    val number = c.getString(0).orEmpty()
                    val kind = when (c.getInt(1)) {
                        SystemCallLog.Calls.INCOMING_TYPE -> "incoming"
                        SystemCallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        else -> "missed"
                    }
                    val startedAt = c.getLong(2)
                    val durationSeconds = c.getInt(3)
                    if (startedAt > newest) newest = startedAt
                    val identity = CallRecordDeduplicator.identity(
                        number, kind, startedAt, durationSeconds
                    )
                    if (!existing.add(identity)) continue
                    batch.add(
                        CallRecordEntity(
                            uuid = CallRecordDeduplicator.stableUuid(identity),
                            number = number,
                            name = c.getString(4).orEmpty(),
                            kind = kind,
                            startedAt = startedAt,
                            // 系统 CallLog 没有单独的结束时间。这里只能用已接通时长估算；
                            // 本 App 自己记录的新通话会保存真实的挂断时间。
                            endedAt = if (durationSeconds > 0) {
                                startedAt + durationSeconds * 1_000L
                            } else {
                                startedAt
                            },
                            durationSeconds = durationSeconds,
                            dirty = true,
                        )
                    )
                    count++
                }
                database.runInTransaction {
                    if (batch.isNotEmpty()) dao.upsertAll(batch)
                    if (newest > since) {
                        dao.putState(
                            (dao.state() ?: CallSyncStateEntity())
                                .copy(systemImportedUpTo = newest)
                        )
                    }
                }
            }
        }.onFailure {
            // 没给 READ_CALL_LOG 权限时走这里。收不到系统记录不该让整次同步失败 ——
            // 本 App 自己记的那些仍然要同步上去。
            Log.i(TAG, "读取系统通话记录失败：${it.message}")
        }

        return count
    }

    // ------------------------------------------------------------ 2. 拉取

    private fun pull(api: CallsApi, key: ByteArray, startSeq: Long): PullReport {
        var since = startSeq
        var applied = 0
        var repairsQueued = 0
        var unrecoverable = 0
        var pages = 0

        while (true) {
            if (pages++ >= MAX_PULL_PAGES) {
                throw IOException("服务器分页超过上限，已停止同步以避免持续耗电")
            }
            val response = api.getChanges(since)
            val changes = response.optJSONArray("changes") ?: JSONArray()
            if (changes.length() == 0) break

            for (i in 0 until changes.length()) {
                val change = changes.optJSONObject(i) ?: continue
                when (applyRemote(key, change)) {
                    RemoteResult.APPLIED -> applied++
                    RemoteResult.REPAIR_QUEUED -> repairsQueued++
                    RemoteResult.UNRECOVERABLE -> unrecoverable++
                    RemoteResult.SKIPPED -> Unit
                }
            }

            val nextSince = response.optLong("nextSince", since)
            if (response.optBoolean("hasMore", false) && nextSince <= since) {
                throw IOException("服务器分页游标没有前进，已停止同步以避免死循环")
            }
            since = nextSince
            dao.putState((dao.state() ?: CallSyncStateEntity()).copy(lastSeq = since))
            if (!response.optBoolean("hasMore", false)) break
        }
        return PullReport(applied, repairsQueued, unrecoverable)
    }

    private fun applyRemote(key: ByteArray, change: JSONObject): RemoteResult {
        val uuid = change.getString("uuid")
        val rev = change.getInt("rev")
        val known = dao.byUuid(uuid)

        // 自己刚推上去又被拉回来的，跳过
        if (known != null && known.rev == rev && !known.dirty) return RemoteResult.SKIPPED

        if (change.optBoolean("deleted", false)) {
            dao.deleteByUuid(uuid)
            return RemoteResult.APPLIED
        }

        val payload = try {
            // decryptRecord 直接给 String（内部已经 unpad + 解 UTF-8）
            JSONObject(VaultCrypto.decryptRecord(key, uuid, rev, decodeSealed(change)))
        } catch (e: Exception) {
            // 解不开只可能是密钥不对或数据被改过。跳过这条，但要记下来 ——
            // 静默丢弃会让用户永远不知道有记录拉不下来。
            Log.e(TAG, "通话记录 $uuid 解密失败", e)
            if (known != null) {
                // 本机仍有这条记录的明文：跟上服务端 rev 后保持 dirty，push 会用
                // 当前 v2 密钥写成 rev+1。服务端旧密文不删除，覆盖成功才结束。
                dao.upsert(known.copy(rev = rev, dirty = true))
                return RemoteResult.REPAIR_QUEUED
            }
            dao.putState(
                (dao.state() ?: CallSyncStateEntity())
                    .copy(lastError = "有 1 条旧通话记录无法解密，本机也没有对应明文")
            )
            return RemoteResult.UNRECOVERABLE
        }

        dao.upsert(
            CallRecordEntity(
                uuid = uuid,
                number = payload.optString("number"),
                name = payload.optString("name"),
                kind = payload.optString("kind", "incoming"),
                startedAt = payload.optLong("startedAt"),
                endedAt = payload.optLong("endedAt"),
                durationSeconds = payload.optInt("duration"),
                rev = rev,
                dirty = false,
            )
        )
        return RemoteResult.APPLIED
    }

    // ------------------------------------------------------------ 3. 推送

    private fun push(api: CallsApi, key: ByteArray): Int {
        var pushed = 0
        var round = 0

        while (round < 5) {
            val pending = dao.pending(PUSH_BATCH)
            if (pending.isEmpty()) break

            val changes = JSONArray()
            val byUuid = HashMap<String, CallRecordEntity>()

            for (record in pending) {
                byUuid[record.uuid] = record
                if (record.deletedLocally) {
                    changes.put(
                        JSONObject()
                            .put("uuid", record.uuid)
                            .put("baseRev", record.rev)
                            .put("deleted", true)
                            .put("schemaVer", SCHEMA_VERSION)
                    )
                    continue
                }
                val payload = JSONObject()
                    .put("number", record.number)
                    .put("name", record.name)
                    .put("kind", record.kind)
                    .put("startedAt", record.startedAt)
                    .put("endedAt", record.endedAt)
                    .put("duration", record.durationSeconds)
                    .toString()

                val sealed = VaultCrypto.encryptRecord(
                    key, record.uuid, record.rev + 1, payload
                )
                changes.put(
                    JSONObject()
                        .put("uuid", record.uuid)
                        .put("baseRev", record.rev)
                        .put("deleted", false)
                        .put("schemaVer", SCHEMA_VERSION)
                        .put("nonce", b64(sealed.copyOfRange(0, Crypto.NONCE_BYTES)))
                        .put("ciphertext", b64(sealed.copyOfRange(Crypto.NONCE_BYTES, sealed.size)))
                )
            }

            if (changes.length() == 0) break

            val results = api.push(changes).optJSONArray("results") ?: JSONArray()
            var hadConflict = false

            for (i in 0 until results.length()) {
                val result = results.optJSONObject(i) ?: continue
                val record = byUuid[result.getString("uuid")] ?: continue
                when (result.optString("status")) {
                    "applied" -> {
                        if (record.deletedLocally) dao.deleteByUuid(record.uuid)
                        else {
                            dao.upsert(record.copy(rev = result.getInt("rev"), dirty = false))
                            pushed++
                        }
                    }
                    "conflict" -> {
                        hadConflict = true
                        val server = result.optJSONObject("server")
                        // 通话记录是**只增不改**的：同一条不会两边同时编辑。
                        // 撞冲突只意味着服务端已经有了，跟着它的版本走即可。
                        if (server != null) applyRemote(key, server)
                        else dao.deleteByUuid(record.uuid)
                    }
                }
            }

            if (!hadConflict && dao.countPending() == 0) break
            round++
        }
        return pushed
    }

    // ------------------------------------------------------------ 工具

    private fun decodeSealed(change: JSONObject): ByteArray =
        Base64.decode(change.getString("nonce"), Base64.NO_WRAP) +
            Base64.decode(change.getString("ciphertext"), Base64.NO_WRAP)

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
