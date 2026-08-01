# 短信 App（Android · Jetpack Compose + Material 3）

按设计稿 1a–1h 实现的**完整可运行短信应用**，所有功能都已接真实数据，不再使用示例数据。

首次取得短信读取权限时只导入最近 100 条系统短信，避免大量历史短信和联系人
匹配阻塞首次启动。之后收到或发出的短信会实时保存到本地数据库。

## 一、怎么跑起来

1. 用 **Android Studio（Koala 2024.1.1 或更新）** 打开本目录（选 `SmsApp` 文件夹本身）。
2. 首次打开会自动下载 Gradle 8.7 和依赖，等索引完成。
3. 直接点 Run，或命令行 `./gradlew assembleDebug`（Windows：`gradlew.bat assembleDebug`）。
4. 产物在 `app/build/outputs/apk/debug/app-debug.apk`。
   本目录里已附带一个编译好的 **`app-debug.apk`**，可以直接装到手机上试。

需要 JDK 17（Android Studio 自带）、Android SDK Platform 34、Build-Tools 34.0.0。

> 首次启动后请在应用内点「设为默认短信应用」，否则系统不会把新短信投递给本应用。

## 一·五、装不上怎么办（国产 ROM）

已经在工程里做掉的两件事：

- **包名不能用 `com.example.*`**。ColorOS / OriginOS / MIUI 会把 `com.example` 开头的包
  判定为「测试应用」直接拦截，提示就是笼统的「应用未安装」。
  现在 `applicationId = "com.miko.messages"`（`namespace` 仍是 `com.example.sms`，
  只影响 R 类，不需要改任何代码）。
- **三重签名**。AGP 在 `minSdk >= 24` 时默认只打 v2；部分国产 ROM 的安装器仍会校验 v1。
  已在 `signingConfigs.debug` 里显式打开 v1 + v2 + v3。

还要在手机上做的（各家叫法不同，找类似的开关）：

- OPPO / 一加（ColorOS）：设置 → 密码与安全 → 系统安全 → 关闭「安装外部来源应用检测」
- vivo（OriginOS）：设置 → 更多设置 → 权限管理 → 允许安装未知来源应用；并关闭「安装安全检测」
- 魅族（Flyme）：设置 → 指纹和安全 → 未知来源应用
- 通用：关闭「纯净模式」/「应用安全检测」

另外注意：**别用微信 / QQ 传 APK**，它们会改扩展名或压缩导致包损坏。用数据线拷贝，
或先传到手机再用系统文件管理器安装。

如果还是失败，最快的定位办法是接电脑跑 `adb install -r app-debug.apk`，
它会直接返回 `INSTALL_FAILED_XXX` 错误码。

## 二、实现了哪些功能

### 短信收发（真实）
- `SmsManager.sendMultipartTextMessage` 发送，自动分段长短信。
- 发送结果与送达回执经 `PendingIntent` 回到 `SmsStatusReceiver`，气泡上实时显示
  「发送中 / 已发送 / 已送达 / 发送失败」，失败点一下即可重发。
- `SMS_DELIVER` 接收新短信（默认短信应用专属），长短信多段自动合并。
- 未设为默认应用时，`SMS_RECEIVED` 兜底接收；已是默认应用则忽略，避免重复入库。
- `WAP_PUSH_DELIVER` 接收彩信通知。
- `HeadlessSmsSendService` 支持来电界面的「短信快捷回复」。
- 作为默认短信应用时，收发的短信会回写系统短信库（`Telephony.Sms` 收件箱 / 已发送）。

### 本地存储（全部落本地磁盘，不用缓存）
- **Room** 数据库 `sms.db`，位于应用私有 `databases/` 目录：会话、消息、拦截记录。
- **DataStore** 保存设置：位于 `files/datastore/settings.preferences_pb`。
- 当前版本不提供录音入口；仅保留对历史语音附件的本地播放兼容。
- 首次授予 `READ_SMS` 后自动把手机里已有短信导入本地库，按系统 `_id` 唯一索引去重，可在设置里手动重新导入。

### 界面（对应设计稿）
| 设计 | 实现 |
|---|---|
| 1a | 标准列表：搜索栏 + 未读分组卡片 + 较早列表 + 扩展式 FAB |
| 1b | 分类 chips 过滤（全部/未读/个人/交易/推广），未读优先 |
| 1c | Expressive 大圆角卡片列表 |
| 1d / 1h | 会话详情，深浅色由主题自动切换 |
| 1e | 新建短信：收件人 chips + 真实系统联系人建议 |
| 1f | 搜索结果（关键词高亮）+ 取件码/验证码抽取卡 + 设置 |
| 1g | 深色模式（`SmsTheme` 自动切换，也可在设置里强制） |

三套列表方案在**设置 → 列表样式**里随时切换，选择会持久化。

### 其它功能
- **时间显示**：列表统一显示实际经过时长（刚刚 / N 分钟前 / N 小时前 / N 天前 / N 个月前 / N 年前），
  每 30 秒自动重算，不会停在旧值；会话内分隔条是「确切时刻 · 多久前」，例如「7月24日 18:03 · 8 天前」。
- **一键已读**：未读分组标题右侧的「全部已读」，一次清掉所有会话未读并撤掉全部通知。
- 会话长按：置顶 / 静音 / 标记已读 / 删除。
- 消息长按：表情回应（❤️👍😂😮😢🙏）、复制文本、重发、删除。
- 图片消息：系统相册选择器（Photo Picker），用 Coil 显示。
- 语音消息：`MediaRecorder` 录制 + 波形气泡 + 点击播放。
- 智能回复：按对方最后一句给三个候选，可在设置里关闭。
- 草稿：退出会话自动保存，列表里显示「草稿：…」。
- 通知：新短信通知，带「回复」（直接在通知里输入并发送）、「标记已读」，
  短信里含验证码/取件码时额外给一个「复制」按钮。
- 骚扰拦截：可开关，命中推广特征的短信不入会话，只计数，设置页显示本月拦截条数。
- 号码归一化：`+86` / 空格 / 横线差异不会产生重复会话。
- 联系人：`READ_CONTACTS` 后把号码显示成姓名。
- 支持从拨号盘 / 浏览器的 `smsto:` 意图直接进入对应会话。
- **主题配色（三级优先级）**：
  1. **动态取色**：Android 12+ 跟随壁纸（`dynamicLightColorScheme` / `dynamicDarkColorScheme`）；
  2. **手动主题色**：设置页提供 8 个种子色（默认紫/靛蓝/天青/薄荷/森绿/琥珀/赤陶/玫瑰），
     用 `material-kolor` 按 Material 3 官方调色算法从种子色推导出整套配色，
     **Android 12 以下的机器也能换色**，选择持久化到 DataStore；
  3. 都不用时回落到内置基线配色。
  设置页带实时色板预览（主色 / 主容器 / 次色 / 第三色 / 表面），切换时立刻可见；
  动态取色开启且系统支持时，手动色块会置灰并提示「当前跟随壁纸」。

## 三、目录结构

```
app/src/main/java/com/example/sms/
├── Graph.kt                     极简依赖容器
├── SmsApplication.kt            初始化 + 通知渠道
├── MainActivity.kt              导航、运行时权限、默认短信应用引导
├── data/
│   ├── db/                      Room 实体 / DAO / 数据库 / 类型转换
│   ├── repo/MessageRepository   唯一数据出入口
│   ├── system/                  系统短信库、系统联系人
│   └── prefs/SettingsStore      DataStore 设置
├── sms/                         发送器、四个接收器、Headless Service、通知
├── ui/
│   ├── theme/                   配色（浅/深 + 动态取色）
│   ├── common/                  头像、UI 模型、ViewModel 工厂
│   ├── conversations/           1a / 1b / 1c + ViewModel
│   ├── chat/                    1d / 1h + ViewModel
│   ├── newmsg/                  1e + ViewModel
│   ├── search/                  1f + ViewModel
│   └── settings/                设置页 + ViewModel
└── util/                        号码、分类、验证码抽取、时间格式、录音播放
```

## 四、与原设计稿的差异

- 图标使用 `material-icons-extended`，正式发版建议把 Material Symbols 的 SVG 导成 vector drawable。
- 键盘用系统输入法（`TextField`），符合平台规范。
- 彩信（MMS）只做到「收到通知并登记一条占位消息」；完整的 MMS 下载/上传需要接运营商 MMSC，超出短信范畴。
- 图片消息在本机作为附件消息保存展示；通过运营商真正发出图片同样需要 MMS 通道。
- 「RCS」是显示层的状态标识，Android 未向第三方应用开放 RCS 发送 API。
