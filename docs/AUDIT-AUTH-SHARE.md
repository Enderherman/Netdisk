# 账户、权限、分享和管理功能审计

审计日期：2026-10-04。以下结论来自初始代码的静态审计，**不表示所有问题都已通过运行复现**。行号对应修复前的代码。未连接原配置中的数据库、Redis、邮箱或 QQ 服务。后续修复和自动测试以 UPDATELOG 及测试报告为准。

本文路径保留审计时的旧布局 `netdisk/src/`。从 1.1.0 起源码位于仓库根目录 `src/`，对应当前 Java 前缀为 `src/main/java/top/enderherman/netdisk/`；以下旧行号和原始缺陷记录不表示当前实现仍相同。

## 优先修复的问题

| 优先级 | 位置（旧布局，相对 `netdisk/src/main/java/top/enderherman/netdisk`） | 触发条件及影响 | 建议验证 |
| --- | --- | --- | --- |
| P1 | `controller/WebShareController.java:119-156` | 提取合法分享后，预览、视频、下载及目录路径接口只使用分享者 userId，没有约束文件属于分享根目录。已知同一分享者其他 fileId 时可能读取未分享内容。 | 分享目录内允许；同用户其他目录、其他用户文件、已删除文件拒绝。 |
| P1 | `controller/WebShareController.java:217-224` | 分享校验仅使用 session 快照，分享者取消分享后旧会话继续生效。 | 提取后取消，再列举、预览、创建下载、保存全部失败。 |
| P1 | `controller/WebShareController.java:180-185` | 保存接口未调用有效期检查；未提取时空指针，过期后仍调用保存。 | 未提取返回 903；过期/取消返回 902。 |
| P1 | `service/impl/FileServiceImpl.java:602-639` | `shareRootFilePid` 未使用，任意源 ID、失效源文件及任意目标父目录可以进入复制；插入后才验配额，方法无事务，超限失败可能残留数据。 | 来源范围、目标目录归属、递归目录、重名、空间不足、并发配额及事务回滚。 |
| P1 | `controller/ACommonFileController.java:125-133` 及 `resources/.../FileMapper.xml:175` | 用户路径直接拼入 `field(...)`，随后 `${query.orderBy}` 原样进入 SQL。 | 含引号和 SQL 片段的路径返回参数错误；排序使用绑定参数或 Java 排序。 |
| P1 | `aspect/GlobalOperationAspect.java:74-85` 与 `service/impl/UserServiceImpl.java:169-175` | 登录鉴权仅检查 session；禁用账号或重置密码不撤销现有会话，禁用还把 useSpace 清零但未删除文件。 | 禁用后旧会话无权限；启用后空间准确；改密/找回后旧会话失效。 |
| P1 | 已提交的 `application-dev.yml:8`、`application-cloud.yml:6` | 仓库包含固定数据库口令；主配置默认 dev。避免把旧配置当作可安全连接的测试环境。 | 使用环境变量和示例配置；离线测试禁止外部连接；已暴露凭据需由所有者轮换。 |
| P2 | `controller/FileShareController.java:30-68` | 所有分享管理接口缺少 GlobalInterceptor，参数注解不生效，未登录请求产生 500。分享创建也不检查文件归属或存活。 | 未登录返回 901；非法有效期、提取码、源文件拒绝；合法操作成功。 |
| P2 | `controller/UserController.java:115,210` | 注册/找回检查 CHECK_CODE_KEY 却清除 CHECK_CODE_KEY_EMAIL，使图形验证码可以复用。 | 成功和失败后均需重新获取验证码。 |
| P2 | `controller/UserController.java:198-201` | 找回密码只检查非空，没有与注册相同的邮箱和密码规则。 | 弱密码、过长密码、非法邮箱拒绝，合法密码可登录。 |
| P2 | `service/impl/UserServiceImpl.java:109-138` | 登录传参需要客户端 MD5，其他密码接口却传明文；存储为无盐 MD5。 | 明确旧协议兼容和强哈希迁移，避免新前端直接用明文调旧登录。 |
| P2 | `service/impl/EmailCodeServiceImpl.java:52-71,112-122` | 邮件类型只区分是否 0，没有合法值检查、用途隔离、发送冷却或失败尝试限流。 | 用途错误、频繁发送、重复使用、过期、并发消费拒绝。 |
| P2 | `common/component/RedisComponent.java:56-65` | 空间缓存失效时用系统默认容量回填，丢失管理员给单个用户调整的额度。 | 管理员调整后删除缓存/等待过期，额度仍为数据库值。 |
| P2 | `service/impl/UserServiceImpl.java:180-184` | 调整容量忽略 update 行数；缩容小于已用量/用户不存在时可能返回成功。 | 缩容失败明确报错，合法增减同步缓存。 |
| P2 | `controller/ManageController.java:47-56,76-89` | 初始容量可为负，邮件模板不校验占位符，status 可为任意整数。 | 配置范围、格式和状态枚举验证。 |
| P2 | `controller/WebShareController.java:91-107` | 分享列表未接收 pageNo/pageSize，超过默认页大小的分享目录无法完整浏览。 | 至少 16 个文件的目录能翻页且无重复遗漏。 |
| P2 | `service/impl/UserServiceImpl.java:162-165`、`controller/UserController.java:273-280` | QQ 登录未实现，回调不验证/消费 state，也不写登录 session。 | 暂不显示未配置的入口；实现后验证 OAuth state、会话和回调地址。 |
| P2 | `controller/UserController.java:231-257` | 头像没有文件格式、内容和大小约束；缺省头像不存在后仍尝试读取输出流。 | 图片解码、限额、异常文件、默认头像回退。 |

## 现有前端接口契约

- 所有接口统一前缀 `/api`；登录使用 Cookie session，同源代理最简。参数目前是表单或查询参数，非 JSON body；上传用 multipart。
- JSON 为 `{status, code, message, data}`，业务异常通常 HTTP 200，必须检查 `code`。200 成功、600 参数/普通业务错误、601 重复、901 未登录、902 分享失效、903 未提取、904 空间不足。
- 分页结构：`{totalCount,pageSize,pageNo,pageTotal,list}`；时间多数为北京时间 `yyyy-MM-dd HH:mm:ss`；容量为字节。
- `GET /checkCode?type=0` 用于登录/注册/找回，`type=1` 用于邮件验证码；返回图片。`POST /sendEmailCode` 参数 email、checkCode、type（0 注册/1 找回）。
- `POST /register`：email、nickName、password（明文）、checkCode、emailCode。`POST /login`：email、password（旧实现要求 MD5）、checkCode。返回 userId、nickName、avatar、isAdmin。
- `/getUserInfo`、`/getUseSpace`；`POST /resetPwd`：email、password、checkCode、emailCode；`/updatePassword`：password；`/updateUserAvatar`：avatar multipart；`/logout`。
- `/share/shareFile`：fileId、validType、code（可省略随机生成）。validType：0 一天、1 七天、2 三十天、3 永久。返回 shareId、fileId、userId、code、expireTime、shareTime 等。`/share/loadShareList` 支持分页；`/share/cancelShare` 参数 shareIds 逗号分隔。
- `/showShare/getShareInfo`：shareId，返回文件名/分享人/时间等；`checkShareCode`：shareId、code；`getShareLoginInfo`：shareId；`loadFileList`：shareId、filePid（0 表示分享入口）；`getFolderInfo`：shareId、path（文件夹 ID 用 `/` 连接）；`saveShare`：shareId、shareFileIds（逗号分隔）、myFolderId（0 为本人根目录）。
- 分享预览 `/showShare/getFile/{shareId}/{fileId}`；视频 `/showShare/ts/getVideoInfo/{shareId}/{fileId}`；下载先创建 `/showShare/createDownloadUrl/{shareId}/{fileId}`，再 `/showShare/download/{code}`。
- 管理 `/admin/getSysSettings`、`saveSysSettings`（registerEmailTitle、registerEmailContent、userInitUseSpace，单位 MB）、`loadUserList`、`updateUserStatus`（userId、status，0 禁用/1 启用）、`updateUserSpace`（userId、changeSpace，MB 增量）、`loadFileList`、`delFile`（fileIdAndUserIds，格式 `fileId_userId` 逗号分隔）。

## 最小自动化回归矩阵

1. 账户：图形验证码、邮件验证码、注册重复、登录成功/失败/禁用、退出、找回/修改密码、会话撤销。
2. 分享：创建文件/目录、提取失败/成功、列表翻页、子目录导航、范围外 ID、预览/视频、下载凭据、取消/过期/源文件删除、保存与空间不足回滚。
3. 权限：未登录、普通用户访问管理接口、禁用后旧会话、跨用户文件/分享/目标目录。
4. 管理：用户搜索分页、合法与非法状态、容量增减和缓存重建、邮件模板与初始容量。
5. 本文涉及外部邮件及 QQ 的部分必须使用模拟服务或专用测试服务，不能以静态审计结果代替真实集成验证。
