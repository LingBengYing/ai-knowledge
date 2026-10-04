# Interfaces：0036 分工与窄入口

状态：CONTRACT_FROZEN。远端全部有效 receipt 必须有准确 target 配置并全部完整验证；每模态可配置一个 profile，遇到集合中其他未配置 profile 前置拒绝。沿原文 revision 最多4096段、原音 receipt 最多600 entry、原 scope界限，不增加隐式采样或截断。

B owns domain metadata 与 repositories/schema/Store：

```java
record ImageVectorBinding(PublicationVersion basePublication,
    ImageVectorPublication origin, String currentBasePhysicalSegmentId,
    String inheritedFromPublicationId, String bindingSha256) {}
record AudioVectorBinding(PublicationVersion basePublication,
    AudioVectorPublication origin, List<String> currentBasePhysicalSegmentIds,
    String inheritedFromPublicationId, String bindingSha256) {}
record ReindexVectorPlan(String jobId, String workspaceId, PublicationVersion basePublication,
    String setSha256, List<ImageVectorBinding> images,
    List<AudioVectorBinding> audios) {}
record ReindexVectorVerification(String route, String originReceiptId,
    IndexTarget target, VerifiedRevision verified) {}
record VerifiedReindexVectors(ReindexVectorPlan plan,
    List<ReindexVectorVerification> receipts) {}
```

origin 永远是真实原行，不能替换其 base 字段。直接 receipt 的 inheritedFrom=null；继承 binding 的 currentBasePhysicalIDs 对应全部 origin evidence，数组顺序相同。binding SHA 包含完整来源/目标/原receipt/current mapping/provenance，完整集合 SHA 确定性排序并包含全部binding身份。

Repository 保留旧 canReindex/insertRebuildJob 严格无媒体资格；新增 canReindexWithVectors(documentId)、vectorPlanForBase(jobId,workspaceId,basePublicationId)、insertRebuildJob 原末参 now 后加 baseVectorSetSha256、baseVectorSetSha256(jobId)->Optional、freezeVectorPlan(jobId)、insertInheritedBindings(newPublication,plan)。Image/AudioVectorRepository 新增 findBindings(workspace,publications,target)、allBindings(workspace,base) 与窄 insertBinding(binding,createdAt)；原 findPublications 保留真实 direct origin语义，不能返回伪造新base原receipt。workspaceId 供 worker 准确生成独立远端 manifest，不从原件文本猜测。

C owns IndexingService/new bounded read-only verifier worker 以及 binding-aware EvidenceService、ImageVectorIndexingService/AudioVectorIndexingService 与 Scope domain消费。新增资格 canReindexWithVectors(Actor,documentId,IndexTarget) 只读事务内完整authority/准确可配置target，无网络；创建continuation冻结完整集合。旧4参构造及旧complete signature保持receipt-free；新5参构造接受 BiPredicate<String,IndexTarget>（route,target），reindexVectorPlan(IndexClaim)返回 Optional<ReindexVectorPlan>，legacy/initial无snapshot返回empty，continuation空集合仍有完整plan/hash；completeIndexing原text参数后加 VerifiedReindexVectors，必须完整匹配计划且同TX插binding/active CAS。

root owns IndexingTaskProcessor及配置/ManagementService/runtime装配。Processor将新可选 service.ReindexVectorVerifier接到实际legacy与managed runtime；constructor为Map<String,IndexTarget>与Map<String,MilvusRestProjection.Settings>，supports(route,target)及verify(plan,remaining)。C实现窄service facade与worker.indexing内有界同步只读执行，逐route既有IndexWorkerLifetime，使用实际operation reservation、parent lifetime、interrupt、剩余HTTPtimeout和finally释放lease；无需新增写入子进程IPC。同一次 process 总 deadline，text worker后传真正remaining逐路验证，不将网络置于authority事务、不嵌套collectionlease。未知/错hash映射使用现 indexing_output_invalid；target不匹配使用 index_configuration_changed，原失败恢复/取消保持。ManagementService新7参构造接受BiPredicate<Actor,String>，由当前target和authority资格组合；旧构造的receipt-free资格保持。

管理行转换已经在 authority 事务内。其 callback 使用显式 canReindexWithVectorsInTransaction(Actor,documentId,target) 只读 helper；独立public canReindexWithVectors 负责外部事务包装，不能嵌套调用 store.transaction。Store 原拒绝 nested guard 保持，实际 HTTP 管理列表必须正常返回且零远端请求。

A owns app.js/image-vectors.mjs/audio-vectors.mjs。canReadImageVector/canReadAudioVector与Session.open增加optional `{ allowPublishedDuringReindex = false }`，默认indexed严格，build不扩资格；仅新cap且真实合法activepub/parsed/current新任务同doc/rev时显式启用读旧receipt。真实 row index_status不得伪造，identity/serial保持。继承 wire shape沿原十字段，不增代理路由。

root唯一 Maven/Spotless/target 和 Node执行；代理先草稿，获实际RED后按ownership编辑，完成后冻结写入等待root验证。新FormatTest旧产品已实际1FAIL(25期望/24实际)；新DOM10实跑8FAIL/2PASS，失败与成功逐项保留，不宣称全部RED。当前HTTP行为RED尚待执行。
