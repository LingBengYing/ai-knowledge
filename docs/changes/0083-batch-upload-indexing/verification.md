# 0083 本地验证（2026-10-16）

## 实现

- 批次清单持久化、逐槽位SHA校验，原件/任务/槽位原子提交；相同字节重复上传回读原任务。
- 网页2路上传，后台解析/索引各默认2路、可配置1–4；固定消费者取SQLite队列，不堆积内存任务。
- 索引claim最多32候选、同资料互斥；自动索引每轮最多派发32条；集合租约只覆盖初始化，模型等待不持集合锁。
- 清单、解析页和片段复用PreparedStatement，每256行executeBatch，整体事务提交或回滚。
- 既有最多16片段embedding与Milvus批写、完整generation校验和逐资料发布保持，没有跨资料合并模型请求。
- 批次额度原子预留；任务/资料关联started/finished；失败不自动退回预留或重试。
- 使用codebase-design技能收敛为小型批次接口，保留既有authority恢复，不引入第二套JobRepository。

## 最终结果

独立构建：`/private/tmp/ai-knowledge-batch-final-0083.mgbZid`，Maven参数为`-Drag.build.directory=...`。
JDK22/Maven3.9.9离线依赖。最终日志`/private/tmp/knowledge-batch-final5.log`，原断言45/45：

| 类 | 通过 |
| --- | ---: |
| DocumentBatchServiceTest | 3 |
| DocumentBatchMigrationTest | 2 |
| DocumentBatchHttpTest | 1 |
| BatchPipelineConcurrencyTest | 1 |
| BoundedTaskJobTest | 1 |
| IndexWorkerModelHttpLogTest | 4 |
| IndexWorkerLifetimeTest | 4 |
| ProcessTextIndexerTest | 6 |
| ProcessTextParserTest | 13 |
| ImportAutoIndexServiceTest | 9 |
| IndexingTaskProcessorTest.timeoutKillsChildAndTheNextQueuedDocumentStillIndexes | 1 |

6文档合成流水线2700ms：先阻塞2个embedding，确认无第3个进入，释放后6份独立发布；
observed_requests=6、usage_uncertain=false，重开同批仍completed。只代表本机回环替身，不代表生产吞吐。
3,827项清单登记/分页本轮32ms，未创建资料或调用模型，末页27条及重开通过。
并发重复上传只一份原件；两个同时额度预留仅一个获准；JDBC后续条目失败整体回滚0条。
真实Spring HTTP清单/原字节/重复上传/自动索引/原件SHA通过；上游只用回环模型和Milvus替身。
历史v37快照→v38全部旧业务行比较、二次重开、仅一次升级备份通过，未使用生产库副本。

Web最终串行466/466：`/private/tmp/knowledge-batch-web-final4.log`；两个真实Node入口的大清单JSON、
原字节槽位、精确分页白名单通过。受限沙箱曾116项listen EPERM，允许回环后原断言全部通过。
实际浏览器选择两份合成TXT→确认导入→自动索引→刷新同URL已完成2份，未手工建索引。
地址`http://127.0.0.1:52629/wiki/#/imports/2cbea9ac-8616-4b8e-83dc-d9b446db688f`，
截图为工作区`.local/batch-preview-0083/completed.jpg`，预览数据完全独立，未装载生产密钥/资料。

限定Java格式、独立目录package、JavaScript语法、两仓git diff --check通过。
JAR SHA256 `4b73d66d72e03cd6304ee47f01db65203c21c8b864dbb5bc2f754dac11731066`，
包内新增类型齐全且无旧DocumentBatchResponse残留；包含其他WIP，**不是生产候选**。
跟踪文件扫描均[]，另用同检查器检查未跟踪文件Java72/Web21均无发现。

## 未关闭边界

扩大Java回归非全绿。隔离HEAD基线`/private/tmp/ai-knowledge-batch-baseline.DKhyJJ`的26项12失败：
IndexingMigration4、IngestionHttp4、IndexingHttp2、IndexingJwtHttp1、ArchitectureRules1，日志`/private/tmp/knowledge-batch-baseline.log`。
最终含架构的56项55过1旧失败（`/private/tmp/knowledge-batch-final4.log`），仅剩同HEAD的
IndexingRepository.migrationManifest四处client.vector依赖；新增DTO分层违规已修，不放宽规则。
早期负载下短截止worker/租约失败保留，相关项已在最终45项以原断言通过，未删断言/加超时。
旧target同步残留重复class曾导致扫描等待；未删除目录内容，改用独立干净构建。

没有真实供应商请求、生产副本迁移、Git提交/推送、部署、TED上传或嵌入；4000次授权未用。
真实provider并发/限流、全3827份吞吐、全仓覆盖率和发布验收不在本次通过范围。
部署前须隔离图片WIP并备份生产库；v38之后不能用拒绝v38的旧JAR或旧库覆盖新业务。
