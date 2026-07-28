import type { FastifyInstance } from 'fastify';
import { statSync, readdirSync } from 'node:fs';
import { db, COLLECTIONS } from '../db.ts';
import { config } from '../config.ts';
import { uuid } from '../lib/crypto.ts';
import { HttpError, requireString, requireInt, clientIp } from '../lib/http.ts';
import { tooManyAttempts, recordAttempt, clearAttempts } from '../lib/ratelimit.ts';
import {
  hashAdminPassword, verifyAdminPassword, checkPasswordStrength,
  createAdminSession, destroyAdminSession, setSessionCookie, clearSessionCookie,
  requireAdmin, createInvite, listInvites, countAdmins, findAdmin,
} from '../lib/admin.ts';

/**
 * 管理后台的接口。
 *
 * ⚠ 这里**没有也不可能有**「查看用户联系人」这类端点。
 * 服务器上只有密文，解密密钥只存在用户的口令里。
 * 管理员能看到的是元数据：账号名、记录条数、占用字节、设备活跃时间。
 * 内容一个字都看不到 —— 这是端到端加密的定义，不是功能没做。
 */
export function registerAdminRoutes(app: FastifyInstance): void {

  // ------------------------------------------------------------ 引导

  /**
   * 一台全新的服务器还没有任何管理员时，允许创建第一个。
   * 有了之后这个端点永久关闭 —— 否则任何人都能给自己开管理员。
   */
  app.get('/v1/admin/bootstrap-needed', async () => ({ needed: countAdmins() === 0 }));

  app.post('/v1/admin/bootstrap', async (req) => {
    if (countAdmins() > 0) {
      throw new HttpError(403, 'bootstrap_closed', '已经存在管理员，这个入口已关闭');
    }
    const body = req.body as Record<string, unknown>;
    const username = requireString(body.username, 'username', 64);
    const password = requireString(body.password, 'password', 200);

    const weak = checkPasswordStrength(password);
    if (weak) throw new HttpError(400, 'weak_password', weak);

    const id = uuid();
    db.prepare('INSERT INTO admins (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)')
      .run(id, username, await hashAdminPassword(password), Date.now());
    return { ok: true, username };
  });

  // ------------------------------------------------------------ 登录

  app.post('/v1/admin/login', async (req, reply) => {
    const body = req.body as Record<string, unknown>;
    const username = requireString(body.username, 'username', 64);
    const password = requireString(body.password, 'password', 200);

    // 管理后台的限流比用户端更严：这是能删掉所有人数据的入口
    const ipKey = `admin:ip:${clientIp(req, config.trustProxy)}`;
    const userKey = `admin:user:${username.toLowerCase()}`;
    if (tooManyAttempts(ipKey, 10, 3600_000) || tooManyAttempts(userKey, 5, 3600_000)) {
      throw new HttpError(429, 'too_many_attempts', '尝试次数过多，请一小时后再试');
    }

    const admin = findAdmin(username);
    // 用户名不存在时也走一遍 Argon2，避免用响应时间区分
    const stored = admin?.password_hash
      ?? 'argon2id$1$3$47104$' + '00'.repeat(16) + '$' + '00'.repeat(32);
    const ok = await verifyAdminPassword(password, stored);

    if (!admin || admin.disabled || !ok) {
      recordAttempt(ipKey);
      recordAttempt(userKey);
      throw new HttpError(401, 'invalid_credentials', '用户名或口令不正确');
    }
    clearAttempts(userKey);

    const token = createAdminSession(
      admin.id,
      clientIp(req, config.trustProxy),
      String(req.headers['user-agent'] ?? '')
    );
    db.prepare('UPDATE admins SET last_login_at = ? WHERE id = ?').run(Date.now(), admin.id);
    setSessionCookie(reply, token);
    return { ok: true, username: admin.username };
  });

  app.post('/v1/admin/logout', async (req, reply) => {
    const cookie = String(req.headers.cookie ?? '');
    const m = cookie.match(/(?:^|;\s*)fc_admin=([^;]+)/);
    if (m) destroyAdminSession(m[1]!);
    clearSessionCookie(reply);
    return { ok: true };
  });

  app.get('/v1/admin/me', async (req) => {
    const admin = requireAdmin(req);
    return { username: admin.username, adminId: admin.adminId };
  });

  app.post('/v1/admin/password', async (req) => {
    const admin = requireAdmin(req);
    const body = req.body as Record<string, unknown>;
    const current = requireString(body.currentPassword, 'currentPassword', 200);
    const next = requireString(body.newPassword, 'newPassword', 200);

    const weak = checkPasswordStrength(next);
    if (weak) throw new HttpError(400, 'weak_password', weak);

    const row = db.prepare('SELECT password_hash FROM admins WHERE id = ?').get(admin.adminId) as
      | { password_hash: string } | undefined;
    if (!row || !(await verifyAdminPassword(current, row.password_hash))) {
      throw new HttpError(401, 'invalid_credentials', '当前口令不正确');
    }

    db.prepare('UPDATE admins SET password_hash = ? WHERE id = ?')
      .run(await hashAdminPassword(next), admin.adminId);
    // 改口令后踢掉自己的其它会话
    db.prepare('DELETE FROM admin_sessions WHERE admin_id = ?').run(admin.adminId);
    return { ok: true };
  });

  // ------------------------------------------------------------ 账号管理

  app.get('/v1/admin/accounts', async (req) => {
    requireAdmin(req);
    const rows = db
      .prepare(
        `SELECT a.id, a.username, a.created_at, a.disabled, a.seq, a.vault_version,
                (SELECT COUNT(*) FROM devices d WHERE d.account_id = a.id AND d.revoked = 0) AS devices,
                (SELECT COUNT(*) FROM records r WHERE r.account_id = a.id AND r.deleted = 0) AS records,
                (SELECT COALESCE(SUM(r.size), 0) FROM records r WHERE r.account_id = a.id) AS record_bytes,
                (SELECT COALESCE(SUM(b.size), 0) FROM blobs b WHERE b.account_id = a.id) AS blob_bytes,
                (SELECT MAX(d.last_seen_at) FROM devices d WHERE d.account_id = a.id) AS last_seen
         FROM accounts a ORDER BY a.created_at DESC`
      )
      .all() as Array<Record<string, unknown>>;

    return {
      accounts: rows.map((r) => ({
        id: r.id, username: r.username, createdAt: r.created_at,
        disabled: r.disabled === 1, devices: r.devices, records: r.records,
        bytes: (r.record_bytes as number) + (r.blob_bytes as number),
        lastSeenAt: r.last_seen, seq: r.seq, vaultVersion: r.vault_version,
      })),
    };
  });

  app.post('/v1/admin/accounts/:id/disabled', async (req) => {
    requireAdmin(req);
    const id = (req.params as { id: string }).id;
    const disabled = (req.body as Record<string, unknown>).disabled === true;

    const exists = db.prepare('SELECT 1 FROM accounts WHERE id = ?').get(id);
    if (!exists) throw new HttpError(404, 'not_found', '账号不存在');

    db.transaction(() => {
      db.prepare('UPDATE accounts SET disabled = ? WHERE id = ?').run(disabled ? 1 : 0, id);
      if (disabled) {
        // 停用要立刻生效：删掉刷新令牌，访问令牌最多 15 分钟后失效
        db.prepare('DELETE FROM refresh_tokens WHERE account_id = ?').run(id);
      }
    })();
    return { ok: true, disabled };
  });

  /**
   * 删除账号会连带删掉全部密文，**不可恢复**。
   * 要求管理员再输一遍用户名当二次确认，避免手滑点错行。
   */
  app.delete('/v1/admin/accounts/:id', async (req) => {
    requireAdmin(req);
    const id = (req.params as { id: string }).id;
    const confirm = (req.query as Record<string, unknown>).confirmUsername;

    const row = db.prepare('SELECT username FROM accounts WHERE id = ?').get(id) as
      | { username: string } | undefined;
    if (!row) throw new HttpError(404, 'not_found', '账号不存在');
    if (confirm !== row.username) {
      throw new HttpError(400, 'confirm_mismatch', '确认用的用户名不匹配');
    }

    db.prepare('DELETE FROM accounts WHERE id = ?').run(id);
    return { ok: true };
  });

  // ------------------------------------------------------------ 设备

  app.get('/v1/admin/accounts/:id/devices', async (req) => {
    requireAdmin(req);
    const id = (req.params as { id: string }).id;
    const rows = db
      .prepare(
        `SELECT d.id, d.name, d.created_at, d.last_seen_at, d.revoked,
                (SELECT COUNT(*) FROM refresh_tokens t WHERE t.device_id = d.id AND t.consumed_at IS NULL) AS active_tokens
         FROM devices d WHERE d.account_id = ? ORDER BY d.last_seen_at DESC`
      )
      .all(id) as Array<Record<string, unknown>>;
    return {
      devices: rows.map((r) => ({
        id: r.id, name: r.name, createdAt: r.created_at, lastSeenAt: r.last_seen_at,
        revoked: r.revoked === 1, activeTokens: r.active_tokens,
      })),
    };
  });

  app.delete('/v1/admin/devices/:id', async (req) => {
    requireAdmin(req);
    const id = (req.params as { id: string }).id;
    const exists = db.prepare('SELECT 1 FROM devices WHERE id = ?').get(id);
    if (!exists) throw new HttpError(404, 'not_found', '设备不存在');
    db.transaction(() => {
      db.prepare('UPDATE devices SET revoked = 1 WHERE id = ?').run(id);
      db.prepare('DELETE FROM refresh_tokens WHERE device_id = ?').run(id);
    })();
    return { ok: true };
  });

  // ------------------------------------------------------------ 邀请码

  app.get('/v1/admin/invites', async (req) => {
    requireAdmin(req);
    return {
      invites: listInvites(),
      // 提示管理员：库里没有邀请码时旧的 .env 那个还生效
      envFallbackActive:
        !db.prepare('SELECT 1 FROM invites WHERE revoked = 0 LIMIT 1').get() && config.registrationToken !== '',
    };
  });

  app.post('/v1/admin/invites', async (req) => {
    const admin = requireAdmin(req);
    const body = req.body as Record<string, unknown>;
    const label = typeof body.label === 'string' ? body.label : '';
    // maxUses = 0 表示不限次数
    const maxUses = requireInt(body.maxUses ?? 1, 'maxUses', 0, 1000);
    const expiresInDays =
      body.expiresInDays === null || body.expiresInDays === undefined
        ? null
        : requireInt(body.expiresInDays, 'expiresInDays', 1, 3650);

    const invite = createInvite(admin.username, label, maxUses, expiresInDays);
    // code 只在这一次返回，库里只有哈希
    return { ok: true, id: invite.id, code: invite.code };
  });

  app.delete('/v1/admin/invites/:id', async (req) => {
    requireAdmin(req);
    const id = (req.params as { id: string }).id;
    const r = db.prepare('UPDATE invites SET revoked = 1 WHERE id = ?').run(id);
    if (r.changes === 0) throw new HttpError(404, 'not_found', '邀请码不存在');
    return { ok: true };
  });

  // ------------------------------------------------------------ 服务器状态

  app.get('/v1/admin/stats', async (req) => {
    requireAdmin(req);

    const counts = db
      .prepare(
        `SELECT
           (SELECT COUNT(*) FROM accounts) AS accounts,
           (SELECT COUNT(*) FROM accounts WHERE disabled = 1) AS disabled_accounts,
           (SELECT COUNT(*) FROM devices WHERE revoked = 0) AS devices,
           (SELECT COUNT(*) FROM records WHERE deleted = 0) AS records,
           (SELECT COUNT(*) FROM records WHERE deleted = 1) AS tombstones,
           (SELECT COUNT(*) FROM blobs) AS blobs,
           (SELECT COALESCE(SUM(size),0) FROM records) AS record_bytes,
           (SELECT COALESCE(SUM(size),0) FROM blobs) AS blob_bytes,
           (SELECT COUNT(*) FROM invites WHERE revoked = 0) AS invites`
      )
      .get() as Record<string, number>;

    const byCollection = db
      .prepare(
        `SELECT collection, COUNT(*) AS n, COALESCE(SUM(size),0) AS bytes
         FROM records WHERE deleted = 0 GROUP BY collection`
      )
      .all() as Array<{ collection: string; n: number; bytes: number }>;

    let dbBytes = 0;
    try { dbBytes = statSync(config.dbPath).size; } catch { /* 文件还没建 */ }

    // 最近一小时的失败登录，用来发现有人在爆破
    const recentFailures = db
      .prepare('SELECT key, COUNT(*) AS n FROM auth_attempts WHERE at > ? GROUP BY key ORDER BY n DESC LIMIT 10')
      .all(Date.now() - 3600_000) as Array<{ key: string; n: number }>;

    let backups: Array<{ name: string; bytes: number; at: number }> = [];
    try {
      const dir = '/app/data';
      backups = readdirSync(dir)
        .filter((f) => f.startsWith('sync-') && f.endsWith('.db'))
        .map((f) => { const st = statSync(`${dir}/${f}`); return { name: f, bytes: st.size, at: st.mtimeMs }; })
        .sort((a, b) => b.at - a.at)
        .slice(0, 7);
    } catch { /* 备份在宿主机上，容器里看不到，正常 */ }

    return {
      counts,
      collections: Object.fromEntries(
        COLLECTIONS.map((c) => {
          const row = byCollection.find((r) => r.collection === c);
          return [c, { records: row?.n ?? 0, bytes: row?.bytes ?? 0 }];
        })
      ),
      dbBytes,
      backups,
      recentAuthFailures: recentFailures.map((r) => ({
        // key 形如 login:ip:1.2.3.4 / admin:user:xxx，直接给管理员看
        key: r.key, count: r.n,
      })),
      uptimeSec: Math.floor(process.uptime()),
      nodeVersion: process.version,
      serverTime: Date.now(),
    };
  });

  app.get('/v1/admin/sessions', async (req) => {
    const admin = requireAdmin(req);
    const rows = db
      .prepare(
        `SELECT s.created_at, s.expires_at, s.ip, s.user_agent, a.username
         FROM admin_sessions s JOIN admins a ON a.id = s.admin_id
         ORDER BY s.created_at DESC LIMIT 50`
      )
      .all() as Array<Record<string, unknown>>;
    return {
      current: admin.username,
      sessions: rows.map((r) => ({
        username: r.username, createdAt: r.created_at, expiresAt: r.expires_at,
        ip: r.ip, userAgent: r.user_agent,
      })),
    };
  });
}
