# AI Context：从这里理解仓库

2026-09-22 17:39:50当前：第二组具名2次诊断获准并完成，累计6/20、未使用14。原/full真正证明均命中SourceInstructions.INSTRUCTION的instruction_in_field；转录SHA与诊断01相同，规范化731=true/AU=false。具体字符不猜测，安全规则不放宽。下一步核对ASR配置/候选而不是重做证明规则；613输入不变、无重试、视频未开始，真实质量与生产未通过。见[台账](changes/0019-audio-video-provider-eval/provider-run.md)，下方2/12等仅为历史。

2026-09-22当前执行终态：[0019本批台账](changes/0019-audio-video-provider-eval/provider-run.md)记录真实LiveIT的`eval_audio_not_grounded`失败，ASR与摘录各1次，2/12后停止，视频0次。未使用10次不得自动转作诊断/重试，后续云调用须另行明确授权。原始响应未记录，不能据同名安全码推断根因；613输入未改，不重跑本机冻结或放宽证明。真实质量与生产未完成，全部既有范围边界保持。下方未授权/NOT_RUN表述为历史快照。

最新入口：[0019音视频模型评测工具](changes/0019-audio-video-provider-eval/intent.md) intent→spec→plan→REVIEW。本机工具范围AVE-01～06及独立制品审计通过，见[verification](changes/0019-audio-video-provider-eval/verification.md)：1685默认/23 native/73 Node、582格式/双80%，613输入且旧606原字节和旧身份多重性保持。只新增测试域共享评测/LiveIT、固定合成资源及说明，未改生产代码；生产JAR的443个class与0018逐字节一致。完整PCM/全部选帧、F+9事前预算、失败计数无重试、原文与同组双事实已验；LiveIT为NOT_RUN，下一步按[运行说明](AUDIO_VIDEO_PROVIDER_EVAL.md)在新授权/私有轮换密钥/模型名就绪后执行真实评测。不要重做本机工具或装配诊断；真实provider/Milvus整链、网页和生产仍未完成，前端/旧服务/数据/Git写入/部署边界不变。下方为历史入口。

最新入口：[0018完整多模态配置装配](changes/0018-multimodal-composition/intent.md) intent→spec→plan→REVIEW。本机MC-01～06与独立制品审计已通过，见[verification](changes/0018-multimodal-composition/verification.md)：仅真实RagApplication与生产Bean，共同开启已有能力并完成四类上传/任务/完整索引、附件问答、音频/视频OCR/字幕来源、短摘要与重启零调用。没有生产源码或旧测试修改，606输入中旧604原字节保持；1680默认/22 native/73 Node与原格式/双80%通过。配置与运行边界见[MULTIMODAL_RUNTIME](MULTIMODAL_RUNTIME.md)。这不是production profile、真实模型效果、网页或生产发布验收；hierarchy只在本切确认装配，既有专项行为测试保留。下一主线为新授权下的真实provider/Milvus质量，不重复已验装配/协议诊断。前端、旧服务/数据、Git写入/部署与云调用边界不变，下方是历史入口。

最新执行入口：[0017查询附件](changes/0017-query-attachments/intent.md) intent→spec→plan→REVIEW。[后端合同](QUERY_ATTACHMENTS.md)已接完整scope→请求期编译/完整分批检索/原图匹配→原完整问题与库内证据证明→v16 hash-only终态trace→opt-in有界HTTP。真实合成PNG/WAV/MP4请求及重启零模型来源回读见[附件答案验证](changes/0017-query-attachments/answers-verification.md)。旧客户端与事实证明不改；QA-01～12后端验证不等于云质量、前端或生产完成。不要重做已冻结输入/排序/授权trace/HTTP诊断；下一交付需要真实质量与生产配置验收，并保持新增云调用、前端和部署需对应授权的边界。下方为历史入口。

当前入口：[0016内嵌字幕](changes/0016-subtitle-tracks/intent.md) intent→spec→plan→REVIEW。[字幕后端](VIDEO_SUBTITLES.md)已接全轨原包→v15独立authority/完整publication→`mode=subtitle`同轨全文证明→独立typed时间/原视频Range→第八类完整摘要材料/重启零模型读取。验收与源码绑定见[library-verification](changes/0016-subtitle-tracks/library-verification.md)。Runtime为显式opt-in、默认关闭，旧ASR/OCR/joint合同保持；raw markup不冒充渲染文字，caption/摘要不作证据。文字证明v5保留中性标签与示例区别，完整共享语义须冻结。下一主线为查询附件，随后真实质量、前端和生产；不重复已完成字幕协议/存储/HTTP诊断。前端、旧服务/数据、Git写入/部署与云调用边界保持。下方入口仅为历史状态。

当前入口：[0015文件摘要](changes/0015-file-synopsis/intent.md) intent→spec→plan→REVIEW。[本机后端合同](FILE_SYNOPSIS.md)已接完整长文件原证据、分层候选/实际引用证明/每批原材料复查，v14持久任务与原typed来源HTTP；[hierarchy-verification](changes/0015-file-synopsis/hierarchy-verification.md)记录1485 Java/73 Node/512格式/双80%和旧1429身份保留。SynopsisLibraryService拥有授权与最终事务，短SynopsisService及新HierarchicalSynopsisService只生成/核验；新Domain保持真实publication/全文件指纹，派生节点不是原证据。Config默认关闭且不依赖Answers/Milvus，关闭仍无模型恢复；旧短协议/结果不变。下一业务主线独立字幕轨与typed时间来源，不重做摘要协议/迁移/HTTP。云质量、查询附件、网页/生产未完成，下方为历史入口。

当前实现入口为0014[视频选中原帧OCR](VIDEO_OCR.md)：先读当前intent→spec中的VOCR合同→plan→REVIEW。默认关闭的v2 compiler共用ImageOcr/ProcessImageParser，完整无字结果也封存；v12独立附表保存frame-local文字、词框、publication和OCR trace。`mode=ocr`使用共用文字证明，混合物理命中先完整authority分类再筛选；旧三模式及v11贡献语义保持。本机冻结已通过1352 Java、73 Node、458格式/双80%和单列native6，[OCR验证记录](changes/0014-video-library/ocr-verification.md)绑定当前源码及旧1299身份/多重性；以下1299是上一冻结基线。只识别已有选帧，真实Tesseract合成英文与本机模型/Milvus替身不代表独立字幕轨、全视频文字或中文质量；网页与生产未完成。

最新入口（2026-09-20）：0014[视频授权问答与来源](VIDEO_ANSWERS.md)已本机通过，完整scope→真实publication候选/同组原帧转录→逐事实全覆盖→v11 trace→typed时间/原帧/原视频Range已接通；[answers-verification](changes/0014-video-library/answers-verification.md)绑定461输入、1299 Java/73 Node、437格式/双80%和单列native5。旧1216用例身份/多重性保留。下一主线为视频原帧OCR文字与真实定位，不重做输入/入库/证明/授权HTTP诊断；独立字幕轨、摘要、查询附件、网页、云质量及生产继续保留。内部span句柄已通过publication映射后才发布，caption只召回。模型/Milvus替身不认证云质量；前端/旧服务/数据、Git分支/提交/推送/部署及云调用边界不变。下方为历史快照，“当前/下一步”不作为现行执行入口。

当前只推进[0014视频主线](changes/0014-video-library/intent.md)的intent→spec→plan→REVIEW。步骤2[视频HTTP上传/持久任务/独立authority/真实EvidenceGroup/完整索引](VIDEO_PUBLICATION.md)已本机验收：1171 Java、native10、73 Node与383格式/双80%，407输入绑定见[验证](changes/0014-video-library/publication-verification.md)。下一步为共同事实身份、原帧+转录同组证明和typed时间来源；caption不是真实视觉证明，indexed不等于视频可问答。旧octet音频容器合同保留，显式video MIME只选处理合同、真实流型由后台核实。前端、旧服务/数据不动，不调用云模型、不推送部署；以下是历史基线。

当前执行入口是[0013音频时间证据](changes/0013-audio-library/intent.md)的intent→spec→plan→REVIEW。默认关闭的音频装配已接入既有上传/任务/索引HTTP、v8完整音频authority和publication；新增共用AnswerService的完整转录证明、v9音频trace、typed时间来源与原音频单byte Range。接口/时间精度/预算边界见[AUDIO_COMPILATION](AUDIO_COMPILATION.md)，实际验收以0013当前记录为准，不将替身测试写成云模型质量或网页验收。0012为保留的图片基线，不重复已完成诊断，不挪用旧文本预算调用音频云模型。

当前入口：[0012原图知识库](changes/0012-visual-library/intent.md) intent→spec→plan→REVIEW及[Interface](VISUAL_LIBRARY.md)。caption存独立image_evidence仅用于召回，原图问答有单独typed HTTP；全scope/ACL/active/trace复用，0文字页/分块不是假OCR。2026-09-10本地942 Java/73 Node及独立限定审查通过，[源码绑定与验收](changes/0012-visual-library/verification.md)为当前证据；云质量、真实Milvus新图、网页与生产未验收。下方0011及此前为历史记录。

当前实现入口：[0011视觉模型](changes/0011-visual-models/intent.md) intent→spec→plan→REVIEW。VisionModels与VisualAssessmentService只处理原图/完整问题/逐事实核验；caption仅供未来召回。还没有纯视觉authority/检索/HTTP接线，不给runtime增加能力旗标。Interface与明确未完成项见[VISION_MODELS](VISION_MODELS.md)。

2026-09-09当前入口：[0010图片OCR区域](changes/0010-image-regions/intent.md)的intent/spec/plan/REVIEW，承接0009整图引用。独立ParsedImage与不可变区域附表，不机械扩展ParsedText/ParserProtocol或Milvus投影；历史验证不认证新增源码。

2026-09-08最新入口：[0009图片证据](changes/0009-image-evidence/intent.md)的intent/spec/plan/REVIEW及[图片接口说明](IMAGE_EVIDENCE.md)。负责人已要求多模态成为主线；先图片文字证据正常闭环，后音频/视频/联合事实。0007固定PDF真实后端结果见[mainline-live](changes/0007-text-answers/mainline-live.md)。下方历史入口不触发重做文本诊断或0008清理B；新图片能力与未验证项只按0009记录声明。

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
| 视频原帧文字与像素词框 / video OCR, frame-local evidence, v12 | [VIDEO_OCR](VIDEO_OCR.md)、[ImageOcr](../src/main/java/com/evidence/rag/worker/parser/ImageOcr.java)、[VideoCompilationService](../src/main/java/com/evidence/rag/service/VideoCompilationService.java)、[VideoFrameOcr](../src/main/java/com/evidence/rag/model/domain/VideoFrameOcr.java)、[VideoOcrResult](../src/main/java/com/evidence/rag/model/dto/VideoOcrResult.java) |
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
