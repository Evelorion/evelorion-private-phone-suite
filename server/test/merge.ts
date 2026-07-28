/**
 * 三方合并的参考实现 —— Kotlin 端 Merger.kt 必须与此等价。
 *
 * 为什么是三方而不是按时间戳裁决：
 *   手机时钟不可靠（时区、手动改时间、NTP 抖动），而且"谁的时间戳大谁赢"
 *   会整条覆盖，另一台设备刚加的号码就没了。三方合并用"上次同步成功时的
 *   那份快照"当共同祖先，能准确区分"这一侧改过"和"这一侧只是没变"。
 *
 * base   = 本机上次同步成功时的快照（存在本地 sync_records.base_payload）
 * local  = 本机当前状态
 * remote = 服务端退回来的版本
 *
 * 规则（和 git 的三方合并同构）：
 *   某一侧相对 base 没变  → 采用另一侧
 *   两侧都变且变得一样    → 采用该值
 *   两侧都变且不一样      → 走确定性裁决，并把这条记为"需要用户确认"
 *
 * 列表元素的 id 是由内容确定性推导出来的（见 client.ts itemId），
 * 所以两台设备对同一个号码会算出同一个 id，不需要额外存 uuid。
 */

export type ListItem = { id: string; [k: string]: unknown };

export type Contact = {
  v: number;
  prefix?: string; first?: string; middle?: string; surname?: string; suffix?: string;
  nickname?: string; company?: string; jobTitle?: string; notes?: string;
  starred?: number; ringtone?: string | null; photo?: string | null;
  phones?: ListItem[]; emails?: ListItem[]; addresses?: ListItem[];
  events?: ListItem[]; websites?: ListItem[]; ims?: ListItem[]; groups?: ListItem[];
};

const SCALARS = [
  'prefix', 'first', 'middle', 'surname', 'suffix', 'nickname',
  'company', 'jobTitle', 'notes', 'starred', 'ringtone', 'photo',
] as const;

const LISTS = ['phones', 'emails', 'addresses', 'events', 'websites', 'ims', 'groups'] as const;

export type MergeOutcome = { merged: Contact; conflicts: string[] };

function eq(a: unknown, b: unknown): boolean {
  return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
}

/**
 * 两侧都改过同一个标量时的确定性裁决。两台设备各自算都必须得到同一个结果，
 * 否则会互相覆盖来回推。先偏向非空值（用户填了东西通常比清空更有意图），
 * 再按 JSON 字典序取大的。
 */
function resolveScalar(local: unknown, remote: unknown): unknown {
  const emptyL = local === undefined || local === null || local === '';
  const emptyR = remote === undefined || remote === null || remote === '';
  if (emptyL && !emptyR) return remote;
  if (emptyR && !emptyL) return local;
  return JSON.stringify(local) >= JSON.stringify(remote) ? local : remote;
}

function indexById(items: ListItem[] | undefined): Map<string, ListItem> {
  const map = new Map<string, ListItem>();
  for (const item of items ?? []) map.set(item.id, item);
  return map;
}

export function threeWayMerge(base: Contact | null, local: Contact, remote: Contact): MergeOutcome {
  const b: Contact = base ?? { v: local.v };
  const conflicts: string[] = [];
  const out: Contact = { v: Math.max(local.v, remote.v) };

  for (const field of SCALARS) {
    const vb = b[field];
    const vl = local[field];
    const vr = remote[field];
    let winner: unknown;
    if (eq(vl, vr)) winner = vl;
    else if (eq(vl, vb)) winner = vr;          // 本机没动，采用远端
    else if (eq(vr, vb)) winner = vl;          // 远端没动，采用本机
    else {
      winner = resolveScalar(vl, vr);
      conflicts.push(field);
    }
    if (winner !== undefined && winner !== null && winner !== '') {
      (out as Record<string, unknown>)[field] = winner;
    } else if (winner === null) {
      (out as Record<string, unknown>)[field] = null;
    }
  }

  for (const list of LISTS) {
    const mb = indexById(b[list]);
    const ml = indexById(local[list]);
    const mr = indexById(remote[list]);
    const ids = new Set([...mb.keys(), ...ml.keys(), ...mr.keys()]);
    const kept: ListItem[] = [];

    for (const id of ids) {
      const inB = mb.has(id), inL = ml.has(id), inR = mr.has(id);
      // 存在性：本机相对 base 有变化就听本机的，否则听远端的
      const present = inL !== inB ? inL : inR;
      if (!present) continue;

      const ib = mb.get(id), il = ml.get(id), ir = mr.get(id);
      let chosen: ListItem;
      if (il && ir) {
        if (eq(il, ir)) chosen = il;
        else if (eq(il, ib)) chosen = ir;
        else if (eq(ir, ib)) chosen = il;
        else {
          chosen = mergeItem(ib, il, ir, `${list}.${id}`, conflicts);
        }
      } else {
        chosen = (il ?? ir)!;
      }
      kept.push(chosen);
    }

    kept.sort((x, y) => (x.id < y.id ? -1 : x.id > y.id ? 1 : 0));
    if (kept.length > 0) out[list] = kept;
  }

  return { merged: out, conflicts };
}

/** 同一个条目（比如同一个号码）的 label/type 两边都改了，逐字段再做一次三方。 */
function mergeItem(
  ib: ListItem | undefined, il: ListItem, ir: ListItem, path: string, conflicts: string[]
): ListItem {
  const out: ListItem = { id: il.id };
  const keys = new Set([...Object.keys(il), ...Object.keys(ir), ...Object.keys(ib ?? {})]);
  for (const k of keys) {
    if (k === 'id') continue;
    const vb = ib?.[k], vl = il[k], vr = ir[k];
    if (eq(vl, vr)) out[k] = vl;
    else if (eq(vl, vb)) out[k] = vr;
    else if (eq(vr, vb)) out[k] = vl;
    else {
      out[k] = resolveScalar(vl, vr);
      conflicts.push(`${path}.${k}`);
    }
  }
  return out;
}

/** 合并结果和某一侧完全一致时可以跳过回推，省一次往返。 */
export function sameContact(a: Contact, b: Contact): boolean {
  return JSON.stringify(sortDeep(a)) === JSON.stringify(sortDeep(b));
}

function sortDeep(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortDeep);
  if (value && typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    const out: Record<string, unknown> = {};
    for (const k of Object.keys(obj).sort()) out[k] = sortDeep(obj[k]);
    return out;
  }
  return value;
}
