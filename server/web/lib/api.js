/**
 * 和服务端说话的那一层。
 *
 * 分成两套，**刻意不共用凭据**：
 *   userApi   Bearer 令牌，401 时自动用刷新令牌续一次
 *   adminApi  HttpOnly Cookie + X-Admin-Request 头
 *
 * 管理员会话拿不到任何用户数据端点，用户令牌也进不了管理端点。
 * 这不是靠"没写"来保证的，是服务端 requireAdmin() 和 requireAuth()
 * 两个独立函数各认各的凭据。
 */

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message || code);
    this.status = status;
    this.code = code;
  }
}

// ---------------------------------------------------------------- 用户端

/**
 * 令牌存在内存里，**不进 localStorage**。
 *
 * localStorage 里的东西任何一段 XSS 都能读走，而且刷新页面也不会消失。
 * 放内存的代价是刷新页面要重新解锁 —— 对一个偶尔用一次的网页端来说，
 * 这个代价换来的安全性是划算的。
 */
let tokens = { access: null, refresh: null, expiresAt: 0 };

export function setTokens(access, refresh, expiresAt) {
  tokens = { access, refresh, expiresAt: expiresAt || 0 };
}

export function clearTokens() {
  tokens = { access: null, refresh: null, expiresAt: 0 };
}

export const hasSession = () => tokens.access !== null;

export async function userApi(path, { method = 'GET', body, auth = true, retry = true } = {}) {
  // 没有 body 就别发 content-type —— 带着它而 body 为空的话，
  // 严格的服务端会判成畸形请求。
  const headers = body === undefined ? {} : { 'content-type': 'application/json' };
  if (auth) {
    if (!tokens.access) throw new ApiError(401, 'no_session', '尚未登录');
    headers.authorization = `Bearer ${tokens.access}`;
  }

  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }

  if (res.ok) return data;

  // 访问令牌 15 分钟过期是常态，静默续一次
  if (res.status === 401 && auth && retry && tokens.refresh && data.error !== 'refresh_token_reuse') {
    const renewed = await refreshTokens();
    if (renewed) return userApi(path, { method, body, auth, retry: false });
  }

  throw new ApiError(res.status, data.error ?? `http_${res.status}`, data.message ?? `HTTP ${res.status}`);
}

async function refreshTokens() {
  try {
    const data = await userApi('/v1/session/refresh', {
      method: 'POST',
      body: { refreshToken: tokens.refresh },
      auth: false,
      retry: false,
    });
    setTokens(data.accessToken, data.refreshToken, data.accessExpiresAt);
    return true;
  } catch (e) {
    // 刷新令牌重放会导致服务端吊销设备，这时不能重试，只能重新登录
    clearTokens();
    return false;
  }
}

// ---------------------------------------------------------------- 管理端

export async function adminApi(path, { method = 'GET', body } = {}) {
  const res = await fetch(path, {
    method,
    // x-admin-request 是 CSRF 的第二道防线：浏览器不会在跨站表单提交里带自定义头
    headers: body === undefined
      ? { 'x-admin-request': '1' }
      : { 'content-type': 'application/json', 'x-admin-request': '1' },
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }

  if (res.ok) return data;
  throw new ApiError(res.status, data.error ?? `http_${res.status}`, data.message ?? `HTTP ${res.status}`);
}
