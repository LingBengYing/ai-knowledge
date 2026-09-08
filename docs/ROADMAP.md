# Roadmap：按可验收纵切推进

默认是Java management_slice；问答关闭时摄取/索引保持text_ingestion/text_indexing，0007独立本机问答开启后为text_answers。三个开关默认关闭；后端可宣告answers/sources，列表can_answer与网页仍未接线、ready503。0005/0006已通过当时本地验收，其历史指纹不认证新增0007；本地全量通过也不是完整RAG。此文记录后续gate，不承诺日期、性能或上线；当前证据见[0007验证](changes/0007-text-answers/verification.md)。

## 能力状态

| 能力 / Capability | 状态 | 当前边界与下一证据 |
| --- | --- | --- |
| 资料列表、筛选、分页、显示名、目录、手工标签 | Implemented | 合成或真实上传资料；服务端ACL、事务，整理不改源身份；见[ManagementService](../src/main/java/com/evidence/rag/service/ManagementService.java) |
| 批量移动、追加标签 | Implemented | 逐项回执/部分失败，不是整批原子提交 |
| JWT / 本机开发身份、Cookie 会话 | Implemented | 无 SSO、用户管理、签发/刷新服务；生产配置被 gate 拒绝 |
| PDF / TXT / MD文本解析与code point分块 | 0003 locally verified | TextParser通过独立ProcessTextParser子进程连接持久证据，不是OS沙箱 |
| 上传、持久任务、取消/重试与崩溃恢复 | 0003 locally verified | 显式loopback opt-in，v2备份迁移，parsed状态与实际分块；见[TEXT_INGESTION](TEXT_INGESTION.md) |
| Spring职责分层与架构约束 | 0005 locally verified | Controller / Service / Repository / Model / Security明确分离，替代旧万能实现；持续执行项目规范 |
| 摄取创建者当前授权复验 | 0006 locally verified | 领取/执行/提交及恢复撤权取消，系统审计同事务，重试权限交集；新增14项负例先红后绿 |
| active revision索引发布 | 0004 IMPLEMENTATION | 每claim独立generation、完整物理manifest、不可变attempt与source→physical→digest台账；active保留source revision；见[TEXT_INDEXING](TEXT_INDEXING.md) |
| OpenAI-compatible embedding / extraction、provider reranker Adapter | 0007 backend wired / locally tested | 索引只调用embedding；问答接embedding/rerank/extract；rerank为provider extension，摘取不等于事实证明，真实provider尚待验收 |
| Milvus写入与full revision verification | 0004 IMPLEMENTATION | 固定Java collection、N+1 metadata/精确ID/float32摘要、前后Strong/schema/index校验；真实版本一致性尚待验收 |
| Milvus dense + sparse / BM25 hybrid retrieval | 0007 backend wired / locally tested | 只读prepareSearch、完整范围前置、确定性合并及权威hydrate；不create/load/upsert，无独立候选检索端点，真实Milvus尚待验收 |
| 有据问答、选中文档范围、来源引用、拒答 | 0007 IMPLEMENTATION / local tests passed | POST answers与GET source已接线，完整scope/资格复验及v4 trace同事务；完整语义、网页和实际质量未验收 |
| 文档删除、重新索引与版本追溯 | Planned | 当前动作明确拒绝，不能操作其他系统数据 |
| 图片 OCR / vision、音频转写、视频音画联合 | Planned | 类型筛选只是元数据；处理、检索、摘要均未接通 |
| 摘要、自动标签、证据组与多模态评测 | Planned | 不能由界面占位或独立 parser 推定已完成 |
| 生产发布与性能结论 | Blocked by gates | readiness 为 503；没有全链路真实 provider/Milvus、运维、负载与生产验收证据 |

## 后续范围：文本入库到可核验回答

完整目标在明确blocked后重新启用，并收到继续完整目标的请求；当前已恢复0007文本问答IMPLEMENTATION。前端详情页仍不改，以下其他路线不是已完成能力；生产gate不解除。当前测试与待修问题见[0007验证](changes/0007-text-answers/verification.md)。

0003文本摄取与0004显式索引已形成后端基线；索引默认关闭，仅development/test和字面loopback，完整TextAdapterSettings必填，任务全deadline默认60000毫秒、范围10–600000，单并发、batch≤16、revision≤4096段、最多3次attempt。0007已进一步接通授权检索、逐事实校验和回答/引用API，问答独立默认关闭，不替代上述摄取/索引任务。尚须完成完整语义、实际provider/Milvus、网页与生产验收，不缩小目标。

已验收历史行为基线为[0006](changes/0006-ingestion-authorization/intent.md)，结构基线为[0005](changes/0005-spring-layering/intent.md)，索引行为按[0004](changes/0004-text-index-publication/spec.md)。当前[0007](changes/0007-text-answers/spec.md)已有本地全量结果但不等于完整验收；继续使用版本化intent/spec/plan/REVIEW冻结行为及acceptance，不能借旧审查认证新增源码。

1. **文本 ingestion authority**：受限上传、文件 hash 与不可变 revision/segment 身份；独立 Java 存储。将当前 `TextParser` 放入有资源边界的 worker，明确取消、失败、重试、崩溃恢复与 active revision 切换规则；不能把解析成功等同索引成功。
2. **真实模型 Adapter**：embedding / generation 按 OpenAI-compatible 契约，reranker 按独立 provider extension（非标准 OpenAI 端点）；固定版本与维度。先本地 HTTP stub 验证协议、总 deadline、响应限长、禁止重定向、空结果/错误/非有限数值、安全错误与密钥脱敏，再独立真实 provider 冒烟。密钥不写入文档、测试、日志或仓库。
3. **Milvus text projection（后端已接）**：隔离Java集合，固定schema/model revision；每attempt独立generation与physical ID隔离晚写。0007完整AuthorizedScope由active publication读取document→generation，在两路top-K前生效，physical候选经publication entries映射回权威source；仍须实际Milvus兼容/质量验收。
4. **回答和来源最小 API（后端已接）**：AnswerService编排重排/摘录/校验，EvidenceService锁内复验完整scope、预算与本地模型/投影资格，v4 trace记录版本及locator；source只向原回答者按当前完整ACL/active回读。完整语义及外部质量尚未认证。
5. **真实网页和冻结 acceptance**：上传 → 状态 → 列表 → 指定范围提问 → 引用定位的浏览器闭环；包括失败、越权、撤权、旧 revision、空范围、提示注入与并发变更。保留并扩展 frozen cases，不放宽旧安全预期来迁就实现。

上述链路的后端已有0007接线；[0002](changes/0002-text-adapters/intent.md)提供协议基线，[0003](changes/0003-text-ingestion/intent.md)连接持久解析证据，[0004](changes/0004-text-index-publication/intent.md)连接embedding、generation隔离与publication，0007增加v4 trace而不改旧source。父存活、lease、父崩溃及晚写仍须按当前源码验收；kill不撤回上游，非OS沙箱。查询prepareSearch也不能冻结外部generation/schema/index，多HTTP调用不是快照。独立完整语义审查、实际provider/Milvus与网页闭环仍缺，多模态和生产分别验收。

## 当前及后续必须保留的安全 invariant

- **范围先于候选截断**：组织、ACL、active revision 与选中文档范围在候选进入模型前生效，不能先全库 top-K 再事后过滤。
- **完整 selected set**：不截断、不静默丢弃所选 ID；显式空选择或任何所选资料不可用/越权不能回退全库或只用剩余部分。开始处理时验证完整集合；模型调用后、答案提交前再次校验完整选中集合及实际证据版本。被选中但最终未入候选的资料也不能省略最终范围检查。
- **权威引用**：检索投影的 ID/score 是候选，正文与结构化 locator 由权威存储重新确认。模型自由生成内容不能成为来源证据。
- **低信任文档**：正文、附件、工具返回都是数据，不执行其指令；无充分证据或远程预算中断就拒答。
- **元数据不改变证据身份**：改名/目录/手工标签不改原文件名、内容 hash、revision/segment，不触发模型。
- **多模态逐事实覆盖**：未来音画问题不得漏掉尾部事实；同一 EvidenceGroup 的 visual/transcript pair 覆盖全部事实，两个模态各自至少贡献一个过阈值事实。查询图片不能代替文本事实证明；审计只保存子问题哈希与分数，不保存原问题片段。

## Frozen fixtures 与 eval 的准确用法

[src/test/resources/corpus](../src/test/resources/corpus/)的四份合成 PDF 覆盖政策、运维、不可信指令、另一组织资料，不含真实业务文档。[TextParserTest](../src/test/java/com/evidence/rag/tool/parser/TextParserTest.java)验证抽取和定位；未修改的[golden.json](evals/golden.json)包含精确事实、政策事实、同义查询、无答案、提示注入、组织隔离六个预期，已由[AnswerGoldenTest](../src/test/java/com/evidence/rag/service/AnswerGoldenTest.java)接入Java解析/发布/问答链路并通过确定性替身验证。

六项通过只证明冻结fixture与确定性Adapter下的预期，不是实际模型召回率、完整语义覆盖或真实provider质量报告，也不是仅凭parser抽出危险文字就推定安全。后续实际质量评测仍须固定语料/配置/模型版本，分别报告召回、重排、答案/引用、拒答与权限结果。

## 生产 gate

进入生产前至少需要：

- 完成目标文本/多模态范围的实现及独立代码审查，所有 relevant acceptance 可重放。
- 完整 Java/浏览器测试、格式/静态检查、覆盖率门禁；实际结果与源码版本绑定。修改共享语义规则后重跑完整相关测试，不能仅跑新增用例。
- 真实 provider 和 Milvus staging 证据，异常/超时/限流/资源耗尽、状态恢复与一致性验证。
- 合法生产身份、TLS/代理/Origin 配置、安全密钥管理、备份恢复、数据迁移和回滚演练；不得复用或改写旧服务数据来跳过迁移设计。
- 真文件网页端验收、引用定位和 selected-set 并发撤权测试。
- 固定硬件、语料、并发与模型配置的负载基线及观测指标。没有对照数据前，不宣称 Java 比其他实现更快。
- 负责人明确生产发布授权与环境选择，随后才修改当前 production/readiness guard；不能为展示上线而将 readiness 强行改为 200。

当前 [RuntimeGuard](../src/main/java/com/evidence/rag/config/RuntimeGuard.java)与 [RuntimeController](../src/main/java/com/evidence/rag/controller/RuntimeController.java)刻意保持开发边界。路线图不构成解除 gate 的授权。
