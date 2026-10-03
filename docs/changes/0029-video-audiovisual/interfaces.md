# 接口与ownership

2026-10-03 FREEZE：六组实际frame-aligned native及16/16重复MP4 SHA已核；以下精确合同为三个backend lane共同依据。ownership按plan.md，不写他人共享文件；共享构建root串行。

## HTTP

- POST /v1/video-av-documents：仅application/octet-stream，唯一X-Filename为percent UTF8（decode一次，literal +保留），只四扩展mp4/mov/webm/mkv且完整magic准入，无query，1..20MiB。201精确document_id,source_revision_id,source_sha256,size_bytes四字段，0decoder/provider/ASR。
- GET/POST /v1/documents/{id}/video-av-index：无query/body，当前ACL；GET纯metadata，POST显式全组build，无自动重试。
- POST /v1/video-av-answers：application/json，无query；question完整≤4096UTF8，mode显式VISUAL|AUDIO|JOINT，document_ids省略为all/显式≤128selected（[]保留）。精确允许字段，不接受attachments或旧mode。
- GET /v1/video-av-sources/{answer_id}/{ordinal}与/content：无body/query，ordinal规范1..32；来源同actor/current完整scope；content同版本原视频200/单Range。

四能力video_av_upload,video_av_index,video_av_answers,video_av_sources仅真实Library+Answer与独立opt-in配置齐全才公开。不声明附件。

## Model seam

VideoAvModels.draft(String fullQuestion,VideoAvWindow window,VideoAvEpoch epoch,VideoAvMode mode)->Draft(boolean complete,List<Claim>)；Claim(String text,VideoAvRequirement requirement)。服务端qSHA+ordinal+claim+requirement产生共同Fact ID；独立verify(String fullQuestion,VideoAvWindow window,VideoAvEpoch epoch,VideoAvMode mode,List<VideoAvFact>facts)->Verification(boolean complete,List<Support>)，Support(String id,boolean supported,boolean visualContribution,boolean audioContribution)。revision()纯metadata。固定prompt/schema/static fps1/storefalse/14MiB serialize budget；exact wrapper与步骤合同见wire-contract。

VideoAvEmbeddingModels.embedText(String fullQuestion),embedVideo(VideoAvClip),embedAudio(AudioWaveform),revision(),dimensions()。每次唯一完整输入，三类型同显式模型/版本/维度/策略，两role的collections必须不同。Index只embed，0describe/ASR。模型与Domain对象immutable/redacted；Media与问题/事实都是数据。

VideoAvFact(String id,String text,VideoAvRequirement requirement,boolean visualContribution,boolean audioContribution)：唯一SHA ID、1..16facts、text≤1024codepoints/总text≤8192UTF8，无字段指令；canonical facts JSON精确固定五字段顺序id,text,requirement,visual_contribution,audio_contribution。贡献bool是独立模型判断，不是逐帧真实质量认证。

下列精确时间、Domain、DTO、SQL已冻结；变更先协调root，不实现猜测兼容壳。

## 实际媒体与时间记录

```java
record VideoAvEpoch(
    long sourceFirstPts,
    long sourceTimeBaseNumerator,
    long sourceTimeBaseDenominator,
    long ticksPerSecond) {}

record VideoAvFrameTiming(
    int sourceOrdinal,
    long localTick,
    long durationTick,
    int width,
    int height,
    String pixelSha256) {}

record VideoAvClip(
    byte[] content,
    String sha256,
    long firstLocalTick,
    long endLocalTick,
    List<VideoAvFrameTiming> frames,
    String framesManifestSha256) {}

record VideoAvWindow(
    String id,
    int ordinal,
    long startTick,
    long endTick,
    VideoAvClip video,
    AudioWaveform audio) {}

record VideoAvCompilation(
    String sourceSha256,
    String decoderRevision,
    VideoAvEpoch epoch,
    long durationTick,
    boolean hasAudio,
    List<VideoAvWindow> windows) {}
```

- 约分 source video timebase 后，`L = lcm(reduced denominator, 16000)`。`ticksPerSecond=L`，source 视频相对 tick 为 `(pts-firstPts)*numerator*(L/denominator)`。音频样本 i 对应 `i*(L/16000)`。BigInteger 计算后 exact 转 long，拒绝溢出；原 firstPts/num/den 全部保留，`timelineOriginUs()` 仅派生 floor 展示值，不作为精确 epoch 身份。wire 所有 long 时间值用 canonical decimal string。
- windows 从 tick 0 连续 partition 到完整 `durationTick`；ordinal 连续、独立 MAX_WINDOWS=1201，不继承旧600。视频部分从真实下一帧 PTS 选取 ≤`chunkSeconds` 的下一边界（配置 1..30，默认 30）。不以 FPS 或 `-t` 假设切点。音轨尾在视频终点之后继续纯 audio 窗，最多 chunkSeconds≤30 秒一窗。
- video/audio 可以缺一边，但不能同时为空。`hasAudio=false` 表示原文件无音轨；原轨真实静音 PCM 仍是存在的 audio。音轨结束后的窗口 audio 为 null，不补造尾部静音。原解码器按 video epoch 保留的延迟前置零样本仍属于真实对齐 PCM。
- `video.firstLocalTick/endLocalTick` 与每帧 localTick 为 window-local；parent tick=`window.startTick+localTick`。必须保持第一真实帧 local offset，不用 `PTS-STARTPTS` 吞掉 gap。`endLocalTick` 是实际最后帧 PTS+duration，不假称整个 parent window 都有画面。所有实际源帧按 sourceOrdinal 保留一次，不删帧、重新排序、resize 或以新帧代替运动。
- `audio.startSample/endSample` 是同 source epoch 的全局实际 16k 样本编号，不是 WAV 的局部零起点。边界=`floor(tick*16000/L)`，并裁到实际 decoded PCM 总样本数；所有非空片段连续覆盖原完整 PCM。WAV 为现有 canonical 44-byte header/16k/mono/S16LE，`wavSha256()` 可从 waveform.wav() 派生。音频第一样本相对窗口 offset 为 `startSample*(L/16000)-startTick`，须在模型 prompt 中明示，不能把两种附件各自零时轴当成精确同步。
- MP4 为 visual-only，去掉原音轨；原 PCM 单独供实际声音 embedding/proof。clip 重新 ffprobe，必须核对全部 local PTS/duration/数量/尺寸及父映射。SHA/manifest/完整 metadata 都由服务器生成，不来自模型。
- 单 clip ≤8MiB、所有 clips 合计 ≤64MiB、完整 PCM ≤19.2MiB、原 source ≤20MiB/600秒、每窗 media 实际覆盖 ≤chunkSeconds≤30秒。超过即整个失败，不删帧或截音。本机 prototype 不认证任意 codec / 像素格式 / 超限输入。
- root 已确认本切没有新 preview/frame endpoint，也不抽取额外 PNG。旧已封存选帧路径保持原行为；新 compilation 的 clips≤64MiB+实际PCM≤19.2MiB，两类媒体 payload 合计≤83.2MiB。原件、frame metadata、防御性复制和 IPC 序列化另占内存；该 payload 上限不是父JVM峰值或已验证的一般容量结论。
- `VideoAvClip.frameCount()` 派生 frames.size；所有 byte[] 防御性复制、容器 immutable、toString 脱敏。framesManifest 长度前缀绑定 source ordinal、local pts、duration、dimensions、实际 pixel SHA 和完整实际顺序；clip SHA 绑定真实 MP4 原字节。推荐 FFmpeg framehash 的有界逐帧摘要，不在 Java 保存全帧 PNG/全 rawvideo。原帧和切片重解码使用同一 pinned pixel policy，全部像素SHA必须逐项相等；不能只凭编码参数 `qp=0` 声称无损。当前 native 只实证 YUV420p 源/输出逐帧原平面相等，其他像素格式未实证。

## 持久 metadata / publication / state

```java
record VideoAvVideoMetadata(
    String clipSha256,
    int frameCount,
    String framesManifestSha256,
    long firstLocalTick,
    long endLocalTick) {}

record VideoAvAudioMetadata(
    String pcmSha256,
    String wavSha256,
    long startSample,
    long endSample,
    int sampleRate) {}

record VideoAvPublishedWindow(
    String id,
    int ordinal,
    long startTick,
    long endTick,
    VideoAvVideoMetadata video,
    VideoAvAudioMetadata audio,
    String visualPhysicalId,
    String visualEntrySha256,
    String audioPhysicalId,
    String audioEntrySha256) {}

record VideoAvRouteReceipt(
    VideoAvRoute route,
    int count,
    String manifestSha256,
    VerifiedRevision verified) {}

enum VideoAvRoute { VISUAL, AUDIO }

record VideoAvPublication(
    String id,
    String workspaceId,
    String documentId,
    String sourceRevisionId,
    String sourceSha256,
    String filename,
    String mediaType,
    long sizeBytes,
    VideoAvEpoch epoch,
    long durationTick,
    boolean hasAudio,
    String decoderRevision,
    String analysisModelRevision,
    String profileFingerprint,
    IndexTarget visualTarget,
    IndexTarget audioTarget,
    int chunkSeconds,
    String windowManifestSha256,
    List<VideoAvPublishedWindow> windows,
    VideoAvRouteReceipt visualReceipt,
    VideoAvRouteReceipt audioReceipt,
    long createdAtMs) {}

record VideoAvState(
    DocumentOriginal original,
    IndexTarget visualTarget,
    IndexTarget audioTarget,
    VideoAvPublication publication) {}

record VideoAvTargets(IndexTarget visual, IndexTarget audio) {}

record VideoAvEvidence(
    VideoAvPublication publication,
    VideoAvPublishedWindow window) {}

enum VideoAvMode { VISUAL, AUDIO, JOINT }
enum VideoAvRequirement { VISUAL, AUDIO, JOINT }

record VideoAvFact(
    String id,
    String text,
    VideoAvRequirement requirement,
    boolean visualContribution,
    boolean audioContribution) {}

record VideoAvProof(
    VideoAvEvidence evidence,
    VideoAvMode mode,
    List<VideoAvFact> facts,
    String factsSha256,
    String proofSha256) {}

record VideoAvTraceDraft(
    String questionSha256,
    String answerSha256,
    String status,
    String reasonCode,
    VideoAvMode mode,
    List<VideoAvProof> citations,
    String modelRevision,
    String policyRevision) {}

record VideoAvScope(
    Actor actor,
    DocumentSelection selection,
    List<VideoAvPublication> publications) {}
```

两路 receipt 永远非 null。count>0 必须有完整同 workspace/doc/gen/target VerifiedRevision；count=0 必须 verified=null、具带 `ABSENT` 标签的摘要（source/gen/target/精确 epoch/window manifest绑定），不能把“该路原不存在”当“尚未构建”。每个 published window 的 route physicalId/entrySHA 只在对应 media 存在时非 null。windowCount / videoWindowCount / audioWindowCount 可由完整 windows 派生，不在构造中接受不可信冗余 count。publication.id 用作两个独立 collection 共享的全新 UUID generation，不能重用旧索引身份。

对应 v20 列可直接 flatten：epoch=`source_first_pts/source_time_base_num/source_time_base_den/ticks_per_second`；header=`duration_tick/has_audio/decoder_revision/analysis_model_revision/profile_fingerprint/chunk_seconds/window_manifest_sha256`；window=`ordinal/start_tick/end_tick`、5个 video nullable 列、5个 audio nullable 列、两组 physical/entrySHA。route receipt 有显式 count/manifest/是否存在；C 的 Repository 负责 SQL，root 最终 interfaces 决定正式列名，B 不先写数据库。

## 编译与 worker seam

```java
interface VideoAvDecoder extends AutoCloseable {
  String revision();
  VideoAvCompilation decode(String filename, String mediaType, byte[] source);
  void close();
}
```

新 `ProcessVideoAvDecoder(Path ffmpeg, Path ffprobe, Duration deadline, int chunkSeconds)` 使用原 `NativeMediaSession` 的有界 admission、空 child env、executable hash、取消/reap/cleanup；实际 timeline 使用内部完整 frames，不扩大旧 `DecodedVideo` 选帧 Interface。独立 profile `java-video-av-decoder-v1:<sha256>` 绑定 ffmpeg/ffprobe bytes、chunkSeconds、actual rational epoch/frame-partition、alignment、lossless codec/pixel policy、重新 probe 对账策略和预算。旧 decoder constructors、revision、返回类型及 byte protocol 不改。

新 CompilationService 薄包该 Module，提供 revision()/decoderRevision()/configurationCurrent()/compile(DocumentOriginal, callback)；一次原文件 decode，0ASR/0caption/0describe。本切没有视频 query preparation 入口。所有 windows 只保留实际媒体，两路 indexer 对非空 media 分别 embedding/upsert/verify；父独立验证所有 vector entries/两路 manifest和 absence 身份，再短事务复验全source/ACL/profile提交。新 IPC ≤128MiB、child heap512MiB、全构建≤120秒/最多2；全组 bytes 本切有界，query 按文档依次重compile并比对全部manifest，不持久缓存128份 compilation。


## v1支持边界及纯Domain规则

MAX_WINDOWS=1201；帧实际区间必须连续不重叠，单帧hold≤chunkSeconds且greedy真实帧边界可切，否则unsupported整组失败。本native已验证YUV420p；其他格式只有实际原像素SHA逐帧与MP4解码全帧相同才允许成功，不自动改pixel format、resize、FPS或有损策略。VideoAvFrameTiming.pixelSha256为实际有界framehash的完整frame SHA256，包含在framesManifest。整个组≤600秒、clips≤64MiB；无preview/PNG/帧endpoint。最终新 Native 已实际验证 time-base denominator 30000、L=240000 的非零epoch及PCM延迟，见verification.md；其他L、其他pixel formats与一般容量不从这些夹具扩大认证。音轨早于首视频epoch明确unsupported，不裁掉真实前部样本。

VideoAvProfile纯Domain负责sourceRevision+ordinal窗口ID、generation+route+window physical ID、完整window组manifest、role ABSENT identity、target/model/decoder/chunk完整profile。VideoAvPublication.manifestSha256()派生完整digest绑定父原件、epoch、windowManifest、两target和两receipt（含absence），Index DTO manifest_sha256取该整体值；不是windowManifest别名。publication.id即全新UUID generation。

## 最终DTO tuple（已冻结nested版本）

下面顺序同时作为record构造顺序。wire全部snake_case；Java accessor用camelCase。类型名/Domain mapper accessor已由root确认，字段不再混旧flat稿。

**UploadResult 4**：`VideoAvUploadResult(String documentId,String sourceRevisionId,String sourceSha256,long sizeBytes)`。

**IndexResult 14**：`VideoAvIndexResult(String status,String documentId,String sourceRevisionId,String sourceSha256,String profileFingerprint,String modelRevision,String embeddingModelRevision,int dimensions,String publicationId,String generationId,String manifestSha256,int windowCount,int videoWindowCount,int audioWindowCount)`。

wire依次为 status/document_id/source_revision_id/source_sha256/profile_fingerprint/model_revision/embedding_model_revision/dimensions/publication_id/generation_id/manifest_sha256/window_count/video_window_count/audio_window_count。status missing|available；missing后三identity=null、三个count=0；available完整publication并可有某一路count=0（absence按B新合同，不伪造旧空RevisionManifest）。modelRevision为理解profile revision，embeddingModelRevision为embedding完整profile revision。

**AnswerCommand**：`VideoAvAnswerCommand(AnswerCommand answer,VideoAvMode mode)`。HTTP mapper只对外输入question/mode/document_ids；沿原AnswerRequestMapper建立AnswerCommand。

**AnswerResult 7**：`VideoAvAnswerResult(String answerId,String status,String mode,String answer,String reasonCode,List<VideoAvCitationResult> citations,String policyRevision)`。wire为 answer_id/status/mode/answer/reason_code/citations/policy_revision；answered|abstained，mode精确三枚举；拒答没有citations且有reason_code。不存在新附件manifest。

**FactResult 5**：`VideoAvFactResult(String id,String text,String requirement,boolean visualContribution,boolean audioContribution)`。wire id/text/requirement/visual_contribution/audio_contribution。id为服务端稳定SHA身份，requirement精确三枚举。公开facts仅已验证事实，不附verify过程的supported或额外模型描述。

**EpochResult 4**：`VideoAvEpochResult(String pts,String timeBaseNum,String timeBaseDen,String ticksPerSecond)`。wire pts/time_base_num/time_base_den/ticks_per_second，**全部canonical十进制字符串**。保留原始epoch PTS和约分timebase，不能用floor timelineOriginUs替换；L共同轴由B定义。

**VideoResult 5**：`VideoAvVideoResult(String clipSha256,int frameCount,String framesManifestSha256,String firstLocalTick,String endLocalTick)`。wire clip_sha256/frame_count/frames_manifest_sha256/first_local_tick/end_local_tick。local tick为canonical字符串，父坐标=window.startTick+localTick；真实完整连续clip的帧manifest，不是旧选帧。

**AudioResult 5**：`VideoAvAudioResult(String pcmSha256,String wavSha256,String startSample,String endSample,int sampleRate)`。wire pcm_sha256/wav_sha256/start_sample/end_sample/sample_rate。samples为canonical十进制字符串，sampleRate固定16000。WAV SHA是实际完整canonical WAV bytes，PCM SHA仅样本；双方不得混用。

**WindowResult 8**：`VideoAvWindowResult(String id,int ordinal,String startTick,String endTick,long startMs,long endMs,VideoAvVideoResult video,VideoAvAudioResult audio)`。wire id/ordinal/start_tick/end_tick/start_ms/end_ms/video/audio。tick是canonical十进制字符串，ms为安全有界number（0..600000），严格floor(startTick*1000/L)/ceil(endTick*1000/L)。video/audio各可null但不可同时null；**不另加presence布尔**。

**CitationResult 20**：`VideoAvCitationResult(int number,String kind,String mode,String documentId,String revisionId,String sourceSha256,String publicationId,String profileFingerprint,String decoderRevision,String filename,String mediaType,VideoAvEpochResult epoch,VideoAvWindowResult window,List<VideoAvFactResult> facts,String factsSha256,String analysisModelRevision,String policyRevision,String timePrecision,String sourceUrl,String contentUrl)`。

wire number/kind/mode/document_id/revision_id/source_sha256/publication_id/profile_fingerprint/decoder_revision/filename/media_type/epoch/window/facts/facts_sha256/analysis_model_revision/policy_revision/time_precision/source_url/content_url。kind固定video_av_window，time_precision固定server_window，mode与Answer一致；URL严格新source路径；content为完整原件。facts SHA按纯Domain固定canonical五字段列表计算，不按展示时的JSON任意格式。

**SourceResult 2**：`VideoAvSourceResult(String answerId,VideoAvCitationResult citation)`，wire answer_id/citation。

所有List defensive copy、toString脱敏；upload/index由ResponseMapper转换；Citation/Source由C Service创建安全DTO，Domain long→canonicalString及窗口ms按冻结精确公式转换（不反向调用Web），不让Controller跨到Client/Config读材料。Controller向Library取得profile/model metadata纯accessor；private配置不进DTO。


## 固定Service / Worker / Repository seam

VideoAvCompilationService(VideoAvDecoder decoder,int chunkSeconds,Duration budget) implements AutoCloseable：revision(),decoderRevision(),chunkSeconds(),configurationCurrent(),compile(DocumentOriginal,BooleanSupplier)->VideoAvCompilation。compiler owned新decoder，旧VideoDecoder Bean不变。callback每次阶段current/预算；完整原source identity和全窗验证，不对外暴露native临时目录。

VideoAvBuildClaim(Actor actor,DocumentOriginal original,VideoAvTargets targets,String generationId,String analysisModelRevision,String decoderRevision,int chunkSeconds,VideoAvCompilation compilation,String profileFingerprint)。VideoAvReceipt(List<Entry>entries,VideoAvRouteReceipt visualReceipt,VideoAvRouteReceipt audioReceipt)，Entry(String windowId,VideoAvRoute route,String physicalSegmentId,List<Double>vector,String entrySha256)。entry.text为该route clipSHA或PCM SHA；父重算float32全部entries/两manifest+absent，不相信worker只报success。ProcessVideoAvIndexer(GeminiVideoAvEmbeddingModels.Configuration,MilvusRestProjection.Settings visualProjection,MilvusRestProjection.Settings audioProjection,Duration budget)；index(VideoAvBuildClaim)->VideoAvReceipt。新IPC128MiB、child512MiB，parent identity/watchdog/collection leases/退出清理；无describe模型配置。

VideoAvLibraryService(SqliteAuthorityStore,VideoAvRepository,ManagementRepository,DocumentPermissionPolicy,VideoAvCompilationService,VideoAvTargets,String analysisModelRevision,GeminiVideoAvEmbeddingModels.Configuration,MilvusRestProjection.Settings visualProjection,MilvusRestProjection.Settings audioProjection,Duration budget,int maxConcurrent) implements AutoCloseable：upload(Actor,String filename,String mime,byte[])->DocumentOriginal；get/build(Actor,String)->VideoAvState；targets(),profileFingerprint(),configurationCurrent(),analysisModelRevision()纯metadata。缺状态mapper从Library取得profile，不跨Client/Config。保留完整写权限复验/幂等/同资料单build。

VideoAvRepository(SqliteAuthorityStore)：insertOriginal(DocumentOriginal,String createdAt)；findOriginal(Actor,String)->Optional<DocumentOriginal>（旧genuine corpus或newraw）；insertPublication(VideoAvPublication)；findPublication(DocumentOriginal,VideoAvTargets,String profile)->Optional<VideoAvPublication>；managedEvidence(String docId)->Optional<VideoAvManagedEvidence>；scope(Actor,DocumentSelection,VideoAvTargets,String profile)->VideoAvScope；hydrate(VideoAvScope,VideoAvRoute,List<String>physicalIDs)->List<VideoAvEvidence>；current(VideoAvScope,VideoAvTargets,String profile)->boolean；finish(VideoAvScope,VideoAvTraceDraft)->VideoAvTraceReceipt；source(Actor,String traceId,int ordinal,VideoAvTargets,String profile)->VideoAvSource。

VideoAvManagedEvidence(String sourceRevisionId,String publicationId nullable,int windowCount)仅newraw管理资料。VideoAvTraceReceipt(String traceId,String status,String reasonCode)。VideoAvSource(String answerId,int ordinal,VideoAvProof proof,DocumentOriginal original)。Repository纯SQL行映射，不开nested事务；Service唯一store.transaction边界，外部操作事务外。

VideoAvAnswerService(SqliteAuthorityStore,VideoAvRepository,ManagementRepository,DocumentPermissionPolicy,VideoAvCompilationService,VideoAvModels,VideoAvEmbeddingModels,RetrievalProjection visualProjection,RetrievalProjection audioProjection,VideoAvTargets,String profile,Duration budget,int concurrency) implements AutoCloseable：answer(Actor,VideoAvAnswerCommand)->VideoAvAnswerResult；source(Actor,String,int)->VideoAvSourceResult；content(...)->DocumentOriginal。policy固定java-video-av-answer-v1。

C独占VideoAvProofIdentity纯Domain/JDK（stableFactId、canonicalFactsJson/facts SHA、whole proof digest/matches与questionSHA）。Tool.VideoAvProofBinding只加SourceFields/SourceInstructions拒绝字段指令和mode共同facts支持策略，委托Domain identity；Repository不能依赖Tool。draft候选Fact贡献初始false，verify返回支持bool后新建verifiedFact，不修改事实文本/requirement/身份。

## 固定v20表列与封存

- video_av_originals(document_id,source_revision_id,source_sha256,filename,media_type,size_bytes,original_blob,created_at)。document_id PK/currentdocuments identity+video MIME；唯一source_revision_id；metadata immutable，blob只在tombstone已存在时可擦x''。
- video_av_publications(id,workspace_id,document_id,source_revision_id,source_sha256,filename,media_type,size_bytes,source_first_pts,source_time_base_num,source_time_base_den,ticks_per_second,duration_tick,has_audio,decoder_revision,analysis_model_revision,profile_fingerprint,visual_embedding_identity,visual_projection_identity,visual_model_revision,visual_dimensions,audio_embedding_identity,audio_projection_identity,audio_model_revision,audio_dimensions,chunk_seconds,window_count,window_manifest_sha256,visual_entry_count,visual_manifest_sha256,audio_entry_count,audio_manifest_sha256,created_at_ms)。id即generation；两个*_model_revision为各target embedding model revision。count0构造absent receipt/verified=null；count>0为actual verified完整manifest。
- video_av_windows(publication_id,id,ordinal,start_tick,end_tick,clip_sha256,frame_count,frames_manifest_sha256,first_local_tick,end_local_tick,pcm_sha256,wav_sha256,audio_start_sample,audio_end_sample,sample_rate,visual_physical_id,visual_entry_sha256,audio_physical_id,audio_entry_sha256)。publication延迟FK；PK(pub,id)/unique(pub,ordinal)；video5+对应physical/digest全null或全非null；audio同理，不可双null。无派生媒体blob。
- video_av_traces(id,workspace_id,actor_id,selection_all,scope_count,citation_count,mode,question_sha256,answer_sha256,status,reason_code,model_revision,policy_revision,created_at)。mode非null；scope≤128，citation≤32，状态/原因/数量shape严格。
- video_av_trace_documents(trace_id,ordinal,publication_id)。trace延迟FK；ordinal从0连续，完整scope含uncited。
- video_av_trace_evidence(trace_id,ordinal,publication_id,window_id,facts_json,facts_sha256,proof_sha256)。trace延迟FK；ordinal从1连续；window FK；fact JSON与SHA/proof由纯Domain重新核。

children先插header后seal。Service在短authority事务内复验当前ACL、完整原件字节/SHA及profile；纯Domain重算完整window、双路manifest/absence及profile身份。SQL核当前父workspace/source元数据、数量/ordinal/连续range、真实路条目数量与封存形状；trace封存另核保存scope的当前ACL。SQL不独立计算原件SHA或profile摘要，也不替代Service的写权限检查。全部metadata/publication/windows/tracehistory防update/delete/replace和封存后追加。format verify新表/列/guards完整，v19→20先backup，不改v1–19语义或旧算法；旧migration fixtures只做版本必要适配，保留全部断言。

冻结后窄接口修正：A发现Window不含epoch/L，因此Model两个方法显式增加VideoAvEpoch epoch。C直接传已对账publication epoch；禁止假设L=16000或由音频反推。未改变持久/window/IPC形状。
