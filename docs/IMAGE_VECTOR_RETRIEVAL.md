# 原图向量检索

已索引的真实 PNG/JPEG 可在资料详情显式“建立原图向量”。操作调用服务器配置的图片 embedding；默认关闭，不自动补建。GET读取保存状态，POST仅当前编辑者可用；同原图、发布及模型profile已有合格回执时不再次调用。失败不改变原文字索引、原文件、active版本或已有来源。

只有原图模式的查询附件具有实际queryImages且模块启用，才走独立图片dense召回。当前完整授权范围内每张库图都需当前profile回执；缺失时明确image_vector_required，不删掉缺失资料或扩大范围。普通文字/无参考图问答沿旧路径。每路candidate先在authority映射和完整回读后融合；图片embedding只召回，最后仍对库内原图进行draft/verify并形成原typed引用。来源重启回读不调用embedding。

## 配置

rag.image-embedding.enabled默认false。启用只接受development/test和字面127.0.0.1或::1，需已有indexing/visual/answers/query-attachments及附件原依赖。实际完整装配后才提供image_vector_retrieval能力；构造不联网。

独立属性：base-url、model、api-key、revision、dimensions、deadline-ms、max-response-bytes、allow-loopback-http；以及milvus.endpoint/token/database/collection/deadline-ms/max-response-bytes/allow-loopback-http。模型revision必须显式固定，dimensions为2..8192；collection必须java_image开头且不同于文字collection。密钥仅由原责任方私有环境提供，不写文档或源码。

processing-timeout-ms默认120000（10..120000），max-concurrent默认2（1..8）；同doc最多一个在途。前端精确POST独立180秒，GET保留普通期限。每次未命中已有回执的尝试产生新的UUID代次；隔离worker保留父存活/collection lease/清环境/有界输出，晚写只落未引用代次。

## HTTP与持久化

认证GET/POST /v1/documents/{id}/image-vector均无query/body。成功响应恰十字段：status、document_id、publication_id、source_revision_id、source_sha256、profile_fingerprint、model_revision、dimensions、vector_generation_id、manifest_sha256。missing的最后两字段null；available为不可变回执。profile_fingerprint绑定完整独立投影，model_revision为完整固定模型profile，不泄漏endpoint或凭据。

v17追加image_vector_publications；原v3文字IndexProtocol/8MiB预算及旧publication不变，原图单独走10MiB协议。v16升级前生成一次备份。回滚须由部署责任方配套恢复相应旧格式数据库及旧制品，不能只替换旧JAR读取新格式；开发任务不操作部署或旧数据。

模型协议依据：[SiliconFlow Embeddings API](https://api-docs.siliconflow.cn/docs/api/embeddings-post)。本Adapter发送单对象input.image完整canonical裸base64及float/dimensions，严格核对返回model、index、维度和finite非零float32，不发caption/URL或截断内容。

验收及源码绑定见[0026记录](changes/0026-image-vector-retrieval/verification.md)。本机协议夹具证明流程和边界，真实语义召回质量、云模型、网页和部署另验；真实ASR旧未通过项继续保留，usage/计费取消。
