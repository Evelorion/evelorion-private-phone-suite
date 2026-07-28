import type { FastifyInstance } from 'fastify';
import { db } from '../db.ts';
import { config } from '../config.ts';
import { HttpError, requireAuth, b64 } from '../lib/http.ts';

/**
 * 头像走独立的内容寻址存储。hash 由客户端用 DEK 派生的 HMAC 密钥算出来
 * （不是明文 SHA256），所以服务端拿两个账号的同一张图片也看不出它们相同。
 */
export function registerBlobRoutes(app: FastifyInstance): void {
  app.put('/v1/blobs/:hash', async (req) => {
    const auth = requireAuth(req);
    const hash = (req.params as { hash: string }).hash;
    if (!/^[0-9a-f]{64}$/.test(hash)) throw new HttpError(400, 'bad_request', 'hash 必须是 64 位十六进制');

    const body = req.body as Record<string, unknown>;
    const nonce = b64(body.nonce, 'nonce', 32);
    if (nonce.length !== 12) throw new HttpError(400, 'bad_request', 'nonce 必须是 12 字节');
    const ciphertext = b64(body.ciphertext, 'ciphertext', config.maxBlobBytes);

    db.prepare(
      `INSERT INTO blobs (account_id, hash, nonce, ciphertext, size, created_at) VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(account_id, hash) DO NOTHING`
    ).run(auth.accountId, hash, nonce, ciphertext, ciphertext.length, Date.now());
    return { ok: true, hash };
  });

  app.get('/v1/blobs/:hash', async (req) => {
    const auth = requireAuth(req);
    const hash = (req.params as { hash: string }).hash;
    const row = db
      .prepare('SELECT nonce, ciphertext FROM blobs WHERE account_id = ? AND hash = ?')
      .get(auth.accountId, hash) as { nonce: Buffer; ciphertext: Buffer } | undefined;
    if (!row) throw new HttpError(404, 'not_found', 'blob 不存在');
    return { hash, nonce: row.nonce.toString('base64'), ciphertext: row.ciphertext.toString('base64') };
  });

  app.delete('/v1/blobs/:hash', async (req) => {
    const auth = requireAuth(req);
    const hash = (req.params as { hash: string }).hash;
    db.prepare('DELETE FROM blobs WHERE account_id = ? AND hash = ?').run(auth.accountId, hash);
    return { ok: true };
  });

  /** 客户端用它来算出哪些头像还没上传，避免重复传大文件。 */
  app.post('/v1/blobs/missing', async (req) => {
    const auth = requireAuth(req);
    const hashes = (req.body as Record<string, unknown>).hashes;
    if (!Array.isArray(hashes) || hashes.length > 500) {
      throw new HttpError(400, 'bad_request', 'hashes 必须是数组且不超过 500 项');
    }
    const have = new Set(
      (db.prepare('SELECT hash FROM blobs WHERE account_id = ?').all(auth.accountId) as Array<{ hash: string }>)
        .map((r) => r.hash)
    );
    return { missing: hashes.filter((h) => typeof h === 'string' && !have.has(h)) };
  });
}
