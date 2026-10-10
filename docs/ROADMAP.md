# Roadmap：按可验收纵切推进

## 当前增量：产品使用帮助（0048）

负责人批准“产品使用问题 → 说明文档与教程视频 → 原文位置/播放位置”。先完成[0048](changes/0048-product-help/spec.md)的原文检索入口与独立前端0036，文档和视频分别召回、类型化定位、读取原件。首版不新增产品中心，不混装旧答案trace，不将字幕检索称为原生视觉/音频嵌入。后续再推进产品metadata、跨类型有据生成和Qwen视觉补充；[使用说明](PRODUCT_HELP.md)。新授权只覆盖开发，不自动扩大为云调用、Git发布或部署；自动化停测要求保持。

## 最新主线：新版管理工作台联调（0047）

负责人要求新版独立前端配合真实Java后端，当前只推进导入→解析任务→资料详情/原件→整理的正常闭环，并配合既有独立文字召回页。[0047合同](changes/0047-management-workspace-integration/spec.md)保持原HTTP字段：全库只取text/OCR候选但完整scope/ACL/配置复验不变，显式所选资料不能被静默过滤；全库无文字材料返回原empty/no_matches，不伪造结果或调用模型。新本机 `run-workspace.sh` 配独立18085前端，使用独立数据、owner身份及managed模型设置；[运行说明](WORKSPACE_FRONTEND.md)。不新增聊天历史、全库任务API或分段编辑。

14:09:01 +08仅跳过全部测试package成功4.951秒，14:10:59真实Java在新隔离目录监听18084并通过已有18085同源代理连通。实际HTTP正常链已通过：14:12:00合成TXT上传202，14:12:01 parsed/1片段；列表、解析任务、原件metadata及原字节回读成功，显示名和两个手工标签PATCH200，源文件名/SHA/版本/解析任务保持。模型设置实际为unconfigured、version0、active为空、三个has_key=false、projection未配置，因此管理/解析通过不等于索引、召回或问答可用。服务在记录时保持运行，用户可在18085以本机 `owner` 查看“青榆工作台 · 联调资料”；[实际记录](changes/0047-management-workspace-integration/REVIEW.md)。

浏览器仍受 `ERR_BLOCKED_BY_CLIENT` 限制，未绕过、未认证新版DOM/布局交互。全库混合文字召回已源码适配和构建，但未运行真实检索、混合媒体运行态回归或RAG acceptance；后续页面证据与获准的模型质量分别验收。本切云0、不推送部署，自动化测试/检查/审计仍停止。下方0046页面成功、调用额度及部署结论保留为此前记录，不认证0047或授权新的模型调用。

## 当前交付与下一主线（2026-10-04）

常用正常链已实现：模型配置→逐角色手动连接测试→明确应用→导入/整理→索引/召回→有证问答→同版本来源；已有文本、扫描PDF、原图、音频、视频/字幕、摘要、参考媒体、独立图片/声音向量、原件更新及显式模型重建入口。不要重新开发下方旧阶段标为“下一步”的已实现能力，也不重新开启已取消的usage/计费工作。

按最新[AGENTS](../AGENTS.md)中的已有10/04记录，后端 `20261004-video-answer` 于08:46:07 +08部署，包含0042主体/分行字段、0043摄取诊断、0044启动日期和[0045问答诊断](changes/0045-video-answer-diagnostics/REVIEW.md)。已有页面记录通过合成TXT/PDF正常事实及来源、图片回答/原图、音频日期回答/时间播放、视频画面回答/原帧时间来源；前端0032来源说明已部署，0033准确model_failure提示已冻结。08:44:46仅跳过全部测试的package成功，不是测试通过。此前未跟踪的 `.tools` 证据和交接包由原工作区维护，本轮不把其路径当成本机已具备制品。

本轮同步至后端 `3a5f6ef` 后，root先只读确认模型应用v2、10份资料列表、合成TXT原文件回读和批量重建确认/取消后选择保留，没有提交实际重建。随后负责人批准最多2次普通视频页面提问，现已完成2/2、剩余0次，无重试或其他模型操作。第1次普通中文背景颜色问题拒答 `unsupported_question`，不是旧model_failure重现，也不是整请求零模型调用。[0046](changes/0046-video-page-followup/spec.md)已定位并最小修复单主体颜色语法，QuestionPlanning/TextGrounding明确升版，完整范围、逐事实证明和原问题保持。

12:45:42 +08仅 `-Dmaven.test.skip=true package` 构建生产JAR成功，用时4.677秒。获负责人另行明确批准后，root仅更新既有后端JAR并重启，前端、配置、数据与旧包回滚保留；运行包SHA-256为 `8682c2e965bac14a1df60e8ab0c20c2898ff68c66d420c097f43e60e5337829e`，12:47:43新后端正常启动。短暂502来自原入口的后端依赖停止，仅启动原入口服务即恢复访问，未改前端文件或配置。

12:51 +08第2次普通页面提交保持 `sample.mp4` 完整单份scope、视频画面模式和相同问题，无语音或附件，成功得到“白色是这个视频的背景颜色”。回答 `61a3dea1-2fa0-4d9b-8cb5-aaa78821707b` 引用原版本 `90149f4f-dd40-4340-8949-2a4e22db1820` 的 `0:00.000–0:00.040`；只读原帧来源重新校验通过，`machine_vlm/group_interval` 与SHA `5a864fd106866cdfffc7c82cf944d5b78ab34aad71dfa0676b037417989d74bf` 保持，截图目视为白色背景。来源回读不产生新模型调用。本切普通中文问法→有据回答→原帧/时间来源已通过，授权已用完，不继续提交、不自动重试或重新索引/ASR。

全部自动化测试、检查、审计继续停止，共享语义旧回归未运行，不宣称冻结或全量回归通过；历史门禁不得据此重新启动或认证新增源码。调用单位是页面提交，不冒充底层模型HTTP请求数，实际进度见[本轮台账](changes/0046-video-page-followup/provider-run.md)。

下一主线是完成当前实际产品链的剩余质量与交付证据，而非扩展异常/权限架构：视频初次parser_failed及两次model_failure的具体原因仍未定位；0043/0045只增加安全失败阶段日志，最后一次画面回答成功不等于修复间歇问题。ASR错识别保留，PDF Blob打开受页面工具策略限制，不绕过、不报告完整预览通过。新的真实模型复验应明确授权范围、样本和调用预算，不能自动花费旧额度。完整多模态质量、同镜像staging、备份迁移/回滚、负载与生产验收继续保留，目标ACTIVE、readiness gate不解除。

## 历史进度记录

下方日期、测试数、未部署及“当前/下一步”只说明对应历史阶段；当前能力以本节、能力表及最新版本化工件为准，不构成测试、云调用、Git写入或部署授权。

2026-10-04当前0041/前端0030完成[选中资料批量文本重建](BATCH_TEXT_REINDEX.md)：原document-actions reindex已接当前实际processor，逐项排队/失败和原任务进度，旧发布在处理中保持，成功才单项切换。01:32:29 +08仅skiptests package成功，未测试、调用真实模型或部署。交付 `.tools/batch-text-reindex-mainline` 已包含此前模型配置/provider、原文件更新、嵌入模型全库重建及媒体装配。常用主线已实现，当前需要实际部署版本及用户页面验收；不再为主线增加测试支线或扩展功能。下方保留历史状态。

2026-10-04当前0040/前端0029完成[网页文字配置接媒体模块](MANAGED_MEDIA_SETUP.md)：已配置媒体资源不再要求重复旧文字密钥，视觉/视频/附件文字图及独立向量操作读取实际网页有效配置。01:21:20 +08仅跳过全部测试package成功，未测试或部署，页面用户验收。交付 `.tools/managed-media-mainline`；下一开发为已选资料批量重新索引，真实模型效果及部署仍由对应验收处理。

2026-10-04当前0039/前端0028已实现[更换嵌入模型后的专门重建](MODEL_INDEX_REBUILD.md)：保存新草稿后明确全库重建独立集合，旧配置继续使用，完整候选索引及图片/音频向量收据全部合格后整体切换配置与发布；原件更新后的资料沿当前保存材料，媒体查询接当前文字图。01:03:40 +08仅跳过全部测试package成功，未运行测试/真实模型/网页或部署；当前交付工作区`.tools/model-index-rebuild-mainline`。本轮完成服务端现有可信投影连接内的新集合和维度迁移；跨服务端地址/凭据的部署迁移、真实ASR/模型质量与整体生产验收仍未完成，原完整目标保留。下方为历史状态。

2026-10-04当前0038/前端0027已实现[同资料原文件更新](DOCUMENT_UPDATES.md)：保留资料ID及整理信息，新版本解析/索引成功后切换，失败或取消保留旧发布；详情独立更新进度，问题及范围保留。此前0037/0026逐角色模型服务商及模型配置主线已接入。本轮仅跳过测试的package成功，未运行自动化测试、未部署、未调用真实模型，页面用户验收；运行包及前端代理交付在工作区 `.tools/document-replacement-mainline`。当前已实现原文件版本替换，真正嵌入/投影迁移、真实ASR/模型质量及生产验收仍保留；下方状态为历史记录。

2026-10-03当前0035/前端0024完成同target保存材料重建的本机主线；此前retrieval-only范围入口也已完成。实际证据见[0035](changes/0035-saved-source-reindex/verification.md)，未部署。下一范围为已有独立向量receipt的迁移，继而真正嵌入/投影迁移及同资料原文件版本更新；原真实ASR/模型质量、页面与生产目标仍保留。

2026-10-03当前本机0034/前端0022完成模型角色切换后的旧媒体来源读取，实际3075 Java/431前端与六Native各1及原门禁通过，见[验证](changes/0034-media-role-switch-sources/verification.md)。未部署，页面用户验收，真实ASR/模型质量未认证，完整目标active。下一业务切为retrieval-only配置下单选、多选、详情和全库范围入口；原通用重建、嵌入/投影迁移及同资料内容版本更新继续保留，不以重复上传新ID替代。下方各“当前/下一步”保留对应历史时点。

2026-10-03本机增量：[0030原视频参考问答](changes/0030-video-av-query/verification.md)与前端0019已接通最多三参考原视频的完整画面/原PCM召回、整批准备身份、库内完整文字证明及原视频来源；实际2640 Java/894格式/原双80%、370前端/syntax、73 Node与六Native各1 PASS，910/57输入无变、668完整Native/target/JAR class一致、旧2567/344用例身份多重性保留。交接入口工作区`.tools/video-av-query-handoff`，冻结/独立审计以实际manifest/validation为准。下一证据为独立部署及用户导入/整理/问答/来源页面验收，真实供应商/Milvus语义质量与旧ASR仍未验；现有授权范围内不发真实模型或接服务器/旧数据，不改Git/旧冻结包，完整目标active，usage/计费取消。下方为历史记录。

2026-10-03本机门禁通过：[0029原视频音画](changes/0029-video-audiovisual/verification.md)与前端0018。原件上传/整理、显式连续MP4及完整原PCM双路索引、VISUAL/AUDIO/JOINT完整文字问题证明和typed原视频时间来源已接通。最终2567 Java、878格式、原LINE/BRANCH双80%门禁、344前端/syntax、73 Node与六项Native各1 PASS；894/55输入执行前后不变，660完整Native生产class与最终target/JAR一致，旧2268/324用例身份多重性保留。交接入口工作区`.tools/video-audiovisual-handoff`，冻结/独立审计只以实际manifest/validation/sidecar为准。无真实模型调用，未部署，网页用户验收，Git/部署归原责任方，usage/计费取消，完整目标active。下方为历史记录。

2026-10-03本机增量：[0028独立声音知识库](changes/0028-sound-library/verification.md)与前端0017已接通无ASR原文件管理、显式完整PCM索引、文字/原声参考召回、库内单窗口完整事实证明和时间来源回读。2268 Java、324前端、73 Node、五项Native、原双80%门禁及803/49输入与586class/JAR绑定通过；合成替身不认证真实声音/ASR/语义质量。交接入口工作区`.tools/sound-library-handoff`，未部署、页面用户验收。下一本地主线为原视频音画检索与证明，真实质量、发布和总体生产验收继续；usage/计费取消。下方为历史记录。

2026-10-03本机增量：[0027原声向量检索](changes/0027-audio-vector-retrieval/verification.md)与前端0016已接通旧/新音频显式完整speech-span构建、参考音频全部声段召回、转录证明与重启后原音频时间来源。2042 Java、299前端、73 Node、4项单列native及原门禁通过，合成loopback验证不代表真实ASR/语义质量。原图与原声向量均未部署；现网仍20261003-voice-tags，页面用户验收。下一本地主线接非语音声音实际原声理解和有来源的事实证明，继续视频主线及实际质量/部署验收；usage/计费取消。下方为历史记录。

2026-10-03本机增量：[0026原图向量检索](changes/0026-image-vector-retrieval/verification.md)已接新旧已发布原PNG/JPEG显式构建、完整授权范围参考图dense召回、原图事实证明与重启来源回读；前端0015配套，默认关闭。1913 Java、287前端、73 Node、三项单列native及原格式/覆盖率门禁通过，真实图片语义召回/模型/Milvus另验。本image增量未部署；当前实际20261003-voice-tags已部署含标签/语音/PDF。随后继续原声向量及真实ASR/provider质量、生产和用户验收，usage/计费取消。下方日期与发布状态保留历史快照。

2026-10-03本机增量：[0025语音提问](changes/0025-voice-questions/spec.md)（2026-10-10已移除）与前端0014接通音频→完整转录→编辑确认→既有范围问答/库内来源，语音准备独立于查询附件与知识库摄取。标签/语音尚待发布；实际部署20261003-scanned-pdf已含附件、摘要、扫描PDF与来源入口，完整生成/问答/中文识别由用户验收。随后继续已批准视觉/声音检索主线；真实ASR旧失败保留，零新增真实调用，usage/计费取消。下方日期及“下一步”属于对应历史阶段。

2026-10-03增量：[0024摘要建议标签](changes/0024-tag-suggestions/verification.md)接通保存摘要→完整短条目建议→用户勾选→原子追加标签→筛选，前端0013配套；无新模型调用，尚未部署。这一保守派生不等于无摘要自由分类。0023/前端0012及附件/摘要前端交接已冻结，页面验收由用户负责；后续无文字语音问题、视觉/声音向量、真实ASR/多模态质量与生产保持原范围，usage/计费取消。

2026-10-03本机增量：[0023扫描PDF](changes/0023-scanned-pdf/verification.md)接通逐页本机OCR→持久解析/完整索引→文字问答→原PDF按页回看，前端0012配套。默认关闭且尚未部署；具体合成native与回归证据见记录，真实中文/复杂布局和页面质量不从测试推定。查询附件/文件摘要前端0010/0011已本地实现待发布，0022数字证明修复已独立发布但真实ASR问题仍待复验。下方能力表及下一步为对应历史阶段；视觉/声音向量、自动标签和完整生产范围继续保留，usage/计费取消。

2026-10-03当前主线：[0021原文件详情](changes/0021-document-originals/verification.md)与前端0009已由现有独立部署任务发布，五份已保存原文件HTTP完整SHA及两项UI外部复测通过（PDF内嵌像素未验）；[0022音频数字序列完整性](changes/0022-audio-numeric-sequences/verification.md)已本机冻结、611项相关测试/592格式/构建通过，仍待发布。真实ASR旧失败保留，未新增模型调用，不能以本机证明或播放器就绪缩减真实音频质量目标。新增usage/计费开发已明确取消；原音频/知识库主线与现有部署继续，Git、凭据、旧数据及生产/readiness边界保持。下方9月台账与“下一步”按其历史日期理解，不挪用旧余额或重复已完成本机诊断。

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
| PDF / TXT / MD文本解析与扫描PDF OCR | Implemented；10/04已有局部页面记录 | 独立解析进程、原页码与code point定位；完整PDF Blob预览受工具策略限制，不是OS沙箱 |
| 上传、持久任务、取消/重试与崩溃恢复 | 0003 locally verified | 显式loopback opt-in，v2备份迁移，parsed状态与实际分块；见[TEXT_INGESTION](TEXT_INGESTION.md) |
| Spring职责分层与架构约束 | 0005 locally verified | Controller / Service / Repository / Model / Security明确分离，替代旧万能实现；持续执行项目规范 |
| 摄取创建者当前授权复验 | 0006 locally verified | 领取/执行/提交及恢复撤权取消，系统审计同事务，重试权限交集；新增14项负例先红后绿 |
| active revision索引发布 | Implemented | 每claim独立generation、完整物理manifest、不可变attempt与source→physical→digest台账；active保留source revision；见[TEXT_INDEXING](TEXT_INDEXING.md) |
| OpenAI-compatible embedding / extraction、provider reranker Adapter | Implemented；实际质量按场景验收 | 索引embedding、问答embedding/rerank/extract；rerank为provider extension，摘取不等于事实证明 |
| 模型草稿、逐角色provider/测试、应用与召回预览 | Implemented；本轮只读确认应用v2 | 0032/0037；完整资料范围，独立召回预览，不生成答案；手动连接测试有外部调用，不自动执行 |
| Milvus写入与full revision verification | Implemented；隔离真实集成有历史证据 | 固定Java collection、完整ID/float32摘要及前后校验；历史边界集成不认证当前一般容量/生产一致性 |
| Milvus dense + sparse / BM25 hybrid retrieval | Implemented | 只读prepareSearch、完整范围前置、确定性合并及权威hydrate；正常查询不create/load/upsert，当前语义质量另验 |
| 有据问答、选中文档范围、来源引用、拒答 | Implemented；10/04已有局部页面记录 | 完整scope/资格复验及trace同事务；0042主体/分行字段和0044启动日期已接，局部实际回答不代替完整语义/质量验收 |
| 文档撤下、在途取消与历史来源失效 | 0008 IMPLEMENTATION | 默认关闭；DELETE返回deleting/pending，当前服务面过滤，审计及原文保留，不等于物理删除 |
| 受控清理、重新索引与原件版本更新 | Implemented；本轮只读确认批量重建入口 | 0031受控逐项清理、0038同资料原件更新、0039模型索引迁移、0041选中文本重建；原action=delete仍拒绝，批量清理由document-cleanups处理；本轮未提交重建 |
| 独立PNG/JPEG文字OCR及原图引用 | 0009 local backend verified | 真实Tesseract固定英文图HTTP闭环；图片模型/Milvus真实质量与网页未验收 |
| 图片区域/vision、音频转写及视频音画联合 | Implemented；10/04已有图片/音频/视频画面页面记录 | 来源分别为词框/整图/真实音频分段/视频group与原帧；ASR错误、视频间歇model_failure根因及联合模式整体质量仍未认证 |
| 视频选中原帧OCR与真实文字定位 | 0014 local backend verified | [独立模式与v12合同](VIDEO_OCR.md)及[本机验证](changes/0014-video-library/ocr-verification.md)；默认关闭、原像素词框、frame interval，不代表全视频字幕 |
| 独立视频字幕轨检索、问答、typed时间来源 | 0016本机后端已接 | [字幕合同](VIDEO_SUBTITLES.md)，v15完整authority与同轨全文证明；云质量/前端另验 |
| 完整短/长文件摘要与原始来源 | 0015/0016本机后端已接 | 八类原始材料，分层候选不作原证据；默认关闭 |
| 查询附件与一至三参考视频问答 | Implemented；完整实际质量待验 | 0017/0030；临时媒体辅助召回，库内原证据证明完整问题，不用查询图片替代文本事实证明 |
| 原图/原声向量、独立声音与原视频音画 | Implemented；完整实际质量待验 | 0026–0029；默认关闭/显式构建及完整scope，向量只召回，原始材料继续作证 |
| 摘要建议标签 | Implemented | 0024从保存摘要建议短条目、人工勾选追加，不等于无摘要自由分类；0025语音提问已于2026-10-10移除 |
| 真实多模态质量 | 部分正常页面链已有记录，整体未验收 | ASR错识别未修复；视频摄取/问答间歇失败未定位；0043/0045仅安全诊断，新实验需明确调用授权 |
| 生产发布与性能结论 | Blocked by gates | readiness 为 503；没有全链路真实 provider/Milvus、运维、负载与生产验收证据 |

## 已实现文本链的历史路线与保留验收范围

以下为早期文本纵切的实现顺序，不是要求本轮重建0003–0007或停止使用已接通的前端。完整语义、实际质量及生产门禁仍保留，验证暂停以当前授权为准。

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

当前停测指令暂停的是执行，不是把下列证据要求判定通过；10/04开发部署及局部页面成功不解除gate。恢复自动化或新增真实模型评测须按最新授权执行。

- 完成目标文本/多模态范围的实现及独立代码审查，所有 relevant acceptance 可重放。
- 完整 Java/浏览器测试、格式/静态检查、覆盖率门禁；实际结果与源码版本绑定。修改共享语义规则后重跑完整相关测试，不能仅跑新增用例。
- 真实 provider 和 Milvus staging 证据，异常/超时/限流/资源耗尽、状态恢复与一致性验证。
- 合法生产身份、TLS/代理/Origin 配置、安全密钥管理、备份恢复、数据迁移和回滚演练；不得复用或改写旧服务数据来跳过迁移设计。
- 真文件网页端验收、引用定位和 selected-set 并发撤权测试。
- 固定硬件、语料、并发与模型配置的负载基线及观测指标。没有对照数据前，不宣称 Java 比其他实现更快。
- 负责人明确生产发布授权与环境选择，随后才修改当前 production/readiness guard；不能为展示上线而将 readiness 强行改为 200。

当前 [RuntimeGuard](../src/main/java/com/evidence/rag/config/RuntimeGuard.java)与 [RuntimeController](../src/main/java/com/evidence/rag/controller/RuntimeController.java)刻意保持开发边界。路线图不构成解除 gate 的授权。
