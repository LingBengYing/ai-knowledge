# Roadmap：按可验收纵切推进

2026-09-22 17:39最新：第二组诊断已定位同字段指令检测，累计6/20、未使用14；ASR转录规范化字母AU未命中，数字731存在。下一步只读核对ASR可用配置/候选，再进行获准的具名实验；不删注入内容、不补金标、不放宽安全层制造通过。视频、真实Milvus整链与发布仍待验。详见[台账](changes/0019-audio-video-provider-eval/provider-run.md)，不要按下方历史额度重置计数。

2026-09-22当前阻塞：0019具名真实评测已执行2/12次，在音频原文证明`eval_audio_not_grounded`失败后停止，视频未开始，见[执行台账](changes/0019-audio-video-provider-eval/provider-run.md)。下一步若继续真实诊断，应另行限定授权与安全诊断输出，区分ASR/摘录/证明原因；不自动消耗剩余10次，不先放宽证明或重跑本机准备。后续真实Milvus整链、前端及生产发布边界保持，下方NOT_RUN等按其历史日期理解。

当前[0019音视频模型评测工具](changes/0019-audio-video-provider-eval/verification.md)本机准备已完成：真实解码、完整预算与生产协议/证明链均通过，默认1685/native23及原门禁和独立审计通过。下一步不是继续加本机替身，而是在新授权、轮换后私有密钥及实际模型配置就绪后执行[具名真实评测](AUDIO_VIDEO_PROVIDER_EVAL.md)，当前样本预检最多12次、实际仍按F+9核算，失败停止无重试。旧文本额度不挪用，工具通过不是云质量通过。其后继续真实Milvus/完整检索与HTTP质量、前端实际操作、同镜像staging/备份迁移回滚和生产发布；完整原范围及对应授权gate保持。下方均为历史阶段。

当前[0018整套后端装配](changes/0018-multimodal-composition/verification.md)已本机验收：所有现有能力的真实Spring配置共存，四类媒体资料→任务/索引→带附件提问/库内证明→短摘要/时间来源→重启回读。下一条主线是真实provider/Milvus的合成多模态质量评测，须先获得新的调用/数据处理授权和已轮换、私有配置的密钥；不复用旧文本额度，不重复已完成的装配或协议诊断。随后按对应授权接前端实际流程、同镜像staging、备份迁移/回滚与生产发布，未验收的扫描PDF、视觉embedding等原范围仍保留。具体环境/运行边界见[MULTIMODAL_RUNTIME](MULTIMODAL_RUNTIME.md)，readiness503不自动解除。下方“当前/下一步”均为历史阶段。

当前0017[查询附件](changes/0017-query-attachments/plan.md)已接完整授权scope后图片/音频/视频编译→附件辅助召回/排序→原问题/库内原证据证明→hash-only trace→有界HTTP。真实三类媒体请求与重启来源回读见[附件答案验证](changes/0017-query-attachments/answers-verification.md)。下一主线收敛为完整生产配置组合及真实provider/Milvus质量验收，再按授权推进前端/目标主机发布；云调用不挪用旧余额。不重复已完成编译/匹配/HTTP，也不顺带扩大权限或异常工程；总体多模态生产目标保持。以下“当前/下一步”属于历史阶段。

当前[0016字幕后端主线](changes/0016-subtitle-tracks/plan.md)已接完整authority/索引→同轨全文证明→typed时间/原视频Range→完整文件摘要/重启读取；[后端验证](changes/0016-subtitle-tracks/library-verification.md)与[输入历史基线](changes/0016-subtitle-tracks/verification.md)分开记录。下一业务主线为查询附件的多模态问题与有据回答，随后真实质量、前端及生产；每次只推进一条正常闭环，不重复字幕/native/存储诊断。旧ASR/视觉/OCR/联合合同保持，模型替身不认证云质量，不挪用旧外部调用预算。下方为历史快照。

当前已完成[0015完整长文件摘要本机后端](changes/0015-file-synopsis/hierarchy-verification.md)：完整authority材料→分层候选/逐引用证明/每批原始复查→v14持久任务→原HTTP/typed来源与重启零模型读取。下一业务主线为独立字幕轨与typed时间来源，随后查询附件、真实质量、前端与生产按原gate推进；不能把本机模型替身当云质量，摘要不充当原事实证据。不重复已完成分层/协议/迁移/HTTP诊断；下方保留历史快照。

当前已本机冻结[视频选中原帧OCR](VIDEO_OCR.md)：默认关闭的v2编译→v12附表/完整索引→独立`ocr`文字证明→原帧CP/像素词框/实际显示区间来源。原三模式语义保持，1352 Java、73 Node、458格式/双80%和单列native6通过，见[OCR验证记录](changes/0014-video-library/ocr-verification.md)；以下1299结果只认证上一授权视频基线。选帧OCR不等于独立字幕轨或全视频文字覆盖，中文OCR、网页和生产仍另验。

2026-09-20：[0014授权视频主链](changes/0014-video-library/answers-verification.md)已本机通过：上传/索引→三模式问答→同组完整事实证明→v11 trace→typed时间/原帧/原视频Range。1299 Java、73 Node、5项native与原格式/双80%门禁通过；独立video+answers配置下才声明视频问答能力，readiness/网页/生产不因接口开放而通过。

下一条业务主线：视频原帧OCR文字→同版本词框/帧时间→授权问答及真实区域来源回读。它不是VLM caption，不以整帧描述伪造OCR定位；独立字幕轨、文件摘要、查询附件、真实模型质量、网页与生产仍按后续主线交付。已完成的视频输入、入库/索引、内部证明和授权HTTP不重复诊断。下方为历史快照，“下一步”仅描述当时阶段。

当前[0014视频主线](changes/0014-video-library/plan.md)已本机跑通真实解码→持久任务/独立authority/实际相交时间组→完整索引发布，证据见[步骤2验证](changes/0014-video-library/publication-verification.md)。下一步共同事实身份→原帧/转录逐事实音画联合问答→typed时间/关键帧与授权Range，不重复已完成入库/编译诊断。字幕/OCR、摘要、实际模型质量、前端和生产继续保留，输入/索引不替代完整视频闭环。下方为历史基线。

正在推进[0013音频时间证据](changes/0013-audio-library/plan.md)。本机后端上传→真实解码/按采样分段→标准ASR协议→完整索引→共用证明问答→v9 typed时间引用→同版本原文件Range已接通，当前验证见[问答与回放记录](changes/0013-audio-library/answers-verification.md)。下一业务主线为视频时间证据及音画联合；真实ASR/模型/Milvus质量、扫描PDF、摘要、前端与生产gate保持，不重复未变的音频编译诊断。

当前[0012原图知识库主链](changes/0012-visual-library/verification.md)已完成本机HTTP和最终回归验收：上传→索引→原图问答→引用回读，942 Java/73 Node及限定独立审查通过，云模型/Milvus服务端为本机协议替身。下一业务主线为音频时间证据，随后视频/音画联合；视觉真实效果、视觉embedding、扫描PDF、摘要、页面与生产门禁继续保留，不借图片子切宣布完整目标完成。

当前推进[0011视觉模型Module](changes/0011-visual-models/plan.md)：原图通信→提出全部事实→独立原图核验。随后才是同一authority下的无文字图片存储→召回→typed引用HTTP闭环；不将caption冒充OCR以省略必要证据接线。0010词框已本机验收，视觉embedding、扫描PDF、音频、视频、摘要、页面与生产仍保留。

2026-09-09当前执行[0010图片OCR区域](changes/0010-image-regions/plan.md)：为已可回读的图片文字引用增加词级位置；仅推进这条正常闭环，后续扫描PDF/视觉、音频、视频和生产范围不缩减。

2026-09-08负责人最新优先级：**多模态主线**。当前执行[0009独立图片文字证据](changes/0009-image-evidence/plan.md)，按上传→OCR→索引→问答→原图回读验收；随后图片区域/纯视觉与扫描PDF、音频时间证据、视频音画联合、摘要/跨模态问题。保持主线优先，0008 B物理清理、通用异常/容器/权限增强不抢占当前闭环。下方历史阶段不再作为本轮任务入口，完整多模态/生产范围仍须逐项交付。

当前继续[0008生命周期](changes/0008-document-lifecycle/spec.md)：先落实共用撤下安全边界，再做物理清理及重建。当前新增document_removal是独立本机开关，不改变下述文本stage；完整原目标保留，验证见[0008记录](changes/0008-document-lifecycle/verification.md)。

默认是Java management_slice；问答关闭时摄取/索引保持text_ingestion/text_indexing，0007独立本机问答开启后为text_answers。三个开关默认关闭；后端可宣告answers/sources，列表can_answer与网页仍未接线、ready503。0005/0006已通过当时本地验收，其历史指纹不认证新增0007；本地全量通过也不是完整RAG。此文记录后续gate，不承诺日期、性能或上线；当前证据见[0007验证](changes/0007-text-answers/verification.md)。

## 能力状态

| 能力 / Capability | 状态 | 当前边界与下一证据 |
| --- | --- | --- |
| 资料列表、筛选、分页、显示名、目录、手工标签 | Implemented | 合成或真实上传资料；服务端ACL、事务，整理不改源身份；见[ManagementService](../src/main/java/com/evidence/rag/service/ManagementService.java) |
| 批量移动、追加标签 | Implemented | 逐项回执/部分失败，不是整批原子提交 |
| JWT / 本机开发身份、Cookie 会话 | Implemented | 无 SSO、用户管理、签发/刷新服务；生产配置被 gate 拒绝 |
| PDF / TXT / MD文本解析与code point分块 | 0003 locally verified | TextParser通过独立ProcessTextParser子进程连接持久证据，不是OS沙箱 |
| 上传、持久任务、取消/重试与崩溃恢复 | 0003 locally verified | 显式loopback opt-in，v2备份迁移，parsed状态与实际分块；见[TEXT_INGESTION](TEXT_INGESTION.md) |
| Spring职责分层与架构约束 | 0005 locally verified | Controller / Service / Repository / Model / Security明确分离，替代旧万能实现；持续执行项目规范 |
| 摄取创建者当前授权复验 | 0006 locally verified | 领取/执行/提交及恢复撤权取消，系统审计同事务，重试权限交集；新增14项负例先红后绿 |
| active revision索引发布 | 0004 IMPLEMENTATION | 每claim独立generation、完整物理manifest、不可变attempt与source→physical→digest台账；active保留source revision；见[TEXT_INDEXING](TEXT_INDEXING.md) |
| OpenAI-compatible embedding / extraction、provider reranker Adapter | 0007 backend wired / locally tested | 索引只调用embedding；问答接embedding/rerank/extract；rerank为provider extension，摘取不等于事实证明，真实provider尚待验收 |
| Milvus写入与full revision verification | 0004 IMPLEMENTATION | 固定Java collection、N+1 metadata/精确ID/float32摘要、前后Strong/schema/index校验；真实版本一致性尚待验收 |
| Milvus dense + sparse / BM25 hybrid retrieval | 0007 backend wired / locally tested | 只读prepareSearch、完整范围前置、确定性合并及权威hydrate；不create/load/upsert，无独立候选检索端点，真实Milvus尚待验收 |
| 有据问答、选中文档范围、来源引用、拒答 | 0007 IMPLEMENTATION / local tests passed | POST answers与GET source已接线，完整scope/资格复验及v4 trace同事务；完整语义、网页和实际质量未验收 |
| 文档撤下、在途取消与历史来源失效 | 0008 IMPLEMENTATION | 默认关闭；DELETE返回deleting/pending，当前服务面过滤，审计及原文保留，不等于物理删除 |
| 物理删除、重新索引与版本追溯 | 未完成 | 清理账本与完成状态、备份恢复和重建迁移待实施；批量delete/reindex仍501，不能操作其他系统数据 |
| 独立PNG/JPEG文字OCR及原图引用 | 0009 local backend verified | 真实Tesseract固定英文图HTTP闭环；图片模型/Milvus真实质量与网页未验收 |
| 图片区域/vision、音频转写及视频音画联合 | 本机后端已接并分切验证 | 0010/0012/0013/0014；来源分别为词框/整图/真实音频分段/视频group与原帧，不等于真实模型质量或网页验收 |
| 视频选中原帧OCR与真实文字定位 | 0014 local backend verified | [独立模式与v12合同](VIDEO_OCR.md)及[本机验证](changes/0014-video-library/ocr-verification.md)；默认关闭、原像素词框、frame interval，不代表全视频字幕 |
| 独立视频字幕轨检索、问答、typed时间来源 | 0016本机后端已接 | [字幕合同](VIDEO_SUBTITLES.md)，v15完整authority与同轨全文证明；云质量/前端另验 |
| 完整短/长文件摘要与原始来源 | 0015/0016本机后端已接 | 八类原始材料，分层候选不作原证据；默认关闭 |
| 查询附件、扫描PDF与视觉/声音向量 | 后续多模态主线 | 下一条为查询附件；不用查询图片替代库内文本事实证明 |
| 自动标签与真实多模态评测 | Planned | 不能由界面占位或模型替身推定已完成 |
| 生产发布与性能结论 | Blocked by gates | readiness 为 503；没有全链路真实 provider/Milvus、运维、负载与生产验收证据 |

## 后续范围：文本入库到可核验回答

完整目标在明确blocked后重新启用，并收到继续完整目标的请求；当前已恢复0007文本问答IMPLEMENTATION。前端详情页仍不改，以下其他路线不是已完成能力；生产gate不解除。当前测试与待修问题见[0007验证](changes/0007-text-answers/verification.md)。

0003文本摄取与0004显式索引已形成后端基线；索引默认关闭，仅development/test和字面loopback，完整TextAdapterSettings必填，任务全deadline默认60000毫秒、范围10–600000，单并发、batch≤16、revision≤4096段、最多3次attempt。0007已进一步接通授权检索、逐事实校验和回答/引用API，问答独立默认关闭，不替代上述摄取/索引任务。尚须完成完整语义、实际provider/Milvus、网页与生产验收，不缩小目标。

已验收历史行为基线为[0006](changes/0006-ingestion-authorization/intent.md)，结构基线为[0005](changes/0005-spring-layering/intent.md)，索引行为按[0004](changes/0004-text-index-publication/spec.md)。当前[0007](changes/0007-text-answers/spec.md)已有本地全量结果但不等于完整验收；继续使用版本化intent/spec/plan/REVIEW冻结行为及acceptance，不能借旧审查认证新增源码。

1. **文本 ingestion authority**：受限上传、文件 hash 与不可变 revision/segment 身份；独立 Java 存储。将当前 `TextParser` 放入有资源边界的 worker，明确取消、失败、重试、崩溃恢复与 active revision 切换规则；不能把解析成功等同索引成功。
2. **真实模型 Adapter**：embedding / generation 按 OpenAI-compatible 契约，reranker 按独立 provider extension（非标准 OpenAI 端点）；固定版本与维度。先本地 HTTP stub 验证协议、总 deadline、响应限长、禁止重定向、空结果/错误/非有限数值、安全错误与密钥脱敏，再独立真实 provider 冒烟。密钥不写入文档、测试、日志或仓库。
3. **Milvus text projection（后端已接）**：隔离Java集合，固定schema/model revision；每attempt独立generation与physical ID隔离晚写。0007完整AuthorizedScope由active publication读取document→generation，在两路top-K前生效，physical候选经publication entries映射回权威source；仍须实际Milvus兼容/质量验收。
4. **回答和来源最小 API（后端已接）**：AnswerService编排重排/摘录/校验，EvidenceService锁内复验完整scope、预算与本地模型/投影资格，v4 trace记录版本及locator；source只向原回答者按当前完整ACL/active回读。完整语义及外部质量尚未认证。
5. **真实网页和冻结 acceptance**：上传 → 状态 → 列表 → 指定范围提问 → 引用定位的浏览器闭环；包括失败、越权、撤权、旧 revision、空范围、提示注入与并发变更。保留并扩展 frozen cases，不放宽旧安全预期来迁就实现。

上述链路的后端已有0007接线；[0002](changes/0002-text-adapters/intent.md)提供协议基线，[0003](changes/0003-text-ingestion/intent.md)连接持久解析证据，[0004](changes/0004-text-index-publication/intent.md)连接embedding、generation隔离与publication，0007增加v4 trace而不改旧source。父存活、lease、父崩溃及晚写仍须按当前源码验收；kill不撤回上游，非OS沙箱。查询prepareSearch也不能冻结外部generation/schema/index，多HTTP调用不是快照。独立完整语义审查、实际provider/Milvus与网页闭环仍缺，多模态和生产分别验收。

## 当前及后续必须保留的安全 invariant

- **范围先于候选截断**：组织、ACL、active revision 与选中文档范围在候选进入模型前生效，不能先全库 top-K 再事后过滤。
- **完整 selected set**：不截断、不静默丢弃所选 ID；显式空选择或任何所选资料不可用/越权不能回退全库或只用剩余部分。开始处理时验证完整集合；模型调用后、答案提交前再次校验完整选中集合及实际证据版本。被选中但最终未入候选的资料也不能省略最终范围检查。
- **权威引用**：检索投影的 ID/score 是候选，正文与结构化 locator 由权威存储重新确认。模型自由生成内容不能成为来源证据。
- **低信任文档**：正文、附件、工具返回都是数据，不执行其指令；无充分证据或远程预算中断就拒答。
- **元数据不改变证据身份**：改名/目录/手工标签不改原文件名、内容 hash、revision/segment，不触发模型。
- **多模态逐事实覆盖**：未来音画问题不得漏掉尾部事实；同一 EvidenceGroup 的 visual/transcript pair 覆盖全部事实，两个模态各自至少贡献一个过阈值事实。查询图片不能代替文本事实证明；审计只保存子问题哈希与分数，不保存原问题片段。

## Frozen fixtures 与 eval 的准确用法

[src/test/resources/corpus](../src/test/resources/corpus/)的四份合成 PDF 覆盖政策、运维、不可信指令、另一组织资料，不含真实业务文档。[TextParserTest](../src/test/java/com/evidence/rag/tool/parser/TextParserTest.java)验证抽取和定位；未修改的[golden.json](evals/golden.json)包含精确事实、政策事实、同义查询、无答案、提示注入、组织隔离六个预期，已由[AnswerGoldenTest](../src/test/java/com/evidence/rag/service/AnswerGoldenTest.java)接入Java解析/发布/问答链路并通过确定性替身验证。

六项通过只证明冻结fixture与确定性Adapter下的预期，不是实际模型召回率、完整语义覆盖或真实provider质量报告，也不是仅凭parser抽出危险文字就推定安全。后续实际质量评测仍须固定语料/配置/模型版本，分别报告召回、重排、答案/引用、拒答与权限结果。

## 生产 gate

进入生产前至少需要：

- 完成目标文本/多模态范围的实现及独立代码审查，所有 relevant acceptance 可重放。
- 完整 Java/浏览器测试、格式/静态检查、覆盖率门禁；实际结果与源码版本绑定。修改共享语义规则后重跑完整相关测试，不能仅跑新增用例。
- 真实 provider 和 Milvus staging 证据，异常/超时/限流/资源耗尽、状态恢复与一致性验证。
- 合法生产身份、TLS/代理/Origin 配置、安全密钥管理、备份恢复、数据迁移和回滚演练；不得复用或改写旧服务数据来跳过迁移设计。
- 真文件网页端验收、引用定位和 selected-set 并发撤权测试。
- 固定硬件、语料、并发与模型配置的负载基线及观测指标。没有对照数据前，不宣称 Java 比其他实现更快。
- 负责人明确生产发布授权与环境选择，随后才修改当前 production/readiness guard；不能为展示上线而将 readiness 强行改为 200。

当前 [RuntimeGuard](../src/main/java/com/evidence/rag/config/RuntimeGuard.java)与 [RuntimeController](../src/main/java/com/evidence/rag/controller/RuntimeController.java)刻意保持开发边界。路线图不构成解除 gate 的授权。
