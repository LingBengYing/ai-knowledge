# 批量导入

网页选择多份文档/图片时登记持久批次，以四个并行请求上传文件（分块流式计算SHA-256，单文件内存只占一块）。
上传接收成功即返回；解析、嵌入及向量写入由持久队列继续执行。
批次详情使用独立URL，可刷新查看逐文件状态、回读原资料；尚未上传的槽位可以重新选择原文件继续。

## 接口

1. `POST /v1/document-batches`：JSON包含`request_id`、`files`，可选`embedding_request_limit`。
   每个file包含`filename`、`size_bytes`、`source_sha256`；最多10,000份，合计不超过工作区原件上限（`RAG_WORKSPACE_ORIGINAL_BYTES`，默认10GiB）。
   可选`include_duplicates`（默认false）：为false时，SHA-256与工作区现存（未删除）资料相同、或与本清单更早文件相同的文件
   登记为`duplicate`并跳过——不上传、不解析、不嵌入、不计额度，视为已完成；
   文件行返回`duplicate_of_document_id`（已有资料，或本批更早文件上传后的资料）与`duplicate_of_ordinal`（本批更早文件）。
   只有`include_duplicates:true`改变清单摘要，旧客户端（不发该字段）的同一`request_id`重放仍得到原批次；
   同一`request_id`改变该选项按清单不同返回409`batch_upload_conflict`。
2. `POST /v1/document-batches/{batch_id}/files/{ordinal}`：`application/octet-stream`原字节。
   同槽位相同字节返回已有任务；不同字节拒绝，丢响应先读取槽位，不盲目重传。
   `duplicate`槽位返回409`batch_file_duplicate`（网页把它当作已跳过，不算失败）。
3. `GET /v1/document-batches/{batch_id}?offset=0&limit=100`：权威任务状态与进度。
   文件`state`另有`duplicate`；`counts.duplicate`计数。批次`state`为`awaiting_upload`、`processing`、`completed`（已索引+跳过=总数）、`completed_with_failures`（全部结束但有失败/取消/删除）、
   `halted`（预算耗尽或用量不确定）或`cancelled`（用户取消）；
   `retryable_files`为仍可批量重试的文件数，`available_actions`列出当前适用的批量操作（`retry_failed`、`cancel`、`resume`）。
4. `POST /v1/document-batches/{batch_id}/retry-failed`：把本批每个失败/已取消且尚有尝试次数的文件重新排队，
   依次为解析重试（同时重新进入自动索引）、失败的自动索引派发重新派发、索引任务重试；均为显式重试，停止的批次也会执行，仍受额度约束。
   用户取消的批次返回409`batch_action_conflict`。
5. `POST /v1/document-batches/{batch_id}/cancel`：把`halted_reason`记为`batch_cancelled`，取消本批排队中的解析/索引任务，
   此后拒绝上传、不再启动自动任务；正在处理的任务照常完成。已取消的批次再次取消返回409。
6. `POST /v1/document-batches/{batch_id}/resume`：可选JSON`{"embedding_request_limit":n}`（1–1,000,000且不低于已预留数）。
   清除`halted_reason`（预算、用量或用户取消），并把本批已取消的自动任务（首次尝试或自动重新排队的尝试）重新排队；
   重新排队取下一次尝试（任务状态机只允许failed/cancelled→queued且attempt+1），仍计为自动；已到第3次的任务保持取消。
   未停止的批次返回409；`embedding_request_limit`不合法返回422。

三个批量操作均无查询参数，返回`{"batch_id","action","affected","batch"}`，`batch`为操作后第一页状态。

清单登记与每个文件的原件/任务/槽位关联分别在SQLite短事务内提交；
清单、解析页和文本片段使用复用预编译语句的JDBC批写，每256行执行一批，仍在同一事务内原子提交或回滚。
网络模型请求和Milvus批量upsert在事务外执行，完整generation验证后逐资料发布。
只有两种原因会停止整批：嵌入请求额度不足（`embedding_budget_exhausted`）和用量审计不完整（`model_usage_uncertain`）。
单个文件解析失败、索引失败、自动索引派发失败，或用户取消单个任务，都只影响该文件，同批其余文件继续处理。
停止后排队中的自动任务在领取时取消（可“解除停止并继续”），已完成资料保持，在途任务完成结算。
上传暂停仅影响尚未上传文件，已接收的任务由后端继续执行。
解析重试（单个、批量、重启或继续）会把该版本失败的自动索引请求恢复为待派发，解析成功后照常建立索引，批次行显示真实状态。

## 临时错误自动重试与重启

采用任务内重试，不做带等待时间的重新排队：重试一个失败的嵌入请求即可，重新排队要从头重新嵌入整篇文档，
在限流时成倍放大费用；同一尝试只占一份预留与一个generation，原有尝试/预留/发布的隔离不变。
解析器或索引器繁忙发生在启动任何子进程或模型请求之前，原地等待后再试即可。

- 嵌入HTTP 429、5xx、超时和连接错误在索引子进程内对该请求退避重试；有`Retry-After`（秒数或HTTP日期）时严格按其等待，
  超过`retry-max-delay-ms`或剩余期限（保留1秒）则不再等待，本次尝试按原失败结束。
- 一次尝试的全部重试次数合计不超过`RAG_BATCH_TRANSIENT_RETRIES`。批次预留时在“预期请求数”之外，
  只从额度的剩余空间里再预留至多这么多次作为重试额度（剩余不足时减少，为0则不重试）；
  每次重试都是一次新的HTTP请求，计入`observed_requests`。审计完整闭合后退回未用的重试额度，
  预期请求部分仍按原规则不退回，`reserved_requests`始终是已发出请求数的上界。
- 并行时另一文件的重试额度可能暂时占住剩余额度：若差额不超过同批其他正在运行、审计尚未记录的尝试的预留之和，
  本尝试在自己的期限内每500ms重新预留，等它们退回，而不是立即以`embedding_budget_exhausted`停止批次；
  即使全部退回仍不够、或期限将到，才按原规则停止。这样重试额度不会让本来够用的额度误停批次。
- `parser_busy`、`indexing_busy`在同一任务内按相同退避等待，最多同样次数，不消耗额度。
- 重启时，属于导入批次且处于处理中的解析/索引任务先记为取消、再回到队列，取下一次尝试并记入v39`batch_task_requeues`（自动尝试）；
  三次尝试上限约束崩溃循环，第三次仍被中断则按`worker_interrupted`失败。所属批次已停止时改为取消（可继续）。
  被中断尝试的预留不退回，审计未闭合，`usage_uncertain`如实为true，但重启本身不停止批次。
  非批次任务仍按原规则标记`worker_interrupted`。
- 停止只拦截自动尝试（首次尝试和自动重新排队的尝试）；用户显式重试照常执行，仍受额度约束。

## 索引期限

一次文本索引尝试的期限 = `RAG_INDEXING_TIMEOUT_MS`（基础） + `RAG_BATCH_INDEX_TIMEOUT_PER_BATCH_MS` × 嵌入请求数，
不超过`RAG_BATCH_INDEX_TIMEOUT_MAX_MS`（且不低于基础值，协议上限600秒）。
同一期限覆盖全部嵌入请求及其重试等待、向量写入和回读校验；性质：期限随分段数单调不减，有上界。

## 并发配置

| 环境变量 | 默认 | 范围 |
| --- | --- | --- |
| RAG_BATCH_PARSE_CONCURRENCY | 4 | 1–4（解析消费者数） |
| RAG_BATCH_INDEX_CONCURRENCY | 4 | 1–4（文本索引消费者数，即同时索引的文档数） |
| RAG_BATCH_UPLOAD_CONCURRENCY | 4 | 1–8（报告给网页的上传路数；网页与Node入口采用4路） |
| RAG_BATCH_MEDIA_INDEX_CONCURRENCY | 2 | 1–4（声音/音视频自动索引派发线程，与文本派发分开） |
| RAG_BATCH_EMBEDDING_CONCURRENCY | 4 | 1–8（单个文档同时在途的嵌入请求） |
| RAG_BATCH_EMBEDDING_REQUESTS_PER_MINUTE | 1000 | 0–100000（全进程共享嵌入请求速率，0=不限） |
| RAG_BATCH_EMBEDDING_TOKENS_PER_MINUTE | 400000 | 0–100000000（全进程共享估算输入token速率，0=不限） |
| RAG_WORKSPACE_ORIGINAL_BYTES | 10737418240 | 1MiB–1TiB（单工作区原件总字节，原固定256MiB） |
| RAG_BATCH_QUEUE_CAPACITY | 10000 | 32–10000 |
| RAG_BATCH_TRANSIENT_RETRIES | 3 | 0–10（一次尝试内的临时错误重试总次数） |
| RAG_BATCH_RETRY_BASE_DELAY_MS | 1000 | 1–60000（指数退避基数） |
| RAG_BATCH_RETRY_MAX_DELAY_MS | 60000 | 基数–600000（单次等待上限，含Retry-After） |
| RAG_BATCH_INDEX_TIMEOUT_PER_BATCH_MS | 5000 | 0–60000（每个嵌入请求增加的索引期限） |
| RAG_BATCH_INDEX_TIMEOUT_MAX_MS | 600000 | 10–600000（索引期限上限） |

解析与索引各有固定消费者，任务堆积留在SQLite，不创建与文件数等量的线程。
独立子进程总容量各4；集合创建/加载保持短独占，文档generation写入不占用集合锁等待模型。
索引每个嵌入请求最多32片段（见下节），批量写向量，不做跨资料合并计费请求。
嵌入速率由下节的共享令牌桶约束，不再依赖消费者数量间接限速；它约束本机全部索引进程，不代表供应商账号在其他机器上的用量。

`reserved_requests`为原子预留上界，失败也不自动退回；`observed_requests`为实际started计数。
`usage_uncertain=true`表示在途/审计未完整闭合，不能当作零请求。
日志以`index_model_request`关联task/document/request ID，仅记录固定字段，不记录原文或密钥。

## 吞吐（第二阶段，2026-10-17）

- **每请求片段数**：SiliconFlow `/v1/embeddings` 的`input`数组上限32条
  （[文档](https://docs.siliconflow.com/cn/api-reference/embeddings/create-embeddings.md)；单条token上限bge-m3为8192、Qwen3为32768，
  分段远小于此）。实际取`min(32, 响应上限, Milvus请求上限)`：响应上限=(`RAG_TEXT_MAX_RESPONSE_BYTES`−1024)/(维度×128+1024)，
  默认4MiB时1024维为**31**、2048维为15；Milvus侧(4MiB−1024)/(16384×6+维度×32+2048)≥40。
  另按JSON转义上界把单请求输入限制在模型请求上限1MiB的3/4内，超长片段自动拆成更小请求。
  父进程`expectedRequests`与子进程使用同一规划，额度预留与实际请求数一致。
- **文档内并发**：一次索引尝试最多`RAG_BATCH_EMBEDDING_CONCURRENCY`个嵌入请求在途（有界窗口，只保留窗口内向量），
  Milvus upsert仍在调用线程按片段顺序逐批写入，最后整体校验generation；任一请求失败即停止提交并中断其余请求。
  每个请求（含重试）都记录started/finished审计，`observed_requests`、预留、`usage_uncertain`与第一阶段任务内重试保持精确；
  重试次数为整次尝试共享的同一额度。
- **共享限速**：令牌桶状态放在私有租约目录（`/tmp/evidence-rag-index-leases-v1-<hash>`）中按嵌入端点+模型+限值命名的0600文件，
  所有索引子进程在文件锁内取令牌：请求速率与估算token速率（UTF-8字节/3）各一桶，容量为2秒的量；
  单个请求超过token桶容量时等满桶后透支，长期速率不变。每次HTTP尝试（含重试）前取令牌，等待计入索引期限。
  文件被篡改（属主、权限、链接、大小）时按`indexing_lifetime_failed`失败，不放开限速。
- **解析并行与媒体**：图片OCR与音频/视频原生解码各有一个独立的公平许可（解析器本身仍各只允许一个子进程），
  图片不再等待音视频；OCR视频按“媒体→OCR”固定顺序取两个许可，不会死锁。
  声音/音视频自动索引由独立派发线程执行，不再占用唯一的文本派发线程；媒体库繁忙（`*_index_busy`）时把请求放回待派发并退避。
- **公平领取**：解析与索引领取都先取不属于任何批次的交互式任务（最早优先），再按批次轮转：每个活动批次取最早的几个候选，
  从上次被服务批次之后的批次开始交错。轮转游标在内存中，重启后从头开始，不影响正确性。
- **唤醒与空闲退避**：SQLite提交若改动了`ingestion_jobs`/`indexing_jobs`/`import_index_requests`，提交后发进程内信号；
  空闲消费者等待信号或从100ms倍增到1.5s的空闲期，信号只缩短等待，领取仍以SQLite为准（丢失或多余信号不会丢/造任务）。
  过期领取检查仍每100ms。
- **批次计数**：v40新增`document_import_file_states`，每个批次文件一行，由触发器在同一事务内按与原四表推导相同的规则重算
  （文件行、解析任务、自动索引请求、索引任务、删除墓碑任一变化时只重算受影响文件）；状态页与计数改为读这张表的`GROUP BY`，
  不再每次轮询做四表连接。重试、取消、继续、重启都走同一触发器，测试逐步比对缓存与完整推导一致。
- **上传通道**：网页4路上传；Node入口对声明了Content-Length的上传按流转发（先按声明长度判断413/空文件，再边收边发，
  不在入口缓存整个文件），无Content-Length的分块请求仍按上限缓冲；每个登录主体最多4个、全入口最多8个上传同时进行，
  上传不再占用长请求名额。Java端仍读取完整请求体（≤20MiB）。
- **生产定制入口**：生产使用的定制`external-server.mjs`须同步：
  1) 引入`scripts/upload-transport.mjs`的`streamUpload`（或等价逻辑），上传先按Content-Length判413/400再流式转发，保留20MiB上限和30秒期限；
  2) 用`createUploadAdmission()`替换`activeUploads>=2`，以`/v1/session`返回的`workspace_id`+`principal_id`为客户端键（每客户端4、合计8），
     上传不计入长请求名额；
  3) 静态资源加入`/sha256-stream.mjs`（`scripts/wiki-transport.mjs`的WIKI_ASSETS已加入）；
  4) `POST /v1/document-batches`的JSON原样透传（含`include_duplicates`）。
- **预期效果**（未用真实模型实测，按瓶颈推算）：原来索引2路×每文档串行、每请求16片段；现在4路文档×每文档4请求在途、每请求31片段，
  同样分段数的请求数约减半、并发约8倍，实际被`RAG_BATCH_EMBEDDING_REQUESTS_PER_MINUTE`/`TOKENS_PER_MINUTE`和供应商限额封顶——
  默认1000 RPM×31片段约每分钟3万片段，或40万token/分钟（约1300字/片段时约300片段/分钟，token通常先成为瓶颈）。
  解析4路、图片不再等音视频；上传4路且入口不缓存文件；重复文件不再上传和嵌入。

## 框架选择与迁移

参考[Spring Batch 6并发/分块模型](https://docs.spring.io/spring-batch/reference/scalability.html)。
当前复用既有authority任务表、不可变attempt/generation与重启恢复，不引入第二套JobRepository。
后续跨节点分区作业再评估Spring Batch。

SQLite v37→v38只新增批次与请求预留三表，原资料/索引/对话表不改写；首次升级前保留一致备份。
旧v37运行包不能直接打开v38库，生产发布须准备v38兼容回滚代码，不能旧库覆盖后续业务。
v38→v39只新增`batch_task_requeues`一表（记录自动重新排队的尝试），不改写既有表；升级前同样保留一致备份。
旧v38运行包不能直接打开v39库，回滚须使用v39兼容代码，不能旧库覆盖后续业务。
v39→v40新增`document_import_file_states`表、6个索引与7个触发器（共14个对象登记于`cleanup_schema_objects`），
按原推导为现有批次文件补齐状态行；不改写既有表行。升级前保留`v39-before-v40`一致备份。
旧v39运行包不能直接打开v40库，回滚须使用v40兼容代码。
（修正：资料清除后的VACUUM复核原固定校验v38，已改为校验当前v40。）
