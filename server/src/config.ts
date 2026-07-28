import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

function loadDotEnv(): void {
  const p = resolve(process.cwd(), '.env');
  if (!existsSync(p)) return;
  for (const rawLine of readFileSync(p, 'utf8').split('\n')) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq < 0) continue;
    const key = line.slice(0, eq).trim();
    const value = line.slice(eq + 1).trim();
    if (process.env[key] === undefined) process.env[key] = value;
  }
}
loadDotEnv();

function str(name: string, fallback?: string): string {
  const v = process.env[name];
  if (v === undefined || v === '') {
    if (fallback !== undefined) return fallback;
    throw new Error(`缺少必需的环境变量 ${name}`);
  }
  return v;
}
function num(name: string, fallback: number): number {
  const v = process.env[name];
  if (v === undefined || v === '') return fallback;
  const n = Number(v);
  if (!Number.isFinite(n)) throw new Error(`环境变量 ${name} 不是合法数字`);
  return n;
}
function bool(name: string, fallback: boolean): boolean {
  const v = process.env[name];
  if (v === undefined || v === '') return fallback;
  return v === 'true' || v === '1';
}

const secretHex = str('SERVER_SECRET');
if (!/^[0-9a-fA-F]{64}$/.test(secretHex)) {
  throw new Error('SERVER_SECRET 必须是 64 位十六进制字符串，用 `openssl rand -hex 32` 生成');
}

export const config = {
  host: str('HOST', '0.0.0.0'),
  port: num('PORT', 8443),
  dbPath: str('DB_PATH', './data/sync.db'),
  serverSecret: Buffer.from(secretHex, 'hex'),
  registrationToken: str('REGISTRATION_TOKEN', ''),
  accessTtlSec: num('ACCESS_TTL_SEC', 900),
  refreshTtlDays: num('REFRESH_TTL_DAYS', 60),
  maxRecordBytes: num('MAX_RECORD_BYTES', 64 * 1024),
  maxBlobBytes: num('MAX_BLOB_BYTES', 4 * 1024 * 1024),
  tombstoneTtlDays: num('TOMBSTONE_TTL_DAYS', 90),
  trustProxy: bool('TRUST_PROXY', false),
  /**
   * 对外的完整来源，比如 https://contacts.example.com:8443
   *
   * WebAuthn 必须知道这个 —— 浏览器签名时会把 origin 一起签进去，
   * 服务端拿它比对。配错的话浏览器直接拒绝，报的是 NotAllowedError，
   * 完全看不出是配置问题。
   */
  publicOrigin: str('PUBLIC_ORIGIN', 'https://localhost:8443'),
} as const;
