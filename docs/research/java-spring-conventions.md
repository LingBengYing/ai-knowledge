# Java / Spring 分层规范研究

访问与核验日期：2026-09-07。用途：为本项目的 Java 分层重构与 [项目开发规范](../JAVA_DEVELOPMENT_STANDARDS.md) 提供 Spring 机制的辅助校准；用户已指定以阿里巴巴《Java 开发手册》作为人机协同的规范主来源。主规范负责逐条对照该手册、确定 DO/DTO/BO/VO 命名与项目例外，本文不以 Spring 示例代替该主来源，也不是重构完成、测试通过或生产批准的证明。

## 结论先行

应明确区分 Controller、Service、Repository、Model，以及配置、调度、外部客户端、纯工具和异常边界。但 **Spring 不强制一套固定目录，也不要求每个业务同时拥有 DTO、VO、BO、DO、Entity 和 ServiceImpl**。Spring 官方定义组件语义和运行机制，具体包名、对象命名、依赖方向与奥卡姆剃刀规则必须标为本项目约定。[Spring Boot：代码结构](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)、[Spring：组件 stereotype](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html)

本项目最重要的重构风险不是少几个注解，而是把已有 SQLite 单写者、权限校验、版本发布和审计原子性拆坏。应先画清职责与事务，再分离强类型 Model 和持久化操作；不能给旧连接包一层 `@Transactional` 就声称它已纳入 Spring 事务。[当前 authority 约束](../ARCHITECTURE.md)、[Spring：JDBC 连接参与事务](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html)

## 1. 版本与证据边界

- 本仓库 [pom.xml](../../pom.xml) 声明 Spring Boot 4.1.1、Java 21 编译目标、SQLite JDBC，没有声明 JPA/Hibernate ORM。这里不以“传统 Spring”为由引入 ORM、换数据库或改动数据格式。
- 本次访问官方系统要求页显示 Boot 4.1.1、Spring Framework 7.0.9 或以上，Java 17 至 26 的兼容范围包含 Java 21；本文 Framework 引用页本次显示 7.0.9。此为文档兼容性依据，不代替本项目实际 JDK 21 的构建、运行和行为验收。[Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)
- 引用 `reference` 和 `current` URL 的内容可能随官方版本推进；未来升级应重新核验。本文未添加依赖、运行 Maven、修改源码或迁移数据库，也未验证新的 Bean Validation provider、Spring 事务管理器或 AOP 配置实际存在。

## 2. 官方定义与团队边界不可混写

| 项目层 / 组件 | 框架事实 | 建议落为本项目规则，而非假称官方强制 |
| --- | --- | --- |
| Controller | `@Controller` 表达 Web 组件；`@RestController` 组合 Controller 与 ResponseBody，面向响应体，而非 HTML 视图解析。[官方声明](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann.html) | 接收 HTTP、解析认证上下文、校验协议形状、调用 Service、映射响应；不写 SQL，不做文件解析或模型调用，不持有业务事务。 |
| Service | `@Service` 是 `@Component` 的专门化；官方允许团队按用途收窄 service 语义。[Service API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/stereotype/Service.html) | 对外提供业务用例；编排授权、状态规则、领域模型和持久化，明确事务边界。类名优先具体能力，不强制接口加唯一 Impl 空壳。 |
| Repository | `@Repository` 标识持久化/DAO 角色；异常翻译依赖相应后处理器和翻译器，不能仅凭注解推定任意 JDBC 异常已被转换。[官方组件说明](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html)、[异常翻译机制](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/dao/annotation/PersistenceExceptionTranslationPostProcessor.html) | 封装 SQL、参数绑定、行映射和持久化约束；不接 Servlet，不决定 HTTP 状态，不调用模型。范围约束可以由 Service 提供，但必须在 SQL 查询/更新生效，不能先取全库再过滤。 |
| Model | MVC `org.springframework.ui.Model` 是模型属性容器，可视为 Map；不是数据库实体或整个领域模型的基类。[Model API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/ui/Model.html) | 强类型承载请求、响应、持久化与领域概念，按不同信任边界区分；无需让业务模型实现 MVC Model。详见下一节。 |
| Config | `@Configuration` 的主要职责是提供 Bean 定义；`@Bean` 方法创建、配置、初始化容器管理对象。[Java 配置](https://docs.spring.io/spring-framework/reference/core/beans/java/basic-concepts.html) | 集中装配依赖、绑定并验证配置、注册线程池/过滤器、管理资源生命周期；不承接业务流程或 SQL。 |
| Job / Worker | Spring 提供 TaskExecutor、TaskScheduler 和 `@Scheduled` 等调度能力；同方法多个调度声明可能重叠执行。[任务执行与调度](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) | Job 负责触发、领取、预算、取消和生命周期，并调用 Service；Worker/独立 JVM 是执行隔离边界。持久状态机、幂等、单并发与父进程死亡处理仍需项目实现和验证。 |
| Filter / Interceptor | Servlet Filter 环绕过滤器链与目标 Servlet；OncePerRequestFilter 的异步/错误 dispatch 有专门控制，不等于所有派发永远只运行一次。[Filters](https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html) | Filter 负责身份提取、请求编号、通用安全头等 HTTP 横切工作；不能把资源 ACL 只放此处，否则 Job/CLI 可绕开。Interceptor 只在确有 MVC handler 级需求时引入。 |
| Exception / Advice | `@ControllerAdvice` / `@RestControllerAdvice` 可统一处理多个 Controller 的异常；属于 MVC 处理范围。[Controller Advice](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html) | 业务异常不携带 HTTP 对象；Advice 映射既有安全错误契约，不返回堆栈、密钥或原文。过滤器、独立 Servlet、异步任务的错误出口需分别验证，不能假定都经过 Advice。 |
| Client / Adapter / Tool | 本次检查的官方结构与 stereotype 文档未把这些包定义为强制层。[官方结构](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html) | 外部模型/Milvus HTTP、进程协议等有 I/O、有配置的对象放明确 Client/Adapter/Worker 归属；`tool` 只容纳确定性、无业务状态的技术函数，不成为所有杂项的容器。 |

## 3. Model 层：必须先消除同名异义

以下是待主规范与阿里巴巴手册对齐的目录和命名候选，不是已冻结的全项目标准，也不是 Spring 或 Java 的统一术语标准。Spring 文档同时使用 “model” 指 MVC 属性和用于绑定的普通对象；甚至提供业务实体与 Web 绑定对象分离的安全建议，因此不能将所有模型压成一个可随意绑定的数据库对象。[Model API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/ui/Model.html)、[Web 绑定与模型安全](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/modelattrib-method-args.html)

| 概念 | 待主规范确认的候选归属 | 边界 |
| --- | --- | --- |
| 请求 / 传输输入 | `model.dto`，类名 `CreateDocumentRequest`、`DocumentQuery` 等 | 只声明调用者可提交的字段；不得接收可伪造的 workspace、角色、active publication 等服务器权威属性。DTO 的概念本身也包含响应，输入目录只是团队约定。 |
| 对外视图 / 响应 | `model.vo`，类名优先 `DocumentResponse`、`IndexingResponse` | 此处 VO 明确定义为 **View Object**，不能又拿同一缩写表达 Value Object。按公开契约输出，避免直接暴露持久化字段、claim token、原文或内部配置。 |
| 持久化模型 | 独立持久化数据包；DO、Entity、Row 的确切后缀由主规范对照手册确定 | 本项目指 JDBC 持久化数据类型，不因目录名就添加 JPA `@Entity` 或 Hibernate。可表示表行/持久化快照，但不自动成为 API 请求或响应。 |
| 领域模型 / 值对象 | `model.domain`，使用 `SourceLocator`、`IndexTarget`、`EvidenceScope` 等语义名 | 承载业务身份、不可变值和必要不变量；不依赖 Servlet、JDBC、JSON 树或 Spring MVC Model。领域值对象不再使用有歧义的 VO 缩写。 |
| 数据转换 | 靠近使用边界的具体 Converter / Mapper | 只有确有不同表示需要转换时才建类。JDBC RowMapper 是 ResultSet→类型化数据；API Converter 是内部模型→输入/响应适配，两者不能混成万能 Mapper。 |

建议按以下判断减少不必要对象，同时保留真实边界：

1. **是否不同信任边界？** 用户请求、数据库行、内部 claim、对外响应应分别明确；复制相同字段并不能证明它们语义相同。
2. **是否真的不同表示？** 没有独立职责时不机械生成 DTO→BO→DO→Entity→VO 五套数据拷贝；只有字段/生命周期/不变量不同才增加转换。
3. **是否真正需要可变？** 请求和响应可采用简单不可变数据类型；含集合、数组或敏感字段时仍须考虑防御复制和安全 `toString`，不能仅凭一个类修饰或命名声称深层不可变。
4. **是否跨层泄漏？** 业务 Service 不返回 `Map<String,Object>` 作为固定业务契约，也不依赖 `HttpServletRequest` 或 `ResponseEntity`。动态第三方 JSON 可留在 Adapter 内部，经过完整校验再返回强类型结果。

以上四点是面向本项目安全与可维护性的团队选择，不是新增框架要求；权威 source 身份、generation、ACL 和台账约束仍由 [0004 spec](../changes/0004-text-index-publication/spec.md) 决定，不得通过对象“简化”移除。

## 4. DI 与对象装配

Spring 团队推荐对必需依赖使用构造器注入；这样有利于明确依赖和构造完成后的可用状态。大量构造参数提示职责可能过多，但官方没有规定一个通用硬性数量上限。单构造器通常无需 `@Autowired`。[依赖注入](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)、[构造器自动装配规则](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired.html)

项目可规定：依赖字段 `final`、默认构造器注入、不做字段注入、不通过全局静态 ApplicationContext 查找依赖；由 Config 构造有资源生命周期或条件装配的对象。`new` 领域值对象和局部纯工具并不违背 DI，不应把每个普通对象都做成 Bean。[DI 的依赖提供机制](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)

使用 `@Configuration(proxyBeanMethods=false)` 时，直接调用另一 `@Bean` 方法是普通 Java 调用，可能创建新对象；需要其他 Bean 时通过方法参数注入。是否使用这种轻量模式应匹配实际装配方式，而不是全仓盲加。[配置方法代理语义](https://docs.spring.io/spring-framework/reference/core/beans/java/basic-concepts.html)

## 5. 校验：语法约束不替代业务授权

Bean Validation 支持声明式约束，但要有兼容 provider 和相应运行时配置。不能只添加 `@Valid` 就声称生效；本仓库未在此次研究中验证 provider 是否存在，也不在此次研究增加依赖。[Spring Bean Validation](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html)、[Boot 校验支持](https://docs.spring.io/spring-boot/reference/io/validation.html)

Spring MVC 对 `@RequestBody` 等对象参数及方法参数的校验路径不同，可能分别产生 `MethodArgumentNotValidException` 与 `HandlerMethodValidationException`；`@Valid` 本身不是约束。要使用 MVC 内置方法校验，不应同时机械地在 Controller 类上加会引入 AOP 方法校验的 `@Validated`。[MVC 校验](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)

本项目规则应明确两道边界：

- Controller / DTO：JSON 类型、必填项、长度、范围、白名单字段和协议形状。表单绑定使用专门对象或受限构造器绑定；不能把持久化对象所有属性开放给客户端。[绑定安全建议](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/modelattrib-method-args.html)
- Service / 领域模型 / Repository：当前用户、固定组织、完整 selected set、状态转换、source/generation 身份和并发复验；这些依赖当前权威状态，不是 DTO 注解能代替的。[当前项目安全契约](../changes/0004-text-index-publication/spec.md)

类型化 DTO 的迁移还须保留现有“未知字段、显式 null、空集合、重复身份头、空选择不回退”的区别。Bean Validation 不自动规定这些项目语义，必须由契约测试逐项证明。

## 6. 事务：Service 确定用例边界，Repository 参与同一物理事务

### 官方机制

1. `@Transactional` 是元数据，需要事务管理基础设施才生效。默认代理模式只拦截经代理进入的外部调用，同对象自调用不触发被调用方法上新增的事务语义，也不能依赖初始化时的 `@PostConstruct` 调用触发事务。[事务注解机制](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
2. 未配置其他规则时，默认对 `RuntimeException` 和 `Error` 回滚，checked exception 不自动回滚。可选择类型安全的 `rollbackFor`；`readOnly=true` 只是给事务子系统的提示，不是强制禁止写入或授权机制。[Transactional API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/annotation/Transactional.html)
3. Framework 支持全局 `rollbackOn=ALL_EXCEPTIONS`，但这是显式选择，不应描述为本项目已经采用或框架原默认。应在项目规范中确定回滚政策并测试，而不是只复制注解。[回滚默认值配置](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
4. 常规 PlatformTransactionManager 事务绑定当前执行线程，不传播到新线程；响应式事务使用 Reactor Context，是另一套模型。本项目 JDBC/子进程流程不能假定事务、身份或锁会跨线程/进程继承。[Transactional 线程边界](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/annotation/Transactional.html)
5. Spring JDBC 事务使用对应 DataSource 的线程绑定 Connection；`JdbcTemplate` 或 `DataSourceUtils` 参与连接获取。自行保存的原生 Connection 不会因上层方法有注解就自动转为该管理器的连接。[JDBC 连接与事务](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html)
6. Spring 也正式支持 `TransactionTemplate` 等程序式事务，不存在“只有注解事务才规范”的结论；它仍依赖对应事务管理器与资源正确接线。[程序式事务](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)

### 对当前知识库的直接影响（团队设计约束）

- Service 表达“一个用例哪些状态必须一起提交”；Repository 及内部事务执行器负责 SQL 与资源操作。业务记录、publication 全映射、active pointer、审计必须仍使用同一受控事务，不能拆成几个各自提交的 Repository 调用。
- 暂不在整理目录时把单连接改连接池，或取消单写者锁、`BEGIN IMMEDIATE`、触发器、备份迁移。若要采用 Spring JdbcTransactionManager，须单独设计资源参与与 SQLite 语义迁移，并用失败/回滚/并发测试证明等价。
- 模型/Milvus HTTP 和独立进程等待不放在数据库事务里。维持“短事务领取快照 → 事务外远程处理 → 短事务复验并发布”；远程晚写仍由 generation 隔离，不能用本地 rollback 撤回。
- 批量整理的逐项回执/部分成功与索引发布的整体原子性不同；不得为了统一注解把二者改成同一种提交策略。

上述约束来自本项目 [Architecture](../ARCHITECTURE.md) 与 [0004 spec](../changes/0004-text-index-publication/spec.md)，不是 Spring 对所有项目的数据库政策。重构验收应验证它们原样保留。

## 7. 配置、任务与错误出口的补充规范

Boot 支持 properties、YAML、环境变量、命令行等外部配置，具有确定优先级；可使用 Environment、`@Value` 或结构化 `@ConfigurationProperties`。配置属性校验也要求 provider 与对应配置。项目选择集中、强类型、启动即失败的配置，不等于框架只允许这一种方式。[外部配置](https://docs.spring.io/spring-boot/reference/features/external-config.html)

项目应将以下职责单独写清，而不是一概塞入 `tool`：

- `config`：配置绑定、条件装配、依赖组合、资源关闭；不输出配置中的密钥。
- `job`：触发 Service、持久任务进展、取消/重试和预算；不由一个 `@Scheduled` 推定跨实例唯一或 exactly-once。官方调度能力允许多个触发重叠，项目仍需自有准入和持久状态。[任务调度](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
- `client` / `adapter`：外部协议、超时、限长、响应校验和安全错误；不持有 HTTP 用户请求或直接发布 authority。
- `exception` 与 `advice`：内部稳定错误类型与 HTTP 映射分离；保留已约定状态码和脱敏响应，不统一返回 HTTP 200 掩盖失败。[MVC 异常处理机制](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html)
- `filter`：认证/请求级横切；ControllerAdvice 的 handler 错误路径不能当作所有 Filter 或后台任务的兜底。[Filter 边界](https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html)、[Advice 范围](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html)
- `converter` / `mapper`：边界转换；只有出现实际转换职责才提取，不预先引入映射框架。
- `tool`：哈希编码、纯解析校验等无业务状态函数；不能隐藏 SQL、网络、文件写入、调度或授权决策。纯函数也必须有明确输入限制与错误契约。

包可以采用“顶层分层、层内业务分包”或“业务分包、内部显式分层”，二者都能遵守 Spring 扫描和 stereotype 机制。最终选一套写进规范并执行依赖检查；本研究不把官方示例当唯一目录标准。[Boot 结构指导](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)

## 8. 奥卡姆剃刀如何变成可执行规则

以下为团队约定候选，不声称来自框架强制：

1. 一个类只有一个可说清的职责；不能用少文件为理由保留“业务+SQL+HTTP+配置”的万能类。
2. 只为真实边界创建层和对象；不为了目录齐全制造空 ServiceImpl、空 Repository 接口、通用 BaseService 或无行为的转发链。
3. 普通用例优先一个具体 Service；真实多 Adapter、可替换外部协议或清晰测试边界才引入接口。若现有项目约定要求两个 Adapter，仍遵守该门槛。
4. 不为本次分层重构引入新微服务、CQRS、事件总线、ORM、代码生成器或缓存；需要它们时由独立需求与证据论证。
5. 以依赖规则和行为验收判断分层，不只检查类名后缀。Controller 禁止依赖 JDBC/Repository，Service 禁止依赖 Servlet，Repository 禁止依赖 Controller/远程模型，Model 禁止持有基础设施资源。
6. 明确允许的例外、理由、退出条件；安全不变量不能成为例外。先写类型边界和事务回归，再分步骤移动实现，避免一次大搬家丢失正在进行的索引修复。

研究结论应落为项目规范和分阶段重构计划；“规范已写”与“现有代码已符合”必须分开报告。
