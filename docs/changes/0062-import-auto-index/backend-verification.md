# 后端自动索引续接

当前：LOCAL_BACKEND_VERIFIED_WITH_HISTORICAL_FAILURES，生产和真实模型由发布负责人另行验证。本文只记录本机后端行为，不认证云模型质量或全仓全绿。

## 合同与装配

- v34 仅新增 `import_index_requests`。新 corpus、独立 sound、独立 video_av 原件以及替换候选，在原件/解析任务的同一 admission 事务写入一次续接意图；历史数据不回填。
- `ImportAutoIndexJob` 单消费者，复用现有 `IndexingTaskProcessor` 与当前 `ManagedTextRuntime`；corpus 等待已保存的解析完成，native 媒体调用已有独立 build。浏览器不拥有队列，不提交后续索引请求。
- 初次 corpus 入队在原有索引事务核对上传 revision；替换复用 candidate/base revision 检查。workspace/document/revision 共同绑定查找已有任务。
- corpus 入队提交和续接回执之间的重启缺口，可由同 revision 真实任务闭合；未提交任务可恢复排队。native 不确定在途调用重启后标记 `indexing_interrupted`，不自动重放可能已计费的调用。所有明确失败保持终态，不自动重试。
- 原始资料管理响应新增 `auto_index: {state,error_code,task_id}`，不公开 actor/私密配置。已提交 corpus 继续使用原 `latest_index_job/index_status`。native 仅在完整 publication 与源 revision 匹配后标记 submitted，不伪造索引任务 ID。
- `RAG_IMPORT_AUTO_INDEX_ENABLED` 默认 true。重启设为 false 时不登记新意图、不调度已有 pending；已经提交到旧 `indexing_jobs` 的任务仍按原索引 Job 运行。恢复 true 后只续接 pending，不重试 failed；停用期间上传的资料不回填。

## 数据库与回滚

- `format_info.version` 与 `PRAGMA user_version` 升至 34；`cleanup_schema_objects` 只登记新增表，`cleanup_managed_backups` 保留 33→34 实体备份记录。没有改写旧业务表或旧资料。
- 可用同 schema34 新包关闭自动续接开关回退行为；真正回旧业务代码时，需在冻结的 0061 JAR 上覆盖 schema 兼容类：`AuthoritySchema`、`SqliteAuthorityStore` 及全部 nested class、`WikiPageLifecycleSchema`、`ImportIndexSchema`。这些类不依赖新的自动业务/DTO类。
- 兼容 overlay 必须另验实际副本重开和旧业务 HTTP；不能仅靠类名推定兼容。不得降低 user_version，不得用旧数据库覆盖新业务写入。

## 已执行与进行中

- 首个 RED：旧上传/重启响应没有 `auto_index`，1 项正常断言失败。
- 第二个 RED：持久 admission 已通过，尚无调度实现时解析后不能自动排队，2 项中 1 项断言失败。
- 首切 GREEN：上传与重启回读、解析后重启自动排队且不重复，2/2。
- 扩大回归实际 70 项：67 通过、3 个历史 ACL 断言失败、0 error/skip。新增自动链、native sound/video_av 完整相关文件、v33 副本迁移/全旧业务行和旧 schema 保持/零回填/两次重开、v32→34生命周期迁移、替换配置、响应快照和架构 11 项均通过。
- 最终补充完整相关文件 22/22：自动续接 8、真实 Spring 定时 Job/开关 1、SoundLibraryService 10、WikiDraft/WikiWorkspace 迁移 3。新增 expected revision 拒绝、解析失败不索引、入队前/提交后重启缺口、缺模型失败不自动重试、native 不确定在途不重试、替换候选保持旧原件，均通过。
- 去重合并上述两组为 77 项：74 通过、3 个原失败仍开放。三个失败在 0057 原始报告逐方法/行号/expected-actual 核对一致：IngestionServiceTest.readsAndActionsRequireCurrentAclAndWorkspaceInTheTransaction；IndexingServiceTest.unparsedSyntheticUnknownAndReaderCannotCreateAndAclIsRechecked；IndexingServiceTest.revokedCreatorCanBeFailedButCannotRetryThroughAnotherEditor。未删除、跳过或放宽断言。
- 历史迁移测试只将当前版本改为34，并在旧 schema 比较中明确排除新表，同时新增零回填断言；旧业务行比较与每级备份哈希校验保持。
- 首次 admission GREEN 执行曾因两个本机 Maven 输出目录误重叠而出现 NoClassDefFoundError；该环境无效结果保留，不计产品 RED/GREEN，后续串行重跑通过。实际 RED 为上面两个确定性断言失败。
- 构建输出在本机隔离目录；发布负责人仍须对最终两切统一源码/制品绑定、兼容回滚包与实际页面进行验证。
- 18:35:54 最终选中文件 Spotless 检查通过（实际匹配40个Java文件，0需修改），Git diff whitespace 检查通过；37个本切源码/资源/相关测试输入摘要复核一致。未生成独立0062发布JAR，交由负责人统一构建最终0061+0062。
- 新增真实模型请求 0，未操作生产、未改旧资料、未推送 Git。
