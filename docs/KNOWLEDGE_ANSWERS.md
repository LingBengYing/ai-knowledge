# 知识问答：文档与视频文字证据综合回答

这是普通知识问答的一条回答路径，不是独立“产品使用帮助”功能。产品名称、型号、操作问题直接在问答页输入；也可以在资料列表完整选择相关说明书和视频后提问。目录和标签帮助选择范围，但不能当作原文事实证据。

## 回答流程

授权范围快照 → 文档/视频文字检索 → 原文摘录与完整问题证明 → 大模型综合表达 → 实际引用逐段核验 → 最终授权/版本复验与持久trace → 答案与引用。

没有证据或生成内容无法核验时拒答。模型只选择服务器提供的证据ID，不生成页码、时间或来源URL。ASR是服务器音频分段时间，字幕是cue时间，帧OCR是原帧显示时间，不冒充词级或操作动作对齐。纯视觉caption、文件摘要不是本路径事实来源；原有视觉/音画联合能力保持单独明确合同。

## HTTP

`POST /v1/knowledge-answers`接受`question`与可选`document_ids`。省略范围为完整授权全库，显式`[]`仍为空，不回退全库。前端默认综合问答使用此技术端点，旧单类型问答端点保持兼容。

响应为`answer_id/status/answer/reason/citations`。每条引用包含`citation_id/evidence_kind`、原document/revision/filename/SHA与quote，文档给页码/码点，视频给start_ms/end_ms/time_precision。source_url和content_url由服务器派生。

`GET /v1/knowledge-sources/{answerId}/{ordinal}`按当前身份和原完整范围重新授权，返回`{answer_id,citation}`。来源读回不调用模型；前端再通过同版本原件元数据和完整字节SHA核对后阅读/播放。不能用客户端缓存替代服务端授权。

## 模型与运行

复用当前已应用的文字嵌入、重排、生成配置与Milvus索引。生成和核验沿同一OpenAI兼容`chat/completions`协议实现，不为DeepSeek、Qwen或星辰增加业务分支。ASR仍用`audio/transcriptions`；重排是明确扩展协议，不标为OpenAI标准。

综合回复增加生成与核验请求，不等同于原来的纯检索请求；默认无自动重试。密钥仅存在后端私密配置，不进网页/源码/日志。

当前验收范围以[0049 REVIEW](changes/0049-unified-knowledge-answers/REVIEW.md)为准。本轮累计8/20调用后已停止，新的综合生成真实联调需具名授权；文档中的流程合同不代表实际质量已经通过。
