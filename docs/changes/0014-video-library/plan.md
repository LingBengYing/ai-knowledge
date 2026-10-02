# 0014 执行计划

最新冻结：2026-09-20 视频选中原帧 OCR 本机后端闭环完成，1352 Java/458格式、双80%、73 Node及native6通过；485输入与旧1299身份/多重性见[ocr-verification](ocr-verification.md)。不重复已完成视频摄取/问答/OCR诊断。完整字幕、摘要、真实质量、网页与生产仍开放，下方保留分步历史与执行合同。

只有一条视频业务主线；先补真实输入依赖，再接同组证据和公开问答，不把每个Module当成独立缩小目标。

1. 冻结VideoDecoder/VideoCompilation与真实时间模型；受控native解码、变化+兜底选帧、原帧字节、音轨相对时间，抽出已有PCM转写循环供音频和视频共用。实际合成MP4验证后保留完整产物，无音轨/空转录不伪造声音。
2. 将完整编译结果接入既有持久任务；视频独立authority、真实EvidenceGroup及完整索引发布。随真实消费方加入附表/FK，旧文本/图片/音频身份及v1–v9保持。
3. 统一完整问题事实身份，接入同组音画证明；分别验证视觉、转录及联合路径。保留旧文字/视觉全覆盖Interface，不以caption、摘要或两个失败结果拼出答案。
4. typed时间/关键帧与原视频Range HTTP闭环，旧用例保留、独立审查、全部门禁和源码绑定。字幕/OCR、查询附件、真实质量/网页与生产未验项显式保留并继续，不用Module或替身代证。

使用codebase-design的小Interface与真实Seam；项目layer-first Spring规范优先。Model保存时间/字节/版本不变量，Worker管理native进程，Tool做确定性媒体解析，Service编排完整处理。没有真实消费方时不铺空Controller/Repository/通用框架。

只读定位及本机合成媒体协议实验已完成。实现ownership：音频代理只负责AudioTranscription/AudioTranscriptionService及新测试，GREEN后以它复用旧AudioCompilationService循环；Domain代理只负责VideoInput、VideoFrame/DecodedVideo/VideoFrameRecall/VideoCompilation及新测试；native代理只负责VideoDecoder/ProcessVideoDecoder/NativeMediaSession及新测试，GREEN后提取旧ProcessAudioDecoder共用进程生命周期；主代理负责VideoCompilationService及测试、版本化工件与唯一Maven运行。旧测试不得修改，代理不运行Maven。共享树不得回退他人修改；各自先提交可编译stub及RED测试，主代理统一行为RED后进入GREEN。

时间协议冻结：以第一实际解码视频帧PTS为epoch，保存原epoch和每帧相对实际PTS/时长；变化阈值0.3，首帧+场景变化+间隔到期后的下一个实际帧，不声称间隔为硬性最大帧距。音轨按同一epoch解码前补静音，真实音频样本早于epoch先明确拒绝，不暗中裁掉；禁止用atrim/shortest截去尾音。完整时间长度取最后实际视频帧末尾和完整对齐PCM末尾的较大值。选帧数/总字节超限整体失败，不截断后伪称完整。

本机FFmpeg 8.1.1实验在`/private/tmp/video-protocol.10yIJr`，合成变帧率MP4/MKV选到0/2/3/5秒；整体PTS平移2秒后相对时间及PNG/对齐PCM保持。实验曾用固定6秒apad/atrim比较波形，该命令不是生产实现。协议参考[FFmpeg select](https://ffmpeg.org/ffmpeg-filters.html#select_002c-aselect)、[copyts](https://ffmpeg.org/ffmpeg.html)及[FFprobe show_frames](https://ffmpeg.org/ffprobe.html)；实际native acceptance仍须在实现后单独执行。无模型调用，不动旧服务/数据。

上一目标回合分类为progress：0013音频完整后端native/HTTP及1073 Java冻结证据已落盘。当前不重复其诊断；0014是已批准视频目标的必要后继，不代表授权前端、云调用或部署。

## 步骤1冻结与下一步

本目标回合为progress：真实视频输入/完整编译已落地；16:50:36完整1134 Java/369格式、双80%门禁及73 Node通过，native8项单列通过，393输入与旧1073用例保留见[verification](verification.md)。不重复已完成视频编译诊断，直接继续步骤2的既有HTTP持久任务→视频authority/EvidenceGroup→完整索引；本步骤不等于0014完成。

下一步最小实际接点为IngestionService的prepareUpload/validateUploadEnvelope/parserRevision/uploadDocument/complete、IngestionTaskProcessor既有事务外编译与currentClaim、Persistence/Ingestion配置装配、IngestionRepository/AuthoritySchema新附表，以及IndexingRepository的投影计数/完整条目/发布映射和EvidenceRepository完整scope计数。复用已有上传Controller、claim、worker及完整manifest，不另造通用框架。

必须先解决正常输入的具体类型碰撞：现有AudioInput与新增VideoInput同时接收.mp4/.webm；UploadServlet在读字节前通过prepareUpload(filename)决定MIME，现有task按AudioInput优先分派。不能通过更换if次序抢占旧audio-only MP4/WebM，必须冻结可验证的真实流型、独立type/MIME/compiler身份后再排队/执行；具体协议在步骤2先以保留旧音频正例的测试定义。视频帧不得借用TextPage，视频转录不得借用独立audio身份，现有v1–v9不改。

装配时注意既有ASR/Vision Bean受各自开关控制，Vision依赖answers；不能为视频摄取顺带打开旧问答能力。202 queued→完整parsed→完整indexed仍须分别真实发生；runtime只随实际consumer接线开放video_upload/video_index，本步不假报视频问答、来源或ready。

## 步骤2执行合同（2026-09-12）

输入为合成视频；用户通过原 `POST /v1/documents?filename=...` 显式以 VideoInput 支持的 `video/*` Content-Type 上传，再使用原索引接口。原 `application/octet-stream` 合同完全保留，包括 audio-only MP4/WebM。Content-Type 只选择请求的编译合同，不证明实际流型；后台固定该 MIME/compiler 执行真实 probe/decode，不自动改型或回退。202 仅表示已保存原字节并排队，只有完整流型验证和编译成功才是 parsed。此处明确细化上一段“排队/执行前冻结”：排队前冻结请求类型，执行时核实真实流型，避免在 HTTP 中新增解码进程。

沿用 REST、现有认证与安全错误、持久任务轮询、SQLite Store 短事务和独立索引 worker，不引入 WebSocket、ORM 或新任务框架。视频配置独立、默认关闭、限 development/test loopback；其 ASR/Vision 资源单独命名且按生命周期关闭，不隐式开启旧音频/图片问答。

v10 保存完整 VideoCompilation、原帧字节和全部转录（含空段）；原视频仍在 corpus_documents。frame/span 使用独立确定性身份。EvidenceGroup 是真实 frame 时间区间与 ASR 分段区间的交集 pair；未相交成员保存单模态组，保留完整音尾，不伪造 scene。每帧 caption 和非空转录各投影一次；总召回文本须在封存前符合既有 1500000 code point 上限，超限整体失败。完整 source/entry 计数必须同时进入发布 gate 和 all/selected scope；本步不开放视频答案或 trace。

Ownership：存储代理负责视频 Domain/确定性 manifest、IngestionRepository、v10 AuthoritySchema/Store 及新持久化测试；索引代理负责 IndexingRepository、EvidenceRepository 及新索引/scope 测试；摄取代理负责 UploadServlet、IngestionService、IngestionTaskProcessor 及新准入/任务测试。主代理负责 Config/runtime、真实 HTTP/native acceptance、文档和唯一 Maven 执行。共享树不回退他人改动；先提交可编译 stub/具名失败测试，主代理记录 RED 后才进入 GREEN。旧 v1–v9 迁移体、旧测试身份和多重性保持，必要当前版本夹具适配逐项记录。

验收输入为实际 FFmpeg 生成的短视频，使用本机 ASR/VLM/embedding/Milvus 协议替身验证公开上传→完整 parsed→显式索引→完整 indexed 与全 scope；另保留 audio-only MP4/WebM 正例。不是云模型质量、联合问答、网页或生产验收。无云请求、分支、推送、部署或旧服务操作。

## 步骤2冻结与下一主线

本回合为progress：2026-09-12 16:04:38实际JDK21完整1171 Java/383格式/双80%、73 Node通过；最后源码的native10项单列通过（新有音/无音视频HTTP上传→parsed→indexed两项、原音视频八项）。407输入与旧1134身份/多重性绑定见[publication-verification](publication-verification.md)。步骤1历史冻结保留，当前步骤2不重复诊断；未操作前端、旧服务/数据、云模型、Git分支/推送或部署。

下一步直接推进步骤3：完整问题事实身份→同一真实EvidenceGroup的原帧/转录逐事实证明，分别验证画面独有/音轨独有/联合问题，再接typed视频时间/关键帧及原视频Range。旧TextGrounding/VisualAssessment全覆盖Interface保持，不能把两份整问题失败结果拼为成功，也不能以caption替代原图核验。v10新增的findVideoCompilation/findVideoGroups及独立publication entries可供消费；已有全scope计数保持。字幕/OCR/摘要/网页/真实质量和生产目标不删，但不抢占这条联合证明主线。

## 步骤3当前实施合同（2026-09-20）

先接真实消费方需要的逐事实证明 Module：输入完整问题、一个完整视频 compilation 和其中真实 group 的身份；输出绑定 parent/frame/transcript/group 的逐事实证明或整体拒绝。它不是已授权答案，不开放 HTTP 或 runtime 能力。随后由现有 AnswerService/EvidenceService 接完整 snapshot、检索、trace 和 typed 来源；不复制第二套任务/授权框架。

共同计划复用 QuestionFacts 的完整、有界解析，不增加远程拆题调用。QuestionPlan 保存完整问题及 SHA、planner revision 和全部 QuestionFact；事实身份绑定整问题 SHA、ordinal、requirement，不与旧 claim SHA 或 requirement SHA 混用。旧确定性语法限制保留并明确声明，不将不支持的问题截断为可处理前缀。旧 verify/verifyText、VisionModels draft/verify 仍要求整问题覆盖；新增独立 fact Interface，生产 Adapter 的 system prompt 明确目标事实及完整上下文，而不是把“只答子题”塞入旧用户 prompt。

同组评估每个事实分别调用原帧 draft/verify 与转录 extraction/确定性 verifyFact；只接受同一真实 group 的成员，原帧评估不接 caption，转录证明只引用该 span，但冲突检查保留完整视频转录上下文。所有事实须覆盖，联合模式还要求两个模态各贡献至少一个有效事实；独有模式分别保持本模态全覆盖。离散通过标记不是概率。每次外部调用前后检查 current、冻结模型版本和总预算；失败不返回部分成功。结果可供后续审计保存 fact ID、离散分数和版本，不保存原问题片段。

Ownership：事实代理负责 QuestionPlan/QuestionFact、tool/answer 的 planner 与 TextGrounding 新方法和新测试；协议代理负责两个 fact 模型 Interface、现有 OpenAI Adapter 的逐事实方法与新协议测试；主代理负责 VideoAssessmentService/结果与测试、工件和唯一 Maven。其他代理只读审查接线。共享工作树不回退他人修改；各自先写具名失败测试与可编译 stub，主代理记录 RED 后再实现。旧测试和 v1–v10 schema 不改。本机临时 JDK/cache 已失，改用已安装 JBR21.0.8 与新隔离构建目录；下载仅限公开构建依赖，不调用云模型。

### 下一实际接点（不重复证明 Module）

2026-09-20冻结：上述内部证明Module已通过最终1216 Java/399格式、双80%、73 Node和单列native1，423输入绑定与限定独立审查见[assessment-verification](assessment-verification.md)。这是步骤3内部证明切，不是整个步骤3/0014完成；下一次从下面的真实授权消费方接线继续。

沿用 AnswerService.submit/execute 的准入、预算和生命周期，视频 proposal 委托具体 VideoAnswerProposalService；禁止递归调用 answerAudio/VisualAnswerService 再合并结果。EvidenceService.snapshot/current/finish 保留完整 all/selected scope，视频 publication 子集只用于检索过滤。EvidenceRepository 从两个 video publication entry 表 hydrate 命中成员，再扩展数据库已记录的真实 group；候选只加载召回材料，选组后再读原帧与完整转录。

不能复用独立图片 sourceSha==imageSha 或独立音频 compiler/MIME 假设。新增 PublishedVideoCandidate/PublishedVideoGroup 表达 source、physical、group 的不同身份，进入模型和提交前重新验证。v11 随真实消费方新增 video trace 及 fact 子表，保留 v1–v10，加入原全局 citation ordinal/count seal；失效必须清空视频引用。

随后新增 typed video-answers/video-sources 与 frame/content 回读。原 group 不是 scene，当前没有真实 scene 实体，不能改名伪造。保留 µs 精度并说明公开 ms 转换；原视频先完整授权/源 SHA 校验，再复用现有 AudioContentResponse 中的单 Range 规则。只有公开链完成才开放 runtime 能力。

## 视频授权答案与来源执行合同（2026-09-20）

上一目标回合为progress：已完成并冻结内部逐事实证明与真实合成视频验证。本轮输入合成视频和完整文本问题，正常路径为既有上传/索引→显式visual/transcript/joint模式问答→真实publication候选/同组原帧转录→完整scope复验与v11 trace→typed来源/关键帧/原视频Range。本轮不做前端、云模型、旧服务数据、Git分支/推送或部署；完整质量/生产目标继续保留。

沿用layer-first Spring、REST、安全错误、现有JWT/session、任务轮询、SQLite短事务与AnswerService唯一并发/总预算。VideoAnswerProposalService隐藏视频检索、真实组选取与证明编排，不引入新任务或授权框架；模型/投影是已有真实Adapter与替身的Seam。VideoProofInput只传选中原帧和完整ordinal转录，不加载其他帧BLOB；五字段不能独立证明父源或精确音轨交集，这些由同revision权威查询验证，不能凭Interface声明伪造已验证事实。

v11追加video_trace_proofs/facts/evidence：proof绑定同一publication/group、mode与模型/策略版本；fact只保存整问题绑定的fact ID、ordinal和两个离散贡献值；每citation关联一个fact、一种模态及真实publication physical ID。转录引用记录精确CP与摘要，视觉引用指向真实原帧；统一原有全模态32citation预算/连续ordinal/final seal。旧v1–v10迁移体不变，失败清空全部视频引用；final/source重新物化全scope与权威身份。

Ownership：存储代理独占AuthoritySchema/SqliteAuthorityStore/EvidenceRepository/TraceDraft与新视频published/source/trace类型和存储测试；接线代理独占AnswerService、Answers/Video配置、新Controller/mapper/安全DTO、共用Range提取及新接口测试；事实代理独占VideoProofInput与VideoAssessmentService输入适配、新等价测试；主代理负责EvidenceService、具体VideoAnswerProposalService及其测试、runtime能力、native HTTP验收、工件和唯一Maven。共享树不回退他人修改；先可编译stub/具名RED，再授权GREEN；旧测试/阈值保留，必要migration版本夹具变更逐项记录。

## 视频授权主链冻结与下一步

本目标回合为progress：2026-09-20 12:14:03完整1299 Java/437格式、双80%及73 Node通过，最后生产源码的native5单列通过。用户已可通过本机后端上传/索引视频，选择画面、转录或联合模式提问，并回读同版本时间引用、原帧与原视频Range；结果有v11真实publication/group/fact trace，完整scope保持。461输入、旧1216用例身份/多重性和限定审查见[answers-verification](answers-verification.md)。不能用协议替身证明真实模型质量，也不是网页或生产完成。

下一条具名主线为视频原帧OCR文字：真实封存帧输入→OCR文本/词框与帧时间绑定→独立文字证据检索和逐事实引用→同版本区域来源回读。复用既有图片OCR Module，不把VLM caption当OCR，不伪造字符/词级时间；独立字幕轨仍是后续输入能力。先读当前工件并定义该正常路径验收，再实现，不重复视频输入/发布/证明/授权HTTP的已完成诊断。摘要、查询附件、视觉/声音向量、真实质量、网页和生产范围保留，前端/旧服务数据/Git/云调用边界不变。

## 视频原帧OCR执行合同

上一目标回合是progress：1299基线的461输入本轮逐SHA复核未变化。当前输入合成英文大字/无字视频，用户沿原上传及索引操作后以ocr模式提问，输出完整有据答案、原帧文字词框与实际帧时间。保持Java21/layer-first Spring、REST、既有JWT/session、安全错误、持久任务轮询与SQLite短事务，不采用技能中的新项目脚手架、feature-first或新权限/实时框架。只改Java正常主线，不推送/部署/云调用或改旧服务数据与前端。

先补必须依赖：ImageOcr Interface以native与明确fixture两个Adapter共用同一ProcessImageParser/TSV实现，Optional.empty只代表合法完整无字结果，旧parse仍拒空。新增frame-local OCR Domain不包含公开page；ParsedImage仅在OCR Adapter内复用。VideoCompilation新增可选OCR产物，旧构造与fingerprint保留；OCR新compiler为v2并绑定OCR revision，每帧前后复验current/预算/版本。

随后一次接通真实consumer：v12 OCR sidecar完整写入/发布，原v10计数与旧schema迁移体保持；OCR完整计数加入现有publication和scope。公开ocr模式走AnswerService已有文字GroundingText流水线，独立trace引用不计作v11音画贡献；同generation混合命中先authority分类，再按证据kind处理。最后真实HTTP/FFmpeg/Tesseract验收、旧1299身份多重性、73 Node、格式/架构/双80%、独立审查及源码绑定。不能把必需Module成功写成视频OCR知识库交付。

Ownership：OCR worker负责ImageOcr Interface、TSV可空解析、ProcessImageParser共用实现及新测试；编译worker负责frame-local OCR Domain、VideoCompilation/VideoCompilationService及新测试；存储worker负责v12、Store/Ingestion/Indexing/Evidence Repository、OCR派生身份/trace Domain与新持久化测试；主代理负责Service授权/问答、HTTP/DTO/config、native acceptance、文档与唯一Maven。共享树不回退别人修改，旧测试保留，先具名RED/stub再GREEN，必要migration版本夹具适配逐项记录。

本轮交付后的非阻塞 backlog：独立字幕轨和未选中帧文字覆盖、真实中文 OCR/模型检索质量、摘要、网页播放器及生产；另记录伪造 Java 编译产物违反 v1/v2 OCR 对应关系时的 Service 错误码优化（现有持久化完整性约束已经拒绝，不为该分支扩展本轮主线）。配置与 native acceptance 分别复用了完成初始工作后的 OCR/编译代理，主代理仍为唯一 Maven 执行者。
