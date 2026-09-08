# Text Ingestion · 当前摄取契约

本页保留0003真实文本到持久解析证据的摄取契约与历史边界，历史审查见[REVIEW](changes/0003-text-ingestion/REVIEW.md)。当前0004索引任务、v3迁移与publication扩展见[TEXT_INDEXING](TEXT_INDEXING.md)，当前源码验证见[VERIFICATION](VERIFICATION.md)。摄取本身不自动索引；最终问答/引用与多模态仍未接通，解析正确不证明RAG质量。

0005 完成职责分层；[0006](changes/0006-ingestion-authorization/spec.md) 单独补齐摄取后台创建者当前授权复验，并通过本地回归。2026-09-07 11:33:24 +08:00 最终 `clean verify`：297 项 JUnit 测试（含 11 项架构测试，0 失败/错误/跳过）、150 个 Java 文件的 Spotless 检查；Node 73 项通过。详见 [0006 验证记录](changes/0006-ingestion-authorization/verification.md)。本次没有新增权限管理端点、修改 schema、推送或部署，不代表完整 RAG、Spring Security 或生产验收。

## Interface

```text
Browser → authenticated UploadServlet (nonblocking, max 2 uploads)
 → IngestionService → IngestionRepository + ManagementRepository
   original BLOB + revision + task + ACL + audit, one SqliteAuthorityStore transaction

IngestionJob → IngestionTaskProcessor → IngestionService.claimIngestion
   current creator authorization checked before original bytes leave authority
 → IngestionTaskProcessor: recheck current claim before creating parser
 → ProcessTextParser → bounded stdin → separate ParserWorker JVM → TextParser
 ← validated pages / Unicode code point segments
 → IngestionService: current claim/attempt + source + creator authorization
 → IngestionRepository: validated evidence + parsed pointer, same transaction as audit
 → parsed (not active)

IngestionJob: periodic claim check → interrupt revoked work → await actual child cleanup
No authority transaction is held while the parser runs.
```

原文件BLOB跟任务原子写入，不使用用户路径保存，避免文件系统与DB双提交不一致。当前固定开发额度不是最终大媒体对象存储架构。Java独立目录和single writer保持，不读旧数据库或现役Milvus集合。

## 配置与限额

`RAG_INGESTION_ENABLED`默认false，显式true且绑定字面127.0.0.1或::1才启用。`RAG_INGESTION_PARSE_TIMEOUT_MS`默认30000、范围10–60000；`RAG_INGESTION_UPLOAD_TIMEOUT_MS`默认30000、范围10–30000。程序不自动source `.env`；无模型密钥也能摄取文本，不自动调用模型/Milvus。

子进程使用当前JDK和固定资源选项，清空继承环境，不传数据库地址/provider凭据。JVM资源限制不等于OS级RSS/CPU/文件/网络隔离；生产仍需非特权容器、无网络、只读根文件系统和硬配额的同镜像验收。

## HTTP与任务

POST `/v1/documents?filename=<URL-encoded original name>`，唯一Content-Type application/octet-stream，body是原文件字节而非JSON/base64/multipart。仅唯一filename参数；文件1字节–20MiB，只接受PDF/TXT/MD。最多2个上传，超过429；接收超时408且无任务，超限413。authority在事务中验证并返回202任务，不等待解析。

GET `/v1/ingestions/{taskId}`；POST同路径`/cancel`或`/retry`，均不接受query/body。沿用身份和同源限制，无权与不存在统一404。网络中断时先检查列表，不自动重试上传或其他写请求。

任务含task_id/document_id/revision_id/state/attempt/error_code/can_cancel/can_retry与时间，status是state的同值别名。queued→processing→parsed或failed；queued/processing可取消，failed/cancelled在下述授权条件成立时可显式重试、最多3次attempt。取消使旧claim失效，不自动重试；重启按创建者当前权限分别取消或恢复为失败。

### 创建者撤权、取消与重试（0006）

任务创建者来自持久化的不可变 `created_by`，不能由请求角色或代办 editor 替换。IngestionService 在现有 authority 事务内读取该创建者的当前组织/文档 ACL，只有 owner/editor 可执行：queued 领取前先验证，再读取原文件；TaskProcessor 创建 parser 前再次验证；运行中 Job 定期检查；最终 complete/fail 及启动恢复继续复验。owner→editor 仍合法，降为 reader 或移除 ACL 则失权。

queued 失权任务直接系统取消，attempt 不增加，也不启动 parser；在有界待处理额度内继续寻找合法任务。已运行任务失权后变为 `cancelled`、`error_code=null`，清空 claim token，并由现有中断/清理流程终止实际 child；清理完成后才能启动下一任务。取消与 `ingestion_authorization_cancelled` 审计同事务提交，actor 为任务组织的 `system:ingestion`，只保存状态/原因白名单字段名及摘要，不保存角色、正文或 token。迟到结果/失败不能覆盖取消或重复审计，不提交任何页、segment 或 parsed 指针，不改变原文件/hash/source revision/parser revision。

完整 claim 身份校验先于授权取消；伪造或旧 claim 不能取消有效新 attempt。complete 还先保留原内容 SHA/current parser 校验及 `parser_output_invalid` 契约，之后才做当前授权和解析结果提交。撤权不能原子撤回已交给本机 child 的字节，也不是零时间终止或 OS 沙箱保证；本地回归用 30 秒 parser deadline 验证撤权后 5 秒内实际退出及重试不重叠，这不是生产时延 SLA。

`/retry` 先验证当前调用者 owner/editor、failed/cancelled 状态及 attempt<3，再验证原创建者仍可写。创建者失权时返回 **409 `authorization_changed`**，不排队、不增加 attempt；这个 HTTP 业务错误不写入摄取 `error_code`。恢复创建者 owner/editor 后，合法管理者才能显式重试。任务详情与列表 `latest_job.can_retry` 同时要求调用者可编辑、创建者当前可编辑、failed/cancelled、attempt<3；`can_cancel` 仍只取决于当前调用者可编辑及 queued/processing，不因创建者失权剥夺其他合法管理者的取消能力。索引任务契约不受这次摄取变更影响。

真实列表行synthetic_fixture=false，latest_job及status/分块数来自持久摄取任务；解析刚完成时active_revision_id=null、can_answer=false。0004增加独立index_status/latest_index_job/index_publication_id，只有完整索引publication后真实active才非空，can_answer仍false；合成注册身份改为registered_revision_id、active为null。parsed仅证明权威存储已有验证过的页/segment，不证明已索引或可引用作答。

当前parser revision为`java-text-parser-v2-monotonic-codepoints`，修复空白裁剪与重叠窗口造成重复起点的问题；全部非空白code points和精确原文范围保留。每个新上传冻结该版本，父进程/authority继续严格校验；不就地升级旧解析证据。旧版本未完成任务不能由不同版本算法提交，后续重新索引/编译须创建明确的新revision。

## 迁移与恢复

0006 不修改既有 v2/v3 schema、状态 CHECK 或触发器，复用合法的 `cancelled/null error`。启动装配在 Job 调度前执行恢复：processing 创建者已失权则同事务系统取消并审计；仍有当前写权限则维持原 `failed/worker_interrupted`，是否可重试仍受当前权限和三次上限约束，不自动重试。

0003的v1升级持有原single-writer锁，先创建新名称的一致性SQLite备份，再单事务添加v2 sidecar。legacy documents.active_revision_id只保留为不可变注册revision兼容字段；v2 corpus_documents.active_revision_id保持恒null。0004继续备份并增量迁移至v3，真实active由独立publication sidecar读取，不放宽v2约束。未来检索不能使用legacy注册字段；完整迁移边界见[TEXT_INDEXING](TEXT_INDEXING.md)。

0004索引重试保留本页source revision，每次claim另建物理projection generation与完整映射台账；对外active仍为source revision，未来检索从publication读取generation再回查source证据。缺generation/台账的早期未发布WIP v3拒绝复用，不改变0003已发布v1/v2迁移路径；父存活、lease与晚写机制属于索引实现和当前验收范围。

备份失败必须拒绝升级，不覆盖或自动恢复。回滚需先停止新writer、保存新库，然后由操作人把已验证v1备份复制到新独立目录；v2新增资料不在旧备份中，禁止自动覆盖含新资料的库。本切无在线schema降级或旧Python迁移。迁移/恢复测试结果见当前验证记录。

参考：[Jakarta ReadListener](https://jakarta.ee/specifications/servlet/6.1/apidocs/jakarta.servlet/jakarta/servlet/readlistener)、[Spring ServletRegistrationBean](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/web/servlet/ServletRegistrationBean.html)。
