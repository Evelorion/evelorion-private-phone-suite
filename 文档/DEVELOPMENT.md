# 开发手册

日常改东西看这份。分三部分：**不能破坏的约定**、**常见任务怎么做**、**出问题怎么查**。

---

## 一、不能破坏的约定

改代码前先扫一眼这一节。这些约定破坏了不会立刻报错，
而是过几天以「数据解不开」「同步停不下来」的形式炸出来。

### 1. 三份密码学实现必须逐字节一致

```
server/test/client.ts                        参考实现
android/contacts/.../sync/crypto/*.kt        通讯录
android/phone/.../privatecalls/CallCrypto.kt 电话
```

**防线**：`server/test/vectors.expected.json` 是标准答案，
`CryptoVectorsTest` 逐条比对。改完必须重新生成并跑真机测试。

### 2. canonical JSON 必须确定性

`ContactPayload.toCanonicalJson()` 的规则：

- 所有键一律输出，空值写 `""` 或 `[]`，**不省略**
- 键按字典序
- 列表按条目 id 排序
- 无空白字符

破坏它的后果：同一份数据在两次序列化中产生不同字节 → 哈希不同 →
`detectLocalChanges` 每次都判定「有改动」→ 无限推送循环。

### 3. 三方合并必须幂等且对称

```
merge(x, x, x) == x                    幂等
merge(base, l, r) == merge(base, r, l)  对称
```

破坏它的后果：两台设备互相推来推去停不下来，永远收敛不了。

`Merger.resolveScalar()` 是裁决函数，它**必须是确定性的**——
两台设备各自算要得到同一个结果。别在里面用随机数、当前时间、设备 id。

有测试覆盖：`CryptoVectorsTest.merge_isIdempotentAndSymmetric`。

### 4. SyncEngine 的五个阶段顺序不能换

尤其是 `verifyManifest()` 必须在 `push()` **之前**——
push 会改写清单，先推就等于用新清单盖掉了本轮该检查的那份。

### 5. CallLogGuard：先写库，再删系统记录

反过来的话写库失败这通电话就彻底没了。

### 6. 数据库操作前必须关连接

`EncryptionMode.switchTo()` 里的 rekey 是**原地操作**。
有连接开着会失败或产生半新半旧的库。所以顺序是
`closeAllDatabases()` → `rekey()` → `persistKey()` → `reinstall()`。

### 7. 升级 commons 要核对反射那段

`EncryptedDatabases.buildContactsDatabase()` 复刻了 commons 里的 builder 配置
（schema 版本 + 两个 migration）。commons 改了而我们没跟上，Room 会直接崩。

**当前对齐的是 commons 6.1.6，`ContactsDatabase` version = 3。**

---

## 二、常见任务

### 给联系人加一个字段

比如加「配偶姓名」。

1. **`ContactPayload.kt`** 加进 data class，并在 `toCanonicalJson()` 里
   **按字典序**插到正确位置：

   ```kotlin
   val spouse: String = "",
   // toCanonicalJson 里：在 "s" 开头的键之间，starred 之前
   key("spouse"); append(quote(spouse))
   ```

2. **`fromJson()`** 加 `spouse = o.optString("spouse")` ——
   老版本的密文里没这个键，`optString` 返回 `""`，向后兼容。

3. **`fromLocalContact()` / `toLocalContact()`** 加映射。
   如果 commons 的 `LocalContact` 里没有对应字段，那存不了，
   得考虑放进 `notes` 或者别加。

4. **`Merger.kt`** 的 `pick()` 列表里加一行。忘了这步的话，
   这个字段永远采用远端值，本机改动会被静默丢弃。

5. 跑 `CryptoVectorsTest.record_roundTrips` 确认序列化没坏。

**不需要**改服务端、不需要改数据库 schema、不需要迁移——
负载是加密的 JSON，服务端只当它是字节。这是这个设计的好处。

### 加一类新数据（比如短信）

1. **服务端** `src/db.ts` 的 `COLLECTIONS` 数组加 `'messages'`。就这一处。
2. **通讯录** `VaultBridgeProvider` 的白名单加 `messages`，
   让短信 App 能拿到 `HKDF(DEK, "fc.collection.messages.v1")`。
3. **短信 App** 照抄 `CallSyncEngine` 的结构。

服务端的 `records` 表是通用的，`collection` 列已经把两类数据隔开了。

### 改密码学参数（比如 Argon2 内存从 64MiB 降到 32MiB）

⚠ **老账号的数据不受影响**——KDF 参数存在账号记录里，
每个账号用自己注册时的参数。改的只是新注册账号的默认值。

1. `VaultCrypto.KDF_MEMORY_KIB`、`CallCrypto`（如果涉及）、`client.ts` 的
   `KDF_MEMORY_KIB` **三处一起改**。
2. 服务端 `routes/account.ts` 的下界校验（现在是 `32768`）要相应放宽。
   这个下界是防止被改过的客户端用弱参数注册。
3. 重新生成向量：

   ```bash
   cd server
   node --experimental-strip-types test/vectors.ts > test/vectors.expected.json
   ```

4. 把新值抄进 `CryptoVectorsTest.kt`。
5. `npm run test:e2e` + 真机 `./gradlew connectedCoreDebugAndroidTest`。

### 加一个 API 端点

1. `src/routes/` 下对应文件里加，用 `requireAuth(req)` 拿身份。
2. 参数校验用 `lib/http.ts` 里的 `requireString` / `requireInt` / `b64` ——
   **别手写**，那几个函数处理了 base64 畸形输入、长度上限这些边界。
3. `test/e2e.ts` 加断言。
4. Kotlin 端 `SyncApi.kt` 加对应方法。
5. 更新 [API.md](API.md)。

### 改服务端后重新部署

```bash
# 本地先测
cd server && npm run test:e2e

# 只把服务器后端目录同步到 VPS，不上传 Android 源码或签名材料
rsync -av --delete --exclude='.env' ./ deploy@YOUR_SERVER_IP:/opt/contacts-sync/

# 重建并重启
ssh deploy@YOUR_SERVER_IP 'cd /opt/contacts-sync && \
  docker build -t contacts-sync:latest . && docker compose up -d && \
  sleep 5 && docker compose logs --tail 20 sync'

# 确认还活着
curl https://contacts.example.com/v1/health
```

数据在 Docker volume 里，重建镜像不会动它。

### 加一个数据库表（同步库）

`SyncDatabase` 的 version 现在是 1。加表要：

1. 加 `@Entity` 类和 DAO 方法
2. `@Database(entities = [...], version = 2)`
3. 写 `Migration(1, 2)` 并 `.addMigrations()`

**别用 `fallbackToDestructiveMigration()`** ——
它会在 schema 不匹配时直接删库重建，用户的同步状态全没，
下次同步会把服务器上的东西全当成新记录重新拉一遍。

---

## 三、出问题怎么查

### 同步不工作

按这个顺序排除：

```kotlin
// 1. 保险库解锁了吗？没解锁 SyncEngine 直接返回
VaultManager.get(context).isUnlocked

// 2. 上次的错误是什么？
SyncDatabase.get(context).syncDao().getState()?.lastError

// 3. 有多少条等着推？
SyncDatabase.get(context).syncDao().countPending()

// 4. 有冲突需要用户确认吗？
SyncDatabase.get(context).syncDao().getConflicted()
```

```bash
# 5. 服务端那边看得到吗
adb logcat -s SyncEngine:* SyncWorker:*

# 6. 服务端日志
ssh deploy@YOUR_SERVER_IP 'cd /opt/contacts-sync && docker compose logs --tail 50 sync'
```

### 某条联系人解不开

`SyncEngine.applyRemoteChange()` 捕获解密异常后会跳过那条并写进
`lastError`，不会让整次同步挂掉。看到「有 1 条联系人解密失败」时：

- **最可能**：这条是另一个账号的数据（服务器串了，或者你换过账号）
- **其次**：AAD 里的 rev 对不上——服务端返回的 rev 和加密时用的不一致
- **再次**：三份密码学实现分叉了，跑 `CryptoVectorsTest` 确认

### 同步停不下来，反复推同一条

几乎肯定是 **canonical JSON 不确定性**。验证方法：

```kotlin
val a = payload.toCanonicalJson()
val b = payload.toCanonicalJson()
assert(a == b)                      // 同一对象两次序列化
assert(a == ContactPayload.fromJson(a).toCanonicalJson())  // 往返
```

常见原因：新加的字段忘了排序、`Map` 迭代顺序、浮点数格式化。

### 清单报「服务器藏了记录」

先别慌，**大概率不是服务器有问题**：

- 本地 `sync_records` 被清过（重装 App、清数据）而服务端清单还是旧的
- 两台设备同时推送，清单撞冲突后有一轮没更新上

真正需要警惕的是**反复出现同一条 uuid 的 Missing**。
那时候去服务端直接查：

```bash
ssh deploy@YOUR_SERVER_IP 'docker exec contacts-sync node -e "
const db = require(\"better-sqlite3\")(\"/app/data/sync.db\");
console.log(db.prepare(\"SELECT uuid,rev,deleted,length(ciphertext) FROM records WHERE account_id=?\").all(\"你的账号id\"));
"'
```

### 本地数据库打不开

```
EncryptedDatabases.lastError
```

几种情况：

| lastError 内容 | 原因 | 怎么办 |
|---|---|---|
| SQLCipher 原生库加载失败 | .so 没打进 APK | 检查 ABI 配置和 ProGuard keep 规则 |
| 需要先通过屏幕锁验证 | 开了 KEYSTORE_SCREEN_LOCK 模式 | 正常，拉起验证后重试 |
| 无法取得数据库口令 | Keystore 密钥没了（改锁屏方式/恢复出厂） | 数据已永久打不开，只能从服务器恢复 |
| 找不到存实例的静态字段 | commons 版本变了 | 改用 composite build 方案 |

### 电话 App 读不到私密联系人

```kotlin
PrivateContactsClient.isAvailable(context)   // false 就往下查
VaultBridge.describe(VaultBridge.requestSession(context))  // 会给出人话原因
```

四种可能，按概率排序：

1. **两个 App 签名不一样** —— 最常见。`signature` 权限不会授予
2. **装反了** —— 必须先 Contacts 后 Phone
3. **通讯录保险库锁着** —— 让用户去通讯录解锁一次
4. `PrivacyGuard.pinnedCertSha256` 填了但和实际证书对不上

```bash
# 确认权限到底有没有授予
adb shell dumpsys package org.fossify.phone | grep -A3 READ_PRIVATE_CONTACTS
```

### 通话记录还留在系统里

```bash
# 保护开了吗
adb shell run-as org.fossify.phone cat shared_prefs/fc_call_guard.xml

# 有读写权限吗（两个都要）
adb shell dumpsys package org.fossify.phone | grep -E "READ_CALL_LOG|WRITE_CALL_LOG"

# observer 在跑吗
adb logcat -s CallLogGuard:*
```

如果范围是 `PRIVATE_AND_UNKNOWN` 而这个号码在系统通讯录里存着，
那它**本来就不该被拿走**——这是设计，不是 bug。

---

## 四、发版前检查清单

```
[ ] server: npm run test:e2e 全绿（86 项）
[ ] server: npx tsc --noEmit 无错误
[ ] Android: ./gradlew assembleRelease 通过
[ ] Android: ./gradlew connectedCoreDebugAndroidTest 全绿（含交叉校验）
[ ] PrivacyGuard.pinnedCertSha256 填了正式证书指纹
[ ] 两个 App 的 allowBackup 都是 false
[ ] 两个 App 用同一把证书签名
[ ] 真机验证：装 Contacts → 装 Phone → 电话 App 能读到私密联系人
[ ] 真机验证：两台设备双向同步 + 冲突合并
[ ] 真机验证：卸载重装 → 恢复码恢复 → 数据回来
[ ] adb backup 导出的文件里搜不到联系人姓名
[ ] strings 数据库文件搜不到号码
```

---

## 五、几个容易被忽略的坑

**`useradd sync` 会失败。** Debian 自带一个 uid=4 的系统账号就叫 `sync`。
Dockerfile 里用镜像自带的 `node` 用户。（这个已经踩过并修了。）

**nginx 的 server 级 `return` 会短路所有 location。**
它在 rewrite 阶段执行，早于 location 选择。要加 ACME 路径必须把
`return 301` 也挪进 `location /`。（也踩过并修了。）

**nginx 的 `alias` 配 `try_files` 永远 404。**
`$uri` 仍按 document root 解析。certbot webroot 要用 `root` 不能用 `alias`。

**`pkill -f certbot` 会杀掉自己。**
如果你的命令行里含 "certbot" 这个字符串，pkill 会连自己一起杀。

**certbot renew 默认有最多 8 分钟随机延迟。**
调试时加 `--no-random-sleep-on-renew`，不然你会以为它卡死了。

**WorkManager 周期任务最小间隔 15 分钟。**
填更小的值会被静默改成 15，不报错。

**`ContentObserver` 会被自己的写操作唤醒。**
`CallLogGuard` 删系统记录会再次触发回调，所以只在**有新记录时**才触发同步，
否则会形成「删除 → sweep → 同步」的空转循环。
