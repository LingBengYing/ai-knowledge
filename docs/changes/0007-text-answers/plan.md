# Plan：从已发布文本到有证答案

状态：IMPLEMENTATION。完整目标在blocked后重新启用，收到恢复请求；已复核重构173文件与原归档SHA，冻结13:54交付源码/297项报告/JAR，逐项恢复53新增文件和12已有文件修改。保留前端详情页不改、全部旧测试与草稿失败用例及生产gate。

1. 修改前核对0006的173文件manifest并冻结原297项报告/源码/JAR；本轮基准不是较旧Git HEAD。
2. 冻结小Interface及Model含义，分别新增权威scope/trace、事实支持算法、HTTP/配置/只读投影的失败测试，再实现。主线程串行运行Maven，禁止各agent写同一target。
3. AnswerService负责一次调用准入、总deadline与远程编排；EvidenceService/Repository负责短事务内授权hydrate/final trace，纯Tool负责事实支持与locator验证。不得重新造万能Service或无意义接口。
4. 组合真实SQLite与本机HTTP替身运行端到端回答/拒答/来源及固定PDF acceptance；每项未实现/未验证都保留记录，不用总数或红绿替代完整链路证明。
5. 完整回归、独立审查、源码绑定和文档同步；前端详情页仍不改，外部/生产后续按总目标逐项推进。

## Ownership

- authority_layering：EvidenceService、EvidenceRepository、scope/evidence/trace Domain与持久化sidecar及测试；不改其他Service/HTTP。
- adapter_layering：纯文本事实支持/否定条件冲突/引用校验Tool及其Domain输入输出和行为测试；只读参照旧Python源码，不修改旧实现。
- spring_authority_explore：HTTP Controller/转换、公开DTO、Config/能力装配、只读Milvus查询验证与对应测试；不改权威事务或事实算法。
- 主线程：规格/共享契约冻结、AnswerService总预算/远程编排与组合测试、串行构建、集成和最终文档。审查按非本人生产代码分配，不自批。

共享工作树不回退他人修改，生产代码前先记录真实红测。若工程发现需调整公开契约或schema，先更新本规格并说明原因；不更改原安全invariant或删除原测试求绿。

## 当前执行结果

### 408e622推送后：示例语境与多句程序修复

负责人随后再次明确要求“推送一下代码”，授权交付本批修复及文档；不包含前端、模型调用或生产部署。推送前限定Spec复核仍发现`required before resetting`被当独立事实：新增两项真实公开接口用例，57项中仅这两项失败（0错误/跳过）。保留红报告后，最小修复让未识别的required/necessary前提留在程序组并安全拒答，不扩展动名词推理；精确操作前提和其他独立事实正例保持。最后重跑完整相关文件、全量门禁、原675项保留校验、候选与历史密钥扫描，再核对远端并普通推送。此前“不自行推送”仅为追加授权前边界。

上一轮普通推送已由远端SHA确认，属于实质进展。本轮继续0007原规格，不重启付费诊断或扩展前端。精确前像为408e622和已绑定675项报告的冻结归档（SHA256 `7a2c3016a09738d18ac1195e0d20d8fee6f70ab5e14ca981c128ed6715f33db4`）；此前待处理两项均已通过公开Tool真实复现：示例6项2红/4正常标题对照绿，多句程序4项全红，原19项政策/单句步骤对照全绿。

按diagnosing-bugs先复现并展示可证伪假设，再做单变量探针。adapter_layering拥有SourceInstructions与新增ExampleTest；spring_authority_explore拥有TextGrounding/EvidenceConflicts、必要的QuestionFacts与package-private程序分组helper及新增ProcedureTest；root拥有真实parser/authority/AnswerService组合测试、工件与唯一串行构建。完整页不得扩成支持来源，跨候选全部步骤覆盖必须有正例；保留原675项、四PDF/六golden，最后共享语义修改后重跑完整行为与全量门禁。审查由非实现者按限定差异进行，不自行推送或部署本轮修改。

首轮322相关及737默认通过后，独立审查另发现程序后续错误标注/具名必要前提两项语义问题，以及逐step全页重扫的G07问题。新增39项8红、真实Service新增6红及独立128MiB/8秒执行+2秒清理的5万步骤资源红；保留首轮源码/报告归档，不用其认证后续修改。扩展到55项时新fixture ID过长引发8个构造错误，缩短仅该合成ID后55项16红/0 errors，断言不变，夹具错误不当产品红。

经root明确ownership授权，程序作者只在TruthContext增加复用SELF_QUALIFIER的包私有selfRefuted，原调用语义不变；程序按组一次检查指令语境、逐步检查原错误标注/问号，前提按完整目标操作名称绑定。未修改SourceFields、SourceInstructions既定修复或任何原测试，未增加公共Seam、任意step上限、依赖或schema。最后修改后11:52:50完整373项相关文件通过，新资源probe总0.928秒（含子JVM启动，不是纯算法或一般容量指标）；最终全量和限定复核以verification/REVIEW为准。

推送收尾：未知前提guard初版误伤包含required的另一具名操作，root接手最后一个参数正例与判断顺序，58项中1红后只将NAMED_OPERATION检查前移；57项既有用例未删改。12:07:08完整376项相关回归全绿，最终两轴限定finding均已关闭。句子边界不等于完整操作边界，具名另一操作与未知前提须分开处理；用真实正反例避免只防漏答却引入混组。最终源码冻结后全量构建、证据绑定和扫描通过才推送。

### 当前请求：同步已验证修复

负责人再次要求“推送一下代码”，本轮停止扩展语义修复，只同步现有四份生产Java、四份新增测试及相关说明。先核对319个候选文件与11:02:21验证后的冻结快照无漂移、235个构建输入与验证副本一致，再重跑Node、扫描候选及可达历史、检查远程main和普通fast-forward推送。不重做已绑定的完整Java验证，不调用外部模型、不部署；两项尚未处理的语义风险继续开放，新的远端CI结果另行确认。

### 推送后：证据验证整体审查与可复现修复

本轮从已推送且工作树干净的 `5a30ea9f4eb518a321a75588b8016c1356c42c68` 继续，上一轮完成代码同步、鉴权实测和本地门禁，属于实质进展。先冻结当前源码/配置/默认报告/JAR，归档SHA256 `d3980f60077d4b602d437cf90933aa022fd862ef4ffb5b247cd23f5dcde1afa7`，不沿用旧报告认证之后修改。

按code-review做独立两轴审查：仅六个新增 `tool/answer` 文件以 `git diff bc82a7a...5a30ea9 -- src/main/java/com/evidence/rag/tool/answer` 为固定完整前像；这六类在bc82a7a不存在，不把该git基线冒充完整0006冻结快照，也不混入parser包迁移。authority_layering只读Standards，spring_authority_explore只读Spec，重点检查整体资源边界和未覆盖的错误支持，不重复认证已关闭的具名前后限定。另由adapter_layering独立只读权威finish/source的完整scope/来源失效边界；root检查AnswerService预算、处理CI证据并串行复现。沿用版本化工件，不建立新的Issue工作流。

发现具体问题后先添加最小失败行为和必要组合回归，再分配生产文件ownership；不删除、跳过或放宽旧断言，不用无限词表或全拒答规避规范。模型调用权限仍待用户确认，本轮零付费调用，不改前端、旧服务、数据或生产gate，不自动推送新修改。

实际ownership与红测：root负责EvidenceConflicts页级冲突检查、TextGrounding policy v3、AnswerService取消映射和三个新Tool/Service测试；adapter_layering只改SourceFields和新的ResourceTest。10:44:46同页冲突20项8红，10:45:47只移除错误chunk边界后20绿；随后加不同主体/草案/数值表示等保护，22Tool+8真实parser组合+原15冲突+2取消均绿。性能复现为合法页公开verify在8秒执行预算内未退出，子JVM强杀并确认结束；单独预中断小测红，6个英文边界/数字冒号characterization在旧实现上先绿，再实施单调扫描、零拷贝视图和协作取消。完整原语义文件与默认门禁须在最终修改后重跑。

收尾：11:00:16完整278项相关行为回归、11:02:21默认675项及222文件格式/双覆盖率通过；原635项与87个旧test/fixture文件完整保留，73项Node通过。独立两轴限定复核完成，最终源码/报告另行绑定，不使用5a30ea9的CI认证本批未提交修改。未新增公共Seam、schema、依赖或生产例外；无模型调用、前端/旧数据修改、推送或部署。尚未关闭的两个语义风险继续登记，下一轮先用具体反例验证，不把当前小型资源结果推广为整体容量结论。

### 本次代码同步收尾

根据负责人推送请求，同步d43504f之后新增的测试入口、生成失败诊断与鉴权验证记录。只提交Java仓库源码和安全文档；不提交运行环境、密码、原始日志或数据，不推送前端、不部署生产。先核对origin/main、完整默认回归、候选和历史密钥扫描，再普通fast-forward推送；不能把未授权的真实PDF模型调用顺带执行。

### 有鉴权Milvus隔离环境准备（不调用模型）

上一轮已完成真实PDF入口的编译、配置保护、接收上限红绿和完整本地门禁；追加模型调用仍待用户确认，本轮不把自动继续当作计费授权。root先读取独立VM/现有测试容器的实际资源状态，在同一任务专用引擎中使用已缓存固定2.6.22/arm64镜像，新建容器、卷、非默认loopback端口与随机测试凭据；不停止、更改或共享旧测试容器的数据，也不接默认Docker/现役服务。

authority_layering仅研究官方固定版本鉴权配置并写单篇research；spring_authority_explore先只读确定真实TextAdapterSettings→Milvus客户端的零模型调用验证入口。root负责新资源的唯一命名、边界/资源检查、密码不回显与受限存储、启动及正确/缺失/错误凭据负例；需要新增测试时再明确ownership。只有真实拒绝负例和有权限操作均通过，才认定鉴权生效；仅token非空或healthz正常不算。预先创建专用空库供未来明确授权的PDF链路使用；无生产鉴权、RBAC最小权限、TLS或全链路通过声明。

已执行：10:32:04真实Java鉴权IT通过，三个拒绝负例、完整manifest和dense/BM25检索均通过；测试后两个专用数据库均为空。最终限定Standards/Spec复核通过，执行与源码绑定见[鉴权记录](milvus-authentication.md)。模型调用为零，付费链路授权仍未获得。

### 推送后的继续执行

代码快照 `d43504fc2341d9bae83352b773e79a1897dfccc7` 已正常推送，远程main已逐SHA确认，GitHub CI已完成且success。此后两次获准生成诊断均已执行：Java Adapter在60秒预算超时；独立curl完成TLS后60秒内无首字节，退出28。保留一次性attempt标记防止自动重复，两次额度已耗尽。不同HTTP客户端使用同模型及语义等同JSON输入，键序和时点不保证相同，不据两个观测直接断言provider内部或代理的固定根因，也不据此更改生产超时。

`authority_layering`独占新增 `TextAnswersLiveIT` 与测试专用 `LiveIndexWorker`，将原固定差旅PDF经真实解析/索引子JVM、publication、AnswerService及来源回读串联。付费前断言分块数不超过生产计算的一个batch，显式要求最多4次调用授权；未获此链路外部执行授权前只编译和检查配置保护，不冒称通过。索引child清空环境且不继承父JVM代理，使用现有测试启动Seam仅传校验后的loopback代理，实际调用IndexWorker.main保留watchdog/lease；不改变生产代理契约。默认回归不运行任何LiveIT，新集合仍按成功初始化后的owned精确清理。

该IT必须通过真实 `TextAdapterSettings.load`，不复制私有身份哈希算法或用类型构造器绕过配置。显式必填独立Milvus token、预先创建且为空的 `java_it_answers_*` 数据库和随机集合；后续需有鉴权隔离实例，现有无鉴权Milvus结果不能代替它。当前最多四次的含义是应用调用上限（索引embedding一次，问题embedding、rerank、extract各一次），并非供应商账单或底层网络包计数保证。

以已推送d43504f为明确前像审查两个新增WIP文件，沿用现有0007 spec与项目规范，不初始化新的Issue工作流。Standards发现管理HTTP在完整收包后才验64KiB，root负责在该新IT内补先红后绿的本地接收上限负例，改为收包时取消，保留15秒总预算和安全错误；其余生产及旧测试不改。编译、配置负例和本地接收测试不作为真实模型链路通过。

该问题已取得红绿与限定复核，10:02:53完整本地门禁通过，详见[验证](verification.md)。root另做零HTTP/零模型请求的直连TLS检查，10秒内未完成建连，未改系统代理。生成仍未可用，下一步先请求一次同平台其他文本模型的有界生成对照授权（仅原合成材料、60秒、最多2048输出tokens、不重试），确认模型可用后再执行完整4次链路；当前没有新增计费调用授权，不自行尝试。

### 2026-09-08：负责人要求推送当前代码

用户追加“推送一下代码”，优先提交当前Java仓库到既有origin/main，不做生产部署、不变更独立前端仓库。提交包含已验证的Spring职责分层、文本索引/问答增量及实测/未验证说明；不得携带模型key、私钥、环境实值、数据库、原始日志或临时诊断脚本。推送前核对远程基准、当前源码与干净构建一致、默认全量回归及暂存/历史敏感信息检查；不得force覆盖远程变更。

生成诊断切换到提交后继续：已停止尚在等待stdin的执行器，未输入key、未调用模型。本批追加的两次请求实际使用0次；新生成专用IT仅完成编译/缺配置保护验证，尚无真实生成通过结论。原provider摘录超时证据保留，端到端入口方案仍为只读研究，不能当实现或验收。

### 2026-09-08：生成超时诊断与完整链路入口

- 前一批为实质进展：Milvus真实补测及provider嵌入/重排成功，生成超时保留为失败。已冻结完整源码/默认报告归档（SHA256 `34635f69efa5def5ca38e8db15db0a9caee0c0804b184fdc7aa8e36ea80d1365`），不重跑通过的大批量Milvus测试，也不调整生产超时参数。
- 用户追加明确授权最多两次生成诊断，仅原三条合成材料、同一模型/JSON载荷、每次60秒/无自动重试：一次生成专用公开Java Adapter IT，一次独立curl时间诊断。凭据仍只经禁回显stdin进入短生命周期环境；响应原文不落盘。原三阶段失败IT及断言保留，新生成IT不再消耗embedding/rerank请求。
- `adapter_layering`仅新增`SiliconFlowGenerationLiveIT`；`spring_authority_explore`仅在新临时目录制作一次性HTTP诊断与安全结果摘要，root审核后执行两次请求。诊断只在有证据时区分上游等待、客户端接收和返回结构；单次超时不作为稳定重现或固定根因，不因本机替身通过宣称修复。
- `authority_layering`独立只读确定固定PDF→真实模型/Milvus→权威publication/AnswerService/source的最小可执行链路和请求预算。实现前冻结输入/权限/清理；这条完整链路的外部请求不占用本批两次生成授权，也不擅自调用。没有实际执行的入口只记为待执行，不冒称端到端通过。

### 2026-09-08：有界外部验证

- 沿用新建隔离 Milvus，不重启现役服务。`adapter_layering` 仅扩展 `MilvusLiveIT`：4096 短正文/4维分块完整验证后，新增第4097条必须按精确完整性错误拒绝；集合 release 后只读 prepare/search 不加载，显式 writer initialize 后恢复。非超时、限长假绿，不作为容量或重启恢复验收。
- 用户本轮重新提供 SiliconFlow 凭据并授权联调。主线程经禁回显 stdin 注入进程环境，不落仓库、不放命令参数或日志、不自动重试。先一次官方 `/models` 只读查询，再最多一次 embedding、一次 rerank、一次 extraction；仅上传固定合成数据，无用户资料。
- `spring_authority_explore` 仅新增显式 `SiliconFlowLiveIT`；主线程串行 Maven。默认测试不调用计费接口；缺少显式环境配置失败而非跳过。通过只代表所选模型别名在本时点的协议/少量合成行为，不是权重版本固定、检索总体质量、多模态或生产验收。

结果：09:04:14三个Milvus IT加35项既有相关测试全绿；09:08:40模型IT嵌入/重排通过、摘录 `model_timeout`，整套失败并停止，无重试/改阈值。09:11:16默认635项、214格式及双覆盖率门禁通过，Node73通过；未推送/部署，生产代码、旧服务及前端详情页不动。详细指纹/限定审查分别见[milvus-integration](milvus-integration.md)、[provider-integration](provider-integration.md)和[verification](verification.md)。

### 本批：真实 Milvus 隔离集成

- 主线程只管理新建临时运行环境及串行 Maven；不启动原 Docker Desktop、不连接旧集合，不读取聊天凭据。固定版本官方运行包校验后使用独立配置、缓存、磁盘与 loopback 端口，不挂载用户目录。环境设置失败与产品协议失败分别记录。
- adapter_layering 仅新增 `MilvusLiveIT`，通过现有生产 Adapter 验证初始化、合成向量写入、即时完整摘要回读、全新只读实例的 dense/BM25 查询及组织/文档/generation 范围；不扩展文本语义规则。测试专用 REST helper 只用于本次集合的跨组织负例与清理。
- 显式配置专用 `java_it_*` 数据库；集合名由测试随机生成，先确认不存在。只有本次初始化成功的集合才允许精确清理，不遍历或删除其他数据。缺配置执行失败，不以 assumption/skip 伪装外部验收。
- 首轮沿用现有 Surefire，显式 `-Dtest=MilvusLiveIT test` 运行；默认 `clean verify` 不自动访问外部实例，原635项回归及覆盖率门禁保持，IT另列结果。没有真实通过前不改能力状态。
- 合成向量用于验证真实 Milvus 协议与过滤，不是实际 embedding/rerank 模型质量、容量、多模态或生产验收。出现可复现协议差异后先保留红测，再按官方固定版本契约修复与回归。

执行结果：固定2.6.22/ARM64新实例于16:47:38通过首轮真实IT和35项既有相关回归；临时移除组织过滤的mutation真红，恢复后绿。默认635项及Node73项另行通过。生产Adapter源码无需调整，235既有输入哈希保持；完整证据和后续容量/冷加载/模型边界见[milvus-integration](milvus-integration.md)。

本批已关闭具名同页跨分块条件/主体绑定与Grounding输出构造不变量，根负责真实parser/publication端到端正反例。改前快照已冻结；独立审查发现的前置条件/标签前提及残余审批限定均以实际红测补齐，再完成限定两轴复核。最终实际Temurin21.0.12.1+1于15:59:54全量clean verify通过635项（含208Tool、16组合、7Domain、原六golden）、212格式及双80%门禁，Node73通过。原564结果保留为历史，当前指纹另绑定；没有删除/跳过旧测试。

环境路径调整：本机无JDK21，按Adoptium官方API下载并校验独立归档后执行，未修改系统Java。Docker引擎未运行，未启动可能恢复旧容器的Desktop；实际Milvus/provider、同生产镜像、完整语义/容量及多模态/生产仍待继续。本批不把这些后续目标改成已通过，也不更改前端详情页、现役服务、数据或发布gate。

已关闭本轮定位的HTTP测试字段、Milvus自身超时分类、最终配置竞态及关闭静默失败；最后代码修改后15:11:07完整clean verify通过564项Java、209文件格式与双80%门禁，Node73通过。配置资格Interface改用闭集状态，已先同步spec/interfaces，不保留无用兼容重载；原断言及297用例保留。限定修复审查、原测试保留及源码/新JAR绑定已记录；完整0007语义/资源审查、实际provider/Milvus、网页和最终生产目标仍未完成。前端详情页、旧服务及数据未动，无推送/部署。
