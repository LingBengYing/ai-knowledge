# 0094 交接

负责人要求先做非多模态建设；本轮只实现批次选源建设、来源更新维护、规则质量待办。后端 main 基线13ddbaa，前端 main基线bc8d9e9；未提交、推送或部署。来源绑定、审阅采纳、旧版本和正常问答继续沿用现有合同。

先读本目录 intent/spec/plan/REVIEW、verification、browser-verification；前端对应0062。使用说明 docs/KNOWLEDGE_CONSTRUCTION.md，代码文件摘要见source-manifest.json（整文件可能包含已有WIP，不是发布范围）。

## 本地运行

- 前端预览 http://127.0.0.1:18394/，实际Java http://127.0.0.1:18091；development_headers 身份owner，组织org-main。
- 数据为 `/private/tmp/knowledge-0094-browser-data` 中本轮新合成资料；模型/Milvus均为本机协议服务，不能把此预览当真实供应商效果。
- Java由隔离源 `/private/tmp/knowledge-0094-maintain-src` 的已编译主类/测试辅助启动；生产JAR不含测试辅助。显式测试辅助拒绝已有数据目录，别用它覆盖当前数据。
- 当前交互进程接收 `restart` 可重开同一数据，`stop` 结束；不要误杀其他本机服务。临时运行文件不提交Git。完整验证日志在工作区 `.local/knowledge-construction-0094/`。

## 下次发布

生产应用仍是0085/schema42，本轮schema43仅新增批次归档sidecar。生产变更需要先对真实副本迁移和兼容回退验证，保存当前业务数据；禁止用旧库覆盖后续写入。当前构建使用保留历史图片/OCR WIP的隔离工作树，不是可直接上线的干净发布包。

前端必须同时包含wiki-construction.mjs及精确代理新路由。capability为wiki_maintenance、batch_sources、batch_organization；生成修订提案使用编译请求期限，普通维护查询仍为读取期限。

真实模型语义质量、生产容量和旧全仓架构/覆盖率问题仍开放。不自动续用任何历史模型余额，不重试生产旧失败任务，不改变0093 Milvus补丁/guard身份或当前生产索引。原生图片/音视频下一阶段再做。
