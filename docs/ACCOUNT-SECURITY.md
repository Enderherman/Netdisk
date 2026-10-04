# 账户接口与安全升级

所有路径使用 `/api` 前缀，表单传参及 Cookie 会话。响应沿用 `{status,code,message,data}`；`code=901` 表示需要重新登录。业务错误通常仍以 HTTP 200 返回，前端必须检查业务码。

## 部署与兼容

已有数据库先备份并执行一次 `sql/migrations/20261004_account_security.sql`，再启动新版本。全新部署直接使用 `sql/init.sql`。上述路径为 1.1.0 起的当前布局；旧版路径 `database/migrations/` 和 `database.sql` 已迁移。迁移将 password 列扩展到 255，增加 `session_version` 与邮件码 `purpose`，使无用途的旧验证码失效。

新前端所有密码均传明文，由 HTTPS 保护传输；不要在浏览器做 MD5。服务端使用 PBKDF2-HMAC-SHA256、600000 轮、16 字节随机盐、32 字节派生密钥。旧数据库 MD5 密码在用户首次正确明文登录时自动升级，其他账户不必提前重置密码。仍在客户端 MD5 的旧前端需要同步升级。

登录成功轮换 session ID。修改密码、找回密码、禁用/重新启用账户都会增加数据库会话版本，旧会话下一次请求即返回 901。禁用不会清空已用容量。管理员列表按逗号拆分、去除两端空白并忽略邮箱大小写，必须完整匹配邮箱。

## 接口

| 方法与路径 | 参数与行为 |
| --- | --- |
| `GET /accountCapabilities` | 返回动态的 `emailVerificationEnabled` 与 `qqLoginEnabled`，不暴露邮件或QQ凭据。QQ默认关闭，仅显式启用且配置完整时返回true。 |
| `GET /checkCode?type=0` | 登录、注册、找回用图形验证码；`type=1` 用于发送邮件码。验证码在提交时即消费，成功或失败都需要刷新。 |
| `POST /sendEmailCode` | `email,checkCode,type`。type 只接受 0 注册、1 找回；未配置邮件服务明确报错。 |
| `POST /register` | `email,nickName,password,checkCode,emailCode`；昵称 1–20 字符，密码 8–64 位且至少包含英文字母与数字。 |
| `POST /login` | `email,password,checkCode`；返回 `userId,nickName,avatar,isAdmin`。 |
| `POST /resetPwd` | `email,password,checkCode,emailCode`；只接受找回用途邮件码，成功后旧会话全部失效。 |
| `POST /updatePassword` | 登录后提交 `currentPassword,password`；核验当前密码，成功后所有旧会话包括当前会话失效。 |
| `GET /getUserInfo` | 当前会话用户。 |
| `GET /getUseSpace` | `{useSpace,totalSpace}`，字节。 |
| `POST /logout` | 当前会话失效。 |
| `POST /qqlogin`、`GET/POST /qqlogin/callback` | QQ OAuth已实现，未配置时明确返回未启用；一次性state、限流、同源回跳和外部验收边界见[QQ接口说明](API-QQ.md)。 |

## 邮件码和请求限制

邮件码使用安全随机数生成，5 位数字、15 分钟有效，绑定邮箱和用途，通过数据库条件更新原子消费。重新发送只使相同用途的旧码失效；注册验证码不能重置密码，反之亦然。

Redis Lua 原子计数用于跨实例限流：每邮箱 60 秒 1 次、每小时 5 次发信；每 IP 每小时 20 次发信；每邮箱每用途 15 分钟 5 次验证码校验；每邮箱 15 分钟 10 次登录、每 IP 15 分钟 50 次登录；每账户 15 分钟 5 次改密。限制包括失败尝试，Redis 不可用时不放行。限流键只存身份 SHA-256 摘要。

自动测试模拟邮件发送与限流器，不向真实邮箱发送测试邮件。真实 SMTP 连通性需在部署方专用测试环境验证。
