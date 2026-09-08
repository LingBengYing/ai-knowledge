# Intent：贯通授权文本问答与可追溯来源

状态：IMPLEMENTATION。Java分层重构已单独交付；当前保留0007实现，处理已定位缺陷并补本地验证，不再反复隔离/恢复源码。前端详情页不改，生产gate保持。

2026-09-07 15:59:54实际Temurin21.0.12.1+1完整clean verify通过635项Java及双80%门禁，Node73通过；真实本机HTTP、四PDF六golden和16项真实分块条件组合已纳入。本批条件/主体与Domain不变量修复通过限定两轴审查；仍须完整语义审查、同生产镜像、实际provider/Milvus、网页及生产验收。结果和历史可恢复归档见[verification](verification.md)，不将局部修复或确定性测试冒充完整目标完成。

目标：从已有真实PDF/TXT/Markdown解析与索引publication，贯通当前授权检索、embedding、Milvus dense/BM25、重排、摘录、服务端事实与引用验证、最终完整范围复验及可还原审计。不是只做六个样例的关键词演示，也不把原文子串校验当成事实支持。

继续Java/Spring layer-first、Model/Security职责分离与奥卡姆剃刀。原297项Java/73项Node及固定四PDF/六golden保留。前端详情页设计不改；新增后端能力先独立opt-in，前端未接线不得宣称网页问答验收。旧Python源码可只读参照行为，不能运行或转发其业务、改旧服务或共享旧数据库/集合。

0007完成也不等于最终目标完成：图片/音频/视频/附件、生命周期、真实模型与Milvus、Java21同镜像、备份迁移/回滚、生产身份、容量与受控发布仍逐项验收，不解除production gate，不使用聊天历史凭据。
