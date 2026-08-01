# 代码地图

每个文件干什么、改东西该动哪。设计理由在 [DESIGN.md](DESIGN.md)，这份只讲「在哪」。

---

## 全局数据流

一次完整的同步长这样：

```
  用户在通讯录里改了一个联系人
            │
            │  写进 commons 的 local_contacts.db（SQLCipher 加密）
            ▼
  ┌──────────────────────────────────────────┐
  │  SyncEngine.detectLocalChanges()         │  全量扫描比对哈希
  │  发现这条的 canonical JSON 变了 → dirty   │  不埋钩子，见下方说明
  └──────────────────┬───────────────────────┘
                     ▼
  ┌──────────────────────────────────────────┐
  │  pull()  拉服务端 seq > lastSeq 的变更     │
  │    本机也改过 → Merger 三方合并           │
  └──────────────────┬───────────────────────┘
                     ▼
  ┌──────────────────────────────────────────┐
  │  verifyManifest()  对照清单查有没有被藏    │
  └──────────────────┬───────────────────────┘
                     ▼
  ┌──────────────────────────────────────────┐
  │  push()  加密后推上去                     │
  │    VaultCrypto.encryptRecord(dek,uuid,rev)│
  │    baseRev 对不上 → conflict → 重新合并    │
  └──────────────────┬───────────────────────┘
                     ▼
  ┌──────────────────────────────────────────┐
  │  updateManifest()  重写清单                │
  │  rebuildBlindIndex()  重建号码索引         │
  └──────────────────────────────────────────┘
```

**为什么不在写入点埋钩子**：联系人的写入路径有七八条（编辑页、VCF 导入、收藏切换、
分组增删、铃声设置……），埋钩子迟早会漏，而漏掉的后果是**静默不同步**——
最难查的一类 bug。几千条联系人全量扫一遍也就几十毫秒，值这个代价。

---

## 服务端 `server/`

Node 22 + Fastify + SQLite。单进程，Docker 一条命令起。

```
src/
  index.ts          45 行   启动、注册路由、全局错误处理、日志脱敏
  config.ts         57 行   读 .env，校验 SERVER_SECRET 格式
  db.ts            155 行   建表、collection 列的迁移、nextSeq()、墓碑清理
  lib/
    crypto.ts      130 行   服务端唯一用到密码学的地方：认证哈希、访问令牌签名、假盐
    http.ts         80 行   requireAuth()、参数校验、错误响应
    ratelimit.ts    20 行   登录端点的滑动窗口计数，存 SQLite
  routes/
    account.ts     268 行   注册、登录、令牌轮换、改口令、设备管理
    sync.ts        188 行   changes / push / status —— 同步的核心
    blobs.ts        58 行   头像的内容寻址存储

test/
  client.ts        288 行   ★ 参考客户端，同时是 Kotlin 端的规格说明
  merge.ts         158 行   ★ 三方合并的参考实现
  manifest.ts       71 行   ★ 同步清单的参考实现
  e2e.ts           575 行   端到端验收，86 项断言
  vectors.ts        43 行   生成交叉校验向量
  vectors.expected.json     标准答案，Kotlin 端比对用
```

★ 标记的三个文件是**规格说明**，不只是测试。Kotlin 端必须逐字节复现它们。

### 服务端要点

**它不参与冲突解决。** 服务器看不懂内容，也就没资格判断谁对。它只做一件事：
拿 `baseRev` 和当前 `rev` 比一比，不匹配就把当前版本原样退回去。

**`nextSeq()` 必须在事务里调。** 整批推送共用一个事务，要么全落库要么全不落，
否则 seq 会出现空洞，客户端的游标逻辑就乱了。

**日志不记请求体。** 请求体全是密文，记下来除了扩大泄露面没有任何好处。

---

## 通讯录 `android/contacts/`

### 密码学层 `sync/crypto/`

```
Crypto.kt            146 行   HKDF / AES-GCM / 填充 / hex / 常量时间比较
VaultCrypto.kt       241 行   密钥体系：MK→KEK→DEK、记录密钥、盲索引、blobId、itemId
RecoveryCode.kt       95 行   Crockford Base32 + 4 字符校验，容错解析
KeystoreVault.kt     177 行   把 DEK 用 Android Keystore 包起来存本机
```

**改这层要跑交叉校验**，见 [DEVELOPMENT.md](DEVELOPMENT.md)。

`Crypto.kt` 里没有任何业务逻辑，纯原语。`VaultCrypto.kt` 才是密钥体系。
分开是为了让「哪些常量绝对不能动」一目了然。

### 数据模型 `sync/model/`

```
ContactPayload.kt    334 行   上传前的明文结构 + canonical JSON + 与 LocalContact 互转
Merger.kt            176 行   三方合并
```

`ContactPayload` **不直接序列化 commons 的类**，原因有三：它们的 id 是本机自增主键
换设备就对不上；它们是外部依赖加字段我们控制不了；三方合并需要给列表条目
稳定的 id（见 `VaultCrypto.itemId`）。

`toCanonicalJson()` 的规则：所有键一律输出（空值写成 `""` 或 `[]`）、键按字典序、
列表按条目 id 排序、无空白。**两端必须产出完全相同的字节**，否则哈希对不上，
每次同步都会误判成「有改动」。

### 同步引擎 `sync/engine/`

```
SyncEngine.kt        593 行   ← 最大的一个文件，同步的全部流程
SyncManifest.kt      190 行   清单的编解码和校验
```

`SyncEngine.sync()` 的五个阶段顺序**不能换**：

1. `detectLocalChanges()` 全量扫描打 dirty 标记
2. `pull()` 拉取，本机也改过的走三方合并
3. `verifyManifest()` 校验有没有被藏记录 —— **必须在 push 之前**，
   因为 push 会改写清单，先推就等于用新清单盖掉了本轮该检查的那份
4. `push()` 推送，撞冲突重新合并再推
5. `updateManifest()` + `rebuildBlindIndex()`

### 存储 `sync/db/` 和 `sync/localdb/`

```
db/SyncDatabase.kt          238 行   同步自己的 Room 库（和 commons 的分开）
localdb/DatabaseKey.kt      220 行   本地库口令的 Keystore 保管
localdb/EncryptedDatabases.kt 275 行 ★ 用反射把 SQLCipher 装进 commons 的库
localdb/DatabaseEncryptionMigrator.kt 232 行  明文↔加密迁移、rekey
localdb/EncryptionMode.kt   146 行   三档加密模式的切换
```

★ `EncryptedDatabases` 是全项目**最脆的一块**。`ContactsDatabase` 是 Maven 依赖里的类，
`getInstance()` 内部直接 `Room.databaseBuilder().build()`，没留注入口。
只能趁没人调过它，把我们建好的实例反射塞进它的静态字段。

三个维护成本写在文件头的注释里，**升级 commons 时必须回来核对**。
更稳的方案（composite build + 打补丁）在 [LOCAL_DB_ENCRYPTION.md](LOCAL_DB_ENCRYPTION.md)。

`SyncDatabase` 和 commons 的 `local_contacts.db` **必须分开**：commons 的
`LocalContact` 是外部依赖里的 Room 实体，我们既不能加列，也不该在它的 schema 上做迁移。

### 网络 `sync/net/`

```
SyncApi.kt           217 行   手写 OkHttp 调用，401 自动续一次令牌
SessionStore.kt      166 行   服务器地址、令牌、KDF 参数。用 EncryptedSharedPreferences
```

没用 Retrofit 是为了少一层依赖和注解处理器——端点就十来个，不划算。

`SessionStore.validateUrl()` **明确拒绝 `http://`**：内容确实是密文，
但访问令牌会明文过网，拿到的人可以删掉服务器上全部数据。

### 跨 App 边界

```
helpers/PrivacyGuard.kt              148 行   校验调用方签名指纹和包名
contentproviders/MyContactsContentProvider.kt 165 行  私密联系人出口 + 按号码查询
sync/bridge/VaultBridgeProvider.kt   132 行   把 calls 子密钥交给电话 App
```

`VaultBridgeProvider` 交出去的**不是 DEK 本身**，是稳定的 `HKDF(DEK, "fc.collection.calls.v2")`。
v2 不再混入口令 KDF salt，因此修改主口令不会让电话 App 和网页派生出不同的通话密钥。
电话 App 有它能加解密通话记录，但推不回 DEK，解不开任何一条联系人。
访问令牌也只给 15 分钟就过期的那个，刷新令牌不给。

### 其余

```
ContactsApp.kt         34 行   Application，在 attachBaseContext 装 SQLCipher
sync/VaultManager.kt  314 行   保险库开关：注册、登录、解锁、改口令、锁定
sync/work/SyncWorker.kt 124 行 WorkManager 调度
sync/ui/SyncSetupActivity.kt      246 行
sync/ui/LocalEncryptionActivity.kt 150 行
```

**`ContactsApp` 必须在 `attachBaseContext` 装加密，不能放 `onCreate`。**
Android 的启动顺序是 `attachBaseContext` → `ContentProvider.onCreate` →
`Application.onCreate`，而我们的 provider 会读数据库。

---

## 电话 `android/phone/`

```
PhoneApp.kt                    42 行   迁移旧数据 + 启动 CallLog 监听 + 排同步任务
privatecalls/
  PrivateCallDatabase.kt      165 行   Room 实体 + DAO
  PrivateCallStore.kt         280 行   读写入口 + 旧 SharedPreferences 迁移
  CallLogGuard.kt             226 行   ★ ContentObserver 监听系统 CallLog 并搬走
  CallLogScope.kt             101 行   三档保护范围
  CallCrypto.kt               172 行   ★ 密码学原语，必须和通讯录的 Crypto.kt 等价
  CallSyncEngine.kt           250 行   通话记录的 E2EE 同步
  CallSyncWorker.kt           121 行   调度
  VaultBridge.kt              112 行   向通讯录索取凭据
  CallDatabaseEncryption.kt   219 行   独立的一把 Keystore 密钥
  CallDatabaseMigrator.kt     101 行   明文→加密迁移
  CallGuardSettingsActivity.kt 218 行
privatecontacts/
  PrivateContactsClient.kt    115 行   读通讯录的私密联系人
```

### 电话端要点

**`CallLogGuard` 里的顺序不能改**：先写进私有库，**再**删系统记录。
反过来的话，写库失败这条通话记录就彻底没了。

**`CallCrypto.kt` 和通讯录的 `Crypto.kt` 是两份代码，行为必须一致。**
两个仓库独立打包，共享代码要么发公共 AAR 要么 git subtree，
对这个规模不划算。代价是改一边必须改另一边。

**Keystore 是按 App 隔离的**，所以两个 App 各有一把本地库密钥，
这不是冗余，是必须的。

**通话记录不做三方合并**：它是只追加的事实记录，不存在「两边把同一通电话改得不一样」。
撞冲突直接采用服务端版本。

---

## 网页端 `server/web/`

纯静态文件，没有构建步骤，由 Fastify 直接托管。详见 [WEB.md](WEB.md)。

```
lib/crypto.js     第四份密码学实现（WebCrypto + hash-wasm）
lib/merge.js      三方合并
lib/api.js        两套 API 客户端，凭据刻意不共用
lib/vault.js      保险库与同步 —— 比 Android 简单一半，因为浏览器没有本地库
user/             用户端：登录、联系人 CRUD、通话记录、设置
admin/            管理后台：账号、邀请码、服务器状态、安全
selftest.html     浏览器端密码学自检
```

**管理后台里没有也不可能有解密用户数据的代码。** 密钥不在服务器上，也不在管理员手里。

---

## 三个仓库之间的依赖

```
                  ┌─────────────────────┐
                  │  server (TypeScript) │
                  └──────────▲──────────┘
                             │ HTTPS
              ┌──────────────┴──────────────┐
              │                             │
   ┌──────────▼──────────┐      ┌───────────▼─────────┐
   │  Contacts (Kotlin)  │      │   Phone (Kotlin)    │
   │                     │      │                     │
   │  持有 DEK           │──────►│  只拿 calls 子密钥   │
   │  声明 signature 权限 │ 两个  │  使用这些权限        │
   │  暴露私密联系人      │ Provider                   │
   └─────────────────────┘      └─────────────────────┘
              │                             │
              └──────────┬──────────────────┘
                         ▼
              org.fossify:commons (Maven，改不了)
```

**安装顺序无所谓。** 两个 App 的 manifest 里**都**用 `<permission>` 声明了
`READ_PRIVATE_CONTACTS` 和 `VAULT_BRIDGE`，谁先装谁定义，另一个装上就能拿到。

两边都声明是刻意的：自定义权限在请求时如果还没有任何应用定义过，
系统会**静默丢弃**这个请求，而且之后定义方装上也不会补授予。

Android 只允许**签名相同**的包声明同名权限 —— 签名不一致时第二个 App 会
`INSTALL_FAILED_DUPLICATE_PERMISSION` 直接装不上。这把「两个 App 必须同签名」
从一个隐性前提变成了安装时的显式报错。

维护上的约束：两处 `<permission>` 声明必须逐字一致（name、protectionLevel、
label/description 指向的字符串内容），否则以先装的那个为准，行为不好预测。

**两个 App 必须用同一把签名证书打包。** 签名不同 → signature 权限不授予 →
电话 App 一条私密联系人都读不到。这是整套方案的地基。
