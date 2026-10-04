# QQ 登录配置与接口

QQ 登录默认关闭。只有管理员显式启用、填写完整有效的应用配置后，`GET /api/accountCapabilities` 中的 `data.qqLoginEnabled` 才为 `true`。未配置时继续使用邮箱账号，系统不会尝试连接 QQ。

## 部署配置

| 环境变量 | 含义 | 默认值 |
| --- | --- | --- |
| `NETDISK_QQ_ENABLED` | 显式启用 QQ 登录 | `false` |
| `NETDISK_QQ_APP_ID` | QQ 互联应用 ID，数字 | 空 |
| `NETDISK_QQ_APP_KEY` | 应用密钥，只配置在后端 | 空 |
| `NETDISK_QQ_REDIRECT` | QQ 平台登记的后端回调地址 | 空 |

推荐登记 `https://网盘域名/api/qqlogin/callback`，由同源 Nginx 将 `/api/` 转发给后端。必须保留回调查询参数和会话 Cookie。不要使用后端容器名、内部端口，或让浏览器先进入后端独立地址：回调的相对 `303` 跳转需要回到前端域名下的 `/drive` 或 `/auth/login`。线上回调必须使用 HTTPS；仅本机 `localhost`、`127.0.0.1`、`::1` 支持 HTTP。生产环境还应设置 `NETDISK_COOKIE_SECURE=true`。

应用密钥不要放进前端构建参数、仓库、浏览器存储或访问日志。代理访问日志应仅记录路径 `$uri`，不要记录包含授权码的 `$request`、`$request_uri` 或查询参数；不应开启外发 OAuth 请求的完整 URL 日志。客户端来源限流使用与邮箱登录相同的 `request.remoteAddr`，不读取任意客户端传入的 `X-Forwarded-For`。位于反向代理后时，默认按代理地址共享限额；如需真实客户端 IP，仅在受信代理层配置和校验转发来源。

## 请求流程

1. 前端查询 `GET /api/accountCapabilities`，仅在 `qqLoginEnabled=true` 时提供 QQ 登录按钮。
2. 用户点击按钮后，以当前 Cookie 会话发送 `POST /api/qqlogin`，表单参数 `callBackUrl` 可省略；其含义是授权后返回的**站内绝对路径**，例如 `/drive` 或 `/s/abc123?path=root`。默认 `/drive`。不是 QQ 平台登记的回调 URL，不接受外部 URL、双斜杠、反斜杠、编码重定向、控制字符或 `/auth` 路径。
3. 成功结果沿用统一响应：`{"code":200,"data":"https://graph.qq.com/oauth2.0/authorize?..."}`（另有统一 `message/status` 字段）。`data` 直接是字符串。前端检查固定 HTTPS 官方授权地址后导航即可；不要保存授权码或令牌。
4. QQ 使用浏览器 `GET /api/qqlogin/callback?code=...&state=...` 回到后端。后端校验并一次性消费 `state`，完成换票、账号查询或开户，轮换会话 ID，再 `303 Location: /drive`（或可信 state 中保存的返回路径）。响应体为空。
5. 前端重新读取 `/api/getUserInfo`，沿用普通 Cookie 会话。

`GET /api/qqlogin` 保留为兼容探测，仅返回业务错误，不创建或改写 state。发起授权必须使用 POST 并遵守项目现有同源请求约束。

## 失败与兼容接口

GET 回调失败固定 `303` 回到 `/auth/login?qqError=...`，仅允许以下类别，响应体为空。不携带原始提供者错误、授权码、state、令牌或未经验证的返回地址。

| 类别 | 前端提示含义 |
| --- | --- |
| `cancelled` | 用户取消或未同意授权 |
| `expired` | state 缺失、不匹配、过期、重放，或授权码无效 |
| `unavailable` | 功能未启用、配置不全或服务暂不可用 |
| `failed` | 账号不可用、提供者拒绝、请求过频等其他失败 |

兼容 `POST /api/qqlogin/callback`，参数同 GET。成功 `data` 为 `{"callbackUrl":"/drive","userInfo":{...}}`；失败保留项目统一 JSON 业务错误。`userInfo` 与普通登录一致，不返回 OpenID、密码、sessionVersion、code、secret 或 token。新前端推荐使用 GET 后端回调，无须接触授权码。

上述响应均带 `Cache-Control: no-store` 与 `Referrer-Policy: no-referrer`。

## 账号与安全规则

- state 来自 32 字节安全随机数，会话仅保存摘要与有效期，5 分钟到期。匹配后先消费再访问提供者，失败、重复和并发回调都不能复用。错误 state 不消费另一条有效授权；重新发起会替换旧 state。
- 按客户端来源做 Redis 原子窗口限流：发起 30 次 / 15 分钟，GET 与 POST 回调合计 20 次 / 15 分钟。Redis 不可用时不放行；不能通过新会话或伪造转发头绕过。启用状态才使用 QQ 限流。
- 固定请求 `graph.qq.com` 的 authorize、token、me 和 get_user_info 官方路径；旧可配置 URL 模板不参与请求。连接超时 3 秒、读超时 5 秒，禁止重定向，每个响应最多 16 KiB，并限制连续读取时长。
- 仅解析 JSON、严格 `callback(JSON)` 或表单令牌响应，不执行脚本。拒绝重复 JSON 字段、额外尾随内容、提供者错误、错误 client_id、非 32 位十六进制 OpenID、非成功资料结果或无效 UTF-8。
- QQ OpenID 是唯一映射依据，不按昵称、邮箱或头像合并本地账号。并发首次登录由既有唯一索引与新事务重试保证一条映射。新账号无邮箱、无本地密码；管理员身份只由已存在的本地邮箱配置决定，新 QQ 账号不会因昵称获得管理员权限。
- QQ 独立账号暂不支持绑定邮箱或设置本地密码；密码修改仍要求现有本地密码。已有本地密码的 QQ 映射保留原有密码能力。QQ 资料不能直接为账号增加邮箱、修改角色或覆盖用户已设置的本地昵称与头像。
- 初始容量须为 1～1,048,576 MB。读取现有用户时加行锁并检查禁用状态、容量和 sessionVersion；保留原容量及会话撤销版本。头像仅允许 HTTPS 的 `qlogo.cn` 域，异常资料中的凭证被丢弃。
- 授权码、应用密钥与 access token 只存在于服务端换票阶段，不写入账号、Session 或响应，也不记录提供者原始响应或可能含密钥的底层异常。

## 验证范围

QQ 专项测试包括配置开关、固定官方端点、模拟 HTTPS 换票、JSON/JSONP/form 解析边界、state 会话绑定和消费、失败跳转、会话轮换、重放和并发回调、并发开户、禁用账号、角色隔离、容量校验、头像与凭证过滤及真实限流器的模拟 Redis 边界。

测试使用 H2、模拟 Redis、模拟网关或内存 HTTPS 响应，未配置真实 QQ 应用凭据，未发起真实 QQ 请求。发布前仍需运营方在 QQ 互联登记正式回调域名，并用真实浏览器与 QQ 账号完成外部验收；这一步不应被模拟测试结果代替。
