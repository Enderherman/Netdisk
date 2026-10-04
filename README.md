# Netdisk

基于程序员老罗 EasyPan 教程手写实现的个人网盘，正在补齐自动化测试、修复历史业务缺陷，并配套新的 [NetdiskWeb](https://github.com/Enderherman/NetdiskWeb)。

当前版本：**0.1.3**。已修复容量缓存、分享权限、目录查询及回收站递归和共用文件清理问题；尚未完成整体业务验收。

## 技术与现有模块

Java 17、Spring Boot 3、MyBatis、MySQL 8、Redis；音视频转码使用 ffmpeg。

现有代码包括账号注册登录、邮件验证码、文件上传/下载/目录管理、分享、回收站及管理后台。逐项实测与修复状态见 [验收清单](docs/ACCEPTANCE.md)。新前端为独立仓库，历史 `dist/` 仅为旧构建产物。

## 启动

1. 安装 JDK 17、Maven 3.9、MySQL 8、Redis，以及需要视频转码时的 ffmpeg。
2. 在独立实例或确认为空的数据库中执行 `database.sql`。该脚本建立 `netdisk` 数据库，不应直接对已有业务数据执行。
3. 按 `.env.example` 设置进程环境变量。Spring Boot 直接读取环境变量，**不会自动加载 `.env` 文件**。
4. 在 `netdisk/` 目录运行 `mvn spring-boot:run`；接口默认地址 `http://localhost:7090/api`。
5. 生产打包使用 `mvn clean verify`，再运行 `java -jar target/netdisk-0.1.3.jar`。生产环境应使用 HTTPS。

`NETDISK_STORAGE` 应指向专用可写存储目录并以 `/` 结尾。数据库和 SMTP 密码不入库。管理员邮箱使用 `NETDISK_ADMIN_EMAILS` 配置；未配置时无邮箱管理员。QQ 登录和实际邮件投递尚需真实服务配置及单独验收。

## 测试

在 `netdisk/` 运行 `mvn test`。测试使用 H2 的 MySQL 兼容模式、Redis 与邮件替身及 `target/test-storage/`，不访问已有 MySQL 数据、Redis 或真实邮箱；此测试不能替代真实 MySQL/Redis 联调。

当前版本包含 70 项已通过检查，覆盖基础、容量、目录查询、分享权限、回收站及真实临时文件清理，包含 MVC/AOP/MyBatis 配合 H2 的接口与事务测试。历史项目只有 1 项无业务断言的启动测试。CI 对提交执行 `mvn verify`。

## 版本与记录

- [UPDATELOG.md](UPDATELOG.md)：按版本记录变化。
- [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)：逐功能验收与待办。
- `VERSION` 与 `pom.xml` 保持一致；每个独立功能测试通过后分别提交、推送并打版本标签。
