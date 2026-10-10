# AI Context：从这里理解仓库

2026-10-09当前本地主线为[0055 Wiki工作区](changes/0055-wiki-workspace/spec.md)：Controller/Service/Repository/Model明确分层，完整已发布原文编译成待审提案，人工采纳产生不可变页版本及逐章节来源。Schema31仅本地临时库验证；线上仍0054/schema30，保留已授权免登录。模型协议和原问答保留，派生页面不作原文证明。API见[WIKI_WORKSPACE](WIKI_WORKSPACE.md)，真实质量/前端接线/部署边界见0055 REVIEW。

最新合同为[0053共享工作区](changes/0053-shared-workspace-rag/spec.md)：保留登录、组织隔离、来源/版本/删除状态；组织内不分角色/逐文档权限，普通知识问答与召回固定全库。统一问答通过TextModels.answerKnowledge一次综合原始检索片段，不再调用摘录/手写事实证明/二次核验；旧专门媒体模式独立。schema30取消全库128份与普通回答32引用存储上限，Milvus按128份过滤批次查询、全局融合，TopK不是权限范围。当前本地实现未发布，验证见[REVIEW](changes/0053-shared-workspace-rag/REVIEW.md)。下方旧合同保留历史，不作为回退新需求的依据。

2026-10-03当前0035：[保存材料重建](changes/0035-saved-source-reindex/verification.md)。已发布资料可明确重建保存的完整文本索引，处理期间旧索引可用，成功才切换；失败、取消和重启中断保留旧版本。入口核对真实能力及当前资格，成功后提示重新查询，保留问题、范围与整理草稿。模型配置、逐角色测试、明确应用和召回测试继续沿既有流程。首切不支持已有独立图片/音频向量的资料及真正嵌入/投影迁移；后续receipt迁移与原文件版本替换继续保留。未部署，页面用户验收，0新增真实provider调用，完整目标ACTIVE。

当前0034已本机修复文字角色切换后的旧图片、视频/OCR/字幕来源回读，来源cap与新问答分开；已有媒体POST门禁保持。实际3075 Java、431前端及六Native各1和原门禁通过，详情见[验证](changes/0034-media-role-switch-sources/verification.md)。交接以工作区`.tools/media-role-switch-handoff`实际manifest为准，未部署、真实模型未验、页面用户验收，目标active。下一切继续基础召回范围入口；通用重建、嵌入迁移及同资料版本更新仍依原范围待完成。下方为历史记录。

2026-10-03当前基础修复0033：已有文字索引后，仅更换生成或重排模型可以保存、单独测试并明确应用；嵌入配置及投影不变时不重建资料索引。实际新角色与新trace、原索引/旧来源、连续切换及重启均已本机验证；真正嵌入或投影变化仍拒绝，legacy媒体按实际完整profile判定。最终3058 Java、1013格式、原LINE/BRANCH双80与架构、六Native各1通过；1029后端输入相同、761生产class稳定。前端64及后端18 Node/static输入字节不变，430/check与73明确复用此前实跑证据。新交接.tools/model-role-switch-handoff以实际manifest/VALIDATION为准；未部署、0新增真实provider调用、页面用户验收、真实ASR未宣称修复，目标active。历史记录保留。

2026-10-03当前本机主线：[模型配置与召回测试](MODEL_SETUP_AND_RETRIEVAL.md)已接通保存草稿、逐角色连接测试、明确应用、完整范围召回预览与同版本来源；无模型可启动，应用丢响应后显式读取能恢复索引/召回入口。资料清理及取消后清理恢复一并整合。实际3011 Java、1004格式、原LINE/BRANCH双80%、430前端/check、73后端Node及六Native各1通过；1020/64执行输入相同，759完整生产class与最终JAR一致，旧2640/370用例身份多重性保留。详见[0032验证](changes/0032-model-setup-retrieval-test/verification.md)。交接工作区`.tools/model-setup-handoff`以实际manifest/VALIDATION为准；未部署、0新增真实provider调用、页面用户验收，原ASR质量未宣称解决，目标active、usage/计费取消。下方保留历史记录。

当前本机稳定增量[0027原声向量检索](changes/0027-audio-vector-retrieval/verification.md)：独立GoogleAudio完整PCM embedding、显式全部speech spans构建、v18 immutable receipt、新generation以及完整scope dense召回已实现。查询沿同次解码/ASR保留全部PCM声段，向量仅用于召回，原转录、SHA及typed时间来源继续作证。2042 Java、299前端、73后端Node、4项单列native与原格式/覆盖门禁通过；732/46输入不变、529 native生产类与最终JAR一致。配置见[AUDIO_VECTOR_RETRIEVAL](AUDIO_VECTOR_RETRIEVAL.md)。未部署、无真实模型调用、网页由用户验收；下一主线为非语音声音证据，真实ASR旧失败保留。下方为历史记录。

当前本机主线[0026原图向量检索](changes/0026-image-vector-retrieval/spec.md)：当前已发布原PNG/JPEG→编辑者显式独立build→receipt绑定旧publication/source与独立profile/generation→参考图片dense召回→完整authority映射→旧库内原图证明及来源。无参考图继续旧路；完整scope缺当前向量明确image_vector_required。前端0015配套，默认关闭，配置见[IMAGE_VECTOR_RETRIEVAL](IMAGE_VECTOR_RETRIEVAL.md)，验证见[0026](changes/0026-image-vector-retrieval/verification.md)。本切未部署；实际voice-tags已部署含标签/语音，部署报告与只读入口结果核对，完整网页用户验收及真实ASR/provider仍开放。下方为历史记录。

0025语音输入（`POST /v1/voice-questions`、`voice_questions`能力、`RAG_VOICE_QUESTIONS_*`）已于2026-10-10移除，唯一前端调用已随旧页面删除；不要重新引入。AudioCompilationService等共享音频/ASR代码继续服务声音库、视频与查询附件。历史见[0025](changes/0025-voice-questions/spec.md)。

当前本机增量[0024摘要建议标签](changes/0024-tag-suggestions/spec.md)：纯Tool从当前available FileSynopsis生成最多8个完整短条目，Service单事务读取/确认合并并复用Management审计。候选fingerprint绑定完整摘要/publication但不含可变tags，确认期间新增标签保留。前端0013以明确选择保存；没有新模型调用/配置/迁移。验证见[0024记录](changes/0024-tag-suggestions/verification.md)，现网仍待本包发布。

当前本机主线为[0023扫描PDF](changes/0023-scanned-pdf/spec.md)：独立PdfOcrOptions/Configuration→摄取profile→隔离PdfOcrCompiler/父子进程生命周期→原ParsedText与索引/问答。全页OCR、完整结果或失败，原parser protocol v1保持。来源沿原服务器页码/CP/SHA，前端0012追加原PDF按页回看。实际证据见[verification](changes/0023-scanned-pdf/verification.md)，配置见[PDF_OCR](PDF_OCR.md)；不把本机native当网页、云质量或生产。

2026-10-03当前源码入口：[0021原文件详情](changes/0021-document-originals/verification.md)与[0022数字序列完整性](changes/0022-audio-numeric-sequences/verification.md)。原文件当前ACL/initial revision/完整SHA读取已接通，前端0009与0021已在独立免登录发布版外部验证；PDF内嵌像素未验。0022只改共享字段/冲突/最小负序列边界与明确policy v6，611项相关回归、592文件格式及构建通过，独立音频交接已冻结但尚未发布。Cedar→Car的ASR与数字识别失败保留，真实复验NOT_RUN；不以本机替身或完整原文件播放认证识别质量。新增usage/计费功能已被用户取消，未产生该项代码/迁移/验证/发布，不继续追加。下方0019等为历史入口及额度，不是当前生产计数或自动追加调用授权。

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
| 应用启动 / bootstrap | [RagApplication](../src/main/java/com/evidence/rag/RagApplication.java)、[PersistenceConfiguration](../src/main/java/com/evidence/rag/config/PersistenceConfiguration.java) |
| 权威连接与事务 / shared connection, rollback, migration | [SqliteAuthorityStore](../src/main/java/com/evidence/rag/repository/SqliteAuthorityStore.java)、[AuthoritySchema](../src/main/java/com/evidence/rag/repository/AuthoritySchema.java)、[Store 回归](../src/test/java/com/evidence/rag/repository/SqliteAuthorityStoreTest.java) |
| ACL、分页、批量回执 / management, permissions, partial failure | [ManagementService](../src/main/java/com/evidence/rag/service/ManagementService.java)、[ManagementRepository](../src/main/java/com/evidence/rag/repository/ManagementRepository.java)、[ManagementServiceTest](../src/test/java/com/evidence/rag/service/ManagementServiceTest.java) |
| HTTP 请求、PATCH 三态、固定响应 / request, DTO, VO | [ManagementController](../src/main/java/com/evidence/rag/controller/ManagementController.java)、[ManagementRequestMapper](../src/main/java/com/evidence/rag/web/converter/ManagementRequestMapper.java)、[ManagementResponseMapper](../src/main/java/com/evidence/rag/web/converter/ManagementResponseMapper.java)、[ManagementHttpTest](../src/test/java/com/evidence/rag/web/ManagementHttpTest.java) |
| 领域身份和证据 / Actor, revision, claim, parser results | [Actor](../src/main/java/com/evidence/rag/model/domain/Actor.java)、[IndexClaim](../src/main/java/com/evidence/rag/model/domain/IndexClaim.java)、[IngestionClaim](../src/main/java/com/evidence/rag/model/domain/IngestionClaim.java)、[ParsedText](../src/main/java/com/evidence/rag/model/domain/ParsedText.java) |
| JWT、Cookie、Origin / authentication, authorization, session | [JwtAuthenticator](../src/main/java/com/evidence/rag/security/authentication/JwtAuthenticator.java)、[RequestAuthenticator](../src/main/java/com/evidence/rag/security/web/RequestAuthenticator.java)、[AuthenticationFilter](../src/main/java/com/evidence/rag/security/web/AuthenticationFilter.java)、[DocumentPermissionPolicy](../src/main/java/com/evidence/rag/security/authorization/DocumentPermissionPolicy.java)、[JWT HTTP](../src/test/java/com/evidence/rag/security/JwtHttpTest.java) |
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
