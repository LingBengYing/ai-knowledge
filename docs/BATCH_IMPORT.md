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

清单登记与每个文件的原件/任务/槽位关联分别在SQLite短事务内提交；
清单、解析页和文本片段使用复用预编译语句的JDBC批写，每256行执行一批，仍在同一事务内原子提交或回滚。
网络模型请求和Milvus批量upsert在事务外执行，完整generation验证后逐资料发布。
某文件失败、取消或请求额度不足时停止该批次后续处理，已完成资料保持；已在途任务完成结算。
上传暂停仅影响尚未上传文件，已接收的任务由后端继续执行。

## 并发配置

| 环境变量 | 默认 | 范围 |
| --- | --- | --- |
| RAG_BATCH_PARSE_CONCURRENCY | 2 | 1–4 |
| RAG_BATCH_INDEX_CONCURRENCY | 2 | 1–4 |
| RAG_BATCH_UPLOAD_CONCURRENCY | 2 | 1–4（当前网页/Node入口采用2路） |
| RAG_BATCH_QUEUE_CAPACITY | 10000 | 32–10000 |

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
