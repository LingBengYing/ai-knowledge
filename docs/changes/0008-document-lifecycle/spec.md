# Spec：删除请求先从授权面失效

状态：IMPLEMENTATION。当前执行A步（撤下/取消/审计）；B步物理清理尚未实现，0008不能标记整体完成。

## 1. 公开契约

- 新增默认关闭的`RAG_DOCUMENT_REMOVAL_ENABLED`，仅development/test和字面loopback允许开启。不要求模型配置，不隐式启用摄取、索引或问答。关闭时新路由404，旧能力与状态不变，readiness503和production拒绝保持。
- 开启后`DELETE /v1/documents/{documentId}`，沿用可信Actor/Origin/安全错误/no-store/request ID；拒绝query、非空body、非法ID。当前owner/editor可请求；Service层reader/未知/其他组织Actor/无文档权限统一NOT_FOUND（HTTP映射404）。HTTP认证先按原固定组织契约执行：异组织token/header和JWT无身份为401，开发模式缺身份为422 invalid_identity；不为新接口放宽或替换原认证行为。
- 202回执仅含`document_id`、`status: "deleting"`、`cleanup_status: "pending"`、稳定的`requested_at`。表示已立即撤出服务面，但物理清理尚未完成；不返回文件路径、正文、claim、模型配置或操作者信息。
- 同一仍有当前写权限的调用者重复请求返回原回执，不重复取消或追加删除审计；不能绕过当前ACL或跨组织重放。Lifecycle专用查询允许读取已撤下身份，但必须先复验同组织当前owner/editor；普通currentRole仍排除墓碑。删除后降权或撤权的重放统一404。没有恢复/撤销接口。
- 能力名称只增加`document_removal`（表示接受撤下请求），不能把`document_delete`从未完成列表移除。原管理批量delete/reindex仍为501，原can_delete/can_reindex仍false，前端不接新按钮。完整硬删除及批量idle约束留待B步，不能拿本步冒充原管理硬删除验收。

## 2. 同事务的权威撤下

- 一个Store事务内验证当前权限，写不可变document_tombstones记录，取消该文档queued/processing摄取与索引任务并清claim，写删除与取消安全审计。parsed/indexed历史终态不改；不调用嵌入、Milvus、文件删除或其他Service嵌套事务。
- 源文件名/hash/size/source revision/pages/segments/publication/generation/历史trace全部保留。为避免已撤下资料的目录外键阻止空目录整理，在同事务清空其可编辑folder_id并记录摘要审计；不是原文件或证据身份变更。
- 任一步骤（包括取消或审计）失败全部回滚，不返回成功回执。202以事务提交为线性化点，不持Store锁等待子进程。真实子进程经既有current-claim检查取消并等待退出，准入在实际执行结束后才释放；数据库取消不等于远端HTTP撤回。
- 删除后上传不得复用已删除身份；物理BLOB仍占用现有配额。当前上传没有按SHA去重，相同内容重传必须产生新document/revision/job身份，旧、新BLOB累计占用配额，不通过隐藏行释放配额或复活旧资料。

## 3. 所有当前入口失效，历史审计不失真

- 管理列表与总数、当前角色、编辑、目录可见性/计数及标签建议在SQL阶段排除tombstone。过滤在分页前，别的可见资料正常；不可仅响应后过滤。
- 解析/索引的状态读取、重试、新领取、解析证据读取及索引sourceCurrent排除已撤下资料。删除前取得的旧claim不能写回解析证据或发布active；重启恢复也不能复活。
- 回答ALL/selected范围在候选截断前排除tombstone。显式所选任一文档失效时整次拒答且不调用模型；处理中删除被选中但未进入候选或引用的资料，下一阶段/最终锁内复验仍使整个scope失效，保留安全scope_changed trace，无答案hash/引用。
- 旧source读取必须失败；正文hydrate查询与当前publication查询都排除tombstone。历史publication和trace scope查询不排除历史行，以便验证真实旧快照并记录安全拒答，不得把正常删除竞态误判为伪造快照。
- 查询分类冻结：currentRole/当前active/hydrate排除；历史publication/traceScope/storedBytes保留。v5重开必须保留墓碑和已取消任务；v4旧备份不能保留之后的删除请求，完整恢复保证属于B步与生产gate。

## 4. v5增量与迁移

- 新增document_tombstones sidecar（document_id主键/RESTRICT外键、workspace_id、requested_by、requested_at），固定组织身份与documents匹配；不允许UPDATE/DELETE/REPLACE。
- v1–v4先按原链升级，再一致性备份v4并事务升级v5；失败不自动覆盖或还原原库。旧迁移方法DDL及不可变source/index/trace约束不改，v5仍验证所有继承的generation/trace结构。
- 版本标记必须一致；缺表/列/必要触发器的v5拒绝打开。旧格式备份可在独立目录用旧版打开，新增删除请求不在旧备份中，恢复旧备份不能被描述为保留删除状态。
- 不建立第二套active、通用事件总线、空Service接口或ORM；SQL仅在Repository，权限/事务在Service，HTTP/配置独立分层。

## 5. 验证与剩余门禁

1. 先取得真实HTTP新路由404→预期202的失败及v5迁移失败测试，再实现；不得用编译错误或fixture错误充当产品红。
2. 真实SQLite覆盖状态全组合、权限与重复请求、取消/审计回滚、重开、配额/目录/标签及旧claim晚到；真实HTTP覆盖无身份、JWT/开发身份、Origin、输入和关闭开关。
3. 真实parser/publication/AnswerService组合验证已成功回答的来源失效、ALL/selected零调用、在途删除非候选资料以及trace保留，固定四PDF/六golden保留。
4. 最后代码变更后完整相关文件、全量Java/Node、格式/双80%、旧测试逐项保留、密钥扫描、两轴独立审查和源码绑定。旧迁移测试只可扩展当前版本与旧夹具隔离，原失败断言保留。
5. 物理文件/BLOB/向量/缓存/备份清理、删除完成状态、批量硬删除、重建/历史版本切换、真实模型/Milvus、网页、多模态与生产均不能由A步通过推定完成。
