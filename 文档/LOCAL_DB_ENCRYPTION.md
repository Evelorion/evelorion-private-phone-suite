# 本地数据库加密

> ← 文档索引：[README.md](README.md)

手机上那个存私密联系人的 `local_contacts.db`，之前是明文的。这份文档说明怎么把它加密，
以及加密之后到底挡住了什么、没挡住什么。

---

## 1. 先说清楚它挡什么

| 攻击场景 | 加密前 | 加密后 |
|---|---|---|
| `adb backup` 导出 App 数据 | 直接拿到全部联系人 | 已被 `allowBackup=false` 挡住；就算漏了也只是一堆密文 |
| 手机丢了，被人拆开读闪存 | 关机状态下 Android 的 FBE 已有一层保护，但 App 私有目录在解锁后就是明文 | 多一层独立加密，密钥在 TEE 里 |
| 别的 App 通过某个路径遍历漏洞读到文件 | 直接拿到全部联系人 | 拿到密文，没用 |
| 恶意刷机包 / 定制 recovery 提取分区 | 直接拿到 | 拿到密文 |
| **设备被 root，攻击者能注入本 App 进程** | 拿到 | **照样拿到** |

最后一行是这套方案的天花板，必须说清楚：**默认模式下加密挡不住 root**。

原因很简单——App 自己得能打开数据库，密钥就必须在 App 能拿到的地方。
攻击者只要能让 App 替他解密（注入进程、hook `SQLiteDatabase`、或者直接 dump 内存），
密钥在不在 TEE 里都一样。

想真正挡住 root，只有一条路：**密钥来自用户脑子里的口令**（下面的模式 C）。
代价是解锁之前整个通讯录不可用。

---

## 2. 三种密钥模式

代码里三种都实现了，在设置页切换。

### 模式 A：Keystore 保管（默认）

数据库口令是一串随机的 32 字节，被 Android Keystore 里的一把 AES 密钥包着。
Keystore 密钥不要求用户验证，所以开机就能用。

- 通讯录正常打开，电话 App 的来电显示正常
- 挡住所有「拿到文件」类的攻击
- 恢复出厂 / 卸载 App → Keystore 密钥消失 → 数据库永久打不开（这是特性不是 bug）

### 模式 B：Keystore + 屏幕锁验证

同上，但 Keystore 密钥加了 `setUserAuthenticationRequired(true)`，
一次验证在 5 分钟内有效。

- 手机被拿走且处于锁屏状态时，就算 root 了也开不了库（Keystore 会拒绝）
- 代价：**开机后第一次用通讯录要验证一次指纹/PIN**，
  在那之前来电显示查不到私密联系人的名字

### 模式 C：由主口令派生

数据库口令 = `HKDF(主密钥, salt, "fc.localdb.key.v1")`，
和同步用的 DEK 同源但做了域分离（拿到其中一个推不出另一个）。

Keystore 里什么都不存。这是唯一能挡住 root 的模式。

代价很实在，要想清楚再开：

- 每次进程启动都要输主口令才能看联系人
- 后台同步在解锁前跑不了
- 电话 App 在解锁前完全查不到私密联系人

---

## 3. 两种接法

### 方案 A：反射注入（代码里实现的这个，不用改构建）

问题在于 `ContactsDatabase` 是 `org.fossify:commons` 这个 Maven 依赖里的类，
它的 `getInstance()` 内部直接 `Room.databaseBuilder(...).build()`，
没留任何注入 `openHelperFactory` 的口子。

但它把实例存在 companion object 的静态字段里，逻辑是「为 null 才创建」。
所以只要在**任何人第一次调用 `getInstance()` 之前**，把我们用 SQLCipher 工厂
建好的实例塞进那个字段，之后全 App 拿到的就都是加密版本——
commons 里的 `LocalContactsHelper`、`ContactsHelper` 一行都不用改。

实现在 `EncryptedDatabases.install()`，在 `ContactsApp.attachBaseContext()` 里调用。

**这条路的三个维护成本，别忽略：**

1. **升级 commons 要回来核对。**
   `buildContactsDatabase()` 复刻了 commons 里的 builder 配置（版本号、两个 migration）。
   commons 改了 schema 而我们没跟上，Room 会直接崩。
   当前对齐的是 **commons 6.1.6，ContactsDatabase version = 3**。

2. **R8 会改字段名。**
   所以代码里是按「类型能装下 ContactsDatabase」找字段，不是按名字。
   但仍然建议加 keep 规则（见下）。

3. **`MainActivity` 里有一处 `ContactsDatabase.destroyInstance()`**
   （原代码第 159 行）。它把静态字段清成 null，下次 `getInstance()` 会重建一个**明文**实例。
   那一行后面必须补一次 `EncryptedDatabases.install(this)`。

### 方案 B：composite build + 打补丁（更稳，推荐长期用这个）

把 commons 拉成本地源码依赖，直接给它开一个注入口。这不是「fork 一个库自己维护」——
是一个 pinned 的 git submodule 加一个十几行的补丁，升级时 rebase 一下就行。

```bash
git submodule add https://github.com/FossifyOrg/Commons.git third_party/Commons
cd third_party/Commons && git checkout <你在用的那个 tag>
```

`settings.gradle.kts`：

```kotlin
includeBuild("third_party/Commons") {
    dependencySubstitution {
        substitute(module("org.fossify:commons")).using(project(":commons"))
    }
}
```

给 `third_party/Commons` 打这个补丁：

```diff
--- a/commons/src/main/kotlin/org/fossify/commons/databases/ContactsDatabase.kt
+++ b/commons/src/main/kotlin/org/fossify/commons/databases/ContactsDatabase.kt
@@
     companion object {
         private var db: ContactsDatabase? = null
+
+        /**
+         * 允许宿主 App 注入一个 SupportSQLiteOpenHelper.Factory，
+         * 用来把本地库跑在 SQLCipher 上。必须在第一次 getInstance() 之前设置。
+         */
+        @JvmStatic
+        var openHelperFactory: SupportSQLiteOpenHelper.Factory? = null
 
         fun getInstance(context: Context): ContactsDatabase {
             if (db == null) {
                 synchronized(ContactsDatabase::class) {
                     if (db == null) {
                         db = Room.databaseBuilder(context.applicationContext, ContactsDatabase::class.java, "local_contacts.db")
+                            .apply { openHelperFactory?.let { openHelperFactory(it) } }
                             .addCallback(object : Callback() {
```

（顶部加一行 `import androidx.sqlite.db.SupportSQLiteOpenHelper`。）

然后 `EncryptedDatabases.install()` 里把反射那段换成：

```kotlin
ContactsDatabase.openHelperFactory = SupportOpenHelperFactory(passphrase)
```

方案 B 没有上面那三个维护成本，代价是构建时间变长（要编译 commons）。
我建议先用方案 A 跑通，确认整体可用之后再切到 B。

---

## 4. 已有数据怎么办

用户手机上已经有联系人了，不能因为启用加密就清空重来。

`DatabaseEncryptionMigrator` 走的是 SQLCipher 官方的 `sqlcipher_export` 路子：

```
1. 用空口令打开明文库（SQLCipher 允许这样打开未加密文件）
2. ATTACH 一个带口令的新库
3. SELECT sqlcipher_export('encrypted')  搬走所有表和数据
4. PRAGMA encrypted.user_version = N     ← 这一步最容易漏
5. DETACH，删明文，改名
```

**第 4 步不能省。** `sqlcipher_export` 不会带 `user_version`，
而 Room 靠这个值判断 schema 版本。漏了它 Room 会以为这是个全新的空库，
直接按 version 0 重建表——用户的联系人就没了。

整个过程写在临时文件里，全部成功并通过校验（表数量 > 0、user_version 对得上）
才替换原文件。任何一步失败都会删掉临时文件，原库不动。

反向的 `decryptInPlace()` 也有，用户想关掉加密时用。

---

## 5. 接入步骤

### 依赖

`gradle/libs.versions.toml`：

```toml
[versions]
sqlcipher = "4.6.1"
androidxSqlite = "2.4.0"

[libraries]
sqlcipher = { module = "net.zetetic:sqlcipher-android", version.ref = "sqlcipher" }
androidx-sqlite = { module = "androidx.sqlite:sqlite", version.ref = "androidxSqlite" }
```

`app/build.gradle.kts`：

```kotlin
dependencies {
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)
}
```

> `sqlcipher-android` 带 4 个 ABI 的 .so，APK 会大 5~7 MB。
> 在意体积的话开 ABI split，或者只保留 `arm64-v8a`。

### 拷贝文件

```
android/contacts/src/main/kotlin/org/fossify/contacts/sync/localdb/**   （新增）
android/contacts/src/main/kotlin/org/fossify/contacts/ContactsApp.kt     （新增）
```

### AndroidManifest

```xml
<application
    android:name=".ContactsApp"          <!-- 原来是 org.fossify.commons.FossifyApp -->
    android:allowBackup="false"
    ... >
```

### 改 MainActivity

原来第 159 行附近：

```kotlin
ContactsDatabase.destroyInstance()
EncryptedDatabases.install(this)   // ← 补这一行，否则重建出来的是明文库
```

### ProGuard

```proguard
# SQLCipher 的 JNI 绑定
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# 反射注入要能找到这个类和它的字段
-keep class org.fossify.commons.databases.ContactsDatabase { *; }
-keep class org.fossify.commons.databases.ContactsDatabase$Companion { *; }
```

---

## 6. 验证

1. **迁移没丢数据** —— 装旧版本，加 10 个联系人，覆盖安装新版本，10 个都还在。
2. **文件真的加密了**：

   ```bash
   adb shell run-as org.fossify.contacts strings databases/local_contacts.db | grep 张三
   ```

   应该什么都搜不到。加密前这条命令会直接把名字打出来。

3. **口令确实在 Keystore 里** —— `strings` 搜 `fc_localdb` 的 SharedPreferences，
   拿到的是 base64 密文，不是可用的口令。
4. **恢复出厂后打不开** —— 这是预期行为，不是 bug。所以**服务器同步和恢复码是必须的**，
   它们才是真正的备份。
5. **降级路径** —— 手动删掉 Keystore 条目（或换个锁屏方式），确认 App 不崩，
   而是提示用户数据库无法打开。

---

## 7. 还剩下的口子

- **同步库 `fc_sync.db` 里的 `base_payload` 是完整的联系人明文快照。**
  已经跟着一起加密了（`SyncDatabase.get()` 里接了同一个工厂），
  但如果加密启用失败退回明文，这个库也会是明文的。

- **内存里的明文挡不住。** 联系人显示在屏幕上时它就在堆里。
  这是所有本地加密方案的共同边界。

- **默认模式挡不住 root**，见第 1 节。想挡就开模式 C，并接受它的代价。

- **这部分代码没有编译验证过**（开发环境里没有 Android SDK）。
  SQLCipher 的具体 API 名（`SupportOpenHelperFactory` 的构造签名、
  `SQLiteDatabase.openOrCreateDatabase` 的重载）在 4.6.x 上是对的，
  但换版本可能要调。第一次 `./gradlew assembleDebug` 会暴露出来。
