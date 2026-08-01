import type { FastifyInstance } from 'fastify';
import { db, type AccountRow } from '../db.ts';
import { config } from '../config.ts';
import {
  hashAuthSecret, verifyAuthSecret, decoySalt, uuid,
  issueAccessToken, newRefreshToken, refreshTokenHash, constantTimeEqual,
} from '../lib/crypto.ts';
import { HttpError, requireAuth, requireString, requireInt, b64, clientIp } from '../lib/http.ts';
import {
  mfaRequired,
  createLoginChallenge,
  consumeLoginChallenge,
  verifyMfa,
  mfaSatisfied,
} from './mfa.ts';
import { tooManyAttempts, recordAttempt, clearAttempts } from '../lib/ratelimit.ts';
import { consumeInvite } from '../lib/admin.ts';

const USERNAME_RE = /^[a-zA-Z0-9._-]{3,64}$/;

function issueTokens(accountId: string, deviceId: string) {
  const access = issueAccessToken(accountId, deviceId);
  const refresh = newRefreshToken();
  const now = Date.now();
  db.prepare(
    'INSERT INTO refresh_tokens (token_hash, account_id, device_id, issued_at, expires_at) VALUES (?, ?, ?, ?, ?)'
  ).run(refresh.hash, accountId, deviceId, now, now + config.refreshTtlDays * 86400_000);
  return {
    accessToken: access.token,
    accessExpiresAt: access.expiresAt,
    refreshToken: refresh.token,
  };
}

function vaultPayload(acc: AccountRow) {
  return {
    accountId: acc.id,
    vaultVersion: acc.vault_version,
    kdf: {
      salt: acc.kdf_salt.toString('base64'),
      memoryKiB: acc.kdf_mem,
      iterations: acc.kdf_time,
      parallelism: acc.kdf_par,
    },
    dekWrapPassword: acc.dek_wrap_password.toString('base64'),
    dekWrapRecovery: acc.dek_wrap_recovery.toString('base64'),
    privateKeyLoginEnabled: acc.recovery_auth_hash !== null,
  };
}

export function registerAccountRoutes(app: FastifyInstance): void {
  /**
   * 客户端必须先拿到盐和 KDF 参数才能算出 authSecret。
   * 对不存在的用户名返回确定性的假盐，避免账号枚举。
   */
  app.get('/v1/account/kdf', async (req) => {
    const username = requireString((req.query as Record<string, unknown>).username, 'username', 64);
    const acc = db.prepare('SELECT * FROM accounts WHERE username = ?').get(username) as AccountRow | undefined;
    if (!acc) {
      return {
        salt: decoySalt(username).toString('base64'),
        memoryKiB: 65536,
        iterations: 3,
        parallelism: 4,
      };
    }
    return {
      salt: acc.kdf_salt.toString('base64'),
      memoryKiB: acc.kdf_mem,
      iterations: acc.kdf_time,
      parallelism: acc.kdf_par,
    };
  });

  app.post('/v1/account/register', async (req) => {
    const body = req.body as Record<string, unknown>;

    // 邀请码现在由管理后台在库里管理。
    // 兼容期：库里一条都没有时，仍认 .env 里那个 REGISTRATION_TOKEN，
    // 这样已经部署好的实例升级后不会突然注册不了。
    const provided = typeof body.registrationToken === 'string' ? body.registrationToken : '';
    const check = consumeInvite(provided, config.registrationToken);
    if (!check.ok) throw new HttpError(403, 'bad_registration_token', check.reason);

    const username = requireString(body.username, 'username', 64);
    if (!USERNAME_RE.test(username)) {
      throw new HttpError(400, 'bad_username', '用户名只能包含字母、数字、点、下划线、连字符，长度 3~64');
    }
    const authSecret = requireString(body.authSecret, 'authSecret', 128);
    if (!/^[0-9a-f]{64}$/.test(authSecret)) throw new HttpError(400, 'bad_request', 'authSecret 必须是 64 位十六进制');

    const kdf = (body.kdf ?? {}) as Record<string, unknown>;
    const salt = b64(kdf.salt, 'kdf.salt', 64);
    if (salt.length < 16) throw new HttpError(400, 'bad_request', 'kdf.salt 至少 16 字节');
    // 下界防止客户端被诱导使用弱参数
    const memoryKiB = requireInt(kdf.memoryKiB, 'kdf.memoryKiB', 32768, 1048576);
    const iterations = requireInt(kdf.iterations, 'kdf.iterations', 2, 20);
    const parallelism = requireInt(kdf.parallelism, 'kdf.parallelism', 1, 16);

    const dekWrapPassword = b64(body.dekWrapPassword, 'dekWrapPassword', 512);
    const dekWrapRecovery = b64(body.dekWrapRecovery, 'dekWrapRecovery', 512);
    const deviceName = requireString(body.deviceName, 'deviceName', 64);

    const exists = db.prepare('SELECT 1 FROM accounts WHERE username = ?').get(username);
    if (exists) throw new HttpError(409, 'username_taken', '用户名已被占用');

    const authHash = await hashAuthSecret(authSecret);

    // 恢复码派生的认证凭据。可选 —— 老客户端不发这个字段，
    // 那样的账号就没法用恢复码登录（只能用口令）。
    const recoveryAuthSecret =
      typeof body.recoveryAuthSecret === 'string' && body.recoveryAuthSecret.length > 0
        ? requireString(body.recoveryAuthSecret, 'recoveryAuthSecret', 128)
        : null;
    const recoveryAuthHash = recoveryAuthSecret ? await hashAuthSecret(recoveryAuthSecret) : null;

    const accountId = uuid();
    const deviceId = uuid();
    const now = Date.now();

    db.transaction(() => {
      db.prepare(
        `INSERT INTO accounts (id, username, kdf_salt, kdf_mem, kdf_time, kdf_par, auth_hash,
                               recovery_auth_hash,
                               dek_wrap_password, dek_wrap_recovery, vault_version, seq, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?)`
      ).run(accountId, username, salt, memoryKiB, iterations, parallelism, authHash,
            recoveryAuthHash,
            dekWrapPassword, dekWrapRecovery, now);
      db.prepare('INSERT INTO devices (id, account_id, name, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)')
        .run(deviceId, accountId, deviceName, now, now);
    })();

    const acc = db.prepare('SELECT * FROM accounts WHERE id = ?').get(accountId) as AccountRow;
    return { ...vaultPayload(acc), deviceId, ...issueTokens(accountId, deviceId), serverSeq: 0 };
  });

  app.post('/v1/session', async (req) => {
    const body = req.body as Record<string, unknown>;
    const username = requireString(body.username, 'username', 64);
    const authSecret = requireString(body.authSecret, 'authSecret', 128);
    const deviceName = requireString(body.deviceName, 'deviceName', 64);

    const ipKey = `login:ip:${clientIp(req, config.trustProxy)}`;
    const userKey = `login:user:${username.toLowerCase()}`;
    if (tooManyAttempts(ipKey, 30, 3600_000) || tooManyAttempts(userKey, 10, 3600_000)) {
      throw new HttpError(429, 'too_many_attempts', '尝试次数过多，请一小时后再试');
    }

    const acc = db.prepare('SELECT * FROM accounts WHERE username = ?').get(username) as AccountRow | undefined;
    // 即使用户名不存在也要走一遍 Argon2，避免用响应时间区分
    const stored = acc?.auth_hash ?? 'argon2id$1$2$19456$00000000000000000000000000000000$' + '00'.repeat(32);
    const passwordOk = await verifyAuthSecret(authSecret, stored);

    if (!acc || acc.disabled || !passwordOk) {
      recordAttempt(ipKey);
      recordAttempt(userKey);
      throw new HttpError(401, 'invalid_credentials', '用户名或口令不正确');
    }
    clearAttempts(userKey);

    // 开了两步验证的话，这一步**不发令牌** —— 只返回一个一次性的
    // mfaToken，客户端拿它去 /v1/session/mfa 走第二步。
    //
    // 注意 vaultPayload 也不返回：那里面有被包裹的 DEK，虽然没有主口令
    // 解不开，但没必要在第一步就交出去。
    if (mfaRequired(acc.id)) {
      return { mfaRequired: true, ...createLoginChallenge(acc.id, deviceName) };
    }

    const deviceId = uuid();
    const now = Date.now();
    db.prepare('INSERT INTO devices (id, account_id, name, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)')
      .run(deviceId, acc.id, deviceName, now, now);

    return { ...vaultPayload(acc), deviceId, ...issueTokens(acc.id, deviceId), serverSeq: acc.seq };
  });

  /**
   * 两步验证的第二步。通过之后才建设备记录、发令牌。
   *
   * 这里重新查一次账号而不是信任第一步传来的数据 ——
   * 两步之间账号可能已经被管理员停用了。
   */
  app.post('/v1/session/mfa/complete', async (req) => {
    const body = req.body as Record<string, unknown>;
    const mfaToken = requireString(body.mfaToken, 'mfaToken', 64);
    const stored = consumeLoginChallenge(mfaToken);

    const acc = db.prepare('SELECT * FROM accounts WHERE id = ?').get(stored.accountId) as AccountRow | undefined;
    if (!acc || acc.disabled) throw new HttpError(401, 'invalid_credentials', '账号不可用');

    const passed = await verifyMfa(acc.id, {
      totpCode: typeof body.totpCode === 'string' ? body.totpCode : undefined,
      backupCode: typeof body.backupCode === 'string' ? body.backupCode : undefined,
      passkey: body.passkey,
      challenge: stored.challenge || undefined,
    });

    if (!mfaSatisfied(acc.id, passed)) {
      throw new HttpError(401, 'mfa_failed', '两步验证没有通过');
    }

    const deviceId = uuid();
    const now = Date.now();
    db.prepare('INSERT INTO devices (id, account_id, name, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)')
      .run(deviceId, acc.id, stored.deviceName, now, now);

    return {
      ...vaultPayload(acc),
      deviceId,
      ...issueTokens(acc.id, deviceId),
      serverSeq: acc.seq,
      mustResetPassphrase: stored.loginKind === 'recovery',
    };
  });

  /**
   * 用恢复码登录。
   *
   * ── 为什么需要这条路 ────────────────────────────────────
   *
   * 恢复码能解开 DEK（服务器上并排存着 dek_wrap_recovery），但登录这一关
   * 需要口令派生的 authSecret —— 忘了口令就过不去，恢复码等于废纸。
   *
   * 所以注册时额外派生一个 HKDF(恢复码, "fc.auth.recovery.v1") 存哈希，
   * 让恢复码本身也能当登录凭据。服务器仍然什么都解不开：
   * 两个都是 Argon2 哈希，推不回原值。
   *
   * ── 登录成功后必须换口令 ────────────────────────────────
   *
   * 响应里带 mustResetPassphrase: true。走到这条路说明用户已经不知道
   * 口令了，让他继续用一个自己不知道的口令没有意义 ——
   * 客户端应当立刻引导他走 /v1/vault/rewrap 设一个新的。
   */
  app.post('/v1/session/recovery', async (req) => {
    const body = req.body as Record<string, unknown>;
    const username = requireString(body.username, 'username', 64);
    const recoveryAuthSecret = requireString(body.recoveryAuthSecret, 'recoveryAuthSecret', 128);
    const deviceName = requireString(body.deviceName, 'deviceName', 64);

    // 恢复码是高熵的，但仍然限流 —— 不限的话它就成了一个可以离线爆破的靶子
    const ipKey = `recovery:ip:${clientIp(req, config.trustProxy)}`;
    const userKey = `recovery:user:${username.toLowerCase()}`;
    if (tooManyAttempts(ipKey, 20, 3600_000) || tooManyAttempts(userKey, 10, 3600_000)) {
      throw new HttpError(429, 'too_many_attempts', '尝试次数过多，请一小时后再试');
    }

    const acc = db.prepare('SELECT * FROM accounts WHERE username = ?').get(username) as
      | AccountRow
      | undefined;

    // 用户名不存在、或者这个账号根本没存恢复凭据时，也要走一遍 Argon2，
    // 否则能用响应时间区分出「这个账号存在且支持恢复码登录」
    const stored =
      acc?.recovery_auth_hash ??
      'argon2id$1$2$19456$00000000000000000000000000000000$' + '00'.repeat(32);
    const ok = await verifyAuthSecret(recoveryAuthSecret, stored);

    if (!acc || acc.disabled || !acc.recovery_auth_hash || !ok) {
      recordAttempt(ipKey);
      recordAttempt(userKey);
      throw new HttpError(401, 'invalid_recovery_code', '恢复码不正确，或这个账号还不支持恢复码登录');
    }
    clearAttempts(userKey);

    // 两步验证在这条路上照样要过 —— 恢复码只替代口令，不替代第二因素
    const directPrivateKeyLogin = body.directLogin === true;
    if (mfaRequired(acc.id)) {
      return {
        mfaRequired: true,
        ...createLoginChallenge(acc.id, deviceName, directPrivateKeyLogin ? 'private_key' : 'recovery'),
      };
    }

    const deviceId = uuid();
    const now = Date.now();
    db.prepare('INSERT INTO devices (id, account_id, name, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)')
      .run(deviceId, acc.id, deviceName, now, now);

    return {
      ...vaultPayload(acc),
      deviceId,
      ...issueTokens(acc.id, deviceId),
      serverSeq: acc.seq,
      mustResetPassphrase: !directPrivateKeyLogin,
    };
  });

  /**
   * 刷新令牌一次性使用并轮换。如果同一个刷新令牌被用第二次，说明它可能已经泄露，
   * 直接吊销该设备的全部令牌。
   */
  app.post('/v1/session/refresh', async (req) => {
    const body = req.body as Record<string, unknown>;
    const token = requireString(body.refreshToken, 'refreshToken', 128);
    const hash = refreshTokenHash(token);

    const row = db.prepare('SELECT * FROM refresh_tokens WHERE token_hash = ?').get(hash) as
      | { account_id: string; device_id: string; expires_at: number; consumed_at: number | null }
      | undefined;
    if (!row) throw new HttpError(401, 'invalid_refresh_token', '刷新令牌无效');

    if (row.consumed_at !== null) {
      db.prepare('UPDATE devices SET revoked = 1 WHERE id = ?').run(row.device_id);
      db.prepare('DELETE FROM refresh_tokens WHERE device_id = ?').run(row.device_id);
      throw new HttpError(401, 'refresh_token_reuse', '刷新令牌被重复使用，该设备已吊销，请重新登录');
    }
    if (row.expires_at < Date.now()) throw new HttpError(401, 'invalid_refresh_token', '刷新令牌已过期');

    const device = db.prepare('SELECT revoked FROM devices WHERE id = ?').get(row.device_id) as
      | { revoked: number } | undefined;
    if (!device || device.revoked) throw new HttpError(401, 'device_revoked', '设备已被吊销');

    db.prepare('UPDATE refresh_tokens SET consumed_at = ? WHERE token_hash = ?').run(Date.now(), hash);
    return issueTokens(row.account_id, row.device_id);
  });

  app.post('/v1/session/logout', async (req) => {
    const auth = requireAuth(req);
    db.prepare('DELETE FROM refresh_tokens WHERE device_id = ?').run(auth.deviceId);
    db.prepare('UPDATE devices SET revoked = 1 WHERE id = ?').run(auth.deviceId);
    return { ok: true };
  });

  app.get('/v1/vault', async (req) => {
    const auth = requireAuth(req);
    const acc = db.prepare('SELECT * FROM accounts WHERE id = ?').get(auth.accountId) as AccountRow;
    return { ...vaultPayload(acc), serverSeq: acc.seq };
  });

  app.post('/v1/vault/private-key-login/enable', async (req) => {
    const auth = requireAuth(req);
    const body = req.body as Record<string, unknown>;
    const currentAuthSecret = requireString(body.currentAuthSecret, 'currentAuthSecret', 128);
    const privateKeyAuthSecret = requireString(body.privateKeyAuthSecret, 'privateKeyAuthSecret', 128);
    if (!/^[0-9a-f]{64}$/.test(privateKeyAuthSecret)) {
      throw new HttpError(400, 'bad_request', 'privateKeyAuthSecret 格式错误');
    }
    const account = db.prepare('SELECT * FROM accounts WHERE id = ?').get(auth.accountId) as AccountRow;
    if (!(await verifyAuthSecret(currentAuthSecret, account.auth_hash))) {
      throw new HttpError(401, 'invalid_credentials', '当前主口令不正确');
    }
    db.prepare('UPDATE accounts SET recovery_auth_hash = ? WHERE id = ?')
      .run(await hashAuthSecret(privateKeyAuthSecret), auth.accountId);
    return { ok: true, privateKeyLoginEnabled: true };
  });

  /**
   * 改口令 / 换恢复码。只是重新包裹 DEK，联系人密文一条都不用重传。
   * 成功后吊销其它设备的刷新令牌，逼它们用新口令重新登录。
   */
  app.post('/v1/vault/rewrap', async (req) => {
    const auth = requireAuth(req);
    const body = req.body as Record<string, unknown>;
    const currentAuthSecret = requireString(body.currentAuthSecret, 'currentAuthSecret', 128);
    const newAuthSecret = requireString(body.newAuthSecret, 'newAuthSecret', 128);
    if (!/^[0-9a-f]{64}$/.test(newAuthSecret)) throw new HttpError(400, 'bad_request', 'newAuthSecret 格式错误');

    const acc = db.prepare('SELECT * FROM accounts WHERE id = ?').get(auth.accountId) as AccountRow;
    if (!(await verifyAuthSecret(currentAuthSecret, acc.auth_hash))) {
      throw new HttpError(401, 'invalid_credentials', '当前口令不正确');
    }

    const kdf = (body.kdf ?? {}) as Record<string, unknown>;
    const salt = b64(kdf.salt, 'kdf.salt', 64);
    if (salt.length < 16) throw new HttpError(400, 'bad_request', 'kdf.salt 至少 16 字节');
    const memoryKiB = requireInt(kdf.memoryKiB, 'kdf.memoryKiB', 32768, 1048576);
    const iterations = requireInt(kdf.iterations, 'kdf.iterations', 2, 20);
    const parallelism = requireInt(kdf.parallelism, 'kdf.parallelism', 1, 16);
    const dekWrapPassword = b64(body.dekWrapPassword, 'dekWrapPassword', 512);
    const dekWrapRecovery = b64(body.dekWrapRecovery, 'dekWrapRecovery', 512);

    const authHash = await hashAuthSecret(newAuthSecret);

    // 换口令时如果客户端也重新生成了恢复码，一并更新它的凭据哈希。
    // 不发就保持原样 —— 换口令不该强制作废恢复码。
    const newRecoveryAuthSecret =
      typeof body.recoveryAuthSecret === 'string' && body.recoveryAuthSecret.length > 0
        ? requireString(body.recoveryAuthSecret, 'recoveryAuthSecret', 128)
        : null;
    const newRecoveryAuthHash = newRecoveryAuthSecret
      ? await hashAuthSecret(newRecoveryAuthSecret)
      : null;

    db.transaction(() => {
      if (newRecoveryAuthHash) {
        db.prepare('UPDATE accounts SET recovery_auth_hash = ? WHERE id = ?')
          .run(newRecoveryAuthHash, auth.accountId);
      }
      db.prepare(
        `UPDATE accounts SET kdf_salt = ?, kdf_mem = ?, kdf_time = ?, kdf_par = ?, auth_hash = ?,
                             dek_wrap_password = ?, dek_wrap_recovery = ?, vault_version = vault_version + 1
         WHERE id = ?`
      ).run(salt, memoryKiB, iterations, parallelism, authHash, dekWrapPassword, dekWrapRecovery, auth.accountId);
      db.prepare('DELETE FROM refresh_tokens WHERE account_id = ? AND device_id != ?')
        .run(auth.accountId, auth.deviceId);
    })();
    return { ok: true, vaultVersion: acc.vault_version + 1 };
  });

  app.get('/v1/devices', async (req) => {
    const auth = requireAuth(req);
    const rows = db
      .prepare('SELECT id, name, created_at, last_seen_at, revoked FROM devices WHERE account_id = ? ORDER BY created_at')
      .all(auth.accountId) as Array<Record<string, unknown>>;
    return {
      devices: rows.map((r) => ({
        id: r.id, name: r.name, createdAt: r.created_at,
        lastSeenAt: r.last_seen_at, revoked: r.revoked === 1, current: r.id === auth.deviceId,
      })),
    };
  });

  app.delete('/v1/devices/:id', async (req) => {
    const auth = requireAuth(req);
    const id = (req.params as { id: string }).id;
    const row = db.prepare('SELECT 1 FROM devices WHERE id = ? AND account_id = ?').get(id, auth.accountId);
    if (!row) throw new HttpError(404, 'not_found', '设备不存在');
    db.transaction(() => {
      db.prepare('UPDATE devices SET revoked = 1 WHERE id = ?').run(id);
      db.prepare('DELETE FROM refresh_tokens WHERE device_id = ?').run(id);
    })();
    return { ok: true };
  });

  /** 彻底销毁账号和全部密文。需要再次提供口令派生的 authSecret。 */
  app.post('/v1/account/destroy', async (req) => {
    const auth = requireAuth(req);
    const body = req.body as Record<string, unknown>;
    const authSecret = requireString(body.authSecret, 'authSecret', 128);
    const acc = db.prepare('SELECT * FROM accounts WHERE id = ?').get(auth.accountId) as AccountRow;
    if (!(await verifyAuthSecret(authSecret, acc.auth_hash))) {
      throw new HttpError(401, 'invalid_credentials', '口令不正确');
    }
    db.prepare('DELETE FROM accounts WHERE id = ?').run(auth.accountId);
    return { ok: true };
  });
}
