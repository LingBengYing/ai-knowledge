# 本机验证记录

2026-10-11；承接0070未提交源码。后端HEAD为fb0171de33e6d683fa117c13904d83aae5cf0aab，前端HEAD为5c5fd79b376c45395918a3e20bd8d8ac2f60bf2b。以下验证包含当前工作树，不拿历史数字认证新变更。

## 首次协议与服务验证

- Java相关74/74通过，0失败/错误/跳过。报告`/private/tmp/native-agent-java.WKWq3m/surefire-reports`，结束09:35:34 +08。覆盖原生协议、同任务reasoning原文回传、只截展示副本、无字段、旧事件JSON、取消晚到、事件metadata及持久会话。首轮新增测试夹具在无reasoning分支误用有值断言，修正夹具后完整重跑通过，没有放宽生产校验。
- CPython3.10.17干净环境117/117通过。初次沙箱内110通过、7个真实loopback因socket.bind权限失败；获准本机监听后原测试完整重跑117通过。新增非法reasoning类型/NUL/超长拒绝、同循环精确保留及旧对话禁止注入；保留一条第三方Starlette弃用warning。
- JDK实际为22.0.2、编译release21；Maven3.9.9 offline。限定Java格式及两仓diff检查通过。
- 前端最终513/513完整串行、JS语法通过。修复了同一响应选择重复工具名的契约兼容，以及非PDF文档不冒称物理页；最终实际页面TXT显示“文本分段1”。Web0052保存浏览器证据。
- 独立只读复核未发现原生reasoning往返、压缩背景隔离、来源metadata权威或取消终态覆盖上的阻塞。超出既有协议/上下文界限仍显式失败；本切没有新增模型生成token上限。

## 完整合成链与重启

全新快照`/private/tmp/native-agent-integration.0YkApK/`，前端18241、Java18242、Python18243，独立新SQLite及本机模型/向量接口。启动前新编译的WikiWorkflowHttpTest 1/1通过。

真实HTTP上传两份合成TXT→自动解析/索引→两轮原生Agent检索/阅读/回答→上下文压缩→草稿/来源SHA回读/对话CRUD/取消均通过。`verification.json`记录两份资料、2条引用、两轮原始问答和全部公开事件。每轮5次模型动作均携带fixture明确返回的短状态文本，工具事件有实际query、source/document/revision、位置、数量、耗时，sequence唯一。

fixture额外断言tool_choice=auto且无forced thinking；每次续工具请求必须精确保留该assistant消息的reasoning_content，压缩请求不能含reasoning标记或字段。

同库Spring重启后运行只读验收，原始事件/思考、两轮回答、分页、压缩检查点、草稿、引用与原件SHA全部保持。重启前后本机计数保持Agent10、search4、read4、compact1，未因回看再次发起模型请求。实际浏览器能展开持久记录的“模型思考”和工具query/结果。

## 发布前本机复验（10:38 +08）

负责人随后明确要求发布生产、真实模型验收及推送Git，并批准限定发布例外：本次没有新增失败、相关回归通过、生产数据库副本迁移通过后，可以发布，不要求先清完全仓旧失败。该授权不把旧失败改判为通过，也不允许忽略本次新增失败。以下只记录本机检查；生产副本迁移仍是独立的发布前置条件。

### 正式门禁及干净基线对照

- 在新隔离目录执行未删减测试配置的离线 Maven `verify`。已完成82个测试类、434项：403通过、31失败、0错误、0跳过。确认失败后安全终止，状态为 **PARTIAL / FAILED**，退出143；剩余测试及正式verify中的覆盖率门禁未执行完，不计通过。
- 31项失败中，23项历史迁移夹具在`HistoricalSchemaV25Fixture`因`import_index_requests`仍有权威记录而拒绝回退，6项为既有资料清理/媒体权限断言，另2项为Wiki历史迁移测试未适配v36新增对象。
- 从干净提交`fb0171de33e6d683fa117c13904d83aae5cf0aab`重新归档源码，以相同JDK/Maven和不变断言定向运行全部22个已失败类，另加`ArchitectureRulesTest`：23类154项，124通过、30失败、0错误、0跳过。上述29项历史失败的测试身份及完整失败消息SHA均与当前树一致；两项Wiki迁移在该基线通过。
- 当前树另跑`ArchitectureRulesTest`：11项中10通过、1失败，仍为`IndexingRepository`依赖`client.vector`的既有分层违规；失败消息SHA与干净基线完全相同，没有放宽架构规则。
- 两项新Wiki测试适配已修复：旧schema、原行及备份断言全部保留，旧对象比较仅精确排除新增`knowledge_conversations`、`knowledge_conversation_turns`、`knowledge_conversations_owner`；新增对象名称/类型、清理schema登记及初始空表单独严格断言。没有删除测试、跳过用例或修改生产代码来规避失败。

初始部分门禁证据保留在`/private/tmp/ai-knowledge-release-0071.nMuDPr/verification-partial.json`及`verify.log`；干净基线报告为同目录`baseline-results.json`、`baseline-comparison.json`，架构对照为`architecture-comparison.json`。干净快照与原始报告位于`/private/tmp/ai-knowledge-baseline-0071.FfwwG4/`。

### 最终相关回归与候选制品

- 新隔离构建`/private/tmp/ai-knowledge-candidate-0071.h9DKFZ/`执行9个直接相关测试类：`AgentProtocolClientTest`、`OpenAiCompatibleModelsTest`、`KnowledgeAgentControllerTest`、`KnowledgeAgentServiceTest`、`ConversationServiceTest`、`ConversationControllerTest`、`ConversationMigrationTest`及上述两个Wiki迁移类。最终 **77/77通过，0失败/错误/跳过**，随后`package`成功，结束于2026-10-11 10:38:35 +08。新增的两项迁移适配失败已消除；这不是全仓测试通过的结论。
- 单独不带文件范围限制运行`spotless:check`，1215个Java文件全部clean，结束10:38:24 +08。初次限定格式化命令误用glob，被工具在测试开始前拒绝；修正为正则后两文件保持clean，首条失败日志保留，不计为代码测试失败。
- 实际JDK22.0.2、Maven3.9.9 offline、release21；抽查候选生产class的major version为65。候选JAR为`/private/tmp/ai-knowledge-candidate-0071.h9DKFZ/rag-java-0.1.0-SNAPSHOT.jar`，SHA256：`374005f6c8ab69b43c3e1bac08a6d1f91ee7242a0667fcf4842f61b65ecafe84`。
- 77项报告为候选目录下`verification.json`、`surefire-reports/`和`candidate-final.log`；全量格式结果为前述release目录下`spotless-check.log`。相关回归内没有剩余新增失败，已观察的旧失败均完成基线对照；未运行的全仓余量不能据此宣称没有新失败。

## 边界

本机模型思考是明确的合成fixture输出，绝不宣称来自DeepSeek真实返回。本机检查过程真实供应商请求0，未由该检查修改生产服务、模型设置、资料、索引或执行Git写入。当前不流式输出token，模型每轮响应完成后随既有轮询显示。正式全仓门禁未通过，覆盖率门禁未完成；生产数据库副本迁移、Linux实际运行、生产回滚及真实模型质量需要另外记录，不由本机打包成功认证。

0071本身不再升级schema，但含新增字段的持久result_json不能据此推定旧JAR读取兼容。未来发布须配套Java/Python/前端，并遵循0070的schema36迁移与备份规则，不用旧库覆盖新数据。
