# 0057 验证记录

2026-10-09。本切状态：**LOCAL_INTEGRATION_VERIFIED，RELEASE_NOT_VERIFIED**。

本机问答已运行真实 DB-GPT 0.8.2 上游循环；Java 继续负责资料、检索、模型配置、正式引用与 Wiki。只安装组件依赖与应用适配器，没有部署 DB-GPT 整个平台，没有另写一套模仿执行器。数据库相关 Python 包是上游导入依赖，未注册 SQL 能力。详见 `agent-service/UPSTREAM.md`。

## 本次证据

| 层次 | 结果 | 能证明的范围 |
| --- | --- | --- |
| Python | 14/14通过，锁定环境79包兼容检查通过 | 实际上游Agent循环、固定工具、来源身份、无重试、8步、取消/超时、禁快照、真实loopback TCP |
| Java | 78/78相关回归通过，无跳过 | 新增12项、旧66项，包括来源/建议、发起人隔离、取消迟到、配置变化、预算、旧普通问答及架构 |
| Java格式/打包 | 全1191文件格式通过；独立JAR打包成功 | 格式与可构建性；打包显式跳测，质量证据来自前述78项 |
| 新启动脚本 | build-only成功，旧WikiWorkflowHttpTest 1/1通过 | 本次新构建快照与现有Wiki流程仍可运行 |
| 前端 | 56/56直接相关、语法检查通过；最后过程折叠修改后完整相关UI/API/session 43/43通过 | API/状态/代理与页面行为；完整前端回归限制见下文 |
| Java+Python真实HTTP | 通过 | 新TXT解析索引、两轮search/read、服务器引用、草稿、取消、不自动写知识页 |
| 实际浏览器 | 通过主流程 | 发起任务、真实阶段显示、答案/引用、展开维护建议、来源原文、存为草稿、刷新回读 |
| Java重启只读回读 | 通过 | 两个正式来源、原文件SHA和草稿；Agent模型计数10→10、search4→4、read4→4 |

Python具体红绿证据见 `agent-service/VERIFICATION.md`。Java相关回归为新增AgentService8、AgentProtocolClient2、Controller2及旧KnowledgeAnswer8、OpenAI23、ManagedRuntime18、LibraryGate6、Architecture11。本次没有重跑完整发布verify/覆盖率门禁，不沿用历史覆盖率数字。

## 整链验收

运行目录：`<PRIVATE_TMP>/dbgpt-0057-integration.jBigXu`（个人临时目录前缀已脱敏）。页面 `http://127.0.0.1:18110/#/ask`，Java18111，Python18112。仅该目录的新数据和本机合成模型/向量替身；实际DB-GPT、Java/Spring/SQLite、文本解析索引、引用回读和前端均真实执行。

`scripts/verify-agent-integration.mjs` 首先确认空库，再上传两份新TXT：灯塔项目说明与灯塔使用指南。实际解析、索引后，同一个request_id二次提交返回同一个任务。Python原生Agent执行5次规划模型步骤，search→read→search→read→terminate；Java返回2条正式引用和1条建议，两个引用都回读原始内容并验证SHA。保存草稿后再次读取；知识页仍0条，证明建议没有自动写Wiki。

取消另一任务后，等待迟到窗口，状态仍cancelled且result为空。该取消由真实HTTP验证。实际浏览器另一次点击停止时，合成快速任务已先完成；页面正确显示completed而非伪称cancelled，这次浏览器操作不计作运行中取消成功证据。

浏览器在真实页面完成查询、展开建议、点击引用阅读原文、保存新草稿；Java重启后刷新该草稿仍保留正文与引用记录。最后过程列表改为默认折叠，页面重新加载后再次执行成功并留作可查看结果。截图见 `browser-answer.jpg`；完整HTTP合成验收结果见 `acceptance.json`。

重启只读验收返回 `STORED_SOURCES_AND_DRAFT_VERIFIED`，引用数2。重启前后的Agent模型计数均为10，工具计数均search4/read4。之后再次页面查询属于额外本机合成调用，未计入上述零新增回读窗口。

## 回归问题与独立审查

- 前端完整默认并发586项：579通过、7失败；完整串行同586项：585通过、1失败。旧HTTP代理的毫秒期限及音频异步断言对当前调度敏感；最后失败的external-server完整文件独立复验34/34通过。原断言、期限未改变，也无删除/跳过。**不存在一次完整全绿的前端回归证据**。最后低影响过程折叠变更仅重跑完整相关43项，未冒称全586重新认证。详见前端0043 verification。
- 独立只读审查发现Python/Java的单次read与建议document_ids上限64/32不一致；Python已统一32，补33拒绝用例并通过最终14项。来源总池64支持分批读取。其余已审正常引用/取消/私有回调链未发现额外可定位阻断。
- 首轮root构建遇到并行编辑造成main旧签名/test新签名不一致；冻结后重新完整构建通过，不认定产品行为失败。初次Spotless筛选表达式错误已改正，最终检查覆盖1191文件。未代用户接受Xcode许可。

## 未验证范围与后续事项

- 全部模型响应/检索向量服务为明确本机替身，**本轮新增云请求0**。预设合成答案只证明流程，不证明真实Agent规划、资料相关性、事实蕴含、写作效果或提示注入抵抗质量。未复用0056或旧批次模型额度。
- 未部署、推送、改旧资料、停旧服务。默认Agent关闭。生产接入/真实质量验收需后续独立执行，不能将本机页称为线上验收。
- 任务运行状态在Java进程内，重启不能恢复进行中任务；正式答案trace和草稿持久化。页面创建响应丢失且尚无run.id时，没有按request_id只读恢复入口；保持未知且不自动重发。
- 建议只展示，不自动采纳/编译/修改Wiki；当前没有联网、SQL、任意代码、纯视觉自主检索、多Agent或长期任务能力。
- Java服务关闭与worker启动交错时，timer.schedule在try/finally外可能使拒绝调度跳过reservation释放：这是审查中的源码推断，未复现，按主线优先记为后续收敛项。已验常规重启不等于证明所有关闭交错。

## 可复核位置

- Java回归：`/private/tmp/knowledge-agent-java-0057-regression.log`及同目录`surefire-reports/TEST-*.xml`。
- Java格式/打包：`/private/tmp/knowledge-agent-java-0057-package.log`。
- 独立JAR：`/private/tmp/knowledge-agent-java-0057/rag-java-0.1.0-SNAPSHOT.jar`；SHA256 `f54ca01b5188a85754df24b68f5e52ca2801d73659e165447232bf77998736f1`。
- 新启动构建：`/private/tmp/dbgpt-0057-build-only-final.log`。
- HTTP与重启：`/private/tmp/dbgpt-0057-http-acceptance.log`、`/private/tmp/dbgpt-0057-restart-acceptance.log`。
- 前端：`/private/tmp/knowledge-agent-frontend-{full,serial,external-recheck,final-ui}.log`。
- 复现步骤：同目录 `local-runtime.md`。输入摘要仅绑定本切相关源码，见 `inputs.sha256`，不以它认证其他脏树改动或旧测试结果。
