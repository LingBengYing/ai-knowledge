# 0007 共享 Interface

状态：IMPLEMENTATION；恢复后采用以下接口契约，不是已验收API或实现通过证明。签名或持久化含义调整须先协调ownership并同步测试。

## 权威 Module

`EvidenceService(SqliteAuthorityStore store, EvidenceRepository evidence, ManagementRepository management, DocumentPermissionPolicy permissions)`。
`EvidenceRepository(SqliteAuthorityStore store)`。均为具体类，不新增单实现 interface/Impl。

```java
EvidenceScope snapshot(Actor actor, DocumentSelection selection, IndexTarget target);
List<PublishedEvidence> hydrate(EvidenceScope snapshot, List<String> physicalIds);
TraceReceipt finish(EvidenceScope snapshot, TraceDraft draft, Supplier<AnswerEligibility> eligibility);
SourceEvidence source(Actor actor, String traceId, int citationOrdinal);
```

每个方法只拥有一次短 Store transaction；远程调用不占事务。`AnswerEligibility`为闭集Domain枚举：`ELIGIBLE`、`PROCESSING_TIMEOUT`、`CONFIGURATION_CHANGED`。`finish`在取得Store锁后和insertTrace前检查eligibility，不合格则分别强制`abstained/processing_timeout`或`abstained/configuration_changed`，清除answer hash和引用；首次不合格不能因随后读到ELIGIBLE而恢复成功。null资格视为无效输入并回滚。只有返回answered receipt才允许上层释放答案。

资格检查仅使用本地冻结身份/预算/中断状态，不允许网络I/O；实际生产Adapter的revision/identity来自构造时固定配置，不支持原地热更新。运行配置更换需创建新的受控运行实例。此签名替换尚未发布的BooleanSupplier契约，避免把配置变化误记为处理超时；对应原测试只做等价typed状态迁移，断言不放宽。

Domain 位于 `model.domain`，不依赖 Entity/DTO/Client/Jackson：

```java
DocumentSelection(boolean all, List<String> documentIds)
PublicationVersion(String documentId, String publicationId, String sourceRevisionId,
    String projectionGenerationId, String sourceSha256, String parserRevision,
    IndexTarget target, String manifestSha256, int segmentCount)
EvidenceScope(Actor actor, DocumentSelection selection, List<PublicationVersion> publications)
PublishedEvidence(PublicationVersion publication, String physicalSegmentId,
    String entrySha256, IndexSegment segment, TextPage page, String pageSha256,
    String filename)
TraceEvidence(int citationOrdinal, String physicalSegmentId, int start, int end,
    double retrievalScore, double rerankScore, List<String> factSha256)
TraceDraft(String questionSha256, String answerSha256, String outcome, String reasonCode,
    String modelRevision, String promptRevision, String policyRevision,
    List<TraceEvidence> evidence)
SourceEvidence(PublishedEvidence evidence, int start, int end)
```

`model.dto.TraceReceipt(String traceId, String outcome, String reasonCode)` 是不含正文的提交回执。

- `DocumentSelection.allDocuments()` 产生 all=true/空列表；`selected(ids)` 保留显式空。IDs 最多128、唯一、合法；all=true 不允许带 ID。HTTP 缺失选项才是 ALL，null 不是 ALL。
- `snapshot` 对显式不可用选择整次抛安全 NOT_FOUND；这些输入/范围失败不是已接受查询 trace。空选择/空 ALL 返回空 scope，由上层 finish 为 `empty_scope`，零远程。ALL 先按 ACL/active 授权，再 LIMIT 129；超过128抛 CAPACITY_EXCEEDED，不截断。
- scope 包含全部冻结 publication，包括无候选文档。ALL 请求开始后的新增文档不补进本次 scope。
- `PublishedEvidence` 复用 `IndexSegment`、`TextPage`；locator 为页内 Unicode code point 半开区间。页正文只供服务器校验，不等于模型上下文。
- `hydrate` 保留 physicalIds 输入顺序；重复、未知或越界 ID 全体拒绝，不默默丢弃。scope 失效用安全 CONFLICT `scope_changed`。
- `TraceDraft` outcome 仅 answered/abstained。answered 必须有 answer hash、引用且 reasonCode=null；abstained 必须 answerSha256=null、无引用且 reasonCode 为安全短码。模型身份使用一次冻结的 `TextModels.revision()`，另记录 prompt/policy revision；不存明文问题、答案或原始模型输出。
- `TraceEvidence` 引用编号从1连续；start/end 属于引用页并落在对应 segment；所有分数必须有限，不假定 rerank 为概率；factSha256 只含事实摘要。
- `finish` 对任何冻结范围变更强制 `abstained/scope_changed`、丢弃答案 hash 与引用，再同事务写结构化 trace；无 trace 成功就无答案成功。
- `source` 仅原 trace Actor/组织可读，且采用更强的**完整冻结 trace 范围当前复验**，不限于引用文档。任一原范围文档撤权/失活/换代均拒绝来源读取；返回 `SourceEvidence` 保留精确 trace start/end，HTTP 不输出整页。

## 持久化边界

v4 增量 sidecar 存 trace header、完整 document/publication 范围与引用台账；保持 v1/v2/v3 schema 和不可变 source/active 行为。保存先子行后 header，延迟外键和封存触发器阻止提交后补写子行；所有 trace 表禁止 update/delete。Service 做业务复验，Repository 仅绑定 SQL/行映射。迁移与完整 trace 事务失败均回滚且错误脱敏。

## 事实验证的Domain契约

`GroundingQuote`是尚未验证的模型输入，保留null、未知ID、空值和非法Unicode的可表达性，Tool统一整次判定`invalid_quote`，toString始终脱敏。

`GroundedQuote`是已验证输出：非空且最多128 code points的无控制字符physicalId，0≤start<end、span≤1200，非空合法Unicode正文的code point长度等于span；factHashes为1–8个小写SHA256并防御复制。不在构造器重做权威来源/事实证明。

`GroundingResult`支持时reason只能为`supported`且quotes非空；拒答时quotes必须为空，reason仅为`unsupported_question`、`incomplete_evidence`、`conflicting_evidence`、`unsafe_evidence`、`invalid_quote`。列表及元素非null、防御复制，错误使用稳定无原文的Domain异常。最终引用数量上限仍由AnswerService验收，不在本次构造器修复中改变Tool展开多个片段的契约。
