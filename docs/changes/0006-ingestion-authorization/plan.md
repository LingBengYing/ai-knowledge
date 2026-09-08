# Plan：复现、修复与全量验证

状态：本切本地验收通过。上一目标轮完成0005 Java重构并冻结证据，本轮以独立0006补齐摄取授权差距；均为progress，不代表整体Java RAG或生产完成。

1. 对照当前 Store/Service/Job/Worker 和 schema，确认摄取复验差距与既有索引规则；按 spec 冻结取消/重试语义。基线是 0005 最终 283 项测试及 source-manifest，修改前核对当前源码指纹一致。
2. 先新增真实 SQLite 的授权负例、实际慢子进程的撤权回归；由主线程串行运行，记录应用行为失败，区分编译/夹具/沙箱故障。红测后才改生产源码。
3. 在当前 Service 私有行为与现有 Repository/Policy 上实施，不新增空接口、不重新造万能层、不修改 schema；TaskResults 复用结果构造，保持取消能力与重试授权分开。
4. 对 parser 创建前补当前 claim 复验；运行中继续复用现有 Job 检查与 ProcessTextParser 清理。测试使用真实本机子 JVM，不以线程替身冒充实际终止。
5. 最后修改后执行完整 clean verify、Node、脚本/静态/架构/凭据检查；独立审查，冻结新 source-manifest 和测试保留映射，更新能力文档。本次没有网页布局修改，真实 HTTP 回归与真实进程验收为必要证据，不用旧浏览器截图认证新增撤权行为。

## 执行结果与偏离

1–5已执行，详见[verification](verification.md)。10项Service负例和4项真实进程负例均在生产修改前失败；最终297项Java、73项Node通过。独立Standards/Spec均scoped PASS。仅修改4个Service/Processor生产文件并新增2个测试文件；未修改schema、Worker、Job、解析算法、HTTP响应结构或UI资源。计划无范围偏离，采用既有cancelled/null约束，不新增持久错误码或兼容壳。

## Ownership

- authority_layering：IngestionService、ManagementService 的解析任务投影、TaskResults，以及新的 IngestionAuthorizationTest；先只写测试，主线程确认红后才能改生产。
- adapter_layering：新的 IngestionAuthorizationProcessTest；只写测试，不改生产，不运行 Maven。
- 主线程：本工件、TaskProcessor 的执行前复验、串行构建/验证、文档/指纹及最终集成。
- spring_authority_explore：独立只读审查，不能自批自己编写的生产代码。

共享工作树各自保留其他改动；禁止并行 Maven 写 target，禁止重建覆盖运行中的 target JAR。真实模型、问答接线、多模态、staging 和生产发布仍须后续逐项完成，不缩减最终目标。
