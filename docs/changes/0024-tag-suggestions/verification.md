# 0024 后端验证记录

2026-10-03 02:46:23 +08:00，本机后端完整 `clean verify` 成功：1812项Java测试，0失败/错误/跳过；624个Java文件通过Spotless，JaCoCo既有LINE/BRANCH双80%门禁通过。本轮只从已保存且当前有效的available摘要派生元数据候选，用户明确确认后合并现有标签；没有新增摘要或标签模型协议、模型调用、数据库迁移或部署。

## RED与夹具修正

root先在未实现的新路由上运行 `TagSuggestionsMainlineHttpTest`，2026-10-03 02:35:23 +08:00得到1项测试、1失败、0错误/跳过。当前有效摘要的 `GET /v1/documents/{id}/tag-suggestions` 应返回200，实际404 `not_found`，这是本切有效业务RED。原日志为工作区 `.local/tag-suggestions-mainline-red.log`，保留不改写。

实现后的首轮169项定向运行于02:41:15结束：0断言失败、6错误、0跳过。六个错误均来自新 `TagSuggestionServiceTest` 的资料列表夹具把 `DocumentQuery.q` 传为null，被既有 `ManagementService.validateQuery` 拒绝；同轮主线HTTP、候选规则及架构测试已通过。pdf_ingestion仅把夹具查询改为既有合同要求的空字符串，没有修改产品输入规则、放宽断言或移除用例。此项是夹具错误修正，不计作产品RED或修复关闭的产品缺陷；原 `.local/tag-suggestions-backend-targeted-initial.log` 保留。

## 定向GREEN与完整HTTP

pdf_ingestion独占串行Maven窗口，在 `github/ai-knowledge` 目录逐条加载工作区工具环境后执行：

```sh
source ../../.tools/env.sh
mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2 spotless:apply
mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2 '-Dtest=Tag*Test,Synopsis*Test,Management*Test,Runtime*Test,DocumentOriginalHttpTest,ArchitectureRulesTest' test spotless:check
```

格式运行02:40:44成功；修正夹具后的定向运行02:42:02成功：169项测试、0失败/错误/跳过，624文件格式检查通过。工作区日志分别为 `.local/tag-suggestions-backend-format.log` 和 `.local/tag-suggestions-backend-targeted.log`。定向包含候选Domain/Tool/DTO、Service真实SQLite、Controller/mappers、启用配置/Runtime、既有摘要/整理/原文件回读和11项架构测试。

root编写的 `TagSuggestionsMainlineHttpTest` 在真实Spring入口和测试临时SQLite中完成：保存合法合成摘要→读取建议且不增加审计→确认期间普通整理新增标签→选择服务器候选序号并合并→按新增标签筛选→应用重启→回读同指纹及完整标签。原始source SHA、active revision和publication保持；已有手工标签与确认期间新增标签均保留。模型入口仅为loopback计数夹具，读取、确认和重启之后均断言调用计数为0；摘要通过既有服务保存合成结果，未调用真实模型。

本切新增34项Java测试：候选Compiler9、Domain3、DTO3、Service8、配置1、请求mapper4、响应mapper2、Controller2、Runtime1、主线HTTP1。覆盖完整条目TERM→TOPIC排序、大小写精确去重/最多8、Unicode40/41CP、分隔符和控制符排除、全部长条目空候选、完整身份和候选顺序指纹、不可变集合/脱敏、reader只读、当前失权/摘要及模型变化、过期指纹、非法序号、并发标签保留、重复确认幂等、20条超限与审计失败原子回滚。

## 最终完整门禁

root在同一后端目录加载 `../../.tools/env.sh` 后执行：

```sh
mvn -B -ntp -o -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2 clean verify
```

工作区归档日志 `.tools/tag-suggestions-verification/backend-full.log` 记录02:46:23 +08:00 `BUILD SUCCESS`、1812项测试全部通过、624文件Spotless通过和JaCoCo门禁成功。`target/site/jacoco/jacoco.xml` 的BUNDLE计数读取如下：

| 门禁 | Covered | Missed | 精确比率 |
| --- | ---: | ---: | --- |
| LINE | 17041 | 1177 | 17041 / 18218 = 93.5393566802% |
| BRANCH | 9239 | 2272 | 9239 / 11511 = 80.2623577448% |

没有修改pom、排除项或门禁阈值。root已核对640个后端与43个前端构建输入；其归档脚本首次把Controller字节码包路径误写为web，修正为实际controller后继续采集，这只是证据脚本路径修正，产品源码/测试没有因此变化，不计作产品失败。最终交接/制品绑定由root另行记录，不以本文替代归档。

## 限定交叉复核与边界

backend_originals静态复核 `TagSuggestionService` 与两个既有Service的包内helper，并追读Management/Synopsis Repository、权限policy及authority事务实现，未发现阻断项：GET与apply均只有一个外层事务；当前workspace/ACL/tombstone及完整publication/原材料input fingerprint、模型和摘要policy复验保留；apply先要求当前owner/editor，再重算服务器候选并匹配指纹；候选身份不含可变现有标签。确认合并重新读取此刻标签，超限检查在写入前，元数据/标签/hash-only审计同事务回滚，SQL只更新既有元数据列及标签，不改变原文件/source revision/publication/索引。此复核为静态范围结论，执行证据来自上述测试，不扩展为通用安全审计。

实现由backend_originals负责纯Domain/Tool/DTO与规则测试，pdf_ingestion负责Service/helpers/真实SQLite测试，pdf_config负责Controller/VO/mappers/配置Runtime及输入测试；root负责跨HTTP正常链、前端/代理及最终全量。本文整理阶段只读日志/覆盖率和更新三个0024文档，没有再运行测试或修改产品/测试。

当前尚未部署；浏览器页面由用户验收，未在本轮代做真实浏览器验收。合成摘要/DOM/loopback证明不认证真实模型分类质量或生产效果；没有云调用或恢复已取消的usage/计费工作。前端与代理完整验证及最终源码制品交接以root的对应记录为准。
