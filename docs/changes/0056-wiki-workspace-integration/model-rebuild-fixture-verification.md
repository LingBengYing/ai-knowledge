# Wiki 模型页：本机索引重建替身修复

2026-10-09，限定本机合成联调。生产 Java 源码、旧运行目录与旧资料未修改；真实云调用 0。

## 原始症状与可复现反馈

新 Wiki 模型页在生成模型 v2 普通应用成功后，将嵌入 revision 改为 `fixture-v2` 并保存 v3，显式重建得到 `model_rebuild_failed`，完成数 0/3，旧 active v2 保留。

新增 `WikiModelRebuildHttpTest` 使用与页面相同的 `WikiLocalIntegrationServer.start`、真实 Spring/SQLite/上传解析/子进程索引、loopback 模型与向量 HTTP 替身。最小场景只保留一份新 TXT：v1 索引 → generation v2 应用 → embedding revision v3 → 重建必须 `completed`、完成 1/1 且 active v3。

首次有效 RED：`expected completed but was failed`，0/1，`model_rebuild_failed`，任务在约 0.85 秒结束。第二轮仅增加不含凭据/正文的路径与集合名探针，再次 RED。报告分别保留在 `/private/tmp/wiki-model-rebuild-first-red.txt` 与 `/private/tmp/wiki-model-rebuild-protocol-red.txt`；完整临时输出为 `/private/tmp/wiki-model-rebuild-diag`。

复现命令（先设置 JDK21 与明确 CLT PATH）：

```sh
mvn -o -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Drag.build.directory=/private/tmp/wiki-model-rebuild-diag \
  -Dtest=WikiModelRebuildHttpTest test
```

## 假设与原因

探针之前依次提出四个可证伪假设：新集合协议不完整；worker 参数/启动错误；向量写完后 publication 事务冲突；保存材料与新目标身份不兼容。

实际请求仅有：

```text
/v2/vectordb/collections/has      collection=java_text_v3_2daf0282dc1033ceaf49c21ec207b635
/v2/vectordb/collections/describe collection=java_text_v3_2daf0282dc1033ceaf49c21ec207b635
```

没有新 upsert。生产 `ManagedTextSettings.rebuildAnchor` 为新嵌入版本创建独立集合；旧 `IndexingTestServer` 对任意集合都回答存在，但 describe 永远返回 `java_index_process_fixture` 与旧嵌入 marker。`MilvusSchema.validate` 正确拒绝集合名不一致，错误是 `projection_schema_mismatch`，外层任务归并为 `model_rebuild_failed`。这是本机替身不支持生产的迁移协议，不是新页面保存了错误模型，也不是需放宽生产 schema 校验。

## 最小修复

- `IndexingTestServer` 新增显式 `managedCollections` 构造参数，旧构造仍为 false，保持已有测试替身行为。
- opt-in 时新集合必须先 create；has/describe/index 信息来自该集合的实际创建请求，缺失集合不冒充已存在。
- upsert/query/search 按 collection 分离数据，不让新集合覆盖旧集合行。
- 仅 `WikiLocalIntegrationServer.main` 与新回归显式启用该模式。生产代码、协议、安全校验与真实 Milvus 配置未改变。
- 没有改变运行中的旧实例或失败任务；新页面复验必须使用新独立实例与新合成数据，不将旧 SQLite 接到丢失旧向量的空替身。

首次 GREEN：完整新 HTTP 回归 1/1、0 失败/跳过，15.38 秒，确认 v3 新集合完成索引并应用。全量格式检查 1176 文件通过。

13:09:31 +08 完成已启动的最终相关回归：26 个直接引用该替身的测试类，70 项执行，63 通过、7 失败、0 error、0 skip，整轮 7:13。`WikiWorkflowHttpTest` 与 `WikiModelRebuildHttpTest` 均通过（22.83 秒与 10.08 秒）。本轮不是全部回归通过，失败没有删除、跳过、放宽或重跑。

失败明细：

| 测试 | 失败点 |
| --- | --- |
| `IndexingHttpTest.authorizedIndexingRunsRealChildAndPublishesOnlyCompleteEvidence` | 第 179 行旧权限期望 404，实际 200 |
| `IndexingHttpTest.indexActionsRejectBodyQueryAndUnauthorizedRequestsBeforeSideEffects` | 第 196 行旧权限期望 404，实际 202 |
| `DocumentRemovalProcessTest.revokedClaimFailsWithoutAWorkerAndDoesNotBlockTheNextAuthorizedDocument` | 第 103 行期望不启动 worker、authorization_changed，实际启动 1 次、indexing_failed |
| `IndexingTaskProcessorTest.revokedWriterInterruptsRunningChildAndCannotPublish` | 第 133 行撤权终止断言不符 |
| `IndexingTaskProcessorTest.timeoutKillsChildAndTheNextQueuedDocumentStillIndexes` | 第 228 行等待调度状态转换超出测试期限；未另行定位或重跑 |
| `VideoIndexingServiceTest.fourModalitiesKeepTheFullScopeWhenTextAnswersDoNotRetrieveVideo` 两个参数用例 | 第 238 行旧所选范围期望 abstained，当前共享全库实际 answered |

以上旧测试仍使用 `managedCollections=false`，其默认路径没有启用本次新增能力；其中旧权限/所选范围断言与当前共享组织合同不一致。没有用这项解释覆盖超时用例，也没有以诊断替代后续必要的回归整治。

最终命令在同一独立 build 中执行 `spotless:check test`，`-Dtest` 明确列出 `rg -l 'IndexingTestServer' src/test/java --glob '*Test.java'` 返回的 26 个类。完整逐类结果位于 `/private/tmp/wiki-model-rebuild-diag/surefire-reports`。

负责人随后要求改用线上模型、真实流程验收。本智能体只完成已启动的回归后停止，没有新建/重启替身实例，也没有继续额外实验或云请求。以上本机结果只保留为开发回归证据，不作该真实流程验收结论。

## 防止重现与范围

新回归锁定真实 call site（模型设置 → 新集合 → 重建发布），而不是只验证表单或 save/activate 的返回。后续增加模型迁移操作时，联调替身必须模拟集合生命周期，不能以固定 schema 响应充当所有 collection。没有新增生产日志或留存调试正文；失败报告的探针只含路由和合成集合名。

本证据不认证真实供应商、真实 Milvus 或生产迁移质量。此前浏览器失败是真实发生的本机失败，保留记录，不被后续新实例成功覆盖。
