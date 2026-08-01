import { createHash, randomUUID } from 'node:crypto';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import {
  generateAuthenticationOptions,
  generateRegistrationOptions,
  verifyAuthenticationResponse,
  verifyRegistrationResponse,
} from '@simplewebauthn/server';
import { config } from '../config.ts';
import { db } from '../db.ts';
import { HttpError, clientIp, requireString } from './http.ts';
import {
  createAdminSession,
  requireAdmin,
  setSessionCookie,
} from './admin.ts';
import {
  generateBackupCodes,
  generateSecret,
  otpauthUri,
  verifyCode,
} from './totp.ts';

const CHALLENGE_TTL_MS = 5 * 60_000;

type Settings = {
  admin_id: string;
  totp_enabled: number;
  passkey_enabled: number;
  require_all: number;
};

type AdminMfaMethod = 'totp' | 'passkey' | 'backup';

type PasskeyRow = {
  id: string;
  admin_id: string;
  credential_id: string;
  public_key: Buffer;
  sign_count: number;
  transports: string;
};

function rpConfig() {
  const url = new URL(config.publicOrigin);
  return { rpID: url.hostname, rpName: 'Contacts Admin', origin: url.origin };
}

function settings(adminId: string): Settings {
  return (db.prepare('SELECT * FROM admin_mfa_settings WHERE admin_id = ?').get(adminId) as Settings | undefined)
    ?? { admin_id: adminId, totp_enabled: 0, passkey_enabled: 0, require_all: 0 };
}

function updateSettings(adminId: string, patch: Partial<Settings>): void {
  const current = settings(adminId);
  db.prepare(
    `INSERT INTO admin_mfa_settings (admin_id, totp_enabled, passkey_enabled, require_all, updated_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(admin_id) DO UPDATE SET
       totp_enabled = excluded.totp_enabled,
       passkey_enabled = excluded.passkey_enabled,
       require_all = excluded.require_all,
       updated_at = excluded.updated_at`
  ).run(
    adminId,
    patch.totp_enabled ?? current.totp_enabled,
    patch.passkey_enabled ?? current.passkey_enabled,
    patch.require_all ?? current.require_all,
    Date.now(),
  );
}

export function adminMfaRequired(adminId: string): boolean {
  const s = settings(adminId);
  return s.totp_enabled === 1 || s.passkey_enabled === 1;
}

function methods(adminId: string): string[] {
  const s = settings(adminId);
  const result: string[] = [];
  if (s.totp_enabled) result.push('totp');
  if (s.passkey_enabled) result.push('passkey');
  if (db.prepare('SELECT 1 FROM admin_mfa_backup_codes WHERE admin_id = ? LIMIT 1').get(adminId)) {
    result.push('backup');
  }
  return result;
}

export function createAdminLoginChallenge(adminId: string, req: FastifyRequest) {
  const token = randomUUID();
  db.prepare(
    `INSERT INTO admin_mfa_challenges
       (token, admin_id, challenge, purpose, expires_at, ip, user_agent)
     VALUES (?, ?, '', 'login', ?, ?, ?)`
  ).run(
    token,
    adminId,
    Date.now() + CHALLENGE_TTL_MS,
    clientIp(req, config.trustProxy),
    String(req.headers['user-agent'] ?? '').slice(0, 200),
  );
  return {
    mfaRequired: true,
    mfaToken: token,
    methods: methods(adminId),
    requireAll: settings(adminId).require_all === 1,
  };
}

function adminMfaSatisfied(adminId: string, passed: Set<AdminMfaMethod>): boolean {
  // 备用码是应急入口，设备丢失时仍应能恢复管理员登录。
  if (passed.has('backup')) return true;
  const s = settings(adminId);
  const need: Array<'totp' | 'passkey'> = [];
  if (s.totp_enabled) need.push('totp');
  if (s.passkey_enabled) need.push('passkey');
  if (need.length === 0) return true;
  return s.require_all === 1
    ? need.every((method) => passed.has(method))
    : need.some((method) => passed.has(method));
}

function takeChallenge(token: string, purpose: 'login' | 'register') {
  const row = db.prepare(
    'SELECT * FROM admin_mfa_challenges WHERE token = ? AND purpose = ?'
  ).get(token, purpose) as {
    token: string;
    admin_id: string;
    challenge: string;
    expires_at: number;
    ip: string;
    user_agent: string;
  } | undefined;
  if (!row) throw new HttpError(400, 'bad_challenge', '验证会话不存在或已经使用');
  db.prepare('DELETE FROM admin_mfa_challenges WHERE token = ?').run(token);
  if (row.expires_at < Date.now()) throw new HttpError(400, 'challenge_expired', '验证会话已过期，请重新登录');
  return row;
}

function challengeForOptions(token: string, purpose: 'login' | 'register') {
  const row = db.prepare(
    'SELECT * FROM admin_mfa_challenges WHERE token = ? AND purpose = ?'
  ).get(token, purpose) as {
    admin_id: string;
    expires_at: number;
  } | undefined;
  if (!row || row.expires_at < Date.now()) throw new HttpError(400, 'challenge_expired', '验证会话已过期，请重新开始');
  return row;
}

function hashBackupCode(code: string): string {
  return createHash('sha256').update(code.replace(/[\s-]/g, '').toUpperCase()).digest('hex');
}

function replaceBackupCodes(adminId: string): string[] {
  const codes = generateBackupCodes();
  db.transaction(() => {
    db.prepare('DELETE FROM admin_mfa_backup_codes WHERE admin_id = ?').run(adminId);
    const insert = db.prepare('INSERT INTO admin_mfa_backup_codes (admin_id, code_hash) VALUES (?, ?)');
    for (const code of codes) insert.run(adminId, hashBackupCode(code));
  })();
  return codes;
}

async function verifyAdminPasskey(adminId: string, challenge: string, response: unknown): Promise<boolean> {
  const id = (response as { id?: string } | null)?.id ?? '';
  const credential = db.prepare(
    'SELECT * FROM admin_mfa_passkeys WHERE admin_id = ? AND credential_id = ?'
  ).get(adminId, id) as PasskeyRow | undefined;
  if (!credential) return false;

  const { rpID, origin } = rpConfig();
  const result = await verifyAuthenticationResponse({
    response: response as never,
    expectedChallenge: challenge,
    expectedOrigin: origin,
    expectedRPID: rpID,
    credential: {
      id: credential.credential_id,
      publicKey: new Uint8Array(credential.public_key),
      counter: credential.sign_count,
      transports: credential.transports ? credential.transports.split(',') as never : undefined,
    },
  }).catch(() => null);
  if (!result?.verified) return false;

  const next = result.authenticationInfo.newCounter;
  if (next > 0 && next <= credential.sign_count) {
    throw new HttpError(401, 'passkey_cloned', '通行密钥计数异常，请删除后重新注册');
  }
  db.prepare('UPDATE admin_mfa_passkeys SET sign_count = ?, last_used_at = ? WHERE id = ?')
    .run(next, Date.now(), credential.id);
  return true;
}

export function registerAdminMfaRoutes(app: FastifyInstance): void {
  app.post('/v1/admin/login/mfa/options', async (req) => {
    const token = requireString((req.body as Record<string, unknown>).mfaToken, 'mfaToken', 64);
    const row = challengeForOptions(token, 'login');
    const credentials = db.prepare(
      'SELECT credential_id, transports FROM admin_mfa_passkeys WHERE admin_id = ?'
    ).all(row.admin_id) as Array<{ credential_id: string; transports: string }>;
    if (credentials.length === 0) return { options: null };

    const options = await generateAuthenticationOptions({
      rpID: rpConfig().rpID,
      allowCredentials: credentials.map((c) => ({
        id: c.credential_id,
        transports: c.transports ? c.transports.split(',') as never : undefined,
      })),
      userVerification: 'required',
    });
    db.prepare('UPDATE admin_mfa_challenges SET challenge = ? WHERE token = ?').run(options.challenge, token);
    return { options };
  });

  app.post('/v1/admin/login/mfa/complete', async (req, reply) => {
    const body = req.body as Record<string, unknown>;
    const token = requireString(body.mfaToken, 'mfaToken', 64);
    const stored = takeChallenge(token, 'login');
    const admin = db.prepare('SELECT username, disabled FROM admins WHERE id = ?').get(stored.admin_id) as
      { username: string; disabled: number } | undefined;
    if (!admin || admin.disabled) throw new HttpError(401, 'invalid_credentials', '管理员账户不可用');

    const passed = new Set<AdminMfaMethod>();
    if (typeof body.totpCode === 'string') {
      const row = db.prepare(
        'SELECT secret FROM admin_mfa_totp WHERE admin_id = ? AND confirmed_at IS NOT NULL'
      ).get(stored.admin_id) as { secret: string } | undefined;
      if (row && verifyCode(row.secret, body.totpCode)) passed.add('totp');
    }
    if (typeof body.backupCode === 'string') {
      const hash = hashBackupCode(body.backupCode);
      const used = db.prepare(
        'DELETE FROM admin_mfa_backup_codes WHERE admin_id = ? AND code_hash = ?'
      ).run(stored.admin_id, hash);
      if (used.changes === 1) passed.add('backup');
    }
    if (body.passkey && stored.challenge) {
      if (await verifyAdminPasskey(stored.admin_id, stored.challenge, body.passkey)) passed.add('passkey');
    }
    if (!adminMfaSatisfied(stored.admin_id, passed)) {
      throw new HttpError(401, 'mfa_failed', '管理员二次验证未通过');
    }

    const session = createAdminSession(stored.admin_id, stored.ip, stored.user_agent);
    db.prepare('UPDATE admins SET last_login_at = ? WHERE id = ?').run(Date.now(), stored.admin_id);
    setSessionCookie(reply, session);
    return { ok: true, username: admin.username };
  });

  app.get('/v1/admin/mfa/status', async (req) => {
    const admin = requireAdmin(req);
    const s = settings(admin.adminId);
    const pending = db.prepare('SELECT confirmed_at FROM admin_mfa_totp WHERE admin_id = ?')
      .get(admin.adminId) as { confirmed_at: number | null } | undefined;
    const passkeys = db.prepare(
      'SELECT id, name, created_at, last_used_at FROM admin_mfa_passkeys WHERE admin_id = ? ORDER BY created_at'
    ).all(admin.adminId);
    const backup = db.prepare('SELECT COUNT(*) AS n FROM admin_mfa_backup_codes WHERE admin_id = ?')
      .get(admin.adminId) as { n: number };
    return {
      totpEnabled: s.totp_enabled === 1,
      totpPending: !!pending && pending.confirmed_at === null,
      passkeyEnabled: s.passkey_enabled === 1,
      requireAll: s.require_all === 1,
      passkeys,
      backupCodesLeft: backup.n,
    };
  });

  app.post('/v1/admin/mfa/settings', async (req) => {
    const admin = requireAdmin(req);
    const body = req.body as Record<string, unknown>;
    if (typeof body.requireAll !== 'boolean') {
      throw new HttpError(400, 'bad_request', 'requireAll 必须是布尔值');
    }
    const s = settings(admin.adminId);
    if (body.requireAll && !(s.totp_enabled && s.passkey_enabled)) {
      throw new HttpError(400, 'need_both_methods', '请先把验证器和通行密钥都设置好');
    }
    updateSettings(admin.adminId, { require_all: body.requireAll ? 1 : 0 });
    return { ok: true };
  });

  app.post('/v1/admin/mfa/totp/setup', async (req) => {
    const admin = requireAdmin(req);
    if (settings(admin.adminId).totp_enabled === 1) {
      throw new HttpError(409, 'totp_already_enabled', '验证器已经启用；如需更换，请先使用当前验证码关闭');
    }
    const secret = generateSecret();
    db.prepare(
      `INSERT INTO admin_mfa_totp (admin_id, secret, confirmed_at, created_at)
       VALUES (?, ?, NULL, ?)
       ON CONFLICT(admin_id) DO UPDATE SET secret = excluded.secret, confirmed_at = NULL, created_at = excluded.created_at`
    ).run(admin.adminId, secret, Date.now());
    return { secret, uri: otpauthUri(admin.username, secret, `${new URL(config.publicOrigin).hostname} Admin`) };
  });

  app.post('/v1/admin/mfa/totp/confirm', async (req) => {
    const admin = requireAdmin(req);
    const code = requireString((req.body as Record<string, unknown>).code, 'code', 12);
    const row = db.prepare('SELECT secret FROM admin_mfa_totp WHERE admin_id = ?').get(admin.adminId) as
      { secret: string } | undefined;
    if (!row || !verifyCode(row.secret, code)) throw new HttpError(401, 'bad_code', '验证码不正确，请检查设备时间');
    db.prepare('UPDATE admin_mfa_totp SET confirmed_at = ? WHERE admin_id = ?').run(Date.now(), admin.adminId);
    updateSettings(admin.adminId, { totp_enabled: 1 });
    return { ok: true, backupCodes: replaceBackupCodes(admin.adminId) };
  });

  app.post('/v1/admin/mfa/totp/disable', async (req) => {
    const admin = requireAdmin(req);
    const code = requireString((req.body as Record<string, unknown>).code, 'code', 12);
    const row = db.prepare('SELECT secret FROM admin_mfa_totp WHERE admin_id = ? AND confirmed_at IS NOT NULL')
      .get(admin.adminId) as { secret: string } | undefined;
    if (!row || !verifyCode(row.secret, code)) throw new HttpError(401, 'bad_code', '验证码不正确');
    db.prepare('DELETE FROM admin_mfa_totp WHERE admin_id = ?').run(admin.adminId);
    updateSettings(admin.adminId, { totp_enabled: 0, require_all: 0 });
    if (!adminMfaRequired(admin.adminId)) db.prepare('DELETE FROM admin_mfa_backup_codes WHERE admin_id = ?').run(admin.adminId);
    return { ok: true };
  });

  app.post('/v1/admin/mfa/backup/regenerate', async (req) => {
    const admin = requireAdmin(req);
    if (!adminMfaRequired(admin.adminId)) throw new HttpError(400, 'mfa_not_enabled', '请先启用验证器或通行密钥');
    return { ok: true, backupCodes: replaceBackupCodes(admin.adminId) };
  });

  app.post('/v1/admin/mfa/passkey/register/options', async (req) => {
    const admin = requireAdmin(req);
    const existing = db.prepare(
      'SELECT credential_id, transports FROM admin_mfa_passkeys WHERE admin_id = ?'
    ).all(admin.adminId) as Array<{ credential_id: string; transports: string }>;
    const { rpID, rpName } = rpConfig();
    const options = await generateRegistrationOptions({
      rpName,
      rpID,
      userName: admin.username,
      attestationType: 'none',
      excludeCredentials: existing.map((c) => ({
        id: c.credential_id,
        transports: c.transports ? c.transports.split(',') as never : undefined,
      })),
      authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
    });
    const token = randomUUID();
    db.prepare(
      `INSERT INTO admin_mfa_challenges
        (token, admin_id, challenge, purpose, expires_at, ip, user_agent)
       VALUES (?, ?, ?, 'register', ?, '', '')`
    ).run(token, admin.adminId, options.challenge, Date.now() + CHALLENGE_TTL_MS);
    return { token, options };
  });

  app.post('/v1/admin/mfa/passkey/register/verify', async (req) => {
    const admin = requireAdmin(req);
    const body = req.body as Record<string, unknown>;
    const token = requireString(body.token, 'token', 64);
    const name = requireString(body.name ?? '管理员通行密钥', 'name', 40);
    const stored = takeChallenge(token, 'register');
    if (stored.admin_id !== admin.adminId) throw new HttpError(403, 'forbidden', '验证会话不属于当前管理员');
    const { rpID, origin } = rpConfig();
    const result = await verifyRegistrationResponse({
      response: body.response as never,
      expectedChallenge: stored.challenge,
      expectedOrigin: origin,
      expectedRPID: rpID,
      requireUserVerification: true,
    }).catch(() => null);
    if (!result?.verified || !result.registrationInfo) throw new HttpError(400, 'passkey_invalid', '通行密钥验证失败');
    const credential = result.registrationInfo.credential;
    db.prepare(
      `INSERT INTO admin_mfa_passkeys
        (id, admin_id, credential_id, public_key, sign_count, transports, name, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    ).run(
      randomUUID(), admin.adminId, credential.id, Buffer.from(credential.publicKey), credential.counter,
      (credential.transports ?? []).join(','), name, Date.now(),
    );
    updateSettings(admin.adminId, { passkey_enabled: 1 });
    if (!db.prepare('SELECT 1 FROM admin_mfa_backup_codes WHERE admin_id = ? LIMIT 1').get(admin.adminId)) {
      return { ok: true, backupCodes: replaceBackupCodes(admin.adminId) };
    }
    return { ok: true };
  });

  app.delete('/v1/admin/mfa/passkey/:id', async (req) => {
    const admin = requireAdmin(req);
    const id = (req.params as { id: string }).id;
    db.prepare('DELETE FROM admin_mfa_passkeys WHERE id = ? AND admin_id = ?').run(id, admin.adminId);
    const left = db.prepare('SELECT COUNT(*) AS n FROM admin_mfa_passkeys WHERE admin_id = ?')
      .get(admin.adminId) as { n: number };
    if (left.n === 0) updateSettings(admin.adminId, { passkey_enabled: 0, require_all: 0 });
    if (!adminMfaRequired(admin.adminId)) db.prepare('DELETE FROM admin_mfa_backup_codes WHERE admin_id = ?').run(admin.adminId);
    return { ok: true, remaining: left.n };
  });
}
