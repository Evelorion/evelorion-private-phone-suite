# 网页版

> ← 文档索引：[README.md](README.md)

两个入口：`/user/` 给用户，`/admin/` 给管理员。**两套认证完全分开，互相进不去对方的端点。**

---

## 1. 先说网页版特有的那个弱点

**这个页面的 JS 是服务器发给浏览器的。服务器被攻破的话，它可以发一段偷口令的脚本，
你看不出区别。**

手机 App 没有这个问题：代码签过名装在本地，服务器换不掉。

这不是这套实现的缺陷，**所有网页端的端到端加密都一样** ——
Proton、Bitwarden 的网页版也是这个结构。业界目前没有干净的解法
（浏览器扩展、Subresource Integrity 都只能缓解）。

页面上把这段话写在了显眼位置。日常建议用 App，网页版按需使用。

已经做了的缓解措施：

- **CSP 收得很紧**：`default-src 'self'`，不允许任何外部来源
- **不用 CDN**：Argon2 的 WASM 本地托管（`/vendor/argon2.umd.min.js`）。
  给加密代码引 CDN 等于把密钥安全交给第三方
- **令牌只在内存**，不进 `localStorage` —— 那里的东西任何一段 XSS 都能读走，
  刷新页面也不消失。代价是刷新要重新解锁

---

## 2. 用户端能做什么

| 功能 | 说明 |
|---|---|
| 注册 / 登录 | 需要邀请码。主口令在浏览器里派生，只有 `authSecret` 发给服务器 |
| 恢复码 | 注册后显示一次，之后任何地方都拿不回来 |
| 联系人 CRUD | 完整增删改，撞冲突自动三方合并 |
| 通话记录 | 只读。新记录用稳定的 `HKDF(DEK,"fc.collection.calls.v2")` 子密钥；网页迁移期兼容 v1 |
| 导出 vCard | 换手机前留副本。**导出的文件是明文** |
| 设备管理 | 看在哪些设备登录过，远程吊销 |
| 改主口令 | 只重新包裹 DEK，联系人一条都不用重传。需要恢复码 |
| 注销账号 | 删掉服务器上全部密文，不可恢复 |
| 完整性告警 | 同步清单发现服务器藏记录或回滚版本时，页面顶部会红字提示 |

### 和 App 的区别

浏览器**没有本地联系人库**：打开页面时把全部记录拉下来解密进内存，
编辑后加密推回去。三方合并的 base 就是「加载时看到的那一份」。

所以网页端不需要 App 里那套 `detectLocalChanges` 全量扫描 —— 谁被改过我们自己知道。

---

## 3. 管理员能做什么

**看不到任何联系人内容。** 服务器上只有密文，解密密钥只存在用户的主口令里。
这不是功能没做，是端到端加密的定义。要让管理员能看，就得放弃端到端加密。

能看到的是元数据：账号名、记录条数、占用字节、设备活跃时间。

| 模块 | 能力 |
|---|---|
| 账号 | 列表、停用/启用、删除（要二次输入用户名确认）、查看每个账号的设备 |
| 邀请码 | 生成（可设次数和有效期）、作废、查看使用状态 |
| 服务器 | 账号数、记录数、密文占用、数据库大小、运行时长、备份列表 |
| 安全 | 验证器、备用码、通行密钥、两种都验证、改口令、管理员会话、登录失败 |

### 第一个管理员怎么来

一台全新服务器访问 `/admin/` 会显示引导页。**创建之后这个入口永久关闭。**

之后再加管理员只能在服务器上跑：

```bash
docker compose exec sync node --experimental-strip-types scripts/create-admin.ts <用户名>
```

这样即使后台被人登进去，也没法悄悄给自己加一个新管理员。

### 邀请码取代了 .env 里那个

原来注册码写死在 `REGISTRATION_TOKEN`，改不了也没法作废。现在存数据库里：

- 只存哈希，生成时明文**只显示一次**
- 可以设可用次数（0 = 不限）和有效期
- 可以随时作废

**兼容期**：库里一条邀请码都没有时，`.env` 里那个仍然有效 ——
这样已经部署好的实例升级后不会突然注册不了。在后台生成第一个邀请码之后，
配置文件里那个自动失效，后台会有提示。

---

## 4. 两套认证怎么隔离的

```
用户端    Bearer 令牌（15 分钟过期 + 刷新令牌轮换）  →  requireAuth()
管理端    HttpOnly Cookie + X-Admin-Request 头        →  requireAdmin()
```

这两个函数各认各的凭据，**没有任何交叉**。e2e 里有两条断言专门验证：

- 用户的访问令牌打管理端点 → 401
- 管理员的 Cookie 打用户数据端点 → 401

### 管理员会话为什么用 Cookie

管理后台是浏览器页面，HttpOnly Cookie 能挡住 XSS 偷令牌（JS 读不到）。
代价是要防 CSRF，靠三层：

1. `SameSite=Strict`
2. 必须带 `X-Admin-Request: 1` 头（浏览器不会在跨站表单提交里带自定义头）
3. 写操作只走 POST / DELETE

`Secure` 标志也必须有 —— 明文过网的会话 Cookie 等于把后台送人。

---

## 5. 浏览器端的密码学

`web/lib/crypto.js` 是**第四份**同一套密码学的实现：

```
server/test/client.ts                          参考实现
android/contacts/.../sync/crypto/Crypto.kt     通讯录
android/phone/.../privatecalls/CallCrypto.kt   电话
server/web/lib/crypto.js                       ← 这个
```

用什么实现的：

- AES-256-GCM / HKDF-SHA256 / HMAC / SHA-256 → **WebCrypto**（浏览器内置）
- Argon2id → **hash-wasm**，和服务端**同一个包**，所以结果天然一致

### 改完必须跑自检

浏览器里打开 `/selftest.html`，它会拿 `vectors.expected.json` 里的标准答案逐条比对。
全绿说明这个浏览器和服务端、和两个 Android App 算的是同一套东西。

CI 里也能跑（Node 里垫一个 DOM shim 后直接执行同一份 `crypto.js`）：

```bash
cd server && npm run test:web
```

> **这套交叉校验不是形式主义。** 开发过程中它逮到了一个真分叉：
> `client.ts` 里的 `itemId` 分隔符是一个**不可见的 NUL 字节**（所以那个文件被
> grep 当成二进制），而 Kotlin 用的是空格。两端算出的 itemId 完全不同，
> 会导致同一个号码被认成两条，真机测试必挂。
>
> 现在统一成 NUL 并写成显式字节（`Buffer.from([0x00])` / `new Uint8Array([0x00])`），
> 源码里不再有不可见字符。NUL 也确实是更对的选择：空格会让
> `itemId('phones','a b')` 和 `itemId('phones a','b')` 撞车。

---

## 6. 部署

网页端是纯静态文件，没有构建步骤，由 Fastify 直接托管：

```
server/web/
  index.html            落地页，两个入口
  styles.css            共用样式
  selftest.html         浏览器端密码学自检
  lib/crypto.js         密码学（第四份实现）
  lib/merge.js          三方合并
  lib/api.js            两套 API 客户端
  lib/vault.js          保险库与同步逻辑
  user/                 用户端
  admin/                管理后台
  vendor/argon2.umd.min.js   本地托管的 Argon2 WASM
  vendor/vectors.json        自检用的标准答案
```

改完直接重建镜像即可，见 [DEVELOPMENT.md](DEVELOPMENT.md)。线上只保留一个
规范网页来源：

```dotenv
PUBLIC_ORIGIN=https://contacts.example.com:443
```

网页、Android 和管理员都使用 `https://contacts.example.com:443`；不要把内部服务的 `:8443` 当成公网入口。
8443 只在回环地址或 Docker 内网中连接后端。完整拓扑、总反代接网与验证命令见
[DEPLOYMENT.md](DEPLOYMENT.md)。

> **踩过的坑**：`web/` 是从挂载盘打包上去的，文件权限可能是 600，
> 而容器以非 root 的 `node` 用户跑 → 全部 500 EACCES。
> Dockerfile 里加了 `chmod -R a+rX`。

---

## 7. 两个实测出来的 bug（已修）

**1. `content-type: application/json` 但 body 为空 → Fastify 直接 400。**

`api.js` 原来在 GET / DELETE 上也发这个头，导致管理后台的**删除和退出按钮全挂**。
两边都修了：客户端没有 body 就不发 content-type，服务端也加了容忍空 body 的解析器。

**2. 错误处理器双重发送。**

`setErrorHandler` 里 `return sendError(...)` 把 reply 对象又当 payload 发了一次，
报 `Attempted to send payload of invalid type 'object'`。这个 bug 一直在，
只是之前没有能触发它的路径 —— 静态文件的 EACCES 才把它暴露出来。

---

## 8. 还没做的

- **头像**。用户端目前不显示也不能上传头像，只有首字母。
  协议层是支持的（`/v1/blobs`），是 UI 没做。
- **通话记录只读**。网页端不能删通话记录，只能看。
- **审计日志**。管理员的删除、停用操作没有留痕。
  单人自用够了，多个管理员的话应该加。
