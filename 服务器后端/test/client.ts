/**
 * 参考客户端实现 —— 这份文件同时是 Android/Kotlin 端的规格说明。
 * Kotlin 里的 Crypto.kt / KeyDerivation.kt 必须和这里逐字节一致，
 * 否则两端互相解不开。test/vectors.ts 里有固定测试向量用于交叉校验。
 */
import { createHmac, createCipheriv, createDecipheriv, randomBytes, hkdfSync, createHash } from 'node:crypto';
import { argon2id } from 'hash-wasm';

export const KDF_MEMORY_KIB = 65536;   // 64 MiB
export const KDF_ITERATIONS = 3;
export const KDF_PARALLELISM = 4;
export const SCHEMA_VERSION = 1;
export const PAD_BLOCK = 256;

const INFO_KEK       = 'fc.kek.v1';
const INFO_AUTH      = 'fc.auth.v1';
const INFO_AUTH_RECOVERY = 'fc.auth.recovery.v1';
const INFO_RECOVERY  = 'fc.rkek.v1';
const INFO_RECORD    = 'fc.rec.v1';
const INFO_INDEX     = 'fc.idx.v1';
const INFO_BLOB_ID   = 'fc.blobid.v1';
const INFO_BLOB_KEY  = 'fc.blob.v1';
const AAD_DEK_PW     = 'fc.dek.pw.v1';
const AAD_DEK_RC     = 'fc.dek.rc.v1';

function hkdf(ikm: Buffer, salt: Buffer, info: string, len = 32): Buffer {
  return Buffer.from(hkdfSync('sha256', ikm, salt, Buffer.from(info, 'utf8'), len));
}

/** 口令先做 NFKC 归一化，否则不同输入法打出的同一个口令会派生出不同密钥。 */
export async function deriveMasterKey(passphrase: string, salt: Buffer): Promise<Buffer> {
  const hex = await argon2id({
    password: Buffer.from(passphrase.normalize('NFKC'), 'utf8'),
    salt,
    parallelism: KDF_PARALLELISM,
    iterations: KDF_ITERATIONS,
    memorySize: KDF_MEMORY_KIB,
    hashLength: 32,
    outputType: 'hex',
  });
  return Buffer.from(hex, 'hex');
}

export function deriveKek(masterKey: Buffer, salt: Buffer): Buffer {
  return hkdf(masterKey, salt, INFO_KEK);
}
/** 送给服务器做认证的值。它由 MK 派生但和 KEK 域分离，服务器拿到它也解不开 DEK。 */
export function deriveAuthSecret(masterKey: Buffer, salt: Buffer): string {
  return hkdf(masterKey, salt, INFO_AUTH).toString('hex');
}

/**
 * 恢复码派生的认证凭据。
 *
 * 和 deriveAuthSecret 用**不同的 info 标签**做域分离 —— 一样的话，
 * 拿到其中一个就能推出另一个，恢复码和口令的独立性就没了。
 *
 * 输入是恢复码解析出的原始密钥（不是恢复码字符串本身）。
 */
export function deriveRecoveryAuthSecret(recoveryKey: Buffer, salt: Buffer): string {
  return hkdf(recoveryKey, salt, INFO_AUTH_RECOVERY).toString('hex');
}

export function deriveRecoveryKek(recoveryKey: Buffer, salt: Buffer): Buffer {
  return hkdf(recoveryKey, salt, INFO_RECOVERY);
}

/**
 * 按 collection 派生出的子密钥。
 *
 * 电话 App 只拿得到 collection = "calls" 的这一把，拿不到 DEK 本身，
 * 所以它就算被攻破也解不开通讯录 —— 反过来也一样。
 * 通讯录 App 通过一个 signature 权限保护的 ContentProvider 把这把子密钥交给电话 App。
 */
export function deriveCollectionKey(dek: Buffer, salt: Buffer, collection: string): Buffer {
  return hkdf(dek, salt, `fc.collection.${collection}.v1`);
}

// ---------- AES-256-GCM ----------

export function seal(key: Buffer, plaintext: Buffer, aad: Buffer): Buffer {
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(aad);
  const body = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  return Buffer.concat([nonce, body, cipher.getAuthTag()]);
}

export function open(key: Buffer, blob: Buffer, aad: Buffer): Buffer {
  if (blob.length < 12 + 16) throw new Error('密文过短');
  const nonce = blob.subarray(0, 12);
  const tag = blob.subarray(blob.length - 16);
  const body = blob.subarray(12, blob.length - 16);
  const decipher = createDecipheriv('aes-256-gcm', key, nonce);
  decipher.setAAD(aad);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(body), decipher.final()]);
}

// ---------- DEK 包裹 ----------

export function wrapDek(kek: Buffer, dek: Buffer, forRecovery: boolean): Buffer {
  return seal(kek, dek, Buffer.from(forRecovery ? AAD_DEK_RC : AAD_DEK_PW, 'utf8'));
}
export function unwrapDek(kek: Buffer, wrapped: Buffer, forRecovery: boolean): Buffer {
  return open(kek, wrapped, Buffer.from(forRecovery ? AAD_DEK_RC : AAD_DEK_PW, 'utf8'));
}

// ---------- 恢复码：Crockford Base32 + 4 字符校验 ----------

const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'; // 去掉 I L O U

function base32Encode(data: Buffer): string {
  let bits = 0, value = 0, out = '';
  for (const byte of data) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += ALPHABET[(value << (5 - bits)) & 31];
  return out;
}

function base32Decode(text: string): Buffer {
  let bits = 0, value = 0;
  const out: number[] = [];
  for (const ch of text) {
    const idx = ALPHABET.indexOf(ch);
    if (idx < 0) throw new Error(`恢复码含有非法字符 ${ch}`);
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

/** 用户看到的形式：14 组 4 字符，用连字符分开。 */
export function formatRecoveryCode(recoveryKey: Buffer): string {
  const payload = base32Encode(recoveryKey);                       // 52 字符
  const check = base32Encode(createHash('sha256').update(recoveryKey).digest()).slice(0, 4);
  return (payload + check).match(/.{1,4}/g)!.join('-');
}

/** 容错解析：忽略大小写、空格、连字符，并把常见的 I/L→1、O→0、U→V 纠正回来。 */
export function parseRecoveryCode(input: string): Buffer {
  const cleaned = input
    .toUpperCase()
    .replace(/[\s-]/g, '')
    .replace(/[IL]/g, '1')
    .replace(/O/g, '0')
    .replace(/U/g, 'V');
  if (cleaned.length !== 56) throw new Error('恢复码长度不对，应为 56 个字符');
  const payload = cleaned.slice(0, 52);
  const check = cleaned.slice(52);
  const key = base32Decode(payload).subarray(0, 32);
  const expected = base32Encode(createHash('sha256').update(key).digest()).slice(0, 4);
  if (check !== expected) throw new Error('恢复码校验失败，请检查是否输错');
  return key;
}

// ---------- 记录加密 ----------

function uuidBytes(uuid: string): Buffer {
  return Buffer.from(uuid.replace(/-/g, ''), 'hex');
}

export function deriveRecordKey(dek: Buffer, uuid: string): Buffer {
  return hkdf(dek, uuidBytes(uuid), INFO_RECORD);
}

/** AAD 绑定 uuid + rev + schema，服务器没法把旧密文冒充成新版本，也没法把 A 的密文塞给 B。 */
export function recordAad(uuid: string, rev: number, schemaVer: number): Buffer {
  const buf = Buffer.alloc(16 + 4 + 1);
  uuidBytes(uuid).copy(buf, 0);
  buf.writeUInt32BE(rev, 16);
  buf.writeUInt8(schemaVer, 20);
  return buf;
}

/** ISO/IEC 7816-4 填充到 PAD_BLOCK 的整数倍，抹平"这个联系人字段多"这种元数据。 */
export function pad(data: Buffer): Buffer {
  const total = (Math.floor(data.length / PAD_BLOCK) + 1) * PAD_BLOCK;
  const out = Buffer.alloc(total);
  data.copy(out, 0);
  out[data.length] = 0x80;
  return out;
}

export function unpad(data: Buffer): Buffer {
  for (let i = data.length - 1; i >= 0; i--) {
    if (data[i] === 0x80) return data.subarray(0, i);
    if (data[i] !== 0x00) throw new Error('填充格式错误');
  }
  throw new Error('填充格式错误');
}

/** 规范化 JSON：键排序、无空白。两端必须产出完全相同的字节。 */
export function canonicalJson(value: unknown): string {
  if (value === null || typeof value === 'number' || typeof value === 'boolean') return JSON.stringify(value);
  if (typeof value === 'string') return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']';
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj).filter((k) => obj[k] !== undefined).sort();
  return '{' + keys.map((k) => JSON.stringify(k) + ':' + canonicalJson(obj[k])).join(',') + '}';
}

export function encryptRecord(dek: Buffer, uuid: string, rev: number, contact: unknown) {
  const key = deriveRecordKey(dek, uuid);
  const plaintext = pad(Buffer.from(canonicalJson(contact), 'utf8'));
  const sealed = seal(key, plaintext, recordAad(uuid, rev, SCHEMA_VERSION));
  return {
    nonce: sealed.subarray(0, 12).toString('base64'),
    ciphertext: sealed.subarray(12).toString('base64'),
  };
}

export function decryptRecord(dek: Buffer, uuid: string, rev: number, nonceB64: string, ciphertextB64: string): unknown {
  const key = deriveRecordKey(dek, uuid);
  const blob = Buffer.concat([Buffer.from(nonceB64, 'base64'), Buffer.from(ciphertextB64, 'base64')]);
  return JSON.parse(unpad(open(key, blob, recordAad(uuid, rev, SCHEMA_VERSION))).toString('utf8'));
}

// ---------- 盲索引（只存在本机，用于按号码查联系人） ----------

export function deriveIndexKey(dek: Buffer, salt: Buffer): Buffer {
  return hkdf(dek, salt, INFO_INDEX);
}

/** 号码先归一化成 E.164 再做 HMAC，否则 +86138... 和 138... 会索引到不同值。 */
export function blindIndex(indexKey: Buffer, normalizedNumber: string): string {
  return createHmac('sha256', indexKey).update(normalizedNumber, 'utf8').digest('hex').slice(0, 32);
}

// ---------- 头像 blob ----------

export function blobId(dek: Buffer, plaintext: Buffer): string {
  const idKey = hkdf(dek, Buffer.alloc(0), INFO_BLOB_ID);
  return createHmac('sha256', idKey).update(plaintext).digest('hex');
}

export function sealBlob(dek: Buffer, plaintext: Buffer) {
  const hash = blobId(dek, plaintext);
  const key = hkdf(dek, Buffer.from(hash, 'hex'), INFO_BLOB_KEY);
  const sealed = seal(key, plaintext, Buffer.from(hash, 'hex'));
  return { hash, nonce: sealed.subarray(0, 12).toString('base64'), ciphertext: sealed.subarray(12).toString('base64') };
}

export function openBlob(dek: Buffer, hash: string, nonceB64: string, ciphertextB64: string): Buffer {
  const key = hkdf(dek, Buffer.from(hash, 'hex'), INFO_BLOB_KEY);
  const blob = Buffer.concat([Buffer.from(nonceB64, 'base64'), Buffer.from(ciphertextB64, 'base64')]);
  return open(key, blob, Buffer.from(hash, 'hex'));
}

// ---------- 完整保险库初始化 ----------

export async function createVault(passphrase: string) {
  const salt = randomBytes(16);
  const masterKey = await deriveMasterKey(passphrase, salt);
  const dek = randomBytes(32);
  const recoveryKey = randomBytes(32);
  return {
    salt,
    dek,
    recoveryKey,
    recoveryCode: formatRecoveryCode(recoveryKey),
    authSecret: deriveAuthSecret(masterKey, salt),
    recoveryAuthSecret: deriveRecoveryAuthSecret(recoveryKey, salt),
    dekWrapPassword: wrapDek(deriveKek(masterKey, salt), dek, false),
    dekWrapRecovery: wrapDek(deriveRecoveryKek(recoveryKey, salt), dek, true),
  };
}

// ---------- 列表条目 id ----------

/**
 * 列表条目的 id 由内容确定性推导，不随机生成。
 * 这样两台设备各自录入同一个号码会算出同一个 id，三方合并时自然去重，
 * 也省掉了「per-item uuid 存哪」的问题（commons 的 LocalContact 表加不了列）。
 *
 * identity 用的是「决定这条是不是同一条」的那部分：
 *   phones    → 归一化后的 E.164 号码
 *   emails    → 小写邮箱地址
 *   addresses → 完整地址字符串
 *   events    → type + 日期
 *   websites  → 网址
 *   ims       → type + 账号
 *   groups    → 组名
 * label / type 这类可改的附属字段不进 identity，改它们 id 不变。
 */
export function itemId(list: string, identity: string): string {
  // 分隔符是 NUL（0x00），不是空格。
  // 空格会造成歧义：itemId('phones', 'a b') 和 itemId('phones a', 'b') 会算出同一个值。
  // NUL 在合法的 UTF-8 文本里不可能出现，所以不会撞。
  // 写成显式字节而不是字符串字面量 —— 源码里放一个不可见的 NUL
  // 会让整个文件被当成二进制，diff 和 grep 都看不见它。
  return createHash('sha256')
    .update(Buffer.from(list, 'utf8'))
    .update(Buffer.from([0x00]))
    .update(Buffer.from(identity, 'utf8'))
    .digest('hex')
    .slice(0, 32);
}
