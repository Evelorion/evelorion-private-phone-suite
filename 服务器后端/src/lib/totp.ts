import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

/**
 * TOTP（RFC 6238）。
 *
 * ── 为什么自己实现 ──────────────────────────────────────────
 *
 * TOTP 就是「HMAC-SHA1 + 动态截断」，四十行。引一个库进来反而多一个
 * 供应链风险点 —— 而 WebAuthn 那种涉及 CBOR 解析和 COSE 密钥的，
 * 我用的是成熟库，两者标准不同：算法简单且规范明确的可以自己写，
 * 涉及复杂二进制解析和多种签名算法的不要。
 *
 * ── 参数 ────────────────────────────────────────────────────
 *
 * SHA1 / 6 位 / 30 秒 —— 这是 RFC 4226 的默认值，也是所有认证器
 * （Google Authenticator、1Password、Authy…）唯一保证支持的组合。
 * 换成 SHA256 会有一部分认证器算出来对不上，而用户根本不知道为什么。
 */

const DIGITS = 6;
const PERIOD_SECONDS = 30;

/**
 * 允许的时间窗口偏移。
 *
 * ±1 表示接受前后各 30 秒的验证码。手机和服务器的时钟差几秒是常态，
 * 不给窗口的话用户会在「码明明是对的」的情况下反复失败。
 * 放太宽（比如 ±5）等于把一个码的有效期拉到 5 分钟，被偷看后可利用的窗口也变长。
 */
const WINDOW = 1;

const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

/** 生成 20 字节（160 位）密钥，这是 RFC 4226 推荐的长度。 */
export function generateSecret(): string {
  return base32Encode(randomBytes(20));
}

export function base32Encode(buf: Buffer): string {
  let bits = 0;
  let value = 0;
  let out = '';
  for (const byte of buf) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += BASE32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += BASE32_ALPHABET[(value << (5 - bits)) & 31];
  return out;
}

export function base32Decode(input: string): Buffer {
  // 认证器扫码后用户可能手抄，空格和小写要容忍；= 是填充，忽略
  const clean = input.toUpperCase().replace(/[\s=]/g, '');
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const ch of clean) {
    const idx = BASE32_ALPHABET.indexOf(ch);
    if (idx < 0) throw new Error('密钥不是合法的 Base32');
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

/** 算某个时间步的验证码。 */
function codeAt(secret: Buffer, counter: number): string {
  // 计数器是 8 字节大端。用 BigInt 是因为 2038 年之后 counter 仍在
  // 32 位范围内，但写成 BigInt 就不用考虑这条边界了
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));

  const hmac = createHmac('sha1', secret).update(buf).digest();

  // RFC 4226 的动态截断：取最后一个字节的低 4 位当偏移量。
  //
  // tsconfig 开了 noUncheckedIndexedAccess，索引访问的类型是 `number | undefined`。
  // HMAC-SHA1 恒定 20 字节、offset ∈ [0,15]，越界不可能发生，
  // 但与其写 `!` 断言不如让编译器满意 —— 用 readUInt32BE 一次读四个字节，
  // 既没有索引访问，也少了四次移位。
  const offset = (hmac[hmac.length - 1] as number) & 0x0f;
  const binary = hmac.readUInt32BE(offset) & 0x7fffffff;

  return (binary % 10 ** DIGITS).toString().padStart(DIGITS, '0');
}

export function currentCode(secretBase32: string, at = Date.now()): string {
  return codeAt(base32Decode(secretBase32), Math.floor(at / 1000 / PERIOD_SECONDS));
}

/**
 * 校验。
 *
 * 比对用 timingSafeEqual —— 逐字符比较会因为提前返回而泄露
 * 「前几位对了」的信息，理论上可以被用来逐位爆破。
 */
export function verifyCode(secretBase32: string, code: string, at = Date.now()): boolean {
  const clean = code.replace(/\s/g, '');
  if (!/^\d{6}$/.test(clean)) return false;

  const secret = base32Decode(secretBase32);
  const step = Math.floor(at / 1000 / PERIOD_SECONDS);
  const given = Buffer.from(clean, 'utf8');

  for (let d = -WINDOW; d <= WINDOW; d++) {
    const expected = Buffer.from(codeAt(secret, step + d), 'utf8');
    if (expected.length === given.length && timingSafeEqual(expected, given)) return true;
  }
  return false;
}

/**
 * 生成 otpauth:// URI，认证器扫这个二维码。
 *
 * issuer 同时放在路径前缀和查询参数里 —— 这是 Google 的约定，
 * 只放一处的话有些认证器显示成「未知」。
 */
export function otpauthUri(username: string, secret: string, issuer: string): string {
  const label = encodeURIComponent(`${issuer}:${username}`);
  const params = new URLSearchParams({
    secret,
    issuer,
    algorithm: 'SHA1',
    digits: String(DIGITS),
    period: String(PERIOD_SECONDS),
  });
  return `otpauth://totp/${label}?${params.toString()}`;
}

/**
 * 恢复码。认证器丢了的时候用。
 *
 * 每个码只能用一次，用完从库里删掉 —— 留着的话，从备份里翻出旧数据库
 * 就能把已经用过的码再用一遍。
 */
export function generateBackupCodes(count = 8): string[] {
  return Array.from({ length: count }, () => {
    const raw = base32Encode(randomBytes(5)).slice(0, 8);
    return `${raw.slice(0, 4)}-${raw.slice(4)}`;
  });
}
