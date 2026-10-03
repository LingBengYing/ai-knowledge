# Spec

1. `GET /v1/documents/{documentId}/original` 返回当前有权读取且保存了原文件的资料元数据：`document_id`、`revision_id`（原文件 initial revision）、`filename`、`document_type`、`media_type`、`source_sha256`、`size_bytes`、`content_url`。精确内容 URL 为 `/v1/documents/{documentId}/revisions/{revisionId}/content`。
2. 内容 GET 必须在 authority 事务中验证当前组织、ACL、未撤回状态、initial revision、文档源 hash/大小及存储原字节 SHA；仅返回原文件，1..20MiB。不存在/无权/撤回/版本不符/无真实原文件拒绝，不能回退其他版本、投影文本或合成记录。
3. 文档 PDF/TXT/MD、图片、音频、视频使用保存的真实 MIME；私有 no-store/nosniff 响应。当前所有管理配置广告 `document_originals`；读取不依赖 answers/ingestion/indexing 开关，不新增模型、索引任务或 schema。
4. 保持薄 HTTP Adapter、现有组织身份与权限 Module。只新增正常链及必要授权/版本/损坏负例，不启动全面评测、付费调用或新检查任务。
