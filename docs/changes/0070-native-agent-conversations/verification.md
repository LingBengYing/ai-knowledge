# 本机验证记录

日期：2026-10-11。后端基线fb0171de33e6d683fa117c13904d83aae5cf0aab，前端基线5c5fd79b376c45395918a3e20bd8d8ac2f60bf2b；验证包含本变更未提交工作树。

## 已完成

- 原生工具协议Java回归55/55通过，覆盖原生tool_calls/tool_call_id、回调、压缩模型请求、来源与结果校验。
- 对话/迁移Java回归30/30通过：ConversationService12、KnowledgeAgentService13、ConversationController1、迁移4。无失败、错误或跳过，完成于09:10:22 +08。报告位于`/private/tmp/knowledge-conversations-build.X6a0kj/surefire-reports/`。两组Java回归存在重叠，不汇总成85个独立用例。
- 实际运行Java为OpenJDK22.0.2、编译release21，Maven3.9.9离线依赖；不将此记录称为实际JDK21运行。
- 新建不含DB-GPT的CPython3.10.17环境，Python111/111通过；pip依赖检查、uv离线锁检查通过。标准tool-call HTTP实际loopback覆盖在内。
- 前端完整串行回归506/506通过，JS语法与两仓git diff检查通过；前端证据见Web0051。
- SQLite35→36迁移/备份/重开保持旧表数据，新增对话表进入清理inventory。分页按20轮/约2MiB封页，超长对话完整分批压缩，原始消息保留。
- 独立只读复核确认摘要不能冒充当前证据，压缩检查点原子提交；分页DTO聚合、引用返回当前对话、未知提交只GET核对和切页创建锁修复后没有确认的主线阻塞。

## 最终完整合成链

最终编译快照运行于`/private/tmp/native-agent-integration.RuoNci/`，记录`verification.json`，前端18231、Java18232、Python18233，独立合成SQLite/资料和loopback模型/向量服务。

实际上传两份新合成TXT，等待自动索引（不手动再建索引），完成两轮原生搜索/阅读/回答、两条来源原件SHA校验、知识整理建议、草稿持久化、对话新建/列表/改名/空对话删除/历史分页与自动压缩。重复request_id返回同一任务，取消后的晚到结果不改变终态。

2026-10-11 09:11:53 +08同库Spring重启后只读验证：原件SHA、草稿、两轮原始问答、分页和压缩检查点均保持。重启前后loopback计数严格保持Agent模型10、搜索4、阅读4、压缩1；这些不是云供应商请求，云请求0。

浏览器实际选中会话、阅读原件并返回同一会话地址、改名为“灯塔项目 · 连续对话演示”后刷新回读成功，原始两轮与“已压缩1轮上下文”保持。该浏览器改名发生在脚本验收后；脚本记录里的旧标题是历史快照，不能拿它断言当前预览标题。前端截图见Web0051/conversation-proof.jpg。

## 既有失败与边界

单列ArchitectureRulesTest为10通过/1失败：原有IndexingRepository引用client.vector.RevisionManifest，不是本切新增依赖；保留原断言。本轮未运行全仓发布门禁、Linux发布包或生产数据库副本迁移。

真实供应商请求0。合成模型能验证协议、上下文接线和生命周期，不能证明DeepSeek/硅基流动的真实压缩质量或回答质量。没有修改生产资料、索引、配置和服务，没有Git提交/推送。

Java/Python内部协议同步改变、SQLite升级到36，未来发布必须同批更新后端执行器与前端，先备份并演练；不能旧包或旧数据库覆盖发布后新写入。
