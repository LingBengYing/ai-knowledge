# 0094 交接

## 当前交接（2026-10-25）

生产Java为 `20261025-knowledge-construction-0094-r2` / `e94a6e17281fd572b1c1b6fb7501796e981c0dd0`，Web仍 `20261025-knowledge-construction-0094/frontend` / `17fc7429add3b592252cfa1cf6a407f37a657276`，已SWITCHED/OPENED。首版已完成42→43迁移及实际UID/兼容回退验证；r2在当前43真实副本上开包全部等值，不迁移。Agent0076-r2、OCR0078-r3、Milvus0093、免登录和模型配置保持。

首轮5次模型HTTP成功但v2提案重复care，浏览器dismissed，原run永久保留halted；后续仅追加1次revise_wiki，提案 `3af0a6cd-2e74-4180-baeb-cca5b853012a` 已浏览器人工采纳v2。原3章ID/顺序exact，guide3秒、care章节全等且无重复；统一6/6=SF嵌入3+DS生成3，0传输失败/0重试。v1内容保持、current2来源/当前原件SHA、目标维护待办消除及当前两份generation Strong已通过（2行/1024维/7只读HTTP、模型0）。

重启前旧数据保持已通过：109旧表全部172,178行，21文件/11受管backup摘要及DDL不变，新增行仅本轮业务。最后受控重启并零模型回读/当前快照等值、公网45项最终复跑均已通过；四服务active/NRestarts0，模型总数保持6。不要为收尾重发模型请求、重新上传/解析/索引；不要重启Milvus、恢复旧批次或重试旧failed。原run不复活，新修订一次性标记不得清除。

浏览器已见v1仍1秒、v2/current来源原文3秒。v1旧原件在替换后不受现有下载API支持；只验证过替换前SHA，不能把v1知识内容回读写成旧原件下载成功。发布和模型结果先读deployment-verification.md、provider-run.md、real-model-repair.md；私密原始证据留服务器，仅脱敏汇总可入库。r2回退只回0094 Java并保留最新43数据，严禁旧库覆盖；首版回更旧行为仍须43兼容包且排空placement批次。图片/音视频继续后置，旧全仓门禁、全库质量和规模性能保持开放。

## 历史本地阶段（以下基线及未部署记录已被上文更新）

负责人要求先做非多模态建设；本轮只实现批次选源建设、来源更新维护、规则质量待办。后端 main 基线13ddbaa，前端 main基线bc8d9e9；未提交、推送或部署。来源绑定、审阅采纳、旧版本和正常问答继续沿用现有合同。

先读本目录 intent/spec/plan/REVIEW、verification、browser-verification；前端对应0062。使用说明 docs/KNOWLEDGE_CONSTRUCTION.md，代码文件摘要见source-manifest.json（整文件可能包含已有WIP，不是发布范围）。

## 本地运行

- 前端预览 http://127.0.0.1:18394/，实际Java http://127.0.0.1:18091；development_headers 身份owner，组织org-main。
- 数据为 `/private/tmp/knowledge-0094-browser-data` 中本轮新合成资料；模型/Milvus均为本机协议服务，不能把此预览当真实供应商效果。
- Java由隔离源 `/private/tmp/knowledge-0094-maintain-src` 的已编译主类/测试辅助启动；生产JAR不含测试辅助。显式测试辅助拒绝已有数据目录，别用它覆盖当前数据。
- 当前交互进程接收 `restart` 可重开同一数据，`stop` 结束；不要误杀其他本机服务。临时运行文件不提交Git。完整验证日志在工作区 `.local/knowledge-construction-0094/`。

## 历史发布前注意事项

生产应用仍是0085/schema42，本轮schema43仅新增批次归档sidecar。生产变更需要先对真实副本迁移和兼容回退验证，保存当前业务数据；禁止用旧库覆盖后续写入。当前构建使用保留历史图片/OCR WIP的隔离工作树，不是可直接上线的干净发布包。

前端必须同时包含wiki-construction.mjs及精确代理新路由。capability为wiki_maintenance、batch_sources、batch_organization；生成修订提案使用编译请求期限，普通维护查询仍为读取期限。

真实模型语义质量、生产容量和旧全仓架构/覆盖率问题仍开放。不自动续用任何历史模型余额，不重试生产旧失败任务，不改变0093 Milvus补丁/guard身份或当前生产索引。原生图片/音视频下一阶段再做。
