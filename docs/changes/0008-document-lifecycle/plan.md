# Plan：按真实生命周期推进

当前：A步本地验收通过；完整0008及生产未完成。已确认10dff37源码/测试归档及CI，冻结本规格与Interface，取得真实红测并实施撤下边界，完成组合与全量回归。负责人最新要求“推送一下代码”，本次同步源码与验证工件，不部署；下述B步及其他完整目标继续保留，不扩大本次调用或发布授权。

## 责任与Interface

- authority_layering：AuthoritySchema/SqliteAuthorityStore的v5增量；新DocumentLifecycleRepository与DocumentRemovalEntity；ManagementRepository/IngestionRepository/IndexingRepository/EvidenceRepository的当前过滤和对应迁移测试。历史读取、物理配额、旧DDL保持；不改Service、HTTP、Tool算法。
- spring_authority_explore：DocumentLifecycleService、DocumentRemovalResult、新Controller/Settings/Configuration、RuntimeService/RuntimeConfiguration能力接线及新Service/HTTP测试；必要时为后台复验加既有Service最小调用，不改SQL/schema。先只写可在现有代码运行的HTTP红测。
- root：工件、共享契约、真实parser/AnswerService撤下竞态组合、唯一串行Maven、旧测试保留/证据与集成；统一分配必要跨边界修改。
- adapter_layering：非实现者做边界审查及独立Standards/Spec复核，不编辑被审源码或自行运行Maven/模型。

DocumentLifecycleService.removeDocument(Actor,String) -> DocumentRemovalResult(documentId,status,cleanupStatus,requestedAt)。Service拥有同一Store事务；Repository仅封装SQL和强类型数据。新tombstone是删除请求的权威事实，不是第二份原文。既有worker/current/finish/source路径复用，不新建临时“测试可用”的实现。

Repository小Interface冻结为findWritableDocument(Actor,String)、findRemoval(Actor,String)、insertRemoval(DocumentRemovalEntity)。第一个查询是已撤下身份重放所需的专用当前ACL复验，不复用排除墓碑的普通currentRole；Entity字段为documentId/workspaceId/requestedBy/requestedAt。事务先授权、已有回执则返回，否则读取两类任务、清folder与摘要审计、取消在途任务与安全审计、写墓碑及删除审计；202在提交后返回。

先让HTTP新路由/响应与v5版本产生真实失败，再批准生产实现；迁移夹具变化只适配新版本并保留旧断言。所有构建在冻结归档之外的独立副本执行，禁止覆盖运行JAR。前端不改，模型请求为零。

2026-09-08 12:34:26实际JDK21首轮2项全部按行为失败（0错误/跳过）：DELETE期望202实际404；新库格式期望5实际4。基准773项源码/报告归档SHA256为4c12f3e7f5eb8d097083ca2c3a10fe3d450982cb2e0924bc9ac633fc0701b369，已独立解包保留。先只落v5 sidecar和合法幂等HTTP路径；通过后用真实删除与已成功回答的来源取得下一红，再补所有当前查询过滤，不批量预写想象测试。

顺序调整：先实现所有模态共用的撤下安全基础，再继续重建与媒体编译；v3唯一job和冻结active意味着重建需要独立可恢复迁移。没有缩小原完整目标，也不把A步“deleting/pending”回执当硬删除完成。B步须明确本机与Milvus/备份清理账本、受控清理和恢复风险，再提供完成状态及管理批量硬删能力。

2026-09-08 13:09:46最终实际JDK21隔离副本clean verify通过857项Java（原773项逐项保留，新增84项），240文件Spotless和双80%门禁；Node73通过。未改UI资源、模型调用为零。所有迁移、任务状态、回滚、重开、HTTP、配置与真实子进程/答案竞态用例都在最后源码变更后完整执行；源码绑定、审查与未完成项见[verification](verification.md)。
