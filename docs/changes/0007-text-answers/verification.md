# Verification：文本问答

状态：IMPLEMENTATION。当前保留0007开发实现，已完成下述既有缺陷修复和完整本地门禁，不宣称完整语义、网页、实际provider/Milvus、多模态或生产验收。下方隔离/恢复和失败记录均为历史，不能覆盖最新结果。

## 本次推送快照：2026-09-08 12:08:55 +08:00

相对408e622，本批修改5份生产Java、新增包私有ProcedureEvidence及4份行为测试；不修改原测试、schema、依赖、构建门禁、前端或旧服务数据。明确Example/例子标签不能成为事实；程序按完整操作分组，必要步骤/前提必须全部由实际候选摘录覆盖。权威完整页只能否决缺失或冲突，不能创造模型未见的支持。验证器为`java-text-grounding-v4-procedure-context`，历史trace不改写。

最后源码修改后12:07:08完整376项相关回归通过，含58程序、23示例、16真实parser/SQLite/AnswerService/source组合、新旧资源用例、原四PDF/六golden及范围/生命周期/取消测试。12:08:55实际Temurin21.0.12.1+1（macOS arm64）隔离副本完整`clean verify`用时79秒：773项Java、76 suites，0失败/错误/跳过；227文件Spotless、行6006/6335与分支3023/3537均通过原80%门禁。Node73项通过（3.165秒）。默认不执行LiveIT，本批模型调用为零；确定性PDF acceptance不代表真实生成质量。

对408e622冻结前像逐项核对，原675项JUnit class/name多重集完整保留，新增98项；91个原src/test文件SHA未变，四PDF与golden未改。240个src/.mvn/pom输入与实际构建副本逐SHA一致。324个现存未忽略候选文件（包括新文件）复制扫描无密钥规则发现，可达历史另行扫描无发现；54份Markdown相对链接存在。最终文档修改不改变上述可执行输入，提交前再验证候选快照和暂存区。有限规则扫描不保证识别所有秘密形态。

审查收尾额外红绿：后续错误标注/具名前提39项中8红；5万短步骤在128MiB隔离子JVM的8秒执行预算内未退出，强杀并确认清理，修复后同用例0.928秒通过（包含启动，不是一般容量或语言性能比较）。未支持的`required before resetting`在57项中2红；新增guard误伤另一具名操作在58项中1红，均0错误/跳过。修复保留未知前提拒答，并优先划清另一具名操作边界。全部原断言保留；此前新fixture ID过长导致的8个构造错误只记夹具问题，缩短合成ID后取得真实失败，不冒充产品证据。

| 冻结工件 | SHA256 |
| --- | --- |
| 未识别前提红XML | `262415ffa1aa575e2df7006bbcdff701c066e0d95427c9952bd1dde9a16ad667` |
| 独立操作顺序红XML | `5e1044463bd09e18d43488ffca9862ef3260f3eb38ae5d924cbacafa2e93ad4c` |
| 最终全量日志 | `7ba6e01a580892e9ddb801598a19991d3b28cb228ee08a24a51c98027e0ac230` |
| 最终JAR | `9cacbc8adfff3c5d9291cbf538f301ca5ec9cbb3972df0f45658510495c423cf` |
| 最终JaCoCo XML | `9e86f8d3831681b0a012a87251948572de3711c923119a01c8116115362fb62f` |
| 最终src/配置/报告/JAR归档 | `4c12f3e7f5eb8d097083ca2c3a10fe3d450982cb2e0924bc9ac633fc0701b369` |

最终生产源码SHA256：ProcedureEvidence `8ecacb229ca6039a861ceafc2d750cfa3259d8fede0d87a3dbb7cf74f9a72ed6`；SourceInstructions `80498614190c5b3a940bfb816e51603acb10d1ac118725fbf3ff5b6777d5faac`；EvidenceConflicts `417d51d1936748a982fc12d3c0d1aa0402065b1a0e34b4d72ede1210a6ddd6a1`；QuestionFacts `57cae36488786bb13adf43cfa6ddf205b6bdaf156c7b3de637e052eb8f52b934`；TextGrounding `8c84a41b5d9799fbf612eb73a8a82505d5b579d45d9abd9439a4b9f7fb254c7e`；TruthContext `5312bf430c4328a6bef795a8bab2ea780acd1dfb97506790908d0a7315095e68`。原始报告和运行归档仅受控保存在本地，不提交日志、数据库、凭据或个人路径。

按负责人本次授权普通推送Java仓库，非部署；远端SHA及新CI须在推送后独立确认，不引用408e622的CI认证本批。Standards与Spec限定finding各0，详见[REVIEW](REVIEW.md)；不代表通用自然语言、整个Tool容量、完整0007、真实模型链路、网页、多模态或生产通过。

## 408e622已推送；当前继续示例与程序语义修复

远端main已回读确认 `408e622d55c60c3e3883d411ad13358b093176e4`，其[GitHub CI 34183261200](https://github.com/LingBengYing/ai-knowledge/actions/runs/34183261200)已completed/success。日志确认675项Java（0失败/错误/跳过）、222文件Spotless、双80%覆盖率和73项Node通过，日志SHA256 `a2cb1bcf8bc27ac95e496efbe5fc62bd702b4f4f1f7aa5557797a083ead75249`。这只认证该提交，不能认证本节后续修改，更不代表生产部署。

当前从该干净工作树继续两项已有风险。公开Tool复现：示例初始6项2失败，扩展22项16失败；多句程序4项全失败，扩展29项23失败（句号改逗号及独立操作/事实6项对照通过）。真实parser/publication/AnswerService/source组合10项8失败，涵盖完整/缺步摘录与真实跨分块。全部0 errors/skip；不是测试夹具编译失败。冻结红XML SHA256：示例22项 `074c5a5119bc77b4dc168613489db4feed1395692f924309c8f56e13b9501fa9`；程序29项 `3547e180c74731be37204fbb0c53c1b4155e75a03c48090990db6aad6558bbcc`；Service10项 `e19c084e31d96a5b917420845e4fbd2c0003828cdcd1e27ed1503109d1fee30e`。

示例最小标签修复先使22项通过，root追加同一行前置英文句子后为23项1失败；据此修正疑似标签筛选与实际句界的分工，不更改数值匹配或正文标题剥离。完整步骤、正常禁令和结束条件的正反例仍保留；此为开发阶段记录，最终回归/限定审查与追加推送授权以上节为准，无新增模型调用、前端改动或部署。

## 当前代码同步范围

按负责人本次推送请求，仅交付下面11:02:21修复快照与补充说明。提交准备时319个候选文件逐SHA与最终冻结清单一致，235个src/.mvn/pom输入与实际验证副本一致，归档和完整构建日志摘要均与下表相同；本轮不重复运行Java全量，也不复用旧提交CI冒充新CI。远端main与本地提交前基准5a30ea9一致；普通推送结果和新提交CI须另行回读。本次不是生产发布，文中“本批未推送”是此前验证完成时的历史状态；示例标签、多句程序两项继续开放。

本轮重新运行Node为73项通过、0失败/跳过（3.321秒）；候选和当前可达历史密钥规则扫描无发现，diff空白和启动脚本语法检查通过。源码未再修改，仅补README/工作约定/plan/本记录以澄清交付状态；有限规则扫描不构成所有秘密形态的识别保证。

## 同页冲突、连接词资源与取消审计：2026-09-08 11:02:21 +08:00

相对5a30ea9，本批只改四份生产Java：EvidenceConflicts、TextGrounding、SourceFields、AnswerService；新增四份测试，无旧测试、schema、构建配置、前端或现役数据变更。policy从v2升级为 `java-text-grounding-v3-page-conflicts`，不改历史trace。完整授权页只用于否决冲突答案，模型未见的分块外正文仍不能成为新支持/引用。

最终实际Temurin21.0.12.1+1（macOS arm64）干净副本 `clean verify` 83秒通过：675项Java/72 suites，0失败/错误/跳过；222文件Spotless与原双80%门禁通过（行5814/6135，分支2892/3387）。Node73项通过，3.267秒。对冻结前像逐项核对：原635项class/name多重集完整保留，新增40项；87个原src/test文件（含语料）SHA未变，4PDF与golden相对5a30ea9无差异。235个src/.mvn/pom输入在仓库与实际构建副本逐SHA一致。

最后修改后已先跑完整 `TextGrounding*Test`、原6golden、原16实际parser条件组合、生命周期与本批组合/取消，共278项通过；并非只跑新增用例。原四PDF六golden使用确定性模型/投影替身，不代表真实模型质量。默认全量未执行任何LiveIT；本轮模型调用为零。

### 可复现红绿

- 同页冲突：10:44:46新Tool12项和真实parser/authority/AnswerService8项共20项8fail，均应拒答却支持/answered；只移除错误chunk范围限制后10:45:47同一20项全绿，排除了数值归一或跨主体误匹配作为这组失败根因。最终新增22项Tool覆盖中英双向、不同主体、明确示例/草案、千位格式和符号/小数/单位；8项真实组合确保冲突不在唯一模型候选内、但在其权威页内，拒答无引用/答案摘要，合法对照仍可回读来源。
- 连接词密集页：400011 code point的合法合成页、候选/quote仅末尾精确事实，经公开TextGrounding.verify在8秒工作预算内未退出。父测试留2秒清理，强杀子JVM并确认退出，10:48:46按预期1fail，不遗留占CPU测试线程。10:51:01预中断负例另1fail（原先未抛取消）。修复后11:00:16资源8项共0.633秒通过，包括真实子JVM、预中断及6个先在旧实现上通过的英文词边界/数字冒号对照；这是本机小型资源回归，不是一般容量或Java/Python性能比较。
- 取消审计：10:48:46两个取消分支原样抛出CancellationException且未留trace（2 errors）。新增安全catch后10:51:01两项通过：处理中断记processing_timeout，未中断上游取消记upstream_unavailable；均无引用/答案摘要、保留1条安全trace。finish仍在上游catch之外，不能把存储失败转成成功。红报告不是通过修改预期或跳过用例消除。

| 冻结执行工件 | SHA256 |
| --- | --- |
| Tool同页冲突红XML | `2f037c31367788000fe76e2a6e5a412a0859dc600984c1772a91491231916ed7` |
| 实际parser/Service冲突红XML | `992a87b5657ecd1c30cdfd9222452b0f3887427375a505689e0427216aeb7fd5` |
| 资源红XML | `adf1ad4e2128dcb6a05c77612a11a7f8b531b9acfdeae2a4a89a2f3d9efdf51f` |
| 预中断红XML | `1883d991eadfbdee8ff6f0648d89ef59d981e5834b3e572c66293a00845a50f3` |
| 取消审计红XML | `35bc6a274d846bf54d4815b985bbb1fd59088e017fe21b53e2c813f15516f528` |
| 最终完整构建日志 | `8ecb4f827c4bd8d50c96f8d05032a847657e2fbc00e507b4e44b5c43e452c5f9` |
| 最终JAR | `723f99ea0f69180dae450b41a076d9cf223bb96204e48a224c08cb8431760e75` |
| 最终JaCoCo XML | `dea59293ab792e1997987fa728a9a95791dced531db3315a87e403d246575878` |
| 最终src/配置/报告/JAR归档 | `7a2c3016a09738d18ac1195e0d20d8fee6f70ab5e14ca981c128ed6715f33db4` |

最终生产源码SHA256：AnswerService `89bcdce05dbc4e597c531cbc43e6fd57b2d23b8004890b0d23e308e0884fda56`；EvidenceConflicts `5b96fcf70af3a0891bfac0c4b33f5b00807c077004834664b7f4d1190073b4c2`；TextGrounding `7eff493a8538e8591fe3725499d9e7603770d6d42b1c0dd33ecd486ee48d5328`；SourceFields `8a50dab70b30393c85652fd7be999b2b257414401cf5257d5ca60215251187f4`。原始执行工件保存在受控本地，不提交日志、凭据或数据。

安全附检：319个现存未忽略发布候选文件（含未跟踪测试）逐SHA复制扫描，规则无发现；当前index/工作树及可达历史另行扫描无发现。文档相对链接、diff空白检查通过；这是有限扫描，不是全机秘密清除声明。最终文档调整未改变上述可执行输入。

限定Standards/Spec复核见[REVIEW](REVIEW.md)。本批关闭同页直接冲突、平方级连接词扫描、分句协作取消及其审计映射；不认证整个语义引擎资源能力。示例标签可能误判、多句程序可能只答首步仍待实证/修复，完整外部PDF模型链路尚未获新调用授权，网页、多模态与生产仍缺证据。本批未推送或部署，production/readiness gate不变。

## 5a30ea9远端CI确认

上一轮增量已正常推送，origin/main回读为 `5a30ea9f4eb518a321a75588b8016c1356c42c68`。其[GitHub CI 34180621543](https://github.com/LingBengYing/ai-knowledge/actions/runs/34180621543)已completed/success，headSha相同，更新时间2026-09-08T02:39:19Z。实际日志确认Java21、635项Java且0失败/错误/跳过、218文件格式、双80%覆盖率及73项Node通过；独立日志SHA256 `6fa4e73c603de31e91800bf76d36cedc71e956d46d1e9e61e548cce43dde1077`。

此CI只绑定5a30ea9，不能认证随后同页冲突/性能修复；默认CI不执行外部LiveIT或模型请求，也不代表生产发布。

## 本次增量推送验证：2026-09-08 10:34:01 +08:00

相对已推送d43504f，只新增三个测试/support文件及验证文档，没有修改生产Java、原测试、pom/.mvn、四PDF或golden。真实Milvus鉴权IT通过，缺失/未知用户/同用户名错密码均精确拒绝；正确凭据完成写入、完整性验证和dense/BM25检索，清理后两个专用库为空，见[鉴权记录](milvus-authentication.md)。该1项外部IT与默认测试分别报告，模型请求为零。

最后源码冻结后，干净副本实际Temurin21.0.12.1+1完整 `clean verify` 82秒通过：635项Java、0失败/错误/跳过、218文件Spotless与行/分支双80%门禁。Node73项通过（3.763秒）。默认不运行LiveIT，已单独冻结其报告；完整真实PDF模型链路仍未执行，两次生成诊断超时仍保留为失败。

231项src/.mvn/pom输入与构建副本逐SHA一致；315个现存未忽略发布候选文件（含新增文件）复制校验并扫描，规则无发现；54份Markdown相对链接存在。当前工作树/index与可达历史另行扫描无发现，不代表能识别所有秘密形态。运行配置、密码、原始日志、数据库和临时脚本不纳入提交；仅Java仓库普通推送，不含前端或生产部署。

| 本次构建工件 | SHA256 |
| --- | --- |
| 完整默认验证日志 | `65e67d0ae91b7e858d7bbb122a18a0ea83d3150855802e80d214cc44b660ff72` |
| Node验证日志 | `6636471c36e70d1e74258f8b254804335772c3e91cd443c6fea357d4cf65d579` |
| JAR | `f8bb6c939e56a92c54dc51b6943d955c78e4ce192abba686d74a8dd6e7e70c94` |
| JaCoCo XML | `b4b9126cb9e214a895ddda3da7eeda15ed6a56980f854969d8fa622217a08bbf` |

此前d43504f的远端CI不能认证本次新增文件；新提交的CI结果须按该提交独立查询，不能提前写成通过。限定两轴复核见[REVIEW](REVIEW.md)。

## 新真实PDF入口的本地验证：2026-09-08 10:02:53 +08:00

新增仅 `TextAnswersLiveIT` 与测试专用 `LiveIndexWorker`，详见[执行说明与边界](text-answers-live.md)。完整默认 `clean verify` 在干净副本和实际Temurin21.0.12.1+1运行84秒通过：635项Java、0失败/错误/跳过、217文件Spotless与原行/分支双80%门禁；Node73项通过，4.029秒。默认构建不运行任何LiveIT；这是编译与原行为回归，不是新的真实PDF/provider/Milvus链路已通过。

相对d43504f，生产Java、原测试、构建配置、4PDF与golden均未改；230项当前src/.mvn/pom输入在工作树和实际构建副本逐SHA一致。新IT SHA256 `f766983ee19ad5629bc59c4672f12e651a2d67848b8cd12ff7e107f5383a99dd`，worker SHA256 `a4e376bb37c2ca45f482bfa34291b97698e627b8225843b5036315a7cbc824b4`。

本地配置保护：10:01:23/26/28分别缺ENABLED、MAX_MODEL_REQUESTS、MILVUS_TOKEN，精确到预期缺配置断言，均1 failure/0 error/0 skip；安全无凭据环境下未到首个外部操作。最初env清空导致JUnit临时目录不可写，单独保留为执行环境错误，不算产品红测；指定可写临时目录后才核对三项保护。

Standards审查发现测试管理HTTP后验长度检查，新增同一实际subscriber的离线负例：09:58:21未及时取消为1 failure，09:59:53修复后1 pass（精确64KiB通过、超过1字节取消、迟到complete不能恢复）。生产代码和15秒总等待未变；红绿XML分别为 `10311a9518be716c062c530b53572d4d12bf59360ed0ddcdab8be8aaf9b8cb56` / `0d4d52d573336ebcbd8271f9196f828d204e03e883c294e4b5ffdcbe0e49cb18`。此新增离线case是显式选择运行，不包含在默认635项中。

本次JAR SHA256 `d6f9a8c6938618ee803f275e7978a6e7e6a021c411b45403d854f28848afeb94`，JaCoCo XML `fa49894f4cb39e7ae48309748e98845099fd0adf634bd516de15878705712b4e`；src/配置/默认Surefire/JaCoCo/JAR冻结归档 `local-default.tgz` 的SHA256为 `d0a13a10c59c19589a68f057763722b0e48c0e4c8614859818fec33d4a424225`，原失败报告另行保留，不依赖被clean清理的target。

附检：312个现存未忽略文件（包括未跟踪的新测试与文档）逐SHA复制到新临时候选，秘密规则扫描无发现；既有工作树/index与可达Git历史另行扫描无发现，真实index未改。52份Markdown的415个相对链接存在，JSON可解析、启动脚本语法与diff空白检查通过。规则扫描不保证所有凭据形态均能识别，也不等于聊天或全机凭据清除。

限定独立两轴复核见[REVIEW](REVIEW.md)。两次获准生成诊断均失败且次数用完，详见[安全诊断记录](generation-diagnostics.md)；本次没有新的付费调用。截至10:02:53当时，新的4次端到端调用还未获授权，也未准备有鉴权专用Milvus实例；后续实例验证见[鉴权记录](milvus-authentication.md)，不解除模型授权、网页、多模态及生产gate。当时新增改动未推送或部署。

## 推送与远端CI确认：2026-09-08

已正常推送提交 `d43504fc2341d9bae83352b773e79a1897dfccc7` 到origin/main，非force，远端分支逐SHA回读一致。[GitHub Actions 34177027271](https://github.com/LingBengYing/ai-knowledge/actions/runs/34177027271) 的head_sha相同、status=completed、conclusion=success，最后更新为2026-09-08T01:36:26Z。日志核对：Ubuntu24、Temurin21.0.12-1/x64、73项Node、635项Java且0失败/错误/跳过、215文件Spotless和行/分支双80%门禁通过。保留日志 `0007-push-ci.log` SHA256为 `6400f16ed92058bd78e3818e3bb34aed866b46cb5c09b561c26b3814c2ac2901`。

这是远端Linux/Java21 CI，不是最终生产容器镜像或受控生产发布。推送后新增的真实PDF端到端IT及诊断文档不属于该提交或CI认证；默认测试未调用外部provider，不能覆盖已记录的生成超时。

## 推送候选检查：2026-09-08 09:29:31 +08:00

负责人明确要求推送当前Java代码；目标为既有origin/main，检查时远程基准与本地HEAD同为 `bc82a7af91e76376bf1a91415908100be07b1852`。干净副本最终 `clean verify` 通过635项、215个Java文件格式及原双80%覆盖率门禁（84秒），Node73项通过（3.642秒），0失败/错误/跳过。默认构建不选三个显式外部IT；新 `SiliconFlowGenerationLiveIT` 只经编译和缺配置保护验证，追加两次诊断实际尚未调用。

当前生产及既有测试235项输入在工作树/干净副本逐SHA均保持原基线；新生成IT SHA256 `d0b9d014141536eda891c7873e11015cc125c646016c403b82438feeba073c63`。本次JAR SHA256 `f188ba1a1801abc2aa82a43bf10f7783894f53af7bdb06e16d20dd26b8e7c775`、JaCoCo XML SHA256 `4859c45aadf2a7ec733133b5a79a8319dd3422e342c1acfaa443b142d3b5e687`；旧归档/真实provider失败记录保留，不由新默认绿覆盖。

暂存源码、工作文件和可达Git历史敏感规则扫描无发现；50份Markdown的403个相对链接存在，启动脚本语法和空白检查通过。只提交项目源码、测试及说明，排除凭据/运行目录/日志/数据库/临时诊断脚本。授权是代码同步，不是生产发布、完整0007验收或独立前端仓库更新。

## 最新默认回归：2026-09-08 09:11:16 +08:00

新增两个显式外部IT后，干净副本以同一实际Temurin21.0.12.1+1执行完整 `clean verify` 通过（84秒）：635项/68 suites，0失败/错误/跳过，214个Java文件格式通过；双80%门禁通过，行5792/6107（94.84%）、分支2878/3369（85.43%）。默认Surefire报告没有任何LiveIT，不能把该次绿覆盖外部摘录超时。Node73项全绿，4.158秒。235旧构建输入在两个目录逐SHA相同，新增仅测试，生产代码未改。

本次JAR SHA256 `38a2f34c3237bd1fd1f32f1c5d16d0d6a1249b77252a69fee178bb2a26531956`；JaCoCo XML SHA256 `6193d3509bdce1a28fa7e01e74cc66d31e67a0c81a3aef77bf7a8560df8148df`。旧[source-manifest](source-manifest.json)继续绑定其历史报告，不用当前target覆盖历史归档。新增IT分别由[milvus-integration](milvus-integration.json)与[provider-integration](provider-integration.json)绑定源码及独立报告；后者仍是partial，不宣称真实完整RAG通过。

该次完整源码/构建配置、Surefire/JaCoCo及JAR已另行冻结归档，SHA256 `34635f69efa5def5ca38e8db15db0a9caee0c0804b184fdc7aa8e36ea80d1365`。外部IT成功/失败XML在默认clean前已各自冻结，不依赖当前target；原工作树index未改，没有推送或部署。

## 补充：真实Milvus首轮集成（16:47:38 +08:00）

2026-09-08最新补测：[Milvus](milvus-integration.md)三个真实IT及35项既有相关测试通过（38项），新增4096+1精确完整性与卸载/显式重载；[SiliconFlow](provider-integration.md)一次有界联调中嵌入与重排通过，第三阶段摘录在60秒截止后 `model_timeout`，整体1项失败，未重试或修改断言求绿。当前用户凭据仅stdin→短生命周期环境，代码/文档/本次报告精确值扫描445文件0匹配；不宣称历史/聊天凭据已清除或已轮换。生产235项既有输入在实际工作树与干净构建副本逐SHA均未变。

固定Milvus2.6.22/ARM64独立实例的合成向量写入、精确摘要回读、dense/BM25范围查询及随机集合清理通过。仅临时构建副本移除workspace前置过滤时真实IT失败，恢复原SHA后1项live及35项既有相关回归全部通过；生产源码未改。默认635项回归和Node73项另行通过，不与重叠的35项重复计数。完整运行边界、指纹、限定审查与未验证项见[milvus-integration](milvus-integration.md)及[机器可读记录](milvus-integration.json)。此补充不认证实际模型、容量、多模态或生产；下述635前像报告/JAR已在本批前完整归档，不能从后续复用的临时target回读旧报告。

## 最新：限定条件、Model不变量与实际JDK21（15:59:54 +08:00）

最后源码修改与统一格式之后，实际Temurin21.0.12.1+1执行完整`clean verify`于2026-09-07 15:59:54 +08:00成功，79秒：635项JUnit，0失败/错误/跳过；212个Java文件Spotless通过，行5791/6107（94.83%）、分支2879/3369（85.46%），双80%门禁保持。包含完整208项Tool、7项Grounding Domain、16项真实parser/publication条件组合、原四PDF六golden、真实SQLite/本机HTTP/子JVM及架构测试。Node73项另行通过，0失败/取消/跳过，4.037秒。不是实际模型质量报告。

JAR SHA256 `6a090cf816d838e0eee0374e36d441c27204ed8d149c556ecaad5d749ffbb7bb`，JaCoCo XML SHA256 `aa00cbe2996b9a78acb9e666e5d3dc855771adf7226f8cd703a557409577227a`，均来自隔离的最终构建副本，不是原工作树污染target。输入逐项绑定及旧测试精确保留见[source-manifest](source-manifest.json)与[test-retention](test-retention.json)。

68个suite；225核心/235扩展输入均与最终副本逐项同SHA，扩展聚合`3423b1d0a3cecdac1007c90d581799cc2c6108cfb5d946dded530650c21dc731`。原297项、上一轮564项class/name多重集均精确保留（分别新增338、71），无缺失、跳过或显示名称映射；上一轮74份测试Java源码SHA全部未变。本批只改4份生产Java（两个Tool、两个Domain），新增3份测试Java；原4PDF与6golden哈希不变。

附检：实际候选发布快照300个现存未忽略文件逐项同SHA复制并执行敏感信息规则扫描，无发现；原仓库index未改。48份Markdown的374个相对链接存在，`git diff --check`和启动脚本语法通过。扫描是有限规则，不保证识别所有凭据形态；未推送或部署。

修改前冻结src/pom/.mvn/0007工件，归档`baseline.tgz` SHA256为`062b2f6a83b03e2c7cb628d487fc5e30fc02fa2ad062c759912260b320fdeff7`。此次比较不使用较旧Git HEAD；未改旧Python/前端详情页/现役服务或数据。

- 同页跨分块条件：真实Java解析、摄取和索引publication下，模型仅摘数值片段时，中英文“仅适用于试运行”均复现错误answered；15:33:12四项组合2fail，其他主体两个正例绿。Tool另以中英文同主体/不同主体、真正跨主体前提和名称前后缀验证，不硬编码名称。
- 独立Spec审查阻止两处漏判：前置远隔限定、带中性标签的真实前提。15:46:08完整Tool204项4fail；逆序真实parser组合15:51:37八项2fail。前置审批限定在首个修复中仍未涵盖，复用原限定集合双向重放后15:54:59完整Tool208项2fail、15:55:44真实parser十六项2fail。所有实际红测均保留，无删/跳过/放宽断言。
- Grounding输出Domain：15:33:32七项5fail；`GroundedQuote`验证身份、Unicode范围/正文长度和1–8事实hash，`GroundingResult`限制支持/拒答形状及安全原因码，保持防御复制。输入`GroundingQuote`仍表示不可信值交Tool整体验证，不强加输出32条构造限制。15:35:31七Domain+原160Tool+六golden共173项全绿。
- 语义policy升级为`java-text-grounding-v2-scoped-conditions`；真实parser组合要求`unsafe_evidence`而不是任意拒答，并验证trace.policy_revision及拒答无答案hash/引用、合法回答来源精确回读。名称前后缀原判断已正确，本次只补回归，没有修改QuestionFacts。
- 实际Temurin21.0.12.1+1（macOS arm64）已通过官方归档安装方式在独立临时目录启动，未改系统Java。下载SHA256 `3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998`与Adoptium API一致；[官方校验方式](https://adoptium.net/installation/archives)。15:44:20的611项与15:55:04的623项JDK21全量均为中间候选通过，不认证随后审批条件修复。

Standards与Spec分别由非Tool实现者审查，前置审批残余问题实际补齐后才关闭两项finding；Domain由非实现者复核，详见[REVIEW](REVIEW.md)。该限定审查不认证整切0007。独立Docker引擎未运行，本机无现成Colima/OrbStack；没有启动旧Docker环境。真实Milvus/provider、同生产镜像、跨页/表格语义、容量、多模态及生产仍未验收，不以本机JDK21通过代替这些证据。以下15:11结果为历史，不认证本批新增修改。

## 历史：完整本地门禁（15:11:07 +08:00）

干净临时副本执行`mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=<isolated-cache> clean verify`，约82秒，BUILD SUCCESS：65个suite、564项JUnit，0失败/错误/跳过；209个Java文件Spotless通过，JaCoCo行/分支双80%门禁不变且通过。实际运行OpenJDK22.0.2、编译release21，不作为实际JDK21运行证明。另在当前仓库执行`node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs`，73项全绿，0失败/取消/跳过。

构建前后当前仓库与副本的222个src/.mvn/pom输入逐项同SHA，聚合`77a9b7378d3f84fa8f17d85f8c92b928b04c03172953922cbfe4cc288aff3b61`。独立核对原297个class/name多重集在新564项结果中全部保留。完整清单、覆盖率计数、新JAR身份与fixture绑定见[source-manifest](source-manifest.json)，原测试保留见[test-retention](test-retention.json)。原46测试源码中43同hash，另外3个仅为测试投影只读prepareSearch与旧库升级到v4的夹具/版本断言推进，不能声称原断言字节完全未改。

四份原PDF通过真实Java解析/publication，六个golden预期未改，`AnswerGoldenTest`全部通过。160项完整Tool回归、真实SQLite/本机HTTP/子JVM及原功能测试均包含在564项中。模型与Milvus使用明确协议/确定性替身；测试总数不等于完整语义覆盖或实际模型质量。

安全/文档附检：历史与已跟踪内容规则扫描无发现；另将297个现存未忽略文件（含新增未跟踪源码）逐项同SHA复制到临时Git快照，扫描无发现，未改实际Git index。文档同步后快照扫描仍无发现；这是有限规则检查，不保证识别所有凭据形态。48份Markdown的370个相对链接均存在，Shell语法与diff空白检查通过。本轮仅文档/示例随后补充验证说明，没有再次改变上述232个可执行输入。

### 本轮修复及先红后绿

- HTTP失败是新增`AnswersHttpTest`误读`code`，既有公开契约一直为`error_code`。仅修正新测试字段，保留全部坏输入、422/invalid_request、安全响应和零外部调用断言；生产错误映射与旧HTTP测试不变。15:00:39完整HTTP9项全绿。
- Milvus的HTTP自身超时经`ExecutionException`包装，原代码漏判为`projection_transport_failed`。只识别`HttpTimeoutException`为`projection_timeout`，未更改deadline/清理/重试策略；新增真实断连仍须transport失败。15:01:56完整44项向量协议测试通过，最终全量仍通过。
- 关闭未确认线程实际退出：14:59:25新增2项负例均因未抛预期关闭错误而失败。现5秒超时/中断明确安全错误，中断标记保留，可再次等待退出，新调用持续被拒绝。独立审查补强再次close断言：临时副本注入旧的closed直接return错误，15:03:56两项均在“Repeated close returned before the blocked worker exited”处失败；已恢复正确源码，最终完整8项生命周期全绿。没有在生产源码保留mutation。
- 配置最终提交竞态：15:04:51模型/投影两项真实Store等锁负例均复现应拒答却answered。现用闭集`AnswerEligibility`在取得Store锁后及写trace前复验；任何拒绝不会恢复成功，配置变更与超时分别记原因，null整体回滚。原两项及新增3项资格转换/回滚测试均在最终全量通过；无Repository/schema改动、无新增网络调用。

关闭和R01资格、Milvus超时分类各有非本人生产实现的限定范围Standards/Spec复核，详见[REVIEW](REVIEW.md)。这不是整切0007独立审查通过。

### 15:11时登记的未验收范围（本批关闭项见上）

- Tool跨分块限制条件/不同明确主体绑定、复杂表格/标题继承/跨段步骤等更广语义，需要独立审查和补充正反例；不能把六golden或受限词法规则说成通用语义证明。
- 大页/密集字段的CPU内存、重复解析与中断响应未做容量验收；Domain构造器更全面的一致性/闭集负例仍待补。
- 显式报告关闭未完成不等于强杀不合作线程，也不保证Spring停止销毁依赖；生产退出策略仍需验收。
- 实际provider/Milvus、实际JDK21同镜像、网页提问/引用、多模态、备份恢复/发布/负载/生产身份均未由本轮证明。前端详情页、旧服务和数据未改，未推送或部署。

## 历史：干净副本定向验证（14:54:24 +08:00）

本轮只验证既有Java候选，不扩功能或修改生产代码。将293个现存、未忽略的文件复制到独立临时构建目录，逐文件核对复制前后SHA相同；不复制原target、Git目录、运行数据或凭据。220个src/.mvn/pom构建输入按相对路径排序，以path+NUL+文件SHA+LF聚合，SHA-256为`135f8660cf8b14e1129807b1ea703595bb3e956c912ae2ab35ee3e311ce7f102`。

OpenJDK22.0.2、release21、Maven3.9.9离线运行18个指定suite，23.830秒结束：117项，115通过、2 failures、0 errors/skip。Spring上下文初始化为540/92/75毫秒，真实HTTP9项已执行8通过。旧目录那次长时间扫描重复class并退出143的运行，不能代表本次副本的结果，也不据此推定重复文件产生原因。

- 失败：`AnswersHttpTest.malformedJsonUnknownFieldsAndAmbiguousScopeFailBeforeAnyProvider`预期`invalid_request`，收到缺失的错误字段。
- 失败：`MilvusSearchPreparationTest.preparationHasOneBudgetAcrossItsReadsAndDoesNotLeaveSearchReady`预期`projection_timeout`，实际为`projection_transport_failed`。
- 通过：权威容量/ID/trace封存，原迁移、AnswerService及29项边界、6项生命周期、配置/输入/Runtime、11项架构规则；四PDF六golden未改预期且全部通过。协议替身不代表实际provider/Milvus质量。
- 未关闭的独立审查风险仍包括最终hydrate等锁后的配置变更窗口、close未确认实际执行器退出。上述6项生命周期测试不覆盖忽略中断超过关闭等待的实现。

复现使用隔离Maven settings/cache执行`mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -Dtest=EvidenceCapacityTest,EvidenceDomainTest,EvidenceTraceSealTest,EvidenceServiceTest,EvidenceMigrationTest,IngestionMigrationTest,IndexingMigrationTest,AnswerServiceTest,AnswerServiceBoundaryTest,AnswerLifecycleTest,AnswersHttpTest,AnswersSettingsTest,TextAdaptersConfigurationTest,AnswerRequestMapperTest,AnswersRuntimeTest,AnswerGoldenTest,MilvusSearchPreparationTest,ArchitectureRulesTest test`。这是定向测试，不是全量clean verify、覆盖率门禁、完整安全审查或生产验收；无前端改动、真实模型调用、推送或部署。

## 历史：恢复与组合验证

原归档SHA已重新核对，按65项manifest逐项恢复53新增文件与12已有文件修改；目标已有文件先验证重构SHA，新文件要求目标不存在，避免覆盖后续更改。未恢复归档中的旧target报告，也没有恢复旧文档覆盖最新恢复记录。

14:30:05 +08:00：组合Maven运行189项，180通过、9 failures、0 errors/skip。纯Tool完整130项、四PDF六个golden、AnswerServiceBoundary29项、配置/输入/Runtime9项通过。9个真实失败为权威页>8MiB和DocumentSelection非法ID未拒绝2项，缺少HTTP入口导致404的7项；这些失败已分配修复，没有改预期或删除用例。该定向运行未执行完整clean verify、全部旧回归或最终双覆盖率，不代表0007已验收。

## 历史：可恢复隔离

初次暂停的29文件草稿曾恢复并继续实施；本轮最终隔离的是53个新增Java文件（29生产、24测试）和12个既有文件的0007修改，不再使用旧29文件清单代表最新成果。完整源码、测试、配置、0007工件与局部Surefire报告保存在父工作区 `artifacts/0007-java-text-answers-paused/20260907-unfinished-source.tgz`；同目录manifest记录65项相对重构基线的路径及新旧SHA-256。

归档SHA-256：`a041dd9b68549635cd05f5d87be329d353bb95ea17c6cd8e527e86b32e3f8bf9`。根任务及只读助手独立从tar回读65项，字节/摘要全部一致后才隔离。12项恢复至已验证基线，53个新增文件从编译路径移出但完整保留归档；没有删除旧测试、放宽断言或以隔离动作宣称新功能通过。归档不纳入Java仓库发布，恢复时须逐项合并，不能覆盖后续工作。

## 草稿的实际局部结果

- 13:03:28：权威/迁移19项真实红测，7 failures、12 errors；已排除之前投影身份夹具错误。
- 13:07:42：只读Milvus准入6项真实HTTP红测，3 failures、3 errors。
- 13:25:57：组合56项运行，47通过、6 failures、3 errors、0跳过。其中权威19项、旧迁移7项、Milvus准入6项、AnswerService9项通过；固定PDF问答仅4/6通过，配置/输入/Runtime仍有7项失败。不是最终acceptance通过。
- 13:49:14：负责纯Tool的助手报告独立javac/JUnit下130项通过，包含否定/条件/冲突/注入及政策/流程表达；不是全量Maven、PDF问答重放或独立安全审查。
- 配置/输入的后续实现、新增真实Spring HTTP9项、权威容量/Domain/seal6项、AnswerServiceBoundary29项均未取得最终验证。尚未创建AnswerController；不能称已有可用问答HTTP API。

完整回归、最终PDF问答acceptance、真实HTTP全链路、容量/并发/语义边界、双覆盖率、独立审查和新产物绑定仍待完成。未来恢复须保留所有失败及未运行用例，重新验证最后修改后的完整相关行为；不能从局部绿灯推定其他契约满足。未宣称文本问答、网页问答、实际provider/Milvus或生产完成。
