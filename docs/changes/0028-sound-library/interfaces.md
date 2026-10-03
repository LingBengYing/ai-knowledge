# 固定接口与唯一ownership

A pdf_config：SoundModels/GeminiSoundModels、SoundEmbeddingModels/GeminiSoundEmbeddingModels、SoundSettings/SoundConfiguration、RuntimeService/RuntimeConfiguration/application.properties、新Controller/DTO/VO/converters、SoundUploadServlet/有界query接线、wire-contract.md及Client/config/HTTP tests。不能改B domain/worker/schema或C repository/service。GeminiSoundModels.Configuration(Endpoint endpoint,String modelRevision,Duration deadline,int maxResponseBytes,boolean allowLoopbackHttp)；Embedding Configuration同现GoogleAudio形状，独立sound protocol/profile。固定API根、model合法单path且显式。

B backend_originals：下列全部Sound Domain、SoundCompilationService、SoundLibraryService、SoundIndexProtocol/Worker/ProcessSoundIndexer、AuthoritySchema/SqliteAuthorityStore v19/guards、RagApplication dispatch、必要旧migration fixtures及newtests。不能写C Repository/mgmt/query/proof或A HTTP/config。

C pdf_ingestion：SoundProofIdentity纯Domain identity（root批准的单独ownership）、SoundRepository、SoundAnswerService及scope/hydrate/proof/trace/source编排、ManagementRepository/ManagementService genuine sound接线、DocumentLifecycleRepository声音blob擦除、PersistenceConfiguration、新tests。不能改B Domain/schema/worker或Aconfig/controller。

root：前端mode/upload/build/typed来源/两个代理、真实Spring/native跨HTTP IT、工件/所有共享Maven/format/gates/bind/freeze。共享target只有root使用。

## A 小Interface

SoundModels.describe(AudioWaveform)->Description(String recallText)；draft(String fullQuestion,AudioWaveform)->Draft(boolean complete,List<String>claims)；verify(String fullQuestion,AudioWaveform,List<String>claims)->Verification(boolean complete,List<Boolean>supported)；revision()。record defensive immutable/redacted。音频内容/描述是数据，不执行指令。

SoundEmbeddingModels.embedAudio(byte[]canonicalWav),embedText(String fullQuestion),revision(),dimensions()。完整canonical WAV沿AudioWaveform/现校验，text≤4096UTF8；新profile绑定text+audio wire，不改旧GeminiAudioEmbeddingModels/protocol。

## B Domain

- SoundInputSpan(String id,int ordinal,AudioWaveform waveform)：唯一id、ordinal连续0起，全部waveform连续覆盖0..actual sampleCount。
- SoundSpan(String id,int ordinal,long startSample,long endSample,String pcmSha256,String recallText,String physicalSegmentId,String entrySha256)：recallText允许空，严格UTF8/无controls≤8192bytes，不是ASR；物理ID由新generation+span ID derived。
- SoundPublication(String id,String workspaceId,String documentId,String sourceRevisionId,String sourceSha256,String filename,String mediaType,long sizeBytes,String generationId,IndexTarget target,String soundModelRevision,String decoderRevision,int chunkSeconds,long sampleCount,List<SoundSpan>spans,String manifestSha256,String profileFingerprint,String createdAt)。窗口1..600、sampleCount1..9600000、完整连续samples/source/target。SoundProfile.fingerprint(target,soundRev,decoderRev,chunkSeconds)绑定全部profile、不含key。spans仅描述/hash，原file独立authority持有。
- SoundBuildClaim(Actor actor,DocumentOriginal original,IndexTarget target,String generationId,String soundModelRevision,String decoderRevision,int chunkSeconds,List<SoundInputSpan>spans,String profileFingerprint)。
- SoundReceipt(List<Entry>entries,VerifiedRevision verified)，Entry(String spanId,String physicalSegmentId,String recallText,List<Double>vector,String entrySha256)。重算每float32 entry及整组manifest，projection entry.text=pcmSha。
- SoundState(DocumentOriginal original,IndexTarget target,String profileFingerprint,SoundPublication publication nullable)。
- SoundScope(Actor actor,DocumentSelection selection,List<SoundPublication>publications)；SoundPublishedSpan(SoundPublication publication,SoundSpan span)。
- SoundProof(SoundPublishedSpan source,List<String>facts,String factsSha256,String proofSha256)。事实≤16条/各≤1024cp/总≤8192UTF8、无指令；必须完整问题独立verify；C helper明确绑定question SHA/sourcePCM/facts/model/policy，不能从描述证明。
- SoundTraceDraft(String questionSha256,String answerSha256,String status,String reasonCode,List<SoundProof>citations,String modelRevision,String policyRevision)；SoundTraceReceipt(String traceId,String status,String reasonCode)。
- SoundSource(String answerId,int ordinal,SoundProof proof,DocumentOriginal original)；SoundManagedEvidence(String sourceRevisionId,String publicationId nullable,int spanCount)，仅真正sound_originals注册资料。

B Service：SoundCompilationService(AudioDecoder,int chunkSeconds,Duration budget) implements AutoCloseable：revision(),decoderRevision(),chunkSeconds(),configurationCurrent(),decode(DocumentOriginal,BooleanSupplier)->List<SoundInputSpan>（一次actual decode完整windows），decodeQuery(String filename,String mime,byte[]source,BooleanSupplier)->List<AudioWaveform>（无ASR）。配置内部拥有ProcessAudioDecoder，不注册第二个AudioDecoder类型Bean导致旧ASR注入冲突。

ProcessSoundIndexer(GeminiSoundModels.Configuration,GeminiSoundEmbeddingModels.Configuration,MilvusRestProjection.Settings,Duration)，index(SoundBuildClaim)->SoundReceipt。独立40MiB请求/结果、parent/lifetime/collection lease/current取消/cleanup，不改旧协议。

SoundLibraryService(SqliteAuthorityStore,SoundRepository,ManagementRepository,DocumentPermissionPolicy,SoundCompilationService,IndexTarget,GeminiSoundModels.Configuration,GeminiSoundEmbeddingModels.Configuration,MilvusRestProjection.Settings,Duration,int maxConcurrent)：upload(Actor,String filename,String mime,byte[])->DocumentOriginal（AudioInput完整hash、documents+owner+sound_originals、0调用），get/build(Actor,String)->SoundState；target(),profileFingerprint(),configurationCurrent(),soundModelRevision()纯metadata getter（missing mapper不跨Client/Config层）。

## C Repository与query Service

SoundRepository(SqliteAuthorityStore)：insertOriginal(DocumentOriginal,String createdAt)；findOriginal(Actor,String)->Optional<DocumentOriginal>（旧corpus audio/new source/current ACL）；insertPublication(SoundPublication)；findPublication(DocumentOriginal,IndexTarget,String profile)->Optional<SoundPublication>；managedEvidence(String docId)->Optional<SoundManagedEvidence>；scope(Actor,DocumentSelection,IndexTarget,String profile)->SoundScope；hydrate(SoundScope,List<String>physicalIDs)->List<SoundPublishedSpan>（完整candidate映射，empty仍复验）；current(SoundScope,IndexTarget,String profile)->boolean；finish(SoundScope,SoundTraceDraft)->SoundTraceReceipt；source(Actor,String traceId,int ordinal,IndexTarget,String profile)->SoundSource。

SoundProofIdentity仅依赖Domain/JDK，提供length-prefixed proof digest/matches与UTF8 SHA；Tool.SoundProofBinding保留SourceFields/SourceInstructions事实指令策略及create并委托该纯identity。Repository仅调用Domain identity复验canonical facts/question/sourcePCM/model/policy绑定，不依赖Tool。

SQL只在Repository，调用者唯一store.transaction，不自开nested transaction。scope/current/source检查全部source/ACL/complete headers；initial scope仅metadata，不能把128份20MiB原file同时加载，逐个读取/校验保持内存有界。managedEvidence不要求旧任务。ManagementRepository.findDocumentOriginal兼容new sound_originals；列表genuine sound synthetic=false、ready/not_indexed直到完整sound publication，然后parsed/indexed/active source/pub，无旧tasks/can_index=false。

SoundAnswerService(store,repo,management,permissions,SoundCompilationService,SoundModels,SoundEmbeddingModels,RetrievalProjection,IndexTarget,String profile,Duration,int concurrency) implements AutoCloseable：answer(actor,AnswerCommand)->SoundAnswerResult；answerAttached(actor,AnswerCommand,List<QueryAttachment>)->SoundAttachmentAnswerResult；source(actor,traceId,ordinal)->SoundSourceResult；content(...)->DocumentOriginal；policy java-sound-answer-v1。A定义以下DTO，C调用固定DTO；可加SoundAssessment小Helper/Tool，不改合同。

## A DTO和HTTP精确形状

- SoundUploadResult：document_id,source_revision_id,source_sha256,size_bytes（4字段）。
- SoundIndexResult：status=missing|available,document_id,source_revision_id,source_sha256,profile_fingerprint,model_revision,embedding_model_revision,dimensions,publication_id nullable,generation_id nullable,manifest_sha256 nullable,span_count=0|N（12字段）。missing后三identity为null/count0；model_revision为SoundModels.revision()。
- SoundCitationResult：number,kind=sound_span,document_id,revision_id,source_sha256,publication_id,profile_fingerprint,decoder_revision,pcm_sha256,filename,media_type,start_sample,end_sample,sample_rate=16000,start_ms,end_ms,facts List<String>,facts_sha256,analysis_model_revision,policy_revision,time_precision=server_window,source_url,content_url（23字段）。source_url精确/v1/sound-sources/{answerId}/{ordinal}。无quote/伪ASR text_origin。
- SoundAnswerResult：answer_id,status=answered|abstained,answer,reason_code,citations,policy_revision（6字段；abstained reason非null/citations空）。SoundSourceResult(answer_id,citation)。SoundAttachmentAnswerResult同6字段追加mode=SOUND、attachment_manifest（8字段）。其列表用新SoundQueryManifestResult.from(QueryAttachmentManifest)映射：ordinal/source_sha256/media_kind固定audio/compiler_revision/content_sha256/text_code_points=0/visual_count=0/selected_image_sha256=[]/visual_sampled=false，精确9字段；prepared revision绑定decoder/chunk，content SHA绑定全部PCM，不截尾。整个输入准备未完成或scope前置拒绝时manifest=[]，只允许abstained；成功准备为全部附件ordinal连续完整列表，无部分manifest。A DTO ctor传List<SoundQueryManifestResult>，C使用from静态映射，旧AttachmentAnswerResult的result/query_attachments envelope不改。

## v19固定表/列（B schema/C SQL同合同）

- sound_originals(document_id PK FK documents,source_revision_id UNIQUE,source_sha256,filename,media_type,size_bytes,original_blob,created_at)。source与documents注册revision/SHA/MIME/typeaudio一致；metadata immutable，blob仅tombstone后可清空。
- sound_publications(id PK,workspace_id,document_id FK documents,source_revision_id,source_sha256,filename,media_type,size_bytes,generation_id UNIQUE,embedding_identity,projection_identity,embedding_model_revision,dimensions,sound_model_revision,decoder_revision,chunk_seconds,sample_count,span_count,manifest_sha256,profile_fingerprint,created_at)。new/legacy当前rawsource，entries先写header后seal、deferredFK。
- sound_spans(publication_id deferredFK,id,ordinal,start_sample,end_sample,pcm_sha256,recall_text,physical_segment_id UNIQUE,entry_sha256)。PK(pub,id)，unique(pub,ordinal)，immutable完整连续cover。
- sound_traces(id PK,workspace_id,actor_id,selection_all,scope_count,citation_count,question_sha256,answer_sha256,status,reason_code,model_revision,policy_revision,created_at)。
- sound_trace_documents(trace_id deferredFK,ordinal,publication_id FK sound_publications)。PK(trace,ordinal)，完整scope含uncited。
- sound_trace_evidence(trace_id deferredFK,ordinal,publication_id,span_id,facts_json,facts_sha256,proof_sha256)。PK(trace,ordinal)，span完整绑定及canonical facts JSON SHA应用核验。tracechild先写header后seal，引用≤32；所有history不可变。

format verify表/列/guards完整，v18→19先备份，不改v1–18语义。B如需调整SQL约束细节先发root/C，不自行更名。
