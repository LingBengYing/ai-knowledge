# Verification：文本问答

状态：IMPLEMENTATION。当前保留0007开发实现，已完成下述既有缺陷修复和完整本地门禁，不宣称完整语义、网页、实际provider/Milvus、多模态或生产验收。下方隔离/恢复和失败记录均为历史，不能覆盖最新结果。

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
