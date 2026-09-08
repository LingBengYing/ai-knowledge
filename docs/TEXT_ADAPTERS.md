# Text adapters：模型与 Milvus 协议子切

本页描述模型与Milvus Module。0003连接上传/解析authority，0004连接索引worker、embedding、投影写入/完整验证与publication；当前[0007](changes/0007-text-answers/spec.md)另接query/search、rerank/extract、回答和引用HTTP，独立默认关闭且仅本机。0007本地全量及六golden确定性替身通过，不是完整语义、网页或实际provider/Milvus验收。0002/0003及0005/0006历史指纹不认证新增源码；当前边界见[0007验证](changes/0007-text-answers/verification.md)。

## 模型 Interface

`TextModels` 的三个操作相互独立：

- `embed(texts)`：向量按原输入顺序返回，数量、索引、有限数值、非零范数与固定维度必须符合契约。
- `rerank(query, documents)`：所有候选必须各出现一次，返回原索引/分数；不能把缺项或重复当作成功。
- `extract(query, evidence)`：返回给定 evidence ID 对应的逐字摘录，或显式拒答。未知 ID、非原文、截断/坏结构失败关闭。

`OpenAiCompatibleModels`分别使用`/embeddings`、`/rerank`和`/chat/completions`。三个Endpoint的base URL、模型和key可以不同；URL是包含版本前缀的基础地址（占位示例`https://models.example.com/v1`），不是具体操作完整路径。`/rerank`是provider扩展，不属于标准OpenAI API；兼容性需要对目标provider单独验证。索引worker只调用`embed`；0007 AnswerService调用query embedding、rerank和extract。[硅基流动rerank文档](https://docs.siliconflow.cn/docs/api/rerank-post)

2026-09-08[真实SiliconFlow联调](changes/0007-text-answers/provider-integration.md)：BGE-M3的3个1024维向量及BGE reranker完整排序合成用例通过；Qwen2.5-7B-Instruct摘录60秒超时，整体失败并停止，没有重试或放宽deadline。这是时点协议smoke，不证明固定权重、完整问题支持或生产质量。

生成请求的上下文把证据作为数据；仅允许摘录的返回格式限制模型自造引用内容，**但原文摘录不等于事实支持、相关性或提示注入安全证明**。0007另经TextGrounding检查事实与冲突，EvidenceService复验ACL、active、完整所选集合和source locator；这些接线及确定性测试不等于完整语义或实际质量验收。

HTTP 使用有界请求、响应字节上限、完整响应体 deadline、禁止重定向和严格 JSON/数值校验。失败只给安全错误类别，不包含 provider body、用户问题、凭据或底层异常。输入/响应超限直接失败，不通过截断证据来求成功。真实计费请求和外部质量测试不属于本地协议回归。

## Milvus Interface

补充真实证据：固定Milvus2.6.22/ARM64的写入、精确float32摘要回读、双路授权查询及清理通过，并用移除组织过滤的临时mutation验证测试能报错。2026-09-08新增4096短正文/4维完整manifest、额外第4097条拒绝及卸载后只读不加载/显式重载恢复通过。详见[真实集成记录](changes/0007-text-answers/milvus-integration.md)。一般容量、flush/重启、真实模型总体质量和完整生产验收不由本轮代证。

`RetrievalProjection`的`initialize/upsert/verify/prepareSearch/search`由调用方显式触发，`identity()`标识冻结投影；构造或加载配置本身不创建集合。索引worker调用前三项，0007 AnswerService仅在非空授权范围确认后调用只读prepareSearch/search。`MilvusRestProjection`使用REST v2、固定schema及dense/BM25两路查询Interface；目标协议为Milvus 2.6，真实版本验收未由本地stub替代。

- 集合名必须以 `java_` 开头，workspace、embedding identity、维度固定。新集合显式建立；已存在集合校验字段、BM25 function、索引与 identity，任何不兼容都拒绝，不自动删除或改写旧集合。
- 每条entry保存workspace、document、物理generation/segment身份、正文与embedding；0004中Milvus列`revision_id`存每次claim独立的projectionGenerationId，`segmentId`由共享`physicalSegmentId(generation, sourceSegmentId)`生成`seg-`加规范SHA-256。源revision/segment保留在authority及publication-entry映射，投影不是权威证据存储。
- `verify(RevisionManifest)`验证全部1–4096段的物理generation：精确generation filter（列仍名revision_id）读取N+1 metadata，严格核对全部物理ID与scope，再以最多16段精确ID回读text/dense及canonical float32摘要。前后重复metadata与Strong/schema/index校验。authority以source ID和generation重构物理manifest，提交不可变attempt/publication/source→physical→digest台账；详见[TEXT_INDEXING](TEXT_INDEXING.md)。
- `prepareSearch()`只执行collection has/describe与两个index describe，缺集合、schema或索引不匹配时失败；不create/load/upsert，不证明loaded，未加载导致search失败时按安全上游失败处理。全部准备读取共享一个操作预算，失败不保持search-ready状态。
- `AuthorizedScope.documentRevisions`由0007 authority从当前active publication派生完整`document → projection generation`，不能直接填HTTP active/source revision。两路top-K之前使用同一完整过滤；显式空范围不请求，超限失败不截断。
- 返回身份再次核对scope，两路以确定性RRF去重排序，只返回physical ID/score。EvidenceService经publication entries映射回source segment，按当前完整ACL/active/locator读取权威正文后才进入rerank/extract，不使用投影正文作证据。

Strong由collection describe校验，不给Query添加未被其契约识别的override。每attempt独立generation隔离未知HTTP晚写，父watchdog与同OS用户跨JVM物理collection lease控制本地worker；kill不能撤回上游请求。verify不修改schema/index，验证至提交仍须冻结外部generation数据和collection/schema/index配置，多HTTP调用不是数据库快照。当前进程/晚写机制仍按VERIFICATION验收；本地断言不能替代真实Milvus字段兼容、可见性、一致性和容量验收。[Milvus REST Search](https://milvus.io/api-reference/restful/v2.6.x/v2/Vector%20%28v2%29/Search.md)、[Full text search](https://milvus.io/docs/full-text-search.md)

## 显式环境配置

`TextAdapterSettings.load(environmentMap)`仅校验配置，不发网络。TextAdaptersConfiguration在`RAG_INDEXING_ENABLED=true`或`RAG_ANSWERS_ENABLED=true`任一成立时加载一次，两者都关闭才不加载；三套完整Endpoint均必填，即使索引任务只调用embedding。问答启用才装配共享model/projection Client及AnswerService，不隐式启动索引/摄取Job；共享Client在Bean生命周期关闭，不随单个请求取消而关闭。远程写入仍仅由索引任务触发，问答仅查询已有发布。仅添加模型变量不会启用功能；不要提交真实key，程序不自动source `.env`。

| 环境变量 | 要求 |
| --- | --- |
| `RAG_EMBEDDING_BASE_URL` / `MODEL` / `API_KEY` | 三项均必填，独立 embedding Endpoint |
| `RAG_RERANK_BASE_URL` / `MODEL` / `API_KEY` | 三项均必填，独立 rerank Endpoint |
| `RAG_GENERATION_BASE_URL` / `MODEL` / `API_KEY` | 三项均必填，独立 generation Endpoint |
| `RAG_EMBEDDING_DIMENSIONS` | 必填整数，环境配置允许 2–8192；与投影维度相同 |
| `RAG_EMBEDDING_REVISION` | 必填不可变模型/部署版本标识；不是随意的 latest 标签 |
| `RAG_MILVUS_ENDPOINT` / `TOKEN` / `COLLECTION` | 必填；collection 必须以 `java_` 开头 |
| `RAG_MILVUS_DATABASE` | 默认 `default` |
| `RAG_WORKSPACE_ID` | 必填，单组织标识 |
| `RAG_TEXT_DEADLINE_MS` | 默认 30000，范围 1–60000；一次 HTTP 请求含完整响应体 |
| `RAG_TEXT_MAX_RESPONSE_BYTES` | 默认 4194304，范围 1024–4194304 |
| `RAG_TEXT_ALLOW_LOOPBACK_HTTP` | 默认 `false`；仅 `true` 可为本地测试允许字面 loopback HTTP |
| `RAG_INDEXING_ENABLED` | 默认`false`；`true`启用索引runtime/路由，要求development/test和字面loopback绑定 |
| `RAG_INDEXING_TIMEOUT_MS` | 默认60000，范围10–600000；索引全任务deadline，独立于模型/投影操作budget |
| `RAG_ANSWERS_ENABLED` | 默认`false`；独立启用回答/引用HTTP，仅development/test和字面loopback |
| `RAG_ANSWERS_TIMEOUT_MS` | 默认60000，范围10–600000；完整JSON解码后的整次处理预算，不是slow-body防护 |
| `RAG_ANSWERS_MAX_CONCURRENT` | 默认2，范围1–8；无无界队列，满额429，实际执行退出后才释放名额 |

环境工厂将 embedding URL、模型、revision 和维度绑定为哈希 identity，改变其中任何一项必须使用匹配的新投影。它不会把密钥写进 identity，也不会自动根据维度重建集合。数字溢出、未知 adapter 前缀配置名、缺项、危险 URL 和常见占位 key 均失败关闭；系统的其他环境变量忽略。

默认只接受 HTTPS。loopback 测试开关不允许任意内网 HTTP 或 `localhost` 名称；不要为绕过限制把生产 provider 假装成本地。显式配置 URL 仍必须来自受信运维配置，不能来自用户问题或上传文档；TLS 与限长不是 SSRF 沙箱。

## 当前接线与下一步

1. 0003连接单authority、上传/解析和v2不可变页/segment；0004的v3保存generation/publication/物理映射，缺台账的早期WIP v3拒绝复用；0007当前v4新增query_traces/query_trace_documents/query_trace_evidence，保留旧证据和逐版本备份迁移。
2. 0004独立JVM按响应字节/维度预算进行至多16段embedding/upsert，以新generation隔离每次attempt，完整manifest后条件发布source active；父存活、lease、晚写、真实provider/Milvus与当前完整源码验收分别记录。
3. 0007 AnswerService/EvidenceService现已连接完整授权scope、search/rerank/extract、TextGrounding与来源；trace写入前在Store锁内复验完整scope、预算及本地配置资格，非候选所选资料也必须复验。trace只存摘要、身份与locator，不存原问题/全文/凭据；不承诺原子冻结外部Milvus。
4. 四PDF与六golden确定性替身已通过；完整语义、真实provider/Milvus staging和浏览器闭环仍待验收。runtime可宣告answers/sources，但列表can_answer仍false、网页未接线、ready503，多模态和生产gate另行验收。HTTP契约及代码入口见[API](API.md)、[AI_CONTEXT](AI_CONTEXT.md)。
