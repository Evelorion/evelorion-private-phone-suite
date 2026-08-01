# 通话记录保护

> ← 文档索引：[README.md](README.md)

面向 `Evelorion/Phone` 的 `private-ui-edition` 分支，和通讯录那套改动配合使用。

---

## 1. 先说一件做不到的事

**App 没法阻止系统写 CallLog。**

通话结束时是 telecom 框架自己往 `CallLog.Calls` 里插一条，这个动作发生在系统进程里。
第三方 App —— 哪怕是默认拨号器 —— 没有任何钩子能拦住它。Android 从来没提供过这种 API。

所以这套方案能做的只有「写进去之后尽快删掉」，追求的是把窗口压到最短，而不是消灭窗口。
谁说能做到零窗口，那是在骗人。

现在的窗口大概是**几十毫秒**（ContentObserver 回调到删除完成）。
之前的实现是**秒级**，下面说为什么。

---

## 2. 现状里发现的问题

### 2.1 通话历史存在明文 XML 里 —— 这条最严重

`PrivateCallHistoryStore` 把整个通话历史序列化成一个 JSON 字符串，
塞进 `config.privateCallHistoryEntries`，也就是
`/data/data/org.fossify.phone/shared_prefs/*.xml`。

配合当时 manifest 里的 `allowBackup="true"`：

```bash
adb backup -f out.ab org.fossify.phone
```

一条命令，号码、时间戳、通话时长全是可读的。

**前面所有从系统 CallLog 里删记录的功夫，被这一行配置完全绕过。**

而且它有 500 条上限，超了静默丢弃，用户不会收到任何提示。

### 2.2 清理时机太晚

原来是在 `CallService.onCallRemoved()` 里去查 CallLog 再删。问题是
`onCallRemoved` 和框架写入 CallLog 之间**没有顺序保证** —— 经常要等几百毫秒
甚至一秒多，还得靠 `PRIVATE_CALL_LOOKBACK_MS` 这种「回溯最近 N 秒」的模糊匹配去找。

### 2.3 只保护私密联系人

陌生号码、骚扰电话、你没存过的号码，通话记录全都明文留在系统 CallLog 里。
别的 App 照样能看到你几点跟哪个号码通了多久的话。

---

## 3. 改动

### 3.1 存储换成加密数据库

`PrivateCallDatabase`（Room + SQLCipher），密钥用 Android Keystore 包着。
和通讯录那边是同一套做法，但**必须是独立的一把 Keystore 密钥** ——
Keystore 是按 App 隔离的，两个 App 互相拿不到对方的条目。

`PrivateCallStore.migrateFromLegacyPrefs()` 负责把老的 SharedPreferences JSON
搬进来，然后**擦掉原文**。这一步不能省：不擦的话，就算新库加密了，
旧的明文副本还躺在 shared_prefs 里。

解析失败时会保留原文不删——宁可留着明文也不能丢数据，然后在日志里报错。

没有条数上限了。

### 3.2 用 ContentObserver 抢时间

`CallLogGuard` 注册 `ContentObserver` 到 `CallLog.Calls.CONTENT_URI`，
一被写入就回调，把窗口从秒级压到几十毫秒。

原来的通话结束回调保留下来当兜底 —— observer 可能因为进程被杀而漏掉。
App 启动时也会补扫一次。

顺序上有个细节：**先写进私有库，再删系统记录**。反过来的话，
如果写库失败这条通话记录就彻底没了。代码里这个顺序不能改。

### 3.3 保护范围做成三档开关

```
PRIVATE_ONLY         只保护私密联系人的通话（原来的行为）
PRIVATE_AND_UNKNOWN  私密联系人 + 通讯录里查不到的号码（默认）
ALL                  全部拿走，系统 CallLog 基本为空
```

`ALL` 的代价要在设置页写清楚：

- 部分 ROM 的通话记录小组件会空白
- 车机通过蓝牙 PBAP 同步通话记录会同步不到东西
- 第三方拨号盘、来电识别类 App 会失去历史

有个防御性细节：判断「号码在不在系统通讯录里」需要 `READ_CONTACTS`。
没这个权限时 `isKnownSystemContact` 返回 `true` 而不是 `false` ——
返回 false 会让所有通话都被当成未知号码全部拿走，等于偷偷把用户切到了 `ALL` 档。
宁可少保护，也不做用户没同意的事。

### 3.4 关掉备份

`allowBackup="false"` + 两个 rules 文件全量排除。和通讯录那边一样。

### 3.5 端到端加密同步

复用同一台服务器、同一个账号，但走 `collection = "calls"`，用不同的密钥。

---

## 4. 密钥怎么过去的

电话 App **不再单独搞一套账号**。用户不该记两个口令、抄两份恢复码。

通讯录暴露一个 signature 权限保护的 ContentProvider（`VaultBridgeProvider`），
电话 App 通过 `VaultBridge` 去要凭据：

```
通讯录持有  DEK
              │
              │ HKDF(DEK, "fc.collection.calls.v2")
              ▼
         calls 子密钥  ──── 只把这个交给电话 App
```

**交出去的不是 DEK 本身。** 有子密钥能加解密通话记录，但推不回 DEK，
所以电话 App 解不开任何一条联系人。

万一电话 App 出漏洞被攻破，攻击者拿到的是通话记录，不是整个通讯录。

访问令牌给的是 15 分钟就过期的那个，**刷新令牌不给** ——
电话 App 也就没法长期独占账号访问权。过期了下次同步再要一个。

服务端因此需要区分两类数据（`collection` 列）。混在一起返回的话，
通讯录会拉到一堆 `calls` 的密文而解不开，反之亦然。
这个改动已经做了，`npm run test:e2e` 里有 10 项断言专门验证隔离，
包括「通讯录的 DEK 解不开通话记录」和「两个 collection 的 uuid 可以重名而互不干扰」。

### 通话记录不做三方合并

联系人要三方合并是因为两台设备可能同时编辑同一个联系人的不同字段。
通话记录是**只追加的事实记录** —— 不存在「两边把同一通电话改得不一样」。

撞冲突只可能是同一条被推了两次，直接采用服务端版本即可。

uuid 由 `(归一化号码, 开始时间取整到秒, 类型)` 确定性推导，
所以同一通电话被 observer 和兜底扫描各抓一次也不会产生两条。

---

## 5. 接入步骤

### 依赖（`app/build.gradle.kts`）

```kotlin
dependencies {
    implementation(libs.okhttp)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)
}
```

版本号沿用通讯录那边 `libs.versions.toml` 里的定义。

### 拷贝文件

```
android/phone/src/main/kotlin/org/fossify/phone/privatecalls/**       （新增）
android/phone/src/main/kotlin/org/fossify/phone/privatecontacts/**    （新增，之前那批）
android/phone/src/main/kotlin/org/fossify/phone/PhoneApp.kt           （新增）
android/phone/src/main/res/xml/backup_rules.xml                       （新增）
android/phone/src/main/res/xml/data_extraction_rules.xml              （新增）
```

### Contacts 仓库这边

```
android/contacts/.../sync/bridge/VaultBridgeProvider.kt               （新增）
```

manifest 里声明权限和注册 provider：

```xml
<permission
    android:name="org.fossify.permission.VAULT_BRIDGE"
    android:description="@string/vault_bridge_permission_description"
    android:label="@string/vault_bridge_permission_label"
    android:protectionLevel="signature" />

<provider
    android:name=".sync.bridge.VaultBridgeProvider"
    android:authorities="org.fossify.contacts.vaultbridge"
    android:exported="true"
    android:readPermission="org.fossify.permission.VAULT_BRIDGE" />
```

### Phone 的 AndroidManifest

```xml
<uses-permission android:name="org.fossify.permission.VAULT_BRIDGE" />

<application
    android:name=".PhoneApp"                        <!-- 原来是 org.fossify.commons.FossifyApp -->
    android:allowBackup="false"                     <!-- 原来是 true -->
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules"
    ... >
```

设置页：

```xml
<activity
    android:name=".privatecalls.CallGuardSettingsActivity"
    android:configChanges="orientation"
    android:exported="false"
    android:label="@string/call_guard_title"
    android:parentActivityName=".activities.SettingsActivity" />
```

`<queries>` 里已经有 `org.fossify.contacts` 了，不用改。

> **安装顺序无所谓** —— 两个 App 都声明了这两个权限，谁先装谁定义。
>
> 之所以要两边都声明：自定义权限的规则是「请求时如果没人定义过它，请求被静默丢弃，
> 之后定义方装上也不补授予」。两边都声明就绕开了这个坑。
>
> 附带好处：Android 只允许**签名相同**的包声明同名权限，签名不一致会直接
> `INSTALL_FAILED_DUPLICATE_PERMISSION`。也就是说签名配错会在**安装时就报错**，
> 而不是装完之后才发现读不到数据、还查不出原因。

### 替换调用点

| 原来 | 改成 |
|---|---|
| `PrivateCallHistoryStore(context)` | `PrivateCallStore(context)`（接口刻意保持一致） |
| `RecentsHelper.protectPrivateCallHistory(call)` | `CallLogGuard.get(context).sweep()` |
| `CallService.onCallRemoved` 里那行 | 同上，放后台线程 |
| `SettingsActivity` 里那个 `settings_private_call_history_protection` 开关 | 跳转到 `CallGuardSettingsActivity`（保护范围有三档，一个开关表达不了） |

同步的调度已经接好了：`PhoneApp` 启动时排周期任务（默认 6 小时），
`CallLogGuard` 每次抓到新记录会触发一次立即同步。
不需要额外埋点。

`PrivateCallHistoryStore.kt` 和 `Config` 里的 `privateCallHistoryEntries`
在迁移代码跑过之后就可以删了，但**别急着删** ——
留一个版本周期，确保所有用户都升级过一次，否则没升级过的人数据会丢。

### ProGuard

```proguard
-keep class net.zetetic.database.** { *; }
-keep class org.fossify.phone.privatecalls.** { *; }
```

---

## 6. 验证

1. **明文 XML 清干净了**

   ```bash
   adb shell run-as org.fossify.phone cat shared_prefs/*.xml | grep -i privateCallHistory
   ```

   升级后应该是空的。

2. **数据库真的加密了**

   ```bash
   adb shell run-as org.fossify.phone strings databases/fc_private_calls.db | grep 138
   ```

   搜不到号码。

3. **系统 CallLog 里没有**

   打一通电话给私密联系人，挂断后：

   ```bash
   adb shell content query --uri content://call_log/calls --projection number,date
   ```

   那条不该出现。

4. **窗口有多大** —— 上面那条命令写成循环每 50ms 跑一次，看那条记录存在了几个周期。
   实测应该在 1~3 个周期内消失。这个数字诚实地反映了残留风险。

5. **跨 App 密钥隔离** —— 在电话 App 里尝试用拿到的子密钥去解一条联系人记录，
   必须失败。

6. **双设备同步** —— 两台手机装同一套，A 打个电话，B 那边通话记录里出现。

---

## 7. 还剩下的口子

- **那几十毫秒。** 另一个持有 `READ_CALL_LOG` 的 App 如果正好在轮询，能读到。
  这是 Android 平台的限制，没有解法。

- **App 进程被杀时 observer 就没了**，那段时间的通话记录会留在系统里，
  等下次启动的兜底扫描才清掉。国产 ROM 上这个窗口可能是几小时。
  把 App 加进电池优化白名单能显著改善。

- **部分厂商 ROM 有自己的通话记录数据库**，不走标准 `CallLog`。
  删了标准表不代表厂商那份也没了。这个没有通用解法，
  需要针对具体 ROM 排查。

- **运营商那边的通话详单跟这个完全无关**，本地怎么删都改变不了。
  如果威胁模型里包括运营商或执法调取，这套方案帮不上忙。

- **默认模式挡不住 root**，和通讯录那边一样，见
  [`LOCAL_DB_ENCRYPTION.md`](LOCAL_DB_ENCRYPTION.md) 第 1 节。

- **这部分代码没有编译验证过**（开发环境里没有 Android SDK）。
  服务端的 collection 隔离**跑过测试**，71 项断言全绿。
