# 批量导入

网页选择多份文档/图片时登记持久批次，以四个并行请求上传文件（分块流式计算SHA-256，单文件内存只占一块）。
上传接收成功即返回；解析、嵌入及向量写入由持久队列继续执行。
批次详情使用独立URL，可刷新查看逐文件状态、回读原资料；尚未上传的槽位可以重新选择原文件继续。

## 接口

1. `POST /v1/document-batches`：JSON包含`request_id`、`files`，可选`embedding_request_limit`。
   每个file包含`filename`、`size_bytes`、`source_sha256`，可选`relative_path`（第三阶段，选择文件夹或拖入文件夹时的相对路径：
   `/`分隔、各段非空且不是`.`/`..`、无反斜杠与控制字符、最多1024字符、最后一段等于`filename`，否则422；等于文件名时视为无路径）。
   只有带路径的文件改变清单摘要，旧清单重放不受影响。`filename`仍是不含路径的文件名（资料名不变）；最多10,000份，合计不超过工作区原件上限（`RAG_WORKSPACE_ORIGINAL_BYTES`，默认10GiB）。
   可选`include_duplicates`（默认false）：为false时，SHA-256与工作区现存（未删除）资料相同、或与本清单更早文件相同的文件
   登记为`duplicate`并跳过——不上传、不解析、不嵌入、不计额度，视为已完成；
   文件行返回`duplicate_of_document_id`（已有资料，或本批更早文件上传后的资料）与`duplicate_of_ordinal`（本批更早文件）。
   只有`include_duplicates:true`改变清单摘要，旧客户端（不发该字段）的同一`request_id`重放仍得到原批次；
   同一`request_id`改变该选项按清单不同返回409`batch_upload_conflict`。
2. `POST /v1/document-batches/{batch_id}/files/{ordinal}`：`application/octet-stream`原字节。
   同槽位相同字节返回已有任务；不同字节拒绝，丢响应先读取槽位，不盲目重传。
   `duplicate`槽位返回409`batch_file_duplicate`（网页把它当作已跳过，不算失败）。
3. `GET /v1/document-batches/{batch_id}?offset=0&limit=100[&state=failed,cancelled]`：权威任务状态与进度。
   可选`state`为逗号分隔的文件状态（`awaiting_upload`…`duplicate`中的已知名称，不重复），只分页这些状态的文件，`matched`为符合数（不筛选时等于`total`）；
   `counts`、批次`state`与操作始终描述整批。文件行另有`relative_path`（文件夹相对路径或null）、`started_at`（文件被接收、解析任务创建的时间）、
   `finished_at`（文件状态最近一次变为indexed/failed/cancelled的时间；重试后清空）；批次另有`created_at`、`updated_at`、`state_filter`与
   `throughput{window_seconds,finished,measured_at}`：最近10分钟（批次不足10分钟则自创建起）内完成的文件数，网页据此算速度与预计剩余时间。
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

7. `GET /v1/document-batches?offset=0&limit=20`（第三阶段）：导入记录，列出当前工作区的批次，按创建时间最新在前；
   `limit`1–100（默认20），只接受`offset`、`limit`。授权与其他批次接口一致：批次属于工作区，同一工作区成员都能看到并打开。
   返回`{"offset","limit","total","items":[{"batch_id","state","total","counts","halted_reason","created_at","updated_at"}]}`，
   `state`的推导与批次详情相同。

8. `POST /v1/document-batches`带`"open":true`与`"expected_total":n`（1–批次文件上限，默认10,000）、不带`files`（或空数组）：登记**开放批次**（v42，见下文“边传边登记”），
   可选`embedding_request_limit`、`include_duplicates`同上；同一`request_id`同样选项重放得到原批次，选项不同409`batch_upload_conflict`。
   不带`open`时`expected_total`必须缺省，原一次性清单行为完全不变。
9. `POST /v1/document-batches/{batch_id}/files`（v42）：JSON`{"files":[{"ordinal","filename","size_bytes","source_sha256","relative_path"?}]}`，1–1,000条，ordinal连续。
   已登记的ordinal必须与原条目完全相同（重放成功、不改变任何东西），否则409`batch_upload_conflict`；新条目必须紧接已登记数（跳号409`batch_manifest_gap`），
   总数不超过`expected_total`、累计字节不超过工作区原件上限（422）；已结束登记的批次只接受重放（新条目409`batch_sealed`），用户取消的批次拒绝新条目（409`batch_action_conflict`），
   一次性清单批次409`batch_not_open`。返回200`{"batch_id","registered","expected_total","open","items":[{"ordinal","state","duplicate_of_document_id","duplicate_of_ordinal"}]}`，
   `items`为本请求各条目的当前状态（新条目为`awaiting_upload`或`duplicate`）。
10. `POST /v1/document-batches/{batch_id}/seal`（v42）：结束开放批次的登记（可少于`expected_total`），重复调用无害（`affected`为0）；一次性清单409`batch_not_open`。返回同批量操作。

批量操作均无查询参数，返回`{"batch_id","action","affected","batch"}`，`batch`为操作后第一页状态。

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

## 体验（第三阶段，2026-10-17）

- **文件夹与拖放**：网页导入窗口新增“选择文件夹”（`webkitdirectory`），拖放区真正接受文件和文件夹（`webkitGetAsEntry`递归，
  最多10,000个文件、32层），隐藏文件/文件夹、Thumbs.db、desktop.ini、`__MACOSX`、`~$`锁文件和当前上传类型不支持的扩展名静默跳过，
  显示“已跳过 N 个不支持的文件”并可展开。文件的相对路径作为清单可选`relative_path`保存（文件名规则禁止`/`，不能放进`filename`，
  也不改资料显示名），批次页显示路径。
- **导入记录**：`GET /v1/document-batches`，网页`#/imports`。
- **批次页**：按状态筛选（全部/失败/处理中/已完成/已跳过（重复），服务端分页）；每个文件的开始/完成时间；整体速度与预计剩余时间。
  速度=最近窗口内完成的文件数÷窗口分钟数（窗口不足30秒或尚无完成时不显示），预计剩余=处理中（queued/parsing/parsed/indexing，
  不含待上传）÷速度；停止/取消的批次不显示。计算在网页（`batchThroughput`），窗口计数在后端。
- **离开页面**：上传进行中`beforeunload`提示；之后在批次页“继续上传”重新选择同一文件夹即可，按文件名+大小+SHA-256匹配未上传槽位，
  同内容同名的多个槽位优先匹配相同`relative_path`；文件夹中不属于本批次的文件（名称+大小都对不上的不计算哈希）跳过并提示数量。
- **嵌入预算**：导入窗口可选“本批嵌入请求上限”并显示估算：字符≈字节，按解析分段（1200字窗口、120字重叠，单文档最多4096段）
  每文档`ceil(段数/31)`次请求求和；中文UTF-8与压缩格式会高估，标注为估算。停止的批次“解除停止并继续”可填写新上限（不低于已预留数）。
- **v41**：新增三表（`document_import_file_paths`、`document_import_file_times`、`document_import_batch_times`）、两个索引
  （`document_import_file_times(batch_id,finished_at)`、`document_import_batches(workspace_id,created_at,id)`）和四个触发器
  （批次插入/停止或改上限时更新`updated_at`；文件状态插入/变化时更新`changed_at`/`finished_at`与批次`updated_at`），共9个对象登记于
  `cleanup_schema_objects`；不改写既有表。时间戳为毫秒UTC文本（`strftime('%Y-%m-%dT%H:%M:%fZ','now')`）。
  迁移按现有批次补齐：文件`changed_at`取其解析/派发/索引任务最近`updated_at`（无任务取批次创建时间），已结束文件的`finished_at`同值；
  批次`updated_at`取创建时间与文件`changed_at`的最大值。升级前保留`v40-before-v41`一致备份；旧v40运行包不能直接打开v41库，回滚须使用v41兼容代码。
- **生产定制入口**须同步：放行`GET /v1/document-batches`（仅`offset`、`limit`），`GET /v1/document-batches/{id}`的query增加`state`
  （`^[a-z_]{1,32}(,[a-z_]{1,32}){0,9}$`），见Web仓库`scripts/wiki-transport.mjs`；无新增静态资源。

## 边传边登记（v42，2026-10-19）

现象：选择3,827个小XML文件后，浏览器要先算完全部SHA-256、登记完整清单才开始第一个上传，期间看不到上传进度。

- **开放批次**：网页（后端报告`batch_streaming_upload`能力时）先登记`open:true`的空批次，每算完一小批指纹（第一批50个，之后每批至多500个，
  或距上次登记满1秒、或上传路空闲时）就追加一段清单并立即上传这一段，后续继续算指纹、追加；最后一段登记后`seal`，上传仍在进行。
  3,827个文件（每个文件读取5ms、往返40ms的模拟）第一个上传约在0.2秒开始，原来约5.7秒（本地文件读取更慢时差距按比例放大）。
- **状态**：开放批次（未`seal`）的批次状态永远不是`completed`/`completed_with_failures`：已登记文件全部完成时显示`awaiting_upload`（等待更多文件）。
  批次详情与导入记录条目新增`open`（布尔）与`expected_total`（开放批次登记时宣布的文件数，一次性清单为null）；开放批次的`available_actions`含`seal`，
  `cancel`在开放期间始终可用。`total`、`counts`只统计已登记的文件。
- **去重**：未选“包含重复”时，每段新条目与资料库现存（未删除）资料、本批更早登记的文件（不含已跳过/已删除）以及同段更早条目比对，规则与一次性清单相同；
  更早文件已上传时，指向它生成的资料（`duplicate_of_document_id`）。
- **额度、停止、取消、继续、重试、列表筛选**：沿用原逻辑（追加不消耗嵌入额度；预算停止时仍可追加登记，上传照原规则被拒；取消后拒绝新条目，继续后可再追加）。
- **刷新与续传**：页面在登记中途关闭时批次保持开放。批次页显示“批次登记未完成：已登记 N / M 份”，可“继续上传”（重新选择同一文件夹：
  已登记未上传的文件按文件名+大小+SHA-256匹配上传；没有同路径（无路径时同名）且同大小条目的文件计算指纹后追加登记并上传，至多到`expected_total`；之后结束登记），
  也可“按已登记的文件结束登记”。选择的文件与本批无关时既不追加也不结束登记。
- **兼容**：一次性清单（不带`open`）的请求、摘要、状态推导完全不变；旧网页不受影响。网页在入口不认识追加路由（404/405且无服务错误码）且尚未登记任何条目时，
  取消该空开放批次并改用一次性清单（新`request_id`），因此网页可以先于入口更新发布。
- **v42**：只新增`document_import_batch_streams`一表（`batch_id`主键、`expected_total` 1–10000、`include_duplicates`、`sealed_at`），登记于`cleanup_schema_objects`；
  不改写既有表，已有批次都是一次性清单（无行）。升级前保留`v41-before-v42`一致备份；旧v41运行包不能直接打开v42库，回滚须使用v42兼容代码。
- **生产定制入口**（Web仓库`scripts/external-server.mjs`的定制版）须同步：
  1) WIKI_ROUTES新增`POST /v1/document-batches/{id}/files`（`^/v1/document-batches/[A-Za-z0-9_-]{1,128}/files$`，与上传路由`/files/{ordinal}`不同），种类同批次JSON接口（`wiki-batch`，要求`application/json`，不带查询）；
  2) 批量操作路由的`(retry-failed|cancel|resume)`加上`seal`；
  3) 追加请求与`POST /v1/document-batches`一样：请求体上限4MiB、计时器用`uploadDeadlineMs`（见Web仓库`scripts/wiki-transport.mjs`的`batchManifestRequest`）；
  4) 无新增静态资源（`batch-upload.mjs`、`wiki-workspace.mjs`、`wiki-workspace-api.mjs`、`wiki-workspace.css`内容更新）。

## 导入归档与知识建设（v43，2026-10-25）

创建批次可增加 `target_folder_id` 和 `preserve_paths`。未提供时与旧版一致；选项进入请求幂等摘要，续传使用该批次持久设置，不能以相同 `request_id` 改变归档方式。

- `target_folder_id` 是当前组织中已有目录的 ID，空值表示未指定。`preserve_paths` 默认为 `false`。
- 保留相对路径时复用现有平面目录，以完整路径作为目录名。例如目标“教材”与清单 `课程/第一课.txt` 对应目录“教材/课程”。这不新增目录树；完整目录名遵守现有 80 字上限，超长会明确拒绝清单，不截断或归并。原文件名、原字节和源版本不变。
- 目录名称以批次创建时目标名称为准；仅本批新上传资料归档。被去重跳过的旧资料保持原目录。目标目录不存在时拒绝后续上传，不静默丢失归档选项。
- 批次详情新增 `target_folder_id`、`target_folder_name`（创建时名称）、`preserve_paths`，供续传回读。配置新增 `batch_organization` 能力；旧后端没有该能力时新前端不展示这些选项。
- `GET /v1/document-batches/{id}/sources?offset=0&limit=50&q=&folder=` 返回可用于知识建设的该批已发布来源。仅返回当前权威发布仍有效、源版本与本批一致且有文字证据的资料；已替换、撤下、未发布或纯视觉资料不会混入。此读取不调用模型，不读取全部正文，也不触发编译。
- 结果为 `{batch_id,total,offset,limit,items,folders}`。每行包含 `ordinal,document_id,filename,display_name,relative_path,publication_id,source_revision_id`；`folders` 是本批合资格来源的实际目录分组 `{path,count}`（无路径为 `""`）。`q` 对文件名、显示名和相对路径做大小写不敏感字面匹配，`folder` 选择该相对目录及其下级；先过滤再计数与分页，`limit` 为 1–100。
- 前端依 `batch_sources` 能力显示“整理为知识页”，显式选择一组资料后使用原提案 API。已有单提案上限 50 份保持，不能把一整个导入批次自动送进模型。提案仍需审阅采纳，读取、勾选或导入完成都不会直接发布知识页。
- v43 只新增 `document_import_batch_placement` 表并登记受管 schema 对象，旧批次没有该表记录时使用默认设置；迁移前保留 `v42-before-v43` 一致备份。生产发布须验证真实 v42 副本及兼容回退包，不能以旧库覆盖新业务。此节描述本地实现，生产状态以当前变更验收记录为准。
- 生产入口需要精确放行新增只读 `GET /v1/document-batches/{id}/sources` 和 `offset,limit,q,folder` 参数；不扩大模型或上传代理权限。
