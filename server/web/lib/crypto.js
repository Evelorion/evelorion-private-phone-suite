/**
 * 浏览器端的密码学实现。
 *
 * ⚠ 这是**第四份**同一套密码学的实现，必须和另外三份逐字节一致：
 *     server/test/client.ts                          参考实现
 *     android/contacts/.../sync/crypto/Crypto.kt     通讯录
 *     android/phone/.../privatecalls/CallCrypto.kt   电话
 *
 * 任何一处对不上，网页端就解不开 App 存的数据（反之亦然），
 * 而且报错会是「解密失败」这种毫无头绪的形式。
 * 改动之后必须跑 /web/selftest.html，它会拿 vectors.expected.json 里的
 * 标准答案逐条比对。
 *
 * ── 用什么实现的 ──────────────────────────────────────────────
 *
 *   AES-256-GCM / HKDF-SHA256 / HMAC-SHA256 / SHA-256  →  WebCrypto（浏览器内置）
 *   Argon2id                                            →  hash-wasm（本地托管的 WASM）
 *
 * Argon2 没有内置实现，只能引第三方。用的是和服务端**同一个包**
 * （hash-wasm），所以结果天然一致。文件在 /vendor/argon2.umd.min.js，
 * wasm 是 base64 内嵌的，没有额外的网络请求。
 *
 * **不用 CDN。** 给加密代码引 CDN 等于把密钥安全交给第三方，
 * CDN 被投毒就能悄悄换掉你的加密实现。
 */

export const KDF_MEMORY_KIB = 65536; // 64 MiB
export const KDF_ITERATIONS = 3;
export const KDF_PARALLELISM = 4;
export const SCHEMA_VERSION = 1;
export const PAD_BLOCK = 256;

const INFO_KEK = 'fc.kek.v1';
const INFO_AUTH = 'fc.auth.v1';
const INFO_AUTH_RECOVERY = 'fc.auth.recovery.v1';
const INFO_RECOVERY = 'fc.rkek.v1';
const INFO_RECORD = 'fc.rec.v1';
const INFO_INDEX = 'fc.idx.v1';
const INFO_BLOB_ID = 'fc.blobid.v1';
const INFO_BLOB_KEY = 'fc.blob.v1';
const AAD_DEK_PW = 'fc.dek.pw.v1';
const AAD_DEK_RC = 'fc.dek.rc.v1';

const enc = new TextEncoder();
const dec = new TextDecoder();

// ---------------------------------------------------------------- 字节工具

export function randomBytes(n) {
  const out = new Uint8Array(n);
  crypto.getRandomValues(out);
  return out;
}

export function toHex(bytes) {
  let s = '';
  for (const b of bytes) s += b.toString(16).padStart(2, '0');
  return s;
}

export function fromHex(hex) {
  if (hex.length % 2 !== 0) throw new Error('十六进制字符串长度必须是偶数');
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

export function toB64(bytes) {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

export function fromB64(b64) {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

export function concat(...arrays) {
  const total = arrays.reduce((n, a) => n + a.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const a of arrays) { out.set(a, off); off += a.length; }
  return out;
}

export function equals(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

/** 用完的密钥立刻抹掉。JS 不保证真清干净，但能缩短它留在内存里的时间。 */
export function wipe(...arrays) {
  for (const a of arrays) if (a) a.fill(0);
}

// ---------------------------------------------------------------- 摘要

export async function sha256(data) {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', data));
}

export async function hmacSha256(key, message) {
  const k = await crypto.subtle.importKey('raw', key, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', k, message));
}

/**
 * HKDF-SHA256。
 * WebCrypto 的 HKDF 一步做完 extract + expand，和我们在 Kotlin/Node 里
 * 手写的两步等价（RFC 5869 标准算法）。
 */
export async function hkdf(ikm, salt, info, length = 32) {
  const key = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt: salt ?? new Uint8Array(0), info: enc.encode(info) },
    key,
    length * 8
  );
  return new Uint8Array(bits);
}

// ---------------------------------------------------------------- AES-256-GCM

/** 返回 nonce ‖ ciphertext ‖ tag，和另外三份实现的格式一致。 */
export async function seal(key, plaintext, aad) {
  const nonce = randomBytes(12);
  const k = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt']);
  const body = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: aad, tagLength: 128 }, k, plaintext)
  );
  return concat(nonce, body);
}

export async function open(key, sealed, aad) {
  if (sealed.length < 12 + 16) throw new Error('密文过短');
  const k = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['decrypt']);
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: sealed.slice(0, 12), additionalData: aad, tagLength: 128 },
    k,
    sealed.slice(12)
  );
  return new Uint8Array(plain);
}

// ---------------------------------------------------------------- 填充

/** ISO/IEC 7816-4：补一个 0x80 再补 0x00 到 PAD_BLOCK 整数倍。 */
export function pad(data) {
  const total = (Math.floor(data.length / PAD_BLOCK) + 1) * PAD_BLOCK;
  const out = new Uint8Array(total);
  out.set(data);
  out[data.length] = 0x80;
  return out;
}

export function unpad(data) {
  for (let i = data.length - 1; i >= 0; i--) {
    if (data[i] === 0x80) return data.slice(0, i);
    if (data[i] !== 0x00) throw new Error('填充格式错误');
  }
  throw new Error('填充格式错误');
}

// ---------------------------------------------------------------- Argon2

let argon2Ready = null;

/** 懒加载 Argon2 的 WASM。第一次调用会有几百毫秒延迟，之后走缓存。 */
async function loadArgon2() {
  if (window.hashwasm?.argon2id) return window.hashwasm;
  if (!argon2Ready) {
    argon2Ready = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = '/vendor/argon2.umd.min.js';
      s.onload = () => (window.hashwasm?.argon2id ? resolve(window.hashwasm) : reject(new Error('Argon2 加载后没有暴露接口')));
      s.onerror = () => reject(new Error('Argon2 脚本加载失败'));
      document.head.appendChild(s);
    });
  }
  return argon2Ready;
}

/**
 * 主口令 → 主密钥。
 *
 * 口令先做 NFKC 归一化 —— 不做的话中文输入法打出的全角字符
 * 会派生出完全不同的密钥，用户会遇到「明明输对了却说口令错误」。
 *
 * 这一步在低端手机上要 1~2 秒，在浏览器里通常快一些，但仍会卡住 UI 线程一段时间。
 * 调用方应当先显示「正在解锁…」。
 */
export async function deriveMasterKey(passphrase, salt, memoryKiB = KDF_MEMORY_KIB, iterations = KDF_ITERATIONS, parallelism = KDF_PARALLELISM) {
  const api = await loadArgon2();
  const hex = await api.argon2id({
    password: enc.encode(passphrase.normalize('NFKC')),
    salt,
    parallelism,
    iterations,
    memorySize: memoryKiB,
    hashLength: 32,
    outputType: 'hex',
  });
  return fromHex(hex);
}

// ---------------------------------------------------------------- 密钥体系

export const deriveKek = (mk, salt) => hkdf(mk, salt, INFO_KEK);
export const deriveRecoveryKek = (rk, salt) => hkdf(rk, salt, INFO_RECOVERY);

/**
 * 恢复码派生的认证凭据。
 *
 * 和 deriveAuthSecret 用**不同的 info 标签**做域分离 —— 一样的话，
 * 拿到其中一个就能推出另一个，恢复码和口令的独立性就没了。
 */
export const deriveRecoveryAuthSecret = async (recoveryKey, salt) =>
  toHex(await hkdf(recoveryKey, salt, INFO_AUTH_RECOVERY));
export const deriveIndexKey = (dek, salt) => hkdf(dek, salt, INFO_INDEX);
/**
 * 旧版通话子密钥。它把口令 KDF 的 salt 也混进来了；修改口令或迁移账号参数后，
 * 电话 App 和网页可能拿到不同的 salt，结果就是联系人能解开、通话记录全打不开。
 * 这里只为读取迁移前的密文保留，新的通话记录一律使用 v2。
 */
export const deriveCollectionKey = (dek, salt, collection) =>
  hkdf(dek, salt, `fc.collection.${collection}.v1`);

/**
 * 稳定的 collection 子密钥。只依赖不会随口令变化的 DEK；空 salt 是 HKDF 的
 * 标准用法，collection 名和版本号继续负责域分离。
 */
export const deriveCollectionKeyV2 = (dek, collection) =>
  hkdf(dek, new Uint8Array(0), `fc.collection.${collection}.v2`);

export async function deriveAuthSecret(mk, salt) {
  return toHex(await hkdf(mk, salt, INFO_AUTH));
}

const aadFor = (forRecovery) => enc.encode(forRecovery ? AAD_DEK_RC : AAD_DEK_PW);

export const wrapDek = (kek, dek, forRecovery) => seal(kek, dek, aadFor(forRecovery));
export const unwrapDek = (kek, wrapped, forRecovery) => open(kek, wrapped, aadFor(forRecovery));

// ---------------------------------------------------------------- 记录

export function uuidToBytes(uuid) {
  return fromHex(uuid.replace(/-/g, ''));
}

export const deriveRecordKey = (dek, uuid) => hkdf(dek, uuidToBytes(uuid), INFO_RECORD);

/**
 * AAD 绑定 uuid + rev + schema。
 * 恶意服务器没法把旧密文冒充成新版本推回来，也没法把 A 的记录塞到 B 的位置。
 */
export function recordAad(uuid, rev, schemaVer = SCHEMA_VERSION) {
  const out = new Uint8Array(21);
  out.set(uuidToBytes(uuid), 0);
  new DataView(out.buffer).setUint32(16, rev, false); // 大端
  out[20] = schemaVer;
  return out;
}

export async function encryptRecord(dek, uuid, rev, obj) {
  const key = await deriveRecordKey(dek, uuid);
  try {
    const sealed = await seal(key, pad(enc.encode(canonicalJson(obj))), recordAad(uuid, rev));
    return { nonce: toB64(sealed.slice(0, 12)), ciphertext: toB64(sealed.slice(12)) };
  } finally {
    wipe(key);
  }
}

export async function decryptRecord(dek, uuid, rev, nonceB64, ciphertextB64) {
  const key = await deriveRecordKey(dek, uuid);
  try {
    const sealed = concat(fromB64(nonceB64), fromB64(ciphertextB64));
    return JSON.parse(dec.decode(unpad(await open(key, sealed, recordAad(uuid, rev)))));
  } finally {
    wipe(key);
  }
}

/**
 * 规范化 JSON：键排序、无空白。
 * 两端必须产出完全相同的字节，否则哈希对不上，每次同步都会误判成有改动。
 */
export function canonicalJson(value) {
  if (value === null || typeof value === 'number' || typeof value === 'boolean' || typeof value === 'string') {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']';
  const keys = Object.keys(value).filter((k) => value[k] !== undefined).sort();
  return '{' + keys.map((k) => JSON.stringify(k) + ':' + canonicalJson(value[k])).join(',') + '}';
}

// ---------------------------------------------------------------- 条目 id

/**
 * 列表条目的 id 由内容确定性推导，不随机生成。
 * 两台设备各自录入同一个号码会算出同一个 id，三方合并时自动去重。
 *
 * 这是唯一一个**同步**函数里用了 SHA-256 的地方 —— WebCrypto 的 digest 是异步的，
 * 所以这里也是异步的，调用方记得 await。
 */
export async function itemId(list, identity) {
  // 分隔符是 NUL（0x00）不是空格 —— 空格会让 ('phones','a b') 和 ('phones a','b') 撞车。
  const h = await sha256(concat(enc.encode(list), new Uint8Array([0x00]), enc.encode(identity)));
  return toHex(h).slice(0, 32);
}

/** 号码归一化。必须和 Kotlin 的 ContactPayload.normalizeNumber 完全一致。 */
export function normalizeNumber(raw) {
  let out = '';
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (c >= '0' && c <= '9') out += c;
    else if (c === '+' && i === 0) out += c;
  }
  return out;
}

// ---------------------------------------------------------------- 头像

export async function blobId(dek, plaintext) {
  const idKey = await hkdf(dek, new Uint8Array(0), INFO_BLOB_ID);
  try {
    return toHex(await hmacSha256(idKey, plaintext));
  } finally {
    wipe(idKey);
  }
}

export async function sealBlob(dek, plaintext) {
  const hash = await blobId(dek, plaintext);
  const key = await hkdf(dek, fromHex(hash), INFO_BLOB_KEY);
  try {
    const sealed = await seal(key, plaintext, fromHex(hash));
    return { hash, nonce: toB64(sealed.slice(0, 12)), ciphertext: toB64(sealed.slice(12)) };
  } finally {
    wipe(key);
  }
}

export async function openBlob(dek, hash, nonceB64, ciphertextB64) {
  const key = await hkdf(dek, fromHex(hash), INFO_BLOB_KEY);
  try {
    return await open(key, concat(fromB64(nonceB64), fromB64(ciphertextB64)), fromHex(hash));
  } finally {
    wipe(key);
  }
}

// ---------------------------------------------------------------- 恢复码

const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'; // Crockford，去掉 I L O U

function base32Encode(data) {
  let bits = 0, value = 0, out = '';
  for (const byte of data) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) { out += ALPHABET[(value >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) out += ALPHABET[(value << (5 - bits)) & 31];
  return out;
}

function base32Decode(text) {
  let bits = 0, value = 0;
  const out = [];
  for (const ch of text) {
    const idx = ALPHABET.indexOf(ch);
    if (idx < 0) throw new Error(`恢复码含有非法字符 ${ch}`);
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return new Uint8Array(out);
}

export async function formatRecoveryCode(recoveryKey) {
  const payload = base32Encode(recoveryKey);
  const check = base32Encode(await sha256(recoveryKey)).slice(0, 4);
  return (payload + check).match(/.{1,4}/g).join('-');
}

/** 容错解析：忽略大小写空格连字符，把 I/L→1、O→0、U→V 纠正回来。 */
export async function parseRecoveryCode(input) {
  let cleaned = '';
  for (const ch of input.toUpperCase()) {
    if (ch === ' ' || ch === '-' || ch === '\t' || ch === '\n') continue;
    if (ch === 'I' || ch === 'L') cleaned += '1';
    else if (ch === 'O') cleaned += '0';
    else if (ch === 'U') cleaned += 'V';
    else cleaned += ch;
  }
  if (cleaned.length !== 56) throw new Error(`恢复码应该是 56 个字符，实际 ${cleaned.length} 个`);
  const key = base32Decode(cleaned.slice(0, 52)).slice(0, 32);
  const expected = base32Encode(await sha256(key)).slice(0, 4);
  if (cleaned.slice(52) !== expected) throw new Error('恢复码校验失败，请检查是否有输错的字符');
  return key;
}

// ---------------------------------------------------------------- 建库

export async function createVault(passphrase) {
  const salt = randomBytes(16);
  const mk = await deriveMasterKey(passphrase, salt);
  const dek = randomBytes(32);
  const recoveryKey = randomBytes(32);
  const kek = await deriveKek(mk, salt);
  const rkek = await deriveRecoveryKek(recoveryKey, salt);
  try {
    return {
      salt, dek, recoveryKey,
      recoveryCode: await formatRecoveryCode(recoveryKey),
      authSecret: await deriveAuthSecret(mk, salt),
      recoveryAuthSecret: await deriveRecoveryAuthSecret(recoveryKey, salt),
      dekWrapPassword: await wrapDek(kek, dek, false),
      dekWrapRecovery: await wrapDek(rkek, dek, true),
    };
  } finally {
    wipe(mk, kek, rkek);
  }
}

// ---------------------------------------------------------------- 同步清单

export const MANIFEST_UUID = '00000000-0000-4000-8000-000000000001';
const MANIFEST_MAGIC = 0x4653594d; // "FSYM"
const MANIFEST_VERSION = 1;
const ENTRY_BYTES = 20;
export const MANIFEST_MAX_ENTRIES = 3200;

export function encodeManifest(entries) {
  const ids = Object.keys(entries).sort();
  if (ids.length > MANIFEST_MAX_ENTRIES) {
    throw new Error(`条目数 ${ids.length} 超过清单上限 ${MANIFEST_MAX_ENTRIES}`);
  }
  const out = new Uint8Array(9 + ids.length * ENTRY_BYTES);
  const view = new DataView(out.buffer);
  view.setUint32(0, MANIFEST_MAGIC, false);
  out[4] = MANIFEST_VERSION;
  view.setUint32(5, ids.length, false);
  let off = 9;
  for (const id of ids) {
    out.set(uuidToBytes(id), off);
    view.setUint32(off + 16, entries[id], false);
    off += ENTRY_BYTES;
  }
  return toB64(out);
}

export function decodeManifest(payload) {
  const buf = fromB64(payload);
  if (buf.length < 9) throw new Error('清单过短');
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  if (view.getUint32(0, false) !== MANIFEST_MAGIC) throw new Error('清单魔数不对');
  if (buf[4] !== MANIFEST_VERSION) throw new Error(`清单版本 ${buf[4]} 不认识`);
  const count = view.getUint32(5, false);
  if (9 + count * ENTRY_BYTES !== buf.length) throw new Error('清单长度和条目数对不上');

  const out = {};
  let off = 9;
  for (let i = 0; i < count; i++) {
    const hex = toHex(buf.slice(off, off + 16));
    out[`${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20,32)}`] =
      view.getUint32(off + 16, false);
    off += ENTRY_BYTES;
  }
  return out;
}

/** 返回问题列表。非空意味着服务器给的数据不完整或被回退过。 */
export function verifyManifest(manifest, manifestRev, lastKnownRev, present) {
  const issues = [];
  if (manifestRev < lastKnownRev) {
    issues.push(`同步清单被退回了（应为 rev=${lastKnownRev}，实际 ${manifestRev}）`);
  }
  for (const [uuid, expectedRev] of Object.entries(manifest)) {
    const actual = present[uuid];
    if (actual === undefined) issues.push(`服务器没有返回记录 ${uuid.slice(0, 8)}（清单里记着 rev=${expectedRev}）`);
    else if (actual < expectedRev) issues.push(`记录 ${uuid.slice(0, 8)} 的版本被退回了（应为 ${expectedRev}，实际 ${actual}）`);
  }
  return issues;
}
