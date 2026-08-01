# 云端部署指南

这份文档只使用示例地址，不包含任何真实服务器、域名、账号或密钥。
Android APK 必须在本机构建；VPS 只运行 `server`。

## 0. 只有一个公网入口

```text
浏览器 / Android ── https://域名:443 ── Caddy/Nginx ── sync:8443（服务器内部）
```

必须同时满足：

- 网页和 Android 只填写 `https://contacts.example.com:443`
- `PUBLIC_ORIGIN=https://contacts.example.com:443`
- 公网防火墙不开放 8443
- 不运行任何把宿主机 `0.0.0.0:8443` 映射出去的代理容器
- 8443 只允许 Caddy/Nginx 在本机回环或 Docker 内网访问

通行密钥会把端口算进 Web Origin，所以部署文件与 App 必须填写同一个完整入口。
这里明确写出公网 TLS 端口 `:443`；内部 `:8443` 只供反向代理访问，不能混用。

## 1. 准备条件

- 一台 Ubuntu 24.04 VPS
- 一个域名，例如 `contacts.example.com`
- 域名的 A/AAAA 记录已指向 VPS
- 防火墙仅开放 `22`、`80`、`443`
- VPS 已安装 Docker Engine、Docker Compose 插件和 Caddy

安装基础软件：

```bash
sudo apt update
sudo apt install -y ca-certificates curl caddy
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
```

重新登录 SSH 后确认：

```bash
docker --version
docker compose version
caddy version
```

## 2. 获取后端代码

推荐只拉取仓库中的后端目录，Android 源码不需要放到 VPS：

```bash
sudo mkdir -p /opt/contacts-sync
sudo chown "$USER":"$USER" /opt/contacts-sync
cd /opt/contacts-sync

git clone --filter=blob:none --no-checkout YOUR_REPOSITORY_URL repo
cd repo
git sparse-checkout init --cone
git sparse-checkout set server
git checkout main
cp -a server/. /opt/contacts-sync/
cd /opt/contacts-sync
rm -rf repo
```

也可以在本机只打包 `server` 目录，再通过 `scp` 上传；不要上传两个
Android 工程、签名证书或本机配置。

## 3. 创建环境变量

```bash
cd /opt/contacts-sync
cp .env.example .env
SERVER_SECRET="$(openssl rand -hex 32)"
REGISTRATION_TOKEN="$(openssl rand -hex 24)"
sed -i "s|^SERVER_SECRET=.*|SERVER_SECRET=${SERVER_SECRET}|" .env
sed -i "s|^REGISTRATION_TOKEN=.*|REGISTRATION_TOKEN=${REGISTRATION_TOKEN}|" .env
sed -i "s|^PUBLIC_ORIGIN=.*|PUBLIC_ORIGIN=https://contacts.example.com:443|" .env
chmod 600 .env
unset SERVER_SECRET REGISTRATION_TOKEN
```

把 `contacts.example.com` 换成自己的域名。不要把 `.env`、邀请码或
`SERVER_SECRET` 提交到 GitHub，也不要粘贴到聊天或部署日志。

## 4. 启动服务

```bash
cd /opt/contacts-sync
docker compose build
docker compose up -d
docker compose ps
curl --fail http://127.0.0.1:8443/v1/health
```

Compose 只把后端绑定到 `127.0.0.1:8443`，外网不能直接绕过 HTTPS 访问。

## 5. 配置 HTTPS

```bash
sudo cp Caddyfile.example /etc/caddy/Caddyfile
sudo sed -i 's/contacts\.example\.com/你的域名/g' /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

Caddy 会自动申请并续期 TLS 证书。验证：

```bash
curl --fail https://你的域名:443/v1/health
```

如果 443 已由另一个 Docker 总入口负责，则 `sync` 只使用 `expose: ["8443"]`，
不要配置 `ports`。仓库提供了可直接复制的配置：

```bash
cp docker-compose.external-proxy.example.yml docker-compose.yml
echo 'CONTACTS_NETWORK_NAME=contacts-sync-net' >> .env
docker compose up -d
```

把总入口加入 `sync` 所在网络：

```bash
docker network connect 你的同步网络 你的总入口容器名
```

然后在总入口的域名 `server` 块中直接反代到 `http://contacts-sync:8443`。
这仍然只有公网 443 一个入口；Docker 的 `expose` 不会把 8443 发布到宿主机。

应用中的“服务器地址”填写 `https://你的域名:443`，不要把内部服务的 `:8443` 当成公网入口。不要填写邀请码到
源码或 Gradle 配置中。邀请码只在首次注册时输入。

## 6. 创建管理员

```bash
cd /opt/contacts-sync
docker compose exec sync npm run admin:create -- YOUR_ADMIN_NAME
```

按终端提示设置凭据。管理员名称与密码都不要写进仓库。

## 7. 更新后端

先备份，再替换后端源码：

```bash
cd /opt/contacts-sync
mkdir -p backups
docker compose exec -T sync node --input-type=module -e \
  "import Database from 'better-sqlite3'; const db=new Database('/app/data/sync.db'); await db.backup('/app/data/sync-before-update.db'); db.close()"
docker compose cp sync:/app/data/sync-before-update.db backups/sync-before-update.db
docker compose exec -T sync rm -f /app/data/sync-before-update.db
docker compose build --pull
docker compose up -d
docker compose ps
curl --fail https://你的域名:443/v1/health
```

更新后同时检查 API 与两个网页入口：

```bash
curl --fail https://你的域名:443/v1/health
curl --fail https://你的域名:443/user/
curl --fail https://你的域名:443/admin/
```

确认公网 8443 已关闭：

```bash
sudo ss -ltnp | grep ':8443 ' && echo '错误：公网 8443 仍在监听' || true
```

更新前应在本机的 `server` 目录运行：

```bash
npm ci
npm run build
npm run test:e2e
npm run test:web
```

## 8. 自动备份

数据库位于 Docker volume 中。可创建 `/usr/local/bin/contacts-sync-backup.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail
cd /opt/contacts-sync
mkdir -p backups
stamp="$(date +%Y%m%d-%H%M%S)"
docker compose exec -T sync node --input-type=module -e \
  "import Database from 'better-sqlite3'; const db=new Database('/app/data/sync.db'); await db.backup('/app/data/sync-${stamp}.db'); db.close()"
docker compose cp "sync:/app/data/sync-${stamp}.db" "backups/sync-${stamp}.db"
docker compose exec -T sync rm -f "/app/data/sync-${stamp}.db"
find backups -type f -name 'sync-*.db' -mtime +30 -delete
```

```bash
sudo chmod 700 /usr/local/bin/contacts-sync-backup.sh
sudo crontab -e
```

加入：

```cron
20 4 * * * /usr/local/bin/contacts-sync-backup.sh
```

备份仍是业务资产，应加密保存并定期做恢复演练。

## 9. 日常检查

```bash
cd /opt/contacts-sync
docker compose ps
docker compose logs --tail 100 sync
curl --fail https://你的域名:443/v1/health
sudo journalctl -u caddy --since today
```

遇到异常时先查看容器状态和日志，不要把 `.env`、访问令牌或完整数据库上传
到工单、聊天或 GitHub。

## 10. 安全清单

- 公开仓库只放源码和示例；`.env`、签名证书、数据库备份及真实凭据绝不进入 Git
- SSH 使用密钥登录，确认密钥可用后关闭密码登录
- 禁止 root 直接远程登录，使用普通部署账号和 `sudo`
- 只开放 `22`、`80`、`443`
- `.env` 权限为 `600`
- 定期更新系统和容器镜像
- 定期备份并验证恢复
- 签名库只存本机离线备份，不放 VPS、不放 GitHub
