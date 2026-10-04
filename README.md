# Netdisk

基于程序员老罗 EasyPan 教程手写实现的个人网盘。本轮在原 Spring Boot / MyBatis 项目上修复历史缺陷、补齐个人网盘功能，并配套独立的 [NetdiskWeb](https://github.com/Enderherman/NetdiskWeb) 前端。

当前版本：**1.1.0**。使用 Java 17、Spring Boot 3.5.16、MyBatis starter 3.0.5、MySQL 8、Redis。前端采用 Vue 3 / TypeScript，支持浅色、深色和跟随系统。

## 功能

| 模块 | 已实现能力 |
| --- | --- |
| 账号 | 图形/邮件验证码、注册/登录/退出、找回/修改密码、昵称/头像、PBKDF2、旧MD5登录迁移、会话撤销、可配置QQ OAuth |
| 文件 | 目录树、列表/网格、搜索/分类/排序/分页、新建、完整名称重命名、移动/复制、重名处理和循环保护 |
| 上传 | 分片、摘要验证、重复分片幂等、乱序完成、暂停/续传/取消/重试、任务恢复/过期、原件当前位置 |
| 预览与下载 | 授权原件、Range/HEAD、图片缩略图、文本/图片/PDF/音视频、递归流式ZIP与空目录 |
| 分享 | 提取码/期限、游客浏览/下载、登录后保存、即时撤销、目录范围隔离 |
| 回收站 | 递归回收/恢复、原位置优先、永久删除、清空、保留期与共用物理文件保护 |
| 管理 | 用户筛选/启停/配额、所属文件管理、系统设置、动态管理员校验 |
| 安全与运行 | 配额事务和并发锁、路径/来源保护、请求日志脱敏、环境配置、CI、非root容器交付 |

使用规则与逐项证据见 [验收清单](docs/ACCEPTANCE.md) 和 [测试报告](docs/TEST-REPORT.md)。版本变化独立记录在 [UPDATELOG.md](UPDATELOG.md)。后端仓库不再保留旧前端 `dist/`，当前前端使用 NetdiskWeb 仓库。

## 目录布局

从 1.1.0 起，后端采用根目录 Maven 项目布局，源码、构建、SQL 和部署入口如下：

```text
Netdisk/
  src/main/                 应用源码与资源
  src/test/                 自动化测试与测试资源
  pom.xml                   Maven 构建入口
  sql/init.sql              空数据库初始化
  sql/migrations/           已有数据库的升级脚本
  scripts/Healthcheck.java  容器健康探针
  scripts/validate-config.py
  scripts/mysql/010-schema.sh
  Dockerfile
  .dockerignore
  compose.yaml
  .env.example              直接运行的进程环境变量示例
  .env.compose.example      Compose 配置示例
  docs/
```

Maven 命令均在此仓库根目录执行。旧版本的 `netdisk/` 源码子目录、`database.sql` 和 `deploy/` 路径仅用于历史记录，当前操作以此布局为准；默认相邻前端目录为 `../NetdiskWeb`。

## 本地启动

1. 安装 JDK 17、Maven 3.9、MySQL 8、Redis。
2. 在独立空数据库执行 `sql/init.sql`。已有旧库应先备份并检查字段，按需执行一次 `sql/migrations/20261004_account_security.sql`；新库不重复迁移。
3. 按 `.env.example` 设置**进程环境变量**。Spring Boot 不自动加载 `.env` 文件；数据库、Redis、SMTP密码均使用环境配置。
4. `NETDISK_STORAGE` 指向专用可写目录并以 `/` 结尾。

从旧布局升级时，Maven 的运行目录由 `netdisk/` 变为仓库根目录，默认相对路径 `NETDISK_STORAGE=./data/` 的解析位置也随之改变。已有安装应先确认原存储目录，并将 `NETDISK_STORAGE` 设置为该目录的绝对路径（保留末尾 `/`），再启动新版本。此次源码目录调整不会自动搬迁业务文件；不要把工作目录变化造成的空目录误认为原文件丢失。

```sh
# 在后端仓库根目录执行
mvn spring-boot:run
```

接口默认地址为 `http://127.0.0.1:7090/api`。前端开发服务默认代理到此地址；在另一个终端进入 NetdiskWeb，执行 `npm ci`、`npm run dev`，访问 `http://127.0.0.1:5173`。

```sh
# 在后端仓库根目录构建并运行
mvn clean verify
java -jar target/netdisk-1.1.0.jar
```

管理员由 `NETDISK_ADMIN_EMAILS` 精确匹配现有账号邮箱，不内置默认管理员或测试账号。首次注册需要可用邮箱验证服务。

## Docker 自部署

两仓库相邻放置，复制根目录 `.env.compose.example` 为 `.env.compose`，填写独立随机凭据及实际服务配置，再按 [部署说明](docs/DEPLOYMENT.md) 检查并构建。Compose 使用根目录 `compose.yaml`，前端构建目录默认 `../NetdiskWeb`；`.env.example` 仍用于直接运行时的进程环境变量，两者用途不同。

```sh
docker compose --env-file .env.compose -f compose.yaml config --quiet
docker compose --env-file .env.compose -f compose.yaml build backend frontend
```

编排包含 MySQL、Redis、后端和前端，默认仅向本机开放8080端口；数据库与Redis不发布宿主机端口。

后端 Java 17 与前端 Nginx 镜像均已在独立CI构建验证。生产域名、HTTPS、SMTP、持久卷、备份和目标机器仍需按文档配置。本轮未部署到 NAS 或生产环境。

## 账号及外部服务

- 密码通过 HTTPS 提交原文，在服务端以带盐PBKDF2保存；修改/重置密码及禁用会使旧会话失效。
- 邮箱默认使用SMTPS。未配置时前端明确禁用注册/找回，现有邮箱账号仍可登录。已验证本地加密SMTP捕获链路，外部邮箱送达需实际配置后核验。
- QQ OAuth 默认关闭。显式设置 `NETDISK_QQ_ENABLED=true` 并配置应用ID、密钥和 `https://你的域名/api/qqlogin/callback` 后启用。实现与模拟网关测试已完成，真实QQ授权仍需应用凭据；QQ独立账号暂不支持绑定邮箱或设置本地密码。详见 [QQ说明](docs/API-QQ.md)。
- 生产使用HTTPS，设置 `NETDISK_COOKIE_SECURE=true` 和精确的 `NETDISK_ALLOWED_ORIGINS`。后端与代理都避免在请求异常日志中记录临时下载凭据和QQ授权参数。

## 验证

当前版本包含 **381 项后端测试**；配套前端 **260 项测试**，类型检查与生产构建通过。后端测试使用真实MVC/AOP/MyBatis与H2、受控Redis/邮件替身、真实临时文件；额外完成隔离MySQL/Redis、文件系统和本地SMTPS的现场验收。

实测包括192 MiB刷新续传、128 MiB暂停继续、配额失败/取消、下载和ZIP字节校验、游客分享与第二账号保存、回收恢复/清空、改密撤销双会话、管理员操作、PDF翻页和手机明暗布局。不能将单元测试与外部QQ/公网SMTP验收混为一谈，完整边界见测试报告。

## 接口与版本

[文件组织](docs/API-FILES.md) · [上传](docs/API-UPLOAD.md) · [内容传输](docs/API-CONTENT.md) · [复制](docs/API-COPY.md) · [ZIP](docs/API-ZIP-DOWNLOAD.md) · [回收站](docs/API-RECYCLE.md) · [账号安全](docs/ACCOUNT-SECURITY.md) · [管理与资料](docs/API-ACCOUNT-ADMIN.md)

按独立功能实现、测试、提交、推送并打 `vX.Y.Z` 标签。`VERSION` 与根目录 `pom.xml` 一致，README用于当前使用说明，UPDATELOG保留完整历史。

本轮范围为单实例个人网盘。不包含商业会员/支付、全文检索、协同编辑、桌面同步、多机对象存储、Office在线转换或自动视频转码；不支持的格式和编码可下载原件查看。
