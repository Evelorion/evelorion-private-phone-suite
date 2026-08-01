# 短信 App 的签名集成与隔离边界

`contacts-app`、`phone-app`、`messages-app` 使用同一份正式发行证书。三个
Manifest 都定义同名的 `signature` 权限；证书不同的包不能获得权限，重签后的
套件也会因为重复权限的签名不一致而无法和正式版同时安装。

短信 App 读取私密联系人时有三层检查：

1. Android PackageManager 校验 `READ_PRIVATE_CONTACTS` 的 signature 权限；
2. 通讯录 Provider 校验调用 UID、包名前缀和固定的正式证书 SHA-256；
3. 短信 App 反向校验 Provider 必须来自正式证书签名的通讯录包。

短信 App 没有导出自己的 Room 数据库或 ContentProvider，`allowBackup=false`，
备份与设备迁移规则也全量排除应用私有数据。普通第三方 App 因此不能跨 Android
沙箱读取 `sms.db`。

## 系统短信库的边界

作为默认短信 App，收发短信必须按 Android 规范写入系统 `Telephony.Sms` 数据库。
该数据库由系统管理，不可能通过“同一应用签名”变成某一套 App 的独占数据库。
系统以后若把默认短信角色或特殊权限授予另一个 App，那个 App 可能读取系统库。

所以安全承诺是：

- 套件的私有联系人和本地 App 数据只允许正式同签名组件访问；
- 不向第三方导出短信私有数据库；
- 不声称能够阻止系统、root 环境或另一个获系统短信角色的 App 读取系统短信库。
