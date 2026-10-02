# 执行计划

上一目标回合分类：progress。0016已改变实现状态并完成真实字幕后端验收；本轮开工逐一重算其567个输入，全部未变，不重复未变的全量门禁。

## 架构决策

- 沿用Java21/Spring layer-first、SQLite、既有REST snake_case和统一错误；不使用skill默认feature-first、不增加ORM或服务框架。
- 使用codebase-design的小Interface/深Module；QueryRankingModels确有生产HTTP和测试Adapter，编译编排使用现有具体Service，无空接口/Impl。
- 文字问题与检索提示分离，查询图与事实图分离；对原完整问题的证明、ACL和服务器引用不改。先做无authority写入的输入/匹配，然后沿现有答案生命周期接线。
- 现有认证和mutation检查复用，任务方式沿已有有界答案执行器；本机验证使用独立临时目录、合成媒体和loopback协议替身，不访问旧服务或云模型。
- 不以文件数量/覆盖异常替代正常闭环；只有复现的泄露、数据损坏或破坏证据底线问题可插队。

## 具名主线与步骤

输入：原文字问题、最多3个图片/音频/视频附件、完整文档范围。输出：附件处理说明、有据答案/拒答及库内typed引用。

1. 请求期附件编译：明确不可变QueryAttachment/PreparedQuery/manifest，复用图像/音视频编译；真实PNG/WAV/带字幕MP4保留完整查询线索与代表原帧。
2. 双角色匹配：QueryRankingModels标准多图通信，严格完整index/score；query和candidate角色在实际请求中可核验，单图证明客户端原字节不改。
3. 授权接线：在EvidenceScope之后同budget编译；仅embed/search/rerank使用prepared query，原问题和旧证明不变；独立hash-only trace与重启读取。
4. HTTP：新增opt-in有界附件入口，不复用资料上传、不破坏旧JSON；真实端到端闭环。
5. 最终冻结：直接相关红绿、必要native/loopback验收、完整Java/格式/双80%/Node，精确旧用例身份与多重性、输入/报告/JAR绑定、独立Standards/Spec复核。

Root负责工件、Interface协调、Maven、native与最终证据；root是唯一Maven执行者且一次一个。并行职责先明确ownership，不回退他人工作；先可编译RED后实现，禁止删除/跳过/放宽测试。代码冻结后worker只报告建议，不追加改动使验证失去源码绑定。

## 开工时确认的接线位置（历史核对）

- `AnswerService.execute` / `VisualAnswerService.execute` 已在propose之前取得完整EvidenceScope。附件准备应置于此处、现有有界执行器内；不能在Controller、scope之前或资料摄取Service内调用模型。
- `AnswerService.propose`、`VisualAnswerService.propose`、`VideoAnswerProposalService.propose` 的embed/search使用retrievalText。候选经原hydrate/current校验后，实际库内原图/帧才可与queryImages交给QueryRankingModels；纯文本旧路径保持既有rerank。
- extract、TextGrounding、VisualAssessment、VideoAssessment及FactPlan继续接收originalQuestion和库内材料。新客户端只返回排序，不替代以上证明；caption/OCR/ASR不能被拼成新增Evidence。
- `TraceDraft`当前无附件字段。后续增加独立hash-only附件记录及追加迁移，经现有EvidenceService.finish事务存储；不能把含bytes/原文的PreparedQuery直接放入trace。原问题SHA与来源回读路径不变。
- 四个JSON答案Controller与默认关闭配置不变；新显式opt-in入口自行落实总请求接收上限。不能开启全局multipart或复用会持久化资料的UploadServlet来冒充临时附件入口。

这些是对现有调用点的只读核对，不构成步骤3–4验收。步骤1–2完成后直接推进此接线，不重做已冻结输入/模型通信诊断。

## 步骤1–2历史交付

步骤1–2已于2026-09-20完成[本机冻结](verification.md)：完整1619 Java、552格式、双80%和73 Node；单列native20。12个新增文件，旧567构建输入原字节不变；没有改动共享证明/授权/schema/旧协议。没有扩展异常、权限或重做此前业务链；必要C1兼容修复只改新产物校验且有先失败回归。

上一步冻结时步骤3–4未实施。本轮直接完成下述授权答案、trace和HTTP接线，没有重做内部编译/排序诊断；完整整体目标不变。

## 后置

### 步骤3–4执行增量（2026-09-20）

上一轮分类progress：579个冻结输入逐一复核未变，直接开展授权问答与HTTP，不重做输入协议。root负责共享QueryAttachmentService、AnswerService、DTO和EvidenceService终态保留及串行Maven；并行worker分别负责Visual/Video接线、追加v16 hash-only trace、独立有界HTTP/config。各自ownership独立，禁止回退其他改动。

当前投影单次查询上限4096 UTF-8字节，小于完整8192 CP材料；按Unicode完整边界切成有界批次，全部检索/验证候选后稳定合并至64，纯空白分隔批无检索信号可略过，不截断任何非空白线索。匹配全部候选按20分批，不只处理前20。完整原问题、库内证明与完整scope保持。追加trace只保存请求哈希/编译版本/manifest，不保存原文；新入口使用独立28 MiB JSON接收器，总解码20 MiB，默认关闭，不启用全局multipart。新响应为mode/result/query_attachments包络，旧typed结果不变。

17:01:33实际JDK21编译成功后的第一批RED23项，10 failure/10 error/0 skip，覆盖Domain/迁移/Repository、Visual/Video、mapper/config尚未实现合同；不是构建失败。隔离构建`/private/tmp/java-query-answers.4MDTg2`，root唯一Maven。步骤3–4仍在实现，未宣称附件HTTP可用。

17:14:56实际定向90项（含新native1项）通过，三类真实媒体HTTP正常链、库内证明与重启来源回读已接通。最终默认门禁、旧用例多重性及制品独立核对见[answers-verification](answers-verification.md)，步骤1–2验证/指纹保持历史原件，不被新切覆盖。

步骤3–4最终于17:33:40完整1680 Java/577格式/双80%和73 Node通过，17:35:00单列native21通过；604输入、旧1619/20用例身份多重性和JAR443 class经独立核对，未闭合项0。本轮分类progress，正常后端闭环已交付，不以此结束完整生产目标。

下一主线收敛为全套配置的实际装配和真实provider/Milvus质量验收，再按授权接前端与生产。当前轮不扩展权限、清理、格式种类或长查询压缩；云请求必须新增授权，前端和部署边界不因后端通过自动解除。

无文字语音问题、额外图像格式、长查询材料的完整压缩/分层、字幕选择UI、视觉/声音向量、真实provider质量、网页与生产按后续主线推进。正常文字+附件流程不能被这些分支阻塞；当前容量外明确不可用，不冒充处理成功。
