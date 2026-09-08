# Architecture：当前实现与未来边界

最新增量为[0008](changes/0008-document-lifecycle/spec.md)。DocumentRemovalController只处理HTTP，DocumentLifecycleService在一个Store事务内复验当前写权限、读取原回执、取消任务、解除目录、写墓碑及摘要审计；DocumentLifecycleRepository封装墓碑和专用当前ACL读取，DocumentRemovalEntity不直接出网，DocumentRemovalResult仅四个安全回执字段。Config负责独立默认关闭的本机开关，不装配模型或启动物理清理Job。已有worker和Answer/Evidence链复验当前状态，权限与墓碑过滤在分页/检索前生效；历史trace和配额不隐藏。删除后字节保留，不等于硬删除完成。

安全基线[0006](changes/0006-ingestion-authorization/spec.md)在原Service/Repository/Policy职责内补齐摄取创建者权限，其297项Java、73项Node及[source-manifest](changes/0006-ingestion-authorization/source-manifest.json)只认证当时源码。当前[0007](changes/0007-text-answers/spec.md)已接通可选后端文本问答、引用与v4 trace；本地全量通过不等于完整语义、真实provider、网页或生产验收，当前结果见[0007验证](changes/0007-text-answers/verification.md)。Java整体仍为IMPLEMENTATION；下面0005保留结构基线。

## 结构基线 0005：本地重构验收通过

0005 已按 [变更工件](changes/0005-spring-layering/intent.md) 与 [Java 开发规范](JAVA_DEVELOPMENT_STANDARDS.md) 完成本地重构验收；Java 整体仍为 IMPLEMENTATION。下面是当前源码地图，不是生产发布报告。本次仅做 Java 重构，不修改前端详情页，未推送或部署。旧 `ManagementModule`、`AuthenticationModule`、`IngestionRuntime`、`IndexingRuntime` 已由新职责实现替代，不留生产兼容壳；历史行为测试迁移到新入口，不删除或放宽原断言。

0005保持0004的业务范围、独立数据目录、显式opt-in、API和生产gate。2026-09-07 10:52:26 +08:00当时最终clean verify通过283项Java测试（含11项架构测试，0失败/错误/跳过）、148个Java文件Spotless及73项Node。重构前[baseline-manifest](changes/0005-spring-layering/baseline-manifest.json)的256项测试逐项保留；[0005验证](changes/0005-spring-layering/verification.md)及[source-manifest](changes/0005-spring-layering/source-manifest.json)仅认证当时快照，不认证新增0007源码。

## 保留的 0004 功能增量

0004在0003持久解析证据上接通[TEXT_INDEXING](TEXT_INDEXING.md)：独立Java索引进程、embedding/Milvus小批次写入、完整revision回读和权威publication，显式本机opt-in。当前状态IMPLEMENTATION；[0004 spec](changes/0004-text-index-publication/spec.md)与[API](API.md)记录契约，当前验收以[VERIFICATION](VERIFICATION.md)为准。0003已发布bc82a7a的190项Java/42项Node与Java21 CI是历史基线，旧manifest不认证本切。

## 交付边界

这是独立的Java / Spring Boot应用，默认`management_slice`；问答关闭时摄取/索引保留`text_ingestion`/`text_indexing`阶段。三个功能开关独立、默认关闭且仅本机development/test；问答开启后stage为`text_answers`、后端capabilities增加`answers/sources`，不隐式启动摄取/索引Job。parsed与indexed分开，列表can_answer仍false、网页问答/来源未接线、ready仍503；多模态处理未实现。能力状态见[ROADMAP](ROADMAP.md)，接口见[API](API.md)。

[pom.xml](../pom.xml)声明 Java 21 编译目标、Spring Boot 4.1.1、SQLite JDBC 与 PDFBox。运行时版本和测试结果以 [VERIFICATION](VERIFICATION.md) 为准。启用虚拟线程不构成性能承诺，尚无可比较的吞吐/延迟基准。

## 当前运行路径

~~~text
同源浏览器：index.html + app.js
  ├─ api.mjs：同源 HTTP / 浏览器托管 Cookie
  └─ workbench-state.mjs：身份 epoch、并发读、选中项、写操作状态
        ↓
web.RequestContextFilter → security.web.AuthenticationFilter
  → RequestAuthenticator → JwtAuthenticator → 可信 Actor
        ↓
controller.ManagementController → web.converter 请求/响应映射
        ↓
service.ManagementService → repository.ManagementRepository
        ↓
repository.SqliteAuthorityStore（全仓共享 Connection / monitor / 事务）

CLI --seed-demo → config.PersistenceConfiguration（全新目录与资源装配）
  → bootstrap.DemoFixtures → ManagementService

web.UploadServlet → IngestionService → IngestionRepository + ManagementRepository
  原文件 / revision / 持久任务 / 审计同一事务提交
job.IngestionJob → IngestionTaskProcessor → IngestionService.claimIngestion
  → 事务外 ProcessTextParser → ParserWorker JVM → tool.parser.TextParser
  → ParsedText / TextPage / TextSegment
  → IngestionService 完整证据验收与条件提交 → parsed，active=null

controller.IndexingController → IndexingTaskProcessor / IndexingService
job.IndexingJob → IndexingTaskProcessor → IndexingService.claimIndexing
  → 事务外 ProcessTextIndexer → IndexWorker JVM
  → IndexWorkerLifetime 父存活 / 跨 JVM collection lease
  → client.model.TextModels.embed
  → client.vector.RetrievalProjection generation-scoped upsert / 完整 verify
  → IndexingService 当前 claim / ACL / source / 完整 manifest 验收
  → IndexingRepository publication / entries / active / indexed
    + ManagementRepository audit（同一个 Store 事务）

本机显式 POST /v1/answers → AnswerController / AnswerRequestMapper → AnswerService
  → EvidenceService.snapshot：当前授权active、完整选中集合与配置身份
  → 事务外只读prepareSearch / query embedding / scoped dense+BM25 search
  → EvidenceService.hydrate：权威source与locator → rerank / extract / TextGrounding
  → EvidenceService.finish：锁内完整scope / 预算 / 本地配置资格复验
  → EvidenceRepository：v4 trace与最终决定同事务 → 回答或拒答
GET /v1/sources/{answerId}/{ordinal} → AnswerService / EvidenceService.source
  → 原回答者与完整当前ACL/active复验 → 权威摘录（不是自由文件下载）
~~~

## Module / Interface / Implementation / Adapter

复杂行为放在小 Interface 后面；不为未来可能性预先铺设抽象层。

| Module | Interface 与职责 | Implementation / Adapter 与验证 |
| --- | --- | --- |
| Management | 资料分页、整理、目录/标签、逐项批量结果；Service 校验当前权限并确定事务 | [ManagementService](../src/main/java/com/evidence/rag/service/ManagementService.java)、[ManagementRepository](../src/main/java/com/evidence/rag/repository/ManagementRepository.java)、[ManagementServiceTest](../src/test/java/com/evidence/rag/service/ManagementServiceTest.java) |
| Authority storage | 一个连接/monitor、同线程事务回调；SQL 不暴露上层；Schema 只做格式与迁移 | [SqliteAuthorityStore](../src/main/java/com/evidence/rag/repository/SqliteAuthorityStore.java)、[AuthoritySchema](../src/main/java/com/evidence/rag/repository/AuthoritySchema.java)、[事务回归](../src/test/java/com/evidence/rag/repository/SqliteAuthorityStoreTest.java) |
| Authentication / authorization | HTTP 提取与签名验证分开；纯权限策略只接受同事务读取的当前角色，不自行查库 | [RequestAuthenticator](../src/main/java/com/evidence/rag/security/web/RequestAuthenticator.java)、[JwtAuthenticator](../src/main/java/com/evidence/rag/security/authentication/JwtAuthenticator.java)、[DocumentPermissionPolicy](../src/main/java/com/evidence/rag/security/authorization/DocumentPermissionPolicy.java)、[JWT HTTP](../src/test/java/com/evidence/rag/security/JwtHttpTest.java) |
| Text parsing | 受限 PDF/TXT/MD 抽取，返回无框架 ParsedText/Page/Segment；独立 JVM 处理不可信文件 | [TextParser](../src/main/java/com/evidence/rag/tool/parser/TextParser.java)、[ProcessTextParser](../src/main/java/com/evidence/rag/worker/parser/ProcessTextParser.java)、[ParserProtocol](../src/main/java/com/evidence/rag/worker/parser/ParserProtocol.java) |
| Text ingestion | 配额、上传、claim/attempt、取消/重试、完整解析结果验收与审计；Job 只调度 | [IngestionService](../src/main/java/com/evidence/rag/service/IngestionService.java)、[IngestionRepository](../src/main/java/com/evidence/rag/repository/IngestionRepository.java)、[IngestionTaskProcessor](../src/main/java/com/evidence/rag/service/IngestionTaskProcessor.java)、[IngestionJob](../src/main/java/com/evidence/rag/job/IngestionJob.java) |
| Text indexing | 冻结 target、claim/generation、完整 manifest 接受及原子 publication；远程阶段在事务外 | [IndexingService](../src/main/java/com/evidence/rag/service/IndexingService.java)、[IndexingRepository](../src/main/java/com/evidence/rag/repository/IndexingRepository.java)、[IndexingTaskProcessor](../src/main/java/com/evidence/rag/service/IndexingTaskProcessor.java)、[IndexingJob](../src/main/java/com/evidence/rag/job/IndexingJob.java) |
| Text answers / evidence | 有界并发与总预算、只读检索和逐事实验证；完整scope、权威hydrate、最终资格与trace原子提交 | [AnswerService](../src/main/java/com/evidence/rag/service/AnswerService.java)、[EvidenceService](../src/main/java/com/evidence/rag/service/EvidenceService.java)、[EvidenceRepository](../src/main/java/com/evidence/rag/repository/EvidenceRepository.java)、[TextGrounding](../src/main/java/com/evidence/rag/tool/answer/TextGrounding.java) |
| Model data | Entity 是持久化快照；DTO/Query 是输入和安全结果；VO 是公开白名单；Domain 表达身份及证据 | [DocumentEntity](../src/main/java/com/evidence/rag/model/entity/DocumentEntity.java)、[DocumentPatchCommand](../src/main/java/com/evidence/rag/model/dto/DocumentPatchCommand.java)、[DocumentQuery](../src/main/java/com/evidence/rag/model/query/DocumentQuery.java)、[DocumentResponse](../src/main/java/com/evidence/rag/model/vo/DocumentResponse.java)、[IndexClaim](../src/main/java/com/evidence/rag/model/domain/IndexClaim.java) |
| Model / vector client | 模型和 Milvus 协议、响应限长与完整投影验证；不是数据 Model 层 | [TextModels](../src/main/java/com/evidence/rag/client/model/TextModels.java)、[RetrievalProjection](../src/main/java/com/evidence/rag/client/vector/RetrievalProjection.java)、[MilvusRestProjection](../src/main/java/com/evidence/rag/client/vector/MilvusRestProjection.java) |
| Browser state | 身份变化使旧票据失效；只接受当前有效读写结果 | [workbench-state.mjs](../src/main/resources/static/workbench-state.mjs)、[notices.mjs](../src/main/resources/static/notices.mjs)、[UI tests](../ui-tests/) |
| HTTP / runtime | Controller 和上传 Servlet 处理协议；转换器保留 PATCH 三态/固定 JSON；错误类别在 Web 映射 HTTP | [ManagementController](../src/main/java/com/evidence/rag/controller/ManagementController.java)、[UploadServlet](../src/main/java/com/evidence/rag/web/UploadServlet.java)、[ManagementRequestMapper](../src/main/java/com/evidence/rag/web/converter/ManagementRequestMapper.java)、[HttpProblemMapper](../src/main/java/com/evidence/rag/web/HttpProblemMapper.java)、[RuntimeService](../src/main/java/com/evidence/rag/service/RuntimeService.java) |
| Composition | 配置绑定、Bean 装配、恢复时序与资源关闭；其他层不读取全局配置 | [PersistenceConfiguration](../src/main/java/com/evidence/rag/config/PersistenceConfiguration.java)、[SecurityConfiguration](../src/main/java/com/evidence/rag/config/SecurityConfiguration.java)、[IndexingConfiguration](../src/main/java/com/evidence/rag/config/IndexingConfiguration.java)、[AnswersConfiguration](../src/main/java/com/evidence/rag/config/AnswersConfiguration.java)、[TextAdaptersConfiguration](../src/main/java/com/evidence/rag/config/TextAdaptersConfiguration.java) |

SQL 和行映射只在 Repository；Service 没有 JDBC、原始 SQL、Servlet 或 HTTP 状态码。Repository 的原始 SQL 方法为包私有，校验当前线程处于该 Store 的事务。没有按 Repository 新开连接，也不增加无意义 ServiceImpl、Manager、ORM 或 BaseService。测试装配 [AuthorityTestContext](../src/test/java/com/evidence/rag/support/AuthorityTestContext.java) 只位于 test 源集，调用真实三层和生产 HTTP 转换器，不是生产万能类的延续。

Model 不是 Controller → Service → Repository 之后的额外转发层。固定业务契约不再用无类型 Map 穿过各层；角色授权表、物理段摘要表和局部动态审计字段仍可用有明确语义的 Map。Domain 只依赖 JDK 与稳定无 HTTP 的异常类型；可变数组/集合保留防御复制，内部 claim/target/实体不直接序列化为 HTTP。已与公开字段一致的 FolderResult/FolderRemovalResult 直接作为安全 DTO 输出，FolderListResult 在同 DTO 包复用，不新增重复 VO；三 Service 的任务投影由包私有 TaskResults 统一，权限计算和 publication 查询仍在原 authority 事务中。

只有确有两个Adapter（如真实provider与测试替身、生产子进程与受控真实进程）时才引入Seam。模型/检索Interface见[TEXT_ADAPTERS](TEXT_ADAPTERS.md)；索引业务已使用embedding与projection写入/验证，其本地替身结果不能代替真实provider/Milvus验收。

`TextAdapterSettings.load(environmentMap)`仅校验配置、不发请求；索引或问答任一opt-in时由TextAdaptersConfiguration加载一次，三套Endpoint均必填。索引仅调用embedding；问答使用embedding/rerank/extract与只读prepareSearch/search。共享问答client按Bean生命周期关闭，不随单个请求关闭。embedding identity绑定URL/模型/revision/维度，Milvus只接受`java_`集合。索引仍按generation验证完整物理manifest；0007 AuthorizedScope从active publication派生完整`document → generation`，physical候选先经台账hydrate权威source。多HTTP调用不是快照，prepareSearch不创建或加载集合，也不证明loaded。

AnswerService在完整请求体解码后实施总预算及有界准入；远程阶段不持Store事务。EvidenceService在最终锁内及写trace前复验完整scope、预算和本地模型/投影资格，任一次资格失败不能恢复成功；外部Milvus配置不在该原子性范围内。来源回读绑定原Actor及当前完整scope。关闭最多等待5秒，未退出或被中断显式安全失败，后续close可继续等待；不能强杀忽略中断的线程，也不保证Spring会停止销毁其他依赖。

## 启动、配置与迁移隔离

[RagApplication](../src/main/java/com/evidence/rag/RagApplication.java)是唯一 Java 启动入口。正常启动 Spring 应用；`--seed-demo NEW_DATA_DIRECTORY` 是显式 CLI，仅向全新数据库写入合成元数据，不上传或解析文件，也不会自动在服务启动时执行。

[application.properties](../src/main/resources/application.properties)和配置校验控制以下边界：

| 环境变量 | 默认 / 约束 |
| --- | --- |
| `RAG_ENVIRONMENT` | `development`；仅接受 `development` / `test`，拒绝 `production` |
| `RAG_BIND_ADDRESS` / `RAG_PORT` | `127.0.0.1` / `18084` |
| `RAG_AUTH_MODE` | `jwt`；也可显式设 `development_headers` |
| `RAG_WORKSPACE_ID` | `org-main`；每个进程固定单组织 |
| `RAG_JWT_SECRET` | 无可用默认值；JWT 模式要求至少 32 字符，拒绝占位前缀；不能提交真实值 |
| `RAG_JWT_ISSUER` / `RAG_JWT_AUDIENCE` | `evidence-rag` / `evidence-rag-web` |
| `RAG_DATA_DIRECTORY` | `./.data`；必须为独立 Java 数据目录 |
| `RAG_INDEXING_ENABLED` / `RAG_INDEXING_TIMEOUT_MS` | `false` / `60000`；显式启用且字面loopback；全任务10–600000毫秒；完整模型/Milvus配置必填 |
| `RAG_ANSWERS_ENABLED` / `RAG_ANSWERS_TIMEOUT_MS` / `RAG_ANSWERS_MAX_CONCURRENT` | `false` / `60000` / `2`；独立本机问答，完整JSON解码后预算10–600000毫秒，并发1–8 |

开发头模式要求绑定字面量 loopback 地址，并检查请求 Host 为本机；它不是可部署到公网的认证方案。JWT 模式也不解除生产 gate。反向代理转发头不被自动信任，不能假定当前配置已具备代理/TLS 部署认证。

[run-dev.sh](../run-dev.sh)启动已经构建的 JAR，并先复制到临时运行目录，避免后续构建覆盖正在运行的 JAR。它不负责构建、不自动换认证模式、不解除启动门禁。

## SQLite authority：单进程 writer

[SqliteAuthorityStore](../src/main/java/com/evidence/rag/repository/SqliteAuthorityStore.java)持有一个数据库连接；事务回调全程在同一 `synchronized` monitor 下执行并绑定当前线程，事务使用 `BEGIN IMMEDIATE`，锁等待上限配置为 5 秒。生命周期文件锁 `.java-library.lock` 阻止同一规范化目录被第二个 Java writer 打开。当前不是多副本或分布式数据库架构。

- 数据库为`java-library.db`，保留独立格式标记`evidence-rag-java-management-v1`，当前v5由format_info与PRAGMA user_version共同标识。打开已有v1/v2/v3/v4库逐版本一致性备份后增量迁移，即使功能开关关闭也执行；失败不覆盖/自动恢复。v5新增不可变document_tombstones，继承generation/trace格式检查。回滚使用独立旧版备份目录，迁移后新增资料及删除请求不在旧备份中，不能声称恢复旧备份仍保留删除状态。v3见[TEXT_INDEXING](TEXT_INDEXING.md)，v4见[0007 spec](changes/0007-text-answers/spec.md)，v5见[0008 spec](changes/0008-document-lifecycle/spec.md)。
- 拒绝带旧 `rag.db` / `authority.db` 的目录、危险符号链接和不匹配的数据库格式；不就地复用其他实现数据库。
- `documents` 保存合成源身份及可编辑展示元数据，`document_acl` 保存 `reader/editor/owner`，`folders`、`document_tags` 和 `management_audit` 保存整理状态。
- 所有列表、总数和分页 SQL 先约束组织与 ACL；目录可见性来自目录所有者或其中有权访问的资料，目录计数只计算当前用户可见资料。
- 只有资料 `owner/editor` 能更新展示元数据；只有目录所有者可改名/删除目录。非空目录删除失败，不隐式删除资料。
- SQLite 触发器保护源身份与审计记录；元数据变更和审计在同一事务内。批量操作逐项事务，允许部分成功并返回逐项回执。
- 存储构造器不运行摄取/索引业务恢复；PersistenceConfiguration 在对应 Service Bean 返回前同步执行 recover，依赖这些 Bean 的 Job 随后才开始调度。
- 审计 Interface 只返回当前 actor 最近至多 100 条；没有 HTTP 审计端点。审计保存字段名及前后值的摘要，不保存这些前后值的明文；这是本地追踪，不是外部不可篡改审计系统。

v3保留v2不可变页/segment/source/revision及触发器，新增indexing_jobs、不可变indexing_attempts/index_publications/index_publication_entries与active_corpus_publications。publication同时保存source revision和projection generation，完整entries绑定source→physical→digest，active只在完整映射提交后读取source revision。旧corpus active仍恒null，合成注册身份由registered_revision_id返回；已有active也不解锁答案。缺generation/台账的早期未发布WIP v3拒绝复用，不改变已发布v1/v2备份迁移。

v4在上述证据旁新增append-only的query_traces、query_trace_documents、query_trace_evidence，不就地改写source或索引publication。它们保存Actor、问题/答案摘要、完整scope与版本、判定原因、模型/提示/验证器revision和引用locator/hash/分数，不保存原问题、全文或凭据；最终决定与trace同事务，失败整体回滚。trace不是跨外部服务事务或公开审计端点。

进程protocol v2传递parent PID/startInstant，`IndexWorkerLifetime`在独立main检测父存活/预算并停止worker；普通同JVM run仅中断调用者。固定/tmp的0700用户目录与0600空lease文件，以规范化endpoint/database/collection为目标提供跨JVM锁，POSIX/owner/symlink/hardlink检查失败即拒绝。锁inode永不unlink，关闭后小文件保留，父崩溃可能遗留私有job临时目录待回收。lease是同OS用户本机约束，不替代外部配置冻结；未知上游晚写靠独立generation隔离，不靠kill撤回。当前机制与异常进程验收分开记录，非OS沙箱或生产批准。

## 当前安全实现与未解决差距

分层只集中已有认证/授权职责，不代表完整 Spring Security FilterChain、方法鉴权或生产安全体系已经接入。Service 在事务内调用纯 DocumentPermissionPolicy；Repository 在组织/ACL SQL 范围内读取和修改，列表过滤仍先于分页。

索引领取、当前claim验证和发布前复验创建者当前写权限。0005登记的摄取差距由0006独立行为修复：读取原文件前、创建parser前、运行中及提交时，按持久created_by查询同组织当前owner/editor权限。合法claim撤权后以cancelled/null终止并同事务写系统审计，旧/伪造claim不影响当前attempt；complete仍先保留内容SHA/parser的422校验。恢复对撤权processing取消、其余保留worker_interrupted。重试同时要求调用者及原创建者当前可写；取消不要求创建者仍可写。真实子进程撤权终止、立即重试无重叠及审计回滚见[0006历史验证](changes/0006-ingestion-authorization/verification.md)；0007问答权限链须使用自身源码和回归证据，不借摄取验收代证。

ApplicationException/FailureKind 不携带 HTTP 状态，HttpProblemMapper 是唯一 HTTP 类别映射；实体/claim 不直接输出。已有不记录原始 provider/parser/JDBC 异常的要求继续有效。若此次新增或调整异常/日志脱敏，必须在对应验证记录单列证据，不能将其混称为已认证的纯包迁移或由结构图证明。

## 身份与浏览器信任边界

- JWT 仅接受 HS256，校验签名、`iss`、`aud`、固定 `workspace_id`、`sub`、整数 `exp` 及可选 `nbf`；没有令牌签发、刷新、SSO 或用户管理。
- HTTP 接受 Bearer 或 `rag_session` Cookie；显式 Authorization 优先，错误 Bearer 不回退 Cookie。重复身份头或重复会话 Cookie 被拒绝。
- 会话交换仅在 JWT 模式可用。Cookie 为 HttpOnly、SameSite=Strict、Path=/；TLS 或非 loopback 链路加 Secure。删除会话清除 Cookie，不撤销已签发 JWT。
- `/v1/` 写请求若携带 Origin，必须单一且与请求 scheme/host/port 精确同源。无 Origin 的非浏览器调用仍需正常身份；不要描述成独立 CSRF token 机制。
- [RequestContextFilter](../src/main/java/com/evidence/rag/web/RequestContextFilter.java)设置请求编号、no-store、nosniff、no-referrer 与同源 CSP；错误响应不输出内部异常、令牌、源码正文或堆栈。
- 浏览器使用 `credentials: same-origin`，不将 JWT 放入 localStorage / sessionStorage；输入提交后清空。身份切换与异步读取用 epoch/ticket 限制旧结果回写。列表切换后的选中项和详情以当前状态为界，批量失败不会被成功回执覆盖。
- 不可信展示文字使用文本节点渲染；文本上传仅在两项后端capability同时开启时可用，提问、摘要、来源及生命周期按钮仍不可用。任务重开从当前授权行解析，旧身份、旧attempt或旧详情闭包不得回填。

## 独立 TextParser 的实际能力

`TextParser.REVISION = java-text-parser-v2-monotonic-codepoints`（0001/0002的独立解析版本为v1）。输入为内存 `byte[]` 与文件名/MIME，输出：

```text
ParsedText(pages, segments)
TextPage(number, text)
TextSegment(ordinal, page, start, end, text)
```

PDF 按页抽取文本；TXT / MD 严格按 UTF-8 解码。统一换行，移除文本文件 UTF-8 BOM，拒绝不合法控制字符。页号从 1 开始；`start/end` 是规范化页文本的 Unicode code point 半开区间，不是 UTF-16 字符索引或原文件字节偏移。分块目标 1200 code points、约 120 重叠，优先在中文句末/换行处分界。

边界：文件 1 字节至 20 MiB；文件名不含路径分隔符，后缀与 MIME/文件头匹配；PDF 最多 500 页，加密 PDF 拒绝；总抽取文本不超过 1,000,000 code points；无可用文本则失败。它不做 OCR、表格语义还原、图像理解、音频转写或视频抽帧。

这些限制**不等于OS解析沙箱**：直接调用TextParser仍是同进程，应用摄取必须经过独立ProcessTextParser JVM的资源/并发/总deadline约束。它不隔离宿主文件系统或网络，仅允许本机显式开发，生产还需容器级隔离与验收。

四份[合成 PDF](../src/test/resources/corpus/)和未修改的[golden](evals/golden.json)已由[AnswerGoldenTest](../src/test/java/com/evidence/rag/service/AnswerGoldenTest.java)贯通Java解析/发布/问答，六项确定性替身通过；不是实际模型质量或完整语义报告。解析器抽取恶意指令本身仍不证明答案安全。

## 已接通链路与尚未验收边界

“受限上传 → 隔离解析 → 权威revision/segment → embedding → Milvus完整验证与publication → 授权混合检索/重排 → 服务端证据校验 → 回答/拒答及来源”现已有0007后端接线和实际本机JDK21回归。完整语义审查、网页闭环、真实provider/Milvus、同生产镜像、多模态及生产验收仍缺；任何局部通过都不解除production gate。

下一切、安全 invariant 和生产 gate 见 [ROADMAP](ROADMAP.md)；实际验证结论仅由 [VERIFICATION](VERIFICATION.md)记录。
