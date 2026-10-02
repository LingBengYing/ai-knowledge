# 文件摘要（0015，本机后端）

0016 新增第八类原始材料 `VIDEO_SUBTITLE`：完整枚举所有非空字幕 cue，包含长文件末尾；来源标记为 `video_subtitle/embedded_subtitle/subtitle_cue`，回读原视频与真实时间区间，不伪造 ASR、原帧或画面文字。v15 迁移精确保留旧摘要历史，并扩展字幕外键；旧七类输入的指纹编码不变。见[字幕合同](VIDEO_SUBTITLES.md)及[后端验证](changes/0016-subtitle-tracks/library-verification.md)。下文 v13/v14 描述为保留的摘要基础合同。

Java 后端为已索引的文档、图片、音频和视频提供独立摘要任务、持久结果和来源回读。默认关闭，仅 local/dev/test 合同；前端列表没有摘要按钮，`GET /v1/documents` 不包含摘要，生产 gate 仍不放行。本轮完整长文件分层的冻结证据见[0015验证记录](changes/0015-file-synopsis/hierarchy-verification.md)；真实模型质量尚未验收，不能把本机后端闭环称为完整多模态生产版本。

## 使用链路

| 操作 | HTTP |
| --- | --- |
| 显式生成一次 | `POST /v1/documents/{documentId}/synopsis`，无 body/query，返回202任务 |
| 轮询进度 | `GET /v1/synopsis-tasks/{taskId}` |
| 查看当前文件摘要 | `GET /v1/documents/{documentId}/synopsis` |
| 回读一条原始来源 | `GET /v1/synopsis-sources/{synopsisId}/{entryOrdinal}/{sourceOrdinal}` |
| 原文件／原视频帧 | 来源 URL 后缀 `/content`、视频帧或帧 OCR 的 `/frame` |

公开序号从1开始，使用响应返回的链接，不由模型或客户端拼页码。来源按类型返回页面 Unicode code-point 区间、整图/相交词框、真实音视频微秒区间、封存原帧；`/content` 与 `/frame` 支持原有单 byte Range，始终先授权后解释 Range。摘要概览、主题、术语和时间线均有实际来源引用。摘要不是问答的原始事实证据。

状态为 `queued → processing → available | unavailable | cancelled`。同 publication/model/policy 的排队、处理中或成功结果重复提交复用任务；不可用后只能用户再次显式提交，没有自动付费重试。生成需要当前编辑权，已保存结果向当前有读取权的用户共享；不采用原提问者私有 trace 的限制。撤权、版本或模型/策略变化会阻止旧内容回读，读取和服务重启不会重新请求模型。

SQLite v13 追加独立 task/input manifest/entry/reference 四表；v14仅将完整输入上限扩大至4096、ordinal至4095，事务内重建并精确保留旧四表身份、结果、状态、指纹、关系与时间，升级前备份，旧 v1–v13 迁移体不变。完整六类物理证据先枚举、校验身份和数量，再取原材料；不用向量 top-k 代替完整文件。最终在同一 authority 事务重新验证当前权限/版本、完整输入指纹和所有引用，再一次封存结果。失败没有部分摘要，文件索引保持可用。启动恢复把遗留 processing 置为 `unavailable/worker_interrupted`，功能关闭时也执行且不依赖模型。

工作区至多32个 pending 任务，后台一次执行一个。超过下述完整输入上限则请求明确返回 `input_capacity_exceeded`；已排队任务在装配输入时发现超限则为 `unavailable/input_capacity_exceeded`，不截取前几段伪装成功。模型/版本切换期间旧 pending 任务尚未结束时，新建可能冲突；待旧任务安全终止后再显式提交，此交互优化列入 backlog。

## 运行配置

`rag.synopsis.enabled=false` 为默认值；显式开启需要独立的 `base-url`、`model`、`api-key`。密钥只从服务端环境变量（如 `RAG_SYNOPSIS_API_KEY`）或私有配置读取，不能写入代码、公共命令示例或 Git。其余配置为 `deadline-ms`（默认30000）、`max-response-bytes`（默认262144）、`budget-ms`（默认600000）、`allow-loopback-http`（默认false）。只在本机协议替身验证时允许 loopback HTTP；普通 endpoint 要求 HTTPS。

不隐式启用 answers、embedding、rerank、Milvus 或摄取。runtime 仅在消费者开启时声明 `file_synopsis`、`synopsis_sources`，不等于生产 ready。真实云请求仍须单独授权。

## 内部生成 Module

```java
new SynopsisService(models, totalBudget).generate(input, current);
```

`input` 必须由调用方从单一已发布文件的完整证据集合装配。`PublicationVersion` 绑定源 revision、编译器、原文件 SHA、publication、projection generation、manifest 与 index target。`SynopsisInput` 校验条数等于 publication 的数量，拒绝重复 ID，并计算有序、长度前缀的完整输入指纹；这不代表它可以替代数据库授权或实际完整枚举。

内容使用 typed `Text` / `Image`：原文、图片 OCR、音频转录、视频转录/OCR/内嵌字幕保留文字；原图/原帧保留原字节，不接受 caption 作为视觉事实材料。音视频引用携带服务器给定的微秒区间。短文件仍使用原上限：64 条证据、64000 Unicode code points、8 张图、合计 8 MiB 原图。超过短模式上限的完整文件自动选择下面的分层模式；旧模型协议、policy和短结果兼容性不变。

生成结果包含概览、主题、术语、媒体时间线。每条 1–8 个来源、最多 1024 code points，整份至多 32 条；每条核验只传该条引用的原证据。模型必须确认整个条目被这些证据共同支持，并确认每个引用有贡献。任一失败则整份 unavailable，entries 为空，没有部分成功。

时间线的 interval 是来源时间区间的最小包络，用于导航，不表示区间内每一时刻均提供证明；每个 reference 仍保留自己的准确区间。模型不能生成 source URL、页码、坐标或时间。结果不保存原图字节，保留来源 ID/内容 SHA/kind/time、输入指纹、publication、model/prompt 指纹和 policy revision。

## 模型协议

`SynopsisModels` 只有 draft、逐条 verify、revision 三个方法；生产 `OpenAiCompatibleSynopsisModels` 与测试替身组成实际 Seam。新 Adapter 复用已有有界 HTTP transport，使用标准 `chat/completions` 与图片 data URL、严格 JSON、禁止重定向/工具调用/输出截断，无自动重试。旧 TextModels/VisionModels 接口与 revision 不变。

构造和读取 revision 不调用网络。endpoint/model/两条完整 prompt 参与独立版本指纹，密钥不参与、不输出。配置由独立 Spring 装配传入，没有默认云请求。全部本机验证只使用合成文件与 loopback 模型替身。

Service 每次外部调用前后复验 current、冻结模型版本、中断和总预算，最后一次成功也复验。它不持有数据库事务、执行器或权限缓存；调用方仍须负责有界任务准入、线程中断、当前授权与最终事务保存。总预算是协作停止边界，不能强杀不响应中断的 Adapter，也不撤回已经发出的上游请求。短模式最多一轮 draft 和32次逐条 verify，失败即停。

## 完整长文件分层

`SynopsisMaterialRepository.document` 装配单一真实 publication 的完整 `SynopsisFileInput`：至多4096条、250万Unicode code points、128张图和32MiB原图。保留完整文件而非top-k；v1原始指纹编码不变并缓存计算结果。当前按已有文件上限完整物化，不引入新的分页/流处理框架。

`HierarchicalSynopsisService.generate(file, current)` 使用独立 `HierarchicalSynopsisModels` Seam，生产 Adapter 与原短模式共用显式配置，但拥有独立版本和transport。每批至多64条、64000CP、8张图/10MiB原字节，16MiB传输上限；10MiB覆盖现有合法单图，不能缩图后称为原图。每批保持真实父publication、全文件指纹和全局连续ordinal，不伪造“子文件publication”。

1. 连续完整分批，每批原材料生成至多16条候选，文件末尾同样参与。
2. 每次合并至多3个相邻候选节点，中间仍至多16条；持续缩减直到最终至多32条。节点是独立派生类型，只保留原始来源ID的继承关系，不冒充事实证据。
3. 对最终每条仅使用它实际引用的原文字/原像素核验，所有引用必须共同在单个有界请求内证明；超限不能拆开后把通过结果拼成支持。
4. 用每一个原始批次独立复查全部最终条目，检查完整性、末尾条件、否定和冲突；所有条目索引必须明确覆盖。中间候选遗漏的条件仍可否决最终结果。
5. 所有调用、完整复查和最终authority事务通过后，才一次保存结果及完整叶级来源清单。任一失败、超时、版本或授权变化都不发布部分摘要，不自动重试。

长模式policy为 `java-file-synopsis-v2-hierarchy-original-review`；模型指纹包含endpoint/model/四条完整prompt，不含key。leaf/reduce/review/verify均使用标准 `chat/completions` 严格JSON；原始材料和派生文字均是低信任user数据。最终来源HTTP、原文件/原帧Range、持久任务和重启读取与短模式相同，不增加第二套任务或来源系统。

结构性“完整遍历和全部原批复查”由程序与本机测试保证，语义完整性仍依赖模型判断，不能据此宣称已经证明真实长文件摘要准确率。

## 不能推定的能力

- 摘要不写入原事实 evidence，也没有接进 AnswerService；不能据此回答事实问题。
- 模型自评布尔值不是已校准的准确率。真实生成/视觉/转录质量及代表性长文件仍需单独评测。
- 音视频时间标记为已封存分段或帧区间，不宣称词级对齐；OCR仅已有选中帧，不等于完整字幕轨。
- 前端、真实模型质量及生产发布仍未完成；摘要不改变原索引或问答模式。长文件本机后端验收与真实质量评测分开记录。

执行入口：[0015 plan](changes/0015-file-synopsis/plan.md)，行为：[spec](changes/0015-file-synopsis/spec.md)。
