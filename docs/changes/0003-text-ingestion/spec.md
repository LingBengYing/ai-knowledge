# Spec: 上传到持久解析证据

1. 身份通过现有 Authentication Module；创建文件者获得 owner。原文件 1字节–20 MiB，仅 PDF/TXT/MD；文件名、UTF-8、后缀/签名校验，拒绝路径、未知字段/重复参数、空文件和超限。原文件 BLOB、hash、文档/revision、任务与审计在同一 SQLite 事务创建，上传成功返回202而非已完成。
2. ManagementModule 保持唯一连接和进程生命周期锁，v1受测迁移到v2，不改合成资料身份/权限/整理行为。真实文件绑定不可变 revision、parser revision、页文本和 Unicode code point segment locator；管理编辑不能改动这些字段或触发解析/模型。
3. 持久任务状态 queued → processing → parsed 或 failed；queued/processing允许取消，failed/cancelled允许有界显式重试，旧claim不能提交到新attempt。进程重开将未结束的 processing 标记为可重试失败，不凭锁文件猜测远端进程状态。task读取及动作在事务内验证当前组织/ACL；返回安全失败码、不含原文或栈。
4. API进程只调度任务，文件解析在独立Java子进程执行。父进程通过有界协议传递原文件，子进程不打开authority DB、不继承provider凭据；固定堆/元空间、单解析并发、总deadline、输出限长、超时/取消终止并等待。父进程重新验证页/segment数量、顺序、范围和逐字内容，未完整成功不得部分持久化。
5. Java进程分离并非OS文件/网络沙箱；当前仅开发/test，不能据此开启生产上传。生产仍需无网络/只读根文件系统/资源配额的容器隔离与同镜像验收。解析到parsed不代表已索引，active_revision_id=null，can_answer=false；合成行继续明确标识。
6. 同源 HTTP：POST `/v1/documents?filename=...`，Content-Type application/octet-stream；GET `/v1/ingestions/{taskId}`；POST `/v1/ingestions/{taskId}/cancel` 或 `/retry`，无请求体。已有鉴权、Origin、错误安全头不放宽。上传同时最多2个，超限429；非阻塞ReadListener限20MiB，接收deadline默认30秒、超时408且不创建任务。业务不放在HTTP Adapter。默认关闭；`RAG_INGESTION_ENABLED=true`显式启用且仅字面loopback绑定，配置在启动验证。
7. 网页根据能力开启文本上传，标明支持类型/大小，展示真实任务/失败/取消/重试；身份或页范围变化使旧异步结果失效。前端开发代理只为精确上传路由扩大请求上限，现有JSON路由仍128KiB；无通配转发、凭据降级或重试写操作。
8. 保留148 Java和全部Node基线、四PDF及六golden。新增真实SQLite迁移/恢复、进程异常/超时/取消、真实HTTP/前端合同回归；全量verify与80%行/分支门禁不放宽。浏览器真实文件上传验收。尚未实现答案，六golden不得写为Java问答通过；独立审查后才声明本切完成。

解析修复版本：`java-text-parser-v2-monotonic-codepoints`。空白裁剪后的同页分块起点严格递增，仍保留全部非空白code points及精确页内定位；不能降低子进程或authority校验来接受重复起点。旧解析证据保留原parser revision，不就地改写；0003尚未发布真实摄取，v1是历史独立解析Adapter。不同parser revision的未完成任务不得被新算法默默提交，后续重建需显式新revision。
