# 0025 语音问题验证记录

2026-10-03本机正常闭环及完整回归通过。结果依据根任务归档的工作区`.tools/voice-questions-verification/backend-final-evidence.json`和对应日志；本记录作者只读取证据、编写本文，没有重新运行测试。分工与限定复核见[plan](plan.md)和[REVIEW](REVIEW.md)，行为合同见[spec](spec.md)。

## 范围与执行方式

本切实现独立默认关闭的语音输入准备：单个音频完整解码/分段ASR，逐ordinal以单个LF连接全部原始text，包含空段和尾段；返回完整文字及源/转录SHA、decoder/model/compiler版本、时长和固定`java-voice-question-v1`。完整预览上限为UTF8 65536字节，用户确认后的文字仍经过旧4096字节问题限制。准备不读取库、不创建资料或回答trace，确认编辑后沿旧完整scope/mode问答和库内来源。

后端Maven/Spotless与两项显式native均由root串行执行，未有实施代理并行写target。当前仓库的工作目录为`github/ai-knowledge`，每次加载工作区`../../.tools/env.sh`，使用离线参数`-B -ntp -o -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2`。有效RED在冻结标签版的独立构建副本中运行，其离线缓存路径按该副本位置解析；不得照搬仓库相对路径。

完整门禁执行`clean verify`；相关回归执行`test spotless:check`；显式native分别执行`-Dtest=VoiceQuestionMainlineNativeIT test`和`-Dtest=QueryAttachmentLibraryNativeIT test`。默认全量不执行`*IT`，两个native结果单列，不加入1839项默认计数。

## RED与夹具修正

1. 首次RED尝试因独立副本的离线缓存路径填错而产生执行错误，未形成业务断言结果，不计产品RED。更正的是执行路径，没有为此修改产品或放宽测试。
2. 有效RED为`VoiceQuestionRuntimeTest.explicitVoiceCapabilityNeedsNoVisualVideoOrQueryAttachmentConfiguration`：在冻结标签版上显式开启voice，期望能力包含`voice_questions`，实际缺失。03:04:44 +08:00为1项、1失败、0错误/跳过，日志`backend-runtime-red.log`。本切没有执行新路由404 RED；新DTO/Service测试先落盘再实施，但没有把缺新类型或编译中间态算作RED。
3. 共享媒体接收器替换需要删除`src/main/java/com/evidence/rag/web/QueryAttachmentServlet.java`，由`BoundedMediaQueryServlet`承接原异步接收/处理生命周期；旧附件和新语音各自注册精确路由、独立handler与预算。旧TEXT/IMAGE分派移入`QueryAttachmentConfiguration`。迁移后的旧IMAGE夹具补回visual=null时503 `query_attachment_unavailable`分支，等价于旧Servlet；没有把IMAGE改走文字答案或修改旧有效断言。
4. 新`VoiceQuestionMainlineNativeIT`首跑已完整解码、处理三段ASR并得到库内answered，但03:23:11 +08:00因新quote期望错误失败：期望“上海住宿上限为650元。”，实际精确字段摘录为“上海住宿上限为650元”。1项、1失败、0错误/跳过，保留日志`backend-native.log`。追读既有`AnswerProtocolServer`/`SourceFields`/`TextGrounding`后，只将新IT回答citation与GET来源两处精确`assertEquals`改为同一无句号`POLICY_QUOTE`；上传原文仍带句号。不改旧server、证明policy、产品或旧断言，不用contains放宽精确quote；此项是新夹具合同期望修正，不算产品RED或产品修复。

上述日志均保留原结果，没有将早期失败重写为通过。

## GREEN与完整门禁

| 检查 | 实际结果 | 完成时间（+08:00）/证据 |
| --- | --- | --- |
| 后端相关回归与格式 | 121项，失败/错误/跳过均0；639文件格式检查通过 | 03:21:20，`backend-targeted.log` |
| 语音显式native | 1项，失败/错误/跳过均0 | 03:26:01，`backend-native-final.log`及`backend-native-final.xml` |
| 后端完整clean verify | 1839项=1812旧项+27新增，失败/错误/跳过均0；639文件Spotless通过，双80%门禁通过 | 03:30:00，`backend-full.log` |
| 后端Node回归 | 73项通过 | `backend-node.log`及`backend-final-evidence.json` |
| 前端完整回归/语法 | 275项通过；语法PASS | `backend-final-evidence.json`，前端0014配套证据 |
| 旧附件显式native补验 | 1项，失败/错误/跳过均0 | 03:32:18，`backend-old-attachment-native.log` |

27项新增默认行为测试包括：VoiceQuestionDtoTest 3项、VoiceQuestionServiceTest 13项、VoiceQuestionRequestMapperTest 4项、VoiceQuestionResponseMapperTest 1项、VoiceQuestionConfigurationTest 4项、VoiceQuestionRuntimeTest 2项。覆盖完整LF拼接/空段/尾段/Unicode字节边界、不套旧4096预览限制、非法音频及安全错误映射、超时/中断/profile漂移、严格包络/尺寸、配置默认关闭/依赖与无启动调用。相关121项另包含旧音频、附件输入/编排、HTTP分派和架构回归；不是只跑新增case。

完整门禁覆盖率取归档JSON中完整默认回归的计数，保持原双80%阈值：

| 指标 | covered | missed | 比例 |
| --- | --- | --- | --- |
| LINE | 17216 | 1191 | 17216/18407 = 0.9352963546476883（93.529635%） |
| BRANCH | 9371 | 2280 | 9371/11651 = 0.8043086430349327（80.430864%） |

完整1839项报告和覆盖率已在后续旧附件native补验前归档；后续单列IT不冒充默认suite或覆盖率门禁报告。

## Native正常闭环

语音IT仅读取显式非秘密环境变量`RAG_VOICE_IT_ENABLED=true`、`RAG_VOICE_IT_FFMPEG`和`RAG_VOICE_IT_FFPROBE`，路径由执行者提供；Spring移除系统环境/系统属性来源，使用临时数据目录与合成配置。开启voice/audio/ingestion/answers，关闭visual/video/query_attachments，使用真实`ProcessAudioDecoder`和生产`OpenAiCompatibleAudioModels`，模型和Milvus均为loopback协议夹具。

单项IT实际完成：合成3秒16kHz PCM/WAV→chunk=1秒的三次完整ASR，响应分别含首段、空中段和尾段→完整LF连接、空白保留及八字段/SHA/3000ms/版本断言；逐段发送的WAV原字节与预期PCM切片精确相等。转录准备前后15张临时库表计数及库模型请求数均不变，没有音频资料、持久任务、审计或trace副作用。

同一IT真实上传一份合成POLICY，使用旧独立文本parser解析、手工完成权威索引发布并安装loopback投影，模拟用户显式编辑确认后POST旧`/v1/answers`，保留完整selectedID，得到answered及650事实；citation与GET来源都精确为库内摘录“上海住宿上限为650元”。确认/来源读取后ASR仍为3次，原资料数与审计不变；仅正常答案产生既有trace，音频编译/音频span/查询附件准备表仍为空。语音不是资料、引用或事实来源。

旧`QueryAttachmentLibraryNativeIT`补验使用原显式`RAG_VIDEO_DECODER_IT_ENABLED`、`RAG_VIDEO_DECODER_IT_FFMPEG`、`RAG_VIDEO_DECODER_IT_FFPROBE`以及`RAG_IMAGE_OCR_IT_EXECUTABLE`、`RAG_IMAGE_OCR_IT_REVISION=tesseract-5.5.3-eng`配置，真实FFmpeg/Tesseract处理原三种媒体辅助问答及重启来源回读，通过共享transport保留旧附件正常链。该IT仅迁移Servlet装配，没有新增或删除旧行为用例。

## 输入与制品绑定

根任务归档JSON以`tag-suggestions-handoff`为旧基线：655个后端输入、42个前端输入在测试前后均无变化。旧296个后端测试/资源文件中294个原字节不变，仅`QueryAttachmentHttpTest.java`和`QueryAttachmentLibraryNativeIT.java`两份旧夹具作共享Servlet等价装配迁移；1812个旧默认case的身份与多重性全部保留。未删除、跳过或放宽旧有效断言求绿。

语音native实际运行的475个生产class与最终打包JAR内对应class字节一致。完整门禁生成`rag-java-0.1.0-SNAPSHOT.jar`，大小38,769,404字节，SHA256为`590a53daedc9ff17cf991f71f54184bf6158398c2f553911f76a7548d791385b`。该绑定不把早期native报告套到不同生产字节，也不将文档收尾当作重新构建。

新交接必须显式纳入旧`QueryAttachmentServlet.java`删除，不能恢复兼容壳或遗漏删除项；既有冻结PDF/标签交接包不覆盖。

## 未验范围与发布边界

本轮没有真实provider或付费模型调用，没有读取凭据/旧数据或进行Git写入、部署。真实中文ASR和provider识别质量、真实Milvus服务、浏览器页面均不由loopback/native结果认证；历史真实ASR失败保留，不能用本切输入准备及文字确认功能宣称已修复。

当前已部署版本仍为独立部署任务的扫描PDF冻结JAR（SHA前缀`12e26`），不含0024标签或0025语音。本文的`590a53da...`本机制品不是当前线上JAR；页面由用户验收，后续部署仍归原责任方。usage/计费开发继续取消，未开展该工作。
