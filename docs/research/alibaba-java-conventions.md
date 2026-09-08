# 阿里巴巴 Java 规范：本项目采用依据

研究日期：2026-09-07。本文正文保留研究阶段的来源、建议与当时差距，不是当前实现状态；执行规则以 [JAVA_DEVELOPMENT_STANDARDS](../JAVA_DEVELOPMENT_STANDARDS.md) 为准。研究阶段没有上传手册全文，也没有安装 P3C、修改 Maven 依赖或运行其兼容性组合；后续实施回执另列于文末，不将历史建议改写成当时已执行。

## 原始资料与核对范围

主资料为 [alibaba/p3c 官方仓库](https://github.com/alibaba/p3c/tree/6c59c8c36ecd8722c712d5685b8c3822c1c8b030) 提供的 [黄山版 PDF](https://github.com/alibaba/p3c/blob/6c59c8c36ecd8722c712d5685b8c3822c1c8b030/Java%E5%BC%80%E5%8F%91%E6%89%8B%E5%86%8C%28%E9%BB%84%E5%B1%B1%E7%89%88%29.pdf)，版本 1.7.1、发布日期 2022-02-03。固定的仓库提交日期为 2022-05-11，不把手册发布日期与提交日期混用。访问时官方 README 仍以该版为最新提供版本，不声称此规范已经覆盖后来的全部 Java 21 特性。

本次从原 PDF 核对命名、格式、并发、异常、单测、安全、工程分层、设计相关条目；分层图另以渲染页面视觉核对。手册印刷页 p37/p38 对应 PDF 第 40/41 页。没有声称对全部 55 个 PDF 页面进行逐条合规审计，也没有把 MySQL/内部二方库所有规则直接套到 SQLite 单体。

关键判断是区分原文等级与项目选择：分层图和模型术语不是要求每个业务都建全部层；服务接口规则有 SOA 背景；本项目明确需要 Controller、Service、Repository、Model 等职责，同时要求奥卡姆剃刀。具体映射、命名与例外见主规范，不借“阿里强制”掩盖团队取舍。

## P3C 工具不等于手册，也不等于 Java 21 门禁

| 已核验的官方事实 | 对本项目的含义 |
| --- | --- |
| [P3C-PMD pom](https://github.com/alibaba/p3c/blob/6c59c8c36ecd8722c712d5685b8c3822c1c8b030/p3c-pmd/pom.xml) 声明 2.1.1、PMD 6.15.0、编译 target 1.8；内部自检插件还引用 P3C-PMD 2.0.1 | 不能照抄整个插件片段；target/JDK 最低运行版本不等于能解析 Java 21 源码 |
| [PMD 官方 Java 支持表](https://pmd.github.io/pmd/pmd_languages_java.html) 列 Java 16 从 6.32.0、Java 21 从 7.0.0 开始 | 默认 6.15.0 不能用作 record / Java 21 语法覆盖证明 |
| [P3C 线程池规则源码](https://github.com/alibaba/p3c/blob/6c59c8c36ecd8722c712d5685b8c3822c1c8b030/p3c-pmd/src/main/java/com/alibaba/p3c/pmd/lang/java/rule/concurrent/ThreadPoolCreationRule.java) 使用旧 AST；[PMD 7 迁移文档](https://pmd.github.io/pmd/pmd_userdocs_migrating_to_pmd7.html) 描述 Java AST 变化 | 只覆盖 PMD 版本不代表 P3C 规则已兼容；须单独测试，不臆测可以直接升级 |
| [JEP 444](https://openjdk.org/jeps/444) 区分虚拟线程与稀缺资源并发限制 | 保留受控虚拟线程设计；不机械改回平台池，不把无告警当作资源有界证据 |

[Issue #1002](https://github.com/alibaba/p3c/issues/1002) 是用户报告某插件组合缺少 `BaseLanguageModule`，不是官方广泛兼容性结论。本次没有执行该组合；不能凭 issue 标题断言“任何 P3C 都不能在 JDK 21 上运行”。

采用建议：继续已有 Spotless 与业务门禁；考虑 [ArchUnit 核心库](https://www.archunit.org/userguide/html/000_Index.html) 的测试作用域接入，通过现有 JUnit 调用检查包/类型依赖，避免另加测试引擎。命名规则只补少量确定性检查。版本、Java 21 运行及违规 fixture 仍待实施验证，不能把建议描述为已安装。

## Spring / JDK 校准和存量差距

框架行为见 [Spring 官方辅助研究](java-spring-conventions.md)：MVC Model 不是持久化 Entity，stereotype 不自动保证分层，原生 Connection 不因上层 `@Transactional` 就参与 Spring 管理。具体包名、VO 含义、构造器注入、错误边界以主规范确定。

本次只读代码检查发现：当前 `ManagementModule` 约 1991 行，混合用例规则、SQL、迁移、锁/事务与响应拼装；Controller/认证/进程类型还存在跨层耦合，固定业务数据有 Map 和嵌套 record。不是补几个目录和注解就完成。

迁移最重要的边界：拆开业务和 SQL，但保留同一 Store/连接/锁/事务；保留索引 claim/source/generation/完整映射发布原子性和批量逐项提交语义。不能因拆成三个 Repository 就各建连接，不能把已经实现的索引发布错误地称为完整 Java RAG。详细步骤在 [0005 plan](../changes/0005-spring-layering/plan.md)。

## 后续实施回执（2026-09-07）

0005 已按阿里规范的项目裁剪、deep Module 和奥卡姆剃刀完成本地重构验收；Java 整体仍为 IMPLEMENTATION。后续实际添加的是 ArchUnit 1.5.0 test-scope 核心库，并复用现有 JUnit，未安装 P3C 或证明上述旧 PMD 组合兼容。2026-09-07 10:52:26 +08:00 最终 `clean verify` 通过 283 项 Java 测试（含 11 项架构测试，0 失败/错误/跳过），Spotless 检查 148 个 Java 文件；原 256 项测试逐项保留，Node 回归 73 项通过。

实施范围、真实环境及未验证项见 [0005 验证记录](../changes/0005-spring-layering/verification.md)，当前源码指纹见 [source-manifest](../changes/0005-spring-layering/source-manifest.json)。这不是完整阿里合规、完整 Spring Security 或生产验收；摄取后台创建者 ACL 复验差距仍按规格登记。本次只做 Java 重构，不修改前端详情页，未推送或部署。
