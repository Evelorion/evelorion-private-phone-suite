import Database from 'better-sqlite3';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { config } from './config.ts';

mkdirSync(dirname(config.dbPath), { recursive: true });

export const db = new Database(config.dbPath);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');
db.pragma('synchronous = NORMAL');

db.exec(`
CREATE TABLE IF NOT EXISTS accounts (
  id                TEXT PRIMARY KEY,
  username          TEXT NOT NULL UNIQUE COLLATE NOCASE,
  kdf_salt          BLOB NOT NULL,
  kdf_mem           INTEGER NOT NULL,
  kdf_time          INTEGER NOT NULL,
  kdf_par           INTEGER NOT NULL,
  auth_hash         TEXT NOT NULL,
  -- 恢复码派生的认证凭据的哈希。
  --
  -- 有了它，恢复码本身就能当登录凭据用 —— 否则「忘记主口令」是个死局：
  -- 恢复码能解开 DEK，但登录这一关需要口令派生的 authSecret，过不去。
  --
  -- 服务器仍然什么都解不开：这里存的是 Argon2 哈希，
  -- 和 auth_hash 一样推不回原值。
  --
  -- 可空是为了兼容加这个功能之前注册的账号。
  recovery_auth_hash TEXT,
  dek_wrap_password BLOB NOT NULL,
  dek_wrap_recovery BLOB NOT NULL,
  vault_version     INTEGER NOT NULL DEFAULT 1,
  seq               INTEGER NOT NULL DEFAULT 0,
  created_at        INTEGER NOT NULL,
  disabled          INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS mfa_settings (
  account_id      TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  totp_enabled    INTEGER NOT NULL DEFAULT 0,
  passkey_enabled INTEGER NOT NULL DEFAULT 0,
  -- 0 = 任一验证方式通过即可，1 = 两个都必须过。
  -- 默认 0：多数人只会配一个，默认要求两个都过会把自己锁在外面。
  require_all     INTEGER NOT NULL DEFAULT 0,
  updated_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS mfa_totp (
  account_id  TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  secret      TEXT NOT NULL,
  -- 未确认的密钥不能用来验证。用户扫了码但没输对第一个验证码就退出，
  -- 不该把账号锁死在一个他根本没存进认证器的密钥上。
  confirmed_at INTEGER,
  created_at  INTEGER NOT NULL
);

-- 恢复码。认证器丢了的时候用，每个只能用一次。
CREATE TABLE IF NOT EXISTS mfa_backup_codes (
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  code_hash  TEXT NOT NULL,
  used_at    INTEGER,
  PRIMARY KEY (account_id, code_hash)
);

CREATE TABLE IF NOT EXISTS mfa_passkeys (
  id            TEXT PRIMARY KEY,
  account_id    TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  credential_id TEXT NOT NULL UNIQUE,
  public_key    BLOB NOT NULL,
  -- 认证器每次签名都会把计数器 +1。收到的计数器不比存的大，
  -- 说明这个凭据可能被克隆了 —— 这是 WebAuthn 规范要求检查的。
  sign_count    INTEGER NOT NULL DEFAULT 0,
  transports    TEXT NOT NULL DEFAULT '',
  name          TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER
);
CREATE INDEX IF NOT EXISTS idx_passkeys_account ON mfa_passkeys(account_id);

-- 挑战值。注册和登录时服务端发一个随机数，客户端签名后带回来，
-- 用完立刻删。不删的话同一个挑战能被重放。
CREATE TABLE IF NOT EXISTS mfa_challenges (
  token      TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  challenge  TEXT NOT NULL,
  -- 'register' | 'login'
  purpose    TEXT NOT NULL,
  -- 登录用的挑战附带设备名，验证通过后才真正建设备记录
  device_name TEXT NOT NULL DEFAULT '',
  expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id           TEXT PRIMARY KEY,
  account_id   TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  revoked      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_devices_account ON devices(account_id);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash  BLOB PRIMARY KEY,
  account_id  TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  device_id   TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  issued_at   INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  consumed_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_refresh_device ON refresh_tokens(device_id);

-- 一条 record = 一个联系人或一条通话记录。服务端只见到 nonce + ciphertext。
-- collection 用来区分两类数据：通讯录 App 和电话 App 用的是不同的密钥，
-- 混在一起拉取的话，各自都会拿到一堆自己解不开的密文。
CREATE TABLE IF NOT EXISTS records (
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  collection TEXT NOT NULL DEFAULT 'contacts',
  uuid       TEXT NOT NULL,
  seq        INTEGER NOT NULL,
  rev        INTEGER NOT NULL,
  deleted    INTEGER NOT NULL DEFAULT 0,
  schema_ver INTEGER NOT NULL DEFAULT 1,
  nonce      BLOB,
  ciphertext BLOB,
  size       INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  device_id  TEXT,
  PRIMARY KEY (account_id, collection, uuid)
);
CREATE INDEX IF NOT EXISTS idx_records_seq ON records(account_id, collection, seq);

-- 删除归档。
--
-- 墓碑会把 records 里那一行的密文覆盖成空，删除就此不可逆 ——
-- 一个客户端 bug 曾经在一次同步里推上来 6 条墓碑，把用户全部联系人抹掉，
-- 密文当场消失，只能从 SQLite 的 WAL 里逐帧回滚才捞回来。
--
-- 服务器读不懂这些密文，但**保管**它们是它能做的事。
-- 覆盖之前先抄一份到这里，给用户留一个后悔期。
--
-- 这不违反零知识：存的还是同一份密文，服务器依然没有密钥。
-- 代价是删除不再是立即物理删除，保留期内数据仍在盘上 ——
-- 想要立即彻底删的用户可以调 purge 接口。
CREATE TABLE IF NOT EXISTS deleted_records (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  collection TEXT NOT NULL DEFAULT 'contacts',
  uuid       TEXT NOT NULL,
  rev        INTEGER NOT NULL,
  schema_ver INTEGER NOT NULL DEFAULT 1,
  nonce      BLOB,
  ciphertext BLOB,
  size       INTEGER NOT NULL DEFAULT 0,
  deleted_at INTEGER NOT NULL,
  device_id  TEXT
);
CREATE INDEX IF NOT EXISTS idx_deleted_records ON deleted_records(account_id, collection, deleted_at);

-- 头像等大对象，内容寻址（hash 由客户端用 DEK 派生的 HMAC 计算，服务端不验证语义）
CREATE TABLE IF NOT EXISTS blobs (
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  hash       TEXT NOT NULL,
  nonce      BLOB NOT NULL,
  ciphertext BLOB NOT NULL,
  size       INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (account_id, hash)
);

-- ============ 管理后台 ============
-- 管理员和用户账号是**完全分开的两套认证**。
-- 管理员没有任何密钥，看不到也解不开任何一条联系人 —— 这是端到端加密的必然结果，
-- 不是功能缺失。管理员能做的是管账号，不是看数据。
CREATE TABLE IF NOT EXISTS admins (
  id            TEXT PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  last_login_at INTEGER,
  disabled      INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS admin_sessions (
  token_hash BLOB PRIMARY KEY,
  admin_id   TEXT NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  ip         TEXT,
  user_agent TEXT
);
CREATE INDEX IF NOT EXISTS idx_admin_sessions_admin ON admin_sessions(admin_id);

-- 邀请码。取代写死在 .env 里的那个单一 REGISTRATION_TOKEN。
-- 只存哈希，管理员生成后也只显示一次 —— 拖库拿不到能用的邀请码。
CREATE TABLE IF NOT EXISTS invites (
  id         TEXT PRIMARY KEY,
  code_hash  BLOB NOT NULL UNIQUE,
  label      TEXT NOT NULL DEFAULT '',
  created_by TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER,
  max_uses   INTEGER NOT NULL DEFAULT 1,
  used_count INTEGER NOT NULL DEFAULT 0,
  revoked    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS auth_attempts (
  key        TEXT NOT NULL,
  at         INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_attempts ON auth_attempts(key, at);
`);

/**
 * 给早于 collection 之前部署的库补列。
 * SQLite 的 ALTER TABLE ADD COLUMN 是常数时间的，跑在启动路径上没问题。
 * 主键改不了，但老库的 (account_id, uuid) 主键在只有 contacts 一类数据时
 * 行为等价，所以不强制重建表。
 */
function migrateAddCollection(): void {
  const columns = db.prepare('PRAGMA table_info(records)').all() as Array<{ name: string }>;
  if (!columns.some((c) => c.name === 'collection')) {
    db.exec("ALTER TABLE records ADD COLUMN collection TEXT NOT NULL DEFAULT 'contacts'");
    db.exec('CREATE INDEX IF NOT EXISTS idx_records_seq2 ON records(account_id, collection, seq)');
  }
}
migrateAddCollection();

/**
 * 给加这个功能之前注册的账号补上 recovery_auth_hash 列。
 *
 * 补的是**列**不是值 —— 老账号的值仍然是 NULL，意味着它们还不能用
 * 恢复码登录。客户端在用口令登录成功后会提示重新生成一次恢复码，
 * 那时才会填上。没法自动补：服务器手里根本没有恢复码。
 */
function migrateAddRecoveryAuth(): void {
  const cols = db.prepare('PRAGMA table_info(accounts)').all() as { name: string }[];
  if (!cols.some((c) => c.name === 'recovery_auth_hash')) {
    db.exec('ALTER TABLE accounts ADD COLUMN recovery_auth_hash TEXT');
  }
}
migrateAddRecoveryAuth();

/** 允许的 collection。不做白名单的话客户端可以拿它当任意键值存储用。 */
export const COLLECTIONS = ['contacts', 'calls'] as const;
export type Collection = (typeof COLLECTIONS)[number];

export type AccountRow = {
  id: string;
  username: string;
  kdf_salt: Buffer;
  kdf_mem: number;
  kdf_time: number;
  kdf_par: number;
  auth_hash: string;
  recovery_auth_hash: string | null;
  dek_wrap_password: Buffer;
  dek_wrap_recovery: Buffer;
  vault_version: number;
  seq: number;
  created_at: number;
  disabled: number;
};

export type AdminRow = {
  id: string;
  username: string;
  password_hash: string;
  created_at: number;
  last_login_at: number | null;
  disabled: number;
};

export type InviteRow = {
  id: string;
  code_hash: Buffer;
  label: string;
  created_by: string | null;
  created_at: number;
  expires_at: number | null;
  max_uses: number;
  used_count: number;
  revoked: number;
};

export type RecordRow = {
  account_id: string;
  collection: string;
  uuid: string;
  seq: number;
  rev: number;
  deleted: number;
  schema_ver: number;
  nonce: Buffer | null;
  ciphertext: Buffer | null;
  size: number;
  updated_at: number;
  device_id: string | null;
};

/** 分配下一个账号级序列号。所有写操作都在同一个事务里调用它。 */
export function nextSeq(accountId: string): number {
  db.prepare('UPDATE accounts SET seq = seq + 1 WHERE id = ?').run(accountId);
  const row = db.prepare('SELECT seq FROM accounts WHERE id = ?').get(accountId) as { seq: number } | undefined;
  if (!row) throw new Error('account not found');
  return row.seq;
}

/** 清理过期墓碑和过期刷新令牌。启动时和每天跑一次。 */
export function sweep(): void {
  const now = Date.now();
  const cutoff = now - config.tombstoneTtlDays * 86400_000;
  db.prepare('DELETE FROM records WHERE deleted = 1 AND updated_at < ?').run(cutoff);
  db.prepare('DELETE FROM refresh_tokens WHERE expires_at < ?').run(now);
  db.prepare('DELETE FROM auth_attempts WHERE at < ?').run(now - 3600_000);
  db.prepare('DELETE FROM admin_sessions WHERE expires_at < ?').run(now);
  db.prepare('DELETE FROM mfa_challenges WHERE expires_at < ?').run(now);
  // 用完或过期的邀请码留 30 天供管理员回看，之后清掉
  db.prepare(
    `DELETE FROM invites WHERE created_at < ?
     AND (revoked = 1 OR (max_uses > 0 AND used_count >= max_uses) OR (expires_at IS NOT NULL AND expires_at < ?))`
  ).run(now - 30 * 86400_000, now);
  // 未被任何 record 引用的 blob 由客户端显式删除；这里只清理孤儿账号残留
  db.prepare('DELETE FROM blobs WHERE account_id NOT IN (SELECT id FROM accounts)').run();
}
