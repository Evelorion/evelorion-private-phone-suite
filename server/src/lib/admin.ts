import { randomBytes, createHash, timingSafeEqual } from 'node:crypto';
import { argon2id } from 'hash-wasm';
import type { FastifyRequest, FastifyReply } from 'fastify';
import { db, type AdminRow, type InviteRow } from '../db.ts';
import { HttpError } from './http.ts';
import { uuid } from './crypto.ts';

/**
 * 管理后台的认证。
 *
 * ── 和用户认证完全分开 ────────────────────────────────────────
 *
 * 用户那套是零知识的：口令派生出 authSecret，服务器只拿到一个推不回口令的值。
 * 管理员这套是**普通的口令认证** —— 因为管理员本来就不需要任何密钥，
 * 他管的是账号不是数据。
 *
 * 这个区分很要紧：不能让管理员会话拿到任何用户数据端点的访问权。
 * 代码上的体现是 requireAdmin() 和 requireAuth() 是两个独立函数，
 * 各自只认自己那套凭据，没有任何交叉。
 *
 * ── 会话用 Cookie 不用 Bearer ────────────────────────────────
 *
 * 管理后台是浏览器里的页面，用 HttpOnly Cookie 能挡住 XSS 偷令牌
 * （JS 读不到 HttpOnly 的 Cookie）。代价是要防 CSRF，靠三层：
 *   SameSite=Strict + 必须带 X-Admin-Request 头 + 写操作只走 POST/DELETE
 */

const SESSION_COOKIE = 'fc_admin';
const SESSION_TTL_MS = 12 * 3600_000;

/** 管理员口令的哈希参数。比用户那边重一些，因为输入是人选的口令而不是 32 字节高熵值。 */
const ADMIN_ARGON2 = { parallelism: 1, iterations: 3, memorySize: 47104, hashLength: 32 } as const;

export async function hashAdminPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const hash = await argon2id({
    password: password.normalize('NFKC'),
    salt,
    ...ADMIN_ARGON2,
    outputType: 'hex',
  });
  return `argon2id$${ADMIN_ARGON2.parallelism}$${ADMIN_ARGON2.iterations}$${ADMIN_ARGON2.memorySize}$${salt.toString('hex')}$${hash}`;
}

export async function verifyAdminPassword(password: string, stored: string): Promise<boolean> {
  const parts = stored.split('$');
  if (parts.length !== 6 || parts[0] !== 'argon2id') return false;
  const expected = Buffer.from(parts[5]!, 'hex');
  const hash = await argon2id({
    password: password.normalize('NFKC'),
    salt: Buffer.from(parts[4]!, 'hex'),
    parallelism: Number(parts[1]),
    iterations: Number(parts[2]),
    memorySize: Number(parts[3]),
    hashLength: expected.length,
    outputType: 'hex',
  });
  const got = Buffer.from(hash, 'hex');
  return got.length === expected.length && timingSafeEqual(got, expected);
}

/** 口令强度下限。管理员账号能删掉所有人的数据，不该允许弱口令。 */
export function checkPasswordStrength(password: string): string | null {
  if (password.length < 12) return '管理员口令至少 12 个字符';
  if (/^\d+$/.test(password)) return '不能是纯数字';
  if (new Set(password).size < 6) return '字符重复度太高';
  return null;
}

// ---------------------------------------------------------------- 会话

export function createAdminSession(adminId: string, ip: string, userAgent: string): string {
  const token = randomBytes(32).toString('base64url');
  const now = Date.now();
  db.prepare(
    'INSERT INTO admin_sessions (token_hash, admin_id, created_at, expires_at, ip, user_agent) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(sha256(token), adminId, now, now + SESSION_TTL_MS, ip, userAgent.slice(0, 200));
  return token;
}

export function destroyAdminSession(token: string): void {
  db.prepare('DELETE FROM admin_sessions WHERE token_hash = ?').run(sha256(token));
}

export function setSessionCookie(reply: FastifyReply, token: string): void {
  // Secure 必须有 —— 明文过网的会话 Cookie 等于把后台送人
  reply.header(
    'set-cookie',
    `${SESSION_COOKIE}=${token}; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=${SESSION_TTL_MS / 1000}`
  );
}

export function clearSessionCookie(reply: FastifyReply): void {
  reply.header('set-cookie', `${SESSION_COOKIE}=; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=0`);
}

export type AdminContext = { adminId: string; username: string };

/**
 * 管理端点的门卫。
 * **不接受 Bearer 令牌** —— 用户的访问令牌在这里一点用都没有。
 */
export function requireAdmin(req: FastifyRequest): AdminContext {
  // CSRF 第二道：浏览器不会在跨站表单提交里带自定义头
  if (req.headers['x-admin-request'] !== '1') {
    throw new HttpError(403, 'missing_admin_header', '缺少 X-Admin-Request 头');
  }

  const token = parseCookie(req.headers.cookie, SESSION_COOKIE);
  if (!token) throw new HttpError(401, 'admin_unauthorized', '请先登录管理后台');

  const row = db
    .prepare(
      `SELECT s.admin_id, s.expires_at, a.username, a.disabled
       FROM admin_sessions s JOIN admins a ON a.id = s.admin_id
       WHERE s.token_hash = ?`
    )
    .get(sha256(token)) as
    | { admin_id: string; expires_at: number; username: string; disabled: number }
    | undefined;

  if (!row) throw new HttpError(401, 'admin_unauthorized', '会话无效');
  if (row.expires_at < Date.now()) {
    db.prepare('DELETE FROM admin_sessions WHERE token_hash = ?').run(sha256(token));
    throw new HttpError(401, 'admin_session_expired', '会话已过期，请重新登录');
  }
  if (row.disabled) throw new HttpError(403, 'admin_disabled', '该管理员账号已停用');

  return { adminId: row.admin_id, username: row.username };
}

function parseCookie(header: string | undefined, name: string): string | null {
  if (!header) return null;
  for (const part of header.split(';')) {
    const eq = part.indexOf('=');
    if (eq < 0) continue;
    if (part.slice(0, eq).trim() === name) return part.slice(eq + 1).trim();
  }
  return null;
}

function sha256(value: string): Buffer {
  return createHash('sha256').update(value).digest();
}

// ---------------------------------------------------------------- 邀请码

/**
 * 生成邀请码。**明文只在这里返回一次**，库里只存哈希。
 * 管理员没记下来就只能作废重发 —— 这和用户的恢复码是同一个道理。
 */
export function createInvite(
  createdBy: string,
  label: string,
  maxUses: number,
  expiresInDays: number | null
): { id: string; code: string } {
  const code = randomBytes(12).toString('hex');
  const id = uuid();
  db.prepare(
    `INSERT INTO invites (id, code_hash, label, created_by, created_at, expires_at, max_uses, used_count, revoked)
     VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)`
  ).run(
    id,
    sha256(code),
    label.slice(0, 100),
    createdBy,
    Date.now(),
    expiresInDays ? Date.now() + expiresInDays * 86400_000 : null,
    maxUses
  );
  return { id, code };
}

export type InviteCheck = { ok: true; inviteId: string | null } | { ok: false; reason: string };

/**
 * 校验邀请码并计数。
 *
 * 兼容旧的 .env REGISTRATION_TOKEN：库里一条邀请码都没有时才认它。
 * 这样已经部署好的实例不会因为升级而突然注册不了，
 * 但管理员一旦在后台建了邀请码，写死在配置里的那个就自动失效。
 */
export function consumeInvite(code: string, envToken: string): InviteCheck {
  const row = db.prepare('SELECT * FROM invites WHERE code_hash = ?').get(sha256(code)) as
    | InviteRow
    | undefined;

  if (!row) {
    const anyInvite = db.prepare('SELECT 1 FROM invites WHERE revoked = 0 LIMIT 1').get();
    if (!anyInvite && envToken !== '' && code === envToken) {
      return { ok: true, inviteId: null };
    }
    return { ok: false, reason: '邀请码不正确' };
  }

  if (row.revoked) return { ok: false, reason: '该邀请码已作废' };
  if (row.expires_at !== null && row.expires_at < Date.now()) {
    return { ok: false, reason: '该邀请码已过期' };
  }
  if (row.max_uses > 0 && row.used_count >= row.max_uses) {
    return { ok: false, reason: '该邀请码使用次数已用完' };
  }

  db.prepare('UPDATE invites SET used_count = used_count + 1 WHERE id = ?').run(row.id);
  return { ok: true, inviteId: row.id };
}

export function listInvites(): Array<Record<string, unknown>> {
  const rows = db.prepare('SELECT * FROM invites ORDER BY created_at DESC LIMIT 200').all() as InviteRow[];
  return rows.map((r) => ({
    id: r.id,
    label: r.label,
    createdAt: r.created_at,
    expiresAt: r.expires_at,
    maxUses: r.max_uses,
    usedCount: r.used_count,
    revoked: r.revoked === 1,
    // 明文不在库里，这里只能给出状态
    status: r.revoked
      ? 'revoked'
      : r.expires_at !== null && r.expires_at < Date.now()
        ? 'expired'
        : r.max_uses > 0 && r.used_count >= r.max_uses
          ? 'used_up'
          : 'active',
  }));
}

export function countAdmins(): number {
  return (db.prepare('SELECT COUNT(*) AS n FROM admins').get() as { n: number }).n;
}

export function findAdmin(username: string): AdminRow | undefined {
  return db.prepare('SELECT * FROM admins WHERE username = ?').get(username) as AdminRow | undefined;
}
