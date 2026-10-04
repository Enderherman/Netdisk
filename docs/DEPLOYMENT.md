# Docker 自部署交付

本交付包含后端 Java 17 多阶段镜像、前端 Nginx 镜像，以及 MySQL 8.4、Redis、后端和前端的 Compose 编排。没有执行 NAS 部署，也没有更改本机现有服务或容器。

## 目录与前提

从后端 1.1.0 起，两个仓库默认按以下布局相邻放置。本文命令均在后端仓库根目录执行：

```text
workspace/
  Netdisk/
    src/
    pom.xml
    sql/init.sql
    sql/migrations/
    scripts/Healthcheck.java
    scripts/validate-config.py
    scripts/mysql/010-schema.sh
    Dockerfile
    .dockerignore
    compose.yaml
    .env.example
    .env.compose.example
  NetdiskWeb/
    Dockerfile
    .dockerignore
    nginx/nginx.conf
```

需要 Docker Engine 与 Compose v2，建议 Compose 2.20 及以上。Linux/amd64 镜像已在 GitHub Ubuntu 24.04 runner 实际构建并检查；Linux/arm64 仍需在目标机器验证。镜像使用官方来源和固定主/次版本系列：

| 用途 | 默认镜像 |
| --- | --- |
| 后端构建 | `maven:3.9-eclipse-temurin-17` |
| 后端运行 | `eclipse-temurin:17-jre-jammy` |
| 前端构建 | `node:24.14-alpine3.23` |
| 前端运行 | `nginx:1.28-alpine` |
| 数据库 | `mysql:8.4` |
| 缓存 | `redis:7.4-alpine` |

这些标签在 2026-10-05 查询官方 Docker Hub 均返回存在，后续两仓库 CI 已实际拉取并构建镜像。标签会随补丁更新，正式升级时记录镜像摘要以便回滚。镜像构建还需访问 Maven Central、npm 注册表与 Ubuntu 官方软件仓库。

## 首次准备

以下示例在后端仓库根目录执行。它们是给操作者的部署步骤，本次没有在本机或 NAS 执行这些步骤或导入业务数据库；远端 CI 仅运行临时镜像检查容器。

1. 选择两个仓库已经验证的提交，复制根目录 `.env.compose.example` 为 `.env.compose`。根目录 `.env.example` 仍是直接运行后端的进程环境变量示例，不作为此 Compose 命令的环境文件。
2. 为 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD` 分别填写独立随机密码。示例故意留空，Compose 会拒绝空凭据。可用密码管理器生成，或执行 `openssl rand -hex 32` 生成十六进制密码。
3. 确认 `COMPOSE_PROJECT_NAME` 没有与已有项目重名；默认只发布 `127.0.0.1:8080`。数据库、Redis 和后端均不发布宿主机端口。
4. 前端构建目录 `NETDISK_WEB_CONTEXT` 默认是 `../NetdiskWeb`，相对于根目录 `compose.yaml`。若仓库不相邻，应调整该值，也可填写绝对路径。
5. 配置实际公开地址、HTTPS、SMTP 和管理员邮箱，见下文。

```sh
docker compose --env-file .env.compose -f compose.yaml config --quiet
docker compose --env-file .env.compose -f compose.yaml build backend frontend
docker compose --env-file .env.compose -f compose.yaml up -d
docker compose --env-file .env.compose -f compose.yaml ps
```

默认本机访问 `http://127.0.0.1:8080`。后台健康检查返回正常后前端才启动。单独构建时：

```sh
# 后端仓库根目录
docker build -f Dockerfile -t netdisk-backend:local .

# 前端仓库根目录
docker build -t netdisk-web:local .
```

后端镜像构建编译测试源码但使用 `-DskipTests` 跳过测试执行；发布前仍应在后端仓库根目录运行 `mvn clean verify`，测试仅使用独立环境。前端镜像会运行类型检查和生产构建，也不替代前端组件测试或浏览器验收。

## 数据库初始化与迁移

首次启动空的 MySQL 数据卷时，官方入口创建 `netdisk` 数据库和最小业务账户，再执行由 `scripts/mysql/010-schema.sh` 挂载的初始化脚本。该脚本读取由 `sql/init.sql` 挂载的 SQL，只去掉重复的 `CREATE DATABASE` 和 `USE` 两行，在已创建的数据库内建表，因此不会因 `MYSQL_DATABASE` 已建库而失败。

初始化 SQL 已包含 `password VARCHAR(255)`、`session_version`、邮件验证码 `purpose`。**新部署不要再执行账户安全迁移。**

初始化 shell 脚本应由仓库属性配置固定为 LF，避免 Windows 检出后产生不可执行的 CRLF shebang。

已有 MySQL 数据卷不会重新执行初始化目录中的脚本；更新镜像也不会自动迁移数据库。旧数据库升级前先备份，并检查：

```sql
SELECT table_name, column_name, column_type
FROM information_schema.columns
WHERE table_schema = 'netdisk'
  AND ((table_name = 'user_info' AND column_name IN ('password', 'session_version'))
    OR (table_name = 'email_code' AND column_name = 'purpose'));
```

确实缺少安全字段的旧库，按 `sql/migrations/20261004_account_security.sql` 执行一次。该迁移不是重复执行脚本，也不应套用于已经升级的数据库。部署配置不擅自修改已有数据库，不内置测试账号或默认管理员。

管理员邮箱由 `NETDISK_ADMIN_EMAILS` 配置，逗号分隔。配置名单本身不会创建用户；通过正常注册等受支持流程创建对应账号后才获得管理员角色。首次注册需要可用的邮箱验证服务。

## 存储、权限与健康检查

- `mysql-data`：MySQL 数据。
- `redis-data`：Redis AOF、系统配置、缓存及短时凭据。
- `files-data`：原件、缩略图、上传任务凭据/分片、头像等。备份时应包含整个卷，不能只复制某个原件目录。

卷由 Compose 项目名前缀隔离；默认实际名称形如 `netdisk_files-data`。不要用 `docker compose down -v` 进行日常升级，它会移除持久卷。

后端以 UID/GID `10001:10001` 运行，根文件系统只读，只有 `/data/netdisk` 和临时目录可写；存储配置固定带末尾 `/`，兼容现有路径拼接逻辑。命名卷首次使用时采用镜像目录的所有权。若改为 NAS/宿主机 bind mount，应事先让目录对选定 UID/GID 可写；`NETDISK_UID/NETDISK_GID` 同时控制构建用户与运行用户，不能设为 0。已有文件属主不会因改环境变量自动转换。

前端 Nginx 也以非 root 用户运行，监听容器内 8080，PID/临时目录在 `/tmp`。后端包含字体配置和基础字体，供验证码绘制使用。新上传使用原件 Range 和 Java 图片处理，不依赖 FFmpeg；镜像没有额外部署媒体转码服务。

后端健康探针只验证本机 `/api/accountCapabilities` 能返回 HTTP 200 和业务 code 200，这是 HTTP 存活检查，不等同于端到端文件读写正常。MySQL 健康检查验证业务账户能查询已初始化的表，Redis 使用带密码的 PING，前端检查 `/healthz`。

## 同源反代、Range 与大文件

前端只使用相对 `/api`。Nginx 将完整 `/api/...` 路径保留给后端，保留浏览器 Host，并通过 Docker DNS 动态解析 backend 地址。

- Nginx 请求上限 128 MiB；后端单文件分片上限 100 MiB，multipart 整体上限 110 MiB；当前前端默认 8 MiB/片。
- `/api/` 禁止请求/响应缓冲与代理缓存，ZIP 直接流式发送，不先把整个归档落入 Nginx 临时文件。
- Range、If-Range 原样传递，API 路径不做 gzip，避免改变字节范围语义。
- API 读取超时 1 小时、上传发送/请求体等待 5 分钟；这些是相邻读写之间的超时，不是总文件大小保证。
- `.mjs` 使用 JavaScript MIME，PDF Worker 可正常加载；SPA 深链回退至 `index.html`，真实缺失的静态资源返回 404。

磁盘要同时容纳原件、待完成分片及合并工作文件；大并发还需按实际负载调整内存、临时目录容量与连接数。没有通过本次静态验证推断出生产并发能力。

## HTTPS 与来源校验

本配置不申请域名证书、不开放公网端口、不自动创建外部代理。生产环境应由现有 HTTPS 反向代理终止 TLS，再转发到本机绑定的 8080；随后设置：

```dotenv
NETDISK_ALLOWED_ORIGINS=https://disk.example.com
NETDISK_COOKIE_SECURE=true
```

这里的域名只是示例，必须换成实际公开 origin，包含非默认端口时也要填写。不要配置通配来源。若只用本机 HTTP 测试，保留默认 `http://127.0.0.1:8080` 和非 Secure Cookie。

Nginx 会重建转发头，不盲目信任客户端传入的 X-Forwarded-For；Spring 使用 native 转发头处理，后端不对宿主机暴露端口。再加一层 HTTPS 代理时，默认 IP 限流看到的是该代理地址。需要真实访客 IP 时，在前端 Nginx 的 http/server 中只为**明确可信代理 IP/CIDR**配置 `set_real_ip_from`、`real_ip_header X-Forwarded-For` 和 `real_ip_recursive on`，然后验证限流来源；不能无条件信任所有来源。

HTTPS 外层代理还须同步设置大请求体限制、长读取超时并关闭该下载路径的响应缓冲。当前内部 MySQL JDBC 仅面向同机隔离数据网络；改接外部数据库时，应使用单独覆盖配置和 TLS/证书验证，不能照搬内部连接的 `sslMode=DISABLED`。

## SMTP 与 QQ 外部配置

当前邮件配置使用 SMTPS，默认端口 465。填写服务商要求的主机、账号和授权密码，确认部署主机能直连服务商并验证证书。不填写邮件凭据时，前端按能力接口禁用注册/找回流程，不会假装邮件发送成功。

项目现有验收包含本地 SMTPS 捕获；它不是公网邮件服务商送达证明。上线时还需核验发件域策略、服务商限制、收件箱/垃圾邮件及实际注册和找回流程。587/STARTTLS 需针对服务商另行覆盖 Spring Mail 协议配置，不能只把端口由 465 改成 587。

QQ 默认关闭。启用前必须使用包含相应 QQ 实现的发布版本，申请并审核 QQ 开放平台应用，配置 `NETDISK_QQ_ENABLED`、APP ID、APP KEY。正式回调应登记为 `https://网盘域名/api/qqlogin/callback`，与前端保持同源；不能填写 backend 容器名或后端内网端口，否则后端的相对 303 跳转无法正确回到前端。具体契约见 [QQ 接口说明](./API-QQ.md)。真实 QQ 平台网络、审核和授权无法由 Compose 自动完成；保持未配置时明确不可用。

Nginx 普通访问日志只记录方法、脱敏路径、响应/上游状态、字节数和耗时，不包含查询参数、原始 request/request_uri 或 Referer。四类文件/ZIP/分享/管理下载路径中的短码统一替换为 `[redacted]`。API 通用代理片段关闭会携带完整 URI 的上游错误日志，保留脱敏访问日志的 status/upstream_status 供排错。

QQ 回调使用精确 location，另外关闭访问日志，并通过通用片段关闭错误日志，避免上游失败/超时的错误消息重新包含 code/state；回调仍完整转发原请求和 Cookie，对浏览器强制 no-referrer/no-store。此处不依靠仅隐藏访问日志来保护回调。

## 一致备份

先记录两个 Git 提交、镜像摘要、Compose 渲染配置和 `.env.compose`，妥善保护包含凭据的备份。选择维护窗口停止前后端写入，再保存数据库和文件卷的一致快照。

```sh
docker compose --env-file .env.compose -f compose.yaml stop frontend backend
mkdir -p backups/2026-10-05
docker compose --env-file .env.compose -f compose.yaml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --user=root --single-transaction --routines --triggers --events --no-tablespaces netdisk' > backups/2026-10-05/database.sql
docker compose --env-file .env.compose -f compose.yaml stop redis
```

确认实际卷名称后，用只读挂载导出；以下卷名适用于默认项目名，改过项目名必须同步替换。先执行 `docker volume inspect` 确认它们存在且属于当前部署，避免将拼错名称生成的空卷当成备份。

```sh
docker volume inspect netdisk_files-data netdisk_redis-data
docker run --rm --network none --mount type=volume,source=netdisk_files-data,target=/source,readonly --entrypoint tar redis:7.4-alpine -C /source -czf - . > backups/2026-10-05/files.tgz
docker run --rm --network none --mount type=volume,source=netdisk_redis-data,target=/source,readonly --entrypoint tar redis:7.4-alpine -C /source -czf - . > backups/2026-10-05/redis.tgz
docker compose --env-file .env.compose -f compose.yaml up -d redis backend frontend
```

这些导出容器仅是未来备份步骤，本次没有运行。Windows 操作者应使用能保持二进制重定向的环境执行 tar 输出命令，或使用 Docker/NAS 的卷快照能力；不要让旧版 PowerShell 将归档流当文本转码。校验 SQL 文件非空、归档可列出并生成校验和，将备份保存到不同故障域。

## 升级与恢复

从 1.0.0 及以前的目录布局升级时，直接使用 Maven/JAR 的安装需先确认 `NETDISK_STORAGE`。Maven 运行目录从旧 `netdisk/` 变为仓库根目录，默认 `./data/` 会解析到不同位置；已有数据应使用原存储目录的绝对路径并保留末尾 `/`。仓库布局迁移不自动搬迁数据，也不能据新相对目录为空就判定文件丢失。容器内的 `/data/netdisk` 挂载和既有卷语义不因此改变，不能为目录整理删除或重建业务卷。

1. 先完成上面的数据库、文件、Redis 一致备份，保留旧镜像；检查新版本迁移说明。
2. 在维护窗口按需执行且只执行一次数据库迁移。构建已验证提交对应的前后端镜像。
3. 使用同一项目名和同一数据卷运行 `docker compose --env-file .env.compose -f compose.yaml up -d --build backend frontend`；不要删除卷，也不要对已有数据重放完整 `sql/init.sql`。
4. 检查容器健康与日志，逐项验证登录、容量、上传/续传、下载 Range、ZIP、分享/回收和邮件配置。单纯健康检查不是业务验收。
5. 回滚先停止写入。若模式未变化，可换回旧应用镜像；数据库已经迁移时，不能只降级 jar，必须评估兼容性或从同一时间点恢复数据库和文件备份。
6. 先在**独立项目名和独立端口**创建恢复验证环境，导入 SQL、恢复文件和 Redis 归档，核对 UID/GID、文件数量和哈希后再安排正式切换。已有浏览器 HTTP 会话通常会因服务重启失效，这是预期行为。

当前没有集成共享 HTTP Session 或跨主机文件锁，不应仅靠 `--scale backend` 扩成多实例；多实例部署需要额外设计共享会话、共享存储及一致锁。

## 本次验证范围（2026-10-05）

执行 `python scripts/validate-config.py` 可在不启动任何服务的情况下，用临时非秘密凭据检查模板的 Compose 渲染、端口/网络/挂载边界和代理关键配置，不读取真实 `.env.compose`。有 Java 17+ 编译器时也编译 `scripts/Healthcheck.java` 健康探针，可用 `--javac /absolute/path/to/javac` 指定编译器；可用 `--bash /absolute/path/to/bash` 对初始化与 CI 脚本做语法检查。

以下记录保留 1.0.0 及以前已完成的配置和镜像验证；1.1.0 的命令与目录已按本文新布局调整，迁移后的复核结果以该版本发布记录和对应 CI 为准。

本次已通过 Docker Compose 2.39.1 配置解析、空凭据拒绝、隔离网络/端口/非 root 配置检查、Java 17 健康探针编译及 Nginx 关键指令静态检查。初始化脚本与 3 段 CI shell 通过 Bash 语法检查，JAR 检查脚本通过 Python 语法解析；所用六种官方基础镜像标签已查询存在。

本机 Docker Linux 引擎未运行，没有在本机启动容器或导入数据库。远端 CI 已补齐镜像构建、受控容器和 Nginx 验证；完整 Compose 业务上线、目标平台持久化/恢复及 NAS 验收仍未执行。

两个仓库另新增独立的 `docker-build.yml` GitHub Actions，不替换现有业务测试 CI。工作流使用官方 Ubuntu 24.04 托管 runner 自带 Docker，权限仅为仓库读取，不推送镜像、不部署应用：

- 后端构建镜像，检查可执行 Java 17 JAR、非 root 用户、打包的健康探针，并在无网络临时容器中验证存储可写和健康探针能拒绝未启动的应用。
- 前端构建镜像，用 `--add-host backend:127.0.0.1` 执行真实 `nginx -t`；随后仅在无网络临时容器的回环地址短时启动 Nginx，检查实际 `.mjs` Worker MIME、SPA 回退及静态资源 404。再用假的 QQ callback 参数和下载短码强制产生 502，检查容器 stdout/stderr 不包含测试 code/state/token；普通 200 请求同时验证查询参数和 Referer 已去除，下载失败只保留 `[redacted]` 路径，而安全访问日志仍正常存在。没有真实 QQ 请求或发布端口，测试结束删除临时容器。

两个 Docker 工作流均已成功：后端 https://github.com/Enderherman/Netdisk/actions/runs/37227282709 ，前端 https://github.com/Enderherman/NetdiskWeb/actions/runs/37226760993 。两者只验证构建与所列容器行为，不代表生产部署已经完成。
