# 原图向量合同

- IV-01：独立默认关闭rag.image-embedding.enabled，完整装配仅development/test和字面127.0.0.1/::1；依赖既有indexing/visual/answers/query-attachments，不放宽附件原依赖。配置使用独立模型、显式版本、维度、deadline/响应限额和独立java_图片collection，不混入文字向量空间。能力image_vector_retrieval仅实际构建和检索装配后声明；启动零联网。
- IV-02：ImageEmbeddingModels小Interface：embed(VisualImage)->不可变List<Double>，revision()，dimensions()。供应商官方POST /v1/embeddings发送model、input单对象{image:完整canonical裸base64}、encoding_format=float、dimensions；仅单原PNG/JPEG、1..10MiB。不发送URL、caption、data URI、user或truncate。严格核对object=list、精确model、data唯一object=embedding/index整数0、指定维度、有限float32及非全零。复用现有有界HTTP，无重定向/重试，上游错误脱敏。profile绑定协议、原字节策略、endpoint/model/显式版本/维度，排除密钥。
- IV-03：认证精确GET/POST /v1/documents/{id}/image-vector，均无query/body。GET当前reader可读，POST必须当前owner/editor及完整已发布原图/当前文字target。只为该doc建立向量，不启动通用重索引。构建默认120秒、范围10..120000ms，全局默认2在途（1..8），同doc同时仅一个。复用隔离worker/父存活/collection lease，不继承私有环境；原v3文字IndexProtocol和8MiB上限原字节保持，独立ImageVectorProtocol处理单原图。
- IV-04：构建冻结base publication、source revision/SHA、原图evidenceID及base physicalID和当前image target；每次全新UUID generation，vector physicalID=现有physicalSegmentId(generation,evidenceID)。原图向量条目text仅source SHA，不用caption/BM25假冒图向量。远程embed/init/upsert/完整verify在事务外；提交短事务重验原publication、原字节SHA、完整选中资格、当前编辑权限和image profile，重算唯一entry manifest及receipt后封存。缺/多/错target、SHA、映射、manifest/count均拒绝。失败不发布，晚写只留在未引用代次。
- IV-05：v17追加不可变image_vector_publications，绑定原publication、source、image evidence、base physicalID与独立vector generation/physicalID、target/profile、entry/manifest SHA。旧表、文字publication/active、来源/审计历史不重写。当前同profile已有合法receipt则显式构建可直接回读，不再次调用模型；新profile显式新建。无后台自动构建、无新持久任务。迁移保留既有backup/失败回滚规则。
- IV-06：GET/POST成功JSON为status(missing/available)、document_id、publication_id、source_revision_id、source_sha256、profile_fingerprint、model_revision、dimensions、vector_generation_id、manifest_sha256十字段；missing最后两字段null。无原图字节、caption、私有endpoint/key。结果/toString与错误脱敏。取消只停止等待，不保证撤回远端，但独立代次保证失败/晚写不覆盖已封存向量。
- IV-07：只有IMAGE模式且存在prepared queryImages并开启模块，走原图向量召回。仍先冻结原完整selected/all scope；对scope内全部图片publication核对对应当前profile receipt，缺或漂移明确拒绝并提示建立原图向量，不能静默删资料或回退全库。普通无参考图/文字模式继续旧路径。
- IV-08：查询每张原图真实embed，独立图片projection执行DENSE_ONLY，授权workspace/doc-generation过滤先于top-K。每路全部candidate先由authority按vector ID映射到base physicalID并完整hydrate，再RRF融合最多64；不得用COSINE原始值冒充旧非负融合分数。旧RetrievalProjection.Query四参默认HYBRID，旧搜索行为/Schema不变。
- IV-09：ImageVectorScope绑定原EvidenceScope、image target及全部不可变receipt；每次模型/投影前后复验，最终finish在原trace事务内复验整组，包括未引用资料。候选只是召回，查询图/向量/caption不成为证明；原库图draft/verify、完整原问题、typed来源与原图SHA回读保持。重启来源回读不再调用向量模型。
- IV-10：前端0015详情在实际能力、已indexed图片及编辑权限下提供显式“建立原图向量”，显示当前profile结果；保留整理草稿，忙碌防重复，不自动重试。换资料/身份/离页使迟到结果失效；请求180秒，仅精确POST独享期限。参考图附件沿现有流程，原图模式提示先建立范围中图片向量，无新附件格式或问题放宽。

必要验收：新旧已发布合成图显式build；相同/误导caption使旧文字召回漏目标，而真实原图字节向量支路找回；完整scope/资格→匹配→原图事实证明→正确库内来源/完整SHA→重启零embedding回读。另验profile变化、无向量、未授权、失败不发布与独立generation晚写，旧文字/附件/索引回归。真实语义召回质量、云模型与浏览器/部署另验。

协议依据（2026-10-03只读核对）：[SiliconFlow Embeddings API](https://api-docs.siliconflow.cn/docs/api/embeddings-post)。官方允许image URL或base64；本Adapter限定原始base64及单对象，模型可配置，不假定旧视觉chat模型具有embedding能力。
