import { userApi } from './api.js';

/**
 * 两步验证的浏览器端。
 *
 * ── 通行密钥（WebAuthn）的编码 ──────────────────────────────
 *
 * 浏览器的 credentials API 收发的是 ArrayBuffer，而 JSON 传不了二进制。
 * 规范约定用 **base64url**（不是普通 base64）：`+/` 换成 `-_`，去掉 `=` 填充。
 *
 * 这是最容易出错的地方 —— 用普通 base64 的话，凭据 ID 里只要出现
 * `+` 或 `/` 就会对不上，而错误表现是「浏览器弹了框，按了指纹，然后
 * 服务器说验证失败」，看不出是编码问题。
 */

export const passkeySupported = () =>
  typeof PublicKeyCredential !== 'undefined' && !!navigator.credentials;

function b64urlToBuf(s) {
  const pad = s.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(pad + '='.repeat((4 - (pad.length % 4)) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

function bufToB64url(buf) {
  const bytes = new Uint8Array(buf);
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** 服务端给的 options 里，challenge 和各种 id 都是 base64url 字符串，要转回 buffer。 */
export function reviveOptions(options) {
  const o = { ...options, challenge: b64urlToBuf(options.challenge) };
  if (o.user) o.user = { ...o.user, id: b64urlToBuf(o.user.id) };
  if (o.excludeCredentials) {
    o.excludeCredentials = o.excludeCredentials.map((c) => ({ ...c, id: b64urlToBuf(c.id) }));
  }
  if (o.allowCredentials) {
    o.allowCredentials = o.allowCredentials.map((c) => ({ ...c, id: b64urlToBuf(c.id) }));
  }
  return o;
}

/** 把浏览器返回的凭据转成服务端能收的 JSON。 */
export function serializeCredential(cred) {
  const r = cred.response;
  const out = {
    id: cred.id,
    rawId: bufToB64url(cred.rawId),
    type: cred.type,
    clientExtensionResults: cred.getClientExtensionResults?.() ?? {},
    response: {
      clientDataJSON: bufToB64url(r.clientDataJSON),
    },
  };
  if (r.attestationObject) {
    out.response.attestationObject = bufToB64url(r.attestationObject);
    out.response.transports = r.getTransports?.() ?? [];
  }
  if (r.authenticatorData) {
    out.response.authenticatorData = bufToB64url(r.authenticatorData);
    out.response.signature = bufToB64url(r.signature);
    out.response.userHandle = r.userHandle ? bufToB64url(r.userHandle) : null;
  }
  return out;
}

// ---------------------------------------------------------------- 设置页

export const status = () => userApi('/v1/mfa/status');

export const setRequireAll = (requireAll) =>
  userApi('/v1/mfa/settings', { method: 'POST', body: { requireAll } });

export const totpSetup = () => userApi('/v1/mfa/totp/setup', { method: 'POST' });

export const totpConfirm = (code) =>
  userApi('/v1/mfa/totp/confirm', { method: 'POST', body: { code } });

export const totpDisable = (code) =>
  userApi('/v1/mfa/totp/disable', { method: 'POST', body: { code } });

export const regenerateBackupCodes = () =>
  userApi('/v1/mfa/backup/regenerate', { method: 'POST' });

export const removePasskey = (id) =>
  userApi(`/v1/mfa/passkey/${encodeURIComponent(id)}`, { method: 'DELETE' });

/**
 * 注册一个通行密钥。
 *
 * 整个流程必须在**用户手势**里发起（点击事件的调用栈内），
 * 否则浏览器会拒绝弹出认证器。所以这里不要在中间 await 别的慢操作。
 */
export async function addPasskey(name) {
  if (!passkeySupported()) throw new Error('这个浏览器不支持通行密钥');

  const { options, token } = await userApi('/v1/mfa/passkey/register/options', { method: 'POST' });
  const cred = await navigator.credentials.create({ publicKey: reviveOptions(options) });
  if (!cred) throw new Error('没有创建通行密钥');

  return userApi('/v1/mfa/passkey/register/verify', {
    method: 'POST',
    body: { token, name: name || '通行密钥', response: serializeCredential(cred) },
  });
}

// ---------------------------------------------------------------- 登录

/**
 * 拿到登录第一步返回的 mfaToken 后，完成第二步。
 *
 * @param need  服务端给的 { methods, requireAll }
 * @param ask   回调，向用户要输入。返回 { totpCode } 或 { backupCode }
 * @returns 第二步的响应（含令牌和被包裹的 DEK）
 */
export async function completeLogin(mfaToken, need, ask) {
  const body = { mfaToken };
  const wantPasskey = need.methods.includes('passkey');
  const wantTotp = need.methods.includes('totp');

  // 「两个都要」时两样都收集；否则让用户挑一个
  const usePasskey = wantPasskey && (need.requireAll || !wantTotp || (await ask.preferPasskey()));
  const useTotp = wantTotp && (need.requireAll || !usePasskey);

  if (usePasskey) {
    const { options } = await userApi('/v1/session/mfa/options', {
      method: 'POST', auth: false, body: { mfaToken },
    });
    if (!options) throw new Error('这个账号没有可用的通行密钥');
    const cred = await navigator.credentials.get({ publicKey: reviveOptions(options) });
    if (!cred) throw new Error('没有完成通行密钥验证');
    body.passkey = serializeCredential(cred);
  }

  if (useTotp) {
    const input = await ask.code();
    if (!input) throw new Error('没有输入验证码');
    // 带连字符的是恢复码（XXXX-XXXX），纯 6 位数字是验证器的码
    if (/^\d{6}$/.test(input.replace(/\s/g, ''))) body.totpCode = input;
    else body.backupCode = input;
  }

  return userApi('/v1/session/mfa/complete', { method: 'POST', auth: false, body });
}
