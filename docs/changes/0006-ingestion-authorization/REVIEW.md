# Review：摄取授权复验

状态：本地限定范围审查通过，2026-09-07。不是完整Java RAG、安全认证或生产验收。

设计只读核对确认旧 currentClaim 只验证持久 claim 身份，没有创建者当前 ACL；领取、最终提交均缺少复验。已有 schema 不接受 ingestion `authorization_changed` 错误或 queued→failed，因此本切明确使用系统取消，不绕过 CHECK/触发器。

## 比较基准与范围

比较基准是修改前冻结且逐项匹配0005的171文件源码快照，不是较旧Git HEAD。4个生产修改文件：IngestionService、ManagementService、TaskResults、IngestionTaskProcessor；新增两个测试文件共14项用例。Schema、Store、Job、ProcessTextParser、模型与Milvus Client均未变。

## Standards：独立只读审查

审查者adapter_layering没有编写上述生产修改；其编写的Process测试不作为生产自批证据。按项目Java规范及0006规格核对4文件，hard违规0、需行动的judgement smell 0，scoped PASS。

- IngestionService把当前授权、取消与审计留在共享事务内，共同取消行为集中于私有方法，未越层引入SQL。
- ManagementService只补任务创建者当前权限事实；TaskResults复用实际公共构造职责，明确区分重试和取消授权，不机械增加DTO或空Interface。
- TaskProcessor在创建parser前复验，保留资源关闭与安全错误映射；符合既有层级和职责边界。

## Spec：独立只读审查

审查者spring_authority_explore未编写本切生产或测试代码；逐项核对spec §1–9与冻结diff，findings 0，scoped PASS。

- 领取前零原文件交付；合法claim撤权取消、清token、不增加attempt，恢复和重复回调幂等。
- 完整claim身份先于取消；complete保留坏内容SHA/parser的422顺序，isCurrent/fail不反复哈希payload。
- 取消和系统审计同事务，提交前复验阻止部分证据；重试与can_retry同时要求caller/creator可写，can_cancel保留合法管理者能力。
- Processor前置检查结合未变的Job串行调度、实际parser清理，符合立即重试隔离设计。

两个审查者均未运行Maven，也未将源码阅读记为执行证据。主线程在最后源码修改后执行完整clean verify：297项Java全部通过；实际子进程、SQL审计回滚、完整原283项保留证据见[verification](verification.md)及[test-retention](test-retention.json)。无待修findings；真实provider/Milvus、最终问答、多模态和生产仍未验收。
