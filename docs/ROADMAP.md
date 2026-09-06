# Roadmap：按可验收纵切推进

当前是 **Java management slice**，不是完整 RAG。此文记录实现状态与后续 gate，不承诺发布日期、吞吐提升或生产上线。精确运行与验证结论见 [README](../README.md)、[VERIFICATION](VERIFICATION.md)。

## 能力状态

| 能力 / Capability | 状态 | 当前边界与下一证据 |
| --- | --- | --- |
| 资料列表、筛选、分页、显示名、目录、手工标签 | Implemented | 合成元数据；服务端 ACL、事务、界面交互；见 [ManagementModule](../src/main/java/com/evidence/rag/management/ManagementModule.java) |
| 批量移动、追加标签 | Implemented | 逐项回执/部分失败，不是整批原子提交 |
| JWT / 本机开发身份、Cookie 会话 | Implemented | 无 SSO、用户管理、签发/刷新服务；生产配置被 gate 拒绝 |
| PDF / TXT / MD 文本解析与 code point 分块 | Library implemented | [TextParser](../src/main/java/com/evidence/rag/corpus/TextParser.java)的独立库能力；6 个测试方法；未接 HTTP、持久化和隔离 worker |
| 真实文件上传、解析任务、revision 发布与恢复 | Planned | 尚无上传、状态机、任务隔离、重试或 active revision 原子切换 |
| OpenAI-compatible embedding / extraction、provider reranker Adapter | Independent module / not wired | [0002](changes/0002-text-adapters/intent.md) 协议子切，运行证据见 VERIFICATION；rerank 为 provider extension，摘取不是最终答案，尚无真实 provider 验收 |
| Milvus dense + sparse / BM25 hybrid retrieval | Independent module / not wired | 显式初始化、身份绑定 schema、范围前置与确定性合并；尚无 authority/发布接线或真实 staging 证据 |
| 有据问答、选中文档范围、来源引用、拒答 | Planned | 无回答或 source HTTP 路径；golden 不是当前通过报告 |
| 文档删除、重新索引与版本追溯 | Planned | 当前动作明确拒绝，不能操作其他系统数据 |
| 图片 OCR / vision、音频转写、视频音画联合 | Planned | 类型筛选只是元数据；处理、检索、摘要均未接通 |
| 摘要、自动标签、证据组与多模态评测 | Planned | 不能由界面占位或独立 parser 推定已完成 |
| 生产发布与性能结论 | Blocked by gates | readiness 为 503；没有全链路真实 provider/Milvus、运维、负载与生产验收证据 |

## 下一条独立纵切：文本入库到可核验回答

先创建新的 `docs/changes/NNNN-topic/{intent,spec,plan,REVIEW}.md`，冻结单组织、文件类型、大小、模型配置及 acceptance。开发顺序不是先搭满所有层：每一步先给失败行为，再穿过最小 Interface 得到可验证结果。

1. **文本 ingestion authority**：受限上传、文件 hash 与不可变 revision/segment 身份；独立 Java 存储。将当前 `TextParser` 放入有资源边界的 worker，明确取消、失败、重试、崩溃恢复与 active revision 切换规则；不能把解析成功等同索引成功。
2. **真实模型 Adapter**：embedding / generation 按 OpenAI-compatible 契约，reranker 按独立 provider extension（非标准 OpenAI 端点）；固定版本与维度。先本地 HTTP stub 验证协议、总 deadline、响应限长、禁止重定向、空结果/错误/非有限数值、安全错误与密钥脱敏，再独立真实 provider 冒烟。密钥不写入文档、测试、日志或仓库。
3. **Milvus text projection**：新建隔离的 Java 集合，禁止写旧集合；固定 schema/model revision，显式验证既有集合的字段、维度、BM25 函数与索引。明确写入可见性；按完整授权 revision 集合，在 dense/sparse 每路 top-K 之前应用同一范围。使用可解释合并/去重和确定性 tie-break；投影只提供候选身份，不替代权威正文。
4. **回答和来源最小 API**：重排候选、权威证据回读、服务器 source locator 校验、逐事实覆盖与无证据拒答。答案记录证据版本、模型和提示版本；模型不能自行决定页码、权限或引用链接。
5. **真实网页和冻结 acceptance**：上传 → 状态 → 列表 → 指定范围提问 → 引用定位的浏览器闭环；包括失败、越权、撤权、旧 revision、空范围、提示注入与并发变更。保留并扩展 frozen cases，不放宽旧安全预期来迁就实现。

上述步骤尚未接通；第2–3步的独立协议 Adapter 由 [0002](changes/0002-text-adapters/intent.md) 推进，第1、4、5步仍未完成。只有整个最小闭环有独立测试与真实集成证据，才可称“文本 RAG 纵切完成”。它仍不等于多模态或生产发布完成。

## 必须保留的未来安全 invariant

- **范围先于候选截断**：组织、ACL、active revision 与选中文档范围在候选进入模型前生效，不能先全库 top-K 再事后过滤。
- **完整 selected set**：不截断、不静默丢弃所选 ID；显式空选择或任何所选资料不可用/越权不能回退全库或只用剩余部分。开始处理时验证完整集合；模型调用后、答案提交前再次校验完整选中集合及实际证据版本。被选中但最终未入候选的资料也不能省略最终范围检查。
- **权威引用**：检索投影的 ID/score 是候选，正文与结构化 locator 由权威存储重新确认。模型自由生成内容不能成为来源证据。
- **低信任文档**：正文、附件、工具返回都是数据，不执行其指令；无充分证据或远程预算中断就拒答。
- **元数据不改变证据身份**：改名/目录/手工标签不改原文件名、内容 hash、revision/segment，不触发模型。
- **多模态逐事实覆盖**：未来音画问题不得漏掉尾部事实；同一 EvidenceGroup 的 visual/transcript pair 覆盖全部事实，两个模态各自至少贡献一个过阈值事实。查询图片不能代替文本事实证明；审计只保存子问题哈希与分数，不保存原问题片段。

## Frozen fixtures 与 eval 的准确用法

[src/test/resources/corpus](../src/test/resources/corpus/)的四份合成 PDF 覆盖政策、运维、不可信指令、另一组织资料。它们不含真实业务文档，仅供测试。[TextParserTest](../src/test/java/com/evidence/rag/corpus/TextParserTest.java)验证抽取和定位；[golden.json](evals/golden.json)保留六个未来回答预期：精确事实、政策事实、同义查询、无答案、提示注入和组织隔离。

目前 golden 没有接到 Java 问答 eval runner，不能写“召回率已通过”“RAG 评测 6/6”或把 parser 正确抽取危险文字说成安全回答成功。后续应固定语料/配置/模型版本，分别报告解析、候选召回、重排、答案/引用、拒答与权限安全结果。

## 生产 gate

进入生产前至少需要：

- 完成目标文本/多模态范围的实现及独立代码审查，所有 relevant acceptance 可重放。
- 完整 Java/浏览器测试、格式/静态检查、覆盖率门禁；实际结果与源码版本绑定。修改共享语义规则后重跑完整相关测试，不能仅跑新增用例。
- 真实 provider 和 Milvus staging 证据，异常/超时/限流/资源耗尽、状态恢复与一致性验证。
- 合法生产身份、TLS/代理/Origin 配置、安全密钥管理、备份恢复、数据迁移和回滚演练；不得复用或改写旧服务数据来跳过迁移设计。
- 真文件网页端验收、引用定位和 selected-set 并发撤权测试。
- 固定硬件、语料、并发与模型配置的负载基线及观测指标。没有对照数据前，不宣称 Java 比其他实现更快。
- 负责人明确生产发布授权与环境选择，随后才修改当前 production/readiness guard；不能为展示上线而将 readiness 强行改为 200。

当前 [RuntimeGuard](../src/main/java/com/evidence/rag/config/RuntimeGuard.java)与 [RuntimeController](../src/main/java/com/evidence/rag/web/RuntimeController.java)刻意保持开发边界。路线图不构成解除 gate 的授权。
