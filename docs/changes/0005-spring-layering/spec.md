# Spec：分层迁移验收

状态：本地重构验收通过（2026-09-07）；Java 整体仍为 IMPLEMENTATION。结构、行为、门禁及独立审查结果见 [verification](verification.md)，不代表完整 RAG 或生产验收完成。

## 已批准的目标结构

以 [JAVA_DEVELOPMENT_STANDARDS](../../JAVA_DEVELOPMENT_STANDARDS.md) 第 2–3 节为已批准结构：顶层按层、层内按需按业务分包。主线为 Controller → Service → Repository；Model 按 entity/dto/query/vo/domain 分类，security 显式拆分 authentication/authorization/web，client/tool/worker/job/config/web/exception/bootstrap 按实际职责存在，不生成空目录。

## 验收约束

1. Controller/Web 不依赖 Repository、JDBC、模型/进程 Adapter；Service 不依赖 Servlet、HTTP 状态或原始 SQL；Repository 不依赖 Service/Web/远程模型。
2. 固定请求与响应类型化，Entity/内部 claim 不直接输出；保留 PATCH 缺失/null/空值与既有字段、状态码、未知字段行为。认证 HTTP 提取与业务权限校验分开。
3. 从 ManagementModule 提取共享 Store、强类型 Repository 和业务 Service；全仓只保留一个受控 authority 连接/共享锁。批量逐项事务与发布整体原子事务保持原语义。
4. source revision、projection generation、完整 manifest/entries/active/audit、取消/重试和晚写隔离不变；进程协议与 fat-JAR worker 入口保持可运行。
5. 不增加无意义 ServiceImpl、Manager 或 BaseService；不引入 ORM 或改 schema 来迎合目录。
6. 架构检查覆盖真实类型依赖、反向调用、循环和 Model 边界，且有违规 fixture 先失败与合法 fixture 通过证据。已通过生产字节码与规则自测，具体证据见 verification。
7. 保留全部旧行为测试。包名变化可以同步迁移测试入口，不可删除、跳过或放宽断言；补跨 Service 并发、共享事务回滚、DTO/HTTP 兼容回归。
8. 最后一次修改后完成 clean verify、双 80% 覆盖率、Node、安全扫描、必要浏览器/进程验证及独立审查；不把规范或局部绿灯当完整 RAG/生产证明。
9. 安全认证与授权规则按 SEC01–SEC06 显式归属；Security 策略不得反向查库或调用 Service，业务操作仍在同一事务使用当前 ACL。后台任务不依赖 HTTP 上下文。保留现有凭据/会话契约；若改为完整 Spring Security 过滤链，另列迁移和兼容性验收，不以依赖已存在宣称接线完成。

最终必须移除被替代的旧万能类、重复实现与废弃入口，不得保留生产兼容壳把业务转回旧实现。删除源码不授权删除/跳过/放宽有效测试或操作旧服务数据。上述源码约束须逐项验收后才能宣称重构完成。

## 明确记录的兼容范围

- 解析领域类型 `ParsedText`、`TextPage`、`TextSegment` 的诊断 `toString()` 改为脱敏；这是满足 M04/G05 的显式安全调整，不改字段、定位、文件内容或 HTTP 契约。保留先失败后通过的脱敏负例。
- 固定 HTTP JSON 在 Web 边界转换成 Query/Command，需要字段裁剪或不同展示形状时返回端转换成 VO；动态请求形状不透传到 Service。任务响应使用解析/索引两个视图，只有索引任务包含 `index_publication_id`。
- `FolderResult` 与 `FolderRemovalResult` 是可直接作为 HTTP 响应的安全、不可变 DTO：前者仅输出 `folder_id`、`name`、`document_count`、`can_edit`，后者仅输出 `folder_id`、`status`。目录列表的 `FolderListResult.items` 复用 `FolderResult` 并防御复制集合；不得为相同字段再创建 VO 与原样转发 Mapper。它们不包含 Entity、内部 claim、创建者身份、原始文件或 provider 密钥。删除重复目录 VO 不改变既有字段、数值类型、HTTP 状态及业务行为，仍须通过全部原 HTTP 回归。
- `IngestionTaskProcessor` 与 `IndexingTaskProcessor` 封装单次隔离执行、超时与失败映射；Job 只调度和管理生命周期，authority Service 只在短事务中领取和提交，互不形成循环。
- Config 负责 CLI seed 的资源装配与关闭，Bootstrap 仅调用 Service，不直接创建数据库连接。
