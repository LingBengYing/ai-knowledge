# 0053 本地验证

日期：2026-10-08，Asia/Shanghai。全部为临时SQLite、本机模型/Milvus协议替身；新增真实模型HTTP 0，未访问生产、未推送部署或修改旧资料。隔离构建输出在`/private/tmp`，没有覆盖正在运行的jar。

## Red

1. `SharedWorkspaceAccessTest`初始3例：1失败（同组织第二成员scope=0）、1错误（跨成员任务404）；模型配置策略例仅改后绿。日志`/private/tmp/shared-workspace-0053-red.log`。
2. `KnowledgeTopicAnswerServiceTest#ordinaryQuestionUsesFullWorkspaceAndOnlyOneSynthesis`在旧实现实际因`empty_scope`失败。日志`/private/tmp/shared-workspace-0053-service-red.log`。
3. Adapter新普通综合、完整长嵌入/重排输入场景旧实现失败；第一次loopback绑定被沙箱阻断不算产品红，获准本机socket后实际红。日志`/private/tmp/ordinary-rag-model-0053-red.log`。
4. schema30首次5例红：当前版本仍29、sound/video第二成员封存被旧ACL触发器拒绝。日志`/private/tmp/shared-workspace-schema-0053-red.log`。

## 阶段绿结果（不等于最终发布验收）

- Adapter两个完整文件32/32通过，日志`/private/tmp/ordinary-rag-model-0053-green.log`。
- schema迁移6 + SoundAnswerService12 + VideoAvAnswerService23 = 41/41通过，日志`/private/tmp/shared-workspace-schema-0053-green.log`。含129份真实合成资料上传/解析/索引、33条实际引用封存、v29备份升级与重启、旧记录不可变、错组织/删除拒绝。
- 前端完整492/492、语法和差异检查通过；见独立前端`docs/changes/0039-shared-workspace/verification.md`。新4条合同红→绿，前端测试中两次计时夹具并发问题通过串行原断言重跑，未弱化断言。
- 首批253例只有2处媒体trace错误，确认其编译早于schema30接线；含新迁移的完整文件已修复通过。普通问答/真实引用、权限与Milvus分批在该批直接验证通过，但不把该批算完整绿。

最终集成、格式、构建结果待本文件追加。真实云质量、浏览器混合来源、生产发布、完整release覆盖率门槛未以这些局部本机结果代替。

## 发布前必要回归最终结果

2026-10-08 23:50:52，最终23个完整相关测试文件188/188通过（0失败、0错误、0跳过），Spotless通过并成功打包；隔离输出目录`/private/tmp/shared-workspace-0053`，日志`/private/tmp/shared-workspace-0053-final3.log`。覆盖组织共享、JWT/会话/未登录/跨组织、普通综合与原来源、配置切换、文字/图片/视频HTTP、真实SQLite和分层规则。模型与向量为本机协议替身，未调用真实服务商。

此前final轮186项有13个旧权限/选择合同失败，final2轮187项有2个测试合同误判，不以这些失败轮宣称通过：旧`/v1/answers`仍有PreparedQuery字节边界，长中文样本保留为legacy明确负例；默认`/v1/knowledge-answers`已有27k字符→一次综合→原来源完整正例。新全库trace包含所有publication，撤下视频使旧图片答案的完整scope失效，测试明确断言旧答案404并验证未删图片的原件/version/SHA/逐字节读取仍200、零模型。没有删失败用例或减小输入。

最终JAR包含当前dirty工作树0050–0053及相关本地修改，不假称仅来自Git HEAD；未提交/推送。前端492项本地回归已通过；部署包另外保留负责人最新要求的线上免登录定制，线上仍需实际烟测。完整全量发布回归、双80%覆盖率和真实模型质量不在该必要回归中，负责人已明确批准可回滚体验版例外。
