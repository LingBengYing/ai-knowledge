# AI Context：从这里理解仓库

最新入口为[0008文档生命周期](changes/0008-document-lifecycle/intent.md)：先读intent/spec/plan/REVIEW与[验证](changes/0008-document-lifecycle/verification.md)。新增DocumentRemovalController→DocumentLifecycleService→DocumentLifecycleRepository与v5 document_tombstones；当前查询排除墓碑、历史trace/publication和物理配额保留。`RAG_DOCUMENT_REMOVAL_ENABLED`默认false，开启仅新增document_removal能力，其他三个文本开关/阶段不变。A步只有deleting/pending撤下回执，B步物理清理与重建、多模态/生产仍未完成。下面0007入口叙述及历史报告不认证新代码。

当前工作入口已切换为[0007文本问答](changes/0007-text-answers/intent.md)：完整目标在blocked后重新启用，恢复本切IMPLEMENTATION。先读0007 intent/spec/plan/REVIEW/interfaces和[最新验证](changes/0007-text-answers/verification.md)。下面0005/0006属于已交付基线，其指纹与测试不认证新增问答源码；前端详情页、旧服务和生产gate保持原边界。

保留的行为基线[0006 摄取后台授权复验](changes/0006-ingestion-authorization/intent.md)已通过当时本地验收：297项Java、73项Node、150个Java文件格式检查通过，原283项用例逐项保留；历史快照见[0006验证](changes/0006-ingestion-authorization/verification.md)与[source-manifest](changes/0006-ingestion-authorization/source-manifest.json)。新增0007后不能沿用该源码认证。

0007现已接通默认关闭的授权文本问答/引用HTTP、search/rerank/extract及v4 trace。主线程于2026-09-07 15:59:54 +08:00使用实际Temurin21.0.12.1+1完成本地全量验证：635项JUnit、212文件Spotless、原双80%门禁及73项Node通过；本批具名条件/Domain修复及policy v2、具体源码边界见[0007验证](changes/0007-text-answers/verification.md)。这不是完整0007语义、同生产镜像、真实provider/Milvus质量、网页或生产验收，整体仍为IMPLEMENTATION。

## 结构基线 0005：本地重构验收通过

负责人已批准的传统Spring职责分层重构完成本地验收。[0005工件](changes/0005-spring-layering/intent.md)与[Java规范](JAVA_DEVELOPMENT_STANDARDS.md)是当前结构source of truth；原0004索引行为及安全gate继续有效。旧万能Module和Runtime已拆除，下面的地图指当前工作树。0005只改Java结构，0006另补授权行为；均未修改前端详情页、推送或部署，不意味着完整RAG eval或生产验收通过。

先读 0005 的 [intent](changes/0005-spring-layering/intent.md) → [spec](changes/0005-spring-layering/spec.md) → [plan](changes/0005-spring-layering/plan.md) → [REVIEW](changes/0005-spring-layering/REVIEW.md)，再按改动链路读 0004/0003 行为工件。最终 `clean verify` 于 2026-09-07 10:52:26 +08:00 通过：283 项 Java 测试（含 11 项架构测试，0 失败/错误/跳过）、148 个 Java 文件的 Spotless 检查；Node 回归 73 项通过。[baseline-manifest](changes/0005-spring-layering/baseline-manifest.json) 中原 256 项测试逐项保留；重构后证据使用 [verification](changes/0005-spring-layering/verification.md)及[source-manifest](changes/0005-spring-layering/source-manifest.json)，不使用旧指纹认证新源码。

## 保留的 0004 功能增量

当前索引行为以 [text-index-publication](changes/0004-text-index-publication/intent.md) 的intent→spec→plan→REVIEW，接通显式索引任务、独立Java embedding/Milvus进程、完整revision验证和authority事务发布；契约见[TEXT_INDEXING](TEXT_INDEXING.md)。0003已发布基线bc82a7a的190项Java、42项Node、独立审查、浏览器、重启与Java21 CI是历史证据，旧manifest不认证新增源码。当前[验证](VERIFICATION.md)分别记录本切检查与未验证项，不推定真实provider/Milvus或生产通过。

新代码检索：`controller` 接 HTTP，`service` 实施用例、当前授权与事务编排，`repository` 封装 SQL/行映射及唯一 SqliteAuthorityStore。索引链路由 IndexingService / IndexingTaskProcessor / IndexingJob / worker.indexing 构成；摄取由 IngestionService / IngestionTaskProcessor / IngestionJob / worker.parser 构成。两条链路共享一个 authority Connection/monitor，业务恢复在 Config 装配 Service 时执行，不在 Store 构造器或后台 Job 中隐式执行。

## 当前事实

本仓库是单组织知识库的独立Java迁移版，默认提供management_slice；问答关闭时仅开启摄取为text_ingestion、开启索引为text_indexing；开启问答时为text_answers。三个开关独立、默认关闭，均要求development/test及字面loopback。问答后端可宣告answers/sources，列表can_answer仍false且网页未接线，readiness仍503。不是完整Java RAG或生产版本，不能以历史测试或能力名称推定完整验收。

当前管理能力：同源网页、JWT/显式本机开发身份、ACL列表与分页、元数据编辑、目录管理、逐项批量回执。合成资料只能显式seed；0003额外接通用户主动上传真实文本文件、持久任务和解析证据，默认关闭，不自动读取文件或导入业务资料。

`TextParser`支持PDF/TXT/Markdown和可还原分块，0003通过独立进程连接上传、任务与SQLite证据。索引或问答任一启用时，TextAdaptersConfiguration只加载一次完整TextAdapterSettings（三套模型Endpoint均必填），装配不发网络。索引任务只调用embedding与Milvus写入/验证；`RAG_INDEXING_TIMEOUT_MS`默认60000、范围10–600000，单并发独立进程、batch≤16、revision≤4096段，完整校验后才发布active。parsed与indexed分开；多模态处理仍未接通。

0007的AnswerController/AnswerRequestMapper经AnswerService接通query embedding、只读prepareSearch、范围前置的Milvus search、权威hydrate、rerank/extract和TextGrounding；EvidenceService/EvidenceRepository负责完整scope复验与v4不可变trace。显式空选择零外部调用，不回退全库；来源只允许原回答者在当前完整ACL/active仍有效时回读。最终Store锁内复验预算及本地模型/投影资格，再与trace同事务提交；不能原子冻结外部Milvus。接口见[API](API.md)，协议见[TEXT_ADAPTERS](TEXT_ADAPTERS.md)，本地替身不证明实际质量。

## 推荐阅读顺序与 source of truth

1. [README](../README.md)：定位与运行方法。
2. 本文件：[AI_CONTEXT](AI_CONTEXT.md)：能力状态与检索入口。
3. [ARCHITECTURE](ARCHITECTURE.md)：Module 边界、代码地图、数据与信任边界。
4. [ROADMAP](ROADMAP.md)：下一纵切和尚未满足的发布条件。
5. [API](API.md)：实际 HTTP 契约，而非未来接口草案。
6. 当前结构变更：[0005 intent](changes/0005-spring-layering/intent.md) → [spec](changes/0005-spring-layering/spec.md) → [plan](changes/0005-spring-layering/plan.md) → [REVIEW](changes/0005-spring-layering/REVIEW.md)；现有索引功能契约继续读 [0004](changes/0004-text-index-publication/spec.md)。历史发布和重构前冻结证据均不认证新源码。
7. [VERIFICATION](VERIFICATION.md)：本次快照的测试、浏览器证据与未验证项；没有记录的结果不可推定通过。

后续变更使用 `docs/changes/NNNN-topic/{intent,spec,plan,REVIEW}.md`。工件先明确行为和验收，再按小纵切实现；验证失败不能通过删除、跳过或放宽测试来消失。

## 快速代码检索地图

| 想找什么 / English keywords | 源码与验证入口 |
| --- | --- |
| 应用启动、合成演示 / bootstrap, synthetic fixtures | [RagApplication](../src/main/java/com/evidence/rag/RagApplication.java)、[PersistenceConfiguration](../src/main/java/com/evidence/rag/config/PersistenceConfiguration.java)、[DemoFixtures](../src/main/java/com/evidence/rag/bootstrap/DemoFixtures.java) |
| 权威连接与事务 / shared connection, rollback, migration | [SqliteAuthorityStore](../src/main/java/com/evidence/rag/repository/SqliteAuthorityStore.java)、[AuthoritySchema](../src/main/java/com/evidence/rag/repository/AuthoritySchema.java)、[Store 回归](../src/test/java/com/evidence/rag/repository/SqliteAuthorityStoreTest.java) |
| ACL、分页、批量回执 / management, permissions, partial failure | [ManagementService](../src/main/java/com/evidence/rag/service/ManagementService.java)、[ManagementRepository](../src/main/java/com/evidence/rag/repository/ManagementRepository.java)、[ManagementServiceTest](../src/test/java/com/evidence/rag/service/ManagementServiceTest.java) |
| HTTP 请求、PATCH 三态、固定响应 / request, DTO, VO | [ManagementController](../src/main/java/com/evidence/rag/controller/ManagementController.java)、[ManagementRequestMapper](../src/main/java/com/evidence/rag/web/converter/ManagementRequestMapper.java)、[ManagementResponseMapper](../src/main/java/com/evidence/rag/web/converter/ManagementResponseMapper.java)、[ManagementHttpTest](../src/test/java/com/evidence/rag/web/ManagementHttpTest.java) |
| 领域身份和证据 / Actor, revision, claim, parser results | [Actor](../src/main/java/com/evidence/rag/model/domain/Actor.java)、[IndexClaim](../src/main/java/com/evidence/rag/model/domain/IndexClaim.java)、[IngestionClaim](../src/main/java/com/evidence/rag/model/domain/IngestionClaim.java)、[ParsedText](../src/main/java/com/evidence/rag/model/domain/ParsedText.java) |
| JWT、Cookie、Origin / authentication, authorization, session | [JwtAuthenticator](../src/main/java/com/evidence/rag/security/authentication/JwtAuthenticator.java)、[RequestAuthenticator](../src/main/java/com/evidence/rag/security/web/RequestAuthenticator.java)、[AuthenticationFilter](../src/main/java/com/evidence/rag/security/web/AuthenticationFilter.java)、[DocumentPermissionPolicy](../src/main/java/com/evidence/rag/security/authorization/DocumentPermissionPolicy.java)、[JWT HTTP](../src/test/java/com/evidence/rag/security/JwtHttpTest.java) |
| 身份切换、旧响应、选中范围 / epoch, stale response, selection | [workbench-state.mjs](../src/main/resources/static/workbench-state.mjs)、[app.js](../src/main/resources/static/app.js)、[UI tests](../ui-tests/) |
| PDF、Unicode 定位、分块 / PDFBox, code point locator, chunking | [TextParser](../src/main/java/com/evidence/rag/tool/parser/TextParser.java)、[ParserProtocol](../src/main/java/com/evidence/rag/worker/parser/ParserProtocol.java)、[TextParserTest](../src/test/java/com/evidence/rag/tool/parser/TextParserTest.java) |
| 上传和持久任务 / ingestion, quota, evidence acceptance | [UploadServlet](../src/main/java/com/evidence/rag/web/UploadServlet.java)、[IngestionService](../src/main/java/com/evidence/rag/service/IngestionService.java)、[IngestionRepository](../src/main/java/com/evidence/rag/repository/IngestionRepository.java)、[IngestionTaskProcessor](../src/main/java/com/evidence/rag/service/IngestionTaskProcessor.java)、[IngestionJob](../src/main/java/com/evidence/rag/job/IngestionJob.java) |
| 索引发布 / indexing, generation, manifest, publication | [IndexingService](../src/main/java/com/evidence/rag/service/IndexingService.java)、[IndexingRepository](../src/main/java/com/evidence/rag/repository/IndexingRepository.java)、[IndexingTaskProcessor](../src/main/java/com/evidence/rag/service/IndexingTaskProcessor.java)、[IndexingJob](../src/main/java/com/evidence/rag/job/IndexingJob.java)、[IndexWorkerLifetime](../src/main/java/com/evidence/rag/worker/indexing/IndexWorkerLifetime.java) |
| 问答HTTP、独立开关与共享配置 / answers, opt-in, composition | [AnswerController](../src/main/java/com/evidence/rag/controller/AnswerController.java)、[AnswerRequestMapper](../src/main/java/com/evidence/rag/web/converter/AnswerRequestMapper.java)、[AnswersConfiguration](../src/main/java/com/evidence/rag/config/AnswersConfiguration.java)、[TextAdaptersConfiguration](../src/main/java/com/evidence/rag/config/TextAdaptersConfiguration.java)、[AnswersHttpTest](../src/test/java/com/evidence/rag/web/AnswersHttpTest.java) |
| 授权证据、最终资格与来源 / full scope, hydrate, trace, source | [AnswerService](../src/main/java/com/evidence/rag/service/AnswerService.java)、[EvidenceService](../src/main/java/com/evidence/rag/service/EvidenceService.java)、[EvidenceRepository](../src/main/java/com/evidence/rag/repository/EvidenceRepository.java)、[AnswerEligibility](../src/main/java/com/evidence/rag/model/domain/AnswerEligibility.java) |
| 文本事实与确定性样例 / grounding, golden | [TextGrounding](../src/main/java/com/evidence/rag/tool/answer/TextGrounding.java)、[AnswerGoldenTest](../src/test/java/com/evidence/rag/service/AnswerGoldenTest.java) |
| 模型 / Milvus 协议 / embedding, vector projection | [OpenAiCompatibleModels](../src/main/java/com/evidence/rag/client/model/OpenAiCompatibleModels.java)、[MilvusRestProjection](../src/main/java/com/evidence/rag/client/vector/MilvusRestProjection.java)、[TEXT_ADAPTERS](TEXT_ADAPTERS.md) |
| 能力发现与错误出口 / readiness, exceptions, runtime guard | [RuntimeService](../src/main/java/com/evidence/rag/service/RuntimeService.java)、[RuntimeController](../src/main/java/com/evidence/rag/controller/RuntimeController.java)、[RuntimeGuard](../src/main/java/com/evidence/rag/config/RuntimeGuard.java)、[HttpProblemMapper](../src/main/java/com/evidence/rag/web/HttpProblemMapper.java)、[ProblemHandler](../src/main/java/com/evidence/rag/web/ProblemHandler.java) |

## 分层修改时必须知道

- Controller/Web 只处理协议并调用 Service；请求 Map 在 Web 转为 DocumentPatchCommand/DocumentActionCommand/DocumentQuery，Service 返回安全 DTO，必要时 Web 映射公开 VO。目录的 FolderResult/FolderRemovalResult 已与公开契约一致，直接输出；FolderListResult 同 DTO 包复用并防御复制，不增设重复 VO。保留 JSON 原字段、null/缺失/空值和错误次序；Entity/claim 不作为 HTTP 响应。
- Model 包不是大模型 Provider，也不是多一级执行流程：Entity 是持久化快照，DTO 是命令/安全结果，Query 是条件，VO 是展示，Domain 是身份与证据不变量。Domain 无 Spring/Servlet/JDBC/Jackson 依赖；稳定无 HTTP 的异常允许使用。
- Service 调用 Store.transaction 确定业务原子性，Repository 必须使用该 Store 的当前线程事务。Schema 与原始 SQL 方法保持包私有，没有 Repository 各自连接/提交，也不强制造 ServiceImpl 或 BaseService。
- Job 只调度、取消和管理线程；TaskProcessor 实施一次远程处理用例；Worker 管理独立 JVM/协议/预算；Client 管理外部 HTTP 协议；Tool 处理确定性解析/算法。它们不是每个请求必须依次经过的空层。
- Security 的 HTTP 凭据提取、无 HTTP 的 JWT 验证、纯权限策略分开；仍采用既有自定义 Filter + JOSE 验证，不宣称完整 Spring Security FilterChain/方法鉴权已经接入。
- 索引claim/current/complete复验当前创建者写权限；0006另以独立规格与14项先红后绿回归补齐摄取后台复验。当前creator失权时任务系统取消、审计同事务；旧/伪造claim无副作用。status与latest_job的can_retry同时要求调用者和创建者当前可写，can_cancel仍只要求调用者可写。具体终态、恢复及422校验顺序见[0006规格](changes/0006-ingestion-authorization/spec.md)。
- 错误类别 ApplicationException/FailureKind 与 HTTP status 映射分开；日志/异常脱敏若有新增调整须单列行为验证。不能把纯结构迁移、异常治理和既有安全事实混为一谈。
- [AuthorityTestContext](../src/test/java/com/evidence/rag/support/AuthorityTestContext.java)只在测试源集，组装真实Store/Repo/Service并使用生产HTTP转换器，以保留原行为断言；不能把它复制进生产重新造万能类。0006验证只覆盖历史基线，当前格式、架构、进程/HTTP及未验证范围以[0007验证记录](changes/0007-text-answers/verification.md)为准，不由总测试数推定完整审查或外部验收。

## 容易误读的地方

- 列表的`synthetic_fixture: true`、`segment_count: 0`、空`modalities`表明整理演示；合成注册身份在`registered_revision_id`，active为null。真实active只来自v3完整publication；即使active非空也不代表当前可问答。
- source revision与projection generation不同：HTTP任务revision及active字段仍为source；每次claim新UUID generation写入Milvus的revision_id，物理segment ID由generation+source ID规范摘要导出。0007 AuthorizedScope从active publication取generation，候选按publication entries回到source证据，禁止拿HTTP source revision直接查询投影。
- 当前SQLite格式为v4，保留v1–v3迁移及不可变证据，新增query_traces/query_trace_documents/query_trace_evidence。trace保存完整scope、摘要、版本和locator，不存原问题/全文/凭据；问答关闭也不跳过格式迁移。历史v3 publication仍是active权威，trace不是第二套语料存储。
- 旧HTTP晚写由generation物理namespace隔离；本地父存活watchdog与跨JVM lease不撤回上游请求、不构成OS沙箱。lease文件永不unlink，父崩溃job临时目录需后续回收；缺generation/台账的早期WIP v3拒绝打开。相关P1/异常父进程测试仅依当前VERIFICATION认定，不能推定已验收。
- 类型筛选包含 `image/audio/video`，只代表合成资料元数据分类，不代表多模态处理已接通。
- `/health/live` 为 200 只说明进程存活；`/health/ready` 刻意为 503，表示迁移及生产门禁不完整。
- JWT 校验与开发用资料管理不等于企业 SSO、用户管理、令牌签发/刷新或生产身份系统。
- 四份[合成 PDF](../src/test/resources/corpus/)与未修改的[golden.json](evals/golden.json)已由AnswerGoldenTest接入Java解析/发布/问答链路，六项确定性替身用例通过；不是实际provider质量、召回率或完整语义覆盖报告。
- TextParser与子进程/authority/HTTP回归已随0006源码重新运行，具体覆盖及未验证项以[0006验证记录](changes/0006-ingestion-authorization/verification.md)为准，不复用历史测试数量。直接调用TextParser仍无进程隔离；ProcessTextParser也不是OS文件/网络沙箱，不能用于公网生产上传。
- embedding与Milvus写入/完整验证已接到语料authority/worker；0007另接查询、rerank/extract与回答/来源。只读prepareSearch不create/load/upsert，也不证明loaded；Strong/schema/index与精确回读不是跨HTTP快照。不能接其他系统数据补演示，原文摘取或六golden通过不是完整事实支持证明。
- 启用 virtual threads 是配置事实，不是吞吐或延迟提升证据；目前没有 Java 与其他语言实现的对照基准。

## 当前与后续 RAG 的安全基线

以下是当前0007与后续实现持续必须满足的 invariant，不是完整问答或生产完成声明：

- 文档是低信任数据，不是系统指令；无足够证据必须拒答。
- 候选进入模型前必须按当前组织、ACL、active revision 与用户选中范围求交；过滤必须在候选 top-K 截断之前生效。
- **完整 selected set** 都要验证，不能截断选中 ID 列表。显式空范围、任一所选资料不可用/无权访问都不能退回全库；模型调用后、答案提交前再次校验完整范围及使用的证据版本，发生变化则安全失败。
- 引用只能由服务器依据权威 source locator 校验和构造；不能信任模型自由生成页码、链接或检索投影正文。
- 修改显示名、目录、手工标签不能改变原文件名、源摘要、revision / segment 身份，也不能触发模型处理。
- 发布、更新、删除、重新索引必须可追踪；答案应可还原到文档版本、分块、模型和提示版本。
- 所有多模态仍待实现。未来音画联合答案必须逐事实证明，且同一 EvidenceGroup 的 visual/transcript pair 覆盖全部事实，两个模态各自至少贡献一个过阈值事实；不能用查询附件替代文本事实证明，预算中断或覆盖不足则拒答。

修改前阅读 [AGENTS](../AGENTS.md)，完成后更新当前变更工件与验证证据，不以计划文字替代可观察行为。
