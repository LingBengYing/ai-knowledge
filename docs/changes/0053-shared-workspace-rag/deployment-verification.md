# 0053 可回滚体验版发布

PREVIEW DEPLOYED：2026-10-08 23:54:52 +08:00。不是完整生产质量验收。

负责人具名批准“先部署可回滚体验版”，接受全量发布回归、覆盖率及真实模型质量未完成；随后明确“免登陆保留啊”，最终保持既有免登录入口/共享组织身份与开关true，不强制登录。

## 制品及验证

- 地址：`https://<DEPLOYMENT_HOST>:18443/`（主机已脱敏）；目录：`/srv/ai-knowledge/releases/20261008-shared-workspace-0053`。
- 后端JAR SHA：`b637ed11fb83f083aa1773dd9023eca50aefe342f5e179a1cb47370a7214e902`。
- 前端open-access包SHA：`2b8173655a9e0bcaf64b4952c1f1851101d440a5b28e869428908a406d8fb597`。
- 后端base `fabc5a64b1e87a3ba54fdbd5f47529bba93849e9`、前端base `2ee35f5a8ae2e4a5ca8fc10445b624b08bfc965a`加本地未提交修改，不把base冒称最终源码。
- 最终直接相关23个Java完整文件188/188、Spotless/package通过；前端492项本地回归通过，详见verification。
- 最终JAR对生产数据完整私密副本实际执行v29→v30；表数量、非维护业务行SHA及原件/配置SHA保持；v29一致副本恢复读取验证通过。没有启动Spring/Worker/模型客户端。
- 停止entry/backend且PID0后备份完整data、config、secrets、两份systemd unit；只替换unit版本路径。features.env及application-release.properties逐字节保持。新后端实际schema30，仍10份资料、模型配置v6，两项服务active，开放入口前业务行/原件摘要一致。
- 首页/config/session/资料列表及4个主JS共8个只读路径通过；188个PDF资产HTTP200且逐项长度/SHA通过，正式Host外部TLS入口通过。
- 浏览器免登录打开公网新版问答页（组织共享全库）及10份资料列表，未提交问题、模型测试或资料写操作。保留页面现有“开发环境 · 非生产”标识，没有伪标正式生产。
- 发布脚本模型请求0；随后服务另有4条model_http_started，无ERROR行，未归因且不能冒称本任务质量验收。用户随后反馈“灯塔”回答包含不相关启动码与重复内容，已转入只读诊断，不能把部署成功当问答质量已通过。

服务器版本目录有bundle.json/STAGED.json/DEPLOYED.json/smoke.json，本地记录位于工作区`.local/release-0053-20261008/`。无Git提交或推送，无重新解析/索引或改写旧资料，不续用0052旧14/20 halted预算。

## 回滚及边界

旧后端保留于`/srv/ai-knowledge/releases/20261008-keyword-hotfix-0051`，旧前端保留于`/srv/ai-knowledge/releases/20261004-unified-knowledge/frontend`。新release的`rollback/data`为停机一致快照，配置/秘密及两unit同在0700的rollback目录；迁移副本在0700的private目录。

v30回滚必须停服务、同时恢复v29数据和旧unit/包，不能只换JAR。发布脚本失败回滚会先校验业务行/原件，若出现新用户写入则停止覆盖并保留现场。本次未触发回滚。入口开放后已有用户提问，后续不得直接用旧快照覆盖新trace/资料，必须先核对并制定保留新增数据方案。

完整发布回归/覆盖率、真实模型问答质量、混合文档视频正确引用播放、负载与长期稳定性仍未完成。PDF资产可访问不等于某条回答的正确页码已验收。

一次发布后journalctl查询的ISO时间格式不被工具接受，改用本地时间后成功；这是诊断命令问题，不是服务故障。
