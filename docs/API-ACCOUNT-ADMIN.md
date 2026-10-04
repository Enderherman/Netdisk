# 管理与个人资料

管理员身份来自当前数据库用户邮箱与 `NETDISK_ADMIN_EMAILS` 的精确匹配。用户被禁用或会话版本失效后不能继续操作。

## 管理功能

现有 `/admin` 路由保持兼容，修改操作只接受 POST。

- 用户列表只接受公开过滤条件，分页 1–100，不返回密码哈希或会话版本。
- 配额调整的 `changeSpace` 单位为 MB，是增减量；调整后不得小于正常/回收文件占用与未完成上传占用之和。不存在用户、零增量、负总额拒绝；并发调整由数据库用户行锁串行化。
- 批量文件删除格式保持历史接口，先校验全部条目，再按用户分组在事务中删除；任一非法条目不会产生部分成功。
- 初始注册配额范围为 1–1,048,576 MB；邮件标题 1–150 字，正文至多 5,000 字并必须含 `%s`。
- 系统设置写入 Redis 失败返回明确失败，不能误报保存成功。

## 个人资料

- `POST /updateProfile`：`nickName`，1–20 字，只更新昵称。
- `POST /updateUserAvatar`：multipart `avatar`，最多 2 MB、单边最多 4096 像素、最多 16 MP；解码后重编码为最大 512 像素 JPEG。
- `GET /getAvatar/{userId}`：返回真实 JPEG；未设置头像时返回生成的默认图片。头像路径不可由用户输入任意指定。

## 同源写请求

所有写操作使用 POST。浏览器写请求校验 Origin 或 Referer，来源不一致返回 HTTP 403。没有来源头的 CLI/API 调用可以使用；显式 `Sec-Fetch-Site: cross-site` 会被拒绝。

开发代理须保留 Host，NetdiskWeb 已使用 `changeOrigin: false`。反向代理如不保留外部来源，应配置逗号分隔的 `NETDISK_ALLOWED_ORIGINS`；不要用任意来源通配符。

会话 Cookie 为 HttpOnly、SameSite=Lax；HTTPS 部署设置 `NETDISK_COOKIE_SECURE=true`，本地 HTTP 保持 false。
