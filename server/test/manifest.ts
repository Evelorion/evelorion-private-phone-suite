/**
 * 同步清单的参考实现 —— Kotlin 端 SyncManifest.kt 必须与此逐字节一致。
 *
 * 编码：魔数(4) ‖ 版本(1) ‖ 条目数(4, 大端) ‖ [uuid 原始 16 字节 ‖ rev 4 字节] × N
 */
export const MANIFEST_UUID = '00000000-0000-4000-8000-000000000001';
const MAGIC = 0x4653594d; // "FSYM"
const VERSION = 1;
const ENTRY_BYTES = 20;
export const MAX_ENTRIES = 3200;

export function encodeManifest(entries: Map<string, number>): string {
  if (entries.size > MAX_ENTRIES) throw new Error(`条目数 ${entries.size} 超过上限 ${MAX_ENTRIES}`);
  const buf = Buffer.alloc(9 + entries.size * ENTRY_BYTES);
  buf.writeUInt32BE(MAGIC, 0);
  buf.writeUInt8(VERSION, 4);
  buf.writeUInt32BE(entries.size, 5);

  let offset = 9;
  // 按 uuid 排序，保证同一份内容永远编码成同样的字节
  for (const uuid of [...entries.keys()].sort()) {
    Buffer.from(uuid.replace(/-/g, ''), 'hex').copy(buf, offset);
    buf.writeUInt32BE(entries.get(uuid)!, offset + 16);
    offset += ENTRY_BYTES;
  }
  return buf.toString('base64');
}

export function decodeManifest(payload: string): Map<string, number> {
  const buf = Buffer.from(payload, 'base64');
  if (buf.length < 9) throw new Error('同步清单过短');
  if (buf.readUInt32BE(0) !== MAGIC) throw new Error('同步清单魔数不对');
  if (buf.readUInt8(4) !== VERSION) throw new Error('同步清单版本不认识');

  const count = buf.readUInt32BE(5);
  if (9 + count * ENTRY_BYTES !== buf.length) throw new Error('同步清单长度和条目数对不上');

  const out = new Map<string, number>();
  let offset = 9;
  for (let i = 0; i < count; i++) {
    const hex = buf.subarray(offset, offset + 16).toString('hex');
    const uuid = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
    out.set(uuid, buf.readUInt32BE(offset + 16));
    offset += ENTRY_BYTES;
  }
  return out;
}

export type Issue =
  | { kind: 'missing'; uuid: string; expectedRev: number }
  | { kind: 'rollback'; uuid: string; expectedRev: number; actualRev: number }
  | { kind: 'manifestRollback'; expectedRev: number; actualRev: number }
  | { kind: 'manifestMissing'; expectedRev: number };

export function verifyManifest(
  manifest: Map<string, number>,
  manifestRev: number,
  lastKnownRev: number,
  present: Map<string, number>,
): Issue[] {
  const issues: Issue[] = [];
  if (manifestRev < lastKnownRev) {
    issues.push({ kind: 'manifestRollback', expectedRev: lastKnownRev, actualRev: manifestRev });
  }
  for (const [uuid, expectedRev] of manifest) {
    const actual = present.get(uuid);
    if (actual === undefined) issues.push({ kind: 'missing', uuid, expectedRev });
    else if (actual < expectedRev) issues.push({ kind: 'rollback', uuid, expectedRev, actualRev: actual });
  }
  return issues;
}
