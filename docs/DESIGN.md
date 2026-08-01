# 私密通讯录：自建服务器 + 端到端加密 设计文档

面向 `Evelorion/Contacts` 与 `Evelorion/Phone` 的 `private-ui-edition` 分支。

> 本项目的文档索引在 [README.md](README.md)。这份讲**为什么这么设计**；
> 想知道**代码在哪**看 [ARCHITECTURE.md](ARCHITECTURE.md)，想**改东西**看 [DEVELOPMENT.md](DEVELOPMENT.md)。

---

## 1. 目标与不做的事

### 要做到的

1. 联系人存在**你自己的服务器**上，多设备双向同步。
2. **端到端加密**：服务器只存密文。就算服务器被拖库、被入侵、或者你自己想看，
   都拿不到任何一条联系人的内容。
3. 手机上的私密联系人**只有同一套签名的自家 App 能读**，第三方通讯录 App 读不到。
4. 忘记口令时能用**恢复码**拿回数据；换手机能恢复。

### 明确不做的

- **不做多用户共享**。这是个人自用的通讯录，一个账号一套数据。
- **不做服务端搜索**。服务器看不懂内容，搜索只能在手机上做。联系人量级（几千条）
  下全量拉到本地解密再搜完全够用。
- **不做防 root**。设备被 root 或刷了改过的 framework 时，任何纯软件方案都挡不住，
  见第 7 节。

---

## 2. 威胁模型

先把「防谁」说清楚，后面每个设计选择才有依据。

| 攻击者 | 能力 | 本方案的应对 |
|---|---|---|
| **拿到服务器的人**（VPS 商、入侵者、以及你自己） | 读写数据库全部内容、读日志、篡改返回的数据 | 只能看到密文、条数、大小、时间戳。改数据会被 AEAD 标签发现；回滚旧版本会被 AAD 里的 rev 发现 |
| **网络中间人** | 看到并篡改流量 | TLS + 内容本身已是密文。**必须用 https**，代码里明确拒绝 http |
| **第三方 App**（同一台手机上） | 调用 ContentProvider、读公开的系统通讯录 | `signature` 级权限 + `PrivacyGuard` 双重校验，返回空 Cursor |
| **拿到你手机文件的人**（丢手机、`adb backup`） | 读 App 私有目录 | 关掉 `allowBackup`；DEK 用 Android Keystore 包裹；可选屏幕锁验证 |
| **暴力破解口令的人** | 拿到服务端的 `auth_hash` 和被包裹的 DEK | Argon2id（64 MiB / 3 轮 / 4 并行），登录端点限流 |
| **root 后的攻击者** | 任意读写、注入进程 | **挡不住**，见第 7 节 |

服务器**看得到**的元数据，这一点必须诚实：联系人条数、每条密文大小（已对齐到
256 字节块）、每次同步的时间、你的 IP、有几台设备。这些抹不掉，除非引入
更重的方案（固定频率的假流量、Tor）。对自建服务器场景来说，这些元数据本来就是你自己的。

---

## 3. 密钥体系

```
                        主口令（用户记忆）
                             │
                    Argon2id(salt, 64MiB, t=3, p=4)
                             ▼
                        MK 主密钥（只在内存中存在）
                          ╱        ╲
        HKDF "fc.kek.v1" ╱          ╲ HKDF "fc.auth.v1"
                        ▼            ▼
                      KEK        authSecret ──► 发给服务器做认证
                        │                        （服务器再 Argon2id 一次存库）
                    包裹 DEK
                        ▼
   ┌──────────────► DEK 数据密钥（32 字节随机，一辈子不变） ◄──────────────┐
   │                     │                                              │
   │        HKDF "fc.rec.v1"(salt = 联系人 uuid)                        │
   │                     ▼                                             包裹
   │              每条联系人各自的记录密钥                                │
   │                     │                                              │
   │        HKDF "fc.idx.v1" ──► 盲索引密钥（只留本机，不上传）            │
   │        HKDF "fc.blob.v1" ─► 头像密钥                               │
   │                                                                    │
   └────── HKDF "fc.rkek.v1" ◄── 恢复码（26 字节熵，14 组 4 字符）────────┘
```

### 为什么要分 KEK 和 DEK 两层

因为**改口令不该重新加密所有数据**。DEK 一辈子不变，换口令时只是用新的 KEK
重新包一次 DEK（一个 60 字节的 blob），服务器上几千条联系人密文一个字节都不用动。

同样的道理，恢复码是 DEK 的第二条独立包裹路径。服务器上并排存着：

- `dek_wrap_password` —— 用口令派生的 KEK 包裹的 DEK
- `dek_wrap_recovery` —— 用恢复码派生的 RKEK 包裹的 DEK

两个 blob 服务器都解不开。忘了口令就用恢复码开第二把锁，拿到同一把 DEK。

### 具体参数

| 项 | 取值 | 理由 |
|---|---|---|
| KDF | Argon2id, m=64 MiB, t=3, p=4 | OWASP 当前推荐档位。低端机上约 0.5~1.5 秒，可接受 |
| 口令归一化 | NFKC | 不做的话中文输入法的全角字符会派生出不同密钥 |
| AEAD | AES-256-GCM, 96 位随机 nonce, 128 位标签 | Android 有硬件加速；每条记录用独立子密钥，不存在 nonce 复用风险 |
| HKDF | HKDF-SHA256，info 做域分离 | KEK 和 authSecret 由同一个 MK 派生，靠 info 保证互不可推 |
| 恢复码 | 32 字节 → Crockford Base32 52 字符 + 4 字符校验 | Crockford 去掉了 I/L/O/U，抄纸上不会认错 |
| 填充 | ISO 7816-4 补到 256 字节整数倍 | 抹掉「这个联系人字段特别多」这类元数据 |

### AAD 绑定

每条记录的 AAD 是 `uuid(16) ‖ rev(4, 大端) ‖ schemaVersion(1)`。

这挡住两类来自恶意服务器的攻击：

- **回滚**：把你三个月前的旧密文当成最新版本推回来 —— rev 对不上，认证失败。
- **张冠李戴**：把 A 联系人的密文塞到 B 的位置 —— uuid 对不上，认证失败。

代价是客户端加密时必须提前知道 rev（用 `baseRev + 1`）。撞冲突时要重新加密，
但那本来就要重新合并一次，不算额外开销。

---

## 4. 同步协议

### 数据模型

服务端每个账号有一个单调递增的 `seq` 计数器，每次写操作 +1。
每条记录有自己的 `rev`，从 1 开始，每次被接受的写入 +1。

```
拉取  GET  /v1/sync/changes?since=<seq>   →  seq 之后的所有变更
推送  POST /v1/sync/push {changes:[{uuid, baseRev, nonce, ciphertext}]}
        baseRev == 服务端当前 rev  →  applied, rev+1
        否则                       →  conflict，原样退回服务端版本
```

服务端**完全不参与冲突解决** —— 它看不懂内容，也就没有资格判断。
它只做一件事：拿版本号比一比，不匹配就把当前版本退回去。

### 三方合并

冲突解决在客户端做，用三方合并而不是「时间戳大的赢」：

```
base   = 本机上次同步成功时的那份快照（存在 sync_records.base_payload）
local  = 本机当前状态
remote = 服务端退回来的版本

某一侧相对 base 没变  →  采用另一侧
两侧都变且结果一样    →  采用该值
两侧都变且不一样      →  确定性裁决 + 记进 conflicts 提示用户
```

**为什么不用时间戳**：手机时钟不可靠（时区、手动改、NTP 抖动），而且
「谁时间戳大谁赢」是整条覆盖 —— 另一台设备刚加的号码会直接消失。
三方合并能准确区分「这一侧改过」和「这一侧只是没变」，
所以 A 改姓名、B 加号码，合并后两个改动都在。

裁决必须满足两个性质，否则两台设备会互相推来推去停不下来：

- **幂等** `merge(x, x, x) == x`
- **对称** `merge(base, l, r) == merge(base, r, l)`

两条都有测试覆盖（服务端 `test/e2e.ts`，Android `CryptoVectorsTest`）。

### 列表条目的 id

号码、邮箱这些列表元素需要跨设备稳定的 id 才能合并。但 commons 的
`LocalContact` 是外部依赖里的 Room 实体，加不了「每条号码的 uuid」这种列。

解法是**由内容确定性推导**：

```
itemId(list, identity) = SHA256(list ‖ 0x00 ‖ identity)[0..16] 的十六进制
```

`identity` 只包含「决定这条是不是同一条」的部分（号码归一化后的值、
小写邮箱、组名……），`label` 和 `type` 这类可改的附属字段不进去。

好处是两台设备各自录入同一个号码会算出同一个 id，合并时自动去重。
副作用是改号码本身等于「删旧的 + 加新的」，这符合直觉。

### 本地改动检测

`SyncEngine` 每次同步会**全量扫描**本机私密联系人，算出 canonical JSON 的哈希，
和 `sync_records.base_hash` 比对。

不在每个写入点埋钩子，是因为联系人的写入路径有七八条（编辑页、VCF 导入、
收藏切换、分组增删、铃声设置……），埋钩子迟早会漏，而漏掉的后果是**静默不同步** ——
这是最难查的一类 bug。几千条联系人扫一遍也就几十毫秒，值这个代价。

---

## 5. 跨 App 访问边界

原来的实现已经做对了大方向。这次收紧了三处：

### 5.1 签名证书指纹钉扎

原来用 `checkSignatures(myPackage, callerPackage) == SIGNATURE_MATCH`。
这在正常情况下够用，但如果 App 被人重打包（改包名、重新签名后发出去），
**重打包后的那一套 App 之间照样能互相通过校验**。

改成钉扎硬编码的证书 SHA-256 指纹，「只有我签的那一套」这个约束才真正成立。
指纹还没填时会退回到 `checkSignatures`，发版前务必填上
（`PrivacyGuard.ownCertificateFingerprint()` 可以直接显示出来）。

### 5.2 共享 uid 的处理

原来是「调用方 uid 下**任意一个**包可信就放行」。一个 uid 可以对应多个共享
uid 的包，只要有一个不可信就应该整体拒绝。改成 `all` 而不是 `any`。

同时加了一条：调用方自报的 `callingPackage` 必须确实属于这个 uid，否则说明它在撒谎。

### 5.3 关掉 allowBackup —— 这条最要紧

**当前 manifest 里写的是 `android:allowBackup="true"`。**

这意味着能执行 `adb backup` 的人（解锁的设备 + 开了 USB 调试）可以把私密联系人
的整个 Room 数据库导出来。前面所有 `signature` 权限的功夫，被这一行完全绕过。

改成 `false`，并加上 `backup_rules.xml` / `data_extraction_rules.xml` 全量排除。
代价是换机时系统备份不会带走通讯录 —— 但既然已经有服务器同步了，
走同步恢复更可靠，也不用把明文交给厂商的云。

### 5.4 按号码查询

新增 `content://…/number/<号码>` 这条 URI。来电时电话 App 只需要知道
「这个号码是谁」，让它把整个通讯录拉过去自己匹配，既慢又等于把全部联系人
复制了一份到另一个进程。

查询走本机的**盲索引**：`HMAC(HKDF(DEK,"fc.idx.v1"), 归一化号码)`。
索引表只存在本机，不上传，数据库里也就不需要一列可搜索的明文号码。

保险库锁定时这条查询返回空 —— 没有 DEK 就算不出索引密钥。
意味着开机后第一次来电可能显示不出名字，直到用户解锁一次。
这是刻意的取舍：宁可少显示一个名字，也不把索引密钥常驻在没保护的地方。

---

## 6. 服务端

Node 22 + Fastify + SQLite（`better-sqlite3`），单进程，Docker 一条命令起。
选 SQLite 是因为个人自用的数据量下它比 Postgres 省心得多，备份就是拷一个文件。

### 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v1/account/kdf?username=` | 取盐和 KDF 参数。**未注册的用户名也返回确定性假盐**，防账号枚举 |
| POST | `/v1/account/register` | 注册，需要邀请码 |
| POST | `/v1/session` | 登录，签发访问令牌 + 刷新令牌 |
| POST | `/v1/session/refresh` | 轮换令牌，**检测重放** |
| GET | `/v1/vault` | 取回被包裹的 DEK |
| POST | `/v1/vault/rewrap` | 改口令，只换包裹 |
| GET | `/v1/sync/changes?since=` | 拉取变更 |
| POST | `/v1/sync/push` | 推送，逐条判定冲突 |
| PUT/GET | `/v1/blobs/:hash` | 头像 |
| GET/DELETE | `/v1/devices[/:id]` | 设备管理，可远程吊销 |

### 几处刻意的设计

- **账号枚举防护**：`/v1/account/kdf` 对不存在的用户名返回
  `HMAC(服务端密钥, 用户名)` 得到的假盐。不这样做的话，任何人都能探出你
  服务器上有哪些账号。登录端点也会对不存在的用户名走一遍 Argon2，避免用响应时间区分。
- **刷新令牌重放检测**：刷新令牌一次性使用并轮换。同一个令牌被用第二次说明它
  可能已经泄露，直接吊销该设备的全部令牌。
- **KDF 参数下界**：服务端拒绝 `memoryKiB < 32768` 的注册请求。防止被改过的
  客户端用弱参数注册，之后暴力破解就容易多了。
- **推送整批一个事务**：要么全落库要么全不落，避免部分成功导致 seq 出现空洞。
- **不记请求体**：日志里只有方法、路径、IP。请求体全是密文，记下来除了扩大
  泄露面没有任何好处。

### 部署

```bash
cd server
cp .env.example .env
openssl rand -hex 32          # 填进 SERVER_SECRET
# REGISTRATION_TOKEN 也务必设一个，否则任何人都能在你服务器上开账号
docker compose up -d
```

`docker-compose.yml` 只绑 `127.0.0.1:8443`，TLS 交给前面的 Caddy
（`Caddyfile.example` 里有现成配置）。**不要直接把 8443 暴露到公网** ——
虽然内容是密文，但访问令牌会明文过网，拿到它的人可以删掉你服务器上的全部数据。

### 备份

```bash
sqlite3 /var/lib/docker/volumes/…/sync.db ".backup '/backup/sync-$(date +%F).db'"
```

备份文件里全是密文，可以放心存到任何地方 —— 这是端到端加密的附带好处。
但**恢复码和主口令一定要另外保管**，丢了它们备份就是一堆解不开的字节。

---

## 7. 已知缺口

写在最前面：下面这些不是「以后再说」的托词，是这套方案确实做不到的事。

### 7.1 本地数据库加密：已实现，但有天花板

私密联系人存在 commons 的 `local_contacts.db` 里。这一版已经接上了 SQLCipher，
详见 [`LOCAL_DB_ENCRYPTION.md`](LOCAL_DB_ENCRYPTION.md)。

`ContactsDatabase` 是 Maven 依赖里的类且没留注入口，所以默认走的是
**反射注入**：在 `Application.attachBaseContext()` 里，趁没人调过 `getInstance()`，
把用 SQLCipher 工厂建好的实例塞进它的静态字段。文档里也给了更稳的
composite build + 打补丁方案。

**天花板必须说清楚：默认模式挡不住 root。**
App 自己得能打开数据库，密钥就必须在 App 拿得到的地方；攻击者只要能注入进程
让 App 替他解密，密钥在不在 TEE 里都一样。它挡的是「拿到文件」那一类攻击
（`adb backup`、拆闪存、路径遍历漏洞、刷机包提取分区）。

想真正挡住 root 只有模式 C：数据库口令由主口令派生，Keystore 里什么都不存。
代价是解锁之前整个通讯录不可用，来电显示也查不到名字。三种模式都实现了，设置页可切换。

### 7.2 signature 权限挡不住 root

`signature` 级权限由系统 PackageManager 执行。设备 root、装了 Xposed/LSPosed、
或者刷了改过 framework 的系统时，这层保护可以被绕过。

它挡的是「普通第三方 App 想读你的通讯录」，不是「拿到 root 的攻击者」。
文档和设置页都应该这么写，不要给用户过强的心理预期。

### 7.3 号码归一化不做国家码补全

`+8613800138000` 和 `13800138000` 会算出不同的 `itemId`，合并后变成两条号码。

要正确处理得引 libphonenumber 并知道用户的默认国家码。加个依赖不难，
但会让 `itemId` 依赖「当前设备认为的国家码」—— 两台设备国家码设得不一样时
反而会分叉。目前的做法是接受这个限制，UI 上引导用户统一用带国家码的格式。

### 7.4 恶意服务器能做的事

服务器解不开内容，也改不了内容（AEAD 标签会发现），但它可以：

- **删数据**（客户端本地有完整副本，能发现但没法阻止）
- **看元数据**（条数、大小、同步时间、IP）

至于**静默隐藏某条记录**，这一版补上了防护，见「同步清单」一节。
之前这里写着「没有实现」，现在实现了。

#### 同步清单

清单是一条由客户端自己加密、uuid 固定的记录，内容是 `{uuid: rev}` 的全量目录。

它和其它记录一样带 AEAD 标签，所以服务器改不了、伪造不了；
想把它藏起来的话，客户端本地记着的 `manifestRev` 会立刻暴露。

于是「服务器说这就是全部」变成了「我上次自己写下的目录说应该有这些」，
信任基础从服务器挪回了客户端。能挡住三类攻击：

| 攻击 | 怎么被发现 |
|---|---|
| 隐藏一整条记录 | 清单里有、本地没有 → `Missing` |
| 把某条退回旧版本（连 rev 一起退） | 清单记着 rev=5，实际给了 3 → `Rollback` |
| 把整份清单退回旧版本 | 本地记着 manifestRev=5，服务端给了 1 → `ManifestRollback` |

编码是紧凑二进制（每条 20 字节），1000 个联系人约 20 KB。
上限 3200 条 —— 超了会**明确报错而不是静默截断**，
因为截断等于悄悄把这层保护关掉了，那是最糟糕的失败方式。

服务端 `test/e2e.ts` 里有 15 项断言专门验证这套机制，
包括模拟服务器藏记录、回滚单条、回滚整份清单、以及伪造清单（没有密钥，做不到）。

### 7.5 权限的安装顺序（已消除）

早先的版本要求先装 Contacts 再装 Phone —— 因为两个 signature 权限只在 Contacts
的 manifest 里声明，Phone 先装的话请求会被静默丢弃，而且之后不会补授予。

现在**两个 App 都声明这两个权限**，顺序无所谓了。

Android 只允许签名相同的包声明同名权限，签名不一致会
`INSTALL_FAILED_DUPLICATE_PERMISSION`。这反而把「两个 App 必须同签名」
这个隐性约束变成了安装时的显式报错。

代价：两边的 `<permission>` 声明必须逐字一致（name、protectionLevel、
label/description 引用的字符串），否则以先装的那个为准，行为会变得难以预测。

### 7.6 没有编译验证

Android 端的 Kotlin 代码**没有经过编译**（开发环境里没有 Android SDK）。
可能有拼写、import、Room 注解处理方面的问题。

服务端和密码学协议**跑过测试**：`npm run test:e2e` 有 163 项断言全绿，
包括「数据库文件里搜不到任何一个联系人的明文」这一组。
`CryptoVectorsTest` 的期望值就是从那套实现导出的，
所以只要它在真机上通过，两端的密码学就是对齐的。

---

## 8. 文件清单

```
docs/DESIGN.md                本文件
docs/LOCAL_DB_ENCRYPTION.md   本地数据库加密（SQLCipher）
docs/CALL_LOG_PROTECTION.md   通话记录保护（Phone 仓库）
server/                       零知识同步服务端（Node + TypeScript）
  src/                          实现
  test/client.ts                参考客户端，同时是 Kotlin 端的规格说明
  test/merge.ts                 三方合并参考实现
  test/e2e.ts                   端到端验收，163 项断言
  test/vectors.expected.json    交叉校验用的固定测试向量
  Dockerfile / docker-compose.yml / Caddyfile.example
android/
  INTEGRATION.md                接入步骤（含 gradle / manifest 具体改动）
  contacts/…/sync/              加密与同步模块
  contacts/…/helpers/PrivacyGuard.kt          加固版
  contacts/…/contentproviders/…               加固版 + 按号码查询
  contacts/…/res/xml/*.xml                    备份排除规则
  contacts/src/androidTest/…                  交叉校验测试
  contacts/…/sync/bridge/VaultBridgeProvider.kt  把 calls 子密钥交给电话 App
  contacts/…/sync/localdb/                    本地库加密
  phone/…/PrivateContactsClient.kt            电话 App 的读取入口
  phone/…/privatecalls/                       通话记录加密存储 + CallLog 监听 + 同步
```

---

## 9. 建议的落地顺序

1. **先改 `allowBackup="false"`**。一行改动，堵住当前最大的洞，和同步功能无关。
2. 部署服务端，用 `test/e2e.ts` 确认能跑通。
3. 合入 Android 的加密模块，跑通 `CryptoVectorsTest`（这一步会暴露编译问题）。
4. 单设备跑通「注册 → 推送 → 卸载重装 → 恢复码恢复」。
5. 两台设备跑双向同步和冲突合并。
6. 接电话 App 的 `PrivateContactsClient`，并填上签名指纹。
7. 上通话记录保护：先换存储（堵住 shared_prefs 明文那个洞），
   再接 CallLog 监听，最后才接同步。见 [`CALL_LOG_PROTECTION.md`](CALL_LOG_PROTECTION.md)。

前三步做完就已经是可用状态了，后面可以慢慢来。

不过有两条建议**插队最先做**，它们都是一行改动、和新功能无关，
但堵住的是当前两个仓库里最大的洞：

- 两个 manifest 的 `allowBackup` 都改成 `false`
- Phone 的通话历史从 `shared_prefs` 的明文 JSON 里搬走
