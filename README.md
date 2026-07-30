# Evelorion 私密通讯录与电话

本仓库包含两个 Android App 和一个端到端加密同步后端：

- `contacts-app`：私密联系人管理与加密同步
- `phone-app`：电话、通话记录与录音入口
- `server`：TypeScript / Fastify / SQLite 同步服务

服务器只保存加密后的联系人和通话记录。Android APK 必须在本机构建，
签名证书、签名口令、真实云端地址和 `.env` 均不进入 Git。

## 应用预览

### 电话 App

![Evelorion Phone 主界面](screenshots/phone-app.png)

### 通讯录 App

![Evelorion Contacts 首页](screenshots/contacts-app.png)

## 文档

- [项目说明](说明.md)
- [开发指南](开发指南.md)
- [云端部署](docs/DEPLOYMENT.md)
- [架构说明](docs/ARCHITECTURE.md)
- [API](docs/API.md)
- [安全设计](docs/DESIGN.md)

## 后端验证

```bash
cd server
npm install
npm run build
npm run test:e2e
npm run test:web
```

## Android 构建

使用 JDK 17 和 Android SDK 36，在本机分别打开 `contacts-app` 与 `phone-app`。
两个 App 必须使用同一份本地签名证书。此项目的 Windows 开发机把发行密码
保存在 Windows 凭据管理器的
`Evelorion.PrivatePhoneSuite.ReleaseSigning` 条目中，使用以下脚本构建：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\build-official-release.ps1
```

脚本只在构建进程内设置 `KEYSTORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD`，
不会把密码写进源码。需要转存到个人密码管理器时，运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\copy-release-signing-password.ps1
```

签名证书、Windows 凭据和离线备份都只保存在本机。
