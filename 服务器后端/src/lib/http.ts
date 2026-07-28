import type { FastifyRequest, FastifyReply } from 'fastify';
import { db } from '../db.ts';
import { verifyAccessToken } from './crypto.ts';

export class HttpError extends Error {
  statusCode: number;
  code: string;
  constructor(statusCode: number, code: string, message?: string) {
    super(message ?? code);
    this.statusCode = statusCode;
    this.code = code;
  }
}

export type AuthContext = { accountId: string; deviceId: string };

/** 从 Authorization: Bearer <token> 解出账号/设备，并确认设备没被吊销。 */
export function requireAuth(req: FastifyRequest): AuthContext {
  const header = req.headers.authorization;
  if (!header || !header.startsWith('Bearer ')) {
    throw new HttpError(401, 'unauthorized', '缺少访问令牌');
  }
  const payload = verifyAccessToken(header.slice(7).trim());
  if (!payload) throw new HttpError(401, 'unauthorized', '访问令牌无效或已过期');

  const device = db
    .prepare('SELECT revoked FROM devices WHERE id = ? AND account_id = ?')
    .get(payload.d, payload.a) as { revoked: number } | undefined;
  if (!device) throw new HttpError(401, 'unauthorized', '设备不存在');
  if (device.revoked) throw new HttpError(401, 'device_revoked', '设备已被吊销');

  const account = db.prepare('SELECT disabled FROM accounts WHERE id = ?').get(payload.a) as
    | { disabled: number }
    | undefined;
  if (!account || account.disabled) throw new HttpError(401, 'unauthorized', '账号不可用');

  db.prepare('UPDATE devices SET last_seen_at = ? WHERE id = ?').run(Date.now(), payload.d);
  return { accountId: payload.a, deviceId: payload.d };
}

export function clientIp(req: FastifyRequest, trustProxy: boolean): string {
  if (trustProxy) {
    const fwd = req.headers['x-forwarded-for'];
    if (typeof fwd === 'string' && fwd.length > 0) return fwd.split(',')[0]!.trim();
  }
  return req.ip;
}

export function sendError(reply: FastifyReply, err: unknown): FastifyReply {
  if (err instanceof HttpError) {
    return reply.code(err.statusCode).send({ error: err.code, message: err.message });
  }
  return reply.code(500).send({ error: 'internal', message: '服务器内部错误' });
}

/** 严格的 base64 解码：拒绝畸形输入而不是静默截断。 */
export function b64(value: unknown, field: string, maxBytes: number): Buffer {
  if (typeof value !== 'string') throw new HttpError(400, 'bad_request', `${field} 必须是 base64 字符串`);
  const buf = Buffer.from(value, 'base64');
  if (buf.toString('base64').replace(/=+$/, '') !== value.replace(/=+$/, '')) {
    throw new HttpError(400, 'bad_request', `${field} 不是合法 base64`);
  }
  if (buf.length > maxBytes) throw new HttpError(413, 'too_large', `${field} 超过 ${maxBytes} 字节上限`);
  return buf;
}

export function requireString(value: unknown, field: string, maxLen = 256): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new HttpError(400, 'bad_request', `${field} 必填`);
  }
  if (value.length > maxLen) throw new HttpError(400, 'bad_request', `${field} 过长`);
  return value;
}

export function requireInt(value: unknown, field: string, min: number, max: number): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) {
    throw new HttpError(400, 'bad_request', `${field} 必须是 ${min}~${max} 之间的整数`);
  }
  return value;
}
