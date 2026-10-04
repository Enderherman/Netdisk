# 构建与运行依赖

版本核验日期：2026-10-04。仍使用 Java 17；Spring Boot 保持 3.x，选择公开发布的 3.5 系列最新稳定补丁，不迁移到 4.x。

## 实际版本与来源

| 项目 | 本次结果 | 来源与理由 |
| --- | --- | --- |
| Spring Boot | 3.0.2 → **3.5.16** | [官方 Maven Central 元数据](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml)中，匹配 `3.5.数字` 的最高稳定版本；元数据更新时间为 `20260924090817`。 |
| MyBatis Spring Boot starter | 3.0.0 → **3.0.5** | [官方元数据](https://repo.maven.apache.org/maven2/org/mybatis/spring/boot/mybatis-spring-boot-starter/maven-metadata.xml)中的最新稳定 3.x；[该版本父 POM](https://repo.maven.apache.org/maven2/org/mybatis/spring/boot/mybatis-spring-boot/3.0.5/mybatis-spring-boot-3.0.5.pom)明确以 Java 17、Spring Boot 3.5.0 构建。 |
| MyBatis / MyBatis Spring | **3.5.19 / 3.0.5** | 由 MyBatis starter 管理，移除旧 MyBatis 3.5.13 单独覆盖。 |
| Maven compiler plugin | 3.8.1 → **3.16.0** | [官方元数据](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml)最新稳定 3.x；[POM](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-compiler-plugin/3.16.0/maven-compiler-plugin-3.16.0.pom)声明 Maven 最低 3.6.3。使用 `release=17` 和 `parameters=true`。 |
| Maven Surefire | **3.2.5** | 保留现有测试插件，完整执行升级后的测试套件。 |

其余直接受管版本来自 [Spring Boot 3.5.16 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom)，并用隔离构建的 `dependency:tree` 核对了实际解析结果：

| 组件 | 实际解析版本 |
| --- | --- |
| Spring Framework | 6.2.19 |
| Jackson Databind | 2.21.4 |
| Logback Classic / Core | 1.5.34 |
| AspectJ Weaver | 1.9.25.1 |
| MySQL Connector/J | 9.7.0 |
| Apache Commons Lang | 3.17.0 |
| Apache Commons Codec | 1.18.0 |

项目使用 `spring-boot-starter-aop` 保留鉴权切面，移除 Logback 与 AspectJ 的旧硬编码版本。Commons Lang 与 Codec 仍有真实源码使用，版本由 BOM 统一管理。

## 移除的未使用依赖

对 `netdisk/src`（包括测试）查询了导入及限定类名，未发现 Fastjson、MyBatis-Plus 或 Commons IO 使用，因此移除：

- Fastjson 1.2.83。
- MyBatis-Plus extension 3.5.3。
- Commons IO 2.11.0。

实际依赖树亦不再包含这些组件。分享码、下载令牌、上传 ID 等继续保持原有数字或 ASCII 字母数字格式和长度，但随机源改为 Java `SecureRandom`，不再调用 `RandomStringUtils`。

## 部署配置

邮件发送对 SMTP 和 SMTPS 都配置超时，现有默认协议仍为 SMTPS。数值单位为毫秒：

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `NETDISK_MAIL_CONNECT_TIMEOUT_MS` | 5000 | 建立邮件服务器连接的超时。 |
| `NETDISK_MAIL_READ_TIMEOUT_MS` | 10000 | 等待邮件服务器响应的超时。 |
| `NETDISK_MAIL_WRITE_TIMEOUT_MS` | 10000 | 写入邮件服务器的超时。 |
| `NETDISK_REDIS_PASSWORD` | 空 | Redis 密码；未配置时沿用无密码连接。 |

这些配置在共同 `application.yml` 中，开发与部署 profile 均可使用。没有改变全局 Maven 设置、镜像、JDK 安装或操作系统环境变量。

## 兼容性验证与边界

验证使用现有 Maven 3.9.9、Java 17.0.7，在独立 Git 快照中运行完整测试与 `mvn verify`，包含 Spring Boot 可执行 JAR 打包，不跳过测试。

- 原有账号、鉴权、文件、上传、转存、回收站、媒体和管理接口测试继续执行。
- 新增运行时参数名检查，保证 Spring MVC 可可靠绑定未显式命名的参数；增加 MySQL 驱动在 Java 17 下的实际加载检查。
- 新增邮件超时和 Redis 密码的配置绑定检查，以及实际 Redis JSON 序列化器对配额 DTO 和 Long 数值的往返检查。
- 随机码测试验证接口所需的长度和字符集兼容性；随机性来源依据代码中 `SecureRandom` 的使用确认。
- Mockito 5 对泛型注入更严格；`StorageQuotaCacheTest` 显式注入原始类型 `RedisUtils` 字段，保留原有四项行为断言，没有禁用测试。

具体用例数和最终结果保留在该功能的独立验证报告与发布记录。数据库集成测试使用 H2 的 MySQL 模式；没有连接生产 MySQL、Redis 或 SMTP，也未在本次运行漏洞数据库扫描。真实服务连接、既有 Redis 数据和生产数据库行为仍应在部署环境验证。
