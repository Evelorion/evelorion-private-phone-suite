import type { FastifyInstance } from 'fastify';
import { randomUUID, createHash } from 'node:crypto';
import {
  generateRegistrationOptions,
  verifyRegistrationResponse,
  generateAuthenticationOptions,
  verifyAuthenticationResponse,
} from '@simplewebauthn/server';
import { db } from '../db.ts';
import { config } from '../config.ts';
import { HttpError, requireAuth, requireString } from '../lib/http.ts';
import {
  generateSecret,
  verifyCode,
  otpauthUri,
  generateBackupCodes,
} from '../lib/totp.ts';
import { androidAuthenticationOrigins } from '../lib/android-passkeys.ts';

/**
 * 两步验证：TOTP 验证器 + 通行密钥（WebAuthn）。
 *
 * ══════════════════════════════════════════════════════════════
 *  这个功能保护的是什么 —— 必须说清楚，否则会给人错误的安全感
 * ══════════════════════════════════════════════════════════════
 *
 * 这套系统是**零知识**的：服务器只有密文，解密靠主口令派生的密钥。
 *
 * 所以两步验证保护的是**服务器账号**（谁能拉取密文、删除数据、看到
 * 「你有多少条联系人、什么时候同步的」这类元数据），
 * **不是加密本身**。
 *
 * 具体说：
 *   开了 2FA，拿到你主口令的人**仍然能解开他已经拿到手的密文** ——
 *   他只是没法再从服务器拉新的。
 *   反过来，2FA 挡不住服务器管理员，因为管理员本来就能读密文
 *   （只是读不懂）。
 *
 * 真正保护数据内容的只有主口令。2FA 是防「口令泄露后被人登录、
 * 拉走全部密文或者把你的数据删了」。
 *
 * ══════════════════════════════════════════════════════════════
 *  两个开关的语义
 * ══════════════════════════════════════════════════════════════
 *
 *   totpEnabled     开了 TOTP
 *   passkeyEnabled  开了通行密钥
 *   requireAll      false = 任一通过即可；true = 两个都必须过
 *
 * requireAll 默认 false。默认 true 的话，用户配好 TOTP 还没配通行密钥
 * 就会把自己锁在外面 —— 登录要求两个，但他只有一个。
 */

const CHALLENGE_TTL_MS = 5 * 60_000;
type MfaSettings = {
  account_id: string;
  totp_enabled: number;
  passkey_enabled: number;
  require_all: number;
};

type PasskeyRow = {
  id: string;
  account_id: string;
  credential_id: string;
  public_key: Buffer;
  sign_count: number;
  transports: string;
  name: string;
  created_at: number;
  last_used_at: number | null;
};

type MfaMethod = 'totp' | 'passkey' | 'backup';

/**
 * WebAuthn 的 Relying Party 标识。
 *
 * rpID 必须是**域名**（不带协议和端口），origin 必须是**完整来源**
 * （带协议和端口）。两者写错任何一个，浏览器会在签名阶段静默拒绝，
 * 错误信息是「NotAllowedError」，看不出是配置问题。
 */
function rpConfig() {
  const url = new URL(config.publicOrigin);
  return {
    rpID: url.hostname,
    rpName: 'Contacts Sync',
    origin: url.origin,
    authenticationOrigins: [url.origin, ...androidAuthenticationOrigins()],
  };
}

export function getMfaSettings(accountId: string): MfaSettings {
  const row = db
    .prepare('SELECT * FROM mfa_settings WHERE account_id = ?')
    .get(accountId) as MfaSettings | undefined;
  return (
    row ?? {
      account_id: accountId,
      totp_enabled: 0,
      passkey_enabled: 0,
      require_all: 0,
    }
  );
}

/** 账号是否开了任何一种两步验证。登录流程用它决定要不要拦一道。 */
export function mfaRequired(accountId: string): boolean {
  const s = getMfaSettings(accountId);
  return s.totp_enabled === 1 || s.passkey_enabled === 1;
}

function upsertSettings(accountId: string, patch: Partial<MfaSettings>): void {
  const cur = getMfaSettings(accountId);
  db.prepare(
    `INSERT INTO mfa_settings (account_id, totp_enabled, passkey_enabled, require_all, updated_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(account_id) DO UPDATE SET
       totp_enabled = excluded.totp_enabled,
       passkey_enabled = excluded.passkey_enabled,
       require_all = excluded.require_all,
       updated_at = excluded.updated_at`
  ).run(
    accountId,
    patch.totp_enabled ?? cur.totp_enabled,
    patch.passkey_enabled ?? cur.passkey_enabled,
    patch.require_all ?? cur.require_all,
    Date.now()
  );
}

function hashBackupCode(code: string): string {
  // 恢复码本身熵足够（40 位），不需要 Argon2 那种慢哈希 ——
  // 慢哈希是给低熵口令用的，这里用 SHA-256 就够，而且验证快
  return createHash('sha256').update(code.replace(/[\s-]/g, '').toUpperCase()).digest('hex');
}

function replaceBackupCodes(accountId: string): string[] {
  const codes = generateBackupCodes();
  db.transaction(() => {
    db.prepare('DELETE FROM mfa_backup_codes WHERE account_id = ?').run(accountId);
    const insert = db.prepare('INSERT INTO mfa_backup_codes (account_id, code_hash) VALUES (?, ?)');
    codes.forEach((code) => insert.run(accountId, hashBackupCode(code)));
  })();
  return codes;
}

function newChallenge(
  accountId: string,
  challenge: string,
  purpose: 'register' | 'login',
  deviceName = '',
  loginKind = 'password',
): string {
  const token = randomUUID();
  db.prepare(
    `INSERT INTO mfa_challenges
      (token, account_id, challenge, purpose, device_name, login_kind, expires_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`
  ).run(token, accountId, challenge, purpose, deviceName, loginKind, Date.now() + CHALLENGE_TTL_MS);
  return token;
}

/**
 * 取出并销毁一个登录挑战。
 *
 * 导出给 account.ts 用 —— 发令牌的逻辑（issueTokens / vaultPayload）
 * 都在那边，第二步验证通过后要在那里建设备、发令牌。
 */
export function consumeLoginChallenge(token: string): {
  accountId: string;
  challenge: string;
  deviceName: string;
  loginKind: string;
} {
  const row = takeChallenge(token, 'login');
  return {
    accountId: row.account_id,
    challenge: row.challenge,
    deviceName: row.device_name,
    loginKind: row.login_kind,
  };
}

function takeChallenge(token: string, purpose: 'register' | 'login') {
  const row = db
    .prepare('SELECT * FROM mfa_challenges WHERE token = ? AND purpose = ?')
    .get(token, purpose) as
    | { token: string; account_id: string; challenge: string; device_name: string; login_kind: string; expires_at: number }
    | undefined;
  if (!row) throw new HttpError(400, 'bad_challenge', '验证会话不存在或已被使用');
  // 无论成功失败都删掉 —— 挑战值一次性，留着能被重放
  db.prepare('DELETE FROM mfa_challenges WHERE token = ?').run(token);
  if (row.expires_at < Date.now()) {
    throw new HttpError(400, 'challenge_expired', '验证会话已过期，请重新登录');
  }
  return row;
}

/**
 * 校验一次两步验证。
 *
 * @returns 通过的方式列表。调用方拿它和 requireAll 比对。
 */
export async function verifyMfa(
  accountId: string,
  input: { totpCode?: string; backupCode?: string; passkey?: unknown; challenge?: string }
): Promise<Set<MfaMethod>> {
  const passed = new Set<MfaMethod>();

  if (input.totpCode) {
    const row = db
      .prepare('SELECT secret FROM mfa_totp WHERE account_id = ? AND confirmed_at IS NOT NULL')
      .get(accountId) as { secret: string } | undefined;
    if (row && verifyCode(row.secret, input.totpCode)) passed.add('totp');
  }

  // 备用码是整个 MFA 的应急入口，不依附于 TOTP。通行密钥丢失时也必须能用。
  if (input.backupCode) {
    const hash = hashBackupCode(input.backupCode);
    const row = db
      .prepare('SELECT code_hash FROM mfa_backup_codes WHERE account_id = ? AND code_hash = ? AND used_at IS NULL')
      .get(accountId, hash) as { code_hash: string } | undefined;
    if (row) {
      // 用完立刻删。标记 used_at 而不删的话，从备份里翻出旧库就能再用一遍
      db.prepare('DELETE FROM mfa_backup_codes WHERE account_id = ? AND code_hash = ?').run(accountId, hash);
      passed.add('backup');
    }
  }

  if (input.passkey && input.challenge) {
    const { rpID, authenticationOrigins } = rpConfig();
    const response = input.passkey as { id?: string };
    const cred = db
      .prepare('SELECT * FROM mfa_passkeys WHERE account_id = ? AND credential_id = ?')
      .get(accountId, response.id ?? '') as PasskeyRow | undefined;

    if (cred) {
      const verification = await verifyAuthenticationResponse({
        response: input.passkey as never,
        expectedChallenge: input.challenge,
        expectedOrigin: authenticationOrigins,
        expectedRPID: rpID,
        credential: {
          id: cred.credential_id,
          publicKey: new Uint8Array(cred.public_key),
          counter: cred.sign_count,
          transports: cred.transports ? (cred.transports.split(',') as never) : undefined,
        },
      }).catch(() => null);

      if (verification?.verified) {
        const next = verification.authenticationInfo.newCounter;
        // 计数器不增长说明凭据可能被克隆了。规范要求检查，
        // 但有些认证器（尤其是平台内置的）始终返回 0 —— 那种情况不报警
        if (next > 0 && next <= cred.sign_count) {
          throw new HttpError(401, 'passkey_cloned', '通行密钥的计数器异常，这个密钥可能已被复制，请在设置里删除并重新注册');
        }
        db.prepare('UPDATE mfa_passkeys SET sign_count = ?, last_used_at = ? WHERE id = ?')
          .run(next, Date.now(), cred.id);
        passed.add('passkey');
      }
    }
  }

  return passed;
}

/** 判断通过的方式够不够。 */
export function mfaSatisfied(accountId: string, passed: Set<MfaMethod>): boolean {
  if (passed.has('backup')) return true;
  const s = getMfaSettings(accountId);
  const need: ('totp' | 'passkey')[] = [];
  if (s.totp_enabled) need.push('totp');
  if (s.passkey_enabled) need.push('passkey');
  if (need.length === 0) return true;
  return s.require_all === 1 ? need.every((m) => passed.has(m)) : need.some((m) => passed.has(m));
}

export function mfaMethods(accountId: string): string[] {
  const s = getMfaSettings(accountId);
  const out: string[] = [];
  if (s.totp_enabled) out.push('totp');
  if (s.passkey_enabled) out.push('passkey');
  const backup = db
    .prepare('SELECT 1 FROM mfa_backup_codes WHERE account_id = ? LIMIT 1')
    .get(accountId);
  if (backup) out.push('backup');
  return out;
}

export function createLoginChallenge(accountId: string, deviceName: string, loginKind = 'password'): {
  mfaToken: string;
  methods: string[];
  requireAll: boolean;
  passkeyOptions?: unknown;
} {
  const s = getMfaSettings(accountId);
  return {
    mfaToken: newChallenge(accountId, '', 'login', deviceName, loginKind),
    methods: mfaMethods(accountId),
    requireAll: s.require_all === 1,
  };
}

export function registerMfaRoutes(app: FastifyInstance): void {
  // ════════════════════════════════════════════════════ 状态

  app.get('/v1/mfa/status', async (req) => {
    const auth = requireAuth(req);
    const s = getMfaSettings(auth.accountId);
    const totp = db
      .prepare('SELECT confirmed_at FROM mfa_totp WHERE account_id = ?')
      .get(auth.accountId) as { confirmed_at: number | null } | undefined;
    const passkeys = db
      .prepare('SELECT id, name, created_at, last_used_at FROM mfa_passkeys WHERE account_id = ? ORDER BY created_at')
      .all(auth.accountId);
    const backupLeft = db
      .prepare('SELECT COUNT(*) AS n FROM mfa_backup_codes WHERE account_id = ? AND used_at IS NULL')
      .get(auth.accountId) as { n: number };

    return {
      totpEnabled: s.totp_enabled === 1,
      totpPending: !!totp && totp.confirmed_at === null,
      passkeyEnabled: s.passkey_enabled === 1,
      requireAll: s.require_all === 1,
      passkeys,
      backupCodesLeft: backupLeft.n,
    };
  });

  app.post('/v1/mfa/backup/regenerate', async (req) => {
    const auth = requireAuth(req);
    if (!mfaRequired(auth.accountId)) {
      throw new HttpError(400, 'mfa_not_enabled', '请先启用验证器或通行密钥');
    }
    return { ok: true, backupCodes: replaceBackupCodes(auth.accountId) };
  });

  app.post('/v1/mfa/settings', async (req) => {
    const auth = requireAuth(req);
    const body = req.body as Record<string, unknown>;
    if (typeof body.requireAll !== 'boolean') {
      throw new HttpError(400, 'bad_request', 'requireAll 必须是布尔值');
    }
    const s = getMfaSettings(auth.accountId);
    // 只开了一种方式却要求「两个都过」= 把自己锁在外面
    if (body.requireAll && !(s.totp_enabled && s.passkey_enabled)) {
      throw new HttpError(
        400,
        'need_both_methods',
        '要求「两个都验证」之前，请先把验证器和通行密钥都设置好 —— 否则你会登不进来'
      );
    }
    upsertSettings(auth.accountId, { require_all: body.requireAll ? 1 : 0 });
    return { ok: true };
  });

  // ════════════════════════════════════════════════════ TOTP

  app.post('/v1/mfa/totp/setup', async (req) => {
    const auth = requireAuth(req);
    const acc = db.prepare('SELECT username FROM accounts WHERE id = ?').get(auth.accountId) as
      | { username: string }
      | undefined;
    if (!acc) throw new HttpError(404, 'not_found', '账号不存在');

    const secret = generateSecret();
    // 覆盖掉之前未确认的密钥。用户可能扫了码没输验证码就退出了，
    // 下次进来应该拿到一个新的，而不是接着用一个他没存进认证器的
    db.prepare(
      `INSERT INTO mfa_totp (account_id, secret, confirmed_at, created_at) VALUES (?, ?, NULL, ?)
       ON CONFLICT(account_id) DO UPDATE SET secret = excluded.secret, confirmed_at = NULL, created_at = excluded.created_at`
    ).run(auth.accountId, secret, Date.now());

    return { secret, uri: otpauthUri(acc.username, secret, new URL(config.publicOrigin).hostname) };
  });

  app.post('/v1/mfa/totp/confirm', async (req) => {
    const auth = requireAuth(req);
    const code = requireString((req.body as Record<string, unknown>).code, 'code', 12);
    const row = db.prepare('SELECT secret FROM mfa_totp WHERE account_id = ?').get(auth.accountId) as
      | { secret: string }
      | undefined;
    if (!row) throw new HttpError(400, 'no_pending_totp', '请先扫码，再输入验证码');
    if (!verifyCode(row.secret, code)) {
      throw new HttpError(401, 'bad_code', '验证码不对。检查手机时间是否准确');
    }

    db.prepare('UPDATE mfa_totp SET confirmed_at = ? WHERE account_id = ?').run(Date.now(), auth.accountId);
    upsertSettings(auth.accountId, { totp_enabled: 1 });

    return { ok: true, backupCodes: replaceBackupCodes(auth.accountId) };
  });

  app.post('/v1/mfa/totp/disable', async (req) => {
    const auth = requireAuth(req);
    const code = requireString((req.body as Record<string, unknown>).code, 'code', 12);
    const row = db.prepare('SELECT secret FROM mfa_totp WHERE account_id = ?').get(auth.accountId) as
      | { secret: string }
      | undefined;
    // 关掉也要验一次 —— 否则拿到会话的人可以直接把 2FA 关了
    if (!row || !verifyCode(row.secret, code)) {
      throw new HttpError(401, 'bad_code', '验证码不对');
    }
    db.prepare('DELETE FROM mfa_totp WHERE account_id = ?').run(auth.accountId);
    db.prepare('DELETE FROM mfa_backup_codes WHERE account_id = ?').run(auth.accountId);
    // 关掉一种方式后，「两个都要」就不成立了，一起关掉
    upsertSettings(auth.accountId, { totp_enabled: 0, require_all: 0 });
    return { ok: true };
  });

  // ════════════════════════════════════════════════ 通行密钥

  app.post('/v1/mfa/passkey/register/options', async (req) => {
    const auth = requireAuth(req);
    const acc = db.prepare('SELECT username FROM accounts WHERE id = ?').get(auth.accountId) as
      | { username: string }
      | undefined;
    if (!acc) throw new HttpError(404, 'not_found', '账号不存在');

    const existing = db
      .prepare('SELECT credential_id, transports FROM mfa_passkeys WHERE account_id = ?')
      .all(auth.accountId) as { credential_id: string; transports: string }[];

    const { rpID, rpName } = rpConfig();
    const options = await generateRegistrationOptions({
      rpName,
      rpID,
      userName: acc.username,
      attestationType: 'none',
      // 排除已注册的，避免同一个密钥被注册两次 ——
      // 浏览器会直接提示「这个设备已经注册过了」
      excludeCredentials: existing.map((e) => ({
        id: e.credential_id,
        transports: e.transports ? (e.transports.split(',') as never) : undefined,
      })),
      authenticatorSelection: {
        residentKey: 'preferred',
        userVerification: 'preferred',
      },
    });

    return { options, token: newChallenge(auth.accountId, options.challenge, 'register') };
  });

  app.post('/v1/mfa/passkey/register/verify', async (req) => {
    const auth = requireAuth(req);
    const body = req.body as Record<string, unknown>;
    const token = requireString(body.token, 'token', 64);
    const name = requireString(body.name ?? '通行密钥', 'name', 40);

    const stored = takeChallenge(token, 'register');
    if (stored.account_id !== auth.accountId) {
      throw new HttpError(403, 'forbidden', '验证会话不属于当前账号');
    }

    const { rpID, origin } = rpConfig();
    const verification = await verifyRegistrationResponse({
      response: body.response as never,
      expectedChallenge: stored.challenge,
      expectedOrigin: origin,
      expectedRPID: rpID,
    }).catch((e: Error) => {
      throw new HttpError(400, 'passkey_invalid', `通行密钥验证失败：${e.message}`);
    });

    if (!verification.verified || !verification.registrationInfo) {
      throw new HttpError(400, 'passkey_invalid', '通行密钥验证失败');
    }

    const info = verification.registrationInfo;
    db.prepare(
      `INSERT INTO mfa_passkeys (id, account_id, credential_id, public_key, sign_count, transports, name, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    ).run(
      randomUUID(),
      auth.accountId,
      info.credential.id,
      Buffer.from(info.credential.publicKey),
      info.credential.counter,
      (info.credential.transports ?? []).join(','),
      name,
      Date.now()
    );
    upsertSettings(auth.accountId, { passkey_enabled: 1 });
    return { ok: true };
  });

  app.delete('/v1/mfa/passkey/:id', async (req) => {
    const auth = requireAuth(req);
    const id = (req.params as { id: string }).id;
    db.prepare('DELETE FROM mfa_passkeys WHERE id = ? AND account_id = ?').run(id, auth.accountId);

    const left = db
      .prepare('SELECT COUNT(*) AS n FROM mfa_passkeys WHERE account_id = ?')
      .get(auth.accountId) as { n: number };
    if (left.n === 0) {
      // 最后一个删掉了，这一档自动关闭，「两个都要」也跟着关
      upsertSettings(auth.accountId, { passkey_enabled: 0, require_all: 0 });
    }
    return { ok: true, remaining: left.n };
  });

  // ══════════════════════════════════════════ 登录时的验证步骤

  /** 登录第一步返回 mfaToken 之后，前端拿它换通行密钥的挑战。 */
  app.post('/v1/session/mfa/options', async (req) => {
    const token = requireString((req.body as Record<string, unknown>).mfaToken, 'mfaToken', 64);
    const row = db.prepare('SELECT * FROM mfa_challenges WHERE token = ? AND purpose = ?').get(token, 'login') as
      | { account_id: string; expires_at: number }
      | undefined;
    if (!row || row.expires_at < Date.now()) {
      throw new HttpError(400, 'challenge_expired', '验证会话已过期，请重新登录');
    }

    const creds = db
      .prepare('SELECT credential_id, transports FROM mfa_passkeys WHERE account_id = ?')
      .all(row.account_id) as { credential_id: string; transports: string }[];
    if (creds.length === 0) return { options: null };

    const { rpID } = rpConfig();
    const options = await generateAuthenticationOptions({
      rpID,
      allowCredentials: creds.map((c) => ({
        id: c.credential_id,
        transports: c.transports ? (c.transports.split(',') as never) : undefined,
      })),
      userVerification: 'preferred',
    });

    // 把挑战值写回这条记录，验证时要拿它比对
    db.prepare('UPDATE mfa_challenges SET challenge = ? WHERE token = ?').run(options.challenge, token);
    return { options };
  });

}
