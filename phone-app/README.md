# 电话 App（com.evelorion.phone）

私密通讯录的配套电话应用。UI 严格按 `谷歌设计规范电话应用.zip` 里的 Compose 原型，
动画（emphasized 曲线、按压圆角形变、拖动接听）**原样保留，没有重写**。

先读 `../开发指南.md`，那里有编译流程和踩过的坑。这份只讲这个模块。

---

## 一件必须先做的事

**装完要去系统设置里把它设为「默认电话应用」。**

不是默认电话应用时，系统**根本不会绑定** `PhoneInCallService`，
来电和通话界面用的仍然是系统自带的那套，本 App 里那两屏永远看不到，
接听/拒接/静音也都不归我们管。

「来电界面不出现」的第一排查点永远是这个，而不是代码。
设置页顶部的「状态」区会用红字提示当前是不是默认。

---

## 目录结构

```
app/src/main/java/com/evelorion/phone/
├─ MainActivity.kt          拨号主界面宿主、运行时权限、tel: 处理、真正拨出
├─ PhoneApplication.kt      崩溃兜底、排周期同步
│
├─ telecom/                 ── 接管系统电话 ──
│   PhoneInCallService        系统把通话交给我们的唯一入口
│   CallManager               通话状态的唯一真相来源（进程级单例）
│   CallActivity              来电屏/通话屏的宿主，能在锁屏上方显示
│   CallerIdResolver          来电显示：号码 → 姓名（异步）
│   CallRecorder              通话结束立刻记一条
│   DialerRole                申请成为默认电话应用
│
├─ bridge/                  ── 跨应用 ──
│   ContactsBridge            读通讯录里的加密联系人
│   VaultBridge               要 calls 子密钥 + 短期令牌（**不是 DEK**）
│
├─ sync/                    ── 通话记录端到端加密同步 ──
│   crypto/                   与通讯录逐字相同的密码学（裁剪版，见下）
│   net/CallsApi              collection=calls，401 时回头再要令牌
│   db/CallDatabase           本地库（明文，见文件内说明）
│   engine/CallSyncEngine     收编系统记录 → 拉 → 推
│   work/CallSyncScheduler    自动同步的两个触发点
│
├─ data/
│   PhoneData.kt             界面的数据来源，替换掉原型里的 SampleData
│   Pinyin.kt                拼音首字母（与通讯录同一份，保证分组一致）
│
└─ ui/                      设计稿原样，只改了数据来源
    PhoneApp / screens/*     九屏
    Motion.kt                动效常量（emphasized 曲线、弹性形变）
    components/Common.kt     MorphingSurface 等
    Bg.kt                    安全的后台线程池
    CrashReport.kt           崩溃记录 + 显示页
```

---

## 几个关键设计，改代码前请先读

### CallManager 为什么是全局单例

通话状态的拥有者是**系统**，不是某个界面。`InCallService` 由系统创建和销毁，
时机和 Activity 完全无关：来电可能在 App 没启动时到达，用户也可能在通话中
把界面划走再切回来。

把状态挂在 Activity 或 ViewModel 上，一旦它被回收，通话还在继续
但界面已经不知道该显示什么了。

### 为什么用 mutableStateOf 而不是 StateFlow

这些值只被 Compose 消费，`mutableStateOf` 直接触发重组，不需要 `collectAsState`
那一层。Telecom 的回调都在主线程，不存在线程安全问题。

### CallActivity 为什么不复用 MainActivity

来电要能在锁屏上方直接亮屏显示，靠的是 manifest 里的
`showWhenLocked` / `turnScreenOn` / `showOnLockScreen`。
这几个属性作用于整个 Activity —— 让日常拨号界面也带上，
等于任何时候打开 App 都强行点亮屏幕并绕过锁屏，那是安全问题。

`singleInstance` + 独立 `taskAffinity`：通话界面自己一个任务栈，
用户从最近任务里划走拨号界面时不会把通话界面一起划掉。

### 为什么自己记通话记录，而不是只扫系统的

系统写入通话记录是**异步且有延迟**的，挂断后立刻去扫，最后一通往往还没写进去。
表现是「刚打完的电话在列表里看不到」。

而且扫系统那条路要 `READ_CALL_LOG` 权限，用户拒绝的话本 App 自己的通话记录
也跟着没了 —— 这通电话本来就是它接的，说不过去。

所以 `CallRecorder` 在通话结束时自己记一条，`CallSyncEngine` 再按**时间水位线**
去重地收编系统那份，两边不会打架。

### 界面代码为什么几乎没改

`PhoneData` 刻意保持**和原型里 `SampleData` 完全一样的 API 形状**
（`people` / `person(id)` / `calls` / `history` / `familySubtitles` / `favoriteSubtitles`），
所以九个界面文件只改了一行 import，其余一个字没动。

这不是偷懒：那些界面是设计稿本身，改动越多、和设计走样的风险越大；
而「把假数据换成真数据」本来也不该要求界面重写。

### 静音/免提为什么不用本地 remember

它们的真相在系统那边（音频路由），不在界面里。
用 `remember` 记的话界面会和实际状态脱节 ——
比如插上耳机，系统把免提关了，而按钮还亮着。

### 通话中键盘要发 ASCII 的 `*#`

显示用的是全角 `＊＃`（设计稿如此），但发出去必须换成 ASCII 的 `*` `#`。
全角字符 Telecom 不认，表现是按了没反应。

---

## 安全边界（改代码时请一起维护）

这个 App 拿到的**不是主密钥**，只有：

- `HKDF(DEK, "fc.collection.calls.v2")` 派生的稳定 calls 子密钥
- 15 分钟的短期访问令牌（刷新令牌不给）

所以它能加解密通话记录，但**推不回 DEK**，解不开任何一条联系人。
万一这个 App 出漏洞被攻破，泄露的是通话记录，不是整个通讯录。

配套措施：

- `sync/crypto/VaultCrypto.kt` 是**裁剪版**。`createVault` / `wrapDek` /
  `deriveMasterKey` / 恢复码那一整套被**刻意删掉**了。
  不是编译不过，是**它不该有这些能力**。
- 依赖里**没有 argon2**（从主口令派生密钥用的）。
- 也**没有 sqlcipher**：本地库是明文的，加密发生在上传之前。
  原因见 `CallDatabase` 里的注释 —— 口令只在保险库解锁时拿得到，
  而来电时（锁屏、刚开机）往往拿不到。

> 如果哪天要在这个 App 里加「登录同步账号」的功能 ——
> 停下来先想清楚。那会把上面整套边界推倒，而且不会有任何编译错误提醒你。

---

## 删除的规矩

`PhoneData.deleteCall` 是本 App 里**唯一**产生删除的地方，而且打的是墓碑标记，
不是直接删行 —— 记录可能已经同步到服务器和其它设备，只删本地的话下次同步
又会被拉回来，用户会觉得「删不掉」。

同步引擎从设计上**不会**因为「扫描时找不到」就推墓碑。
通讯录那边正是那样丢过一次数据，详见 `../开发指南.md` 第四节。

---

## 已知未完成

| 功能 | 状态 | 为什么 |
| --- | --- | --- |
| 号码归属地 | 界面留空 | 没有离线号码库；在线查号会把「谁给你打电话」发给第三方 |
| 号码拦截 | 已接 | 名单用 Android Keystore 加密保存在本机；取得系统来电筛选角色后，由 `CallScreeningService` 自动拒接并保留通话记录 |
| 通话录音 | 手动开始/停止，挂断自动保存 | Android 10+ 保存到系统 `Recordings/Evelorion`；能否录到对方声音取决于手机系统与厂商限制 |
| 家人分组 | 已接 | 读通讯录里名为「家人」/「家庭」/「Family」的分组；没有该分组时整块隐藏 |

---

## 构建

**Android Studio → Open → 选这个文件夹 → Sync → Build。**
标准 Gradle 工程，wrapper 和签名证书都在里面。
需要 JDK 17+、Android SDK 36、支持 AGP 9.1.0 的 Android Studio。

几点注意：

- **必须和通讯录用同一把证书**（`signing/shared.jks`，两边是同一个文件）。
  不同签名两个 App 装都装不上（`INSTALL_FAILED_DUPLICATE_PERMISSION`）。
- **只打 arm64-v8a**（`abiFilters`）。SQLCipher 的原生库四种架构占 21 MB，
  砍到一种后 APK 从 41 MB 降到 9.5 MB。
  代价：**装不进 x86 模拟器**。要在模拟器上跑就临时把 `abiFilters` 那几行注释掉。
- debug 关掉了 R8。那是为了迁就一台 961 MB 内存的构建机（R8 在那上面要
  6~10 分钟却只省 30 KB）。**你自己的电脑上想开就开，没有坏处。**
- 后台任务一律用 `Bg.single(name)`，不要直接 `Executors.newSingleThreadExecutor()` ——
  原因见 `../开发指南.md` 坑 #6。
