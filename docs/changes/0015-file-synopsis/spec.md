# 0015 行为规格

## 正常路径

已编译且发布的单文件完整证据 → 生成概览/主题/术语/媒体时间线 → 每条独立用其列出的原始证据核验 → 保存版本化摘要 → 授权回读原证据。摘要失败独立于索引，不撤销已有 publication。

## 第一步：必要的生成 Module（当前实施范围）

- `SynopsisInput` 绑定一个 PublicationVersion、全部有序 physical evidence ID 和原始文字/原图。数量必须等于 publication.segmentCount，ID 不重复；输入内容来自后续 authority 接线，不允许用 caption 冒充图片、用向量 top-k 代替全文件。Module 本身不认证权限或数据库来源。
- 首切有界输入最多 64 证据、合计 64000 Unicode code points、8 张原图和 8 MiB 原图字节。超限显式拒绝，绝不截断/取前几段后声称完整文件摘要。完整长媒体的分批/分层处理仍须随后实现。
- 每项证据有明确 kind、文字或原图且二者恰一项，以及可选的服务器时间区间（微秒）。图片不使用 caption。来源摘要由服务器根据证据内容及版本计算，时间不让模型生成。
- `SynopsisModels` 是真实 OpenAI-compatible Adapter 与测试替身的 Seam：`draft(input)` 返回有来源 ID 的候选条目；`verify(statement, citedEvidence)` 仅看到该条实际引用的原证据；`revision()` 包含 endpoint/model/prompt 身份而不含密钥。
- 候选最多 32 项，恰一条 OVERVIEW，至少一条 TOPIC、一条 TERM。有时间证据的文件至少一条 TIMELINE；无时间证据不得虚构时间线。每项最多 1024 code points、1–8 个不同原证据 ID；TIMELINE 只能引用有时间的证据。每项来源必须存在于本输入，禁止模型指定 URL/页码/坐标/时间等定位字段。
- Service 在每次外部请求前后检查 current、总预算、中断及冻结模型版本；任何条目未通过、拒绝、协议故障、预算或版本变化均整体 unavailable，绝不发布部分成功。单次 HTTP 仍受旧有界 transport 约束，无自动重试；本 Module 的总预算是协作停止边界，不承诺强杀不响应中断的 Adapter。
- 模型必须逐条评估完整条目是否被所引用证据共同支持，且每个引用确实贡献支持。输入是低信任数据，不执行其中指令。通过模型核验不等于真实摘要质量已验收。
- `FileSynopsis` 保留有序条目、实际引用与服务器区间、input fingerprint、publication、model/prompt/policy revision；失败结果不携带任何生成条目。
- 不修改旧 TextModels/VisionModels 协议、revision 或 AnswerService；摘要不会成为事实答案来源。此步不新增配置开关、runtime capability、数据库迁移或公开 HTTP。

## 后续同一主线（未实现）

完整 authority 枚举与分批摘要；短事务领取/远程生成/复验提交的持久摘要任务；模型、编译器、prompt、source revision 变化失效重建；GET 文档摘要及专用来源回读；重新授权、删除与 active 变化时失效。不得伪造 answered query trace 复用答案来源。

## 第二步：文件摘要持久后端（当前实施）

正常用户流程：已索引文档/图片/音频/视频 → `POST /v1/documents/{documentId}/synopsis` → 202任务回执 → 轮询 `GET /v1/synopsis-tasks/{taskId}` → `GET /v1/documents/{documentId}/synopsis` → 条目来源 `GET /v1/synopsis-sources/{synopsisId}/{entryOrdinal}/{sourceOrdinal}` 及 `/content`、视频 `/frame`。JSON沿用snake_case；任务/摘要/来源均不暴露claim、key或服务器路径。

- 原发布物完整有序枚举六类physical证据，校验总数/唯一性/父revision；不查询向量、不截top-k。原文只支持它实际覆盖的文字/转录/帧OCR区间，不能拿完整转录中的远处事实给当前span时间背书。图片必须回读原字节，caption不作证明。
- 仍保持第一步明确的生成限额，完整枚举后超限任务标 unavailable/input_capacity_exceeded，文件保持indexed。长文件分层不是此限额的替代承诺，仍须后续实现；不得静默截短或伪造subset publication。
- 新v13附表保存独立摘要任务、条目与来源。`queued → processing → available | unavailable | cancelled`，一个任务为一次显式执行；没有自动模型重试。重复创建同一当前publication/model/policy的queued/processing/available任务复用回执；终态失败可由用户再次POST新任务。重启将遗留processing变为unavailable/worker_interrupted，保留历史，不影响索引。
- 创建/领取/远程请求前后/提交复验创建者当前编辑权限与完整publication；最终在原Store同一短事务重新物化输入与引用、验证指纹和model/policy后封存，失败不发布任何条目。模型调用不持数据库锁。最多一个执行任务，持久队列有界。
- 摘要是文档级派生物，当前有读权限者可查看已完成摘要，不套用原问答者私有trace；每次摘要/来源/原字节回读重新验证当前ACL、active publication、源SHA、model/policy。版本失效时不返回旧正文；改名/目录/手工标签不触发生成。
- 来源使用服务器typed locator：文本页/CP，图片原图/OCR框，音频span真实时间，视频原帧显示时间/转录span/帧OCR框；原文件和帧回读先授权及SHA校验，再按既有单byte Range合同响应。时间区间不是词级对齐，视频选帧不是全帧字幕。
- 独立 `rag.synopsis.enabled` 默认false，仅现有development/test字面loopback；模型配置独立，不因摘要开启而开启answers、embedding/rerank/Milvus或摄取。不放开production/readiness；不修改前端和旧服务数据，不调用真实云模型。

验收使用真实SQLite/Spring HTTP及本机模型协议替身，四类publication生成保存/重启读回/typed来源回读，失败不影响indexed，当前授权或publication变化拒绝旧来源；旧1400用例及门禁保留。真实语义质量、完整长文件、前端和生产未由此认证。

## 验收

## 第三步：完整长文件分层（当前实施）

沿用第二步所有HTTP与授权合同，解除“完整文件必须一次装入模型”的限制。旧SynopsisInput/短文件模型协议、revision与策略仍作为有效短文件执行模式保留，不改变其已验证语义；长文件采用独立模型/策略身份。既有文件原合同有上限，不声称任意大小。

- 完整不可变SynopsisFileInput保留真实publication与全部有序原材料，最多4096证据、250万CP、128原图/原帧、32MiB图像字节。指纹严格复用v1长度前缀原始内容编码并缓存，短文件指纹不变。当前一次执行一个任务，最大图像内存按已封存视频32MiB原合同有界；不先引入新的流式存储/任务框架。
- 新SynopsisBatch是显式子批次，仍携带真实父publication及完整文件指纹/全局序号，不伪造segmentCount。连续贪心分批，恰好覆盖全部输入一次；每批最多64证据、64000CP、8图、10MiB图像，覆盖现有单图10MiB合同。单HTTP仍16MiB请求上限，原图不缩放/替换。
- 每批生成未证明的叶候选；最多16条×1024CP。DerivedSynopsisNode明确是派生候选，不是SynopsisEvidence；服务器赋节点ID和完整后代序号范围。每次合并1–3个连续节点，非最终层必须严格收敛；每个节点都参加下一层，不截首尾。输出引用只能来自所输入候选的原physical ID lineage。
- 最终候选仍最多32项、每项最多8原引用，并有原有必需section。先逐条用实际引用的原材料验证完整支持及全部引用贡献；引用集合本身必须在单次有界原始验证请求内，不能拆组OR支持。
- 再对每一批完整原材料复查全部最终候选：逐项兼容性（反证、遗漏否定/必要条件、跨批不确定一律不通过）及重要内容完整性。必须收齐每个候选index和全部原批次，尾部被中间摘要省略仍能否决最终结果；任一失败/预算中断整体unavailable，不能保存前面已通过条目。此结构证明遍历/核验合同，不将模型自评当自然语言准确率证明。
- 模型调用不持Store锁，每次前后校验current、模型身份、中断和总预算。没有自动重试、没有云调用；同一任务使用完整指纹并最终事务重物化原材料与所有引用。
- SQLite v14只扩充task.input_count到4096、input.ordinal到4095；原子重建四张相关表保留全部旧关系/状态/指纹/时间，保持旧v1–v13迁移体。最终条目/引用数量不放宽。
- 根据完整文件是否符合原短文件限额选择短/长执行模式；两种当前模式各自精确校验model/policy。旧短文件结果继续按原模式读取；不把旧模型策略当新分层策略放行。前端、旧服务/数据、Git写操作、生产/真实云质量范围不变。

验收：真实长文本超过64段且引用最后叶、文字总量超64000CP、视频超8帧且包含最后帧/转录/OCR；三级及以上合并不丢任何叶；尾部反证/限定被叶摘要故意省略仍在最终全文复查被拒绝；真实Spring/SQLite/loopback模型从POST到持久摘要/原始来源/重启读回；v13旧结果精确保留。旧1429用例身份及双80%门禁不变。

## 第一步历史验收

四类文件原始证据的候选生成与逐条核验；混合视频画面/转录/OCR；来源贡献、Unicode、内容/版本绑定；非法/未知/重复引用拒绝；完整输入与时间线，不静默截尾；模型拒绝/失败/变更和 current/预算失效。真实 loopback OpenAI chat JSON 与 image data URL 验证，云调用 0。保留旧 1352 用例及所有门禁；不把 Module 测试说成用户已能生成文件摘要。
