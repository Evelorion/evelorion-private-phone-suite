/**
 * 用户端。
 *
 * 所有解密都在这个页面里完成，主口令不会以任何形式发给服务器 ——
 * 发出去的只有 authSecret（HKDF 派生，推不回口令）。
 */

import * as C from '../lib/crypto.js';
import * as V from '../lib/vault.js';
import { ApiError } from '../lib/api.js';
import * as MFA from '../lib/mfa.js';

const $ = (id) => document.getElementById(id);

/**
 * HTML 转义。
 *
 * 通行密钥的名字是用户自己起的，直接拼进 innerHTML 的话，
 * 起名叫 `<img src=x onerror=...>` 就能在自己的页面上执行脚本。
 * 这个页面里有解密后的联系人明文和内存里的密钥 —— 一次 XSS 就全泄了。
 */
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
}[c]));
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

function busy(button, on, label) {
  button.disabled = on;
  if (on) {
    button.dataset.label = button.textContent;
    button.innerHTML = '';
    button.appendChild(el('span', 'spin'));
    button.append(' ' + (label ?? '处理中…'));
  } else if (button.dataset.label) {
    button.textContent = button.dataset.label;
  }
}

// ================================================================ 登录

let mode = 'login';

$('tabLogin').onclick = () => setMode('login');
$('tabPrivateKey').onclick = () => setMode('privateKey');
$('tabRegister').onclick = () => setMode('register');

function setMode(m) {
  mode = m;
  $('tabLogin').classList.toggle('active', m === 'login');
  $('tabPrivateKey').classList.toggle('active', m === 'privateKey');
  $('tabRegister').classList.toggle('active', m === 'register');
  $('inviteWrap').classList.toggle('hidden', m !== 'register');
  $('passphraseWrap').classList.toggle('hidden', m === 'privateKey');
  $('privateKeyWrap').classList.toggle('hidden', m !== 'privateKey');
  $('submitBtn').textContent = m === 'login' ? '登录' : m === 'privateKey' ? '用账户私钥直接登录' : '创建账号';
  $('passphrase').autocomplete = m === 'login' ? 'current-password' : 'new-password';
  $('passHint').textContent = m === 'login'
    ? '主口令不会上传，也无法找回。'
    : '至少 10 个字符。建议用一句只有你知道的话，比单个词安全得多。';
  $('authMsg').innerHTML = '';
}

$('submitBtn').onclick = async () => {
  const username = $('username').value.trim();
  const passphrase = $('passphrase').value;
  const privateKey = $('accountPrivateKey').value.trim();
  const btn = $('submitBtn');
  $('authMsg').innerHTML = '';

  if (!username) return showAuthError('请填写用户名');
  if (mode === 'register' && passphrase.length < 10) {
    return showAuthError('主口令至少 10 个字符。它是唯一能解开数据的东西，没有找回途径。');
  }
  if (mode !== 'privateKey' && !passphrase) return showAuthError('请输入主口令');
  if (mode === 'privateKey' && !privateKey) return showAuthError('请输入账户私钥');

  busy(btn, true, mode === 'privateKey' ? '正在验证账户私钥…' : '正在派生密钥…');
  try {
    if (mode === 'register') {
      const { recoveryCode } = await V.register(
        username, passphrase, $('invite').value.trim(), deviceName()
      );
      $('recoveryCode').textContent = recoveryCode;
      $('auth').classList.add('hidden');
      $('recoveryPanel').classList.remove('hidden');
    } else if (mode === 'privateKey') {
      await V.loginWithPrivateKey(username, privateKey, deviceName(), mfaPrompt);
      await enterApp();
    } else {
      await V.login(username, passphrase, deviceName(), mfaPrompt);
      await enterApp();
    }
  } catch (e) {
    showAuthError(friendly(e));
  } finally {
    busy(btn, false);
    $('passphrase').value = '';
    $('accountPrivateKey').value = '';
  }
};

function showAuthError(msg) {
  $('authMsg').innerHTML = '';
  $('authMsg').appendChild(el('div', 'err', msg));
}

function friendly(e) {
  if (e instanceof ApiError) {
    if (e.code === 'invalid_credentials') return '用户名或主口令不正确';
    if (e.code === 'invalid_recovery_code') return '账户私钥不正确，或这是尚未启用私钥登录的旧账户。请先用主口令登录一次，在设置里启用。';
    if (e.code === 'bad_registration_token') return e.message;
    if (e.code === 'username_taken') return '这个用户名已经被占用了';
    if (e.code === 'too_many_attempts') return '尝试次数过多，请一小时后再试';
    if (e.code === 'weak_password') return e.message;
    return e.message;
  }
  return e.message || String(e);
}

const deviceName = () => {
  const ua = navigator.userAgent;
  const os = /Windows/.test(ua) ? 'Windows' : /Mac/.test(ua) ? 'macOS'
    : /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : 'Linux';
  const br = /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome'
    : /Firefox\//.test(ua) ? 'Firefox' : /Safari\//.test(ua) ? 'Safari' : '浏览器';
  return `网页版 ${br} / ${os}`;
};

$('copyRecovery').onclick = () => {
  navigator.clipboard.writeText($('recoveryCode').textContent).then(
    () => toast('已复制。别只存在剪贴板里。'),
    () => toast('复制失败，请手动选中', true)
  );
};

$('recoveryDone').onclick = async () => {
  if (!confirm('确认已经把恢复码抄到安全的地方了？\n\n它不会再显示第二次。')) return;
  $('recoveryPanel').classList.add('hidden');
  await enterApp();
};

// ================================================================ 主界面

async function enterApp() {
  $('auth').classList.add('hidden');
  $('app').classList.remove('hidden');
  $('whoami').textContent = V.vault.username;
  await reload();
}

async function reload() {
  $('contactList').innerHTML = '';
  $('contactList').appendChild(el('div', 'empty', '正在拉取并解密…'));
  try {
    await V.loadAll((n) => {
      const e = $('contactList').querySelector('.empty');
      if (e) e.textContent = `正在拉取并解密…已解出 ${n} 条`;
    });
    renderContacts();
    renderIntegrity();
  } catch (e) {
    $('contactList').innerHTML = '';
    $('contactList').appendChild(el('div', 'empty', '加载失败：' + friendly(e)));
  }
}

function renderIntegrity() {
  const box = $('integrityWarn');
  if (V.vault.integrityIssues.length === 0) return box.classList.add('hidden');
  box.classList.remove('hidden');
  box.innerHTML = '';
  box.appendChild(el('strong', null, '完整性校验发现问题：'));
  const ul = el('ul');
  ul.style.margin = '8px 0 0';
  for (const i of V.vault.integrityIssues) ul.appendChild(el('li', null, i));
  box.appendChild(ul);
  box.appendChild(el('div', 'hint',
    '同步清单是你自己加密写上去的目录，服务器伪造不了。如果反复出现同一条记录缺失，说明服务器可能不老实。'));
}

// ---------------- 视图切换

document.querySelectorAll('.topbar .tabs button').forEach((b) => {
  b.onclick = async () => {
    document.querySelectorAll('.topbar .tabs button').forEach((x) => x.classList.remove('active'));
    b.classList.add('active');
    const v = b.dataset.view;
    $('viewContacts').classList.toggle('hidden', v !== 'contacts');
    $('viewCalls').classList.toggle('hidden', v !== 'calls');
    $('viewSettings').classList.toggle('hidden', v !== 'settings');
    if (v === 'calls') await renderCalls();
    if (v === 'settings') await renderSettings();
  };
});

$('lockBtn').onclick = () => {
  V.lock();
  location.reload();
};

// ---------------- 联系人列表

$('search').oninput = renderContacts;
$('newContact').onclick = () => openEditor(null);

function renderContacts() {
  const q = $('search').value.trim().toLowerCase();
  const list = $('contactList');
  list.innerHTML = '';

  const entries = [...V.vault.contacts.entries()]
    .filter(([, e]) => !q || matches(e.payload, q))
    .sort((a, b) => V.displayName(a[1].payload).localeCompare(V.displayName(b[1].payload), 'zh'));

  if (entries.length === 0) {
    list.appendChild(el('div', 'empty',
      V.vault.contacts.size === 0 ? '还没有联系人。点「新建」加一个。' : '没有匹配的联系人'));
    return;
  }

  for (const [uuid, e] of entries) {
    const c = e.payload;
    const name = V.displayName(c);
    const row = el('div', 'item');
    row.appendChild(el('div', 'avatar', name.slice(0, 1)));
    const info = el('div', 'grow');
    info.appendChild(el('div', 'name', name));
    const sub = [c.phones?.[0]?.value, c.company].filter(Boolean).join(' · ');
    if (sub) info.appendChild(el('div', 'meta', sub));
    row.appendChild(info);
    if (c.starred) row.appendChild(el('span', 'badge on', '收藏'));
    row.onclick = () => openEditor(uuid);
    list.appendChild(row);
  }
}

function matches(c, q) {
  const hay = [
    c.first, c.middle, c.surname, c.nickname, c.company, c.jobTitle, c.notes,
    ...(c.phones ?? []).map((p) => p.value + ' ' + p.norm),
    ...(c.emails ?? []).map((e) => e.value),
  ].filter(Boolean).join(' ').toLowerCase();
  return hay.includes(q);
}

// ---------------- 编辑器

function openEditor(uuid) {
  const existing = uuid ? V.vault.contacts.get(uuid) : null;
  const c = existing ? structuredClone(existing.payload) : V.emptyContact();

  const bg = el('div', 'modal-bg');
  const box = el('div', 'modal');
  bg.appendChild(box);
  bg.onclick = (ev) => { if (ev.target === bg) bg.remove(); };

  box.appendChild(el('h2', null, uuid ? '编辑联系人' : '新建联系人'));

  const field = (label, value, oninput, type = 'text') => {
    box.appendChild(el('label', null, label));
    const i = el('input');
    i.type = type;
    i.value = value ?? '';
    i.oninput = () => oninput(i.value);
    box.appendChild(i);
    return i;
  };

  const nameRow = el('div', 'row');
  nameRow.style.gap = '8px';
  box.appendChild(el('label', null, '姓 / 名'));
  const surname = el('input'); surname.placeholder = '姓'; surname.value = c.surname;
  const first = el('input'); first.placeholder = '名'; first.value = c.first;
  surname.oninput = () => (c.surname = surname.value);
  first.oninput = () => (c.first = first.value);
  nameRow.append(surname, first);
  box.appendChild(nameRow);

  field('昵称', c.nickname, (v) => (c.nickname = v));
  field('公司', c.company, (v) => (c.company = v));
  field('职位', c.jobTitle, (v) => (c.jobTitle = v));

  // 号码 / 邮箱 / 网址 三个可增删的列表
  const listEditor = (title, key, placeholder, valueKey = 'value') => {
    box.appendChild(el('label', null, title));
    const wrap = el('div');
    box.appendChild(wrap);
    const draw = () => {
      wrap.innerHTML = '';
      (c[key] ?? []).forEach((item, idx) => {
        const r = el('div', 'row');
        r.style.marginBottom = '6px';
        const i = el('input', 'grow');
        i.placeholder = placeholder;
        i.value = item[valueKey] ?? '';
        i.oninput = () => (item[valueKey] = i.value);
        const del = el('button', 'ghost', '✕');
        del.onclick = () => { c[key].splice(idx, 1); draw(); };
        r.append(i, del);
        wrap.appendChild(r);
      });
      const add = el('button', 'sm', '+ 添加');
      add.onclick = () => {
        c[key] = c[key] ?? [];
        c[key].push({ id: '', value: '', ...(key === 'phones' ? { norm: '', type: 2, label: '', primary: false } : {}),
                      ...(key === 'emails' ? { type: 1, label: '' } : {}) });
        draw();
      };
      wrap.appendChild(add);
    };
    draw();
  };

  listEditor('电话', 'phones', '+8613800138000');
  listEditor('邮箱', 'emails', 'name@example.com');
  listEditor('网址', 'websites', 'https://…');

  box.appendChild(el('label', null, '备注'));
  const notes = el('textarea');
  notes.value = c.notes ?? '';
  notes.oninput = () => (c.notes = notes.value);
  box.appendChild(notes);

  const starRow = el('label');
  starRow.style.display = 'flex';
  starRow.style.alignItems = 'center';
  starRow.style.gap = '8px';
  const star = el('input');
  star.type = 'checkbox';
  star.style.width = 'auto';
  star.checked = !!c.starred;
  star.onchange = () => (c.starred = star.checked ? 1 : 0);
  starRow.append(star, document.createTextNode('收藏'));
  box.appendChild(starRow);

  const actions = el('div', 'actions');
  const save = el('button', 'primary', '保存');
  const cancel = el('button', null, '取消');
  cancel.onclick = () => bg.remove();
  actions.append(save, cancel);

  if (uuid) {
    actions.appendChild(el('div', 'spacer'));
    const del = el('button', 'danger', '删除');
    del.onclick = async () => {
      if (!confirm(`删除「${V.displayName(c)}」？\n\n其它设备下次同步时也会删掉。`)) return;
      busy(del, true, '删除中…');
      try {
        await V.deleteContact(uuid);
        bg.remove();
        renderContacts();
        toast('已删除');
      } catch (e) {
        busy(del, false);
        toast(friendly(e), true);
      }
    };
    actions.appendChild(del);
  }
  box.appendChild(actions);

  save.onclick = async () => {
    // 清掉空行，再重算列表项的 id（号码改了 id 也要跟着变，否则合并时会认成另一条）
    for (const k of ['phones', 'emails', 'websites']) {
      c[k] = (c[k] ?? []).filter((x) => (x.value ?? '').trim() !== '');
    }
    await V.normalizeItemIds(c);

    busy(save, true, '加密并上传…');
    try {
      const id = uuid ?? crypto.randomUUID();
      const res = await V.saveContact(id, c);
      bg.remove();
      renderContacts();
      toast(res.conflicts.length
        ? `已保存，但有 ${res.conflicts.length} 个字段和另一台设备冲突，已自动合并`
        : '已保存');
    } catch (e) {
      busy(save, false);
      toast(friendly(e), true);
    }
  };

  $('modalRoot').appendChild(bg);
  surname.focus();
}

// ---------------- 通话记录

async function renderCalls() {
  const list = $('callList');
  list.innerHTML = '';
  list.appendChild(el('div', 'empty', '正在解密…'));
  try {
    const calls = await V.loadCalls();
    list.innerHTML = '';
    if (calls.length === 0) {
      list.appendChild(el('div', 'empty', '还没有通话记录。需要在电话 App 里开启加密同步。'));
      return;
    }
    const TYPE = { 1: '呼入', 2: '呼出', 3: '未接', 5: '拒接', 6: '拦截' };
    for (const c of calls) {
      const row = el('div', 'item');
      row.style.cursor = 'default';
      row.appendChild(el('div', 'avatar', TYPE[c.type]?.[0] ?? '?'));
      const info = el('div', 'grow');
      info.appendChild(el('div', 'name', c.name || c.number || '未知号码'));
      info.appendChild(el('div', 'meta',
        `${TYPE[c.type] ?? '通话'} · ${new Date(c.ts).toLocaleString('zh-CN')} · ${fmtDur(c.dur)}`));
      row.appendChild(info);
      list.appendChild(row);
    }
  } catch (e) {
    list.innerHTML = '';
    list.appendChild(el('div', 'empty', '加载失败：' + friendly(e)));
  }
}

const fmtDur = (s) => (!s ? '未接通' : s < 60 ? `${s} 秒` : `${Math.floor(s / 60)} 分 ${s % 60} 秒`);

// ---------------- 设置

async function renderSettings() {
  try {
    const privateKey = await V.privateKeyLoginStatus();
    $('privateKeyLoginStatus').textContent = privateKey.enabled
      ? '已启用：以后可以在登录页只输入账户私钥直接进入。'
      : '此旧账户尚未启用。完成下面的一次性验证后即可只凭私钥登录。';
    $('privateKeyEnableForm').classList.toggle('hidden', privateKey.enabled);
  } catch (e) {
    $('privateKeyLoginStatus').textContent = '读取失败：' + friendly(e);
  }

  try {
    const st = await V.syncStatus();
    const c = st.collections.contacts, k = st.collections.calls;
    $('statusBox').textContent =
      `联系人 ${c.records} 条（${fmtBytes(c.cipherBytes)}）· ` +
      `通话记录 ${k.records} 条（${fmtBytes(k.cipherBytes)}）· ` +
      `墓碑 ${c.tombstones + k.tombstones} 条`;
  } catch (e) {
    $('statusBox').textContent = '读取失败：' + friendly(e);
  }

  try {
    const { devices } = await V.listDevices();
    const box = $('deviceList');
    box.innerHTML = '';
    const t = el('table');
    const head = el('tr');
    ['设备', '最后活跃', '状态', ''].forEach((h) => head.appendChild(el('th', null, h)));
    t.appendChild(head);
    for (const d of devices) {
      const tr = el('tr');
      tr.appendChild(el('td', null, d.name + (d.current ? '（当前）' : '')));
      tr.appendChild(el('td', null, d.lastSeenAt ? new Date(d.lastSeenAt).toLocaleString('zh-CN') : '—'));
      const st = el('td');
      st.appendChild(el('span', 'badge ' + (d.revoked ? 'off' : 'on'), d.revoked ? '已吊销' : '正常'));
      tr.appendChild(st);
      const act = el('td');
      if (!d.revoked && !d.current) {
        const b = el('button', 'sm danger', '吊销');
        b.onclick = async () => {
          if (!confirm(`吊销「${d.name}」？那台设备需要重新登录。`)) return;
          await V.revokeDevice(d.id);
          toast('已吊销');
          renderSettings();
        };
        act.appendChild(b);
      }
      tr.appendChild(act);
      t.appendChild(tr);
    }
    box.appendChild(t);
  } catch (e) {
    $('deviceList').textContent = '读取失败：' + friendly(e);
  }
}

const fmtBytes = (n) => (n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB');

$('reloadBtn').onclick = reload;

$('exportBtn').onclick = () => {
  const blob = new Blob([V.exportVCard()], { type: 'text/vcard;charset=utf-8' });
  const a = el('a');
  a.href = URL.createObjectURL(blob);
  a.download = `contacts-${new Date().toISOString().slice(0, 10)}.vcf`;
  a.click();
  URL.revokeObjectURL(a.href);
  toast(`已导出 ${V.vault.contacts.size} 条。这个文件是明文，注意保管。`);
};

$('privateKeyEnableBtn').onclick = async () => {
  const current = $('privateKeyCurrentPass').value;
  const key = $('privateKeyEnableCode').value.trim();
  if (!current || !key) return toast('请填写当前主口令和账户私钥', true);
  const btn = $('privateKeyEnableBtn');
  busy(btn, true, '正在验证…');
  try {
    await V.enablePrivateKeyLogin(current, key);
    $('privateKeyCurrentPass').value = '';
    $('privateKeyEnableCode').value = '';
    toast('账户私钥直接登录已启用');
    await renderSettings();
  } catch (e) {
    toast(friendly(e), true);
  } finally {
    busy(btn, false);
  }
};

$('changePassBtn').onclick = async () => {
  const btn = $('changePassBtn');
  const oldP = $('oldPass').value, newP = $('newPass').value, rec = $('recoveryForChange').value;
  if (newP.length < 10) return toast('新主口令至少 10 个字符', true);
  if (!rec.trim()) return toast('需要恢复码才能重新包裹', true);

  busy(btn, true, '重新派生密钥…');
  try {
    await V.changePassphrase(oldP, newP, rec);
    $('oldPass').value = $('newPass').value = $('recoveryForChange').value = '';
    toast('口令已修改。其它设备需要用新口令重新登录。');
  } catch (e) {
    toast(friendly(e), true);
  } finally {
    busy(btn, false);
  }
};

$('destroyBtn').onclick = async () => {
  const pass = $('destroyPass').value;
  if (!pass) return toast('请输入主口令确认', true);
  if (!confirm('永久删除账号和服务器上的全部密文？\n\n这个操作不可恢复。手机上已有的联系人不受影响。')) return;
  if (!confirm('真的确定？再确认一次。')) return;
  busy($('destroyBtn'), true, '删除中…');
  try {
    await V.destroyAccount(pass);
    alert('账号已删除。');
    location.href = '/';
  } catch (e) {
    busy($('destroyBtn'), false);
    toast(friendly(e), true);
  }
};

// 离开页面时把密钥抹掉
window.addEventListener('beforeunload', () => { if (V.isUnlocked()) V.lock(); });


// ════════════════════════════════════════════════════ 两步验证

/**
 * 登录时的第二步交互。
 *
 * 做成 Promise 而不是回调地狱：vault.login 里 await 它，
 * 用户点了按钮才 resolve。取消就 reject，整个登录流程回到表单。
 */
const mfaPrompt = {
  /** 同时有两种方式且不要求都验时，问用户想用哪个。 */
  preferPasskey: () => Promise.resolve(MFA.passkeySupported()),

  code: () => new Promise((resolve, reject) => {
    $('auth').classList.add('hidden');
    $('mfaPanel').classList.remove('hidden');
    $('mfaCodeBlock').classList.remove('hidden');
    $('mfaPasskeyBlock').classList.add('hidden');
    $('mfaPanelHint').textContent = '这个账号开启了两步验证。输入验证器上的 6 位码。';
    $('mfaCode').value = '';
    $('mfaCode').focus();

    const done = (value) => {
      $('mfaPanel').classList.add('hidden');
      $('auth').classList.remove('hidden');
      $('mfaSubmit').onclick = null;
      $('mfaCancel').onclick = null;
      value === null ? reject(new Error('已取消验证')) : resolve(value);
    };
    $('mfaSubmit').onclick = () => done($('mfaCode').value.trim());
    $('mfaCancel').onclick = () => done(null);
  }),
};

// ── 设置页里的两步验证 ──

const mfaMsg = (text, isError) => {
  const el = $('mfaMsg');
  el.textContent = text;
  el.className = isError ? 'err' : 'sub';
};

async function refreshMfa() {
  try {
    const st = await MFA.status();

    // 顶部一句话状态，用徽标而不是长句子
    $('mfaStatus').innerHTML = st.totpEnabled || st.passkeyEnabled
      ? '<span class="badge on">已开启</span>'
      : '<span class="badge off">未开启</span>';

    // 验证器开关。程序性地改 checked 不会触发 change，所以不用担心回环
    $('totpSwitch').checked = st.totpEnabled;
    $('backupCodeStatus').textContent = st.backupCodesLeft
      ? `还剩 ${st.backupCodesLeft} 个；重新生成会让旧码失效`
      : '用于无法使用验证器或通行密钥时登录';
    $('backupRegenerate').textContent = st.backupCodesLeft ? '重新生成' : '生成';
    $('backupRegenerate').disabled = !(st.totpEnabled || st.passkeyEnabled);

    // 通行密钥列表
    $('passkeyList').innerHTML = st.passkeys.length
      ? st.passkeys.map((k) => `
          <div style="display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 0 8px 14px;font-size:13px">
            <div>
              <div>${esc(k.name)}</div>
              <div class="row-desc" style="margin-top:1px">
                ${new Date(k.created_at).toLocaleDateString()} 添加${k.last_used_at ? ' · 用过' : ' · 还没用过'}
              </div>
            </div>
            <button class="ghost" data-passkey="${esc(k.id)}">删除</button>
          </div>`).join('')
      : '';

    $('passkeyList').querySelectorAll('[data-passkey]').forEach((btn) => {
      btn.onclick = async () => {
        if (!confirm('删除这个通行密钥？')) return;
        try { await MFA.removePasskey(btn.dataset.passkey); await refreshMfa(); }
        catch (e) { mfaMsg(friendly(e), true); }
      };
    });

    // 「两种都要」只有两种都设置好才能开 —— 只有一种就要求两种会把自己锁在外面。
    // 这里禁用开关并把原因写在描述里，而不是等用户点了再弹错误
    const canRequireAll = st.totpEnabled && st.passkeyEnabled;
    $('requireAll').checked = st.requireAll;
    $('requireAll').disabled = !canRequireAll;
    $('requireAllDesc').textContent = canRequireAll
      ? (st.requireAll ? '登录时验证器和通行密钥都要过' : '默认通过其中一种即可')
      : '两种都设置好之后才能打开';

    $('passkeyAdd').disabled = !MFA.passkeySupported();
    if (!MFA.passkeySupported()) $('passkeyAdd').textContent = '浏览器不支持';
  } catch (e) {
    $('mfaStatus').innerHTML = '<span class="badge off">读取失败</span>';
  }
}

$('totpSwitch').onchange = async () => {
  const turningOn = $('totpSwitch').checked;

  if (!turningOn) {
    // 关掉也要验一次 —— 否则拿到你已登录会话的人可以直接把 2FA 关了
    const code = prompt('关闭验证器需要先验证一次。输入当前的 6 位码：');
    if (!code) { $('totpSwitch').checked = true; return; }
    try {
      await MFA.totpDisable(code.trim());
      mfaMsg('验证器已关闭');
    } catch (e) {
      $('totpSwitch').checked = true;
      mfaMsg(friendly(e), true);
    }
    await refreshMfa();
    return;
  }

  // 开启是个多步流程：先拿密钥，用户存进验证器，再输码确认。
  // 开关先弹回去，确认成功后 refreshMfa 会把它打开
  $('totpSwitch').checked = false;
  try {
    const { secret, uri } = await MFA.totpSetup();
    $('totpSecret').textContent = secret.replace(/(.{4})/g, '$1 ').trim();
    // 不用公开的二维码渲染服务 —— 那等于把 TOTP 密钥发给第三方。
    // 手机上点这个链接会直接打开验证器
    $('totpUri').innerHTML =
      `<a href="${esc(uri)}" style="font-size:12px;word-break:break-all">在手机上点这里直接添加</a>`;
    $('totpSetup').classList.remove('hidden');
    $('backupCodes').classList.add('hidden');
    $('totpCode').value = '';
    $('totpCode').focus();
  } catch (e) { mfaMsg(friendly(e), true); }
};

$('totpCancel').onclick = () => {
  $('totpSetup').classList.add('hidden');
  mfaMsg('');
};

$('totpConfirm').onclick = async () => {
  const code = $('totpCode').value.trim();
  if (!code) return mfaMsg('请输入验证码', true);
  try {
    const res = await MFA.totpConfirm(code);
    $('totpSetup').classList.add('hidden');
    $('backupCodeList').textContent = res.backupCodes.join('   ');
    $('backupCodes').classList.remove('hidden');
    mfaMsg('验证器已开启');
    await refreshMfa();
  } catch (e) { mfaMsg(friendly(e), true); }
};

$('copyBackup').onclick = async () => {
  await navigator.clipboard.writeText($('backupCodeList').textContent);
  toast('已复制');
};

$('backupRegenerate').onclick = async () => {
  if (!confirm('生成新备用码后，之前的备用码会全部失效。继续吗？')) return;
  try {
    const res = await MFA.regenerateBackupCodes();
    $('backupCodeList').textContent = res.backupCodes.join('   ');
    $('backupCodes').classList.remove('hidden');
    mfaMsg('新备用码已生成，请立即保存');
    await refreshMfa();
  } catch (e) {
    mfaMsg(friendly(e), true);
  }
};

$('passkeyAdd').onclick = async () => {
  const name = prompt('给这个通行密钥起个名字：', '通行密钥');
  if (name === null) return;
  try {
    await MFA.addPasskey(name.trim());
    mfaMsg('已添加');
    await refreshMfa();
  } catch (e) {
    // 用户按了取消，浏览器抛 NotAllowedError —— 那不是错误
    if (e && e.name === 'NotAllowedError') return mfaMsg('已取消');
    mfaMsg(friendly(e), true);
  }
};

$('requireAll').onchange = async () => {
  try {
    await MFA.setRequireAll($('requireAll').checked);
    await refreshMfa();
  } catch (e) {
    $('requireAll').checked = !$('requireAll').checked;
    mfaMsg(friendly(e), true);
  }
};

setMode('login');
