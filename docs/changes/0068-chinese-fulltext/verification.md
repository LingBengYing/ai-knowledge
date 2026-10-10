# 中文检索验证

## 红例与根因

2026-10-10 生产已有 26 份已发布资料、190 个片段。正常全文/加权接口、阈值关闭下，“外国企业或者个人”“外商投资合伙企业”等字面查询均 0 命中，原文实际存在这些词。实际 Milvus 集合 text 字段为 standard；run_analyzer 对中文连续句子输出整句 token，chinese 则拆词。

两组独立新合成 Milvus 集合差分：短中文在 standard 0 命中、chinese 命中指定行；英文 Cedar 两者命中正确行；不含共同 token 的负例均 0。初次中文负例错误预期 0，因为“合成”“的”等 token 与样本共享而召回 2 行，失败保留；此差分不宣称中文精确率已完全验收。分词用于候选召回，重排和阈值仍负责相关性。

## 本机

首轮配置回归先红后绿。85 项直接配置/协议测试通过；扩大 114 项 113 通过，旧 IndexingTaskProcessorTest.revokedWriterInterruptsRunningChildAndCannotPublish 的 can_retry 断言失败，保留。

真实 Spring/SQLite/索引 worker + loopback 协议：Analyzer-only 新代际向量复用成功，原文/版本/嵌入身份保持、模型请求数不增加；改变旧向量行原文导致迁移失败，原 active publication 完整保持。另一次包含旧 embedding rebuild 的 6 项回归中，5 项通过，旧 GET 轮询恰逢维护锁而返回 migration_incomplete/503，失败保留，不修改断言求绿。

共享 20 请求预算原子并发测试及默认无限额测试通过；预算仅由运维临时 JVM 参数显式启用，普通运行不限制模型请求数。

最终清理准入/预算/配置/协议 53 项串行回归通过。该轮不覆盖或清除前述两条历史/竞态失败。

## 制品与发布边界

最终制品相对实际 0066 生产包只变更 14 个生产源码、22 个 class；依赖、资源与 manifest 内容逐项保持。schema 35 不变。

首轮运行账户预检拦截 features.env 被 umask 收紧为 0600，服务未切换；修正公开运行配置到原 0644 后通过。初次迁移预检发现历史 blocked 清理被当作运行任务，尚未提交 rebuild，模型 0；保留其记录。该实际阻塞通过专门回归修正，不触发清理或改业务状态。

真实副本、停机备份、索引迁移、真实问答与同版本原件结果以 deployment-verification.md/provider-run.md 最终证据为准。未完成前不得声明生产问答已修复。旧全仓 ACL/readiness/质量开放事项不随本次局部回归自动关闭。
