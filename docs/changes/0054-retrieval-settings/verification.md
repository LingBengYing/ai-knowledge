# Verification — 0054

2026-10-09，LOCAL_VERIFIED。只使用合成材料、隔离本机数据和loopback模型/Milvus Adapter；没有真实模型请求、Git提交/推送或远端部署，没有修改旧资料。

## 先失败再通过

- `KnowledgeAnswerServiceTest#defaultTopKLimitsActualSynthesisEvidenceAcrossTheFullWorkspace`：旧生产实现把13条候选传入综合，预期最终5条，实际断言失败。日志 `/private/tmp/retrieval-settings-0054-red.log`。最终同例通过，验证的是实际模型输入，不是页面条数。
- `RetrievalSettingsRepositoryTest`：初始保存尚未实现导致运行时失败；持久化实现后读写、重启、版本冲突、组织身份与损坏文件保护通过。日志 `/private/tmp/retrieval-settings-0054-repository-red.log`。
- 共用mapper允许缺省继承后，旧ProductHelp入口依赖非空默认值，完整mapper用例出现2失败/3错误。恢复该旧入口独立默认值并明确拒绝新设置字段后，同完整文件通过，未静默声称旧入口支持新配置。日志 `/private/tmp/retrieval-settings-0054-product-mapper-red.log`。
- 首轮完整相关回归遇到测试向量Adapter仍要求全文查询携带向量；更新Adapter使SPARSE_ONLY不校验本来不存在的向量维度，密集/混合要求不变。没有删除、跳过或放宽生产行为断言。

## 最终Java相关回归

`/private/tmp/retrieval-settings-0054-final.log`：2026-10-09 00:34:37 +08，190项、0失败、0错误、0跳过。运行以下18个完整文件，而非只选新增方法：

```text
RetrievalSettingsRepositoryTest,RetrievalSettingsTest,RetrievalSettingsControllerTest,
RetrievalSettingsRequestMapperTest,RetrievalSettingsConfigurationTest,
MilvusRestProjectionTest,RetrievalProjectionTest,KnowledgeAnswerServiceTest,
TextRetrievalTestServiceTest,TextRetrievalFailureBoundaryTest,
RetrievalTestContractBoundaryTest,RetrievalTestTypesTest,ProductHelpRequestMapperTest,
ArchitectureRulesTest,ManagedTextWiringBoundaryTest,ManagedMediaSourcesRuntimeTest,
ManagedTextMainlineHttpTest,KnowledgeTopicAnswerServiceTest
```

运行环境：JDK21.0.8（PyCharm bundled JBR）、IntelliJ bundled Maven；输出目录 `/private/tmp/shared-workspace-0053`，未在运行中JAR上打包。直接相关测试命令使用 `-Dtest=<上述逗号分隔列表> test`，并设置 `-Drag.build.directory=/private/tmp/shared-workspace-0053`。最后一次生产/测试修改后重跑上述完整集合。

已验证可观察结果：

- 全库候选13条时生成最多收到默认5条；12条极低重排分噪声可被阈值排除，等于阈值保留。
- 文档和视频合并后全局TopK、相同原文去重；引用页码、码点、时间和版本身份保持。未选中的同页全文噪声不能通过context旁路重新进入模型。
- 一次请求捕获一次设置；并发保存只影响下一请求。筛空则无生成请求，不用模型常识补齐。
- 全文检索不调用嵌入；向量、BM25、归一化加权及服务商原始重排分分别使用明确分数语义，旧Query构造器RRF语义保持。
- 真实Spring HTTP保存全文/加权/Top1后，召回返回实际快照且无嵌入/重排；再保存高阈值，普通问答无生成并拒答；恢复默认后模型配置活动版本不变。
- Repository重启读取、CAS、共享成员、跨组织隔离及Spring分层回归通过。

`/private/tmp/retrieval-settings-0054-package.log`：00:35:35 +08，`-DskipTests package spotless:check`成功，1129个Java文件格式清洁。此构建跳过测试，不将它另算完整测试或覆盖率验收。

## 前端与真实页面

- 前端完整504项、0跳过；语法及diff检查通过，见Web变更0040的verification.md。
- 隔离预览：后端127.0.0.1:18088、前端127.0.0.1:18089；空环境启动，不加载真实模型密钥。运行不可变JAR副本 `/private/tmp/retrieval-settings-0054-preview.oLjjiP/app.jar`，新数据仅在同目录data中。
- 实际浏览器打开 `http://127.0.0.1:18089/#/settings`，初始v0、混合重排、Top5、阈值关闭；保存Top6/启用0.5阈值，界面回读v1；reload后仍为v1/Top6/0.5。这是持久化与页面联调，不是推荐生产阈值。
- 页面正确显示尚无已应用文字模型，没有假显示模型或提交模型测试/问答。保存与重新读取不调用模型。截图在Web `docs/changes/0040-retrieval-settings/page-proof.jpg`。

## 未验证与发布边界

- 没有真实provider语义质量、线上“灯塔”复验或阈值标定。默认阈值关闭，0.5仅初始输入值，不代表所有模型统一置信度。旧0052的14/20 halted，余量不自动续用。
- 未执行完整发布verify/覆盖率门禁、真实Milvus服务回归、手机视觉验收或生产发布。190项仅认证上述相关行为；TODO1新版质量、TODO2混合来源播放及完整TODO6仍开放。
- 召回测试仍只覆盖文字/OCR，普通综合可包括视频文字；专门媒体问答设置保持原合同，不称全部多模态模式已统一配置。
- 保留旧分支及未提交修改；本次没有提交/推送。后续发布应包含新增 `retrieval-settings.mjs`，合并现有生产免登录定制入口，不覆盖成强制登录版本。
- 使用fullstack-dev与codebase-design沿既有Controller/Service/Repository/Model分层，设置快照用小Interface封装；没有另建权限系统或引入新框架。计划无范围扩展，真实效果和发布继续后置并明确待验。
