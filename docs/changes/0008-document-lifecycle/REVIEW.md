# Review：文档生命周期

状态：A步限定Standards/Spec审查通过，整体0008仍IMPLEMENTATION。固定前像10dff37；已审实际WIP及新增文件全文，不是HEAD三点空diff。

Standards关注Spring职责、无嵌套事务/隐蔽I/O、强类型Model、安全错误与有界进程。Spec关注原物理删除目标未被逻辑撤下替代、全当前读取路径、完整selected set、在途任务/晚写、历史scope留存、原文件配额和同事务审计回滚。

迁移须保留v1–v4DDL和既有不变量，v5继承格式检查不能漏掉trace/generation；partial/损坏格式不能自动补表。独立审查者不得自批自己的生产实现。结果绑定最终源码与真实回归，不能用旧773项或CI认证新增代码。

设计限定复核（adapter_layering，未参与本切实现）：A/B划分无结构性BLOCKER，三项IMPORTANT已补入spec/plan，尚须实现与真实回归证明：合法重放专用当前ACL查询及降权/撤权404；202以事务提交为准、实际进程退出与准入另验；当前查询与历史/配额查询分类、同内容新身份累计配额、v5重开与旧备份边界。该设计结论不是代码审查PASS。

## Standards：最终限定通过

独立lifecycle_standards未参与实现，复核17个生产文件、9个测试文件及相关工件；6个新增测试全文已读。0个硬违规、0个启发式建议。HTTP、Service事务与授权、Repository SQL、DTO/Entity和Config职责符合项目规范，v1–v4迁移方法逐字节保持。未为测试扩大生产Interface，也未删除原测试失败断言。

格式后生产文件清单摘要`e8662c9b674e0155ebd75f9e4e98b8513cf5e0158b0a5516339e3ba3a914dff5`；9个受审测试清单摘要`6b326efc34532f5f30e341899e8d9a1e6c143df6d887111af62321037da7f164`。清单按相对路径排序，每行“SHA256、两个空格、路径、LF”。完整构建输入绑定见[source-manifest](source-manifest.json)。

## Spec：最终A步限定通过

独立adapter_layering未参与本切生产实现，复核当前ACL重放、审计/取消/墓碑回滚、分页前过滤、完整问答范围与旧来源失效、配额保留、迁移失败及重开。最终0个未关闭finding。先前guard导致撤权任务卡在processing的问题已有真实红绿关闭；两类子JVM退出断言使用撤下前统一五秒截止，不能靠30秒自身超时假绿。

最终6个新测试文件共84项：Service23、HTTP6、Config8、migration31、Answer12、实际进程/旧claim4。主线程实际Temurin21全量857项Java、73项Node通过；独立审查者没有另行运行测试，不能混称独立执行证据。保留检查及结果见[verification](verification.md)。

## 结论边界

Standards 0项，Spec A步0项；两轴不替代运行验证，不扩大为完整0008或生产通过。物理清理及完成回执、批量硬删、重建/恢复、网页、多模态和生产仍未完成。deleting/pending仅代表权威撤下已提交，原文件与向量/备份尚未清理，readiness继续503。
