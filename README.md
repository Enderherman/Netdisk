# Netdisk

基于程序员老罗 EasyPan 教程手写实现的个人网盘，正在补齐自动化测试、修复历史业务缺陷，并配套新的 [NetdiskWeb](https://github.com/Enderherman/NetdiskWeb)。

当前版本：**0.7.2**。已有后端核心模块已建立回归，并提供上传任务查询、续传与取消；继续补齐文件便利功能与完整前端。

## 技术与现有模块

Java 17、Spring Boot 3.5.16、MyBatis starter 3.0.5、MySQL 8、Redis。图片缩略图使用 Java ImageIO；视频直接以原件 Range 方式供浏览器播放，不依赖 ffmpeg 完成上传。

现有代码包括账号注册登录、邮件验证码、文件上传/下载/目录管理、分享、回收站及管理后台。逐项实测与修复状态见 [验收清单](docs/ACCEPTANCE.md)。新前端为独立仓库，历史 `dist/` 仅为旧构建产物。

## 启动

1. 安装 JDK 17、Maven 3.9、MySQL 8、Redis。
2. 在独立实例或确认为空的数据库中执行 `database.sql`。该脚本建立 `netdisk` 数据库，不应直接对已有业务数据执行。
   已有数据库升级至本版前，备份后执行一次 `database/migrations/20261004_account_security.sql`；新建数据库不重复执行迁移。
3. 按 `.env.example` 设置进程环境变量。Spring Boot 直接读取环境变量，**不会自动加载 `.env` 文件**。
4. 在 `netdisk/` 目录运行 `mvn spring-boot:run`；接口默认地址 `http://localhost:7090/api`。
5. 生产打包使用 `mvn clean verify`，再运行 `java -jar target/netdisk-0.7.2.jar`。生产环境应使用 HTTPS，并设置 `NETDISK_COOKIE_SECURE=true`。

`NETDISK_STORAGE` 应指向专用可写存储目录并以 `/` 结尾。数据库和 SMTP 密码不入库。管理员邮箱使用 `NETDISK_ADMIN_EMAILS` 精确匹配；未配置时无管理员。QQ 登录尚未实现真实 OAuth，接口明确关闭。邮件未配置时注册/找回提示不可用；实际邮件投递仍需单独验收。

密码由前端通过 HTTPS 提交原文，由服务端 PBKDF2 带盐存储；旧数据库 MD5 在下一次正确登录时迁移。修改密码需提交当前密码，修改/重置密码、禁用账号后旧会话失效。接口约定见 [ACCOUNT-SECURITY.md](docs/ACCOUNT-SECURITY.md)。

## 测试

在 `netdisk/` 运行 `mvn test`。测试使用 H2 的 MySQL 兼容模式、Redis 与邮件替身及 `target/test-storage/`，不访问已有 MySQL 数据、Redis 或真实邮箱；此测试不能替代真实 MySQL/Redis 联调。

当前版本包含 327 项已通过检查，覆盖账号、文件、分享/回收站、上传与配额、管理事务、头像及跨站防护。已另用独立 MySQL/Redis 通过 9 组文件组织/回收接口联调。参见 [文件契约](docs/API-FILES.md)、[上传契约](docs/API-UPLOAD.md)、[内容预览](docs/API-CONTENT.md) 和 [管理/资料](docs/API-ACCOUNT-ADMIN.md)。历史项目只有 1 项无业务断言的启动测试。CI 对提交执行 `mvn verify`。

## 版本与记录

- [UPDATELOG.md](UPDATELOG.md)：按版本记录变化。
- [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)：逐功能验收与待办。
- `VERSION` 与 `pom.xml` 保持一致；每个独立功能测试通过后分别提交、推送并打版本标签。
