# 合同

- 支持显式 standard/chinese 两种 Milvus 文字分词配置；未知类型拒绝。
- 旧配置、旧构造器及已封存 anchor 继续绑定 standard，旧 projection identity 字节不变。
- chinese 使用独立 schema 标记及 projection identity；不得把旧 standard 集合视为中文索引。
- 新 anchor 持久绑定分词类型；环境变更不能暗改已发布资料的索引身份。
- 中文切换使用新集合及完整验证后显式切换，保留原文、版本、旧集合和回滚能力。不得直接修改旧索引或伪造 receipt。
- 实现同嵌入身份/同原文版本的逐条 receipt 校验、向量流式复用、独立新 generation 与完整验证后原子切换；历史 publication 不修改。
- 负责人已具名批准迁移现有已发布资料及一次原问题真实生产复验，新批最多 20 次模型 HTTP、双服务商统一计数、无自动重试、失败即停。
- 迁移优先零模型向量复用；不借用已关闭的一次复验授权，不把配置测试称为实际业务回答验收。
# 真实复验执行护栏

真实迁移预检还定位到 5 条 `blocked` 清理任务。`canStart` 仅将 pending/running 清理计为活跃工作；blocked/failed 不自动重试，不修改其资料或清理账本。删除资料仍由 tombstone 从迁移快照排除，运行中的清理继续阻止迁移。

用户已具名批准迁移与一次“新加坡人可以注册企业吗”生产复验，新批所有供应商共最多 20 次 HTTP、无重试、失败即停。
维护窗口内仅复验 JVM 临时设置 `-Drag.validation.modelHttpBudget=20`，共享传输在外部派发前原子计数；Agent loopback 协调调用不计作供应商请求。迁移阶段应为零模型请求。
复验结束移除 JVM 参数再恢复公开入口。普通运行未设置时无限额；不是产品 token 上限、检索限制或旧余额。
