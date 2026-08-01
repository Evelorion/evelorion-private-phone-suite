# API 参考

> ← 文档索引：[README.md](README.md)

基址：`https://contacts.example.com:443`

所有请求响应都是 JSON。二进制字段（盐、nonce、密文、包裹）一律 **base64**，
除了 `authSecret` 和 blob 的 `hash` 用**小写十六进制**。

认证：`Authorization: Bearer <accessToken>`，访问令牌 15 分钟过期。

---

## 错误响应

所有失败都是这个形状：

```json
{ "error": "invalid_credentials", "message": "用户名或口令不正确" }
```

| HTTP | error | 含义 |
|---|---|---|
| 400 | `bad_request` | 参数缺失或格式错误 |
| 400 | `bad_collection` | collection 不在白名单里 |
| 400 | `duplicate_uuid` | 同一批推送里有重复 uuid |
| 401 | `unauthorized` | 令牌缺失、无效或过期 |
| 401 | `invalid_credentials` | 口令不对 |
| 401 | `refresh_token_reuse` | 刷新令牌被重复使用，设备已吊销 |
| 401 | `device_revoked` | 设备被吊销 |
| 403 | `bad_registration_token` | 邀请码不对 |
| 409 | `username_taken` | 用户名已占用 |
| 413 | `too_large` | 超过大小上限 |
| 429 | `too_many_attempts` | 登录限流 |

---

## 账号

### `GET /v1/account/kdf?username=<name>`

取 KDF 参数。**注册和登录之前必须先调它**——客户端要有盐才能算出 `authSecret`。

不需要认证。

```json
{ "salt": "AAECAwQFBgcICQoLDA0ODw==", "memoryKiB": 65536, "iterations": 3, "parallelism": 4 }
```

> **不存在的用户名也返回一个盐**，值是 `HMAC(服务端密钥, 用户名)` 算出来的假盐。
> 不这样做的话任何人都能探出服务器上有哪些账号。

### `POST /v1/account/register`

```json
{
  "registrationToken": "<registration-token>",
  "username": "miko",
  "authSecret": "<64 位十六进制>",
  "kdf": { "salt": "<base64，≥16 字节>", "memoryKiB": 65536, "iterations": 3, "parallelism": 4 },
  "dekWrapPassword": "<base64>",
  "dekWrapRecovery": "<base64>",
  "deviceName": "Pixel 8"
}
```

响应：

```json
{
  "accountId": "...", "deviceId": "...", "vaultVersion": 1,
  "kdf": { "salt": "...", "memoryKiB": 65536, "iterations": 3, "parallelism": 4 },
  "dekWrapPassword": "...", "dekWrapRecovery": "...",
  "accessToken": "...", "accessExpiresAt": 1785044252, "refreshToken": "...",
  "serverSeq": 0
}
```

约束：

- 用户名 `^[a-zA-Z0-9._-]{3,64}$`
- `memoryKiB` ∈ [32768, 1048576]、`iterations` ∈ [2, 20]、`parallelism` ∈ [1, 16]
  —— **下界是防止被改过的客户端用弱参数注册**

### `POST /v1/session`

登录。响应结构和注册一样。

```json
{ "username": "miko", "authSecret": "<64 位十六进制>", "deviceName": "Pixel 8" }
```

每次登录会创建一个**新的 deviceId**，不复用。

限流：每 IP 每小时 30 次，每账号每小时 10 次。
不存在的用户名也会走一遍 Argon2，避免用响应时间区分。

### `POST /v1/session/refresh`

```json
{ "refreshToken": "..." }
```

返回新的三件套。**刷新令牌一次性使用并轮换**。

> 同一个刷新令牌被用第二次 → 判定为泄露 → **立即吊销该设备的全部令牌**，
> 返回 401 `refresh_token_reuse`。客户端遇到这个不要重试，让用户重新登录。

### `POST /v1/session/logout`

吊销当前设备。需要认证。

### `GET /v1/vault`

取回被包裹的 DEK。解锁和恢复码流程都用它。

```json
{
  "accountId": "...", "vaultVersion": 1,
  "kdf": { ... }, "dekWrapPassword": "...", "dekWrapRecovery": "...",
  "serverSeq": 42
}
```

### `POST /v1/vault/rewrap`

改口令。**只重新包裹 DEK，联系人密文一条都不用重传。**

```json
{
  "currentAuthSecret": "...", "newAuthSecret": "...",
  "kdf": { "salt": "<新盐>", "memoryKiB": 65536, "iterations": 3, "parallelism": 4 },
  "dekWrapPassword": "<新的>", "dekWrapRecovery": "<新的>"
}
```

成功后**吊销其它设备的刷新令牌**，逼它们用新口令重新登录。

### `GET /v1/devices` / `DELETE /v1/devices/:id`

设备列表和远程吊销。

```json
{ "devices": [{ "id": "...", "name": "Pixel 8", "createdAt": 1785044252000,
                "lastSeenAt": 1785044252000, "revoked": false, "current": true }] }
```

### `POST /v1/account/destroy`

彻底销毁账号和全部密文。需要再次提供 `authSecret`。

---

## 同步

### `GET /v1/sync/changes`

| 参数 | 默认 | 说明 |
|---|---|---|
| `since` | 0 | 客户端上次处理到的 seq |
| `collection` | `contacts` | `contacts` 或 `calls` |
| `limit` | 500 | 上限 500 |

```json
{
  "changes": [{
    "collection": "contacts",
    "uuid": "11111111-2222-3333-4444-555555555555",
    "seq": 7, "rev": 3, "deleted": false, "schemaVer": 1,
    "nonce": "<base64，12 字节>", "ciphertext": "<base64>",
    "updatedAt": 1785044252000, "deviceId": "..."
  }],
  "nextSince": 7,
  "hasMore": false,
  "collection": "contacts",
  "collectionSeq": 7,
  "serverSeq": 12,
  "serverTime": 1785044252000
}
```

> `hasMore` 是拿 **collectionSeq**（本 collection 的最大 seq）判断的，
> 不是账号级的 `serverSeq`——另一个 collection 的写入也会推高 serverSeq，
> 用它会导致客户端以为还有数据没拉完。

删除的记录 `deleted: true`，`nonce` 和 `ciphertext` 是 `null`。

### `POST /v1/sync/push`

```json
{
  "collection": "contacts",
  "changes": [{
    "uuid": "11111111-2222-3333-4444-555555555555",
    "baseRev": 2,
    "deleted": false,
    "schemaVer": 1,
    "nonce": "<base64，必须正好 12 字节>",
    "ciphertext": "<base64，≥17 字节，≤64KB>"
  }]
}
```

响应：

```json
{
  "results": [
    { "uuid": "...", "status": "applied", "rev": 3, "seq": 8 },
    { "uuid": "...", "status": "conflict", "server": { /* 完整的服务端版本 */ } }
  ],
  "collection": "contacts",
  "serverSeq": 8,
  "serverTime": 1785044252000
}
```

判定规则：

```
baseRev == 服务端当前 rev  →  applied，rev + 1
baseRev == 0 且服务端没有这个 uuid  →  applied，rev = 1（新建）
其它                       →  conflict，原样退回服务端版本
```

约束：

- 一次最多 200 条
- 同一批里不能有重复 uuid
- uuid 必须是 36 字符的 UUID 格式
- **整批一个事务**，要么全落库要么全不落（避免 seq 出现空洞）

> 加密时的 `rev` 要用 `baseRev + 1`，因为 AAD 绑定了 rev。
> 撞冲突后要用服务端返回的 rev 重新加密。

### `GET /v1/sync/status`

```json
{
  "serverSeq": 12,
  "collections": {
    "contacts": { "records": 5, "tombstones": 1, "cipherBytes": 3072 },
    "calls":    { "records": 2, "tombstones": 0, "cipherBytes": 512 }
  },
  "serverTime": 1785044252000
}
```

---

## 头像

内容寻址。`hash` 是 `HMAC(HKDF(DEK,"fc.blobid.v1"), 明文字节)` 的十六进制——
**不是明文 SHA-256**，否则服务器看到两个账号有同一个 hash 就知道它们存了同一张图。

### `PUT /v1/blobs/:hash`

```json
{ "nonce": "<base64，12 字节>", "ciphertext": "<base64，≤4MB>" }
```

已存在时静默成功（`ON CONFLICT DO NOTHING`）。

### `GET /v1/blobs/:hash`

```json
{ "hash": "...", "nonce": "...", "ciphertext": "..." }
```

### `DELETE /v1/blobs/:hash`

### `POST /v1/blobs/missing`

批量查哪些还没传过，避免重复上传大文件。一次最多 500 个。

```json
{ "hashes": ["abc...", "def..."] }
→ { "missing": ["def..."] }
```

---

## 其它

### `GET /v1/health`

不需要认证。

```json
{ "ok": true, "time": 1785044252000 }
```

---

## 客户端要注意的几件事

**访问令牌 15 分钟过期。** 遇到 401 自动用刷新令牌续一次，
只重试一次，再失败就让用户重新登录（见 `SyncApi.refreshTokens()`）。

**遇到 `refresh_token_reuse` 不要重试。** 那意味着令牌可能已经泄露，
设备已被服务端吊销。

**必须用 https。** 内容是密文，但访问令牌明文过网的话，
拿到它的人可以删掉服务器上全部数据。客户端 `SessionStore.validateUrl()`
明确拒绝 `http://`。

**同步清单是一条特殊记录。** uuid 固定为
`00000000-0000-4000-8000-000000000001`，客户端拉取时要把它挑出来
单独处理，别当成联系人建出来。

**两个 collection 的 uuid 可以重名。** 主键是
`(account_id, collection, uuid)`，互不干扰。
