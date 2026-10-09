# AI Knowledge · Java Edition

## 当前交付（2026-10-09）：Wiki 建设与可选 DB-GPT 问答

Java 提供原资料入库、文件级查找、知识页编译提案、审阅采纳、不可变版本和同版原文回读，配套新版前端独立模型设置。0056 已用真实 DeepSeek / 硅基流动和 Milvus 完成单份合成 TXT 的两次编译采纳及重启回读；这不代表多模态、批量增量或生产验收。见 [真实流程记录](docs/changes/0056-wiki-workspace-integration/provider-run.md)。

0057 增加默认关闭的独立 [DB-GPT 0.8.2 服务](agent-service/README.md)，Java 继续负责模型凭据、资料与引用权威。Agent 的检索、原文阅读、进度、停止和维护建议已通过本机协议替身联调，尚未验证真实模型质量。[0057 验证边界](docs/changes/0057-dbgpt-knowledge-agent/REVIEW.md)与前端全量未通过记录保留。当前同步包含知识库及问答改动，不包含部署、模型密钥、数据或运行日志。

## Wiki 后端首切（0055，历史阶段）

新增“已发布原资料 → 知识页提案 → 人工审阅 → 不可变知识页版本 → 原文来源”的后端API与持久化。明确区分零模型原文摘编与OpenAI兼容模型编译；派生知识不替代原始RAG证据。见 [Wiki API使用说明](docs/WIKI_WORKSPACE.md) 与 [0055验收边界](docs/changes/0055-wiki-workspace/REVIEW.md)。本轮仅本地实现；18090仍是前端静态预览，未部署、未调用真实模型。最新线上免登录决定及0054检索设置保持；下方日期段落为历史合同，不覆盖该决定。

## 当前合同：登录后共享全库（0053）

保留登录，同组织所有成员平等读取、维护资料和配置模型；不再设置角色、文档ACL或问答范围。普通知识问答为“全库检索原片段 → 一次模型综合 → 服务器真实来源”，允许给出有据的部分答案并说明缺失，不再用手写问句分类/字段证明/二次模型核验作统一入口硬门槛。服务器仍核实来源版本、页码/时间与删除状态，回答不宣称独立核验通过。接口见[知识问答](docs/KNOWLEDGE_ANSWERS.md)，实施与未验证项见[0053](docs/changes/0053-shared-workspace-rag/REVIEW.md)。后面的ACL/选择范围/多步核验描述为历史阶段，不覆盖0053。当前尚未发布。

面向单组织的 Java / Spring AI 知识库，使用 Milvus 和可配置模型服务，以库内原始证据回答问题并回读来源；独立前端见 [ai-knowledge-web](https://github.com/LingBengYing/ai-knowledge-web)。当前代码包含文本、图片、音频、视频、字幕、文件摘要和多模态查询入口，不是只有资料列表，也不是仅做文件摘要。

## 当前增量：知识问答统一综合回答（0049）

产品操作说明是知识问答的一种场景，不单列“产品使用帮助”功能。当前增量把0048文档/视频文字检索作为内部步骤，接原文证明→大模型综合回复→逐段支持核验→可回读的混合引用。普通问答使用同一页面，文档定位到页码，视频定位到真实ASR段/字幕cue/帧OCR时间；不把文字转录称作视觉理解。接口见[KNOWLEDGE_ANSWERS](docs/KNOWLEDGE_ANSWERS.md)，具体实现与未验证范围以[0049记录](docs/changes/0049-unified-knowledge-answers/REVIEW.md)为准，不能以设计文档推定真实模型或生产已验收。

## 最新入口：新版独立前端联调（0047）

已增加 `bash run-workspace.sh` 本机入口，使用JDK21+、独立 `.data/workspace`、后端18084，配合独立前端 `npm start` 的18085；默认本机身份与模型管理员为 `owner`。无需先配置模型即可管理资料、主动上传真实文本并查看解析任务；未应用模型/Milvus配置时不宣称索引、召回或问答可用。普通 `run-dev.sh` 与JWT默认不变，不复制独立前端资源。完整步骤见[新版前端运行说明](docs/WORKSPACE_FRONTEND.md)。

[0047](docs/changes/0047-management-workspace-integration/spec.md)已配合独立文字召回页：全库候选只含真实text/OCR材料，完整scope/ACL/配置复验保持；显式所选资料仍完整验证，不静默丢弃无文字资料。14:09:01 +08仅跳过全部测试package成功4.951秒；14:10:59真实Java在独立目录正常监听18084，经已有18085同源代理连通。实际HTTP已走通合成TXT上传202→解析parsed/1片段→列表/任务/原文件回读→显示名和两个手工标签整理200，原文件名、SHA、版本及任务不变；记录时服务保持运行，用户可打开18085、设置本机身份 `owner` 查看“青榆工作台 · 联调资料”。模型状态仍unconfigured、version0、active为空、三个has_key=false、projection未配置，索引/召回/问答未开放。

Chrome仍为 `ERR_BLOCKED_BY_CLIENT`，未认证新版DOM、布局及页面操作；全库混合文字召回只完成源码适配与构建，真实检索/生成及RAG acceptance未运行。详见[0047实际记录](docs/changes/0047-management-workspace-integration/REVIEW.md)。本切云调用0，不推送或部署；全部自动化测试、检查及审计继续停止，以下0046结果完整保留且不认证0047改动。

## 当前交付与继续开发边界（2026-10-04）

网页已实现模型草稿、逐角色服务商及连接测试、明确应用、召回预览；生成/重排切换不重建旧索引，更换嵌入模型走明确的全库重建。同资料原文件更新、媒体接当前有效文字配置、选中资料批量文本重建均已实现。入口说明见[模型配置](docs/MODEL_SETUP_AND_RETRIEVAL.md)、[媒体配置](docs/MANAGED_MEDIA_SETUP.md)、[原件更新](docs/DOCUMENT_UPDATES.md)和[批量重建](docs/BATCH_TEXT_REINDEX.md)。

仓库已有的10/04交付记录：后端 `20261004-video-answer` 于08:46:07 +08部署，包含0042主体/分行字段证明、0043视频摄取安全失败日志、0044普通启动日期证明及[0045视频问答安全失败日志](docs/changes/0045-video-answer-diagnostics/REVIEW.md)；08:44:46仅跳过全部测试的生产代码package成功。前端0032来源说明已部署，0033准确的 `model_failure` 提示已冻结。已有页面记录覆盖合成TXT三个事实、PDF预算及页引用、图片问答及原图、音频启动日期及时间来源/播放、视频画面回答及原帧/时间来源。这些是[AGENTS](AGENTS.md)记录的此前验收，不是本轮重新运行的模型质量评测。

本轮拉取到后端 `3a5f6ef` 后，root先只读页面确认模型设置已应用v2、10份资料列表、合成TXT原文件回读和批量文本重建确认/取消后选择保留，没有提交重建。随后负责人明确批准最多2次普通视频页面提问，现已完成2/2、剩余0次，无重试或其他模型操作。第1次“这个视频的背景是什么颜色？”拒答 `unsupported_question`，不是旧 `model_failure` 重现，也不能把整请求记为零模型调用。已定位单主体中文颜色问句语法缺口，并最小修改QuestionFacts；QuestionPlanning升级为 `java-question-planning-v2-single-colors`、TextGrounding升级为 `java-text-grounding-v9-single-colors`，完整范围和逐事实证明保持。见[0046续验](docs/changes/0046-video-page-followup/spec.md)及[本轮调用台账](docs/changes/0046-video-page-followup/provider-run.md)。

12:45:42 +08仅 `-Dmaven.test.skip=true package` 构建生产JAR成功，用时4.677秒，未编译或运行测试。负责人另行批准仅更新既有后端JAR并重启，保留前端、配置、数据及旧包回滚；运行包SHA-256为 `8682c2e965bac14a1df60e8ab0c20c2898ff68c66d420c097f43e60e5337829e`，12:47:43新后端正常启动。短暂502来自原入口服务的后端依赖：入口随后端停止而退出，仅重新启动原入口即恢复访问，未改前端文件或配置。

12:51 +08第2次以同一 `sample.mp4`、完整单份scope、视频画面模式和原问题提交，无语音或附件，页面成功显示“有据回答”：白色是这个视频的背景颜色。回答 `61a3dea1-2fa0-4d9b-8cb5-aaa78821707b` 的引用为 `0:00.000–0:00.040`、原版本 `90149f4f-dd40-4340-8949-2a4e22db1820`；来源只读回读重新校验通过，类型为 `machine_vlm/group_interval`，原帧截图目视确认为白色背景，原SHA为 `5a864fd106866cdfffc7c82cf944d5b78ab34aad71dfa0676b037417989d74bf`。来源回读没有新增模型调用。这一普通中文问法→有据回答→原帧/时间来源的局部闭环已通过，不等于全共享语义或生产验收。

全部自动化测试、检查及审计继续停止，共享语义旧回归未运行，不能宣称冻结或全量回归通过。历史测试结果不认证后续新增代码，旧调用余额不自动复用。

已部署不等于生产验收完成。视频初次 `parser_failed` 与两次 `model_failure` 的具体原因仍未定位；后续一次成功没有证明间歇问题已修复。ASR保存的 `budget`→`But` 错识别未改写，不能据启动日期回答通过宣称ASR整体质量通过。PDF Blob打开被页面工具策略拒绝，未绕过，也未认证完整PDF预览。完整多模态质量、同镜像staging/运维及生产gate继续保留，目标仍ACTIVE。此前 `.tools` 交接路径仅表示原工作区证据位置，不代表当前克隆包含这些未跟踪文件。

当前能力见[能力表](#当前能力)，后续主线及门禁见[ROADMAP](docs/ROADMAP.md)；版本化 `intent/spec/plan/REVIEW` 与最新[AGENTS](AGENTS.md)优先于下方历史摘要。

## 历史交付记录

下方日期、测试数、未部署及“当前/下一步”只描述各自历史版本，不作为当前任务入口或新增调用/测试授权。

2026-10-03当前0035：[保存材料重建](docs/changes/0035-saved-source-reindex/verification.md)。已发布资料可明确重建保存的完整文本索引，处理期间旧索引可用，成功才切换；失败、取消和重启中断保留旧版本。入口核对真实能力及当前资格，成功后提示重新查询，保留问题、范围与整理草稿。模型配置、逐角色测试、明确应用和召回测试继续沿既有流程。首切不支持已有独立图片/音频向量的资料及真正嵌入/投影迁移；后续receipt迁移与原文件版本替换继续保留。未部署，页面用户验收，0新增真实provider调用，完整目标ACTIVE。

2026-10-03基础修复0034：更换文字生成或重排模型后，已有图片、视频、OCR及字幕来源继续按当前权限打开；来源能力与新问答能力分开。保留已生效的媒体POST门禁，只改三处后端和一个前端回调。实际3075 Java、431前端/syntax及六Native各1通过，原格式、架构与双80%门禁保持。结果见[0034验证](docs/changes/0034-media-role-switch-sources/verification.md)，交接以工作区`.tools/media-role-switch-handoff`实际manifest为准；未部署、0新增真实provider调用、页面用户验收。完整目标active，下一基础缺口为retrieval-only范围入口，原重建及版本更新范围保留。下方是历史记录。

2026-10-03当前基础修复0033：已有文字索引后，仅更换生成或重排模型可以保存、单独测试并明确应用；嵌入配置及投影不变时不重建资料索引。实际新角色与新trace、原索引/旧来源、连续切换及重启均已本机验证；真正嵌入或投影变化仍拒绝，legacy媒体按实际完整profile判定。最终3058 Java、1013格式、原LINE/BRANCH双80与架构、六Native各1通过；1029后端输入相同、761生产class稳定。前端64及后端18 Node/static输入字节不变，430/check与73明确复用此前实跑证据。新交接.tools/model-role-switch-handoff以实际manifest/VALIDATION为准；未部署、0新增真实provider调用、页面用户验收、真实ASR未宣称修复，目标active。历史记录保留。

2026-10-03当前本机主线：[模型配置与召回测试](docs/MODEL_SETUP_AND_RETRIEVAL.md)已接通保存草稿、逐角色连接测试、明确应用、完整范围召回预览与同版本来源；无模型可启动，应用丢响应后显式读取能恢复索引/召回入口。资料清理及取消后清理恢复一并整合。实际3011 Java、1004格式、原LINE/BRANCH双80%、430前端/check、73后端Node及六Native各1通过；1020/64执行输入相同，759完整生产class与最终JAR一致，旧2640/370用例身份多重性保留。详见[0032验证](docs/changes/0032-model-setup-retrieval-test/verification.md)。交接工作区`.tools/model-setup-handoff`以实际manifest/VALIDATION为准；未部署、0新增真实provider调用、页面用户验收，原ASR质量未宣称解决，目标active、usage/计费取消。下方保留历史记录。

新增[原视频参考问答](docs/VIDEO_AV_QUERY.md)：一至三份参考视频按画面/声音/联合模式使用完整实际媒体帮助召回，库内证据继续证明完整问题并打开原来源。后端0030/前端0019实际2640 Java、894格式、原双80%门禁、370前端、73 Node及六Native通过，910/57输入和668完整生产class/JAR绑定见[验证](docs/changes/0030-video-av-query/verification.md)。交接入口工作区`.tools/video-av-query-handoff`以实际manifest/validation为准；未部署，当前已核部署仍voice-tags，页面用户验收、真实语义质量待验、目标active、usage/计费取消。下方为历史快照。

新增[原视频音画知识库](docs/VIDEO_AUDIOVISUAL.md)：已接真实连续画面和完整原音轨分别索引、画面/声音/联合完整文字问题核验及原视频来源。最终本机完整门禁、六项真实媒体Native与制品绑定通过，见[0029验证](docs/changes/0029-video-audiovisual/verification.md)。功能默认关闭，交接以工作区`.tools/video-audiovisual-handoff`实际manifest/validation为准；未部署，已核对的部署记录为20261003-voice-tags。网页用户验收、真实语义质量和原ASR问题仍开放，目标active；下方为历史快照。

新增[原声向量检索](docs/AUDIO_VECTOR_RETRIEVAL.md)：已发布音频由编辑者显式建立全部可引用语音分段的原始PCM向量；携参考音频提问时保留全部查询声段，按完整授权范围召回，再用库内保存转录证明并打开原音频时间来源。独立默认关闭，本机正常链和最终门禁见[0027验证](docs/changes/0027-audio-vector-retrieval/verification.md)。新原图/原声向量尚未部署，当前部署仍20261003-voice-tags；完整页面由用户验收，真实ASR、原声语义质量及非语音声音事实仍待完成。下方为历史记录。

新增[原图向量检索](docs/IMAGE_VECTOR_RETRIEVAL.md)：已索引图片显式建立独立原图向量，原图模式携参考图片时按完整授权范围召回，再核验库内原图事实并打开原来源。默认关闭，不自动补建旧资料；本机实现与验证见[0026](docs/changes/0026-image-vector-retrieval/verification.md)。本增量未部署。当前已部署20261003-voice-tags，含扫描PDF、查询附件、摘要建议标签和语音提问；部署结果与只读入口报告已核对，完整页面由用户验收，真实ASR仍未通过。下方日期和发布状态均为对应历史快照。

新增[语音提问](docs/VOICE_QUESTIONS.md)：上传音频→完整转录→编辑核对→确认填入问题→按原资料范围问答并打开库内来源。默认关闭，复用现有ASR，转录输入不入库、不成为引用。后端0025与前端0014配套，验证见[0025](docs/changes/0025-voice-questions/verification.md)。本机实现尚待发布；当前部署20261003-scanned-pdf已含查询附件、文件摘要和PDF来源，生成/问答全链由用户验收。下方日期和发布状态保留为对应历史快照。

新增[摘要建议标签](docs/TAG_SUGGESTIONS.md)：读取已保存摘要中的短术语/主题，勾选后追加到现有标签，再用标签筛选资料；保留手工标签且不增加模型调用。默认随既有摘要功能启用，本机验证与未验范围见[0024](docs/changes/0024-tag-suggestions/verification.md)。本轮尚未部署。

新增[扫描PDF逐页OCR](docs/PDF_OCR.md)：独立开关启用后可识别扫描件及含文字层/扫描图的混合页，索引后以文字模式问答，引用按原页码回看PDF。默认关闭，TXT/Markdown与旧资料身份保持。源码和本机合成验证见[0023](docs/changes/0023-scanned-pdf/verification.md)；新功能尚未部署，真实中文/复杂版面及用户页面验收仍开放。

2026-09-22 17:39最新诊断：累计6/20次；已通过原字节码定位到同字段指令检测`instruction_in_field`，原摘录/完整转录均被拒绝。编号规范化能找到731，找不到AU，具体错字不猜测。下一步检查ASR配置/候选，不放宽安全规则；视频尚未开始，见[完整台账](docs/changes/0019-audio-video-provider-eval/provider-run.md)。诊断完成不等于真实质量或上线通过，下方2/12为首次运行历史。

2026-09-22真实评测结果：获批最多12次后，音频ASR和文字摘录各调用1次，随后原文证据校验`eval_audio_not_grounded`失败；视频尚未开始。实际2/12，剩余10次未使用，无重试，见[执行台账](docs/changes/0019-audio-video-provider-eval/provider-run.md)。这不是质量或上线通过，后续云诊断需另行授权。以下为9/21及更早交付快照，其中NOT_RUN不是当前状态。

最新进展：[音视频模型评测入口](docs/AUDIO_VIDEO_PROVIDER_EVAL.md)已完成本机验证：固定合成语音/视频完整解码，实际PCM和原帧经生产模型协议，验证音频原文和同组音画双事实；请求前核算完整额度，失败停止、不自动重试。1685 Java、23项原生媒体测试、73 Node与格式/覆盖率及独立制品审计通过，见[0019验证](docs/changes/0019-audio-video-provider-eval/verification.md)。这次仅新增评测代码和资源，**真实云模型尚未运行，前端和生产未发布**。下一步需要新调用授权与轮换后私有密钥/模型配置。以下为历史交付快照，不重复已验收的后端装配。

最新状态：[完整多模态后端配置](docs/MULTIMODAL_RUNTIME.md)已在同一真实Spring装配下通过本机验收：文本/图片/音频/视频上传与索引、图片/音频/视频附件辅助问答、音频和视频OCR/字幕来源、短文件摘要，以及重启后原媒体与时间引用回读。无测试Bean替换；真实SQLite/FFmpeg/Tesseract，模型和Milvus服务端使用本机协议替身。1680 Java、22项原生媒体测试、73 Node、格式与覆盖率门禁及独立审计通过，见[0018验证](docs/changes/0018-multimodal-composition/verification.md)。配置示例已补齐字幕和摘要，运行说明区分了配置共存与模型效果；**真实多模态模型/Milvus质量、前端和生产发布仍未完成**。下一步在新授权和轮换后私有密钥就绪时验证真实质量，不自动调用、推送或部署。下方“当前/下一步”均为历史交付快照。

当前实施主线是[多模态查询附件](docs/QUERY_ATTACHMENTS.md)：已接完整scope后图片/音频/视频编译、辅助召回/原图匹配、原问题与库内证据证明、v16 hash-only trace和独立有界HTTP。真实合成PNG/WAV/MP4的入口与重启来源回读已通过，最终冻结见[附件答案验证](docs/changes/0017-query-attachments/answers-verification.md)。默认关闭，附件不入库、不作引用；**前端、真实模型/Milvus质量与生产发布仍未完成**。以下为历史阶段，不触发重复开发或诊断。

当前多模态主线：[视频内嵌字幕](docs/VIDEO_SUBTITLES.md)。本机后端已接真实全轨字幕→v15完整持久化/索引→同轨文字证明→typed时间/原视频Range，以及包含尾部字幕的完整文件摘要和重启来源回读；验收与源码绑定见[后端验证](docs/changes/0016-subtitle-tracks/library-verification.md)。默认关闭，不把字幕当ASR，不以caption/摘要代替证据。模型/Milvus服务端为本机协议替身，**真实模型质量、查询附件、前端与生产仍未完成**。下方为历史交付快照。

最新增量：[文件摘要后端](docs/FILE_SYNOPSIS.md)支持完整长文件分批/分层生成、逐条原始引用证明、全部原批次复查，再通过原持久任务/来源HTTP回读；长文字、长音频、9帧视频与重启零模型读取已本机通过。1485 Java、73 Node、512格式及双80%门禁，详见[本机验证](docs/changes/0015-file-synopsis/hierarchy-verification.md)。保留文档/图片/音频/视频七种原始来源与旧短文件结果，默认关闭，摘要失败不影响检索。模型为本机协议替身；**前端入口、真实模型质量及生产仍未完成**。以下为历史基线。

当前增量：[视频选中原帧OCR](docs/VIDEO_OCR.md)已本机冻结。新增独立`mode=ocr`、原帧文字/词框/时间来源及v12附表，原`visual/transcript/joint`语义保持；1352 Java、73 Node、458文件格式/双80%及单列6项真实音视频流程通过，见[OCR验证记录](docs/changes/0014-video-library/ocr-verification.md)。视频OCR默认关闭，仅本机development/test；真实Tesseract合成英文链与模型/Milvus协议替身不等于全视频字幕、中文质量、网页或生产验收。以下1299是上一冻结基线，不单独认证新增源码。

2026-09-20进展：[视频问答与来源API](docs/VIDEO_ANSWERS.md)已本机跑通。视频上传/索引后，可分别询问画面、音轨或联合事实；引用能回读真实时间区间、封存原帧与原视频Range。完整范围和同组逐事实证明保留，caption不作证明。1299 Java、73 Node、5项真实音视频流程及格式/覆盖率门禁通过，详见[验证记录](docs/changes/0014-video-library/answers-verification.md)。模型/Milvus为本机协议替身，不代表真实模型质量；网页和生产未改。下一步推进视频原帧OCR文字证据，字幕、摘要及其余质量/发布门禁仍未完成。以下为历史阶段记录，其中“当前/下一步”只描述当时版本。

当前开发主线为[0014视频时间证据与音画联合](docs/changes/0014-video-library/intent.md)：已接通[显式视频上传→持久任务→完整原帧/转录/时间组→索引发布](docs/VIDEO_PUBLICATION.md)，2026-09-12本机后端[验收通过](docs/changes/0014-video-library/publication-verification.md)。下一步接原帧与转录的同组逐事实证明、typed时间/关键帧引用；当前没有视频问答API，caption仅用于召回。前端与生产未改，模型/Milvus服务端验收使用本机协议替身，不代表云模型质量。以下是保留的历史后端基线。

当前开发主线为[0013音频时间证据](docs/changes/0013-audio-library/intent.md)。本机后端已接通上传→实际解码/标准ASR协议→完整索引→逐事实问答→typed时间引用→同版本原音频回读及单byte Range，配置和接口见[音频知识库](docs/AUDIO_COMPILATION.md)。音频时间为服务器真实分段，不是词级对齐；前端播放器、真实模型效果和生产仍待验收。以下0012是保留的图片基线。

音频完整后端链已通过真实FFmpeg/Spring HTTP验收，ASR/问答模型/Milvus为本机协议替身，详见[0013问答与回放验证](docs/changes/0013-audio-library/answers-verification.md)。[此前上传/索引基线](docs/changes/0013-audio-library/publication-verification.md)保留不覆盖；未调用云模型或部署。

最新实现入口为[0012原图知识库](docs/VISUAL_LIBRARY.md)：无文字图片上传→独立描述召回→Milvus→原图逐事实问答→typed整图引用。2026-09-10本地后端HTTP与942 Java/73 Node、格式/覆盖率和限定独立审查通过，见[验收记录](docs/changes/0012-visual-library/verification.md)。模型/Milvus服务端仍是本机协议替身；旧文字/OCR API保留，新增图片接口默认关闭，前端未改。以下0011及更早段落是历史基线，不认证0012，也不代表云视觉质量或生产完成。

当前增量[0011图片视觉模型](docs/changes/0011-visual-models/intent.md)已通过本地Module验收：原图生成与逐事实评估，机器描述只供召回；923 Java/73 Node及限定独立审查通过，云请求0。尚未接纯视觉入库/检索/公开问答，接口边界见[VISION_MODELS](docs/VISION_MODELS.md)与[验收记录](docs/changes/0011-visual-models/verification.md)；下方0010为已验收OCR基线，不能代替真实视觉质量验收。

2026-09-09当前增量：[图片OCR词级区域](docs/changes/0010-image-regions/intent.md)。图片文字问答的引用已能返回同版本词框；真实Tesseract合成英文PNG闭环与900 Java/73 Node通过，模型/Milvus仍为协议替身。见[验收记录](docs/changes/0010-image-regions/verification.md)，不是网页高亮、纯视觉、音视频或生产完成。

最新开发主线已按负责人要求切换为[0009图片证据](docs/changes/0009-image-evidence/intent.md)：先PNG/JPEG文字识别、既有索引问答与同版本原图回读，再音频/视频。入口与明确边界见[IMAGE_EVIDENCE](docs/IMAGE_EVIDENCE.md)。0007固定PDF真实后端流程已通过，见[文本主线记录](docs/changes/0007-text-answers/mainline-live.md)；下方“当前0008”等为历史快照，不能作为新图片源码的验收。当前不推送或部署。

当前增量：[0008文档生命周期](docs/changes/0008-document-lifecycle/intent.md)，实现独立开关控制的撤下请求、在途任务取消及旧引用失效。2026-09-08 13:09:46实际JDK21完整857项Java、73项Node、240文件格式及行/分支双80%门禁通过。`DELETE /v1/documents/{id}`返回`deleting/pending`，不表示物理清理完成；没有恢复接口，文件与历史证据仍保留并占配额。当前验证与剩余门禁见[0008验证](docs/changes/0008-document-lifecycle/verification.md)。本次仅同步源码与说明，不部署；下方0007及更早日期均为历史基线，不认证新增源码。

2026-09-08 12:08:55 本次代码同步快照：修复明确示例语境、多句操作步骤/必要前提遗漏及程序组重复全页扫描，policy 为 `java-text-grounding-v4-procedure-context`。最后修改后773项Java、73项Node、227文件格式与双80%覆盖率门禁通过，原675项测试及91个测试/语料文件完整保留；本批限定Standards/Spec审查均无未关闭问题。完整真实生成链路、网页接线、多模态和生产仍未验收。本次只同步Java代码与说明，不部署或改前端；当前范围与源码绑定见[0007验证](docs/changes/0007-text-answers/verification.md)和[REVIEW](docs/changes/0007-text-answers/REVIEW.md)，下方日期及“未推送”均为历史状态。

当前开发：[0007授权文本问答](docs/changes/0007-text-answers/intent.md)，仍为IMPLEMENTATION。2026-09-07 15:59:54 +08:00实际Temurin21.0.12.1+1干净构建通过635项Java、73项Node、212个Java文件格式检查和双80%覆盖率门禁。本批修复具名条件跨分块漏判及Model输出不变量，限定两轴审查通过。默认关闭的问答HTTP已接通，但不是完整语义、网页、真实provider/Milvus、多模态或生产验收。当前证据见[0007验证](docs/changes/0007-text-answers/verification.md)。

[真实Milvus集成](docs/changes/0007-text-answers/milvus-integration.md)于2026-09-08补测通过：固定2.6.22/ARM64、float32精确摘要、dense/BM25授权范围、4096+1完整性边界，以及卸载后只读不加载/显式重载恢复。[SiliconFlow联调](docs/changes/0007-text-answers/provider-integration.md)中真实嵌入与重排通过，证据摘录60秒超时，整体未通过；无自动重试。这不是完整容量、总体模型质量、多模态或生产验收。

[0005分层重构](docs/changes/0005-spring-layering/closure.md)和[0006摄取授权补强](docs/changes/0006-ingestion-authorization/verification.md)是已完成的历史基线，其297项结果不认证新增源码。前端详情页未改，未推送或部署。

一个面向单组织的 **Java AI 知识库 / RAG（Retrieval-Augmented Generation）** 项目。

当前可运行的是 **资料管理工作台与本机文本/图片/音频/视频后端链路**：列表、授权分页、目录、标签、改名、批量整理和审计；独立开关控制上传/解析、索引发布、问答及摘要HTTP。0010提供PNG/JPEG文字与词框，0012提供无文字原图问答及整图引用，0013提供音频转录/索引/问答及时间来源，0014提供视频原帧/转录及音画联合问答、选中原帧OCR与时间来源；0015提供短/长文件完整摘要与持久来源，0016提供独立字幕轨检索/问答及摘要来源。独立前端已接问答、来源预览/播放器和摘要入口，实际开放能力由当前运行配置决定。后端接口通过不等于网页或真实模型质量通过。

> **非生产版。** `parsed`与`indexed`不自动开放问答；需显式启用0007并满足当前授权与publication约束。资料问答资格由当前角色、已发布材料及实际运行能力决定，`/health/ready`仍为503。Java21历史CI、本机替身和已有浏览器截图均不认证当前真实provider/Milvus或生产可用性。

**For AI agents:** A standalone Java knowledge-management application being extended into an evidence-grounded RAG system. Read [AI_CONTEXT](docs/AI_CONTEXT.md), [AGENTS.md](AGENTS.md), and the capability table before making claims or changes. Planned capabilities are not implemented APIs.

## 当前能力

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 传统列表、分页、搜索和类型筛选 | 可运行 | 授权过滤先于统计和分页 |
| 目录、改名、手工标签、批量移动/加标签 | 可运行 | 整理不会修改原文件身份或触发模型 |
| JWT / HttpOnly 会话 / 文档角色 | 可运行 | owner、editor、reader；开发身份仅限显式 loopback |
| SQLite 持久化与哈希审计 | 可运行 | 单写入者；重启保留；独立数据目录 |
| PDF / TXT / Markdown 文本解析与扫描PDF OCR | 已实现；已有局部页面记录 | 独立Java进程、原页码/code point定位；扫描PDF开关独立，完整PDF Blob预览未认证；不是OS沙箱 |
| Milvus写入与完整revision验证 | 隔离真实集成通过 | Java专用collection、完整ID与正文/float32摘要回读；4096短正文/4维边界通过，非一般容量验收 |
| Milvus dense + BM25查询 | 已接0007后端，本机替身已测 | 范围前置、RRF、权威正文回读；查询只验证现有集合，不创建/加载/写入 |
| 嵌入、重排、原文摘取 | 已实现；实际质量按场景记录 | 网页配置与逐角色provider接线；rerank为provider扩展，最终答案仍须服务端验证；不把早期smoke或后续局部页面回答扩大为总体质量 |
| 模型配置、手动连接测试、应用与召回预览 | 已实现；本轮只读确认应用v2 | 逐角色服务商；生成/重排切换保留索引，嵌入变化走明确全库重建；手动连接测试会调用外部服务，不自动执行 |
| 上传、持久任务、取消/重试、版本化解析证据 | 本地验收通过 | 默认关闭；显式启用且loopback；解析完成标为parsed，保留原文件 |
| 摄取后台当前授权与撤权取消 | 0006 本地验收通过 | 领取/执行前/提交复验原创建者当前写权限；取消审计原子提交；重试需恢复创建者权限 |
| 显式索引任务与active发布 | 已实现 | 每attempt独立generation、完整物理manifest与映射台账；实际能力取决于运行配置，不将parsed当indexed |
| 有证问答与来源 | 已实现；10/04已有局部页面记录 | POST /v1/answers、GET /v1/sources/{answerId}/{ordinal}；完整范围/配置复验和trace同事务；0042/0044支持正常字段/启动日期表达，不改原文 |
| 文档撤下、取消在途任务与旧引用失效 | 0008开发实现，默认关闭 | DELETE /v1/documents/{id}；当前权限与事务审计，v5墓碑；返回deleting/pending；当前受控清理和逐项状态见0031，不代表同步清理完成 |
| PNG/JPEG文字识别、索引问答、词框与原图引用回读 | 0010本机后端验收通过 | 实际Tesseract合成英文PNG；模型/Milvus为协议替身，非真实图片检索质量；见[图片入口](docs/IMAGE_EVIDENCE.md) |
| 原图视觉模型通信、库内索引、逐事实问答与整图引用 | 已实现；10/04已有图片页面记录 | 独立image证据，caption仅用于召回；局部实际回答/原图回读不认证所有图片质量 |
| 音频解码、ASR协议、索引、问答与原文件Range | 已实现；10/04已有日期/播放记录 | [接口及验证边界](docs/AUDIO_COMPILATION.md)；时间为真实分段，保存转录错字保留，不假报词级对齐或ASR质量已解决 |
| 视频上传、原帧/完整音轨/时间组与完整索引 | 0014本机后端验收通过 | [显式视频MIME合同](docs/VIDEO_PUBLICATION.md)，真实FFmpeg/HTTP/SQLite/索引进程；描述仅供召回，模型/Milvus为协议替身 |
| 视频三模式问答、typed时间/原帧/原视频Range | 已实现；10/04已有画面页面记录 | [接口合同](docs/VIDEO_ANSWERS.md)；同组逐事实证明及完整scope；原帧时间不等于逐词对齐，间歇model_failure原因仍开放 |
| 视频选中原帧OCR、文字问答与像素词框 | 0014本机后端验收通过 | [独立OCR合同](docs/VIDEO_OCR.md)及[验证记录](docs/changes/0014-video-library/ocr-verification.md)，默认关闭；真实帧显示时间，非全视频字幕或中文识别质量结论 |
| 独立视频字幕轨、完整索引、文字问答与时间来源 | 0016本机后端已接 | [配置与接口](docs/VIDEO_SUBTITLES.md)，默认关闭；完整同轨上下文，字幕不是ASR或画面OCR |
| 短/长文件摘要、完整材料与持久原始来源 | 0015/0016本机后端已接 | [文件摘要](docs/FILE_SYNOPSIS.md)，包含字幕第八类原始材料；摘要不替代事实证据 |
| 多模态查询附件与原视频参考提问 | 已实现；整体实际质量待验 | PNG/WAV/MP4及一至三参考视频辅助召回，最终只用库内证据；附件不入库、不作事实来源 |
| 原图/原声向量、独立声音与原视频音画 | 已实现；真实语义质量待验 | 独立可选能力、显式构建及原始材料召回；向量不能替代逐事实证明 |
| 语音输入与摘要建议标签 | 已实现 | 音频完整转录须用户确认；摘要建议标签由保存材料生成且用户勾选，不额外调用模型 |
| 受控清理、保存材料重建、同资料原件更新与模型重建 | 已实现；本轮只读确认批量入口 | 保留ID与整理信息，旧发布成功后才切换；未在本轮提交重建或更新 |
| 部署、生产验收与真实性能对比 | 有开发部署记录；生产未验收 | 当前readiness gate不解除，完整质量/备份迁移回滚/负载仍待验，不声称 Java 已比 Python 更快 |

## 技术栈

Java 21 编译目标、Spring Boot 4.1.1、Maven、SQLite JDBC、PDFBox 3.0.8；前端为原生 HTML/CSS/JavaScript。JUnit、真实 SQLite/HTTP 测试、Node 原生测试、JaCoCo 行与分支双 80% 门禁。

不依赖 Python，不通过 Python 代理业务。无模型 API key 也能运行当前管理工作台。

前端已独立发布至 [ai-knowledge-web](https://github.com/LingBengYing/ai-knowledge-web)，提供原生界面、本机同源开发代理及独立运行说明。本仓库仍保留同源内置页面；分仓不代表自动同步或跨域认证已启用。

## 快速开始

需要 **JDK 21+、Maven 3.6.3+**；Node 22+ 用于前端测试与敏感文件检查。命令在本仓库根目录执行。

当前10/03停测指令仍生效：下方通用 `verify` 及测试/检查命令不是本轮执行授权；继续开发只按最新[AGENTS](AGENTS.md)构建跳过全部测试的运行包或进行获准的实际页面操作，不据未运行命令写成通过。

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml verify
RAG_AUTH_MODE=development_headers bash run-dev.sh
```

打开 [本地工作台](http://127.0.0.1:18084/)。空库没有资料；开发模式可以输入 `owner` 作为本地演示身份。不要把开发 header 模式接到公网或反向代理。

要演示四类资料的整理界面，可显式创建一份 **合成元数据**，不是上传/解析/检索结果：

```bash
java -jar target/rag-java-0.1.0-SNAPSHOT.jar --seed-demo ./demo-data
RAG_AUTH_MODE=development_headers RAG_DATA_DIRECTORY=./demo-data bash run-dev.sh
```

`owner` 可整理四条合成资料，`reader` 只能读取两条被授权资料，`editor` 可编辑其被授权资料。重复 seed 同一目录会被拒绝，避免覆盖。

`run-dev.sh` 会复制一个不变 JAR 后启动，避免后续 Maven 打包覆盖运行中的程序。默认绑定 `127.0.0.1:18084`。

### 文本摄取开发入口（0003）

新建专用目录和端口，不重启或替换其他演示进程：

```bash
RAG_AUTH_MODE=development_headers RAG_INGESTION_ENABLED=true \
RAG_DATA_DIRECTORY=./text-demo-data RAG_PORT=18086 bash run-dev.sh
```

页面接受PDF/TXT/MD，最大20MiB。任务持久化为queued/processing/parsed/failed/cancelled；解析成功后仍未索引，问答继续禁用。默认30秒解析/上传接收时限、最多2个在途上传、单解析并发。上传仅授予创建者owner，其他身份不自动获得权限。详见 [TEXT_INGESTION](docs/TEXT_INGESTION.md)。仅允许字面loopback绑定，不得放到公网或代理后当生产服务。

### 文本索引开发入口（0004）

先通过进程环境配置[TEXT_ADAPTERS](docs/TEXT_ADAPTERS.md)中的全部三种模型Endpoint与独立Java Milvus集合，再显式设置`RAG_INDEXING_ENABLED=true`。这会启用`POST /v1/documents/{documentId}/index`与持久任务状态/取消/重试；只处理当前有写权限且已解析的真实资料，上传不会自动索引。摄取开关独立，需要新上传时再开启`RAG_INGESTION_ENABLED=true`。

索引仅允许development/test和字面`127.0.0.1`或`::1`绑定；全任务默认60秒，`RAG_INDEXING_TIMEOUT_MS`范围10–600000。完整模型配置是启动要求，当前worker仅调用embedding与Milvus，缺配置不退回假模型或内存索引。使用新专用数据目录/端口与合成资料；不要更改现有服务、数据或collection。v3迁移备份、计费重试、完整验证与运行限制见[TEXT_INDEXING](docs/TEXT_INDEXING.md)。

每次索引claim使用新的物理generation，重试保留source revision；即使旧上游HTTP迟到完成，也写旧namespace。protocol v2检测父PID/startInstant，worker使用同OS用户的跨JVM collection lease；不把kill当上游撤回或OS沙箱。lease小文件保留，父崩溃的私有job临时目录还需后续回收。早期缺generation/台账的未发布WIP v3拒绝复用；这些机制仍需当前源码的真实进程/远程验收，未解除生产gate。

## 配置与密钥

文档撤下须显式设`RAG_DOCUMENT_REMOVAL_ENABLED=true`，只允许development/test及字面loopback，不依赖模型配置，也不会开启其他任务。能力名为`document_removal`；物理删除`document_delete`仍不可用，前端未加删除按钮。关闭开关只关闭新请求，不会使已撤下资料重新可见。已有Java库会一致性备份后迁移v5；旧备份不包含后续删除请求，切勿当作保留删除状态的生产恢复方案。

文本问答需另设`RAG_ANSWERS_ENABLED=true`，并提供[模型与Milvus配置](docs/TEXT_ADAPTERS.md)。仅development/test及字面loopback；默认总处理预算60000毫秒、并发2，分别通过`RAG_ANSWERS_TIMEOUT_MS`（10–600000）和`RAG_ANSWERS_MAX_CONCURRENT`（1–8）调整。接口、显式空选择、错误和来源语义见[API](docs/API.md)。索引/摄取/问答三个开关独立，不自动创建索引或调用真实模型重试。

真实配置只放环境变量或部署平台的 secret 中。[.env.example](.env.example) 仅列出空值/非敏感默认值；**程序不自动读取 `.env`**，不要仅复制文件就以为配置生效。

| 变量 | 默认值 / 用途 |
| --- | --- |
| `RAG_AUTH_MODE` | `jwt`；本地演示需显式改为 `development_headers` |
| `RAG_JWT_SECRET` | 无默认值，JWT 模式至少 32 字符；不要使用文档或测试中的值 |
| `RAG_JWT_ISSUER` / `RAG_JWT_AUDIENCE` | `evidence-rag` / `evidence-rag-web` |
| `RAG_WORKSPACE_ID` | `org-main` |
| `RAG_BIND_ADDRESS` / `RAG_PORT` | `127.0.0.1` / `18084` |
| `RAG_DATA_DIRECTORY` | 独立的 `.data` 目录，不能指向旧数据库 |
| `RAG_ENVIRONMENT` | `development`；当前拒绝 `production` |

JWT 模式缺少 secret 会拒绝启动。浏览器通过 `POST /v1/session` 换取 HttpOnly、SameSite=Strict 会话，不把 token 放入 localStorage。索引和问答均关闭时不加载模型配置；任一开启时共用一份`TextAdapterSettings`，启动校验三种模型及Milvus配置但不发网络请求。远程写入仅由已授权索引任务触发，问答采用只读查询。完整变量见[TEXT_ADAPTERS](docs/TEXT_ADAPTERS.md)，0007装配与接口以[API](docs/API.md)为准。

**不要提交 API key、JWT secret、SSH 私钥、`.env`、数据库或运行日志。** [.gitignore](.gitignore) 与 [敏感信息检查](scripts/check-secrets.mjs) 是双层防护；完整处理流程见 [SECURITY.md](SECURITY.md)。若密钥曾被贴入聊天或日志，应在对应平台轮换，而不是只删除代码里的字符串。

## 测试与工程方式

以下为恢复自动化验证后的通用工程门禁说明；当前暂停执行，不以历史结果认证0042以后源码。

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml spotless:apply
mvn -s .mvn/settings.xml -gs .mvn/settings.xml clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
node scripts/check-secrets.mjs --history
```

按 AI-Native 的版本化意图、规格、计划和验证证据推进小的完整功能链路，采用先失败后通过的测试；不以生成了代码或界面有按钮作为完成标准。见 [CONTRIBUTING](CONTRIBUTING.md)。

0006 最终 `clean verify` 于 **2026-09-07 11:33:24 +08:00** 通过：297项Java测试，失败/错误/跳过均为0，其中包含11项架构测试；Spotless检查150个Java文件。原283项测试逐项保留，新增14项撤权回归先红后绿，Node回归73项通过。源码指纹、命令与未验证项见[0006验证记录](docs/changes/0006-ingestion-authorization/verification.md)。本地门禁不是完整阿里规范合规认证、实际JDK21运行或生产发布证明。

固定合成PDF位于`src/test/resources/corpus/`，`docs/evals/golden.json`保存未修改的六个问答预期。0007的`AnswerGoldenTest`已使用真实Java解析/publication和明确的确定性模型/投影替身全部通过；这是固定样例回归，不是实际模型召回率或完整语义验收。最新全量已在实际JDK21运行，仍不能替代同生产镜像、真实provider/Milvus或生产验收。详见[0007验证](docs/changes/0007-text-answers/verification.md)。

## 文档导航

- [Java / Spring 人机协同规范](docs/JAVA_DEVELOPMENT_STANDARDS.md)：阿里规范裁剪结合 deep Module / 奥卡姆剃刀；0005 本地重构验收通过，后续执行入口在 AGENTS
- [AI_CONTEXT](docs/AI_CONTEXT.md)：项目是什么、代码在哪里、哪些不能假设
- [ARCHITECTURE](docs/ARCHITECTURE.md)：Module、数据与安全约束
- [API](docs/API.md)：当前真实 HTTP 契约
- [TEXT_ADAPTERS](docs/TEXT_ADAPTERS.md)：模型与Milvus Interface、环境配置及实际验收边界
- [TEXT_INGESTION](docs/TEXT_INGESTION.md) / [TEXT_INDEXING](docs/TEXT_INDEXING.md)：解析与索引任务、进程、迁移和发布契约
- [ROADMAP](docs/ROADMAP.md)：文本、多模态、生产迁移路线
- [AGENTS](AGENTS.md)：AI 开发约定
- [SECURITY](SECURITY.md)：密钥与安全报告
- [llms.txt](llms.txt)：机器可读文档导航，不保证被任何搜索引擎或模型收录

本仓库尚未指定开源许可证；公开可读不等于已授予 MIT/Apache 等许可。
