# LLM Wiki 与当前知识库：思想、源码和产品差距对比

研究日期：2026-10-09。状态：研究建议，供负责人审阅；不代表架构方案已采纳或实施。

## 先看结论

**当前系统已经具备原始资料、版本、索引、引用和单文件摘要的基础，但“围绕一个主题持续整理知识”和“理解用户要找文件还是问事实”这两层明显不足。** 用户截图中的问题是“有哪些文件包含灯塔，这个讲了啥”；按当前源码，这类问题仍进入一次片段检索与综合路径，正好暴露了这个差距。

卡帕西的 LLM Wiki 值得借鉴之处，是让跨文件的理解成为可保存、可更新、可浏览的知识产物。`nashsu/llm_wiki` 已把这个方向做成包含知识页、关联图、工具检索、会话和维护工作流的桌面应用。但它也没有自动解决事实准确性、原文定位、来源撤回和大规模维护问题。

**建议方向：保留现有 Java、Milvus 和原始证据能力，先补文件发现与问答编排，再增加可追溯的主题知识层。** 暂不整体换技术栈、不把所有原文压缩成摘要、不以绘制关系图代替改善实际检索。

## 1. 本次对比的版本和证据范围

| 对象 | 核对基线 | 如何使用证据 |
| --- | --- | --- |
| Karpathy LLM Wiki | 作者 Gist，修订 `ac46de1ad27f92b28ac95459c782c07f6b8c964a` | 完整核对作者正文；评论区和其他衍生项目不算作者观点。[原文][K] |
| nashsu/llm_wiki | `main` 快照 `48fd970e206a02a6d2028d1dbfc41b7a0345bf0b`，提交时间 2026-09-28；package 版本 0.6.12 | 阅读 README 与实际 TypeScript/Rust 源码；实现结论以固定提交的调用路径为准。[仓库快照][N0] |
| 当前 Java 后端 | 本地工作树，已包含 0053/0054；HEAD `fabc5a64b1e87a3ba54fdbd5f47529bba93849e9` 上有未提交改动 | 对比的是当前源码，不是只比较该 HEAD 的已提交内容。[0054规格][L0] |
| 当前前端 | 本地工作树，已包含 0039/0040；HEAD `2ee35f5a8ae2e4a5ca8fc10445b624b08bfc965a` 上有未提交改动 | 直接检查页面、请求、摘要和来源代码；当前源码与历史 README 描述分开判断。[0040规格][F0] |

本轮未运行参考应用、未进行真实模型对照实验、未修改业务代码或生产配置。下面的“已实现”表示已找到对应源码，不等于已证明真实质量优于另一方案。上一轮线上故障诊断仅作为需求背景，不充当本轮性能或准确率实验。

## 2. 卡帕西的思想到底是什么

原文的核心是：保留原资料，在其上维护持久、互相链接的知识页，并用 schema 约定组织方式。操作包括新资料融入、查询和知识维护；有价值的回答可以回存。规模扩大后仍可使用搜索，文中也列出包含向量检索的工具。因此，它没有要求取消 RAG、改用图数据库，或把所有回答自动变成事实。[原文][K]

对本项目的启发是：**检索负责找到材料，知识编译负责保存已经整理出来的理解。二者可以共存。** 单篇摘要只回答“一份文件讲什么”，主题页还要回答“多份资料之间有什么共同点、差异、条件和待确认事项”。这是本报告的设计判断，不是作者对本系统的实现承诺。

## 3. 与参考仓库、当前源码逐项比较

| 维度 | nashsu/llm_wiki 当前实现 | 我们当前实现 | 对产品的影响 |
| --- | --- | --- | --- |
| 主要产物 | Markdown 知识页、来源页、实体/概念页及链接；可直接浏览 | 原文件、解析证据、向量索引、单文件摘要、问答引用 | 我们能保存和查证资料，但还没有跨文件知识页面。[N1] [L1] [L8] |
| 知识加工 | 导入分析和生成分开，长文处理、缓存、合并和检查点已有代码 | 摄取、解析、完整索引发布；摘要单独生成，支持长文件分层 | 现有摘要可复用，但不能直接称为知识 Wiki。[N1] [L2] [L8] |
| 查询方式 | Rust Agent 可调用 Wiki、原资料、关联图等工具；有迭代检索模式 | 普通综合问答固定一次检索，再一次综合生成 | 问题类型变化时，我们缺少不同的读取策略。[N2] [L3] [L4] |
| 找文件 | Agent 有原资料片段搜索、Wiki 页读取；MCP 另提供项目文本文件读取 | 管理列表只按文件名/显示名搜索；问答按片段 TopK | “文件包含某词”与“回答某个事实”被混用。[N3] [N12] [L5] [L6] |
| 跨文件关系 | `[[wikilinks]]`、来源重叠和图结构相关性等 | 目录、标签、同一答案内的多来源引用 | 管理分类已经有，但不是实体归一、概念关联或持久综合。[N4] [L6] |
| 多轮与知识回存 | 多会话持久化；有显式 Save to Wiki 动作 | 请求只包含当前问题；trace 保存哈希和来源身份，不是可浏览对话正文 | “这个”“刚才那份”等指代与回答沉淀缺少支持。[N5] [N11] [L7] [L9] |
| 来源与引用 | Agent 引用以路径、标题、片段等为主；有只读原资料的 Faithful 模式 | 服务器回填文件、版本、SHA、页码/时间及来源接口 | 我们的原始证据定位更细；两边都不能仅凭有引用就保证每句话正确。[N2] [N6] [L10] |
| 更新维护 | 来源监听、重新导入、删除清理、文件历史及知识检查 | 原文件更新/重建/撤下；现有单文件摘要按发布版本与指纹失效 | 我们缺跨文件知识依赖传播；参考实现也有多来源正文残留风险。[N7] [N8] [L8] [L11] |
| 多模态 | 已有 PDF 内图提取、图像描述和图像结果；主要围绕文档知识工作流 | OCR、ASR、字幕、原图/声音/视频专门入口及 typed 来源 | 不应因 Wiki 能力更强就断言它覆盖了我们承诺的全部音视频链。[N9] [L12] [F1] |
| 使用体验 | 可浏览知识、来源、关联和历史，能展示 Agent 工具事件 | 资料/任务/问答/设置；默认综合问答，仍有多种媒体模式 | 我们更像资料工作台，缺少围绕主题探索和透明检索过程。[N2] [N4] [F1] [F2] |
| 系统形态 | Tauri 2、Rust、React/TypeScript，Markdown 工作区；可选 LanceDB | Java/Spring、SQLite 权威存储、Milvus、独立网页 | 产品目标不同；源码阅读不能证明 Rust 或 Java 的端到端性能孰优。[N0] [L2] |

## 4. 当前项目最需要正视的不足

### 4.1 “找文件”被当成“从几个片段回答问题”

当前普通问答路径为：

```text
原始问题整句
  → 文档文字与视频文字检索
  → 重排 / 阈值 / 原文去重 / 片段 TopK
  → 一次综合生成
  → 服务器组装引用
```

源码依据：`KnowledgeAnswerService` 138–265、`ProductHelpService` 162–221、`RetrievalSelection` 17–36。[L3] [L4] [L5]

对“有哪些文件包含灯塔，这个讲了啥”，存在四个具体问题：

1. **没有先执行文件发现。** 整句话用于片段匹配，没有把“原文包含灯塔”作为独立的查找条件。
2. **TopK 的单位不对。** 当前是最终片段数，不是文件数。一个文件的多个片段可能占据多个名额，无法保证列出全部命中文件。
3. **去重会丢文件身份。** 不同文件原文相同，`RetrievalSelection` 会只保留其中一个候选。对减少重复回答有用，对“哪些文件”却可能造成漏列。
4. **生成输入缺少文件元数据。** `answerKnowledge` 发送的是 `evidence_id/text/context_id`，没有 `filename/document_id/display_name`。服务器之后能显示文件名引用卡，不代表模型已按文件名正确完成枚举。[L4] [L5] [L13]

管理列表也不能补上这一步：当前 `ManagementRepository` 的 `q` 匹配文件名和显示名，不搜索正文。[L6]

**判断：这首先是查询任务和数据合同的问题，不能靠把 TopK 调大、阈值调低，或者加一个 Wiki 名称来解决。** “明确包含某词”的命中事实与语义重排分应分开处理；语义分可辅助排序，但不应否定已经核实的字面包含关系。

### 4.2 知识加工停留在单文件，没有形成跨文件积累

我们已经有 `SynopsisLibraryService` 和 `HierarchicalSynopsisService`，能保存概览、主题、术语、时间线与原始依据。这部分应保留，不需要从零再造一套摘要。[L8]

缺少的是长期存在的跨文件对象，例如：

- “青榆灯塔项目”页面及其名称、别名、涉及的资料。
- 同一项目的计划、变更、实施记录之间的关系。
- 相互冲突的预算或日期，以及各自来源、适用版本和待确认状态。
- 主题页每个结论依赖哪些原文版本，哪些来源变了就需要更新。

当前 `topic/term` 是一份摘要中的条目，不是全库统一的实体或概念。目录和手工标签也不具备这些语义。这里的不足是**跨文件知识层缺位**，不是“完全没有摘要或知识加工”。

### 4.3 问答缺少会话和按需补检索能力

后端 `AnswerRequestMapper` 只接收当前 `question` 及兼容字段，没有会话历史、当前文件或前一答案对象。前端 `AnswerSession` 也按一次问答处理。模型客户端明确拒绝 `tool_calls/function_call`，当前生成过程不能自主再读一份文件或补查一个关键词。[L7] [L13] [F3]

因此，既不应期待它可靠理解下一轮单独的“这个讲了啥”，也不应期待第一次召回不足后它会自动换词、查看目录或读完整文件。

可借鉴参考仓库的工具编排，但先开放有限的知识读取工具即可：找文件、检索原文、读指定段落、读有效摘要、读主题页。当前主线不需要一并引入 shell、联网研究或通用智能体平台。

### 4.4 普通问答的诊断信息不够解释失败

当前 trace 保存范围、引用、问题/答案哈希、模型/提示/策略版本和终态原因，但没有完整候选的分数、被过滤原因及各阶段数量。模型客户端已经有协议操作、阶段、完成原因等安全诊断字段，缺口集中在与一次问答关联的检索选择过程。前端把 `no_evidence` 显示成“没有找到足够的原文证据”。[L9] [L13] [F2]

此外，召回测试目前只覆盖文字/OCR，普通问答还合并视频文字，两者不是完全相同的执行链。页面已有说明，但仍容易出现“召回测试能找到，问答却拒答”的认知断裂。[F4]

建议保存并按需展示：检索方式、命中文件数、候选片段数、阈值淘汰数、去重数、最终片段数和所用配置版本。原文不必为了排障全部进入日志。这比继续增加模糊拒答码更有用。

### 4.5 多模态能力较多，统一产品入口仍未完成

当前前端代码声明了综合、文字、原图、音频转录、声音理解及多种视频模式；可用项再由能力配置控制。默认综合问答又明确拒绝查询附件，原图/声音等需要单独路径。[F1] [F3]

这意味着“代码拥有很多多模态模块”和“用户普通提问就能自然利用全部相关材料”仍有距离。合理方向是由问题、资料类型和所需证据决定内部读取方式，技术模式放到高级设置或诊断入口。不能把仅有 ASR/OCR 的答案说成完整音画理解。

### 4.6 引用完整性不能替代答案语义质量

0053/0054 普通问答已经改成一次综合生成。`KnowledgeAnswerService.validate` 检查输出结构和引用 ID 合法性，不再执行旧的手写字段证明与二次模型核验。[L3]

因此当前优势应准确表述为“引用可回到真实原文版本”，不能扩大成“每个结论均已独立证明”。反过来，也不建议恢复对所有自然语言问句进行手写证明的老路线。更需要的是合适的上下文、明确的部分回答/冲突表达和真实质量评测。

### 4.7 已有评测与协作文档，需要对齐当前产品链

项目并非没有评测：根目录已有 acceptance 清单、指标、报告 schema、来源指纹和多模态 runner；Java 也有专门的真实 provider/Milvus 入口与大量确定性测试。[E1] [E2] [E3]

但根评测 runner 的源码绑定范围是旧 Python 实现，不能认证当前 Java 0054。项目已有真实成功和失败个案：0049 曾成功回答中文产品操作问题并回读 PDF 原文/版本/SHA，但属于旧执行链，也未完成混合视频来源验收。0054 的验证记录明确没有完成当前链路真实相关性、阈值标定和综合回答质量验证。[E2] [E4] [L14]

应补的主要是当前链路的文件集合准确性、中文查找、跨文件总结、引用支持程度、更新后旧知识失效等结果。测试数量增加不能替代这些结果。

协作文档也存在历史堆叠：部分 README 仍保留先前登录、未发布或范围选择叙述，而最新变更已覆盖它们。建议让 README/AI_CONTEXT 只保留一份清晰的现行能力说明，历史留在变更目录。这能减少人和 AI 从过时合同推导实现的成本。[L0] [F0] [F5]

## 5. 参考仓库值得借鉴，也有不能照搬的地方

### 5.1 真正值得借鉴的实现

- **知识页是可浏览的产品对象。** 不是只有问答框和隐藏向量库。
- **读取可以分步骤进行。** Agent 根据任务使用不同工具，界面能展示过程；不能据此保证每个问题都能答对。[N2]
- **好答案能显式保存。** Save to Wiki 把答案变成持久页面并维护索引/日志，减少有价值成果停留在一次聊天中的损失。[N5]
- **维护流程进入产品。** 源文件监听、知识检查、审核、历史与增量处理都有落点，不只靠重新导入全部资料。[N1] [N7] [N8]

### 5.2 来源链接不等于我们需要的精确引用

`AgentReference` 主要包含标题、路径、类型、片段、分数和知识上下文；没有与我们相同的文档不可变版本、页内偏移、原件 SHA 和音视频时间定位合同。Agent 最终返回答案和参考列表，也不等于逐句完成事实证明。[N6] [N13]

它确实有 **Faithful / Read Sources Only** 模式，运行时代码会限制可用工具和上下文来源，不能说它“只信 Wiki、不支持原文”。但只使用原文仍不自动保证引用完整、关系正确或回答没有遗漏。[N2]

### 5.3 自动更新已经有，但多来源结论的撤回仍可能不完整

来源监听会把新增/修改送入导入流程，也有删除清理。不能笼统评价为“没有更新和删除支持”。[N7]

更具体的边界是：删除一个来源时，对仍有其他来源的知识页，当前分支会更新 `sources` 列表并保留页面正文；原来仅由被删来源支持的句子不一定逐条被删除。更正来源时，多来源页还会走模型合并。[N8] [N1]

这是源码推导出的残留风险，不是本轮已运行复现的故障。对我们而言，值得补的是结论到原始版本的依赖记录和明确的失效状态，不能仅靠页面上还有若干来源链接就认为全部内容仍有效。

### 5.4 原文搜索和关系图也各有边界

参考仓库的 `source.search` 是原资料关键词扫描并返回片段，有扫描数量和片段长度边界；它与 Wiki 的混合搜索是不同工具，不能直接认为所有文件搜索都具备同一检索能力。Rust Agent 的 `wiki.read_page` 只读 Wiki，不能据此认为 Faithful 模式已经会逐份通读原文件；MCP 另有项目文本文件读取接口。[N3] [N12]

关联图的显式边主要来自页面链接和 `related` 字段；相关性评分另外使用来源重叠、共同邻居及类型亲和等信号。它们适合帮助探索，不能直接解释为事实关系已经验证。[N4] [N14]

### 5.5 质量、成本和移植不能凭 README 下结论

其 README 有召回率提升的数字，本轮没有在相同中文语料、模型和问题集上复跑，不拿该数字证明它优于当前项目。导入、长文分析、合并、维护及回写会产生模型工作量；Wiki 将部分工作提前到导入时，并不必然降低总成本。[N0] [N1]

源码与 README 也存在差异，例如“每次导入自动重写 overview”的描述与当前导入代码的保护逻辑并不完全一致，应按实际路径判断。[N15]

它有源码测试和可选真实模型测试，不能说“没有测试”；本次读取的 CI 主要执行构建，也不能把 CI 徽章当成真实问答质量通过。文件历史功能亦有开关和保留限额，适合编辑恢复，与我们需要的答案原始证据版本不是同一种合同。[N16] [N17]

该仓库是 GPL-3.0 许可，若以后复制源码需要单独核对许可适用条件；本报告推荐先借鉴行为与结构，不将它误称为 MIT 项目。[许可证][N10]

## 6. 建议的演进方向：保留底座，增加两种能力

以下是供审阅的候选方案，不改变现有开发 TODO 或自动启动实施。

### 第一优先：文件发现与正常问答

先让现有资料可靠可找、可问，不以完成 Wiki 为前置条件。

| 用户想做的事 | 建议的执行方式 | 返回内容 |
| --- | --- | --- |
| 哪些文件原文包含“灯塔” | 从当前有效解析内容查包含关系，按文件归类，支持分页/完整数量 | 文件名、命中位置、原文片段；明确字面命中范围 |
| 找与“灯塔”相关的资料 | 混合召回和重排，按文件组织相关结果 | 相关文件及匹配理由；与精确包含结果区分 |
| 这些文件分别讲什么 | 读取各文件当前摘要；需要时读取完整相关章节 | 每份文件独立概括并引用，不拿少数命中句冒充全文总结 |
| 青榆 X1 怎么开启夜间模式 | 以产品/版本为线索查文档与视频文字，按需补读 | 操作步骤、条件、原文页码或视频片段 |

这一步要补来源元数据、文件级身份保留和候选诊断。精确文件集合由服务端查询结果决定；大模型负责解释和综合，不负责猜哪些文件存在。对无法完整处理的集合，明确返回数量和处理范围，而不是把片段 TopK 当成“所有文件”。

### 第二优先：加入最小可用的主题知识页

建议保留三层职责：

| 层 | 保存什么 | 在问答中的作用 |
| --- | --- | --- |
| 原始资料层 | 原文件、版本、SHA、解析内容、页码和时间定位 | 最终事实依据 |
| 知识整理层 | 单文件摘要、主题/实体页面、别名、关联、冲突、来源依赖 | 导航、跨文件概览、减少重复整理 |
| 查询层 | 意图与会话对象、文件/原文/Wiki 检索、必要的补读 | 按用户任务组织答案并回到原始证据 |

先做一个“项目主题页”或“产品知识页”的纵向闭环，不同时建设全领域图谱、任意本体和大型 Agent 平台。

主题页最少需要稳定身份、标题与别名、内容修订、支撑来源版本、待确认冲突、生成模型/提示版本以及当前是否失效。原资料更新或撤下后，先把受影响内容标为待更新，再修订；别让陈旧页面继续伪装成当前知识。

可以继续用现有数据库管理这些身份和依赖，以 Markdown 作为可阅读/导出形式。没有证据表明第一版必须引入 Neo4j 或替换 Milvus。Wiki 页面可以帮助定位原文，但不应把上一轮生成的总结当成新的独立事实来源。

### 第三优先：按需工具检索与知识回存

在统一问答入口增加有限的读取动作：搜索文件、搜索片段、读取文件/摘要、读取知识页、沿关联定位来源。支持“刚才那份说明书”等会话对象；保留标准协议适配，不因换模型重新写业务流程。

有价值的回答可由用户选择保存成知识页草稿，再关联原始依据。保存和再编译的成本应清楚可见；不把普通聊天自动转成可信知识。

这一步可复用当前 Controller / Service / Repository / Model / client 分层。优先新增清晰的小业务入口，不做新一轮全库目录重构；是否提取共享检索接口，取决于确实出现了多个调用方及相同语义。

## 7. 用什么标准判断这次借鉴有价值

建议先固定下面六类任务，再比较“当前实现”“文件发现修复版”“增加知识层版”。同一批语料、同一模型配置，分别记录结果与模型请求成本。

| 验收问题 | 主要检查内容 |
| --- | --- |
| 哪些文件包含灯塔，各自讲什么？ | 文件集合漏列/误列、字面与语义匹配区分、每份文件概括是否有据 |
| 同一段内容在两份文件中都存在 | 去重后仍能列出两份文件；回答正文可以合并，来源身份不能丢 |
| 项目名称有简称/英文名/同名不同项目 | 别名是否有依据，是否错误合并不同项目；不能靠模型猜等价关系 |
| 新版说明书改了一个步骤 | 原件版本正确，旧摘要/主题页被识别为过期，答案使用新步骤 |
| 删除支持某结论的唯一来源 | 该结论撤回或标记无有效依据，即使知识页还引用其他文件也不能继续支持它 |
| 上一轮找到资料后追问“这个讲了啥” | 会话对象明确，读取正确文件，必要时说明歧义；不只靠句子相似度 |

指标至少区分文件级召回/精度、片段相关性、回答正确性、引用支持程度、更新失效正确性、拒答是否合理、时延和实际调用量。现有 Java 来源/生命周期测试、旧 acceptance 语料可以复用，但必须绑定到新的实际执行链，不能直接继承旧报告的通过状态。

当前最值得先做的不是图谱界面，而是第一行这个用户已经遇到的问题。它通过后，再验证知识页是否确实改善跨文件理解和重复查询成本。

## 源码索引

以下本地链接对应研究时工作树；行号供定位，后续修改可能移动。外部链接固定到本次核对的提交。

| 编号 | 文件与定位 | 支持的主要判断 |
| --- | --- | --- |
| L3 | KnowledgeAnswerService，138–304 | 当前普通问答编排、快照、空结果、输出结构验证 |
| L4 | ProductHelpService，162–221、303起 | 文档/视频文字两路、原问题检索、合并选择 |
| L5 | RetrievalSelection，17–36 | 阈值、按原文去重、片段 TopK |
| L6 | ManagementRepository，191–200 | 列表查询只匹配文件名/显示名 |
| L7 | AnswerRequestMapper，15–33 | 当前问句请求形状、无会话字段 |
| L8 | SynopsisLibraryService，87–155、367–381；HierarchicalSynopsisService，43–150 | 单文件摘要及当前版本失效、分层处理 |
| L9 | KnowledgeAnswerRepository，20–61 | trace 内容及候选诊断缺口 |
| L10 | KnowledgeCitation，30–57 | 服务器来源身份与页码/时间映射 |
| L13 | OpenAiCompatibleModels，461–508、734–739 | 生成材料缺文件名、当前不支持 tool calls |
| F1/F2/F3/F4 | index.html 110；app.js 1305–1350；answers.mjs 263–300；app.js 360–382 | 多模式、拒答展示、单请求与附件边界、召回测试差异 |

[K]: https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f/ac46de1ad27f92b28ac95459c782c07f6b8c964a
[N0]: https://github.com/nashsu/llm_wiki/tree/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b
[N1]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src/lib/ingest.ts
[N2]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src-tauri/src/agent/runtime.rs
[N3]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src-tauri/src/agent/tools.rs#L2520
[N4]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src/lib/graph-relevance.ts
[N5]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src/components/chat/chat-message.tsx#L547
[N6]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src-tauri/src/agent/types.rs#L182
[N7]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src/lib/project-file-sync.ts#L243
[N8]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src/lib/source-lifecycle.ts#L533
[N9]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src-tauri/src/commands/extract_images.rs
[N10]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/LICENSE
[N11]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src-tauri/src/agent/session.rs#L40
[N12]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/mcp-server/src/index.ts#L81
[N13]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src-tauri/src/agent/runtime.rs#L1584
[N14]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src/lib/wiki-graph.ts#L266
[N15]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src/lib/ingest.ts#L2053
[N16]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/.github/workflows/ci.yml#L44
[N17]: https://github.com/nashsu/llm_wiki/blob/48fd970e206a02a6d2028d1dbfc41b7a0345bf0b/src-tauri/src/commands/file_history.rs#L9
[L0]: ../changes/0054-retrieval-settings/spec.md
[L1]: ../../src/main/java/com/evidence/rag/service/IngestionService.java
[L2]: ../../src/main/java/com/evidence/rag/service/IndexingService.java
[L3]: ../../src/main/java/com/evidence/rag/service/KnowledgeAnswerService.java
[L4]: ../../src/main/java/com/evidence/rag/service/ProductHelpService.java
[L5]: ../../src/main/java/com/evidence/rag/tool/retrieval/RetrievalSelection.java
[L6]: ../../src/main/java/com/evidence/rag/repository/ManagementRepository.java
[L7]: ../../src/main/java/com/evidence/rag/web/converter/AnswerRequestMapper.java
[L8]: ../../src/main/java/com/evidence/rag/service/SynopsisLibraryService.java
[L9]: ../../src/main/java/com/evidence/rag/repository/KnowledgeAnswerRepository.java
[L10]: ../../src/main/java/com/evidence/rag/model/dto/KnowledgeCitation.java
[L11]: ../../src/main/java/com/evidence/rag/repository/CleanupPayloadTables.java
[L12]: ../changes/0054-retrieval-settings/REVIEW.md
[L13]: ../../src/main/java/com/evidence/rag/client/model/OpenAiCompatibleModels.java
[L14]: ../changes/0054-retrieval-settings/verification.md
[F0]: ../../../ai-knowledge-web/docs/changes/0040-retrieval-settings/spec.md
[F1]: ../../../ai-knowledge-web/public/index.html
[F2]: ../../../ai-knowledge-web/public/app.js
[F3]: ../../../ai-knowledge-web/public/answers.mjs
[F4]: ../../../ai-knowledge-web/public/app.js
[F5]: ../../../ai-knowledge-web/README.md
[E1]: ../../../../evals/acceptance.yaml
[E2]: ../../../../scripts/validate_acceptance.py
[E3]: ../../src/test/java/com/evidence/rag/service/AudioVideoProviderEvaluation.java
[E4]: ../changes/0049-unified-knowledge-answers/provider-run.md
