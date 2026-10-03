# Verification

2026-10-03 00:39:27 +08:00，本机数字序列子切已完成：现有音频 authority/publication/AnswerService 路径可完整返回并保存 `Project Car launch code is 7,3,9,21` 的答案和 typed 音频引用；首项/前缀摘录不足时拒答，Cedar 仍不能由 Car 证明。611 项相关行为与架构、592 个 Java 文件格式检查及离线 package 通过。未改 ASR、转录、前端、0021、部署副本/配置、Git 或旧数据。

真实 ASR 复验 **NOT_RUN**。主线已准备新的清晰合成样本，但当前协调要求只读核对计费和既有台账、不增加调用；本轮新增模型调用 **0**，没有运行 pilot8、真实 provider/Milvus 或完整默认评测。旧部署音频仍保留其识别错误与答案/引用仅为 7 的失败记录，本机通过不能改写它。

## 实现及规格映射

| 行为 | 实现与验证 |
| --- | --- |
| 完整数字序列及准确原始范围 | SourceFields 只在逗号两侧（可有 ASCII 空格/Tab）均为十进制数字时保留其原范围；ASCII/中文逗号、ASCII/全角数字均有行为用例。引用字符串不做重写，emoji 前缀的 code-point 起止定位保持。 |
| 完整证明与主体 | TextGrounding 既有“整个字段必须落在模型摘录内”的循环不改；只引用 7 或 7,3 整体拒答，73921 不能凭空成为原摘录，Cedar/Car 匹配保持严格。 |
| 普通文本、列表与相邻赋值 | 普通文字逗号/中文逗号继续拆独立字段；末尾 `Project Car owner is Ada` 不归入 launch code；英文数词原文、千位分组及符号/小数/单位正例保持。 |
| 完整冲突 | EvidenceConflicts 只移除完整合法 ASCII 千位分组 token 的分隔逗号；`7,3,921` 不再被局部规范化成 `7,3921`。序列尾部差异、混合分组、拼接猜测及数字排除句在前/后两个方向均拒答。 |
| 最小负序列边界 | QuestionFacts.ScalarFact.matches 仅拒绝完整 `not + 数字逗号序列` 单独证明实际值；value 保留观察，不能静默忽略排除句的冲突否决。`is not enabled`、`is not approved`、`are not enabled`、未安排、NOT-READY-X、不夜城保持合法；不是通用否定规则升级。 |
| 既有条件/指令合同 | 完整共享中英文条件/否定/示例/指令、范围、更正及资源行为测试重跑。同字段/前置指令拒答；独立后缀指令不执行，不替换完整原事实。SourceInstructions/TruthContext 未修改。 |
| policy 身份 | TextGrounding.VERSION 升为 `java-text-grounding-v6-numeric-sequences`。AnswerService 新 trace 使用它，VideoAssessmentService 原 policy SHA 组合也包含它；既有版本断言引用当前常量，无历史 trace/ASR compiler/转录版本改写。 |
| 真实本机答案/来源接线 | AudioNumericSequenceAnswerTest 复用现有真实 SQLite、上传音频、摄取/索引 publication 和 AnswerService；3 项验证完整答案、保存的来源与时间定位、前缀拒答且不写引用及错误主体拒答。远端模型/向量使用确定性夹具，不认证 ASR/真实模型质量。 |

Ownership 为 4 个生产文件及 2 个新测试文件，指纹与本次 JAR 见 [source-manifest.json](source-manifest.json)。已有测试均保留，未修改门禁、规则/白名单或新增抽象。

## RED 与过程修正

保留工作区私有 `.local/` 日志，不把实际失败改写为通过。时间均为 +08:00。

| 时间 / 日志 | 实际结果 | 解释 |
| --- | --- | --- |
| 00:26:30 `audio-numeric-sequences-red.log` | 35 项、23 failure、0 error、0 skip | 上传/音频答案实际仅为 7，前缀可被接受、数字字段/尾部冲突及完整负序列护栏出现行为 RED。其中 1 项“独立后缀指令存在即应拒答”的预期过宽，不能计为本次关闭的注入缺陷。 |
| 00:28:43 `audio-numeric-sequences-first-green.log` | 35 项、2 failure | 文件名中的 first-green 不代表通过；剩余为负数字序列与上述错误后缀预期。 |
| 00:32:15 `audio-numeric-sequences-guards.log` | 12 项、1 failure | 六项合法负状态/名称对照与最小负序列处理通过，仍保留错误后缀预期 RED。 |
| 00:35:04 `audio-numeric-sequences-regression.log` | 604 项、0 failure、1 error | 新长序列夹具把 12k 完整 context 误作超过 4096 的候选 snippet 范围，GroundingText 输入门禁正确拒绝。不是产品 RED；改成合法短 snippet + 完整 context，原拒答断言和候选限制保持。 |
| 00:37:09 `audio-numeric-sequences-exclusion-red.log` | 10 项、2 failure | 实现中若直接将负序列 value 丢弃，会忽略正/排除共存两个方向的否决；改为只禁止其 matches 支持答案，保留 value 冲突观察。 |
| 00:38:37 `audio-numeric-sequences-regression-final.log` | 611 项全部通过 | 所有过程修正后的实际最终结果。 |

后缀用例修正由主线负责人依据 SourceInstructions 的当前字段/同句前缀和示例后缀边界，以及 TextGroundingInstructionTest.safeSecurityFacts 的有效事实隔离合同确认。原场景保留为正反对照：仅完整原事实 quote 支持且精确保留 7,3,9,21；伪造 73921 的 quote 与仅指令的 quote 拒绝；同字段/前置指令仍拒绝。没有删掉用例或为求绿放宽已有失败断言。

## 最终命令及结果

实际环境：Eclipse Adoptium Temurin 21.0.12.1+1，Maven 3.9.16，macOS 26.2 aarch64，UTF-8。在仓库根执行，工具环境及 Maven repository 均复用现有工作区工具。

```sh
source ../../.tools/env.sh
mvn -o -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2 spotless:apply
mvn -o -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2 '-Dtest=com.evidence.rag.tool.answer.*Test,com.evidence.rag.service.Answer*Test,AudioAnswerServiceTest,AudioNumericSequenceAnswerTest,VisualAnswerServiceTest,VisualQueryAttachmentAnswerTest,Video*Answer*Test,VideoAssessmentServiceTest,VideoProofInputServiceTest,ArchitectureRulesTest' test spotless:check
mvn -o -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2 -DskipTests package
```

最终相关集合为 47 个测试类 / 611 项：完整 `tool.answer` 的 21 类 / 407 项（含新增序列 48 项），22 类共享 Answer/音频/视觉/视频 Service / 185 项（含新增音频 3 项），3 类视频配置/请求映射/Controller / 8 项，以及 ArchitectureRulesTest / 11 项。全部 0 failure/error/skip；未执行完整默认套件或 IT。真实 parser 的 AnswerSemanticContextTest、AnswerSemanticScopeTest 与共享完整范围/条件/正反例均包含在该集合内。

格式日志：`audio-numeric-sequences-format.log` 与 `audio-numeric-sequences-format-final.log`；首轮实际处理 592 个 Java 文件，仅 4 个本批 ownership 文件需格式修正，最终检查确认 592 文件 clean。package 日志：`audio-numeric-sequences-package.log`，00:39:27 BUILD SUCCESS，明确跳过已单独通过的测试。

构建前/后分别保留 `audio-numeric-sequences-inputs-before.json` / `audio-numeric-sequences-inputs-after.json`；全部生产/测试 Java、打包资源和 pom 共 600 个输入的 SHA 完全一致，无新增、删除或内容变化。JAR 为 `target/rag-java-0.1.0-SNAPSHOT.jar`，38,711,206 bytes，SHA256 `879714e0e967734eeed9ad1c359da274d5ae08ad58c275350aa1b8b7f35ba543`。主线将在此稳定点后创建新的音频 snapshot，本批未触碰 `.tools/document-originals-handoff`。

这份记录为执行者的限定行为/结构/格式自查，不宣称独立审查、双 80% 新覆盖率门禁、全量评测、部署或公网复验完成。
