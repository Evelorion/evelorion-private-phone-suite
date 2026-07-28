import { db } from '../db.ts';

/**
 * 针对登录这类昂贵/敏感端点的滑动窗口计数。用 SQLite 存，重启不丢，
 * 也不需要额外的 Redis。
 */
export function tooManyAttempts(key: string, limit: number, windowMs: number): boolean {
  const now = Date.now();
  db.prepare('DELETE FROM auth_attempts WHERE key = ? AND at < ?').run(key, now - windowMs);
  const row = db.prepare('SELECT COUNT(*) AS n FROM auth_attempts WHERE key = ?').get(key) as { n: number };
  return row.n >= limit;
}

export function recordAttempt(key: string): void {
  db.prepare('INSERT INTO auth_attempts (key, at) VALUES (?, ?)').run(key, Date.now());
}

export function clearAttempts(key: string): void {
  db.prepare('DELETE FROM auth_attempts WHERE key = ?').run(key);
}
