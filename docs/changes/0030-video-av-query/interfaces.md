# 冻结Interface与身份

`VideoAvCompilationService.compileQuery(QueryAttachment, BooleanSupplier): VideoAvCompilation`。与compile共用完整decode/校验/预算；临时revision=`query-`+SHA256(UTF8(sourceSHA+NUL+compilerRevision))，再复用VideoAvProfile.windowId(revision,ordinal)。原compile的资料窗口算法不改。public static `validateQueryInput(QueryAttachment)`提供完整envelope准入，内部使用VideoInput；Web通过此Service方法准入，不能直接依赖Tool。

`VideoAvQueryManifest` record按spec Q07的11字段camelCase，status为String；counts为Integer、audioPresent为Boolean，not_prepared时这四项和contentSha256均null。静态`prepared(int, VideoAvMode, String compilerRevision, VideoAvCompilation)`及`notPrepared(int, VideoAvMode, String compilerRevision, String sourceSha256)`。contentSHA=SHA256(UTF8("java-video-av-query-content-v1"+NUL+sourceSHA+NUL+compilerRevision+NUL+VideoAvProfile.windowManifestSha256(compilation)))。该window manifest已经绑定完整epoch/实际时间/全部媒体/absence。重复source也保留各请求ordinal。

`VideoAvQueryTrace(VideoAvMode mode,String questionSha256,String profileFingerprint,String embeddingRevision,List<VideoAvQueryManifest> attachments)`。preparationRevision固定`java-video-av-query-preparation-v1`。`manifestSha256()`使用DataOutputStream写每UTF8值的4-byte长度及bytes，依次：policy、mode、questionSHA、profileSHA、embeddingRevision、attachment count十进制；每件按Q07字段顺序写字符串（enum用name；null用固定`NULL`；boolean小写；计数十进制）。合法值不含该null哨兵歧义。整组status相同、compilerRevision相同、usedMode相同，ordinal连续。constructor校验1..3；toString redacted。

`VideoAvTraceDraft`新增nullable queryTrace最后一参并保留原八参构造委托null；父mode/question相同；answered必须prepared。`VideoAvRepository.finish(scope,draft)`在现事务保存sidecar；source验证sidecar，原trace合法。无需暴露SQL/额外测试API。

v21表名`video_av_query_preparations`：trace_id(主键，deferred FK video_av_traces)、attachment_count、mode、question_sha256、profile_fingerprint、embedding_revision、preparation_revision、manifest_sha256。`video_av_query_attachments`：trace_id(deferred FK header)、ordinal、source_sha256、media_kind、compiler_revision、content_sha256、window_count、visual_window_count、audio_window_count、audio_present、used_mode、status；(trace_id,ordinal)主键。prepared counts1..1201总窗、visual1..window_count、audio0..window_count，audio_present对应audio>0；AUDIO/JOINT必须audio>0。not_prepared四count/boolean/content全null。header完整组与parent绑定/immutability由新增guards保障，应用重新计算digest。旧v20定义不重写。

`VideoAvQueryCommand(VideoAvAnswerCommand answer,List<QueryAttachment> attachments)`仅纯domain验证VIDEO 1..3、20MiB及不可变输入；mapper调用compiler的validateQueryInput，compileQuery再次独立核验，层次为Web→Service→Tool，DTO不反向依赖Tool。`VideoAvQueryRequestMapper.attached(byte[])`沿独立strict JSON合同。`VideoAvAnswerService.answerAttached(Actor,VideoAvAnswerCommand,List<QueryAttachment>): VideoAvQueryAnswerResult`，同submit/admission/Processing；原answer结果不改。query result三个字段mode/result/queryAttachments，receipt DTO VideoAvQueryAttachmentResult.from按Q07 @JsonProperty，不经旧QueryAttachmentManifest。

新servlet bean `videoAvQueryServlet`由VideoAvConfiguration同实际graph注册，仅精确路径；RuntimeConfiguration实际bean presence发布新cap。不增加vendor接口/独立模型/credentials。
