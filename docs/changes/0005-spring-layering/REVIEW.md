# Review：Spring 分层重构最终审查

审查日期：2026-09-07。结论：本次 Standards / Spec 审查发现的问题均已修复并复核关闭，未发现尚未关闭的本次重构问题。Java 整体仍为 IMPLEMENTATION；本记录不是完整 Java RAG、生产发布或推送批准。

## 审查依据与比较边界

- 规范依据：[Java 开发规范](../../JAVA_DEVELOPMENT_STANDARDS.md)、[AGENTS](../../../AGENTS.md) 与本变更 [intent](intent.md)、[spec](spec.md)、[plan](plan.md)。此前阿里手册及 Spring/JDK 研究是设计依据，不作为当前源码已经合规的证明。
- 行为比较固定于重构前 `source-and-evidence.tgz` 冻结快照，对应 [baseline-manifest](baseline-manifest.json) 的 `2026-09-07T10:10:14+08:00` 验证记录。比较包含当时尚未提交的 0004 源码，不以较旧的 `bc82a7a` Git 基线把 0004 增量误记为本次修改；256 项旧测试结果不认证重构后代码。
- Standards 与 Spec 分轴复核。Spec 审阅者未编写被审生产实现，按源码检查事务、ACL、HTTP、任务与进程行为；其本人编写的架构门禁不由其自审。本文件汇总另外的 Standards 复核及主线程提供的最终执行结果，不把审查、测试和浏览器证据混为一项。

## Standards

结论：已关闭本轮规范问题与去重建议。职责实际落入 Controller / Service / Repository，SQL 与行转换留在 Repository，业务规则及事务边界留在 Service；并非一行 Service 转发旧万能 Repository。Security 的 HTTP 凭据提取、纯 JWT 验证和权限策略已分开，Job、TaskProcessor、Worker、Client、Tool 各自承担调度、单次用例、进程、远程协议和确定性处理职责。

| 已关闭事项 | 修复与复核结果 |
| --- | --- |
| M01：相同目录字段重复建 DTO / VO | `FolderResult`、`FolderRemovalResult` 作为安全 DTO 直接输出；`FolderListResult.items` 复用 DTO 并防御复制，不保留重复目录 VO 或逐字段原样映射。Spec 已列公开字段，仍无 Entity、claim、创建者或密钥泄漏。 |
| M04：浅不可变 record 持有可变集合 | `DocumentResponse`、`DocumentPageResponse`、`DocumentActionsResponse`、`RuntimeResponse`、`TagListResponse` 五个 VO 增加集合快照；`FolderListResult` 同样防御复制。独立六项负例已有红→绿记录。请求 Command 保留允许 null 的防御复制，未把 PATCH 三态改成构造器异常。 |
| 重复任务结果映射 | 包私有 `TaskResults` 统一三个 Service 的任务投影；调用者仍在原事务内提供当前权限，未把授权或查库转移到转换器。没有新增公开 facade 或无意义 ServiceImpl。 |
| 上传适配职责 | `prepareUpload` 移到 IngestionService；Tool 明确区分元数据校验与全文 envelope 校验，不再构造假文件字节供预检。Servlet 只负责 HTTP 接收、限流、超时和输出。 |

解析领域值的诊断脱敏属于 Spec 已批准的 M04/G05 调整，未改变内容、定位字段或 parser revision。架构门禁检查真实字节码依赖及合法/违规 fixture；通过记录见下节，不用目录名称代替依赖验证。

## Spec

结论：最终源码复核通过。共享 `SqliteAuthorityStore` 保留单连接、同一 monitor 与 `BEGIN IMMEDIATE`；批量动作仍逐项事务，索引的当前 claim / 创建者 ACL / 完整 manifest 校验及 publication → entries → active → audit → indexed 仍整体原子提交。source revision 与 projection generation 未混用，取消、重试和晚写隔离未见本次回归。启动恢复先于依赖 Job 的调度；隔离处理在 authority 事务外，保留清理后再领取、关闭中断及原等待边界，进程协议和 fat-JAR worker 入口未改变契约。

P3 HTTP 兼容问题已关闭：`UploadServlet.onAllDataRead` 恢复直接 `fail(problem)`，不再重写 `unsupported_document` 的既有 `detail`。新增真实 HTTP 用例 `invalidFileContentPreservesTheEstablishedProblemDetail` 同时锁定 422、错误码和原文案；主线程于 `2026-09-07T10:48:50+08:00` 对修复前编译产物取得仅 detail 不符的真实失败证据，最终完整测试已通过。

后续收拢亦已复核：上传元数据预检仍发生在读取、限流接收之前，入库前仍克隆并重验全文；TaskResults 保留原状态和权限映射；目录 DTO 保留 JSON 字段、long 计数、HTTP 状态与业务行为；集合快照未改变有效响应或 PATCH 缺失/null/空值语义。

## 最终验证记录与责任边界

以下检查由主线程（root）执行并提供结果，审查助手没有运行 Maven，也没有写共享 target。审查本身不替代测试；完整命令、源码绑定、进程和浏览器证据由主线程维护在 [VERIFICATION](../../VERIFICATION.md)。

| 检查 | 主线程最终结果 |
| --- | --- |
| 最后一次源码修改后的 Maven clean verify | `2026-09-07T10:52:26+08:00` 完成；283 JUnit，0 failure / error / skip |
| Spotless | 148 个文件通过 |
| 架构门禁及自测 | 11 项通过，包含真实生产依赖与规则合法/违规 fixture；已计入上述 JUnit 总数 |
| Node | 73 项通过 |
| 浏览器核心流程 | 主线程报告通过；环境、步骤与证据仅以 VERIFICATION 中的浏览器记录为准，不由本审查推定更多场景通过 |

## 保留的存量差距与发布限制

- 索引 claim/current/complete 会复验创建者当前写权限；摄取 HTTP status/cancel/retry 检查当前 ACL，但摄取 `currentClaim`/完成路径尚未等价复验创建者 ACL。这是冻结快照已存在、需要另列规格和撤权负例验证的差距，不是本次拆层引入的问题，也未借重构暗改；不据此宣称已复现越权。
- 当前仍为 Nimbus/JOSE 验证与自定义 Filter，不宣称完整 Spring Security FilterChain、方法鉴权或生产身份体系已接入。
- 完整授权检索、问答、来源、多模态、真实 provider/Milvus 和生产验收不由本次分层审查证明。`can_answer=false`、readiness 503、显式本机 opt-in 与独立数据目录约束继续有效；没有性能对照、推送或生产部署完成声明。
