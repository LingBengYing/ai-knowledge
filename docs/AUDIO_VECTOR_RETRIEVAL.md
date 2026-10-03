# 原声向量检索

已索引的真实音频可在资料详情显式“建立原声向量”。服务器重新解码保存的原文件，为全部已发布、可引用的非空语音分段建立独立向量；不重复 ASR，不自动补建。当前 reader 可读取状态，owner/editor 可显式建立。同一原 publication、来源 SHA、完整模型与投影 profile 已有合格回执时，直接返回，不再次解码或调用模型。失败不改写原文字索引、原文件、active 版本和已有来源。

实际使用步骤：先按原音频流程上传、解析并完成文字索引，在详情分别为准备检索的音频建立原声向量，再在音频问答模式附上参考音频并输入问题。当前完整 selected/all 范围内每份音频都必须有当前 profile 的完整回执；缺失或漂移返回 `audio_vector_required`，不会删掉缺失资料或扩大范围。参考音频沿同一次完整解码和 ASR 准备，保留每个 PCM 分段，包括静音分段，逐路检索后融合。普通文字模式和没有实际参考波形的问答继续使用既有路径。

波形仅用于召回库内语音分段。最终事实仍以原问题和保存的库内转录作证，引用继续绑定原 source SHA、`audio_span`、`machine_asr` 与 `server_chunk` 时间。打开来源可回读同版本原音频及 Range；重启后的来源读取不调用 embedding。非语音声音理解及保存转录之外的声学事实不在本切已完成范围，真实 ASR 质量仍需独立验收。

## 配置

`rag.audio-embedding.enabled` 默认 `false`。启用只接受 `rag.environment=development/test` 与字面 `server.address=127.0.0.1/::1`，要求已有 audio、ingestion、indexing、answers、query-attachments 及附件原依赖完整装配，不要求开启原图向量。公开 `GET /v1/config` 只有在实际服务和依赖均有效时才声明 `audio_vector_retrieval`；构造不会探测模型或 Milvus。

以下属性均以 `rag.audio-embedding.` 为前缀。endpoint、model、revision、dimensions 和 decoder-revision 必须显式提供，没有猜定的云模型默认值；私有凭据由原责任方通过私有运行环境提供，不写入源码或文档。

| 属性后缀 | 环境变量 | 默认值或约束 |
| --- | --- | --- |
| enabled | RAG_AUDIO_EMBEDDING_ENABLED | false |
| base-url | RAG_AUDIO_EMBEDDING_BASE_URL | API 根 URL，不能带模型路径、query 或 fragment |
| model | RAG_AUDIO_EMBEDDING_MODEL | 单个合法模型路径标识符 |
| api-key | RAG_AUDIO_EMBEDDING_API_KEY | 必填私有值 |
| revision | RAG_AUDIO_EMBEDDING_REVISION | 显式固定版本，拒绝 latest/default/unknown |
| dimensions | RAG_AUDIO_EMBEDDING_DIMENSIONS | 2..3072 |
| decoder-revision | RAG_AUDIO_EMBEDDING_DECODER_REVISION | 必须与实际 AudioDecoder.revision() 精确一致 |
| deadline-ms | RAG_AUDIO_EMBEDDING_DEADLINE_MS | 30000，范围 10..120000 |
| max-response-bytes | RAG_AUDIO_EMBEDDING_MAX_RESPONSE_BYTES | 4194304，范围 1024..4194304 |
| allow-loopback-http | RAG_AUDIO_EMBEDDING_ALLOW_LOOPBACK_HTTP | false；true 仅允许字面 loopback HTTP |
| processing-timeout-ms | RAG_AUDIO_EMBEDDING_PROCESSING_TIMEOUT_MS | 120000，范围 10..120000 |
| max-concurrent | RAG_AUDIO_EMBEDDING_MAX_CONCURRENT | 2，范围 1..8；同 document 最多一个在途 |
| milvus.endpoint | RAG_AUDIO_MILVUS_ENDPOINT | 独立投影的 API 根 URL |
| milvus.token | RAG_AUDIO_MILVUS_TOKEN | 私有值，沿原 Milvus 配置约束 |
| milvus.database | RAG_AUDIO_MILVUS_DATABASE | default |
| milvus.collection | RAG_AUDIO_MILVUS_COLLECTION | 必须以 java_audio 开头，且不同于文字及已启用图片集合 |
| milvus.deadline-ms | RAG_AUDIO_MILVUS_DEADLINE_MS | 30000 |
| milvus.max-response-bytes | RAG_AUDIO_MILVUS_MAX_RESPONSE_BYTES | 4194304 |
| milvus.allow-loopback-http | RAG_AUDIO_MILVUS_ALLOW_LOOPBACK_HTTP | false |

模型 profile 绑定协议、完整 PCM 策略、endpoint/model、显式模型版本、decoder revision 和维度，排除密钥与执行预算。独立 target 的 embeddingIdentity/modelRevision 均为 Client 完整 profile；投影另绑定独立 collection、维度及模型身份。配置或解码器变化后，旧回执不能冒充当前可用状态。

协议依据：[Google Gemini embeddings 指南](https://ai.google.dev/gemini-api/docs/embeddings)与 [models.embedContent REST](https://ai.google.dev/api/embeddings)。固定请求为 `POST /v1beta/models/{model}:embedContent`，使用 `x-goog-api-key`；body 仅含 `content.parts[0].inlineData` 的 `audio/wav` 和完整裸 canonical base64，以及 `embedContentConfig` 的 `outputDimensionality`、`autoTruncate=false`。每次仅发送完整 16 kHz、mono、s16le canonical WAV，1..480000 samples（最多 30 秒），不发转录、caption、URL 或 Files 引用。返回只接受规定 `embedding.values`、可选 `usageMetadata`，向量须指定维度、有限 float32 且非全零；未知或 soft-tensor 形态拒绝，不自动重试、重定向或截断。

## HTTP、费用与取消

认证 `GET /v1/documents/{id}/audio-vector` 读取状态，`POST` 同一路由显式建立；两者均无 query/body，使用当前认证及资料权限。成功响应恰十字段：`status`、`document_id`、`publication_id`、`source_revision_id`、`source_sha256`、`profile_fingerprint`、`model_revision`、`dimensions`、`vector_generation_id`、`manifest_sha256`。missing 的最后两字段为 null；available 的 manifest 绑定该 publication 的全部已发布 speech spans。`profile_fingerprint` 是完整独立投影身份，不泄漏 endpoint、key、PCM、向量或转录。

首次建立会对每个已发布语音分段调用一次 embedding；附参考音频提问会执行原 ASR 准备，并对全部实际查询波形逐段调用 embedding。缺回执时拒绝原声检索、不调用原声 embedding，但查询准备可能已完成 ASR。没有后台自动建立或失败自动重试；费用取决于实际服务、音频分段与请求，本文不估算价格。

前端两个代理只为精确、无 query/body 的建立 POST 保留 180 秒，GET 沿普通期限。用户“停止等待”不保证撤销服务器或上游工作，状态显示未知，必须显式刷新确认；不要据此立即重复建立。每次新尝试使用独立 UUID generation，隔离 worker 沿父存活、collection lease、清环境和有界输出规则；晚写仅留未引用代次，不能替换已发布证据。

## 持久化与迁移

v18 追加不可变 `audio_vector_publications` 与 `audio_vector_entries`。封存必须包含全部已发布 speech spans，逐项绑定 evidence/base physical/vector physical ID、原 sample 范围、PCM SHA、向量 digest 与完整 manifest；不接受成功前缀。解码及远程 embedding/投影工作在 authority 事务外，提交短事务复验当前 ACL、原 publication/source、profile 和完整回执。检索先按授权 workspace/document-vector generation 过滤，再取得 top-K；每路所有候选先由 authority 映射回原语音证据，才执行 RRF，最后原 trace 事务再次复验完整范围，包括未引用资料。

现有 v17 库升级前生成一次一致备份 `java-library.v17-before-v18-*.db`；`.partial` 不是完成备份。旧文字 v3 IndexProtocol/8 MiB 和既有 ImageVectorProtocol 保持，原声使用独立最多 40 MiB 协议。回滚由部署责任方配套恢复旧格式数据库和相应旧制品，不能仅换旧 JAR 读取 v18；本次开发没有操作旧数据或部署。

2026-10-03本机完整门禁和四项显式native已通过，最终输入与JAR绑定见 [0027 验证记录](changes/0027-audio-vector-retrieval/verification.md)。本机使用合成音频与 loopback 模型/Milvus，真实语义召回、真实 provider/Milvus、网页和发布另验；原声新功能尚未部署。页面由用户验收；新增真实调用为 0，usage/计费开发保持取消。
