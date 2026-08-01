/**
 * 浏览器端的保险库和同步。
 *
 * 和 Android 的 SyncEngine 相比少了一半复杂度，因为浏览器**没有本地联系人库**：
 * 页面加载时把全部记录拉下来解密进内存，编辑后加密推回去。
 * 三方合并的 base 就是"我加载时看到的那一份"。
 *
 * 也因此，网页端不需要 detectLocalChanges 那套全量扫描 ——
 * 谁被改过我们自己知道。
 */

import * as C from './crypto.js';
import { threeWayMerge } from './merge.js';
import * as MFA from './mfa.js';
import { userApi, setTokens, clearTokens } from './api.js';

export const vault = {
  username: null,
  accountId: null,
  salt: null,
  dek: null,
  kdf: null,
  /** uuid → { rev, payload, base }  base 是上次同步成功时的快照，合并用 */
  contacts: new Map(),
  calls: [],
  manifestRev: 0,
  integrityIssues: [],
};

export const isUnlocked = () => vault.dek !== null;

/** 登出并把内存里的密钥抹掉。 */
export function lock() {
  C.wipe(vault.dek, vault.salt);
  vault.dek = null;
  vault.salt = null;
  vault.contacts = new Map();
  vault.calls = [];
  vault.integrityIssues = [];
  clearTokens();
}

// ---------------------------------------------------------------- 登录

/**
 * 登录。
 *
 * @param mfaPrompt 两步验证的输入回调。账号开了 2FA 时才会用到，
 *                  形状见 lib/mfa.js 的 completeLogin。
 *                  不传的话遇到开了 2FA 的账号会明确报错，
 *                  而不是静默失败在「主口令不正确」上 —— 那个提示会
 *                  让用户去反复试口令，方向完全错了。
 */
export async function login(username, passphrase, deviceName, mfaPrompt = null) {
  const kdf = await userApi(`/v1/account/kdf?username=${encodeURIComponent(username)}`, { auth: false });
  const salt = C.fromB64(kdf.salt);
  const mk = await C.deriveMasterKey(passphrase, salt, kdf.memoryKiB, kdf.iterations, kdf.parallelism);

  let session = await userApi('/v1/session', {
    method: 'POST', auth: false,
    body: { username, authSecret: await C.deriveAuthSecret(mk, salt), deviceName },
  });

  // 开了两步验证的话，第一步不发令牌，只给一个一次性的 mfaToken。
  // 走完第二步拿到的响应结构和一步登录时完全一样，所以后面的代码不用分叉。
  if (session.mfaRequired) {
    if (!mfaPrompt) {
      C.wipe(mk);
      throw new Error('这个账号开启了两步验证，但当前页面没有提供验证界面');
    }
    session = await MFA.completeLogin(
      session.mfaToken,
      { methods: session.methods, requireAll: session.requireAll },
      mfaPrompt
    );
  }

  setTokens(session.accessToken, session.refreshToken, session.accessExpiresAt);

  const kek = await C.deriveKek(mk, salt);
  try {
    vault.dek = await C.unwrapDek(kek, C.fromB64(session.dekWrapPassword), false);
  } catch (e) {
    clearTokens();
    throw new Error('主口令不正确');
  } finally {
    C.wipe(mk, kek);
  }

  vault.username = username;
  vault.accountId = session.accountId;
  vault.salt = salt;
  vault.kdf = { memoryKiB: kdf.memoryKiB, iterations: kdf.iterations, parallelism: kdf.parallelism };
  return session;
}

/** 使用账户私钥直接登录并解密，不需要主口令。 */
export async function loginWithPrivateKey(username, privateKeyCode, deviceName, mfaPrompt = null) {
  const kdf = await userApi(`/v1/account/kdf?username=${encodeURIComponent(username)}`, { auth: false });
  const salt = C.fromB64(kdf.salt);
  const recoveryKey = await C.parseRecoveryCode(privateKeyCode);
  let session;
  try {
    session = await userApi('/v1/session/recovery', {
      method: 'POST', auth: false,
      body: {
        username,
        recoveryAuthSecret: await C.deriveRecoveryAuthSecret(recoveryKey, salt),
        deviceName,
        directLogin: true,
      },
    });
    if (session.mfaRequired) {
      if (!mfaPrompt) throw new Error('这个账户还要求二次验证');
      session = await MFA.completeLogin(
        session.mfaToken,
        { methods: session.methods, requireAll: session.requireAll },
        mfaPrompt,
      );
    }

    const rkek = await C.deriveRecoveryKek(recoveryKey, salt);
    try {
      vault.dek = await C.unwrapDek(rkek, C.fromB64(session.dekWrapRecovery), true);
    } catch {
      clearTokens();
      throw new Error('账户私钥不属于这个账号');
    } finally {
      C.wipe(rkek);
    }
    setTokens(session.accessToken, session.refreshToken, session.accessExpiresAt);
    vault.username = username;
    vault.accountId = session.accountId;
    vault.salt = salt;
    vault.kdf = { memoryKiB: kdf.memoryKiB, iterations: kdf.iterations, parallelism: kdf.parallelism };
    return session;
  } finally {
    C.wipe(recoveryKey);
  }
}

export async function register(username, passphrase, inviteCode, deviceName) {
  const v = await C.createVault(passphrase);
  const res = await userApi('/v1/account/register', {
    method: 'POST', auth: false,
    body: {
      registrationToken: inviteCode,
      username,
      authSecret: v.authSecret,
      // 让恢复码也能当登录凭据 —— 否则忘了主口令就是死局：
      // 恢复码能解开 DEK，但登录这一关过不去
      recoveryAuthSecret: v.recoveryAuthSecret,
      kdf: {
        salt: C.toB64(v.salt),
        memoryKiB: C.KDF_MEMORY_KIB,
        iterations: C.KDF_ITERATIONS,
        parallelism: C.KDF_PARALLELISM,
      },
      dekWrapPassword: C.toB64(v.dekWrapPassword),
      dekWrapRecovery: C.toB64(v.dekWrapRecovery),
      deviceName,
    },
  });

  setTokens(res.accessToken, res.refreshToken, res.accessExpiresAt);
  vault.username = username;
  vault.accountId = res.accountId;
  vault.salt = v.salt;
  vault.dek = v.dek;
  vault.kdf = { memoryKiB: C.KDF_MEMORY_KIB, iterations: C.KDF_ITERATIONS, parallelism: C.KDF_PARALLELISM };

  // 恢复码只在这里返回一次，之后任何地方都拿不回来
  const recoveryCode = v.recoveryCode;
  C.wipe(v.recoveryKey);
  return { recoveryCode };
}

/** 忘了口令时用恢复码解锁。需要先能登录（拿到令牌）才能取到包裹。 */
export async function unlockWithRecoveryCode(username, code) {
  const key = await C.parseRecoveryCode(code);
  const info = await userApi('/v1/vault');
  const salt = C.fromB64(info.kdf.salt);
  const rkek = await C.deriveRecoveryKek(key, salt);
  try {
    vault.dek = await C.unwrapDek(rkek, C.fromB64(info.dekWrapRecovery), true);
    vault.salt = salt;
  } catch (e) {
    throw new Error('恢复码不属于这个账号');
  } finally {
    C.wipe(key, rkek);
  }
}

// ---------------------------------------------------------------- 拉取

/** 把服务器上的联系人全部拉下来解密。这是打开页面后的第一件事。 */
export async function loadAll(onProgress) {
  vault.contacts = new Map();
  vault.integrityIssues = [];

  let since = 0;
  let manifestChange = null;
  const failed = [];

  for (;;) {
    const res = await userApi(`/v1/sync/changes?collection=contacts&since=${since}&limit=500`);
    for (const ch of res.changes) {
      if (ch.uuid === C.MANIFEST_UUID) { manifestChange = ch; continue; }
      if (ch.deleted) { vault.contacts.delete(ch.uuid); continue; }
      try {
        const payload = await C.decryptRecord(vault.dek, ch.uuid, ch.rev, ch.nonce, ch.ciphertext);
        vault.contacts.set(ch.uuid, { rev: ch.rev, payload, base: structuredClone(payload) });
      } catch (e) {
        // 解不开只可能是密钥不对或数据被改过。跳过这条别让整次加载挂掉，
        // 但要记下来 —— 静默吞掉的话用户永远不知道少了东西。
        failed.push(ch.uuid);
      }
    }
    onProgress?.(vault.contacts.size);
    since = res.nextSince;
    if (!res.hasMore) break;
  }

  if (failed.length > 0) {
    vault.integrityIssues.push(`有 ${failed.length} 条记录解密失败，可能来自另一个账号`);
  }

  await verifyManifest(manifestChange);
  return vault.contacts;
}

/**
 * 拿服务端的清单和实际拉到的对一遍。
 * 这是唯一能发现「服务器少给了东西」的手段 —— 尤其在网页端，
 * 本地没有任何副本，完全依赖服务器说的话。
 */
async function verifyManifest(change) {
  if (!change) {
    if (vault.manifestRev > 0) {
      vault.integrityIssues.push(`服务器没有返回同步清单（本地记着 rev=${vault.manifestRev}）`);
    }
    return;
  }
  let manifest;
  try {
    const json = await C.decryptRecord(vault.dek, C.MANIFEST_UUID, change.rev, change.nonce, change.ciphertext);
    manifest = C.decodeManifest(json);
  } catch (e) {
    vault.integrityIssues.push('同步清单解密失败，它可能被改过');
    return;
  }
  const present = {};
  for (const [uuid, entry] of vault.contacts) present[uuid] = entry.rev;
  vault.integrityIssues.push(...C.verifyManifest(manifest, change.rev, vault.manifestRev, present));
  vault.manifestRev = Math.max(change.rev, vault.manifestRev);
}

/**
 * 通话记录用的是另一把密钥，只读展示。
 *
 * v2 不再依赖可能变化的口令 KDF salt；迁移期间逐条尝试 v2 和旧 v1，
 * 这样新旧密文可以同时存在。解不开的数量必须返回给界面，不能再把
 * “云端有密文但当前密钥不匹配”误报成“没有通话记录”。
 */
export async function loadCalls() {
  const callKeyV2 = await C.deriveCollectionKeyV2(vault.dek, 'calls');
  const callKeyV1 = await C.deriveCollectionKey(vault.dek, vault.salt, 'calls');
  const out = [];
  let encrypted = 0;
  let failed = 0;
  try {
    let since = 0;
    for (;;) {
      const res = await userApi(`/v1/sync/changes?collection=calls&since=${since}&limit=500`);
      for (const ch of res.changes) {
        if (ch.deleted) continue;
        encrypted++;
        let record = null;
        try {
          record = await C.decryptRecord(callKeyV2, ch.uuid, ch.rev, ch.nonce, ch.ciphertext);
        } catch {
          try {
            record = await C.decryptRecord(callKeyV1, ch.uuid, ch.rev, ch.nonce, ch.ciphertext);
          } catch { /* 下面统一计数并明确提示用户 */ }
        }
        if (record) out.push(record);
        else failed++;
      }
      since = res.nextSince;
      if (!res.hasMore) break;
    }
  } finally {
    C.wipe(callKeyV2, callKeyV1);
  }
  out.sort((a, b) => (b.startedAt ?? b.ts ?? 0) - (a.startedAt ?? a.ts ?? 0));
  vault.calls = out;
  return { records: out, encrypted, failed };
}

// ---------------------------------------------------------------- 写入

/**
 * 保存一个联系人。
 *
 * 撞冲突时自动做三方合并再推一次，最多两轮。合并用的 base 是
 * 我们加载时看到的那份快照 —— 这正是三方合并需要的共同祖先。
 *
 * @returns {{uuid: string, conflicts: string[]}}
 */
export async function saveContact(uuid, payload) {
  const existing = vault.contacts.get(uuid);
  let baseRev = existing?.rev ?? 0;
  let toPush = payload;
  let conflicts = [];

  for (let round = 0; round < 3; round++) {
    const enc = await C.encryptRecord(vault.dek, uuid, baseRev + 1, toPush);
    const res = await userApi('/v1/sync/push', {
      method: 'POST',
      body: { collection: 'contacts', changes: [{ uuid, baseRev, schemaVer: 1, ...enc }] },
    });
    const r = res.results[0];

    if (r.status === 'applied') {
      vault.contacts.set(uuid, { rev: r.rev, payload: toPush, base: structuredClone(toPush) });
      await updateManifest();
      return { uuid, conflicts };
    }

    // 冲突：别人先推了。解开服务端那版，和我们的改动三方合并。
    const srv = r.server;
    if (!srv) { vault.contacts.delete(uuid); throw new Error('这条联系人已被其它设备删除'); }
    const remote = await C.decryptRecord(vault.dek, uuid, srv.rev, srv.nonce, srv.ciphertext);
    const merged = threeWayMerge(existing?.base ?? null, toPush, remote);
    toPush = merged.merged;
    conflicts = merged.conflicts;
    baseRev = srv.rev;
  }
  throw new Error('反复冲突，另一台设备正在同时修改。稍后再试。');
}

export async function deleteContact(uuid) {
  const existing = vault.contacts.get(uuid);
  if (!existing) return;
  await userApi('/v1/sync/push', {
    method: 'POST',
    body: { collection: 'contacts', changes: [{ uuid, baseRev: existing.rev, deleted: true, schemaVer: 1 }] },
  });
  vault.contacts.delete(uuid);
  await updateManifest();
}

/**
 * 重写同步清单。每次改动后都要更新，否则下次校验会用过时的清单误报。
 * 撞冲突就跳过 —— 清单不是数据，晚一轮更新不会丢东西。
 */
async function updateManifest() {
  const entries = {};
  for (const [uuid, e] of vault.contacts) if (e.rev > 0) entries[uuid] = e.rev;

  let payload;
  try {
    payload = C.encodeManifest(entries);
  } catch (e) {
    vault.integrityIssues.push(e.message);
    return;
  }

  const nextRev = vault.manifestRev + 1;
  const enc = await C.encryptRecord(vault.dek, C.MANIFEST_UUID, nextRev, payload);
  try {
    const res = await userApi('/v1/sync/push', {
      method: 'POST',
      body: {
        collection: 'contacts',
        changes: [{ uuid: C.MANIFEST_UUID, baseRev: vault.manifestRev, schemaVer: 1, ...enc }],
      },
    });
    const r = res.results[0];
    if (r.status === 'applied') vault.manifestRev = r.rev;
    else if (r.server) vault.manifestRev = r.server.rev;
  } catch { /* 清单更新失败不该阻塞用户保存联系人 */ }
}

// ---------------------------------------------------------------- 账号

export async function changePassphrase(currentPassphrase, newPassphrase, recoveryCode) {
  const recoveryKey = await C.parseRecoveryCode(recoveryCode);
  const oldMk = await C.deriveMasterKey(
    currentPassphrase, vault.salt, vault.kdf.memoryKiB, vault.kdf.iterations, vault.kdf.parallelism
  );

  // 换口令顺便换盐，避免新旧口令共用同一个盐
  const newSalt = C.randomBytes(16);
  const newMk = await C.deriveMasterKey(newPassphrase, newSalt);
  const newKek = await C.deriveKek(newMk, newSalt);
  const newRkek = await C.deriveRecoveryKek(recoveryKey, newSalt);

  try {
    await userApi('/v1/vault/rewrap', {
      method: 'POST',
      body: {
        currentAuthSecret: await C.deriveAuthSecret(oldMk, vault.salt),
        newAuthSecret: await C.deriveAuthSecret(newMk, newSalt),
        kdf: {
          salt: C.toB64(newSalt),
          memoryKiB: C.KDF_MEMORY_KIB,
          iterations: C.KDF_ITERATIONS,
          parallelism: C.KDF_PARALLELISM,
        },
        dekWrapPassword: C.toB64(await C.wrapDek(newKek, vault.dek, false)),
        dekWrapRecovery: C.toB64(await C.wrapDek(newRkek, vault.dek, true)),
        recoveryAuthSecret: await C.deriveRecoveryAuthSecret(recoveryKey, newSalt),
      },
    });
    vault.salt = newSalt;
    vault.kdf = { memoryKiB: C.KDF_MEMORY_KIB, iterations: C.KDF_ITERATIONS, parallelism: C.KDF_PARALLELISM };
  } finally {
    C.wipe(oldMk, newMk, newKek, newRkek, recoveryKey);
  }
}

export async function enablePrivateKeyLogin(currentPassphrase, privateKeyCode) {
  const privateKey = await C.parseRecoveryCode(privateKeyCode);
  const info = await userApi('/v1/vault');
  const salt = C.fromB64(info.kdf.salt);
  const rkek = await C.deriveRecoveryKek(privateKey, salt);
  const mk = await C.deriveMasterKey(
    currentPassphrase, salt, info.kdf.memoryKiB, info.kdf.iterations, info.kdf.parallelism,
  );
  try {
    const unwrapped = await C.unwrapDek(rkek, C.fromB64(info.dekWrapRecovery), true);
    try {
      if (!C.equals(unwrapped, vault.dek)) throw new Error('账户私钥不属于这个账号');
    } finally {
      C.wipe(unwrapped);
    }
    return userApi('/v1/vault/private-key-login/enable', {
      method: 'POST',
      body: {
        currentAuthSecret: await C.deriveAuthSecret(mk, salt),
        privateKeyAuthSecret: await C.deriveRecoveryAuthSecret(privateKey, salt),
      },
    });
  } finally {
    C.wipe(privateKey, rkek, mk);
  }
}

export const listDevices = () => userApi('/v1/devices');
export const revokeDevice = (id) => userApi(`/v1/devices/${encodeURIComponent(id)}`, { method: 'DELETE' });
export const syncStatus = () => userApi('/v1/sync/status');
export const privateKeyLoginStatus = async () => {
  const info = await userApi('/v1/vault');
  return { enabled: info.privateKeyLoginEnabled === true };
};

export async function destroyAccount(passphrase) {
  const mk = await C.deriveMasterKey(
    passphrase, vault.salt, vault.kdf.memoryKiB, vault.kdf.iterations, vault.kdf.parallelism
  );
  try {
    await userApi('/v1/account/destroy', {
      method: 'POST',
      body: { authSecret: await C.deriveAuthSecret(mk, vault.salt) },
    });
  } finally {
    C.wipe(mk);
  }
  lock();
}

// ---------------------------------------------------------------- 导出

/** 导出成 vCard。换手机之前留个副本，或者导进别的通讯录 App。 */
export function exportVCard() {
  const esc = (s) => String(s ?? '').replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\n/g, '\\n');
  const lines = [];
  for (const { payload: c } of vault.contacts.values()) {
    lines.push('BEGIN:VCARD', 'VERSION:3.0');
    lines.push(`N:${esc(c.surname)};${esc(c.first)};${esc(c.middle)};${esc(c.prefix)};${esc(c.suffix)}`);
    const display = [c.prefix, c.first, c.middle, c.surname, c.suffix].filter(Boolean).join(' ');
    lines.push(`FN:${esc(display || c.nickname || '（无名）')}`);
    if (c.nickname) lines.push(`NICKNAME:${esc(c.nickname)}`);
    if (c.company || c.jobTitle) lines.push(`ORG:${esc(c.company)}`), c.jobTitle && lines.push(`TITLE:${esc(c.jobTitle)}`);
    for (const p of c.phones ?? []) lines.push(`TEL;TYPE=CELL:${esc(p.value)}`);
    for (const e of c.emails ?? []) lines.push(`EMAIL:${esc(e.value)}`);
    for (const a of c.addresses ?? []) lines.push(`ADR:;;${esc(a.value)};;;;`);
    for (const w of c.websites ?? []) lines.push(`URL:${esc(w.value)}`);
    if (c.notes) lines.push(`NOTE:${esc(c.notes)}`);
    lines.push('END:VCARD');
  }
  return lines.join('\r\n');
}

// ---------------------------------------------------------------- 空联系人

export function emptyContact() {
  return {
    v: 1, prefix: '', first: '', middle: '', surname: '', suffix: '', nickname: '',
    company: '', jobTitle: '', notes: '', starred: 0, ringtone: '', photo: '',
    phones: [], emails: [], addresses: [], events: [], websites: [], ims: [], groups: [],
  };
}

export function displayName(c) {
  const n = [c.prefix, c.first, c.middle, c.surname, c.suffix].filter(Boolean).join(' ').trim();
  return n || c.nickname || c.company || (c.phones?.[0]?.value ?? '（无名）');
}

/** 重建列表项的 id。改完号码/邮箱要调它，否则合并时会认成另一条。 */
export async function normalizeItemIds(c) {
  for (const p of c.phones ?? []) {
    p.norm = C.normalizeNumber(p.norm || p.value);
    p.id = await C.itemId('phones', p.norm);
  }
  for (const e of c.emails ?? []) e.id = await C.itemId('emails', e.value.toLowerCase());
  for (const a of c.addresses ?? []) a.id = await C.itemId('addresses', a.value);
  for (const e of c.events ?? []) e.id = await C.itemId('events', `${e.type} ${e.value}`);
  for (const w of c.websites ?? []) w.id = await C.itemId('websites', w.value);
  for (const i of c.ims ?? []) i.id = await C.itemId('ims', `${i.type} ${i.value}`);
  for (const g of c.groups ?? []) g.id = await C.itemId('groups', g.value);
  return c;
}
