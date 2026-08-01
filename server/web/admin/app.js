/**
 * 管理后台。
 *
 * 这里**没有也不可能有**解密任何用户数据的代码 —— 密钥不在服务器上，
 * 也不在管理员手里。能做的是管账号、发邀请码、看统计。
 */

import { adminApi, ApiError } from '../lib/api.js';
import { passkeySupported, reviveOptions, serializeCredential } from '../lib/mfa.js';

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
};

function toast(msg, isError = false) {
  const t = el('div', 'toast' + (isError ? ' err' : ''), msg);
  document.body.appendChild(t);
  setTimeout(() => t.remove(), isError ? 5200 : 2600);
}

function busy(btn, on, label) {
  btn.disabled = on;
  if (on) { btn.dataset.label = btn.textContent; btn.textContent = label ?? '处理中…'; }
  else if (btn.dataset.label) btn.textContent = btn.dataset.label;
}

const msg = (target, text, cls = 'err') => {
  $(target).innerHTML = '';
  $(target).appendChild(el('div', cls, text));
};

const fmtBytes = (n) =>
  n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB'
  : n < 1073741824 ? (n / 1048576).toFixed(1) + ' MB' : (n / 1073741824).toFixed(2) + ' GB';

const fmtTime = (t) => (t ? new Date(t).toLocaleString('zh-CN') : '—');

const table = (headers, rows) => {
  const t = el('table');
  const hr = el('tr');
  headers.forEach((h) => hr.appendChild(el('th', null, h)));
  t.appendChild(hr);
  for (const cells of rows) {
    const tr = el('tr');
    for (const c of cells) {
      const td = el('td');
      if (c instanceof Node) td.appendChild(c);
      else if (typeof c === 'object' && c?.num !== undefined) { td.className = 'num'; td.textContent = c.num; }
      else td.textContent = c ?? '';
      tr.appendChild(td);
    }
    t.appendChild(tr);
  }
  return t;
};

// ================================================================ 启动

(async function boot() {
  try {
    await adminApi('/v1/admin/me');
    await enterApp();
    return;
  } catch (e) {
    if (!(e instanceof ApiError) || (e.status !== 401 && e.status !== 403)) {
      document.body.innerHTML = `<div class="wrap"><div class="err">无法连接服务器：${e.message}</div></div>`;
      return;
    }
  }
  const { needed } = await adminApi('/v1/admin/bootstrap-needed');
  $(needed ? 'bootstrap' : 'login').classList.remove('hidden');
})();

$('bsBtn').onclick = async () => {
  const u = $('bsUser').value.trim(), p = $('bsPass').value, p2 = $('bsPass2').value;
  if (p !== p2) return msg('bsMsg', '两次输入的口令不一致');
  if (p.length < 12) return msg('bsMsg', '口令至少 12 个字符');
  busy($('bsBtn'), true);
  try {
    await adminApi('/v1/admin/bootstrap', { method: 'POST', body: { username: u, password: p } });
    await adminApi('/v1/admin/login', { method: 'POST', body: { username: u, password: p } });
    $('bootstrap').classList.add('hidden');
    await enterApp();
  } catch (e) {
    msg('bsMsg', e.message);
  } finally {
    busy($('bsBtn'), false);
  }
};

let pendingAdminMfa = null;

$('lgBtn').onclick = async () => {
  busy($('lgBtn'), true, '登录中…');
  try {
    const result = await adminApi('/v1/admin/login', {
      method: 'POST',
      body: { username: $('lgUser').value.trim(), password: $('lgPass').value },
    });
    if (result.mfaRequired) {
      pendingAdminMfa = result;
      $('lgMfa').classList.remove('hidden');
      $('lgPasskeyWrap').classList.toggle('hidden', !result.methods.includes('passkey'));
      $('lgCodeWrap').classList.toggle('hidden', !result.methods.includes('totp') && !result.methods.includes('backup'));
      $('lgPass').value = '';
      return;
    }
    $('lgPass').value = '';
    $('login').classList.add('hidden');
    await enterApp();
  } catch (e) {
    msg('lgMsg', e.code === 'invalid_credentials' ? '用户名或口令不正确' : e.message);
  } finally {
    busy($('lgBtn'), false);
  }
};

async function finishAdminMfa(body) {
  await adminApi('/v1/admin/login/mfa/complete', {
    method: 'POST', body: { mfaToken: pendingAdminMfa.mfaToken, ...body },
  });
  pendingAdminMfa = null;
  $('login').classList.add('hidden');
  await enterApp();
}

async function getAdminPasskeyResponse() {
  const { options } = await adminApi('/v1/admin/login/mfa/options', {
    method: 'POST', body: { mfaToken: pendingAdminMfa.mfaToken },
  });
  if (!options) throw new Error('这个管理员账号没有可用的通行密钥');
  const credential = await navigator.credentials.get({ publicKey: reviveOptions(options) });
  if (!credential) throw new Error('没有完成通行密钥验证');
  return serializeCredential(credential);
}

$('lgPasskey').onclick = async () => {
  if (!pendingAdminMfa || !passkeySupported()) return msg('lgMsg', '当前浏览器不支持通行密钥');
  const btn = $('lgPasskey');
  busy(btn, true, '正在验证…');
  try {
    const body = { passkey: await getAdminPasskeyResponse() };
    if (pendingAdminMfa.requireAll && pendingAdminMfa.methods.includes('totp')) {
      const code = prompt('还需要验证器验证码。请输入当前的 6 位码：');
      if (!code) return;
      body.totpCode = code.trim();
    }
    await finishAdminMfa(body);
  } catch (e) {
    msg('lgMsg', e?.name === 'NotAllowedError' ? '已取消通行密钥验证' : e.message);
  } finally {
    busy(btn, false);
  }
};

$('lgCodeBtn').onclick = async () => {
  if (!pendingAdminMfa) return;
  const code = $('lgCode').value.trim();
  if (!code) return msg('lgMsg', '请输入验证码或备份码');
  const btn = $('lgCodeBtn');
  busy(btn, true, '正在验证…');
  try {
    const isTotp = /^\d{6}$/.test(code.replace(/\s/g, ''));
    const body = isTotp ? { totpCode: code } : { backupCode: code };
    if (isTotp && pendingAdminMfa.requireAll && pendingAdminMfa.methods.includes('passkey')) {
      if (!passkeySupported()) throw new Error('当前浏览器不支持通行密钥，请改用备用码');
      body.passkey = await getAdminPasskeyResponse();
    }
    await finishAdminMfa(body);
  } catch (e) {
    msg('lgMsg', e.message);
  } finally {
    busy(btn, false);
  }
};

$('logoutBtn').onclick = async () => {
  await adminApi('/v1/admin/logout', { method: 'POST' }).catch(() => {});
  location.reload();
};

async function enterApp() {
  const me = await adminApi('/v1/admin/me');
  $('adminName').textContent = me.username;
  $('app').classList.remove('hidden');
  await renderAccounts();
}

document.querySelectorAll('.topbar .tabs button').forEach((b) => {
  b.onclick = async () => {
    document.querySelectorAll('.topbar .tabs button').forEach((x) => x.classList.remove('active'));
    b.classList.add('active');
    const v = b.dataset.view;
    for (const name of ['accounts', 'invites', 'stats', 'security']) {
      $('view' + name[0].toUpperCase() + name.slice(1)).classList.toggle('hidden', v !== name);
    }
    if (v === 'accounts') await renderAccounts();
    if (v === 'invites') await renderInvites();
    if (v === 'stats') await renderStats();
    if (v === 'security') await renderSecurity();
  };
});

// ================================================================ 账号

async function renderAccounts() {
  const box = $('accountTable');
  box.innerHTML = '';
  box.appendChild(el('div', 'empty', '读取中…'));
  try {
    const { accounts } = await adminApi('/v1/admin/accounts');
    box.innerHTML = '';
    if (accounts.length === 0) {
      box.appendChild(el('div', 'empty', '还没有用户账号。去「邀请码」页生成一个邀请码。'));
      return;
    }

    const rows = accounts.map((a) => {
      const st = el('span', 'badge ' + (a.disabled ? 'off' : 'on'), a.disabled ? '已停用' : '正常');

      const acts = el('div', 'row');
      const toggle = el('button', 'sm', a.disabled ? '启用' : '停用');
      toggle.onclick = async () => {
        if (!a.disabled && !confirm(`停用「${a.username}」？\n\n所有设备会在 15 分钟内断开同步。数据保留。`)) return;
        await adminApi(`/v1/admin/accounts/${a.id}/disabled`, { method: 'POST', body: { disabled: !a.disabled } });
        toast(a.disabled ? '已启用' : '已停用');
        renderAccounts();
      };

      const devBtn = el('button', 'sm', `设备 ${a.devices}`);
      devBtn.onclick = () => showDevices(a);

      const del = el('button', 'sm danger', '删除');
      del.onclick = () => confirmDelete(a);

      acts.append(toggle, devBtn, del);

      return [
        a.username, st, { num: a.records }, { num: fmtBytes(a.bytes) },
        fmtTime(a.lastSeenAt), fmtTime(a.createdAt), acts,
      ];
    });

    box.appendChild(table(
      ['用户名', '状态', '记录数', '占用', '最后活跃', '注册时间', ''],
      rows
    ));
  } catch (e) {
    box.innerHTML = '';
    box.appendChild(el('div', 'empty', '读取失败：' + e.message));
  }
}

function confirmDelete(a) {
  const bg = el('div', 'modal-bg');
  const box = el('div', 'modal');
  bg.appendChild(box);
  bg.onclick = (ev) => ev.target === bg && bg.remove();

  box.appendChild(el('h2', null, '删除账号'));
  box.appendChild(el('div', 'err',
    `这会删掉「${a.username}」在服务器上的全部密文（${a.records} 条记录，${fmtBytes(a.bytes)}），不可恢复。` +
    `用户手机上已有的联系人不受影响。`));
  box.appendChild(el('label', null, `输入用户名「${a.username}」确认`));
  const input = el('input');
  input.spellcheck = false;
  box.appendChild(input);

  const acts = el('div', 'actions');
  const ok = el('button', 'danger', '永久删除');
  const cancel = el('button', null, '取消');
  cancel.onclick = () => bg.remove();
  ok.onclick = async () => {
    busy(ok, true, '删除中…');
    try {
      await adminApi(`/v1/admin/accounts/${a.id}?confirmUsername=${encodeURIComponent(input.value)}`,
        { method: 'DELETE' });
      bg.remove();
      toast('已删除');
      renderAccounts();
    } catch (e) {
      busy(ok, false);
      toast(e.code === 'confirm_mismatch' ? '用户名不匹配' : e.message, true);
    }
  };
  acts.append(ok, cancel);
  box.appendChild(acts);
  $('modalRoot').appendChild(bg);
  input.focus();
}

async function showDevices(a) {
  const bg = el('div', 'modal-bg');
  const box = el('div', 'modal');
  bg.appendChild(box);
  bg.onclick = (ev) => ev.target === bg && bg.remove();
  box.appendChild(el('h2', null, `${a.username} 的设备`));

  const holder = el('div');
  holder.appendChild(el('div', 'empty', '读取中…'));
  box.appendChild(holder);

  const draw = async () => {
    const { devices } = await adminApi(`/v1/admin/accounts/${a.id}/devices`);
    holder.innerHTML = '';
    if (devices.length === 0) {
      holder.appendChild(el('div', 'empty', '没有设备'));
      return;
    }
    holder.appendChild(table(['设备', '最后活跃', '状态', ''], devices.map((d) => {
      const st = el('span', 'badge ' + (d.revoked ? 'off' : 'on'), d.revoked ? '已吊销' : '正常');
      const act = el('div');
      if (!d.revoked) {
        const b = el('button', 'sm danger', '吊销');
        b.onclick = async () => {
          if (!confirm(`吊销「${d.name}」？那台设备需要重新登录。`)) return;
          await adminApi(`/v1/admin/devices/${d.id}`, { method: 'DELETE' });
          toast('已吊销');
          draw();
        };
        act.appendChild(b);
      }
      return [d.name, fmtTime(d.lastSeenAt), st, act];
    })));
  };
  await draw();

  const acts = el('div', 'actions');
  const close = el('button', null, '关闭');
  close.onclick = () => bg.remove();
  acts.appendChild(close);
  box.appendChild(acts);
  $('modalRoot').appendChild(bg);
}

// ================================================================ 邀请码

$('invCreate').onclick = async () => {
  const btn = $('invCreate');
  busy(btn, true);
  try {
    const days = $('invDays').value.trim();
    const res = await adminApi('/v1/admin/invites', {
      method: 'POST',
      body: {
        label: $('invLabel').value.trim(),
        maxUses: Number($('invUses').value || 1),
        expiresInDays: days === '' ? null : Number(days),
      },
    });
    $('invResult').innerHTML = '';
    const wrap = el('div', 'ok');
    wrap.appendChild(el('div', null, '邀请码已生成。它只显示这一次，库里只存哈希：'));
    const code = el('div', 'code-block', res.code);
    code.style.marginTop = '8px';
    wrap.appendChild(code);
    const copy = el('button', 'sm', '复制');
    copy.style.marginTop = '8px';
    copy.onclick = () => navigator.clipboard.writeText(res.code).then(() => toast('已复制'));
    wrap.appendChild(copy);
    $('invResult').appendChild(wrap);
    $('invLabel').value = '';
    renderInvites();
  } catch (e) {
    toast(e.message, true);
  } finally {
    busy(btn, false);
  }
};

async function renderInvites() {
  const box = $('inviteTable');
  box.innerHTML = '';
  box.appendChild(el('div', 'empty', '读取中…'));
  try {
    const { invites, envFallbackActive } = await adminApi('/v1/admin/invites');
    box.innerHTML = '';

    if (envFallbackActive) {
      box.appendChild(el('div', 'warn',
        '当前库里没有可用邀请码，配置文件里的 REGISTRATION_TOKEN 仍然生效。' +
        '在这里生成第一个邀请码之后，配置文件里那个会自动失效。'));
    }

    if (invites.length === 0) {
      box.appendChild(el('div', 'empty', '还没有邀请码'));
      return;
    }

    const LABEL = { active: ['on', '可用'], used_up: ['', '已用完'], expired: ['', '已过期'], revoked: ['off', '已作废'] };
    box.appendChild(table(['备注', '状态', '已用 / 上限', '有效期', '创建时间', ''], invites.map((i) => {
      const [cls, text] = LABEL[i.status];
      const st = el('span', 'badge ' + cls, text);
      const act = el('div');
      if (i.status === 'active') {
        const b = el('button', 'sm danger', '作废');
        b.onclick = async () => {
          if (!confirm('作废这个邀请码？已经用它注册的账号不受影响。')) return;
          await adminApi(`/v1/admin/invites/${i.id}`, { method: 'DELETE' });
          toast('已作废');
          renderInvites();
        };
        act.appendChild(b);
      }
      return [
        i.label || '（无备注）', st,
        { num: `${i.usedCount} / ${i.maxUses === 0 ? '∞' : i.maxUses}` },
        i.expiresAt ? fmtTime(i.expiresAt) : '永久',
        fmtTime(i.createdAt), act,
      ];
    })));
  } catch (e) {
    box.innerHTML = '';
    box.appendChild(el('div', 'empty', '读取失败：' + e.message));
  }
}

// ================================================================ 服务器

async function renderStats() {
  const box = $('statsBox');
  box.innerHTML = '';
  box.appendChild(el('div', 'empty', '读取中…'));
  try {
    const s = await adminApi('/v1/admin/stats');
    box.innerHTML = '';

    const overview = el('div', 'card');
    overview.appendChild(el('h3', null, '概览'));
    overview.appendChild(table(['项目', '值'], [
      ['账号', { num: `${s.counts.accounts}（停用 ${s.counts.disabled_accounts}）` }],
      ['活跃设备', { num: s.counts.devices }],
      ['记录总数', { num: `${s.counts.records}（墓碑 ${s.counts.tombstones}）` }],
      ['头像', { num: `${s.counts.blobs} 个，${fmtBytes(s.counts.blob_bytes)}` }],
      ['密文占用', { num: fmtBytes(s.counts.record_bytes + s.counts.blob_bytes) }],
      ['数据库文件', { num: fmtBytes(s.dbBytes) }],
      ['可用邀请码', { num: s.counts.invites }],
      ['运行时长', { num: fmtUptime(s.uptimeSec) }],
      ['Node 版本', s.nodeVersion],
    ]));
    box.appendChild(overview);

    const byColl = el('div', 'card');
    byColl.appendChild(el('h3', null, '按数据类型'));
    byColl.appendChild(table(['类型', '记录数', '密文大小'], [
      ['联系人 contacts', { num: s.collections.contacts.records }, { num: fmtBytes(s.collections.contacts.bytes) }],
      ['通话记录 calls', { num: s.collections.calls.records }, { num: fmtBytes(s.collections.calls.bytes) }],
    ]));
    byColl.appendChild(el('div', 'hint',
      '两类数据用的是不同的密钥，服务端也分开存。管理员对两者都同样看不到内容。'));
    box.appendChild(byColl);

    const bk = el('div', 'card');
    bk.appendChild(el('h3', null, '备份'));
    if (s.backups.length === 0) {
      bk.appendChild(el('p', 'sub',
        '容器里看不到备份文件 —— 备份脚本跑在宿主机上，把数据库拷到 /opt/contacts-sync/backups。' +
        '这是正常的，不是没在备份。'));
    } else {
      bk.appendChild(table(['文件', '大小', '时间'],
        s.backups.map((b) => [b.name, { num: fmtBytes(b.bytes) }, fmtTime(b.at)])));
    }
    box.appendChild(bk);
  } catch (e) {
    box.innerHTML = '';
    box.appendChild(el('div', 'empty', '读取失败：' + e.message));
  }
}

const fmtUptime = (s) => {
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  return d > 0 ? `${d} 天 ${h} 小时` : h > 0 ? `${h} 小时 ${m} 分` : `${m} 分`;
};

// ================================================================ 安全

$('pwBtn').onclick = async () => {
  const btn = $('pwBtn');
  if ($('pwNew').value.length < 12) return toast('新口令至少 12 个字符', true);
  busy(btn, true);
  try {
    await adminApi('/v1/admin/password', {
      method: 'POST',
      body: { currentPassword: $('pwOld').value, newPassword: $('pwNew').value },
    });
    $('pwOld').value = $('pwNew').value = '';
    toast('口令已修改，其它会话已失效');
  } catch (e) {
    toast(e.code === 'invalid_credentials' ? '当前口令不正确' : e.message, true);
  } finally {
    busy(btn, false);
  }
};

async function renderSecurity() {
  await renderAdminMfa();
  try {
    const s = await adminApi('/v1/admin/sessions');
    const box = $('sessionTable');
    box.innerHTML = '';
    box.appendChild(table(['管理员', '登录时间', '过期时间', '来源 IP', '客户端'],
      s.sessions.map((x) => [x.username, fmtTime(x.createdAt), fmtTime(x.expiresAt), x.ip,
        (x.userAgent || '').slice(0, 60)])));
  } catch (e) {
    $('sessionTable').textContent = '读取失败：' + e.message;
  }

  try {
    const s = await adminApi('/v1/admin/stats');
    const box = $('failTable');
    box.innerHTML = '';
    if (s.recentAuthFailures.length === 0) {
      box.appendChild(el('div', 'empty', '最近一小时没有失败的登录'));
      return;
    }
    box.appendChild(table(['来源', '失败次数'],
      s.recentAuthFailures.map((f) => [f.key, { num: f.count }])));
    box.appendChild(el('div', 'hint',
      'login: 开头是用户端，admin: 开头是管理后台。ip 是按来源地址计数，user 是按用户名计数。'));
  } catch (e) {
    $('failTable').textContent = '读取失败：' + e.message;
  }
}

function showBackupCodes(codes) {
  const bg = el('div', 'modal-bg');
  const box = el('div', 'modal');
  bg.appendChild(box);
  box.appendChild(el('h2', null, '请立即保存管理员备份码'));
  box.appendChild(el('div', 'warn', '每个备份码只能使用一次，关闭后不会再次显示。'));
  const code = el('div', 'code-block', codes.join('\n'));
  code.style.whiteSpace = 'pre-wrap';
  box.appendChild(code);
  const actions = el('div', 'actions');
  const copy = el('button', null, '复制');
  copy.onclick = () => navigator.clipboard.writeText(codes.join('\n')).then(() => toast('已复制'));
  const close = el('button', 'primary', '我已保存');
  close.onclick = () => bg.remove();
  actions.append(copy, close);
  box.appendChild(actions);
  $('modalRoot').appendChild(bg);
}

async function renderAdminMfa() {
  const box = $('adminMfaBox');
  box.innerHTML = '';
  try {
    const status = await adminApi('/v1/admin/mfa/status');
    const section = el('div');
    const addRow = (title, description, control) => {
      const row = el('div', 'switch-row');
      const text = el('div', 'row-text');
      text.append(el('div', 'row-title', title), el('div', 'row-desc', description));
      row.append(text, control);
      section.appendChild(row);
    };
    const makeSwitch = (checked, disabled = false) => {
      const label = el('label', 'switch');
      const input = el('input');
      input.type = 'checkbox';
      input.checked = checked;
      input.disabled = disabled;
      label.append(input, el('span', 'track'), el('span', 'thumb'));
      return { label, input };
    };

    const totpSwitch = makeSwitch(status.totpEnabled);
    addRow(
      '验证器 App',
      status.totpEnabled
        ? '已开启；Google Authenticator、1Password 等每 30 秒生成一个 6 位码'
        : 'Google Authenticator、1Password 这类，每 30 秒一个 6 位码',
      totpSwitch.label,
    );
    totpSwitch.input.onchange = async () => {
      const turningOn = totpSwitch.input.checked;
      try {
        if (!turningOn) {
          const code = prompt('输入当前验证器的 6 位验证码以关闭：');
          if (!code) { totpSwitch.input.checked = true; return; }
          await adminApi('/v1/admin/mfa/totp/disable', { method: 'POST', body: { code } });
          toast('验证器已关闭');
        } else {
          totpSwitch.input.checked = false;
          const setup = await adminApi('/v1/admin/mfa/totp/setup', { method: 'POST' });
          const code = prompt(`请在验证器中添加下面的密钥，然后输入生成的 6 位验证码：\n\n${setup.secret}`);
          if (!code) return;
          const result = await adminApi('/v1/admin/mfa/totp/confirm', { method: 'POST', body: { code } });
          showBackupCodes(result.backupCodes);
          toast('验证器已开启');
        }
        await renderAdminMfa();
      } catch (e) { toast(e.message, true); }
    };

    const backup = el('button', null, status.backupCodesLeft ? '重新生成' : '生成');
    backup.disabled = !status.totpEnabled && !status.passkeyEnabled;
    addRow(
      '备用码',
      status.backupCodesLeft
        ? `还剩 ${status.backupCodesLeft} 个；重新生成会让旧码失效`
        : '用于无法使用验证器或通行密钥时登录',
      backup,
    );
    backup.onclick = async () => {
      if (status.backupCodesLeft && !confirm('旧的管理员备用码会立即失效，继续吗？')) return;
      try {
        const result = await adminApi('/v1/admin/mfa/backup/regenerate', { method: 'POST' });
        showBackupCodes(result.backupCodes);
        await renderAdminMfa();
      } catch (e) { toast(e.message, true); }
    };

    const add = el('button', 'primary', status.passkeys.length ? '再添加一个' : '添加');
    add.disabled = !passkeySupported();
    addRow(
      '通行密钥',
      status.passkeys.length
        ? `已添加 ${status.passkeys.length} 个；可使用指纹、面容、系统 PIN 或硬件密钥`
        : '尚未添加；可使用指纹、面容、系统 PIN 或硬件密钥',
      add,
    );
    add.onclick = async () => {
      if (!passkeySupported()) return toast('当前浏览器不支持通行密钥', true);
      const name = prompt('给这个通行密钥起个名字：', '管理员通行密钥');
      if (name === null) return;
      try {
        const start = await adminApi('/v1/admin/mfa/passkey/register/options', { method: 'POST' });
        const credential = await navigator.credentials.create({ publicKey: reviveOptions(start.options) });
        const result = await adminApi('/v1/admin/mfa/passkey/register/verify', {
          method: 'POST',
          body: { token: start.token, name: name.trim() || '管理员通行密钥', response: serializeCredential(credential) },
        });
        if (result.backupCodes) showBackupCodes(result.backupCodes);
        toast('通行密钥已添加');
        await renderAdminMfa();
      } catch (e) {
        toast(e?.name === 'NotAllowedError' ? '已取消' : e.message, true);
      }
    };

    if (status.passkeys.length) {
      const list = el('div');
      status.passkeys.forEach((key) => {
        const item = el('div', 'row');
        item.style.cssText = 'justify-content:space-between;gap:12px;padding:9px 0 9px 14px;border-bottom:1px solid var(--line,var(--border))';
        const info = el('div', 'grow');
        info.append(
          el('div', null, key.name),
          el('div', 'row-desc', `${fmtTime(key.created_at)} 添加 · ${key.last_used_at ? `${fmtTime(key.last_used_at)} 使用过` : '尚未使用'}`),
        );
        const remove = el('button', 'sm danger', '删除');
        remove.onclick = async () => {
          if (!confirm(`删除「${key.name}」？`)) return;
          try {
            await adminApi(`/v1/admin/mfa/passkey/${encodeURIComponent(key.id)}`, { method: 'DELETE' });
            await renderAdminMfa();
          } catch (e) { toast(e.message, true); }
        };
        item.append(info, remove);
        list.appendChild(item);
      });
      section.appendChild(list);
    }

    const canRequireAll = status.totpEnabled && status.passkeyEnabled;
    const requireAllSwitch = makeSwitch(status.requireAll, !canRequireAll);
    addRow(
      '两种都要验证',
      canRequireAll
        ? (status.requireAll ? '登录时验证器和通行密钥都要通过；备用码仍可应急登录' : '默认通过其中一种即可')
        : '验证器和通行密钥都设置好之后才能打开',
      requireAllSwitch.label,
    );
    requireAllSwitch.input.onchange = async () => {
      try {
        await adminApi('/v1/admin/mfa/settings', {
          method: 'POST', body: { requireAll: requireAllSwitch.input.checked },
        });
        await renderAdminMfa();
      } catch (e) {
        requireAllSwitch.input.checked = !requireAllSwitch.input.checked;
        toast(e.message, true);
      }
    };

    box.appendChild(section);
  } catch (e) {
    box.appendChild(el('div', 'err', '读取二次验证状态失败：' + e.message));
  }
}
