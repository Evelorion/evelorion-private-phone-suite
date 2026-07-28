import Fastify from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { config } from './config.ts';
import { sweep } from './db.ts';
import { sendError } from './lib/http.ts';
import { registerAccountRoutes } from './routes/account.ts';
import { registerSyncRoutes } from './routes/sync.ts';
import { registerBlobRoutes } from './routes/blobs.ts';
import { registerAdminRoutes } from './routes/admin.ts';
import { registerMfaRoutes } from './routes/mfa.ts';
import fastifyStatic from '@fastify/static';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const app = Fastify({
  logger: {
    level: 'info',
    // 绝不记录请求体：里面全是密文，但记下来只会扩大泄露面
    redact: ['req.headers.authorization', 'req.headers.cookie'],
    serializers: {
      req(req) {
        return { method: req.method, url: req.url.split('?')[0], remoteAddress: req.ip };
      },
    },
  },
  trustProxy: config.trustProxy,
  bodyLimit: config.maxBlobBytes + 64 * 1024,
});

await app.register(rateLimit, {
  max: 600,
  timeWindow: '1 minute',
  keyGenerator: (req) => req.ip,
});

/**
 * 允许 content-type 是 json 但 body 为空的请求。
 *
 * Fastify 默认会对这种请求直接返回 400。但 DELETE 和某些 POST（比如登出）
 * 本来就没有 body，而浏览器的 fetch 包装通常无脑带上 content-type ——
 * 结果就是删除和退出按钮全挂。这个坑是实测出来的。
 */
app.addContentTypeParser('application/json', { parseAs: 'string' }, (_req, body, done) => {
  const text = (body as string).trim();
  if (text === '') return done(null, {});
  try {
    done(null, JSON.parse(text));
  } catch (e) {
    done(new Error('请求体不是合法 JSON'), undefined);
  }
});

app.setErrorHandler((err, _req, reply) => {
  if ((err as { statusCode?: number }).statusCode === undefined) app.log.error(err);
  // 注意不要 return —— sendError 内部已经 reply.send() 过了，
  // 再把返回的 reply 对象交给 Fastify 会被当成 payload 二次发送，
  // 报 "Attempted to send payload of invalid type 'object'"。
  sendError(reply, err);
});

app.get('/v1/health', async () => ({ ok: true, time: Date.now() }));

/**
 * 网页端的静态文件。
 *
 * CSP 收得很紧：不允许任何外部来源。原因是网页版 E2EE 有个固有弱点 ——
 * JS 是服务器发的，服务器被攻破就能发一段偷口令的脚本。
 * 挡不住这个（只能靠 App 或者浏览器扩展），但至少要保证
 * 不会因为某个 CDN 被投毒而多一条攻击路径。
 */
const webRoot = join(dirname(fileURLToPath(import.meta.url)), '..', 'web');
await app.register(fastifyStatic, {
  root: webRoot,
  prefix: '/',
  index: ['index.html'],
  setHeaders(reply) {
    reply.header(
      'Content-Security-Policy',
      [
        "default-src 'self'",
        "script-src 'self' 'wasm-unsafe-eval'",   // Argon2 是 WASM，必须允许
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data: blob:",
        "connect-src 'self'",
        "frame-ancestors 'none'",
        "base-uri 'none'",
        "form-action 'none'",
      ].join('; ')
    );
    reply.header('X-Content-Type-Options', 'nosniff');
    reply.header('Referrer-Policy', 'no-referrer');
    reply.header('Cross-Origin-Opener-Policy', 'same-origin');
  },
});

registerAccountRoutes(app);
registerAdminRoutes(app);
registerSyncRoutes(app);
registerBlobRoutes(app);
registerMfaRoutes(app);

sweep();
setInterval(sweep, 86400_000).unref();

await app.listen({ host: config.host, port: config.port });
