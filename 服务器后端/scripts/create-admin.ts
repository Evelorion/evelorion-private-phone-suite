/**
 * 命令行创建管理员。
 *
 *   docker compose exec sync node --experimental-strip-types scripts/create-admin.ts <用户名>
 *
 * 口令从标准输入读，不走命令行参数 —— 参数会留在 shell 历史和 ps 输出里。
 *
 * 网页上也有一个「首次引导」入口（/admin 会自动引导），但那个在有了第一个
 * 管理员之后就永久关闭。之后再加管理员只能用这个脚本，
 * 这样即使后台被人登进去也没法悄悄加一个新管理员。
 */
import { createInterface } from 'node:readline';
import { db } from '../src/db.ts';
import { uuid } from '../src/lib/crypto.ts';
import { hashAdminPassword, checkPasswordStrength, countAdmins } from '../src/lib/admin.ts';

function ask(question: string, hidden = false): Promise<string> {
  const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  return new Promise((resolve) => {
    if (hidden) {
      // 关掉回显，别把口令打在屏幕上
      const stdin = process.stdin as NodeJS.ReadStream & { isTTY?: boolean };
      const onData = (char: Buffer) => {
        const s = char.toString();
        if (s === '\n' || s === '\r' || s === '') {
          stdin.removeListener('data', onData);
        } else {
          process.stdout.write('\x1b[2K\r' + question + '*'.repeat(rl.line?.length ?? 0));
        }
      };
      stdin.on('data', onData);
    }
    rl.question(question, (answer) => {
      rl.close();
      if (hidden) process.stdout.write('\n');
      resolve(answer);
    });
  });
}

const username = (process.argv[2] ?? '').trim();
if (!username) {
  console.error('用法：node --experimental-strip-types scripts/create-admin.ts <用户名>');
  process.exit(1);
}
if (!/^[a-zA-Z0-9._-]{3,64}$/.test(username)) {
  console.error('用户名只能包含字母、数字、点、下划线、连字符，长度 3~64');
  process.exit(1);
}

const existing = db.prepare('SELECT 1 FROM admins WHERE username = ?').get(username);
if (existing) {
  console.error(`管理员 ${username} 已存在。改口令请在网页后台操作。`);
  process.exit(1);
}

console.log(`\n创建管理员 ${username}（当前已有 ${countAdmins()} 个管理员）\n`);
console.log('提示：管理员看不到任何联系人内容 —— 服务器上只有密文。');
console.log('     这个账号能做的是管账号、发邀请码、看统计。\n');

const password = await ask('设置口令（至少 12 位）：', true);
const weak = checkPasswordStrength(password);
if (weak) {
  console.error('口令太弱：' + weak);
  process.exit(1);
}
const again = await ask('再输一遍确认：', true);
if (password !== again) {
  console.error('两次输入不一致');
  process.exit(1);
}

db.prepare('INSERT INTO admins (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)')
  .run(uuid(), username, await hashAdminPassword(password), Date.now());

console.log(`\n✓ 管理员 ${username} 已创建，去 /admin 登录\n`);
process.exit(0);
