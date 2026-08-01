/**
 * 在 Node 里跑一遍浏览器端的 crypto.js。
 * crypto.js 用的是 WebCrypto（Node 22 有）+ 一个 <script> 加载的 Argon2 UMD，
 * 这里把 document/window 垫上，其余代码一行不改地执行 —— 等于把 selftest.html
 * 的核心断言在 CI 里跑了一遍。
 */
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const sandbox = { window: {}, self: {}, TextEncoder, TextDecoder, WebAssembly, console, Uint8Array, Math, Date, Promise,
  atob: (s) => Buffer.from(s, 'base64').toString('binary') };
sandbox.globalThis = sandbox; sandbox.self = sandbox; sandbox.window = sandbox;
vm.createContext(sandbox);
vm.runInContext(readFileSync('node_modules/hash-wasm/dist/argon2.umd.min.js', 'utf8'), sandbox);

globalThis.window = { hashwasm: sandbox.hashwasm };
globalThis.document = { createElement: () => ({}), head: { appendChild() {} } };
globalThis.btoa = (s) => Buffer.from(s, 'binary').toString('base64');
globalThis.atob = (s) => Buffer.from(s, 'base64').toString('binary');

const C = await import(new URL('../web/lib/crypto.js', import.meta.url));
const { threeWayMerge, sameContact } = await import(new URL('../web/lib/merge.js', import.meta.url));
const V = JSON.parse(readFileSync(new URL('../web/vendor/vectors.json', import.meta.url), 'utf8'));

let pass = 0, fail = 0;
const ck = (n, ok, d = '') => { ok ? (pass++, console.log('  ✓ ' + n)) : (fail++, console.log('  ✗ ' + n + ' ' + d)); };

const salt = C.fromHex(V.saltHex), dek = C.fromHex(V.dekHex), rk = C.fromHex(V.recoveryKeyHex), uuid = V.uuid;

console.log('\n[浏览器端 crypto.js 交叉校验]');
const mk = await C.deriveMasterKey('correct horse battery staple', salt);
ck('Argon2id 主密钥', C.toHex(mk) === V.argon2id_masterKeyHex, C.toHex(mk));
ck('KEK', C.toHex(await C.deriveKek(mk, salt)) === V.kekHex);
ck('authSecret', (await C.deriveAuthSecret(mk, salt)) === V.authSecretHex);
ck('恢复码 KEK', C.toHex(await C.deriveRecoveryKek(rk, salt)) === V.recoveryKekHex);
ck('记录密钥', C.toHex(await C.deriveRecordKey(dek, uuid)) === V.recordKeyHex);
ck('盲索引密钥', C.toHex(await C.deriveIndexKey(dek, salt)) === V.indexKeyHex);
ck('collection 子密钥', C.toHex(await C.deriveCollectionKey(dek, salt, 'calls')) === V.collectionKey_calls);
ck('稳定 collection v2 子密钥', C.toHex(await C.deriveCollectionKeyV2(dek, 'calls')) === V.collectionKeyV2_calls);
ck('记录 AAD', C.toHex(C.recordAad(uuid, 7, 1)) === V.recordAadHex);
ck('blob id', (await C.blobId(dek, new TextEncoder().encode('AAAA'))) === V.blobIdOfAAAA);
ck('itemId(phones)', (await C.itemId('phones', '+8613800138000')) === V.itemId_phones_e164);
ck('itemId(groups,中文)', (await C.itemId('groups', '家人')) === V.itemId_groups_family);
ck('恢复码格式', (await C.formatRecoveryCode(rk)) === V.recoveryCode);
ck('恢复码往返', C.equals(await C.parseRecoveryCode(V.recoveryCode), rk));
ck('恢复码容错', C.equals(await C.parseRecoveryCode(V.recoveryCode.toLowerCase().replace(/-/g,' ')), rk));
ck('canonical JSON', C.canonicalJson({b:1,a:[3,{z:null,y:'x'}],starred:false}) === V.canonicalJson);
ck('清单编码', C.encodeManifest({'11111111-2222-3333-4444-555555555555':7,'00000000-0000-4000-8000-000000000001':1}) === V.manifest_twoEntries);

const padded = C.pad(new TextEncoder().encode('hello'));
ck('填充', padded.length === 256 && C.toHex(padded.slice(0,8)) === '68656c6c6f800000');
const contact = { v:1, first:'张三', phones:[{id:await C.itemId('phones','+8613800138000'),value:'+8613800138000',norm:'+8613800138000',type:2,label:''}] };
const enc = await C.encryptRecord(dek, uuid, 3, contact);
ck('加解密往返', C.canonicalJson(await C.decryptRecord(dek, uuid, 3, enc.nonce, enc.ciphertext)) === C.canonicalJson(contact));
ck('密文对齐 256', (C.fromB64(enc.ciphertext).length - 16) % 256 === 0);
let f1=false; try { await C.decryptRecord(dek, uuid, 4, enc.nonce, enc.ciphertext); } catch { f1=true; }
ck('换 rev 解不开（防回滚）', f1);
const t = C.fromB64(enc.ciphertext); t[0]^=0xff;
let f2=false; try { await C.decryptRecord(dek, uuid, 3, enc.nonce, C.toB64(t)); } catch { f2=true; }
ck('篡改被发现', f2);
ck('号码归一化', ['+86 138 0013 8000','+86-138-0013-8000','+86(138)00138000'].every(r=>C.normalizeNumber(r)==='+8613800138000'));

const base = {v:1,first:'张三',company:'旧公司'};
const m = threeWayMerge(base, {...base,first:'张三丰'}, {...base,company:'新公司'});
ck('三方合并保留两侧改动', m.merged.first==='张三丰' && m.merged.company==='新公司');
ck('三方合并对称', sameContact(threeWayMerge(base,{...base,company:'新公司'},{...base,first:'张三丰'}).merged, m.merged));
ck('三方合并幂等', sameContact(threeWayMerge(m.merged,m.merged,m.merged).merged, m.merged));

console.log(`\n通过 ${pass} 项，失败 ${fail} 项`);
process.exit(fail === 0 ? 0 : 1);
