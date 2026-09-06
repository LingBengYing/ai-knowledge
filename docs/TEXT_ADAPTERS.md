# Text adapters：模型与 Milvus 协议子切

本页描述独立 Java Module，不是新 HTTP 路由或可用的最终 RAG 问答。当前网页仍为资料管理工作台。上传、隔离 worker、语料 authority、版本发布和服务端引用接线尚未完成；实际验证状态见 [0002](changes/0002-text-adapters/intent.md) 与 [VERIFICATION](VERIFICATION.md)。

## 模型 Interface

`TextModels` 的三个操作相互独立：

- `embed(texts)`：向量按原输入顺序返回，数量、索引、有限数值、非零范数与固定维度必须符合契约。
- `rerank(query, documents)`：所有候选必须各出现一次，返回原索引/分数；不能把缺项或重复当作成功。
- `extract(query, evidence)`：返回给定 evidence ID 对应的逐字摘录，或显式拒答。未知 ID、非原文、截断/坏结构失败关闭。

`OpenAiCompatibleModels` 分别使用 `/embeddings`、`/rerank` 和 `/chat/completions`。三个 Endpoint 的 base URL、模型和 key 可以不同；URL 是包含版本前缀的基础地址（如 `https://api.siliconflow.cn/v1`），不是某个具体操作的完整路径。`/rerank` 是 provider 扩展，不属于标准 OpenAI API；兼容性需要对目标 provider 单独验证。[硅基流动 rerank 文档](https://docs.siliconflow.cn/cn/api-reference/rerank/create-rerank)

生成请求的上下文把证据作为数据；仅允许摘录的返回格式防止模型自造引用内容，**但原文摘录不等于事实支持、相关性或提示注入安全证明**。问答服务未来还必须验证全部事实、冲突、ACL、active revision、完整所选集合与 source locator。

HTTP 使用有界请求、响应字节上限、完整响应体 deadline、禁止重定向和严格 JSON/数值校验。失败只给安全错误类别，不包含 provider body、用户问题、凭据或底层异常。输入/响应超限直接失败，不通过截断证据来求成功。真实计费请求和外部质量测试不属于本地协议回归。

## Milvus Interface

`RetrievalProjection` 的 `initialize`、`upsert`、`search` 由调用方显式触发，构造或加载配置本身不创建集合。`MilvusRestProjection` 使用 REST v2、固定 schema 与 dense/BM25 两路搜索；部署目标协议为 Milvus 2.6。

- 集合名必须以 `java_` 开头，workspace、embedding identity、维度固定。新集合显式建立；已存在集合校验字段、BM25 function、索引与 identity，任何不兼容都拒绝，不自动删除或改写旧集合。
- 每条 entry 保留 workspace、document、revision、segment 身份、文本与 embedding；投影不是权威证据存储。
- 查询作用域是 authority 预先验证的完整 `document → active revision` 集合。两路 top-K 之前使用同一过滤；空范围不发请求，超限范围报错而非截断。
- 返回身份再次核对作用域。两路用确定性 RRF 去重与排序，候选只返回 ID/score。最终正文必须从 authority 在当前权限下回读，不能把投影正文直接喂给模型。

创建、索引与 Strong consistency 的本地请求断言不能替代真实 Milvus 的字段兼容、写入可见性和容量验收。[Milvus REST Search](https://milvus.io/api-reference/restful/v2.6.x/v2/Vector%20%28v2%29/Search.md)、[Full text search](https://milvus.io/docs/full-text-search.md)

## 显式环境配置

`TextAdapterSettings.load(environmentMap)` 仅校验并返回模型与投影配置，不发网络、不安装 Spring bean。正常管理应用暂不调用它，因此仅设置下表也不会自动启用上传、检索或模型接口。不要把示例填入真实 key 后提交；程序不自动 source `.env`。

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

环境工厂将 embedding URL、模型、revision 和维度绑定为哈希 identity，改变其中任何一项必须使用匹配的新投影。它不会把密钥写进 identity，也不会自动根据维度重建集合。数字溢出、未知 adapter 前缀配置名、缺项、危险 URL 和常见占位 key 均失败关闭；系统的其他环境变量忽略。

默认只接受 HTTPS。loopback 测试开关不允许任意内网 HTTP 或 `localhost` 名称；不要为绕过限制把生产 provider 假装成本地。显式配置 URL 仍必须来自受信运维配置，不能来自用户问题或上传文档；TLS 与限长不是 SSRF 沙箱。

## 后续接线顺序

1. 在唯一 Java authority writer 中建立受测的 ingestion/revision/segment 迁移、任务事务与授权 snapshot。
2. 上传经受限独立进程解析，向量写入隔离 projection；验证可见性后才原子发布 active revision。
3. 服务端计算完整授权范围，再调用此检索/重排 Interface；最终答案提交前重验所有所选资料及证据。
4. 完成 PDF golden、引用与撤权竞态、真实 provider/Milvus staging 和浏览器闭环后才启用相应 API；生产 gate 另行验收。
