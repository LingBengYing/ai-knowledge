# 视频逐事实证明 Module

这是 0014 步骤3的内部证明能力。现在由 [VIDEO_ANSWERS](VIDEO_ANSWERS.md) 的完整授权检索、不可变视频 trace、typed 来源与原视频 Range 链消费；本 Module 的成功结果仍不能绕过授权提交直接发布。视频上传和完整索引见 [VIDEO_PUBLICATION](VIDEO_PUBLICATION.md)。

## Interface

`VideoAssessmentService.assess(question, revisionId, compilation, groupId, mode, current)` 接收完整问题、完整视频编译结果和其中一个真实时间组。模式为 `VISUAL`、`TRANSCRIPT` 或 `JOINT`。调用方提供完整 scope、ACL、active、配置与预算资格检查；每次外部上下文发送前后以及返回前再次调用。新增 `assess(question, VideoProofInput, mode, current)` 供授权消费者使用，只传选中原帧和完整转录，避免读取所有帧 BLOB。`VideoProofInput` 本身只检查可见字段的不变量；真实父源、物理发布身份、span 时间与交集由 `PublishedVideoEvidence` 和权威 Repository 验证。配置与公开路由见 VIDEO_ANSWERS，不隐式启用其他模态能力。

流程为：

1. 从 compilation 重建确定性的真实 group，绑定 parent source SHA 与完整 manifest；不按相近文字猜成员。
2. 规划完整问题，事实 ID 绑定整问题摘要、序号和 requirement。两种模态使用相同 ID，不能 join 旧视觉 claim SHA 和文字 requirement SHA。
3. 每个事实单独取原帧提案、独立原帧验证和同组转录摘录/确定性验证。caption 完全不进入证明；转录支持只能来自当前 span 的精确摘录。
4. 完整转录上下文可以否决原帧结论或摘录，包括组外明确反证。模型拒绝摘录不能隐藏反证；未摘录文本不能贡献支持。
5. 全部事实覆盖才成功；联合模式还要求原帧和转录各自至少贡献一个事实，可以共同支持同一事实。任何失败清空全部成功 proof，保留安全拒绝原因。

`VideoAssessment` 保存 group、source/manifest/question 摘要、全部事实 ID、分模态 proof 和模型/证明 policy 版本，不携带问题原文。原帧 claim 与转录 quote 是瞬态返回材料；其 `toString()` 脱敏。支持值为离散 `0/1`，不是校准置信概率，也不是召回或重排分数。

本 Module 不承担 publication：转录 `GroundedQuote.physicalId` 在这里是内部候选句柄，绑定当前 `group.transcriptSpanId`，**不是 Milvus physical segment ID**。授权消费者已通过 publication 台账重新映射和物化，不能把这里的句柄直接写入 trace。CP 定位基于按 ordinal、既有换行规则连接的完整转录（前置空段不产生前导换行）；不声称词级时间。

## 模型协议与旧行为

新增 `FactTextModels` 和 `FactVisionModels` 两个真实 Seam，生产由已有 OpenAI-compatible Adapter 实现，测试使用明确替身。逐事实请求保留完整问题及 `target_fact`，system prompt 明确只证明目标事实；不把目标事实包装到旧整问题 prompt 中。HTTP、严格响应验证、资源关闭和无自动重试规则共用原 Implementation。

旧 `TextModels`、`VisionModels`、`TextGrounding.verify/verifyText` 和 `VisualAssessmentService` 的整问题覆盖语义不变。旧模型 revision 不变；新 fact prompt revision 与 planner/text-grounding version 绑定到独立视频 policy。所有原模型配置仍由调用方显式构造，本 Module 不读取密钥或环境变量。

## 明确的支持范围

当前使用既有确定性字段、颜色、布尔与操作步骤语法，完整问题上限 4096 UTF-8 字节、最多8个事实；超限整体拒绝而非截断。不提供通用自然语言拆题或代词消解。

可验证示例包括“指示灯的颜色是什么？重启等待时间是多少秒？”和“What is the indicator color? What is the wait time?”。无法保留共享限定的多事实条件/时间/逗号上下文，以及带所属关系的共享颜色句式明确拒绝；不会静默丢条件。双模态同值比较是确定性比较，不推测同义表达。这些限制不替代完整视频语义和真实模型质量评测。

## 验证边界

`VideoAssessmentNativeIT` 实际生成蓝色指示块视频和合成音轨，用 FFmpeg 解码，经标准 ASR/视觉/文字 **本机协议替身** 完成同组双事实证明。它检查原帧字节和像素、真实时间交集、共同事实身份和 CP 回读，并故意使用红色/99秒的错误 caption 验证召回文本不作证明。

上述独立 Module 验证不是云 ASR/VLM 质量、授权问答 HTTP、网页或生产验收；没有新增云请求。历史结果见 [步骤3证明记录](changes/0014-video-library/assessment-verification.md)。当前授权问答另由 `VideoAnswersNativeIT` 验证，不能混用两次验收的范围；不重复已完成的解码和入库诊断。
