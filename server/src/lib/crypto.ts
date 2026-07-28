import { createHmac, randomBytes, timingSafeEqual, createHash } from 'node:crypto';
import { argon2id } from 'hash-wasm';
import { config } from '../config.ts';

/**
 * 服务端只做三件跟密码学有关的事：
 *   1. 对客户端送上来的 authSecret 再做一次 Argon2id 后存库（防止拖库直接拿到 authSecret）
 *   2. 签发/校验访问令牌
 *   3. 生成随机 ID 和盐
 * 它永远不参与联系人内容的加解密，也拿不到任何能解密的材料。
 */

export function randomHex(bytes: number): string {
  return randomBytes(bytes).toString('hex');
}

export function uuid(): string {
  return crypto.randomUUID();
}

export function sha256(data: Buffer | string): Buffer {
  return createHash('sha256').update(data).digest();
}

export function constantTimeEqual(a: Buffer, b: Buffer): boolean {
  if (a.length !== b.length) return false;
  return timingSafeEqual(a, b);
}

/** 服务端存储用的 Argon2id。参数比客户端轻，因为输入已经是 32 字节高熵密钥。 */
export async function hashAuthSecret(authSecretHex: string): Promise<string> {
  const salt = randomBytes(16);
  const hash = await argon2id({
    password: Buffer.from(authSecretHex, 'hex'),
    salt,
    parallelism: 1,
    iterations: 2,
    memorySize: 19456,
    hashLength: 32,
    outputType: 'hex',
  });
  return `argon2id$1$2$19456$${salt.toString('hex')}$${hash}`;
}

export async function verifyAuthSecret(authSecretHex: string, stored: string): Promise<boolean> {
  const parts = stored.split('$');
  if (parts.length !== 6 || parts[0] !== 'argon2id') return false;
  const parallelism = Number(parts[1]);
  const iterations = Number(parts[2]);
  const memorySize = Number(parts[3]);
  const salt = Buffer.from(parts[4]!, 'hex');
  const expected = Buffer.from(parts[5]!, 'hex');
  let input: Buffer;
  try {
    input = Buffer.from(authSecretHex, 'hex');
  } catch {
    return false;
  }
  const hash = await argon2id({
    password: input,
    salt,
    parallelism,
    iterations,
    memorySize,
    hashLength: expected.length,
    outputType: 'hex',
  });
  return constantTimeEqual(Buffer.from(hash, 'hex'), expected);
}

/**
 * 未注册用户名也要返回一个"看起来正常"的盐，否则攻击者可以用 /account/kdf
 * 枚举出服务器上有哪些账号。这里用服务端密钥对用户名做 HMAC 得到稳定的假盐。
 */
export function decoySalt(username: string): Buffer {
  return createHmac('sha256', config.serverSecret)
    .update('decoy-salt:')
    .update(username.toLowerCase())
    .digest()
    .subarray(0, 16);
}

// ---------- 访问令牌（HMAC 签名，无状态，短有效期） ----------

type TokenPayload = {
  a: string; // accountId
  d: string; // deviceId
  e: number; // 过期时间（秒）
};

function b64u(buf: Buffer): string {
  return buf.toString('base64url');
}

export function issueAccessToken(accountId: string, deviceId: string): { token: string; expiresAt: number } {
  const expiresAt = Math.floor(Date.now() / 1000) + config.accessTtlSec;
  const payload: TokenPayload = { a: accountId, d: deviceId, e: expiresAt };
  const body = b64u(Buffer.from(JSON.stringify(payload), 'utf8'));
  const sig = b64u(createHmac('sha256', config.serverSecret).update(body).digest());
  return { token: `${body}.${sig}`, expiresAt };
}

export function verifyAccessToken(token: string): TokenPayload | null {
  const dot = token.indexOf('.');
  if (dot < 0) return null;
  const body = token.slice(0, dot);
  const sig = token.slice(dot + 1);
  const expected = b64u(createHmac('sha256', config.serverSecret).update(body).digest());
  if (!constantTimeEqual(Buffer.from(sig), Buffer.from(expected))) return null;
  let payload: TokenPayload;
  try {
    payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
  } catch {
    return null;
  }
  if (typeof payload.a !== 'string' || typeof payload.d !== 'string' || typeof payload.e !== 'number') return null;
  if (payload.e < Math.floor(Date.now() / 1000)) return null;
  return payload;
}

// ---------- 刷新令牌（不可预测的随机串，库里只存哈希） ----------

export function newRefreshToken(): { token: string; hash: Buffer } {
  const token = randomBytes(32).toString('base64url');
  return { token, hash: sha256(token) };
}

export function refreshTokenHash(token: string): Buffer {
  return sha256(token);
}
