# 通讯录 App（com.evelorion.contacts）

端到端加密的联系人应用。它是整个项目的**密钥中心** ——
主口令、DEK、恢复码都归它管，电话 App 只向它借一把子密钥。

先读 `../开发指南.md`，那里有编译流程和踩过的坑。这份只讲这个模块。

---

## 目录结构

```
app/src/main/kotlin/com/evelorion/contacts/
├─ ContactsApp.kt           崩溃兜底 → 装数据库加密层 → 恢复 DEK → 排周期同步
│                           （顺序不能换，见下）
├─ data/
│   ContactRepository        UI 唯一的数据入口
│   PrivateContactStore      加密库的读写（**不碰系统通讯录**）
│   Pinyin                   汉字 → 拼音首字母
│   DuplicateFinder / VCard  查重合并、vCard 导入导出
│
├─ privacy/
│   PrivateContactsProvider  给电话 App 读联系人 / 按号码查人
│   （VaultBridgeProvider 在 sync/bridge/ 下，见下面的坑）
│
├─ helpers/PrivacyGuard      调用方签名校验
│
├─ sync/                    ── 端到端加密同步 ──
│   VaultManager              保险库：解锁、改口令、恢复码
│   crypto/                   Argon2 + HKDF + AES-GCM，完整版
│   net/SyncApi + SessionStore
│   db/SyncDatabase           同步状态、盲索引、base_payload 快照
│   engine/SyncEngine         扫描 → 拉 → 推 → 重建盲索引
│   engine/SyncManifest       检测服务器藏记录
│   localdb/                  SQLCipher 整库加密
│   bridge/VaultBridgeProvider  把 calls 子密钥交给电话 App
│
└─ ui/                      M3 界面（XML 视图，不是 Compose）
    Bg.kt                    安全的后台线程池
    CrashReport.kt           崩溃记录 + 显示页
```

---

## 启动顺序不能改

```kotlin
attachBaseContext:
    CrashReport.install()           // 最早，否则更早的崩溃记不下来
    EncryptedDatabases.install()    // 必须在这里，不能放 onCreate

onCreate:
    M3Theme.applyDarkModeGlobally()
    VaultManager.unlockFromCache()  // 从 Keystore 取回 DEK
    SyncScheduler.schedulePeriodic()
```

**`EncryptedDatabases.install()` 为什么必须在 `attachBaseContext`：**
ContentProvider 的 `onCreate` 比 `Application.onCreate` **更早**执行，
而我们的 provider 会读数据库。放在 `onCreate` 里的话，provider 已经先一步
用明文方式把库打开了。

---

## 几个关键设计

### 数据源只有一个

这个 App **只管自己的加密库**，完全不碰系统通讯录。
想把系统里的搬进来，用「设置 → 从系统通讯录导入」，那是一次性操作，
而且分两步（先导入，确认没问题后再单独决定删不删原件）。

混着读会让「这条到底加不加密、会不会同步」变得含糊。

### 数据库加密层是靠反射注入的

`ContactsDatabase` 是 `org.fossify:commons` 依赖里的类，它的 `getInstance()`
内部直接 `Room.databaseBuilder(...).build()`，没有留注入 `openHelperFactory`
的口子。

做法是在任何人第一次调 `getInstance()` **之前**，把我们用 SQLCipher 工厂
建好的实例塞进它的静态字段。之后全 App 拿到的都是加密版本。

**维护成本在这里：** 升级 commons 版本时要回头核对
`EncryptedDatabases.buildContactsDatabase()` 里复刻的那份 builder 配置
（migrations、版本号）。对不上 Room 会崩。

### holdsOurInstance 必须做同一性判断

不能只判非空。commons 那边有代码会调 `destroyInstance()` 把字段清空，
之后 `getInstance()` 会新建一个**明文**实例 —— 字段非空，但那不是我们的。

而明文实例打开加密文件时，Room 会认定「数据库损坏」**直接删掉重建**，
不抛异常、不留提示。那是一次静默的数据清零。详见 `../开发指南.md` 坑 #11。

---

## 跨应用接口

| provider | authority | 路径 |
| --- | --- | --- |
| `privacy.PrivateContactsProvider` | `com.evelorion.contacts.privateprovider` | `/contacts` 全部联系人（含分组名）<br>`/lookup/<号码>` 按号码查人 |
| `sync.bridge.VaultBridgeProvider` | `com.evelorion.contacts.vaultbridge` | `/session` baseUrl + 短期令牌 + calls 子密钥 |

> **注意 manifest 里 VaultBridgeProvider 的 `android:name` 指向的是
> `.sync.bridge.VaultBridgeProvider`。**
> `.privacy` 包下曾经有一个同名的 29 行空壳，manifest 一度指向它，
> 结果「共用同步账号」这条通道一直返回空 —— 而且不报错，纯粹静默失效。
> 那个空壳已经删掉了。

### 为什么按号码查人要在这一侧做

号码在数据库里没有明文列，能查是靠**盲索引**：
`HMAC(HKDF(DEK, salt), 归一化号码)`。算它需要 DEK，而 DEK 只在这边。

电话 App 只能把号码递过来。这不算泄露：那个号码本来就是它从来电里拿到的。
反过来把索引密钥交给它，它就能离线枚举任意号码是否在通讯录里 ——
权限扩大了，收益只是省一次跨进程调用。

### 拒绝时返回空 Cursor，不要抛异常

抛异常会让调用方闪退，反而等于告诉对方「这里确实有东西」。

---

## 删除的规矩（重要）

`ContactRepository.deleteContact` 是**唯一**权威的删除来源，它会同时：

1. 从加密库删除
2. 调 `markDeletedForSync()` 给同步层打墓碑标记

**同步引擎不再从「扫描时找不到」推断删除。** 扫不到的记录会走
`SyncEngine.healVanished`，**用上次同步的 `base_payload` 快照重建出来**，
并且绝不推墓碑。

原因是一次真实的数据丢失事故，详见 `../开发指南.md` 第四节。
**新写同步逻辑前请先读那一节。**

---

## 构建

**Android Studio → Open → 选这个文件夹 → Sync → Build。**
标准 Gradle 工程，wrapper 和签名证书都在里面。
需要 JDK 17+、Android SDK 36、支持 AGP 9.1.0 的 Android Studio。

依赖里有 JitPack（commons 和 IndicatorFastScroll 发在那里），
首次 sync 需要联网，公司网络挡 JitPack 的话会卡在这一步。

---

## 已知未完成 / 待办

- `PrivacyGuard.pinnedCertSha256` 是空的，现在退回用 `checkSignatures`。
  发布前要填真实签名证书指纹。
- 用户的账号建于 `recovery_auth_hash` 上线之前，**需要改一次主口令**，
  否则恢复码登录不了服务器。
- 通讯录的 APK 还没做 `abiFilters` 瘦身（现在 60 MB，砍到 arm64 应该能到 ~15 MB）。
  电话 App 那边已经做了，照抄即可。
