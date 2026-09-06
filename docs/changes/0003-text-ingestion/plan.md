# Plan

1. 冻结authority、解析进程、HTTP与浏览器Interface，先失败测试。
2. authority worker拥有ManagementModule迁移/任务/语料持久化及测试；parser worker拥有ProcessTextParser/ParserWorker及测试；UI worker拥有两个仓库静态文件、前端代理与对应测试/文档；主线程拥有调度/HTTP/配置/集成及本工件。
3. SQLite单写者内完成权威变更，外部解析/未来模型调用不占事务。原文件暂存SQLite BLOB避免文件系统与DB双提交不一致；20MiB上限与开发总量配额保持有界，未来大媒体通过新工件接对象存储。
4. 单个调度线程领取任务、启动有界子进程、提交或失败；取消使claim失效并终止当前进程。解析成功保存证据，不发布尚未索引的版本。
5. 冻结后依次运行各worker测试窗口，禁止并发Maven写target；最后全量clean verify、Node、敏感信息扫描与独立审查。新隔离目录/端口启动，不修改现有演示资料；实际浏览器上传并记录结果。
6. 更新能力/API/AI导航/验证边界；关联完整文本路线图而非缩小总目标。检索/答案、媒体与生产门禁继续推进，未完成不提前声称。

技能影响：codebase-design要求单authority隐藏事务/ACL复杂性；fullstack-dev用于配置、文件限额、独立worker、HTTP错误和网页状态闭环。沿用已有会话，无新增SSO/刷新令牌或跨域架构。

实现决策：v2采用增量sidecar而不重建legacy documents，保留原immutable_identity触发器与合成基线。真实active字段在corpus_documents，legacy registered revision不作检索authority。既有v1先创建新名称一致性备份再迁移；失败不自动覆盖/恢复。HTTP使用Servlet ReadListener，限制接收并发和总接收时间，不在同步请求线程运行解析。浏览器验收使用playwright技能、独立缓存与新目录/端口，不触碰原演示。

2026-09-06本切本地完成：190项Java、31+11项Node、独立审查、打包JAR真实上传/失败/重试/ACL/locator与重启验证全部通过。独立发现的空白重叠窗口问题先复现再修复，并升级parser revision；未放宽严格定位校验。前端已独立发布，Java完整发布状态见当前[验证](../../VERIFICATION.md)及对应Git提交/Actions。

偏离与边界：原计划“单个调度线程”实际为一个解析调度加一个取消检查线程，共用单解析并发；为保证阻塞child可及时取消，不增加并行解析。进程环境清理的macOS系统注入变量精确例外见REVIEW。没有扩大到OS沙箱、真实模型或索引。下一工件接通索引任务、完整投影可见性验证、权威active发布和有证回答；完整文本/多模态/生产目标继续，不用局部完成终止总目标。
