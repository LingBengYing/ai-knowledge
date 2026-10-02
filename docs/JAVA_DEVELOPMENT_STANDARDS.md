# Java / Spring 人机协同开发规范

版本：1.1 · 2026-09-08 · 新增负责人要求的P01–P05主线优先规则；0005 本地重构验收通过，0006 摄取授权补强本地验证通过；Java 整体仍为 IMPLEMENTATION。适用 Java 21、Spring Boot、单组织知识库。

本文是**已批准的项目开发规则**：参考阿里手册并明确裁剪，结合 deep Module 与奥卡姆剃刀，不是手册全文或完整阿里合规认证。负责人授权的含 Security 职责分层重构已完成本地验收，被替代的旧实现已删除；范围见 [0005 规格](changes/0005-spring-layering/spec.md)，验收与快照见 [verification](changes/0005-spring-layering/verification.md)及[source-manifest](changes/0005-spring-layering/source-manifest.json)。以下“必须/禁止”继续约束新增与重构代码，不覆盖已批准的安全与行为工件。本地验收不代表所有 SEC 能力、完整 Spring Security、RAG 或生产安全体系已经完成；能力边界见 [ARCHITECTURE](ARCHITECTURE.md)。

0005 明确保留的摄取后台创建者当前 ACL 复验差距，现由独立 [0006 规格](changes/0006-ingestion-authorization/spec.md)及其[验证记录](changes/0006-ingestion-authorization/verification.md)补齐，不回写成 0005 当时已实现。0006 不改变 schema、索引契约或前端详情页，也未推送/部署；当前结果只认证本切摄取安全行为及对应回归。

## 1. 依据、等级与裁剪

主要参考阿里巴巴官方仓库提供的《Java 开发手册》黄山版（1.7.1，2022-02-03），不是“2026 新版”。已核对命名、格式、并发、异常、单测、安全和工程分层等相关章节及分层图。来源固定到 [P3C 官方提交](https://github.com/alibaba/p3c/tree/6c59c8c36ecd8722c712d5685b8c3822c1c8b030)；研究范围和工具兼容性见 [阿里规范研究](research/alibaba-java-conventions.md)。

阿里手册的原始等级与项目执行等级分开：本文“必须/禁止”是项目强制，“建议/优先”是审查建议，“候选/待接入”不是已执行检查。Spring 不强制固定包结构；本项目的 layer-first 结构来自负责人要求，不伪称框架规定。[Spring Boot 代码结构](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)

| 来源定位（手册印刷页） | 本项目处理 |
| --- | --- |
| 六（一）1，p37：分层为推荐；六（一）3，p37–38：模型分类为参考 | 采用职责分离，落为下文可检查的包和依赖规则；不机械补齐 Manager |
| 一（一）17，p3：SOA 服务接口与 Impl 要求 | 明确裁剪 E01：本地单实现 Service/Repository 不强制造接口 |
| 一（三）5、8，p4–5：缩进和折行要求 | 明确裁剪 E02：暂保留已有 Spotless / google-java-format，避免混合格式 |
| 一（七）3、4，p14：线程创建规则 | Java 21 虚拟线程按 E03 单独约束，仍须证明资源有界 |
| 三，p29–30：自动化、独立、可重复的测试 | 保留既有测试和双 80% 门禁；不能用架构检查替代业务测试 |

本项目优先级：产品安全 invariant / 已批准行为契约 → 本文明确规则与例外 → 外部参考。冲突必须写入变更工件，不能由 AI 默默选择有利于通过检查的一方。工程代码采用传统 Spring 分层，协作流程仍采用 AI-Native 的 intent → spec → plan → 验证 / review，不退回文档与代码脱节的线性流程。

## 2. 包结构与职责（0005 本地重构验收通过）

根包保持 `com.evidence.rag`，启动类放根包。顶层按层，层内在确有需要时按业务或技术边界分包；没有实现就不建空目录。以下职责已在 0005 源码中落地并通过本地架构门禁与行为回归，旧职责混合包及被替代实现已移除；本节定义持续约束，具体源码地图见 [ARCHITECTURE](ARCHITECTURE.md)，不是对未来改动或全部外部规范的认证。

```text
com.evidence.rag
├── controller       HTTP 路由与协议适配
├── service          用例、权限、状态规则与事务编排
├── repository       SQL、行映射、持久化与共享事务执行器
├── model
│   ├── entity       持久化数据对象（本项目 DO 的表示）
│   ├── dto          请求 / 命令 / 跨边界传输结果
│   ├── query        强类型查询条件
│   ├── vo           对外展示视图（View Object）
│   └── domain       身份、领域值、业务对象与不变量
├── security
│   ├── authentication  凭据验证、可信身份构造
│   ├── authorization   资源权限策略（不持有数据库）
│   └── web             安全 Filter、HTTP 凭据提取、安全错误出口
├── client           外部模型和向量服务的协议 Adapter
├── tool             确定性技术算法；不含业务流程和隐蔽 I/O
├── worker           隔离进程、执行协议、进程预算与退出
├── job              后台触发和生命周期；调用 Service
├── config           配置绑定、Bean 装配、资源生命周期
├── web              非安全 Filter、上传 Servlet、业务 Advice、HTTP 转换
├── exception        稳定业务 / 基础设施异常与安全错误码
└── bootstrap        显式 CLI、合成资料初始化
```

| 规则 | 职责与禁止事项 |
| --- | --- |
| L01 Controller | 解析 HTTP、读取可信身份上下文、验证请求形状、调用 Service、输出契约。禁止 SQL、Repository、模型调用、直接启动解析器或持有业务事务。特殊上传 Servlet 同属 Web 边界，不可借名字绕过限制。 |
| L02 Service | 负责业务规则、当前 ACL、状态转换、幂等与事务范围。`IngestionTaskProcessor` / `IndexingTaskProcessor` 负责单次任务的隔离执行编排、冻结目标检查和失败映射，与处理 authority 事务的 Service 分工；不是 Job 内运行 Worker，也不是空壳转发。禁止 Servlet、ResponseEntity、HTTP 状态码、JDBC 类型、SQL、全局 ApplicationContext 查找。用具体方法名表达业务，不能只把原万能类换名。 |
| L03 Repository | 封装绑定 SQL、授权范围内读写、持久化约束和行映射；不调用 Service、Web、外部模型或向量服务。禁止把 Connection/ResultSet/SQLException 暴露给上层。查询必须先带组织/ACL 条件，再 count/page/top-K；授权决策不是简单移到客户端。 |
| L04 Client / Worker | Client 封装外部协议、超时、响应限长、校验与安全错误。Worker 封装隔离执行及父进程/预算约束。不得自行写 authority、发布 active、决定用户权限。保留真实 Adapter 与确定性测试替身的 Seam。 |
| L05 Tool | 只做边界明确的哈希、编码、文本分块等确定性计算；输入输出及大小限制明确。网络归 Client，隔离子进程及其临时文件归 Worker；持久数据目录、数据库、迁移备份与 writer 锁归 Repository 的 Store，Config 负责装配和受控关闭。禁止 `CommonUtils`、`Helper` 杂物箱；不另建同义 `util` 包。 |
| L06 Config / Job / Bootstrap | Config 只绑定、验证和装配，不写业务 SQL；Job 只触发和管理调度生命周期，持久 claim/状态迁移仍经 Service；Bootstrap 通过 Service 显式执行 seed，不能在 Bean 构造时偷偷灌数据。 |
| L07 Web / Exception | 通用 web 处理请求追踪、上传协议、业务异常映射；认证及安全 Filter 归 security.web。业务异常与 HTTP 映射分离；Advice、Filter、独立 Servlet、后台任务各自有安全错误出口，不假定全部经过 Advice。 |
| L08 Security | authentication 验证身份，authorization 表达纯权限规则，security.web 处理安全 HTTP 协议。禁止 Security 反向调用业务 Service 或自行读写 Repository；Service 在同一受控事务内提供当前权限快照并执行授权结果，SQL 仍带权限条件。 |

Converter 只在真实转换处出现：API 转换放 `web.converter`；数据库行映射使用 `repository` 同包的包私有 `AuthorityRows`，不为子包访问扩大 JDBC 行数据可见性；外部 JSON 编解码留 Client/Worker 内。不要额外生成万能 Mapper 或每个类配一个 Converter。

### 依赖方向

以下为生产代码依赖白名单的设计依据；JDK、必要第三方库及测试代码另作规则限定，不以此表免除安全审查。

| 调用方 | 可依赖的项目职责 |
| --- | --- |
| Controller / Web | Service、DTO / Query / VO / Domain、Exception；Web 的协议辅助类及 security.web 的可信身份读取；会话协议入口可调用 authentication 的凭据验证 Interface |
| Service | Repository 的强类型接口和事务执行器、security.authorization、Client、Worker、Tool、Entity / DTO / Query / Domain、Exception |
| Repository | Entity / Query / Domain、Exception、内部 Store / Schema / Mapper；不得返回 VO |
| Security authentication / authorization | Domain、Exception 和必要验证库；不依赖 Servlet、Controller、业务 Service、Repository；authorization 是基于传入快照的纯策略 |
| Security web | authentication、Domain、Exception、必要 Servlet/安全框架；仅额外允许叶子类 `web.HttpProblemMapper` 统一安全状态映射，使异常解析器失败时仍拒绝请求。该精确依赖不放行整个 Web 包；不依赖业务 Service、Repository，不能调用工具处理业务 |
| Client / Worker | Domain / DTO、Tool、Exception；Worker 可调用 Client |
| Tool | JDK / 所需算法库与无框架 Domain 类型；无应用层反向依赖 |
| Model | Domain 可依赖 JDK 和纯稳定 Exception；Entity / DTO / Query / VO 可依赖 Domain，彼此不组成转换链或资源引用 |
| Job / Bootstrap | Service、Domain / DTO、Exception；无直接 Repository / SQL |
| Config / 启动类 | 仅为装配引用其他职责；其他层不得反向调用配置逻辑 |

配置值通过构造器传入基本值或不可变选项，避免所有层依赖一个全局配置对象。领域公共选项归 Domain；模型和向量协议选项保留在各 Client 自有不可变类型中，由 Config 构造后直接传给 Worker，不令 Worker 依赖 `config.TextAdapterSettings`。Model 中非 Domain 类型可在真实边界使用必要 JSON / Validation 注解；Domain 不依赖 Spring、Servlet、JDBC、Jackson。禁止生产包循环；Service 不互相形成环，不能用 `@Lazy` 掩盖循环。

### 安全职责：跨层执行，不止入口拦截

以下安全职责分层已通过 0005 本地重构验收。当前源码使用 `spring-security-oauth2-jose`、Nimbus JWT 验证及自定义 Filter；没有接入完整的 `SecurityFilterChain` 或方法鉴权。0005 拆清已有职责，不宣称从零新增全部安全能力或完成框架迁移。其登记的摄取后台 ACL 差距已由 0006 的独立红绿验证关闭：领取、parser 创建前、运行中检查、提交/失败和恢复按持久化创建者的当前写权限复验。该结论来自摄取自身的事务与实际子进程回归，不借索引检查代证，也不扩大成 SEC 全部完成。

```text
HTTP → security.web → authentication → 可信 Actor → Controller
                                                       ↓
                                                    Service
                       同一 authority 事务：取当前授权快照
                          → authorization 策略 → 带范围条件的 Repository 操作
```

SEC01 认证：身份来自受验证的凭据，不来自请求体角色。保留签名算法、issuer/audience/组织/时间、重复凭据与 Bearer 优先规则；无效 Bearer 不降级到 Cookie。`model.domain.Actor` 是唯一 framework-free 身份类型，HTTP 边界通过 `security.web.AuthenticatedActor` 读取可信身份；不创建第二份 UserContext，也不在身份中缓存永远有效的文档权限。开发身份只能显式 loopback，不能作为生产降级路径。

SEC02 授权：`security.authorization` 可包含具体 `DocumentPermissionPolicy`，输入为可信 Actor、操作与同一事务中读取的当前权限事实，输出允许/拒绝或受限范围；不自行调用 Repository，避免检查与使用分离。Service 不能绕过策略；Repository 在 SQL 中落实组织、ACL 和版本范围。列表的统计/分页、未来检索的 top-K 都先过滤再截断，不能用响应后过滤代替。

SEC03 异步/RAG：Job 必须携带持久任务的身份和受限范围，经 Service 复验，不假定 Web 的 ThreadLocal/安全上下文会传入新线程或子进程。提交前复验当前授权、版本与完整 selected set；远程处理前后按规格检查。禁止把 `@PreAuthorize` 的一次入口判断当作提交时权限、版本和审计原子性的证明；方法鉴权也需要显式启用。[Spring 方法安全](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)

SEC04 Web 防护：安全 Filter/错误出口归 `security.web`，装配仍在 `config.SecurityConfiguration`，会话 HTTP 入口仍在 Controller。Cookie 会话需明确 CSRF、Origin、SameSite、HttpOnly 及 TLS 下 Secure 策略；CORS 不等于认证，采用 JWT 也不等于不需考虑 Cookie CSRF。受保护路由及未知路由的默认策略、401/403、上传 Servlet、异步/error dispatch 都须测试。[Spring CSRF](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html)

SEC05 框架选择：后续安全接线优先评估 Spring Security 的现成过滤链/凭据验证能力，避免并存两套入口认证。若接入，必须单独记录依赖和行为迁移，测试过滤器顺序、公共路径及安全错误响应；自定义 Filter 不能同时由容器和安全链重复注册。0005 保留既有认证机制，不引入完整安全过滤链接线。[Spring Security 架构](https://docs.spring.io/spring-security/reference/servlet/architecture.html)

SEC06 防护责任不能全塞进 Security：参数化 SQL 在 Repository；上传大小/类型和解析预算由摄取 Service/Worker/Tool 各守其边界；密钥加载在 Config、使用与脱敏在 Client；服务端证据/引用校验和拒答在业务 Service；业务成功审计与修改同事务。Security 集中认证和授权规则，不能变成万能安全工具箱，也不能让其他层免除安全责任。生产限流、防刷、文件/网络隔离与密钥轮换仍须专门验收，不因有 security 目录就声称完成。

## 3. Model 不等于 Entity，也不是多一层转发

MVC 的 `org.springframework.ui.Model` 是视图属性容器，与此处领域/数据类型分类不同；LLM 模型客户端也不属于这个 Model 层。[Spring Model API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/ui/Model.html)

| 类型 | 项目命名示例 | 信任边界 |
| --- | --- | --- |
| Entity / DO | `model.entity.DocumentEntity` | JDBC 持久化快照；不自动引入 JPA `@Entity`，不直接绑定用户输入或作为 HTTP 响应。这里统一 Entity 后缀，不并存同义 DocumentDO / DocumentPO。 |
| DTO | `model.dto.DocumentPatchCommand`、`DocumentResult`、`TaskResult` | 传输输入或结果；DTO 不专指请求。输入类型只声明用户可提交字段，可信 Actor 单独传入；不接受可伪造角色、claim token、active pointer。 |
| Query | `model.query.DocumentQuery` | 承载筛选、分页、排序等条件，明确上下界和排序白名单；不使用可拼 SQL 的自由字符串，也不传无类型 Map。 |
| VO | `model.vo.DocumentResponse` | VO 在本仓库只表示 View Object。按公开 API 白名单输出；`can_edit` 等由 Service 按当前权限计算，Web 只转换，不泄漏内部 claim、密钥、私有路径。 |
| Domain / BO / Value Object | `model.domain.Actor`、`IndexClaim`、`ParsedText`、`VerifiedRevision`、`IndexingResult` | 表达业务身份、不变量或业务对象；值对象不用 VO 缩写。Worker 的索引校验结果不引用 Client 类型。BO 仅在有实际业务组合时创建，不强制每张表生成 BO。 |

M01：按信任边界和生命周期区分类型，不机械生成五套相同字段。传输结果已经与公开契约完全相同时，可直接使用同一安全 DTO，不强制复制成 VO；需在 spec 明确公开字段和敏感字段负例。持久化 Entity 和含内部 claim 的 Domain 不适用此简化。

Service 返回强类型安全 DTO/领域结果，并在其中计算权限派生值；仅确有公开字段裁剪或不同展示表示时，Web 才转换成 VO。比如 `TaskResponseMapper` 按摄取/索引任务映射固定 snake_case 字段，保留公开 `status` 别名与显式 null 语义，但不泄漏内部 `indexing` 标记；摄取响应不新增索引发布字段。目录的 `FolderResult` / `FolderRemovalResult` 已与公开白名单一致，直接作为安全 DTO 输出；`FolderListResult` 同 DTO 包复用并防御复制。禁止为满足目录而生成字段完全相同的 Result → VO 转发链。

M02：固定业务契约禁止 `Map<String,Object>` / `JsonNode` 贯穿多层。动态第三方 JSON 只留协议边界，校验后转强类型。局部查找 Map 合法，不把禁令误扩为禁止集合。跨边界参数较多时使用 Query/Command，而不是万能参数包。

M03：迁移 Request 必须保持 PATCH 的“缺字段、显式 null、空值”三态及现有未知字段行为；不能用默认值悄悄变更删除/不修改语义。新增 `@Valid` 之前检查 provider 与实际错误路径，必须有失败输入的真实 HTTP 回归。[Spring MVC 校验](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)

M04：数据载体优先不可变；Java 21 `record` 可用于合适的 Request/Result/领域值，不强制 Lombok。集合和数组须防御复制，敏感字段不得进入自动 `toString()`；`record` 是浅不可变，不是自动脱敏。[JDK Record](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Record.html)

M05：source revision、projection generation、source segment、physical segment 是不同身份。禁止为“减少 Model”合并这些类型/字段，删除验证清单或用显示名代替证据身份。

## 4. Spring、持久化与事务

S01：必需依赖使用构造器注入和 `final` 字段；不做字段注入或静态容器取 Bean。普通数据对象不必变成 Bean。`@Service` / `@Repository` / `@Configuration` 表达真实职责，不靠注解名替代实现边界。[Spring DI](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)

S02：Service 定义业务事务范围，Repository 参与同一物理事务。0005 已从旧 `ManagementModule` 提取共享 `repository.SqliteAuthorityStore`：一个受控连接、一把共享锁、同线程事务回调，SQL/资源异常封闭在 Repository；原混合实现已删除。Store 的 SQL 方法仅包内可见，并要求当前线程持有有效事务，不是新建通用事务框架。Repository 不得各开连接、各自提交，Service 不操作 SQL。

S03：分层本身不迁移 ORM、数据库、连接池、schema 或文件格式；保留现有 single-writer、`BEGIN IMMEDIATE`、迁移备份和触发器。一次列表读取的 count/rows/附属状态保持同一权威快照；批量整理维持逐项事务/部分成功，不能变成整批回滚。

S04：索引遵循“短事务领取 → 事务外远程执行 → 短事务复验发布”。最后一次事务覆盖 claim、当前权限、source、generation、完整映射、publication、active 与审计；不会因为 Milvus 写成功而放宽。禁止持有数据库锁等待模型或子进程，也不声称本地 rollback 能撤销远程写入。

S05：不能只添加 `@Transactional` 就声称原生 Connection 已被管理。若未来单独迁移 Spring 事务，须验证 DataSource/连接参与、代理自调用、回滚类型和新线程边界；`readOnly` 不等于权限控制。当前分层计划不引入该迁移。[Spring JDBC 连接](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html)、[事务注解](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)

S06：Repository 绑定查询参数；动态排序/列名采用封闭白名单。跨表业务决策归 Service，数据库约束和 ACL 范围条件仍必须实际落实在 SQL。不能为了“纯 Repository”把授权过滤挪到查全库之后。

## 5. 日常编码、资源与安全

G01：包名小写且语义明确；类 UpperCamelCase，方法/变量 lowerCamelCase，常量 UPPER_SNAKE_CASE。类名对应职责后缀 Controller / Service / Repository / Configuration / Exception；DTO 采用上表 Request / Command / Result / Response 等清晰角色名。状态枚举使用业务名如 `IndexingStatus`，不强制 Enum 后缀；禁止含糊缩写、拼音夹英文或 XxxPOJO。

G02：UTF-8、LF；只用仓库固定格式化器。新增/修改控制语句使用大括号，不用 wildcard import，移除 unused import。原有存量格式/导入违规需登记迁移，不能谎称现有 formatter 检查了全部命名和结构。方法过长、构造参数过多需审查职责；不靠机械拆成许多单行转发满足行数。

G03：空结果、未找到、上游失败含义分明，不用空集合吞掉异常。ID/状态/时间采用适当类型，时间明确时区与精度，日志定位字段统一；修改既有 JSON 字段、枚举值、时间格式须走 API 兼容性规格。

G04：异常必须处理、转换或传播，禁止空 catch、finally return 和失败返回成功。HTTP 保留当前安全错误码/状态契约；不要新增一套全局 `Result<T>` 把现有 API 全改成 200。资源使用 try-with-resources 或等效受控关闭，保留取消/超时/中断语义。

G05：普通应用日志用 SLF4J 等门面，只记允许的 request/task/document 标识、安全错误码和耗时；禁止原问题、全文、token、密钥、claim 凭据、个人路径及原始上游错误。子进程 stdout 是受限协议通道，不能注入日志；stderr 同样脱敏、限长。日志保留/轮转由部署方案明确，不把手册示例目录写入程序。

G06：配置集中绑定并验证，缺少必要配置启动失败；真实凭据仅由外部 secret / 环境提供，不提交或写死加密后密文与解密密钥。示例只留非敏感占位值。禁止 `record`/自动日志意外输出配置对象。

G07：线程命名、任务准入有界、执行器/进程必须关闭；平台线程池明确队列、容量与拒绝策略。虚拟线程允许，但仍须限制模型/Milvus/解析并发、队列、字节数和总 deadline；取消不等于上游撤回。ThreadLocal 清理由 finally 保障，锁顺序固定，跨 Service 不能误拆共享锁。

G08：依赖通过 Maven/BOM 统一管理，新增依赖说明用途、版本、兼容性和替代方案；生产依赖禁止未评估的漂移版本。当前开发 SNAPSHOT 不等于生产制品，发布另行固定版本与指纹。禁止因整理规范引入 ORM、映射框架、微服务或事件总线。

G09：沿用 [AGENTS 安全 invariant](../AGENTS.md)：ACL/完整 selected set 在证据入模型前生效并按规格复验；无证据拒答；服务器验证引用；文档内容永远是数据；整理不改源身份。安全不变量没有“简化实现”的豁免。

## 6. 奥卡姆剃刀与明确例外

“简单”指职责和依赖少而清楚，不是文件越少越好。一个 2000 行类同时处理 SQL、用例和 HTTP 输出，也不是简单设计。

| ID | 当前采用的项目裁剪 | 再评估条件 |
| --- | --- | --- |
| E01 | 本地单实现 Service/Repository 直接使用具体类；仅确有两个 Adapter（含真实测试替身）时增加 Seam，不生成无意义接口 + Impl、BaseService 或空 Manager | 出现真实替换边界并有两种实现及 Interface 行为测试 |
| E02 | 保留现有 Spotless / google-java-format 的默认排版（含两空格缩进），本次不改格式器配置，不宣称严格逐条阿里格式兼容；新增 test-scope 架构检查依赖不属于格式器变更 | 如需调整格式器，单独固定兼容 Java 21 的工具并审查全量格式差异 |
| E03 | Java 21 不池化虚拟线程；稀缺资源用准入/信号量等约束。旧线程规则不能直接作为现代 API 禁用表 | JDK/并发模型升级时重验；见 [JEP 444](https://openjdk.org/jeps/444) |
| E04 | 使用 Entity / Request / Response 等明确后缀映射 DO/DTO/VO，Domain 值对象不用 VO；无 ORM 注解要求 | 公共模型语义或持久化技术真实变化 |
| E05 | 保留 SQLite 原生事务与受控错误出口，不照搬 MySQL 专属约定或“每层捕获并打印”范例；不普遍吞掉 Throwable | 独立存储/事务/错误治理变更并证明安全等价 |
| E06 | 责任归属由变更工件、真实维护者审查与 Git 记录表达，不自动伪造每个类的作者姓名；重要注释写契约、原因、单位和安全边界 | 团队明确统一作者元数据政策后调整 |

上述是已批准的项目裁剪，不是临时忽略清单。新增例外需写明规则 ID、具体范围、理由、替代验证和退出条件；由负责人或其授权审查者确认。ACL、来源身份、证据完整性、测试保留和密钥防泄漏不得申请例外。

## 7. 人和 AI 使用同一套工作闭环

### 主线优先（2026-09-08 负责人补充）

以下规则约束每轮工作的取舍与顺序。负责人最新指令优先于历史 gate 中扩大分支、异常或权限增强的工作安排；后置事项继续保留，不缩减已接受的完整需求。

P01 收敛需求：每轮只推进一条正常业务流程，先在 plan 写清输入、用户操作、可见输出和成功条件，以可运行的用户流程确定主线。

P02 最小路径：只实现打通当前主线必需的行为与接线；主线尚未完成时，不自行扩展旁支、通用抽象或防御性增强。

P03 后置分支：不阻断主线的业务分支、异常场景、权限增强和审查建议统一登记 backlog，记录影响与后续处理条件，主线完成后再按优先级处理，不因发现新场景持续扩大本轮工作。

P04 插队边界：只有阻断主线、已复现的数据损坏或泄露、无法保持有证据回答的安全底线问题可以插队；说明具体证据和最小修复范围后继续主线。保留现有鉴权、证据校验、安全机制与测试，不得关闭鉴权、伪造证据或删除、跳过、放宽测试来制造成功。

P05 验证与汇报：迭代时运行直接相关测试和可复现验证；主线交付或发布时保留原全量测试、格式、静态检查、覆盖率及必要 acceptance 等门禁。汇报先说明用户已能完成的流程、实际验证结果和剩余阻塞，测试数量仅作辅助证据，不能代替流程完成。

H01 开工：按 [AGENTS](../AGENTS.md) 阅读工件及本文，给出要改的职责/文件、保持的 API/数据/安全不变量、是否触发例外。先检查 dirty worktree，不回退他人修改。

H02 设计：先写类型与依赖方向，说明新增类隐藏什么复杂性、为何需要转换或 Seam。涉及事务/并发须描述失败与回滚边界。人确认范围与高风险取舍；AI 不以“标准要求”为名擅自扩大到部署、数据库迁移或购买服务。

H03 实现：按小的可验收步骤，先记录失败测试/可复现检查，再实现和回归。已有测试跟随包迁移但保留行为断言；禁止删除、跳过、放宽失败测试或降低覆盖率。测试通过 Module Interface 验证可观察行为，不为方便测试扩大生产可见性。

H04 审查：执行者与独立审查者分别核对 Standards 和 Spec；层级检查绿不代表权限/事务正确，单测绿不代表实际 provider/浏览器/生产可用。只读诊断发现原有行为缺口时另建修复负例，不混进“纯重构”偷偷改变语义。

H05 交付：更新规格映射、命令/时间/环境、红绿结果、完整回归、必要 acceptance/浏览器、例外和未验证项。明确“规范已写 / 代码已迁移 / 门禁已接入 / 已推送 / 已部署”是五件不同的事。

## 8. 自动执行与人工判断

| 规则 / 验收 | 当前状态 | 实施与证据要求 |
| --- | --- | --- |
| 格式、编译、测试、行/分支双 80% | 0006 最终 `clean verify` 通过；297 项 Java 测试、150 个文件的 Spotless 检查；0005 的 283/148 是历史基线 | `mvn -s .mvn/settings.xml -gs .mvn/settings.xml clean verify`；不能认证本文所有规则 |
| UI 回归、敏感信息检查器及历史扫描 | 已有 Node 工具 | 见 [CONTRIBUTING](../CONTRIBUTING.md)；公开前扫描实际发布快照与历史 |
| L01–L08 真实类型依赖、反向依赖、循环、SQL 越层、注解归属 | ArchUnit **1.5.0 test-scope** 已接入；11 项架构测试随完整回归通过 | `ArchitectureRulesTest` 只导入生产字节码，拒绝空导入，检查全限定类型关系/静态调用，不仅 grep import；安全错误状态映射只允许精确叶子依赖 |
| SEC01–SEC06 安全入口、同事务授权与后台复验 | 0006 已验证摄取创建者当前 ACL、系统取消/审计、恢复及重试能力一致性；**非 SEC 全部完成** | 无身份/伪造/过期/错组织/重复凭据、只读修改、撤权竞态、无 HTTP 上下文的任务、CSRF/安全错误出口、敏感字段负例按规格与证据逐项判断；不得把 JOSE 依赖当完整框架接线 |
| Model 边界、字段注入、固定契约 Map | 字节码规则与模型边界测试随完整回归通过 | 公共返回类型及泛型需检查，必要模型 JSON 注解与 framework-free Domain 分开；枚举合成成员、测试替身等例外须精确，不做整包无限豁免；职责和成员命名仍需人工审查 |
| 新检查器本身可靠性 | 动态编译违规/合法 fixture 已通过 | 每类规则提供违规 fixture 应失败、合法 fixture 应通过；不能只因当前代码碰巧过绿就宣布可用 |
| ACL、PATCH 三态、并发、共享连接、发布回滚、进程生命周期 | 原 256 项测试逐项保留，分层后完整本地回归通过 | 验证真实 SQLite、HTTP、子进程和故障路径，不以 Mock 仓储取代事务证据；不推定既有测试以外的行为已覆盖 |
| 职责命名、必要抽象、事务语义、日志脱敏与例外理由 | 人 + AI 独立审查 | 在对应 REVIEW 记录具体证据；自动检查不能完全替代判断 |

不直接把旧 P3C-PMD 设置为 Java 21 强制门禁；它的默认 PMD 版本与新 Java 语法存在范围差异，替换 PMD 依赖也不自动兼容旧 AST 规则。具体来源和未实测范围见 [研究记录](research/alibaba-java-conventions.md)。0005 于 2026-09-07 10:52:26 +08:00 通过的 283 项 Java / 148 个格式检查文件是已冻结的重构基线。0006 最终 `clean verify` 于 **2026-09-07 11:33:24 +08:00** 通过：297 项 JUnit 测试（含 11 项架构测试，0 失败/错误/跳过）、150 个 Java 文件的 Spotless 检查，Node 回归 73 项通过。实际环境、完整回归和未验证范围以 [0006 验证记录](changes/0006-ingestion-authorization/verification.md) 为准，既不以“依赖已接入”代替执行证据，也不将本地验收扩大为完整阿里合规、实际 JDK 21 运行、完整 Spring Security/RAG 或生产批准。
