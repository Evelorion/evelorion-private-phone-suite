# 短信 App · 开发交接文档

给接手继续开发的人（或 AI）看的。读完这份就能上手改代码，不用再去翻别的地方。

---

## 0. 这是什么 / 当前状态

一个功能完整的 Android 短信应用，Jetpack Compose + Material 3。
从一份纯 UI 设计稿（1a–1h）起步，现在已经接了真实的短信收发、本地数据库、系统短信/联系人导入。

**能用的：** 收发真实短信（含长短信分段、发送/送达回执）、导入系统已有短信、本地持久化、
搜索与验证码提取、通知（含快捷回复）、双卡选择、骚扰拦截、三套列表皮肤、动态取色 + 手动主题色。

**没做完的：** 见 [§8 待办与已知限制](#8-待办与已知限制)。最要紧的一条是
**「设为默认短信应用」在 OPPO/OnePlus ColorOS 上尚未验证通过**。

---

## 1. 环境与构建

| 项 | 值 |
|---|---|
| JDK | **17**（AGP 8.5 要求，用 16 或 11 会直接报错） |
| AGP | 8.5.2 |
| Kotlin | 2.0.20（含 `kotlin.plugin.compose`，不再需要 composeOptions） |
| KSP | 2.0.20-1.0.25（Room 编译器用） |
| Compose BOM | 2024.09.00 |
| compileSdk / targetSdk | 34 |
| minSdk | 24 |
| Gradle | 8.7（wrapper 已提交，直接 `./gradlew`） |

```bash
./gradlew assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:compileDebugKotlin # 只查编译错误，快
```

依赖用 version catalog 管理，见 `gradle/libs.versions.toml`。加依赖要改两处：
catalog 里声明 + `app/build.gradle.kts` 里引用。

### 1.1 包名的坑（别改回去）

```kotlin
namespace     = "com.example.sms"    // 只影响 R 类和源码包路径
applicationId = "com.miko.messages"  // 系统看到的包名
```

两者故意不一致。**`applicationId` 绝对不能改回 `com.example.*`** ——
ColorOS / OriginOS / MIUI 会把 `com.example` 开头的包判定为「测试应用」直接拦截安装，
提示只有笼统的一句「应用未安装」，查不出原因。

源码里的包声明仍是 `com.example.sms.*`，这是 `namespace`，跟安装无关，不用动。

### 1.2 签名

`signingConfigs.debug` 里显式开了 v1 + v2 + v3。AGP 在 `minSdk >= 24` 时默认只打 v2，
但部分国产 ROM 的安装器仍会校验 v1。验证方式：

```bash
apksigner verify --min-sdk-version 21 --verbose app-debug.apk
# 必须三个 scheme 都是 true（不加 --min-sdk-version 21 的话 v1 会显示 false，那是正常的）
```

---

## 2. 架构总览

单向数据流，没有引入 DI 框架，用一个手写的 service locator。

```
        ┌─────────────── UI (Compose) ───────────────┐
        │  Screen（纯展示，无状态）                    │
        │      ↑ State            ↓ 事件回调           │
        │  ViewModel（StateFlow）                     │
        └────────────────┬───────────────────────────┘
                         │
                  MessageRepository          ← 唯一的数据出入口
                         │
        ┌────────────────┼────────────────┐
        │                │                │
   Room (sms.db)   SystemSmsStore   ContactsRepository
                   (系统短信库)      (系统联系人)

        SmsSender ──→ SmsManager ──→ 运营商
             ↑                          │
        SmsStatusReceiver ←─────────────┘（发送/送达回执）

        SmsDeliverReceiver ←── 系统投递的新短信 ──→ Repository ──→ 通知
```

**规矩：** UI 不直接碰 Room / ContentProvider，一律走 `MessageRepository`。
Screen 是纯函数，所有状态从 ViewModel 的 `StateFlow` 来，所有交互通过 lambda 回调出去。

### 2.1 Graph（service locator）

`Graph.kt` —— 全局单例容器，避免为了 DI 引进 Hilt。

```kotlin
Graph.init(context)        // Application.onCreate 和每个 BroadcastReceiver 里都要先调
Graph.repository           // MessageRepository
Graph.settings             // SettingsStore
Graph.sender               // SmsSender
Graph.applicationScope     // 进程级 CoroutineScope，给 Receiver 用
```

BroadcastReceiver 里必须 `Graph.init(context)` 再用 —— 进程可能是被广播拉起来的，
`Application.onCreate` 不保证已经跑过。

---

## 3. 目录与文件职责

```
app/src/main/java/com/example/sms/
├── Graph.kt                    依赖容器
├── SmsApplication.kt           初始化 Graph + 建通知渠道
├── MainActivity.kt             导航、运行时权限、默认短信应用引导（446 行，最大的文件）
│
├── data/
│   ├── db/
│   │   ├── Entities.kt         3 张表 + 3 个枚举
│   │   ├── Daos.kt             ConversationDao / MessageDao / BlockedLogDao
│   │   ├── Converters.kt       枚举 ↔ String
│   │   └── AppDatabase.kt      Room 实例（单例）
│   ├── repo/MessageRepository.kt   唯一数据出入口（260 行）
│   ├── system/
│   │   ├── SystemSmsStore.kt   读写系统短信库 Telephony.Sms
│   │   └── ContactsRepository.kt  读系统联系人
│   └── prefs/SettingsStore.kt  DataStore 设置
│
├── sms/                        短信引擎，见 §5
│   ├── SmsSender.kt            发送（支持指定 SIM）
│   ├── SmsDeliverReceiver.kt   3 个接收器都在这个文件里
│   ├── SmsStatusReceiver.kt    发送/送达回执
│   ├── HeadlessSmsSendService.kt  来电快捷回复
│   ├── NotificationHelper.kt   通知构建
│   └── NotificationActionReceiver.kt  通知上的回复/已读/复制
│
├── ui/
│   ├── theme/Theme.kt          配色三级优先级
│   ├── theme/SeedColors.kt     手动主题色候选
│   ├── common/                 头像、UI 模型、ViewModel 工厂
│   ├── conversations/          列表 1a / 1b / 1c + ViewModel
│   ├── chat/                   会话详情 + ViewModel
│   ├── newmsg/                 新建短信 + ViewModel
│   ├── search/                 搜索 + ViewModel
│   └── settings/               设置 + ViewModel
│
└── util/
    ├── PhoneUtils.kt           号码归一化（+86、空格、末8位匹配）
    ├── Classifier.kt           会话分类：个人/交易/推广
    ├── CodeExtractor.kt        验证码/取件码正则抽取
    ├── TimeFormat.kt           相对时间格式化
    ├── SimUtils.kt             读取可用 SIM 卡
    ├── SmsRoleCheck.kt         默认短信应用资格自检
    └── VoicePlayer.kt          语音消息播放（录音已移除）
```

---

## 4. 数据层

### 4.1 Room（`sms.db`，version 1）

落在应用私有 `databases/` 目录，**不是缓存目录**，杀进程/重启都在。
`exportSchema = true`，schema JSON 在 `app/schemas/`。

**`conversations`** — PK `threadId`（Long，App 自己生成，见下方注意）

| 字段 | 说明 |
|---|---|
| `address` | 多收件人用 `;` 分隔，存归一化后的号码 |
| `displayName` | 联系人名，查不到就是格式化号码 |
| `snippet` / `snippetIsImage` | 列表预览 |
| `lastTime` | 排序依据 |
| `unreadCount` | 由 `refreshSummary` 重算，不手动累加 |
| `category` | `MsgCategory`，1b 的过滤 chips 用 |
| `pinned` / `muted` / `blocked` | 会话开关 |
| `draft` | 草稿，退出会话时保存 |

**`messages`** — PK `id`（自增），索引 `threadId` / `time` / **`systemId` (unique)**

`systemId` 是系统短信库的 `_id`，导入时用它去重（unique 索引 + `OnConflictStrategy.IGNORE`）。
App 自己产生的消息该字段为 null。

| 字段 | 说明 |
|---|---|
| `outgoing` | true=自己发的 |
| `status` | `MsgStatus`：RECEIVED / PENDING / SENT / DELIVERED / FAILED |
| `type` | `MsgType`：TEXT / IMAGE / VOICE |
| `attachmentUri` | 图片/语音的 uri |
| `reaction` | 表情回应，如 `"❤️"` |
| `errorMessage` | 发送失败原因 |

**`blocked_log`** — 被拦截的推广短信只计数，不进会话。设置页的「本月已拦截 N 条」读这张表。

> ⚠️ **注意：`threadId` 是 App 自己生成的**（`nextThreadId() = MAX+1`），
> **不是**系统短信库的 threadId。导入时按系统 threadId 分组，但落库时重新映射。
> 如果以后要和系统库双向同步，这里需要重构。

### 4.2 迁移策略

目前是 `fallbackToDestructiveMigration()` —— **改表结构会清空用户数据**。
发布正式版之前必须改成写 `Migration`，并把 version 加 1。

### 4.3 DataStore（`settings`）

`data/prefs/SettingsStore.kt`，落在 `files/datastore/settings.preferences_pb`。

| key | 类型 | 默认 | 用途 |
|---|---|---|---|
| `notifications_enabled` | Bool | true | 新短信通知 |
| `block_spam` | Bool | false | 骚扰拦截 |
| `rcs_enabled` | Bool | true | 仅显示层标识 |
| `smart_reply_enabled` | Bool | true | 智能回复候选 |
| `list_style` | String | LIST_1A | 三套列表皮肤 |
| `theme_mode` | String | SYSTEM | 深浅色 |
| `dynamic_color` | Bool | true | 跟随壁纸取色 |
| `seed_color` | Int | 0 | 手动主题色（0=不用） |
| `send_sub_id` | Int | -1 | 发送用的 SIM（-1=系统默认卡） |
| `imported_system_sms` | Bool | false | 是否已导入过系统短信 |

**加一个设置项要改 4 处：** `AppSettings` 字段 → `Keys` 里加 key → `settings` flow 里读 →
加 setter。然后在 `SettingsViewModel` 加转发方法、`SettingsScreen` 加 UI。

---

## 5. 短信引擎（最容易踩坑的部分）

### 5.1 默认短信应用的四个必需组件

系统只有在这四个组件**全部**存在时，才会把 App 列进「默认短信应用」候选。
**少任何一个都不会出现在列表里，而且系统不给任何提示。**

| # | 组件 | 类 | 必需权限 |
|---|---|---|---|
| 1 | `SMS_DELIVER` 接收器 | `SmsDeliverReceiver` | `BROADCAST_SMS` |
| 2 | `WAP_PUSH_DELIVER` 接收器 | `MmsDeliverReceiver` | `BROADCAST_WAP_PUSH` |
| 3 | `SENDTO` Activity（`smsto:`） | `MainActivity` | — |
| 4 | `RESPOND_VIA_MESSAGE` Service | `HeadlessSmsSendService` | `SEND_RESPOND_VIA_MESSAGE` |

#### ⚠️ 最坑的一条：WAP_PUSH 接收器必须声明 `mms:` scheme

系统做资格判定时，用的是一个 **`mms:` scheme + `application/vnd.wap.mms-message`** 的 Intent。
而按 Android 的匹配规则，**只声明 mimeType、不声明 scheme 的 filter 只匹配 `content:` / `file:` URI**，
匹配不上 `mms:` —— 于是这个组件被判定为「不存在」，App 永远进不了候选列表。

Google 2013 年那份被到处抄的示例 manifest 就是只写 mimeType 的。所以 manifest 里现在有**两条** filter：

```xml
<!-- 实际投递彩信用 -->
<intent-filter>
    <action android:name="android.provider.Telephony.WAP_PUSH_DELIVER" />
    <data android:mimeType="application/vnd.wap.mms-message" />
</intent-filter>
<!-- 资格判定用，别删 -->
<intent-filter>
    <action android:name="android.provider.Telephony.WAP_PUSH_DELIVER" />
    <data android:scheme="mms"   android:mimeType="application/vnd.wap.mms-message" />
    <data android:scheme="mmsto" android:mimeType="application/vnd.wap.mms-message" />
</intent-filter>
```

`MainActivity` 同理，除了那条混合 SEND/SENDTO/VIEW 的 filter，另有一条只含
`SENDTO + DEFAULT + smsto` 的干净 filter，完全贴合规范。**两条都要留着。**

#### 自检工具

`util/SmsRoleCheck.kt` 用 `PackageManager.query*` 逐个验证这四个组件系统认不认，
结果显示在设置页的红色卡片里（✓/✕ 逐项列出）。改 manifest 之后应该先看这个自检。

### 5.2 接收链路

```
新短信到达
  ├─ 已是默认短信应用 → SMS_DELIVER  → SmsDeliverReceiver（writeToSystemInbox = true）
  └─ 不是默认应用     → SMS_RECEIVED → SmsReceivedReceiver（writeToSystemInbox = false）
                                       ↑ 已是默认应用时这个接收器直接 return，避免重复入库
        ↓
  合并长短信分段（多个 PDU 按发件人拼成一条）
        ↓
  作为默认应用时：写系统收件箱 Telephony.Sms.Inbox
        ↓
  Repository.onIncoming() → 拦截判断 → 入 Room → 刷新会话摘要 → 分类
        ↓
  NotificationHelper.showNewMessage()
```

两个接收器共用 `handleIncomingSms()`（`SmsDeliverReceiver.kt` 文件底部的顶层函数）。

**Receiver 里做异步的正确姿势**（三个接收器都是这个模式）：

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    Graph.init(context)
    val pending = goAsync()                 // 必须，否则进程可能在协程跑完前被杀
    Graph.applicationScope.launch {
        try { ... } finally { pending.finish() }
    }
}
```

### 5.3 发送链路

`SmsSender.send(threadId, address, body, subId)`：

1. `Repository.createOutgoing()` 先本地登记一条 `PENDING`，UI 立刻能看到
2. 作为默认应用时写系统已发送库
3. `divideMessage()` 分段 → `sendMultipartTextMessage()`
4. 每段挂两个 `PendingIntent`（sent / delivered）指向 `SmsStatusReceiver`
5. `SmsStatusReceiver` 收到回执 → 只在**最后一段**时更新状态为 SENT / DELIVERED / FAILED

状态机：`PENDING → SENT → DELIVERED`，任一步失败 → `FAILED`（气泡变红，点一下重发）。

### 5.4 双卡

`SmsSender.smsManager(subId)`：`subId >= 0` 时绑定指定卡
（API 31+ 用 `createForSubscriptionId`，更早用 `getSmsManagerForSubscriptionId`），
否则用系统默认卡。选中的 subId 存在 DataStore 的 `send_sub_id`。

`SimUtils.activeSims()` 需要 `READ_PHONE_STATE` 权限，没权限返回空列表（UI 就不显示选择器）。

---

## 6. UI 层

### 6.1 导航

`MainActivity.SmsApp()` 里的 `NavHost`，四条路由：

| route | Screen | ViewModel |
|---|---|---|
| `list` | 按 `settings.listStyle` 三选一 | `ConversationListViewModel`（在 SmsApp 作用域，全局共享） |
| `chat/{threadId}` | `ChatScreen` | `ChatViewModel`（key = `"chat-$threadId"`，每会话一个） |
| `new` | `NewMessageScreen` | `NewMessageViewModel` |
| `search` | `SearchScreen` | `SearchViewModel` |
| `settings` | `SettingsScreen` | `SettingsViewModel` |

ViewModel 用 `ui/common/ViewModelFactory.kt` 的 `SimpleFactory { ... }` 构造：

```kotlin
val vm: ChatViewModel = viewModel(
    key = "chat-$threadId",
    factory = SimpleFactory { ChatViewModel(threadId) },
)
```

### 6.2 Screen 的约定

所有 Screen 都是**无状态**的：入参一个 `XxxUiState` + 若干 `on*` 回调，
不持有 ViewModel 引用，方便预览和替换。

三套列表 Screen 签名基本一致，都接受一个 `banner: (@Composable () -> Unit)? = null` 插槽
（用来渲染「设为默认短信应用」提示，作为 LazyColumn 第一个 item，
这样 inset 由 Scaffold 统一处理，还能跟着滚走）。

### 6.3 配色三级优先级（`Theme.kt`）

```
1. 动态取色   dynamicColor && SDK >= 31  → 跟随壁纸
2. 手动种子色 seedColor != 0             → material-kolor 从种子推导整套 M3 配色
3. 内置基线配色
```

第 2 级让 Android 12 以下的机器也能换色。候选种子色在 `SeedColors.kt`，加一个就往列表里加一项。

### 6.4 时间显示

列表统一显示经过时长（`刚刚 / N 分钟前 / N 小时前 / N 天前 / N 个月前 / N 年前`）。

⚠️ 相对时间是**算出来的**，不刷新就会一直停在旧值。
`ConversationListViewModel` 里有个 30 秒的 `ticker` flow 参与 `combine`，
每次 tick 重新 `toUi(now)`。**加新的相对时间显示时记得也要有刷新源。**

---

## 7. 平台坑清单

按踩坑概率排序，改代码前建议扫一眼。

### 7.1 edge-to-edge 下键盘不顶起输入框

`enableEdgeToEdge()` 开启后，manifest 里的 `android:windowSoftInputMode="adjustResize"`
**不再自动生效**，必须在 Compose 里自己消费 IME inset。

```kotlin
bottomBar = {
    Column(
        Modifier
            .navigationBarsPadding()   // 先消费导航栏 inset
            .imePadding()              // 再补剩余的键盘高度
    ) { ... }
}
```

顺序不能反：`navigationBarsPadding` 会消费掉导航栏 inset，
`imePadding` 只补剩下的，两者不会重复叠加。
同时 `Scaffold` 设了 `contentWindowInsets = WindowInsets(0,0,0,0)`，避免内容区再加一次。

### 7.2 国产 ROM 装不上

- **包名不能 `com.example.*`**（见 §1.1）
- **要打 v1 签名**（见 §1.2）
- 手机上要关掉「安装外部来源应用检测」/「纯净模式」
- **别用微信/QQ 传 APK**，会改扩展名或二次压缩导致包损坏
- 最省事的安装方式：`adb install -r app-debug.apk`。
  仓库外的 `诊断工具/` 目录里有现成的 adb 和批处理脚本

### 7.3 `echo|set /p=` 在批处理里会卡死

写 Windows 诊断脚本时别用这个技巧做格式化输出，在 `for` 循环里配合重定向会挂起。
改成整块 `( ... ) > log.txt 2>&1` 一次性重定向。

### 7.4 adb shell 里不能用 `findstr`

`adb shell pm list packages | findstr xxx` 会在设备侧执行 findstr（不存在）。
要先把输出落到本地文件，再在 Windows 侧 findstr。

---

## 8. 待办与已知限制

### 高优先级

1. **默认短信应用在 ColorOS 上未验证通过。**
   `mms:` scheme 的修复已经做了但没在真机确认。
   排查顺序：装新版 → 设置页看自检卡片五项是否全 ✓ →
   若全 ✓ 但选择列表里仍看不到，那是厂商限制，需要另想办法。
   测试机：OnePlus CPH2769，Android 16 (SDK 36)，ColorOS 16.0.8.300。

2. **数据库迁移策略**。现在是 `fallbackToDestructiveMigration()`，
   改表会清空用户数据，发布前必须换成正式 `Migration`。

### 中优先级

3. **MMS 只做到占位**。`MmsDeliverReceiver` 收到 WAP Push 只登记一条
   `[彩信] 收到一条多媒体消息`，没有真正下载。完整实现需要接运营商 MMSC，
   涉及 `MmsManager` / APN 配置，工作量不小。

4. **图片消息是本地的**。选图后作为本地附件消息保存展示，
   **没有真正通过运营商发出去**（那需要 MMS 通道）。

5. **`threadId` 与系统库不对应**（见 §4.1 的注意）。要做双向同步得重构。

6. **搜索是 `LIKE '%q%'`**，数据量大了会慢。可以上 Room FTS4。

### 低优先级 / 说明

7. **RCS 是纯显示层标识**。Android 没有向第三方应用开放 RCS 发送 API，
   设置里那个开关只控制会话标题是否显示「RCS ·」前缀。

8. **智能回复是本地关键词规则**（`ChatViewModel.smartRepliesFor`），不是模型。

9. **「正在输入」气泡有 UI 但没有数据源**（`setPeerTyping` 无人调用）。
   短信协议本身没有 typing indicator，除非接 RCS。

10. **录音已移除**。`RECORD_AUDIO` 权限、录音按钮、`VoiceRecorder` 类都删了。
    `VoicePlayer` 保留用于播放历史语音消息。

11. **没有任何测试**。`app/src/` 下只有 `main/`，`test/` 和 `androidTest/` 目录还没建。
    建议优先给 `PhoneUtils` / `CodeExtractor` / `Classifier` / `TimeFormat`
    这四个纯函数工具类补单元测试，收益最高。

---

## 9. 常见改动怎么做

### 加一个数据库字段

1. `Entities.kt` 加字段（给默认值）
2. `AppDatabase` 的 `version` +1
3. 写 `Migration`（现在是 destructive，正式版必须补）
4. 涉及展示的话，改 `ui/common/UiModels.kt` 的 `toUi()`

### 加一个设置项

见 §4.3 末尾，改 4 处 + VM + Screen。

### 加一个新页面

1. `ui/xxx/XxxScreen.kt`（无状态）+ `XxxViewModel.kt`
2. `MainActivity` 的 `NavHost` 里加 `composable("xxx") { ... }`
3. 用 `SimpleFactory` 构造 VM

### 改列表外观

三套皮肤分别在
`ConversationListScreen.kt`（1a）和
`ConversationListVariants.kt`（1b `ConversationListFilteredScreen` / 1c `ConversationListExpressiveScreen`）。
改哪套看 `settings.listStyle`。**注意三套都要改，否则切皮肤会不一致。**

---

## 10. 与原设计稿的对应关系

| 设计 | 实现 |
|---|---|
| 1a | `ConversationListScreen` — 搜索栏 + 未读分组卡片 + 其余会话 + 扩展式 FAB |
| 1b | `ConversationListFilteredScreen` — 分类 chips 过滤 |
| 1c | `ConversationListExpressiveScreen` — 大圆角卡片 |
| 1d / 1h | `ChatScreen` — 深浅色由主题自动切换，不需要两套代码 |
| 1e | `NewMessageScreen` — 收件人 chips + 真实系统联系人 |
| 1f | `SearchScreen` — 关键词高亮 + 验证码/取件码抽取卡 |
| 1g | 深色模式，`SmsTheme` 处理 |

相对设计稿的改动：

- 「较早」分组标题去掉了（用户要求），改成 8dp 间距
- 时间从「昨天 / 周日 / 7月24日」改成经过时长
- 输入框右侧的麦克风按钮换成常驻发送按钮
- 顶栏加了 SIM 卡选择器
- 未读分组标题右侧加了「全部已读」
- 图标用 `material-icons-extended` 近似替代，正式发版建议把 Material Symbols 的 SVG 导成 vector drawable
