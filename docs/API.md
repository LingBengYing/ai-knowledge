# HTTP API：资料管理、文本摄取、索引与可选问答

0003提供显式本机摄取：[TEXT_INGESTION](TEXT_INGESTION.md)；0004新增显式索引：[TEXT_INDEXING](TEXT_INDEXING.md)。0007新增独立、默认关闭的文本问答与引用读取，见下方“可选文本问答与引用读取”。三个开关只允许development/test与字面loopback。0007仍为IMPLEMENTATION：本机真实HTTP九项回归已通过，但不代表完整0007、网页问答、真实provider/Milvus质量或生产验收；逐次源码与验证边界见[0007 verification](changes/0007-text-answers/verification.md)。0003发布bc82a7a的190项Java/42项Node和0005/0006历史验收不认证新增问答代码。

本文只记录当前代码已映射的端点。基础URL使用本地配置，默认端口18084；响应通常为JSON，上传body为原文件字节，任务操作无body。源码依据：[ManagementController](../src/main/java/com/evidence/rag/controller/ManagementController.java)、[ManagementService](../src/main/java/com/evidence/rag/service/ManagementService.java)、[IndexingController](../src/main/java/com/evidence/rag/controller/IndexingController.java)、[AnswerController](../src/main/java/com/evidence/rag/controller/AnswerController.java)、[RuntimeController](../src/main/java/com/evidence/rag/controller/RuntimeController.java)、[SessionController](../src/main/java/com/evidence/rag/controller/SessionController.java)。

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

有JSON body的写请求使用`Content-Type: application/json`；上传使用application/octet-stream；索引和任务操作必须无query/body，无需JSON Content-Type。若有Origin，它必须与请求同源；重复或跨源Origin为403。JSON重复键、尾随token、错误类型和超限输入被拒绝。响应有`X-Request-Id`与`Cache-Control: private, no-store`。

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
  "unavailable": ["answers", "sources", "reindex", "document_delete", "upload", "ingestions", "text_index", "indexings"]
}
```

capabilities/unavailable是能力名称数组，不是布尔字段。管理五项能力始终保留；摄取启用时加入`text_upload/ingestions`并移除`upload/ingestions`不可用项；索引启用时加入`text_index/indexings`并移除对应不可用项。问答关闭时，原stage优先级保持`text_indexing`、`text_ingestion`、`management_slice`；问答启用时另加入`answers/sources`、移除对应不可用项，stage为`text_answers`，不隐式开启摄取或索引。`reindex/document_delete`继续不可用，readiness始终503。能力开关不代表网页已经接线或任何具体资料可直接回答。

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
| `status` | 接受`ready/queued/processing/parsed/failed/cancelled/deleting`；真实行按摄取状态过滤，合成行仍为ready；索引独立，`indexed`不是此query的可用值 |
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
    "active_revision_id": null,
    "registered_revision_id": "synthetic-demo-document",
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
    "can_answer": false,
    "index_status": "not_indexed",
    "latest_index_job": null,
    "index_publication_id": null,
    "can_index": false,
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

0004明确区分注册与发布：合成行`ready`仍仅是元数据，`active_revision_id=null`，原注册身份在`registered_revision_id`；不修改数据库旧注册值。真实行`registered_revision_id=null`，active只有完整索引publication后非空。真实资料的`status/latest_job`继续表示摄取，索引成功后status仍parsed；`index_status`独立为not_indexed/queued/processing/indexed/failed/cancelled，latest_index_job是下方完整任务，index_publication_id仅发布后非空。

HTTP任务revision_id与列表active_revision_id始终表示source revision。每次claim的内部projection generation、物理segment ID及完整source→physical→digest台账由authority管理，不是客户端设置字段；Milvus列revision_id使用generation，不能直接以HTTP source revision构造未来检索scope。

`can_index`表示真实parsed、当前owner/editor、尚无索引任务/active的行资格，还必须与全局text_index/indexings能力同时成立才能发请求；服务端再次验证。所有行`can_answer=false`、`can_reindex=false`仍保持原契约：0007后端问答没有接入该网页能力，不能把indexed或active非空当成资料具备回答任何问题的资格；问答API独立复验当前授权和证据。没有独立`GET /v1/management/documents/{id}`详情端点；网页详情使用列表行。

## 可选文本摄取与索引路由

`RAG_INGESTION_ENABLED=true`启用`POST /v1/documents?filename=<URL-encoded name>`（application/octet-stream原文件，1字节–20MiB，PDF/TXT/MD）以及`GET /v1/ingestions/{taskId}`、`POST /v1/ingestions/{taskId}/cancel`和`/retry`。上传成功202，其余成功200；任务路由不接受query/body。详细上传限额、取消和解析状态见[TEXT_INGESTION](TEXT_INGESTION.md)。

**摄取授权补强（0006，本地验证通过）：**任务始终绑定原创建者；领取 queued、创建 parser 前、运行中检查和最终提交都会复验其当前文档写权限。原创建者不再是 owner/editor 时，任务系统取消为 `state/status=cancelled`、`error_code=null`，未领取项不启动 parser，运行项中断并等待实际 child 清理，不提交解析证据。取消与内部 `ingestion_authorization_cancelled` 审计同事务；不增加新的 HTTP 审计/权限端点或响应字段。启动恢复时失权的 processing 同样取消；仍合法的 processing 保持原 `failed/worker_interrupted` 恢复行为。

摄取 `/retry` 先检查当前调用者可编辑、failed/cancelled 和 attempt<3，再检查原创建者仍可写；若仅原创建者失权，返回 **409 `authorization_changed`**，不排队、不增加 attempt。恢复创建者写权限后须显式重试。任务详情与管理列表 `latest_job.can_retry` 为这四项条件的交集；`can_cancel` 仍只要求当前调用者 owner/editor 且 queued/processing，其他合法 editor 仍可取消。`authorization_changed` 是本次摄取重试的 HTTP 错误，不是摄取持久 `error_code`；无权/不存在仍统一404。源 revision、任务 JSON 形状、schema 和下述索引契约不变。规格及证据见 [0006 spec](changes/0006-ingestion-authorization/spec.md)、[verification](changes/0006-ingestion-authorization/verification.md)。

`RAG_INDEXING_ENABLED=true`独立启用以下四条路由。启动要求完整TextAdapterSettings及字面loopback；全任务timeout默认60000毫秒、范围10–600000。配置加载无网络，只有显式任务调用embedding/Milvus，不调用rerank/generation。

| 方法与路径 | 成功响应 | 操作条件 |
| --- | --- | --- |
| `POST /v1/documents/{documentId}/index` | 202，索引任务 | 当前owner/editor，真实parsed资料，1–4096段，没有现有索引任务/active |
| `GET /v1/indexings/{taskId}` | 200，索引任务 | 当前有资料读取权限 |
| `POST /v1/indexings/{taskId}/cancel` | 200，索引任务 | 当前owner/editor，queued/processing |
| `POST /v1/indexings/{taskId}/retry` | 200，索引任务 | 当前owner/editor，failed/cancelled，冻结目标匹配、创建者仍可写，总attempt未满3 |

四条索引路由均不接受query或body：包括空query分隔符、非空body或Transfer-Encoding都会返回422 invalid_request。合成资料、未解析资料、重复创建和已发布版本不会隐式重建。未知或无权读取/修改任务统一404；reader可读取其授权任务，不能创建/取消/重试。

索引任务结构（占位示例，state可能已被后台调度推进）：

```json
{
  "task_id": "<task-id>",
  "document_id": "<document-id>",
  "revision_id": "<revision-id>",
  "filename": "示例政策.txt",
  "state": "queued",
  "status": "queued",
  "attempt": 1,
  "created_at": "2026-01-01T00:00:00Z",
  "updated_at": "2026-01-01T00:00:00Z",
  "error_code": null,
  "can_cancel": true,
  "can_retry": false,
  "index_publication_id": null
}
```

任务status是state的同值别名，与列表摄取status分开。queued→processing→indexed/failed/cancelled。单document只有一个索引任务，失败/取消通过原任务显式重试，最多3次总attempt。can_cancel/can_retry只是角色/状态/次数提示；retry提交时还校验冻结target/source与创建者权限，配置变化409 index_configuration_changed，次数上限409 indexing_retry_limit，其他状态冲突409 indexing_state_conflict。持久失败安全码为indexing_failed、indexing_timeout、indexing_output_invalid、worker_interrupted、authorization_changed、index_configuration_changed，不包含provider原文或密钥。

取消使旧claim失效，普通清理确认旧worker退出后释放本进程额度；重启将processing改为failed/worker_interrupted，需显式重试。新claim使用独立generation与物理ID；旧请求即使后来在上游完成，也仅写旧namespace，不以kill代表撤回上游。protocol v2父存活检查和跨JVM collection lease控制本地writer，相关异常进程/晚写验收仍以VERIFICATION为准。每次retry可能再次计费/写投影，网络中断先读状态，不自动重复写请求。完整物理manifest与映射台账事务提交后才indexed且publication非空；回执/generation无HTTP提交接口。索引路由和原管理命名空间保持分离，批量reindex仍拒绝。

## 可选文本问答与引用读取（0007）

本节记录已接入并通过本机真实HTTP回归的协议，不宣称0007已完成完整验收。测试使用临时SQLite与本机模型/Milvus协议替身，不是实际provider质量或网页验收。实现和未验证项以[0007 spec](changes/0007-text-answers/spec.md)及[verification](changes/0007-text-answers/verification.md)为准。

### 启用条件与预算

| 环境变量 | 默认值与行为 |
| --- | --- |
| `RAG_ANSWERS_ENABLED` | `false`；显式`true`才注册下述两条路由，仅development/test且服务绑定字面`127.0.0.1`或`::1` |
| `RAG_ANSWERS_TIMEOUT_MS` | `60000`；范围10–600000，完整JSON解码后的整次处理预算 |
| `RAG_ANSWERS_MAX_CONCURRENT` | `2`；范围1–8；满额直接429，不建立无界等待队列 |

问答与摄取、索引开关独立；仅开启问答不会启动摄取/索引Job。索引或问答任一开启时，共用的TextAdapterSettings只装配一次，要求完整embedding、rerank、generation Endpoint、embedding维度/revision和Milvus配置；缺项拒绝启动，不使用假模型补位。字段说明见[TEXT_ADAPTERS配置表](TEXT_ADAPTERS.md#显式环境配置)，0007的启用与装配条件以本节为准。密钥仅通过受信外部配置提供，不放入请求、响应或代码。

装配不发网络请求；非空授权范围确认后才准备查询。查询只校验已有Milvus集合/schema/索引，不创建、加载或写集合；schema检查成功不等于集合已经loaded。原发布目标须与当前模型/投影配置匹配。总处理预算不包含慢请求体接收防护；取消请求不代表上游计费或任务已撤回，不自动重试计费调用。

### `POST /v1/answers`

请求使用`Content-Type: application/json`，不接受query（包括空query分隔符）。仅允许以下字段：

```json
{"question":"上海住宿上限是多少？","document_ids":["doc-policy"]}
```

`doc-policy`是示意ID，调用时应使用当前有权访问且已发布的真实资料ID。

| 字段/范围 | 契约 |
| --- | --- |
| `question` | 必填字符串，非空白、合法Unicode，最多4096 UTF-8字节；不接受非法surrogate或控制字符（允许换行和制表符） |
| 不提供`document_ids` | 当前用户有权读取、已发布的全库；超过128份明确失败，不截断成前128份 |
| `document_ids: []` | 显式空范围，200拒答、`reason=empty_scope`、`citations=[]`，零模型/投影调用，绝不回退全库 |
| 非空`document_ids` | 最多128个不重复字符串ID；每个匹配`[A-Za-z0-9][A-Za-z0-9._:-]{0,99}` |

`document_ids:null`、重复/非法/超量ID、未知字段、非对象JSON、重复键和尾随JSON均422 `invalid_request`。客户端不能设置角色、workspace、模型、分数、页码或locator。任一显式所选资料不存在、无权、未发布或发布目标不匹配，整次404 `not_found`，不会只保留其余项或扩大范围。

沿用本文件的身份和Origin规则：JWT Bearer/Cookie对应的可信Actor决定范围；无效Bearer不降级Cookie，开发身份头不能覆盖JWT。JWT缺失/无效为401；开发身份缺失为422 `invalid_identity`；跨源写入403。回答提交前复验整个冻结范围，包含未进入候选的所选资料。

成功回答和有审计的拒答均返回200 `application/json`，固定字段如下；客户端必须检查`status`，不能把所有200当作已回答。

| 字段 | 含义 |
| --- | --- |
| `answer_id` | 服务器生成并持久化的答案/拒答标识 |
| `status` | `answered`或`abstained` |
| `answer` | 已验证的证据摘录组成的答案；拒答时为安全说明，不用模型常识补齐 |
| `reason` | `answered`时为null；拒答时为安全原因码，例如`empty_scope`、`no_evidence`、`incomplete_evidence`、`conflicting_evidence`、`upstream_unavailable`、`scope_changed` |
| `citations` | 服务器校验后的引用数组；拒答时为空数组，不返回未验证引文 |

每个citation固定包含以下字段，不返回内部claim、projection generation或整页正文：

| 字段 | 含义 |
| --- | --- |
| `number` | 此答案中的1起引用序号，最多32 |
| `document_id`、`revision_id` | 文档与source revision身份；不是Milvus generation |
| `source_sha256`、`parser_revision` | 原文件摘要与解析版本 |
| `filename` | 原文件名，不是可修改的显示名 |
| `page`、`start`、`end` | 页号从1起；页内Unicode code point的0起半开范围`[start,end)`，不是UTF-8字节或JavaScript UTF-16索引 |
| `quote`、`quote_sha256` | 经过服务器原文校验的摘录及其UTF-8 SHA-256 |
| `source_url` | 服务器生成的相对URL：`/v1/sources/{answer_id}/{number}`；不能信任模型自由生成链接 |

### `GET /v1/sources/{answerId}/{ordinal}`

使用原回答者的当前身份读取`source_url`；`ordinal`为1–32整数。不接受query或body（包括Transfer-Encoding）；非法序号/请求形状422。成功200，JSON仅含`answer_id`和`citation`，后者采用上表同一结构，由权威原文重新构造，不把保存的答案文本当成来源。

未知答案/引用、拒答的引用、非原回答者访问，或原回答完整范围内任一资料的当前ACL/active绑定失效，均404 `not_found`，不返回旧摘录；仅拥有同一资料权限也不能读取其他人的答案来源。该接口不是文件下载、任意页浏览或可自由传入文件路径的预览端点。回答和来源响应均保持no-store与`X-Request-Id`。

问答协议错误仍使用下方既有`application/problem+json`，安全码字段是`error_code`，不是`code`或回答的`reason`。典型额外错误为408 `answer_timeout`、429 `answer_capacity_exceeded`/`scope_capacity_exceeded`、503 `answers_unavailable`；未开启问答时上述路由在通过身份检查后为404。证据不足的200拒答与这些HTTP错误必须分开处理。

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
| 408 | 问答`answer_timeout` |
| 409 | `folder_name_conflict`、`folder_not_empty`；逐项 `tag_limit_reached` |
| 422 | `invalid_request`、`invalid_identity`、`invalid_session` |
| 429 | 问答`answer_capacity_exceeded`、`scope_capacity_exceeded`；来源证据超限`evidence_capacity_exceeded` |
| 500 / 503 | `internal_error` / `management_unavailable`、问答`answers_unavailable`，安全常量 detail |
| 501 | `migration_incomplete`，目前用于 delete/reindex 批量动作拒绝 |

`/health/ready` 的 503 使用专门的健康状态 JSON，不使用此问题 schema。不要依赖异常原文、数据库信息或 token 内容诊断；它们不会被 API 返回。

## 明确不存在的业务端点

当前没有streaming、独立候选检索、任意来源页预览/原文件下载、文档删除/已发布版本重索引、摘要、自动标签、独立嵌入/重排/Milvus管理或多模态API。摄取、索引和0007文本问答/引用读取仅在各自开关启用时提供上文路由；关闭或未映射路径在通过身份后通常404。引用读取只支持服务器已校验引用，不是通用来源浏览器。

`TextParser.parse(...)`、`TextModels.rerank/extract`和`RetrievalProjection.search`仍是Java库Interface，没有独立HTTP端点；问答由上述受授权用例编排调用。未来端点应先在版本化变更工件中明确范围和验收，再更新本文及HTTP测试。完整问答、selected-set、来源、多模态和生产目标见[ROADMAP](ROADMAP.md)，局部HTTP通过不缩减或完成这些目标。
