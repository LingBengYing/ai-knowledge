# 批量导入

网页选择多份文档/图片时登记持久批次，以两个并行请求上传文件。
上传接收成功即返回；解析、嵌入及向量写入由持久队列继续执行。
批次详情使用独立URL，可刷新查看逐文件状态、回读原资料；尚未上传的槽位可以重新选择原文件继续。

## 接口

1. `POST /v1/document-batches`：JSON包含`request_id`、`files`，可选`embedding_request_limit`。
   每个file包含`filename`、`size_bytes`、`source_sha256`；最多10,000份，合计256MiB。
2. `POST /v1/document-batches/{batch_id}/files/{ordinal}`：`application/octet-stream`原字节。
   同槽位相同字节返回已有任务；不同字节拒绝，丢响应先读取槽位，不盲目重传。
3. `GET /v1/document-batches/{batch_id}?offset=0&limit=100`：权威任务状态与进度。
   `state`为`awaiting_upload`、`processing`、`completed`、`completed_with_failures`（全部结束但有失败/取消/删除）、
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
| RAG_BATCH_PARSE_CONCURRENCY | 2 | 1–4 |
| RAG_BATCH_INDEX_CONCURRENCY | 2 | 1–4 |
| RAG_BATCH_UPLOAD_CONCURRENCY | 2 | 1–4（当前网页/Node入口采用2路） |
| RAG_BATCH_QUEUE_CAPACITY | 10000 | 32–10000 |
| RAG_BATCH_TRANSIENT_RETRIES | 3 | 0–10（一次尝试内的临时错误重试总次数） |
| RAG_BATCH_RETRY_BASE_DELAY_MS | 1000 | 1–60000（指数退避基数） |
| RAG_BATCH_RETRY_MAX_DELAY_MS | 60000 | 基数–600000（单次等待上限，含Retry-After） |
| RAG_BATCH_INDEX_TIMEOUT_PER_BATCH_MS | 5000 | 0–60000（每个嵌入请求增加的索引期限） |
| RAG_BATCH_INDEX_TIMEOUT_MAX_MS | 600000 | 10–600000（索引期限上限） |

解析与索引各有固定消费者，任务堆积留在SQLite，不创建与文件数等量的线程。
独立子进程总容量各4；集合创建/加载保持短独占，文档generation写入不占用集合锁等待模型。
索引沿既有算法按最多16片段调用embedding并批量写向量，不做跨资料合并计费请求。
并发上限约束本条索引流水线，不代表整个模型供应商账号的RPM/TPM限流。

`reserved_requests`为原子预留上界，失败也不自动退回；`observed_requests`为实际started计数。
`usage_uncertain=true`表示在途/审计未完整闭合，不能当作零请求。
日志以`index_model_request`关联task/document/request ID，仅记录固定字段，不记录原文或密钥。

## 框架选择与迁移

参考[Spring Batch 6并发/分块模型](https://docs.spring.io/spring-batch/reference/scalability.html)。
当前复用既有authority任务表、不可变attempt/generation与重启恢复，不引入第二套JobRepository。
后续跨节点分区作业再评估Spring Batch。

SQLite v37→v38只新增批次与请求预留三表，原资料/索引/对话表不改写；首次升级前保留一致备份。
旧v37运行包不能直接打开v38库，生产发布须准备v38兼容回滚代码，不能旧库覆盖后续业务。
v38→v39只新增`batch_task_requeues`一表（记录自动重新排队的尝试），不改写既有表；升级前同样保留一致备份。
旧v38运行包不能直接打开v39库，回滚须使用v39兼容代码，不能旧库覆盖后续业务。
