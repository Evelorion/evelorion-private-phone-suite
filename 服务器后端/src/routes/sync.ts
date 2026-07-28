import type { FastifyInstance } from 'fastify';
import { db, nextSeq, COLLECTIONS, type RecordRow } from '../db.ts';
import { config } from '../config.ts';
import { HttpError, requireAuth, requireString, b64 } from '../lib/http.ts';

const MAX_PAGE = 500;
const MAX_PUSH_ITEMS = 200;

/**
 * collection 决定这批数据属于通讯录还是通话记录。
 * 两者用的是不同的密钥，混在一起拉的话双方都会拿到自己解不开的密文。
 */
function parseCollection(value: unknown): string {
  const name = value === undefined || value === '' ? 'contacts' : value;
  if (typeof name !== 'string' || !(COLLECTIONS as readonly string[]).includes(name)) {
    throw new HttpError(400, 'bad_collection', `collection 只能是 ${COLLECTIONS.join(' / ')}`);
  }
  return name;
}

function serializeRecord(r: RecordRow) {
  return {
    collection: r.collection,
    uuid: r.uuid,
    seq: r.seq,
    rev: r.rev,
    deleted: r.deleted === 1,
    schemaVer: r.schema_ver,
    nonce: r.nonce ? r.nonce.toString('base64') : null,
    ciphertext: r.ciphertext ? r.ciphertext.toString('base64') : null,
    updatedAt: r.updated_at,
    deviceId: r.device_id,
  };
}

export function registerSyncRoutes(app: FastifyInstance): void {
  /**
   * 拉取。since 是客户端上次成功处理到的账号级序列号。
   * 服务端只按 seq 单调返回，客户端自己判断是否需要覆盖本地。
   */
  app.get('/v1/sync/changes', async (req) => {
    const auth = requireAuth(req);
    const q = req.query as Record<string, unknown>;
    const since = Number(q.since ?? 0);
    if (!Number.isInteger(since) || since < 0) throw new HttpError(400, 'bad_request', 'since 必须是非负整数');
    const limit = Math.min(Number(q.limit ?? MAX_PAGE) || MAX_PAGE, MAX_PAGE);
    const collection = parseCollection(q.collection);

    const rows = db
      .prepare(
        'SELECT * FROM records WHERE account_id = ? AND collection = ? AND seq > ? ORDER BY seq ASC LIMIT ?'
      )
      .all(auth.accountId, collection, since, limit) as RecordRow[];

    const head = db.prepare('SELECT seq FROM accounts WHERE id = ?').get(auth.accountId) as { seq: number };
    // 这个 collection 里最大的 seq。用账号级 head 判断 hasMore 会误判 ——
    // 另一个 collection 的写入也会推高 head，导致客户端以为还有数据没拉完。
    const collectionHead = (db
      .prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM records WHERE account_id = ? AND collection = ?')
      .get(auth.accountId, collection) as { seq: number }).seq;
    const nextSince = rows.length > 0 ? rows[rows.length - 1]!.seq : since;

    return {
      changes: rows.map(serializeRecord),
      nextSince,
      hasMore: rows.length === limit && nextSince < collectionHead,
      collection,
      collectionSeq: collectionHead,
      serverSeq: head.seq,
      serverTime: Date.now(),
    };
  });

  /**
   * 推送。每条独立判定：
   *   baseRev === 服务端当前 rev  → 接受，rev+1
   *   baseRev === 0 且服务端没有该 uuid → 接受，rev=1（新建）
   *   其它                          → conflict，把服务端版本原样退回，由客户端解密后合并再推
   * 整批在一个事务里，要么全部落库要么全不落，避免部分成功导致 seq 空洞。
   */
  app.post('/v1/sync/push', async (req) => {
    const auth = requireAuth(req);
    const body = req.body as Record<string, unknown>;
    const collection = parseCollection(body.collection);
    const changes = body.changes;
    if (!Array.isArray(changes)) throw new HttpError(400, 'bad_request', 'changes 必须是数组');
    if (changes.length > MAX_PUSH_ITEMS) {
      throw new HttpError(400, 'too_many_items', `一次最多推送 ${MAX_PUSH_ITEMS} 条`);
    }

    // 先做全部校验，避免事务中途抛异常
    type Parsed = {
      uuid: string; baseRev: number; deleted: boolean; schemaVer: number;
      nonce: Buffer | null; ciphertext: Buffer | null;
    };
    const parsed: Parsed[] = changes.map((raw, i) => {
      const c = raw as Record<string, unknown>;
      const uuid = requireString(c.uuid, `changes[${i}].uuid`, 64);
      if (!/^[0-9a-fA-F-]{36}$/.test(uuid)) {
        throw new HttpError(400, 'bad_request', `changes[${i}].uuid 必须是 UUID`);
      }
      const baseRev = c.baseRev;
      if (typeof baseRev !== 'number' || !Number.isInteger(baseRev) || baseRev < 0) {
        throw new HttpError(400, 'bad_request', `changes[${i}].baseRev 必须是非负整数`);
      }
      const deleted = c.deleted === true;
      const schemaVer = typeof c.schemaVer === 'number' ? c.schemaVer : 1;
      if (deleted) return { uuid, baseRev, deleted, schemaVer, nonce: null, ciphertext: null };
      const nonce = b64(c.nonce, `changes[${i}].nonce`, 32);
      if (nonce.length !== 12) throw new HttpError(400, 'bad_request', `changes[${i}].nonce 必须是 12 字节`);
      const ciphertext = b64(c.ciphertext, `changes[${i}].ciphertext`, config.maxRecordBytes);
      if (ciphertext.length < 17) throw new HttpError(400, 'bad_request', `changes[${i}].ciphertext 过短`);
      return { uuid, baseRev, deleted, schemaVer, nonce, ciphertext };
    });

    const seen = new Set<string>();
    for (const p of parsed) {
      if (seen.has(p.uuid)) throw new HttpError(400, 'duplicate_uuid', `同一批里出现重复 uuid ${p.uuid}`);
      seen.add(p.uuid);
    }

    const results = db.transaction(() => {
      const out: Array<Record<string, unknown>> = [];
      const now = Date.now();
      for (const p of parsed) {
        const existing = db
          .prepare('SELECT * FROM records WHERE account_id = ? AND collection = ? AND uuid = ?')
          .get(auth.accountId, collection, p.uuid) as RecordRow | undefined;
        const currentRev = existing?.rev ?? 0;

        if (p.baseRev !== currentRev) {
          out.push({
            uuid: p.uuid,
            status: 'conflict',
            server: existing ? serializeRecord(existing) : null,
          });
          continue;
        }

        // 覆盖之前先归档。
        //
        // 删除在这套协议里是「用一条空墓碑覆盖掉原行」，密文当场就没了。
        // 客户端一个误判就能让所有设备上的数据永久消失 —— 已经发生过一次。
        // 服务器读不懂内容，但可以替用户留一份，给他一个后悔的机会。
        if (p.deleted && existing && !existing.deleted && existing.ciphertext) {
          db.prepare(
            `INSERT INTO deleted_records
               (account_id, collection, uuid, rev, schema_ver, nonce, ciphertext, size, deleted_at, device_id)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
          ).run(
            auth.accountId, collection, existing.uuid, existing.rev, existing.schema_ver,
            existing.nonce, existing.ciphertext, existing.size, now, auth.deviceId
          );
        }

        // 已经是墓碑的记录不允许再被复活成同一 rev，避免删除被回滚
        const seq = nextSeq(auth.accountId);
        const rev = currentRev + 1;
        db.prepare(
          `INSERT INTO records (account_id, collection, uuid, seq, rev, deleted, schema_ver, nonce, ciphertext, size, updated_at, device_id)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(account_id, collection, uuid) DO UPDATE SET
             seq = excluded.seq, rev = excluded.rev, deleted = excluded.deleted,
             schema_ver = excluded.schema_ver, nonce = excluded.nonce, ciphertext = excluded.ciphertext,
             size = excluded.size, updated_at = excluded.updated_at, device_id = excluded.device_id`
        ).run(
          auth.accountId, collection, p.uuid, seq, rev, p.deleted ? 1 : 0, p.schemaVer,
          p.nonce, p.ciphertext, p.ciphertext?.length ?? 0, now, auth.deviceId
        );
        out.push({ uuid: p.uuid, status: 'applied', rev, seq });
      }
      return out;
    })();

    const head = db.prepare('SELECT seq FROM accounts WHERE id = ?').get(auth.accountId) as { seq: number };
    return { results, collection, serverSeq: head.seq, serverTime: Date.now() };
  });

  app.get('/v1/sync/status', async (req) => {
    const auth = requireAuth(req);
    const head = db.prepare('SELECT seq FROM accounts WHERE id = ?').get(auth.accountId) as { seq: number };
    const rows = db
      .prepare(
        `SELECT collection, COUNT(*) AS total, SUM(deleted) AS tombstones, SUM(size) AS bytes
         FROM records WHERE account_id = ? GROUP BY collection`
      )
      .all(auth.accountId) as Array<{ collection: string; total: number; tombstones: number | null; bytes: number | null }>;

    const byCollection: Record<string, unknown> = {};
    for (const name of COLLECTIONS) {
      const row = rows.find((r) => r.collection === name);
      byCollection[name] = {
        records: (row?.total ?? 0) - (row?.tombstones ?? 0),
        tombstones: row?.tombstones ?? 0,
        cipherBytes: row?.bytes ?? 0,
      };
    }
    return {
      serverSeq: head.seq,
      collections: byCollection,
      serverTime: Date.now(),
      trash: (db
        .prepare('SELECT COUNT(*) AS n FROM deleted_records WHERE account_id = ?')
        .get(auth.accountId) as { n: number }).n,
    };
  });

  // ------------------------------------------------------------------ 回收站

  /**
   * 列出被删掉的记录。
   *
   * 返回的是密文 —— 服务器读不懂，客户端拿到后自己解密才能显示是谁。
   * 这是零知识必然的代价：回收站没法在服务端渲染成「张三、李四」。
   */
  app.get('/v1/sync/trash', async (req) => {
    const auth = requireAuth(req);
    const collection = parseCollection((req.query as Record<string, unknown>)?.collection);
    const rows = db
      .prepare(
        `SELECT id, uuid, rev, schema_ver, nonce, ciphertext, size, deleted_at
         FROM deleted_records WHERE account_id = ? AND collection = ?
         ORDER BY deleted_at DESC LIMIT 500`
      )
      .all(auth.accountId, collection) as Array<Record<string, never>>;
    return {
      collection,
      items: rows.map((r: Record<string, unknown>) => ({
        id: r.id,
        uuid: r.uuid,
        rev: r.rev,
        schemaVer: r.schema_ver,
        nonce: (r.nonce as Buffer)?.toString('base64') ?? null,
        ciphertext: (r.ciphertext as Buffer)?.toString('base64') ?? null,
        size: r.size,
        deletedAt: r.deleted_at,
      })),
    };
  });

  /**
   * 把回收站里的记录放回去。
   *
   * ── 为什么必须还原成**原来的 rev**，而不是 currentRev + 1 ──
   *
   * 密文的 AAD 绑定了 uuid‖rev‖schemaVersion。换个 rev 写回去，
   * 客户端解密时 AAD 对不上，AEAD 会直接判定为伪造 —— 数据还在，
   * 但谁也打不开。所以这里让 rev 退回删除前的值，
   * 而不是像正常写入那样往前推。
   *
   * seq 仍然取新的，客户端才会在下一次增量拉取里看到它。
   */
  app.post('/v1/sync/trash/restore', async (req) => {
    const auth = requireAuth(req);
    const body = (req.body ?? {}) as Record<string, unknown>;
    const ids = Array.isArray(body.ids) ? body.ids : [];
    if (ids.length === 0) throw new HttpError(400, 'bad_request', '要还原哪些？ids 不能为空');
    if (ids.length > MAX_PUSH_ITEMS) throw new HttpError(400, 'too_many', `一次最多还原 ${MAX_PUSH_ITEMS} 条`);

    const restored = db.transaction(() => {
      const now = Date.now();
      const done: string[] = [];
      for (const rawId of ids) {
        const id = typeof rawId === 'number' ? rawId : Number(rawId);
        if (!Number.isInteger(id)) continue;
        const row = db
          .prepare('SELECT * FROM deleted_records WHERE id = ? AND account_id = ?')
          .get(id, auth.accountId) as Record<string, unknown> | undefined;
        if (!row) continue;

        const seq = nextSeq(auth.accountId);
        db.prepare(
          `INSERT INTO records (account_id, collection, uuid, seq, rev, deleted, schema_ver, nonce, ciphertext, size, updated_at, device_id)
           VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(account_id, collection, uuid) DO UPDATE SET
             seq = excluded.seq, rev = excluded.rev, deleted = 0,
             schema_ver = excluded.schema_ver, nonce = excluded.nonce, ciphertext = excluded.ciphertext,
             size = excluded.size, updated_at = excluded.updated_at, device_id = excluded.device_id`
        ).run(
          auth.accountId, row.collection, row.uuid, seq, row.rev, row.schema_ver,
          row.nonce, row.ciphertext, row.size, now, auth.deviceId
        );
        db.prepare('DELETE FROM deleted_records WHERE id = ?').run(id);
        done.push(row.uuid as string);
      }
      return done;
    })();

    const head = db.prepare('SELECT seq FROM accounts WHERE id = ?').get(auth.accountId) as { seq: number };
    return { restored: restored.length, uuids: restored, serverSeq: head.seq };
  });

  /** 彻底清空回收站。想让删除立刻变成物理删除的用户调它。 */
  app.post('/v1/sync/trash/purge', async (req) => {
    const auth = requireAuth(req);
    const info = db.prepare('DELETE FROM deleted_records WHERE account_id = ?').run(auth.accountId);
    return { purged: info.changes };
  });
}
