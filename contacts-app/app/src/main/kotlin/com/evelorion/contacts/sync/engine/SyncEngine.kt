package com.evelorion.contacts.sync.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.evelorion.contacts.data.PrivateContactStore
import org.fossify.commons.extensions.contactsDB
import org.fossify.commons.extensions.groupsDB
import org.fossify.commons.models.contacts.Group
import com.evelorion.contacts.sync.VaultManager
import com.evelorion.contacts.sync.crypto.Crypto
import com.evelorion.contacts.sync.crypto.VaultCrypto
import com.evelorion.contacts.sync.db.BlindIndexEntity
import com.evelorion.contacts.sync.db.BlobStateEntity
import com.evelorion.contacts.sync.db.SyncDatabase
import com.evelorion.contacts.sync.db.SyncRecordEntity
import com.evelorion.contacts.sync.db.SyncStateEntity
import com.evelorion.contacts.sync.localdb.EncryptedDatabases
import com.evelorion.contacts.sync.model.ContactPayload
import com.evelorion.contacts.sync.model.ContactPayload.Companion.toLocalContact
import com.evelorion.contacts.sync.model.Merger
import com.evelorion.contacts.sync.net.SyncApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * 同步引擎。一次 sync() 干四件事，顺序不能换：
 *
 *   1. 扫描本机，算出哪些联系人相对上次同步变了（新增 / 修改 / 删除）
 *   2. 拉取远端自 lastSeq 以来的变更，逐条落地；本机也改过的走三方合并
 *   3. 把本机的改动推上去，撞冲突的重新合并再推一轮
 *   4. 重建盲索引，供电话 App 按号码查人
 *
 * 关于「怎么知道本机改了什么」：
 * 这里用的是每次全量扫描比对哈希，而不是在每个写入点埋钩子。
 * 联系人的写入路径有七八条（编辑页、VCF 导入、收藏切换、分组增删、
 * 铃声设置……），埋钩子迟早会漏，漏了就是静默不同步 —— 这是最难查的一类 bug。
 * 全量扫描对几千条联系人来说也就几十毫秒，值这个代价。
 */
class SyncEngine(private val context: Context) {

    companion object {
        private const val TAG = "SyncEngine"
        private const val PULL_PAGE = 500
        private const val PUSH_BATCH = 100

        /** 冲突后重推的轮数上限，防止和另一台同时在推的设备无限打架。 */
        private const val MAX_CONFLICT_ROUNDS = 5
    }

    class Report(
        val pulled: Int = 0,
        val pushed: Int = 0,
        val deleted: Int = 0,
        val conflicts: Int = 0,
        val error: String = "",
        /**
         * 同步清单校验发现的问题。非空意味着服务器给的数据不完整或被回退过 ——
         * 这不是「同步慢了点」，UI 上要和普通错误区分开、显眼地报出来。
         */
        val integrityIssues: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = error.isEmpty()
        val trustworthy: Boolean get() = integrityIssues.isEmpty()
    }

    private val vault = VaultManager.get(context)
    // 懒加载：同步库打不开时会抛异常，放在构造函数里的话，
    // 异常会在 new SyncEngine() 的地方炸出来，绕过 sync() 里那一整套错误处理。
    private val dao by lazy { SyncDatabase.get(context).syncDao() }

    fun sync(): Report {
        if (!vault.isConfigured) return Report(error = "还没有配置同步服务器")
        val dek = vault.dek() ?: return Report(error = "保险库已锁定，请先解锁")
        val api = vault.api()

        return try {
            // 一开跑就把上一次的错误清掉。
            //
            // 以前 lastError 只在同步**成功**时才清，于是一条早就修好的旧错误
            // 会一直挂在同步页上。用户看到红字，没法判断这是「现在的状态」
            // 还是「一小时前的事」—— 而这两者要做的处理完全不同。
            runCatching { dao.putState((dao.getState() ?: SyncStateEntity()).copy(lastError = "")) }

            // 加密层没装上就绝不往下走。读到一个空的/错的库再去做差异比对，
            // 得出的结论必然是错的，而同步的结论是会写回服务器的。
            EncryptedDatabases.requireReady(context)

            val state = dao.getState() ?: SyncStateEntity()
            detectLocalChanges(dek, api)
            val pullResult = pull(dek, api, state.lastSeq)
            val pulled = pullResult.applied
            // 清单校验要在拉取之后、推送之前做 —— 推送会改写清单，
            // 先推的话就等于用新清单盖掉了本轮该被检查的那份。
            val issues = verifyManifest(dek, pullResult)
            val pushResult = push(dek, api)
            updateManifest(dek, api)
            rebuildBlindIndex(dek)
            PrivateContactStore.notifyChanged(context)

            val head = runCatching { api.status().optLong("serverSeq", 0) }.getOrDefault(0L)
            dao.putState(
                (dao.getState() ?: SyncStateEntity()).copy(
                    lastSyncAt = System.currentTimeMillis(),
                    lastError = "",
                    serverSeq = head,
                    manifestIssues = issues.joinToString("\n") { it.describe() },
                )
            )
            Report(
                pulled = pulled,
                pushed = pushResult.first,
                deleted = pushResult.second,
                conflicts = dao.getConflicted().size,
                integrityIssues = issues.map { it.describe() },
            )
        } catch (e: SyncApi.AuthExpired) {
            fail("登录已失效，请重新登录同步账号")
        } catch (e: SyncApi.HttpFailure) {
            fail("服务器返回错误：${e.message}")
        } catch (e: IOException) {
            fail("网络不可用：${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "同步失败", e)
            fail("同步失败：${e.message}")
        }
    }

    private fun fail(message: String): Report {
        // 写状态本身也可能失败（同步库就是打不开的时候）。
        // 在 catch 里再抛一次异常会盖掉真正的错误原因，那才是最难查的。
        runCatching { dao.putState((dao.getState() ?: SyncStateEntity()).copy(lastError = message)) }
        return Report(error = message)
    }

    // ------------------------------------------------------------ 1. 本机变更

    /**
     * 全量扫描本机私密联系人，和 sync_records 里的快照比对。
     * 结果只是给记录打上 dirty / deletedLocally 标记，真正的上传在 push 里做。
     */
    private fun detectLocalChanges(dek: ByteArray, api: SyncApi) {
        val groupTitles = context.groupsDB.getGroups().associate { (it.id ?: 0L) to it.title }
        val locals = context.contactsDB.getContacts()
        val allKnown = dao.getAll()
        val knownByLocalId = allKnown.filter { it.localId != 0 }.associateBy { it.localId }
        val seenContentHashes = allKnown
            .filterNot { it.deletedLocally }
            .mapNotNullTo(HashSet()) { it.baseHash.takeIf(String::isNotEmpty) }
        val seenLocalIds = HashSet<Int>()

        for (local in locals) {
            val localId = local.id ?: continue
            seenLocalIds.add(localId)

            val photoBlobId = local.photo?.takeIf { it.isNotEmpty() }?.let { bytes ->
                ensureBlobUploaded(dek, api, bytes)
            }.orEmpty()

            val payload = ContactPayload.fromLocalContact(local, photoBlobId, groupTitles)
            val json = payload.toCanonicalJson()
            val hash = hashOf(json)

            val known = knownByLocalId[localId]
            if (known == null) {
                // Keep an exact local duplicate, but do not create another cloud record for it.
                if (!seenContentHashes.add(hash)) continue
                // 本机新建的联系人，还没有对应的 uuid
                dao.upsert(
                    SyncRecordEntity(
                        uuid = UUID.randomUUID().toString(),
                        localId = localId,
                        rev = 0,
                        basePayload = "",
                        baseHash = "",
                        dirty = true,
                    )
                )
            } else if (known.baseHash != hash) {
                seenContentHashes.add(hash)
                dao.upsert(known.copy(dirty = true, updatedAt = System.currentTimeMillis()))
            } else {
                seenContentHashes.add(hash)
            }
        }

        // sync_records 里有、但这次扫描没扫到的记录。
        //
        // ── 这里绝不能推墓碑 ──────────────────────────────────────
        //
        // 曾经的实现是「扫不到 = 用户删了 → 推墓碑」。那是一次真实的数据事故：
        // 本机数据库有一瞬间读出了空表（加密层没装上、commons 重建了一个空库），
        // 这段代码于是把用户全部 6 位联系人在服务器上一次性删光，
        // 而墓碑会覆盖掉密文 —— 所有设备上同时永久消失。
        //
        // 两种错法的代价完全不对等：
        //   漏报删除 → 服务器上多留一条已删的联系人，用户再删一次就好
        //   误报删除 → 全部设备上的数据被销毁，不可逆
        //
        // 所以删除改成在**删除入口**显式打标记（ContactRepository.deleteContact →
        // markDeletedByLocalId），那是唯一权威的来源。扫描扫不到只说明
        // 「本机这条不见了，但没人报告删过它」—— 那是异常，不是意图。
        // 对异常的正确反应是**用上次同步的快照把它恢复出来**，而不是扩大破坏。
        val vanished = knownByLocalId.values.filter {
            it.localId !in seenLocalIds && !it.deletedLocally
        }
        if (vanished.isNotEmpty()) {
            healVanished(dek, api, vanished, locals.isEmpty())
        }
    }

    /**
     * 本机记录不明消失时的自愈。
     *
     * basePayload 是上次同步成功时的完整快照，所以绝大多数情况都能原样重建。
     * 重建后 dirty 保持 false —— 内容和服务端一致，没有东西需要推。
     *
     * @param localDbLooksEmpty 整张表都读不到东西。这几乎一定是数据库故障
     *   而不是用户删了所有人，日志和状态里要分开记，方便事后判断。
     */
    private fun healVanished(
        dek: ByteArray,
        api: SyncApi,
        vanished: Collection<SyncRecordEntity>,
        localDbLooksEmpty: Boolean,
    ) {
        var healed = 0
        var unhealable = 0

        for (record in vanished) {
            val snapshot = record.basePayload.takeIf { it.isNotEmpty() }
            if (snapshot == null) {
                // 没有快照说明它从没同步成功过，服务器上也没有这条。
                // 本机又找不到，那就是真的什么都没有了 —— 删掉这条索引即可，
                // 不推墓碑（服务器上本来就没有对应记录）。
                if (record.rev == 0) dao.deleteByUuid(record.uuid) else unhealable++
                continue
            }
            val restoredId = runCatching {
                writeLocalContact(dek, api, ContactPayload.fromJson(snapshot), null)
            }.getOrDefault(0)

            if (restoredId > 0) {
                dao.upsert(record.copy(localId = restoredId, dirty = false, updatedAt = System.currentTimeMillis()))
                healed++
            } else {
                unhealable++
            }
        }

        val note = buildString {
            if (localDbLooksEmpty) append("本机联系人库读出来是空的（很可能是数据库没能正常打开）；")
            append("有 ${vanished.size} 条联系人在本机消失但没有删除记录")
            if (healed > 0) append("，已用上次同步的快照恢复 $healed 条")
            if (unhealable > 0) append("，还有 $unhealable 条无法自动恢复，请到网页端检查")
            append("。没有向服务器推送任何删除。")
        }
        Log.w(TAG, note)
        dao.putState((dao.getState() ?: SyncStateEntity()).copy(lastError = note))
    }

    // ------------------------------------------------------------ 2. 拉取

    /** 拉取的结果。清单要单独拎出来，它不是联系人。 */
    private class PullResult(
        val applied: Int,
        /** 服务端这一轮给出的清单密文，没给就是 null。 */
        val manifestChange: JSONObject?,
    )

    private fun pull(dek: ByteArray, api: SyncApi, startSeq: Long): PullResult {
        var since = startSeq
        var applied = 0
        var manifestChange: JSONObject? = null

        while (true) {
            val response = api.getChanges(since, PULL_PAGE)
            val changes = response.optJSONArray("changes") ?: JSONArray()
            if (changes.length() == 0) break

            for (i in 0 until changes.length()) {
                val change = changes.optJSONObject(i) ?: continue
                // 清单不是联系人，别让它走建联系人的那条路
                if (change.optString("uuid") == SyncManifest.MANIFEST_UUID) {
                    manifestChange = change
                    continue
                }
                if (applyRemoteChange(dek, api, change)) applied++
            }

            since = response.optLong("nextSince", since)
            dao.putState((dao.getState() ?: SyncStateEntity()).copy(lastSeq = since))

            if (!response.optBoolean("hasMore", false)) break
        }
        return PullResult(applied, manifestChange)
    }

    // -------------------------------------------------- 2.5 清单校验

    /**
     * 拿服务端给的清单和本地实际持有的记录对一遍。
     *
     * 这是整套方案里唯一能发现「服务器少给了东西」的手段。
     * 端到端加密保证服务器读不懂、改不了内容，但挡不住它装作某条记录不存在 ——
     * 尤其是换新手机恢复的时候，本地什么都没有，完全依赖服务器说的话。
     *
     * 清单是客户端自己加密写上去的，服务器伪造不了、改不了；
     * 藏起来的话，本地记着的 manifestRev 会立刻暴露它。
     */
    private fun verifyManifest(dek: ByteArray, pullResult: PullResult): List<SyncManifest.Issue> {
        val state = dao.getState() ?: SyncStateEntity()
        val change = pullResult.manifestChange

        if (change == null) {
            // 这一轮没拉到清单有两种可能：本轮没有变化（正常），或者被藏了。
            // 只有本地记着清单存在过、而且这是一次从头拉取时，才判定为异常。
            return if (state.lastSeq == 0L) SyncManifest.verifyAbsence(state.manifestRev) else emptyList()
        }

        val rev = change.getInt("rev")
        val manifest = try {
            SyncManifest.decode(VaultCrypto.decryptRecord(dek, SyncManifest.MANIFEST_UUID, rev, decodeSealed(change)))
        } catch (e: Exception) {
            // 清单解不开比联系人解不开严重得多：要么密钥不对，要么它被动过手脚。
            Log.e(TAG, "同步清单解密失败", e)
            return listOf(SyncManifest.Issue.ManifestMissing(state.manifestRev))
        }

        val present = dao.getAll()
            .filterNot { it.deletedLocally }
            .associate { it.uuid to it.rev }

        val issues = SyncManifest.verify(manifest, rev, state.manifestRev, present)
        dao.putState(state.copy(manifestRev = maxOf(rev, state.manifestRev)))

        if (issues.isNotEmpty()) {
            Log.w(TAG, "同步清单校验发现 ${issues.size} 个问题：${issues.joinToString { it.describe() }}")
        }
        return issues
    }

    /**
     * 把本机认为的完整目录写上去。
     *
     * 每次推送之后都要更新，否则下次校验会用过时的清单误报。
     * 清单自己也用 baseRev 做乐观并发 —— 两台设备同时推时会撞冲突，
     * 那种情况下直接采用服务端那份并跳过本轮（下次同步会补上），
     * 因为清单不是数据，晚一轮更新不会丢东西。
     */
    private fun updateManifest(dek: ByteArray, api: SyncApi) {
        val state = dao.getState() ?: SyncStateEntity()
        val entries = dao.getAll()
            .filterNot { it.deletedLocally || it.rev == 0 }
            .associate { it.uuid to it.rev }

        val payload = try {
            SyncManifest.encode(entries)
        } catch (e: SyncManifest.TooManyRecords) {
            // 超上限时明确报出来，而不是静默截断。
            // 截断等于悄悄把完整性保护关掉了 —— 最糟糕的失败方式。
            Log.e(TAG, e.message, e)
            dao.putState(state.copy(manifestIssues = e.message ?: "同步清单超出上限"))
            return
        }

        val nextRev = state.manifestRev + 1
        val sealed = VaultCrypto.encryptRecord(dek, SyncManifest.MANIFEST_UUID, nextRev, payload)
        val change = JSONObject()
            .put("uuid", SyncManifest.MANIFEST_UUID)
            .put("baseRev", state.manifestRev)
            .put("deleted", false)
            .put("schemaVer", VaultCrypto.SCHEMA_VERSION)
            .put("nonce", Base64.encodeToString(sealed.copyOfRange(0, Crypto.NONCE_BYTES), Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(sealed.copyOfRange(Crypto.NONCE_BYTES, sealed.size), Base64.NO_WRAP))

        val response = api.push(JSONArray().put(change))
        val result = response.optJSONArray("results")?.optJSONObject(0) ?: return

        when (result.optString("status")) {
            "applied" -> dao.putState((dao.getState() ?: state).copy(manifestRev = result.getInt("rev")))
            "conflict" -> {
                // 另一台设备刚更新过清单。跟着它的 rev 走，下次同步再写我们这份。
                val serverRev = result.optJSONObject("server")?.optInt("rev") ?: state.manifestRev
                dao.putState((dao.getState() ?: state).copy(manifestRev = serverRev))
                Log.i(TAG, "同步清单被另一台设备抢先更新，跳过本轮")
            }
        }
    }

    private fun applyRemoteChange(dek: ByteArray, api: SyncApi, change: JSONObject): Boolean {
        val uuid = change.getString("uuid")
        // 兜底：清单不是联系人，任何路径都不该把它当联系人建出来。
        // pull() 里已经拦过一次，这里防的是 push 冲突回退那条路。
        if (uuid == SyncManifest.MANIFEST_UUID) return false
        val rev = change.getInt("rev")
        val deleted = change.optBoolean("deleted", false)
        val known = dao.getByUuid(uuid)

        // 自己刚推上去的那条又被拉回来了，跳过
        if (known != null && known.rev == rev && !known.dirty) return false

        if (deleted) {
            // A remote tombstone is not enough evidence to destroy a local contact.
            // Preserve the local copy and require an explicit local delete.
            if (known != null && known.localId != 0) {
                val conflicts = (known.conflictFields.split(",")
                    .filter(String::isNotBlank) + "remote_deleted")
                    .distinct()
                    .joinToString(",")
                dao.upsert(
                    known.copy(
                        rev = rev,
                        deletedLocally = false,
                        dirty = false,
                        conflictFields = conflicts,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                return false
            }
            if (known != null) dao.deleteByUuid(uuid)
            return true
        }

        val remotePayload = try {
            ContactPayload.fromJson(
                VaultCrypto.decryptRecord(dek, uuid, rev, decodeSealed(change))
            )
        } catch (e: Exception) {
            // 解不开只可能是密钥不对或者数据被改过。跳过这条，不要让整次同步挂掉，
            // 但要记进状态里，否则用户永远不知道有条联系人拉不下来。
            Log.e(TAG, "记录 $uuid 解密失败", e)
            dao.putState(
                (dao.getState() ?: SyncStateEntity())
                    .copy(lastError = "有 1 条联系人解密失败，可能来自另一个账号")
            )
            return false
        }

        val groupTitles = context.groupsDB.getGroups().associate { (it.id ?: 0L) to it.title }
        var conflicts = emptyList<String>()

        val finalPayload: ContactPayload
        if (known != null && known.dirty && known.localId != 0) {
            // 两边都改了 → 三方合并
            val localContact = context.contactsDB.getContactWithId(known.localId)
            val localPayload = if (localContact != null) {
                ContactPayload.fromLocalContact(
                    localContact,
                    localContact.photo?.takeIf { it.isNotEmpty() }?.let { ensureBlobUploaded(dek, api, it) }.orEmpty(),
                    groupTitles,
                )
            } else {
                remotePayload
            }
            val base = known.basePayload.takeIf { it.isNotEmpty() }?.let { ContactPayload.fromJson(it) }
            val result = Merger.merge(base, localPayload, remotePayload)
            finalPayload = result.merged
            conflicts = result.conflicts
        } else {
            finalPayload = remotePayload
        }

        val localId = writeLocalContact(dek, api, finalPayload, known?.localId?.takeIf { it != 0 })
        val mergedJson = finalPayload.toCanonicalJson()

        dao.upsert(
            SyncRecordEntity(
                uuid = uuid,
                localId = localId,
                rev = rev,
                basePayload = remotePayload.toCanonicalJson(),
                baseHash = hashOf(remotePayload.toCanonicalJson()),
                deletedLocally = false,
                // 合并后本机内容和远端不一样，说明还有东西要推回去
                dirty = mergedJson != remotePayload.toCanonicalJson(),
                conflictFields = conflicts.joinToString(","),
            )
        )
        return true
    }

    // ------------------------------------------------------------ 3. 推送

    /** 返回（推送成功条数，删除条数）。 */
    private fun push(dek: ByteArray, api: SyncApi): Pair<Int, Int> {
        var pushed = 0
        var deleted = 0
        var round = 0

        while (round < MAX_CONFLICT_ROUNDS) {
            val pending = dao.getPending(PUSH_BATCH)
            if (pending.isEmpty()) break

            val changes = JSONArray()
            val byUuid = HashMap<String, Pair<SyncRecordEntity, ContactPayload?>>()
            val groupTitles = context.groupsDB.getGroups().associate { (it.id ?: 0L) to it.title }

            for (record in pending) {
                if (record.deletedLocally) {
                    changes.put(
                        JSONObject()
                            .put("uuid", record.uuid)
                            .put("baseRev", record.rev)
                            .put("deleted", true)
                            .put("schemaVer", VaultCrypto.SCHEMA_VERSION)
                    )
                    byUuid[record.uuid] = record to null
                    continue
                }

                val local = context.contactsDB.getContactWithId(record.localId) ?: continue
                val photoBlobId = local.photo?.takeIf { it.isNotEmpty() }
                    ?.let { ensureBlobUploaded(dek, api, it) }.orEmpty()
                val payload = ContactPayload.fromLocalContact(local, photoBlobId, groupTitles)
                val sealed = VaultCrypto.encryptRecord(
                    dek, record.uuid, record.rev + 1, payload.toCanonicalJson()
                )
                changes.put(
                    JSONObject()
                        .put("uuid", record.uuid)
                        .put("baseRev", record.rev)
                        .put("deleted", false)
                        .put("schemaVer", VaultCrypto.SCHEMA_VERSION)
                        .put("nonce", Base64.encodeToString(sealed.copyOfRange(0, Crypto.NONCE_BYTES), Base64.NO_WRAP))
                        .put(
                            "ciphertext",
                            Base64.encodeToString(sealed.copyOfRange(Crypto.NONCE_BYTES, sealed.size), Base64.NO_WRAP)
                        )
                )
                byUuid[record.uuid] = record to payload
            }

            if (changes.length() == 0) break

            val response = api.push(changes)
            val results = response.optJSONArray("results") ?: JSONArray()
            var hadConflict = false

            for (i in 0 until results.length()) {
                val result = results.optJSONObject(i) ?: continue
                val uuid = result.getString("uuid")
                val (record, payload) = byUuid[uuid] ?: continue

                when (result.optString("status")) {
                    "applied" -> {
                        val rev = result.getInt("rev")
                        if (payload == null) {
                            dao.deleteByUuid(uuid)
                            deleted++
                        } else {
                            val json = payload.toCanonicalJson()
                            dao.upsert(
                                record.copy(
                                    rev = rev, basePayload = json, baseHash = hashOf(json),
                                    dirty = false, updatedAt = System.currentTimeMillis(),
                                )
                            )
                            pushed++
                        }
                    }

                    "conflict" -> {
                        hadConflict = true
                        val server = result.optJSONObject("server")
                        if (server == null) {
                            // 服务端说冲突却没给版本，只可能是那条已经被彻底删了
                            dao.deleteByUuid(uuid)
                        } else {
                            applyRemoteChange(dek, api, server)
                        }
                    }
                }
            }

            if (!hadConflict && dao.countPending() == 0) break
            round++
        }

        if (round >= MAX_CONFLICT_ROUNDS) {
            Log.w(TAG, "冲突重试次数用尽，剩余 ${dao.countPending()} 条留到下次同步")
        }
        return pushed to deleted
    }

    // ------------------------------------------------------------ 4. 盲索引

    /**
     * 重建号码到联系人的盲索引。电话 App 靠它按来电号码查人，
     * 而数据库里不需要留一列可搜索的明文号码。
     */
    private fun rebuildBlindIndex(dek: ByteArray) {
        val salt = vault.session.kdfSalt ?: return
        val indexKey = VaultCrypto.deriveIndexKey(dek, salt)
        try {
            dao.clearAllIndex()
            val entries = ArrayList<BlindIndexEntity>()
            for (record in dao.getAll()) {
                if (record.localId == 0) continue
                val local = context.contactsDB.getContactWithId(record.localId) ?: continue
                for (phone in local.phoneNumbers) {
                    val norm = ContactPayload.normalizeNumber(
                        phone.normalizedNumber.ifEmpty { phone.value }
                    )
                    if (norm.isEmpty()) continue
                    entries.add(
                        BlindIndexEntity(
                            idx = VaultCrypto.blindIndex(indexKey, norm),
                            localId = record.localId,
                            uuid = record.uuid,
                        )
                    )
                }
            }
            if (entries.isNotEmpty()) dao.putIndex(entries.distinctBy { it.idx to it.localId })
        } finally {
            Crypto.wipe(indexKey)
        }
    }

    // ------------------------------------------------------------ 本地读写

    private fun writeLocalContact(
        dek: ByteArray, api: SyncApi, payload: ContactPayload, existingLocalId: Int?,
    ): Int {
        val photoBytes = payload.photo.takeIf { it.isNotEmpty() }?.let { downloadBlob(dek, api, it) }
        val localContact = payload.toLocalContact(existingLocalId, photoBytes) { title ->
            resolveGroupId(title)
        }
        val newId = context.contactsDB.insertOrUpdate(localContact)
        return if (newId > 0) newId.toInt() else (existingLocalId ?: 0)
    }

    private fun deleteLocalContact(localId: Int) {
        context.contactsDB.deleteContactId(localId)
        dao.clearIndexFor(localId)
    }

    /** 组按名字对齐。本机没有同名组就建一个，组 id 是本地的，不进负载。 */
    private fun resolveGroupId(title: String): Long? {
        val existing = context.groupsDB.getGroups().firstOrNull { it.title == title }
        if (existing?.id != null) return existing.id
        return runCatching { context.groupsDB.insertOrUpdate(Group(null, title)) }.getOrNull()
    }

    // ------------------------------------------------------------ 头像

    /**
     * 头像先算 blob id，服务器上没有才上传。
     * 本机 blob_state 记着传过哪些，避免每次同步都问服务器一遍。
     */
    private fun ensureBlobUploaded(dek: ByteArray, api: SyncApi, bytes: ByteArray): String {
        val hash = VaultCrypto.blobId(dek, bytes)
        if (dao.getBlob(hash)?.uploaded == true) return hash

        return try {
            val (_, sealed) = VaultCrypto.sealBlob(dek, bytes)
            api.putBlob(
                hash,
                sealed.copyOfRange(0, Crypto.NONCE_BYTES),
                sealed.copyOfRange(Crypto.NONCE_BYTES, sealed.size),
            )
            dao.putBlob(BlobStateEntity(hash, uploaded = true))
            hash
        } catch (e: IOException) {
            // 头像传不上去不该让整条联系人同步失败，先记下来下次补传
            dao.putBlob(BlobStateEntity(hash, uploaded = false))
            hash
        }
    }

    private fun downloadBlob(dek: ByteArray, api: SyncApi, hash: String): ByteArray? = try {
        val json = api.getBlob(hash)
        val sealed = Base64.decode(json.getString("nonce"), Base64.NO_WRAP) +
            Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP)
        VaultCrypto.openBlob(dek, hash, sealed)
    } catch (e: Exception) {
        Log.w(TAG, "头像 $hash 取回失败", e)
        null
    }

    // ------------------------------------------------------------ 工具

    private fun decodeSealed(change: JSONObject): ByteArray =
        Base64.decode(change.getString("nonce"), Base64.NO_WRAP) +
            Base64.decode(change.getString("ciphertext"), Base64.NO_WRAP)

    private fun hashOf(json: String): String =
        Crypto.toHex(Crypto.sha256(json.toByteArray(Charsets.UTF_8)))
}
