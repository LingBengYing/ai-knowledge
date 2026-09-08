# Spec：任务授权失效与确定性终态

状态：本地验收通过；14项新增负例先失败后通过，最终全量证据见[verification](verification.md)，独立审查见[REVIEW](REVIEW.md)。Java整体仍为IMPLEMENTATION，不把本规格当生产证明。

## 行为

1. 任务仍绑定持久化且不可变的 `created_by`，不信任调用者传来的角色，不把其他 editor 视为原创建者。Service 在现有 Store 事务内通过 ManagementRepository 读取该创建者在该组织/文档的当前角色，纯 DocumentPermissionPolicy 仅接受 owner/editor 为可执行权限。
2. 领取 queued 前复验，失权者系统取消为 `cancelled`，`error_code=null`、token 清空、attempt 不增加；先取消再跳过，并在待处理配额上限内继续找合法任务，不能使后续任务饥饿。取消项不得读取并返回 original 或启动解析器。
3. 完整 claim 身份校验必须先于授权失效处理。空、伪造 token、错组织/文档/revision/文件名/MIME/parser、旧 attempt 等无效 claim 仅返回 false，不得借其取消当前任务。有效 processing claim 在 isCurrent、complete、fail 路径失权时同事务系统取消并返回 false；不能只返回 false 而把任务留在 processing。

   “身份”指既有元数据、attempt 和 token 绑定，不是对所有 payload 做相同处理。complete 保留原内容 SHA/current parser 校验及 `parser_output_invalid` 的 422 契约，顺序为身份 → 内容与 parser 身份 → 当前授权 → 解析结果/持久化；坏内容不得把原错误改成取消。isCurrent/fail 不消费 payload，不额外在每次 100ms 监测中重复散列最大 20MiB 文件，其权限决定仍仅依完整持久 claim 身份与当前 ACL。
4. TaskProcessor 在创建 parser 前再次调用当前 claim 复验。已启动解析由现有 Job 的有界检查发现失权并中断，ProcessTextParser 终止并等待实际子进程清理后才能继续下一任务。撤权与已经交给本机子进程的数据不是可原子撤回的同一动作，不声称零时间停止或 OS 沙箱；最终提交复验保证失权结果不能成为证据。
5. complete 的授权判断先于任何页/segment/parsed 指针写入，并与提交同事务。失权取消和系统审计整体提交；不产生部分证据，不发布 active，不改变原文件/hash/source revision/parser revision。后续 parser failure 或迟到 complete 不得覆盖取消或重复审计。
6. 系统取消使用独立 action `ingestion_authorization_cancelled`，actor 为当前任务组织的 `system:ingestion`；只记录状态/原因等白名单字段的摘要和字段名，不记录角色、正文、claim token 或问题。恢复启动时 processing 的失权任务同样取消，合法任务仍按既有 `worker_interrupted` 恢复为可重试失败。
7. 取消仍允许当前 owner/editor 操作，即使不是创建者；不得因创建者失权剥夺合法管理者终止任务的能力。重试先验证当前调用者可编辑、既有状态/三次上限，再复验原创建者权限；创建者仍失权时返回 409 `authorization_changed`，不增加 attempt、不排队。恢复创建者 owner/editor 后可显式重试，旧 claim 不能跨 attempt 提交；取消不会自动重试。
8. 任务详情及列表的 `latest_job.can_retry` 必须同时满足调用者可编辑、原创建者当前可编辑、failed/cancelled 及 attempt<3；`can_cancel` 仍按当前调用者权限及 queued/processing。其他 JSON 字段、状态码、PATCH 语义和索引契约不变。
9. 使用既有合法状态 `cancelled/null error`，不把授权取消伪装成 parser_failed，也不修改旧 v2/v3 schema、CHECK 或触发器。HTTP `authorization_changed` 是安全业务错误，不写入受限 ingestion error_code。数据库迁移若将来需要新增状态/原因须另列工件，不能关闭约束求通过。

## 必须验证

- queued 撤权、全部队列撤权与合法后续任务；领取前零 parser 调用。
- 有效 claim 执行中撤权、直接 complete/fail 撤权；原文件/源身份保留且零 parsed 证据。
- 伪造和旧 claim 不得取消有效新任务；creator owner→editor 仍合法，owner→reader 或移除 ACL 则停止；跨组织不影响其他任务。
- 合法代办 editor 可取消但不能绕过原创建者复验重试；详情/列表 can_retry 一致，恢复授权后的显式重试和次数上限不变。
- 实际慢子进程在自身 deadline 前因撤权退出，清理后重试成功且不重叠；parser 创建前再次拦截失效 claim。
- 关闭重开/恢复、取消审计幂等及无敏感数据；完整摄取/管理/索引/HTTP、四 PDF 解析、Node 与所有原 283 项 Java 回归。
- 最后修改后 clean verify、双 80% 门禁、架构规则、敏感信息扫描和独立 Standards/Spec 审查。六个 Java 最终问答 golden 仍未接通，本变更不能冒称 RAG eval 完成。
