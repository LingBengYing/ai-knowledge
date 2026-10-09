# 0051 验证与未发布边界

2026-10-08，Asia/Shanghai。此记录是关键词热修的本机证据，不是线上问答验收。

最新补充：负责人明确接受已知门禁问题后，本后端热修已于21:34:04例外发布。服务/前端入口及配置资料保持检查通过；唯一Project实际4/6后model_failure停止，真实关键词回答/来源仍未验收。详见[deployment-verification.md](deployment-verification.md)。下方21:16未发布与门禁失败记录完整保留，不因例外上线改写成门禁通过。

## 21:16 当前结论：修复已合回，发布门禁仍未通过

负责人已批准收敛发布阻塞，下方“等待确认”是当时的历史状态，不再作为授权障碍。必要分层接线、历史迁移真实schema夹具、版本化旧合同夹具、向量authority失败分类与PDF测试构建目录均已完成，并合回主树；没有夹带主树0050到隔离发布源。

- 第一次完整发布运行在21:04:30结束：3285项、17失败、0错误/跳过，涉及9个完整测试文件。原日志`/private/tmp/keyword-hotfix-0051-release-verify.log`；第一次全部报告已封存到工作区私有`first-full-reports.tar.gz`，未覆盖失败证据。
- 最终修复后，同一build目录执行全部9个失败文件以及EvidenceService受影响行为、完整关键词与协议文件，共18文件243项。原17项失败全部恢复；另一个原有PDF生命周期测试因1500毫秒总期限内OCR夹具未登记PID而失败（242通过、1失败）。`parser_timeout`与总耗时断言已通过，不能将此归为已证实的子进程泄漏。该次全部报告另存`recovery-reports-211421.tar.gz`。
- 其他构建结束后，以完全相同源码单独执行完整`ProcessPdfOcrTest`，21:16:23全部5项通过；没有放宽期限或缺PID断言。首次启动时序失败仍作为测试稳定性风险保留，不能写成一次完整全绿。
- 主树末尾合并验证：此前563项直接相关通过；21:15:17再执行向量/维护/PDF最终5整文件35项，0失败/错误/跳过。隔离源与主树剩余差异仅为此前0049/0050主动保留的问答协议代码，不覆盖、不部署这些主树改动。
- 隔离源全1102 Java文件Spotless检查通过。21:15:59原样JaCoCo门禁实际失败：LINE约88.46%，BRANCH约75.64%，仍低于原定80%；未排除类或改变门槛。最终执行数据按新class身份统计；修改后的EvidenceService不能继承旧字节码的执行数据。相关日志`/private/tmp/keyword-hotfix-0051-final-static-coverage.log`。

现有报告的覆盖率缺口主要跨历史业务模块；新TopicEvidence已约94.64%分支覆盖，仅重复关键词测试不能补齐。剩余是历史正常业务模块的验证补齐及该PDF期限用例的稳定性问题，不是热修小尾项。不能以局部通过或聚合报告代替完整发布通过。

本轮没有制作可交付发布清单、上传包、部署或重启。新Project批次仍0/6、halted；未重发灯塔。用户新截图的上游失败另见[只读诊断](upstream-diagnostic.md)，它不是关键词原文校验问题，也没有因本机热修改动而解决。

相关细节：[历史迁移](migration-verification.md)、[视频版本合同](video-domain-verification.md)、[PDF测量路径](pdf-measurement-verification.md)。

## 原问题与红→绿

实际线上 `4bf8dbd8-5fe1-4b86-9710-57d6cfef792f` 的问题SHA与 `Project` 相同、scope9、旧v9、`incomplete_evidence`。原次模型摘录未留存；本机用截图整页原文及标题精确摘录稳定复现，不能声称恢复原次所有候选。

隔离fabc5a6源新增真实SQLite的Service测试：旧实现3条中2条失败，Project有原文仍拒答，预算正控通过；记录 `/private/tmp/keyword-hotfix-0051-service-red/surefire-reports`。测试编译前仅按实际生产签名适配已有两份配置夹具的ObjectProvider/ManagedMediaTextFactory装配，保留全部行为断言；编译错误不算产品RED。

新增TopicEvidence独立模块78条初测29失败，原断言实现后全绿，再补两项边界共80/80。公共Service随后确认Project综合回答、独立核验必经、原文页/版本引用及重启零模型回读通过；核验false不释放答案，预算仍使用原字段证明。

## 最后一次相关验证

- 隔离发布源：17:58:55完整直接相关518项通过，0失败/错误/跳过；包括TopicEvidence、KnowledgeTopicAnswerService、OpenAI原/主题协议、全部TextGrounding行为文件、QuestionPlanning和两份必要配置测试。日志 `/private/tmp/keyword-hotfix-0051-direct.log`，报告目录 `/private/tmp/keyword-hotfix-0051-direct/surefire-reports`。
- 当前主树：17:57:36合回主题增量后的132项通过，保留0050视频协议与原知识问答Service测试；日志 `/private/tmp/keyword-hotfix-0051-mainline.log`。这不意味着主树0050已发布。
- 模型协议新增完整12条通过：全部context_only、未引用限制/反证、独立核验false、未知引用拒绝、默认不静默丢上下文、旧重载兼容；固定旧extract PROMPT SHA及model revision回归保持。
- 修改文件均按固定Spotless格式器处理。首次相对文件列表未命中文件，已改用绝对路径匹配正则并实际处理；不把空匹配视作格式通过。
- 本次没有外部模型请求、没有旧资料变更、没有Git提交/推送、没有上传发布包或重启线上服务。

## 发布检查未通过（不删除、不跳过、不放宽）

1. `ArchitectureRulesTest.productionBytecodeObeysApprovedLayers`：DocumentReplacementController直接依赖配置类2条；ModelRebuildMutationFilter直接依赖Repository/Store6条。原605基线classes运行同一架构测试也出现全部相同8条；两个生产类字节与旧发布一致，0051没有新增违规。不能写成整体分层检查通过。
2. 完整回归出现历史迁移夹具未跟上schema29：ReindexSqlFixture期待25；VideoAvMigrationTest.restoreVersionNineteen仅处理24/25及22/23后期待21。原基线与隔离源repository和资源字节一致；代表用例在历史fixture准备时失败，尚未进入迁移/资料保持断言。不能只改预期版本数字让测试通过，必须真正构造对应历史schema。
3. 全量回归另有尚未逐项诊断的历史测试失败，不能把两类代表诊断扩为所有失败根因。为避免未经确认扩大热修范围，已在发现上述确定失败后停止完整回归，保留 `/private/tmp/keyword-hotfix-0051-full.log` 及已有报告；没有全量通过结论。已确认本轮Maven及其测试子进程退出。

## 发布与模型授权

负责人“改一下线上的吧”授权本次关键词后端修复发布。当前尚未执行发布：需要确认是否一并收敛发现的既有发布门禁，相关范围问题已发出，尚无答复。准备好的后端专用发布脚本会保留配置、前端和旧包、备份SQLite；代码回滚绝不恢复旧数据库覆盖新写入。该脚本仅语法和限定只读审查，未实际发布/演练。

负责人另明确批准独立 `keyword-0051-production-project-six-requests-20261008`：一次Project、硅基流动最多3次、DeepSeek最多3次，共6次、无重试、失败即停、无需重解析。**实际0/6，尚未开启**，私有台账 `.local/hotfix-0051-20261008/provider-run.json` 为 awaiting_release_gate/halted。0050旧20/27 halted保持，两批额度不混用。

## 已核对的线上基线

- 后端路径 `/srv/ai-knowledge/releases/20261004-unified-knowledge`，原JAR SHA `9b08f855fca1fa9acbd07b7c4265c720b98f466c7c63bd61d5e5fc0fcde3179c`。
- 后端与entry均active，live为ok/java；schema29、10份资料。早先记录模型active_version2；21:09再核对已为5，部署必须保留最新配置而非恢复旧预检值，详见上游诊断。
- 旧 `/health/ready` 固定503 migration_incomplete是原产品状态，不修改为200冒充完整生产可用。

完整目标仍未完成；本轮仅已修复本机关键词拒答，线上实测/发布和原TODO2视频来源主线继续开放。
