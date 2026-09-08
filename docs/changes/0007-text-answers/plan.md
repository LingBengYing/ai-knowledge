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
