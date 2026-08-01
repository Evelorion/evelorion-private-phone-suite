# 零知识同步服务端

给私密通讯录用的同步服务器。它只存密文 —— 没有你的主口令，
连你自己登进服务器也读不出任何一条联系人。

设计细节见 [`../docs/DESIGN.md`](../docs/DESIGN.md)。

## 部署

```bash
cp .env.example .env

# 生成服务端密钥，填进 .env 的 SERVER_SECRET
openssl rand -hex 32

# 邀请码也务必设一个，否则任何人都能在你的服务器上开账号
# REGISTRATION_TOKEN=你自己起一个

docker compose up -d
curl http://127.0.0.1:8443/v1/health
```

compose 只绑 `127.0.0.1`，TLS 交给前面的反代。最省事的是 Caddy，
`Caddyfile.example` 里有现成配置，换个域名就能用。

公网只保留 `https://域名`（443）一个入口。8443 是回环地址或 Docker 内网里的
后端端口，不能发布到公网；应用的服务器地址也不能带 `:8443`。如果 443 由另一个
Docker 总入口负责，让它与 `sync` 共用内部网络并直接反代到
`http://contacts-sync:8443`。完整步骤见 [`../docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md)。

**不要直接把端口暴露到公网。** 内容确实是密文，但访问令牌会明文过网，
拿到它的人可以删掉你服务器上的全部数据。

## 不用 Docker

```bash
npm install
npm run build
node dist/index.js
```

需要 Node 20 以上。`better-sqlite3` 要编译原生模块，装之前确认有 `python3` 和 `g++`。

## 测试

```bash
npm run test:e2e
```

会起一个临时服务器，模拟两台设备完整跑一遍：注册、双向同步、冲突合并、
恢复码找回、令牌轮换与重放检测，最后**直接搜服务端的数据库文件**，
确认里面找不到任何联系人姓名、号码、口令或恢复码。目前覆盖 163 项断言。

改了 `src/lib/crypto.ts` 或 `test/client.ts` 之后，重新生成交叉校验向量：

```bash
node --experimental-strip-types test/vectors.ts > test/vectors.expected.json
```

然后把新值同步到 Android 的 `CryptoVectorsTest.kt`。两边对不上就说明实现分叉了，
设备之间会解不开彼此的数据。

## 备份

整个数据库就是一个文件：

```bash
sqlite3 data/sync.db ".backup '/backup/sync-$(date +%F).db'"
```

里面全是密文，可以放心存到任何地方。但**主口令和恢复码一定要另外保管** ——
丢了它们，备份就只是一堆解不开的字节，没有任何人能帮你恢复。

## 运维要点

| 事项 | 说明 |
|---|---|
| 换 `SERVER_SECRET` | 所有设备需要重新登录，数据不受影响 |
| 墓碑清理 | 默认保留 90 天，`TOMBSTONE_TTL_DAYS` 可调。设备离线超过这个时间，删除操作可能同步不到它 |
| 日志 | 只记方法、路径、IP，不记请求体 |
| 限流 | 登录每 IP 每小时 30 次、每账号 10 次；全局每 IP 每分钟 600 次 |
| 磁盘 | 每条联系人密文对齐到 256 字节块，1000 条大约 1 MB；头像另算 |
