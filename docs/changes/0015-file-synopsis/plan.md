# 0015 执行计划

输入是合成文档、原图、音频转录与视频原帧/OCR/转录。最终用户操作为已索引资料生成摘要、查看结构化内容并回读原证据。本轮先交付这个闭环必需的生成 Module，再接 authority/持久任务/HTTP，不把内部 Module 报为完整产品。

1. 核对 0014 冻结输入，不重跑未改源码。2026-09-20 已复核 485 个输入 SHA，全部一致。
2. 明确 SynopsisInput、候选条目、FileSynopsis 和模型 Seam。以独立测试先 RED，随后 Domain/Client/Service 实现 GREEN。
3. 使用现有 ModelHttpTransport，保持 Spring layer-first、现有 REST/鉴权/SQLite/polling，不引入新的权限、ORM、任务或模型框架。Skill 中的 feature-first/脚手架不适用于本迁移版。
4. 主代理是唯一 Maven 执行者；在新临时隔离副本运行定向测试，最终完整回归、格式/架构/双80%、Node、独立限定审查及源码绑定。旧测试不删除、不跳过、不放宽。
5. 接续完整 authority 枚举、分层处理、持久化与独立来源 HTTP 后，才能声称文件摘要后端可用；随后真实模型质量/前端/生产按原 gate 推进。

Ownership：Domain worker 负责新 synopsis Domain 与其测试；Client worker 负责 SynopsisModels、OpenAiCompatibleSynopsisModels 与协议测试；主代理负责 SynopsisService、Service 测试、工件、唯一构建与整体验证。共享工作树不回退他人，等待整批就绪后同步，未授权不运行云模型、Git 写操作、部署或 Maven。

后置：非阻塞异常组合、容器/权限增强不扩展本轮；文件规模超出首切限额时显式不可用，分层处理是后续必要能力，不谎报完整长媒体摘要已交付。

## 第一步结果

2026-09-20 13:16:15完成必要内部Module：1400 Java、468格式/双80%、73 Node通过；初始RED48完整转绿并保留旧1352身份/多重性。未修改旧485输入，当前495输入绑定见[verification](verification.md)。独立限定代码审查无未关闭阻断。下一步从完整authority材料与分层、持久摘要/来源接线继续；不重复已完成协议模块，不扩大为用户可用摘要或总目标完成。

## 第二步执行

上一回合为progress，已逐SHA确认495冻结输入未变。本轮直接完成短文件完整授权证据→持久摘要任务→保存/重启→公开摘要与typed来源回读，完整长文件分层保留下一必要工作，不以正常短文件通过认证长媒体。

沿用layer-first Spring、既有REST snake_case/安全错误/JWT或session、原Store短事务、单任务轮询及标准模型Adapter。遵从skill的小Interface和集成测试方法，不采用新脚手架/feature-first、ORM/Redis/自动云重试/新权限体系。

Ownership：材料worker独占SynopsisMaterialRepository和新typed来源Domain与测试（不改旧Repository）；存储worker独占v13 schema/Store、新SynopsisRepository/任务Entity/Claim与迁移/Repository测试；接线worker独占SynopsisTaskProcessor/Job/Config/Controller/公开DTO/HTTP映射及对应测试；主代理负责SynopsisLibraryService、真实四模态后端验收、runtime按实际consumer声明、文档及唯一Maven。共享树不回退他人，接口先冻结；先可编译stub与具名RED，再GREEN，整批就绪后同步构建。旧测试仅在schema当前版本夹具必须适配时具名记录，不删除/跳过/放宽。

第二步于2026-09-20 13:54:10完成本机冻结：1429 Java/494格式/双80%/73 Node，完整四模态有界摘要与七种来源、持久化/重启/Range通过。521输入及旧1400用例身份多重性见[library-verification](library-verification.md)。三类RED及新fixture修正、两项独立审查修复均具名保留；没有前端、旧服务/数据、Git或云调用改变。原生成Module工件不覆盖。

下一具名主线为完整长文件分层：先明确完整输入与叶级真实来源的组合合同，再实现分层生成/全条目证明和有界持久执行。不能先截top-k/文件尾部，不把中间摘要当原事实证据，不重复本步骤已完成诊断。非阻塞backlog：模型/publication切换且旧pending未终止时的新建冲突交互；正常同版本重提已可复用。

## 第三步执行

上一回合progress：持久后端完成并获独立制品PASS。开始前live核对521输入SHA全部未变，不重跑已冻结源码。沿用Spring layer-first、SQLite/REST snake_case、既有JWT/session/任务轮询/安全错误；按skill的小Interface与完整集成测试推进，不引入新ORM/权限/重试框架。

Implementation采用有界完整不可变文件值（已有原图总32MiB、文字总250万CP）和有界模型batch，缓存原v1输入指纹，优先完整业务闭环；分页/流式读取作为测量后性能工作，不能成为本轮停在Module不接消费者的理由。长文件leaf/reduce/逐引用原始verify/全部原批review共用现有任务/来源HTTP。原模型、短文件测试语义保留。

Ownership：材料worker负责新FileInput/Batch/DerivedNode/Reduction/Review Domain、共享v1 fingerprint提取、新完整物化入口与测试；存储worker负责v14迁移/Store、完整Claim/Repository重载、真实迁移及必要旧fixture适配；模型worker负责独立Hierarchy模型Seam/OpenAI Adapter与协议测试；主代理负责HierarchicalSynopsisService、Library/Processor/Config消费者接线、真实长文件HTTP验收、文档、唯一Maven与最终门禁。所有人共享树不回退他人，先冻结接口+stub/RED再GREEN；不使用云额度，不Git写/部署。

执行分工小幅调整：材料worker完成17项Domain/材料测试后，继续独占Library/Processor/Config与五项Library/一项Config接线测试；主代理保留分层Service和真实HTTP验收、唯一Maven。无文件冲突或他人改动回退。

第三步于2026-09-20 14:33:25本机冻结：1485 Java/512格式/双80%/73 Node；完整长文字/长音频/9帧视频在原任务与来源HTTP闭环通过，末尾反证可否决，重启零模型读取。539输入及旧1429身份多重性见[hierarchy-verification](hierarchy-verification.md)，上步工件保留。没有新增云请求、前端、旧服务/数据或Git操作。下一条业务主线为独立字幕轨与typed时间来源，真实质量、查询附件、网页与生产继续保留；本步不重复诊断。
