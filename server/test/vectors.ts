/**
 * 固定测试向量。Kotlin 端 CryptoVectorsTest 必须逐字节复现这些值。
 * 只要有一条对不上，两端就解不开彼此的数据 —— 这是最省事的排错手段。
 * 生成：node --experimental-strip-types test/vectors.ts
 */
import * as C from './client.ts';
import { encodeManifest } from './manifest.ts';

const salt = Buffer.from('000102030405060708090a0b0c0d0e0f', 'hex');
const dek = Buffer.from('202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f', 'hex');
const recoveryKey = Buffer.from('404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f', 'hex');
const uuid = '11111111-2222-3333-4444-555555555555';

const out: Record<string, unknown> = {};
out.note = 'Kotlin 端必须复现这些值';
out.saltHex = salt.toString('hex');
out.dekHex = dek.toString('hex');
out.recoveryKeyHex = recoveryKey.toString('hex');
out.uuid = uuid;

const mk = await C.deriveMasterKey('correct horse battery staple', salt);
out.argon2id_masterKeyHex = mk.toString('hex');
out.kekHex = C.deriveKek(mk, salt).toString('hex');
out.authSecretHex = C.deriveAuthSecret(mk, salt);
out.recoveryKekHex = C.deriveRecoveryKek(recoveryKey, salt).toString('hex');
out.recordKeyHex = C.deriveRecordKey(dek, uuid).toString('hex');
out.recordAadHex = C.recordAad(uuid, 7, 1).toString('hex');
out.indexKeyHex = C.deriveIndexKey(dek, salt).toString('hex');
out.blindIndex_plus8613800138000 = C.blindIndex(C.deriveIndexKey(dek, salt), '+8613800138000');
out.recoveryCode = C.formatRecoveryCode(recoveryKey);
out.canonicalJson = C.canonicalJson({ b: 1, a: [3, { z: null, y: 'x' }], starred: false });
out.padHex_5bytes = C.pad(Buffer.from('hello', 'utf8')).subarray(0, 8).toString('hex') + '...len=' +
  C.pad(Buffer.from('hello', 'utf8')).length;
out.blobIdOfAAAA = C.blobId(dek, Buffer.from('AAAA', 'utf8'));
out.itemId_phones_e164 = C.itemId('phones', '+8613800138000');
out.itemId_groups_family = C.itemId('groups', '家人');
out.collectionKey_calls = C.deriveCollectionKey(dek, salt, 'calls').toString('hex');
out.manifest_twoEntries = encodeManifest(new Map([
  ['11111111-2222-3333-4444-555555555555', 7],
  ['00000000-0000-4000-8000-000000000001', 1],
]));

console.log(JSON.stringify(out, null, 2));
