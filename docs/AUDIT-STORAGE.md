# 存储与文件功能审计

审计日期：2026-10-04。审计对象：本轮改造开始时的后端源码。下列行号对应改造前版本，后续修复会改变行号；本文件保留发现记录，不代表所有问题仍未修复。

## 验证边界

初次审计为静态代码审查，未连接生产数据库，未启动外部服务，未对真实数据执行删除或攻击请求。表中“复现条件”是由代码推导的测试场景，并非声称已经运行成功。修复后的自动化验证记录见本文末尾；其余问题须由相应功能的回归测试证明。

审计时的旧布局：Java 路径前缀为 `netdisk/src/main/java/top/enderherman/netdisk/`，Mapper 前缀为 `netdisk/src/main/resources/top/enderherman/netdisk/mapper/`。从 1.1.0 起对应当前前缀分别为 `src/main/java/top/enderherman/netdisk/` 和 `src/main/resources/top/enderherman/netdisk/mapper/`，Maven 从仓库根目录运行；本文保留旧版本行号作为历史证据。

## 静态发现

| 优先级 | 位置（改造前） | 缺陷、复现条件与影响 |
| --- | --- | --- |
| P1 | `service/impl/FileServiceImpl.java:193,242,250,309` | 上传 fileId 直接拼接磁盘路径。非末分片携带 `../` 可越出存储目录写入数字文件，异常清理还可能递归删除越界目录。需服务器上传 ID、路径规范化和根边界限制。 |
| P1 | `controller/ACommonFileController.java:125-134`；`FileMapper.xml:174-175` | 面包屑 path 拼入 SQL 排序表达式，再通过 `${query.orderBy}` 注入 SQL。应在 Java 排序或使用安全参数化实现。 |
| P1 | `controller/WebShareController.java:119,131,143,155,180`；`service/impl/FileServiceImpl.java:602-614` | 校验分享后，预览、下载、面包屑、转存未限制目标属于分享根目录。知道同一分享者其他文件 ID 可越权操作。 |
| P1 | `controller/WebShareController.java:216-224` | 分享授权只读 session，不重新检查分享记录及根文件。取消分享后已授权访问仍有效；转存甚至未检查缓存有效期。 |
| P1 | `task/ScheduledTask.java:39-70` | 秒传/转存共享物理路径，某用户永久删除后清理任务直接删除实体，破坏其他用户正常文件和可恢复文件。需保留引用检查。 |
| P1 | `service/impl/FileServiceImpl.java:601-640` | 转存先批量插入再检查容量，且没有事务。超额时返回失败却留下文件和错误配额。 |
| P1 | `service/impl/FileServiceImpl.java:202-232,250-289,745-800` | 秒传信任客户端 MD5，合并后不校验摘要。伪造摘要能污染他人后续秒传内容；知道摘要即可取得其他用户文件副本。 |
| P1 | `service/impl/FileServiceImpl.java:846-855` | 回收站递归把 userId 当作下一级 fileId。A/B/C.txt 删除 A 后漏掉 C，永久删除不释放全部空间，留下孤儿节点。 |
| P1 | `service/impl/FileServiceImpl.java:391-428` | 移动未校验目标是目录或是否属于选中项的后代；可创建环或把文件挂在普通文件下。上传、新建目录、转存也缺少有效父目录检查。 |
| P2 | `service/impl/FileServiceImpl.java:650-656` | 自动改名将父目录 ID 写到 fileId 查询条件，而非 filePid。同目录重复上传产生同名记录。 |
| P2 | `service/impl/FileServiceImpl.java:357-358` | 重命名以 folderType 的 0 对比 fileType 的 1 至 10，永远不追加原扩展名。 |
| P2 | `common/component/RedisComponent.java:116-119`；`service/impl/FileServiceImpl.java:250-259,755-773` | 分片重试重复计费，累计读改写有并发丢失；最后编号到达就合并，不检查缺片、乱序、真实大小。 |
| P2 | `service/impl/FileServiceImpl.java:305-317` | 普通上传异常被吞掉返回 null，控制器仍响应成功，事务可能提交残缺状态。 |
| P2 | `common/component/RedisComponent.java:56-67` | 空间缓存失效后总额度取系统默认值而非用户额度，管理员扩容后会回退显示并错误拒绝上传。 |
| P2 | `service/impl/FileServiceImpl.java:270-289,737-741` | 转码前 file_size 为空，重算空间会漏计正在处理的文件；失败后预占空间缺少一致结算。 |
| P2 | `service/impl/FileServiceImpl.java:539-569` | adminOp 参数没有使用，管理员删除正常文件实际无效，仍返回成功。 |
| P2 | `controller/ACommonFileController.java:69-121,141-175` | 预览、下载不检查删除/转码状态；过期链接返回空 200；无 User-Agent 时空指针；缺少 Range、长度、标准中文下载文件名。不存在 TS 回退查询会 get(0) 越界。 |
| P2 | `controller/ACommonFileController.java:48-58` | 缩略图接口不验证所属用户，也未限定实际文件为图片，知道路径即可跨用户读取内容。 |
| P2 | `common/utils/ProcessUtils.java:29-50`；`common/utils/ScaleFiler.java:14-21,42-50` | FFmpeg 未检查退出码、路径未作为独立参数、缺少超时；失败可被标记正常，每次执行还永久注册 shutdown hook。 |
| P2 | `controller/WebShareController.java:89-106` | 分享目录不接收分页参数，只能查看默认第一页 15 项；返回实体泄露内部路径、摘要等多余字段。 |
| P2 | `controller/FileShareController.java:30-66` | 分享管理缺少 GlobalInterceptor，未登录或参数缺失出现 500，新增分享未校验源文件。 |
| P3 | `FileMapper.xml`；`FileShareMapper.xml:336-370` | FileMapper 缺少继承的 updateByParam SQL；分享通用更新/删除没有 s 别名却引用 s.file_id/s.user_id。目前属于潜在服务层调用缺陷。 |

## 原有功能范围

- 文件分页、分类、模糊搜索、目录和面包屑。
- 分片上传、秒传、异步合并、图片封面、视频 HLS。
- 新建目录、重命名、批量移动、预览和短期下载令牌。
- 批量回收、恢复到根目录、永久删除标记、定期物理清理。
- 分享有效期、提取码、浏览次数、我的分享、取消、访问和转存。
- 用户空间、管理员额度调整、文件管理、用户状态和系统设置。

账号与邮件功能由独立审计覆盖，不在本文件重复。

## 建议补齐顺序

1. 先修复权限、上传完整性、目录树、配额事务、共享实体清理，再开展新增功能。
2. 上传任务状态、幂等重试、断点续传、取消和临时文件到期清理；文件夹上传。
3. 文件复制、批量 ZIP 下载、排序、最近文件、收藏、详情和统一同名策略。
4. 回收站清空、保留期限、剩余天数、恢复原位置或明确根目录回退。
5. 分享完整分页、即时撤销、提取码策略、多项分享、下载/访问权限策略。
6. Range 下载、中文文件名、准确内容类型和错误状态；PDF、文本、音视频基础预览。
7. 数据库迁移、环境配置、健康检查、存储统计与关键操作审计。

每项完成后单独验证、记录版本和推送。重点覆盖正常路径、越权、非法参数、配额边界、重试、用户隔离、多层目录和共享物理文件。

## 本轮回收站与清理修复的验证记录

2026-10-04 已运行 `mvn -q -Dtest=RecycleServiceTest,StorageCleanupTest test`，22 项通过，失败 0、错误 0、跳过 0。

- `RecycleServiceTest`：13 项 Spring Boot + H2 MySQL 模式集成测试，实际执行 Mapper SQL、用户行锁、事务和文件服务入口。覆盖多层回收、父子重复选择、深层恢复、同名恢复、独立回收项及同时选中恢复、永久删除配额回收、管理员删除正常树、跨用户拒绝、非法选择、旧目录环和缓存故障。
- `StorageCleanupTest`：9 项 Mockito + JUnit 临时磁盘目录测试，真实创建/删除测试文件，覆盖正常/回收/间接删除引用保护、最后引用的原文件/封面/视频分片清理、越界路径拒绝、先验证封面路径、缺失文件幂等、目录元数据清理、缺失路径保留。
- 新服务采用迭代遍历避免递归栈溢出，所有节点来自当前用户；回收仍计入使用容量，永久删除才重算配额。恢复保留原接口的“选中项恢复根目录”约定，内部目录层级保持不变。
- 清理保留 `netdisk.cleanup-enabled` 开关，逐项处理并保留失败记录以便重试，禁止清理存储根本身、越界路径或链接导向的外部位置。

验证范围不包含生产 MySQL 并发压力、真实 Redis 故障恢复、FFmpeg 或浏览器页面。清理与上传/转存的并发完整性，还要求上传和复制入口锁定源存储引用并重新检查源状态；该入口属于后续独立功能修复。所有磁盘验证均只使用 JUnit 临时目录，没有触碰生产存储。
