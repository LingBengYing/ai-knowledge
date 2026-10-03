# 0027 原声向量检索验证记录

2026-10-03，本切 Client、相关后端测试、新原声 native 正常闭环、旧三项 native 及最终完整门禁通过，输入和制品绑定已由collector确认。首轮分支覆盖率失败仍保留，57项补测后重新 clean verify 于06:01:04 +08:00通过。本文只读取工作区 `.tools/audio-vector-retrieval-verification/backend-final-evidence.json`、对应日志与 `.local` 的已执行证据，没有重跑测试。行为合同见 [spec](spec.md)、固定接口见 [interfaces](interfaces.md)，分工与限定复核见 [plan](plan.md)和 [REVIEW](REVIEW.md)。

## 范围与执行方式

本切新增独立默认关闭的 `rag.audio-embedding.enabled`，仅 development/test 与字面 loopback、既有 audio/ingestion/indexing/answers/query-attachments 及原附件依赖完整有效时开放。当前 owner/editor 为已发布音频显式建立全部非空语音 span 的原声向量；reader 只读状态。同 profile 完整回执直接返回，不解码、不调用模型。构建从保存原文件真实解码，不重复 ASR；独立 generation/collection/profile 与 v18 不可变多 span 回执不改写旧文字 publication 或来源。

协议依据为根任务已只读核对的 [Google Gemini embeddings 指南](https://ai.google.dev/gemini-api/docs/embeddings)与 [models.embedContent REST](https://ai.google.dev/api/embeddings)。固定 Google 路径和 `x-goog-api-key` 请求完整单段 canonical WAV；仅 `content` 与 `embedContentConfig`，显式维度、`autoTruncate=false`。严格验证 embedding.values、有限非零 float32 和完整 profile/decoder；不猜模型默认、不将 ASR 文字冒充原声 embedding。共享 HTTP 的旧默认 Bearer 入口保持。

AUDIO 模式存在实际 prepared queryAudio 时才走新 DENSE_ONLY 路径；同一次 decode/ASR 留存全部 PCM，包括静音分段。冻结完整 selected/all 范围后准备查询，再检查全部音频的当前回执；缺失时拒绝且原声 embedding 为 0，查询 ASR 可能已执行。每路所有候选先完整 authority 映射回库内原语音证据，再 RRF；所有模型/投影调用前后及最终原 trace 事务复验完整范围，包括未引用资料。事实证明仍只取原问题和库内保存转录，typed 时间、原音频 SHA/Range 和重启来源保持。

根任务串行执行共享 Maven/Spotless，代理未并发写共享 target。Client RED/GREEN 在各自独立冻结基线副本、离线 Maven 与绝对缓存路径执行。默认 suite 不执行 `*IT`；新原声与旧图片、附件、语音共四项 native 单列，不计入默认测试数或用其覆盖率替代完整门禁。全部远程模型/Milvus均为 loopback 合成协议夹具，真实云调用为 0。

## RED 与真实失败记录

1. Client 有效 RED：以0026冻结源码副本、可执行最小 Client stub 和八项新测试运行，05:16:13 +08:00 得到 8 项、7 失败、0 错误、0 跳过；旧共享 Bearer 回归 1 项通过。失败覆盖完整 WAV/固定 Google 请求、配置与 profile、输入/包络拒绝、取消和上游失败处理。原日志为工作区 `.local/audio-embedding-client-red.log`，验证目录保存同名副本。这是可执行行为断言失败，不把新类缺失或 testCompile 失败算作 RED。此前准备脚本缩进错误发生在测试执行前，不计产品 RED。
2. 替换为实际 Client 和固定 Google transport 入口后，独立 GREEN 于05:24:48完成 8 项全部通过，失败/错误/跳过均0；原日志 `.local/audio-embedding-client-green.log`。没有真实 provider 请求。
3. 首次共享相关回归于05:35:21运行72项，70通过、2个新夹具失败、0错误/跳过，保留 `backend-related-initial.log` 及 `related-initial-reports`。新 `AudioVectorPreparationTest` 的单 span 8192字符超过原4096限制，先触发 `parser_invalid_output`，不能据此认证总转录上限；新 `AudioVectorAnswerServiceTest` 的期望句号与实际摘录范围不符。仅修正新夹具的合法分段和精确 quote 期望，没有放宽旧断言或生产门禁，也不将这两项夹具误配计作产品 RED。05:37:58重跑同72项全部通过，见 `backend-related-final.log`。
4. 首次新 native 于05:38:25因执行命令提供含 `..` 的非规范 native 路径，报 `Invalid native media executable`，1项/0失败/1错误/0跳过。保留 `backend-native-path-error.log` 和 XML；这是启动执行错误，不是产品行为 RED。改用规范绝对路径后的同一测试于05:39:16通过，产品执行文件校验未放宽。
5. 首轮完整 clean verify 于05:44:19完成1985项默认测试，失败/错误/跳过均0，710个Java文件格式通过；但 BRANCH 为10710/13487=79.409802%，低于原80%门槛，实际 BUILD FAILURE。LINE 为19900/21437=92.830153%。保留 `backend-full-coverage-initial.log`、`first-full-test-pass-coverage-failure.json` 和 `backend-initial-jacoco.xml`。三代理随后只补新功能的合理遗漏行为测试，保持原LINE/BRANCH双80%门槛；最终独立 clean verify 结果见下表，没有将本轮测试通过写成门禁通过。

补测共57项：A配置/Client 5项，B domain/独立协议16项，C准备/查询/authority 36项。覆盖可选配置未完整装配不能声明能力、图声空间独立共存、当前实际服务及开关依赖、API根URL末尾斜线；完整波形与时间/物理映射、entry/manifest完整性及独立协议错误；全部PCM与原文字预算、候选全量authority映射、完整scope与最终回执资格变化。补测仅新增或完善本切新测试，没有改生产、旧有效断言或pom门禁，不另宣称有效RED。

05:56:33补测后129项定向全部通过，见 `backend-related-supplement.log`。根任务逐SHA确认全部生产源码和529个native生产class在补测前后未变。05:57:13的 `supplement-coverage-preflight.json` 来自首次完整回归与相关回归execution data的合并，BRANCH10827/13487=80.277304%、LINE19946/21437=93.044736%仅作预测；它不是最终干净构建门禁，也不替代随后重新启动的 clean verify。

其余新 Domain/worker/查询/配置/HTTP 测试没有单独有效 RED 的，不从编译中间态或夹具失败倒推 RED。所有失败日志按原结果保留。

## 已完成验证

| 检查 | 实际结果 | 完成时间（+08:00）/证据 |
| --- | --- | --- |
| 独立 Client GREEN | 8项，失败/错误/跳过均0 | 05:24:48，`.local/audio-embedding-client-green.log` |
| 后端相关回归 | 72项，失败/错误/跳过均0 | 05:37:58，`backend-related-final.log` |
| 新原声检索 native | 1项，失败/错误/跳过均0 | 05:39:16，`backend-native.log`、`backend-native-final.xml` |
| 旧图片/附件/语音 native | 各1项，合计3项，失败/错误/跳过均0 | 05:40:33，`backend-native-regression.log`及三份归档 XML |
| 后端 Node | 73项通过，失败/取消/跳过均0 | `backend-node.log` |
| 前端配套回归/语法 | 299项=287旧项+12新增，失败/取消/跳过均0；语法通过 | `frontend-final.log`、`frontend-syntax.log`及前端0016记录 |
| 首轮完整 clean verify | 1985项全通过、710个Java文件格式通过；BRANCH 79.409802%，原门槛未过 | 05:44:19，`backend-full-coverage-initial.log` |
| 补测相关回归 | 129项，失败/错误/跳过均0 | 05:56:33，`backend-related-supplement.log` |
| 补测后最终完整 clean verify | 2042项=1913旧项+129新增，失败/错误/跳过均0；716个Java文件格式与原双80%门禁通过 | 06:01:04，`backend-full.log`、`backend-final-evidence.json` |

最终完整默认回归的实际覆盖率如下，保持原LINE/BRANCH双80%阈值；四项显式native和先前合并预测不混入本轮计数：

| 指标 | covered | missed | 实际比例 |
| --- | --- | --- | --- |
| LINE | 19951 | 1486 | 19951/21437 = 0.9306805989644074（93.068060%） |
| BRANCH | 10831 | 2656 | 10831/13487 = 0.8030696225995403（80.306962%） |

collector于06:02:44 +08:00确认测试、输入和制品绑定。完整报告和case身份清单归档；首次未过覆盖率的结果仍单独保留，2042项最终默认回归包含全部1913个旧case，没有删除、跳过旧项或降低门槛。

## Native 正常闭环

`AudioVectorRetrievalMainlineNativeIT` 使用临时 authority 库、合成 WAV、真实 Spring/生产 HTTP、FFmpeg/FFprobe 解码和独立 indexing worker，模型与 Milvus 为 loopback。Spring 环境移除系统环境与系统属性来源；只显式读取本机 native 路径/开关，不读取真实 provider 凭据。

旧、新两份库内音频各有三段实际 PCM，中段静音、首段相同、尾段不同；非空 ASR 转录相同。另建其他身份私有音频。先关闭原声模块发布旧音频，旧转录检索夹具没有目标召回，旧音频问答 `no_evidence` 且原声 embedding 为0。再启用模块：旧资料 GET 为十字段 missing，私有资料 GET 为404；完整选择新旧两份而未建立回执时，参考音频完成三段 ASR 后返回 `audio_vector_required`，原声 embedding 仍为0。

分别显式 POST 为两份音频建立完整原声向量，全部四个已发布 speech spans 真实调用 embedding，返回 available 和不同 generation，ASR 计数不变；同 profile 再 POST 旧资料不新增 embedding，旧文字 publication 原样。查询参考音频前两段静音、末段匹配旧音频尾段；三段 PCM 全部发往固定 Google 接口，含静音，总原声 embedding 为7次。请求 WAV 字节与库内四段、查询三段逐一核对，包络/header/维度/autoTruncate 受严格断言。

三路搜索均为原声独立集合的 DENSE_ONLY，预 top-K 过滤同时包含两份授权资料及各自 vector generation，不含私有资料。全部候选映射回原语音证据后融合、排序与证明，最终命中旧资料真实尾段2000..3000ms；答案事实来自保存转录中的650，证明请求只含原问题和库内文本，不使用查询文件作来源。

citation 绑定旧 document/source SHA、audio_span/machine_asr/server_chunk；typed source GET回读同一citation，完整原音频逐字节相等，Range返回正确原字节。重启后来源和原音频仍可读取，模型及 ASR 请求数不增加。该结果证明合成原波形与真实生产路径的传递、召回和来源封存，不认证实际供应商的声音语义能力。

旧 `ImageVectorRetrievalMainlineNativeIT`、`QueryAttachmentLibraryNativeIT`、`VoiceQuestionMainlineNativeIT` 各通过，保留原图向量、三媒体附件、语音确认后旧问答及来源正常路径。collector已逐个确认四项native时点的529个生产class与最终target及JAR对应字节一致，未把早期native报告套到变化后的生产制品。

## 输入与旧实现保留

本切以 `image-vector-retrieval-handoff` 为旧基线，首次完整验证记录后端726个与前端46个输入；补测新增6个测试文件后，最终clean verify已按后端732个、前端46个输入重新记录。collector确认最终测试前后各自哈希均未变化，并逐项核对旧1913个默认case身份与多重性全部保留。`old-test-file-retention.json` 与最终证据确认旧318个后端测试/资源文件中299个原字节不变，19个既有文件仅作v18 schema夹具适配，没有放宽旧有效断言求绿。

`old-index-protocol-preservation.json` 已核对旧八个生产文件字节不变：IndexClaim、IndexProtocol、IndexWorkerLifetime、ProcessTextIndexer、IndexWorker，以及 ImageVectorProtocol、ImageVectorWorker、ProcessImageVectorIndexer。原文字v3/8MiB和图片协议保持，原声独立最多40MiB。最终 `rag-java-0.1.0-SNAPSHOT.jar` 大小38,933,745字节，SHA256为 `1a354f2a984950715818b02bdf03170b6ebaeb7e2ba6145a1bb62bc5595a393b`。529个native生产class与最终target和JAR字节一致；collector还确认已删除的 `QueryAttachmentServlet.class` 未恢复，`BoundedMediaQueryServlet.class` 保留在最终制品。

限定只读交叉审阅核对AV-02/07–09：同次完整PCM及静音、全部候选先authority后RRF、完整scope和未引用回执的最终trace复验、独立profile/decoder/空间，以及旧构造器和无波形manifest兼容，未发现阻断项。该静态复核与上述实际测试分开记录，不替代真实provider或网页验收。

## 未验范围与部署

新增真实 provider/付费调用为0，没有访问真实凭据、旧业务数据或部署页面，没有 Git 写入和部署。真实原声语义召回、真实 Milvus、非语音理解、真实 ASR 正确性及浏览器完整业务闭环不由本机替身认证；页面由用户验收，usage/计费开发保持取消。

部署状态沿只读本地记录 `deployment-observation.json`：当前 `20261003-voice-tags`，旧语音冻结 JAR SHA256 为 `590a53daedc9ff17cf991f71f54184bf6158398c2f553911f76a7548d791385b`，已含标签、语音及原PDF能力。该观察复用上轮本地发布报告，本轮没有重新访问服务器或浏览器；入口/空状态检查不等于完整业务质量验收。原图0026与本切原声0027均尚未部署，真实ASR失败继续保留。
