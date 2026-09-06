# HTTP API：当前资料管理契约

0003额外提供显式启用的本机文本摄取接口：[TEXT_INGESTION](TEXT_INGESTION.md)。原始文件POST不是JSON；任务status/cancel/retry无body。默认关闭时保持下方管理基线config，启用后migration_stage=text_ingestion并增加text_upload/ingestions能力，问答/来源仍不可用。

本文只记录当前代码已映射的端点。基础 URL 使用本地配置的 scheme/host/port，默认端口 `18084`；请求与响应使用 JSON。源码依据：[ManagementController](../src/main/java/com/evidence/rag/management/ManagementController.java)、[ManagementModule](../src/main/java/com/evidence/rag/management/ManagementModule.java)、[RuntimeController](../src/main/java/com/evidence/rag/web/RuntimeController.java)、[SessionController](../src/main/java/com/evidence/rag/security/SessionController.java)。

## 认证与公共约定

除 `GET /v1/config` 和会话端点的专门处理外，`/v1/` 路径需要身份。JWT 是默认模式：

```http
Authorization: Bearer <YOUR_JWT>
```

JWT 为 HS256，必需 claims：`iss`、`aud`、`workspace_id`、`sub`、整数秒 `exp`，可选整数秒 `nbf`。`aud` 可为字符串或包含配置 audience 的字符串数组。算法、签名、组织及有效期必须全部匹配。重复 Authorization 拒绝；已提供但无效的 Bearer 不回退浏览器 Cookie。

仅在显式 `RAG_AUTH_MODE=development_headers` 且通过 loopback 启动/Host 检查时接受：

```http
X-Workspace-Id: org-main
X-Principal-Id: owner
```

这两个头是本机测试身份，不是公网认证。重复头、缺失头或不匹配的组织失败。`owner/editor/reader` 是演示 principal 名，不是客户端可自行声明的资料角色；实际 role 来自服务端 ACL。

写请求使用 `Content-Type: application/json`。若有 Origin，它必须与请求同源；重复或跨源 Origin 为 403。JSON 重复键、尾随 token、错误类型和超限输入被拒绝。响应有 `X-Request-Id` 与 `Cache-Control: private, no-store`。

## 能力、健康与会话

| 方法与路径 | 当前响应 |
| --- | --- |
| `GET /v1/config` | 200，见下例；无需身份 |
| `GET /health/live` | 200 `{"status":"ok","edition":"java"}` |
| `GET /health/ready` | **503**，`status: migration_incomplete`、`edition: java`、说明迁移/生产条件尚未完整的 `detail` |
| `POST /v1/session` | JWT 模式交换 Cookie；请求/响应见下 |
| `DELETE /v1/session` | 200 `{"status":"signed_out"}` 并过期删除会话 Cookie |

`GET /v1/config` 示例（具体身份配置取决于运行参数）：

```json
{
  "auth_mode": "jwt",
  "workspace_id": "org-main",
  "edition": "java",
  "migration_stage": "management_slice",
  "capabilities": ["management", "folders", "metadata", "batch_move", "batch_tag"],
  "unavailable": ["upload", "answers", "sources", "ingestions", "reindex", "document_delete"]
}
```

`POST /v1/session` 请求只能含一个字段 `token`，为非空字符串，最大 16,384 字符：

```json
{"token":"<YOUR_JWT>"}
```

成功 200：

```json
{"status":"authenticated","workspace_id":"org-main","principal_id":"owner"}
```

服务器设置 `rag_session`：HttpOnly、SameSite=Strict、Path=/；TLS 或非 loopback 链路加 Secure。后续请求用浏览器 Cookie，前端不持久化 JWT。删除会话只清除 Cookie，不撤销外部签发的 JWT；没有 refresh/login-password/token-issuance 端点。

## 资料列表与分页

`GET /v1/management/documents`

| Query 字段 | 行为 |
| --- | --- |
| `q` | 原文件名或显示名的子串搜索，最多 200 code points；使用 SQLite `lower`，大小写折叠主要覆盖 ASCII，不承诺完整 Unicode casefold；不是全文/向量检索 |
| `type` | `document` / `image` / `audio` / `video` |
| `status` | 接受 `ready/queued/processing/parsed/failed/cancelled/deleting`；真实行按任务状态过滤，合成行仍为ready；parsed不等于已索引 |
| `folder_id` | 指定可见目录 ID；`unfiled` 表示未归档；省略/空字符串不限制目录 |
| `tag` | 精确手工标签匹配，最多 40 code points |
| `sort` | `updated_desc`（默认）、`updated_asc`、`name_asc`、`name_desc`；名称使用 SQLite `NOCASE`（主要 ASCII），同值以资料 ID 排序 |
| `page` | 1 起，默认 1，最大 1,000,000 |
| `page_size` | 默认 20，范围 1–100 |

未知 query 字段为 422。ACL 与固定 workspace 在计算 total、排序及分页之前应用。空列表的 `total_pages` 为 0；超出末页返回空 `items`，不会自动回退第一页。

```http
GET /v1/management/documents?q=差旅&type=document&sort=name_asc&page=1&page_size=20
```

成功 200 的完整行结构示例（ID、时间及元数据是示意值）：

```json
{
  "items": [{
    "document_id": "demo-document",
    "filename": "差旅政策（合成资料）.pdf",
    "status": "ready",
    "active_revision_id": "synthetic-demo-document",
    "segment_count": 0,
    "updated_at": "2026-01-01T00:00:00Z",
    "modalities": [],
    "media_info": {
      "mime_type": "application/pdf",
      "size_bytes": 0,
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
    },
    "display_name": "差旅政策（合成资料）.pdf",
    "folder_id": null,
    "folder_name": null,
    "tags": [],
    "current_role": "owner",
    "can_edit": true,
    "can_delete": false,
    "can_reindex": false,
    "latest_job": null,
    "synthetic_fixture": true,
    "document_type": "document"
  }],
  "total": 1,
  "page": 1,
  "page_size": 20,
  "total_pages": 1
}
```

`ready` 与 `active_revision_id` 在本切只是合成元数据，不代表存在原文件、解析分块或向量索引。没有独立 `GET /v1/management/documents/{id}` 详情端点；网页详情使用已返回的列表行。

## 编辑资料展示元数据

`PATCH /v1/management/documents/{documentId}`

允许字段仅 `display_name`、`folder_id`、`tags`，至少一个字段；省略保持原值：

```json
{"display_name":"新版差旅制度","folder_id":null,"tags":["人事","制度"]}
```

- `display_name`：去两端空白后非空，最多 255 code points，不接受 null。
- `folder_id`：目录 ID 或 null；null 移回未归档。目标目录必须对当前用户可见。
- `tags`：字符串数组，最多 20 项，每项去两端空白后非空、最多 40 code points；去重，保留顺序。此 PATCH **替换**标签，`[]` 清空，不接受 null。
- 标签、名称和 ID 不接受控制字符或非法 surrogate；未知字段为 422。
- 必须为资料 owner/editor；无权或不存在统一返回 404，reader 不能写。

成功 200 返回更新后的**单个资料行**，结构同列表 items。只改显示名、目录、标签和更新时间；不能改 `filename`、源摘要、revision 身份，也不会调用模型或重新索引。

## 目录与标签

| 方法与路径 | 请求 | 成功响应 |
| --- | --- | --- |
| `GET /v1/management/folders` | 无 | 200 `{"items":[{"folder_id":"<id>","name":"制度","document_count":1,"can_edit":true}]}` |
| `POST /v1/management/folders` | `{"name":"制度"}` | 201，单个目录对象，初始 count 为 0 |
| `PATCH /v1/management/folders/{folderId}` | `{"name":"人事制度"}` | 200，单个更新后的目录对象 |
| `DELETE /v1/management/folders/{folderId}` | 无 | 200 `{"folder_id":"<id>","status":"removed"}` |
| `GET /v1/management/tags` | 无 | 200 `{"items":["人事","制度"]}` |

目录是平面结构，没有嵌套树；名称仅 `name` 一个字段，去两端空白后非空，最多 80 code points。当前用户创建的同名目录按 `toLowerCase(Locale.ROOT)` 加 `ß→ss`、`ς→σ` 折叠检测冲突（409 `folder_name_conflict`）；这不是完整 Unicode/Python casefold。

用户能看到自己拥有的目录，或含其可见资料的目录；`document_count` 只计可见资料，目录改名/删除要求目录所有者。删除必须真实为空（含当前用户不可见的资料也不能留下），否则 409 `folder_not_empty`。不会连带删除或移动资料。

标签列表只来自当前用户可见资料，排序去重；没有全局标签编辑或自动打标签端点。

## 批量操作与 partial failure

`POST /v1/management/document-actions`

移动到目录（`folder_id: null` 表示未归档）：

```json
{"document_ids":["demo-document","demo-image"],"action":"move","folder_id":"<folder-id>"}
```

追加手工标签：

```json
{"document_ids":["demo-document","demo-image"],"action":"tag","tags":["待核对"]}
```

规则：`document_ids` 为 1–100 个不重复的非空 ID，每个最多 100 code points。`move` 必须提供 `folder_id` 且不能带非 null 的 tags；`tag` 要求非空 tags 数组且不能带非 null 的 folder_id。批量 tag 是**追加并去重**，不是 PATCH 的替换语义；合并后超过 20 个标签，该项返回 409 `tag_limit_reached`。

请求整体有效后，资料逐项校验/独立事务。HTTP 200 **不代表全部成功**：

```json
{
  "items": [
    {"document_id":"demo-document","ok":true,"receipt":{"document_id":"demo-document","status":"updated"}},
    {"document_id":"demo-image","ok":false,"error_code":"not_found","detail":"资料不存在、无操作权限或当前状态不可操作。"}
  ]
}
```

客户端必须显示所有失败，并将缺失、重复或无法对应请求 ID 的回执视为异常。不要自动无差别重试整个批次；已有成功项可能已提交。

`action: delete/reindex` 明确返回 **501 `migration_incomplete`**；不是可用生命周期操作。未知 action、未知字段、重复 ID 或错误整体结构返回 422。

## 安全错误 schema

统一 `Content-Type: application/problem+json`，示例：

```json
{
  "type": "https://evidence.local/problems/not-found",
  "title": "资料不可用",
  "status": 404,
  "detail": "资料不存在、无操作权限或当前状态不可操作。",
  "instance": "/v1/management/documents/example-id",
  "error_code": "not_found"
}
```

`type` 是问题类型标识，不是需访问的服务地址。请求编号位于响应头 `X-Request-Id`，不在此 JSON 中。401 还返回 `WWW-Authenticate: Bearer`。

| 状态 | 常见 `error_code` |
| --- | --- |
| 401 | `unauthorized` |
| 403 | `development_host_denied`、`cross_origin_denied` |
| 404 | `not_found`（不存在、无权限或未迁移资源不暴露内部差异） |
| 405 / 415 | `method_not_allowed` / `unsupported_media_type` |
| 409 | `folder_name_conflict`、`folder_not_empty`；逐项 `tag_limit_reached` |
| 422 | `invalid_request`、`invalid_identity`、`invalid_session` |
| 500 / 503 | `internal_error` / `management_unavailable`，安全常量 detail |
| 501 | `migration_incomplete`，目前用于 delete/reindex 批量动作拒绝 |

`/health/ready` 的 503 使用专门的健康状态 JSON，不使用此问题 schema。不要依赖异常原文、数据库信息或 token 内容诊断；它们不会被 API 返回。

## 明确不存在的业务端点

当前没有上传、ingestion/job、解析调用、问题回答、streaming、检索、来源预览/下载、文档删除/重索引、摘要、自动标签、嵌入/重排/Milvus 管理或多模态 API。未映射路径不会成功执行这些能力，通常在通过身份后返回 404；不要把未来 route 名当作已实现契约。

`TextParser.parse(...)` 是 Java 库 Interface，不是 HTTP API。未来端点应先在版本化 [变更工件](changes/0001-java-publication/intent.md)中明确范围和验收，再更新本文及 HTTP 测试。
