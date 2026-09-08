# Plan：完整分层重构与验证

状态：下列 Java 重构及本地验证步骤已完成，整体产品仍为 IMPLEMENTATION；没有推送或部署。结果与未验证项见 [verification](verification.md)。负责人明确本轮前端详情页不改。

1. 研究阿里官方手册与 Spring/JDK 运行机制，形成类型/职责/例外草案；只读定位存量混合职责。已形成研究文件，不代表源码符合。
2. 负责人审阅 Controller/Service/Repository/Model 及辅助包；确认裁剪与边界后更新本工件和 AGENTS 的执行入口。
3. 冻结 0004 当前源码与完整测试基线，记录未完成浏览器/真实 provider/生产项，不用旧发布 manifest 认证新代码。
4. 先提取 framework-free 领域类型及 HTTP DTO/响应契约，以负例验证敏感字段、PATCH 三态和认证语义；不一次生成所有 Model 分类。
5. 从当前 authority 提取一个共享 Store/Schema 与按业务 Repository，把用例留到具体 Service；保留单连接/共享锁/原事务，增加跨 Service 并发、回滚和部分成功验证。
6. 分批整理 Controller、Security 的认证/纯授权策略/HTTP 安全边界、Client、Tool、Worker、Job 与 Config；安全装配归 config，Actor 不重复建模。保留独立 worker 启动、取消、父死、代际隔离及资源关闭。源码审查发现的原有行为缺口另列修复，不偷偷混入纯搬包；完整 Spring Security 过滤链接入亦须单独验收。
7. 接入少量测试作用域架构规则及其负例；采用 ArchUnit 1.5.0 核心库，真实字节码依赖与合法/违规fixture单独验证。保留格式器，不同时进行全仓格式风格迁移。
8. 全量行为与安全门禁、真实 HTTP/子进程/必要浏览器验收、独立 Standards/Spec 审查，更新代码地图、规范状态、验证记录；推送和生产发布各自按授权与 gate 执行。

并行边界：文档/只读审查可独立；Store/Service/Repository 属共享事务核心，应统一 ownership 后逐步迁移，不能多人各开连接重写。所有执行者必须保留共享工作树中其他人的改动。

## 执行分工与冻结基线

- authority_layering：旧 ManagementModule → 3 个业务 Service、类型化 Repository/Model、共享 Store/Schema、纯授权策略及原管理/摄取/索引 authority 测试；不留生产旧 facade。
- adapter_layering：Client/Tool/Worker、Job/单次TaskProcessor拆分、摄取/索引Controller与配置、解析领域数据与验证回执、对应Adapter/真实进程/HTTP测试。
- 主线程：认证与HTTP安全、管理/会话/运行信息Controller、稳定异常/Actor、配置/启动总接线、文档和最终集成；独立助手负责ArchUnit门禁与自测。共享target的Maven串行执行。
- 10:10:14 +08:00 重跑原源码 clean verify 成功，256 JUnit、0失败/错误/跳过、Spotless 74文件通过、双80%覆盖率门禁通过；完整源码与测试用例清单见 [baseline-manifest](baseline-manifest.json)。该文件只认证重构前快照，不认证随后新代码。
- 为维持包级封装，Store/Schema 与 Repository 可同包，不强造 support 中转；Domain 允许依赖纯稳定异常，但不依赖 HTTP/Spring/JDBC/Client。配置仍只装配，运行对象不反向依赖配置加载器。

## 执行回执与裁剪

- 旧万能类、混合职责包和 Runtime 已实际删除；没有生产旧 facade。104 个生产 Java 文件、44 个测试 Java 文件形成当前职责结构，源码指纹与旧用例映射分别冻结于 [source-manifest](source-manifest.json)、[test-retention](test-retention.json)。本地保留重构前源码/测试压缩快照供恢复，不进入生产路径。
- 目录响应复用安全 DTO，删除重复 VO；单次任务处理归 TaskProcessor，Job 仅调度。共享 Store 的事务执行器和包私有 TaskResults 用于真实共同职责，不引入 ORM 或额外通用业务框架。
- 保留既有格式器配置，补齐显式 import 和控制块大括号。ArchUnit 使用 test-scope 1.5.0，不强接未校准的新 Java P3C-PMD。诊断脱敏调整已写入 spec，HTTP 错误文案漂移经独立审查修回原契约。
- 256 个旧测试全部迁移保留，最终 283 JUnit 与 73 Node 测试通过；Standards / Spec 发现项均复核关闭。替身浏览器验收不等于真实模型或 Milvus 验收；摄取后台 ACL 复验存量差距单独保留，不作为纯分层变更暗改。
