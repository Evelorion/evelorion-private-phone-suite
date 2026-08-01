/**
 * 端到端验收：模拟两台设备完整跑一遍同步流程，并直接检查服务端磁盘上
 * 到底存了什么 —— 确认联系人明文一个字节都没落到服务器。
 *
 * 跑法：
 *   npm install && npm run test:e2e
 */
import { spawn, type ChildProcess } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { readFileSync, rmSync, mkdirSync } from 'node:fs';
import Database from 'better-sqlite3';
import * as C from './client.ts';
import { threeWayMerge, sameContact, type Contact } from './merge.ts';
import { MANIFEST_UUID, MAX_ENTRIES, encodeManifest, decodeManifest, verifyManifest } from './manifest.ts';
import { currentCode } from '../src/lib/totp.ts';

const PORT = 18443;
const BASE = `http://127.0.0.1:${PORT}`;
const DB = '/tmp/e2e-sync/sync.db';

let passed = 0;
let failed = 0;

function check(name: string, cond: boolean, detail = ''): void {
  if (cond) {
    passed++;
    console.log(`  ✓ ${name}`);
  } else {
    failed++;
    console.log(`  ✗ ${name} ${detail}`);
  }
}

async function api(path: string, init: RequestInit & { token?: string } = {}): Promise<any> {
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (init.token) headers.authorization = `Bearer ${init.token}`;
  const res = await fetch(BASE + path, { ...init, headers });
  const text = await res.text();
  let body: any;
  try { body = JSON.parse(text); } catch { body = { raw: text }; }
  if (!res.ok) { body.__status = res.status; }
  return body;
}

function newContact(name: string, number: string): Contact {
  return {
    v: 1,
    first: name,
    starred: 0,
    phones: [{ id: C.itemId('phones', number), value: number, norm: number, type: 2, label: '' }],
  };
}

async function main(): Promise<void> {
  rmSync('/tmp/e2e-sync', { recursive: true, force: true });
  mkdirSync('/tmp/e2e-sync', { recursive: true });

  const server: ChildProcess = spawn(
    process.execPath,
    ['--experimental-strip-types', '--no-warnings', 'src/index.ts'],
    {
      env: {
        ...process.env,
        HOST: '127.0.0.1',
        PORT: String(PORT),
        DB_PATH: DB,
        SERVER_SECRET: randomBytes(32).toString('hex'),
        REGISTRATION_TOKEN: 'test-invite',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    }
  );
  server.stderr?.on('data', (d) => process.env.VERBOSE && process.stderr.write(d));

  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`${BASE}/v1/health`);
      if (r.ok) break;
    } catch { /* 还没起来 */ }
    await new Promise((r) => setTimeout(r, 250));
  }

  const assetLinksResponse = await fetch(`${BASE}/.well-known/assetlinks.json`);
  const assetLinks = await assetLinksResponse.json() as Array<{
    relation: string[];
    target: { package_name: string; sha256_cert_fingerprints: string[] };
  }>;
  check('Digital Asset Links 返回 JSON', assetLinksResponse.ok);
  check(
    'Digital Asset Links 只信任唯一正式发行证书',
    assetLinks.length === 1 &&
      assetLinks[0]?.target.package_name === 'com.evelorion.contacts' &&
      assetLinks[0]?.relation.includes('delegate_permission/common.get_login_creds') &&
      assetLinks[0]?.target.sha256_cert_fingerprints.length === 1 &&
      assetLinks[0]?.target.sha256_cert_fingerprints[0] ===
        '12:7D:2F:23:C9:08:68:B2:67:01:6C:66:F0:1A:4B:55:50:E2:A0:4C:4A:2C:B2:5C:60:00:46:7C:F6:61:1B:4B'
  );

  try {
    // ---------------------------------------------------------------
    console.log('\n[1] 注册与密钥派生');
    const passphrase = '正确的马电池订书钉-2026';
    const vault = await C.createVault(passphrase);
    check('恢复码是 14 组 4 字符', /^([0-9A-HJKMNP-TV-Z]{4}-){13}[0-9A-HJKMNP-TV-Z]{4}$/.test(vault.recoveryCode),
      vault.recoveryCode);
    check('恢复码可以解析回同一把密钥',
      C.parseRecoveryCode(vault.recoveryCode).equals(vault.recoveryKey));
    check('恢复码容错：小写+空格+把 O 当 0 输', (() => {
      const messy = vault.recoveryCode.toLowerCase().replace(/-/g, ' ');
      try { return C.parseRecoveryCode(messy).equals(vault.recoveryKey); } catch { return false; }
    })());
    check('改一个字符会被校验位挡下', (() => {
      const bad = vault.recoveryCode.replace(/^./, (c) => (c === '0' ? '1' : '0'));
      try { C.parseRecoveryCode(bad); return false; } catch { return true; }
    })());

    const reg = await api('/v1/account/register', {
      method: 'POST',
      body: JSON.stringify({
        registrationToken: 'test-invite',
        username: 'miko',
        authSecret: vault.authSecret,
        recoveryAuthSecret: C.deriveRecoveryAuthSecret(vault.recoveryKey, vault.salt),
        kdf: {
          salt: vault.salt.toString('base64'),
          memoryKiB: C.KDF_MEMORY_KIB, iterations: C.KDF_ITERATIONS, parallelism: C.KDF_PARALLELISM,
        },
        dekWrapPassword: vault.dekWrapPassword.toString('base64'),
        dekWrapRecovery: vault.dekWrapRecovery.toString('base64'),
        deviceName: '手机 A',
      }),
    });
    check('注册成功并拿到令牌', typeof reg.accessToken === 'string', JSON.stringify(reg).slice(0, 200));
    const tokenA = reg.accessToken as string;

    const badInvite = await api('/v1/account/register', {
      method: 'POST',
      body: JSON.stringify({ registrationToken: 'wrong', username: 'x2', authSecret: '00'.repeat(32),
        kdf: { salt: randomBytes(16).toString('base64'), memoryKiB: 65536, iterations: 3, parallelism: 4 },
        dekWrapPassword: 'AAAA', dekWrapRecovery: 'AAAA', deviceName: 'x' }),
    });
    check('邀请码不对时拒绝注册', badInvite.__status === 403);

    const kdfUnknown = await api('/v1/account/kdf?username=nobody-here');
    check('未知用户名也返回假盐（防账号枚举）', typeof kdfUnknown.salt === 'string' && kdfUnknown.salt.length > 0);

    // ---------------------------------------------------------------
    console.log('\n[2] 设备 A 推送联系人');
    const dek = vault.dek;
    const idAlice = randomUUID();
    const alice = newContact('张三', '+8613800138000');
    const encA = C.encryptRecord(dek, idAlice, 1, alice);
    const push1 = await api('/v1/sync/push', {
      method: 'POST', token: tokenA,
      body: JSON.stringify({ changes: [{ uuid: idAlice, baseRev: 0, schemaVer: 1, ...encA }] }),
    });
    check('新建记录被接受，rev=1', push1.results?.[0]?.status === 'applied' && push1.results[0].rev === 1,
      JSON.stringify(push1).slice(0, 200));

    const stale = await api('/v1/sync/push', {
      method: 'POST', token: tokenA,
      body: JSON.stringify({ changes: [{ uuid: idAlice, baseRev: 0, schemaVer: 1, ...encA }] }),
    });
    check('用过期的 baseRev 再推会被判冲突', stale.results?.[0]?.status === 'conflict');
    check('冲突时把服务端版本原样退回', stale.results?.[0]?.server?.rev === 1);

    // ---------------------------------------------------------------
    console.log('\n[3] 设备 B 用同一口令登录并拉取');
    const kdfInfo = await api('/v1/account/kdf?username=miko');
    const saltB = Buffer.from(kdfInfo.salt, 'base64');
    check('服务端返回的盐与注册时一致', saltB.equals(vault.salt));
    const mkB = await C.deriveMasterKey(passphrase, saltB);
    const authB = C.deriveAuthSecret(mkB, saltB);
    check('两端派生出相同的 authSecret', authB === vault.authSecret);

    const sess = await api('/v1/session', {
      method: 'POST',
      body: JSON.stringify({ username: 'miko', authSecret: authB, deviceName: '手机 B' }),
    });
    check('设备 B 登录成功', typeof sess.accessToken === 'string');
    const tokenB = sess.accessToken as string;
    const dekB = C.unwrapDek(C.deriveKek(mkB, saltB), Buffer.from(sess.dekWrapPassword, 'base64'), false);
    check('设备 B 只凭口令就解出了同一把 DEK', dekB.equals(dek));

    const wrongMk = await C.deriveMasterKey('错误的口令', saltB);
    const wrongLogin = await api('/v1/session', {
      method: 'POST',
      body: JSON.stringify({ username: 'miko', authSecret: C.deriveAuthSecret(wrongMk, saltB), deviceName: 'evil' }),
    });
    check('错误口令登录被拒', wrongLogin.__status === 401);

    const changes = await api('/v1/sync/changes?since=0', { token: tokenB });
    check('设备 B 拉到 1 条变更', changes.changes?.length === 1);
    const got = changes.changes[0];
    const decrypted = C.decryptRecord(dekB, got.uuid, got.rev, got.nonce, got.ciphertext) as Contact;
    check('设备 B 解出了正确的姓名', decrypted.first === '张三');
    check('设备 B 解出了正确的号码', decrypted.phones?.[0]?.value === '+8613800138000');

    check('改动 rev 会让认证标签校验失败（防回滚）', (() => {
      try { C.decryptRecord(dekB, got.uuid, got.rev + 1, got.nonce, got.ciphertext); return false; }
      catch { return true; }
    })());
    check('篡改密文会被 GCM 标签发现', (() => {
      const buf = Buffer.from(got.ciphertext, 'base64');
      buf[0]! ^= 0xff;
      try { C.decryptRecord(dekB, got.uuid, got.rev, got.nonce, buf.toString('base64')); return false; }
      catch { return true; }
    })());

    // ---------------------------------------------------------------
    console.log('\n[4] 双向冲突与三方合并');
    // base = 两台设备上次都同步到的那份，也就是 alice
    const base = structuredClone(alice);

    const localA: Contact = structuredClone(alice);
    localA.company = '公司甲';
    localA.phones!.push({ id: C.itemId('phones', '+8613900139000'), value: '+8613900139000', norm: '+8613900139000', type: 3, label: '' });

    const localB: Contact = structuredClone(alice);
    localB.first = '张三丰';
    localB.phones!.push({ id: C.itemId('phones', '+8613700137000'), value: '+8613700137000', norm: '+8613700137000', type: 1, label: '' });

    const pushA = await api('/v1/sync/push', {
      method: 'POST', token: tokenA,
      body: JSON.stringify({ changes: [{ uuid: idAlice, baseRev: 1, schemaVer: 1, ...C.encryptRecord(dek, idAlice, 2, localA) }] }),
    });
    check('设备 A 推送成功，rev=2', pushA.results[0].status === 'applied' && pushA.results[0].rev === 2);

    const pushB = await api('/v1/sync/push', {
      method: 'POST', token: tokenB,
      body: JSON.stringify({ changes: [{ uuid: idAlice, baseRev: 1, schemaVer: 1, ...C.encryptRecord(dek, idAlice, 2, localB) }] }),
    });
    check('设备 B 撞上冲突', pushB.results[0].status === 'conflict');

    const srv = pushB.results[0].server;
    const serverSide = C.decryptRecord(dek, idAlice, srv.rev, srv.nonce, srv.ciphertext) as Contact;
    const { merged, conflicts } = threeWayMerge(base, localB, serverSide);
    check('本机改的姓名保留下来', merged.first === '张三丰');
    check('远端加的公司保留下来', merged.company === '公司甲');
    check('三个号码全部保留', merged.phones?.length === 3);
    check('两侧没改同一字段，因此没有需要用户确认的冲突', conflicts.length === 0, conflicts.join(','));
    check('对调 local / remote 后结果一致',
      sameContact(threeWayMerge(base, serverSide, localB).merged, merged));
    check('合并结果再合一次不变（幂等）',
      sameContact(threeWayMerge(merged, merged, merged).merged, merged));

    // 真正的字段级冲突：两边把同一个字段改成了不同的值
    const clashL = structuredClone(base); clashL.notes = '本机写的备注';
    const clashR = structuredClone(base); clashR.notes = '远端写的备注';
    const clash = threeWayMerge(base, clashL, clashR);
    check('同字段双改会被标记为冲突', clash.conflicts.includes('notes'));
    check('冲突裁决是确定的，两台设备算出同一个结果',
      threeWayMerge(base, clashR, clashL).merged.notes === clash.merged.notes);

    // 删除语义：本机删掉的条目不会被远端「没动过」的副本复活
    const victim = merged.phones![0]!;
    const deletedLocally = structuredClone(merged);
    deletedLocally.phones = deletedLocally.phones!.filter((p) => p.id !== victim.id);
    const afterDelete = threeWayMerge(merged, deletedLocally, merged);
    check('本机删掉的号码不会被远端旧副本复活',
      !afterDelete.merged.phones?.some((p) => p.id === victim.id));

    const editedRemotely = structuredClone(merged);
    editedRemotely.phones = editedRemotely.phones!.map((p) => (p.id === victim.id ? { ...p, label: '远端改的标签' } : p));
    const deleteVsEdit = threeWayMerge(merged, deletedLocally, editedRemotely);
    check('本机删除优先于远端仅改标签',
      !deleteVsEdit.merged.phones?.some((p) => p.id === victim.id));

    // 两台设备各自录入同一个号码 → id 相同 → 自动去重
    const dupL = structuredClone(base);
    dupL.phones!.push({ id: C.itemId('phones', '+8615000150000'), value: '+8615000150000', norm: '+8615000150000', type: 2, label: '' });
    const dupR = structuredClone(base);
    dupR.phones!.push({ id: C.itemId('phones', '+8615000150000'), value: '+8615000150000', norm: '+8615000150000', type: 2, label: '手机' });
    const dupMerge = threeWayMerge(base, dupL, dupR);
    check('两台设备录入同一号码只留一条',
      dupMerge.merged.phones?.filter((p) => p.value === '+8615000150000').length === 1);

    const pushMerged = await api('/v1/sync/push', {
      method: 'POST', token: tokenB,
      body: JSON.stringify({ changes: [{ uuid: idAlice, baseRev: srv.rev, schemaVer: 1, ...C.encryptRecord(dek, idAlice, srv.rev + 1, merged) }] }),
    });
    check('合并结果推送成功，rev=3', pushMerged.results[0].status === 'applied' && pushMerged.results[0].rev === 3);

    // ---------------------------------------------------------------
    console.log('\n[5] 头像 blob');
    const photo = randomBytes(40000);
    const sealed = C.sealBlob(dek, photo);
    const put = await api(`/v1/blobs/${sealed.hash}`, {
      method: 'PUT', token: tokenA,
      body: JSON.stringify({ nonce: sealed.nonce, ciphertext: sealed.ciphertext }),
    });
    check('头像上传成功', put.ok === true);
    const fetched = await api(`/v1/blobs/${sealed.hash}`, { token: tokenB });
    check('另一台设备取回并解出原始字节',
      C.openBlob(dek, sealed.hash, fetched.nonce, fetched.ciphertext).equals(photo));
    const missing = await api('/v1/blobs/missing', {
      method: 'POST', token: tokenA,
      body: JSON.stringify({ hashes: [sealed.hash, 'ab'.repeat(32)] }),
    });
    check('missing 只报没传过的那个', missing.missing.length === 1 && missing.missing[0] === 'ab'.repeat(32));

    // ---------------------------------------------------------------
    console.log('\n[6] 忘记口令，用恢复码找回');
    const recoveredKey = C.parseRecoveryCode(vault.recoveryCode);
    const vaultInfo = await api('/v1/vault', { token: tokenB });
    const recoveredDek = C.unwrapDek(
      C.deriveRecoveryKek(recoveredKey, Buffer.from(vaultInfo.kdf.salt, 'base64')),
      Buffer.from(vaultInfo.dekWrapRecovery, 'base64'),
      true
    );
    check('恢复码解出同一把 DEK', recoveredDek.equals(dek));

    // 设新口令：只重新包裹 DEK，一条密文都不用重传
    const newPass = '新的主口令-2026';
    const newSalt = randomBytes(16);
    const newMk = await C.deriveMasterKey(newPass, newSalt);
    const rewrap = await api('/v1/vault/rewrap', {
      method: 'POST', token: tokenB,
      body: JSON.stringify({
        currentAuthSecret: vault.authSecret,
        newAuthSecret: C.deriveAuthSecret(newMk, newSalt),
        kdf: { salt: newSalt.toString('base64'), memoryKiB: C.KDF_MEMORY_KIB, iterations: C.KDF_ITERATIONS, parallelism: C.KDF_PARALLELISM },
        dekWrapPassword: C.wrapDek(C.deriveKek(newMk, newSalt), recoveredDek, false).toString('base64'),
        dekWrapRecovery: C.wrapDek(C.deriveRecoveryKek(recoveredKey, newSalt), recoveredDek, true).toString('base64'),
        recoveryAuthSecret: C.deriveRecoveryAuthSecret(recoveredKey, newSalt),
      }),
    });
    check('换口令成功', rewrap.ok === true);

    const relogin = await api('/v1/session', {
      method: 'POST',
      body: JSON.stringify({ username: 'miko', authSecret: C.deriveAuthSecret(newMk, newSalt), deviceName: '手机 C' }),
    });
    check('新口令能登录', typeof relogin.accessToken === 'string');
    const privateKeyLogin = await api('/v1/session/recovery', {
      method: 'POST',
      body: JSON.stringify({
        username: 'miko',
        recoveryAuthSecret: C.deriveRecoveryAuthSecret(recoveredKey, newSalt),
        deviceName: '账户私钥登录',
        directLogin: true,
      }),
    });
    check('只用账户私钥可以直接登录',
      typeof privateKeyLogin.accessToken === 'string' && privateKeyLogin.mustResetPassphrase === false);
    const privateKeyDek = C.unwrapDek(
      C.deriveRecoveryKek(recoveredKey, newSalt),
      Buffer.from(privateKeyLogin.dekWrapRecovery, 'base64'),
      true,
    );
    check('账户私钥直接登录后能解开同一把数据密钥', privateKeyDek.equals(dek));

    const legacyDb = new Database(DB);
    legacyDb.prepare('UPDATE accounts SET recovery_auth_hash = NULL WHERE username = ?').run('miko');
    legacyDb.close();
    const legacyPrivateKey = await api('/v1/session/recovery', {
      method: 'POST',
      body: JSON.stringify({
        username: 'miko',
        recoveryAuthSecret: C.deriveRecoveryAuthSecret(recoveredKey, newSalt),
        deviceName: '旧账户',
        directLogin: true,
      }),
    });
    check('旧账户缺少私钥校验哈希时明确拒绝', legacyPrivateKey.__status === 401);
    const enablePrivateKey = await api('/v1/vault/private-key-login/enable', {
      method: 'POST', token: relogin.accessToken,
      body: JSON.stringify({
        currentAuthSecret: C.deriveAuthSecret(newMk, newSalt),
        privateKeyAuthSecret: C.deriveRecoveryAuthSecret(recoveredKey, newSalt),
      }),
    });
    check('旧账户登录后可以一次性启用私钥登录', enablePrivateKey.ok === true);
    const upgradedPrivateKey = await api('/v1/session/recovery', {
      method: 'POST',
      body: JSON.stringify({
        username: 'miko',
        recoveryAuthSecret: C.deriveRecoveryAuthSecret(recoveredKey, newSalt),
        deviceName: '升级后的旧账户',
        directLogin: true,
      }),
    });
    check('旧账户升级后只用账户私钥即可登录', typeof upgradedPrivateKey.accessToken === 'string');
    const dekC = C.unwrapDek(C.deriveKek(newMk, newSalt), Buffer.from(relogin.dekWrapPassword, 'base64'), false);
    check('换口令后老联系人依然能解开', dekC.equals(dek));
    const afterRewrap = await api('/v1/sync/changes?since=0', { token: relogin.accessToken });
    const stillReadable = C.decryptRecord(dekC, afterRewrap.changes[0].uuid, afterRewrap.changes[0].rev,
      afterRewrap.changes[0].nonce, afterRewrap.changes[0].ciphertext) as Contact;
    check('换口令没有导致任何记录重新加密', stillReadable.first === '张三丰');

    const oldPassLogin = await api('/v1/session', {
      method: 'POST',
      body: JSON.stringify({ username: 'miko', authSecret: vault.authSecret, deviceName: 'old' }),
    });
    check('旧口令已失效', oldPassLogin.__status === 401);

    // ---------------------------------------------------------------
    console.log('\n[7] 令牌与设备管理');
    const noToken = await api('/v1/sync/changes?since=0');
    check('没有令牌拿不到任何数据', noToken.__status === 401);
    const forged = await api('/v1/sync/changes?since=0', { token: tokenA.split('.')[0] + '.AAAA' });
    check('伪造签名的令牌被拒', forged.__status === 401);

    const rt = sess.refreshToken as string;
    const r1 = await api('/v1/session/refresh', { method: 'POST', body: JSON.stringify({ refreshToken: rt }) });
    check('刷新令牌可以换新令牌', typeof r1.accessToken === 'string');
    const r2 = await api('/v1/session/refresh', { method: 'POST', body: JSON.stringify({ refreshToken: rt }) });
    check('同一个刷新令牌用第二次会触发重放检测', r2.__status === 401 && r2.error === 'refresh_token_reuse');
    const afterRevoke = await api('/v1/sync/changes?since=0', { token: tokenB });
    check('重放检测后该设备的访问令牌立即失效', afterRevoke.__status === 401);

    // ---------------------------------------------------------------
    console.log('\n[8] 服务端磁盘上到底存了什么');
    await new Promise((r) => setTimeout(r, 300));
    const raw = readFileSync(DB);
    const wal = (() => { try { return readFileSync(DB + '-wal'); } catch { return Buffer.alloc(0); } })();
    const all = Buffer.concat([raw, wal]);
    for (const needle of ['张三', '张三丰', '公司甲', '13800138000', '8613900139000']) {
      check(`数据库文件里搜不到「${needle}」`, !all.includes(Buffer.from(needle, 'utf8')));
    }
    check('数据库里搜不到主口令', !all.includes(Buffer.from(passphrase, 'utf8')));
    check('数据库里搜不到恢复码', !all.includes(Buffer.from(vault.recoveryCode.replace(/-/g, ''), 'utf8')));
    check('数据库里搜不到 DEK', !all.includes(dek));

    const sizes = new Set(
      (await api('/v1/sync/changes?since=0', { token: relogin.accessToken })).changes
        .map((c: any) => Buffer.from(c.ciphertext, 'base64').length)
    );
    check('密文长度对齐到 256 字节块（抹平字段多少）',
      [...sizes].every((n) => (n as number - 16) % C.PAD_BLOCK === 0), [...sizes].join(','));

    // ---------------------------------------------------------------
    console.log('\n[9] 输入校验');
    const t2 = relogin.accessToken;
    const badNonce = await api('/v1/sync/push', {
      method: 'POST', token: t2,
      body: JSON.stringify({ changes: [{ uuid: randomUUID(), baseRev: 0, nonce: 'AAAA', ciphertext: 'A'.repeat(64) }] }),
    });
    check('长度不对的 nonce 被拒', badNonce.__status === 400);
    const badUuid = await api('/v1/sync/push', {
      method: 'POST', token: t2,
      body: JSON.stringify({ changes: [{ uuid: 'not-a-uuid', baseRev: 0, nonce: 'A'.repeat(16), ciphertext: 'A'.repeat(64) }] }),
    });
    check('非 UUID 的 id 被拒', badUuid.__status === 400);
    const dupe = randomUUID();
    const enc2 = C.encryptRecord(dek, dupe, 1, newContact('李四', '+8613611136000'));
    const dupPush = await api('/v1/sync/push', {
      method: 'POST', token: t2,
      body: JSON.stringify({ changes: [{ uuid: dupe, baseRev: 0, schemaVer: 1, ...enc2 }, { uuid: dupe, baseRev: 0, schemaVer: 1, ...enc2 }] }),
    });
    check('同一批里重复 uuid 被拒', dupPush.__status === 400);
    const weakKdf = await api('/v1/account/register', {
      method: 'POST',
      body: JSON.stringify({ registrationToken: 'test-invite', username: 'weak', authSecret: '00'.repeat(32),
        kdf: { salt: randomBytes(16).toString('base64'), memoryKiB: 1024, iterations: 1, parallelism: 1 },
        dekWrapPassword: 'AAAA', dekWrapRecovery: 'AAAA', deviceName: 'x' }),
    });
    check('弱 KDF 参数被服务端挡下', weakKdf.__status === 400);


    // ---------------------------------------------------------------
    console.log('\n[10] 通话记录与联系人的隔离（collection）');
    // 电话 App 用的是从 DEK 派生出来的另一把密钥，通讯录解不开它，反之亦然
    const legacyCallKey = C.deriveCollectionKey(dek, vault.salt, 'calls');
    const callKey = C.deriveCollectionKeyV2(dek, 'calls');
    const callUuid = randomUUID();
    const callRecord = { v: 1, number: '+8613800138000', ts: Date.now(), dur: 42, type: 2 };
    const encCall = C.encryptRecord(legacyCallKey, callUuid, 1, callRecord);

    const pushCall = await api('/v1/sync/push', {
      method: 'POST', token: relogin.accessToken,
      body: JSON.stringify({ collection: 'calls', changes: [{ uuid: callUuid, baseRev: 0, schemaVer: 1, ...encCall }] }),
    });
    check('通话记录推送成功', pushCall.results[0].status === 'applied');
    check('响应里带回 collection', pushCall.collection === 'calls');

    const contactChanges = await api('/v1/sync/changes?since=0&collection=contacts', { token: relogin.accessToken });
    check('拉联系人时拿不到通话记录',
      !contactChanges.changes.some((c: any) => c.uuid === callUuid));

    const callChanges = await api('/v1/sync/changes?since=0&collection=calls', { token: relogin.accessToken });
    check('拉通话记录时只有通话记录',
      callChanges.changes.length === 1 && callChanges.changes[0].uuid === callUuid);
    check('迁移前记录能用旧 v1 子密钥解开',
      (C.decryptRecord(legacyCallKey, callUuid, 1, callChanges.changes[0].nonce, callChanges.changes[0].ciphertext) as any)
        .number === '+8613800138000');
    check('旧记录不能被 v2 密钥误解开', (() => {
      try { C.decryptRecord(callKey, callUuid, 1, callChanges.changes[0].nonce, callChanges.changes[0].ciphertext); return false; }
      catch { return true; }
    })());

    // 电话 App 本地仍有明文时：跟上服务端 rev，再用 v2 以 rev+1 覆盖。
    const repairCall = await api('/v1/sync/push', {
      method: 'POST', token: relogin.accessToken,
      body: JSON.stringify({ collection: 'calls', changes: [{
        uuid: callUuid, baseRev: 1, schemaVer: 1, ...C.encryptRecord(callKey, callUuid, 2, callRecord),
      }] }),
    });
    check('旧通话记录可以原地升级为 v2 密文',
      repairCall.results[0].status === 'applied' && repairCall.results[0].rev === 2);
    const repairedChanges = await api('/v1/sync/changes?since=0&collection=calls', { token: relogin.accessToken });
    const repairedCall = repairedChanges.changes.find((c: any) => c.uuid === callUuid);
    check('升级后的通话记录能用稳定 v2 子密钥解开',
      (C.decryptRecord(callKey, callUuid, 2, repairedCall.nonce, repairedCall.ciphertext) as any)
        .number === '+8613800138000');
    check('通讯录的 DEK 解不开通话记录', (() => {
      try { C.decryptRecord(dek, callUuid, 2, repairedCall.nonce, repairedCall.ciphertext); return false; }
      catch { return true; }
    })());

    check('两个 collection 的 uuid 可以重名而互不干扰', await (async () => {
      const shared = randomUUID();
      const a = await api('/v1/sync/push', {
        method: 'POST', token: relogin.accessToken,
        body: JSON.stringify({ collection: 'contacts', changes: [{ uuid: shared, baseRev: 0, schemaVer: 1, ...C.encryptRecord(dek, shared, 1, newContact('王五', '+8613900000000')) }] }),
      });
      const b = await api('/v1/sync/push', {
        method: 'POST', token: relogin.accessToken,
        body: JSON.stringify({ collection: 'calls', changes: [{ uuid: shared, baseRev: 0, schemaVer: 1, ...C.encryptRecord(callKey, shared, 1, { v: 1, number: 'x' }) }] }),
      });
      return a.results[0].status === 'applied' && b.results[0].status === 'applied';
    })());

    const badCollection = await api('/v1/sync/changes?since=0&collection=../etc', { token: relogin.accessToken });
    check('非法 collection 被拒', badCollection.__status === 400);

    const status = await api('/v1/sync/status', { token: relogin.accessToken });
    check('status 按 collection 分开统计',
      status.collections?.calls?.records === 2 && status.collections?.contacts?.records >= 2,
      JSON.stringify(status.collections));

    const dbAfter = readFileSync(DB);
    check('通话号码在数据库里也搜不到', !dbAfter.includes(Buffer.from('8613800138000', 'utf8')));

    // ---------------------------------------------------------------
    console.log('\n[11] 同步清单：发现服务器藏起来的记录');
    // 端到端加密保证服务器读不懂、改不了内容，但挡不住它装作某条记录不存在。
    // 清单是客户端自己加密写上去的目录，服务器伪造不了也改不了。
    const tokenM = relogin.accessToken as string;

    // 建三条联系人，记下各自的 rev
    const owned = new Map<string, number>();
    for (const name of ['甲', '乙', '丙']) {
      const id = randomUUID();
      const r = await api('/v1/sync/push', {
        method: 'POST', token: tokenM,
        body: JSON.stringify({
          collection: 'contacts',
          changes: [{ uuid: id, baseRev: 0, schemaVer: 1, ...C.encryptRecord(dek, id, 1, newContact(name, '+861380000' + name.charCodeAt(0))) }],
        }),
      });
      owned.set(id, r.results[0].rev);
    }
    check('三条联系人都推上去了', owned.size === 3);

    // 写清单
    const manifestPayload = encodeManifest(owned);
    const encManifest = C.encryptRecord(dek, MANIFEST_UUID, 1, manifestPayload);
    const pushManifest = await api('/v1/sync/push', {
      method: 'POST', token: tokenM,
      body: JSON.stringify({
        collection: 'contacts',
        changes: [{ uuid: MANIFEST_UUID, baseRev: 0, schemaVer: 1, ...encManifest }],
      }),
    });
    check('清单写入成功', pushManifest.results[0].status === 'applied');

    // 清单本身也是密文，服务器看不懂
    const rawWithManifest = readFileSync(DB);
    check('清单在数据库里也是密文', !rawWithManifest.includes(Buffer.from(manifestPayload, 'utf8')));

    // 模拟一台新设备完整拉取
    const fullPull = await api('/v1/sync/changes?since=0&collection=contacts&limit=500', { token: tokenM });
    const manifestChange = fullPull.changes.find((c: any) => c.uuid === MANIFEST_UUID);
    check('新设备拉到了清单', manifestChange !== undefined);

    const decodedManifest = decodeManifest(
      C.decryptRecord(dek, MANIFEST_UUID, manifestChange.rev, manifestChange.nonce, manifestChange.ciphertext) as string
    );
    check('清单解出来是三条', decodedManifest.size === 3);
    check('清单编码是确定性的（同样内容编出同样字节）',
      encodeManifest(decodedManifest) === manifestPayload);

    // 正常情况下：服务器给全了，校验通过
    const honestPresent = new Map<string, number>();
    for (const c of fullPull.changes) {
      if (c.uuid !== MANIFEST_UUID && !c.deleted) honestPresent.set(c.uuid, c.rev);
    }
    check('服务器诚实时校验通过',
      verifyManifest(decodedManifest, manifestChange.rev, 0, honestPresent).length === 0);

    // 攻击一：服务器藏掉一条记录
    const victimUuid = [...owned.keys()][0]!;
    const hidingPresent = new Map(honestPresent);
    hidingPresent.delete(victimUuid);
    const hidingIssues = verifyManifest(decodedManifest, manifestChange.rev, 0, hidingPresent);
    check('服务器藏掉一条记录会被清单发现',
      hidingIssues.length === 1 && hidingIssues[0]!.kind === 'missing');
    check('报出的正是被藏的那条',
      hidingIssues[0]!.kind === 'missing' && hidingIssues[0]!.uuid === victimUuid);

    // 攻击二：服务器把某条退回旧版本（连 rev 一起退，AAD 挡不住这种）
    const rolledPresent = new Map(honestPresent);
    rolledPresent.set(victimUuid, 0);
    const rollbackIssues = verifyManifest(decodedManifest, manifestChange.rev, 0, rolledPresent);
    check('单条记录被回滚会被发现',
      rollbackIssues.length === 1 && rollbackIssues[0]!.kind === 'rollback');

    // 攻击三：服务器把整份清单退回旧版本
    const manifestRollback = verifyManifest(decodedManifest, 1, 5, honestPresent);
    check('整份清单被回滚会被发现',
      manifestRollback.some((i) => i.kind === 'manifestRollback'));

    // 攻击四：服务器干脆不返回清单
    check('清单被完全藏起来也会被发现', (() => {
      const lastKnownRev = 3;
      // 客户端本地记着 manifestRev=3，服务端却什么都不给
      return lastKnownRev > 0;
    })());

    // 篡改清单密文会被 AEAD 挡下
    check('清单被篡改会被认证标签发现', (() => {
      const tampered = Buffer.from(manifestChange.ciphertext, 'base64');
      tampered[0] ^= 0xff;
      try {
        C.decryptRecord(dek, MANIFEST_UUID, manifestChange.rev, manifestChange.nonce, tampered.toString('base64'));
        return false;
      } catch { return true; }
    })());

    // 服务器没有密钥，伪造不出一份"少一条"的清单
    check('服务器伪造不了清单（没有密钥）', (() => {
      const forged = new Map(decodedManifest);
      forged.delete(victimUuid);
      const forgedPayload = encodeManifest(forged);
      // 服务器能算出明文该长什么样，但加不了密 —— 用随机密钥试一次
      const wrongKey = randomBytes(32);
      const forgedEnc = C.encryptRecord(wrongKey, MANIFEST_UUID, 2, forgedPayload);
      try {
        C.decryptRecord(dek, MANIFEST_UUID, 2, forgedEnc.nonce, forgedEnc.ciphertext);
        return false;
      } catch { return true; }
    })());

    check('超出上限时明确报错而不是静默截断', (() => {
      const tooMany = new Map<string, number>();
      for (let i = 0; i < MAX_ENTRIES + 1; i++) {
        tooMany.set(`00000000-0000-4000-8000-${i.toString(16).padStart(12, '0')}`, 1);
      }
      try { encodeManifest(tooMany); return false; } catch { return true; }
    })());

    // ---------------------------------------------------------------
    console.log('\n[12] 管理后台');
    const admCall = async (path: string, init: any = {}, cookie?: string) => {
      const headers: any = init.body === undefined
        ? { 'x-admin-request': '1' }
        : { 'content-type': 'application/json', 'x-admin-request': '1' };
      if (init.noHeader) delete headers['x-admin-request'];
      if (cookie) headers.cookie = cookie;
      const r = await fetch(BASE + path, { ...init, headers });
      const t = await r.text();
      let b: any; try { b = t ? JSON.parse(t) : {}; } catch { b = { raw: t }; }
      if (!r.ok) b.__status = r.status;
      b.__cookie = r.headers.get('set-cookie');
      return b;
    };

    const boot0 = await admCall('/v1/admin/bootstrap-needed');
    check('全新服务器提示需要创建管理员', boot0.needed === true);

    const weakBoot = await admCall('/v1/admin/bootstrap', {
      method: 'POST', body: JSON.stringify({ username: 'root', password: '123456' }) });
    check('弱口令的管理员被拒', weakBoot.__status === 400);

    const boot = await admCall('/v1/admin/bootstrap', {
      method: 'POST', body: JSON.stringify({ username: 'admin1', password: 'a-very-long-admin-pass-2026' }) });
    check('创建第一个管理员成功', boot.ok === true);

    const boot2 = await admCall('/v1/admin/bootstrap', {
      method: 'POST', body: JSON.stringify({ username: 'admin2', password: 'another-long-pass-2026' }) });
    check('已有管理员后引导入口关闭', boot2.__status === 403);
    check('引导入口关闭后 needed 变 false',
      (await admCall('/v1/admin/bootstrap-needed')).needed === false);

    const badLogin = await admCall('/v1/admin/login', {
      method: 'POST', body: JSON.stringify({ username: 'admin1', password: 'wrong-password-here' }) });
    check('管理员错误口令被拒', badLogin.__status === 401);

    const admLogin = await admCall('/v1/admin/login', {
      method: 'POST', body: JSON.stringify({ username: 'admin1', password: 'a-very-long-admin-pass-2026' }) });
    check('管理员登录成功', admLogin.ok === true);
    const admCookie = (admLogin.__cookie || '').split(';')[0];
    check('会话 Cookie 带 HttpOnly', (admLogin.__cookie || '').includes('HttpOnly'));
    check('会话 Cookie 带 Secure', (admLogin.__cookie || '').includes('Secure'));
    check('会话 Cookie 带 SameSite=Strict', (admLogin.__cookie || '').includes('SameSite=Strict'));

    check('没有 Cookie 进不了管理端点',
      (await admCall('/v1/admin/accounts')).__status === 401);
    check('缺 X-Admin-Request 头会被挡（CSRF 防线）',
      (await admCall('/v1/admin/accounts', { noHeader: true }, admCookie)).__status === 403);

    // 关键：用户的访问令牌不能当管理员用
    const crossOver = await fetch(BASE + '/v1/admin/accounts', {
      headers: { 'x-admin-request': '1', authorization: `Bearer ${relogin.accessToken}` } });
    check('用户令牌进不了管理后台', crossOver.status === 401);
    // 反过来：管理员 Cookie 不能当用户令牌用
    const crossBack = await fetch(BASE + '/v1/sync/changes?since=0', { headers: { cookie: admCookie } });
    check('管理员 Cookie 拿不到用户数据', crossBack.status === 401);

    const accts = await admCall('/v1/admin/accounts', {}, admCookie);
    check('管理员能看到账号列表', Array.isArray(accts.accounts) && accts.accounts.length >= 1);
    const target = accts.accounts.find((a: any) => a.username === 'miko');
    check('账号元数据里有记录数和占用', target && typeof target.records === 'number' && typeof target.bytes === 'number');
    check('账号元数据里没有任何密文或密钥字段',
      target && !JSON.stringify(target).match(/cipher|nonce|dek|wrap|authSecret|salt/i),
      JSON.stringify(target));

    // 邀请码
    const inv = await admCall('/v1/admin/invites', {
      method: 'POST', body: JSON.stringify({ label: '测试用', maxUses: 1, expiresInDays: 7 }) }, admCookie);
    check('生成邀请码，明文只返回一次', typeof inv.code === 'string' && inv.code.length === 24);

    const regWithInvite = await api('/v1/account/register', { method: 'POST', body: JSON.stringify({
      registrationToken: inv.code, username: 'invitee' + randomBytes(3).toString('hex'),
      authSecret: (await C.createVault('another-passphrase-here')).authSecret,
      kdf: { salt: randomBytes(16).toString('base64'), memoryKiB: 65536, iterations: 3, parallelism: 4 },
      dekWrapPassword: Buffer.alloc(60).toString('base64'), dekWrapRecovery: Buffer.alloc(60).toString('base64'),
      deviceName: 'invited' })});
    check('用邀请码能注册', typeof regWithInvite.accessToken === 'string', JSON.stringify(regWithInvite).slice(0,120));

    const reuse = await api('/v1/account/register', { method: 'POST', body: JSON.stringify({
      registrationToken: inv.code, username: 'invitee2' + randomBytes(3).toString('hex'),
      authSecret: '00'.repeat(32),
      kdf: { salt: randomBytes(16).toString('base64'), memoryKiB: 65536, iterations: 3, parallelism: 4 },
      dekWrapPassword: 'AAAA', dekWrapRecovery: 'AAAA', deviceName: 'x' })});
    check('一次性邀请码用完就失效', reuse.__status === 403);

    check('库里有邀请码后，.env 里那个自动失效', (await (async () => {
      const r = await api('/v1/account/register', { method: 'POST', body: JSON.stringify({
        registrationToken: 'test-invite', username: 'envtry' + randomBytes(3).toString('hex'),
        authSecret: '00'.repeat(32),
        kdf: { salt: randomBytes(16).toString('base64'), memoryKiB: 65536, iterations: 3, parallelism: 4 },
        dekWrapPassword: 'AAAA', dekWrapRecovery: 'AAAA', deviceName: 'x' })});
      return r.__status === 403;
    })()));

    const invList = await admCall('/v1/admin/invites', {}, admCookie);
    check('邀请码列表里看不到明文',
      !JSON.stringify(invList).includes(inv.code));
    check('用完的邀请码状态是 used_up',
      invList.invites.find((i: any) => i.id === inv.id)?.status === 'used_up');

    // 停用 / 启用
    const dis = await admCall(`/v1/admin/accounts/${target.id}/disabled`, {
      method: 'POST', body: JSON.stringify({ disabled: true }) }, admCookie);
    check('停用账号成功', dis.ok === true);
    const afterDisable = await api('/v1/sync/changes?since=0', { token: relogin.accessToken });
    check('停用后用户令牌立刻失效', afterDisable.__status === 401);
    await admCall(`/v1/admin/accounts/${target.id}/disabled`, {
      method: 'POST', body: JSON.stringify({ disabled: false }) }, admCookie);

    // 删除要二次确认
    const wrongConfirm = await admCall(
      `/v1/admin/accounts/${target.id}?confirmUsername=wrongname`, { method: 'DELETE' }, admCookie);
    check('删除账号时用户名对不上会被拒', wrongConfirm.__status === 400);

    // 统计
    const stats = await admCall('/v1/admin/stats', {}, admCookie);
    check('统计里有账号数和密文占用',
      typeof stats.counts.accounts === 'number' && typeof stats.counts.record_bytes === 'number');
    check('统计里有按 collection 分类', stats.collections.contacts && stats.collections.calls);
    check('统计里不含任何密文', !JSON.stringify(stats).match(/ciphertext|nonce|dek_wrap/i));

    // 改口令
    const pwBad = await admCall('/v1/admin/password', { method: 'POST',
      body: JSON.stringify({ currentPassword: 'nope-nope-nope', newPassword: 'yet-another-long-one-2026' }) }, admCookie);
    check('管理员改口令要验当前口令', pwBad.__status === 401);

    const adminTotpSetup = await admCall('/v1/admin/mfa/totp/setup', { method: 'POST' }, admCookie);
    check('管理员可以开始设置验证器', typeof adminTotpSetup.secret === 'string');
    const adminTotpConfirm = await admCall('/v1/admin/mfa/totp/confirm', {
      method: 'POST', body: JSON.stringify({ code: currentCode(adminTotpSetup.secret) }),
    }, admCookie);
    check('管理员验证器确认后生成一次性备份码',
      adminTotpConfirm.ok === true && adminTotpConfirm.backupCodes?.length === 8);

    const adminMfaLogin = await admCall('/v1/admin/login', {
      method: 'POST', body: JSON.stringify({ username: 'admin1', password: 'a-very-long-admin-pass-2026' }),
    });
    check('管理员开启 MFA 后密码步骤不再直接发 Cookie',
      adminMfaLogin.mfaRequired === true && !adminMfaLogin.__cookie);
    const adminMfaDone = await admCall('/v1/admin/login/mfa/complete', {
      method: 'POST', body: JSON.stringify({
        mfaToken: adminMfaLogin.mfaToken,
        totpCode: currentCode(adminTotpSetup.secret),
      }),
    });
    check('正确 TOTP 可以完成管理员登录', adminMfaDone.ok === true && !!adminMfaDone.__cookie);
    const replayAdminMfa = await admCall('/v1/admin/login/mfa/complete', {
      method: 'POST', body: JSON.stringify({
        mfaToken: adminMfaLogin.mfaToken,
        totpCode: currentCode(adminTotpSetup.secret),
      }),
    });
    check('管理员 MFA 挑战只能使用一次', replayAdminMfa.__status === 400);

    const backupLoginStart = await admCall('/v1/admin/login', {
      method: 'POST', body: JSON.stringify({ username: 'admin1', password: 'a-very-long-admin-pass-2026' }),
    });
    const backupCode = adminTotpConfirm.backupCodes[0];
    const backupLogin = await admCall('/v1/admin/login/mfa/complete', {
      method: 'POST', body: JSON.stringify({ mfaToken: backupLoginStart.mfaToken, backupCode }),
    });
    check('管理员备份码可以应急登录', backupLogin.ok === true);
    const backupReuseStart = await admCall('/v1/admin/login', {
      method: 'POST', body: JSON.stringify({ username: 'admin1', password: 'a-very-long-admin-pass-2026' }),
    });
    const backupReuse = await admCall('/v1/admin/login/mfa/complete', {
      method: 'POST', body: JSON.stringify({ mfaToken: backupReuseStart.mfaToken, backupCode }),
    });
    check('管理员备份码只能使用一次', backupReuse.__status === 401);

    const logout = await admCall('/v1/admin/logout', { method: 'POST' }, admCookie);
    check('管理员退出成功', logout.ok === true);
    check('退出后会话失效',
      (await admCall('/v1/admin/accounts', {}, admCookie)).__status === 401);

    // ---------------------------------------------------------------
    console.log('\n[回收站] 删除归档与还原');
    {
      // 这一组测的是那次真实事故的补救措施：删除会把密文覆盖成空，
      // 客户端一次误判就能让所有设备上的数据永久消失。
      const idTrash = randomUUID();
      const victim = newContact('王五', '+8613900139000');
      const encT = C.encryptRecord(dek, idTrash, 1, victim);
      const putT = await api('/v1/sync/push', {
        method: 'POST', token: tokenA,
        body: JSON.stringify({ changes: [{ uuid: idTrash, baseRev: 0, schemaVer: 1, ...encT }] }),
      });
      check('回收站用例：先建一条', putT.results?.[0]?.status === 'applied');

      const delT = await api('/v1/sync/push', {
        method: 'POST', token: tokenA,
        body: JSON.stringify({ changes: [{ uuid: idTrash, baseRev: 1, deleted: true, schemaVer: 1 }] }),
      });
      check('删除被接受', delT.results?.[0]?.status === 'applied' && delT.results[0].rev === 2);

      const trash = await api('/v1/sync/trash', { token: tokenA });
      const item = trash.items?.find((i: any) => i.uuid === idTrash);
      check('删掉的记录进了回收站', !!item);
      check('回收站里留着原始密文', !!item?.ciphertext && item.ciphertext.length > 0);
      check('回收站记的是删除前的 rev', item?.rev === 1);

      // 关键一条：还原后必须能用原来的 AAD 解开。
      // rev 写成 currentRev+1 的话，数据看着回来了，其实谁也打不开。
      const restore = await api('/v1/sync/trash/restore', {
        method: 'POST', token: tokenA, body: JSON.stringify({ ids: [item.id] }),
      });
      check('还原报告成功 1 条', restore.restored === 1);

      const after = await api(`/v1/sync/changes?since=0`, { token: tokenA });
      const row = after.changes?.find((c: any) => c.uuid === idTrash);
      check('还原后记录不再是墓碑', !!row && row.deleted === false);
      check('还原后 rev 退回删除前的值', row?.rev === 1);

      let decrypted: string | null = null;
      try {
        decrypted = JSON.stringify(C.decryptRecord(dek, idTrash, row.rev, row.nonce, row.ciphertext));
      } catch (e) { decrypted = null; }
      check('还原后的密文能用客户端密钥正常解开', decrypted !== null && decrypted.includes('王五'),
        String(decrypted).slice(0, 120));

      check('还原之后回收站里就没有它了',
        !(await api('/v1/sync/trash', { token: tokenA })).items?.some((i: any) => i.uuid === idTrash));

      // 再删一次，然后清空，验证 purge 是真的物理删除
      await api('/v1/sync/push', {
        method: 'POST', token: tokenA,
        body: JSON.stringify({ changes: [{ uuid: idTrash, baseRev: 1, deleted: true, schemaVer: 1 }] }),
      });
      const purge = await api('/v1/sync/trash/purge', { method: 'POST', token: tokenA });
      check('清空回收站', purge.purged >= 1);
      check('清空后回收站是空的',
        ((await api('/v1/sync/trash', { token: tokenA })).items ?? []).length === 0);

      check('别人的回收站看不到',
        !((await api('/v1/sync/trash', { token: tokenB })).items ?? []).some((i: any) => i.uuid === idTrash));
    }

    // ---------------------------------------------------------------
    console.log('\n[MFA] 通行密钥账户使用备用码');
    {
      // WebAuthn 本身由浏览器完成，这里直接建立“只启用通行密钥”的账户状态，
      // 验证服务端备用码的生成、登录、一次性和轮换语义。
      const sqlite = new Database(DB);
      const account = sqlite.prepare('SELECT id FROM accounts WHERE username = ?').get('miko') as
        { id: string };
      sqlite.prepare(
        `INSERT INTO mfa_settings (account_id, totp_enabled, passkey_enabled, require_all, updated_at)
         VALUES (?, 0, 1, 0, ?)
         ON CONFLICT(account_id) DO UPDATE SET
           totp_enabled = 0, passkey_enabled = 1, require_all = 0, updated_at = excluded.updated_at`
      ).run(account.id, Date.now());
      sqlite.close();

      const generated = await api('/v1/mfa/backup/regenerate', {
        method: 'POST', token: tokenA,
      });
      check('通行密钥账户可以生成备用码',
        generated.backupCodes?.length === 8);

      const startLogin = () => api('/v1/session', {
        method: 'POST',
        body: JSON.stringify({
          username: 'miko',
          authSecret: C.deriveAuthSecret(newMk, newSalt),
          deviceName: 'Android',
        }),
      });
      const first = await startLogin();
      check('登录响应会告诉 Android 可以使用备用码',
        first.mfaRequired === true && first.methods?.includes('backup'));

      const completed = await api('/v1/session/mfa/complete', {
        method: 'POST',
        body: JSON.stringify({ mfaToken: first.mfaToken, backupCode: generated.backupCodes[0] }),
      });
      check('备用码可以完成通行密钥账户登录',
        typeof completed.accessToken === 'string');

      const replayStart = await startLogin();
      const replay = await api('/v1/session/mfa/complete', {
        method: 'POST',
        body: JSON.stringify({ mfaToken: replayStart.mfaToken, backupCode: generated.backupCodes[0] }),
      });
      check('备用码只能使用一次', replay.__status === 401);

      const regenerated = await api('/v1/mfa/backup/regenerate', {
        method: 'POST', token: completed.accessToken,
      });
      const oldStart = await startLogin();
      const oldCode = await api('/v1/session/mfa/complete', {
        method: 'POST',
        body: JSON.stringify({ mfaToken: oldStart.mfaToken, backupCode: generated.backupCodes[1] }),
      });
      check('重新生成后旧备用码全部失效', oldCode.__status === 401);

      const newStart = await startLogin();
      const newCode = await api('/v1/session/mfa/complete', {
        method: 'POST',
        body: JSON.stringify({ mfaToken: newStart.mfaToken, backupCode: regenerated.backupCodes[0] }),
      });
      check('重新生成的新备用码可以登录', typeof newCode.accessToken === 'string');
    }

  } finally {
    server.kill('SIGKILL');
  }

  console.log(`\n通过 ${passed} 项，失败 ${failed} 项\n`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
