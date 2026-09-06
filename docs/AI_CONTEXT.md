# AI Context：从这里理解仓库

## 0003增量（LOCAL_VERIFIED；Java整体IMPLEMENTATION）

当前变更为 [text-ingestion](changes/0003-text-ingestion/intent.md) 的intent→spec→plan→REVIEW，0002是已发布历史协议基线。管理基线已接通真实文本上传、持久任务、独立Java解析进程和版本化页/segment，并完成190项Java、42项Node、独立审查、浏览器及重启验收；见[TEXT_INGESTION](TEXT_INGESTION.md)与当前[验证](VERIFICATION.md)。`RAG_INGESTION_ENABLED`默认false，显式true且字面loopback绑定才启用；parsed不等于indexed/ready，真实active=null，问答/来源/多模态/生产仍未完成。历史manifest不认证本次源码。

新代码检索：`ingestion/UploadServlet`有界非阻塞接收，`IngestionRuntime`单解析调度，`corpus/ProcessTextParser`进程生命周期，`ParserWorker`有界协议，`ManagementModule`单authority事务/ACL/v2迁移。基线TextParser的直接调用仍是同进程，不可绕过ProcessTextParser接公网文件。

## 当前事实

本仓库是单组织知识库的独立 Java 迁移版，默认提供management_slice；显式开启文本摄取后为text_ingestion。不是完整Java RAG或生产版本。不能将合成元数据、解析成功或其他实现的历史测试当端到端检索证据。

当前管理能力：同源网页、JWT/显式本机开发身份、ACL列表与分页、元数据编辑、目录管理、逐项批量回执。合成资料只能显式seed；0003额外接通用户主动上传真实文本文件、持久任务和解析证据，默认关闭，不自动读取文件或导入业务资料。

`TextParser`支持PDF/TXT/Markdown和可还原分块。0003通过`ProcessTextParser`独立进程连接上传、任务与SQLite证据，尚未连接嵌入、索引发布、检索或回答。图片、音频、视频处理全部planned。不要直接在HTTP线程调用TextParser来绕过子进程限制。

`TextModels` / `OpenAiCompatibleModels` 与 `RetrievalProjection` / `MilvusRestProjection` 是已通过 0002 本地协议回归和独立源码审查的独立 Module；[TEXT_ADAPTERS](TEXT_ADAPTERS.md)记录小 Interface 与显式配置。它们没有 Spring 自动接线或新 HTTP 路由，本地协议检查不是完整 RAG 或真实 provider/Milvus 验收。

## 推荐阅读顺序与 source of truth

1. [README](../README.md)：定位与运行方法。
2. 本文件：[AI_CONTEXT](AI_CONTEXT.md)：能力状态与检索入口。
3. [ARCHITECTURE](ARCHITECTURE.md)：Module 边界、代码地图、数据与信任边界。
4. [ROADMAP](ROADMAP.md)：下一纵切和尚未满足的发布条件。
5. [API](API.md)：实际 HTTP 契约，而非未来接口草案。
6. 当前变更：[intent](changes/0003-text-ingestion/intent.md) → [spec](changes/0003-text-ingestion/spec.md) → [plan](changes/0003-text-ingestion/plan.md) → [REVIEW](changes/0003-text-ingestion/REVIEW.md)。0001/0002的发布与测试记录是历史基线，不认证新增源码。
7. [VERIFICATION](VERIFICATION.md)：本次快照的测试、浏览器证据与未验证项；没有记录的结果不可推定通过。

后续变更使用 `docs/changes/NNNN-topic/{intent,spec,plan,REVIEW}.md`。工件先明确行为和验收，再按小纵切实现；验证失败不能通过删除、跳过或放宽测试来消失。

## 快速代码检索地图

| 想找什么 / English keywords | 源码与验证入口 |
| --- | --- |
| 应用启动、合成演示 / bootstrap, synthetic fixtures | [RagApplication](../src/main/java/com/evidence/rag/RagApplication.java)、[DemoFixtures](../src/main/java/com/evidence/rag/config/DemoFixtures.java) |
| 数据权限、分页、批量回执 / ACL, pagination, partial failure | [ManagementModule](../src/main/java/com/evidence/rag/management/ManagementModule.java)、[ManagementModuleTest](../src/test/java/com/evidence/rag/management/ManagementModuleTest.java) |
| HTTP routes / request-response contract | [ManagementController](../src/main/java/com/evidence/rag/management/ManagementController.java)、[ManagementHttpTest](../src/test/java/com/evidence/rag/web/ManagementHttpTest.java) |
| JWT、Cookie、Origin / authentication, session, CSRF boundary | [AuthenticationModule](../src/main/java/com/evidence/rag/security/AuthenticationModule.java)、[AuthenticationFilter](../src/main/java/com/evidence/rag/security/AuthenticationFilter.java)、[SessionController](../src/main/java/com/evidence/rag/security/SessionController.java)、[JwtHttpTest](../src/test/java/com/evidence/rag/security/JwtHttpTest.java) |
| 身份切换、旧响应、选中范围 / epoch, stale response, selection | [workbench-state.mjs](../src/main/resources/static/workbench-state.mjs)、[app.js](../src/main/resources/static/app.js)、[UI tests](../ui-tests/) |
| PDF、Unicode 定位、分块 / PDFBox, code point locator, chunking | [TextParser](../src/main/java/com/evidence/rag/corpus/TextParser.java)、[TextParserTest](../src/test/java/com/evidence/rag/corpus/TextParserTest.java) |
| 能力发现、拒绝生产启动 / capabilities, readiness, runtime guard | [RuntimeController](../src/main/java/com/evidence/rag/web/RuntimeController.java)、[RagProperties](../src/main/java/com/evidence/rag/config/RagProperties.java)、[RuntimeGuard](../src/main/java/com/evidence/rag/config/RuntimeGuard.java) |

## 容易误读的地方

- 列表的 `synthetic_fixture: true`、`segment_count: 0`、空 `modalities` 表明它是整理演示；`status: ready` 和 `active_revision_id` 不是已解析、已索引、可问答的证明。
- 类型筛选包含 `image/audio/video`，只代表合成资料元数据分类，不代表多模态处理已接通。
- `/health/live` 为 200 只说明进程存活；`/health/ready` 刻意为 503，表示迁移及生产门禁不完整。
- JWT 校验与开发用资料管理不等于企业 SSO、用户管理、令牌签发/刷新或生产身份系统。
- 四份[合成 PDF](../src/test/resources/corpus/)用于解析回归；[golden.json](evals/golden.json)保留未来问答、安全与隔离验收预期，不是 Java 检索评测报告。
- 原TextParser有6个测试方法，新增子进程/authority/HTTP验证以当前[VERIFICATION](VERIFICATION.md)为准。直接调用TextParser仍无进程隔离；ProcessTextParser也不是OS文件/网络沙箱，不能用于公网生产上传。
- 本仓库含独立Milvus/embedding/reranker/OpenAI-compatible Adapter，尚未与新的语料authority/worker/问答接通。不能接其他系统的数据目录/集合补演示；原文摘取不是事实支持证明。
- 启用 virtual threads 是配置事实，不是吞吐或延迟提升证据；目前没有 Java 与其他语言实现的对照基准。

## 后续 RAG 的安全基线

以下是未来实现必须满足的 invariant，不是当前问答功能的完成声明：

- 文档是低信任数据，不是系统指令；无足够证据必须拒答。
- 候选进入模型前必须按当前组织、ACL、active revision 与用户选中范围求交；过滤必须在候选 top-K 截断之前生效。
- **完整 selected set** 都要验证，不能截断选中 ID 列表。显式空范围、任一所选资料不可用/无权访问都不能退回全库；模型调用后、答案提交前再次校验完整范围及使用的证据版本，发生变化则安全失败。
- 引用只能由服务器依据权威 source locator 校验和构造；不能信任模型自由生成页码、链接或检索投影正文。
- 修改显示名、目录、手工标签不能改变原文件名、源摘要、revision / segment 身份，也不能触发模型处理。
- 发布、更新、删除、重新索引必须可追踪；答案应可还原到文档版本、分块、模型和提示版本。
- 所有多模态仍待实现。未来音画联合答案必须逐事实证明，且同一 EvidenceGroup 的 visual/transcript pair 覆盖全部事实，两个模态各自至少贡献一个过阈值事实；不能用查询附件替代文本事实证明，预算中断或覆盖不足则拒答。

修改前阅读 [AGENTS](../AGENTS.md)，完成后更新当前变更工件与验证证据，不以计划文字替代可观察行为。
