# Text Ingestion · 0003

真实文本到持久解析证据，验收状态见[VERIFICATION](VERIFICATION.md)与[REVIEW](changes/0003-text-ingestion/REVIEW.md)。无索引发布、最终问答/引用或多模态；解析正确不证明RAG质量。

## Interface

```text
Browser → authenticated UploadServlet (nonblocking, max 2 uploads)
 → ManagementModule: original BLOB + revision + task + ACL + audit, one transaction
 → IngestionRuntime: claim; no DB transaction during parsing
 → ProcessTextParser → bounded stdin → separate ParserWorker JVM
 ← validated pages / Unicode code point segments
 → authority conditional commit on current claim/attempt → parsed (not active)
```

原文件BLOB跟任务原子写入，不使用用户路径保存，避免文件系统与DB双提交不一致。当前固定开发额度不是最终大媒体对象存储架构。Java独立目录和single writer保持，不读旧数据库或现役Milvus集合。

## 配置与限额

`RAG_INGESTION_ENABLED`默认false，显式true且绑定字面127.0.0.1或::1才启用。`RAG_INGESTION_PARSE_TIMEOUT_MS`默认30000、范围10–60000；`RAG_INGESTION_UPLOAD_TIMEOUT_MS`默认30000、范围10–30000。程序不自动source `.env`；无模型密钥也能摄取文本，不自动调用模型/Milvus。

子进程使用当前JDK和固定资源选项，清空继承环境，不传数据库地址/provider凭据。JVM资源限制不等于OS级RSS/CPU/文件/网络隔离；生产仍需非特权容器、无网络、只读根文件系统和硬配额的同镜像验收。

## HTTP与任务

POST `/v1/documents?filename=<URL-encoded original name>`，唯一Content-Type application/octet-stream，body是原文件字节而非JSON/base64/multipart。仅唯一filename参数；文件1字节–20MiB，只接受PDF/TXT/MD。最多2个上传，超过429；接收超时408且无任务，超限413。authority在事务中验证并返回202任务，不等待解析。

GET `/v1/ingestions/{taskId}`；POST同路径`/cancel`或`/retry`，均不接受query/body。沿用身份和同源限制，无权与不存在统一404。网络中断时先检查列表，不自动重试上传或其他写请求。

任务含task_id/document_id/revision_id/state/attempt/error_code/can_cancel/can_retry与时间。queued→processing→parsed或failed；queued/processing可取消，failed/cancelled可显式重试、最多3次attempt。取消使旧claim失效；重启把未完成processing置为可重试失败，不自动无界重试。

真实列表行synthetic_fixture=false，latest_job及状态/分块数来自持久任务；active_revision_id=null、can_answer=false。合成行仍保持原语义。parsed仅证明权威存储已有验证过的页/segment，不证明已索引或可引用作答。

当前parser revision为`java-text-parser-v2-monotonic-codepoints`，修复空白裁剪与重叠窗口造成重复起点的问题；全部非空白code points和精确原文范围保留。每个新上传冻结该版本，父进程/authority继续严格校验；不就地升级旧解析证据。旧版本未完成任务不能由不同版本算法提交，后续重新索引/编译须创建明确的新revision。

## 迁移与恢复

v1升级持有原single-writer锁，先创建新名称的一致性SQLite备份，再单事务添加v2 sidecar。legacy documents.active_revision_id只保留为不可变注册revision兼容字段；真实active由corpus_documents持有、初始null。未来检索不能使用legacy字段。

备份失败必须拒绝升级，不覆盖或自动恢复。回滚需先停止新writer、保存新库，然后由操作人把已验证v1备份复制到新独立目录；v2新增资料不在旧备份中，禁止自动覆盖含新资料的库。本切无在线schema降级或旧Python迁移。迁移/恢复测试结果见当前验证记录。

参考：[Jakarta ReadListener](https://jakarta.ee/specifications/servlet/6.1/apidocs/jakarta.servlet/jakarta/servlet/readlistener)、[Spring ServletRegistrationBean](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/web/servlet/ServletRegistrationBean.html)。
