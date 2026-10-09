# 0057 发布 Java 验证：完整回归未完成，直接修复回归已收口

记录时间：2026-10-09 15:15:30 +08 后收口。状态：`NOT_COMPLETED_WITH_RECORDED_FAILURES`。本文不认证完整发布门禁或真实模型质量。

## 快照和最终制品

全量命令为离线 Maven `verify`，无 `-Dtest` 过滤、无跳过测试参数、未删除或放宽断言。使用 JDK `/Applications/PyCharm.app/Contents/jbr/Contents/Home`、仓库 `.mvn/settings.xml`，独立目录 `/private/tmp/knowledge-agent-release-0057.0v8SyD`，只使用新临时合成资料和本机协议替身，没有云调用或生产数据操作。默认 Surefire 不运行 `*LiveIT`；本次没有另开真实 provider/native 专项。

该全量快照包含 Agent 关停资源释放修复，但在本轮发现的数据库清理校验修复之前编译。它与最终源码的唯一输入差异是 `SqliteAuthorityStore.compact()` 把压缩后的 `verifyVersionTwentyNine()` 改为当前启动采用的 `verifyVersionThirtyTwo()`，没有降低格式校验。1207 个源码/资源/pom 输入清单分别归档，不能把旧快照全量称为最终包全量通过。

最终 JAR：`/private/tmp/knowledge-agent-final-artifact-0057.aZRKFB/rag-java-0.1.0-SNAPSHOT.jar`。

SHA-256：`35fff38ca5eae6634804c5a5866bd31b9b9f678846ddf926b4309c99fb0cd34c`。

最终包从已完成编译的直接回归目录复制 classes 后，执行完整 `spotless:check jar:jar spring-boot:repackage`，未再次启动编译或测试。938 个生产 class/resource 文件与直接回归目录逐字节相同，1207 个输入无漂移；1191 个 Java 文件格式全部通过。打包成功不等于完整测试通过。

## 全量运行的实际结果与停止原因

2026-10-09 15:15:29 +08，发布协调者明确要求结束修复前快照全量，保留全部结果，不再启动任何测试。核对进程链 Maven `67632` → shell `69183` → test JVM `69188` 后，仅向 test JVM 发送温和 TERM；15:15:30 Maven 退出 1，test JVM 退出 143，随后确认三个 PID 全部不存在。没有删除任何测试、断言或报告。

停止时测试仍有进度；这是授权收尾，不能记为已证实死锁。此前 `ArchitectureRulesTest` 耗时 414 秒，最终 11/11 通过；诊断时主线程处于 JUnit 临时目录删除，堆已用约 528 MB，主机 swap 使用约 19 GB。该信息只说明观察结果，不证明浏览器超时由此造成。

已落盘的 309 个完整套件报告共 **1838 项：1757 通过、78 失败、3 错误、0 跳过**。`SoundLibraryServiceBoundaryTest` 正在运行时收到 TERM，没有完整 XML；它与后续未完成测试不计为通过。日志里的 `Crashed tests` 是本次主动 TERM 的 Surefire 表述，不是另行发现的应用崩溃。

| 失败/错误分类 | 数量 | 处理 |
| --- | ---: | --- |
| 真实清理压缩后仍校验 schema 29 | 16（13 failure + 3 error） | 原0054/schema30与新schema32合成路径均复现；最终包已修，完整相关文件重跑 |
| 旧组织内共享、角色/ACL或原文引用可见性预期 | 50 failure | 原断言与实际结果完整保留；本轮未按旧逐文档权限回改生产 |
| 旧选中文档范围、请求输入校验或问题长度预期 | 15 failure | 原断言完整保留；不一律归类为ACL，未扩改生产 |

分类指观察到的失败断言及本轮定位，不把保留失败改记为通过或永久豁免。`AnswerCommand`、`DocumentSelection`、`SoundRequestMapper`、`VideoAvQueryRequestMapper` 的实际 class 与0054发布JAR逐字节一致；这些例子能确认相关合同变化不是本次 Agent 引入，不能替代对所有历史失败的未来收敛。

完整 `verify` 未完成且已有失败，因此整体发布回归门禁未通过。生命周期内后续格式/覆盖率检查未到达；最终制品格式另行完整通过，覆盖率门禁本轮没有结果。停止后没有再开测试或覆盖率任务。

## 最终包直接回归

最终源码的 7 个完整相关测试文件累计 **65 项：60 通过、5 失败、0 错误、0 跳过**。没有只挑新用例，也没有删除旧失败。

| 文件 | 项数 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| DocumentCleanupMigrationTest | 6 | 0 | 0 | 0 |
| DocumentCleanupPermissionBoundaryTest | 27 | 2 | 0 | 0 |
| DocumentCleanupRepositoryTest | 12 | 1 | 0 | 0 |
| WikiDraftMigrationTest | 1 | 0 | 0 | 0 |
| WikiWorkspaceMigrationTest | 2 | 0 | 0 | 0 |
| DocumentCleanupServiceTest | 8 | 2 | 0 | 0 |
| KnowledgeAgentServiceTest | 9 | 0 | 0 | 0 |

原16个清理失败点的压缩错误均消除，其中15个用例完整转绿；`completedReceiptNeedsAllNineResourcesAndIsImmutableWhileAclPaginationRemainsCurrent` 已通过真实 purge/compact、九类资源完成与 deleted 断言，随后在旧 reader 角色应不可见断言失败。因此不能把这一整个用例说成通过。

保留的5条直接失败全部位于三个清理文件：组织共享与旧 ACL 的预期差异。Agent 完整9项通过，包含 `closeBeforeReservedWorkerStartsReleasesLibraryAndNeverCallsAgent`；该关停用例在修复前确定性复现两次，修复后通过。修复把 timer.schedule 放入 reservation 的 try/finally 生命周期，保证任务关闭后仍释放操作门和并发额度。

## 所有已记录失败按测试文件分类

每个具体方法、参数化身份、原错误文本和相关栈帧见归档 `full-verify-summary.json`；原始 XML/TXT 全部保留。下表覆盖已记录的81个失败/错误实例。

| 文件 | Failure | Error | 分类 |
| --- | ---: | ---: | --- |
| AnswerServiceTest | 3 | 0 | 旧组织共享/ACL预期，保留失败 |
| AudioVectorAnswerServiceTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| AudioVectorBuildBoundaryTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| AudioVectorIndexingServiceTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| DemoFixturesTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| DocumentCleanupHttpTest | 4 | 0 | 旧组织共享/ACL预期，保留失败 |
| DocumentCleanupPermissionBoundaryTest | 2 | 0 | 旧组织共享/ACL预期，保留失败 |
| DocumentCleanupPermissionBoundaryTest | 8 | 0 | 真实压缩后schema校验问题，最终包已修 |
| DocumentCleanupRepositoryTest | 0 | 3 | 真实压缩后schema校验问题，最终包已修 |
| DocumentCleanupServiceTest | 2 | 0 | 旧组织共享/ACL预期，保留失败 |
| DocumentCleanupServiceTest | 5 | 0 | 真实压缩后schema校验问题，最终包已修 |
| DocumentOriginalHttpTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| DocumentRemovalHttpTest | 2 | 0 | 旧组织共享/ACL预期，保留失败 |
| DocumentRemovalProcessTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| ImageVectorEvidenceServiceTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| ImageVectorIndexingServiceTest | 2 | 0 | 旧组织共享/ACL预期，保留失败 |
| IndexingHttpTest | 2 | 0 | 旧组织共享/ACL预期，保留失败 |
| IndexingServiceTest | 2 | 0 | 旧组织共享/ACL预期，保留失败 |
| IngestionHttpTest | 3 | 0 | 旧组织共享/ACL预期，保留失败 |
| IngestionServiceTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| ManagedVideoSourcesBoundaryTest | 3 | 0 | 旧组织共享/ACL预期，保留失败 |
| QueryAttachmentRequestMapperTest | 1 | 0 | 旧范围/输入长度合同预期，保留失败 |
| SavedSourceReindexManagementTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| SoundRequestMapperTest | 3 | 0 | 旧范围/输入长度合同预期，保留失败 |
| SourceImageTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| SynopsisHttpTest | 2 | 0 | 旧组织共享/ACL预期，保留失败 |
| VideoAnswerEntryTest | 2 | 0 | 旧组织共享/ACL预期，保留失败 |
| VideoAnswerRequestMapperTest | 2 | 0 | 旧范围/输入长度合同预期，保留失败 |
| VideoAvQueryRequestMapperTest | 2 | 0 | 旧范围/输入长度合同预期，保留失败 |
| VideoAvRequestBoundaryTest | 2 | 0 | 旧范围/输入长度合同预期，保留失败 |
| VideoAvRequestMapperTest | 2 | 0 | 旧范围/输入长度合同预期，保留失败 |
| VideoOcrAnswerServiceTest | 2 | 0 | 旧组织共享/ACL预期，保留失败 |
| VideoOcrRepositoryTest | 2 | 0 | 旧组织共享/ACL预期，保留失败 |
| VideoOcrRequestMapperTest | 2 | 0 | 旧范围/输入长度合同预期，保留失败 |
| VideoQueryAttachmentProposalTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| VideoSubtitleAnswerServiceTest | 3 | 0 | 旧组织共享/ACL预期，保留失败 |
| VideoTraceRepositoryTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| VisualAnswerServiceTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| VisualQueryAttachmentAnswerTest | 1 | 0 | 旧组织共享/ACL预期，保留失败 |
| VoiceQuestionDtoTest | 1 | 0 | 旧范围/输入长度合同预期，保留失败 |

## 可回读归档

根工作区 `.local/verification-0057-java-release/`：

- `full-verify-summary.json`、`full-verify.log`、`full-surefire-reports/`：未完成全量、所有已完成结果、终止原因与时间。
- `direct-regression.json`、`direct-regression.log`、`cleanup-repository.log`、`final-direct-surefire-reports/`：最终65项完整直接回归。
- `final-package.log`、`final-artifact-binding.json`、`final-source-manifest.json`：格式、制品与最终输入绑定。
- `full-snapshot-source-manifest.json`、`full-snapshot-artifact-binding.json`：修复前全量快照绑定。
- `agent-shutdown-red.log`、`agent-shutdown-red-second.log`、`agent-shutdown-green.log`：关停竞态复现。
- `cleanup-probe-old30.log`、`cleanup-probe-new32.log`、`cleanup-probe-fixed32.log`：旧发布包、新包、修复后的相同合成清理路径。
- `runtime-heap.txt`、`runtime-threads.txt`：长耗时观察记录。

生产切换、真实生产副本30→32演练、公网和旧资料哈希验证由发布协调者另记部署验证；本文仅描述本机后端验证，不把本机替身或部分回归当成真实模型、浏览器或完整生产质量认证。
