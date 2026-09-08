# Text Indexing · 0004

当前状态为IMPLEMENTATION。0004把已解析真实文本连接到embedding与独立Java Milvus投影，完整revision验证后才发布权威active。它不提供检索、重排、最终回答、来源或多模态API。0003发布基线bc82a7a的190项Java/42项Node与Java21 CI是历史记录；当前源码、独立审查、浏览器、真实provider/Milvus及生产验收分别以[VERIFICATION](VERIFICATION.md)为准，旧manifest不认证新增源码。

## 启用与任务入口

`RAG_INDEXING_ENABLED`默认false；显式true才安装索引运行时与路由。仅允许`RAG_ENVIRONMENT=development/test`及字面`RAG_BIND_ADDRESS=127.0.0.1`或`::1`，不接受`localhost`作为绑定替代，也不能放到公网或代理后当生产服务。`RAG_INDEXING_TIMEOUT_MS`默认60000毫秒、范围10–600000，是整个任务的deadline。摄取开关独立，索引已有parsed资料不要求再次启用上传。

启用时必须配置完整[TextAdapterSettings](TEXT_ADAPTERS.md)：embedding、rerank、generation三套BASE_URL/MODEL/API_KEY、固定embedding revision/维度，以及固定workspace、Milvus endpoint/token/database和`java_`专用collection。当前worker只调用embedding与Milvus；要求其他Endpoint配置不代表调用它们。配置加载本身不发网络，缺项或非法值拒绝启动，不退回假模型或内存向量库。程序不自动source `.env`。

远程操作由已授权任务触发。配置目标须是明确允许使用的独立Java集合；只用合成资料做验收，不复用或修改其他服务的数据目录、运行配置和现役集合。新集合可由worker显式initialize创建；已有集合不兼容时拒绝，不自动删除、重建或修改schema/index来求通过。

| 方法与路径 | 成功响应与条件 |
| --- | --- |
| `POST /v1/documents/{documentId}/index` | 202任务；当前owner/editor；真实parsed资料；尚无索引任务或active发布 |
| `GET /v1/indexings/{taskId}` | 200任务；当前有该资料读取权限 |
| `POST /v1/indexings/{taskId}/cancel` | 200任务；当前owner/editor；仅queued/processing |
| `POST /v1/indexings/{taskId}/retry` | 200任务；当前owner/editor；仅failed/cancelled；同冻结目标，最多3次attempt |

四条路由都不接受query或body；包括空query分隔符、非空body或Transfer-Encoding都会被拒绝。沿用JWT/显式本机开发身份和同源Origin限制，无权与不存在统一404。它们不位于`/v1/management`；现有管理命名空间与批量`reindex`的501拒绝语义不变。

任务返回`task_id/document_id/revision_id/filename/state/status/attempt/created_at/updated_at/error_code/can_cancel/can_retry/index_publication_id`，任务status是state的同值别名。状态是`queued → processing → indexed/failed/cancelled`。一个document只创建一个索引任务；失败/取消通过原任务重试，attempt初始1、总计最多3次，不隐式覆盖已发布版本。`can_retry`是当前角色/状态/次数提示，提交仍会重验冻结目标、创建者当前写权限及解析版本。配置变更返回409 `index_configuration_changed`；次数耗尽返回409 `indexing_retry_limit`；状态不允许返回409 `indexing_state_conflict`。

重试可能再次产生embedding费用与Milvus写入；不自动重试写请求。取消使旧claim失效，runtime终止旧子进程并确认退出后才释放本进程并发额度。重启将遗留processing任务改为`failed/worker_interrupted`，需显式重试；queued任务仍可被调度。每次claim分配独立UUID `projectionGenerationId`并记录不可变attempt台账；重试使用新的物理generation，保留同一source revision。失败或取消保留原解析证据及旧generation中未发布的投影，不自动清空远程数据。

generation隔离用于处理取消/超时后结果未知的远程写入：旧HTTP请求即使在重试之后才完成，仍只能写旧generation的物理ID，不能覆盖新publication的数据。终止子进程不能撤回Milvus已接受的请求；本机制不依赖上游撤回。父存活检测、跨JVM lease、晚写与父进程异常终止测试均按当前[VERIFICATION](VERIFICATION.md)认定，源码接线不构成P1已验收或完整生命周期通过声明。

## Module与进程边界

```text
IndexingController → ManagementModule.createIndexing (ACL + immutable source/target + queued)
  → IndexingRuntime claim (new generation + immutable attempt ledger; transaction ends)
  → ProcessTextIndexer → bounded stdin → separate IndexWorker JVM
      → parent lifetime + collection lease → embed → generation-scoped upsert/verify
  ← complete digest manifest + VerifiedRevision
  → ManagementModule.completeIndexing (current claim + ACL + authority identity)
      → publication + source/physical/digest map + active pointer + audit + indexed
        (one authority transaction)
```

[SqliteAuthorityStore](../src/main/java/com/evidence/rag/repository/SqliteAuthorityStore.java)持有唯一authority连接与目录进程锁；[IndexingService](../src/main/java/com/evidence/rag/service/IndexingService.java)定义领取与发布事务。`IndexTarget`绑定embedding identity、projection identity、model revision与维度；`IndexClaim`绑定job/doc/source revision/workspace、attempt、随机claim token、source/parser、target、完整不可变segment快照和`projectionGenerationId`。数据库只保存token摘要；`indexing_attempts`保存每次claim的generation。领取和提交时重验创建者当前写权限、同一parsed revision与segment、attempt台账和generation；旧claim、旧attempt或不匹配结果不能发布。

源身份与投影物理身份必须分开：

| 字段/位置 | 0004含义 |
| --- | --- |
| HTTP任务`revision_id`、publication/列表`active_revision_id` | 不可变source revision，重试不改 |
| `projectionGenerationId` / `projection_generation_id` | 每次claim新UUID；内部物理索引namespace，不作为HTTP可设置参数 |
| Milvus `revision_id` / `Entry.revisionId`、`RevisionManifest.revisionId` | 本次projection generation，不是source revision |
| Milvus `id` / `Entry.segmentId` | 共享`physicalSegmentId(generation, sourceSegmentId)`；对`evidence-rag-physical-segment-v1`、generation、source ID进行长度前缀UTF-8编码后SHA-256，前缀`seg-` |
| `index_publication_entries` | 完整不可变`source_segment_id → physical_segment_id → entry_sha256`映射 |

[ProcessTextIndexer](../src/main/java/com/evidence/rag/worker/indexing/ProcessTextIndexer.java)的Interface是`index(IndexClaim) → IndexingResult`与`close()`。整个应用单索引并发，HTTP线程和authority事务不调用模型/Milvus。`--index-worker`在Spring启动前分流，不打开authority数据库；一个子进程处理一个请求。

父进程清空继承环境，通过有界stdin协议v2传递显式模型/Milvus配置、source segment快照、projection generation及父进程PID/startInstant；不传JWT、authority token、数据目录、用户路径或任意环境变量。协议请求最多8MiB、输出最多1MiB；配置凭据经pipe传递，不在argv、任务HTTP、审计或日志中输出。stdout只用于协议，库日志与stderr被丢弃；协议坏结构、截断、超限、错identity或额外数据失败关闭。

子JVM固定`-Xmx256m`、metaspace 96MiB、direct memory 32MiB、1 active processor与Serial GC，使用独立临时工作目录。父进程总deadline包含进程启动、输入、全部远程调用与输出解码；超时、取消、中断会强制终止并等待子进程和输入线程退出，清理失败不会释放并发permit。固定JVM参数和进程分离不等于OS文件/网络/RSS/CPU沙箱，生产隔离需另行验收。

[IndexWorkerLifetime](../src/main/java/com/evidence/rag/worker/indexing/IndexWorkerLifetime.java)在main入口核对实际父PID，并以PID与startInstant共同判断父存活，避免PID复用。watchdog检测父消失或总deadline后终止独立worker；每次远程阶段也检查lifetime。普通同JVM `run` Interface只中断调用者，不终止宿主JVM。该机制控制本地生命周期；generation隔离负责已送达上游的迟到写入。

worker另以固定`/tmp`下按当前OS用户隔离的lease目录，对规范化endpoint scheme/host/port、database、collection取得跨JVM文件锁；不同应用数据目录也遵守同一物理collection锁。锁路径独立于job临时目录，目录0700、空普通文件0600、同owner、单hardlink、无symlink，不满足POSIX/权限要求就失败关闭。锁文件关闭后保留，永不unlink活跃inode，防止新进程锁到另一inode而形成双writer；每个目标保留一个小lease文件。它不是跨机器分布式锁，也不禁止外部客户端修改Milvus。

同JVM先取得按lease路径哈希选择的本地Semaphore，再打开channel，避免等待者close撤销其他持有者的OS锁。固定64条带限制内存，不累积目标注册表；哈希碰撞会保守串行不同collection，可能耗尽等待预算，不保证不同collection独立并行。本地与文件锁等待均计入总deadline；close幂等，只有确认channel关闭后才释放本地许可，关闭状态不明时保留许可而非冒险放行。

普通退出由父进程清理私有job临时目录；父进程崩溃后可能遗留该目录，需要后续受控回收。当前没有自动孤儿目录清扫或旧generation投影回收，不能把watchdog或lease描述为OS沙箱/完整资源回收验收。

## 完整revision验证与publication

[IndexWorker](../src/main/java/com/evidence/rag/worker/indexing/IndexWorker.java)按响应字节预算与向量维度选择batch，最多16段；无法容纳1段就失败，不截断。模型返回数量、索引、维度、有限值与非零向量，以及完整upsert receipt均严格校验。每批完成只保留摘要，不把整个revision所有向量常驻内存。revision必须包含1–4096段。

`RAG_TEXT_MAX_RESPONSE_BYTES`默认4194304、范围1024–4194304；`RAG_TEXT_DEADLINE_MS`默认30000、范围1–60000。HTTP完整响应体受限，重定向被拒绝；projection的每个公开操作使用一个总budget，完整verify的多次请求共享该budget，外层索引任务deadline继续限制全部操作。

[RetrievalProjection](../src/main/java/com/evidence/rag/client/vector/RetrievalProjection.java)扩展`identity()`和`verify(RevisionManifest) → VerifiedRevision`。验证不使用ANN/top-K抽样：

1. 校验实际collection的identity marker、Strong一致性、完整schema/BM25 function，以及已Finished的dense FLAT/COSINE与sparse SPARSE_INVERTED_INDEX/BM25索引。
2. 使用精确`revision_id == projectionGenerationId` filter、`limit=N+1`读取完整metadata集合，与期望N个唯一物理segment ID严格相等；逐行验证workspace/document/generation。此列仍名为revision_id，但0004值是generation。generation-only过滤有意捕获同generation中错scope的额外行，不能缩窄过滤后将它们隐藏。
3. 按精确物理ID以最多16段的小批次回读text+dense；每行再次检查scope并计算规范摘要。entry摘要绑定physical segment/workspace/document/generation、正文与canonical float32向量（大端编码、规范化零值），完整清单按物理ID排序再计算manifest SHA-256。少行、多行、重复、错scope、正文/向量变化、超限、超时或取消均拒绝。
4. 完成后再次验证完整metadata、两个索引及collection/schema/Strong；全部通过才返回绑定projection identity、manifest hash和segment count的回执。verify本身不修改外部索引配置。

Strong要求来自collection describe，不把未被官方Query契约识别的请求参数当证明。每次claim独占其generation，进程本地调度与同OS用户的跨JVM物理collection lease限制本应用writer；验证至authority提交期间还必须冻结外部writer/运维对当前generation数据、collection/schema/index及一致性配置的修改。多次HTTP回读不是跨请求数据库快照，lease不能替代外部配置冻结；本地stub也不能证明真实Milvus版本的可见性或并发语义。

authority以自身全部source segment ID和当前generation调用共享`physicalSegmentId`重新构造完整物理清单，核对集合、数量、manifest hash与projection identity，并重验claim/attempt台账/generation/source/parser/target/ACL。摘要值由可信内部worker提供；它不是用户可提交的签名生产attestation，也不是authority独立再次调用模型的结果。外部HTTP不能提交回执或设置active。通过后同一事务创建不可变publication、完整source→physical→digest台账、保留source revision的active pointer及审计，再置indexed；半份或旧generation没有active，不能进入未来授权检索scope。

## v3迁移与列表语义

authority打开已有合法Java v1/v2数据库时，持有single-writer锁后逐版本迁移到v3；即使索引开关关闭也执行schema迁移。每个已有版本迁移前用`VACUUM INTO`创建唯一`.partial`文件，成功后原子改名为`java-library.v1-before-v2-*.db`或`java-library.v2-before-v3-*.db`，再执行增量事务。新库直接经过初始化/迁移到v3。

v3新增`indexing_jobs`、不可变`indexing_attempts`、`index_publications`、完整`index_publication_entries`和`active_corpus_publications` sidecar；保留v2原文件、页、segment、source/revision触发器及恒null旧corpus active字段，不重建或放宽它们。publication记录source revision与projection generation，真实active只由publication sidecar读取source revision。

缺少generation字段或attempt/publication-entry台账的早期0004 WIP v3从未发布，当前代码拒绝复用，不静默迁移或继续使用其投影；应使用新独立目录。该检查不改变已发布v1/v2的备份增量迁移路径，也不会读取或修复其他服务数据。

迁移/备份失败拒绝打开，不自动覆盖或恢复。回滚需停止并确认新writer退出，保存新库，再将已验证的对应旧版备份复制到新独立目录交给匹配旧版程序；迁移后新增资料与publication不在旧备份中。没有在线降级、自动回滚或旧Python数据库迁移。

资料列表保留`status`和`latest_job`作为摄取状态；索引成功后摄取仍为`parsed`。独立字段为`index_status`（无任务为`not_indexed`）、`latest_index_job`、`index_publication_id`和`can_index`。合成行`active_revision_id=null`，旧注册身份仅在`registered_revision_id`返回；真实行`registered_revision_id=null`，active只有完整publication后才非空。`can_index`还须与全局`text_index/indexings`能力一起判断，不能凭行能力绕过关闭的路由。

未来`AuthorizedScope`须由authority从当前active publication读取`document → projection generation`后构造，不能直接把对外active/source revision填入Milvus范围。返回候选的physical ID必须按不可变publication entries映射回source segment，再以当前ACL/active和服务器locator读取原证据；这些查询/来源链路仍未接线，完整selected-set复验要求不变。

开启索引时`migration_stage=text_indexing`，新增`text_index/indexings`；摄取能力按其独立开关提供。`answers/sources/reindex/document_delete`仍不可用，全部资料`can_answer=false`，`/health/ready`仍503、production启动仍拒绝。改名/目录/手工标签不改原文件身份或触发模型。

完整文本golden必须等答案链路可运行后重放。四份合成PDF、六个golden、完整selected-set/ACL/active重验、逐事实支持/冲突/条件/否定、服务器引用校验，以及全部多模态和生产目标继续有效；索引publication不能缩减这些验收。
