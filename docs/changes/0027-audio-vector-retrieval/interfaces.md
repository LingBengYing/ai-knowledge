# 固定接线与 ownership

A pdf_config：AudioEmbeddingModels/GeminiAudioEmbeddingModels/ModelHttpTransport固定Google入口；AudioEmbeddingConfiguration/Settings、Runtime能力、HTTP Controller/VO/mappers/application.properties及新tests、QueryAttachmentConfiguration的可选beans接线。不要修改其它查询Service/domain/SQL或共享Maven target。
B backend_originals：AudioWaveform/AudioVectorSpan/BuildClaim/Receipt/Entry/Publication/State domains、AudioVectorProtocol/Worker/ProcessAudioVectorIndexer、AudioVectorIndexingService、v18 AuthoritySchema/SqliteStore、RagApplication worker dispatch及全部必要旧schema fixtures/new tests。不写AudioVectorRepository/Evidence/Query模块。
C pdf_ingestion：AudioVectorRepository、AudioVectorScope、EvidenceService及AnswerService的AUDIO新路；AudioCompilationService新增保留同次PCM接口、AudioPreparedCompilation、PreparedQuery/QueryPreparationService/QueryAttachmentService（旧ctors保留）与新tests。PersistenceConfiguration repobean归C，不写A configs/domain(B所列)/schema。
root：前端/代理及tests、真实Spring/FFmpeg跨HTTP/native链、工件/docs、所有共享Maven/Spotless/完整验证/冻结。

固定Java：
- AudioWaveform(String sourceSha256,String decoderRevision,long startSample,long endSample,byte[] pcm)：16k mono s16le，start≥0/end≤9600000/end>start，pcm.length==2*(end-start)且≤960000；clone accessor，wav()/pcmSha256()，redacted。
- AudioVectorSpan(String audioEvidenceId,String basePhysicalSegmentId,int ordinal,long startMs,long endMs,AudioWaveform waveform)：B验证claim中的base source/decoder/id/physical/time映射，按原ordinal严格排序，不要求ordinal连续（静音未发布）。
- AudioVectorBuildClaim(Actor actor,PublicationVersion basePublication,IndexTarget target,String vectorGenerationId,String decoderRevision,List<AudioVectorSpan> spans)。
- AudioVectorReceipt(List<AudioVectorReceipt.Entry> entries,VerifiedRevision verified)，Entry(String physicalSegmentId,List<Double> vector,String entrySha256)；worker/root独立重算每entry和整组manifest。
- AudioVectorEntry(String audioEvidenceId,String basePhysicalSegmentId,String vectorPhysicalSegmentId,int ordinal,long startSample,long endSample,String pcmSha256,String entrySha256)。
- AudioVectorPublication(String id,PublicationVersion basePublication,IndexTarget target,String vectorGenerationId,String decoderRevision,List<AudioVectorEntry> entries,String manifestSha256,String createdAt)。
- AudioVectorState(PublicationVersion basePublication,IndexTarget target,AudioVectorPublication publication nullable)。
- AudioVectorRepository(SqliteAuthorityStore)，insert(AudioVectorPublication)，findPublications(String workspaceId,List<PublicationVersion>,IndexTarget)->List<AudioVectorPublication>。
- AudioVectorScope(EvidenceScope base,IndexTarget target,String decoderRevision,List<AudioVectorPublication> publications)。EvidenceService.audioVectorScope(scope,target,decoderRevision)、hydrateAudioVectors(vectorScope,List<String>vectorIDs)->List<PublishedAudioEvidence>原baseID；empty仍复验。finish旧3/4参数保留，新增finish(scope,TraceDraft,eligibility,ImageVectorScope nullable,AudioVectorScope nullable)在同一原trace事务复验两可选scope。
- AudioEmbeddingModels.embed(byte[]canonicalWav), revision(), dimensions(), decoderRevision()；GeminiAudioEmbeddingModels.Configuration(Endpoint endpoint,String modelRevision,int dimensions,String decoderRevision,Duration deadline,int maxResponseBytes,boolean allowLoopbackHttp)。Endpoint base为API根URL，model只能合法单path identifier，Googlemodel默认不猜、必须显式。
- AudioVectorIndexingService public ctor(store,AudioVectorRepository,EvidenceRepository,ManagementRepository,IngestionRepository,DocumentPermissionPolicy,IndexTarget textTarget,IndexTarget audioTarget,GeminiAudioEmbeddingModels.Configuration,MilvusRestProjection.Settings,AudioDecoder,Duration processingBudget,int maxConcurrent)，get/build(Actor,String)->AudioVectorState。ProcessAudioVectorIndexer(Configuration,MilvusSettings,Duration)，index(claim)->receipt。
- AudioPreparedCompilation(AudioCompilation compilation,List<AudioWaveform> waveforms)，AudioCompilationService.compileWithWaveforms(filename,mime,source,current)->AudioPreparedCompilation：同一次decoder/transcription，旧compile结果/revision保持。
- PreparedQuery原5参ctor保留，新增record最后字段List<AudioWaveform> queryAudio。旧无波形manifest原样，新完整波形有额外hash binding。QueryPreparationService原5参ctor保留，新6参boolean retainAudio；只开新module时保留PCM并使用新preparationRevision。
- QueryAttachmentService旧5/8参ctors保留，新11参追加AudioEmbeddingModels audioModels,RetrievalProjection audioProjection,IndexTarget audioTarget；usesAudioVectors(query)/audioTarget()/audioDecoderRevision()/searchAudio(query,authorized,current,Function<List<String>,List<String>>validatedBaseIDs)。

v18固定header列：id,publication_id,document_id,source_revision_id,source_sha256,vector_generation_id,embedding_identity,projection_identity,model_revision,dimensions,decoder_revision,manifest_sha256,segment_count,created_at。
entries列：audio_vector_publication_id,audio_evidence_id,base_physical_segment_id,vector_physical_segment_id,ordinal,start_sample,end_sample,pcm_sha256,entry_sha256。仅原声向量新表；foreign keys/不可变/完整唯一约束沿原规则。
