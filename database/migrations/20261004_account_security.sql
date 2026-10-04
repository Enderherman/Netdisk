-- 现有数据库升级：先备份，再在目标 Netdisk 数据库中执行一次。
-- 新部署直接使用根目录 database.sql，不再重复执行本迁移。
ALTER TABLE user_info MODIFY COLUMN password VARCHAR(255) NULL COMMENT '带盐密码哈希';
ALTER TABLE user_info ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0 COMMENT '会话撤销版本';
ALTER TABLE email_code ADD COLUMN purpose TINYINT NOT NULL DEFAULT 0 COMMENT '0:注册 1:找回密码';
ALTER TABLE email_code DROP PRIMARY KEY, ADD PRIMARY KEY (email, code, purpose);
-- 旧验证码没有用途信息，全部失效，要求重新发送。
UPDATE email_code SET status = 1;
