# 私密通讯录 + 电话：加密同步项目

两个 Android App（`Evelorion/Contacts`、`Evelorion/Phone`）加一个自建的零知识同步服务端。

联系人和通话记录加密后存在自己的服务器上，多设备双向同步。服务器只拿得到密文，
没有主口令谁都解不开——包括服务器管理员自己。

---

## 从哪开始看

| 你想干什么 | 看这份 |
|---|---|
| 先搞懂整体设计和为什么这么做 | [DESIGN.md](DESIGN.md) |
| 知道每个文件是干嘛的、要改东西该动哪 | [ARCHITECTURE.md](ARCHITECTURE.md) |
| 加功能 / 改协议 / 排查问题 | [DEVELOPMENT.md](DEVELOPMENT.md) ← **日常最常用** |
| 查某个接口的请求响应长什么样 | [API.md](API.md) |
| 网页版（用户端 + 管理后台） | [WEB.md](WEB.md) |
| 服务器怎么部署的、日常怎么维护 | [DEPLOYMENT.md](DEPLOYMENT.md) |
| Android 工程说明 | [../开发指南.md](../开发指南.md) |
| 本地数据库加密的三种模式 | [LOCAL_DB_ENCRYPTION.md](LOCAL_DB_ENCRYPTION.md) |
| 通话记录怎么从系统里拿走的 | [CALL_LOG_PROTECTION.md](CALL_LOG_PROTECTION.md) |

**第一次接手这个项目，按 DESIGN → ARCHITECTURE → DEVELOPMENT 的顺序看一遍，
大概一小时。** 其余按需查。

---

## 五分钟跑起来

```bash
cd server
npm install
npm run test:e2e        # 131 项断言，包含「数据库文件里搜不到明文」
npm run test:web        # 25 项，浏览器端密码学交叉校验
```

测试会自己起一个临时服务端，跑完自动清理。**改任何服务端或密码学代码后都先跑这个。**

部署方法见 [DEPLOYMENT.md](DEPLOYMENT.md)。文档中的地址均为示例：

```
https://contacts.example.com
```

---

## 术语表

看代码之前先认清这几个词，它们在文档和代码里到处出现。

### 密钥相关

| 词 | 是什么 | 存在哪 |
|---|---|---|
| **主口令** | 用户脑子里那句话 | 哪都不存 |
| **MK** Master Key | 主口令过 Argon2id 得到的 32 字节 | 只在内存，用完就抹 |
| **KEK** Key Encryption Key | 从 MK 派生，**只用来包裹 DEK** | 不存，每次现算 |
| **DEK** Data Encryption Key | 32 字节随机数，**一辈子不变**，真正加密数据的根 | 被 KEK 包裹后存服务器；解出来后进 Keystore |
| **恢复码** | 14 组 4 字符，注册时显示一次 | 用户抄在纸上 |
| **RKEK** | 从恢复码派生，DEK 的**第二条包裹路径** | 不存 |
| **记录密钥** | `HKDF(DEK, salt=联系人uuid)`，每条联系人一把 | 不存，每次现算 |
| **盲索引密钥** | `HKDF(DEK, "fc.idx.v1")`，用来按号码查人 | 不存，每次现算 |
| **collection 子密钥** | `HKDF(DEK, "fc.collection.calls.v2")`，交给电话 App；不再绑定口令 KDF salt | 不存 |

关键性质：**改口令只需要重新包裹 DEK**（一个 60 字节的 blob），
服务器上几千条联系人密文一个字节都不用动。这就是分两层的意义。

### 同步相关

| 词 | 含义 |
|---|---|
| **seq** | 账号级的全局递增计数器，每次写 +1。客户端拿它当拉取游标 |
| **rev** | 每条记录自己的版本号，从 1 开始。推送时当乐观锁用 |
| **collection** | 数据分类，目前有 `contacts` 和 `calls`。两者用**不同的密钥** |
| **三方合并** | 用「上次同步的快照」当共同祖先来合并冲突，不用时间戳 |
| **base / local / remote** | 三方合并的三个输入：上次同步的快照 / 本机当前 / 服务端退回的 |
| **同步清单** | 客户端自己加密的目录，列出全部 uuid 和 rev。用来发现服务器藏记录 |
| **盲索引** | `HMAC(索引密钥, 归一化号码)`，只存本机。让电话 App 能按号码查人而不存明文号码 |
| **墓碑** | 删除操作的标记。直接删行会导致另一台设备把它当"新记录"拉回来 |

### 跨 App 相关

| 词 | 含义 |
|---|---|
| **signature 权限** | Android 的权限级别，只有同一把证书签名的 App 能拿到 |
| **PrivacyGuard** | 通讯录里校验调用方签名和包名的那层 |
| **VaultBridge** | 通讯录把 calls 子密钥交给电话 App 的通道 |
| **commons** | `org.fossify:commons`，两个 App 共用的 Maven 依赖。**我们改不了它** |

---

## 四份密码学实现，必须逐字节一致

这是整个项目**最容易出事的地方**，先记住：

```
server/test/client.ts                          ← 参考实现，也是规格说明
android/contacts/.../sync/crypto/Crypto.kt     ← 通讯录用
android/contacts/.../sync/crypto/VaultCrypto.kt
android/phone/.../privatecalls/CallCrypto.kt   ← 电话用
server/web/lib/crypto.js                       ← 网页端用
```

四份代码算的必须是同一个东西。任何一处对不上，设备之间、以及和服务器之间的数据
就解不开了——而且报错会是「解密失败」这种毫无头绪的形式。

**防线是固定测试向量**：`server/test/vectors.expected.json` 里存着标准答案，
Kotlin 那边 `CryptoVectorsTest`、浏览器那边 `/selftest.html` 逐条比对。改动其中任何一份之后：

```bash
# 1. 重新生成向量
cd server && node --experimental-strip-types test/vectors.ts > test/vectors.expected.json

# 2. 同步给网页端
cp test/vectors.expected.json web/vendor/vectors.json

# 3. 把新值同步进 CryptoVectorsTest.kt，真机跑一遍
./gradlew connectedCoreDebugAndroidTest

# 4. 网页端也要跑
cd server && npm run test:web        # CI 里跑
# 或者浏览器打开 /selftest.html
```

> 这套校验逮到过一个真分叉：`itemId` 的分隔符在 TS 里是不可见的 NUL 字节，
> 在 Kotlin 里是空格，两端算出来完全不同。细节见 [WEB.md](WEB.md) 第 5 节。

详细步骤见 [DEVELOPMENT.md](DEVELOPMENT.md) 的「改密码学代码」一节。

---

## 项目状态

**跑过测试、可信的：**

- 服务端全部逻辑，118 项断言
- 网页端密码学，25 项交叉校验
- 密码学协议（三方交叉校验）
- 线上部署，15 项实机走查（含管理后台全流程）

**写完但没编译过的：**

- 全部 Android 代码。开发环境里没有 Android SDK，
  拼写、import、Room 注解处理这类问题要第一次 `./gradlew` 才会暴露。

**已知做不到的**（都写在各自文档的「还剩下的口子」一节）：

- 挡不住 root
- 通话记录从系统 CallLog 删除有几十毫秒窗口，Android 平台限制
- 服务器能看到元数据：条数、密文大小、同步时间、你的 IP

---

## 代码量

```
服务端  TypeScript   约 2900 行（含测试）
网页端  JS + HTML    约 2900 行
通讯录  Kotlin       约 4300 行
电话    Kotlin       约 1900 行
文档                 约 3000 行
```
