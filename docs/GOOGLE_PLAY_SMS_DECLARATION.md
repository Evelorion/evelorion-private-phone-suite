# Google Play 短信权限申报

## 应用信息

- 包名：`com.evelorion.messages`
- 核心功能：系统默认短信应用，负责在设备上接收、显示和发送 SMS/MMS
- 隐私政策：<https://contacts.etheraler.com/privacy/>
- 发布格式：签名 AAB（`messages-app/app/build/outputs/bundle/release/app-release.aab`）

## Play Console 权限声明

在“应用内容 → 敏感权限和 API → 短信和通话记录权限”中选择：

- 核心功能：Default SMS handler（默认短信处理程序）
- `READ_SMS`：读取用户已有短信并在会话列表中显示
- `RECEIVE_SMS`：作为默认短信应用接收新短信
- `SEND_SMS`：发送用户主动编写的短信
- `RECEIVE_MMS` / `RECEIVE_WAP_PUSH`：作为默认短信应用接收 MMS 投递

审核说明可写：

> Evelorion Messages is a user-facing default SMS handler. The app asks the
> user to grant the Android SMS role before requesting or using any SMS
> permission. If the app is no longer the default SMS handler, it immediately
> stops reading and sending system SMS. SMS content stays on-device and is not
> uploaded, used for advertising, or shared with third parties.

## 审核操作路径

1. 启动应用；首页仅显示“设为默认短信应用”，不会先弹短信权限。
2. 点击该按钮，在 Android 系统角色界面选择 `Evelorion 信息`。
3. 成为默认短信应用后，才出现短信运行时权限。
4. 打开“设置 → 隐私政策”可查看公开政策。
5. 撤销默认短信角色后，系统短信读取、导入和发送均停止。

Play Console 仍需要账号所有者提交权限声明表并完成 Google 审核；上传到 GitHub
或使用同一签名不能代替该审核，也不应要求用户关闭 Play Protect。
