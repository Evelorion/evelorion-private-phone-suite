/**
 * 三方合并 —— 浏览器端。
 *
 * ⚠ 必须和 android/contacts/.../sync/model/Merger.kt 以及
 *   server/test/merge.ts 行为一致。
 *
 * 为什么不按时间戳裁决：手机和电脑的时钟都不可靠，而且「谁时间戳大谁赢」
 * 是整条覆盖 —— 另一台设备刚加的号码会直接消失。三方合并用「上次同步
 * 成功时的那份快照」当共同祖先，能准确区分「这一侧改过」和「这一侧只是没变」。
 *
 * 两个必须成立的性质，否则两端会互相推来推去停不下来：
 *   幂等  merge(x, x, x) === x
 *   对称  merge(base, l, r) === merge(base, r, l)
 */

const SCALARS = [
  'prefix', 'first', 'middle', 'surname', 'suffix', 'nickname',
  'company', 'jobTitle', 'notes', 'starred', 'ringtone', 'photo',
];

const LISTS = ['phones', 'emails', 'addresses', 'events', 'websites', 'ims', 'groups'];

const eq = (a, b) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);

/**
 * 两侧都改过同一个标量时的确定性裁决。
 * 两端各自算都必须得到同一个结果。先偏向非空值（用户填了东西通常比清空更有意图），
 * 再按 JSON 字典序取大的。
 */
function resolveScalar(local, remote) {
  const emptyL = local === undefined || local === null || local === '' || local === 0;
  const emptyR = remote === undefined || remote === null || remote === '' || remote === 0;
  if (emptyL && !emptyR) return remote;
  if (emptyR && !emptyL) return local;
  return JSON.stringify(local) >= JSON.stringify(remote) ? local : remote;
}

const indexById = (items) => {
  const m = new Map();
  for (const it of items ?? []) m.set(it.id, it);
  return m;
};

/**
 * @returns {{merged: object, conflicts: string[]}}
 *   conflicts 非空表示两侧都改过同一字段，UI 应当提示用户确认。
 */
export function threeWayMerge(base, local, remote) {
  const b = base ?? {};
  const conflicts = [];
  const out = { v: Math.max(local.v ?? 1, remote.v ?? 1) };

  for (const f of SCALARS) {
    const vb = b[f], vl = local[f], vr = remote[f];
    let winner;
    if (eq(vl, vr)) winner = vl;
    else if (eq(vl, vb)) winner = vr;          // 本机没动，采用远端
    else if (eq(vr, vb)) winner = vl;          // 远端没动，采用本机
    else { winner = resolveScalar(vl, vr); conflicts.push(f); }
    if (winner !== undefined) out[f] = winner;
  }

  for (const list of LISTS) {
    const mb = indexById(b[list]), ml = indexById(local[list]), mr = indexById(remote[list]);
    const ids = new Set([...mb.keys(), ...ml.keys(), ...mr.keys()]);
    const kept = [];

    for (const id of ids) {
      const inB = mb.has(id), inL = ml.has(id), inR = mr.has(id);
      // 存在性：本机相对 base 有变化就听本机的（增或删），否则听远端的。
      // 这条保证「本机删掉的条目不会被远端没动过的旧副本复活」。
      const present = inL !== inB ? inL : inR;
      if (!present) continue;

      const ib = mb.get(id), il = ml.get(id), ir = mr.get(id);
      let chosen;
      if (il && ir) {
        if (eq(il, ir)) chosen = il;
        else if (eq(il, ib)) chosen = ir;
        else if (eq(ir, ib)) chosen = il;
        else { chosen = mergeItem(ib, il, ir, `${list}.${id}`, conflicts); }
      } else {
        chosen = il ?? ir;
      }
      kept.push(chosen);
    }

    kept.sort((x, y) => (x.id < y.id ? -1 : x.id > y.id ? 1 : 0));
    if (kept.length > 0) out[list] = kept;
  }

  return { merged: out, conflicts: [...new Set(conflicts)] };
}

/** 同一条目（同一个号码）的 label/type 两边都改了，逐字段再做一次三方。 */
function mergeItem(ib, il, ir, path, conflicts) {
  const out = { id: il.id };
  const keys = new Set([...Object.keys(il), ...Object.keys(ir), ...Object.keys(ib ?? {})]);
  for (const k of keys) {
    if (k === 'id') continue;
    const vb = ib?.[k], vl = il[k], vr = ir[k];
    if (eq(vl, vr)) out[k] = vl;
    else if (eq(vl, vb)) out[k] = vr;
    else if (eq(vr, vb)) out[k] = vl;
    else { out[k] = resolveScalar(vl, vr); conflicts.push(`${path}.${k}`); }
  }
  return out;
}

/** 合并结果和某一侧完全一致时可以跳过回推，省一次往返。 */
export function sameContact(a, b) {
  return JSON.stringify(sortDeep(a)) === JSON.stringify(sortDeep(b));
}

function sortDeep(v) {
  if (Array.isArray(v)) return v.map(sortDeep);
  if (v && typeof v === 'object') {
    const out = {};
    for (const k of Object.keys(v).sort()) out[k] = sortDeep(v[k]);
    return out;
  }
  return v;
}
