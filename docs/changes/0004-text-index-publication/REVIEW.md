# Review

状态：IMPLEMENTATION，已进行分域独立审查，仍有P2修复及最终组合验证待完成；整体BLOCK，不是生产批准。

重点：v3增量迁移/一致性备份、single writer、完整manifest而非top-K、实际一致性与revision写入冻结、float32确定性、bounded child协议与密钥、总deadline/取消、重试费用、旧claim和半份索引永不active、当前ACL、列表不冒充问答、保留全部旧测试。

最终由未参与对应实现的上下文按Standards/Spec只读审查。所有未运行的provider/Milvus、浏览器、Java21、生产或golden均不得推定通过。

## 2026-09-07 分域发现与复核

- P1：父进程崩溃后旧worker或已被上游接受的旧写可能覆盖重试发布结果。实际kill父进程回归先红；现用每claim新generation、确定性physical ID、protocol清单校验以及同事务attempt/publication/完整映射。独立复核确认旧claim/generation不能提交，晚写不能复用新generation物理ID，原P1的覆盖风险在源码范围关闭。父存活watchdog和跨JVM lease不承诺撤回HTTP；真实provider/Milvus和完整authority进程重启仍需验收。
- P2（源码独立复核关闭）：IndexWorkerLifetime同JVM等待者另开相同文件channel并在超时后关闭，某些POSIX实现会同时释放该JVM已有OS锁。真实跨JVM回归连续两次红测后，改为64个固定Semaphore条带在channel打开前准入；close幂等且确认channel关闭才释放。4项专属测试通过，独立复核确认等待者无channel可误关、重复close不释放新持有者许可。条带碰撞会保守串行不同collection并计入等待预算。依据[JDK 21 FileLock平台说明](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html)。该问题不重新打开上述generation晚写覆盖风险，仍须最终全量验证。
- P2（已修复）：合成资料的legacy active字段误示可检索身份；active统一来自publication，registered_revision_id保留原身份和数据库值，新增回归并保持旧行为测试。
- 前端两项P2（源码独立复核关闭）：任务poll未更新详情终态；取消/重试后的列表刷新丢失未保存表单。新增实际app受控DOM测试先红后绿，未以其替代真实浏览器。另有相关P2：权限editor降为reader后保留表单的保存按钮/submit仍用旧权限，后端会拒绝PATCH，但界面状态错误；该新增边界在修复复核中。

当前定向组合108 Java测试通过，不等于全量verify/覆盖率/source-manifest认证；最终结果见[VERIFICATION](../../VERIFICATION.md)。检索与答案六golden未执行，多模态、外部集成及生产gate保持不变。
