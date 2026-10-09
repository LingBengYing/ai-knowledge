# 0063 生产发布

状态：DEPLOYED_SCOPED_VERIFIED。2026-10-09 22:37:27 +08切换成功，未触发回滚。

负责人授权：“补齐吧，然后发线上”。只发布功能，不执行旧资料删除、不调用模型、不推送Git。

## 固定版本

- 目标：私有生产主机，`/srv/ai-knowledge/releases/20261009-cleanup-wiki-purge-0063`。完整主机标识仅保留于私有发布记录。
- 前基线：后端和定制免登录入口0062b；智能体服务0062保持原目录与配置。
- 新JAR SHA256：`92b6b5b5d065d098c1ae3a106cebb7a7387bb38f501518e0781fd55225349a04`。
- 前端包：`e7a90546fc150986ea0347afaf8487360f08fbb0a758d8d210b1d74dad363f50`。
- 兼容回滚JAR：`70427543d527e378b705c606c7cea2e2d255d7c0aae0d52ebd4cfeb3d9ae55d9`，旧业务代码加精确v35 Store/schema支持，不覆盖旧数据库。
- 仅新增配置开关：`rag.document-removal.enabled=true`、`rag.document-cleanup.enabled=true`；模型、鉴权和密钥配置从旧版保留，不输出值。

## 发布流程

1. 验证线上旧包SHA、服务工作目录和本次制品SHA；先确认无执行中任务、无待续跑清理任务。
2. 生产SQLite一致性副本和原件副本上，用新包迁移并重开，再用兼容旧业务包重开；比较全部旧表行哈希和文件哈希。
3. 兼容旧包在副本启动完整Spring，禁用全部worker/model功能，只GET检查配置、资料、Wiki和同版本原件SHA。
4. 关闭入口、排空请求、停止后端后，复制并核验数据/配置/unit备份；切换后验证新能力、页面、服务和业务行保持。
5. 失败回滚为兼容v35旧业务包+旧前端，不用旧数据库覆盖用户的新状态。

## 验收边界

副本迁移、兼容重开及完整兼容Spring HTTP通过：原96张表的旧业务行、9个原文件均保留；v35新增purge表为空。停机备份与正式升级重复核对一致。服务端主入口、资料管理、Wiki active/deleted、配置及静态资源均200，静态SHA匹配；免登录共享会话仍有效；document_cleanup能力及清理记录200；旧模型配置/原目录未更改。

公网真实浏览器只读核验：`#/knowledge-deleted`显示2篇旧已删除页，每篇都有“恢复知识页/彻底删除”；`#/documents`显示5份旧资料，每行均有“删除并清理”。未点击生产删除确认。新增生产数据删除0、模型请求0。

详细摘要：deployment-{staged,migration,compatibility-http,result,smoke}.json。备份位于新release下rollback，兼容包位于compatibility。生产发布与Git推送分开，本轮未提交或推送。

只读核验清理能力、清理记录和已删除知识页；生产不执行永久删除。删除行为由本地新合成资料真实HTTP测试验证。继承的ready503及旧角色ACL测试失败不冒充绿色；不称全部生产门禁通过。
