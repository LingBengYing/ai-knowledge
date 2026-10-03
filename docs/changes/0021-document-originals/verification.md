# 0021 后端原文件入口验证

2026-10-03，本批已完成后端、前端、针对性回归、当前源码原文件 HTTP 贯通与交接 JAR 构建。部署切换及公网页面复验仍待现有部署任务与用户验收；音频真实识别偏差未据此关闭。

## 行为与证据

- `GET /v1/documents/{documentId}/original` 按白名单返回八个公开字段，其中版本为保存原文件的 `initial_revision_id`，内容地址固定到该文档/版本。
- 精确内容 GET 返回封存原字节及保存 MIME，大小 1..20MiB，`private, no-store` 与 `nosniff`。两个入口都拒绝 query/body。
- `ManagementService` 定义同一 authority 事务，复用当前权限策略及 `ManagementRepository`。SQL 范围同时核对组织、当前 ACL、未撤回状态、文档注册版本与原始版本、该版本所属资料及源 SHA、原字节大小。`DocumentOriginal` 在事务内核对实际原字节 SHA，并防御复制且 `toString` 脱敏；没有答案、publication、投影正文或其他版本回退。
- `document_originals` 作为所有管理配置的基础能力，无须打开摄取、索引或问答，不启动模型、任务或 schema 变更。
- `DocumentOriginalHttpTest` 用生产 `IngestionService.uploadDocument` 保存真实 PDF、TXT、Markdown、生成 PNG、已有合成 WAV/MP4；随后经真实 Spring HTTP 读取 metadata 与 pinned 内容并逐字节比较。所有解析、索引、问答运行开关关闭，实际上传任务保持 queued。另覆盖 reader 读取、当前撤权、撤回、不存在、版本不符、无原文件的合成元数据、原字节损坏和保存大小不符。

## 红绿与命令

命令均在 Java 仓库根目录执行，先加载工作区 `.tools/env.sh`，实际 JDK 21.0.12.1+1、Maven 3.9.16，使用本机 `.tools/m2`。完整原测试、格式配置及覆盖率阈值均未修改。

1. 实现前执行 `mvn -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2 -Dtest=DocumentOriginalHttpTest test`，23:49:01 +08:00：10 项运行，9 个行为失败，0 错误/跳过。六种已保存原文件及 reader metadata 因缺路由返回 404；能力缺失；query/body 因无路由返回 404 而非 422。损坏负例在无路由阶段通过，不能单独视作损坏防护证据。
2. 实现后执行 `mvn -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2 -Dtest=DocumentOriginalHttpTest,ManagementHttpTest,ManagementServiceTest,RuntimeControllerTest,ArchitectureRulesTest test`，23:51:53 +08:00：38 项运行，37 通过、1 失败，0 错误/跳过。原文件 HTTP 10、管理 HTTP 3、管理 Service 13、运行配置 1 均通过。架构测试 11 中 1 项失败，仅报告此前 0020 `config.ExternalEntryPolicy` 被 Controller/security.web 引用的反向依赖；没有报告本批 0021 类型依赖。该已有门禁缺口由主线负责人单独最小修复，未修改规则或白名单来求绿。
3. 执行过限定文件 `spotless:apply` 命令，但限定表达式未处理文件，不能据此宣称新源码已格式化。为避免主线移动 0020 Policy 时构建中间态，本批停止 Maven/Spotless；最终格式、针对性回归与 package 由主线统一执行。
4. 当前 `git diff --check` 通过。只读 Git；未暂存、提交、推送或改索引。

5. 主线把 0020 `ExternalEntryPolicy` 移至 `security.web`，由既有 `SecurityConfiguration` 的 Bean 装配，移除 Policy 对配置包的依赖。原拒绝条件与 4 项外部入口 HTTP 测试保留；架构规则及白名单未修改。先统一 `spotless:apply`，随后执行 `mvn -o -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2 -Dtest=DocumentOriginalHttpTest,ManagementHttpTest,ManagementServiceTest,RuntimeControllerTest,ExternalEntryHttpTest,AuthenticationFilterTest,SessionControllerTest,ArchitectureRulesTest clean test spotless:check`，2026-10-03 00:03:27 +08:00：66 项通过，0 失败/错误/跳过；架构 11 项全部通过，590 个 Java 文件格式检查通过。clean 清除了旧包的编译残留。
6. 执行同一离线 Maven 配置的 `-DskipTests package`，00:06:56 +08:00 BUILD SUCCESS。这不代表默认完整测试或全面评测已执行。最终 JAR SHA 记录在工作区新建 `.tools/document-originals-handoff/SOURCE-MANIFEST.json`。
7. `npm run check`、`npm test`：完整前端 185 项通过。当前生产 Spring/SQLite、FFmpeg/Tesseract、前端 `createApi`、`DocumentOriginalSession` 与精确开发代理，实际上传/解析/索引合成 TXT/PNG/WAV/MP4，并上传/解析 PDF；五个原文件 metadata 与内容均通过身份及完整 SHA 校验，Session 为 ready。本机模型与 Milvus 使用 loopback 夹具，不能代表真实模型质量或浏览器显示。结果复制到新交接 `native-originals-result.json`；临时 HTTP 服务已关闭。

本机原始日志保存在工作区私有 `.local/document-originals-red.log`、`.local/document-originals-green.log`、`.local/document-originals-format.log`、`.local/document-originals-final-java.log`、`.local/document-originals-final-node.log`、`.local/document-originals-final-package.log`、`.local/document-originals-native.log`；不是待发布业务数据。

## 剩余验收

- 本机格式、针对性回归、构建、前端四类详情与跨导航清理，以及完整原文件 SHA 贯通已通过。部署免登录改动由独立发布者保留；交接提供相对上一冻结点的最小前端补丁，不覆盖其整份外部代理或身份页面。
- 浏览器实际显示、PDF 打开、音视频播放及公网复验仍待用户和协调任务；本机 HTTP 不代表这些已经通过。
- 本批没有模型/Milvus或付费调用，没有操作服务器、凭据、旧数据库或部署隔离副本，也没有执行完整默认测试或全面评测。

没有扩大 0021 业务范围或新增运行开关。由于发现 0020 架构位置问题，主线先最小修复再序列化 clean 构建，这是验证顺序的明确偏离。前端详情进入问答后遗留旧资料与 scope 的缺陷同时按用户复现修复，详见前端 0009。
