-- 2026-09-21 贝壳课表（BeikeSchedule）云备份
-- 两张独立表，与 showwe 社区的 users 等表完全隔离（共用 MySQL 库，不共用任何账号数据）。
-- 账号 = 学号：客户端内嵌教务 WebView 完成统一身份认证（扫码或账密）后回传学号，
-- 学号即账号、无密码——教务登录本身就是身份验证，服务端信任回传的学号。
-- 已有库执行: mysql -u <user> -p <db> < migrations/2026-09-21-beike-backup.sql
-- 新库直接用 schema.sql，无需执行本文件

CREATE TABLE IF NOT EXISTS bs_users (
  xh VARCHAR(20) PRIMARY KEY COMMENT '学号（教务统一认证登录成功后由客户端回传）',
  name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '姓名（登录时带回，仅展示用）',
  token_version INT NOT NULL DEFAULT 0 COMMENT '递增使旧 token 失效（预留）',
  created_at BIGINT NOT NULL,
  last_login_at BIGINT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS bs_backup (
  xh VARCHAR(20) PRIMARY KEY,
  data MEDIUMTEXT NOT NULL COMMENT '整包快照 JSON（课表/节次/成绩/考试/日程 + 配置）',
  size_bytes INT NOT NULL DEFAULT 0,
  app_version_code INT NOT NULL DEFAULT 0 COMMENT '上传时的客户端 versionCode，便于排查兼容问题',
  updated_at BIGINT NOT NULL,
  CONSTRAINT fk_bs_backup_user FOREIGN KEY (xh) REFERENCES bs_users (xh) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
